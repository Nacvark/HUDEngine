package io.github.nacvark.hudengine.api;

import java.util.List;

/**
 * One player's view of the HUD.
 *
 * <p>There are two independent switches. {@link #setVisible(boolean)} is the master one and hides
 * everything; {@link #show(String)} and {@link #hide(String)} pick which HUDs are in the player's
 * set. A player whose master switch is off sees nothing regardless of their set.
 *
 * <p>Both survive a reconnect. A player who turned their HUD off does not expect it back the moment
 * they log in again.
 */
public interface PlayerHuds {

    /** Whether the player is currently being shown a HUD at all. */
    boolean isVisible();

    /** The master switch. */
    void setVisible(boolean visible);

    /**
     * Flips the master switch.
     *
     * @return true if the HUD is now shown
     */
    boolean toggle();

    /**
     * Adds a HUD to this player's set, including one outside the configured defaults.
     *
     * @return false if no such HUD was compiled
     */
    boolean show(String hudKey);

    /**
     * Removes a HUD from this player's set.
     *
     * @return false if no such HUD was compiled
     */
    boolean hide(String hudKey);

    /**
     * Shows a HUD for a fixed number of ticks, then hides it again. Calling this again before the
     * timer expires extends it rather than stacking.
     *
     * <p>For something whose duration is not known up front, such as a dialogue, use {@link #show}
     * and {@link #hide} directly.
     *
     * @return false if no such HUD was compiled
     */
    boolean showFor(String hudKey, int ticks);

    /** Returns the player to the configured default set. */
    void reset();

    /** This player's HUDs, in compile order. Empty when the master switch is off. */
    List<String> visible();

    /** Recomputes and sends now instead of waiting for the next tick. */
    void refresh();
}
