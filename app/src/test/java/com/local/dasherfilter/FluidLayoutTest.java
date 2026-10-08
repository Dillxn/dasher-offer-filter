package com.local.dasherfilter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * The homepage's one fluid layout as pure arithmetic ({@link FluidLayout}), at every window size a pixel apart (the
 * owner, 7 October 2026: "I do not like how it only shows the map or the radar"; "it should be gradual and
 * seamless"). There is no threshold anywhere: no edge moves more than {@link FluidLayout#TURN_RATE} times as fast as
 * the window's edge, and nothing fades faster than over its own range; as the window shrinks, what must go fades in a
 * fixed order of least importance (the road, the counts, the skyline with its caption, then the radar and the map
 * together), and as it grows nothing fades; the radar and the map stand together wherever the room under the lines
 * holds both at their least, side by side while it is wide and one above the other once it is tall; and no two parts
 * ever overlap or leave the page. {@link FluidPageTest} checks the real page.
 */
public final class FluidLayoutTest {
    /** The page's widths, in dp: narrow and typical phones, a large phone, a phone on its side, a tablet. */
    private static final int[] WIDTHS = {280, 320, 360, 411, 480, 600, 731, 840, 1280};
    /** The strip's words and the lines, in dp: the normal font with no line, a setup step, cards, twice the font. */
    private static final int[][] CONTENTS = {{69, 0}, {69, 48}, {90, 160}, {170, 96}};
    private static final int HEADER_DP = 56;
    private static final float[] DENSITIES = {1f, 2.625f};
    /** The tallest window swept, in dp. */
    private static final int TALLEST_DP = 1600;

    private static FluidLayout solve(float density, int width, int height, int[] content, boolean area) {
        FluidLayout layout = new FluidLayout(density);
        layout.width = width;
        layout.height = height;
        layout.wordsHeight = Math.round(content[0] * density);
        layout.headerHeight = Math.round(HEADER_DP * density);
        layout.linesHeight = Math.round(content[1] * density);
        layout.areaLine = area;
        layout.solve();
        return layout;
    }

    /** The parts as they stand, the counts as drawn (smaller from their top's middle while their row shrinks). */
    private static Map<String, FluidLayout.Box> boxes(FluidLayout layout) {
        Map<String, FluidLayout.Box> boxes = new LinkedHashMap<>();
        boxes.put("mascot", layout.mascot);
        boxes.put("words", layout.words);
        boxes.put("header", layout.header);
        FluidLayout.Box counts = new FluidLayout.Box();
        float middle = (layout.counts.left + layout.counts.right) / 2;
        float half = layout.counts.width() / 2 * layout.countsShown;
        counts.set(middle - half, layout.counts.top, middle + half,
                layout.counts.top + layout.counts.height() * layout.countsShown);
        boxes.put("counts", counts);
        boxes.put("lines", layout.lines);
        boxes.put("radar", layout.radar);
        boxes.put("caption", layout.caption);
        boxes.put("chart", layout.chart);
        boxes.put("map", layout.map);
        boxes.put("area line", layout.area);
        boxes.put("road", layout.road);
        return boxes;
    }

    /** How much of each part that fades is there, and the turn. */
    private static float[] shown(FluidLayout layout) {
        return new float[] {layout.roadShown, layout.countsShown, layout.horizonShown, layout.stageShown};
    }

    private static final String[] SHOWN = {"road", "counts", "skyline", "radar and map"};

    private interface Check {
        void at(Where where, FluidLayout before, FluidLayout now, float density, int widthDp, int[] content,
                boolean area);
    }

    /** Where a check is, said only when it fails (a sweep makes millions of checks). */
    private static final class Where {
        final String said;
        final int pixels;

        Where(String said, int pixels) {
            this.said = said;
            this.pixels = pixels;
        }

        @Override public String toString() {
            return String.format(java.util.Locale.US, said, pixels);
        }
    }

