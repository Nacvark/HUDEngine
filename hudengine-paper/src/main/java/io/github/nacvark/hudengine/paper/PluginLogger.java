package io.github.nacvark.hudengine.paper;

import io.github.nacvark.hudengine.core.util.EngineLogger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

/**
 * Console output.
 *
 * Goes through Paper's {@link ComponentLogger} so colour survives to the console. No engine
 * prefix is added: the platform already tags every line with the plugin name, and adding a second
 * one would print it twice.
 *
 * The {@link EngineLogger} methods take plain strings and exist for the compiler, whose messages
 * are technical and carry embedded data. Localised lifecycle messages come in as components.
 */
final class PluginLogger implements EngineLogger {

    private final ComponentLogger logger;

    PluginLogger(ComponentLogger logger) {
        this.logger = logger;
    }

    @Override
    public void info(String message) {
        logger.info(Component.text(message));
    }

    @Override
    public void warn(String message) {
        logger.warn(Component.text(message));
    }

    @Override
    public void error(String message, Throwable cause) {
        if (cause == null) {
            logger.error(Component.text(message));
        } else {
            logger.error(Component.text(message), cause);
        }
    }

    void info(Component message) {
        logger.info(message);
    }

    void warn(Component message) {
        logger.warn(message);
    }

    void error(Component message, Throwable cause) {
        logger.error(message, cause);
    }
}
