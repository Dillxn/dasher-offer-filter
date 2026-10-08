package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * The main page as one illustration, and its one fluid layout ({@link FluidLayout}). A sky runs from the top of the page
 * down to the horizon, the offers' skyline's street; the ground runs from there down to the road at the bottom. The
 * strip (the mascot, the latest offer's verdict, Autopilot's chip and the status line), the header's buttons, the
 * dash's counts and the lines that need the user stand along the top; under them the minimums' constellation with the
 * skyline under it, above the offer map in a tall window and beside it in a wide one; then the road. Every part keeps
 * its place at every window size and only its size changes, continuously: what must go at a small size shrinks and
 * fades out over a range of heights, least important first, and is used again (touched, heard) only once it is wholly
 * there. A resize does nothing but lay the page out and draw it.
 *
 * <p>By day the sky is a soft morning blue warming towards the horizon, with a sun and drifting clouds; by night (dark
 * theme) it is deep blue, with a moon and stars. Each region stays light enough (or dark enough) for the page's own
 * text. While the user is dashing, two searchlights sweep the sky from the horizon: the app is watching. Tilting the
 * phone slides the far layers a little; with Android's animations off, everything rests (the searchlights stand still).
 * No star shines and no cloud drifts under the page's words (a cloud fades as it passes behind them), and the signpost
 * keeps clear of the skyline, the words and the constellation's icons.
 */
@SuppressLint("ViewConstructor")
final class ScenePage extends ViewGroup {
    /** How far, in dp, each layer slides at full tilt: the farther away, the less. */
    private static final float STARS_DEPTH = 3;
    private static final float SUN_DEPTH = 5;
    private static final float CLOUD_DEPTH = 8;
    private static final float FAR_HILL_DEPTH = 4;
    private static final float NEAR_HILL_DEPTH = 7;
    /** The sun's disc's radius (the moon's is smaller), and how high the far and near hills rise, in dp. */
    private static final int SUN_DP = 21;
    private static final int FAR_HILL_DP = 46;
    private static final int NEAR_HILL_DP = 26;
    /** How far, in dp, the signpost may rise from the hills to clear the skyline before it is left out. */
    private static final int SIGN_RISE_DP = 64;
    /** The skyline's street, this far above the skyline's bottom, is the horizon. */
    private static final int STREET_DP = 11;
    /** Stars across the whole night sky: position as shares of the sky, size in dp, twinkle phase. */
    private static final float[][] STARS = new float[46][4];

    static {
        java.util.Random scatter = new java.util.Random(11);
        for (float[] star : STARS) {
            star[0] = scatter.nextFloat();
            star[1] = (float) Math.pow(scatter.nextFloat(), 1.4);
            star[2] = 0.7f + scatter.nextFloat() * 1.0f;
            star[3] = scatter.nextFloat();
        }
    }

    private final Ui ui;
    private final FluidLayout fluid;
    /** Where the mascot's drawing and its counts stand, worked out at each layout into the same two boxes. */
    private final FluidLayout.Box heroBox = new FluidLayout.Box();
    private final android.graphics.RectF countsBox = new android.graphics.RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path farHills = new Path();
    private final Path nearHills = new Path();
    private final Path moon = new Path();
    private final Path moonBite = new Path();
    private final Path beam = new Path();
    private Shader beamShade;
    private float beamFor = Float.NaN;
    private boolean watching;
    private long watchingSince;
    private View sunAnchor;
    /** The neighbourhood the phone is in, on a signpost on the hills; null hides it. */
    private String place;
    private final android.text.TextPaint signText = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF board = new android.graphics.RectF();
    /** Where the signpost's board was last drawn; empty when it was left out. */
    private final android.graphics.RectF signBoard = new android.graphics.RectF();
    private Shader sky;
    private Shader ground;
    private Shader sunGlow;
    private float shadedFor = Float.NaN;
    /** The horizon, width and rise the hills were last shaped for. */
    private float hillsLine = Float.NaN;
    private float hillsWidth = Float.NaN;
    private float hillsRise = Float.NaN;
    /** Where the page's words and the constellation's icons stand this frame, in this page's pixels. */
    private final java.util.List<android.graphics.RectF> words = new java.util.ArrayList<>();
    private final java.util.List<android.graphics.RectF> overIcons = new java.util.ArrayList<>();

