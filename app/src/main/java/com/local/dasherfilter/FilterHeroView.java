package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import java.util.Locale;

/**
 * The mascot, a funnel standing upright, with the last 24 hours in three quiet counts below: passed, filtered and
 * left to review. On, it breathes, blinks, has its sieve, and an offer ticket drifts down into it while a drop falls
 * from its spout; paused, it sleeps (dashed, amber, drifting "z"s); off, it is grey and still. "Filtered" counts only
 * offers the app acted on (a Decline tap, a decline request, or a hidden notification).
 */
@SuppressLint("ViewConstructor")
final class FilterHeroView extends View {
    enum State { ON, PAUSED, OFF }

    /** The drawing's own size; narrower screens scale it down whole. */
    private static final int DESIGN_WIDTH_DP = 320;
    private static final int DESIGN_HEIGHT_DP = 250;

    /** Stars around the halo: dp from the middle across, dp down, and size. */
    private static final float[][] TWINKLES = {
            {-98, 40, 5}, {-116, 118, 3.5f}, {-80, 170, 3}, {100, 30, 4}, {120, 96, 5.5f}, {88, 162, 3.5f}};

    private final Ui ui;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final DashPathEffect pausedDash;
    private State state = State.OFF;
    private int passed;
    private int filtered;
    private int review;

