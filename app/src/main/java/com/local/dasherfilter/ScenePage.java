package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * The main page as one illustration. A sky runs from the top of the page down to the horizon, where the offers'
 * skyline stands; the ground runs from there down to the road at the bottom. By day the sky is a soft morning blue
 * warming towards the horizon, with a sun and drifting clouds; by night (dark theme) it is deep blue, with a moon and
 * stars. The mascot, its state and the minimums' constellation sit in the sky; the chosen offer and the map on the
 * ground. Each region stays light enough (or dark enough) for the page's own text. While the user is dashing, two
 * searchlights sweep the sky from the horizon: the app is watching. Tilting the phone slides the far layers a little;
 * with Android's animations off, everything rests (the searchlights stand still). No star shines and no cloud drifts
 * under the sky's words (a cloud fades as it passes behind them), and the signpost keeps clear of the skyline, the
 * sky's words and its icons.
 */
@SuppressLint("ViewConstructor")
final class ScenePage extends LeastColumn {
    /** How far, in dp, each layer slides at full tilt: the farther away, the less. */
    private static final float STARS_DEPTH = 3;
    private static final float SUN_DEPTH = 5;
    private static final float CLOUD_DEPTH = 8;
    private static final float FAR_HILL_DEPTH = 4;
    private static final float NEAR_HILL_DEPTH = 7;
    /** How far, in dp, the signpost may rise from the hills to clear the skyline before it is left out. */
    private static final int SIGN_RISE_DP = 64;
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
    private View horizon;
    private int horizonInset;
    private View fallback;
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
    private float hillsFor = Float.NaN;
    /** What stands over the sky (its words and icons), and where they stand this frame, in this page's pixels. */
    private View overView;
    private Over over;
    private final java.util.List<android.graphics.RectF> words = new java.util.ArrayList<>();
    private final java.util.List<android.graphics.RectF> overIcons = new java.util.ArrayList<>();

    /** A view over the sky that says where its words and icons stand, in its own pixels. */
    interface Over {
        void wordsAt(java.util.List<android.graphics.RectF> out);

        void iconsAt(java.util.List<android.graphics.RectF> out);
    }

    ScenePage(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        setOrientation(VERTICAL);
        setWillNotDraw(false);
    }

    /**
     * The horizon is {@code insetPx} above the bottom of {@code view} (the skyline's street), or the bottom of
     * {@code otherwise} while that view is hidden.
     */
    void setHorizon(View view, int insetPx, View otherwise) {
        horizon = view;
        horizonInset = insetPx;
        fallback = otherwise;
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

    /** Stars and clouds keep out from under {@code view}'s words, and the signpost from its words and icons. */
    void setOver(View view, Over source) {
        overView = view;
        over = source;
    }

    /** Where the signpost's board was last drawn, in this page's pixels; null when it was left out (for tests). */
    android.graphics.RectF signBoard() {
        return place == null || signBoard.isEmpty() ? null : new android.graphics.RectF(signBoard);
    }

    /** Where the sky's words stand this frame, in this page's pixels (for tests). */
    java.util.List<android.graphics.RectF> words() {
        gatherOver();
        return new java.util.ArrayList<>(words);
    }

    private void gatherOver() {
        words.clear();
        overIcons.clear();
        if (over == null || overView == null || !overView.isShown() || overView.getHeight() <= 0) return;
        over.wordsAt(words);
        over.iconsAt(overIcons);
        float x = left(overView);
        float y = top(overView);
        for (android.graphics.RectF box : words) box.offset(x, y);
        for (android.graphics.RectF box : overIcons) box.offset(x, y);
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

    /** The sun (or moon) is drawn over this view, which is its button. */
    void setSunAnchor(View view) {
        sunAnchor = view;
    }

    /** Where the horizon is, in this page's coordinates. */
    float horizonY() {
        if (horizon != null && horizon.getVisibility() == VISIBLE && horizon.getHeight() > 0) {
            return top(horizon) + horizon.getHeight() - horizonInset;
        }
        if (fallback != null && fallback.getVisibility() == VISIBLE && fallback.getHeight() > 0) {
            return top(fallback) + fallback.getHeight() + ui.dp(24);
        }
        return getHeight() * 0.6f;
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
            canvas.drawCircle(x, y, ui.dp(21), fill);
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
        if (!(horizon instanceof DecisionChartView) || !horizon.isShown() || horizon.getHeight() <= 0) return rest;
        float chartLeft = left(horizon);
        float highest = top(horizon) + ((DecisionChartView) horizon).highestWithin(x - halfWidth - gap - chartLeft,
                x + halfWidth + gap - chartLeft);
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

    /** Two ranges of hills along the horizon, behind the skyline, the nearer darker. */
    private void drawHills(Canvas canvas, float width, float line) {
        if (hillsFor != line * 31 + width) {
            hillsFor = line * 31 + width;
            float over = ui.dp(12);
            hills(farHills, -over, width + over, line, ui.dp(46), 3.0);
            hills(nearHills, -over, width + over, line, ui.dp(26), 5.0);
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
