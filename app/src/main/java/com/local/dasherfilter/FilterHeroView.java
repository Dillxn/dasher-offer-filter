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
 * The filter as a little machine: offer tickets float down on parachutes into the mascot, a funnel standing
 * upright, and drop from its spout into three containers with the last 24 hours' counts: a basket for passed
 * offers, a bin for filtered ones and a crate for those left to review. On, the mascot is cheerful and has its
 * sieve; paused, it is asleep and open (dashed, amber) and nothing reaches the bin; off, it is grey and blank.
 * "Filtered" counts only offers the app acted on (a Decline tap, a decline request, or a hidden notification).
 */
@SuppressLint("ViewConstructor")
final class FilterHeroView extends View {
    enum State { ON, PAUSED, OFF }

    /** The drawing's own size; narrower screens scale it down whole. */
    private static final int DESIGN_WIDTH_DP = 320;
    private static final int DESIGN_HEIGHT_DP = 342;
    private static final int[] CANOPIES_LIGHT = {0xFFA9C8F2, 0xFFF4BCCB, 0xFFBFE3B4};
    private static final int[] CANOPIES_DARK = {0xFF3D5A85, 0xFF7A4452, 0xFF3F6A3A};

    private final Ui ui;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private State state = State.OFF;
    private int passed;
    private int filtered;
    private int review;
    /** Shrinks the container labels to fit large system font sizes; decided once per drawing. */
    private float labelScale = 1f;

    FilterHeroView(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
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
        drawScene(canvas, designWidth);
        canvas.restore();
    }

    private int stateColor() {
        return state == State.ON ? ui.accent : state == State.PAUSED ? Ui.WARNING : ui.inkMuted;
    }

    private void drawScene(Canvas canvas, float width) {
        float cx = width / 2;
        int[] canopies = ui.dark ? CANOPIES_DARK : CANOPIES_LIGHT;
        boolean off = state == State.OFF;
        // Kept off the middle, where the speech bubble above points down at the mascot.
        drawParachute(canvas, cx - ui.dp(116), ui.dp(74), -10, off ? ui.gridline : canopies[0]);
        drawParachute(canvas, cx + ui.dp(118), ui.dp(80), 9, off ? ui.gridline : canopies[1]);
        drawParachute(canvas, cx + ui.dp(62), ui.dp(58), -5, off ? ui.gridline : canopies[2]);

        float spacing = Math.min(ui.dp(104), width * 0.32f);
        float binTop = ui.dp(254);
        float spoutY = ui.dp(216);
        drawFlow(canvas, cx, spoutY, cx - spacing, binTop, OfferRule.Result.KEEP, state != State.OFF);
        drawFlow(canvas, cx, spoutY, cx, binTop, OfferRule.Result.DECLINE, state == State.ON);
        drawFlow(canvas, cx, spoutY, cx + spacing, binTop, OfferRule.Result.REVIEW, state != State.OFF);
        drawMascot(canvas, cx);

        fitLabels(spacing - ui.dp(8), ui.dp(DESIGN_HEIGHT_DP) - ui.dp(302));
        drawBasket(canvas, cx - spacing, binTop, state != State.OFF);
        drawBin(canvas, cx, binTop, state == State.ON);
        drawCrate(canvas, cx + spacing, binTop, state != State.OFF);
        drawCount(canvas, cx - spacing, passed, "passed", state != State.OFF);
        drawCount(canvas, cx, filtered, "filtered", state == State.ON);
        drawCount(canvas, cx + spacing, review, "review", state != State.OFF);
    }

