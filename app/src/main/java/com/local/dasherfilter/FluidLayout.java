package com.local.dasherfilter;

/**
 * Where each part of the homepage stands in one window: one fluid layout, the same at every size (the owner, 7 October
 * 2026, on 0.5.1's split screen: "I do not like how it only shows the map or the radar"; "it should be gradual and
 * seamless"). From the top: the strip (the mascot, the latest offer's verdict, Autopilot's chip and the status line),
 * the header's buttons, the dash's counts, the lines that need the user, then the stage, and the road along the
 * bottom. The stage holds the minimums' constellation (the radar) and the ground: the skyline's caption, the skyline
 * and the offer map, with the line naming the map's chosen area under it.
 *
 * <p>Every size and place is a continuous function of the window's width and height, with no threshold anywhere.
 * Room beyond what every part reads well at goes to the radar, the skyline and the map by weight. As the window
 * shrinks, what must go shrinks and fades out over a range of heights as long as its own, least important first: the
 * road, the counts, then the skyline to its least, the radar and the map to a little less, the skyline with its
 * caption, the radar and the map to their least, and last the radar and the map together. A part is used (touched,
 * heard) only while it is wholly there; the strip, the header and the lines never go, and below what they need the
 * page scrolls.
 *
 * <p>The radar and the ground share the stage: the radar above the ground while the room under the lines is tall, side
 * by side (the radar on the left) while it is wide, and between the two a gradual turn rather than a flip. From side by
 * side, as the window grows taller, the radar's column shortens from below while the ground's moves down beside it;
 * then the radar's widens to the right over the ground, whose own widens to the left under it, until the two stand one
 * above the other. The turn takes a range of heights long enough that nothing in it moves more than {@link #TURN_RATE}
 * times as fast as the window's edge.
 *
 * <p>Pure arithmetic in pixels with no Android type, so every window size can be checked.
 */
final class FluidLayout {
    /** The rows' side padding, and the room above and below the strip. */
    static final float SIDE_DP = 16;
    static final float STRIP_TOP_DP = 8;
    static final float STRIP_BOTTOM_DP = 4;
    /** The mascot's ring across, at the strip's left, and the room between it and the words. */
    static final float MASCOT_DP = 64;
    static final float MASCOT_LEFT_DP = 12;
    static final float WORDS_GAP_DP = 12;
    /** The parts' heights where each reads well (the most a part that fades takes), and their least. */
    static final float COUNTS_DP = FilterHeroView.COUNTS_HEIGHT_DP;
    static final float ROAD_DP = 78;
    static final float CAPTION_DP = 48;
    static final float CHART_DP = 80;
    static final float CHART_LEAST_DP = 56;
    static final float MAP_DP = 120;
    static final float MAP_MIDDLE_DP = 96;
    static final float MAP_LEAST_DP = 84;
    static final float AREA_LINE_DP = 48;
    /** The radar's radius where it reads well, a little less, and the least its knobs work at. */
    static final float RADIUS_DP = 84;
    static final float RADIUS_MIDDLE_DP = 72;
    static final float RADIUS_LEAST_DP = 56;
    /** How room beyond what every part reads well at is shared. */
    static final float RADAR_WEIGHT = 1.7f;
    static final float CHART_WEIGHT = 0.7f;
    static final float MAP_WEIGHT = 1f;
    /**
     * The radar and the ground stand side by side while the room under the lines is at most {@link #SIDE_FROM_DP} and
     * {@link #SIDE_SHARE} of the page's width tall; taller, they turn towards one above the other.
     */
    static final float SIDE_FROM_DP = 180;
    static final float SIDE_SHARE = 0.5f;
    /** The fastest any part moves while the stage turns, against the window's edge. */
    static final float TURN_RATE = 2f;
    /** Side by side, the ground keeps at least this width (its skyline's buildings), and the radar at most this share. */
    static final float SIDE_GROUND_LEAST_DP = 200;
    static final float SIDE_RADAR_MOST = 0.6f;
    /** The radar's spokes stand 30 degrees above and below level; its icons stand this far past their ends. */
    private static final float COS = (float) Math.cos(Math.toRadians(MinimumsStarView.SPREAD));
    private static final float SIN = (float) Math.sin(Math.toRadians(MinimumsStarView.SPREAD));
    private static final float ICON_ACROSS_DP = 4 + 11 + 4;
    private static final float ICON_UP_DP = 12 + 22;
    private static final float RADAR_EDGE_DP = 4;

