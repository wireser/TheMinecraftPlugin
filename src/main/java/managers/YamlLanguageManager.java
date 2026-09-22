package managers;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import utils.TextComponentParser;

/**
 * Loads YAML language messages and keeps the resolved messages in memory.
 *
 * <p>The bundled {@code lang.yml} provides permanent fallback values. The
 * server-side {@code lang.yml} provides editable overrides. Filesystem values
 * take priority over bundled values.</p>
 *
 * <p>Language files are accessed only during construction and
 * {@link #reload()}. Runtime lookups use the completed in-memory map.</p>
 */
public final class YamlLanguageManager implements LanguageManager {

    private static final String LANGUAGE_FILE_NAME = "lang.yml";
    private static final String LOG_PREFIX = "[Language] ";

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Path languageFile;

    /**
     * Immutable snapshot of the merged language data.
     */
    private volatile Map<String, String> lines = Map.of();

    /**
     * Generated placeholders for keys missing from both language sources.
     *
     * <p>They are cached so each missing key is logged only once between
     * reloads.</p>
     */
    private final Map<String, String> missingLines =
            new ConcurrentHashMap<>();

    /**
     * Creates and immediately loads the language manager.
     *
     * <p>If the server-side {@code lang.yml} does not exist, it is copied from
     * the plugin JAR before the language data is loaded.</p>
     *
     * @param plugin owning plugin
     */
    public YamlLanguageManager(@NotNull JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(
                plugin,
                "plugin"
        );

        this.logger = plugin.getLogger();

        this.languageFile = plugin
                .getDataFolder()
                .toPath()
                .resolve(LANGUAGE_FILE_NAME);

        reload();
    }

    /**
     * Reloads bundled fallbacks and filesystem overrides.
     *
     * <p>The complete merged map is prepared separately and published in one
     * assignment. Callers cannot observe partially loaded language data.</p>
     */
    @Override
    public synchronized void reload() {
        createFilesystemLanguageIfMissing();

        Map<String, String> bundledLines =
                readBundledLanguage();

        Map<String, String> filesystemLines =
                readFilesystemLanguage();

        Map<String, String> mergedLines =
                new HashMap<>(bundledLines);

        /*
         * Filesystem values override bundled defaults with the same key.
         */
        mergedLines.putAll(filesystemLines);

        lines = Map.copyOf(mergedLines);
        missingLines.clear();

        logger.info(
                LOG_PREFIX
                        + "Loaded "
                        + lines.size()
                        + " language lines: "
                        + bundledLines.size()
                        + " bundled and "
                        + filesystemLines.size()
                        + " filesystem entries."
        );
    }

    /**
     * Creates the editable server-side language file when necessary.
     */
    private void createFilesystemLanguageIfMissing() {
        if (Files.exists(languageFile)) {
            return;
        }

        /*
         * saveResource() creates the plugin data directory when necessary and
         * copies the bundled resource without overwriting existing files.
         */
        try {
            plugin.saveResource(
                    LANGUAGE_FILE_NAME,
                    false
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Cannot create "
                            + languageFile
                            + " because the bundled "
                            + LANGUAGE_FILE_NAME
                            + " resource is missing.",
                    exception
            );
        }

        if (!Files.exists(languageFile)) {
            throw new IllegalStateException(
                    "The language file could not be created: "
                            + languageFile
            );
        }
    }

    /**
     * Reads fallback messages bundled inside the plugin JAR.
     */
    private Map<String, String> readBundledLanguage() {
        InputStream bundledResource =
                plugin.getResource(LANGUAGE_FILE_NAME);

        if (bundledResource == null) {
            throw new IllegalStateException(
                    "Bundled "
                            + LANGUAGE_FILE_NAME
                            + " is missing from the plugin JAR."
            );
        }

        Reader reader = new InputStreamReader(
                bundledResource,
                StandardCharsets.UTF_8
        );

        return readLanguageSource(
                reader,
                "bundled " + LANGUAGE_FILE_NAME
        );
    }

    /**
     * Reads editable language overrides from the plugin data directory.
     */
    private Map<String, String> readFilesystemLanguage() {
        if (!Files.exists(languageFile)) {
            logger.warning(
                    LOG_PREFIX
                            + "Filesystem language file does not exist: "
                            + languageFile
            );

            return Map.of();
        }

        try {
            Reader reader = Files.newBufferedReader(
                    languageFile,
                    StandardCharsets.UTF_8
            );

            return readLanguageSource(
                    reader,
                    languageFile.toString()
            );
        } catch (IOException exception) {
            logger.log(
                    Level.SEVERE,
                    LOG_PREFIX
                            + "Could not open "
                            + languageFile,
                    exception
            );

            return Map.of();
        }
    }

    /**
     * Parses one YAML language source.
     *
     * @param reader source reader
     * @param sourceName human-readable source name
     * @return valid flattened language entries
     */
    private Map<String, String> readLanguageSource(
            Reader reader,
            String sourceName
    ) {
        Map<String, String> loadedLines =
                new HashMap<>();

        Yaml yaml = new Yaml();

        try (reader) {
            Object root = yaml.load(reader);

            /*
             * An empty YAML document contributes no entries.
             */
            if (root == null) {
                return loadedLines;
            }

            if (!(root instanceof Map<?, ?> rootMap)) {
                logger.warning(
                        LOG_PREFIX
                                + "The root of "
                                + sourceName
                                + " is not a YAML map."
                );

                return loadedLines;
            }

            collectLines(
                    rootMap,
                    "",
                    loadedLines,
                    sourceName
            );
        } catch (MarkedYAMLException exception) {
            logYamlError(
                    sourceName,
                    exception
            );
        } catch (IOException exception) {
            logger.log(
                    Level.SEVERE,
                    LOG_PREFIX
                            + "Could not read "
                            + sourceName,
                    exception
            );
        } catch (Exception exception) {
            logger.log(
                    Level.SEVERE,
                    LOG_PREFIX
                            + "Unexpected error while loading "
                            + sourceName,
                    exception
            );
        }

        return loadedLines;
    }

