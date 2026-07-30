package io.github.nacvark.hudengine.core.runtime;

import io.github.nacvark.hudengine.core.model.Compiled;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Assembles the boss bar title from a compiled HUD.
 *
 * The output is a list of {@link Run}s — font, text, colour, shadow — which a platform layer maps
 * one to one onto its own component type. Nothing here knows about Bukkit or Adventure, so the whole
 * renderer runs in a plain unit test.
 *
 * Two invariants hold for every string this produces:
 *
 * - Total advance is zero. The client centres the boss bar title, so a zero-width string
 *   starts exactly at screen centre, which is the origin the shader expects.
 * - Shadow is off except where an outline was asked for. The client's native +1/+1 text
 *   shadow is what draws the outline; leaving it on elsewhere would draw images and head pixels
 *   twice.
 */
public final class HudRenderer {

    /** Resolves a placeholder key to its current value for one player. */
    public interface ValueResolver {

        String text(String key);

        /**
         * The key's value as a number.
         *
         * Falls back to reading the key itself when nothing resolves it, because a bar's maximum
         * is often a plain number — {@code max: "20"} for hunger — and there is no value anywhere
         * called "20". Without this such a bar silently reads as zero and never draws, which looks
         * like a broken sprite rather than a misread config.
         */
        default double number(String key) {
            Double resolved = parseNumber(text(key));
            if (resolved != null) {
                return resolved;
            }
            Double literal = parseNumber(key);
            return literal != null ? literal : 0;
        }

