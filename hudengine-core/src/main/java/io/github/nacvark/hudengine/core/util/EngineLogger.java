package io.github.nacvark.hudengine.core.util;

/**
 * Log sink for the compiler and renderer.
 *
 * The core has no server to log to, so callers supply one. The plugin passes an adapter for
 * {@code Plugin#getLogger()}; tests and the CLI use {@link #console()}.
 */
public interface EngineLogger {

    void info(String message);

    void warn(String message);

    void error(String message, Throwable cause);

    static EngineLogger console() {
        return new EngineLogger() {
            @Override
            public void info(String message) {
                System.out.println("[HUDEngine] " + message);
            }

            @Override
            public void warn(String message) {
                System.err.println("[HUDEngine] WARN: " + message);
            }

            @Override
            public void error(String message, Throwable cause) {
                System.err.println("[HUDEngine] ERROR: " + message);
                if (cause != null) {
                    cause.printStackTrace(System.err);
                }
            }
        };
    }

    /** Discards everything. Useful in tests that assert on results rather than output. */
    static EngineLogger silent() {
        return new EngineLogger() {
            @Override
            public void info(String message) {
            }

            @Override
            public void warn(String message) {
            }

            @Override
            public void error(String message, Throwable cause) {
            }
        };
    }
}
