package com.wireser.minecraft.lib;

import java.awt.Color;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.bukkit.configuration.file.FileConfiguration;
import org.json.JSONArray;
import org.json.JSONObject;

import com.wireser.minecraft.TheMinecraftPlugin;
import com.wireser.minecraft.utils.PluginLogger;

/**
 * Sends messages from the Minecraft server to configured Discord webhooks.
 *
 * <p>Webhook requests are sent asynchronously so Discord or network latency never
 * blocks the Paper server thread. This class handles outbound webhook messages only;
 * Discord-to-server communication requires a Discord bot/gateway connection.</p>
 */
public final class Discord {

    private static final String LOG_PREFIX = "Discord";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    private final String url;
    private String content;
    private String username;
    private String avatarUrl;
    private boolean tts;
    private final List<EmbedObject> embeds = new ArrayList<>();

    /**
     * Configured outbound Discord webhook destinations.
     *
     * <p>The enum stores configuration paths rather than webhook credentials.
     * The actual URLs belong in {@code config.yml} and are resolved when a
     * message is sent.</p>
     */
    public enum WebhookTarget {
        ANNO("Discord.Webhooks.Anno"),
        GENERAL("Discord.Webhooks.General"),
        PRIUS("Discord.Webhooks.Prius"),
        SUPPORT("Discord.Webhooks.Support"),
        TICKET("Discord.Webhooks.Ticket"),
        UPDATE("Discord.Webhooks.Update"),
        IG_CHAT_MESSAGE("Discord.Webhooks.InGameChat");

        private final String configPath;

        WebhookTarget(String configPath) {
            this.configPath = configPath;
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
     * Sends a fully formatted player chat line to Discord as normal webhook content.
     *
     * <p>The method does not add a player name, prefix, color, or embed formatting.
     * The caller supplies the complete Discord message, including any Discord
     * Markdown used for rank or player-name styling.</p>
     *
     * @param target configured webhook destination
     * @param message complete chat line to send
     * @return future completed when Discord accepts or rejects the request
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

        return webhook.executeAsync();
    }

    /**
     * Sends an embedded server notification to Discord.
     *
     * @param target configured webhook destination
     * @param color embed accent color
     * @param title embed title
     * @param message embed body
     * @return future completed when Discord accepts or rejects the request
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

        return webhook.executeAsync();
    }

    /**
     * Creates a webhook message for a Discord webhook URL.
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
     * Sends this webhook message asynchronously.
     *
     * <p>The returned future completes normally only for HTTP 2xx responses.
     * Configuration, serialization, network, and non-success HTTP failures are
     * logged through {@link PluginLogger} and complete the future exceptionally.</p>
     *
     * @return future representing the webhook request
     * @throws IllegalStateException if neither content nor an embed has been supplied
     */
    public CompletableFuture<Void> executeAsync() {
        if (content == null && embeds.isEmpty()) {
            throw new IllegalStateException("Set content or add at least one EmbedObject.");
        }

        HttpRequest request;

        try {
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

            request = requestBuilder
                    .POST(HttpRequest.BodyPublishers.ofString(createPayload().toString()))
                    .build();
        } catch (RuntimeException exception) {
            PluginLogger.errorReduced(LOG_PREFIX, exception, "Could not build Discord webhook request.");
            return CompletableFuture.failedFuture(exception);
        }

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new CompletionException(new IOException(
                                "Discord returned HTTP " + response.statusCode()
                                        + formatResponseBody(response.body())
                        ));
                    }

                    PluginLogger.debug(
                            LOG_PREFIX,
                            "Webhook delivered with HTTP {}.",
                            response.statusCode()
                    );
                })
                .whenComplete((ignored, exception) -> {
                    if (exception != null) {
                        PluginLogger.errorReduced(
                                LOG_PREFIX,
                                unwrapCompletionException(exception),
                                "Discord webhook request failed."
                        );
                    }
                });
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

    private static Discord fromConfig(WebhookTarget target) {
        Objects.requireNonNull(target, "target");

        TheMinecraftPlugin plugin = TheMinecraftPlugin.getInstance();
        if (plugin == null || plugin.getMainConfig() == null) {
            PluginLogger.error(LOG_PREFIX, "Cannot send webhook before the main configuration is available.");
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

    private static Throwable unwrapCompletionException(Throwable throwable) {
        Throwable current = throwable;

        while (current instanceof CompletionException && current.getCause() != null) {
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
}