    /**
     * Recursively converts nested YAML sections into dotted keys.
     *
     * <p>For example, {@code command -> unknown} becomes
     * {@code command.unknown}.</p>
     */
    private void collectLines(
            Map<?, ?> source,
            String parentKey,
            Map<String, String> destination,
            String sourceName
    ) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String keyPart = String
                    .valueOf(entry.getKey())
                    .trim();

            if (keyPart.isEmpty()) {
                logger.warning(
                        LOG_PREFIX
                                + "Ignoring an empty key in "
                                + sourceName
                                + "."
                );

                continue;
            }

            String completeKey = parentKey.isEmpty()
                    ? keyPart
                    : parentKey + "." + keyPart;

            Object value = entry.getValue();

            if (value instanceof Map<?, ?> nestedSection) {
                collectLines(
                        nestedSection,
                        completeKey,
                        destination,
                        sourceName
                );

                continue;
            }

            if (!(value instanceof String message)) {
                logger.warning(
                        LOG_PREFIX
                                + "Language key '"
                                + completeKey
                                + "' in "
                                + sourceName
                                + " is not a string and was ignored."
                );

                continue;
            }

            registerLine(
                    completeKey,
                    message,
                    destination,
                    sourceName
            );
        }
    }

    /**
     * Validates and registers one language line.
     *
     * <p>Invalid filesystem entries are ignored so their bundled fallback
     * remains available.</p>
     */
    private void registerLine(
            @NotNull String key,
            @NotNull String message,
            Map<String, String> destination,
            String sourceName
    ) {
        String normalizedKey =
                normalizeKey(key);

        if (message.isBlank()) {
            logger.warning(
                    LOG_PREFIX
                            + "Language key '"
                            + normalizedKey
                            + "' in "
                            + sourceName
                            + " is empty and was ignored."
            );

            return;
        }

        if (message.length() > MAX_MESSAGE_LENGTH) {
            logger.warning(
                    LOG_PREFIX
                            + "Language key '"
                            + normalizedKey
                            + "' in "
                            + sourceName
                            + " exceeds "
                            + MAX_MESSAGE_LENGTH
                            + " characters and was ignored."
            );

            return;
        }

        destination.put(
                normalizedKey,
                message
        );
    }

    /**
     * Returns a formatted language line as an Adventure component.
     *
     * <p>This method performs only in-memory operations.</p>
     */
    @Override
    public @NotNull Component line(
            @NotNull String key,
            Object... arguments
    ) {
        String template =
                resolveLine(key);

        String resolvedMessage =
                applyArguments(
                        template,
                        arguments
                );

        return TextComponentParser.toComponent(
                resolvedMessage
        );
    }

    /**
     * Returns a formatted language line without colours or decorations.
     *
     * <p>This method performs only in-memory operations.</p>
     */
    @Override
    public @NotNull String plain(
            @NotNull String key,
            Object... arguments
    ) {
        return PlainTextComponentSerializer
                .plainText()
                .serialize(
                        line(key, arguments)
                );
    }

    /**
     * Resolves one language template from memory.
     */
    private @NotNull String resolveLine(
            @NotNull String key
    ) {
        String normalizedKey =
                normalizeKey(key);

        String configuredLine =
                lines.get(normalizedKey);

        if (configuredLine != null) {
            return configuredLine;
        }

        return missingLines.computeIfAbsent(
                normalizedKey,
                missingKey -> {
                    logger.warning(
                            LOG_PREFIX
                                    + "Missing language key: "
                                    + missingKey
                    );

                    return createMissingPlaceholder(
                            missingKey
                    );
                }
        );
    }

    /**
     * Replaces positional placeholders with supplied values.
     *
     * <p>{@code %1} represents the first argument, {@code %2} the second,
     * and so on.</p>
     */
    private static @NotNull String applyArguments(
            @NotNull String template,
            Object... arguments
    ) {
        if (arguments == null || arguments.length == 0) {
            return template;
        }

        String result = template;

        /*
         * Replace from highest to lowest so %10 is not interpreted as %1
         * followed by a zero.
         */
        for (int index = arguments.length; index >= 1; index--) {
            Object argument =
                    arguments[index - 1];

            String replacement = argument == null
                    ? "null"
                    : String.valueOf(argument);

            result = result.replace(
                    "%" + index,
                    replacement
            );
        }

        return result;
    }

    /**
     * Converts a language key to its canonical case-insensitive form.
     */
    private static @NotNull String normalizeKey(
            @NotNull String key
    ) {
        return key
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Creates the visible marker for an unavailable language key.
     */
    private static @NotNull String createMissingPlaceholder(
            @NotNull String normalizedKey
    ) {
        return "&c%"
                + normalizedKey.toUpperCase(Locale.ROOT)
                + "%";
    }

    /**
     * Logs a detailed YAML parsing error.
     */
    private void logYamlError(
            String sourceName,
            MarkedYAMLException exception
    ) {
        Mark problemMark =
                exception.getProblemMark();

        String location = problemMark == null
                ? ""
                : " at line "
                        + (problemMark.getLine() + 1)
                        + ", column "
                        + (problemMark.getColumn() + 1);

        logger.log(
                Level.SEVERE,
                LOG_PREFIX
                        + "Failed to parse "
                        + sourceName
                        + location
                        + ": "
                        + exception.getProblem(),
                exception
        );
    }

}