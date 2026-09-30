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
 * The status card's picture of what the filter does: offer tickets flow into a funnel lying on its side, and out
 * to three piles with the last 24 hours' counts. On, the funnel has its sieve and sends failing offers to the
 * filtered pile; paused, it is open (dashed, amber) and nothing reaches that pile; off, it is grey and idle.
 * "Filtered" counts only offers the app acted on (a Decline tap, a decline request, or a hidden notification).
 */
@SuppressLint("ViewConstructor")
final class FilterHeroView extends View {
    enum State { ON, PAUSED, OFF }

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
    /** Shrinks the pile labels to fit large system font sizes; decided once per drawing. */
    private float labelScale = 1f;

    FilterHeroView(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
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

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), resolveSize(ui.dp(132), heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        // Drawn at no less than its design width and scaled down to fit, so narrow phones see all of it.
        float designWidth = Math.max(getWidth(), ui.dp(300));
        float scale = getWidth() / designWidth;
        canvas.save();
        canvas.translate(0, getHeight() * (1 - scale) / 2);
        canvas.scale(scale, scale);
        drawScene(canvas, designWidth, getHeight() / 2f);
        canvas.restore();
    }

    private void drawScene(Canvas canvas, float width, float middle) {
        int color = state == State.ON ? ui.accent : state == State.PAUSED ? Ui.WARNING : ui.inkMuted;

        // The funnel, mouth to the left.
        float mouthX = Math.max(ui.dp(84), width * 0.30f);
        float mouthHalf = ui.dp(40);
        float neckX = mouthX + ui.dp(46);
        float spoutX = neckX + ui.dp(22);
        float spoutHalf = ui.dp(7);

        drawTickets(canvas, mouthX, middle);

        path.reset();
        path.moveTo(mouthX, middle - mouthHalf);
        path.lineTo(neckX, middle - spoutHalf);
        path.lineTo(spoutX, middle - spoutHalf);
        path.lineTo(spoutX, middle + spoutHalf);
        path.lineTo(neckX, middle + spoutHalf);
        path.lineTo(mouthX, middle + mouthHalf);
        path.close();
        fill.setColor(withAlpha(color, state == State.OFF ? 0x14 : 0x2E));
        canvas.drawPath(path, fill);
        line.setColor(color);
        line.setStrokeWidth(ui.dp(2));
        line.setPathEffect(state == State.PAUSED ? new DashPathEffect(new float[] {ui.dp(5), ui.dp(4)}, 0) : null);
        canvas.drawPath(path, line);
        rect.set(mouthX - ui.dp(6), middle - mouthHalf, mouthX + ui.dp(6), middle + mouthHalf);
        canvas.drawOval(rect, line);
        line.setPathEffect(null);

        if (state == State.ON) {
            // The sieve: the rules every offer passes through.
            line.setStrokeWidth(ui.dp(2));
            for (int i = 1; i <= 3; i++) {
                float x = mouthX + ui.dp(11) * i;
                float half = mouthHalf - (mouthHalf - spoutHalf) * (x - mouthX) / (neckX - mouthX) - ui.dp(6);
                canvas.drawLine(x, middle - half, x, middle + half, line);
            }
        } else if (state == State.PAUSED) {
            fill.setColor(Ui.WARNING);
            float barX = mouthX + ui.dp(18);
            rect.set(barX, middle - ui.dp(9), barX + ui.dp(4), middle + ui.dp(9));
            canvas.drawRoundRect(rect, ui.dp(1), ui.dp(1), fill);
            rect.offset(ui.dp(8), 0);
            canvas.drawRoundRect(rect, ui.dp(1), ui.dp(1), fill);
        }

        // Three outcomes fanned out from the spout.
        float pileX = spoutX + ui.dp(40);
        float gap = ui.dp(36);
        fitLabels(width - (pileX + ui.dp(18)) - ui.dp(2), gap - ui.dp(4));
        drawOutcome(canvas, spoutX, middle, pileX, middle - gap, OfferRule.Result.KEEP, passed, "passed",
                state != State.OFF);
        drawOutcome(canvas, spoutX, middle, pileX, middle, OfferRule.Result.DECLINE, filtered, "filtered",
                state == State.ON);
        drawOutcome(canvas, spoutX, middle, pileX, middle + gap, OfferRule.Result.REVIEW, review, "review",
                state != State.OFF);
    }

