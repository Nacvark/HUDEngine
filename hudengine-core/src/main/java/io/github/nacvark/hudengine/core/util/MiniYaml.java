package io.github.nacvark.hudengine.core.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for the subset of YAML the HUD configs use: block maps, block lists, scalars, single and
 * double quotes, {@code #} comments, and indentation by spaces.
 *
 * Anchors, flow style and multi-line literals are deliberately unsupported — none of them appear
 * in HUD configs, and leaving them out keeps the core free of a YAML dependency. Hosts that already
 * have a parser can bypass this class entirely via {@link ConfigNode#fromRaw(Object)}.
 */
public final class MiniYaml {

    private MiniYaml() {
    }

    private record Line(int indent, String content, int number) {
    }

    public static ConfigNode parse(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8), file.getFileName().toString());
    }

    public static ConfigNode parse(String text, String sourceName) {
        List<Line> lines = new ArrayList<>();
        int number = 0;
        for (String raw : text.split("\n", -1)) {
            number++;
            String noComment = stripComment(raw.replace("\r", ""));
            String trimmed = noComment.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (noComment.contains("\t")) {
                throw new IllegalArgumentException(
                        sourceName + ":" + number + ": tabs are not allowed in indentation");
            }
            int indent = 0;
            while (indent < noComment.length() && noComment.charAt(indent) == ' ') {
                indent++;
            }
            lines.add(new Line(indent, trimmed, number));
        }

        int[] pos = {0};
        ConfigNode node = parseBlock(lines, pos, lines.isEmpty() ? 0 : lines.getFirst().indent, sourceName);
        if (pos[0] < lines.size()) {
            Line line = lines.get(pos[0]);
            throw new IllegalArgumentException(sourceName + ":" + line.number + ": unexpected indentation");
        }
        return node;
    }

    private static ConfigNode parseBlock(List<Line> lines, int[] pos, int indent, String source) {
        if (pos[0] >= lines.size()) {
            return ConfigNode.ofMap(new LinkedHashMap<>());
        }
        String first = lines.get(pos[0]).content;
        boolean isList = first.startsWith("- ") || first.equals("-");
        return isList ? parseList(lines, pos, indent, source) : parseMap(lines, pos, indent, source);
    }

    private static ConfigNode parseMap(List<Line> lines, int[] pos, int indent, String source) {
        Map<String, ConfigNode> map = new LinkedHashMap<>();
        while (pos[0] < lines.size()) {
            Line line = lines.get(pos[0]);
            if (line.indent < indent) {
                break;
            }
            if (line.indent > indent) {
                throw new IllegalArgumentException(source + ":" + line.number + ": unexpected extra indentation");
            }
            int colon = findColon(line.content);
            if (colon < 0) {
                throw new IllegalArgumentException(source + ":" + line.number + ": expected 'key: value'");
            }
            String key = unquote(line.content.substring(0, colon).strip());
            String rest = line.content.substring(colon + 1).strip();
            pos[0]++;

            if (!rest.isEmpty()) {
                map.put(key, scalar(rest));
            } else if (pos[0] < lines.size() && lines.get(pos[0]).indent > indent) {
                map.put(key, parseBlock(lines, pos, lines.get(pos[0]).indent, source));
            } else {
                map.put(key, ConfigNode.ofScalar(null)); // key with an empty body
            }
        }
        return ConfigNode.ofMap(map);
    }

    private static ConfigNode parseList(List<Line> lines, int[] pos, int indent, String source) {
        List<ConfigNode> list = new ArrayList<>();
        while (pos[0] < lines.size()) {
            Line line = lines.get(pos[0]);
            boolean isItem = line.content.startsWith("- ") || line.content.equals("-");
            if (line.indent != indent || !isItem) {
                break;
            }
            String rest = line.content.equals("-") ? "" : line.content.substring(2).strip();
            pos[0]++;

            if (rest.isEmpty()) {
                if (pos[0] < lines.size() && lines.get(pos[0]).indent > indent) {
                    list.add(parseBlock(lines, pos, lines.get(pos[0]).indent, source));
                } else {
                    list.add(ConfigNode.ofScalar(null));
                }
            } else if (findColon(rest) >= 0 && !isQuoted(rest)) {
                list.add(parseInlineMapItem(lines, pos, indent, source, rest));
            } else {
                list.add(scalar(rest));
            }
        }
        return ConfigNode.ofList(list);
    }

    /** A list item whose map starts on the dash line: {@code - key: value}. */
    private static ConfigNode parseInlineMapItem(List<Line> lines, int[] pos, int indent,
                                                 String source, String rest) {
        int colon = findColon(rest);
        Map<String, ConfigNode> map = new LinkedHashMap<>();
        map.put(unquote(rest.substring(0, colon).strip()), scalar(rest.substring(colon + 1).strip()));
        int childIndent = indent + 2;
        while (pos[0] < lines.size() && lines.get(pos[0]).indent >= childIndent) {
            map.putAll(parseMap(lines, pos, lines.get(pos[0]).indent, source).asMap());
        }
        return ConfigNode.ofMap(map);
    }

    private static ConfigNode scalar(String s) {
        return ConfigNode.ofScalar(unquote(s));
    }

    /** The separating colon: followed by a space or end of line, and outside quotes. */
    private static int findColon(String s) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == ':' && !inSingle && !inDouble
                    && (i + 1 >= s.length() || s.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private static String stripComment(String s) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble && (i == 0 || s.charAt(i - 1) == ' ')) {
                return s.substring(0, i);
            }
        }
        return s;
    }

    private static boolean isQuoted(String s) {
        return s.length() >= 2
                && ((s.charAt(0) == '"' && s.endsWith("\"")) || (s.charAt(0) == '\'' && s.endsWith("'")));
    }

    private static String unquote(String s) {
        if (!isQuoted(s)) {
            return s;
        }
        String inner = s.substring(1, s.length() - 1);
        return s.charAt(0) == '\''
                ? inner.replace("''", "'")
                : inner.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
