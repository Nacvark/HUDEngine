package io.github.nacvark.hudengine.paper;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Values every HUD can draw without PlaceholderAPI or any other plugin.
 *
 * The point is that a HUD showing health, hunger, armour and coordinates — which is most of them
 * — should work on a bare server. PlaceholderAPI is then for what only another plugin knows.
 *
 * Both the bare name and the {@code player_} prefixed one are accepted, because the prefixed
 * spelling is PlaceholderAPI's and configs get written both ways.
 */
final class BuiltInValues {

    private BuiltInValues() {
    }

    /** Ticks in a Minecraft day, used to turn world time into a clock. */
    private static final int DAY_TICKS = 24000;
    private static final int TICKS_PER_HOUR = 1000;

    /** Minecraft's day starts at 06:00, not midnight. */
    private static final int DAWN_HOUR = 6;

    private static final String[] COMPASS_16 = {
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};

    /** Every key this resolves, for documentation and for the wiki. */
    static List<String> keys() {
        return List.of(
                "name", "uuid", "world", "gamemode", "ping",
                "x", "y", "z", "x_exact", "y_exact", "z_exact",
                "yaw", "pitch", "direction", "direction_short",
                "health", "health_rounded", "max_health", "health_percent", "absorption",
                "armor", "armor_toughness", "attack_damage", "attack_speed", "movement_speed",
                "food", "saturation", "exhaustion",
                "air", "max_air", "air_percent",
                "level", "xp_percent", "total_experience",
                "world_time", "clock", "is_day", "is_raining", "is_thundering",
                "on_ground", "is_sneaking", "is_sprinting", "is_flying", "is_swimming");
    }

    /**
     * Resolves a key, or returns null when it is not a built-in one.
     *
     * Null rather than empty matters: an unknown key has to fall through to the registry and to
     * PlaceholderAPI, and swallowing it here would make every other source unreachable.
     */
    @SuppressWarnings("deprecation") // getMaxHealth: see below
    static String resolve(Player player, String key) {
        String name = key.startsWith("player_") ? key.substring("player_".length()) : key;

        return switch (name) {
            case "name", "displayname" -> player.getName();
            case "uuid" -> player.getUniqueId().toString();
            case "world" -> player.getWorld().getName();
            case "gamemode" -> player.getGameMode().name().toLowerCase(Locale.ROOT);
            case "ping" -> String.valueOf(player.getPing());

            case "x" -> String.valueOf(player.getLocation().getBlockX());
            case "y" -> String.valueOf(player.getLocation().getBlockY());
            case "z" -> String.valueOf(player.getLocation().getBlockZ());
            case "x_exact" -> oneDecimal(player.getLocation().getX());
            case "y_exact" -> oneDecimal(player.getLocation().getY());
            case "z_exact" -> oneDecimal(player.getLocation().getZ());
            case "yaw" -> oneDecimal(player.getLocation().getYaw());
            case "pitch" -> oneDecimal(player.getLocation().getPitch());
            case "direction" -> direction(player.getLocation().getYaw(), false);
            case "direction_short" -> direction(player.getLocation().getYaw(), true);

            // getMaxHealth is deprecated in favour of the attribute API, but its replacement's
            // constant was renamed across the versions supported here while this has not changed.
            case "health" -> String.valueOf((int) Math.ceil(player.getHealth()));
            case "health_rounded" -> String.valueOf(Math.round(player.getHealth()));
            case "max_health" -> String.valueOf((int) Math.ceil(player.getMaxHealth()));
            case "health_percent" -> percent(player.getHealth(), player.getMaxHealth());
            case "absorption" -> String.valueOf((int) Math.ceil(player.getAbsorptionAmount()));

            case "armor" -> String.valueOf((int) Math.round(attribute(player, "armor")));
            case "armor_toughness" -> String.valueOf((int) Math.round(attribute(player, "armor_toughness")));
            case "attack_damage" -> oneDecimal(attribute(player, "attack_damage"));
            case "attack_speed" -> oneDecimal(attribute(player, "attack_speed"));
            case "movement_speed" -> oneDecimal(attribute(player, "movement_speed"));

            case "food" -> String.valueOf(player.getFoodLevel());
            case "saturation" -> String.valueOf((int) Math.floor(player.getSaturation()));
            case "exhaustion" -> oneDecimal(player.getExhaustion());

            case "air" -> String.valueOf(Math.max(0, player.getRemainingAir()) / 20);
            case "max_air" -> String.valueOf(player.getMaximumAir() / 20);
            case "air_percent" -> percent(Math.max(0, player.getRemainingAir()), player.getMaximumAir());

            case "level" -> String.valueOf(player.getLevel());
            case "xp_percent" -> String.valueOf(Math.round(player.getExp() * 100));
            case "total_experience" -> String.valueOf(player.getTotalExperience());

            case "world_time" -> String.valueOf(player.getWorld().getTime());
            case "clock" -> clock(player.getWorld().getTime());
            case "is_day" -> bool(player.getWorld().isDayTime());
            case "is_raining" -> bool(player.getWorld().hasStorm());
            case "is_thundering" -> bool(player.getWorld().isThundering());

            case "on_ground" -> bool(player.isOnGround());
            case "is_sneaking" -> bool(player.isSneaking());
            case "is_sprinting" -> bool(player.isSprinting());
            case "is_flying" -> bool(player.isFlying());
            case "is_swimming" -> bool(player.isSwimming());

            default -> null;
        };
    }

