package managers;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import utils.TextComponentParser;

/**
 * Loads scalar messages and string lists from {@code lang.yml} into memory.
 *
 * <p>The bundled file supplies defaults and the file in the plugin data folder
 * supplies editable overrides. Runtime reads never touch the filesystem.</p>
 */
public final class YamlLanguageManager implements LanguageManager {

    private static final String LANGUAGE_FILE_NAME = "lang.yml";
    private static final String LOG_PREFIX = "[Language] ";

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Path languageFile;
    private final Map<String, String> missingLines = new ConcurrentHashMap<>();
    private final Set<String> missingLists = ConcurrentHashMap.newKeySet();

    private volatile Map<String, String> lines = Map.of();
    private volatile Map<String, List<String>> lists = Map.of();

    /** Creates the manager and loads both language sources immediately. */
    public YamlLanguageManager(@NotNull JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        this.languageFile = plugin.getDataFolder().toPath().resolve(LANGUAGE_FILE_NAME);
        reload();
    }

    /** Rebuilds and atomically publishes the complete in-memory snapshot. */
    @Override
    public synchronized void reload() {
        createFilesystemLanguageIfMissing();

        LanguageData bundled = readBundledLanguage();
        LanguageData filesystem = readFilesystemLanguage();
        Map<String, String> mergedLines = new HashMap<>(bundled.lines());
        Map<String, List<String>> mergedLists = new HashMap<>(bundled.lists());

        for (Map.Entry<String, String> entry : filesystem.lines().entrySet()) {
            if (bundled.lists().containsKey(entry.getKey())) {
                logTypeMismatch(entry.getKey(), "list", "line");
                continue;
            }

            mergedLines.put(entry.getKey(), entry.getValue());
            mergedLists.remove(entry.getKey());
        }

        for (Map.Entry<String, List<String>> entry : filesystem.lists().entrySet()) {
            if (bundled.lines().containsKey(entry.getKey())) {
                logTypeMismatch(entry.getKey(), "line", "list");
                continue;
            }

            mergedLists.put(entry.getKey(), entry.getValue());
            mergedLines.remove(entry.getKey());
        }

        lines = Map.copyOf(mergedLines);
        lists = Map.copyOf(mergedLists);
        missingLines.clear();
        missingLists.clear();

        logger.info(LOG_PREFIX + "Loaded " + lines.size() + " language lines and "
                + lists.size() + " language lists into memory.");
    }

    /** Returns one dotted language key rendered as an Adventure component. */
    @Override
    public @NotNull Component line(@NotNull String key, Object... arguments) {
        return render(resolveLine(key), arguments);
    }

    /** Returns one dotted language key with Adventure formatting removed. */
    @Override
    public @NotNull String plain(@NotNull String key, Object... arguments) {
        return PlainTextComponentSerializer.plainText().serialize(line(key, arguments));
    }

    /**
     * Returns at most {@code maximumItems} valid entries from a cached list.
     * Missing or invalid data falls back to {@code defaultItem} when supplied.
     */
    @Override
    public @NotNull List<String> list(@NotNull String key, int maximumItems,
            @Nullable String defaultItem) {
        if (maximumItems < 1) throw new IllegalArgumentException("maximumItems must be positive");

        String normalizedKey = normalizeKey(key);
        List<String> configuredList = lists.get(normalizedKey);

        if (configuredList == null || configuredList.isEmpty()) {
            if (missingLists.add(normalizedKey)) {
                logger.warning(LOG_PREFIX + "Missing or invalid language list: " + normalizedKey);
            }

            return defaultItem == null || defaultItem.isBlank()
                    ? List.of()
                    : List.of(defaultItem);
        }

        int returnedItems = Math.min(configuredList.size(), maximumItems);
        return List.copyOf(configuredList.subList(0, returnedItems));
    }

    /** Renders a raw template selected from a language list. */
    @Override
    public @NotNull Component render(@NotNull String template, Object... arguments) {
        return TextComponentParser.toComponent(applyArguments(template, arguments));
    }

    /** Copies the bundled language file into the plugin directory once. */
    private void createFilesystemLanguageIfMissing() {
        if (Files.exists(languageFile)) return;

        try {
            plugin.saveResource(LANGUAGE_FILE_NAME, false);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Bundled " + LANGUAGE_FILE_NAME + " is missing.", exception);
        }

        if (!Files.exists(languageFile)) {
            throw new IllegalStateException("Language file could not be created: " + languageFile);
        }
    }

    /** Reads the defaults packaged inside the plugin JAR. */
    private LanguageData readBundledLanguage() {
        InputStream resource = plugin.getResource(LANGUAGE_FILE_NAME);
        if (resource == null) {
            throw new IllegalStateException("Bundled " + LANGUAGE_FILE_NAME + " is missing.");
        }

        return readLanguageSource(new InputStreamReader(resource, StandardCharsets.UTF_8),
                "bundled " + LANGUAGE_FILE_NAME);
    }

    /** Reads the server owner's editable language file. */
    private LanguageData readFilesystemLanguage() {
        if (!Files.exists(languageFile)) return LanguageData.empty();

        try {
            return readLanguageSource(Files.newBufferedReader(languageFile, StandardCharsets.UTF_8),
                    languageFile.toString());
        } catch (IOException exception) {
            logger.log(Level.SEVERE, LOG_PREFIX + "Could not open " + languageFile, exception);
            return LanguageData.empty();
        }
    }

