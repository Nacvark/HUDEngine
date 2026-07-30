package io.github.nacvark.hudengine.core.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A configuration node: a section, a list, or a scalar.
 *
 * The same tree is produced either by the built-in {@link MiniYaml} parser or by
 * {@link #fromRaw(Object)} from whatever the host platform's config library returns, so the core
 * never has to care which one loaded the file.
 */
public final class ConfigNode {

    /** {@code Map<String, ConfigNode>}, {@code List<ConfigNode>}, {@code String}, or {@code null}. */
    private final Object value;

    private ConfigNode(Object value) {
        this.value = value;
    }

    public static ConfigNode ofMap(Map<String, ConfigNode> map) {
        return new ConfigNode(map);
    }

    public static ConfigNode ofList(List<ConfigNode> list) {
        return new ConfigNode(list);
    }

    public static ConfigNode ofScalar(String scalar) {
        return new ConfigNode(scalar);
    }

    public static ConfigNode empty() {
        return new ConfigNode(null);
    }

    /** Builds a tree from plain maps, lists and scalars, e.g. a SnakeYAML result. */
    public static ConfigNode fromRaw(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, ConfigNode> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), fromRaw(e.getValue()));
            }
            return ofMap(out);
        }
        if (raw instanceof List<?> list) {
            return ofList(list.stream().map(ConfigNode::fromRaw).toList());
        }
        return ofScalar(raw == null ? null : String.valueOf(raw));
    }

    public boolean isMap() {
        return value instanceof Map;
    }

    public boolean isList() {
        return value instanceof List;
    }

    @SuppressWarnings("unchecked")
    public Map<String, ConfigNode> asMap() {
        return isMap() ? (Map<String, ConfigNode>) value : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    public List<ConfigNode> asList() {
        return isList() ? (List<ConfigNode>) value : Collections.emptyList();
    }

    /** This node's children flattened to {@code key -> scalar}. */
    public LinkedHashMap<String, String> asFlatStringMap() {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        asMap().forEach((k, v) -> out.put(k, v.asString(null)));
        return out;
    }

    /** Never null: a missing key yields an empty node, so lookups can be chained. */
    public ConfigNode child(String key) {
        ConfigNode node = asMap().get(key);
        return node != null ? node : empty();
    }

    public boolean has(String key) {
        return asMap().containsKey(key);
    }

    public String asString(String fallback) {
        return value instanceof String s ? s : fallback;
    }

    public int asInt(int fallback) {
        try {
            return value instanceof String s ? (int) Math.round(Double.parseDouble(s)) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public double asDouble(double fallback) {
        try {
            return value instanceof String s ? Double.parseDouble(s) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean asBool(boolean fallback) {
        return value instanceof String s ? s.equalsIgnoreCase("true") : fallback;
    }

    public String str(String key, String fallback) {
        return child(key).asString(fallback);
    }

    public int integer(String key, int fallback) {
        return child(key).asInt(fallback);
    }

    public double dbl(String key, double fallback) {
        return child(key).asDouble(fallback);
    }

    public boolean bool(String key, boolean fallback) {
        return child(key).asBool(fallback);
    }
}