    /**
     * Attributes resolved once, not on every read.
     *
     * Looking one up means building a {@link NamespacedKey} and going through the registry. That
     * is cheap in isolation and not cheap at all when a HUD showing armour does it for every player
     * five times a second, forever. The registry never changes at runtime, so once is enough.
     */
    private static final Map<String, Attribute> ATTRIBUTES = new ConcurrentHashMap<>();

    /**
     * Reads an attribute by its namespaced key rather than its enum constant.
     *
     * The constants were renamed — {@code GENERIC_ARMOR} became {@code ARMOR} — but the key
     * {@code minecraft:armor} did not, so this keeps working across every supported version.
     */
    private static double attribute(Player player, String key) {
        Attribute attribute = ATTRIBUTES.computeIfAbsent(key, k -> {
            try {
                return Registry.ATTRIBUTE.get(NamespacedKey.minecraft(k));
            } catch (RuntimeException e) {
                return null;
            }
        });
        if (attribute == null) {
            return 0;
        }
        AttributeInstance instance = player.getAttribute(attribute);
        return instance == null ? 0 : instance.getValue();
    }

    /** Sixteen-point compass. Yaw 0 faces south in Minecraft, hence the offset. */
    private static String direction(float yaw, boolean shortForm) {
        double normalized = (yaw % 360 + 360) % 360;
        int index = (int) Math.round(normalized / 22.5) % 16;
        String cardinal = COMPASS_16[(index + 8) % 16];
        return shortForm ? cardinal.substring(0, 1) : cardinal;
    }

    /** In-game time of day as {@code HH:MM}. */
    private static String clock(long worldTime) {
        long ticks = ((worldTime % DAY_TICKS) + DAY_TICKS) % DAY_TICKS;
        long hours = (ticks / TICKS_PER_HOUR + DAWN_HOUR) % 24;
        long minutes = (ticks % TICKS_PER_HOUR) * 60 / TICKS_PER_HOUR;
        return String.format("%02d:%02d", hours, minutes);
    }

    private static String percent(double value, double max) {
        return max <= 0 ? "0" : String.valueOf(Math.round(value / max * 100));
    }

    private static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /**
     * Booleans come out as {@code 1} and {@code 0} on purpose. That is what an image
     * {@code condition} treats as truthy, so a value can drive both a label and a sprite.
     */
    private static String bool(boolean value) {
        return value ? "1" : "0";
    }
}
