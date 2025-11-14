package utils;

import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.net.URL;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

/**
 * Class used to execute Discord Webhooks with low effort
 */
public class Discord {

    private final String url;
    private String content;
    private String username;
    private String avatarUrl;
    private boolean tts;
    private List<EmbedObject> embeds = new ArrayList<>();

    public enum Bots {
    	ANNO("https://discord.com/api/webhooks/1339530570799120435/K8Q3YTkptN3MmKkFU2gXZQFsAjs7T4duQ2rSXQTzmx02Kf5nqc8PA0k-VRUtPYikkZax"),
    	GENERAL("https://discord.com/api/webhooks/1339530856041025547/jjO2BBVeqSXMuunL2HcaRjDm6qCdX0vlkqUXWbGJV8h_23dnVUvHMBBZwgEF6c_-HVU7"),
    	PRIUS("https://discord.com/api/webhooks/1339529556272480287/hgdOQA0v5lAi4uMI9UBYXDINybwgBPodF0BbVyqweIFliUI3p1iVKFOMxcUIxjYD_027"),
    	SUPPORT("https://discord.com/api/webhooks/1339530965336326195/B_ZzVMS96mPES4jJLxxN0Y2lI7g0gkut4GZJUwOFwzSIWVBJQxSTIyWuaCR1C0yOXEGt"),
    	TICKET("https://discord.com/api/webhooks/1339530671395311641/iOZAOkDI_X8lAFrb66dqrvM9_uxmS41OljRi1tQYM7GEqz_9tqf3bpqepaqayGHYw4Gd"),
    	UPDATE("https://discord.com/api/webhooks/1339530415790227508/84Zymf5mKPgxsQRqT25Mvoyzj6zLME4S7GvZTbCtjwVSjx59-1NmTfPRQj1et1fZ3ZwA"),

    	IG_CHAT_MESSAGE("https://discord.com/api/webhooks/1339530496564138069/_Wk89joZbaIVcidV8dxeF6kmVNYqJKgGfthHJBLrIUcHo2U09Q-3U0K2fmXeSn1VKqyY");

    	public final String value;

    	Bots(String value) {
    		this.value = value;
    	}
    }

    public enum Colors {

    	// RED
    	MAROON(128,0,0),
    	CRIMSON(220,20,60),
    	TOMATO(255,99,71),
    	CORAL(255,127,80),
    	INDIAN_RED(205,92,92),
    	SALMON(255,128,114),

    	// YELLOW
    	ORANGE(255,165,0),
    	GOLD(255,215,0),

    	// GREEN
    	OLIVE(107,142,35),
    	FOREST_GREEN(34,139,34),

    	// BLUE
    	TEAL(0,128,128),
    	DARK_TURQUOISE(0,206,209),
    	TURQUOISE(64,224,208),
    	STEEL_BLUE(70,130,180),
    	CORN_FLOWER_BLUE(100,149,237),
    	SKY_BLUE(135,206,235),
    	DODGER_BLUE(30,144,255),
    	ROYAL_BLUE(65,105,225),

    	// PURPLE
    	INDIGO(75,0,130),
    	HOT_PINK(255,105,180),
    	PLUM(221,160,221),

    	// GRAY
    	SILVER(192,192,192);

    	public final Color color;

    	Colors(int r, int g, int b) {
    		this.color = new Color(r, g, b);
    	}
    }

    public Color rgb = new Color(0, 0, 0);

    
    public static void sendAsPlayer(Discord.Bots hook, Discord.Colors color, String sender, String title, String msg) {
		Discord webhook = new Discord(hook.value);
		
		webhook.setTts(false);
		webhook.setUsername(sender);
		webhook.setContent(msg);

		try {
			webhook.execute();
		}
		
		catch (IOException e) {
			e.printStackTrace();
		}
	}
    
	public static void send(Discord.Bots hook, Discord.Colors color, String title, String msg) {
		Discord webhook = new Discord(hook.value);
		
		webhook.setTts(true);
		webhook.addEmbed(new Discord.EmbedObject()
			.setDescription(msg)
			.setTitle(title)
			.setColor(color.color));
		
		try {
			webhook.execute();
		}
		
		catch (IOException e) {
			e.printStackTrace();
		}
	}
    
    /**
     * Constructs a new DiscordWebhook instance
     *
     * @param url The webhook URL obtained in Discord
     */
    public Discord(String url) {
        this.url = url;
    }

    /**
     *
     * @param content
     */
    public void setContent(String content) {
        this.content = content;
    }

    /**
     *
     * @param username
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     *
     * @param avatarUrl
     */
    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    /**
     *
     * @param tts
     */
    public void setTts(boolean tts) {
        this.tts = tts;
    }

    /**
     *
     * @param embed
     */
    public void addEmbed(EmbedObject embed) {
        this.embeds.add(embed);
    }