        private static Double parseNumber(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Double.valueOf(value.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** Supplies a player's face as 8x8 ARGB, or null while it is still loading. */
    public interface SkinProvider {
        int[] facePixels(String headKey);
    }

    /** A point on the compass: its offset from the player and an icon key, or null for the default. */
    public record CompassPointView(double dx, double dz, String icon) {
    }

    /** Compass input for one tick. */
    public record CompassCtx(double yaw, List<CompassPointView> points) {
    }

    /** Supplies compass context. A null context hides every compass. */
    public interface CompassSource {
        CompassCtx ctx();
    }

    /** One homogeneous piece of the string. */
    public record Run(String font, String text, int color, boolean shadow) {
    }

    /**
     * The assembled string.
     *
     * @param changed false means byte-for-byte identical to the previous tick, so no packet is needed
     */
    public record Output(List<Run> runs, Set<Integer> missingChars, boolean changed) {
    }

    /** Legacy {@code §0}-{@code §f} colours. */
    private static final int[] LEGACY_COLORS = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA,
            0xFFAA00, 0xAAAAAA, 0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
            0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    /**
     * Separators for the block cache fingerprint. Control characters are used so that a value
     * containing the separator cannot make two different states hash to the same string.
     */
    private static final char KEY_SEPARATOR = 0x01;
    private static final char VALUE_SEPARATOR = 0x02;

    /** The section sign that introduces a legacy formatting code. */
    private static final int SECTION_SIGN = 0x00A7;
    private static final int HEAD_PIXELS = 64;
    private static final int OPAQUE_ALPHA = 128;

    private final Compiled.Pack pack;
    private final boolean headCacheEnabled;
    private final boolean blockCacheEnabled;

    /**
     * Assembled head runs, keyed by the pixel array's identity.
     *
     * Everything from the first opaque pixel onwards is deterministic — colour lives in the run,
     * row position in the leading move, and the font never changes — so it can be reused verbatim.
     * A weak map keyed on the array means the entry dies with the player's cached skin.
     */
    private final Map<int[], Map<Compiled.Head, CachedHead>> headCache = new WeakHashMap<>();

    private record CachedHead(int firstIndex, List<Run> runs, int endCursor) {
    }

    /** What a HUD depends on, computed once so the block cache can fingerprint it cheaply. */
    private record HudDeps(List<String> valueKeys, List<String> headKeys, boolean compass) {
    }

    private final Map<String, HudDeps> deps = new LinkedHashMap<>();

    /**
     * Per-player block cache. Each HUD's runs are assembled from cursor zero and reused until one of
     * its inputs changes. Owned by the caller so it can be dropped on reload, HUD swap or quit.
     */
    public static final class RenderCache {

        private final Map<String, Entry> entries = new HashMap<>();
        private List<String> lastKeys = List.of();

        public void clear() {
            entries.clear();
            lastKeys = List.of();
        }
    }

    private record Entry(String fingerprint, int[][] heads, CompassCtx ctx,
                         List<Run> runs, int endCursor, Set<Integer> missing) {
    }

    public HudRenderer(Compiled.Pack pack) {
        this(pack, true, true);
    }

    public HudRenderer(Compiled.Pack pack, boolean headCacheEnabled, boolean blockCacheEnabled) {
        this.pack = pack;
        this.headCacheEnabled = headCacheEnabled;
        this.blockCacheEnabled = blockCacheEnabled;
        for (Compiled.Hud hud : pack.huds().values()) {
            deps.put(hud.key(), analyse(hud));
        }
    }

    private static HudDeps analyse(Compiled.Hud hud) {
        List<String> valueKeys = new ArrayList<>();
        List<String> headKeys = new ArrayList<>();
        boolean compass = false;
        for (Compiled.Element element : hud.elements()) {
            switch (element) {
                case Compiled.Img e -> {
                    if (e.condition() != null) {
                        valueKeys.add(e.condition());
                    }
                }
                case Compiled.Bar e -> {
                    valueKeys.add(e.valueKey());
                    valueKeys.add(e.maxKey());
                    if (e.condition() != null) {
                        valueKeys.add(e.condition());
                    }
                }
                case Compiled.Follow e -> {
                    valueKeys.add(e.placeholderKey());
                    if (e.condition() != null) {
                        valueKeys.add(e.condition());
                    }
                }
                case Compiled.Text e -> {
                    for (Compiled.Seg segment : e.segments()) {
                        if (segment.placeholder()) {
                            valueKeys.add(segment.value());
                        }
                    }
                    if (e.colorByKey() != null) {
                        valueKeys.add(e.colorByKey());
                    }
                }
                case Compiled.Head e -> headKeys.add(e.key());
                case Compiled.CompassEl ignored -> compass = true;
            }
        }
        return new HudDeps(List.copyOf(valueKeys), List.copyOf(headKeys), compass);
    }

    public Output render(Collection<String> hudKeys, ValueResolver resolver, SkinProvider skins) {
        return render(hudKeys, resolver, skins, null, null);
    }

    public Output render(Collection<String> hudKeys, ValueResolver resolver, SkinProvider skins,
                         CompassSource compass) {
        return render(hudKeys, resolver, skins, compass, null);
    }

    public Output render(Collection<String> hudKeys, ValueResolver resolver, SkinProvider skins,
                         CompassSource compass, RenderCache cache) {
        if (cache != null && blockCacheEnabled) {
            return renderCached(hudKeys, resolver, skins, compass, cache);
        }
        Buf buf = new Buf();
        Set<Integer> missing = new LinkedHashSet<>();
        for (String hudKey : hudKeys) {
            Compiled.Hud hud = pack.huds().get(hudKey);
            if (hud == null) {
                continue;
            }
            for (Compiled.Element element : hud.elements()) {
                renderElement(element, buf, resolver, skins, compass, missing);
            }
        }
        buf.move(-buf.cursor);
        return new Output(buf.finish(), missing, true);
    }

    private void renderElement(Compiled.Element element, Buf buf, ValueResolver resolver,
                               SkinProvider skins, CompassSource compass, Set<Integer> missing) {
        switch (element) {
            case Compiled.Img img -> renderImage(img, buf, resolver);
            case Compiled.Bar bar -> renderBar(bar, buf, resolver);
            case Compiled.Follow follow -> renderFollow(follow, buf, resolver);
            case Compiled.Head head -> {
                int[] pixels = skins == null ? null : skins.facePixels(head.key());
                if (pixels != null) { // still loading: skip, it appears a tick or two later
                    renderHead(head, pixels, buf);
                }
            }
            case Compiled.Text text -> renderText(text, buf, resolver, missing);
            case Compiled.CompassEl compassEl -> renderCompass(compassEl, buf, compass);
        }
    }

    private void renderImage(Compiled.Img img, Buf buf, ValueResolver resolver) {
        if (img.condition() != null && !Compiled.truthy(resolver.text(img.condition()))) {
            return;
        }
        // A grid of rows by columns: columns butt against each other, every row restarts at img.x().
        List<Compiled.Glyph> parts = img.parts();
        int cols = Math.max(1, img.cols());
        for (int i = 0; i < parts.size(); i++) {
            if (i % cols == 0) {
                buf.moveTo(img.x());
            } else {
                buf.move(-1);
            }
            buf.glyph(pack.hudFont(), parts.get(i), 0xFFFFFF, false);
        }
    }

    private void renderBar(Compiled.Bar bar, Buf buf, ValueResolver resolver) {
        if (bar.condition() != null && !Compiled.truthy(resolver.text(bar.condition()))) {
            return;
        }
        double value = resolver.number(bar.valueKey());
        double max = resolver.number(bar.maxKey());
        double ratio = max > 0 ? Math.clamp(value / max, 0.0, 1.0) : 0;
        int frame = (int) Math.ceil(ratio * bar.frames().size());
        if (frame <= 0) {
            return;
        }
        buf.moveTo(bar.x());
        buf.glyph(pack.hudFont(), bar.frames().get(Math.min(frame, bar.frames().size()) - 1),
                0xFFFFFF, false);
    }

    private void renderFollow(Compiled.Follow follow, Buf buf, ValueResolver resolver) {
        if (follow.condition() != null && !Compiled.truthy(resolver.text(follow.condition()))) {
            return;
        }
        Compiled.Glyph child = follow.children().get(resolver.text(follow.placeholderKey()));
        if (child != null) {
            buf.moveTo(follow.x());
            buf.glyph(pack.hudFont(), child, 0xFFFFFF, false);
        }
    }

    /* ---------------- text ---------------- */

    private void renderText(Compiled.Text text, Buf buf, ValueResolver resolver, Set<Integer> missing) {
        StringBuilder builder = new StringBuilder();
        for (Compiled.Seg segment : text.segments()) {
            builder.append(segment.placeholder() ? resolver.text(segment.value()) : segment.value());
        }
        String value = builder.toString();

        int base = text.color();
        if (text.colorByKey() != null && text.colorBy() != null) {
            Integer selected = text.colorBy().get(resolver.text(text.colorByKey()));
            if (selected != null) {
                base = selected;
            }
        }

        // Measure and colour in one pass, then place: alignment needs the total width up front.
        List<int[]> visible = new ArrayList<>(); // {codepoint or -1 for space, colour}
        int width = 0;
        int color = base;
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);

            if (cp == SECTION_SIGN && i < value.length()) {
                Formatting formatting = readFormatting(value, i, base, color);
                color = formatting.color();
                i += formatting.skip();
                continue;
            }
            if (cp == ' ') {
                width += text.spaceAdvance();
                visible.add(new int[]{-1, 0});
                continue;
            }
            Compiled.Glyph glyph = text.glyphs().get(cp);
            if (glyph == null) {
                missing.add(cp);
                continue;
            }
            width += glyph.width();
            visible.add(new int[]{cp, color});
        }

        double startX = switch (text.align()) {
            case "center" -> text.x() - width / 2.0;
            case "right" -> text.x() - width;
            default -> text.x();
        };
        buf.moveTo(startX);

        for (int[] entry : visible) {
            if (entry[0] < 0) {
                buf.move(text.spaceAdvance());
                continue;
            }
            Compiled.Glyph glyph = text.glyphs().get(entry[0]);
            buf.glyphCp(pack.hudFont(), glyph.cp(), glyph.width(), entry[1], text.outline());
        }
    }

