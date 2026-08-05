package managers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import utils.TextComponentParser;

import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Generic YAML-based LanguageManager.
 *
 * Usage per module:
 *   YamlLanguageManager lang = new YamlLanguageManager(logger);
 *   lang.load(modulesBase.resolve("warps").resolve("lang.yml"));
 *
 *   lang.get("error_warp_not_found");
 *   lang.get("error_warp_not_found", warpName);
 */
public final class YamlLanguageManager implements LanguageManager {

    private final Logger logger;
    private final Map<String, String> templates = new ConcurrentHashMap<>();

    public YamlLanguageManager(@NotNull Logger logger) {
        this.logger = logger;
    }

    /**
     * Loads language entries from the given YAML file into memory.
     *
     * @param file path to the module's lang.yml
     */
    public void load(@NotNull Path file) {
        templates.clear();

        if (!Files.exists(file)) {
            logger.warning("[Language] Language file does not exist: " + file);
            return;
        }

        Yaml yaml = new Yaml();

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object root = yaml.load(reader);

            if (!(root instanceof Map)) {
                logger.warning("[Language] Root of " + file + " is not a map, nothing loaded.");
                return;
            }

            @SuppressWarnings("unchecked")
            Map<Object, Object> map = (Map<Object, Object>) root;

            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                String rawKey = String.valueOf(entry.getKey());
                Object valueObj = entry.getValue();

                if (!(valueObj instanceof String)) {
                    logger.warning("[Language] Key '" + rawKey + "' has non-string value, skipping.");
                    continue;
                }

                String value = (String) valueObj;
                registerEntry(rawKey, value);
            }

            logger.info("[Language] Loaded " + templates.size() + " language entries from " + file);

        } catch (MarkedYAMLException e) {
            Mark mark = e.getProblemMark();
            String location = "";
            if (mark != null) {
                location = " at line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1);
            }
            logger.log(Level.SEVERE,
                    "[Language] Failed to parse YAML file " + file + location + ": " + e.getProblem(), e);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "[Language] I/O error while reading " + file, e);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "[Language] Unexpected error while loading language file " + file, e);
        }
    }

    private void registerEntry(@NotNull String key, @NotNull String value) {
        String normalizedKey = normalizeKey(key);

        if (normalizedKey.isEmpty()) {
            logger.warning("[Language] Ignoring empty key.");
            return;
        }

        if (value.isEmpty()) {
            logger.warning("[Language] Key '" + key + "' has empty value, using red %KEY% placeholder.");
            templates.put(normalizedKey, makeErrorPlaceholder(normalizedKey));
            return;
        }

        if (value.length() > MAX_MESSAGE_LENGTH) {
            logger.warning("[Language] Key '" + key + "' exceeds max length (" + MAX_MESSAGE_LENGTH
                    + "), using red %KEY% placeholder.");
            templates.put(normalizedKey, makeErrorPlaceholder(normalizedKey));
            return;
        }

        templates.put(normalizedKey, value);
    }

    @NotNull
    private static String normalizeKey(@NotNull String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }

    @NotNull
    private static String makeErrorPlaceholder(@NotNull String normalizedKey) {
        // &c%ERROR_WARP_NOT_FOUND%
        return "&c%" + normalizedKey.toUpperCase(Locale.ROOT) + "%";
    }

    // ===================== LanguageManager implementation =====================

    @Override
    public @NotNull Component get(@NotNull String key) {
        String template = resolveTemplate(key);
        return TextComponentParser.toComponent(template);
    }

    @Override
    public @NotNull Component get(@NotNull String key, Object... arguments) {
        String template = resolveTemplate(key);
        String withArgs = applyArguments(template, arguments);
        return TextComponentParser.toComponent(withArgs);
    }

    @Override
    public @NotNull String getString(@NotNull String key) {
        return PlainTextComponentSerializer.plainText().serialize(get(key));
    }

    @Override
    public @NotNull String getString(@NotNull String key, Object... arguments) {
        return PlainTextComponentSerializer.plainText().serialize(get(key, arguments));
    }

    // ===================== Internals =====================

    @NotNull
    private String resolveTemplate(@NotNull String key) {
        String normalizedKey = normalizeKey(key);
        String template = templates.get(normalizedKey);

        if (template == null || template.isEmpty()) {
            return makeErrorPlaceholder(normalizedKey);
        }

        if (template.length() > MAX_MESSAGE_LENGTH) {
            return makeErrorPlaceholder(normalizedKey);
        }

        return template;
    }

    /**
     * Replaces %1..%N with the given arguments.
     * Named placeholders like %warp_name% are left untouched.
     */
    @NotNull
    private static String applyArguments(@NotNull String template, Object... arguments) {
        if (arguments == null || arguments.length == 0) {
            return template;
        }
        String result = template;
        for (int i = 0; i < arguments.length; i++) {
            String placeholder = "%" + (i + 1);
            Object arg = arguments[i];
            String replacement = (arg == null ? "null" : String.valueOf(arg));
            result = result.replace(placeholder, replacement);
        }
        return result;
    }
    
}