    /**
     *
     * @throws IOException
     */
    public void execute() throws IOException {
        if (this.content == null && this.embeds.isEmpty()) {
            throw new IllegalArgumentException("Set content or add at least one EmbedObject");
        }

        JSONObject json = new JSONObject();

        json.put("content", this.content);
        json.put("username", this.username);
        json.put("avatar_url", this.avatarUrl);
        json.put("tts", this.tts);

        if (!this.embeds.isEmpty()) {
            List<JSONObject> embedObjects = new ArrayList<>();

            for (EmbedObject embed : this.embeds) {
                JSONObject jsonEmbed = new JSONObject();

                jsonEmbed.put("title", embed.getTitle());
                jsonEmbed.put("description", embed.getDescription());
                jsonEmbed.put("url", embed.getUrl());

                if (embed.getColor() != null) {
                    Color color = embed.getColor();
                    int rgb = color.getRed();
                    rgb = (rgb << 8) + color.getGreen();
                    rgb = (rgb << 8) + color.getBlue();

                    jsonEmbed.put("color", rgb);
                }

                EmbedObject.Footer footer = embed.getFooter();
                EmbedObject.Image image = embed.getImage();
                EmbedObject.Thumbnail thumbnail = embed.getThumbnail();
                EmbedObject.Author author = embed.getAuthor();
                List<EmbedObject.Field> fields = embed.getFields();

                if (footer != null) {
                    JSONObject jsonFooter = new JSONObject();

                    jsonFooter.put("text", footer.getText());
                    jsonFooter.put("icon_url", footer.getIconUrl());
                    jsonEmbed.put("footer", jsonFooter);
                }

                if (image != null) {
                    JSONObject jsonImage = new JSONObject();

                    jsonImage.put("url", image.getUrl());
                    jsonEmbed.put("image", jsonImage);
                }

                if (thumbnail != null) {
                    JSONObject jsonThumbnail = new JSONObject();

                    jsonThumbnail.put("url", thumbnail.getUrl());
                    jsonEmbed.put("thumbnail", jsonThumbnail);
                }

                if (author != null) {
                    JSONObject jsonAuthor = new JSONObject();

                    jsonAuthor.put("name", author.getName());
                    jsonAuthor.put("url", author.getUrl());
                    jsonAuthor.put("icon_url", author.getIconUrl());
                    jsonEmbed.put("author", jsonAuthor);
                }

                List<JSONObject> jsonFields = new ArrayList<>();
                for (EmbedObject.Field field : fields) {
                    JSONObject jsonField = new JSONObject();

                    jsonField.put("name", field.getName());
                    jsonField.put("value", field.getValue());
                    jsonField.put("inline", field.isInline());

                    jsonFields.add(jsonField);
                }

                jsonEmbed.put("fields", jsonFields.toArray());
                embedObjects.add(jsonEmbed);
            }

            json.put("embeds", embedObjects.toArray());
        }

        try {
        	URI uri = new URI(this.url);
            URL url = uri.toURL();
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.addRequestProperty("Content-Type", "application/json");
            connection.addRequestProperty("User-Agent", "Java-DiscordWebhook-BY-Gelox_");
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");

            OutputStream stream = connection.getOutputStream();
            stream.write(json.toString().getBytes(StandardCharsets.UTF_8));
            stream.flush();
            stream.close();

            connection.getInputStream().close(); //I'm not sure why but it doesn't work without getting the InputStream
            connection.disconnect();
        }
        
        catch(Exception ex) {
        	//Lib.errorNoTarget("Discord", ex, "545-875-652");
        }
    }

    /**
     *
     * @author wireser
     *
     */
    public static class EmbedObject {
        private String title;
        private String description;
        private String url;
        private Color color;

        private Footer footer;
        private Thumbnail thumbnail;
        private Image image;
        private Author author;
        private List<Field> fields = new ArrayList<>();

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
            return fields;
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

        private class Footer {
            private String text;
            private String iconUrl;

            private Footer(String text, String iconUrl) {
                this.text = text;
                this.iconUrl = iconUrl;
            }

            private String getText() {
                return text;
            }

            private String getIconUrl() {
                return iconUrl;
            }
        }

        private class Thumbnail {
            private String url;

            private Thumbnail(String url) {
                this.url = url;
            }

            private String getUrl() {
                return url;
            }
        }

        private class Image {
            private String url;

            private Image(String url) {
                this.url = url;
            }

            private String getUrl() {
                return url;
            }
        }

        private class Author {
            private String name;
            private String url;
            private String iconUrl;

            private Author(String name, String url, String iconUrl) {
                this.name = name;
                this.url = url;
                this.iconUrl = iconUrl;
            }

            private String getName() {
                return name;
            }

            private String getUrl() {
                return url;
            }

            private String getIconUrl() {
                return iconUrl;
            }
        }

        private class Field {
            private String name;
            private String value;
            private boolean inline;

            private Field(String name, String value, boolean inline) {
                this.name = name;
                this.value = value;
                this.inline = inline;
            }

            private String getName() {
                return name;
            }

            private String getValue() {
                return value;
            }

            private boolean isInline() {
                return inline;
            }
        }
    }

    /**
     *
     * @author wireser
     *
     */
    private class JSONObject {

        private final HashMap<String, Object> map = new HashMap<>();

        void put(String key, Object value) {
            if (value != null) {
                map.put(key, value);
            }
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            Set<Map.Entry<String, Object>> entrySet = map.entrySet();
            builder.append("{");

            int i = 0;
            for (Map.Entry<String, Object> entry : entrySet) {
                Object val = entry.getValue();
                builder.append(quote(entry.getKey())).append(":");

                if (val instanceof String) {
                    builder.append(quote(String.valueOf(val)));
                } else if (val instanceof Integer) {
                    builder.append(Integer.valueOf(String.valueOf(val)));
                } else if (val instanceof Boolean) {
                    builder.append(val);
                } else if (val instanceof JSONObject) {
                    builder.append(val.toString());
                } else if (val.getClass().isArray()) {
                    builder.append("[");
                    int len = Array.getLength(val);
                    for (int j = 0; j < len; j++) {
                        builder.append(Array.get(val, j).toString()).append(j != len - 1 ? "," : "");
                    }
                    builder.append("]");
                }

                builder.append(++i == entrySet.size() ? "}" : ",");
            }

            return builder.toString();
        }

        private String quote(String string) {
            return "\"" + string + "\"";
        }
    }

}