    /** What one formatting escape resolved to, and how many characters it consumed. */
    private record Formatting(int color, int skip) {
    }

    /**
     * Reads one formatting escape that starts at {@code index}, just past the section sign.
     *
     * Supports the sixteen legacy colours, {@code #RRGGBB}, and {@code r} to reset to the
     * element's own colour. Style codes ({@code l o n m k}) are consumed and ignored, because
     * rasterised glyphs have no alternate weights to switch to.
     */
    private static Formatting readFormatting(String value, int index, int base, int current) {
        char code = Character.toLowerCase(value.charAt(index));

        if (code == '#' && index + 7 <= value.length()) {
            try {
                return new Formatting(Integer.parseInt(value, index + 1, index + 7, 16), 7);
            } catch (NumberFormatException ignored) {
                // not a hex colour after all; fall through and treat it as an unknown code
            }
        }
        int legacy = "0123456789abcdef".indexOf(code);
        if (legacy >= 0) {
            return new Formatting(LEGACY_COLORS[legacy], 1);
        }
        if (code == 'r') {
            return new Formatting(base, 1);
        }
        if ("lonmk".indexOf(code) >= 0) {
            return new Formatting(current, 1);
        }
        return new Formatting(current, 0); // a lone sign: drop it, the next character is ordinary
    }

