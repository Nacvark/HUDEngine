package io.github.nacvark.hudengine.core;

import io.github.nacvark.hudengine.core.compile.VanillaHud;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaHudTest {

    @ParameterizedTest
    @EnumSource(VanillaHud.class)
    void everyElementNamesRealSpritePaths(VanillaHud element) {
        if (element != VanillaHud.LEVEL_TEXT) {
            assertFalse(element.sprites().isEmpty(), element + " hides nothing");
        }
        for (String sprite : element.sprites()) {
            assertTrue(sprite.startsWith("hud/"), sprite + " is not under hud/");
            assertTrue(sprite.endsWith(".png"), sprite + " is not a texture");
        }
    }

    @Test
    void onlyTheLevelNumberIsHiddenWithoutASprite() {
        // It is text, not a texture, so the shader hides it and there is nothing to replace. Any
        // other spriteless element would be one that silently does nothing when switched on.
        for (VanillaHud element : VanillaHud.values()) {
            if (element.sprites().isEmpty()) {
                assertEquals(VanillaHud.LEVEL_TEXT, element,
                        element + " has no sprites, so turning it on would do nothing");
            }
        }
    }

    @Test
    void noSpriteIsClaimedByTwoElements() {
        Set<String> seen = new HashSet<>();
        for (VanillaHud element : VanillaHud.values()) {
            for (String sprite : element.sprites()) {
                assertTrue(seen.add(sprite),
                        sprite + " is listed by more than one element, so hiding one hides the other");
            }
        }
    }

    @Test
    void heartsCoverEveryState() {
        List<String> hearts = VanillaHud.HEALTH.sprites();

        // Missing one variant means hearts reappear the moment a player is poisoned or takes
        // damage, which looks worse than not hiding them at all.
        for (String state : List.of("", "poisoned_", "withered_", "frozen_", "absorbing_")) {
            for (String fill : List.of("full", "half")) {
                assertTrue(hearts.contains("hud/heart/" + state + fill + ".png"),
                        "missing " + state + fill);
                assertTrue(hearts.contains("hud/heart/" + state + fill + "_blinking.png"),
                        "missing blinking " + state + fill);
                assertTrue(hearts.contains("hud/heart/" + state + "hardcore_" + fill + ".png"),
                        "missing hardcore " + state + fill);
            }
        }
        assertTrue(hearts.contains("hud/heart/container.png"));
        assertTrue(hearts.contains("hud/heart/vehicle_full.png"), "riding a horse shows vehicle hearts");
    }

    @Test
    void configNamesRoundTrip() {
        for (VanillaHud element : VanillaHud.values()) {
            assertEquals(element, VanillaHud.byConfigName(element.configName()));
            // Config keys are read as written, so a stray capital must not silently miss.
            assertEquals(element, VanillaHud.byConfigName(element.configName().toUpperCase()));
        }
        assertEquals(VanillaHud.values().length, VanillaHud.configNames().size());
    }

    @Test
    void anUnknownNameIsRejectedRatherThanGuessed() {
        assertNull(VanillaHud.byConfigName("hearts"), "reporting a typo beats silently doing nothing");
        assertNull(VanillaHud.byConfigName(""));
    }
}
