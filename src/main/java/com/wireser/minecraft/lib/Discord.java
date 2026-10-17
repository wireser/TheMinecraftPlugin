package com.wireser.minecraft.lib;

import java.awt.Color;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.bukkit.configuration.file.FileConfiguration;
import org.json.JSONArray;
import org.json.JSONObject;

import com.wireser.minecraft.TheMinecraftPlugin;
import com.wireser.minecraft.utils.PluginLogger;

/**
 * Sends messages from the Minecraft server to configured Discord webhooks.
 *
 * <p>Configuration is loaded into an immutable in-memory snapshot. Reloading
 * replaces that snapshot for newly queued messages without changing messages
 * that are already waiting for delivery.</p>
 *
 * <p>Each webhook target has an ordered asynchronous queue. Only one request per
 * target is in flight at a time, preserving message order without blocking the
 * Paper server thread.</p>
 */
public final class Discord {

    private static final String LOG_PREFIX = "Discord";
    private static final long RATE_LIMIT_BUFFER_MS = 50L;

    private static final Map<WebhookTarget, OutboundQueue> OUTBOUND_QUEUES =
            createOutboundQueues();

    private static volatile DiscordSettings settings;

    private final String url;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final List<Long> retryDelaysMillis;

    private String content;
    private String username;
    private String avatarUrl;
    private boolean tts;
    private final List<EmbedObject> embeds = new ArrayList<>();

    private enum QueuePolicy {
        STANDARD,
        IMPORTANT,
        CHAT
    }

    /**
     * Configured outbound Discord webhook destinations.
     */
    public enum WebhookTarget {
        ANNO("Discord.Webhooks.Anno", QueuePolicy.STANDARD),
        GENERAL("Discord.Webhooks.General", QueuePolicy.STANDARD),
        PRIUS("Discord.Webhooks.Prius", QueuePolicy.STANDARD),
        SUPPORT("Discord.Webhooks.Support", QueuePolicy.IMPORTANT),
        TICKET("Discord.Webhooks.Ticket", QueuePolicy.IMPORTANT),
        UPDATE("Discord.Webhooks.Update", QueuePolicy.STANDARD),
        IG_CHAT_MESSAGE("Discord.Webhooks.InGameChat", QueuePolicy.CHAT);

        private final String configPath;
        private final QueuePolicy queuePolicy;

        WebhookTarget(String configPath, QueuePolicy queuePolicy) {
            this.configPath = configPath;
            this.queuePolicy = queuePolicy;
        }

        public String getConfigPath() {
            return configPath;
        }
    }

    /**
     * Common Discord embed colors.
     */
    public enum Colors {
        MAROON(128, 0, 0),
        CRIMSON(220, 20, 60),
        TOMATO(255, 99, 71),
        CORAL(255, 127, 80),
        INDIAN_RED(205, 92, 92),
        SALMON(255, 128, 114),

        ORANGE(255, 165, 0),
        GOLD(255, 215, 0),

        OLIVE(107, 142, 35),
        FOREST_GREEN(34, 139, 34),

        TEAL(0, 128, 128),
        DARK_TURQUOISE(0, 206, 209),
        TURQUOISE(64, 224, 208),
        STEEL_BLUE(70, 130, 180),
        CORN_FLOWER_BLUE(100, 149, 237),
        SKY_BLUE(135, 206, 235),
        DODGER_BLUE(30, 144, 255),
        ROYAL_BLUE(65, 105, 225),

        INDIGO(75, 0, 130),
        HOT_PINK(255, 105, 180),
        PLUM(221, 160, 221),

        SILVER(192, 192, 192);

        private final Color color;

        Colors(int red, int green, int blue) {
            this.color = new Color(red, green, blue);
        }

        public Color getColor() {
            return color;
        }
    }