    /* ---------------- head ---------------- */

    private void renderHead(Compiled.Head head, int[] pixels, Buf buf) {
        if (!headCacheEnabled || pixels.length < HEAD_PIXELS) {
            renderHeadRange(head, pixels, buf, 0, HEAD_PIXELS, true);
            return;
        }
        CachedHead cached = headCache
                .computeIfAbsent(pixels, k -> new IdentityHashMap<>())
                .computeIfAbsent(head, h -> buildHead(h, pixels));

        if (cached.firstIndex() < 0) { // fully transparent face
            renderHeadRange(head, pixels, buf, 0, HEAD_PIXELS, true);
            return;
        }
        renderHeadRange(head, pixels, buf, 0, cached.firstIndex(), true);
        if ((cached.firstIndex() & 7) == 0) {
            buf.moveTo(head.x());
        }
        buf.appendRuns(cached.runs());
        buf.cursor = cached.endCursor();
    }

    private CachedHead buildHead(Compiled.Head head, int[] pixels) {
        int first = -1;
        for (int i = 0; i < HEAD_PIXELS; i++) {
            if ((pixels[i] >>> 24) >= OPAQUE_ALPHA) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return new CachedHead(-1, List.of(), 0);
        }
        Buf buf = new Buf();
        buf.cursor = (int) Math.round(head.x()) + (first & 7) * head.pixel();
        renderHeadRange(head, pixels, buf, first, HEAD_PIXELS, false);
        return new CachedHead(first, List.copyOf(buf.finish()), buf.cursor);
    }

    private void renderHeadRange(Compiled.Head head, int[] pixels, Buf buf,
                                 int from, int to, boolean leadingMove) {
        for (int i = from; i < to; i++) {
            int row = i >> 3;
            int col = i & 7;
            if (col == 0 && (i > from || leadingMove)) {
                buf.moveTo(head.x());
            }
            int argb = pixels[i];
            if ((argb >>> 24) >= OPAQUE_ALPHA) {
                buf.glyphCp(pack.hudFont(), head.rowCps().get(row), head.pixelAdvance(),
                        argb & 0xFFFFFF, false);
                buf.move(head.pixel() - head.pixelAdvance()); // pull the next pixel flush
            } else {
                buf.move(head.pixel());
            }
        }
    }

    /* ---------------- block cache ---------------- */

