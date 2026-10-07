package com.wireser.minecraft.utils;

import java.util.Objects;

import org.slf4j.Logger;

import com.wireser.minecraft.TheMinecraftPlugin;

/**
 * Small static logging facade for TheMinecraftPlugin.
 *
 * <p>The facade keeps plugin logging consistent without requiring every caller
 * to keep its own plugin or logger reference. The Paper-provided SLF4J logger
 * is captured once through {@link #configure(TheMinecraftPlugin)} during plugin
 * load and reused by all later calls.</p>
 *
 * <p>Messages may use SLF4J placeholders:</p>
 *
 * <pre>{@code
 * PluginLogger.info("Loaded {} profiles.", count);
 * PluginLogger.warn("[Moderation] Player {} was already jailed.", playerId);
 * }</pre>
 *
 * <p>Error logging is intentionally available in three forms:</p>
 *
 * <ul>
 *     <li>{@link #error(String, Object...)} for errors without an exception,</li>
 *     <li>{@link #errorReduced(Throwable, String, Object...)} for a compact
 *         one-line exception summary, and</li>
 *     <li>{@link #errorFull(Throwable, String, Object...)} for the complete
 *         exception stack trace.</li>
 * </ul>
 *
 * <p>Debug output is controlled by a plugin-owned runtime switch. When enabled,
 * debug messages are forwarded at INFO level with a {@code [DEBUG]} marker so
 * they remain visible even when the server's logging backend suppresses native
 * SLF4J DEBUG output.</p>
 */
public final class PluginLogger {

    private static TheMinecraftPlugin plugin;
    private static Logger logger;

    private static volatile boolean debugEnabled;

    private PluginLogger() {
    }

    /**
     * Configures the logging facade from the active plugin instance.
     *
     * <p>This method should be called once from the plugin load lifecycle before
     * any other {@code PluginLogger} method is used.</p>
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
     * Enables or disables runtime debug logging.
     *
     * @param enabled {@code true} to show debug messages
     */
    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
        logger().info("[Debug] Debug logging {}.", enabled ? "enabled" : "disabled");
    }

    /**
     * Returns whether runtime debug logging is currently enabled.
     *
     * @return current debug state
     */
    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    /**
     * Logs a debug message when runtime debugging is enabled.
     *
     * <p>The message is intentionally forwarded at INFO level with a
     * {@code [DEBUG]} marker so the plugin's own runtime switch fully controls
     * whether the message is visible.</p>
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
     * Logs an informational message.
     *
     * @param message SLF4J message template
     * @param arguments values for {@code {}} placeholders
     */
    public static void info(String message, Object... arguments) {
        logger().info(message, arguments);
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
     * Logs an error without attaching an exception.
     *
     * @param message SLF4J message template
     * @param arguments values for {@code {}} placeholders
     */
    public static void error(String message, Object... arguments) {
        logger().error(message, arguments);
    }

    /**
     * Logs an error with a compact single-line exception summary.
     *
     * <p>The complete stack trace is intentionally not printed. The summary
     * contains the root exception type, exception message, and the first stack
     * frame belonging to this plugin when available.</p>
     *
     * @param throwable exception associated with the error, may be {@code null}
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
     * Logs an error and attaches the complete exception stack trace.
     *
     * <p>Use this when the full call chain is required for diagnosis. Prefer
     * {@link #errorReduced(Throwable, String, Object...)} for repetitive paths
     * such as database polling where full stack traces would flood the log.</p>
     *
     * @param throwable exception associated with the error
     * @param message SLF4J message template
     * @param arguments values for {@code {}} placeholders
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
     * Logs a fatal error and immediately disables the plugin.
     *
     * <p>A trailing {@link Throwable} may be supplied in {@code arguments};
     * SLF4J will treat it as the attached exception and print its full stack
     * trace. This method is reserved for unrecoverable failures where the
     * plugin must not continue operating.</p>
     *
     * @param message SLF4J message template
     * @param arguments values for {@code {}} placeholders and optionally a
     *                  trailing exception
     */
    public static void fatal(String message, Object... arguments) {
        logger().error("[FATAL] " + message, arguments);
        plugin().getServer().getPluginManager().disablePlugin(plugin());
    }

    /**
     * Builds a compact diagnostic description of an exception.
     *
     * <p>The deepest cause is used when the supplied exception wraps another
     * failure. The first stack frame inside {@code com.wireser.minecraft} is
     * preferred so the summary points to plugin code instead of library
     * internals.</p>
     *
     * <p>Example:</p>
     *
     * <pre>{@code
     * NullPointerException: profile was null
     * @ com.wireser.minecraft.modules.ModerationModule.sendPlayerToJail(ModerationModule.java:184)
     * }</pre>
     *
     * @param throwable exception to summarize, may be {@code null}
     * @return compact exception description
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

    private static Logger logger() {
        if (logger == null) {
            throw new IllegalStateException("PluginLogger has not been configured.");
        }

        return logger;
    }

    private static TheMinecraftPlugin plugin() {
        if (plugin == null) {
            throw new IllegalStateException("PluginLogger has not been configured.");
        }

        return plugin;
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;

        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }

        return current;
    }

    private static StackTraceElement relevantFrame(Throwable throwable) {
        StackTraceElement[] trace = throwable.getStackTrace();

        for (StackTraceElement element : trace) {
            if (element.getClassName().startsWith("com.wireser.minecraft.")) {
                return element;
            }
        }

        return trace.length == 0 ? null : trace[0];
    }

    private static Object[] append(Object[] arguments, Object value) {
        Object[] result = new Object[arguments.length + 1];

        System.arraycopy(arguments, 0, result, 0, arguments.length);
        result[arguments.length] = value;

        return result;
    }
}
