package com.local.dasherfilter;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * What became of an offer as a small round badge: the outcome's colour with ✓, ✕ or ?, a shopping bag for an accepted
 * offer (amber while its automatic Accept is only requested, 0.6.3, so it no longer reads as one to review) and a
 * person for one left to the user. The skyline's flags, the mascot's offers and the verdict tile draw the same badge.
 * Drawing happens on the main thread only, so the paints are shared.
 */
final class OutcomeBadge {
    /** The badge's symbol, whatever its colour. */
    enum Symbol {
        /** ✓: the rules let it through. */
        CHECK,
        /** ✕: declined. */
        CROSS,
        /** ?: left for the user's review. */
        QUESTION,
        /** A shopping bag (the order picked up): accepted, or its automatic Accept requested. */
        BAG,
        /** A person, head and shoulders: left to the user. */
        PERSON
    }

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

    /**
     * The symbol {@code outcome} is drawn with: an accepted offer's bag, and the same bag for an automatic Accept only
     * requested (its amber says it is not confirmed; the question mark is the review's alone).
     */
    static Symbol symbol(DecisionLog.Outcome outcome) {
        switch (outcome) {
            case PASSED:
                return Symbol.CHECK;
            case DECLINED:
                return Symbol.CROSS;
            case ACCEPTED:
            case REQUESTED:
                return Symbol.BAG;
            case YOURS:
                return Symbol.PERSON;
            default:
                return Symbol.QUESTION;
        }
    }

    /** The badge centred at ({@code x}, {@code y}), ringed in the page's surface, at {@code alpha} (0–255). */
    static void draw(Canvas canvas, Ui ui, float x, float y, DecisionLog.Outcome outcome, int alpha) {
        draw(canvas, ui, x, y, Ui.outcomeColor(outcome), symbol(outcome), alpha);
    }

    /**
     * A badge of {@code color} with {@code symbol} centred at ({@code x}, {@code y}), ringed in the page's surface, at
     * {@code alpha} (0–255): for an outcome its words qualify (an amber ✓ for an offer passed below the minimums by
     * Autopilot's bar). Its symbol takes the ink that stays legible on that colour ({@link Ui#onStatus}).
     */
    static void draw(Canvas canvas, Ui ui, float x, float y, int color, Symbol symbol, int alpha) {
        float radius = ui.dp(7);
        FILL.setColor(withAlpha(ui.surface, alpha));
        canvas.drawCircle(x, y, radius + ui.dp(2), FILL);
        FILL.setColor(withAlpha(color, alpha));
        canvas.drawCircle(x, y, radius, FILL);
        drawSymbol(canvas, ui, x, y, symbol, withAlpha(Ui.onStatus(color), alpha));
    }

    /** A quiet roof mark for history; the selected offer retains the full coloured badge. */
    static void drawQuiet(Canvas canvas, Ui ui, float x, float y, DecisionLog.Outcome outcome) {
        drawSymbol(canvas, ui, x, y, symbol(outcome), ui.inkSecondary);
    }

    /**
     * A small dot of {@code color} centred at ({@code x}, {@code y}), 3.5 dp across its radius and ringed 1 dp in the
     * page's surface, so it stands apart from the badge it sits on (Autopilot's purple at a badge's upper right: passed
     * by its bar below the minimums).
     */
    static void drawPip(Canvas canvas, Ui ui, float x, float y, int color) {
        float radius = ui.dp(3.5f);
        FILL.setColor(ui.surface);
        canvas.drawCircle(x, y, radius + ui.dp(1), FILL);
        FILL.setColor(color);
        canvas.drawCircle(x, y, radius, FILL);
    }

    /**
     * The road ({@link Glyph.Shape#ROAD}'s two edges and dashed middle line) {@code size} pixels square, centred at
     * ({@code x}, {@code y}) in {@code ink} on a disc of the page's surface a little wider than it, so it reads over a
     * badge's edge or a roof: the offer the stored route is (a badge's lower right, a building's roof). Drawn by hand
     * with the shared paints: nothing is made while drawing.
     */
    static void drawRoadPip(Canvas canvas, Ui ui, float x, float y, float size, int ink) {
        FILL.setColor(ui.surface);
        canvas.drawCircle(x, y, size / 2f + ui.dp(1), FILL);
        float unit = size / 24f;
        float left = x - size / 2f;
        float top = y - size / 2f;
        LINE.setColor(ink);
        LINE.setStrokeWidth(Math.max(1, 2 * unit));
        canvas.drawLine(left + 5 * unit, top + 21 * unit, left + 9 * unit, top + 3 * unit, LINE);
        canvas.drawLine(left + 19 * unit, top + 21 * unit, left + 15 * unit, top + 3 * unit, LINE);
        canvas.drawLine(left + 12 * unit, top + 4.5f * unit, left + 12 * unit, top + 7 * unit, LINE);
        canvas.drawLine(left + 12 * unit, top + 10.5f * unit, left + 12 * unit, top + 13.5f * unit, LINE);
        canvas.drawLine(left + 12 * unit, top + 17 * unit, left + 12 * unit, top + 20 * unit, LINE);
    }

    private static void drawSymbol(Canvas canvas, Ui ui, float x, float y, Symbol symbol, int ink) {
        switch (symbol) {
            case BAG:
                drawBag(canvas, ui, x, y, ink);
                break;
            case PERSON:
                drawPerson(canvas, ui, x, y, ink);
                break;
            default:
                // The symbol belongs to its circle, so it follows the font setting only as far as the circle allows.
                SYMBOL.setTextSize(Math.min(ui.sp(10), ui.dp(11)));
                SYMBOL.setColor(ink);
                canvas.drawText(symbol == Symbol.CHECK ? "✓" : symbol == Symbol.CROSS ? "✕" : "?", x,
                        y + SYMBOL.getTextSize() / 3f, SYMBOL);
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