    /**
     * Renders with per-HUD reuse.
     *
     * Each HUD is assembled into its own buffer starting at cursor zero — every element moves to
     * an absolute X, so a block is position-independent — and kept until its fingerprint changes:
     * its placeholder values, the identity of its skin arrays, and the compass context. Blocks are
     * stitched together by bridging the cursor back to zero before each one.
     */
    private Output renderCached(Collection<String> hudKeys, ValueResolver resolver, SkinProvider skins,
                                CompassSource compass, RenderCache cache) {
        boolean changed = false;
        List<String> keys = List.copyOf(hudKeys);
        if (!keys.equals(cache.lastKeys)) {
            changed = true;
            cache.lastKeys = keys;
        }

        Buf master = new Buf();
        Set<Integer> missing = new LinkedHashSet<>();

        for (String hudKey : keys) {
            Compiled.Hud hud = pack.huds().get(hudKey);
            if (hud == null) {
                continue;
            }
            HudDeps dep = deps.get(hudKey);

            // Values are resolved once and reused by the render below, not looked up twice.
            Map<String, String> resolved = new HashMap<>();
            StringBuilder fingerprint = new StringBuilder(96);
            for (String key : dep.valueKeys()) {
                String value = resolved.computeIfAbsent(key, resolver::text);
                fingerprint.append(key).append(KEY_SEPARATOR).append(value).append(VALUE_SEPARATOR);
            }
            int[][] heads = new int[dep.headKeys().size()][];
            for (int i = 0; i < heads.length; i++) {
                heads[i] = skins == null ? null : skins.facePixels(dep.headKeys().get(i));
            }
            CompassCtx ctx = dep.compass() && compass != null ? compass.ctx() : null;

            Entry entry = cache.entries.get(hudKey);
            boolean hit = entry != null
                    && entry.fingerprint().contentEquals(fingerprint)
                    && sameHeads(entry.heads(), heads)
                    && Objects.equals(entry.ctx(), ctx);

            if (!hit) {
                Buf block = new Buf();
                Set<Integer> blockMissing = new LinkedHashSet<>();
                ValueResolver cachedResolver = key -> resolved.computeIfAbsent(key, resolver::text);
                CompassSource frozen = ctx == null ? null : () -> ctx;
                for (Compiled.Element element : hud.elements()) {
                    renderElement(element, block, cachedResolver, skins, frozen, blockMissing);
                }
                entry = new Entry(fingerprint.toString(), heads, ctx,
                        List.copyOf(block.finish()), block.cursor, Set.copyOf(blockMissing));
                cache.entries.put(hudKey, entry);
                changed = true;
            }

            master.move(-master.cursor);
            master.appendRuns(entry.runs());
            master.cursor = entry.endCursor();
            missing.addAll(entry.missing());
        }

        master.move(-master.cursor);
        return new Output(master.finish(), missing, changed);
    }

