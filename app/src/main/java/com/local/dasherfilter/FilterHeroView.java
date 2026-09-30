package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import java.util.Locale;

/**
 * The mascot, a funnel standing upright in a brush-drawn ring, with the last 24 hours in three quiet counts below:
 * passed, filtered and left to review. It is also the screen's one button: a tap pauses or resumes auto-decline (or,
 * with no rule yet, opens the rules), and it squishes a little while pressed. The ring says the state: nearly closed
 * while on, two-thirds and amber while paused, a faint dotted circle while off; it draws itself when the state
 * changes. On, the mascot breathes, blinks, has its sieve, and an offer ticket drifts down into it while a drop
 * falls from its spout; paused, it sleeps (dashed, amber, drifting "z"s); off, it is grey and still. "Filtered"
 * counts only offers the app acted on (a Decline tap, a decline request, or a hidden notification).
 */
@SuppressLint("ViewConstructor")
final class FilterHeroView extends View {
    enum State { ON, PAUSED, OFF }

    /** The drawing's own size; narrower screens scale it down whole. */
    private static final int DESIGN_WIDTH_DP = 320;
    private static final int DESIGN_HEIGHT_DP = 268;
    /** The drawing (ring, mascot, ticket) takes the design's top; the counts under it keep their own size. */
    private static final int ART_HEIGHT_DP = 196;
    private static final int COUNTS_HEIGHT_DP = 84;
    /** The least share of its size the drawing shrinks to when a screen is short. */
    private static final float MIN_SHARE = 0.3f;
    private static final float ON_SWEEP = 324;
    private static final float PAUSED_SWEEP = 228;
    private static final long RING_DRAW_MS = 1100;

    /** Stars around the ring: dp from the middle across, dp down, and size. */
    private static final float[][] TWINKLES = {
            {-98, 40, 5}, {-116, 118, 3.5f}, {-80, 170, 3}, {100, 30, 4}, {120, 96, 5.5f}, {88, 162, 3.5f}};

    private final Ui ui;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final DashPathEffect pausedDash;
    private final DashPathEffect dotted;
    private final Enso ring = new Enso();
    /** When the ring began drawing itself (uptime), for the current state. */
    private long ringFrom;
    /** What a tap does, as screen readers announce it: "Pause auto-decline", for example. */
    private String action;
    private State state = State.OFF;
    /** This dash's (or the last dash's) counts, and every offer's since the history was cleared. */
    private int passed;
    private int filtered;
    private int review;
    private int[] totals = new int[3];
    private String dashLabel = "No dash yet";

