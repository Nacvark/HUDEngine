package io.github.nacvark.hudengine.paper;

import io.github.nacvark.hudengine.core.runtime.HudRenderer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns renderer runs into Adventure components.
 *
 * The awkward part is the text shadow. The client draws every glyph twice, offset by one pixel,
 * and that second copy is what an outlined HUD element uses as its outline. For everything else it
 * is damage: images and head pixels would be drawn twice, one pixel apart.
 *
 * Turning the shadow off per component needs {@code shadow_color}, which the client only gained
 * in 1.21.4. Below that there is no way to ask for it, so the engine says so plainly instead of
 * quietly rendering something wrong.
 */
abstract class RunStyler {

    private static final String SHADOW_COLOR_CLASS = "net.kyori.adventure.text.format.ShadowColor";

    private final Map<String, Key> fonts = new ConcurrentHashMap<>();
    private final Map<Integer, TextColor> colors = new ConcurrentHashMap<>();

    /**
     * Built components, keyed by the run they came from.
     *
     * Worth caching because the renderer's block cache hands back the very same immutable run
     * objects tick after tick, so an unchanged part of a HUD costs no component building at all.
     * Weak keys mean the entries go when the blocks holding them do.
     */
    private final Map<HudRenderer.Run, Component> pieces =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Picks the best styler this server can support. */
    static RunStyler create(PluginLogger log, Messages messages) {
        try {
            Class.forName(SHADOW_COLOR_CLASS);
            return new ShadowAware();
        } catch (ClassNotFoundException | LinkageError e) {
            log.warn(messages.plain("console.shadow-unsupported"));
            return new NoShadowControl();
        }
    }

    /** Assembles the boss bar title. */
    final Component compose(List<HudRenderer.Run> runs) {
        TextComponent.Builder root = root();
        for (HudRenderer.Run run : runs) {
            root.append(pieces.computeIfAbsent(run, this::build));
        }
        return root.build();
    }

    void clearCache() {
        pieces.clear();
    }

    abstract TextComponent.Builder root();

    abstract Component build(HudRenderer.Run run);

    final TextComponent.Builder base(HudRenderer.Run run) {
        return Component.text()
                .content(run.text())
                .font(fonts.computeIfAbsent(run.font(), Key::key))
                .color(colors.computeIfAbsent(run.color(), TextColor::color));
    }

    /** 1.21.4 and newer: shadow off everywhere, back on only where an outline was asked for. */
    private static final class ShadowAware extends RunStyler {

        private static final net.kyori.adventure.text.format.ShadowColor OUTLINE =
                net.kyori.adventure.text.format.ShadowColor.shadowColor(0xFF000000);

        @Override
        TextComponent.Builder root() {
            return Component.text().shadowColor(net.kyori.adventure.text.format.ShadowColor.none());
        }

        @Override
        Component build(HudRenderer.Run run) {
            TextComponent.Builder piece = base(run);
            if (run.shadow()) {
                piece.shadowColor(OUTLINE);
            }
            return piece.build();
        }
    }

    /** Before 1.21.4: the shadow is whatever the client decides. */
    private static final class NoShadowControl extends RunStyler {

        @Override
        TextComponent.Builder root() {
            return Component.text();
        }

        @Override
        Component build(HudRenderer.Run run) {
            return base(run).build();
        }
    }
}
