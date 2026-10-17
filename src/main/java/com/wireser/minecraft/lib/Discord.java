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
 * <p>Configured webhook targets use ordered outbound queues. Only one request per
 * target is in flight at a time, preserving message order and preventing a burst
 * of queued messages from being released simultaneously after a network delay.</p>
 *
 * <p>Requests are asynchronous and never wait on the Paper server thread.
 * Discord-to-server communication requires a separate Discord bot/gateway
 * connection.</p>
 */
public final class Discord {

    private static final String LOG_PREFIX = "Discord";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final long RATE_LIMIT_BUFFER_MS = 50L;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    private static final Map<WebhookTarget, OutboundQueue> OUTBOUND_QUEUES =
            createOutboundQueues();

    private final String url;
    private String content;
    private String username;
    private String avatarUrl;
    private boolean tts;
    private final List<EmbedObject> embeds = new ArrayList<>();

    /**
     * Configured outbound Discord webhook destinations.
     *
     * <p>Each target defines its configuration path, maximum pending queue size,
     * and maximum number of delivery attempts per message.</p>
     */
    public enum WebhookTarget {
        ANNO("Discord.Webhooks.Anno", 250, 4),
        GENERAL("Discord.Webhooks.General", 250, 4),
        PRIUS("Discord.Webhooks.Prius", 250, 4),
        SUPPORT("Discord.Webhooks.Support", 1_000, 6),
        TICKET("Discord.Webhooks.Ticket", 1_000, 6),
        UPDATE("Discord.Webhooks.Update", 250, 4),
        IG_CHAT_MESSAGE("Discord.Webhooks.InGameChat", 500, 4);

        private final String configPath;
        private final int queueLimit;
        private final int maxAttempts;

        WebhookTarget(String configPath, int queueLimit, int maxAttempts) {
            this.configPath = configPath;
            this.queueLimit = queueLimit;
            this.maxAttempts = maxAttempts;
        }

        public String getConfigPath() {
            return configPath;
        }

        public int getQueueLimit() {
            return queueLimit;
        }