    /** One scale for all three pile labels, so the widest and the tallest fit their space. */
    private void fitLabels(float availableWidth, float availableHeight) {
        float widest = 0;
        float tallest = 0;
        for (Object[] label : new Object[][] {{passed, "passed"}, {filtered, "filtered"}, {review, "review"}}) {
            text.setFakeBoldText(true);
            text.setTextSize(ui.sp(16));
            float width = text.measureText(label[0].toString()) + ui.dp(5);
            tallest = Math.max(tallest, Ui.lineHeight(text));
            text.setFakeBoldText(false);
            text.setTextSize(ui.sp(13));
            widest = Math.max(widest, width + text.measureText((String) label[1]));
        }
        labelScale = Math.min(1f, Math.min(availableWidth / widest, availableHeight / tallest));
    }

    /** Three offer tickets on their way into the funnel's mouth. */
    private void drawTickets(Canvas canvas, float mouthX, float middle) {
        float ticketWidth = ui.dp(42);
        float ticketHeight = ui.dp(24);
        float[][] placements = {{mouthX - ui.dp(40), middle - ui.dp(30), -9}, {mouthX - ui.dp(62), middle, 5},
                {mouthX - ui.dp(38), middle + ui.dp(30), -4}};
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        // Part of the picture, not reading text: sized with it rather than with the font setting.
        text.setTextSize(ui.dp(12));
        for (float[] placement : placements) {
            canvas.save();
            canvas.rotate(placement[2], placement[0], placement[1]);
            rect.set(placement[0] - ticketWidth / 2, placement[1] - ticketHeight / 2,
                    placement[0] + ticketWidth / 2, placement[1] + ticketHeight / 2);
            fill.setColor(ui.surface);
            canvas.drawRoundRect(rect, ui.dp(5), ui.dp(5), fill);
            line.setColor(ui.baseline);
            line.setStrokeWidth(Math.max(1, ui.dp(1.5f)));
            canvas.drawRoundRect(rect, ui.dp(5), ui.dp(5), line);
            // A perforation near one end makes it read as a ticket.
            line.setPathEffect(new DashPathEffect(new float[] {ui.dp(2), ui.dp(2)}, 0));
            float cut = rect.right - ui.dp(12);
            canvas.drawLine(cut, rect.top + ui.dp(3), cut, rect.bottom - ui.dp(3), line);
            line.setPathEffect(null);
            text.setColor(ui.inkSecondary);
            canvas.drawText("$", rect.left + (cut - rect.left) / 2, placement[1] + text.getTextSize() / 3, text);
            canvas.restore();
        }
    }

    private void drawOutcome(Canvas canvas, float fromX, float fromY, float x, float y, OfferRule.Result result,
                             int count, String word, boolean live) {
        int color = Ui.resultColor(result);
        float radius = ui.dp(11);
        if (live) {
            path.reset();
            path.moveTo(fromX, fromY);
            path.cubicTo(fromX + ui.dp(18), fromY, x - radius - ui.dp(22), y, x - radius - ui.dp(4), y);
            line.setColor(withAlpha(color, 0xB0));
            line.setStrokeWidth(ui.dp(2));
            line.setPathEffect(new DashPathEffect(new float[] {ui.dp(4), ui.dp(4)}, 0));
            canvas.drawPath(path, line);
            line.setPathEffect(null);
        }
        // An idle pile is faded, but its label stays in readable muted ink rather than being made transparent.
        fill.setColor(withAlpha(color, live ? 0xFF : 0x70));
        canvas.drawCircle(x, y, radius, fill);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        text.setTextSize(ui.dp(11));
        text.setColor(withAlpha(Ui.onStatus(color), live ? 0xFF : 0xB0));
        canvas.drawText(Ui.resultSymbol(result), x, y + text.getTextSize() / 3, text);

        float textX = x + radius + ui.dp(7);
        String number = Integer.toString(count);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(ui.sp(16) * labelScale);
        text.setColor(live ? ui.ink : ui.inkMuted);
        canvas.drawText(number, textX, y + text.getTextSize() / 3, text);
        float numberWidth = text.measureText(number);
        text.setFakeBoldText(false);
        text.setTextSize(ui.sp(13) * labelScale);
        text.setColor(live ? ui.inkSecondary : ui.inkMuted);
        canvas.drawText(word, textX + numberWidth + ui.dp(5), y + text.getTextSize() / 3, text);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
