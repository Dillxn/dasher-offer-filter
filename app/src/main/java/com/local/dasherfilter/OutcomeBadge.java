package com.local.dasherfilter;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * What became of an offer as a small round badge: the outcome's colour with ✓, ✕ or ?, a shopping bag for an accepted
 * offer and a person for one left to the user. The skyline's flags and the mascot's offers draw the same badge.
 * Drawing happens on the main thread only, so the paints are shared.
 */
final class OutcomeBadge {
    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint LINE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint SYMBOL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path PATH = new Path();
    private static final RectF RECT = new RectF();

    static {
        LINE.setStyle(Paint.Style.STROKE);
        LINE.setStrokeCap(Paint.Cap.ROUND);
        SYMBOL.setTextAlign(Paint.Align.CENTER);
        SYMBOL.setFakeBoldText(true);
    }

    private OutcomeBadge() {}

    /** The badge centred at ({@code x}, {@code y}), ringed in the page's surface, at {@code alpha} (0–255). */
    static void draw(Canvas canvas, Ui ui, float x, float y, DecisionLog.Outcome outcome, int alpha) {
        int color = Ui.outcomeColor(outcome);
        float radius = ui.dp(7);
        FILL.setColor(withAlpha(ui.surface, alpha));
        canvas.drawCircle(x, y, radius + ui.dp(2), FILL);
        FILL.setColor(withAlpha(color, alpha));
        canvas.drawCircle(x, y, radius, FILL);
        int ink = withAlpha(Ui.onStatus(color), alpha);
        drawSymbol(canvas, ui, x, y, outcome, ink);
    }

    /** A quiet roof mark for history; the selected offer retains the full coloured badge. */
    static void drawQuiet(Canvas canvas, Ui ui, float x, float y, DecisionLog.Outcome outcome) {
        drawSymbol(canvas, ui, x, y, outcome, ui.inkSecondary);
    }

    private static void drawSymbol(Canvas canvas, Ui ui, float x, float y,
            DecisionLog.Outcome outcome, int ink) {
        switch (outcome) {
            case ACCEPTED:
                drawBag(canvas, ui, x, y, ink);
                break;
            case YOURS:
                drawPerson(canvas, ui, x, y, ink);
                break;
            default:
                // The symbol belongs to its circle, so it follows the font setting only as far as the circle allows.
                SYMBOL.setTextSize(Math.min(ui.sp(10), ui.dp(11)));
                SYMBOL.setColor(ink);
                canvas.drawText(outcome == DecisionLog.Outcome.PASSED ? "✓"
                        : outcome == DecisionLog.Outcome.DECLINED ? "✕" : "?", x, y + SYMBOL.getTextSize() / 3f, SYMBOL);
                break;
        }
    }

    /**
     * A shopping bag (the order picked up): the offer was accepted. Its body widens to the bottom and its handle is a
     * wide, shallow loop, so it never reads as a padlock.
     */
    private static void drawBag(Canvas canvas, Ui ui, float x, float y, int ink) {
        FILL.setColor(ink);
        PATH.reset();
        PATH.moveTo(x - ui.dp(3), y - ui.dp(1.4f));
        PATH.lineTo(x + ui.dp(3), y - ui.dp(1.4f));
        PATH.lineTo(x + ui.dp(3.9f), y + ui.dp(4.4f));
        PATH.lineTo(x - ui.dp(3.9f), y + ui.dp(4.4f));
        PATH.close();
        canvas.drawPath(PATH, FILL);
        LINE.setColor(ink);
        LINE.setStrokeWidth(Math.max(1, ui.dp(1.1f)));
        // The handle: a loop wider than it is tall, its ends inside the bag's top.
        RECT.set(x - ui.dp(2.1f), y - ui.dp(3.9f), x + ui.dp(2.1f), y + ui.dp(0.6f));
        canvas.drawArc(RECT, 180, 180, false, LINE);
    }

    /** A person, head and shoulders: the offer was left to the user. */
    private static void drawPerson(Canvas canvas, Ui ui, float x, float y, int ink) {
        FILL.setColor(ink);
        canvas.drawCircle(x, y - ui.dp(2.2f), ui.dp(2), FILL);
        RECT.set(x - ui.dp(3.9f), y + ui.dp(0.9f), x + ui.dp(3.9f), y + ui.dp(8.1f));
        canvas.drawArc(RECT, 180, 180, true, FILL);
    }

    private static int withAlpha(int color, int alpha) {
        int a = (color >>> 24) * Math.max(0, Math.min(255, alpha)) / 255;
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
