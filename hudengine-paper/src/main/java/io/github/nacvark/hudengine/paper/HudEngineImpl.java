package io.github.nacvark.hudengine.paper;

import io.github.nacvark.hudengine.api.HudCompass;
import io.github.nacvark.hudengine.api.HudEngine;
import io.github.nacvark.hudengine.api.HudMetrics;
import io.github.nacvark.hudengine.api.HudValues;
import io.github.nacvark.hudengine.api.PlayerHuds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;

/**
 * Binds the public API to the running plugin.
 *
 * Deliberately thin. Everything real lives in {@link HudService} and the registries; this exists
 * so third-party plugins compile against interfaces that stay put while the implementation moves.
 */
final class HudEngineImpl implements HudEngine, HudMetrics {

    private final HudEnginePlugin plugin;

    HudEngineImpl(HudEnginePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> huds() {
        HudService service = plugin.service();
        return service == null ? List.of() : service.availableHuds();
    }

    @Override
    public boolean hasHud(String hudKey) {
        return huds().contains(hudKey);
    }

    @Override
    public PlayerHuds player(Player player) {
        Objects.requireNonNull(player, "player");
        return new PlayerHudsImpl(requireService(), player);
    }

    @Override
    public HudValues values() {
        return plugin.values();
    }

    @Override
    public HudCompass compass() {
        return plugin.compass();
    }

    @Override
    public HudMetrics metrics() {
        return this;
    }

    @Override
    public int width(String hudKey, String elementKey, String text) {
        return requireService().textWidth(hudKey, elementKey, text);
    }

    @Override
    public List<String> wrap(String hudKey, String elementKey, String text, int maxWidthPx) {
        return requireService().wrapText(hudKey, elementKey, text, maxWidthPx);
    }

    @Override
    public ReloadResult reload() {
        List<Component> messages = plugin.reload();
        // The API is plain strings: a caller may be writing to a log or a web panel, not chat.
        List<String> plain = messages.stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .toList();
        return new ReloadResult(plugin.service() != null, plain);
    }

    @Override
    public boolean isRunning() {
        return plugin.service() != null;
    }

    private HudService requireService() {
        HudService service = plugin.service();
        if (service == null) {
            throw new IllegalStateException(
                    "HUDEngine is not running; the configuration failed to compile. See the console.");
        }
        return service;
    }

    /** One player's handle. Holds no state of its own, so it is cheap to create per call. */
    private record PlayerHudsImpl(HudService service, Player player) implements PlayerHuds {

        @Override
        public boolean isVisible() {
            return service.isVisible(player);
        }

        @Override
        public void setVisible(boolean visible) {
            service.setVisible(player, visible);
        }

        @Override
        public boolean toggle() {
            return service.toggle(player);
        }

        @Override
        public boolean show(String hudKey) {
            return service.showHud(player, hudKey);
        }

        @Override
        public boolean hide(String hudKey) {
            return service.hideHud(player, hudKey);
        }

        @Override
        public boolean showFor(String hudKey, int ticks) {
            return service.showHudFor(player, hudKey, ticks);
        }

        @Override
        public void reset() {
            service.resetHuds(player);
        }

        @Override
        public List<String> visible() {
            return service.visibleHuds(player);
        }

        @Override
        public void refresh() {
            service.refresh(player);
        }
    }
}