    /** A box in the page's pixels. */
    static final class Box {
        float left;
        float top;
        float right;
        float bottom;

        void set(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        float width() {
            return right - left;
        }

        float height() {
            return bottom - top;
        }

        @Override public String toString() {
            return "[" + Math.round(left) + "," + Math.round(top) + "][" + Math.round(right) + ","
                    + Math.round(bottom) + "]";
        }
    }

    private final float density;

    // ---- What the page measured, in pixels ----
    /** The page's width, and the height it is given (zero or less: as short as it can be). */
    int width;
    int height;
    /** The strip's words (the verdict, then the chip and the status line) at {@link #wordsWidth}. */
    int wordsHeight;
    int headerHeight;
    int linesHeight;
    /** Whether the line naming the map's chosen area is shown (it then stands under the map). */
    boolean areaLine;

    // ---- Where everything stands, in pixels ----
    final Box strip = new Box();
    final Box mascot = new Box();
    final Box words = new Box();
    final Box header = new Box();
    /** The counts' row, and the counts as they are drawn at full size in it. */
    final Box countsRow = new Box();
    final Box counts = new Box();
    float countsSpacing;
    final Box lines = new Box();
    final Box stage = new Box();
    final Box radar = new Box();
    final Box caption = new Box();
    final Box chart = new Box();
    final Box map = new Box();
    final Box area = new Box();
    final Box road = new Box();
    /** The radar's circle in the page: its middle and radius. */
    float radarX;
    float radarY;
    float radius;
    /** How much of each part that can fade is there (1: wholly, 0: gone). */
    float countsShown;
    float horizonShown;
    float stageShown;
    float roadShown;
    /** From one above the other (0) to side by side (1). */
    float turn;
    /** The page's whole height: the height given, or what never goes when that is more. */
    int total;

    FluidLayout(float density) {
        this.density = density;
    }

    private float dp(float value) {
        return value * density;
    }

    /** How wide the strip's words are in a page {@code pageWidth} pixels wide. */
    int wordsWidth(int pageWidth) {
        return Math.max(0, Math.round(pageWidth - dp(MASCOT_LEFT_DP + MASCOT_DP + WORDS_GAP_DP + SIDE_DP)));
    }

    /** What never goes: the strip, the header and the lines. */
    float fixedHeight() {
        return stripHeight() + headerHeight + linesHeight;
    }

    private float stripHeight() {
        return Math.max(dp(MASCOT_DP), wordsHeight) + dp(STRIP_TOP_DP + STRIP_BOTTOM_DP);
    }

    // ---- The radar's own box: its circle, the spokes' icons above and below them, and a little edge. ----

    /** The height the radar's box needs for a circle of {@code radius}. */
    float radarHeight(float radius) {
        return 2 * Math.max(radius, SIN * radius + dp(ICON_UP_DP)) + 2 * dp(RADAR_EDGE_DP);
    }

    /** The width it needs: the spokes' icons inside the box (the circle's rim may run past its sides, faded). */
    float radarWidth(float radius) {
        return 2 * (COS * radius + dp(ICON_ACROSS_DP));
    }

    /** The radar's box height where a column {@code width} wide stops its circle growing. */
    private float tallest(float width) {
        return radarHeight(radiusIn(width, Float.MAX_VALUE));
    }