    /** An offer ticket hanging from a parachute, tilted by {@code degrees}. */
    private void drawParachute(Canvas canvas, float x, float y, float degrees, int canopy) {
        canvas.save();
        canvas.rotate(degrees, x, y);
        float half = ui.dp(19);
        float domeY = y - ui.dp(34);
        // Strings from the canopy's rim to the ticket's top corners.
        line.setColor(ui.baseline);
        line.setStrokeWidth(Math.max(1, ui.dp(1)));
        line.setPathEffect(null);
        canvas.drawLine(x - half + ui.dp(2), domeY, x - ui.dp(16), y - ui.dp(12), line);
        canvas.drawLine(x + half - ui.dp(2), domeY, x + ui.dp(16), y - ui.dp(12), line);
        canvas.drawLine(x, domeY, x, y - ui.dp(12), line);
        rect.set(x - half, domeY - half, x + half, domeY + half);
        fill.setColor(canopy);
        canvas.drawArc(rect, 180, 180, true, fill);
        fill.setColor(0x33FFFFFF);
        rect.set(x - half / 3, domeY - half, x + half / 3, domeY + half);
        canvas.drawArc(rect, 180, 180, true, fill);
        // The ticket.
        rect.set(x - ui.dp(23), y - ui.dp(13), x + ui.dp(23), y + ui.dp(13));
        fill.setColor(ui.surface);
        canvas.drawRoundRect(rect, ui.dp(5), ui.dp(5), fill);
        line.setColor(ui.baseline);
        line.setStrokeWidth(Math.max(1, ui.dp(1.5f)));
        canvas.drawRoundRect(rect, ui.dp(5), ui.dp(5), line);
        line.setPathEffect(new DashPathEffect(new float[] {ui.dp(2), ui.dp(2)}, 0));
        float cut = rect.right - ui.dp(13);
        canvas.drawLine(cut, rect.top + ui.dp(3), cut, rect.bottom - ui.dp(3), line);
        line.setPathEffect(null);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        // Part of the picture, not reading text: sized with it rather than with the font setting.
        text.setTextSize(ui.dp(13));
        text.setColor(ui.inkSecondary);
        canvas.drawText("$", rect.left + (cut - rect.left) / 2, y + ui.dp(4.5f), text);
        canvas.restore();
    }

    /** The mascot: rim, body narrowing to the spout, a sieve while on, arms, and its face. */
    private void drawMascot(Canvas canvas, float cx) {
        int color = stateColor();
        float rimY = ui.dp(108);
        float half = ui.dp(74);
        float neckY = ui.dp(194);
        float neck = ui.dp(13);
        float spoutY = ui.dp(216);

        // Arms first, so the body covers where they join; one waves while on.
        float armY = ui.dp(146);
        float side = half - (half - neck) * (armY - rimY) / (neckY - rimY);
        boolean waving = state == State.ON;
        line.setColor(color);
        line.setStrokeWidth(ui.dp(3.5f));
        line.setPathEffect(null);
        path.reset();
        path.moveTo(cx - side, armY);
        path.quadTo(cx - side - ui.dp(16), armY + ui.dp(6), cx - side - ui.dp(20), armY + ui.dp(26));
        path.moveTo(cx + side, armY);
        if (waving) {
            path.quadTo(cx + side + ui.dp(16), armY - ui.dp(2), cx + side + ui.dp(22), armY - ui.dp(24));
        } else {
            path.quadTo(cx + side + ui.dp(16), armY + ui.dp(6), cx + side + ui.dp(20), armY + ui.dp(26));
        }
        canvas.drawPath(path, line);
        fill.setColor(color);
        canvas.drawCircle(cx - side - ui.dp(20), armY + ui.dp(28), ui.dp(4), fill);
        canvas.drawCircle(cx + side + ui.dp(waving ? 22 : 20), armY + ui.dp(waving ? -26 : 28), ui.dp(4), fill);

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
        fill.setColor(withAlpha(color, state == State.OFF ? 0x18 : 0x30));
        canvas.drawPath(path, fill);
        line.setStrokeWidth(ui.dp(2.5f));
        line.setPathEffect(state == State.PAUSED ? new DashPathEffect(new float[] {ui.dp(7), ui.dp(5)}, 0) : null);
        canvas.drawPath(path, line);

        rect.set(cx - half, rimY - ui.dp(14), cx + half, rimY + ui.dp(14));
        fill.setColor(ui.surface);
        canvas.drawOval(rect, fill);
        fill.setColor(withAlpha(color, state == State.OFF ? 0x26 : 0x55));
        canvas.drawOval(rect, fill);
        canvas.drawOval(rect, line);
        line.setPathEffect(null);
        if (state == State.ON) {
            // The sieve: a mesh of holes across the rim.
            fill.setColor(withAlpha(color, 0xB0));
            for (int row = -1; row <= 1; row++) {
                for (int col = -5; col <= 5; col++) {
                    float x = cx + col * ui.dp(12) + (row == 0 ? ui.dp(6) : 0);
                    float y = rimY + row * ui.dp(6);
                    float dx = (x - cx) / (half - ui.dp(10));
                    float dy = (y - rimY) / ui.dp(9);
                    if (dx * dx + dy * dy <= 1) canvas.drawCircle(x, y, ui.dp(1.8f), fill);
                }
            }
        }
        Mascot.face(canvas, mood(), cx, ui.dp(150), ui.dp(46), state == State.OFF ? ui.inkMuted : ui.ink);
        if (state == State.PAUSED) Mascot.snore(canvas, cx + ui.dp(40), ui.dp(134), ui.dp(16), ui.inkSecondary);
    }