    FilterHeroView(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        pausedDash = new DashPathEffect(new float[] {ui.dp(7), ui.dp(5)}, 0);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    Mascot.Mood mood() {
        return Mascot.moodOf(state);
    }

    void set(State state, int passed, int filtered, int review) {
        this.state = state;
        this.passed = passed;
        this.filtered = filtered;
        this.review = review;
        String mode = state == State.ON ? "Auto-decline on" : state == State.PAUSED ? "Auto-decline paused"
                : "Auto-decline off";
        setContentDescription(String.format(Locale.US, "%s. Last 24 hours: %d passed, %d filtered, %d to review.",
                mode, passed, filtered, review));
        invalidate();
    }

    private float designWidth(float width) {
        return Math.max(width, ui.dp(DESIGN_WIDTH_DP));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        float scale = width / designWidth(width);
        setMeasuredDimension(width, resolveSize(Math.round(ui.dp(DESIGN_HEIGHT_DP) * scale), heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        float designWidth = designWidth(getWidth());
        float scale = getWidth() / designWidth;
        canvas.save();
        canvas.scale(scale, scale);
        float cx = designWidth / 2;
        // Tilting the phone slides the halo and its stars (far) against the ticket (near).
        slide(canvas, -4);
        drawHalo(canvas, cx);
        drawTwinkles(canvas, cx);
        canvas.restore();
        if (state == State.ON) {
            slide(canvas, 8);
            drawDriftingTicket(canvas, cx);
            canvas.restore();
        }
        drawMascot(canvas, cx);
        drawCounts(canvas, cx, designWidth);
        canvas.restore();
        if (state != State.OFF) Motion.next(this);
    }

    private void slide(Canvas canvas, float depth) {
        canvas.save();
        canvas.translate(Tilt.x() * ui.dp(depth), Tilt.y() * ui.dp(depth) * 0.6f);
    }

    private int stateColor() {
        return state == State.ON ? ui.accent : state == State.PAUSED ? Ui.WARNING : ui.inkMuted;
    }

    /** A soft circle of light behind the mascot. */
    private void drawHalo(Canvas canvas, float cx) {
        float breathe = state == State.ON ? Motion.wave(6f, 0) : 0;
        fill.setColor(withAlpha(stateColor(), state == State.OFF ? 0x0C : 0x16));
        canvas.drawCircle(cx, ui.dp(104), ui.dp(88) + ui.dp(3) * breathe, fill);
    }

    /** Small stars around the halo that brighten and fade in turn; none while off. */
    private void drawTwinkles(Canvas canvas, float cx) {
        if (state == State.OFF) return;
        int color = ui.dark ? 0xFFE9E2C8 : 0xFFE0B94F;
        for (int i = 0; i < TWINKLES.length; i++) {
            float[] star = TWINKLES[i];
            float twinkle = 0.5f + 0.5f * Motion.wave(2.4f + i * 0.45f, i * 0.23f);
            fill.setColor(withAlpha(color, (int) (40 + 150 * twinkle)));
            sparkle(canvas, cx + ui.dp(star[0]), ui.dp(star[1]), ui.dp(star[2]) * (0.55f + 0.45f * twinkle));
        }
    }

    private void sparkle(Canvas canvas, float x, float y, float half) {
        float waist = half * 0.22f;
        path.reset();
        path.moveTo(x, y - half);
        path.quadTo(x + waist, y - waist, x + half, y);
        path.quadTo(x + waist, y + waist, x, y + half);
        path.quadTo(x - waist, y + waist, x - half, y);
        path.quadTo(x - waist, y - waist, x, y - half);
        path.close();
        canvas.drawPath(path, fill);
    }

    /** One offer ticket on a small parachute, drifting down into the funnel's mouth, again and again. */
    private void drawDriftingTicket(Canvas canvas, float cx) {
        float t = Motion.on() ? Motion.loop(7f, 0) : 0.55f;
        float y = ui.dp(38) + ui.dp(30) * t;
        float x = cx + ui.dp(10) * Motion.wave(7f, 0.25f);
        int alpha = (int) (255 * Math.min(1f, Math.min(t * 5f, (1f - t) * 5f)));
        canvas.save();
        canvas.rotate(4 * Motion.wave(3.5f, 0), x, y);
        line.setPathEffect(null);
        line.setColor(withAlpha(ui.baseline, alpha));
        line.setStrokeWidth(Math.max(1, ui.dp(1)));
        float dome = y - ui.dp(22);
        canvas.drawLine(x - ui.dp(12), dome, x - ui.dp(10), y - ui.dp(8), line);
        canvas.drawLine(x + ui.dp(12), dome, x + ui.dp(10), y - ui.dp(8), line);
        rect.set(x - ui.dp(13), dome - ui.dp(13), x + ui.dp(13), dome + ui.dp(13));
        fill.setColor(withAlpha(ui.dark ? 0xFF3D5A85 : 0xFFA9C8F2, alpha));
        canvas.drawArc(rect, 180, 180, true, fill);
        rect.set(x - ui.dp(15), y - ui.dp(8), x + ui.dp(15), y + ui.dp(8));
        fill.setColor(withAlpha(ui.surface, alpha));
        canvas.drawRoundRect(rect, ui.dp(3), ui.dp(3), fill);
        line.setColor(withAlpha(ui.baseline, alpha));
        canvas.drawRoundRect(rect, ui.dp(3), ui.dp(3), line);
        text.setFakeBoldText(true);
        text.setTextSize(ui.dp(9));
        text.setColor(withAlpha(ui.inkSecondary, alpha));
        canvas.drawText("$", x - ui.dp(3), y + ui.dp(3), text);
        canvas.restore();
    }

    /** The mascot: rim (a sieve while on), body narrowing to the spout, arms, face; a drop falls while on. */
    private void drawMascot(Canvas canvas, float cx) {
        int color = stateColor();
        float breathe = state == State.OFF ? 0 : Motion.wave(4.5f, 0);
        canvas.save();
        canvas.scale(1 + 0.012f * breathe, 1 + 0.012f * breathe, cx, ui.dp(120));
        float rimY = ui.dp(72);
        float half = ui.dp(62);
        float neckY = ui.dp(146);
        float neck = ui.dp(11);
        float spoutY = ui.dp(164);

        float armY = ui.dp(104);
        float side = half - (half - neck) * (armY - rimY) / (neckY - rimY);
        line.setPathEffect(null);
        line.setColor(color);
        line.setStrokeWidth(ui.dp(3));
        path.reset();
        path.moveTo(cx - side, armY);
        path.quadTo(cx - side - ui.dp(13), armY + ui.dp(5), cx - side - ui.dp(16), armY + ui.dp(21));
        path.moveTo(cx + side, armY);
        path.quadTo(cx + side + ui.dp(13), armY + ui.dp(5), cx + side + ui.dp(16), armY + ui.dp(21));
        canvas.drawPath(path, line);

        path.reset();
        path.moveTo(cx - half, rimY);
        path.lineTo(cx - neck, neckY);
        path.lineTo(cx - neck, spoutY);
        path.lineTo(cx + neck, spoutY);
        path.lineTo(cx + neck, neckY);
        path.lineTo(cx + half, rimY);
        path.close();
        fill.setColor(ui.surface);
        canvas.drawPath(path, fill);
        fill.setColor(withAlpha(color, state == State.OFF ? 0x16 : 0x2A));
        canvas.drawPath(path, fill);
        line.setStrokeWidth(ui.dp(2.5f));
        line.setPathEffect(state == State.PAUSED ? pausedDash : null);
        canvas.drawPath(path, line);
        rect.set(cx - half, rimY - ui.dp(12), cx + half, rimY + ui.dp(12));
        fill.setColor(ui.surface);
        canvas.drawOval(rect, fill);
        fill.setColor(withAlpha(color, state == State.OFF ? 0x20 : 0x48));
        canvas.drawOval(rect, fill);
        canvas.drawOval(rect, line);
        line.setPathEffect(null);
        if (state == State.ON) {
            fill.setColor(withAlpha(color, 0xA0));
            for (int row = -1; row <= 1; row++) {
                for (int col = -4; col <= 4; col++) {
                    float x = cx + col * ui.dp(12) + (row == 0 ? ui.dp(6) : 0);
                    float y = rimY + row * ui.dp(5);
                    float dx = (x - cx) / (half - ui.dp(9));
                    float dy = (y - rimY) / ui.dp(8);
                    if (dx * dx + dy * dy <= 1) canvas.drawCircle(x, y, ui.dp(1.6f), fill);
                }
            }
        }
        // A blink every few seconds while awake.
        boolean blink = state == State.ON && Motion.on() && Motion.loop(5.3f, 0) > 0.965f;
        Mascot.face(canvas, blink ? Mascot.Mood.BLINK : mood(), cx, ui.dp(110), ui.dp(40),
                state == State.OFF ? ui.inkMuted : ui.ink);
        canvas.restore();

        if (state == State.ON) {
            float t = Motion.on() ? Motion.loop(1.8f, 0) : 0.4f;
            fill.setColor(withAlpha(color, (int) (0xC0 * (1 - t))));
            canvas.drawCircle(cx, spoutY + ui.dp(4) + ui.dp(16) * t * t, ui.dp(3), fill);
        } else if (state == State.PAUSED) {
            float t = Motion.on() ? Motion.loop(3.2f, 0) : 0.3f;
            text.setFakeBoldText(true);
            text.setColor(withAlpha(ui.inkSecondary, (int) (255 * (1 - t))));
            text.setTextSize(ui.dp(12) + ui.dp(6) * t);
            canvas.drawText("z", cx + ui.dp(36) + ui.dp(10) * t, ui.dp(92) - ui.dp(28) * t, text);
        }
    }

    /** Three quiet counts under the mascot: a small badge, the number, and its word. */
    private void drawCounts(Canvas canvas, float cx, float width) {
        float spacing = Math.min(ui.dp(96), width * 0.3f);
        drawCount(canvas, cx - spacing, OfferRule.Result.KEEP, passed, "passed", state != State.OFF);
        drawCount(canvas, cx, OfferRule.Result.DECLINE, filtered, "filtered", state == State.ON);
        drawCount(canvas, cx + spacing, OfferRule.Result.REVIEW, review, "review", state != State.OFF);
    }

    private void drawCount(Canvas canvas, float x, OfferRule.Result result, int count, String word, boolean live) {
        int color = Ui.resultColor(result);
        float top = ui.dp(192);
        // The number and its word size with the font setting, shrunk only as far as their space needs.
        text.setFakeBoldText(true);
        float numberSize = Math.min(ui.sp(18), ui.dp(26));
        text.setTextSize(numberSize);
        String number = Integer.toString(count);
        float badge = ui.dp(8);
        float numberWidth = text.measureText(number);
        float rowWidth = badge * 2 + ui.dp(6) + numberWidth;
        float left = x - rowWidth / 2;
        fill.setColor(withAlpha(color, live ? 0xFF : 0x60));
        canvas.drawCircle(left + badge, top, badge, fill);
        Paint.FontMetrics metrics = text.getFontMetrics();
        text.setColor(live ? ui.ink : ui.inkMuted);
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(number, left + badge * 2 + ui.dp(6), top - (metrics.ascent + metrics.descent) / 2, text);
        text.setTextSize(ui.dp(9));
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(withAlpha(Ui.onStatus(color), live ? 0xFF : 0xB0));
        canvas.drawText(Ui.resultSymbol(result), left + badge, top + text.getTextSize() / 3, text);
        text.setFakeBoldText(false);
        text.setTextSize(Math.min(ui.sp(12), ui.dp(17)));
        text.setColor(live ? ui.inkSecondary : ui.inkMuted);
        canvas.drawText(word, x, top + ui.dp(22) + text.getTextSize() / 2, text);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }
}
