package com.local.dasherfilter;

/**
 * Where each part of the homepage stands in one window: one fluid layout, the same at every size (the owner, 7 October
 * 2026, on 0.5.1's split screen: "I do not like how it only shows the map or the radar"; "it should be gradual and
 * seamless"; and 12 October 2026: "at small split ratios I still want to see all relevant infographic"). From the top:
 * the band, the lines that need the user, then the stage, and the road along the bottom.
 *
 * <p>The band never fades. Its first row holds the mascot, the latest offer's tile ({@link VerdictTile}) and the dash's
 * counts at its end; its second, Autopilot's chip, the scoring mode's and the status words; the header's buttons stand
 * at the second row's end where the window is wide enough for them beside the chips, else on a third row of their own
 * under it. How the band folds goes by the window's width alone, never its height: over {@link #NARROW_DP} up to the
 * width at which the buttons fit beside the chips, the mascot shrinks from 64 to 48 dp into the corner, the room above
 * it closes and the status words' end pulls in to leave the buttons their room; from there, over {@link #RISE_DP}, the
 * buttons rise into the chips' row. Five buttons (a full screen's, with Open Dasher) fold up to
 * {@link #FULL_SCREEN_EXTRA_DP} later, so a phone's own screen keeps its whole mascot.
 *
 * <p>The stage holds the minimums' constellation (the radar) and the ground: the skyline's caption over the skyline,
 * and the offer map over the line naming its chosen area. In a short window they stand in three columns: the radar,
 * the skyline and the map. As the window grows taller the map moves under the skyline (the inner turn: the map's top
 * comes down while the skyline's bottom rises, then the skyline widens to the right over the map and the map to the
 * left under it) until the radar stands beside the skyline over the map; then, as before 0.6.3, the radar's column
 * shortens from below while the ground's moves down beside it, and the radar's widens to the right over the ground
 * and the ground's to the left under it, until the radar stands above the ground (the outer turn). Each turn takes a
 * range of heights long enough that nothing in it moves more than {@link #TURN_RATE} times as fast as the window's
 * edge, and the inner turn is over before the outer one begins; the radar's column keeps one width from where the
 * inner turn begins.
 *
 * <p>Every size and place is a continuous function of the window's width and height, with no threshold anywhere. Room
 * beyond what every part reads well at goes to the radar, the skyline and the map by weight. As the window shrinks,
 * what must go gives way least important first: the road (it takes room only beyond what every part reads well at,
 * and waits for it as if the buttons stood in the chips' row, so the band's fold never takes the road back), then the
 * stage shrinks through both turns to its three columns, the radar's icons tuck in towards its rim so that its circle
 * keeps its least in a radar as short as {@link #TUCKED_DP}, and only below that do the radar, the skyline and the map
 * fade, all together. In three columns the skyline also fades by the width alone, as its column narrows below
 * {@link #COLUMN_LEAST_DP}. A part is used (touched, heard) only while it is wholly there; the band and the lines never
 * go, and below what they need the page scrolls.
 *
 * <p>Pure arithmetic in pixels with no Android type, so every window size can be checked.
 */
final class FluidLayout {
    // ---- The band ----
    /** The band's sides, and the room between its parts (the tile and the counts, the chips and the buttons). */
    static final float BAND_SIDE_DP = 4;
    static final float BAND_GAP_DP = 4;
    /** The room above the band while its first row is open (none once it is folded). */
    static final float STRIP_TOP_DP = 8;
    /** The mascot's ring across and its room from the page's left, open and folded; and the room before the tile. */
    static final float MASCOT_DP = 64;
    static final float MASCOT_FOLDED_DP = 48;
    static final float MASCOT_LEFT_DP = 12;
    static final float MASCOT_LEFT_FOLDED_DP = 4;
    static final float TILE_GAP_DP = 12;
    static final float TILE_GAP_FOLDED_DP = 4;
    /** The chips' row and the buttons' (a touch target and its room each), and the least of the first row. */
    static final float ROW_DP = 56;
    static final float ROW1_LEAST_DP = 48;
    /**
     * The band folds over {@link #NARROW_DP} of the width up to where the buttons fit beside the chips, and the
     * buttons rise over {@link #RISE_DP} from there. With a fifth button (Open Dasher, a full screen's) that width is
     * up to {@link #FULL_SCREEN_EXTRA_DP} more, in step with the buttons' width past {@link #FOUR_BUTTONS_DP} over one
     * more button's {@link #BUTTON_STEP_DP}.
     */
    static final float NARROW_DP = 68;
    static final float RISE_DP = 40;
    static final float FULL_SCREEN_EXTRA_DP = 64;
    static final float FOUR_BUTTONS_DP = 212;
    static final float BUTTON_STEP_DP = 52;

