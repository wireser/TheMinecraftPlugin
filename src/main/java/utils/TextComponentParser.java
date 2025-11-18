package utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts legacy-style message strings (with & codes and #RRGGBB)
 * into Adventure Components via MiniMessage.
 */
public final class TextComponentParser {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Pattern HEX_PATTERN = Pattern.compile("#([A-Fa-f0-9]{6})");

    private static final Map<Character, String> LEGACY_TAGS = new HashMap<>();

    static {
        // Colors
        LEGACY_TAGS.put('0', "black");
        LEGACY_TAGS.put('1', "dark_blue");
        LEGACY_TAGS.put('2', "dark_green");
        LEGACY_TAGS.put('3', "dark_aqua");
        LEGACY_TAGS.put('4', "dark_red");
        LEGACY_TAGS.put('5', "dark_purple");
        LEGACY_TAGS.put('6', "gold");
        LEGACY_TAGS.put('7', "gray");
        LEGACY_TAGS.put('8', "dark_gray");
        LEGACY_TAGS.put('9', "blue");
        LEGACY_TAGS.put('a', "green");
        LEGACY_TAGS.put('b', "aqua");
        LEGACY_TAGS.put('c', "red");
        LEGACY_TAGS.put('d', "light_purple");
        LEGACY_TAGS.put('e', "yellow");
        LEGACY_TAGS.put('f', "white");
        // Styles
        LEGACY_TAGS.put('l', "bold");
        LEGACY_TAGS.put('o', "italic");
        LEGACY_TAGS.put('n', "underlined");
        LEGACY_TAGS.put('m', "strikethrough");
        LEGACY_TAGS.put('k', "obfuscated");
        LEGACY_TAGS.put('r', "reset");
    }

    private TextComponentParser() {
    	
    }

    @NotNull
	public static Component toComponent(@NotNull String input) {
        if (input.isEmpty()) {
            return Component.empty();
        }
        String mini = toMiniMessageSyntax(input);
        return MINI_MESSAGE.deserialize(mini);
    }

    @NotNull
    private static String toMiniMessageSyntax(@NotNull String input) {
        // #RRGGBB -> <#RRGGBB>
        Matcher matcher = HEX_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            matcher.appendReplacement(sb, "<#" + hex + ">");
        }
        matcher.appendTail(sb);
        String withHex = sb.toString();

        // &x -> <color/style>
        StringBuilder out = new StringBuilder(withHex.length());
        char[] chars = withHex.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '&' && i + 1 < chars.length) {
                char code = Character.toLowerCase(chars[i + 1]);
                String tag = LEGACY_TAGS.get(code);
                if (tag != null) {
                    if ("reset".equals(tag)) {
                        out.append("<reset>");
                    } else if ("bold".equals(tag) || "italic".equals(tag)
                            || "underlined".equals(tag) || "strikethrough".equals(tag)
                            || "obfuscated".equals(tag)) {
                        out.append('<').append(tag).append('>');
                    } else {
                        out.append('<').append(tag).append('>');
                    }
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

}