    /** Fails with {@code message} unless {@code condition}; the message is made only then. */
    private static void that(boolean condition, Supplier<String> message) {
        if (!condition) fail(message.get());
    }

    /** Every width, content and density, the window growing a pixel at a time from nothing. */
    private static void heights(Check check) {
        for (float density : DENSITIES) {
            for (int widthDp : WIDTHS) {
                int width = Math.round(widthDp * density);
                for (int[] content : CONTENTS) {
                    for (boolean area : new boolean[] {false, true}) {
                        String said = widthDp + " dp wide, %d px tall, density " + density + ", words " + content[0]
                                + " dp, lines " + content[1] + " dp, area line " + area;
                        FluidLayout before = null;
                        for (int height = 0; height <= Math.round(TALLEST_DP * density); height++) {
                            FluidLayout now = solve(density, width, height, content, area);
                            check.at(new Where(said, height), before, now, density, widthDp, content, area);
                            before = now;
                        }
                    }
                }
            }
        }
    }

    /** The farthest any edge of {@code a} and {@code b} stands apart. */
    private static float apart(FluidLayout.Box a, FluidLayout.Box b) {
        return Math.max(Math.max(Math.abs(a.left - b.left), Math.abs(a.top - b.top)),
                Math.max(Math.abs(a.right - b.right), Math.abs(a.bottom - b.bottom)));
    }

    /**
     * From one window to the next, a pixel apart: no edge moves more than {@code most} pixels, the radar's circle no
     * more than TURN_RATE, nothing fades by more than over its shortest range (the counts' row), and the turn by no
     * more than over the shortest of its halves.
     */
    private static void step(Object where, FluidLayout before, FluidLayout now, float density, float most) {
        Map<String, FluidLayout.Box> a = boxes(before);
        Map<String, FluidLayout.Box> b = boxes(now);
        for (String part : a.keySet()) {
            FluidLayout.Box was = a.get(part);
            FluidLayout.Box is = b.get(part);
            float moved = apart(was, is);
            that(moved <= most, () -> where + ": " + part + " moved " + moved + " px from " + was + " to " + is);
        }
        that(Math.abs(before.radius - now.radius) <= FluidLayout.TURN_RATE
                && Math.abs(before.radarX - now.radarX) <= FluidLayout.TURN_RATE
                && Math.abs(before.radarY - now.radarY) <= FluidLayout.TURN_RATE, () -> where + ": the radar's circle");
        float[] was = shown(before);
        float[] is = shown(now);
        for (int i = 0; i < was.length; i++) {
            int part = i;
            that(Math.abs(was[i] - is[i]) <= 1 / (FluidLayout.COUNTS_DP * density) + 1e-4f,
                    () -> where + ": the " + SHOWN[part] + " faded from " + was[part] + " to " + is[part]);
        }
        that(Math.abs(before.turn - now.turn) <= 0.01f / density,
                () -> where + ": the turn from " + before.turn + " to " + now.turn);
    }

    /**
     * No edge moves more than TURN_RATE pixels for each pixel the window grows, and nothing fades by more than over
     * its shortest range: swept a pixel at a time, from nothing to a tall tablet.
     */
    @Test
    public void aWindowGrowingAPixelAtATimeMovesNothingFasterThanTwiceItsEdge() {
        heights((where, before, now, density, widthDp, content, area) -> {
            if (before != null) step(where, before, now, density, FluidLayout.TURN_RATE + 0.01f);
        });
    }

