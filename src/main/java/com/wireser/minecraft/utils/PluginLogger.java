package com.wireser.minecraft.utils;

import java.util.Objects;

import org.slf4j.Logger;

import com.wireser.minecraft.TheMinecraftPlugin;

/**
 * Static logging facade for TheMinecraftPlugin.
 *
 * <p>This utility exposes the Paper-provided SLF4J logger through a small,
 * consistent API that can be used from ordinary classes and static utility
 * code without repeatedly resolving the plugin instance.</p>
 *
 * <p>The logger is configured once during plugin load with
 * {@link #configure(TheMinecraftPlugin)}. After that, all logging methods reuse
 * the cached SLF4J logger.</p>
 *
 * <p>SLF4J placeholders are supported:</p>
 *
 * <pre>{@code
 * PluginLogger.info("Loaded {} profiles.", count);
 * PluginLogger.warn("Database", "Player {} was not found.", playerId);
 * }</pre>
 *
 * <p>Error logging is intentionally split into three forms:</p>
 *
 * <ul>
 *     <li>{@link #error(String, Object...)} logs an error without an exception.</li>
 *     <li>{@link #errorReduced(Throwable, String, Object...)} logs a compact,
 *         single-line summary of an exception.</li>
 *     <li>{@link #errorFull(Throwable, String, Object...)} logs the complete
 *         exception with its full stack trace.</li>
 * </ul>
 *
 * <p>{@link #fatal(String, Object...)} is reserved for unrecoverable failures.
 * It logs the message and disables the plugin immediately.</p>
 */
public final class PluginLogger {

    private static TheMinecraftPlugin plugin;
    private static Logger logger;

    private static volatile boolean debugEnabled;

    private PluginLogger() {
    }

    /**
     * Configures the logging facade for the active plugin instance.
     *
     * <p>This method must be called once during plugin load before any other
     * logging method is used.</p>
     *
     * @param pluginInstance active plugin instance
     * @throws NullPointerException if {@code pluginInstance} is {@code null}
     * @throws IllegalStateException if the logger has already been configured
     */
    public static void configure(TheMinecraftPlugin pluginInstance) {
        Objects.requireNonNull(pluginInstance, "pluginInstance");

        if (logger != null) {
            throw new IllegalStateException("PluginLogger is already configured.");
        }

        plugin = pluginInstance;
        logger = pluginInstance.getSLF4JLogger();
    }

