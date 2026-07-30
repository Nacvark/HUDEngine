package io.github.nacvark.hudengine.paper;

import io.github.nacvark.hudengine.api.HudValues;
import io.github.nacvark.hudengine.core.runtime.HudRenderer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Resolves the placeholder keys a HUD asks for.
 *
 * Keys arrive exactly as the config wrote them, minus any {@code (number)} cast hint. Where a key
 * goes is decided by its prefix:
 *
 * - {@code papi:name} goes to PlaceholderAPI as {@code %name%}, if it is installed;
 * - anything else is looked up in this registry, which is where other plugins publish values;
 * - a handful of player basics are built in so a HUD works before anything registers.
 *
 * Resolvers are called once per HUD tick per player on that player's own thread. Keep them cheap;
 * if a value is expensive to produce, cache it inside the provider.
 */
public final class ValueRegistry implements HudValues {

    private static final String PAPI_PREFIX = "papi:";

    private final Map<String, Function<Player, String>> values = new ConcurrentHashMap<>();
    private final PlaceholderApiBridge placeholders;

    ValueRegistry(PlaceholderApiBridge placeholders) {
        this.placeholders = placeholders;
    }

    @Override
    public Function<Player, String> register(String key, Function<Player, String> provider) {
        return values.put(key, provider);
    }

    @Override
    public void unregister(String key) {
        values.remove(key);
    }

    @Override
    public boolean isRegistered(String key) {
        return values.containsKey(key);
    }

    /**
     * A resolver bound to one player for the duration of a tick.
     *
     * Order matters: a registered provider wins, so a plugin can override a built-in with its own
     * notion of, say, health. Then {@code papi:} routes to PlaceholderAPI. Only then do the built-in
     * values answer, which keeps them from shadowing anything a server deliberately set up.
     */
    public HudRenderer.ValueResolver forPlayer(Player player) {
        return key -> {
            Function<Player, String> provider = values.get(key);
            if (provider != null) {
                String value = provider.apply(player);
                return value != null ? value : "";
            }
            if (key.startsWith(PAPI_PREFIX)) {
                return placeholders.resolve(player, key.substring(PAPI_PREFIX.length()));
            }
            String builtIn = BuiltInValues.resolve(player, key);
            return builtIn != null ? builtIn : "";
        };
    }

    /** Every key the engine resolves on its own, for documentation and diagnostics. */
    public static List<String> builtInKeys() {
        return BuiltInValues.keys();
    }
}