    /** The largest circle a box {@code width} by {@code height} holds; 0 for none. */
    float radiusIn(float width, float height) {
        float across = (width / 2 - dp(ICON_ACROSS_DP)) / COS;
        float edge = dp(RADAR_EDGE_DP);
        // Above the circle's top or above the upper icons, whichever stands higher (the rim from 68 dp on).
        float tall = (height - 2 * edge) / 2;
        float upright = tall >= dp(ICON_UP_DP) / (1 - SIN) ? tall : (tall - dp(ICON_UP_DP)) / SIN;
        return Math.max(0, Math.min(across, upright));
    }

    // ---- The stage's steps, from where every part reads well to the least, as the window shrinks ----

    /**
     * The radar's height at each step: where it reads well, still so while the skyline comes to its least, a little
     * less, still so while the skyline and its caption shrink away, and its least.
     */
    private float[] radarSteps() {
        float most = radarHeight(dp(RADIUS_DP));
        float middle = radarHeight(dp(RADIUS_MIDDLE_DP));
        return new float[] {most, most, middle, middle, radarHeight(dp(RADIUS_LEAST_DP))};
    }

    private float areaHeight() {
        return areaLine ? dp(AREA_LINE_DP) : 0;
    }

    /**
     * The ground's height (the caption, the skyline, the map and its area's line) at each of the same steps. The map
     * takes its area's line's room while the line is not shown, so the steps are the same either way.
     */
    private float[] groundSteps() {
        float area = dp(AREA_LINE_DP);
        float horizon = dp(CAPTION_DP + CHART_LEAST_DP);
        return new float[] {dp(CAPTION_DP + CHART_DP + MAP_DP) + area, horizon + dp(MAP_DP) + area,
                horizon + dp(MAP_MIDDLE_DP) + area, dp(MAP_MIDDLE_DP) + area, dp(MAP_LEAST_DP) + area};
    }

    /**
     * The ground in a column {@code height} tall: {caption, skyline, how much of the skyline is there}; the map takes
     * the rest, over its area's line. Taller than every part reads well at, the room goes to the skyline and the map
     * by weight; shorter, the skyline comes to its least, the map to a little less, the skyline and its caption shrink
     * away, and the map to its least and below.
     */
    private float[] groundColumn(float height) {
        float[] b = groundSteps();
        float caption = dp(CAPTION_DP);
        float chartLeast = dp(CHART_LEAST_DP);
        if (height >= b[0]) {
            float share = CHART_WEIGHT / (CHART_WEIGHT + MAP_WEIGHT);
            return new float[] {caption, dp(CHART_DP) + (height - b[0]) * share, 1};
        }
        if (height >= b[1]) return new float[] {caption, chartLeast + height - b[1], 1};
        if (height >= b[2]) return new float[] {caption, chartLeast, 1};
        if (height >= b[3]) {
            float shown = (height - b[3]) / (caption + chartLeast);
            return new float[] {caption * shown, chartLeast * shown, shown};
        }
        return new float[] {0, 0, 0};
    }

    /**
     * The radar and the ground one above the other, sharing {@code height}: {radar, ground}. Each step takes the radar
     * and the ground along together, so they come to a little less together and to their least together; room beyond
     * every part's ease goes to both by weight, but to the radar only as far as the page's width lets its circle grow.
     */
    private float[] stacked(float height) {
        float[] a = radarSteps();
        float[] b = groundSteps();
        float[] n = new float[a.length];
        for (int i = 0; i < a.length; i++) n[i] = a[i] + b[i];
        if (height >= n[0]) {
            float more = height - n[0];
            float share = RADAR_WEIGHT / (RADAR_WEIGHT + CHART_WEIGHT + MAP_WEIGHT);
            float radar = Math.min(a[0] + more * share, Math.max(a[0], tallest(width)));
            return new float[] {radar, height - radar};
        }
        for (int i = 1; i < n.length; i++) {
            if (height >= n[i]) {
                float along = n[i - 1] > n[i] ? (height - n[i]) / (n[i - 1] - n[i]) : 0;
                return new float[] {a[i] + along * (a[i - 1] - a[i]), b[i] + along * (b[i - 1] - b[i])};
            }
        }
        float last = n[n.length - 1];
        float share = last > 0 ? Math.max(0, height) / last : 0;
        return new float[] {a[a.length - 1] * share, b[b.length - 1] * share};
    }

