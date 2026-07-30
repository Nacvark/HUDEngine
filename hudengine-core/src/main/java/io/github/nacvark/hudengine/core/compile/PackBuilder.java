package io.github.nacvark.hudengine.core.compile;

import io.github.nacvark.hudengine.core.util.Json;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Accumulates the contents of a resource pack — font providers, textures and arbitrary files — and
 * writes them out as an unpacked tree, a zip, or both.
 */
public final class PackBuilder {

    private final String namespace;
    private final Map<String, List<Map<String, Object>>> fonts = new LinkedHashMap<>();

    /** Pack-relative path to file bytes. Sorted, so the zip is written in a stable order. */
    private final Map<String, byte[]> files = new TreeMap<>();

    public PackBuilder(String namespace) {
        this.namespace = namespace;
    }

    public String namespace() {
        return namespace;
    }

    /** Fully qualified font name for use in components: {@code namespace:name}. */
    public String fontId(String name) {
        return namespace + ":" + name;
    }

    public void addProvider(String fontName, Map<String, Object> provider) {
        fonts.computeIfAbsent(fontName, k -> new ArrayList<>()).add(provider);
    }

    /** Stores a texture and returns the {@code namespace:file.png} reference a provider needs. */
    public String addTexture(String fileName, BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            files.put("assets/" + namespace + "/textures/" + fileName, out.toByteArray());
            return namespace + ":" + fileName;
        } catch (IOException e) {
            throw new IllegalStateException("failed to encode PNG: " + fileName, e);
        }
    }

    public void addFile(String packPath, byte[] bytes) {
        files.put(packPath, bytes);
    }

    public void addFile(String packPath, String text) {
        files.put(packPath, text.getBytes(StandardCharsets.UTF_8));
    }

    public static Map<String, Object> bitmap(String file, int ascent, int height, List<String> charRows) {
        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("type", "bitmap");
        provider.put("file", file);
        provider.put("ascent", ascent);
        provider.put("height", height);
        provider.put("chars", charRows);
        return provider;
    }

    public static Map<String, Object> space(Map<String, Integer> advances) {
        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("type", "space");
        provider.put("advances", advances);
        return provider;
    }

    /**
     * Serialises the font definitions and writes the pack.
     *
     * Zip entries get a fixed timestamp so that identical configs produce a byte-identical
     * archive. That keeps the pack's SHA-1 stable across restarts, which is what stops every client
     * from re-downloading it.
     */
    public void write(Path outDir, Path outZip) throws IOException {
        for (Map.Entry<String, List<Map<String, Object>>> font : fonts.entrySet()) {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("providers", font.getValue());
            files.put("assets/" + namespace + "/font/" + font.getKey() + ".json",
                    Json.write(root).getBytes(StandardCharsets.UTF_8));
        }

        if (outDir != null) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                Path path = outDir.resolve(file.getKey());
                Files.createDirectories(path.getParent());
                Files.write(path, file.getValue());
            }
        }

        if (outZip != null) {
            Files.createDirectories(outZip.toAbsolutePath().getParent());
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(outZip))) {
                for (Map.Entry<String, byte[]> file : files.entrySet()) {
                    ZipEntry entry = new ZipEntry(file.getKey());
                    entry.setTime(0L);
                    zip.putNextEntry(entry);
                    zip.write(file.getValue());
                    zip.closeEntry();
                }
            }
        }
    }
}
