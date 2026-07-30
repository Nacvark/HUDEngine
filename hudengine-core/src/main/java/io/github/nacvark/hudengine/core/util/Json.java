package io.github.nacvark.hudengine.core.util;

import java.util.List;
import java.util.Map;

/**
 * Minimal JSON writer for the files that go into the resource pack.
 *
 * Accepts a tree of {@code Map}, {@code List}, {@code String}, {@code Number}, {@code Boolean}
 * and {@code null}. Non-ASCII is escaped to {@code \\uXXXX} exactly as vanilla packs do, which keeps
 * the output diffable and independent of the reader's encoding.
 *
 * Written by hand so the core stays dependency-free; the pack format needs nothing a real JSON
 * library would provide.
 */
public final class Json {

    private Json() {
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        switch (v) {
            case null -> sb.append("null");
            case String s -> writeString(sb, s);
            case Boolean b -> sb.append(b ? "true" : "false");
            case Double d -> writeDouble(sb, d);
            case Float f -> writeDouble(sb, f.doubleValue());
            case Number n -> sb.append(n);
            case Map<?, ?> m -> writeObject(sb, m);
            case List<?> l -> writeArray(sb, l);
            default -> throw new IllegalArgumentException(
                    "Json: unsupported type " + v.getClass().getName());
        }
    }

    private static void writeDouble(StringBuilder sb, double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            sb.append((long) d); // 5.0 -> 5, matching vanilla
        } else {
            sb.append(d);
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> m) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> l) {
        sb.append('[');
        boolean first = true;
        for (Object o : l) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(sb, o);
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7E) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