    /** Identity comparison is deliberate: a player's face array is replaced, never mutated. */
    private static boolean sameHeads(int[][] a, int[][] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    /* ---------------- compass ---------------- */

    /**
     * Draws the compass ribbon and its points.
     *
     * The ribbon's fractional position comes from yaw, which gives a sub-pixel shift within each
     * column; cardinal marks and chain links are placed by column index. Points are placed by
     * bearing, with the size variant chosen by how far the point sits from the window's centre.
     */
    private void renderCompass(Compiled.CompassEl compass, Buf buf, CompassSource source) {
        if (source == null) {
            return;
        }
        CompassCtx ctx = source.ctx();
        if (ctx == null) {
            return;
        }

        int length = compass.length();
        double degrees = ctx.yaw() < 0 ? ctx.yaw() + 360.0 : ctx.yaw();
        double quarter = degrees / 90.0 * length;
        int step = (int) Math.ceil(quarter);
        double fraction = quarter - step + 0.5;
        int halfLength = length / 2;

        buf.moveTo(compass.x());
        int totalWidth = 0;
        for (int i = 1; i < length; i++) {
            Compiled.CompassSlot slot = compass.slots().get(cardinalSlot(step - i + halfLength, length));
            int distance = i > halfLength ? length - i : i;
            Compiled.Glyph glyph = variant(slot, compass.div() - distance);
            if (glyph == null) {
                buf.move(2 * compass.space());
                totalWidth += 2 * compass.space();
                continue;
            }
            int visualWidth = glyph.width() - 1;
            int columnWidth = 2 * compass.space() + visualWidth;
            int shift = (int) Math.round(fraction * columnWidth);

            if (slot.xOff() != 0) {
                buf.move(slot.xOff());
            }
            buf.move(compass.space() + shift);
            buf.glyph(pack.hudFont(), glyph, 0xFFFFFF, compass.outline());
            buf.move(compass.space() - shift - 1);
            if (slot.xOff() != 0) {
                buf.move(-slot.xOff());
            }
            totalWidth += columnWidth;
        }

        for (CompassPointView point : ctx.points()) {
            renderCompassPoint(compass, buf, ctx, point, totalWidth);
        }
    }

    private void renderCompassPoint(Compiled.CompassEl compass, Buf buf, CompassCtx ctx,
                                    CompassPointView point, int totalWidth) {
        Compiled.CompassSlot slot = null;
        if (point.icon() != null) {
            slot = compass.slots().get("icon:" + point.icon());
        }
        if (slot == null) {
            slot = compass.slots().get("point");
        }
        if (slot == null) {
            return;
        }

        double bearing = Math.atan2(point.dz(), point.dx()) / Math.PI;
        if (bearing < 0) {
            bearing += 2;
        }
        double facing = (ctx.yaw() > 90 ? -270 + ctx.yaw() : 90 + ctx.yaw()) / 180.0;
        if (facing < 0) {
            facing += 2;
        }
        double delta = absMin(bearing - facing, -(facing - bearing));
        double offset = absMin(delta > 0 ? -(2 - delta) : 2 + delta, delta);

        int distance = (int) Math.ceil((compass.length() - Math.abs(offset * compass.length())) / 2.0);
        if (distance < 1) {
            return; // behind the player, or past the edge of the window
        }
        Compiled.Glyph glyph = variant(slot, compass.div() - Math.min(compass.div(), distance));
        if (glyph == null) {
            return;
        }

        int visualWidth = glyph.width() - 1;
        double centerX = compass.x() + totalWidth * (1 + offset) / 2.0;
        buf.moveTo(centerX - Math.floor(visualWidth / 2.0) + slot.xOff());
        buf.glyph(pack.hudFont(), glyph, 0xFFFFFF, compass.outline());

        Compiled.CompassDist dist = compass.dist();
        if (dist != null && Math.abs(offset) <= dist.focus()) {
            renderDistanceLabel(dist, buf, point, centerX);
        }
    }

    private void renderDistanceLabel(Compiled.CompassDist dist, Buf buf,
                                     CompassPointView point, double centerX) {
        long metres = Math.round(Math.sqrt(point.dx() * point.dx() + point.dz() * point.dz()));
        String label = metres + dist.suffix();

        int width = 0;
        for (int i = 0; i < label.length(); ) {
            int cp = label.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == ' ') {
                width += dist.spaceAdvance();
                continue;
            }
            Compiled.Glyph glyph = dist.glyphs().get(new String(Character.toChars(cp)));
            if (glyph != null) {
                width += glyph.width();
            }
        }

        buf.moveTo(centerX - width / 2.0 + dist.x());
        for (int i = 0; i < label.length(); ) {
            int cp = label.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == ' ') {
                buf.move(dist.spaceAdvance());
                continue;
            }
            Compiled.Glyph glyph = dist.glyphs().get(new String(Character.toChars(cp)));
            if (glyph != null) {
                buf.glyph(pack.hudFont(), glyph, dist.color(), dist.outline());
            }
        }
    }

