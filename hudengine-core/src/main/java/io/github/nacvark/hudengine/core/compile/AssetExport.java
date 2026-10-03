package io.github.nacvark.hudengine.core.compile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Writes the pack's assets into a folder another plugin merges into a pack of its own.
 *
 * The folder usually sits inside another plugin's data, next to that plugin's own content, so it is
 * never cleared. Each export records the files it wrote, and the next one removes those that are no
 * longer produced. A mistyped path leaves stray files behind and deletes nothing else.
 */
public final class AssetExport {

    /** Lists the files the previous export wrote, one relative path per line. */
    static final String MANIFEST = ".hudengine-export";

    public record Result(int written, int removed) {
    }

    private AssetExport() {
    }

    public static Result write(Path folder, Map<String, byte[]> files) throws IOException {
        Path root = folder.toAbsolutePath().normalize();
        Files.createDirectories(root);

        Set<String> previous = readManifest(root);
        int removed = 0;
        for (String stale : previous) {
            if (files.containsKey(stale)) {
                continue;
            }
            Path path = inside(root, stale);
            if (path != null && Files.deleteIfExists(path)) {
                removed++;
                pruneEmptyParents(root, path.getParent());
            }
        }

        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            Path path = inside(root, file.getKey());
            if (path == null) {
                throw new IOException("refusing to write outside the export folder: " + file.getKey());
            }
            Files.createDirectories(path.getParent());
            Files.write(path, file.getValue());
        }

        Files.writeString(root.resolve(MANIFEST), String.join("\n", files.keySet()) + "\n",
                StandardCharsets.UTF_8);
        return new Result(files.size(), removed);
    }

    private static Set<String> readManifest(Path root) throws IOException {
        Path manifest = root.resolve(MANIFEST);
        if (!Files.isRegularFile(manifest)) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                out.add(line.strip());
            }
        }
        return out;
    }

    /**
     * Resolves a recorded path, or null if it would land outside the folder.
     *
     * The manifest is a plain file anyone can edit, so a line like {@code ../../server.properties} is
     * refused rather than trusted.
     */
    private static Path inside(Path root, String relative) {
        Path path = root.resolve(relative).normalize();
        return path.startsWith(root) && !path.equals(root) ? path : null;
    }

    /** Removes directories the export emptied, stopping at the folder itself. */
    private static void pruneEmptyParents(Path root, Path dir) throws IOException {
        while (dir != null && dir.startsWith(root) && !dir.equals(root) && isEmpty(dir)) {
            Files.delete(dir);
            dir = dir.getParent();
        }
    }

    private static boolean isEmpty(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            return !entries.iterator().hasNext();
        }
    }
}
