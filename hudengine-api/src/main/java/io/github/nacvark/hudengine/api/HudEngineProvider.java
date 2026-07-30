package io.github.nacvark.hudengine.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Optional;

/**
 * Resolves the running {@link HudEngine}.
 *
 * <p>HUDEngine registers itself with the services manager on enable, so declare it as a
 * {@code depend} or {@code softdepend} in your plugin.yml and look it up in {@code onEnable}:
 *
 * <pre>{@code
 * HudEngine hud = HudEngineProvider.get();
 * }</pre>
 *
 * <p>Or, if your plugin should work without it:
 *
 * <pre>{@code
 * HudEngineProvider.find().ifPresent(hud -> hud.values().register(...));
 * }</pre>
 */
public final class HudEngineProvider {

    private HudEngineProvider() {
    }

    /** The running engine, or empty if it is not installed or failed to start. */
    public static Optional<HudEngine> find() {
        RegisteredServiceProvider<HudEngine> registration =
                Bukkit.getServicesManager().getRegistration(HudEngine.class);
        return registration == null ? Optional.empty() : Optional.ofNullable(registration.getProvider());
    }

    /**
     * The running engine.
     *
     * @throws IllegalStateException if HUDEngine is not installed, has not enabled yet, or failed to
     *                               compile. Declaring it as a {@code depend} in your plugin.yml
     *                               guarantees the ordering; it does not guarantee it compiled.
     */
    public static HudEngine get() {
        return find().orElseThrow(() -> new IllegalStateException(
                "HUDEngine is not available. Declare it in your plugin.yml under depend or "
                        + "softdepend, and check the console: the engine does not register itself "
                        + "if the configuration failed to compile."));
    }
}