    /** Parses and flattens one YAML language source. */
    private LanguageData readLanguageSource(Reader reader, String sourceName) {
        Map<String, String> loadedLines = new HashMap<>();
        Map<String, List<String>> loadedLists = new HashMap<>();

        try (reader) {
            Object root = new Yaml().load(reader);
            if (root == null) return LanguageData.empty();

            if (!(root instanceof Map<?, ?> rootMap)) {
                logger.warning(LOG_PREFIX + "The root of " + sourceName + " is not a YAML map.");
                return LanguageData.empty();
            }

            collectEntries(rootMap, "", loadedLines, loadedLists, sourceName);
        } catch (MarkedYAMLException exception) {
            logYamlError(sourceName, exception);
        } catch (IOException exception) {
            logger.log(Level.SEVERE, LOG_PREFIX + "Could not read " + sourceName, exception);
        } catch (Exception exception) {
            logger.log(Level.SEVERE, LOG_PREFIX + "Unexpected error while loading " + sourceName,
                    exception);
        }

        return new LanguageData(Map.copyOf(loadedLines), Map.copyOf(loadedLists));
    }

    /** Flattens nested maps while preserving YAML string lists as lists. */
    private void collectEntries(Map<?, ?> source, String parentKey,
            Map<String, String> destinationLines, Map<String, List<String>> destinationLists,
            String sourceName) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String keyPart = String.valueOf(entry.getKey()).trim();
            if (keyPart.isEmpty()) {
                logger.warning(LOG_PREFIX + "Ignoring an empty key in " + sourceName + ".");
                continue;
            }

            String completeKey = parentKey.isEmpty() ? keyPart : parentKey + "." + keyPart;
            Object value = entry.getValue();

            if (value instanceof Map<?, ?> nestedSection) {
                collectEntries(nestedSection, completeKey, destinationLines, destinationLists,
                        sourceName);
            } else if (value instanceof String message) {
                registerLine(completeKey, message, destinationLines, sourceName);
            } else if (value instanceof List<?> messageList) {
                registerList(completeKey, messageList, destinationLists, sourceName);
            } else {
                logger.warning(LOG_PREFIX + "Language key '" + completeKey + "' in " + sourceName
                        + " is neither a string nor a string list and was ignored.");
            }
        }
    }

    /** Registers one valid scalar message. */
    private void registerLine(String key, String message, Map<String, String> destination,
            String sourceName) {
        String normalizedKey = normalizeKey(key);
        if (!isValidMessage(message)) {
            logger.warning(LOG_PREFIX + "Language line '" + normalizedKey + "' in " + sourceName
                    + " is empty or longer than " + MAX_MESSAGE_LENGTH + " characters.");
            return;
        }

        destination.put(normalizedKey, message);
    }

    /** Registers a list only when every item is a valid message string. */
    private void registerList(String key, List<?> values, Map<String, List<String>> destination,
            String sourceName) {
        String normalizedKey = normalizeKey(key);
        List<String> messages = new ArrayList<>();

        for (Object value : values) {
            if (!(value instanceof String message) || !isValidMessage(message)) {
                logger.warning(LOG_PREFIX + "Language list '" + normalizedKey + "' in "
                        + sourceName + " contains an invalid item and was ignored.");
                return;
            }

            messages.add(message);
        }

        if (messages.isEmpty()) {
            logger.warning(LOG_PREFIX + "Language list '" + normalizedKey + "' in " + sourceName
                    + " is empty and was ignored.");
            return;
        }

        destination.put(normalizedKey, List.copyOf(messages));
    }

    private String resolveLine(String key) {
        String normalizedKey = normalizeKey(key);
        String configuredLine = lines.get(normalizedKey);
        if (configuredLine != null) return configuredLine;

        return missingLines.computeIfAbsent(normalizedKey, missingKey -> {
            logger.warning(LOG_PREFIX + "Missing language key: " + missingKey);
            return "<red>%" + missingKey.toUpperCase(Locale.ROOT) + "%";
        });
    }

    /** Replaces %1, %2 and later positional placeholders without overlap. */
    private static String applyArguments(String template, Object... arguments) {
        if (arguments == null || arguments.length == 0) return template;

        String result = template;
        for (int index = arguments.length; index >= 1; index--) {
            String replacement = String.valueOf(arguments[index - 1]);
            result = result.replace("%" + index, replacement);
        }
        return result;
    }

    private static boolean isValidMessage(String message) {
        return message != null && !message.isBlank() && message.length() <= MAX_MESSAGE_LENGTH;
    }

    private static String normalizeKey(String key) {
        return Objects.requireNonNull(key, "key").trim().toLowerCase(Locale.ROOT);
    }

    private void logTypeMismatch(String key, String expectedType, String actualType) {
        logger.warning(LOG_PREFIX + "Filesystem key '" + key + "' must remain a " + expectedType
                + ", but a " + actualType + " was supplied. The bundled fallback remains active.");
    }

    private void logYamlError(String sourceName, MarkedYAMLException exception) {
        Mark mark = exception.getProblemMark();
        String location = mark == null ? "" : " at line " + (mark.getLine() + 1)
                + ", column " + (mark.getColumn() + 1);
        logger.log(Level.SEVERE, LOG_PREFIX + "Failed to parse " + sourceName + location + ": "
                + exception.getProblem(), exception);
    }

    /** Immutable data loaded from one language source. */
    private record LanguageData(Map<String, String> lines, Map<String, List<String>> lists) {
        private static LanguageData empty() {
            return new LanguageData(Map.of(), Map.of());
        }
    }
}