    private void drawFlow(Canvas canvas, float fromX, float fromY, float toX, float toY, OfferRule.Result result,
                          boolean live) {
        if (!live) return;
        path.reset();
        path.moveTo(fromX, fromY);
        path.cubicTo(fromX, fromY + ui.dp(20), toX, toY - ui.dp(24), toX, toY - ui.dp(6));
        line.setColor(withAlpha(Ui.resultColor(result), 0xB0));
        line.setStrokeWidth(ui.dp(2.5f));
        line.setPathEffect(new DashPathEffect(new float[] {ui.dp(5), ui.dp(5)}, 0));
        canvas.drawPath(path, line);
        line.setPathEffect(null);
    }

    /** A woven basket for the offers that passed. */
    private void drawBasket(Canvas canvas, float x, float top, boolean live) {
        int color = Ui.GOOD;
        float halfTop = ui.dp(36);
        float halfBottom = ui.dp(28);
        float bottom = top + ui.dp(44);
        drawPeeking(canvas, x, top, passed, live);
        path.reset();
        path.moveTo(x - halfTop, top);
        path.lineTo(x + halfTop, top);
        path.lineTo(x + halfBottom, bottom);
        path.lineTo(x - halfBottom, bottom);
        path.close();
        fill.setColor(ui.surface);
        canvas.drawPath(path, fill);
        fill.setColor(withAlpha(color, live ? 0x33 : 0x14));
        canvas.drawPath(path, fill);
        line.setColor(withAlpha(color, live ? 0xFF : 0x70));
        line.setStrokeWidth(ui.dp(2));
        canvas.drawPath(path, line);
        // The weave.
        line.setStrokeWidth(Math.max(1, ui.dp(1.2f)));
        for (int i = 1; i <= 2; i++) {
            float y = top + ui.dp(44) * i / 3f;
            float inset = (halfTop - halfBottom) * i / 3f;
            canvas.drawLine(x - halfTop + inset, y, x + halfTop - inset, y, line);
        }
        for (int i = -2; i <= 2; i++) {
            canvas.drawLine(x + i * ui.dp(13), top, x + i * ui.dp(10.5f), bottom, line);
        }
        drawBadge(canvas, x, top + ui.dp(22), OfferRule.Result.KEEP, live);
    }

    /** A bin with its lid tipped open for the filtered offers. */
    private void drawBin(Canvas canvas, float x, float top, boolean live) {
        int color = Ui.CRITICAL;
        float half = ui.dp(28);
        float bottom = top + ui.dp(44);
        drawPeeking(canvas, x, top, filtered, live);
        rect.set(x - half, top + ui.dp(4), x + half, bottom);
        fill.setColor(ui.surface);
        canvas.drawRoundRect(rect, ui.dp(4), ui.dp(4), fill);
        fill.setColor(withAlpha(color, live ? 0x2E : 0x12));
        canvas.drawRoundRect(rect, ui.dp(4), ui.dp(4), fill);
        line.setColor(withAlpha(color, live ? 0xFF : 0x70));
        line.setStrokeWidth(ui.dp(2));
        canvas.drawRoundRect(rect, ui.dp(4), ui.dp(4), line);
        line.setStrokeWidth(Math.max(1, ui.dp(1.2f)));
        for (int i = -1; i <= 1; i += 2) {
            canvas.drawLine(x + i * ui.dp(15), top + ui.dp(10), x + i * ui.dp(15), bottom - ui.dp(6), line);
        }
        // The lid, lifted off and tilted above the bin, with its handle.
        canvas.save();
        canvas.rotate(-12, x + ui.dp(10), top - ui.dp(10));
        rect.set(x - half + ui.dp(8), top - ui.dp(14), x + half + ui.dp(12), top - ui.dp(8));
        fill.setColor(withAlpha(color, live ? 0xFF : 0x70));
        canvas.drawRoundRect(rect, ui.dp(3), ui.dp(3), fill);
        rect.set(x + ui.dp(4), top - ui.dp(19), x + ui.dp(16), top - ui.dp(13));
        canvas.drawRoundRect(rect, ui.dp(3), ui.dp(3), fill);
        canvas.restore();
        drawBadge(canvas, x, top + ui.dp(25), OfferRule.Result.DECLINE, live);
    }