    // ---- The page's parts, in the order screen readers reach them ----
    private FilterHeroView hero;
    private View strip;
    private View header;
    private View lines;
    private MinimumsStarView star;
    private View caption;
    private DecisionChartView chart;
    private AreaMapView map;
    private View area;
    private View road;
    /** The parts that fade, with how much of each is there now and whether screen readers may reach it then. */
    private View[] fading = new View[0];
    private float[] shown = new float[0];
    private int[] heard = new int[0];
    /** A finger that went down on a part on its way out: the page keeps the whole touch, and nothing acts on it. */
    private boolean swallowing;

    ScenePage(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        fluid = new FluidLayout(context.getResources().getDisplayMetrics().density);
        setWillNotDraw(false);
    }

    /**
     * The page's parts, added in the order screen readers reach them: the strip's words (the verdict, the chip and the
     * status line), the mascot with the counts, the header, the lines, the constellation, the skyline's caption, the
     * skyline, the map, its area's line and the road. Only the mascot's drawing spans more than its row (the counts
     * stand under the header), and it takes no touch but on the mascot and the counts.
     */
    void setParts(View strip, FilterHeroView hero, View header, View lines, MinimumsStarView star, View caption,
                  DecisionChartView chart, AreaMapView map, View area, View road) {
        this.strip = strip;
        this.hero = hero;
        this.header = header;
        this.lines = lines;
        this.star = star;
        this.caption = caption;
        this.chart = chart;
        this.map = map;
        this.area = area;
        this.road = road;
        for (View part : new View[] {strip, hero, header, lines, star, caption, chart, map, area, road}) addView(part);
        road.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        fading = new View[] {star, caption, chart, map, area};
        shown = new float[fading.length];
        heard = new int[fading.length];
        for (int i = 0; i < fading.length; i++) {
            shown[i] = 1;
            heard[i] = fading[i].getImportantForAccessibility();
        }
    }

    /** The color at the very top of the sky, for behind the status bar. */
    static int skyTop(Ui ui) {
        return ui.dark ? 0xFF070B16 : 0xFFD4E4F4;
    }

    /** Searchlights sweep the sky while the user is dashing. */
    void setWatching(boolean on) {
        if (on == watching) return;
        watching = on;
        watchingSince = android.os.SystemClock.uptimeMillis();
        invalidate();
    }

    boolean watching() {
        return watching;
    }

    /** The neighbourhood the phone is in, shown on a signpost on the hills. */
    void setPlace(String name) {
        if (java.util.Objects.equals(name, place)) return;
        place = name;
        invalidate();
    }

    /** Where the signpost's board was last drawn, in this page's pixels; null when it was left out (for tests). */
    android.graphics.RectF signBoard() {
        return place == null || signBoard.isEmpty() ? null : new android.graphics.RectF(signBoard);
    }

    /** Where the page's words stand this frame, in this page's pixels (for tests). */
    java.util.List<android.graphics.RectF> words() {
        gatherOver();
        return new java.util.ArrayList<>(words);
    }

    /** The sun (or moon) is drawn over this view, which is its button. */
    void setSunAnchor(View view) {
        sunAnchor = view;
    }

    // ---- The fluid layout ----

