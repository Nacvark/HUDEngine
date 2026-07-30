package io.github.nacvark.hudengine.core.compile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Vanilla HUD elements a pack can blank out.
 *
 * A custom HUD usually draws its own health and hunger, and the vanilla ones underneath turn the
 * screen into two interfaces stacked on top of each other. There is no setting that switches them
 * off, so the pack replaces their sprites with transparent ones — the same trick that already hides
 * the boss bar the HUD rides on.
 *
 * The replacement texture's size does not have to match the original. None of these sprites carry
 * nine-slice metadata, and a fully transparent texture draws nothing whatever size it is stretched
 * to, so one small texture serves every path and no per-version size table has to be maintained.
 *
 * The paths themselves were checked against 1.21.4 and 26.1 and are identical in both.
 */
public enum VanillaHud {

    HEALTH("health", hearts()),

    FOOD("food",
            "hud/food_empty.png",
            "hud/food_full.png",
            "hud/food_half.png",
            "hud/food_empty_hunger.png",
            "hud/food_full_hunger.png",
            "hud/food_half_hunger.png"),

    ARMOR("armor",
            "hud/armor_empty.png",
            "hud/armor_full.png",
            "hud/armor_half.png"),

    AIR("air",
            "hud/air.png",
            "hud/air_bursting.png",
            "hud/air_empty.png"),

    EXPERIENCE_BAR("experience-bar",
            "hud/experience_bar_background.png",
            "hud/experience_bar_progress.png"),

    HOTBAR("hotbar",
            "hud/hotbar.png",
            "hud/hotbar_selection.png",
            "hud/hotbar_offhand_left.png",
            "hud/hotbar_offhand_right.png",
            "hud/hotbar_attack_indicator_background.png",
            "hud/hotbar_attack_indicator_progress.png"),

    CROSSHAIR("crosshair",
            "hud/crosshair.png",
            "hud/crosshair_attack_indicator_background.png",
            "hud/crosshair_attack_indicator_full.png",
            "hud/crosshair_attack_indicator_progress.png"),

    /** The status effect frames in the top right corner. */
    EFFECTS("effects",
            "hud/effect_background.png",
            "hud/effect_background_ambient.png"),

    /** The horse jump charge bar, which replaces the experience bar while riding. */
    JUMP_BAR("jump-bar",
            "hud/jump_bar_background.png",
            "hud/jump_bar_cooldown.png",
            "hud/jump_bar_progress.png"),

    /**
     * The experience level number above the hotbar.
     *
     * Text rather than a sprite, so there is nothing to replace: the shader hides it instead, by
     * recognising the level colour inside a band across the middle of the screen. Listed here anyway,
     * so that one config section covers every vanilla element the engine can hide.
     */
    LEVEL_TEXT("level-text");

    private final String configName;
    private final List<String> sprites;

    VanillaHud(String configName, String... sprites) {
        this(configName, List.of(sprites));
    }

    VanillaHud(String configName, List<String> sprites) {
        this.configName = configName;
        this.sprites = List.copyOf(sprites);
    }

    /** The key this element is switched on by in the config. */
    public String configName() {
        return configName;
    }

    /** Sprite paths relative to {@code assets/minecraft/textures/gui/sprites/}. */
    public List<String> sprites() {
        return sprites;
    }

    /** Looks up an element by its config name, or null if there is no such element. */
    public static VanillaHud byConfigName(String name) {
        String wanted = name.strip().toLowerCase(Locale.ROOT);
        for (VanillaHud element : values()) {
            if (element.configName.equals(wanted)) {
                return element;
            }
        }
        return null;
    }

    public static List<String> configNames() {
        List<String> names = new ArrayList<>(values().length);
        for (VanillaHud element : values()) {
            names.add(element.configName);
        }
        return names;
    }

    /**
     * Every heart sprite there is.
     *
     * Hearts come in a variant per state — poisoned, withered, frozen, absorbing, hardcore, and a
     * blinking version of each — and missing one means it reappears the moment a player is poisoned
     * or takes damage, which is worse than not hiding hearts at all.
     */
    private static List<String> hearts() {
        List<String> paths = new ArrayList<>();
        for (String state : List.of("", "absorbing_", "poisoned_", "withered_", "frozen_")) {
            for (String mode : List.of("", "hardcore_")) {
                for (String fill : List.of("full", "half")) {
                    paths.add("hud/heart/" + state + mode + fill + ".png");
                    paths.add("hud/heart/" + state + mode + fill + "_blinking.png");
                }
            }
        }
        for (String container : List.of("container", "container_blinking",
                "container_hardcore", "container_hardcore_blinking",
                "vehicle_container", "vehicle_full", "vehicle_half")) {
            paths.add("hud/heart/" + container + ".png");
        }
        return paths;
    }
}
