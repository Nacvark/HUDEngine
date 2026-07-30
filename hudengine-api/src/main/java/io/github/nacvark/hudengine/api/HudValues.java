package io.github.nacvark.hudengine.api;

import org.bukkit.entity.Player;

import java.util.function.Function;

/**
 * Where a plugin publishes values for HUD patterns.
 *
 * <p>A key registered here is what goes between brackets in a config, so registering
 * {@code "myplugin:mana"} makes {@code [myplugin:mana]} draw. Prefix keys with your plugin's name:
 * the namespace is flat and two plugins claiming {@code mana} would silently fight over it.
 *
 * <p>A provider runs once per HUD tick per player, on that player's own thread. Keep it to a lookup.
 * If a value is expensive, compute it on your own schedule and have the provider return the cached
 * result — a slow provider shows up directly as server tick time.
 *
 * <p>Returning null is treated as an empty string, so a value that does not apply to a player does
 * not need a special case.
 */
public interface HudValues {

    /**
     * Publishes a value.
     *
     * @return the provider this replaced, or null if the key was free. A non-null return means
     *         another plugin already owned that key and you have just taken it from them.
     */
    Function<Player, String> register(String key, Function<Player, String> provider);

    /** Withdraws a value. Patterns using it fall back to whatever else resolves the key. */
    void unregister(String key);

    /** Whether anything is registered under this key. */
    boolean isRegistered(String key);
}