    /**
     * Loads and validates Discord settings from the main plugin configuration.
     *
     * <p>A successful reload atomically replaces the in-memory settings used for
     * future messages. Existing queued messages retain the URL and delivery policy
     * captured when they were enqueued.</p>
     *
     * @return {@code true} when the new configuration was loaded successfully
     */
    public static synchronized boolean reloadConfiguration() {
        TheMinecraftPlugin plugin = TheMinecraftPlugin.getInstance();

        if (plugin == null || plugin.getMainConfig() == null
                || plugin.getMainConfig().getConfig() == null) {
            PluginLogger.error(
                    LOG_PREFIX,
                    "Cannot load Discord configuration before the main configuration is available."
            );
            return false;
        }

        try {
            DiscordSettings loaded = loadSettings(plugin.getMainConfig().getConfig());
            settings = loaded;

            PluginLogger.info(
                    LOG_PREFIX,
                    "Configuration loaded: {} webhook target(s) enabled.",
                    loaded.webhookUrls().size()
            );
            return true;
        } catch (IllegalStateException exception) {
            PluginLogger.errorReduced(
                    LOG_PREFIX,
                    exception,
                    "Discord configuration is invalid; keeping the previous settings."
            );
            return false;
        }
    }

    /**
     * Sends a fully formatted player chat line to Discord as normal webhook content.
     *
     * @param target configured webhook destination
     * @param message complete chat line to send
     * @return future completed when the message is delivered or permanently fails
     */
    public static CompletableFuture<Void> sendAsPlayer(
            WebhookTarget target,
            String message
    ) {
        Objects.requireNonNull(message, "message");

        PreparedWebhook prepared = fromSettings(target);
        if (prepared == null) {
            return CompletableFuture.completedFuture(null);
        }

        prepared.webhook().setTts(false);
        prepared.webhook().setContent(message);

        return enqueue(prepared);
    }

    /**
     * Sends an embedded server notification to Discord.
     *
     * @param target configured webhook destination
     * @param color embed accent color
     * @param title embed title
     * @param message embed body
     * @return future completed when the message is delivered or permanently fails
     */
    public static CompletableFuture<Void> send(
            WebhookTarget target,
            Colors color,
            String title,
            String message
    ) {
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(message, "message");

        PreparedWebhook prepared = fromSettings(target);
        if (prepared == null) {
            return CompletableFuture.completedFuture(null);
        }

        prepared.webhook().setTts(false);
        prepared.webhook().addEmbed(new EmbedObject()
                .setDescription(message)
                .setTitle(title)
                .setColor(color.getColor()));

        return enqueue(prepared);
    }

    /**
     * Creates a webhook message for a Discord webhook URL using the currently
     * loaded transport and retry settings.
     *
     * @param url Discord webhook URL
     * @throws IllegalStateException if Discord configuration has not been loaded
     */
    public Discord(String url) {
        this(url, requireSettings());
    }

    private Discord(String url, DiscordSettings settingsSnapshot) {
        Objects.requireNonNull(url, "url");

        if (url.isBlank()) {
            throw new IllegalArgumentException("Discord webhook URL cannot be blank.");
        }

        this.url = url;
        this.httpClient = settingsSnapshot.httpClient();
        this.requestTimeout = settingsSnapshot.requestTimeout();
        this.retryDelaysMillis = settingsSnapshot.retryDelaysMillis();
    }