    /**
     * Narrowing or widening a window of any height a pixel at a time (a split screen's divider on a phone on its side)
     * moves nothing faster than twice its edge either, give or take a pixel of the words' rounded width.
     */
    @Test
    public void aWindowNarrowingAPixelAtATimeMovesNothingFasterThanTwiceItsEdge() {
        for (float density : DENSITIES) {
            for (int heightDp : new int[] {220, 300, 411, 560, 731, 900, 1280}) {
                int height = Math.round(heightDp * density);
                for (int[] content : CONTENTS) {
                    for (boolean area : new boolean[] {false, true}) {
                        String said = heightDp + " dp tall, %d px wide, density " + density + ", words " + content[0]
                                + " dp, lines " + content[1] + " dp, area line " + area;
                        FluidLayout before = null;
                        for (int width = Math.round(200 * density); width <= Math.round(1400 * density); width++) {
                            FluidLayout now = solve(density, width, height, content, area);
                            if (before != null) {
                                step(new Where(said, width), before, now, density, FluidLayout.TURN_RATE + 1.01f);
                            }
                            before = now;
                        }
                    }
                }
            }
        }
    }

    /** As the window grows nothing fades, and the stage only ever turns from side by side to one above the other. */
    @Test
    public void asTheWindowGrowsNothingFadesAndTheStageTurnsOneWay() {
        heights((where, before, now, density, widthDp, content, area) -> {
            if (before == null) return;
            float[] was = shown(before);
            float[] is = shown(now);
            for (int i = 0; i < was.length; i++) {
                int part = i;
                that(is[i] >= was[i] - 1e-5f, () -> where + ": the " + SHOWN[part]
                        + " faded as the window grew, from " + was[part] + " to " + is[part]);
            }
            that(now.turn <= before.turn + 1e-5f, () -> where + ": the stage turned back");
        });
    }

    /**
     * As it shrinks, what must go fades in a fixed order, least important first: the road, then the counts, then the
     * skyline with its caption, and last the radar and the map together, each beginning only once the one before is
     * gone; and the radar and the map go only at their least.
     */
    @Test
    public void whatMustGoFadesLeastImportantFirst() {
        heights((where, before, now, density, widthDp, content, area) -> {
            float[] is = shown(now);
            for (int i = 1; i < is.length; i++) {
                if (is[i] >= 1) continue;
                for (int j = 0; j < i; j++) {
                    int fading = i;
                    int there = j;
                    that(is[j] == 0, () -> where + ": the " + SHOWN[fading] + " fade while the " + SHOWN[there]
                            + " are there");
                }
            }
            that(now.stageShown >= 1 || now.radius <= FluidLayout.RADIUS_LEAST_DP * density + 0.5f,
                    () -> where + ": the radar goes only at its least, not at " + now.radius);
        });
    }

    /**
     * The radar and the map stand together, wholly there and usable (the radar's knobs at their least circle, the map
     * at its least height), wherever the room under the lines holds both side by side at their least: from 132 dp,
     * in every page at least 280 dp wide.
     */
    @Test
    public void theRadarAndTheMapStandTogetherWhereverBothFit() {
        heights((where, before, now, density, widthDp, content, area) -> {
            float room = now.height - now.fixedHeight();
            // Side by side at their least: the radar's least circle with its icons, the map's least with its line.
            float least = Math.max(now.radarHeight(FluidLayout.RADIUS_LEAST_DP * density),
                    (FluidLayout.MAP_LEAST_DP + FluidLayout.AREA_LINE_DP) * density);
            assertEquals(132 * density, least, 0.01f);
            if (room < least) return;
            that(now.stageShown == 1, () -> where + ": the radar and the map wholly there, room " + room);
            that(now.radius >= FluidLayout.RADIUS_LEAST_DP * density - 0.5f,
                    () -> where + ": the radar's knobs usable, radius " + now.radius);
            that(now.map.height() >= FluidLayout.MAP_LEAST_DP * density - 0.5f
                    && now.map.width() >= Math.min(200 * density, now.width / 2f) - 0.5f,
                    () -> where + ": the map readable " + now.map);
            that(!area || now.area.height() >= FluidLayout.AREA_LINE_DP * density - 0.5f,
                    () -> where + ": the area line whole " + now.area);
        });
    }