        public int getMaxAttempts() {
            return maxAttempts;
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
     * Sends a fully formatted player chat line to Discord as normal webhook content.
     *
     * <p>The message enters the target's ordered outbound queue. The method does
     * not add a player name, prefix, color, or embed formatting.</p>
     *
     * @param target configured webhook destination
     * @param message complete chat line to send
     * @return future completed when the message is delivered or permanently fails
     * @throws NullPointerException if {@code target} or {@code message} is {@code null}
     */
    public static CompletableFuture<Void> sendAsPlayer(
            WebhookTarget target,
            String message
    ) {
        Objects.requireNonNull(message, "message");

        Discord webhook = fromConfig(target);
        if (webhook == null) {
            return CompletableFuture.completedFuture(null);
        }

        webhook.setTts(false);
        webhook.setContent(message);

        return enqueue(target, webhook);
    }

    /**
     * Sends an embedded server notification to Discord.
     *
     * <p>The message enters the target's ordered outbound queue.</p>
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

        Discord webhook = fromConfig(target);
        if (webhook == null) {
            return CompletableFuture.completedFuture(null);
        }

        webhook.setTts(false);
        webhook.addEmbed(new EmbedObject()
                .setDescription(message)
                .setTitle(title)
                .setColor(color.getColor()));

        return enqueue(target, webhook);
    }

    /**
     * Creates a webhook message for a Discord webhook URL.
     *
     * <p>Messages created directly with this constructor are not associated with
     * a configured target queue. Call {@link #executeAsync()} to send them with
     * the default retry policy.</p>
     *
     * @param url Discord webhook URL
     * @throws NullPointerException if {@code url} is {@code null}
     * @throws IllegalArgumentException if {@code url} is blank
     */
    public Discord(String url) {
        Objects.requireNonNull(url, "url");

        if (url.isBlank()) {
            throw new IllegalArgumentException("Discord webhook URL cannot be blank.");
        }

        this.url = url;
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
     * Sends this webhook asynchronously using four total delivery attempts.
     *
     * <p>Network failures and HTTP 5xx responses use bounded backoff retries.
     * HTTP 429 responses use Discord's supplied retry delay. Other non-success
     * responses fail immediately.</p>
     *
     * @return future completed when the webhook is delivered or permanently fails
     * @throws IllegalStateException if neither content nor an embed has been supplied
     */
    public CompletableFuture<Void> executeAsync() {
        return executeAsync(4);
    }

    private CompletableFuture<Void> executeAsync(int maxAttempts) {
        if (content == null && embeds.isEmpty()) {
            throw new IllegalStateException("Set content or add at least one EmbedObject.");
        }

        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1.");
        }

        String payload;

        try {
            payload = createPayload().toString();
        } catch (RuntimeException exception) {
            PluginLogger.errorReduced(LOG_PREFIX, exception, "Could not serialize Discord webhook payload.");
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
            PluginLogger.errorReduced(LOG_PREFIX, exception, "Could not build Discord webhook request.");
            result.completeExceptionally(exception);
            return;
        }

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
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
                                    () -> sendAttempt(payload, attempt + 1, maxAttempts, result)
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
                "Discord returned HTTP " + status + formatResponseBody(response.body())
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
                .timeout(REQUEST_TIMEOUT)
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

    private static CompletableFuture<Void> enqueue(
            WebhookTarget target,
            Discord webhook
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(webhook, "webhook");

        return OUTBOUND_QUEUES.get(target).enqueue(webhook);
    }

    private static Map<WebhookTarget, OutboundQueue> createOutboundQueues() {
        Map<WebhookTarget, OutboundQueue> queues =
                new EnumMap<>(WebhookTarget.class);

        for (WebhookTarget target : WebhookTarget.values()) {
            queues.put(target, new OutboundQueue(target));
        }

        return Map.copyOf(queues);
    }

    private static Discord fromConfig(WebhookTarget target) {
        Objects.requireNonNull(target, "target");

        TheMinecraftPlugin plugin = TheMinecraftPlugin.getInstance();
        if (plugin == null || plugin.getMainConfig() == null) {
            PluginLogger.error(
                    LOG_PREFIX,
                    "Cannot send webhook before the main configuration is available."
            );
            return null;
        }

        FileConfiguration config = plugin.getMainConfig().getConfig();
        String url = config.getString(target.getConfigPath());

        if (url == null || url.isBlank()) {
            PluginLogger.warn(
                    LOG_PREFIX,
                    "Webhook target {} is not configured at {}.",
                    target,
                    target.getConfigPath()
            );
            return null;
        }

        return new Discord(url.trim());
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
                // Fall through to the standard rate-limit header.
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

    private static long backoffDelayMillis(int failedAttempt) {
        return switch (failedAttempt) {
            case 1 -> 1_000L;
            case 2 -> 2_000L;
            default -> 5_000L;
        };
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

        private CompletableFuture<Void> enqueue(Discord webhook) {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            QueuedWebhook dropped = null;
            boolean startProcessing = false;

            synchronized (this) {
                if (pending.size() >= target.getQueueLimit()) {
                    dropped = pending.pollFirst();
                }

                pending.addLast(new QueuedWebhook(webhook, completion));

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
                        target.getQueueLimit()
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
                    .executeAsync(target.getMaxAttempts())
                    .whenComplete((ignored, exception) -> {
                        if (exception == null) {
                            queued.completion().complete(null);
                        } else {
                            queued.completion().completeExceptionally(
                                    unwrapCompletionException(exception)
                            );
                        }

                        // Schedule the next drain step to avoid recursive stack growth
                        // if a request fails before asynchronous HTTP dispatch begins.
                        CompletableFuture.runAsync(this::processNext);
                    });
        }
    }

    private record QueuedWebhook(
            Discord webhook,
            CompletableFuture<Void> completion
    ) {
    }
}
