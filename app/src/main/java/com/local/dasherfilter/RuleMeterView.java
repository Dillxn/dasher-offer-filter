package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shows what the rules ask of one example offer (the latest one read, or a typical one): a bar per rule sized by
 * the pay it requires, the rule that sets the bar highlighted, extra-stop fees drawn as an add-on, and the total
 * the offer must pay. Updates as the rules are typed, so their effect is visible before saving.
 */
@SuppressLint("ViewConstructor")
final class RuleMeterView extends View {
    /** A pay large enough to pass every money rule, so evaluating reveals the requirement itself. */
    private static final int ANY_PAY = Integer.MAX_VALUE;

    /** A saved rule, a fee added on top of the highest rule, or an adaptive floor that stands on its own. */
    private enum Kind { RULE, ADD_ON, ADAPTIVE }

    private static final class Part {
        final Glyph.Shape icon;
        final String label;
        final long cents;
        final Kind kind;

        Part(Glyph.Shape icon, String label, long cents, Kind kind) {
            this.icon = icon;
            this.label = label;
            this.cents = cents;
            this.kind = kind;
        }
    }

    private final Ui ui;
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final List<Part> parts = new ArrayList<>();
    private OfferSnapshot example = new OfferSnapshot(null, 5.0, 20, 2);
    private String footer = "";
    private String total = "";
    private boolean tooManyStops;

    RuleMeterView(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    /** @param example the offer to illustrate with; only its miles, minutes and stops are used */
    void show(FilterSettings rules, OfferSnapshot example) {
        this.example = example;
        parts.clear();
        Integer minutes = example.minutes;
        Double miles = example.miles;
        Integer stops = example.stops;
        if (rules.flatCents > 0) parts.add(new Part(Glyph.Shape.COIN, "Minimum pay", rules.flatCents, Kind.RULE));
        if (rules.perMileCents > 0 && miles != null) {
            parts.add(new Part(Glyph.Shape.ROAD, DecisionLog.money(rules.perMileCents) + " × " + trim(miles) + " mi",
                    OfferRule.mileageCost(rules.perMileCents, miles), Kind.RULE));
        }
        if (rules.perMinuteCents > 0 && minutes != null) {
            parts.add(new Part(Glyph.Shape.CLOCK, DecisionLog.money(rules.perMinuteCents) + " × " + minutes + " min",
                    (long) rules.perMinuteCents * minutes, Kind.RULE));
        }
        if (rules.risingOffers && rules.lastAcceptedCents > 0) {
            parts.add(new Part(Glyph.Shape.TREND, "Beat last " + DecisionLog.money(rules.lastAcceptedCents),
                    rules.lastAcceptedCents + 1L, Kind.ADAPTIVE));
        }
        AcceptedBest best = rules.best;
        if (rules.risingOffers && best.hasPerMinute() && minutes != null) {
            parts.add(new Part(Glyph.Shape.TREND, "Best " + best.perMinuteLabel(), best.forMinutes(minutes),
                    Kind.ADAPTIVE));
        }
        if (rules.risingOffers && best.hasPerMile() && miles != null) {
            parts.add(new Part(Glyph.Shape.TREND, "Best " + best.perMileLabel(), best.forMiles(miles),
                    Kind.ADAPTIVE));
        }
        if (rules.risingOffers && best.hasPerStop() && stops != null) {
            parts.add(new Part(Glyph.Shape.TREND, "Best " + best.perStopLabel(), best.forStops(stops),
                    Kind.ADAPTIVE));
        }
        int extraStops = stops == null ? 0 : Math.max(0, stops - 2);
        if (rules.extraStopCents > 0 && extraStops > 0) {
            parts.add(new Part(Glyph.Shape.PIN, "+" + DecisionLog.money(rules.extraStopCents) + " × " + extraStops
                    + (extraStops == 1 ? " extra stop" : " extra stops"), (long) rules.extraStopCents * extraStops,
                    Kind.ADD_ON));
        }

        OfferRule.Decision decision = OfferRule.evaluate(new OfferSnapshot(ANY_PAY, miles, minutes, stops), rules);
        tooManyStops = rules.maxStops > 0 && stops != null && stops > rules.maxStops;
        if (tooManyStops || decision.result == OfferRule.Result.DECLINE) {
            total = "declined";
        } else if (decision.requiredCents > 0 && decision.requiredCents < ANY_PAY) {
            total = "needs " + DecisionLog.money(decision.requiredCents);
        } else {
            total = parts.isEmpty() ? "any pay passes" : "";
        }
        footer = rules.maxStops > 0 ? "At most " + rules.maxStops + (rules.maxStops == 1 ? " stop" : " stops")
                + (tooManyStops ? ": this one has " + stops : "") : "";
        setContentDescription("An offer like " + describe() + (total.isEmpty() ? "" : ": " + total)
                + (footer.isEmpty() ? "" : ". " + footer));
        requestLayout();
        invalidate();
    }

    private String describe() {
        List<String> amounts = new ArrayList<>();
        if (example.minutes != null) amounts.add(example.minutes + " min");
        if (example.miles != null) amounts.add(trim(example.miles) + " mi");
        if (example.stops != null) amounts.add(example.stops + (example.stops == 1 ? " stop" : " stops"));
        return String.join(" · ", amounts);
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.US, "%.1f", value);
    }