    public void setContent(String content) {
        this.content = content;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public void setTts(boolean tts) {
        this.tts = tts;
    }

    public void addEmbed(EmbedObject embed) {
        embeds.add(Objects.requireNonNull(embed, "embed"));
    }

    /**
     * Sends this webhook asynchronously using the configured standard retry policy.
     *
     * @return future completed when the webhook is delivered or permanently fails
     */
    public CompletableFuture<Void> executeAsync() {
        DiscordSettings current = requireSettings();
        return executeAsync(current.defaultMaxAttempts());
    }

    private CompletableFuture<Void> executeAsync(int maxAttempts) {
        if (content == null && embeds.isEmpty()) {
            throw new IllegalStateException("Set content or add at least one EmbedObject.");
        }

        String payload;

        try {
            payload = createPayload().toString();
        } catch (RuntimeException exception) {
            PluginLogger.errorReduced(
                    LOG_PREFIX,
                    exception,
                    "Could not serialize Discord webhook payload."
            );
            return CompletableFuture.failedFuture(exception);
        }

        CompletableFuture<Void> result = new CompletableFuture<>();
        sendAttempt(payload, 1, maxAttempts, result);
        return result;
    }

    private void sendAttempt(
            String payload,
            int attempt,
            int maxAttempts,
            CompletableFuture<Void> result
    ) {
        HttpRequest request;

        try {
            request = createRequest(payload);
        } catch (RuntimeException exception) {
            PluginLogger.errorReduced(
                    LOG_PREFIX,
                    exception,
                    "Could not build Discord webhook request."
            );
            result.completeExceptionally(exception);
            return;
        }

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        Throwable cause = unwrapCompletionException(throwable);

                        if (attempt < maxAttempts) {
                            long delay = backoffDelayMillis(attempt);

                            PluginLogger.warn(
                                    LOG_PREFIX,
                                    "Webhook request failed; retrying attempt {}/{} in {} ms. {}",
                                    attempt + 1,
                                    maxAttempts,
                                    delay,
                                    PluginLogger.summarize(cause)
                            );

                            schedule(
                                    delay,
                                    () -> sendAttempt(
                                            payload,
                                            attempt + 1,
                                            maxAttempts,
                                            result
                                    )
                            );
                            return;
                        }

                        PluginLogger.errorReduced(
                                LOG_PREFIX,
                                cause,
                                "Discord webhook request failed after {} attempts.",
                                maxAttempts
                        );
                        result.completeExceptionally(cause);
                        return;
                    }

                    handleResponse(payload, response, attempt, maxAttempts, result);
                });
    }

    private void handleResponse(
            String payload,
            HttpResponse<String> response,
            int attempt,
            int maxAttempts,
            CompletableFuture<Void> result
    ) {
        int status = response.statusCode();

        if (status >= 200 && status < 300) {
            long delay = rateLimitResetDelayMillis(response);

            if (delay > 0) {
                PluginLogger.debug(
                        LOG_PREFIX,
                        "Webhook rate-limit bucket exhausted; next queued message waits {} ms.",
                        delay
                );

                schedule(delay, () -> result.complete(null));
            } else {
                result.complete(null);
            }

            return;
        }

        if (status == 429 && attempt < maxAttempts) {
            long delay = retryAfterMillis(response);

            PluginLogger.warn(
                    LOG_PREFIX,
                    "Discord rate limited a webhook; retrying attempt {}/{} in {} ms.",
                    attempt + 1,
                    maxAttempts,
                    delay
            );

            schedule(
                    delay,
                    () -> sendAttempt(payload, attempt + 1, maxAttempts, result)
            );
            return;
        }

        if (status >= 500 && status <= 599 && attempt < maxAttempts) {
            long delay = backoffDelayMillis(attempt);

            PluginLogger.warn(
                    LOG_PREFIX,
                    "Discord returned HTTP {}; retrying attempt {}/{} in {} ms.",
                    status,
                    attempt + 1,
                    maxAttempts,
                    delay
            );

            schedule(
                    delay,
                    () -> sendAttempt(payload, attempt + 1, maxAttempts, result)
            );
            return;
        }

        IOException exception = new IOException(
                "Discord returned HTTP " + status
                        + formatResponseBody(response.body())
        );

        PluginLogger.errorReduced(
                LOG_PREFIX,
                exception,
                "Discord rejected webhook request after {} attempt(s).",
                attempt
        );
        result.completeExceptionally(exception);
    }

    private HttpRequest createRequest(String payload) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json");

        TheMinecraftPlugin plugin = TheMinecraftPlugin.getInstance();
        if (plugin != null) {
            requestBuilder.header(
                    "User-Agent",
                    plugin.getPluginMeta().getName()
                            + "/"
                            + plugin.getPluginMeta().getVersion()
            );
        }

        return requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
    }

    private JSONObject createPayload() {
        JSONObject json = new JSONObject();

        if (content != null) {
            json.put("content", content);
        }
        if (username != null) {
            json.put("username", username);
        }
        if (avatarUrl != null) {
            json.put("avatar_url", avatarUrl);
        }

        json.put("tts", tts);

        if (!embeds.isEmpty()) {
            JSONArray jsonEmbeds = new JSONArray();

            for (EmbedObject embed : embeds) {
                jsonEmbeds.put(embed.toJson());
            }

            json.put("embeds", jsonEmbeds);
        }

        return json;
    }

    private static CompletableFuture<Void> enqueue(PreparedWebhook prepared) {
        return OUTBOUND_QUEUES
                .get(prepared.target())
                .enqueue(
                        prepared.webhook(),
                        prepared.queueLimit(),
                        prepared.maxAttempts()
                );
    }

    private static Map<WebhookTarget, OutboundQueue> createOutboundQueues() {
        Map<WebhookTarget, OutboundQueue> queues =
                new EnumMap<>(WebhookTarget.class);

        for (WebhookTarget target : WebhookTarget.values()) {
            queues.put(target, new OutboundQueue(target));
        }

        return Map.copyOf(queues);
    }

    private static PreparedWebhook fromSettings(WebhookTarget target) {
        Objects.requireNonNull(target, "target");

        DiscordSettings current = settings;
        if (current == null) {
            PluginLogger.error(
                    LOG_PREFIX,
                    "Cannot send webhook before Discord configuration is loaded."
            );
            return null;
        }

        String url = current.webhookUrls().get(target);

        if (url == null) {
            PluginLogger.warn(
                    LOG_PREFIX,
                    "Webhook target {} is not configured.",
                    target
            );
            return null;
        }

        return new PreparedWebhook(
                target,
                new Discord(url, current),
                current.queueLimit(target.queuePolicy),
                current.maxAttempts(target.queuePolicy)
        );
    }

    private static DiscordSettings loadSettings(FileConfiguration config) {
        long requestTimeoutMs = requiredLong(
                config,
                "Discord.RequestTimeoutMs",
                1_000L,
                60_000L
        );

        int defaultMaxAttempts = requiredInt(
                config,
                "Discord.Retry.DefaultMaxAttempts",
                1,
                10
        );

        int importantMaxAttempts = requiredInt(
                config,
                "Discord.Retry.ImportantMaxAttempts",
                1,
                10
        );

        List<Long> retryDelays = requiredLongList(
                config,
                "Discord.Retry.DelaysMs",
                0L,
                60_000L
        );

        int defaultQueueLimit = requiredInt(
                config,
                "Discord.Queues.DefaultLimit",
                1,
                100_000
        );

        int chatQueueLimit = requiredInt(
                config,
                "Discord.Queues.ChatLimit",
                1,
                100_000
        );

        int importantQueueLimit = requiredInt(
                config,
                "Discord.Queues.ImportantLimit",
                1,
                100_000
        );

        Map<WebhookTarget, String> webhookUrls =
                new EnumMap<>(WebhookTarget.class);

        for (WebhookTarget target : WebhookTarget.values()) {
            String url = optionalString(config, target.configPath);

            if (url != null) {
                validateWebhookUrl(url, target.configPath);
                webhookUrls.put(target, url);
            }
        }

        Duration requestTimeout = Duration.ofMillis(requestTimeoutMs);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .build();

        return new DiscordSettings(
                requestTimeout,
                List.copyOf(retryDelays),
                defaultMaxAttempts,
                importantMaxAttempts,
                defaultQueueLimit,
                chatQueueLimit,
                importantQueueLimit,
                Map.copyOf(webhookUrls),
                client
        );
    }

    private static int requiredInt(
            FileConfiguration config,
            String path,
            int min,
            int max
    ) {
        if (!config.isInt(path)) {
            throw new IllegalStateException(path + " must be an integer.");
        }

        int value = config.getInt(path);

        if (value < min || value > max) {
            throw new IllegalStateException(
                    path + " must be between " + min + " and " + max + "."
            );
        }

        return value;
    }

    private static long requiredLong(
            FileConfiguration config,
            String path,
            long min,
            long max
    ) {
        if (!(config.isInt(path) || config.isLong(path))) {
            throw new IllegalStateException(path + " must be an integer.");
        }

        long value = config.getLong(path);

        if (value < min || value > max) {
            throw new IllegalStateException(
                    path + " must be between " + min + " and " + max + "."
            );
        }

        return value;
    }

    private static List<Long> requiredLongList(
            FileConfiguration config,
            String path,
            long min,
            long max
    ) {
        List<?> raw = config.getList(path);

        if (raw == null || raw.isEmpty()) {
            throw new IllegalStateException(path + " must contain at least one value.");
        }

        List<Long> values = new ArrayList<>(raw.size());

        for (Object item : raw) {
            if (!(item instanceof Number number)) {
                throw new IllegalStateException(path + " must contain only numbers.");
            }

            long value = number.longValue();

            if (value < min || value > max) {
                throw new IllegalStateException(
                        path + " values must be between " + min + " and " + max + "."
                );
            }

            values.add(value);
        }

        return values;
    }

    private static String optionalString(
            FileConfiguration config,
            String path
    ) {
        if (!config.contains(path)) {
            return null;
        }

        if (!config.isString(path)) {
            throw new IllegalStateException(path + " must be a string.");
        }

        String value = config.getString(path);

        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    private static void validateWebhookUrl(String value, String path) {
        URI uri;

        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(path + " is not a valid URI.", exception);
        }

        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalStateException(path + " must be an HTTPS URL.");
        }
    }

    private static DiscordSettings requireSettings() {
        DiscordSettings current = settings;

        if (current == null) {
            throw new IllegalStateException(
                    "Discord configuration has not been loaded."
            );
        }

        return current;
    }

    private long backoffDelayMillis(int failedAttempt) {
        int index = Math.min(
                Math.max(0, failedAttempt - 1),
                retryDelaysMillis.size() - 1
        );

        return retryDelaysMillis.get(index);
    }

    private static long retryAfterMillis(HttpResponse<String> response) {
        String body = response.body();

        if (body != null && !body.isBlank()) {
            try {
                JSONObject json = new JSONObject(body);
                Number retryAfter = json.optNumber("retry_after");

                if (retryAfter != null) {
                    return Math.max(
                            1L,
                            (long) Math.ceil(retryAfter.doubleValue())
                    ) + RATE_LIMIT_BUFFER_MS;
                }
            } catch (RuntimeException ignored) {
                // Use the rate-limit header when the response body is unavailable.
            }
        }

        return parseSecondsHeaderMillis(
                response,
                "X-RateLimit-Reset-After",
                1_000L
        );
    }

    private static long rateLimitResetDelayMillis(
            HttpResponse<String> response
    ) {
        String remaining = response.headers()
                .firstValue("X-RateLimit-Remaining")
                .orElse(null);

        if (!"0".equals(remaining)) {
            return 0L;
        }

        return parseSecondsHeaderMillis(
                response,
                "X-RateLimit-Reset-After",
                0L
        );
    }

    private static long parseSecondsHeaderMillis(
            HttpResponse<String> response,
            String header,
            long fallback
    ) {
        String value = response.headers().firstValue(header).orElse(null);

        if (value == null) {
            return fallback;
        }

        try {
            double seconds = Double.parseDouble(value);
            return Math.max(
                    0L,
                    (long) Math.ceil(seconds * 1_000.0)
            ) + RATE_LIMIT_BUFFER_MS;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static void schedule(long delayMillis, Runnable task) {
        CompletableFuture.delayedExecutor(
                Math.max(0L, delayMillis),
                TimeUnit.MILLISECONDS
        ).execute(task);
    }

    private static Throwable unwrapCompletionException(Throwable throwable) {
        Throwable current = throwable;

        while (current instanceof CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }

        return current;
    }

    private static String formatResponseBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }

        String normalized = body.replaceAll("\\s+", " ").trim();

        if (normalized.length() > 300) {
            normalized = normalized.substring(0, 300) + "...";
        }

        return ": " + normalized;
    }

    /**
     * Mutable builder for a Discord embed object.
     */
    public static final class EmbedObject {

        private String title;
        private String description;
        private String url;
        private Color color;

        private Footer footer;
        private Thumbnail thumbnail;
        private Image image;
        private Author author;
        private final List<Field> fields = new ArrayList<>();

        public String getTitle() {
            return title;
        }

        public String getDescription() {
            return description;
        }

        public String getUrl() {
            return url;
        }

        public Color getColor() {
            return color;
        }

        public Footer getFooter() {
            return footer;
        }

        public Thumbnail getThumbnail() {
            return thumbnail;
        }

        public Image getImage() {
            return image;
        }

        public Author getAuthor() {
            return author;
        }

        public List<Field> getFields() {
            return List.copyOf(fields);
        }

        public EmbedObject setTitle(String title) {
            this.title = title;
            return this;
        }

        public EmbedObject setDescription(String description) {
            this.description = description;
            return this;
        }

        public EmbedObject setUrl(String url) {
            this.url = url;
            return this;
        }

        public EmbedObject setColor(Color color) {
            this.color = color;
            return this;
        }

        public EmbedObject setFooter(String text, String icon) {
            this.footer = new Footer(text, icon);
            return this;
        }

        public EmbedObject setThumbnail(String url) {
            this.thumbnail = new Thumbnail(url);
            return this;
        }

        public EmbedObject setImage(String url) {
            this.image = new Image(url);
            return this;
        }

        public EmbedObject setAuthor(String name, String url, String icon) {
            this.author = new Author(name, url, icon);
            return this;
        }

        public EmbedObject addField(String name, String value, boolean inline) {
            this.fields.add(new Field(name, value, inline));
            return this;
        }

        private JSONObject toJson() {
            JSONObject json = new JSONObject();

            if (title != null) {
                json.put("title", title);
            }
            if (description != null) {
                json.put("description", description);
            }
            if (url != null) {
                json.put("url", url);
            }
            if (color != null) {
                json.put("color", color.getRGB() & 0x00FFFFFF);
            }
            if (footer != null) {
                json.put("footer", footer.toJson());
            }
            if (image != null) {
                json.put("image", image.toJson());
            }
            if (thumbnail != null) {
                json.put("thumbnail", thumbnail.toJson());
            }
            if (author != null) {
                json.put("author", author.toJson());
            }
            if (!fields.isEmpty()) {
                JSONArray jsonFields = new JSONArray();

                for (Field field : fields) {
                    jsonFields.put(field.toJson());
                }

                json.put("fields", jsonFields);
            }

            return json;
        }

        public static final class Footer {
            private final String text;
            private final String iconUrl;

            private Footer(String text, String iconUrl) {
                this.text = text;
                this.iconUrl = iconUrl;
            }

            public String getText() {
                return text;
            }

            public String getIconUrl() {
                return iconUrl;
            }

            private JSONObject toJson() {
                JSONObject json = new JSONObject();
                json.put("text", text);

                if (iconUrl != null) {
                    json.put("icon_url", iconUrl);
                }

                return json;
            }
        }

        public static final class Thumbnail {
            private final String url;

            private Thumbnail(String url) {
                this.url = url;
            }

            public String getUrl() {
                return url;
            }

            private JSONObject toJson() {
                return new JSONObject().put("url", url);
            }
        }

        public static final class Image {
            private final String url;

            private Image(String url) {
                this.url = url;
            }

            public String getUrl() {
                return url;
            }

            private JSONObject toJson() {
                return new JSONObject().put("url", url);
            }
        }

        public static final class Author {
            private final String name;
            private final String url;
            private final String iconUrl;

            private Author(String name, String url, String iconUrl) {
                this.name = name;
                this.url = url;
                this.iconUrl = iconUrl;
            }

            public String getName() {
                return name;
            }

            public String getUrl() {
                return url;
            }

            public String getIconUrl() {
                return iconUrl;
            }

            private JSONObject toJson() {
                JSONObject json = new JSONObject();
                json.put("name", name);

                if (url != null) {
                    json.put("url", url);
                }
                if (iconUrl != null) {
                    json.put("icon_url", iconUrl);
                }

                return json;
            }
        }

        public static final class Field {
            private final String name;
            private final String value;
            private final boolean inline;

            private Field(String name, String value, boolean inline) {
                this.name = name;
                this.value = value;
                this.inline = inline;
            }

            public String getName() {
                return name;
            }

            public String getValue() {
                return value;
            }

            public boolean isInline() {
                return inline;
            }

            private JSONObject toJson() {
                return new JSONObject()
                        .put("name", name)
                        .put("value", value)
                        .put("inline", inline);
            }
        }
    }

    private static final class OutboundQueue {

        private final WebhookTarget target;
        private final Deque<QueuedWebhook> pending = new ArrayDeque<>();
        private boolean processing;

        private OutboundQueue(WebhookTarget target) {
            this.target = target;
        }

        private CompletableFuture<Void> enqueue(
                Discord webhook,
                int queueLimit,
                int maxAttempts
        ) {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            QueuedWebhook dropped = null;
            boolean startProcessing = false;

            synchronized (this) {
                if (pending.size() >= queueLimit) {
                    dropped = pending.pollFirst();
                }

                pending.addLast(
                        new QueuedWebhook(webhook, maxAttempts, completion)
                );

                if (!processing) {
                    processing = true;
                    startProcessing = true;
                }
            }

            if (dropped != null) {
                IOException exception = new IOException(
                        "Discord queue limit reached for " + target
                );

                dropped.completion().completeExceptionally(exception);

                PluginLogger.warn(
                        LOG_PREFIX,
                        "Webhook queue {} reached its {} message limit; dropped the oldest pending message.",
                        target,
                        queueLimit
                );
            }

            if (startProcessing) {
                processNext();
            }

            return completion;
        }

        private void processNext() {
            QueuedWebhook queued;

            synchronized (this) {
                queued = pending.pollFirst();

                if (queued == null) {
                    processing = false;
                    return;
                }
            }

            queued.webhook()
                    .executeAsync(queued.maxAttempts())
                    .whenComplete((ignored, exception) -> {
                        if (exception == null) {
                            queued.completion().complete(null);
                        } else {
                            queued.completion().completeExceptionally(
                                    unwrapCompletionException(exception)
                            );
                        }

                        CompletableFuture.runAsync(this::processNext);
                    });
        }
    }

    private record DiscordSettings(
            Duration requestTimeout,
            List<Long> retryDelaysMillis,
            int defaultMaxAttempts,
            int importantMaxAttempts,
            int defaultQueueLimit,
            int chatQueueLimit,
            int importantQueueLimit,
            Map<WebhookTarget, String> webhookUrls,
            HttpClient httpClient
    ) {
        private int maxAttempts(QueuePolicy policy) {
            return policy == QueuePolicy.IMPORTANT
                    ? importantMaxAttempts
                    : defaultMaxAttempts;
        }

        private int queueLimit(QueuePolicy policy) {
            return switch (policy) {
                case STANDARD -> defaultQueueLimit;
                case IMPORTANT -> importantQueueLimit;
                case CHAT -> chatQueueLimit;
            };
        }
    }

    private record PreparedWebhook(
            WebhookTarget target,
            Discord webhook,
            int queueLimit,
            int maxAttempts
    ) {
    }

    private record QueuedWebhook(
            Discord webhook,
            int maxAttempts,
            CompletableFuture<Void> completion
    ) {
    }
}
