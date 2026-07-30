package io.github.nacvark.hudengine.paper;

import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The 8x8 face a head element draws, taken from the player's own skin.
 *
 * {@link #facePixels(Player)} never blocks. Until the skin has been fetched it returns null and
 * the renderer simply leaves the head out, so a slow skin server delays one element rather than the
 * whole HUD. Any failure falls back to a built-in face, because a head that never appears looks like
 * a broken plugin.
 *
 * A player's pixel array is replaced wholesale, never mutated in place. The renderer relies on
 * that: it compares faces by identity to decide whether a cached block is still valid.
 */
public final class SkinFaces {

    private static final Pattern TEXTURE_URL = Pattern.compile("\"url\"\\s*:\\s*\"(http[^\"]+)\"");
    private static final int FACE_SIZE = 8;

    /** Skins are at least 64x32; anything smaller is not a skin. */
    private static final int MIN_SKIN_WIDTH = 64;
    private static final int MIN_SKIN_HEIGHT = 32;

    private final Plugin plugin;
    private final PluginLogger log;
    private final Messages messages;
    private final HttpClient http;
    private final boolean enabled;

    private final Map<UUID, int[]> faces = new ConcurrentHashMap<>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private volatile boolean noTexturesReported;

    SkinFaces(Plugin plugin, PluginLogger log, Messages messages, boolean enabled, Duration timeout) {
        this.plugin = plugin;
        this.log = log;
        this.messages = messages;
        this.enabled = enabled;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** The player's face, or null while it is still being fetched. */
    public int[] facePixels(Player player) {
        UUID id = player.getUniqueId();
        int[] cached = faces.get(id);
        if (cached != null) {
            return cached;
        }
        if (!enabled) {
            faces.put(id, FallbackFace.PIXELS);
            return FallbackFace.PIXELS;
        }
        if (inFlight.add(id)) {
            String url = textureUrl(player);
            if (url == null) {
                reportNoTextures();
                inFlight.remove(id);
                faces.put(id, FallbackFace.PIXELS);
                return FallbackFace.PIXELS;
            }
            Bukkit.getAsyncScheduler().runNow(plugin, task -> download(id, url));
        }
        return null;
    }

    /**
     * Said once, not once per player: a profile with no texture property means the server never
     * authenticated with Mojang, which is normal offline-mode behaviour and not a fault.
     */
    private void reportNoTextures() {
        if (noTexturesReported) {
            return;
        }
        noTexturesReported = true;
        log.info(messages.plain("console.skins-offline"));
    }

    public void forget(UUID id) {
        faces.remove(id);
        inFlight.remove(id);
    }

    void shutdown() {
        faces.clear();
        inFlight.clear();
        http.close();
    }

    private String textureUrl(Player player) {
        for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
            if (!"textures".equals(property.getName())) {
                continue;
            }
            String json = new String(Base64.getDecoder().decode(property.getValue()), StandardCharsets.UTF_8);
            Matcher matcher = TEXTURE_URL.matcher(json);
            if (matcher.find()) {
                return matcher.group(1).replaceFirst("^http://", "https://");
            }
        }
        return null;
    }

    private void download(UUID id, String url) {
        int[] face = FallbackFace.PIXELS;
        try {
            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                BufferedImage skin = ImageIO.read(new ByteArrayInputStream(response.body()));
                if (skin != null && skin.getWidth() >= MIN_SKIN_WIDTH && skin.getHeight() >= MIN_SKIN_HEIGHT) {
                    face = compose(skin);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            inFlight.remove(id);
            return;
        } catch (Exception e) {
            log.warn(messages.plain("console.skin-failed", "player", id, "error", e));
        }
        // Only cache if the player is still around. Without this check a player who left mid-download
        // would keep an entry until restart.
        if (inFlight.remove(id)) {
            faces.put(id, face);
        }
    }

    /** The face layer with the hat layer composited on top, opaque pixels only. */
    private static int[] compose(BufferedImage skin) {
        int[] out = new int[FACE_SIZE * FACE_SIZE];
        for (int y = 0; y < FACE_SIZE; y++) {
            for (int x = 0; x < FACE_SIZE; x++) {
                int base = skin.getRGB(8 + x, 8 + y) | 0xFF000000;
                int hat = skin.getRGB(40 + x, 8 + y);
                out[y * FACE_SIZE + x] = (hat >>> 24) >= 128 ? (hat | 0xFF000000) : base;
            }
        }
        return out;
    }

    /**
     * The face drawn when no skin can be had, which on an offline-mode server is every player.
     *
     * Laid out as a small pixel map rather than by index arithmetic, because the two eyes have to
     * be separated by a pixel of skin and getting that wrong is not obvious in code — it just shows
     * up in game as one wide band across the face.
     */
    private static final class FallbackFace {

        private static final int SKIN = 0xFFC58C6D;
        private static final int HAIR = 0xFF3B2A1A;
        private static final int EYE_WHITE = 0xFFFFFFFF;
        private static final int EYE_IRIS = 0xFF523CA4;
        private static final int MOUTH = 0xFF8A5C42;

        /** h = hair, . = skin, w = eye white, i = iris, m = mouth. */
        private static final String[] MAP = {
                "hhhhhhhh",
                "hhhhhhhh",
                "h......h",
                "........",
                ".wi..iw.",
                "........",
                "..mmmm..",
                "........"};

        private static final int[] PIXELS = build();

        private static int[] build() {
            int[] face = new int[FACE_SIZE * FACE_SIZE];
            for (int row = 0; row < FACE_SIZE; row++) {
                for (int col = 0; col < FACE_SIZE; col++) {
                    face[row * FACE_SIZE + col] = switch (MAP[row].charAt(col)) {
                        case 'h' -> HAIR;
                        case 'w' -> EYE_WHITE;
                        case 'i' -> EYE_IRIS;
                        case 'm' -> MOUTH;
                        default -> SKIN;
                    };
                }
            }
            return face;
        }
    }
}
