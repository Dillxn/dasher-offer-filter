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
import android.widget.LinearLayout;

/**
 * The main page as one illustration. A sky runs from the top of the page down to the horizon, where the offers'
 * skyline stands; the ground runs from there down to the road at the bottom. By day the sky is a soft morning blue
 * warming towards the horizon, with a sun and drifting clouds; by night (dark theme) it is deep blue, with a moon and
 * stars. The mascot, its state and the minimums' constellation sit in the sky; the chosen offer and the map on the
 * ground. Each region stays light enough (or dark enough) for the page's own text. While the user is dashing, two
 * searchlights sweep the sky from the horizon: the app is watching. Tilting the phone slides the far layers a little;
 * with Android's animations off, everything rests (the searchlights stand still).
 */
@SuppressLint("ViewConstructor")
final class ScenePage extends LinearLayout {
    /** How far, in dp, each layer slides at full tilt: the farther away, the less. */
    private static final float STARS_DEPTH = 3;
    private static final float SUN_DEPTH = 5;
    private static final float CLOUD_DEPTH = 8;
    private static final float FAR_HILL_DEPTH = 4;
    private static final float NEAR_HILL_DEPTH = 7;
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
    private Shader sky;
    private Shader ground;
    private Shader sunGlow;
    private float shadedFor = Float.NaN;
    private float hillsFor = Float.NaN;

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

        if (ui.dark) drawStars(canvas, width, line);
        drawSun(canvas, width);
        if (!ui.dark) drawClouds(canvas, width, line);
        if (watching) drawSearchlights(canvas, width, line);
        drawHills(canvas, width, line);
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
        for (float[] star : STARS) {
            float twinkle = 0.5f + 0.5f * Motion.wave(2.2f + star[3] * 3f, star[3]);
            int alpha = (int) (0x50 + 0x9F * twinkle * (1 - 0.5f * star[1]));
            fill.setColor((Math.min(255, alpha) << 24) | 0xFFF3E9);
            canvas.drawCircle(width * star[0], ui.dp(8) + reach * star[1], ui.dp(star[2]) * (0.7f + 0.3f * twinkle),
                    fill);
        }
        canvas.restore();
    }

    /** By day a sun, by night a crescent moon, up by the settings button, both with a soft glow. */
    private void drawSun(Canvas canvas, float width) {
        float x = width * 0.68f;
        float y = ui.dp(44);
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

    private void drawCloud(Canvas canvas, float x, float y, float size, int color) {
        fill.setColor(color);
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
