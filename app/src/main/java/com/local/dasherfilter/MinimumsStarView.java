package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.text.TextPaint;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * The minimums as constellations in a night-sky porthole: a spoke each for pay, per mile, per minute and per stop,
 * with the minimums you set (solid, round stars) and the adaptive minimums learned from offers you accepted
 * (dashed, sparkles). The corners carry the minimums themselves; distance from the middle is the pay each one asks
 * of one example offer, so every spoke shares a dollar scale and the rings are labeled in dollars. The extra-stop
 * fee is added on top of the other minimums rather than being one, and nothing adaptive matches it, so it has no
 * spoke: nothing is converted from one meaning to another.
 */
@SuppressLint("ViewConstructor")
final class MinimumsStarView extends View {
    private static final String[] NAMES = {"Pay", "Per mile", "Per minute", "Per stop"};
    private static final Glyph.Shape[] ICONS = {
            Glyph.Shape.COIN, Glyph.Shape.ROAD, Glyph.Shape.CLOCK, Glyph.Shape.PIN};
    /** Spokes point to the corners, where their labels sit: top-left, top-right, bottom-right, bottom-left. */
    private static final float[] ANGLES = {-135, -45, 45, 135};
    /** A pay no rule can ask more than, for working out what an example offer needs. */
    private static final int ANY_PAY = Integer.MAX_VALUE;
    private static final int SET_STAR = 0xFFA9CBFF;
    private static final int LEARNED_STAR = 0xFFDCC2FF;

    private final Ui ui;
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path diamond = new Path();
    /** Cents the minimum asks of the example offer; NaN where there is no such minimum. */
    private final double[] set = new double[NAMES.length];
    private final double[] learned = new double[NAMES.length];
    /** Dollars between rings; three rings. */
    private long ringCents;
    private String needs = "";
    private final float[][] stars = new float[28][3];
    private final String[] setText = new String[NAMES.length];
    private final String[] learnedText = new String[NAMES.length];
    private boolean adaptiveOn;

    MinimumsStarView(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        // A fixed scatter of background stars: position as a share of the porthole, and size.
        java.util.Random scatter = new java.util.Random(7);
        for (float[] star : stars) {
            double angle = scatter.nextDouble() * Math.PI * 2;
            double reach = Math.sqrt(scatter.nextDouble()) * 0.95;
            star[0] = (float) (Math.cos(angle) * reach);
            star[1] = (float) (Math.sin(angle) * reach);
            star[2] = 0.6f + scatter.nextFloat() * 1.1f;
        }
    }

    /**
     * @param example the offer whose miles, minutes and stops turn each minimum into pay; all three must be known
     *     (the screen uses the latest fully read offer, or a typical one)
     */
    void show(FilterSettings rules, OfferSnapshot example) {
        double miles = example.miles;
        int minutes = example.minutes;
        int stops = example.stops;
        AcceptedBest best = rules.best;
        boolean last = rules.lastAcceptedCents > 0;
        put(0, rules.flatCents > 0 ? DecisionLog.money(rules.flatCents) : null, rules.flatCents,
                last ? "more than " + DecisionLog.money(rules.lastAcceptedCents) : null,
                last ? rules.lastAcceptedCents + 1L : 0);
        put(1, rules.perMileCents > 0 ? DecisionLog.money(rules.perMileCents) : null,
                OfferRule.mileageCost(rules.perMileCents, miles),
                best.hasPerMile() ? best.perMile() : null, best.hasPerMile() ? best.forMiles(miles) : 0);
        put(2, rules.perMinuteCents > 0 ? DecisionLog.money(rules.perMinuteCents) : null,
                (long) rules.perMinuteCents * minutes,
                best.hasPerMinute() ? best.perMinute() : null, best.hasPerMinute() ? best.forMinutes(minutes) : 0);
        put(3, null, 0, best.hasPerStop() ? best.perStop() : null, best.hasPerStop() ? best.forStops(stops) : 0);
        adaptiveOn = rules.risingOffers;
        needs = needs(rules, example);
        double top = 0;
        for (int i = 0; i < NAMES.length; i++) {
            if (!Double.isNaN(set[i])) top = Math.max(top, set[i]);
            if (!Double.isNaN(learned[i])) top = Math.max(top, learned[i]);
        }
        ringCents = top > 0 ? ringStep(top) : 0;
        setContentDescription(describe());
        requestLayout();
        invalidate();
    }

