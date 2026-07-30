package io.github.nacvark.hudengine.core.compile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Supplies the vanilla glyph sheets and their metrics table.
 *
 * Text is rasterised from the client's own bitmap glyphs first so that digits, Latin and Cyrillic
 * look exactly like the font the player already sees; a TTF only fills in what vanilla lacks. That
 * data has to come from somewhere, and where depends on the host: the plugin ships it inside its jar
 * but lets a folder on disk override it, while the CLI and tests read a folder directly.
 */
public interface VanillaGlyphSource {

    /** File contents, or {@code null} if this source does not have it. Paths use {@code /}. */
    byte[] read(String path);

    /** Names of the files directly inside a directory, sorted. Empty if the directory is absent. */
    List<String> list(String directory);

    /** Where the bundled copy lives inside the jar. */
    String BUNDLED_PREFIX = "hudengine/vanilla";

    /**
     * The copy shipped inside the engine, so a server gets vanilla-matching text with no setup.
     *
     * Layer it under {@link #ofDirectory} to let a folder on disk override individual files.
     */
    static VanillaGlyphSource bundled() {
        ClassLoader loader = VanillaGlyphSource.class.getClassLoader();
        List<String> index = List.of();
        try (InputStream in = loader.getResourceAsStream(BUNDLED_PREFIX + "/index.txt")) {
            if (in != null) {
                index = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                        .lines()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty())
                        .toList();
            }
        } catch (IOException ignored) {
            // leaves the index empty; reads still work, only listing is unavailable
        }
        return ofClasspath(loader, BUNDLED_PREFIX, index);
    }

    /** Reads from a directory on disk. */
    static VanillaGlyphSource ofDirectory(Path root) {
        return new VanillaGlyphSource() {
            @Override
            public byte[] read(String path) {
                Path file = root.resolve(path);
                try {
                    return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
                } catch (IOException e) {
                    return null;
                }
            }

            @Override
            public List<String> list(String directory) {
                Path dir = root.resolve(directory);
                if (!Files.isDirectory(dir)) {
                    return List.of();
                }
                try (Stream<Path> stream = Files.list(dir)) {
                    return stream.filter(Files::isRegularFile)
                            .map(p -> p.getFileName().toString())
                            .sorted()
                            .toList();
                } catch (IOException e) {
                    return List.of();
                }
            }
        };
    }

    /**
     * Reads from the classpath under {@code prefix}, e.g. resources bundled in the plugin jar.
     *
     * Directory listing is driven by {@code index}, a manifest of relative paths, because a jar
     * offers no reliable way to enumerate a directory through a classloader.
     */
    static VanillaGlyphSource ofClasspath(ClassLoader loader, String prefix, List<String> index) {
        String base = prefix.endsWith("/") ? prefix : prefix + "/";
        return new VanillaGlyphSource() {
            @Override
            public byte[] read(String path) {
                try (InputStream in = loader.getResourceAsStream(base + path)) {
                    return in == null ? null : in.readAllBytes();
                } catch (IOException e) {
                    return null;
                }
            }

            @Override
            public List<String> list(String directory) {
                String dir = directory.isEmpty() ? "" : directory + "/";
                Set<String> names = new LinkedHashSet<>();
                for (String entry : index) {
                    if (!entry.startsWith(dir)) {
                        continue;
                    }
                    String rest = entry.substring(dir.length());
                    if (!rest.isEmpty() && rest.indexOf('/') < 0) {
                        names.add(rest);
                    }
                }
                List<String> sorted = new ArrayList<>(names);
                sorted.sort(String::compareTo);
                return sorted;
            }
        };
    }

    /** Tries each source in order; the first that has a file wins. Listings are merged. */
    static VanillaGlyphSource layered(VanillaGlyphSource... sources) {
        List<VanillaGlyphSource> chain = List.of(sources);
        return new VanillaGlyphSource() {
            @Override
            public byte[] read(String path) {
                for (VanillaGlyphSource source : chain) {
                    byte[] bytes = source.read(path);
                    if (bytes != null) {
                        return bytes;
                    }
                }
                return null;
            }

            @Override
            public List<String> list(String directory) {
                Set<String> names = new LinkedHashSet<>();
                for (VanillaGlyphSource source : chain) {
                    names.addAll(source.list(directory));
                }
                List<String> sorted = new ArrayList<>(names);
                sorted.sort(String::compareTo);
                return sorted;
            }
        };
    }
}