    // ---- The stage ----
    /** The parts' heights where each reads well (the most a part that fades takes), and their least. */
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
    /**
     * In three columns the skyline's column is half the ground's, the map's keeping at least this width (the skyline's
     * gives way first); the skyline fades by the width alone as its column narrows below it.
     */
    static final float COLUMN_LEAST_DP = 100;
    /**
     * The least stage before anything fades, in three columns: the radar's least circle with its icons tucked in; and
     * the map's height there over its area's line.
     */
    static final float TUCKED_DP = 116;
    static final float MAP_TUCKED_DP = TUCKED_DP - AREA_LINE_DP;
    /** The inner turn begins where the stage's drive ({@link #solve}) is this. */
    static final float INNER_START_DP = 284;

    // ---- The radar's icons: the constellation's own as the page's sky (MinimumsStarView) ----
    /**
     * An icon stands this far from the end of its spoke where the radar has its room, down to the least as the page
     * tucks it in towards the rim in a radar shorter than 132 dp; it is this large, and keeps this far inside the
     * radar's box.
     */
    static final float ICON_GAP_DP = MinimumsStarView.ICON_GAP_DP;
    static final float ICON_GAP_LEAST_DP = MinimumsStarView.ICON_GAP_LEAST_DP;
    static final float ICON_DP = MinimumsStarView.BACKDROP_ICON_DP;
    static final float ICON_EDGE_DP = MinimumsStarView.ICON_EDGE_DP;
    /** The room kept past the circle's rim at the radar's top and bottom: this much, the least as the icons tuck in. */
    static final float RIM_EDGE_DP = 4;
    static final float RIM_EDGE_LEAST_DP = 2;
    /** The radar's spokes stand 30 degrees above and below level; its icons' middles stand this far past their ends. */
    private static final float COS = (float) Math.cos(Math.toRadians(MinimumsStarView.SPREAD));
    private static final float SIN = (float) Math.sin(Math.toRadians(MinimumsStarView.SPREAD));
    private static final float ICON_ACROSS_DP = 4 + 11 + 4;
    /**
     * The inner turn's end is worked out as a fixed point, each step at least halving the distance to it: at most this
     * many steps, fewer once a step finds it.
     */
    private static final int INNER_END_STEPS = 40;

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
    int linesHeight;
    /** Whether the line naming the map's chosen area is shown (it then stands under the map). */
    boolean areaLine;
    /** The latest offer's tile's own height ({@link VerdictTile#heightFor}), the same whatever it shows. */
    int tileHeight;
    /** The counts' own width ({@link FilterHeroView#countsWidth}). */
    int countsWidth;
    /** The chips side by side at their widest words ({@link AutopilotChip.Row#widestChips}). */
    int chipsMost;
    /** The header's buttons side by side: the header's own width less its title's room. */
    int buttonsWidth;

    // ---- Where everything stands, in pixels ----
    /** The band: the mascot, the tile and the counts in its first row; the chips' row; the header with its buttons. */
    final Box mascot = new Box();
    final Box tile = new Box();
    final Box counts = new Box();
    final Box chipRow = new Box();
    final Box header = new Box();
    final Box lines = new Box();
    final Box stage = new Box();
    final Box radar = new Box();
    final Box caption = new Box();
    final Box chart = new Box();
    final Box map = new Box();
    final Box area = new Box();
    final Box road = new Box();
    /** The radar's circle in the page: its middle and radius; and how far its icons stand from the spokes' ends. */
    float radarX;
    float radarY;
    float radius;
    float iconGap;
    /** How much of each part that can fade is there (1: wholly, 0: gone). */
    float horizonShown;
    float stageShown;
    float roadShown;
    /** The outer turn, from one above the other (0) to side by side (1); the inner, stacked (0) to three columns. */
    float turn;
    float inner;
    /**
     * How far the band has folded: the mascot into the corner with the status words' end pulled in (0 to 1), then the
     * buttons risen into the chips' row (0 to 1); and the band's height.
     */
    float narrow;
    float rise;
    float band;
    /** The page's whole height: the height given, or what never goes when that is more. */
    int total;