    /**
     * Enables or disables plugin-controlled debug output.
     *
     * <p>Debug messages are emitted at INFO level with a {@code [DEBUG]} marker
     * so this switch, rather than the server's global SLF4J level, determines
     * whether TMP debug output is visible.</p>
     *
     * @param enabled {@code true} to enable debug logging; {@code false} to disable it
     */
    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
        logger().info("[Debug] Debug logging {}.", enabled ? "enabled" : "disabled");
    }

    /**
     * Returns whether plugin-controlled debug output is currently enabled.
     *
     * @return {@code true} when debug messages are enabled
     */
    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    /**
     * Logs a debug message when plugin-controlled debug output is enabled.
     *
     * @param message SLF4J message template
     * @param arguments values for {@code {}} placeholders
     */
    public static void debug(String message, Object... arguments) {
        if (!debugEnabled) {
            return;
        }

        logger().info("[DEBUG] " + message, arguments);
    }

    /**
     * Logs a prefixed debug message when plugin-controlled debug output is enabled.
     *
     * <p>For example, prefix {@code "Database"} produces
     * {@code [DEBUG] [Database] ...}.</p>
     *
     * @param prefix logical subsystem or feature name
     * @param message SLF4J message template
     * @param arguments values for {@code {}} placeholders
     */
    public static void debug(String prefix, String message, Object... arguments) {
        debug(prefixed(prefix, message), arguments);
    }

    /**
     * Logs an informational message.
     *
     * @param message SLF4J message template
     * @param arguments values for {@code {}} placeholders
     */
    public static void info(String message, Object... arguments) {
        logger().info(message, arguments);
    }

    /**
     * Logs a prefixed informational message.
     *
     * @param prefix logical subsystem or feature name
     * @param message SLF4J message template
     * @param arguments values for {@code {}} placeholders
     */
    public static void info(String prefix, String message, Object... arguments) {
        info(prefixed(prefix, message), arguments);
    }

    /**
     * Logs a warning message.
     *
     * @param message SLF4J message template
     * @param arguments values for {@code {}} placeholders
     */
    public static void warn(String message, Object... arguments) {
        logger().warn(message, arguments);
    }

    /**
     * Logs a prefixed warning message.
     *
     * @param prefix logical subsystem or feature name
     * @param message SLF4J message template
     * @param arguments values for {@code {}} placeholders
     */
    public static void warn(String prefix, String message, Object... arguments) {
        warn(prefixed(prefix, message), arguments);
    }

    /**
     * Logs an error without attaching an exception.
     *
     * @param message SLF4J message template
     * @param arguments values for {@code {}} placeholders
     */
    public static void error(String message, Object... arguments) {
        logger().error(message, arguments);
    }

    /**
     * Logs a prefixed error without attaching an exception.
     *
     * @param prefix logical subsystem or feature name
     * @param message SLF4J message template
     * @param arguments values for {@code {}} placeholders
     */
    public static void error(String prefix, String message, Object... arguments) {
        error(prefixed(prefix, message), arguments);
    }

    /**
     * Logs an error with a compact, single-line exception summary.
     *
     * <p>The full stack trace is deliberately omitted. The summary contains the
     * deepest exception type, its message, and the first stack frame belonging
     * to TMP when available.</p>
     *
     * @param throwable exception associated with the error; may be {@code null}
     * @param message SLF4J message template
     * @param arguments values for {@code {}} placeholders
     */
    public static void errorReduced(
            Throwable throwable,
            String message,
            Object... arguments
    ) {
        logger().error(
                message + " | {}",
                append(arguments, summarize(throwable))
        );
    }

    /**
     * Logs a prefixed error with a compact, single-line exception summary.
     *
     * @param prefix logical subsystem or feature name
     * @param throwable exception associated with the error; may be {@code null}
     * @param message SLF4J message template
     * @param arguments values for {@code {}} placeholders
     */
    public static void errorReduced(
            String prefix,
            Throwable throwable,
            String message,
            Object... arguments
    ) {
        errorReduced(throwable, prefixed(prefix, message), arguments);
    }

    /**
     * Logs an error with the complete exception stack trace.
     *
     * <p>Use this form when the complete call chain is needed for diagnosis.
     * For repetitive operations such as database polling, prefer
     * {@link #errorReduced(Throwable, String, Object...)} to avoid flooding the
     * console and log files.</p>
     *
     * @param throwable exception associated with the error
     * @param message SLF4J message template
     * @param arguments values for {@code {}} placeholders
     * @throws NullPointerException if {@code throwable} is {@code null}
     */
    public static void errorFull(
            Throwable throwable,
            String message,
            Object... arguments
    ) {
        Objects.requireNonNull(throwable, "throwable");

        logger().error(
                message,
                append(arguments, throwable)
        );
    }

    /**
     * Logs a prefixed error with the complete exception stack trace.
     *
     * @param prefix logical subsystem or feature name
     * @param throwable exception associated with the error
     * @param message SLF4J message template
     * @param arguments values for {@code {}} placeholders
     * @throws NullPointerException if {@code throwable} is {@code null}
     */
    public static void errorFull(
            String prefix,
            Throwable throwable,
            String message,
            Object... arguments
    ) {
        errorFull(throwable, prefixed(prefix, message), arguments);
    }

    /**
     * Logs an unrecoverable failure and disables the plugin immediately.
     *
     * <p>If the final item in {@code arguments} is a {@link Throwable}, SLF4J
     * treats it as the attached exception and prints its full stack trace.</p>
     *
     * <p>This method should only be used when continuing to run the plugin would
     * be unsafe or invalid.</p>
     *
     * @param message SLF4J message template
     * @param arguments values for {@code {}} placeholders and optionally a trailing exception
     */
    public static void fatal(String message, Object... arguments) {
        logger().error("[FATAL] " + message, arguments);
        plugin().getServer().getPluginManager().disablePlugin(plugin());
    }

    /**
     * Logs a prefixed unrecoverable failure and disables the plugin immediately.
     *
     * @param prefix logical subsystem or feature name
     * @param message SLF4J message template
     * @param arguments values for {@code {}} placeholders and optionally a trailing exception
     */
    public static void fatal(String prefix, String message, Object... arguments) {
        fatal(prefixed(prefix, message), arguments);
    }

    /**
     * Creates a compact diagnostic description of an exception.
     *
     * <p>The deepest cause is used when the supplied exception wraps another
     * failure. The first stack frame within {@code com.wireser.minecraft} is
     * preferred so the result points to TMP code rather than third-party
     * internals.</p>
     *
     * <p>Example result:</p>
     *
     * <pre>{@code
     * NullPointerException: profile was null
     * @ com.wireser.minecraft.modules.ModerationModule.sendPlayerToJail(ModerationModule.java:184)
     * }</pre>
     *
     * @param throwable exception to summarize; may be {@code null}
     * @return compact one-line exception description
     */
    public static String summarize(Throwable throwable) {
        if (throwable == null) {
            return "Unknown exception";
        }

        Throwable root = rootCause(throwable);

        StringBuilder summary = new StringBuilder(
                root.getClass().getSimpleName()
        );

        String exceptionMessage = root.getMessage();
        if (exceptionMessage != null && !exceptionMessage.isBlank()) {
            summary.append(": ").append(exceptionMessage);
        }

        StackTraceElement frame = relevantFrame(root);
        if (frame != null) {
            summary.append(" @ ")
                    .append(frame.getClassName())
                    .append('.')
                    .append(frame.getMethodName())
                    .append('(')
                    .append(frame.getFileName())
                    .append(':')
                    .append(frame.getLineNumber())
                    .append(')');
        }

        return summary.toString();
    }

    /**
     * Returns the configured SLF4J logger.
     *
     * @return configured logger
     * @throws IllegalStateException if {@link #configure(TheMinecraftPlugin)} has not been called
     */
    private static Logger logger() {
        if (logger == null) {
            throw new IllegalStateException("PluginLogger has not been configured.");
        }

        return logger;
    }

    /**
     * Returns the configured plugin instance used for fatal shutdown.
     *
     * @return configured plugin instance
     * @throws IllegalStateException if {@link #configure(TheMinecraftPlugin)} has not been called
     */
    private static TheMinecraftPlugin plugin() {
        if (plugin == null) {
            throw new IllegalStateException("PluginLogger has not been configured.");
        }

        return plugin;
    }

    /**
     * Prepends a normalized subsystem prefix to a message.
     *
     * @param prefix logical subsystem or feature name
     * @param message message to prefix
     * @return message in the form {@code [Prefix] message}
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code prefix} is blank
     */
    private static String prefixed(String prefix, String message) {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(message, "message");

        if (prefix.isBlank()) {
            throw new IllegalArgumentException("Log prefix cannot be blank.");
        }

        return "[" + prefix + "] " + message;
    }

    /**
     * Returns the deepest cause in an exception chain.
     *
     * @param throwable starting exception
     * @return deepest reachable cause
     */
    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;

        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }

        return current;
    }

    /**
     * Finds the most useful stack frame for a reduced exception message.
     *
     * <p>A TMP frame is preferred. If none exists, the first available frame is
     * returned.</p>
     *
     * @param throwable exception whose stack trace should be inspected
     * @return preferred stack frame, or {@code null} when no frame exists
     */
    private static StackTraceElement relevantFrame(Throwable throwable) {
        StackTraceElement[] trace = throwable.getStackTrace();

        for (StackTraceElement element : trace) {
            if (element.getClassName().startsWith("com.wireser.minecraft.")) {
                return element;
            }
        }

        return trace.length == 0 ? null : trace[0];
    }

    /**
     * Appends one value to an argument array.
     *
     * <p>This helper remains private because it currently exists only to attach
     * reduced exception summaries or full throwables to SLF4J argument arrays.
     * It should move to a shared array utility only if another independent TMP
     * caller needs the same operation.</p>
     *
     * @param arguments original arguments
     * @param value value to append
     * @return new array containing all original arguments followed by {@code value}
     */
    private static Object[] append(Object[] arguments, Object value) {
        Object[] result = new Object[arguments.length + 1];

        System.arraycopy(arguments, 0, result, 0, arguments.length);
        result[arguments.length] = value;

        return result;
    }
}