    private static String cardinalSlot(int column, int length) {
        if (column == (int) Math.round(length * 0.5)) {
            return "sw";
        }
        if (column == length) {
            return "w";
        }
        if (column == (int) Math.round(length * 1.5)) {
            return "nw";
        }
        if (column == length * 2) {
            return "n";
        }
        if (column == (int) Math.round(length * 2.5)) {
            return "ne";
        }
        if (column == length * 3) {
            return "e";
        }
        if (column == (int) Math.round(length * 3.5)) {
            return "se";
        }
        if (column == 0 || column == length * 4) {
            return "s";
        }
        return "chain";
    }

    private static Compiled.Glyph variant(Compiled.CompassSlot slot, int index) {
        if (slot == null || slot.variants().isEmpty()) {
            return null;
        }
        int clamped = Math.max(0, index);
        return clamped >= slot.variants().size() ? null : slot.variants().get(clamped);
    }

    private static double absMin(double a, double b) {
        return Math.abs(a) < Math.abs(b) ? a : b;
    }

    /* ---------------- run buffer ---------------- */

    /** Accumulates runs while tracking the cursor, in GUI pixels from the anchor. */
    private final class Buf {

        private final List<Run> runs = new ArrayList<>();
        private final StringBuilder current = new StringBuilder();
        private String font;
        private int color;
        private boolean shadow;
        private int cursor;

        void moveTo(double x) {
            move((int) Math.round(x) - cursor);
        }

        void move(int dx) {
            if (dx == 0) {
                return;
            }
            cursor += dx;
            // The advance table is registered under both fonts, so a move can stay in whatever font
            // is already open and keep the surrounding glyphs in a single component. With no font
            // open yet the HUD font is the better guess than the space font: almost everything that
            // follows a leading move is HUD content, so they merge instead of forcing a switch.
            String font = this.font != null ? this.font : pack.hudFont();
            int color = this.font != null ? this.color : 0xFFFFFF;
            boolean shadow = this.font != null && this.shadow;

            int remaining = dx;
            while (remaining != 0) {
                int step = Math.clamp(remaining, -pack.spaceRange(), pack.spaceRange());
                appendCp(font, pack.spaceBase() + pack.spaceRange() + step, color, shadow);
                remaining -= step;
            }
        }

        void glyph(String font, Compiled.Glyph glyph, int color, boolean shadow) {
            glyphCp(font, glyph.cp(), glyph.width(), color, shadow);
        }

        void glyphCp(String font, int cp, int width, int color, boolean shadow) {
            appendCp(font, cp, color, shadow);
            cursor += width;
        }

        private void appendCp(String font, int cp, int color, boolean shadow) {
            if (!current.isEmpty()
                    && (!font.equals(this.font) || color != this.color || shadow != this.shadow)) {
                flush();
            }
            this.font = font;
            this.color = color;
            this.shadow = shadow;
            current.appendCodePoint(cp);
        }

        /**
         * Appends pre-assembled runs, merging with the current one where the style matches.
         *
         * The last run is copied into the builder rather than appended so that whatever comes
         * next can still merge into it.
         */
        void appendRuns(List<Run> cached) {
            int size = cached.size();
            for (int i = 0; i < size; i++) {
                Run run = cached.get(i);
                if (!current.isEmpty() && run.font().equals(font)
                        && run.color() == color && run.shadow() == shadow) {
                    current.append(run.text());
                    continue;
                }
                if (!current.isEmpty()) {
                    flush();
                }
                if (i < size - 1) {
                    runs.add(run);
                } else {
                    current.append(run.text());
                }
                font = run.font();
                color = run.color();
                shadow = run.shadow();
            }
        }

        private void flush() {
            if (!current.isEmpty()) {
                runs.add(new Run(font, current.toString(), color, shadow));
                current.setLength(0);
            }
        }

        List<Run> finish() {
            flush();
            return runs;
        }
    }
}