    /** Line heights at the current font setting; the layout grows with them instead of overlapping. */
    private float smallLine;
    private float bigLine;
    private boolean stacked;

    private void measureLines() {
        text.setFakeBoldText(false);
        text.setTextSize(ui.sp(13));
        smallLine = Ui.lineHeight(text);
        text.setFakeBoldText(true);
        text.setTextSize(ui.sp(19));
        bigLine = Ui.lineHeight(text);
    }

    private float headerHeight() {
        return smallLine + bigLine + ui.dp(10);
    }

    /** With a large font the labels would be cut short, so each row puts its bar under its label instead. */
    private boolean stacked(float width) {
        text.setFakeBoldText(true);
        text.setTextSize(ui.sp(13));
        float labelRoom = Math.min(width * 0.46f, width - ui.dp(106)) - ui.dp(24);
        for (Part part : parts) {
            if (text.measureText(part.label) > labelRoom) return true;
        }
        return false;
    }

    private float rowHeight() {
        return stacked ? smallLine + Math.max(ui.dp(16), smallLine) + ui.dp(12)
                : Math.max(ui.dp(30), smallLine + ui.dp(12));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        measureLines();
        stacked = stacked(MeasureSpec.getSize(widthSpec));
        int rows = Math.max(parts.size(), 1);
        float height = headerHeight() + rows * rowHeight() + (footer.isEmpty() ? 0 : rowHeight());
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), resolveSize(Math.round(height), heightSpec));
    }

    /** Baseline that centers one line of the current text size on {@code middle}. */
    private float centered(float middle) {
        Paint.FontMetrics metrics = text.getFontMetrics();
        return middle - (metrics.ascent + metrics.descent) / 2;
    }

    @Override protected void onDraw(Canvas canvas) {
        measureLines();
        float width = getWidth();
        stacked = stacked(width);
        // Header: the example offer, and what it must pay.
        text.setFakeBoldText(false);
        text.setTextSize(ui.sp(13));
        text.setColor(ui.inkSecondary);
        text.setTextAlign(Paint.Align.LEFT);
        CharSequence header = Ui.fit(text, "An offer like " + describe(), width, 0.8f);
        canvas.drawText(header, 0, header.length(), 0, -text.getFontMetrics().ascent, text);
        text.setFakeBoldText(true);
        text.setTextSize(ui.sp(19));
        text.setColor(tooManyStops ? Ui.CRITICAL : ui.ink);
        CharSequence needs = Ui.fit(text, total, width, 0.8f);
        canvas.drawText(needs, 0, needs.length(), 0, smallLine + ui.dp(4) - text.getFontMetrics().ascent, text);

        float top = headerHeight();
        float row = rowHeight();
        text.setFakeBoldText(false);
        text.setTextSize(ui.sp(13));
        if (parts.isEmpty()) {
            text.setColor(ui.inkMuted);
            canvas.drawText("No pay rules set.", 0, centered(top + row / 2), text);
        }
        // As in OfferRule: fees add to the highest saved rule, and that total competes with each adaptive floor.
        long largest = 1;
        long highestRule = 0;
        long fees = 0;
        long highestAdaptive = 0;
        float widestValue = 0;
        for (Part part : parts) {
            largest = Math.max(largest, part.cents);
            if (part.kind == Kind.RULE) highestRule = Math.max(highestRule, part.cents);
            if (part.kind == Kind.ADD_ON) fees += part.cents;
            if (part.kind == Kind.ADAPTIVE) highestAdaptive = Math.max(highestAdaptive, part.cents);
            text.setFakeBoldText(true);
            widestValue = Math.max(widestValue, text.measureText(value(part)));
        }
        boolean rulesWin = highestRule + fees >= highestAdaptive;
        float valueWidth = Math.max(ui.dp(58), widestValue + ui.dp(8));
        float labelWidth = stacked ? width
                : Math.max(ui.dp(60), Math.min(width * 0.46f, width - valueWidth - ui.dp(48)));
        float barLeft = stacked ? ui.dp(24) : labelWidth + ui.dp(8);
        float barRight = Math.max(barLeft + ui.dp(8), width - valueWidth);
        boolean highlighted = false;
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            // Side by side: label, bar and amount share one line. Stacked: the label, then bar and amount below.
            float labelMiddle = stacked ? top + row * i + ui.dp(4) + smallLine / 2 : top + row * i + row / 2;
            float middle = stacked ? labelMiddle + smallLine / 2 + ui.dp(4) + Math.max(ui.dp(16), smallLine) / 2
                    : labelMiddle;
            boolean sets = !highlighted && (rulesWin ? part.kind == Kind.RULE && part.cents == highestRule
                    : part.kind == Kind.ADAPTIVE && part.cents == highestAdaptive);
            if (sets) highlighted = true;
            boolean feeApplies = part.kind == Kind.ADD_ON && rulesWin;
            int ink = sets || feeApplies ? ui.ink : ui.inkSecondary;
            Glyph.draw(canvas, part.icon, sets ? ui.accent : ui.inkSecondary, 0, labelMiddle - ui.dp(9), ui.dp(18));

            text.setFakeBoldText(sets);
            text.setTextSize(ui.sp(13));
            text.setColor(ink);
            text.setTextAlign(Paint.Align.LEFT);
            CharSequence label = TextUtils.ellipsize(part.label, text, labelWidth - ui.dp(24),
                    TextUtils.TruncateAt.END);
            canvas.drawText(label, 0, label.length(), ui.dp(24), centered(labelMiddle), text);

            rect.set(barLeft, middle - ui.dp(5), barRight, middle + ui.dp(5));
            fill.setColor(ui.gridline);
            canvas.drawRoundRect(rect, ui.dp(5), ui.dp(5), fill);
            float length = (barRight - barLeft) * Math.min(1f, part.cents / (float) largest);
            rect.set(barLeft, middle - ui.dp(5), barLeft + Math.max(ui.dp(4), length), middle + ui.dp(5));
            fill.setColor(sets ? ui.accent : feeApplies ? (ui.accent & 0x00FFFFFF) | 0x80000000 : ui.baseline);
            canvas.drawRoundRect(rect, ui.dp(5), ui.dp(5), fill);

            text.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(value(part), width, centered(middle), text);
        }
        if (!footer.isEmpty()) {
            float middle = top + row * Math.max(parts.size(), 1) + row / 2;
            Glyph.draw(canvas, Glyph.Shape.STOPS, tooManyStops ? Ui.CRITICAL : ui.inkSecondary, 0,
                    middle - ui.dp(9), ui.dp(18));
            text.setFakeBoldText(false);
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(tooManyStops ? Ui.CRITICAL : ui.inkSecondary);
            CharSequence stops = Ui.fit(text, footer, width - ui.dp(24), 0.8f);
            canvas.drawText(stops, 0, stops.length(), ui.dp(24), centered(middle), text);
        }
    }

    private static String value(Part part) {
        return part.cents >= ANY_PAY ? "—" : (part.kind == Kind.ADD_ON ? "+" : "") + DecisionLog.money(part.cents);
    }
}
