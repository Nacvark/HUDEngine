package io.github.nacvark.hudengine.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.List;

/**
 * Fired after the configuration has been recompiled and the new model is live.
 *
 * <p>Registered values and compass providers survive a reload untouched, so most plugins do not need
 * this. It matters when you hold something derived from the compiled model — an element key for
 * {@link io.github.nacvark.hudengine.api.HudMetrics}, say — because a reload can renumber those.
 *
 * <p>Not fired when a reload fails; the previous model stays live in that case.
 */
public final class HudsReloadedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final List<String> huds;

    public HudsReloadedEvent(List<String> huds) {
        this.huds = List.copyOf(huds);
    }

    /** The HUDs the new model compiled, in compile order. */
    public List<String> huds() {
        return huds;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
