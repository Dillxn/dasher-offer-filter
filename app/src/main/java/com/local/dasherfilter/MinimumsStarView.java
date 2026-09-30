package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * The minimums as constellations in the page's sky: a spoke each for pay, per mile, per minute and per stop,
 * with the minimums you set (solid, round stars) and the adaptive minimums learned from offers you accepted or
 * declined by hand (dashed, sparkles). Distance from the middle is the pay each one asks of one example offer, so
 * every spoke shares a dollar scale. Recent offers are small marks on each spoke at what their own pay, per mile,
 * per minute and per stop would pay for the example (● passed, ✕ declined, ○ review): a mark outside a minimum beat
 * it. The extra-stop fee is added on top of the other minimums rather than being one, and nothing adaptive matches
 * it, so it has no spoke: nothing is converted from one meaning to another. The shapes glide to new values and the
 * adaptive sparkles breathe; with Android's animations off they rest. By day the stars are drawn in ink on the
 * morning sky, by night they shine.
 */
@SuppressLint("ViewConstructor")
final class MinimumsStarView extends View {
    private static final String[] NAMES = {"Pay", "Per mile", "Per minute", "Per stop"};
    /** Spokes point to the corners, where their names sit: top-left, top-right, bottom-right, bottom-left. */
    private static final float[] ANGLES = {-135, -45, 45, 135};
    /** A pay no rule can ask more than, for working out what an example offer needs. */
    private static final int ANY_PAY = Integer.MAX_VALUE;
    /** Night-sky colors; by day the page's own accent, learned and outcome colors read on the pale sky. */
    private static final int NIGHT_SET = 0xFFA9CBFF;
    private static final int NIGHT_LEARNED = 0xFFDCC2FF;
    private static final int NIGHT_PASSED = 0xFF8BE08B;
    private static final int NIGHT_DECLINED = 0xFFFF8F87;
    private static final int NIGHT_REVIEW = 0xFFFFD27A;
    /** At most this many recent offers are marked, as on the skyline. */
    private static final int MARKS = DecisionChartView.SLOTS;
    /** Offers far above every minimum stretch the scale only this far, so the minimums stay readable. */
    private static final double OFFER_STRETCH = 1.35;
    private static final long GLIDE_MS = 700;

    private final Ui ui;
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path diamond = new Path();
    private final DashPathEffect dash;
    /** Cents the minimum asks of the example offer; NaN where there is no such minimum. */
    private final double[] set = new double[NAMES.length];
    private final double[] learned = new double[NAMES.length];
    /** Where each point is heading and where it set out from, as fractions of the outer ring. */
    private final float[] setTo = new float[NAMES.length];
    private final float[] setFrom = new float[NAMES.length];
    private final float[] learnedTo = new float[NAMES.length];
    private final float[] learnedFrom = new float[NAMES.length];
    private long glideStart;
    /** Recent offers, newest first: result, then cents on each spoke (NaN where the offer did not say). */
    private final List<OfferRule.Result> markResults = new ArrayList<>();
    private final List<double[]> marks = new ArrayList<>();
    /** Dollars between rings; three rings. */
    private long ringCents;
    private String needs = "";
    private final String[] setText = new String[NAMES.length];
    private final String[] learnedText = new String[NAMES.length];
    private boolean adaptiveOn;