    FluidLayout(float density) {
        this.density = density;
        float most = radarHeight(dp(RADIUS_DP));
        float middle = radarHeight(dp(RADIUS_MIDDLE_DP));
        radarSteps = new float[] {most, most, middle, middle, radarHeight(dp(RADIUS_LEAST_DP))};
        float area = dp(AREA_LINE_DP);
        float horizon = dp(CAPTION_DP + CHART_LEAST_DP);
        groundSteps = new float[] {dp(CAPTION_DP + CHART_DP + MAP_DP) + area, horizon + dp(MAP_DP) + area,
                horizon + dp(MAP_MIDDLE_DP) + area, dp(MAP_MIDDLE_DP) + area, dp(MAP_LEAST_DP) + area};
        comfortable = radarSteps[0] + groundSteps[0];
    }

    private float dp(float value) {
        return value * density;
    }

    private static float clamp(float value) {
        return Math.max(0, Math.min(1, value));
    }

    // ---- The radar's own box: its circle, the spokes' icons above and below them, and a little edge. ----

    /**
     * How far the icons stand from the spokes' ends in a radar {@code height} pixels tall, in pixels: the whole
     * {@link #ICON_GAP_DP} from 132 dp up, tucked in towards the rim below that, to {@link #ICON_GAP_LEAST_DP} at
     * {@link #TUCKED_DP}, so the radar keeps its least circle down to that height.
     */
    float gapFor(float height) {
        return dp(Math.max(ICON_GAP_LEAST_DP, Math.min(ICON_GAP_DP, (height / density - TUCKED_DP) / 2
                + ICON_GAP_LEAST_DP)));
    }

    /** The room kept past the rim at the radar's top and bottom while its icons stand {@code gap} pixels out. */
    float rimEdge(float gap) {
        return dp(RIM_EDGE_LEAST_DP + (gap / density - ICON_GAP_LEAST_DP) * (RIM_EDGE_DP - RIM_EDGE_LEAST_DP)
                / (ICON_GAP_DP - ICON_GAP_LEAST_DP));
    }

    /** The height the radar's box needs for a circle of {@code radius}, its icons the whole gap out. */
    float radarHeight(float radius) {
        return 2 * Math.max(radius + rimEdge(dp(ICON_GAP_DP)), SIN * radius + dp(ICON_GAP_DP + ICON_DP + ICON_EDGE_DP));
    }

    /** The width it needs: the spokes' icons inside the box (the circle's rim may run past its sides, faded). */
    float radarWidth(float radius) {
        return 2 * (COS * radius + dp(ICON_ACROSS_DP));
    }

    /** The radar's box height where a column {@code width} wide stops its circle growing. */
    private float tallest(float width) {
        return radarHeight(radiusIn(width, Float.MAX_VALUE));
    }

    /**
     * The largest circle a box {@code width} by {@code height} holds, its icons as far out as that height lets them
     * stand ({@link #gapFor}); 0 for none. Above the circle's top or above the upper icons, whichever stands higher.
     */
    float radiusIn(float width, float height) {
        float gap = gapFor(height);
        float across = (width / 2 - dp(ICON_ACROSS_DP)) / COS;
        float rim = height / 2 - rimEdge(gap);
        float icons = (height / 2 - (gap + dp(ICON_DP + ICON_EDGE_DP))) / SIN;
        return Math.max(0, Math.min(across, Math.min(rim, icons)));
    }

    // ---- The stage's steps, from where every part reads well to the least, as the window shrinks ----

    /**
     * The radar's height at each step: where it reads well, still so while the skyline comes to its least, a little
     * less, still so while the skyline and its caption shrink away, and its least. The same in every window at one
     * density, so worked out once.
     */
    private final float[] radarSteps;
    /**
     * The ground's height (the caption, the skyline, the map and its area's line) at each of the same steps. The map
     * takes its area's line's room while the line is not shown, so the steps are the same either way.
     */
    private final float[] groundSteps;
    /** The stage where every part reads well one above the other: the most it ever needs. */
    private final float comfortable;