    /**
     * Side by side (the radar on the left) while the room under the lines is short for the page's width, one above the
     * other (the radar across the whole width over the skyline's caption) once it is tall, and between the two a turn
     * in which the parts stand apart.
     */
    @Test
    public void sideBySideWhenWideOneAboveTheOtherWhenTall() {
        heights((where, before, now, density, widthDp, content, area) -> {
            float room = now.height - now.fixedHeight();
            // Where the turn begins: SIDE_FROM_DP and SIDE_SHARE of the width, and never before the radar (at its
            // middle circle) and the whole skyline fit one above the other.
            float start = Math.max(FluidLayout.SIDE_FROM_DP * density + FluidLayout.SIDE_SHARE * now.width,
                    now.radarHeight(FluidLayout.RADIUS_MIDDLE_DP * density) + (FluidLayout.CAPTION_DP
                            + FluidLayout.CHART_LEAST_DP + FluidLayout.MAP_MIDDLE_DP + FluidLayout.AREA_LINE_DP)
                            * density);
            that(room > start || now.turn == 1, () -> where + ": side by side, not turned " + now.turn);
            that(now.turn < 1 || now.stageShown <= 0 || now.radar.left == 0
                    && now.radar.right <= now.map.left + 0.01f && now.radar.right <= now.caption.left + 0.01f,
                    () -> where + ": the radar on the left " + now.radar + " " + now.map);
            that(now.turn > 0 || now.stageShown <= 0 || now.radar.width() == now.width
                    && now.radar.bottom <= now.caption.top + 0.01f && now.caption.left == 0,
                    () -> where + ": one above the other " + now.radar + " " + now.caption);
            that(room < 3 * now.width + 300 * density || now.turn == 0,
                    () -> where + ": one above the other in a tall window, not turned " + now.turn);
        });
    }

    /**
     * No two parts overlap, nothing stands outside the page, and the page is the window's height, or taller only by
     * what never goes (the strip, the header and the lines) with everything else gone.
     */
    @Test
    public void nothingOverlapsAndNothingLeavesThePage() {
        heights((where, before, now, density, widthDp, content, area) -> {
            Map<String, FluidLayout.Box> boxes = boxes(now);
            String[] names = boxes.keySet().toArray(new String[0]);
            for (int i = 0; i < names.length; i++) {
                FluidLayout.Box a = boxes.get(names[i]);
                String one = names[i];
                that(a.left >= -0.01f && a.right <= now.width + 0.01f && a.top >= -0.01f
                        && a.bottom <= now.total + 0.51f && a.width() >= -0.01f && a.height() >= -0.01f,
                        () -> where + ": " + one + " inside the page " + a);
                for (int j = i + 1; j < names.length; j++) {
                    FluidLayout.Box b = boxes.get(names[j]);
                    String other = names[j];
                    float across = Math.min(a.right, b.right) - Math.max(a.left, b.left);
                    float down = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
                    that(across <= 0.01f || down <= 0.01f,
                            () -> where + ": " + one + " " + a + " overlaps " + other + " " + b);
                }
            }
            if (now.height >= now.fixedHeight()) {
                that(now.total == now.height, () -> where + ": the window's height, not " + now.total);
            } else {
                that(now.total == Math.round(now.fixedHeight()) && now.stageShown == 0,
                        () -> where + ": only what never goes, " + now.total);
            }
        });
    }

    /** The strip never moves with the window's height: the mascot at its left, the words beside it. */
    @Test
    public void theStripStaysPut() {
        heights((where, before, now, density, widthDp, content, area) -> {
            that(Math.abs(now.mascot.left - 12 * density) < 0.01f && Math.abs(now.mascot.top - 8 * density) < 0.01f
                    && Math.abs(now.words.left - now.mascot.right - 12 * density) < 0.01f
                    && Math.abs(now.words.top - 8 * density) < 0.01f
                    && Math.abs(now.words.width() - now.wordsWidth(now.width)) < 0.01f,
                    () -> where + ": the mascot " + now.mascot + ", the words " + now.words);
        });
    }
}