    private void put(int axis, String setLabel, long setCents, String learnedLabel, long learnedCents) {
        set[axis] = setLabel != null && setCents > 0 ? setCents : Double.NaN;
        setText[axis] = setLabel;
        learned[axis] = learnedLabel != null && learnedCents > 0 && learnedCents < Long.MAX_VALUE
                ? learnedCents : Double.NaN;
        learnedText[axis] = learnedLabel;
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

    /** The sentences under the star: what the example needs, and what distance from the middle means. */
    String caption() {
        if (ringCents <= 0) return needs + " No minimums yet: set one, or accept offers with Adaptive minimum on.";
        return needs + " Farther out asks more; rings are " + DecisionLog.money(ringCents) + " apart.";
    }

    private String describe() {
        List<String> spokes = new ArrayList<>();
        for (int i = 0; i < NAMES.length; i++) {
            spokes.add(NAMES[i] + ": " + (setText[i] == null ? "no set minimum" : "set " + setText[i]) + ", "
                    + (learnedText[i] == null ? "no adaptive minimum yet" : "adaptive " + learnedText[i]));
        }
        return "Minimums, set and adaptive now. " + String.join(". ", spokes) + "."
                + (adaptiveOn ? "" : " Adaptive minimum is off, so the adaptive values are not applied.");
    }

    // ---- Layout: labels in the four corners around the star, or with a large font in a 2 × 2 grid under it. ----

    private float nameLine;
    private float valueLine;

    private void measureLines() {
        text.setFakeBoldText(false);
        text.setTextSize(ui.sp(12));
        nameLine = Ui.lineHeight(text);
        text.setFakeBoldText(true);
        text.setTextSize(ui.sp(14));
        valueLine = Ui.lineHeight(text);
    }

    private float labelHeight() {
        return nameLine + 2 * valueLine + ui.dp(2);
    }

    private float keyHeight() {
        return nameLine + ui.dp(10);
    }

    private float cornerChartHeight() {
        return Math.max(ui.dp(200), 2 * labelHeight() + ui.dp(100));
    }

    /** The star's radius with the labels in the corners: clear of each label's inner corner. */
    private float cornerRadius(float width, float chartHeight) {
        float cx = width / 2;
        float cy = chartHeight / 2;
        float across = cx - labelWidth(width);
        float down = cy - labelHeight();
        float clear = across > 0 && down > 0 ? (float) Math.hypot(across, down) : Math.max(across, down);
        return Math.min(Math.min(cx, cy) - ui.dp(4), clear - ui.dp(10));
    }

    /** True when corner labels would leave the star too small to read, as with a large font. */
    private boolean stacked(float width) {
        return cornerRadius(width, cornerChartHeight()) < ui.dp(64);
    }

    private float stackedStar(float width) {
        return Math.min(width, ui.dp(220));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        measureLines();
        int width = MeasureSpec.getSize(widthSpec);
        float chart = stacked(width) ? stackedStar(width) + ui.dp(8) + 2 * labelHeight() + ui.dp(10)
                : cornerChartHeight();
        setMeasuredDimension(width, resolveSize(Math.round(chart + keyHeight()), heightSpec));
    }

    private float marker() {
        return ui.dp(12);
    }

    /** The widest corner label, capped so the two labels on a side never meet. */
    private float labelWidth(float width) {
        float widest = 0;
        for (int i = 0; i < NAMES.length; i++) {
            text.setFakeBoldText(false);
            text.setTextSize(ui.sp(12));
            widest = Math.max(widest, ui.dp(18) + text.measureText(NAMES[i]));
            text.setFakeBoldText(true);
            text.setTextSize(ui.sp(14));
            widest = Math.max(widest, marker() + text.measureText(value(setText[i])));
            widest = Math.max(widest, marker() + text.measureText(value(learnedText[i])));
        }
        return Math.min(widest, width / 2 - ui.dp(4));
    }

    private static String value(String label) {
        return label == null ? "—" : label.startsWith("more than ") ? ">" + label.substring(10) : label;
    }

    @Override protected void onDraw(Canvas canvas) {
        measureLines();
        float width = getWidth();
        float chartHeight = getHeight() - keyHeight();
        float labelWidth = labelWidth(width);
        boolean stacked = stacked(width);
        float cx = width / 2;
        float cy = stacked ? stackedStar(width) / 2 : chartHeight / 2;
        float radius = stacked ? cy - ui.dp(6) : Math.max(ui.dp(24), cornerRadius(width, chartHeight));

        drawGrid(canvas, cx, cy, radius);
        drawRingLabels(canvas, cx, cy, radius);
        int learnedColor = adaptiveOn ? ui.learned : ui.inkMuted;
        drawShape(canvas, cx, cy, radius, set, SET_STAR, true, false);
        drawShape(canvas, cx, cy, radius, learned, adaptiveOn ? LEARNED_STAR : 0xFF8E8A9C, adaptiveOn, true);
        float firstRow = stacked ? stackedStar(width) + ui.dp(8) : 0;
        float secondRow = stacked ? firstRow + labelHeight() + ui.dp(10) : chartHeight - labelHeight();
        for (int i = 0; i < NAMES.length; i++) {
            drawLabel(canvas, i, width, i == 0 || i == 1 ? firstRow : secondRow, labelWidth, learnedColor);
        }
        drawKey(canvas, width, chartHeight, learnedColor);
    }

    /** The porthole: a night sky with scattered stars, faint rings and spokes. */
    private void drawGrid(Canvas canvas, float cx, float cy, float radius) {
        float window = radius + ui.dp(12);
        fill.setShader(new RadialGradient(cx, cy - window * 0.3f, window * 1.3f,
                ui.dark ? 0xFF1E2A4A : 0xFF263B6B, ui.dark ? 0xFF0B1020 : 0xFF111A33, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, window, fill);
        fill.setShader(null);
        line.setPathEffect(null);
        line.setStrokeWidth(ui.dp(3));
        line.setColor(ui.dark ? 0xFF3A3A38 : 0xFFD9D6CC);
        canvas.drawCircle(cx, cy, window, line);
        for (float[] star : stars) {
            fill.setColor(star[2] > 1.3f ? 0xCCFFFFFF : 0x80FFFFFF);
            canvas.drawCircle(cx + star[0] * window, cy + star[1] * window, ui.dp(star[2]), fill);
        }
        line.setStrokeWidth(Math.max(1, ui.dp(1)));
        line.setColor(0x33FFFFFF);
        for (int ring = 1; ring <= 3; ring++) canvas.drawCircle(cx, cy, radius * ring / 3, line);
        for (int i = 0; i < NAMES.length; i++) {
            boolean empty = Double.isNaN(set[i]) && Double.isNaN(learned[i]);
            line.setColor(empty ? 0x26FFFFFF : 0x4DFFFFFF);
            float[] tip = point(cx, cy, radius, i, 1);
            canvas.drawLine(cx, cy, tip[0], tip[1], line);
        }
    }

    /** Dollar values just inside each ring, in the gap between the two top spokes. */
    private void drawRingLabels(Canvas canvas, float cx, float cy, float radius) {
        if (ringCents <= 0) return;
        text.setFakeBoldText(false);
        text.setTextSize(Math.min(ui.sp(10), ui.dp(13)));
        text.setColor(0xB3FFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        float below = -text.getFontMetrics().ascent + ui.dp(1);
        for (int ring = 2; ring <= 3; ring++) {
            String label = "$" + (ringCents * ring / 100);
            canvas.drawText(label, cx, cy - radius * ring / 3 + below, text);
        }
    }

    /** Where {@code fraction} of the way along spoke {@code axis} is. */
    private static float[] point(float cx, float cy, float radius, int axis, double fraction) {
        double angle = Math.toRadians(ANGLES[axis]);
        return new float[] {(float) (cx + Math.cos(angle) * radius * fraction),
                (float) (cy + Math.sin(angle) * radius * fraction)};
    }

    /** How far out a value sits on the shared dollar scale, the outer ring being three steps. */
    private double fraction(double[] values, int axis) {
        double value = values[axis];
        if (Double.isNaN(value) || ringCents <= 0) return 0;
        return Math.min(1, value / (ringCents * 3.0));
    }

    private void drawShape(Canvas canvas, float cx, float cy, float radius, double[] values, int color,
                           boolean filled, boolean dashed) {
        int known = 0;
        for (double value : values) if (!Double.isNaN(value)) known++;
        if (known == 0) return;
        path.reset();
        for (int i = 0; i < values.length; i++) {
            float[] at = point(cx, cy, radius, i, fraction(values, i));
            if (i == 0) path.moveTo(at[0], at[1]);
            else path.lineTo(at[0], at[1]);
        }
        path.close();
        if (filled) {
            fill.setColor((color & 0x00FFFFFF) | (dashed ? 0x26000000 : 0x38000000));
            canvas.drawPath(path, fill);
        }
        line.setColor(color);
        line.setStrokeWidth(ui.dp(2));
        line.setPathEffect(dashed ? new DashPathEffect(new float[] {ui.dp(5), ui.dp(4)}, 0) : null);
        canvas.drawPath(path, line);
        line.setPathEffect(null);
        for (int i = 0; i < values.length; i++) {
            if (Double.isNaN(values[i])) continue;
            float[] at = point(cx, cy, radius, i, fraction(values, i));
            // Each point glows: a soft halo, then the star itself.
            fill.setColor((color & 0x00FFFFFF) | 0x40000000);
            canvas.drawCircle(at[0], at[1], ui.dp(8), fill);
            fill.setColor(color);
            if (dashed) drawSparkle(canvas, at[0], at[1], ui.dp(6.5f));
            else canvas.drawCircle(at[0], at[1], ui.dp(3.8f), fill);
            if (!dashed) {
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

    /** Name with its icon, then the set value (round star) and the adaptive value (sparkle). */
    private void drawLabel(Canvas canvas, int axis, float width, float top, float labelWidth, int learnedColor) {
        boolean right = axis == 1 || axis == 2;
        float left = right ? width - labelWidth : 0;

        text.setFakeBoldText(false);
        text.setTextSize(ui.sp(12));
        text.setColor(ui.inkSecondary);
        text.setTextAlign(Paint.Align.LEFT);
        float icon = ui.dp(14);
        CharSequence name = Ui.fit(text, NAMES[axis], labelWidth - ui.dp(18), 0.75f);
        float nameWidth = ui.dp(18) + text.measureText(name, 0, name.length());
        float nameLeft = right ? width - nameWidth : left;
        Paint.FontMetrics metrics = text.getFontMetrics();
        float baseline = top - metrics.ascent;
        Glyph.draw(canvas, ICONS[axis], ui.inkSecondary, nameLeft, top + (nameLine - icon) / 2, icon);
        canvas.drawText(name, 0, name.length(), nameLeft + ui.dp(18), baseline, text);

        float y = top + nameLine + ui.dp(2);
        drawValue(canvas, value(setText[axis]), setText[axis] != null ? ui.ink : ui.inkMuted, ui.accent, false,
                right, left, width, labelWidth, y);
        drawValue(canvas, value(learnedText[axis]), learnedText[axis] != null && adaptiveOn ? ui.ink : ui.inkMuted,
                learnedColor, true, right, left, width, labelWidth, y + valueLine);
    }

    private void drawValue(Canvas canvas, String value, int ink, int markerColor, boolean diamond, boolean right,
                           float left, float width, float labelWidth, float top) {
        text.setFakeBoldText(true);
        text.setTextSize(ui.sp(14));
        text.setColor(ink);
        text.setTextAlign(Paint.Align.LEFT);
        CharSequence shown = Ui.fit(text, value, labelWidth - marker(), 0.75f);
        float textWidth = text.measureText(shown, 0, shown.length());
        float start = right ? width - textWidth - marker() : left;
        float middle = top + valueLine / 2;
        fill.setColor(markerColor);
        if (diamond) drawSparkle(canvas, start + ui.dp(4.5f), middle, ui.dp(5.5f));
        else canvas.drawCircle(start + ui.dp(4.5f), middle, ui.dp(3.5f), fill);
        canvas.drawText(shown, 0, shown.length(), start + marker(), top - text.getFontMetrics().ascent, text);
    }

    /** "● Set  ✦ Adaptive now", or "Adaptive (off)" while the adaptive minimum is switched off. */
    private void drawKey(Canvas canvas, float width, float chartHeight, int learnedColor) {
        text.setFakeBoldText(false);
        text.setTextSize(ui.sp(12));
        text.setColor(ui.inkSecondary);
        text.setTextAlign(Paint.Align.LEFT);
        String first = "Set";
        String second = adaptiveOn ? "Adaptive now" : "Adaptive (off)";
        float gap = ui.dp(16);
        float total = 2 * marker() + text.measureText(first) + gap + text.measureText(second);
        float scale = Math.min(1f, (width - ui.dp(4)) / total);
        text.setTextSize(ui.sp(12) * scale);
        total = 2 * marker() + text.measureText(first) + gap + text.measureText(second);
        float x = (width - total) / 2;
        float top = chartHeight + ui.dp(10);
        float middle = top + Ui.lineHeight(text) / 2;
        float baseline = top - text.getFontMetrics().ascent;
        fill.setColor(ui.accent);
        canvas.drawCircle(x + ui.dp(4.5f), middle, ui.dp(3.5f), fill);
        canvas.drawText(first, x + marker(), baseline, text);
        x += marker() + text.measureText(first) + gap;
        fill.setColor(learnedColor);
        drawSparkle(canvas, x + ui.dp(4.5f), middle, ui.dp(5.5f));
        canvas.drawText(second, x + marker(), baseline, text);
    }
}