    /**
     * The ground one above the other in a column {@code height} tall: {caption, skyline, how much of the skyline it
     * would leave}; the map takes the rest, over its area's line. Taller than every part reads well at, the room goes
     * to the skyline and the map by weight; shorter, the skyline comes to its least, the map to a little less, the
     * skyline and its caption shrink, and the map to its least and below. The skyline stands over the map only so far
     * as the inner turn has gone ({@link #ground}), and how much of it is there goes by its column's width.
     */
    private float[] groundColumn(float height) {
        float[] b = groundSteps;
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
        float[] a = radarSteps;
        float[] b = groundSteps;
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
     * The skyline's column's width in three columns, in a ground {@code wide} pixels across: half of it, the map's
     * keeping at least {@link #COLUMN_LEAST_DP} (none for the skyline in a ground narrower than that).
     */
    private float skySide(float wide) {
        return Math.max(0, Math.min(wide / 2, wide - dp(COLUMN_LEAST_DP)));
    }

    /**
     * The stage's height where the stage's drive ({@link #solve}) is {@code drive}, with the band {@code unfolded}
     * pixels taller than folded.
     */
    private float stageAt(float drive, float unfolded) {
        return drive - unfolded / 2 - Math.min(dp(ROAD_DP) / 2,
                Math.max(0, drive - (dp(ROW_DP) + comfortable) + unfolded / 2));
    }

    // ---- The band ----

    /**
     * The width from which the buttons rise into the chips' row: the band's sides, the chips at their widest, the
     * buttons and the room between them, and with a fifth button up to {@link #FULL_SCREEN_EXTRA_DP} more.
     */
    private float risesFrom() {
        float extra = dp(FULL_SCREEN_EXTRA_DP) * clamp((buttonsWidth - dp(FOUR_BUTTONS_DP)) / dp(BUTTON_STEP_DP));
        return dp(BAND_SIDE_DP) + chipsMost + dp(BAND_GAP_DP) + buttonsWidth + dp(BAND_SIDE_DP) + extra;
    }

    /** The folded band's height: what it is once the buttons stand in the chips' row. */
    private float bandFolded;

    /**
     * The band, by the window's width alone. Its first row is as tall as the mascot, the tile or a touch target,
     * whichever is tallest: the mascot at its start, the counts at its end (their whole width where the window has it,
     * else what there is past the mascot) and the tile between them, all in the row's middle. The chips' row under it
     * from the mascot's left, as wide as the chips at their widest at least, else to where the buttons' room begins
     * once the band folds; the header's buttons at the end of the row under that, rising into the chips' row as the
     * window widens. The header's box takes its title's room at the buttons' left, and keeps its whole width where the
     * window has it, so no button is squeezed.
     */
    private void band() {
        float risesFrom = risesFrom();
        rise = clamp((width - risesFrom) / dp(RISE_DP));
        narrow = clamp((width - (risesFrom - dp(NARROW_DP))) / dp(NARROW_DP));
        float ring = dp(MASCOT_DP - (MASCOT_DP - MASCOT_FOLDED_DP) * narrow);
        float left = dp(MASCOT_LEFT_DP - (MASCOT_LEFT_DP - MASCOT_LEFT_FOLDED_DP) * narrow);
        float top = dp(STRIP_TOP_DP) * (1 - narrow);
        float first = Math.max(Math.max(ring, tileHeight), dp(ROW1_LEAST_DP));
        float firstFolded = Math.max(Math.max(dp(MASCOT_FOLDED_DP), tileHeight), dp(ROW1_LEAST_DP));
        float second = top + first;
        mascot.set(left, top + (first - ring) / 2, left + ring, top + (first + ring) / 2);
        float countsTall = dp(FilterHeroView.COUNTS_HEIGHT_DP);
        float countsLeft = Math.max(width - dp(BAND_SIDE_DP) - countsWidth, mascot.right + dp(BAND_GAP_DP));
        counts.set(countsLeft, top + (first - countsTall) / 2, Math.max(countsLeft, width - dp(BAND_SIDE_DP)),
                top + (first + countsTall) / 2);
        float tileLeft = Math.min(mascot.right + dp(TILE_GAP_DP - (TILE_GAP_DP - TILE_GAP_FOLDED_DP) * narrow),
                counts.left);
        tile.set(tileLeft, top + (first - tileHeight) / 2, Math.max(tileLeft, counts.left - dp(BAND_GAP_DP)),
                top + (first + tileHeight) / 2);
        float wordsEnd = width - dp(BAND_SIDE_DP) - (buttonsWidth + dp(BAND_GAP_DP)) * narrow;
        chipRow.set(left, second, Math.min(width, Math.max(wordsEnd, left + chipsMost)), second + dp(ROW_DP));
        float buttonsTop = second + dp(ROW_DP) * (1 - rise);
        float buttonsLeft = Math.max(0, width - dp(BAND_SIDE_DP) - buttonsWidth);
        float headerLeft = Math.max(0, buttonsLeft - dp(BAND_GAP_DP));
        header.set(headerLeft, buttonsTop, Math.max(headerLeft, Math.min(width,
                headerLeft + buttonsWidth + dp(BAND_GAP_DP))), buttonsTop + dp(ROW_DP));
        band = second + dp(ROW_DP) + dp(ROW_DP) * (1 - rise);
        bandFolded = firstFolded + dp(ROW_DP);
    }

    // The turns' measures for the stage as it stands: the stage's drive, where the outer turn begins, the radar's
    // column's height one above the other, its width and height side by side, how far the outer turn's halves move
    // their parts, and the skyline's column's width in three columns.
    private float drive;
    private float start;
    private float above;
    private float side;
    private float sideHeight;
    private float down;
    private float across;
    private float skyRest;

    /**
     * Works out where everything stands. The band first, by the width alone, then the lines under it. The road takes
     * room only beyond what every part reads well at, and only as if the buttons stood in the chips' row, so the band's
     * fold gives its room to the stage, never to the road. The turns go by the stage's drive, its height and half of
     * what the band's fold and the road take on top of it, so neither turn moves its parts faster for the band folding
     * or the road coming as the window grows. The inner turn begins at a drive of {@link #INNER_START_DP} and moves its
     * parts TURN_RATE pixels for each pixel of drive; the outer turn begins where the inner turn is over (and never
     * before SIDE_FROM_DP and SIDE_SHARE of the width, or the radar at its middle circle above the whole skyline), and
     * moves its parts as fast. The radar's column side by side is as wide as the stage's height asks up to where the
     * inner turn begins, and keeps that width through both turns.
     */
    void solve() {
        band();
        lines.set(0, band, width, band + linesHeight);
        float folded = height - linesHeight - bandFolded;
        float under = Math.max(0, height - band - linesHeight);
        float roadHeight = Math.max(0, Math.min(dp(ROAD_DP), Math.min(under, folded - dp(ROW_DP)) - comfortable));
        float stageHeight = under - roadHeight;
        drive = (stageHeight + Math.max(stageHeight, folded)) / 2;
        float unfolded = band - bandFolded;
        stage.set(0, lines.bottom, width, lines.bottom + stageHeight);
        road.set(0, stage.bottom, width, stage.bottom + roadHeight);
        roadShown = roadHeight / dp(ROAD_DP);
        side = sideWidth(Math.max(0, Math.min(stageHeight, stageAt(dp(INNER_START_DP), unfolded))));
        float groundWide = width - side;
        skyRest = skySide(groundWide);
        float innerAcross = Math.max(1, Math.max(skyRest, groundWide - skyRest));
        float[] a = radarSteps;
        float[] b = groundSteps;
        start = Math.max(Math.max(dp(SIDE_FROM_DP) + SIDE_SHARE * width, a[2] + b[2]),
                innerEnd(unfolded, innerAcross));
        float travelled = Math.max(0, TURN_RATE * (drive - start));
        // The radar's column one above the other leaves the ground what the whole skyline needs over the map at a
        // little less, so the skyline never shrinks as the stage turns.
        above = Math.min(stacked(stageHeight)[0], Math.max(0, stageHeight - b[2]));
        sideHeight = Math.min(stageHeight, tallest(side));
        down = Math.max(1, Math.max(above, Math.abs(sideHeight - above)));
        across = Math.max(1, Math.max(side, width - side));
        turn = 1 - 0.5f * Math.min(1, travelled / down) - 0.5f * Math.min(1, Math.max(0, travelled - down) / across);
        arrange(stageHeight, travelled);
        // Before anything fades: the radar and the ground at their least one above the other, the tucked stage in
        // three columns, and in between in step with the turn.
        int last = a.length - 1;
        float least = a[last] + b[last] + turn * (dp(TUCKED_DP) - a[last] - b[last]);
        stageShown = least > 0 ? Math.min(1, stageHeight / least) : 1;
        total = Math.max(height, Math.round(road.bottom));
    }

    /**
     * The drive at which the inner turn is over: it begins at {@link #INNER_START_DP} and takes, at TURN_RATE, the
     * skyline's column's height in a stage at that drive and the width across it ({@code innerAcross}). Worked out
     * step by step from where it begins: each step at least halves the distance to it, since the stage's height grows
     * no faster than its drive and the skyline's no faster than the stage's.
     */
    private float innerEnd(float unfolded, float innerAcross) {
        float end = dp(INNER_START_DP);
        for (int i = 0; i < INNER_END_STEPS; i++) {
            float at = stageAt(end, unfolded);
            float[] column = groundColumn(at);
            float sky = column[0] + column[1];
            float innerDown = Math.max(1, Math.max(sky, Math.abs(at - sky)));
            float next = dp(INNER_START_DP) + (innerDown + innerAcross) / TURN_RATE;
            // Found: every step from here gives the same again.
            if (next == end) break;
            end = next;
        }
        return end;
    }

    /**
     * The stage {@code height} tall, turned {@code travelled} pixels from side by side ({@link #solve}): side by side,
     * the radar at the top of its column on the left (as tall as its circle at that width needs) and the ground on the
     * right; as it turns, first the ground's column moves down beside the radar's, which takes the height it has one
     * above the other, each edge moving {@code travelled} pixels at most; then the radar's column widens to the right
     * over the ground and the ground's to the left under it, until the two stand one above the other. The radar's icons
     * stand as far out as its height lets them.
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
        iconGap = gapFor(radar.height());
        radius = radiusIn(radar.width(), radar.height());
        radarX = (radar.left + radar.right) / 2;
        radarY = (radar.top + radar.bottom) / 2;
        ground(groundLeft, groundTop, width, bottom, Math.max(0, TURN_RATE * (drive - dp(INNER_START_DP))));
    }

    /**
     * The ground between {@code left} and {@code right}, {@code top} and {@code bottom}, its inner turn {@code tau}
     * pixels along: at none, the skyline's column (its caption over the skyline) and the map's (the map over its
     * area's line) side by side, each the ground's whole height, the skyline's half the ground's width
     * ({@link #skySide}); as it turns, first the map's top moves down while the skyline's bottom rises to what the
     * skyline needs over the map ({@link #groundColumn}), each edge moving {@code tau} pixels at most; then the skyline
     * widens to the right over the map and the map to the left under it, until the skyline stands over the map. In
     * three columns the skyline is there only as far as its column's width lets it read, by the width alone; once over
     * the map, wholly.
     */
    private void ground(float left, float top, float right, float bottom, float tau) {
        float wide = Math.max(0, right - left);
        float tall = Math.max(0, bottom - top);
        float skyWide = skySide(wide);
        float[] column = groundColumn(tall);
        float sky = column[0] + column[1];
        float innerDown = Math.max(1, Math.max(sky, Math.abs(tall - sky)));
        float innerAcross = Math.max(1, Math.max(skyWide, wide - skyWide));
        inner = 1 - 0.5f * Math.min(1, tau / innerDown)
                - 0.5f * Math.min(1, Math.max(0, tau - innerDown) / innerAcross);
        float mapTop = top + Math.min(sky, tau);
        float skyBottom = top + (tall > sky ? Math.max(sky, Math.min(tall, sky + innerDown - tau))
                : Math.min(sky, tall + tau));
        float over = Math.max(0, tau - innerDown);
        float skyRight = Math.min(right, left + skyWide + over);
        float mapLeft = Math.max(left, left + skyWide - over);
        caption.set(left, top, skyRight, top + Math.min(dp(CAPTION_DP), Math.max(0, skyBottom - top)));
        chart.set(left, caption.bottom, skyRight, Math.max(caption.bottom, skyBottom));
        float line = areaLine ? Math.min(dp(AREA_LINE_DP), Math.max(0, bottom - mapTop)) : 0;
        map.set(mapLeft, mapTop, right, Math.max(mapTop, bottom - line));
        area.set(mapLeft, bottom - line, right, bottom);
        horizonShown = 1 - inner * (1 - clamp(skyRest / dp(COLUMN_LEAST_DP)));
    }
}