    /** A taped crate for the offers left to review. */
    private void drawCrate(Canvas canvas, float x, float top, boolean live) {
        int color = 0xFFC98A0B;
        float half = ui.dp(31);
        float bottom = top + ui.dp(44);
        drawPeeking(canvas, x, top, review, live);
        rect.set(x - half, top, x + half, bottom);
        fill.setColor(ui.surface);
        canvas.drawRect(rect, fill);
        fill.setColor(withAlpha(Ui.WARNING, live ? 0x40 : 0x16));
        canvas.drawRect(rect, fill);
        line.setColor(withAlpha(color, live ? 0xFF : 0x70));
        line.setStrokeWidth(ui.dp(2));
        canvas.drawRect(rect, line);
        line.setStrokeWidth(Math.max(1, ui.dp(1.2f)));
        canvas.drawLine(x - half, top + ui.dp(11), x + half, top + ui.dp(11), line);
        canvas.drawLine(x - half, bottom - ui.dp(11), x + half, bottom - ui.dp(11), line);
        drawBadge(canvas, x, top + ui.dp(22), OfferRule.Result.REVIEW, live);
    }

    /** One or two tiny tickets sticking out of a container that holds any. */
    private void drawPeeking(Canvas canvas, float x, float top, int count, boolean live) {
        if (count <= 0) return;
        for (int i = 0; i < Math.min(2, count); i++) {
            canvas.save();
            canvas.rotate(i == 0 ? -14 : 12, x + (i == 0 ? -ui.dp(10) : ui.dp(12)), top);
            rect.set(x + (i == 0 ? -ui.dp(24) : 0), top - ui.dp(12), x + (i == 0 ? ui.dp(4) : ui.dp(26)),
                    top + ui.dp(8));
            fill.setColor(ui.surface);
            canvas.drawRoundRect(rect, ui.dp(3), ui.dp(3), fill);
            line.setColor(withAlpha(ui.baseline, live ? 0xFF : 0x90));
            line.setStrokeWidth(Math.max(1, ui.dp(1.2f)));
            canvas.drawRoundRect(rect, ui.dp(3), ui.dp(3), line);
            canvas.restore();
        }
    }

    private void drawBadge(Canvas canvas, float x, float y, OfferRule.Result result, boolean live) {
        int color = Ui.resultColor(result);
        float radius = ui.dp(11);
        fill.setColor(ui.surface);
        canvas.drawCircle(x, y, radius + ui.dp(2), fill);
        fill.setColor(withAlpha(color, live ? 0xFF : 0x70));
        canvas.drawCircle(x, y, radius, fill);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        text.setTextSize(ui.dp(12));
        text.setColor(withAlpha(Ui.onStatus(color), live ? 0xFF : 0xB0));
        canvas.drawText(Ui.resultSymbol(result), x, y + text.getTextSize() / 3, text);
    }

    /** One scale for the three labels under the containers, so the widest and the tallest fit. */
    private void fitLabels(float availableWidth, float availableHeight) {
        float widest = 0;
        float tallest = 0;
        for (Object[] label : new Object[][] {{passed, "passed"}, {filtered, "filtered"}, {review, "review"}}) {
            text.setFakeBoldText(true);
            text.setTextSize(ui.sp(17));
            float number = text.measureText(label[0].toString());
            float height = Ui.lineHeight(text);
            text.setFakeBoldText(false);
            text.setTextSize(ui.sp(12));
            widest = Math.max(widest, Math.max(number, text.measureText((String) label[1])));
            tallest = Math.max(tallest, height + Ui.lineHeight(text));
        }
        labelScale = Math.min(1f, Math.min(availableWidth / widest, availableHeight / tallest));
    }

    private void drawCount(Canvas canvas, float x, int count, String word, boolean live) {
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        text.setTextSize(ui.sp(17) * labelScale);
        Paint.FontMetrics metrics = text.getFontMetrics();
        float y = ui.dp(302) - metrics.ascent;
        text.setColor(live ? ui.ink : ui.inkMuted);
        canvas.drawText(Integer.toString(count), x, y, text);
        float next = y + metrics.descent;
        text.setFakeBoldText(false);
        text.setTextSize(ui.sp(12) * labelScale);
        text.setColor(live ? ui.inkSecondary : ui.inkMuted);
        canvas.drawText(word, x, next - text.getFontMetrics().ascent, text);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