    FilterHeroView(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        pausedDash = new DashPathEffect(new float[] {ui.dp(7), ui.dp(5)}, 0);
        dotted = new DashPathEffect(new float[] {ui.dp(2), ui.dp(6)}, 0);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setClickable(true);
        setFocusable(true);
        setAccessibilityDelegate(new AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
                if (action != null) {
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,
                            action));
                }
            }
        });
    }

    State state() {
        return state;
    }

    /** What a tap does, in words for screen readers. */
    void setAction(String action) {
        this.action = action;
    }

    String action() {
        return action;
    }

    /** How much of the ring has drawn itself, 0 to 1; at once when Android's animations are off. */
    float ringDrawn() {
        return state == State.OFF ? 1f : Motion.settle(ringFrom, RING_DRAW_MS);
    }

    Mascot.Mood mood() {
        return Mascot.moodOf(state);
    }

    /**
     * @param dash       passed, filtered and review counts for the dash named by {@code dashLabel}
     * @param totals     the same for every offer since the history was cleared
     * @param dashLabel  "This dash", "Last dash" or "No dash yet"
     */
    void set(State state, int[] dash, int[] totals, String dashLabel) {
        if (state != this.state) ringFrom = SystemClock.uptimeMillis();
        this.state = state;
        this.passed = dash[0];
        this.filtered = dash[1];
        this.review = dash[2];
        this.totals = totals.clone();
        this.dashLabel = dashLabel;
        String mode = state == State.ON ? "Auto-decline on" : state == State.PAUSED ? "Auto-decline paused"
                : "Auto-decline off";
        setContentDescription(String.format(Locale.US,
                "%s. %s: %d offers, %d passed, %d filtered, %d to review. In all: %d passed, %d filtered, %d to review.",
                mode, dashLabel, passed + filtered + review, passed, filtered, review, totals[0], totals[1],
                totals[2]));
        invalidate();
    }

    private float designWidth(float width) {
        return Math.max(width, ui.dp(DESIGN_WIDTH_DP));
    }

    /**
     * As tall as the design at this width, or whatever the page gives it on one screen; asked with no limit (the
     * page working out what fits), it answers the least it reads well at.
     */
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        float scale = width / designWidth(width);
        int design = Math.round(ui.dp(ART_HEIGHT_DP) * scale) + ui.dp(COUNTS_HEIGHT_DP);
        // Side by side, the drawing and the counts share one short row.
        int least = Math.max(ui.dp(COUNTS_HEIGHT_DP) + ui.dp(16),
                Math.round(ui.dp(ART_HEIGHT_DP) * scale * MIN_SHARE));
        int height = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED ? least
                : resolveSize(design, heightSpec);
        setMeasuredDimension(width, height);
    }

    /**
     * Wide and short (a phone's one-screen page), the drawing stands at the left with the counts beside it; tall
     * enough, the counts sit under it.
     */
    boolean sideBySide() {
        return getHeight() > 0 && getHeight() < ui.dp(ART_HEIGHT_DP) * 0.8f + ui.dp(COUNTS_HEIGHT_DP)
                && getWidth() >= getHeight() * 1.9f;
    }

    /** The drawing's scale: the design fitted to the width, and to the height left above (or beside) the counts. */
    private float scale() {
        float byWidth = getWidth() / designWidth(getWidth());
        if (getHeight() <= 0) return byWidth;
        if (sideBySide()) return Math.min(getHeight() / (float) ui.dp(ART_HEIGHT_DP), getWidth() * 0.44f / ui.dp(ART_HEIGHT_DP));
        float byHeight = (getHeight() - ui.dp(COUNTS_HEIGHT_DP)) / (float) ui.dp(ART_HEIGHT_DP);
        return Math.max(0.2f, Math.min(byWidth, byHeight));
    }

    /** Where the drawing starts, so the drawing and the counts sit centred in the height given. */
    private float artTop(float scale) {
        return Math.max(0, (getHeight() - ui.dp(ART_HEIGHT_DP) * scale - ui.dp(COUNTS_HEIGHT_DP)) / 2);
    }

    @Override protected void onDraw(Canvas canvas) {
        float scale = scale();
        boolean side = sideBySide();
        float artSize = ui.dp(ART_HEIGHT_DP) * scale;
        // Side by side, the drawing's own width is one design height, centred in its square at the left.
        float designWidth = side ? ui.dp(ART_HEIGHT_DP) : getWidth() / scale;
        float artTop = side ? (getHeight() - artSize) / 2 : artTop(scale);
        canvas.save();
        canvas.translate(0, artTop);
        canvas.scale(scale, scale);
        float cx = designWidth / 2;
        // Tilting the phone slides the ring and its stars (far) against the ticket (near).
        slide(canvas, -4);
        drawRing(canvas, cx);
        drawTwinkles(canvas, cx);
        canvas.restore();
        if (state == State.ON) {
            slide(canvas, 8);
            drawDriftingTicket(canvas, cx);
            canvas.restore();
        }
        drawMascot(canvas, cx);
        canvas.restore();
        // The counts keep the page's text size however small the drawing is.
        if (side) {
            float room = getWidth() - artSize;
            drawCounts(canvas, artSize + room / 2f, room, (getHeight() - ui.dp(COUNTS_HEIGHT_DP)) / 2f);
        } else {
            drawCounts(canvas, getWidth() / 2f, getWidth(), artTop + ui.dp(ART_HEIGHT_DP) * scale);
        }
        if (state != State.OFF) Motion.next(this);
    }

    private void slide(Canvas canvas, float depth) {
        canvas.save();
        canvas.translate(Tilt.x() * ui.dp(depth), Tilt.y() * ui.dp(depth) * 0.6f);
    }

    private int stateColor() {
        return state == State.ON ? ui.accent : state == State.PAUSED ? Ui.WARNING : ui.inkMuted;
    }

    /** The brush-drawn ring the mascot stands in, over a faint wash; the brush breathes a little while on. */
    private void drawRing(Canvas canvas, float cx) {
        float cy = ui.dp(104);
        float radius = ui.dp(82);
        if (state == State.OFF) {
            line.setPathEffect(dotted);
            line.setColor(withAlpha(ui.inkMuted, 0x80));
            line.setStrokeWidth(Math.max(1, ui.dp(1.5f)));
            canvas.drawCircle(cx, cy, radius, line);
            line.setPathEffect(null);
            return;
        }
        fill.setColor(withAlpha(stateColor(), 0x12));
        canvas.drawCircle(cx, cy, radius - ui.dp(9), fill);
        float breathe = state == State.ON ? 1 + 0.06f * Motion.wave(6f, 0) : 1;
        ring.draw(canvas, withAlpha(stateColor(), state == State.ON ? 0xD9 : 0xBF), ui.page, cx, cy, radius,
                ui.dp(9) * breathe, state == State.ON ? ON_SWEEP : PAUSED_SWEEP, ringDrawn());
    }

    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    /** Small stars around the ring that brighten and fade in turn; none while off. */
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
        // Pressed, the mascot squishes down a little, like a button.
        float squish = isPressed() ? 0.93f : 1f;
        canvas.save();
        canvas.scale((1 + 0.012f * breathe) * squish, (1 + 0.012f * breathe) * squish, cx, ui.dp(150));
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
    /** "THIS DASH" over three counts, each with its word and, under it, the all-time total. */
    private void drawCounts(Canvas canvas, float cx, float width, float top) {
        text.setFakeBoldText(true);
        text.setTextSize(Math.min(ui.sp(10), ui.dp(14)));
        text.setLetterSpacing(0.12f);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(ui.inkMuted);
        int offers = passed + filtered + review;
        String heading = dashLabel.startsWith("No") ? dashLabel
                : dashLabel + " · " + offers + (offers == 1 ? " offer" : " offers");
        canvas.drawText(heading.toUpperCase(Locale.US), cx, top + ui.dp(8) - text.getFontMetrics().ascent / 2, text);
        text.setLetterSpacing(0);
        float row = top + ui.dp(30);
        float spacing = Math.min(ui.dp(96), width * 0.3f);
        drawCount(canvas, cx - spacing, row, OfferRule.Result.KEEP, passed, totals[0], "passed", state != State.OFF);
        drawCount(canvas, cx, row, OfferRule.Result.DECLINE, filtered, totals[1], "filtered", state == State.ON);
        drawCount(canvas, cx + spacing, row, OfferRule.Result.REVIEW, review, totals[2], "review",
                state != State.OFF);
    }

    private void drawCount(Canvas canvas, float x, float top, OfferRule.Result result, int count, int total,
                           String word, boolean live) {
        int color = Ui.resultColor(result);
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
        text.setTextSize(Math.min(ui.sp(11), ui.dp(15)));
        text.setColor(ui.inkMuted);
        canvas.drawText(total + " total", x, top + ui.dp(40) + text.getTextSize() / 2, text);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }
}