    /**
     * The radar's column's width side by side in a stage {@code height} tall: as wide as its radar needs there, the
     * ground keeping room of its own and the radar at most {@link #SIDE_RADAR_MOST} of the page, and at least what its
     * least circle needs (or half the page, if less).
     */
    private float sideWidth(float height) {
        float side = Math.min(Math.min(radarWidth(radiusIn(Float.MAX_VALUE, height)), SIDE_RADAR_MOST * width),
                width - dp(SIDE_GROUND_LEAST_DP));
        return Math.max(side, Math.min(radarWidth(dp(RADIUS_LEAST_DP)), width / 2f));
    }

    /**
     * What the road and the counts take of the room under the lines, {road, counts}: they give way to the stage where
     * every part reads well one above the other (the most it ever needs, so neither comes back only to fade again as the
     * stage turns), the road first.
     */
    private float[] giveWay(float avail) {
        float[] a = radarSteps();
        float[] b = groundSteps();
        float comfortable = a[0] + b[0];
        float road = dp(ROAD_DP);
        float counts = dp(COUNTS_DP);
        if (avail >= road + counts + comfortable) return new float[] {road, counts};
        if (avail >= counts + comfortable) return new float[] {avail - counts - comfortable, counts};
        return new float[] {0, Math.max(0, avail - comfortable)};
    }

    /** Works out where everything stands. */
    void solve() {
        float avail = height - fixedHeight();
        float[] given = giveWay(avail);
        float roadHeight = given[0];
        float countsHeight = given[1];
        float stageHeight = Math.max(0, avail - roadHeight - countsHeight);
        // The stage turns from side by side once the room under the lines is more than SIDE_FROM_DP and SIDE_SHARE of
        // the width, and holds the radar and the whole skyline one above the other (so the skyline never fades as the
        // stage turns); from there its parts move TURN_RATE pixels for every pixel the room grows, less what the
        // counts and the road take of it meanwhile (the stage moving down with the counts' row as they come back), so
        // no part ever moves more than TURN_RATE times as fast as the window's edge. Side by side, the radar's column
        // is as wide as the stage's height asks; the turn keeps the width it had where it began.
        float[] a = radarSteps();
        float[] b = groundSteps();
        float start = Math.max(dp(SIDE_FROM_DP) + SIDE_SHARE * width, a[2] + b[2]);
        float[] givenAtStart = giveWay(start);
        float stageAtStart = Math.max(0, start - givenAtStart[0] - givenAtStart[1]);
        float travelled = Math.max(0, TURN_RATE * Math.max(0, avail - start) - countsHeight - roadHeight);
        measureTurn(stageHeight, Math.min(stageHeight, stageAtStart));
        turn = 1 - 0.5f * Math.min(1, travelled / down) - 0.5f * Math.min(1, Math.max(0, travelled - down) / across);
        int last = a.length - 1;
        float least = a[last] + b[last] + turn * (Math.max(a[last], b[last]) - a[last] - b[last]);
        roadShown = roadHeight / dp(ROAD_DP);
        countsShown = countsHeight / dp(COUNTS_DP);
        stageShown = least > 0 ? Math.min(1, stageHeight / least) : 1;

        float y = 0;
        float stripHeight = stripHeight();
        strip.set(0, y, width, y + stripHeight);
        float mascotLeft = dp(MASCOT_LEFT_DP);
        mascot.set(mascotLeft, y + dp(STRIP_TOP_DP), mascotLeft + dp(MASCOT_DP), y + dp(STRIP_TOP_DP + MASCOT_DP));
        float wordsLeft = mascot.right + dp(WORDS_GAP_DP);
        words.set(wordsLeft, y + dp(STRIP_TOP_DP), wordsLeft + wordsWidth(width), y + dp(STRIP_TOP_DP) + wordsHeight);
        y = strip.bottom;
        header.set(0, y, width, y + headerHeight);
        y = header.bottom;
        countsRow.set(0, y, width, y + countsHeight);
        countsSpacing = Math.min(dp(FilterHeroView.COUNTS_SPACING_MOST_DP),
                (width - 2 * dp(SIDE_DP)) * FilterHeroView.COUNTS_SPACING_SHARE);
        float countsWidth = 2 * countsSpacing + dp(FilterHeroView.COUNTS_ENDS_DP);
        counts.set((width - countsWidth) / 2, y, (width + countsWidth) / 2, y + dp(COUNTS_DP));
        y = countsRow.bottom;
        lines.set(0, y, width, y + linesHeight);
        y = lines.bottom;
        stage.set(0, y, width, y + stageHeight);
        arrange(stageHeight, travelled);
        y = stage.bottom;
        road.set(0, y, width, y + roadHeight);
        total = Math.round(Math.max(height, road.bottom));
    }