    MinimumsStarView(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        dash = new DashPathEffect(new float[] {ui.dp(5), ui.dp(4)}, 0);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    private int setColor() {
        return ui.dark ? NIGHT_SET : ui.accent;
    }

    private int learnedColor() {
        return adaptiveOn ? (ui.dark ? NIGHT_LEARNED : ui.learned) : 0xFF8E8A9C;
    }

    private int markColor(OfferRule.Result result) {
        if (!ui.dark) return Ui.resultColor(result);
        return result == OfferRule.Result.KEEP ? NIGHT_PASSED
                : result == OfferRule.Result.DECLINE ? NIGHT_DECLINED : NIGHT_REVIEW;
    }

    /**
     * @param example the offer whose miles, minutes and stops turn each minimum into pay; all three must be known
     *     (the screen uses the latest fully read offer, or a typical one)
     * @param recent recent decisions, newest first; standalone offers with pay are marked
     */
    void show(FilterSettings rules, OfferSnapshot example, List<DecisionLog.Entry> recent) {
        double miles = example.miles;
        int minutes = example.minutes;
        int stops = example.stops;
        AcceptedBest best = rules.best;
        DeclinedFloor declined = rules.declined;
        put(0, rules.flatCents > 0 ? DecisionLog.money(rules.flatCents) : null, rules.flatCents,
                rules.lastAcceptedCents > 0 ? rules.lastAcceptedCents + 1L : 0,
                "more than " + DecisionLog.money(rules.lastAcceptedCents),
                declined.payCents > 0 ? declined.beatPay() : 0, "more than " + DecisionLog.money(declined.payCents));
        put(1, rules.perMileCents > 0 ? DecisionLog.money(rules.perMileCents) : null,
                OfferRule.mileageCost(rules.perMileCents, miles),
                best.hasPerMile() ? best.forMiles(miles) : 0, best.hasPerMile() ? best.perMile() : null,
                declined.rates.hasPerMile() ? declined.beatMiles(miles) : 0,
                declined.rates.hasPerMile() ? "more than " + declined.rates.perMile() : null);
        put(2, rules.perMinuteCents > 0 ? DecisionLog.money(rules.perMinuteCents) : null,
                (long) rules.perMinuteCents * minutes,
                best.hasPerMinute() ? best.forMinutes(minutes) : 0, best.hasPerMinute() ? best.perMinute() : null,
                declined.rates.hasPerMinute() ? declined.beatMinutes(minutes) : 0,
                declined.rates.hasPerMinute() ? "more than " + declined.rates.perMinute() : null);
        put(3, null, 0,
                best.hasPerStop() ? best.forStops(stops) : 0, best.hasPerStop() ? best.perStop() : null,
                declined.rates.hasPerStop() ? declined.beatStops(stops) : 0,
                declined.rates.hasPerStop() ? "more than " + declined.rates.perStop() : null);
        adaptiveOn = rules.risingOffers;
        needs = needs(rules, example);
        markOffers(recent, example);

        double top = 0;
        for (int i = 0; i < NAMES.length; i++) {
            if (!Double.isNaN(set[i])) top = Math.max(top, set[i]);
            if (!Double.isNaN(learned[i])) top = Math.max(top, learned[i]);
        }
        double offers = 0;
        for (double[] mark : marks) {
            for (double cents : mark) if (!Double.isNaN(cents)) offers = Math.max(offers, cents);
        }
        top = top > 0 ? Math.max(top, Math.min(offers, top * OFFER_STRETCH)) : offers;
        ringCents = top > 0 ? ringStep(top) : 0;
        glideTo();
        setContentDescription(describe());
        invalidate();
    }

    /** A spoke's set minimum and its adaptive one: the higher of what accepted and declined offers taught. */
    private void put(int axis, String setLabel, long setCents, long acceptedCents, String acceptedLabel,
                     long declinedCents, String declinedLabel) {
        set[axis] = setLabel != null && setCents > 0 ? setCents : Double.NaN;
        setText[axis] = setLabel;
        boolean fromDecline = declinedCents > acceptedCents;
        long cents = fromDecline ? declinedCents : acceptedCents;
        String label = fromDecline ? declinedLabel : acceptedLabel;
        learned[axis] = label != null && cents > 0 && cents < Long.MAX_VALUE ? cents : Double.NaN;
        learnedText[axis] = Double.isNaN(learned[axis]) ? null : label;
    }

    /** Each recent standalone offer with pay, at what its own rates would pay for the example offer. */
    private void markOffers(List<DecisionLog.Entry> recent, OfferSnapshot example) {
        markResults.clear();
        marks.clear();
        if (recent == null) return;
        for (DecisionLog.Entry entry : recent) {
            if (marks.size() >= MARKS) break;
            OfferSnapshot offer = entry.facts;
            if (entry.addOn || offer.payCents == null || offer.payCents <= 0) continue;
            double pay = offer.payCents;
            marks.add(new double[] {
                    pay,
                    offer.miles != null && offer.miles > 0 ? pay * example.miles / offer.miles : Double.NaN,
                    offer.minutes != null && offer.minutes > 0 ? pay * example.minutes / offer.minutes : Double.NaN,
                    offer.stops != null && offer.stops > 0 ? pay * example.stops / offer.stops : Double.NaN});
            markResults.add(entry.result);
        }
    }

    /** Starts the points gliding from where they are now to the new values, if any moved. */
    private void glideTo() {
        float progress = Motion.settle(glideStart, GLIDE_MS);
        boolean moved = glideStart == 0;
        for (int i = 0; i < NAMES.length; i++) {
            float nextSet = fraction(set[i]);
            float nextLearned = fraction(learned[i]);
            moved |= nextSet != setTo[i] || nextLearned != learnedTo[i];
        }
        if (!moved) return;
        for (int i = 0; i < NAMES.length; i++) {
            setFrom[i] = setFrom[i] + (setTo[i] - setFrom[i]) * progress;
            learnedFrom[i] = learnedFrom[i] + (learnedTo[i] - learnedFrom[i]) * progress;
            setTo[i] = fraction(set[i]);
            learnedTo[i] = fraction(learned[i]);
        }
        glideStart = SystemClock.uptimeMillis();
    }

    /**
     * A round step in whole dollars so that three rings just reach past {@code topCents}: any whole dollar up to
     * $10, then multiples of $5.
     */
    static long ringStep(double topCents) {
        long dollars = (long) Math.ceil(topCents * 1.05 / 3 / 100);
        if (dollars > 10) dollars = (dollars + 4) / 5 * 5;
        return Math.max(1, dollars) * 100;
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(java.util.Locale.US, "%.1f",
                value);
    }

    /**
     * "An offer like 24 min · 6 mi · 2 stops needs $18.75.", or that it is declined for its stops, or that any pay
     * passes: the rules as given, applied to the example offer.
     */
    static String needs(FilterSettings rules, OfferSnapshot example) {
        String like = "An offer like " + example.minutes + " min · " + trim(example.miles) + " mi · " + example.stops
                + (example.stops == 1 ? " stop" : " stops");
        if (rules.maxStops > 0 && example.stops > rules.maxStops) {
            return like + " is declined: at most " + rules.maxStops + (rules.maxStops == 1 ? " stop." : " stops.");
        }
        OfferRule.Decision decision = OfferRule.evaluate(
                new OfferSnapshot(ANY_PAY, example.miles, example.minutes, example.stops), rules);
        if (decision.result == OfferRule.Result.DECLINE) return like + " is declined by your rules.";
        if (decision.requiredCents > 0 && decision.requiredCents < ANY_PAY) {
            return like + " needs " + DecisionLog.money(decision.requiredCents) + ".";
        }
        return like + " passes at any pay.";
    }

    /** The line under the star: what the example offer needs. */
    String caption() {
        boolean anyMinimum = false;
        for (int i = 0; i < NAMES.length; i++) anyMinimum |= !Double.isNaN(set[i]) || !Double.isNaN(learned[i]);
        return anyMinimum ? needs : needs + " No minimums yet.";
    }

    private String describe() {
        List<String> spokes = new ArrayList<>();
        for (int i = 0; i < NAMES.length; i++) {
            spokes.add(NAMES[i] + ": " + (setText[i] == null ? "no set minimum" : "set " + setText[i]) + ", "
                    + (learnedText[i] == null ? "no adaptive minimum yet" : "adaptive " + learnedText[i]));
        }
        String described = "Minimums, set and adaptive now. " + String.join(". ", spokes) + "."
                + (adaptiveOn ? "" : " Adaptive minimum is off, so the adaptive values are not applied.");
        if (marks.isEmpty()) return described;
        int passed = 0;
        int declined = 0;
        for (OfferRule.Result result : markResults) {
            if (result == OfferRule.Result.KEEP) passed++;
            else if (result == OfferRule.Result.DECLINE) declined++;
        }
        return described + " Marked: your last " + marks.size() + (marks.size() == 1 ? " offer, " : " offers, ")
                + passed + " passed, " + declined + " declined, " + (marks.size() - passed - declined)
                + " to review.";
    }

    // ---- Layout: the constellation's circle, a spoke name at each corner, and a small key under it. ----

    private float nameLine;

    private void measureName() {
        text.setFakeBoldText(false);
        text.setTextSize(Math.min(ui.sp(12), ui.dp(17)));
        nameLine = Ui.lineHeight(text);
    }

    private float keyHeight() {
        return nameLine + ui.dp(12);
    }

    private float nameWidth() {
        float widest = 0;
        for (String name : NAMES) widest = Math.max(widest, text.measureText(name));
        return widest;
    }

    /** The circle's radius: as large as fits with the names outside it at the corners, up to 130 dp. */
    private float windowRadius(float width) {
        float room = (width / 2 - nameWidth() - ui.dp(2)) / 0.7071f - ui.dp(6);
        return Math.max(ui.dp(76), Math.min(ui.dp(130), room));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        measureName();
        int width = MeasureSpec.getSize(widthSpec);
        float window = windowRadius(width);
        setMeasuredDimension(width, resolveSize(Math.round(2 * window + ui.dp(8) + keyHeight()), heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        measureName();
        float width = getWidth();
        float window = windowRadius(width);
        float cx = width / 2;
        float cy = window + ui.dp(4);
        float radius = window - ui.dp(12);

        drawGrid(canvas, cx, cy, radius);
        drawMarks(canvas, cx, cy, radius);
        float glide = Motion.settle(glideStart, GLIDE_MS);
        drawShape(canvas, cx, cy, radius, set, setFrom, setTo, glide, setColor(), true, false);
        drawShape(canvas, cx, cy, radius, learned, learnedFrom, learnedTo, glide, learnedColor(), adaptiveOn, true);
        for (int i = 0; i < NAMES.length; i++) drawName(canvas, i, cx, cy, window, width);
        drawKey(canvas, width, cy + window + ui.dp(4));
        Motion.next(this);
    }

    /** Faint rings and spokes, with the rings' dollars in the gap between the two top spokes. */
    private void drawGrid(Canvas canvas, float cx, float cy, float radius) {
        line.setStrokeWidth(Math.max(1, ui.dp(1)));
        line.setColor(ui.dark ? 0x2EFFFFFF : 0x260B2A55);
        for (int ring = 1; ring <= 3; ring++) canvas.drawCircle(cx, cy, radius * ring / 3, line);
        for (int i = 0; i < NAMES.length; i++) {
            float[] tip = point(cx, cy, radius, i, 1);
            canvas.drawLine(cx, cy, tip[0], tip[1], line);
        }
        if (ringCents <= 0) return;
        text.setFakeBoldText(false);
        text.setTextSize(Math.min(ui.sp(10), ui.dp(13)));
        text.setColor(ui.dark ? 0x80FFFFFF : 0xB0214066);
        text.setTextAlign(Paint.Align.CENTER);
        float below = -text.getFontMetrics().ascent + ui.dp(1);
        for (int ring = 2; ring <= 3; ring++) {
            canvas.drawText("$" + (ringCents * ring / 100), cx, cy - radius * ring / 3 + below, text);
        }
    }

    /** Where {@code fraction} of the way along spoke {@code axis} is, turned by {@code turn} degrees. */
    private static float[] point(float cx, float cy, float radius, int axis, double fraction, float turn) {
        double angle = Math.toRadians(ANGLES[axis] + turn);
        return new float[] {(float) (cx + Math.cos(angle) * radius * fraction),
                (float) (cy + Math.sin(angle) * radius * fraction)};
    }

    private static float[] point(float cx, float cy, float radius, int axis, double fraction) {
        return point(cx, cy, radius, axis, fraction, 0);
    }

    /** How far out a value sits on the shared dollar scale, the outer ring being three steps. */
    private float fraction(double cents) {
        if (Double.isNaN(cents) || ringCents <= 0) return 0;
        return (float) Math.min(1, cents / (ringCents * 3.0));
    }

    /** Each recent offer on each spoke it can be placed on; older ones fainter, the newest with a soft pulse. */
    private void drawMarks(Canvas canvas, float cx, float cy, float radius) {
        for (int m = marks.size() - 1; m >= 0; m--) {
            double[] mark = marks.get(m);
            OfferRule.Result result = markResults.get(m);
            int color = markColor(result);
            int alpha = m == 0 ? 0xFF : Math.max(0x60, 0xE0 - m * 0x0C);
            // Offers side by side on a spoke fan out a little so they do not hide one another.
            float turn = ((m % 5) - 2) * 2.2f;
            for (int axis = 0; axis < NAMES.length; axis++) {
                if (Double.isNaN(mark[axis]) || ringCents <= 0) continue;
                float out = (float) Math.min(1.04, mark[axis] / (ringCents * 3.0));
                float[] at = point(cx, cy, radius, axis, out, turn);
                if (m == 0) {
                    float pulse = Motion.on() ? Motion.loop(2.4f, 0) : 1;
                    line.setPathEffect(null);
                    line.setStrokeWidth(Math.max(1, ui.dp(1)));
                    line.setColor((color & 0x00FFFFFF) | ((int) (0x90 * (1 - pulse)) << 24));
                    canvas.drawCircle(at[0], at[1], ui.dp(4) + ui.dp(7) * pulse, line);
                }
                drawMark(canvas, at[0], at[1], result, (color & 0x00FFFFFF) | (alpha << 24));
            }
        }
    }

    /** ● passed, ✕ declined, ○ review: the shape carries the outcome, not only the color. */
    private void drawMark(Canvas canvas, float x, float y, OfferRule.Result result, int color) {
        float size = ui.dp(3.2f);
        if (result == OfferRule.Result.KEEP) {
            fill.setColor(color);
            canvas.drawCircle(x, y, size, fill);
            return;
        }
        line.setPathEffect(null);
        line.setColor(color);
        line.setStrokeWidth(ui.dp(1.6f));
        if (result == OfferRule.Result.DECLINE) {
            canvas.drawLine(x - size, y - size, x + size, y + size, line);
            canvas.drawLine(x - size, y + size, x + size, y - size, line);
        } else {
            canvas.drawCircle(x, y, size, line);
        }
    }

    private void drawShape(Canvas canvas, float cx, float cy, float radius, double[] values, float[] from, float[] to,
                           float glide, int color, boolean filled, boolean dashed) {
        int known = 0;
        for (double value : values) if (!Double.isNaN(value)) known++;
        if (known == 0) return;
        float[][] points = new float[values.length][];
        for (int i = 0; i < values.length; i++) {
            float shown = Double.isNaN(values[i]) ? 0 : from[i] + (to[i] - from[i]) * glide;
            points[i] = point(cx, cy, radius, i, shown);
        }
        path.reset();
        for (int i = 0; i < values.length; i++) {
            if (i == 0) path.moveTo(points[i][0], points[i][1]);
            else path.lineTo(points[i][0], points[i][1]);
        }
        path.close();
        if (filled) {
            fill.setColor((color & 0x00FFFFFF) | (dashed ? 0x22000000 : 0x30000000));
            canvas.drawPath(path, fill);
        }
        line.setColor(color);
        line.setStrokeWidth(ui.dp(2));
        line.setPathEffect(dashed ? dash : null);
        canvas.drawPath(path, line);
        line.setPathEffect(null);
        // The adaptive sparkles breathe slowly.
        float breathe = dashed ? 1 + 0.14f * Motion.wave(3.2f, 0) : 1;
        for (int i = 0; i < values.length; i++) {
            if (Double.isNaN(values[i])) continue;
            float[] at = points[i];
            fill.setColor((color & 0x00FFFFFF) | 0x3A000000);
            canvas.drawCircle(at[0], at[1], ui.dp(8) * breathe, fill);
            fill.setColor(color);
            if (dashed) {
                drawSparkle(canvas, at[0], at[1], ui.dp(6.5f) * breathe);
            } else {
                canvas.drawCircle(at[0], at[1], ui.dp(3.8f), fill);
                fill.setColor(0xFFFFFFFF);
                canvas.drawCircle(at[0], at[1], ui.dp(1.6f), fill);
            }
        }
    }

    /** A four-pointed sparkle, the adaptive minimums' mark. */
    private void drawSparkle(Canvas canvas, float x, float y, float half) {
        float waist = half * 0.22f;
        diamond.reset();
        diamond.moveTo(x, y - half);
        diamond.quadTo(x + waist, y - waist, x + half, y);
        diamond.quadTo(x + waist, y + waist, x, y + half);
        diamond.quadTo(x - waist, y + waist, x - half, y);
        diamond.quadTo(x - waist, y - waist, x, y - half);
        diamond.close();
        canvas.drawPath(diamond, fill);
    }

    /** A spoke's name just outside the circle at its corner. */
    private void drawName(Canvas canvas, int axis, float cx, float cy, float window, float width) {
        boolean right = axis == 1 || axis == 2;
        boolean below = axis == 2 || axis == 3;
        float[] corner = point(cx, cy, window + ui.dp(6), axis, 1);
        text.setFakeBoldText(false);
        text.setTextSize(Math.min(ui.sp(12), ui.dp(17)));
        text.setColor(ui.inkSecondary);
        text.setTextAlign(right ? Paint.Align.LEFT : Paint.Align.RIGHT);
        float room = right ? width - corner[0] : corner[0];
        CharSequence name = Ui.fit(text, NAMES[axis], room, 0.7f);
        Paint.FontMetrics metrics = text.getFontMetrics();
        float baseline = below ? corner[1] - metrics.ascent : corner[1] - metrics.descent;
        baseline = Math.max(-metrics.ascent, baseline);
        canvas.drawText(name, 0, name.length(), corner[0], baseline, text);
    }

    /** "● set  ✦ adaptive  ● ✕ ○ offers", or "adaptive (off)" while the adaptive minimum is switched off. */
    private void drawKey(Canvas canvas, float width, float top) {
        text.setFakeBoldText(false);
        text.setTextSize(Math.min(ui.sp(11), ui.dp(16)));
        text.setColor(ui.inkMuted);
        text.setTextAlign(Paint.Align.LEFT);
        String first = "set";
        String second = adaptiveOn ? "adaptive" : "adaptive (off)";
        String third = marks.isEmpty() ? "" : "offers";
        float marker = ui.dp(12);
        float gap = ui.dp(14);
        float offerMarks = third.isEmpty() ? 0 : 3 * marker;
        float total = 2 * marker + text.measureText(first) + gap + text.measureText(second)
                + (third.isEmpty() ? 0 : gap + offerMarks + text.measureText(third));
        float x = Math.max(0, (width - total) / 2);
        float middle = top + ui.dp(6) + Ui.lineHeight(text) / 2;
        float baseline = top + ui.dp(6) - text.getFontMetrics().ascent;
        fill.setColor(ui.accent);
        canvas.drawCircle(x + ui.dp(4), middle, ui.dp(3), fill);
        canvas.drawText(first, x + marker, baseline, text);
        x += marker + text.measureText(first) + gap;
        fill.setColor(adaptiveOn ? ui.learned : ui.inkMuted);
        drawSparkle(canvas, x + ui.dp(4), middle, ui.dp(5));
        canvas.drawText(second, x + marker, baseline, text);
        if (third.isEmpty()) return;
        x += marker + text.measureText(second) + gap;
        OfferRule.Result[] results = {OfferRule.Result.KEEP, OfferRule.Result.DECLINE, OfferRule.Result.REVIEW};
        for (OfferRule.Result result : results) {
            drawMark(canvas, x + ui.dp(4), middle, result, Ui.resultColor(result));
            x += marker;
        }
        canvas.drawText(third, x, baseline, text);
    }
}