    /**
     * Exactly the height given, the parts sharing it ({@link FluidLayout}); asked with no limit (the page's scroller
     * working out whether it fits), only what never goes: the strip, the header and the lines. The scroller then gives
     * it the screen, or, where even those need more (a very large font in a small window), it scrolls by the
     * difference, everything else gone.
     */
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        strip.measure(exactly(fluid.wordsWidth(width)), any);
        header.measure(exactly(width), any);
        lines.measure(exactly(width), any);
        fluid.width = width;
        fluid.height = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED ? 0 : MeasureSpec.getSize(heightSpec);
        fluid.wordsHeight = strip.getMeasuredHeight();
        fluid.headerHeight = header.getMeasuredHeight();
        fluid.linesHeight = lines.getMeasuredHeight();
        fluid.areaLine = area.getVisibility() != GONE;
        fluid.solve();
        measure(hero, heroBox());
        measure(star, fluid.radar);
        measure(caption, fluid.caption);
        measure(chart, fluid.chart);
        measure(map, fluid.map);
        measure(area, fluid.area);
        measure(road, fluid.road);
        setMeasuredDimension(width, fluid.total);
    }

    private static int exactly(float size) {
        return MeasureSpec.makeMeasureSpec(Math.max(0, Math.round(size)), MeasureSpec.EXACTLY);
    }

    private static void measure(View part, FluidLayout.Box box) {
        part.measure(exactly(Math.round(box.right) - Math.round(box.left)),
                exactly(Math.round(box.bottom) - Math.round(box.top)));
    }

    private static void place(View part, FluidLayout.Box box) {
        part.layout(Math.round(box.left), Math.round(box.top), Math.round(box.right), Math.round(box.bottom));
    }

    /** The mascot's drawing: from the mascot down to the counts' row, across both (one box, reused). */
    private FluidLayout.Box heroBox() {
        heroBox.set((float) Math.floor(Math.min(fluid.mascot.left, fluid.counts.left)), fluid.mascot.top,
                (float) Math.ceil(Math.max(fluid.mascot.right, fluid.counts.right)),
                (float) Math.ceil(Math.max(fluid.mascot.bottom, fluid.countsRow.bottom)));
        return heroBox;
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        place(strip, fluid.words);
        FluidLayout.Box heroBox = heroBox();
        place(hero, heroBox);
        float mascotRadius = fluid.mascot.width() / 2;
        countsBox.set(fluid.counts.left - heroBox.left, fluid.counts.top - heroBox.top,
                fluid.counts.right - heroBox.left, fluid.counts.bottom - heroBox.top);
        hero.place((fluid.mascot.left + fluid.mascot.right) / 2 - heroBox.left,
                (fluid.mascot.top + fluid.mascot.bottom) / 2 - heroBox.top, mascotRadius, countsBox,
                fluid.countsSpacing, fluid.countsShown);
        place(header, fluid.header);
        place(lines, fluid.lines);
        place(star, fluid.radar);
        star.compose(fluid.radarX - Math.round(fluid.radar.left), fluid.radarY - Math.round(fluid.radar.top),
                fluid.radius, java.util.Collections.<MinimumsStarView.Veil>emptyList());
        place(caption, fluid.caption);
        place(chart, fluid.chart);
        place(map, fluid.map);
        place(area, fluid.area);
        place(road, fluid.road);
        road.setAlpha(fluid.roadShown);
        float stage = fluid.stageShown;
        float horizon = stage * fluid.horizonShown;
        // The constellation, the skyline's caption, the skyline, the map and its area's line.
        for (int i = 0; i < fading.length; i++) show(i, i == 1 || i == 2 ? horizon : stage);
    }

    /**
     * Part {@code i} at {@code amount} (0 to 1): drawn that faded, and reached by screen readers (and touched) only
     * while it is wholly there.
     */
    private void show(int i, float amount) {
        View part = fading[i];
        if (part.getAlpha() != amount) part.setAlpha(amount);
        shown[i] = amount;
        int importance = amount >= 1 ? heard[i] : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;
        if (part.getImportantForAccessibility() != importance) part.setImportantForAccessibility(importance);
    }

    /** How much of {@code part} is there now: 1 wholly (it is used), 0 gone, between on its way out (for tests too). */
    float shown(View part) {
        if (part == hero) return 1;
        if (part == road) return fluid.roadShown;
        for (int i = 0; i < fading.length; i++) if (fading[i] == part) return shown[i];
        return 1;
    }

    /** How much of the dash's counts is there now. */
    float countsShown() {
        return fluid.countsShown;
    }

    /** The constellation and the map are wholly there (for the one-time notices that name them, and the knobs). */
    boolean stageShown() {
        return fluid.stageShown >= 1 && getHeight() > 0;
    }

    /** From the radar above the map (0) to side by side (1) (for tests). */
    float turn() {
        return fluid.turn;
    }

    /** A finger going down on a part on its way out is the page's: nothing there acts on it, nor on its drag. */
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) swallowing = onFading(event.getX(), event.getY());
        return swallowing;
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!swallowing) return super.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swallowing = false;
        return true;
    }

    private boolean onFading(float x, float y) {
        for (int i = fading.length - 1; i >= 0; i--) {
            View part = fading[i];
            if (part.getVisibility() != VISIBLE || x < part.getLeft() || x >= part.getRight() || y < part.getTop()
                    || y >= part.getBottom()) continue;
            return shown[i] < 1;
        }
        return false;
    }

    /** Where the horizon is, in this page's coordinates: the skyline's street, or where the skyline would stand. */
    float horizonY() {
        if (chart == null || chart.getHeight() <= 0 && chart.getTop() <= 0) return getHeight() * 0.6f;
        return Math.max(chart.getTop(), chart.getBottom() - ui.dp(STREET_DP));
    }

    private void gatherOver() {
        words.clear();
        overIcons.clear();
        if (strip == null || getHeight() <= 0) return;
        addWords(strip);
        if (fluid.countsShown > 0) {
            android.graphics.RectF counts = new android.graphics.RectF();
            hero.countsAt(counts);
            counts.offset(hero.getLeft(), hero.getTop());
            counts.inset(-ui.dp(4), -ui.dp(2));
            words.add(counts);
        }
        addRows(lines, 0, 0);
        if (shown(caption) > 0) addWords(caption);
        if (area.getVisibility() == VISIBLE && shown(area) > 0) addWords(area);
        if (shown(star) > 0 && star.getHeight() > 0) {
            int from = words.size();
            star.wordsAt(words);
            for (int i = from; i < words.size(); i++) words.get(i).offset(star.getLeft(), star.getTop());
            star.iconsAt(overIcons);
            for (android.graphics.RectF box : overIcons) box.offset(star.getLeft(), star.getTop());
        }
    }

    /** Each row of a column of lines (the setup steps, the cards), where its words stand. */
    private void addRows(View view, float dx, float dy) {
        if (view.getVisibility() != VISIBLE || view.getHeight() <= 0) return;
        if (view instanceof android.widget.LinearLayout
                && ((android.widget.LinearLayout) view).getOrientation() == android.widget.LinearLayout.VERTICAL) {
            ViewGroup column = (ViewGroup) view;
            for (int i = 0; i < column.getChildCount(); i++) {
                addRows(column.getChildAt(i), dx + view.getLeft(), dy + view.getTop());
            }
            return;
        }
        words.add(new android.graphics.RectF(dx + view.getLeft(), dy + view.getTop(), dx + view.getRight(),
                dy + view.getBottom()));
    }

    private void addWords(View part) {
        if (part.getVisibility() != VISIBLE || part.getHeight() <= 0) return;
        words.add(new android.graphics.RectF(part.getLeft(), part.getTop(), part.getRight(), part.getBottom()));
    }

    /** Whether ({@code x}, {@code y}) is within {@code margin} of any of the sky's words. */
    private boolean underWords(float x, float y, float margin) {
        for (android.graphics.RectF box : words) {
            if (x >= box.left - margin && x <= box.right + margin && y >= box.top - margin
                    && y <= box.bottom + margin) {
                return true;
            }
        }
        return false;
    }

    private float top(View view) {
        float y = 0;
        for (View at = view; at != null && at != this; at = (View) at.getParent()) {
            y += at.getTop();
            if (!(at.getParent() instanceof View)) break;
        }
        return y;
    }

    private float left(View view) {
        float x = 0;
        for (View at = view; at != null && at != this; at = (View) at.getParent()) {
            x += at.getLeft();
            if (!(at.getParent() instanceof View)) break;
        }
        return x;
    }

    @Override protected void onDraw(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();
        float line = Math.max(ui.dp(120), Math.min(height, horizonY()));
        shade(line, height);

        fill.setShader(sky);
        canvas.drawRect(0, 0, width, line, fill);
        fill.setShader(ground);
        canvas.drawRect(0, line, width, height, fill);
        fill.setShader(null);

        gatherOver();
        if (ui.dark) drawStars(canvas, width, line);
        drawSun(canvas, width);
        if (!ui.dark) drawClouds(canvas, width, line);
        if (watching) drawSearchlights(canvas, width, line);
        drawHills(canvas, width, line);
        if (place != null) drawSignpost(canvas, width, line);
        Motion.next(this);
    }

    /** The sky's and ground's gradients, made again only when the horizon moves or the page resizes. */
    private void shade(float line, float height) {
        float key = line * 7919 + height;
        if (key == shadedFor) return;
        shadedFor = key;
        int[] skyColors = ui.dark
                ? new int[] {skyTop(ui), 0xFF0D1428, 0xFF1C1B34}
                : new int[] {skyTop(ui), 0xFFE7EEF2, 0xFFF6E7D2};
        sky = new LinearGradient(0, 0, 0, line, skyColors, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
        // Down to the colors the road strip at the bottom starts from, so they meet without a seam.
        ground = new LinearGradient(0, line, 0, height, ui.dark ? 0xFF121821 : 0xFFE3E7D6,
                ui.dark ? 0xFF141413 : 0xFFE8E6DE, Shader.TileMode.CLAMP);
    }

    private void drawStars(Canvas canvas, float width, float line) {
        canvas.save();
        canvas.translate(-Tilt.x() * ui.dp(STARS_DEPTH), -Tilt.y() * ui.dp(STARS_DEPTH));
        float reach = line - ui.dp(70);
        float shiftX = -Tilt.x() * ui.dp(STARS_DEPTH);
        float shiftY = -Tilt.y() * ui.dp(STARS_DEPTH);
        for (float[] star : STARS) {
            float x = width * star[0];
            float y = ui.dp(8) + reach * star[1];
            // None under the sky's words, so no dot lands on a letter.
            if (underWords(x + shiftX, y + shiftY, ui.dp(star[2]) + ui.dp(3))) continue;
            float twinkle = 0.5f + 0.5f * Motion.wave(2.2f + star[3] * 3f, star[3]);
            int alpha = (int) (0x50 + 0x9F * twinkle * (1 - 0.5f * star[1]));
            fill.setColor((Math.min(255, alpha) << 24) | 0xFFF3E9);
            canvas.drawCircle(x, y, ui.dp(star[2]) * (0.7f + 0.3f * twinkle), fill);
        }
        canvas.restore();
    }

    /** By day a sun, by night a crescent moon, up by the settings button, both with a soft glow. */
    private void drawSun(Canvas canvas, float width) {
        boolean anchored = sunAnchor != null && sunAnchor.getWidth() > 0;
        float x = anchored ? left(sunAnchor) + sunAnchor.getWidth() / 2f : width * 0.68f;
        float y = anchored ? top(sunAnchor) + sunAnchor.getHeight() / 2f : ui.dp(44);
        canvas.save();
        canvas.translate(-Tilt.x() * ui.dp(SUN_DEPTH), -Tilt.y() * ui.dp(SUN_DEPTH));
        float breathe = 1 + 0.06f * Motion.wave(7f, 0);
        if (sunGlow == null) {
            sunGlow = new RadialGradient(0, 0, ui.dp(60), ui.dark ? 0x40E9DDB8 : 0x66FFD36E, 0x00FFFFFF,
                    Shader.TileMode.CLAMP);
        }
        canvas.save();
        canvas.translate(x, y);
        canvas.scale(breathe, breathe);
        fill.setShader(sunGlow);
        canvas.drawCircle(0, 0, ui.dp(60), fill);
        fill.setShader(null);
        canvas.restore();
        if (ui.dark) {
            moon.reset();
            moon.addCircle(x, y, ui.dp(15), Path.Direction.CW);
            moonBite.reset();
            moonBite.addCircle(x + ui.dp(7), y - ui.dp(5), ui.dp(13), Path.Direction.CW);
            moon.op(moonBite, Path.Op.DIFFERENCE);
            fill.setColor(0xFFF1E6C8);
            canvas.drawPath(moon, fill);
        } else {
            fill.setColor(0xFFFFE3A0);
            canvas.drawCircle(x, y, ui.dp(SUN_DP), fill);
            fill.setColor(0xFFF8C85A);
            canvas.drawCircle(x, y, ui.dp(16), fill);
        }
        canvas.restore();
    }

    /** Three soft clouds at different heights, each drifting across the sky at its own pace. */
    private void drawClouds(Canvas canvas, float width, float line) {
        canvas.save();
        canvas.translate(-Tilt.x() * ui.dp(CLOUD_DEPTH), 0);
        drawCloud(canvas, drift(width, 0.52f, 110f), ui.dp(30), ui.dp(12), 0xE6FFFFFF);
        drawCloud(canvas, drift(width, 0.12f, 160f), line * 0.34f, ui.dp(15), 0xB3FFFFFF);
        drawCloud(canvas, drift(width, 0.8f, 200f), line * 0.62f, ui.dp(11), 0x99FFFFFF);
        canvas.restore();
    }

    private float drift(float width, float share, float period) {
        float span = width + ui.dp(90);
        float moved = Motion.on() ? Motion.loop(period, 0) : 0;
        return (share + moved) % 1f * span - ui.dp(45);
    }

    /** A cloud; it fades out while it passes behind the sky's words, gone once a fifth of it is behind them. */
    private void drawCloud(Canvas canvas, float x, float y, float size, int color) {
        float left = x - size * 1.8f;
        float top = y - size;
        float right = x + size * 2.05f;
        float bottom = y + size * 1.05f;
        float behind = 0;
        for (android.graphics.RectF box : words) {
            float across = Math.min(right, box.right) - Math.max(left, box.left);
            float down = Math.min(bottom, box.bottom) - Math.max(top, box.top);
            if (across > 0 && down > 0) behind += across * down;
        }
        float shown = 1 - Math.min(1, behind / (0.2f * (right - left) * (bottom - top)));
        if (shown <= 0) return;
        fill.setColor((color & 0x00FFFFFF) | (Math.round((color >>> 24) * shown) << 24));
        canvas.drawCircle(x, y, size, fill);
        canvas.drawCircle(x - size * 1.1f, y + size * 0.35f, size * 0.7f, fill);
        canvas.drawCircle(x + size * 1.15f, y + size * 0.3f, size * 0.78f, fill);
        canvas.drawRect(x - size * 1.1f, y + size * 0.35f, x + size * 1.15f, y + size * 1.05f, fill);
    }

    /** Two beams rising from behind the hills, each sweeping slowly to and fro on its own rhythm. */
    private void drawSearchlights(Canvas canvas, float width, float line) {
        float length = Math.max(ui.dp(200), line * 0.85f);
        if (beamFor != length) {
            beamFor = length;
            int color = ui.dark ? 0x3DD6E6FF : 0x4DF5B942;
            beamShade = new LinearGradient(0, 0, 0, -length, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP);
            float spread = length * 0.09f;
            beam.reset();
            beam.moveTo(-ui.dp(3), 0);
            beam.lineTo(-spread, -length);
            beam.lineTo(spread, -length);
            beam.lineTo(ui.dp(3), 0);
            beam.close();
        }
        float shown = Motion.settle(watchingSince, 600);
        fill.setShader(beamShade);
        fill.setAlpha(Math.round(255 * shown));
        drawBeam(canvas, width * 0.26f, line - ui.dp(18), -14 + 24 * Motion.wave(6.5f, 0));
        drawBeam(canvas, width * 0.76f, line - ui.dp(14), 12 - 24 * Motion.wave(8.2f, 0.3f));
        fill.setShader(null);
        fill.setAlpha(255);
    }

    private void drawBeam(Canvas canvas, float x, float y, float degrees) {
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(degrees);
        canvas.drawPath(beam, fill);
        canvas.restore();
    }

    /**
     * A wooden signpost on the near hills at the left, naming where you are. Its board rises above the skyline where
     * buildings stand under it, and steps right past (or rises over) the sky's words and icons; one that would have to
     * rise more than {@link #SIGN_RISE_DP} dp is left out.
     */
    private void drawSignpost(Canvas canvas, float width, float line) {
        signText.setTextSize(Math.min(ui.sp(11), ui.dp(15)));
        signText.setFakeBoldText(true);
        signText.setTextAlign(Paint.Align.CENTER);
        CharSequence name = Ui.fit(signText, place, width * 0.36f, 0.8f);
        float halfWidth = signText.measureText(name, 0, name.length()) / 2 + ui.dp(7);
        float halfHeight = signText.getTextSize() * 0.8f;
        float x = Math.max(halfWidth + ui.dp(10), width * 0.15f);
        float foot = line - ui.dp(4);
        float rest = foot - ui.dp(40);
        float gap = ui.dp(4);
        float cap = rest;
        float middle = rest;
        for (int pass = 0; pass < 5; pass++) {
            middle = Math.min(cap, overSkyline(x, halfWidth, halfHeight, gap, rest));
            android.graphics.RectF hit = signHit(x, middle, halfWidth, halfHeight, gap);
            if (hit == null) break;
            // Past the sky's words and icons: a step right if it stays on the left of the page, else up over them.
            float right = hit.right + gap + halfWidth;
            if (right + halfWidth <= width * 0.5f) x = right;
            else cap = Math.min(cap, hit.top - gap - halfHeight);
        }
        signBoard.setEmpty();
        if (rest - middle > ui.dp(SIGN_RISE_DP) || signHit(x, middle, halfWidth, halfHeight, gap) != null) return;
        signBoard.set(x - halfWidth, middle - halfHeight, x + halfWidth, middle + halfHeight);
        fill.setColor(ui.dark ? 0xFF4A3D2A : 0xFF9C7A52);
        canvas.drawRect(x - ui.dp(1.5f), middle, x + ui.dp(1.5f), foot, fill);
        board.set(x - halfWidth, middle - halfHeight, x + halfWidth, middle + halfHeight);
        fill.setColor(ui.dark ? 0xFF5B4A33 : 0xFFC9A36F);
        canvas.drawRoundRect(board, ui.dp(3), ui.dp(3), fill);
        board.inset(ui.dp(2), ui.dp(2));
        fill.setColor(ui.dark ? 0xFF2A2216 : 0xFFF6E8CF);
        canvas.drawRoundRect(board, ui.dp(2), ui.dp(2), fill);
        signText.setColor(ui.dark ? 0xFFF3E6C8 : 0xFF3A2A18);
        canvas.drawText(name, 0, name.length(), x, middle + signText.getTextSize() / 3, signText);
    }

    /** The board's middle at {@code x}: at {@code rest}, or over the skyline's buildings and flags standing under it. */
    private float overSkyline(float x, float halfWidth, float halfHeight, float gap, float rest) {
        if (chart == null || shown(chart) <= 0 || chart.getHeight() <= 0) return rest;
        float chartLeft = left(chart);
        float highest = top(chart) + chart.highestWithin(x - halfWidth - gap - chartLeft, x + halfWidth + gap - chartLeft);
        return Math.min(rest, highest - gap - halfHeight);
    }

    /** The first of the sky's words the signpost's board would overlap, or icons it would come within {@code gap} of. */
    private android.graphics.RectF signHit(float x, float middle, float halfWidth, float halfHeight, float gap) {
        board.set(x - halfWidth, middle - halfHeight, x + halfWidth, middle + halfHeight);
        for (android.graphics.RectF box : words) if (android.graphics.RectF.intersects(board, box)) return box;
        board.inset(-gap, -gap);
        for (android.graphics.RectF box : overIcons) if (android.graphics.RectF.intersects(board, box)) return box;
        return null;
    }

    /**
     * Two ranges of hills along the horizon, behind the skyline, the nearer darker. They never rise over the sun (or
     * moon), the header's day and night button: where the horizon comes up near it (a short window, the skyline gone),
     * they flatten as it comes.
     */
    private void drawHills(Canvas canvas, float width, float line) {
        float rise = Math.max(0, Math.min(1, (line - sunBottom()) / ui.dp(FAR_HILL_DP)));
        if (line != hillsLine || width != hillsWidth || rise != hillsRise) {
            hillsLine = line;
            hillsWidth = width;
            hillsRise = rise;
            float over = ui.dp(12);
            hills(farHills, -over, width + over, line, ui.dp(FAR_HILL_DP) * rise, 3.0);
            hills(nearHills, -over, width + over, line, ui.dp(NEAR_HILL_DP) * rise, 5.0);
        }
        canvas.save();
        canvas.translate(-Tilt.x() * ui.dp(FAR_HILL_DEPTH), 0);
        fill.setColor(ui.dark ? 0xFF1B2238 : 0xFFDCE4D2);
        canvas.drawPath(farHills, fill);
        canvas.restore();
        canvas.save();
        canvas.translate(-Tilt.x() * ui.dp(NEAR_HILL_DEPTH), 0);
        fill.setColor(ui.dark ? 0xFF151B28 : 0xFFD2DCC6);
        canvas.drawPath(nearHills, fill);
        canvas.restore();
    }

    /**
     * The lowest the sun's (or moon's) disc reaches, in this page's pixels, with the phone tilted as far as it slides
     * it; 0 before its button is laid out.
     */
    float sunBottom() {
        if (sunAnchor == null || sunAnchor.getWidth() <= 0) return 0;
        return top(sunAnchor) + sunAnchor.getHeight() / 2f + ui.dp(SUN_DP + SUN_DEPTH);
    }

    /** The highest the hills rise as last drawn, in this page's pixels (for tests). */
    float hillsTop() {
        android.graphics.RectF bounds = new android.graphics.RectF();
        farHills.computeBounds(bounds, true);
        android.graphics.RectF near = new android.graphics.RectF();
        nearHills.computeBounds(near, true);
        return Math.min(bounds.top, near.top);
    }

    /** Rolling hills whose tops reach {@code rise} above {@code line}, with {@code waves} crests across. */
    private static void hills(Path path, float left, float right, float line, float rise, double waves) {
        path.reset();
        path.moveTo(left, line + 1);
        int steps = 48;
        for (int i = 0; i <= steps; i++) {
            float x = left + (right - left) * i / steps;
            double t = (double) i / steps;
            double swell = 0.55 + 0.25 * Math.sin(t * Math.PI * waves + 0.7) + 0.2 * Math.sin(t * Math.PI * waves * 2.3);
            path.lineTo(x, (float) (line - rise * swell));
        }
        path.lineTo(right, line + 1);
        path.close();
    }
}