    // The turn's measures for the stage as it stands: the radar's column's height one above the other, its width and
    // height side by side, and how far the turn's first and second halves move their parts.
    private float above;
    private float side;
    private float sideHeight;
    private float down;
    private float across;

    /**
     * The turn's measures for a stage {@code height} tall whose radar's column side by side is as wide as a stage
     * {@code sideFor} tall asks: in the turn's first half the ground's column moves down beside the radar's, which takes
     * the height it has one above the other; in its second half the radar's column widens over the ground's and the
     * ground's under the radar's. Each half is as long as the farthest any edge moves in it.
     */
    private void measureTurn(float height, float sideFor) {
        above = stacked(height)[0];
        side = sideWidth(sideFor);
        sideHeight = Math.min(height, tallest(side));
        down = Math.max(1, Math.max(above, Math.abs(sideHeight - above)));
        across = Math.max(1, Math.max(side, width - side));
    }

    /**
     * The stage {@code height} tall, turned {@code travelled} pixels from side by side ({@link #solve}): side by side,
     * the radar at the top of its column on the left (as tall as its circle at that width needs) and the ground on the
     * right; as it turns, first the ground's column moves down beside the radar's, which takes the height it has one
     * above the other, each edge moving {@code travelled} pixels at most; then the radar's column widens to the right
     * over the ground and the ground's to the left under it, until the two stand one above the other.
     */
    private void arrange(float height, float travelled) {
        float top = stage.top;
        float groundTop = top + Math.min(above, travelled);
        // A radar taller side by side than one above the other keeps its height as long as it can, so its bottom never
        // moves with both the turn and its own column's width at once.
        float radarBottom = top + (sideHeight > above
                ? Math.max(above, Math.min(sideHeight, above + down - travelled))
                : Math.min(above, sideHeight + travelled));
        float over = Math.max(0, travelled - down);
        float radarRight = Math.min(width, side + over);
        float groundLeft = Math.max(0, side - over);
        float bottom = top + height;
        radar.set(0, top, radarRight, radarBottom);
        float[] ground = groundColumn(bottom - groundTop);
        caption.set(groundLeft, groundTop, width, groundTop + ground[0]);
        chart.set(groundLeft, caption.bottom, width, caption.bottom + ground[1]);
        horizonShown = ground[2];
        float area = Math.min(areaHeight(), Math.max(0, bottom - chart.bottom));
        map.set(groundLeft, chart.bottom, width, Math.max(chart.bottom, bottom - area));
        this.area.set(groundLeft, bottom - area, width, bottom);
        radius = radiusIn(radar.width(), radar.height());
        radarX = (radar.left + radar.right) / 2;
        radarY = (radar.top + radar.bottom) / 2;
    }
}
