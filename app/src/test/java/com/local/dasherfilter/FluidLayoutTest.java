package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The homepage's one fluid layout as pure arithmetic ({@link FluidLayout}), at every window size a pixel apart (the
 * owner, 7 October 2026: "I do not like how it only shows the map or the radar"; "it should be gradual and
 * seamless"; and 12 October 2026: "at small split ratios I still want to see all relevant infographic"). There is no
 * threshold anywhere: no edge moves more than {@link FluidLayout#TURN_RATE} times as fast as the window's height (and
 * three times as fast as its width, as the band folds), and nothing fades faster than over the road's height; the band
 * never fades and folds by the width alone; as the window shrinks, the road goes first, then the stage turns from two
 * tiers (0.6.8: the radar and the skyline side by side over the map strip) into three columns (the radar, the skyline,
 * the map) and fades only below its least, all of it together; as it grows nothing fades and the turn goes one way;
 * the radar, the skyline and the map are all wholly there wherever the window is at least 335 dp wide and the stage at
 * least {@link FluidLayout#TUCKED_DP} tall; and no two parts ever overlap or leave the page. {@link FluidPageTest} checks the real page.
 *
 * <p>Each sweep is made once and every test's check is run on each of its windows; each test fails with the first
 * window its own check found wrong.
 */
public final class FluidLayoutTest {
    /** The page's widths, in dp: narrow and typical phones, a large phone, a phone on its side, a tablet. */
    private static final int[] WIDTHS = {280, 320, 360, 411, 480, 600, 731, 840, 1280};
    /** The windows' heights for the sweeps across, in dp: split screens, phones, a phone on its side, a tablet. */
    private static final int[] HEIGHTS = {220, 236, 300, 400, 411, 560, 600, 731, 900, 1280};
    private static final float[] DENSITIES = {1f, 2.625f};
    /** The tallest window swept, in dp; and the narrowest and widest swept across. */
    private static final int TALLEST_DP = 1600;
    private static final int NARROWEST_DP = 200;
    private static final int WIDEST_DP = 1400;
    /**
     * What the band holds at the normal font and at twice it, in dp: the latest offer's tile's height, the counts'
     * width (none since 0.6.6: the chart's key holds them) and the chips' width at their widest words ({@link VerdictTile#heightFor},
     * {@link FilterHeroView#countsWidth}, {@link AutopilotChip.Row#widestChips}).
     */
    private static final int[][] FONTS = {{48, 0, 146}, {52, 0, 200}};
    /** The header's buttons: beside Dasher (three), beside another app (four, with Split), a full screen (five). */
    private static final int[] BUTTONS = {3, 4, 5};
    /** The lines that need the user, in dp: none, a setup step, several cards. */
    private static final int[] LINES = {0, 48, 160};
    /** From this width, in dp, the stage's least holds all of the radar, the skyline and the map. */
    private static final int WHOLE_FROM_DP = 335;

    /** One page's content: its font, its header's buttons and its lines. */
    private static final class Content {
        final int font;
        final int buttons;
        final int lines;

        Content(int font, int buttons, int lines) {
            this.font = font;
            this.buttons = buttons;
            this.lines = lines;
        }

        /** The header's buttons' width, in dp: each icon button 48 dp after 4 dp of room, and the sun's 56. */
        int buttonsDp() {
            return 52 * (buttons - 1) + 56;
        }

        /** The narrowest window whose band holds the header's buttons whole, in dp. */
        int narrowestDp() {
            return buttons == 3 ? 200 : buttons == 4 ? 220 : 270;
        }

        @Override public String toString() {
            return (font == 0 ? "the normal font" : "twice the font") + ", " + buttons + " buttons, lines " + lines
                    + " dp";
        }
    }

    private static final List<Content> CONTENTS = new ArrayList<>();

    static {
        for (int font = 0; font < FONTS.length; font++) {
            for (int buttons : BUTTONS) {
                for (int lines : LINES) CONTENTS.add(new Content(font, buttons, lines));
            }
        }
    }

    private static FluidLayout solve(float density, int width, int height, Content content, boolean area) {
        FluidLayout layout = new FluidLayout(density);
        layout.width = width;
        layout.height = height;
        int[] font = FONTS[content.font];
        layout.tileHeight = Math.round(font[0] * density);
        layout.countsWidth = Math.round(font[1] * density);
        layout.chipsMost = Math.round(font[2] * density);
        layout.buttonsWidth = Math.round(content.buttonsDp() * density);
        layout.linesHeight = Math.round(content.lines * density);
        layout.areaLine = area;
        layout.solve();
        return layout;
    }

    private static final String[] PARTS = {"mascot", "tile", "counts", "chips", "header", "lines", "radar", "caption",
        "chart", "map", "area line", "road"};

    /** The parts as they stand, in {@link #PARTS}' order; the band's first. */
    private static FluidLayout.Box[] boxes(FluidLayout layout) {
        return new FluidLayout.Box[] {layout.mascot, layout.tile, layout.counts, layout.chipRow, layout.header,
            layout.lines, layout.radar, layout.caption, layout.chart, layout.map, layout.area, layout.road};
    }

    /** How many of {@link #boxes} are the band's, which never fade. */
    private static final int BAND_PARTS = 5;

    /** How much of each part that fades is there. */
    private static float[] shown(FluidLayout layout) {
        return new float[] {layout.roadShown, layout.horizonShown, layout.stageShown};
    }

    private static final String[] SHOWN = {"road", "skyline", "radar and map"};

    /** Where the buttons begin to rise into the chips' row, in pixels, from the band's own inputs. */
    private static float risesFrom(FluidLayout layout, float density) {
        float past = (layout.buttonsWidth - FluidLayout.FOUR_BUTTONS_DP * density)
                / (FluidLayout.BUTTON_STEP_DP * density);
        float extra = FluidLayout.FULL_SCREEN_EXTRA_DP * density * Math.max(0, Math.min(1, past));
        return (2 * FluidLayout.BAND_SIDE_DP + FluidLayout.BAND_GAP_DP) * density + layout.chipsMost
                + layout.buttonsWidth + extra;
    }

    private interface Check {
        void at(Where where, FluidLayout before, FluidLayout now, float density, Content content, boolean area);
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
            return String.format(Locale.US, said, pixels);
        }
    }

    /** Fails with {@code message} unless {@code condition}; the message is made only then. */
    private static void that(boolean condition, Supplier<String> message) {
        if (!condition) fail(message.get());
    }

    /** A sweep made once, with the first failure each of its checks found. */
    private static final class Sweep {
        final Map<Check, String> failures = new IdentityHashMap<>();

        /** Runs every check that has not failed yet on one window. */
        void at(List<Check> checks, Where where, FluidLayout before, FluidLayout now, float density, Content content,
                boolean area) {
            for (Check check : checks) {
                if (failures.containsKey(check)) continue;
                try {
                    check.at(where, before, now, density, content, area);
                } catch (AssertionError | RuntimeException e) {
                    failures.put(check, e instanceof AssertionError ? e.getMessage() : where + ": " + e);
                }
            }
        }
    }

    private static Sweep heights;
    private static Sweep widths;

    /** Every width, content and density, the window growing a pixel at a time from nothing. */
    private static synchronized Sweep heights() {
        if (heights != null) return heights;
        Sweep sweep = new Sweep();
        for (float density : DENSITIES) {
            for (int widthDp : WIDTHS) {
                int width = Math.round(widthDp * density);
                for (Content content : CONTENTS) {
                    for (boolean area : new boolean[] {false, true}) {
                        String said = widthDp + " dp wide, %d px tall, density " + density + ", " + content
                                + ", area line " + area;
                        FluidLayout before = null;
                        for (int height = 0; height <= Math.round(TALLEST_DP * density); height++) {
                            FluidLayout now = solve(density, width, height, content, area);
                            sweep.at(HEIGHT_CHECKS, new Where(said, height), before, now, density, content, area);
                            before = now;
                        }
                    }
                }
            }
        }
        heights = sweep;
        return sweep;
    }

    /** Every height, content and density, the window widening a pixel at a time (a split screen's divider dragged). */
    private static synchronized Sweep widths() {
        if (widths != null) return widths;
        Sweep sweep = new Sweep();
        for (float density : DENSITIES) {
            for (int heightDp : HEIGHTS) {
                int height = Math.round(heightDp * density);
                for (Content content : CONTENTS) {
                    for (boolean area : new boolean[] {false, true}) {
                        String said = heightDp + " dp tall, %d px wide, density " + density + ", " + content
                                + ", area line " + area;
                        FluidLayout before = null;
                        for (int width = Math.round(NARROWEST_DP * density); width <= Math.round(WIDEST_DP * density);
                                width++) {
                            FluidLayout now = solve(density, width, height, content, area);
                            sweep.at(WIDTH_CHECKS, new Where(said, width), before, now, density, content, area);
                            before = now;
                        }
                    }
                }
            }
        }
        widths = sweep;
        return sweep;
    }

    /** Fails with the first window {@code check} found wrong in {@code sweep}. */
    private static void passes(Sweep sweep, Check check) {
        String failed = sweep.failures.get(check);
        if (failed != null) fail(failed);
    }

    /** The farthest any edge of {@code a} and {@code b} stands apart. */
    private static float apart(FluidLayout.Box a, FluidLayout.Box b) {
        return Math.max(Math.max(Math.abs(a.left - b.left), Math.abs(a.top - b.top)),
                Math.max(Math.abs(a.right - b.right), Math.abs(a.bottom - b.bottom)));
    }

    /**
     * From one window to the next, a pixel apart: no edge moves more than {@code most} pixels, the radar's circle no
     * more than that either, nothing fades by more than over the road's height, and neither turn by more than over the
     * shortest of its halves.
     */
    private static void step(Object where, FluidLayout before, FluidLayout now, float density, float most) {
        step(where, before, now, density, most, 1);
    }

    /** As {@link #step}, the fades allowed {@code fades} times as fast. */
    private static void step(Object where, FluidLayout before, FluidLayout now, float density, float most,
            float fades) {
        FluidLayout.Box[] was = boxes(before);
        FluidLayout.Box[] is = boxes(now);
        for (int i = 0; i < was.length; i++) {
            float moved = apart(was[i], is[i]);
            if (moved > most) fail(where + ": " + PARTS[i] + " moved " + moved + " px from " + was[i] + " to " + is[i]);
        }
        that(Math.abs(before.radius - now.radius) <= most && Math.abs(before.radarX - now.radarX) <= most
                && Math.abs(before.radarY - now.radarY) <= most, () -> where + ": the radar's circle");
        float[] a = shown(before);
        float[] b = shown(now);
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > fades / (FluidLayout.ROAD_DP * density) + 1e-4f) {
                fail(where + ": the " + SHOWN[i] + " faded from " + a[i] + " to " + b[i]);
            }
        }
        that(Math.abs(before.turn - now.turn) <= 0.03f / density + 1e-5f,
                () -> where + ": the turn from " + before.turn + " to " + now.turn);
        that(Math.abs(before.inner - now.inner) <= 0.03f / density + 1e-5f,
                () -> where + ": the inner turn from " + before.inner + " to " + now.inner);
    }

    /**
     * No edge moves more than TURN_RATE pixels for each pixel the window grows, and nothing fades by more than over
     * the road's height: swept a pixel at a time, from nothing to a tall tablet.
     */
    @Test
    public void aWindowGrowingAPixelAtATimeMovesNothingFasterThanTheTurnRate() {
        passes(heights(), GROWS);
    }

    private static final Check GROWS = (where, before, now, density, content, area) -> {
        if (before != null) step(where, before, now, density, FluidLayout.TURN_RATE + 0.01f);
    };

    /**
     * Narrowing or widening a window of any height a pixel at a time (a split screen's divider on a phone on its side)
     * moves nothing in the band or the lines faster than three times its edge (as the band folds, the status words' end
     * pulls in by the buttons' width over the fold's {@link FluidLayout#NARROW_DP}, less the pixel the window's edge
     * moved); and nothing in the stage faster than one and a half times TURN_RATE and once more, since the turn goes by
     * the stage's height, which the buttons' rise into the chips' row (56 dp over {@link FluidLayout#RISE_DP}) changes
     * up to one and a half times as fast as the window's edge (and the skyline, which shows as far as its column's
     * width lets it read, fades as much faster as it turns).
     */
    @Test
    public void aWindowNarrowingAPixelAtATimeMovesNothingFasterThanThreeTimesItsEdge() {
        passes(widths(), NARROWS);
    }

    private static final Check NARROWS = (where, before, now, density, content, area) -> {
        if (before == null) return;
        step(where, before, now, density, 1.5f * FluidLayout.TURN_RATE + 1.01f, 1.5f);
        FluidLayout.Box[] was = boxes(before);
        FluidLayout.Box[] is = boxes(now);
        for (int i = 0; i <= BAND_PARTS; i++) {
            float moved = apart(was[i], is[i]);
            String part = PARTS[i];
            that(moved <= 3.01f, () -> where + ": " + part + " moved " + moved + " px");
        }
    };

    /** As the window grows nothing fades, and the turn goes only one way, towards two tiers. */
    @Test
    public void asTheWindowGrowsNothingFadesAndTheStageTurnsOneWay() {
        passes(heights(), NOTHING_FADES);
    }

    private static final Check NOTHING_FADES = (where, before, now, density, content, area) -> {
        if (before == null) return;
        float[] was = shown(before);
        float[] is = shown(now);
        for (int i = 0; i < was.length; i++) {
            if (is[i] < was[i] - 1e-5f) {
                fail(where + ": the " + SHOWN[i] + " faded as the window grew, from " + was[i] + " to " + is[i]);
            }
        }
        that(now.turn <= before.turn + 1e-5f, () -> where + ": the stage turned back");
        that(now.inner <= before.inner + 1e-5f, () -> where + ": the ground turned back");
    };

    /**
     * As it shrinks, what must go fades least important first: the road, then the radar, the skyline and the map all
     * together, and only at their least (the radar's least circle); the skyline fades by the width alone besides, only
     * as its column narrows in three columns, the same at every height there.
     */
    @Test
    public void whatMustGoFadesLeastImportantFirst() {
        passes(heights(), IN_ORDER);
    }

    private static final Check IN_ORDER = (where, before, now, density, content, area) -> {
        that(now.stageShown >= 1 && now.horizonShown >= 1 || now.roadShown == 0,
                () -> where + ": the stage fades while the road is there");
        that(now.stageShown >= 1 || now.radius <= FluidLayout.RADIUS_LEAST_DP * density + 0.5f,
                () -> where + ": the radar goes only at its least, not at " + now.radius);
        that(now.horizonShown >= 1 || now.inner > 0, () -> where + ": the skyline fades over the map");
        that(before == null || before.inner < 1 || now.inner < 1
                || Math.abs(before.horizonShown - now.horizonShown) <= 1e-6f,
                () -> where + ": in three columns the skyline faded with the height, from " + before.horizonShown
                        + " to " + now.horizonShown);
    };

    /**
     * The radar and the map stand together, wholly there and usable (the radar's knobs at their least circle, the map
     * at its least, {@link #mapLeast}), wherever the stage holds them side by side at their least: from
     * {@link FluidLayout#TUCKED_DP}, the radar's icons tucked in towards its rim, in every page at least 280 dp wide.
     */
    @Test
    public void theRadarAndTheMapStandTogetherWhereverBothFit() {
        for (float density : DENSITIES) {
            FluidLayout layout = solve(density, Math.round(411 * density), 0, CONTENTS.get(0), true);
            assertEquals(FluidLayout.RADIUS_LEAST_DP * density,
                    layout.radiusIn(Float.MAX_VALUE, FluidLayout.TUCKED_DP * density), 0.01f);
        }
        assertEquals(FluidLayout.TUCKED_DP, FluidLayout.MAP_TUCKED_DP + FluidLayout.AREA_LINE_DP, 0);
        passes(heights(), TOGETHER);
    }

    private static final Check TOGETHER = (where, before, now, density, content, area) -> {
        if (now.stage.height() < FluidLayout.TUCKED_DP * density - 0.01f) return;
        that(now.stageShown == 1, () -> where + ": the radar and the map wholly there, stage " + now.stage);
        that(now.radius >= FluidLayout.RADIUS_LEAST_DP * density - 0.5f,
                () -> where + ": the radar's knobs usable, radius " + now.radius);
        float mapLeast = mapLeast(now, area, density);
        that(now.map.height() >= mapLeast - 0.5f && now.map.width() >= FluidLayout.COLUMN_LEAST_DP * density - 0.5f,
                () -> where + ": the map readable " + now.map);
        that(!area || now.area.height() >= FluidLayout.AREA_LINE_DP * density - 0.5f,
                () -> where + ": the area line whole " + now.area);
    };

    /**
     * The map's least height: in three columns its tucked height over its area's line (or the whole tucked height
     * without the line); turning or in two tiers, the strip's least over the line.
     */
    private static float mapLeast(FluidLayout now, boolean area, float density) {
        if (now.turn < 1) return FluidLayout.MAP_LEAST_DP * density;
        return (area ? FluidLayout.MAP_TUCKED_DP : FluidLayout.TUCKED_DP) * density;
    }

    /**
     * All four infographics are wholly there wherever the window is at least 335 dp wide and the stage at least
     * {@link FluidLayout#TUCKED_DP} tall: the radar at its least circle or more, the skyline's caption whole over the
     * skyline at its least, and the map at its least ({@link #mapLeast}), in three columns, two tiers or anywhere
     * between (the counts are the chart's key since 0.6.6). Swept both ways, the window growing taller and wider.
     */
    @Test
    public void allFourWholeWhereverTheyFit() {
        passes(heights(), ALL_FOUR);
        passes(widths(), ALL_FOUR);
    }

    private static final Check ALL_FOUR = (where, before, now, density, content, area) -> {
        if (now.width < WHOLE_FROM_DP * density || now.stage.height() < FluidLayout.TUCKED_DP * density - 0.01f) {
            return;
        }
        that(now.stageShown == 1 && now.horizonShown == 1,
                () -> where + ": shown " + now.stageShown + ", skyline " + now.horizonShown);
        that(now.radius >= FluidLayout.RADIUS_LEAST_DP * density - 0.5f, () -> where + ": radius " + now.radius);
        that(now.caption.height() >= FluidLayout.CAPTION_DP * density - 0.5f
                && now.chart.height() >= FluidLayout.CHART_LEAST_DP * density - 0.5f,
                () -> where + ": the skyline " + now.caption + " " + now.chart);
        float mapLeast = mapLeast(now, area, density);
        that(now.map.height() >= mapLeast - 0.5f, () -> where + ": the map " + now.map);
        that(!area || now.area.height() >= FluidLayout.AREA_LINE_DP * density - 0.5f,
                () -> where + ": the area line " + now.area);
    };

    /**
     * Three columns (the radar, the skyline, the map) while the stage is short, up to {@link FluidLayout#turnsFrom};
     * then the turn; and two tiers once it is over (0.6.8, the owner's mockup): the radar on the left and the skyline's
     * caption over the skyline on the right, side by side at the stage's top, over the map, a strip the page's width.
     */
    @Test
    public void threeColumnsWhenShortTwoTiersWhenTall() {
        passes(heights(), SIDE_BY_SIDE);
    }

    private static final Check SIDE_BY_SIDE = (where, before, now, density, content, area) -> {
        that(now.stage.height() > now.turnsFrom() || now.turn == 1,
                () -> where + ": three columns, not turned " + now.turn);
        if (now.stageShown <= 0) return;
        that(now.turn < 1 || now.radar.left == 0 && now.radar.right <= now.caption.left + 0.01f
                && now.caption.right <= now.map.left + 0.01f && now.map.right == now.width
                && now.radar.top == now.stage.top && now.caption.top == now.stage.top && now.map.top == now.stage.top
                && Math.abs(now.chart.bottom - now.stage.bottom) <= 0.01f
                && Math.abs(now.area.bottom - now.stage.bottom) <= 0.01f,
                () -> where + ": three columns " + now.radar + " " + now.caption + " " + now.chart + " " + now.map);
        that(now.radar.left == 0 && now.radar.top == now.stage.top && now.radar.right <= now.caption.left + 0.01f,
                () -> where + ": the radar at the top on the left " + now.radar + " " + now.caption);
        that(now.turn > 0 || Math.abs(now.caption.left - now.radar.right) <= 0.01f && now.caption.right == now.width
                && now.caption.top == now.stage.top && Math.abs(now.chart.bottom - now.radar.bottom) <= 0.01f
                && now.map.left == 0 && now.map.right == now.width
                && Math.abs(now.map.top - now.radar.bottom) <= 0.01f
                && Math.abs(now.area.bottom - now.stage.bottom) <= 0.01f,
                () -> where + ": two tiers " + now.radar + " " + now.caption + " " + now.chart + " " + now.map);
    };

    /** The road comes only once the turn is over: wherever there is a road, the stage stands in two tiers. */
    @Test
    public void theRoadWaitsForTheTurn() {
        passes(heights(), ROAD_AFTER);
    }

    private static final Check ROAD_AFTER = (where, before, now, density, content, area) ->
            that(now.roadShown == 0 || now.turn == 0 && now.inner == 0,
                    () -> where + ": a road while the stage turns, " + now.turn);

    /**
     * No two parts overlap, nothing stands outside the page, and the page is the window's height, or taller only by
     * what never goes (the band and the lines) with everything else gone. Swept both ways; across, from the narrowest
     * window whose band holds the header's buttons whole.
     */
    @Test
    public void nothingOverlapsAndNothingLeavesThePage() {
        passes(heights(), APART);
        passes(widths(), APART_ACROSS);
    }

    private static final Check APART = (where, before, now, density, content, area) -> {
        FluidLayout.Box[] boxes = boxes(now);
        for (int i = 0; i < boxes.length; i++) {
            FluidLayout.Box a = boxes[i];
            if (a.left < -0.01f || a.right > now.width + 0.01f || a.top < -0.01f || a.bottom > now.total + 0.51f
                    || a.width() < -0.01f || a.height() < -0.01f) {
                fail(where + ": " + PARTS[i] + " outside the page " + a);
            }
            for (int j = i + 1; j < boxes.length; j++) {
                FluidLayout.Box b = boxes[j];
                float across = Math.min(a.right, b.right) - Math.max(a.left, b.left);
                float down = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
                if (across > 0.01f && down > 0.01f) {
                    fail(where + ": " + PARTS[i] + " " + a + " overlaps " + PARTS[j] + " " + b);
                }
            }
        }
        float fixed = now.band + now.linesHeight;
        if (now.height >= fixed) {
            that(now.total == now.height, () -> where + ": the window's height, not " + now.total);
        } else {
            that(now.total == Math.round(fixed) && now.stageShown == 0,
                    () -> where + ": only what never goes, " + now.total);
        }
    };

    private static final Check APART_ACROSS = (where, before, now, density, content, area) -> {
        if (now.width >= Math.round(content.narrowestDp() * density)) {
            APART.at(where, before, now, density, content, area);
        }
    };

    /**
     * The band never moves with the window's height; where the window is wide enough not to fold it, the mascot
     * stands where it always has, 64 dp at (12, 8), and the tile 12 dp past it wherever the counts leave it the room.
     */
    @Test
    public void theStripStaysPut() {
        passes(heights(), STAYS);
    }

    private static final Check STAYS = (where, before, now, density, content, area) -> {
        if (before != null) {
            FluidLayout.Box[] was = boxes(before);
            FluidLayout.Box[] is = boxes(now);
            for (int i = 0; i < BAND_PARTS; i++) {
                if (apart(was[i], is[i]) != 0) {
                    fail(where + ": the " + PARTS[i] + " moved with the height, " + was[i] + " to " + is[i]);
                }
            }
            that(before.band == now.band, () -> where + ": the band's height moved with the window's");
        }
        if (now.narrow > 0) return;
        that(Math.abs(now.mascot.left - FluidLayout.MASCOT_LEFT_DP * density) < 0.01f
                && Math.abs(now.mascot.top - FluidLayout.STRIP_TOP_DP * density) < 0.01f
                && Math.abs(now.mascot.width() - FluidLayout.MASCOT_DP * density) < 0.01f
                && Math.abs(now.tile.left - Math.min(now.mascot.right + FluidLayout.TILE_GAP_DP * density,
                        now.counts.left)) < 0.01f,
                () -> where + ": the mascot " + now.mascot + ", the tile " + now.tile);
    };

    /**
     * The band folds by the window's width alone: the mascot into its corner over {@link FluidLayout#NARROW_DP} up to
     * where the buttons fit beside the chips at their widest, then the buttons rising into the chips' row over
     * {@link FluidLayout#RISE_DP}, five buttons up to {@link FluidLayout#FULL_SCREEN_EXTRA_DP} later; the chips keep
     * their widest from where the fold begins; the header keeps its whole width wherever the window holds it and never
     * overlaps the chips. At the normal font the buttons rise from 318 dp beside Dasher, 370 dp beside another app and
     * 486 dp in a full screen; where a readable tile leaves them the room, they lift on into the tile's row (0.6.8).
     */
    @Test
    public void theBandFoldsByWidthOnly() {
        for (float density : DENSITIES) {
            for (int buttons : BUTTONS) {
                int risesFrom = buttons == 3 ? 318 : buttons == 4 ? 370 : 486;
                Content content = new Content(0, buttons, 0);
                FluidLayout rising = solve(density, Math.round((risesFrom + FluidLayout.RISE_DP / 2) * density), 600,
                        content, true);
                assertEquals(content + ", density " + density, 0.5f, rising.rise, 0.02f);
                assertEquals(1, rising.narrow, 0);
                FluidLayout folding = solve(density, Math.round((risesFrom - FluidLayout.NARROW_DP / 2) * density),
                        600, content, true);
                assertEquals(content + ", density " + density, 0.5f, folding.narrow, 0.02f);
                assertEquals(0, folding.rise, 0);
            }
        }
        passes(widths(), FOLDS);
    }

    private static final Check FOLDS = (where, before, now, density, content, area) -> {
        float from = risesFrom(now, density);
        float rise = Math.max(0, Math.min(1, (now.width - from) / (FluidLayout.RISE_DP * density)));
        float narrow = Math.max(0, Math.min(1, (now.width - from + FluidLayout.NARROW_DP * density)
                / (FluidLayout.NARROW_DP * density)));
        that(Math.abs(now.rise - rise) <= 1e-5f && Math.abs(now.narrow - narrow) <= 1e-5f,
                () -> where + ": rise " + now.rise + " not " + rise + ", narrow " + now.narrow + " not " + narrow);
        that(now.width < from - FluidLayout.NARROW_DP * density || now.chipRow.width() >= now.chipsMost - 0.01f,
                () -> where + ": the chips squeezed, " + now.chipRow);
        float across = Math.min(now.chipRow.right, now.header.right) - Math.max(now.chipRow.left, now.header.left);
        float down = Math.min(now.chipRow.bottom, now.header.bottom) - Math.max(now.chipRow.top, now.header.top);
        that(across <= 0.01f || down <= 0.01f, () -> where + ": the header " + now.header + " over the chips "
                + now.chipRow);
        float whole = now.buttonsWidth + FluidLayout.BAND_GAP_DP * density;
        that(now.width < whole + FluidLayout.BAND_SIDE_DP * density
                || Math.abs(now.header.width() - whole) <= 0.01f && now.header.right <= now.width + 0.01f,
                () -> where + ": the header squeezed, " + now.header);
        that(now.lift > 0 || Math.abs(now.header.bottom - now.band) <= 0.01f
                && now.header.top >= now.chipRow.top - 0.01f, () -> where + ": the header outside the band, "
                + now.header);
        that(now.header.top >= -0.01f && now.header.bottom <= now.band + 0.01f,
                () -> where + ": the header outside the band, " + now.header);
        float overTile = Math.min(now.tile.right, now.header.right) - Math.max(now.tile.left, now.header.left);
        float besideTile = Math.min(now.tile.bottom, now.header.bottom) - Math.max(now.tile.top, now.header.top);
        that(overTile <= 0.01f || besideTile <= 0.01f, () -> where + ": the header " + now.header + " over the tile "
                + now.tile);
    };

    /**
     * The road waits for room as if the buttons stood in the chips' row: it is the same at every width from where the
     * mascot has folded into its corner (the buttons rising or risen beside the chips) and at every width too narrow to
     * fold the band at all, and in between only grows as the window widens, by what the mascot's fold gives back.
     */
    @Test
    public void theRoadNeverMovesWithWidth() {
        passes(widths(), ROAD_STAYS);
    }

    private static final Check ROAD_STAYS = (where, before, now, density, content, area) -> {
        if (before == null) return;
        float was = before.road.height();
        float is = now.road.height();
        if (before.narrow == 1 && now.narrow == 1 || before.narrow == 0 && now.narrow == 0) {
            that(Math.abs(was - is) <= 1e-3f, () -> where + ": the road moved with the width, from " + was + " to "
                    + is);
        } else {
            that(is >= was - 1e-3f, () -> where + ": the road shrank as the window widened, from " + was + " to "
                    + is);
        }
    };

    /** The words of a box in {@link #windowsAThroughF}: its name, then its left and top, width by height, in dp. */
    private static final Pattern BOX = Pattern.compile("(\\w+) (-?[\\d.]+),(-?[\\d.]+) ([\\d.]+)x([\\d.]+)");

    /**
     * One window at a common phone's density, against what each part should be within half a dp: {@code expected}
     * names a box with its left and top, width by height ("tile 56,0 169.1x48"), or one measure ("radius 56.2",
     * "gap 12", "band 104" and "stage 132.2" in dp; "narrow", "rise", "turn" and "inner" from 0 to 1; "shown" the radar
     * and the map's, the skyline's and the road's).
     */
    private static void window(String name, int widthDp, int heightDp, int buttons, int font, int linesDp,
            String... expected) {
        float density = 2.625f;
        FluidLayout layout = solve(density, Math.round(widthDp * density), Math.round(heightDp * density),
                new Content(font, buttons, linesDp), true);
        for (String one : expected) {
            Matcher box = BOX.matcher(one);
            if (box.matches()) {
                FluidLayout.Box at = box(layout, box.group(1));
                float[] want = {Float.parseFloat(box.group(2)), Float.parseFloat(box.group(3)),
                    Float.parseFloat(box.group(4)), Float.parseFloat(box.group(5))};
                float[] got = {at.left / density, at.top / density, at.width() / density, at.height() / density};
                for (int i = 0; i < want.length; i++) {
                    assertEquals(name + ": " + one + ", not " + Arrays.toString(got), want[i], got[i], 0.5f);
                }
                continue;
            }
            String[] words = one.split(" ");
            switch (words[0]) {
                case "radius": near(name, one, layout.radius / density, words[1], 0.5f); break;
                case "gap": near(name, one, layout.iconGap / density, words[1], 0.5f); break;
                case "band": near(name, one, layout.band / density, words[1], 0.5f); break;
                case "stage": near(name, one, layout.stage.height() / density, words[1], 0.5f); break;
                case "narrow": near(name, one, layout.narrow, words[1], 0.01f); break;
                case "rise": near(name, one, layout.rise, words[1], 0.01f); break;
                case "turn": near(name, one, layout.turn, words[1], 0.01f); break;
                case "inner": near(name, one, layout.inner, words[1], 0.01f); break;
                case "shown":
                    near(name, one, layout.stageShown, words[1], 0.01f);
                    near(name, one, layout.horizonShown, words[2], 0.01f);
                    near(name, one, layout.roadShown, words[3], 0.01f);
                    break;
                default: fail(name + ": no measure " + one);
            }
        }
    }

    private static void near(String name, String one, float got, String want, float within) {
        assertEquals(name + ": " + one + ", not " + got, Float.parseFloat(want), got, within);
    }

    private static FluidLayout.Box box(FluidLayout layout, String name) {
        switch (name) {
            case "mascot": return layout.mascot;
            case "tile": return layout.tile;
            case "counts": return layout.counts;
            case "chips": return layout.chipRow;
            case "header": return layout.header;
            case "lines": return layout.lines;
            case "radar": return layout.radar;
            case "caption": return layout.caption;
            case "chart": return layout.chart;
            case "map": return layout.map;
            case "area": return layout.area;
            case "road": return layout.road;
            default: throw new AssertionError("no part " + name);
        }
    }

    /**
     * The windows the layout was designed at, each part where it should be: A, 411 by 236 dp beside Dasher (three
     * buttons in the tile's row), in three columns, with no line, with a 48 dp line and beside another app (four
     * buttons, under the chips); B, 360 by 220 dp, and B' beside another app, where the band cannot fold and the stage
     * fades to half; C, A at twice the font; D1 and D2, A taller, D2 the owner's mockup's 411 by 416 dp turning towards
     * two tiers with the area's line shown (without it, two tiers: {@link #theMockupsWindowStandsInTwoTiers}); E1, a
     * full screen (five buttons) at the normal and twice the font, two tiers with the road, and E2, shorter, two tiers
     * without it; F and F4, a phone on its side, turning.
     */
    @Test
    public void windowsAThroughF() {
        window("A", 411, 236, 3, 0, 0, "mascot 4,0 48x48", "tile 56,0 183x48", "counts 243,0 0x48", "chips 4,48 239x56",
                "header 243,0 164x56", "lines 0,104 411x0", "radar 0,104 135x116", "caption 135,104 138x48",
                "chart 135,152 138x84.2", "map 273,104 138x84.2", "area 273,188.2 138x48", "road 0,236.2 411x0",
                "radius 56", "gap 4", "narrow 1.00", "rise 1.00", "band 104", "stage 132.2", "turn 1.000",
                "inner 1.000", "shown 1.00 1.00 0.00");
        window("A with a line", 411, 236, 3, 0, 48, "mascot 4,0 48x48", "tile 56,0 183x48", "counts 243,0 0x48",
                "chips 4,48 239x56", "header 243,0 164x56", "lines 0,104 411x48", "radar 0,152 135x84.2",
                "caption 135,152 138x48", "chart 135,200 138x36.2", "map 273,152 138x36.2", "area 273,188.2 138x48",
                "road 0,236.2 411x0", "radius 24.2", "gap 4", "narrow 1.00", "rise 1.00", "band 104", "stage 84.2",
                "turn 1.000", "inner 1.000", "shown 0.73 1.00 0.00");
        window("A4", 411, 236, 4, 0, 0, "mascot 4,0 48x48", "tile 56,0 158.3x48", "counts 218.3,0 0x48",
                "chips 4,48 186.9x56", "header 190.9,48 216.2x56", "lines 0,104 411x0", "radar 0,104 135x116",
                "caption 135,104 138x48", "chart 135,152 138x84.2", "map 273,104 138x84.2", "area 273,188.2 138x48",
                "road 0,236.2 411x0", "radius 56", "gap 4", "narrow 1.00", "rise 1.00", "band 104", "stage 132.2",
                "turn 1.000", "inner 1.000", "shown 1.00 1.00 0.00");
        window("B", 360, 220, 3, 0, 0, "mascot 4,0 48x48", "tile 56,0 156x48", "counts 216,0 0x48", "chips 4,48 188x56",
                "header 192,48 164x56", "lines 0,104 360x0", "radar 0,104 135x116", "caption 135,104 112.5x48",
                "chart 135,152 112.5x68.2", "map 247.5,104 112.5x68.2", "area 247.5,172.2 112.5x48",
                "road 0,220.2 360x0", "radius 56", "gap 4", "narrow 1.00", "rise 1.00", "band 104", "stage 116.2",
                "turn 1.000", "inner 1.000", "shown 1.00 1.00 0.00");
        window("B'", 360, 220, 4, 0, 0, "mascot 5.2,1.2 50.4x50.4", "tile 60.9,2.4 255.5x48", "counts 320.4,2.4 0x48",
                "chips 5.2,51.6 167.4x56", "header 139.8,107.6 216.2x56", "lines 0,163.6 360x0",
                "radar 0,163.6 135x56.5", "caption 135,163.6 112.5x48", "chart 135,211.6 112.5x8.5",
                "map 247.5,163.6 112.5x8.5", "area 247.5,172.2 112.5x48", "road 0,220.2 360x0", "radius 0", "gap 4",
                "narrow 0.85", "rise 0.00", "band 163.6", "stage 56.5", "turn 1.000", "inner 1.000",
                "shown 0.49 1.00 0.00");
        window("C", 411, 236, 3, 1, 0, "mascot 4,2.1 48x48", "tile 56,0 185.9x52.2", "counts 245.9,2.1 0x48",
                "chips 4,52.2 239x56", "header 243,53.5 164x56", "lines 0,109.5 411x0", "radar 0,109.5 135x116",
                "caption 135,109.5 138x48", "chart 135,157.5 138x78.7", "map 273,109.5 138x78.7",
                "area 273,188.2 138x48", "road 0,236.2 411x0", "radius 56", "gap 4", "narrow 1.00", "rise 0.98",
                "band 109.5", "stage 126.7", "turn 1.000", "inner 1.000", "shown 1.00 1.00 0.00");
        window("D1", 411, 300, 3, 0, 0, "mascot 4,0 48x48", "tile 56,0 183x48", "counts 243,0 0x48",
                "chips 4,48 239x56", "header 243,0 164x56", "lines 0,104 411x0", "radar 0,104 135x116",
                "caption 135,104 138x48", "chart 135,152 138x148.2", "map 273,104 138x148.2", "area 273,252.2 138x48",
                "road 0,300.2 411x0", "radius 56", "gap 4", "narrow 1.00", "rise 1.00", "band 104", "stage 196.2",
                "turn 1.000", "inner 1.000", "shown 1.00 1.00 0.00");
        window("D2", 411, 416, 3, 0, 0, "mascot 4,0 48x48", "tile 56,0 183x48", "counts 243,0 0x48",
                "chips 4,48 239x56", "header 243,0 164x56", "lines 0,104 411x0", "radar 0,104 183.5x176",
                "caption 183.5,104 227.6x48", "chart 183.5,152 227.6x128", "map 153.3,280 257.8x88",
                "area 153.3,368 257.8x48", "road 0,416 411x0", "radius 84", "gap 12", "narrow 1.00", "rise 1.00",
                "band 104", "stage 312", "turn 0.258", "inner 0.258", "shown 1.00 1.00 0.00");
        window("E1", 411, 860, 5, 0, 0, "mascot 12,8 64x64", "tile 88,16 315x48", "counts 407,16 0x48",
                "chips 12,72 395x56", "header 139,128 268x56", "lines 0,184 411x0", "radar 0,184 205.5x201.4",
                "caption 205.5,184 205.5x48", "chart 205.5,232 205.5x153.4", "map 0,385.4 411x348.8",
                "area 0,734.2 411x48", "road 0,782.2 411x78", "radius 96.7", "gap 12", "narrow 0.00", "rise 0.00",
                "band 184", "stage 598.2", "turn 0.000", "inner 0.000", "shown 1.00 1.00 1.00");
        window("E1 at twice the font", 411, 860, 5, 1, 0, "mascot 12,8 64x64", "tile 88,13.9 315x52.2",
                "counts 407,16 0x48", "chips 12,72 395x56", "header 139,128 268x56", "lines 0,184 411x0",
                "radar 0,184 205.5x201.4", "caption 205.5,184 205.5x48", "chart 205.5,232 205.5x153.4",
                "map 0,385.4 411x348.8", "area 0,734.2 411x48", "road 0,782.2 411x78", "radius 96.7", "gap 12",
                "narrow 0.00", "rise 0.00", "band 184", "stage 598.2", "turn 0.000", "inner 0.000",
                "shown 1.00 1.00 1.00");
        window("E2", 411, 600, 5, 0, 0, "mascot 12,8 64x64", "tile 88,16 315x48", "counts 407,16 0x48",
                "chips 12,72 395x56", "header 139,128 268x56", "lines 0,184 411x0", "radar 0,184 205.5x201.4",
                "caption 205.5,184 205.5x48", "chart 205.5,232 205.5x153.4", "map 0,385.4 411x166.6",
                "area 0,552 411x48", "road 0,600 411x0", "radius 96.7", "gap 12", "narrow 0.00", "rise 0.00",
                "band 184", "stage 416", "turn 0.000", "inner 0.000", "shown 1.00 1.00 0.00");
        window("F", 600, 400, 3, 0, 0, "mascot 4,0 48x48", "tile 56,0 372x48", "counts 432,0 0x48", "chips 4,48 428x56",
                "header 432,0 164x56", "lines 0,104 600x0", "radar 0,104 173.1x164", "caption 173.1,104 289.4x48",
                "chart 173.1,152 289.4x116", "map 310.5,268 289.5x84", "area 310.5,352 289.5x48", "road 0,400 600x0",
                "radius 78", "gap 12", "narrow 1.00", "rise 1.00", "band 104", "stage 296", "turn 0.402", "inner 0.402",
                "shown 1.00 1.00 0.00");
        window("F4", 600, 400, 4, 0, 0, "mascot 4,0 48x48", "tile 56,0 319.8x48", "counts 379.8,0 0x48",
                "chips 4,48 375.8x56", "header 379.8,0 216.2x56", "lines 0,104 600x0", "radar 0,104 173.1x164",
                "caption 173.1,104 289.4x48", "chart 173.1,152 289.4x116", "map 310.5,268 289.5x84",
                "area 310.5,352 289.5x48", "road 0,400 600x0", "radius 78", "gap 12", "narrow 1.00", "rise 1.00",
                "band 104", "stage 296", "turn 0.402", "inner 0.402", "shown 1.00 1.00 0.00");
    }

    /**
     * The owner's mockup's window (12 October 2026): his half of a split screen beside Dasher, 411 by 416 dp, at the
     * normal font. The header's three buttons stand in the tile's row; the stage stands in two tiers, the radar beside
     * the skyline over a map strip at least 380 dp wide, the radar at least half as wide as the page less a little.
     */
    @Test
    public void theMockupsWindowStandsInTwoTiers() {
        for (float density : DENSITIES) {
            FluidLayout layout = solve(density, Math.round(411 * density), Math.round(416 * density), CONTENTS.get(0),
                    false);
            String where = "411 by 416 dp at density " + density;
            assertEquals(where + ": the buttons in the tile's row", 1, layout.lift, 0);
            assertTrue(where + ": the header beside the tile " + layout.header + " " + layout.tile,
                    layout.header.top < layout.tile.bottom && layout.header.left >= layout.tile.right);
            assertEquals(where + ": two tiers", 0, layout.turn, 0);
            assertTrue(where + ": the radar beside the skyline " + layout.radar + " " + layout.chart,
                    layout.radar.right <= layout.chart.left + 0.01f && layout.radar.top == layout.caption.top
                    && layout.radar.width() >= 180 * density);
            assertTrue(where + ": the map a strip under both " + layout.map, layout.map.top >= layout.radar.bottom
                    - 0.01f && layout.map.top >= layout.chart.bottom - 0.01f && layout.map.width() >= 380 * density
                    && layout.map.height() >= FluidLayout.MAP_LEAST_DP * density - 0.5f);
            assertEquals(where + ": all of it there", 1, layout.stageShown * layout.horizonShown, 0);
        }
    }

    /**
     * The radar's icons tuck in towards its rim so its circle keeps its least in a short radar: from 132 dp down to
     * {@link FluidLayout#TUCKED_DP} the radius stays {@link FluidLayout#RADIUS_LEAST_DP}, the icons' gap goes from
     * {@link FluidLayout#ICON_GAP_DP} to {@link FluidLayout#ICON_GAP_LEAST_DP}, half a dp for each dp, and the upper
     * icons stay at least {@link FluidLayout#ICON_EDGE_DP} inside the radar's box. With the whole gap, from 132 dp up,
     * the circle and the radar's height for it are the ones they were before the tuck (0.6.2).
     */
    @Test
    public void theTuckKeepsTheCircle() {
        float sin = (float) Math.sin(Math.toRadians(MinimumsStarView.SPREAD));
        float cos = (float) Math.cos(Math.toRadians(MinimumsStarView.SPREAD));
        for (float density : DENSITIES) {
            FluidLayout layout = solve(density, Math.round(411 * density), 0, CONTENTS.get(0), true);
            float wide = 1000 * density;
            for (float heightDp = FluidLayout.TUCKED_DP; heightDp <= 132; heightDp += 0.25f) {
                float height = heightDp * density;
                float radius = layout.radiusIn(wide, height);
                float gap = layout.gapFor(height);
                String where = heightDp + " dp tall at density " + density;
                assertEquals(where, FluidLayout.RADIUS_LEAST_DP * density, radius, 0.01f);
                assertEquals(where, ((heightDp - FluidLayout.TUCKED_DP) / 2 + FluidLayout.ICON_GAP_LEAST_DP) * density,
                        gap, 0.01f);
                // The upper icons' top: past the upper spoke's end by the gap and the icon itself, from the middle.
                float iconTop = height / 2 - sin * radius - gap - FluidLayout.ICON_DP * density;
                assertTrue(where + ": the icon " + iconTop / density + " dp inside",
                        iconTop >= FluidLayout.ICON_EDGE_DP * density - 0.01f);
                assertTrue(where + ": the rim", height / 2 - radius >= layout.rimEdge(gap) - 0.01f);
            }
            // Before the tuck: a 4 dp edge, and the icons 12 + 22 dp above the upper spokes' ends.
            float edge = 4 * density;
            float up = (12 + 22) * density;
            for (float heightDp = 132; heightDp <= 400; heightDp += 0.5f) {
                float height = heightDp * density;
                assertEquals(FluidLayout.ICON_GAP_DP * density, layout.gapFor(height), 0.001f);
                float tall = (height - 2 * edge) / 2;
                float upright = tall >= up / (1 - sin) ? tall : (tall - up) / sin;
                float before = Math.max(0, Math.min((wide / 2 - (4 + 11 + 4) * density) / cos, upright));
                assertEquals(heightDp + " dp tall", before, layout.radiusIn(wide, height), 0.01f);
            }
            for (float radiusDp = 0; radiusDp <= 200; radiusDp += 0.5f) {
                float radius = radiusDp * density;
                float before = 2 * Math.max(radius, sin * radius + up) + 2 * edge;
                assertEquals(radiusDp + " dp", before, layout.radarHeight(radius), 0.01f);
            }
        }
    }

    // The sweeps' checks, each run on every window of its sweep (declared last, after every check they hold).
    private static final List<Check> HEIGHT_CHECKS = List.of(GROWS, NOTHING_FADES, IN_ORDER, TOGETHER, ALL_FOUR,
            SIDE_BY_SIDE, ROAD_AFTER, APART, STAYS);
    private static final List<Check> WIDTH_CHECKS = List.of(NARROWS, ALL_FOUR, APART_ACROSS, FOLDS, ROAD_STAYS);
}
