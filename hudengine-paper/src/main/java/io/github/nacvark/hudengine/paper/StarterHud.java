package io.github.nacvark.hudengine.paper;

import io.github.nacvark.hudengine.core.util.EngineLogger;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Writes an example HUD into the plugin folder on first start.
 *
 * A fresh install with no configuration compiles nothing and leaves the screen empty, which is hard
 * to tell apart from a plugin that failed to load.
 *
 * Written only when the folder holds no HUD at all, so a server with a configuration of its own is
 * never touched again, including after an update.
 */
final class StarterHud {

    private static final String RESOURCE_ROOT = "default-hud";

    /** Written last: its presence is what marks the folder as already set up. */
    private static final String MARKER = "README.txt";

    private StarterHud() {
    }

    /**
     * Installs the starter HUD if the folder has no configuration yet.
     *
     * @return true if anything was written
     */
    static boolean installIfMissing(HudEnginePlugin plugin, Path dataFolder, PluginLogger log,
                                    Messages messages) {
        if (alreadyConfigured(dataFolder)) {
            return false;
        }
        try {
            List<String> files = listBundledFiles(plugin);
            if (files.isEmpty()) {
                log.warn(messages.plain("console.starter-empty"));
                return false;
            }
            for (String file : files) {
                copy(plugin, dataFolder, file);
            }
        } catch (IOException e) {
            log.error(messages.plain("console.starter-failed", "error", e.getMessage()), null);
            return false;
        }
        log.info(messages.plain("console.starter-written", "path", dataFolder));
        return true;
    }

    /**
     * Whether this folder already holds a HUD.
     *
     * Checked by looking for any HUD definition rather than for the marker file. A server that
     * deleted the example and wrote its own must not have it reappear, and one whose {@code huds/}
     * is empty has nothing to lose by getting one.
     */
    private static boolean alreadyConfigured(Path dataFolder) {
        Path huds = dataFolder.resolve("huds");
        if (!Files.isDirectory(huds)) {
            return false;
        }
        try (var entries = Files.list(huds)) {
            return entries.anyMatch(path -> path.getFileName().toString().endsWith(".yml"));
        } catch (IOException e) {
            // Unreadable is not empty; leave it alone rather than write over something.
            return true;
        }
    }

    /**
     * Every file of the starter HUD, read out of the plugin's own jar.
     *
     * The jar is listed rather than the names hardcoded. A fixed list goes stale as soon as an asset
     * is added: the config references a texture that never gets written, and the first compile on a
     * fresh install fails on a missing file.
     *
     * The marker is written last, so a half-finished copy is never mistaken for a complete one.
     */
    private static List<String> listBundledFiles(HudEnginePlugin plugin) throws IOException {
        List<String> files = new ArrayList<>();
        try (ZipFile jar = new ZipFile(plugin.jarFile().toFile())) {
            Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(RESOURCE_ROOT + "/")) {
                    continue;
                }
                String file = entry.getName().substring(RESOURCE_ROOT.length() + 1);
                if (!file.equals(MARKER)) {
                    files.add(file);
                }
            }
        }
        files.sort(String::compareTo);
        files.add(MARKER);
        return files;
    }

    private static void copy(Plugin plugin, Path dataFolder, String file) throws IOException {
        Path target = dataFolder.resolve(file);
        Files.createDirectories(target.getParent());

        try (InputStream in = plugin.getResource(RESOURCE_ROOT + "/" + file)) {
            if (in == null) {
                throw new IOException("missing from the plugin jar: " + file);
            }
            byte[] bytes = in.readAllBytes();
            if (file.endsWith(".yml") || file.endsWith(".txt")) {
                // Written with the platform's line endings so the file opens cleanly in whatever
                // editor the server owner reaches for, which on Windows is often Notepad.
                String text = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
                Files.writeString(target, text.replace("\n", System.lineSeparator()),
                        StandardCharsets.UTF_8);
            } else {
                Files.write(target, bytes);
            }
        }
    }
}
