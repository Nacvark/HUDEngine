package io.github.nacvark.hudengine.api;

import org.bukkit.entity.Player;

import java.util.List;

/**
 * Entry point for plugins building on HUDEngine.
 *
 * <p>Obtain it through {@link HudEngineProvider#get()}, which resolves it from the services manager,
 * and hold onto the interface rather than the implementation:
 *
 * <pre>{@code
 * HudEngine hud = HudEngineProvider.get();
 * hud.values().register("myplugin:mana", player -> String.valueOf(mana.of(player)));
 * hud.player(player).show("quest_tracker");
 * }</pre>
 *
 * <p>Everything here runs on the calling thread and does no I/O, so it is safe to call from an event
 * handler. The exception is {@link #reload()}, which recompiles the whole pack.
 */
public interface HudEngine {

    /** Every compiled HUD, in the order the configuration defined them. */
    List<String> huds();

    /** Whether a HUD with this key was compiled. */
    boolean hasHud(String hudKey);

    /** What one player currently sees, and how to change it. */
    PlayerHuds player(Player player);

    /** Where a plugin publishes values for HUD patterns to draw. */
    HudValues values();

    /** Where a plugin publishes points for compasses to point at. */
    HudCompass compass();

    /** Pixel measurement against a HUD's real fonts. */
    HudMetrics metrics();

    /**
     * Recompiles the configuration and swaps the model in.
     *
     * <p>Text and positions change at once. Clients only see new textures, fonts or glyph positions
     * once they receive the pack again, which in practice means a reconnect.
     */
    ReloadResult reload();

    /** Whether the engine compiled successfully and is currently rendering. */
    boolean isRunning();

    /**
     * The outcome of a reload.
     *
     * @param messages lines describing what happened, already localised, suitable to show a player
     */
    record ReloadResult(boolean success, List<String> messages) {

        public ReloadResult {
            messages = List.copyOf(messages);
        }
    }
}
