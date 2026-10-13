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
 * <p>Since 0.6.8 the buttons also lift into the first row, beside the tile, where the folded mascot, a readable tile
 * ({@link #TILE_FIT_DP}) and the buttons fit there (the owner's mockup: the buttons in the verdict's row beside
 * Dasher): first the tile pulls in, then the buttons rise. The first row keeps its height: the header's box hangs
 * past it into the second row's end, where the chips' row leaves the buttons' room, so the band is no taller for it.
 *
 * <p>The stage holds the minimums' constellation (the radar) and the ground: the skyline's caption over the skyline,
 * and the offer map over the line naming its chosen area. In a short window they stand in three columns: the radar,
 * the skyline and the map. From where the stage holds the top tier's least over the map strip's least
 * ({@link #turnsFrom}) it turns to two tiers (0.6.8, the owner's mockup of 12 October 2026: the radar and the skyline
 * side by side over the map, a strip the page's width): the radar's and the skyline's columns shorten from below to
 * the tier's line while the map's top comes down to it, then the map widens to the left under them and the skyline to
 * the right over it. Nothing in the turn moves more than {@link #TURN_RATE} times as fast as the window's edge. Taller
 * still, the top tier grows until the radar's circle fills its column, the map strip takes the rest, and then the road.
 *
 * <p>Every size and place is a continuous function of the window's width and height, with no threshold anywhere. As
 * the window shrinks, what must go gives way least important first: the road (it waits until the turn is over, and as
 * if the buttons stood in the chips' row, so the band's fold never takes the road back), then the stage shrinks through
 * the turn to its three columns, the radar's icons tuck in towards its rim so that its circle keeps its least in a
 * radar as short as {@link #TUCKED_DP}, and only below that do the radar, the skyline and the map fade, all together.
 * In three columns the skyline also fades by the width alone, as its column narrows below {@link #COLUMN_LEAST_DP}. A
 * part is used (touched, heard) only while it is wholly there; the band and the lines never go, and below what they
 * need the page scrolls.
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
    /**
     * The latest offer's tile reads at this width beside the buttons: from where the folded mascot, this tile and the
     * buttons fit in the first row, the buttons rise into it over {@link #LIFT_RISE_DP} (0.6.8, the owner's mockup:
     * the buttons in the verdict's row beside Dasher), once the tile has pulled in to leave them their room.
     */
    static final float TILE_FIT_DP = 140;
    static final float LIFT_RISE_DP = 32;

    // ---- The stage ----
    /** The parts' heights where each reads well (the most a part that fades takes), and their least. */
    static final float ROAD_DP = 78;
    static final float CAPTION_DP = 48;
    static final float CHART_DP = 80;
    static final float CHART_LEAST_DP = 56;
    static final float MAP_DP = 120;
    static final float MAP_LEAST_DP = 84;
    static final float AREA_LINE_DP = 48;
    /** The radar's radius where it reads well, and the least its knobs work at. */
    static final float RADIUS_DP = 84;
    static final float RADIUS_LEAST_DP = 56;
    /** The fastest any part moves while the stage turns, against the window's edge. */
    static final float TURN_RATE = 5f;
    /** The widest page the turn is over in before the road comes (a tablet on its side). */
    static final float TURN_WIDEST_DP = 1400;
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
    /** The turn, from two tiers (0) to three columns (1); and the same again, as the inner turn was before 0.6.8. */
    float turn;
    float inner;
    /**
     * How far the band has folded: the mascot into the corner with the status words' end pulled in (0 to 1), then the
     * buttons risen into the chips' row (0 to 1); and the band's height.
     */
    float narrow;
    float rise;
    /** How far the buttons have lifted from the chips' row into the first row (0 to 1). */
    float lift;
    float band;
    /** The page's whole height: the height given, or what never goes when that is more. */
    int total;

    FluidLayout(float density) {
        this.density = density;
        tierEase = Math.max(radarHeight(dp(RADIUS_DP)), dp(CAPTION_DP + CHART_DP));
        tierLeast = Math.max(dp(TUCKED_DP), dp(CAPTION_DP + CHART_LEAST_DP));
        comfortable = tierEase + dp(MAP_DP);
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

    // ---- The stage's two tiers ----

    /** The top tier where every part reads well (the radar's circle at {@link #RADIUS_DP}), and its least. */
    private final float tierEase;
    private final float tierLeast;
    /** The stage where every part reads well: the top tier over the map strip. */
    private final float comfortable;

    /**
     * The radar's column's width in a top tier {@code height} tall: as wide as its circle there needs, at most half the
     * page, and at least its least circle's (or half the page, if less). The skyline takes the rest.
     */
    private float radarSide(float height) {
        float half = width / 2f;
        return Math.max(Math.min(radarWidth(radiusIn(Float.MAX_VALUE, height)), half),
                Math.min(radarWidth(dp(RADIUS_LEAST_DP)), half));
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
        // The buttons lift into the first row in two steps, one after the other, so nothing overlaps on the way and
        // nothing moves more than three times as fast as the window's edge: the tile pulls in to leave them their room,
        // then they rise into it.
        float pull = buttonsWidth + dp(BAND_GAP_DP);
        float making = pull / 3;
        float rising = dp(LIFT_RISE_DP);
        float liftsFrom = Math.max(risesFrom + dp(RISE_DP), dp(MASCOT_LEFT_FOLDED_DP + MASCOT_FOLDED_DP
                + TILE_GAP_FOLDED_DP + TILE_FIT_DP + BAND_GAP_DP + BAND_SIDE_DP) + countsWidth + pull);
        float room = clamp((width - (liftsFrom - making)) / making);
        lift = clamp((width - liftsFrom) / rising);
        narrow = clamp((width - (risesFrom - dp(NARROW_DP))) / dp(NARROW_DP));
        float ring = dp(MASCOT_DP - (MASCOT_DP - MASCOT_FOLDED_DP) * narrow);
        float left = dp(MASCOT_LEFT_DP - (MASCOT_LEFT_DP - MASCOT_LEFT_FOLDED_DP) * narrow);
        float top = dp(STRIP_TOP_DP) * (1 - narrow);
        float first = Math.max(Math.max(ring, tileHeight), dp(ROW1_LEAST_DP));
        float firstFolded = Math.max(Math.max(dp(MASCOT_FOLDED_DP), tileHeight), dp(ROW1_LEAST_DP));
        float second = top + first;
        mascot.set(left, top + (first - ring) / 2, left + ring, top + (first + ring) / 2);
        float countsTall = dp(FilterHeroView.COUNTS_HEIGHT_DP);
        // The tile is never squeezed under a touch target's width: wide counts (a large font) give way to it.
        float tileGap = dp(TILE_GAP_DP - (TILE_GAP_DP - TILE_GAP_FOLDED_DP) * narrow);
        float countsLeft = Math.max(width - dp(BAND_SIDE_DP) - countsWidth - pull * room,
                mascot.right + tileGap + dp(ROW1_LEAST_DP) + dp(BAND_GAP_DP));
        counts.set(countsLeft, top + (first - countsTall) / 2, Math.max(countsLeft,
                width - dp(BAND_SIDE_DP) - pull * room),
                top + (first + countsTall) / 2);
        float tileLeft = Math.min(mascot.right + tileGap, counts.left);
        float tileRight = counts.left - dp(BAND_GAP_DP);
        tile.set(tileLeft, top + (first - tileHeight) / 2, Math.max(tileLeft, tileRight),
                top + (first + tileHeight) / 2);
        float wordsEnd = width - dp(BAND_SIDE_DP) - pull * narrow;
        chipRow.set(left, second, Math.min(width, Math.max(wordsEnd, left + chipsMost)), second + dp(ROW_DP));
        float buttonsTop = (second + dp(ROW_DP) * (1 - rise)) * (1 - lift)
                + Math.max(0, top + (first - dp(ROW_DP)) / 2) * lift;
        float buttonsLeft = Math.max(0, width - dp(BAND_SIDE_DP) - buttonsWidth);
        float headerLeft = Math.max(0, buttonsLeft - dp(BAND_GAP_DP));
        header.set(headerLeft, buttonsTop, Math.max(headerLeft, Math.min(width,
                headerLeft + buttonsWidth + dp(BAND_GAP_DP))), buttonsTop + dp(ROW_DP));
        band = second + dp(ROW_DP) + dp(ROW_DP) * (1 - rise);
        bandFolded = firstFolded + dp(ROW_DP);
    }

    /**
     * Works out where everything stands. The band first, by the width alone, then the lines under it. The road takes
     * room only beyond what every part reads well at, and only as if the band were folded, so the band's fold gives
     * its room to the stage, never to the road. The stage is two tiers: the radar and the skyline side by side at its
     * top, the map a strip the page's width under them ({@link #tiers}).
     */
    void solve() {
        band();
        lines.set(0, band, width, band + linesHeight);
        float folded = height - linesHeight - bandFolded;
        float under = Math.max(0, height - band - linesHeight);
        float line = areaLine ? dp(AREA_LINE_DP) : 0;
        // The road waits for the turn to be over: past its longest moves (down by the top tier or the map strip, and
        // across the widest page) at TURN_RATE from where it begins, the same at every width.
        float settled = Math.max(comfortable + line,
                turnsFrom() + (Math.max(tierEase, dp(MAP_DP) + line) + dp(TURN_WIDEST_DP)) / TURN_RATE);
        float roadHeight = Math.max(0, Math.min(dp(ROAD_DP), Math.min(under, folded - dp(ROW_DP)) - settled));
        float stageHeight = under - roadHeight;
        stage.set(0, lines.bottom, width, lines.bottom + stageHeight);
        road.set(0, stage.bottom, width, stage.bottom + roadHeight);
        roadShown = roadHeight / dp(ROAD_DP);
        arrange(stageHeight);
        total = Math.max(height, Math.round(road.bottom));
    }

    /**
     * The stage {@code height} tall (the owner's mockup of 12 October 2026, "each thing once"). From
     * {@link #turnsFrom} up it turns, gradually, from three columns ({@link #columns}) to two tiers ({@link #tiers}):
     * first the radar's and the skyline's columns shorten from below to the top tier while the map's top comes down to
     * it beside them, then the map widens to the left under the skyline and the radar, and the skyline to the right
     * over it, until the map is a strip the page's width. Each edge moves at most {@link #TURN_RATE} pixels for each
     * pixel of the stage's height, so the turn takes a range of heights long enough for its longest move. Below
     * {@link #TUCKED_DP} the radar, the skyline and the map fade, all together.
     */
    private void arrange(float height) {
        float top = stage.top;
        float least = turnsFrom();
        columns(top, height);
        if (height <= least) {
            turn = 1;
        } else {
            tiers(top, height);
            float travelled = TURN_RATE * (height - least);
            // The first half: bottoms and tops along the columns to the tier's line.
            float down = Math.max(1, Math.max(Math.abs(c[1][3] - t[1][3]), Math.max(Math.abs(c[3][3] - t[3][3]),
                    Math.abs(c[4][1] - t[4][1]))));
            float s = Math.min(travelled, down);
            float over = Math.max(0, travelled - down);
            float across = Math.max(1, Math.max(Math.abs(c[4][0] - t[4][0]), Math.max(Math.abs(c[3][2] - t[3][2]),
                    Math.abs(c[1][2] - t[1][2]))));
            turn = 1 - 0.5f * Math.min(1, travelled / down) - 0.5f * Math.min(1, over / across);
            for (int k = 0; k < c.length; k++) {
                c[k][0] = toward(c[k][0], t[k][0], over);
                c[k][1] = toward(c[k][1], t[k][1], s);
                c[k][2] = toward(c[k][2], t[k][2], over);
                c[k][3] = toward(c[k][3], t[k][3], s);
            }
            // The skyline's left edge follows the radar's right one.
            c[2][0] = c[1][2];
            c[3][0] = c[1][2];
            horizonShown += (clamp((width - c[1][2]) / dp(COLUMN_LEAST_DP)) - horizonShown) * (1 - turn);
        }
        inner = turn;
        radar.set(c[1][0], c[1][1], c[1][2], c[1][3]);
        caption.set(c[2][0], c[2][1], c[2][2], c[2][3]);
        chart.set(c[3][0], c[3][1], c[3][2], c[3][3]);
        map.set(c[4][0], c[4][1], c[4][2], c[4][3]);
        area.set(c[5][0], c[5][1], c[5][2], c[5][3]);
        iconGap = gapFor(radar.height());
        radius = radiusIn(radar.width(), radar.height());
        radarX = (radar.left + radar.right) / 2;
        radarY = (radar.top + radar.bottom) / 2;
        // A pixel's rounding short of its least the stage is still wholly there (its knobs keep their touches); it
        // fades from there at the same rate, never in a step.
        stageShown = Math.min(1, height / (dp(TUCKED_DP) - 1));
    }

    /** {@code from} moved toward {@code to} by at most {@code by}. */
    private static float toward(float from, float to, float by) {
        return from < to ? Math.min(to, from + by) : Math.max(to, from - by);
    }

    /**
     * The stage's height from which it turns towards two tiers: the top tier's least over the map strip's least (and
     * its area's line, when shown).
     */
    float turnsFrom() {
        return tierLeast + dp(MAP_LEAST_DP) + (areaLine ? dp(AREA_LINE_DP) : 0);
    }

    // The parts' boxes as {left, top, right, bottom}, in three columns (c) and in two tiers (t): unused, the radar,
    // the skyline's caption, the skyline, the map and its area's line.
    private final float[][] c = new float[6][4];
    private final float[][] t = new float[6][4];

    /**
     * Three columns in a stage {@code height} tall: the radar on the left at the top of its column, the box the top
     * tier would give it ({@link #tierFor}), so the turn never shrinks it; the skyline's caption over the skyline, half
     * the rest, the map's keeping at least {@link #COLUMN_LEAST_DP}; and the map over its area's line. The skyline is
     * there only as far as its column's width lets it read.
     */
    private void columns(float top, float height) {
        float bottom = top + height;
        float tier = Math.min(height, tierFor(height));
        // The radar's column grows with it only while the skyline's and the map's keep their least, so the skyline
        // never fades as the window grows.
        float side = Math.min(radarSide(tier), Math.max(radarSide(tierLeast), width - 2 * dp(COLUMN_LEAST_DP)));
        float ground = width - side;
        float sky = Math.max(0, Math.min(ground / 2, ground - dp(COLUMN_LEAST_DP)));
        float split = side + sky;
        put(c[1], 0, top, side, top + tier);
        put(c[2], side, top, split, top + Math.min(dp(CAPTION_DP), height));
        put(c[3], side, c[2][3], split, bottom);
        float line = areaLine ? Math.min(dp(AREA_LINE_DP), height) : 0;
        put(c[4], split, top, width, bottom - line);
        put(c[5], split, bottom - line, width, bottom);
        horizonShown = clamp(sky / dp(COLUMN_LEAST_DP));
    }

    /**
     * Two tiers in a stage {@code height} tall, at least {@link #turnsFrom}: the radar on the left and the skyline's
     * caption over the skyline on the right, side by side, over the map, a strip the page's width, over its area's
     * line. Taller than every part reads well at, the top tier grows until the radar's circle fills its column (half
     * the page at most), and the map takes the rest; shorter, the map comes to its least first, then the top tier.
     */
    private void tiers(float top, float height) {
        float line = areaLine ? dp(AREA_LINE_DP) : 0;
        float tier = tierFor(height);
        float bottom = top + height;
        float side = radarSide(tier);
        put(t[1], 0, top, side, top + tier);
        put(t[2], side, top, width, top + Math.min(dp(CAPTION_DP), tier));
        put(t[3], side, t[2][3], width, top + tier);
        put(t[4], 0, top + tier, width, bottom - line);
        put(t[5], 0, bottom - line, width, bottom);
    }

    /**
     * The top tier's height in a stage {@code height} tall: the map strip comes to its least first, then the tier to
     * its least; taller than every part reads well at, the tier grows until the radar's circle fills half the page and
     * the strip takes the rest.
     */
    private float tierFor(float height) {
        float line = areaLine ? dp(AREA_LINE_DP) : 0;
        float mapEase = dp(MAP_DP) + line;
        float mapLeast = dp(MAP_LEAST_DP) + line;
        if (height >= tierEase + mapEase) {
            float most = Math.max(tierEase, radarHeight(radiusIn(width / 2f, Float.MAX_VALUE)));
            return Math.min(most, height - mapEase);
        }
        if (height >= tierEase + mapLeast) return tierEase;
        return Math.max(tierLeast, height - mapLeast);
    }

    private static void put(float[] box, float left, float top, float right, float bottom) {
        box[0] = left;
        box[1] = top;
        box[2] = right;
        box[3] = bottom;
    }
}
