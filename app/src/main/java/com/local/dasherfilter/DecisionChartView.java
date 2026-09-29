package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.format.DateFormat;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Recent offers as columns, oldest left: bar height is the pay read, the ink tick is the pay the rules required,
 * and the badge above says what happened (✓ passed, ✕ declined, ? review). A bar that ends below its tick is a
 * decline by the rules as written, so a surprising decline shows either a misread pay or a rule to adjust.
 * Tapping a column selects it and reports it to the listener.
 */
@SuppressLint("ViewConstructor")
final class DecisionChartView extends View {
    interface OnSelect {
        void selected(DecisionLog.Entry entry);
    }

    static final int SLOTS = 14;
    /** Values above $1,000 (for example a saturated per-minute requirement) are drawn at the top of the scale. */
    private static final long SCALE_CAP_CENTS = 100_000;
    private static final long[] NICE_DOLLARS = {10, 15, 20, 25, 30, 40, 50, 60, 80, 100, 150, 200, 300, 500, 1000};

    private final Ui ui;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint symbol = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private List<DecisionLog.Entry> entries = Collections.emptyList();
    private int selected = -1;
    private OnSelect listener;

    DecisionChartView(android.content.Context context, Ui ui) {
        super(context);
        this.ui = ui;
        label.setTextSize(ui.sp(11));
        label.setColor(ui.inkMuted);
        symbol.setTextAlign(Paint.Align.CENTER);
        symbol.setFakeBoldText(true);
        symbol.setTextSize(ui.sp(10));
        line.setStyle(Paint.Style.STROKE);
        setClickable(true);
        setFocusable(true);
    }

    void setOnSelect(OnSelect listener) {
        this.listener = listener;
    }

    /** @param newestFirst recent entries, newest first; only the latest {@link #SLOTS} are drawn */
    void setEntries(List<DecisionLog.Entry> newestFirst) {
        List<DecisionLog.Entry> ordered = new ArrayList<>(newestFirst.subList(0, Math.min(SLOTS, newestFirst.size())));
        Collections.reverse(ordered);
        DecisionLog.Entry previous = selectedEntry();
        entries = ordered;
        selected = previous == null ? -1 : ordered.indexOf(previous);
        setContentDescription(describe(ordered));
        invalidate();
    }

    DecisionLog.Entry selectedEntry() {
        return selected >= 0 && selected < entries.size() ? entries.get(selected) : null;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), resolveSize(ui.dp(184), heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        float left = ui.dp(44);
        float right = getWidth() - ui.dp(4);
        float top = ui.dp(24);
        float bottom = getHeight() - ui.dp(24);
        if (entries.isEmpty()) {
            label.setTextAlign(Paint.Align.CENTER);
            label.setColor(ui.inkSecondary);
            canvas.drawText("No offers recorded yet", getWidth() / 2f, getHeight() / 2f, label);
            label.setColor(ui.inkMuted);
            return;
        }

        long maxCents = scaleMax();
        drawGrid(canvas, left, right, top, bottom, maxCents);
        float slot = (right - left) / SLOTS;
        float barWidth = Math.min(ui.dp(24), slot - ui.dp(4));
        int firstSlot = SLOTS - entries.size();
        for (int i = 0; i < entries.size(); i++) {
            DecisionLog.Entry entry = entries.get(i);
            float center = left + slot * (firstSlot + i + 0.5f);
            if (i == selected) {
                fill.setColor(ui.selectionWash);
                rect.set(center - slot / 2f + ui.dp(1), top - ui.dp(20), center + slot / 2f - ui.dp(1), bottom);
                canvas.drawRoundRect(rect, ui.dp(6), ui.dp(6), fill);
            }
            int color = Ui.resultColor(entry.result);
            float barTop = bottom;
            if (entry.facts.payCents != null) {
                barTop = y(entry.facts.payCents, maxCents, top, bottom);
                drawBar(canvas, center - barWidth / 2f, barTop, center + barWidth / 2f, bottom, color);
            }
            if (entry.requiredCents > 0) {
                float tick = y(entry.requiredCents, maxCents, top, bottom);
                line.setColor(ui.ink);
                line.setStrokeWidth(ui.dp(2));
                line.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawLine(center - barWidth / 2f - ui.dp(3), tick, center + barWidth / 2f + ui.dp(3), tick, line);
            }
            drawBadge(canvas, center, barTop - ui.dp(10), entry.result);
        }
        drawTimes(canvas, left + slot * firstSlot, right);
    }

    private void drawGrid(Canvas canvas, float left, float right, float top, float bottom, long maxCents) {
        line.setStrokeWidth(Math.max(1, ui.dp(1)));
        line.setStrokeCap(Paint.Cap.BUTT);
        label.setTextAlign(Paint.Align.RIGHT);
        for (int step = 0; step <= 2; step++) {
            long cents = maxCents * step / 2;
            float y = y(cents, maxCents, top, bottom);
            line.setColor(step == 0 ? ui.baseline : ui.gridline);
            canvas.drawLine(left, y, right, y, line);
            canvas.drawText("$" + cents / 100, left - ui.dp(8), y + label.getTextSize() / 3f, label);
        }
    }

    /** A bar with a 4dp rounded data end and a square base. */
    private void drawBar(Canvas canvas, float l, float t, float r, float b, int color) {
        float radius = Math.min(ui.dp(4), Math.min((r - l) / 2f, (b - t) / 2f));
        path.reset();
        rect.set(l, t, r, b);
        path.addRoundRect(rect, new float[] {radius, radius, radius, radius, 0, 0, 0, 0}, Path.Direction.CW);
        fill.setColor(color);
        canvas.drawPath(path, fill);
    }

    private void drawBadge(Canvas canvas, float x, float y, OfferRule.Result result) {
        int color = Ui.resultColor(result);
        float radius = ui.dp(7);
        fill.setColor(ui.surface);
        canvas.drawCircle(x, y, radius + ui.dp(2), fill);
        fill.setColor(color);
        canvas.drawCircle(x, y, radius, fill);
        symbol.setColor(Ui.onStatus(color));
        canvas.drawText(Ui.resultSymbol(result), x, y + symbol.getTextSize() / 3f, symbol);
    }

    private void drawTimes(Canvas canvas, float firstX, float right) {
        float baseline = getHeight() - ui.dp(6);
        label.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(time(entries.get(0).at), firstX, baseline, label);
        if (entries.size() > 1) {
            label.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(time(entries.get(entries.size() - 1).at), right, baseline, label);
        }
    }

    private String time(long at) {
        return DateFormat.getTimeFormat(getContext()).format(new Date(at));
    }

    /** The smallest "nice" dollar ceiling at or above every pay and requirement shown. */
    private long scaleMax() {
        long max = 0;
        for (DecisionLog.Entry entry : entries) {
            if (entry.facts.payCents != null) max = Math.max(max, entry.facts.payCents);
            max = Math.max(max, Math.min(entry.requiredCents, SCALE_CAP_CENTS));
        }
        for (long dollars : NICE_DOLLARS) {
            if (dollars * 100 >= max) return dollars * 100;
        }
        return SCALE_CAP_CENTS;
    }

    private static float y(long cents, long maxCents, float top, float bottom) {
        return bottom - (bottom - top) * Math.min(cents, maxCents) / (float) maxCents;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (entries.isEmpty()) return super.onTouchEvent(event);
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float left = ui.dp(44);
            float slot = (getWidth() - ui.dp(4) - left) / SLOTS;
            int index = (int) Math.floor((event.getX() - left) / slot) - (SLOTS - entries.size());
            if (index >= 0 && index < entries.size()) select(index);
            performClick();
        }
        return true;
    }

    @Override public boolean performClick() {
        return super.performClick();
    }

    void select(int index) {
        selected = index;
        invalidate();
        if (listener != null) listener.selected(entries.get(index));
    }

    private static String describe(List<DecisionLog.Entry> entries) {
        int passed = 0;
        int declined = 0;
        int review = 0;
        for (DecisionLog.Entry entry : entries) {
            if (entry.result == OfferRule.Result.KEEP) passed++;
            else if (entry.result == OfferRule.Result.DECLINE) declined++;
            else review++;
        }
        return String.format(Locale.US, "Chart of the last %d offers: %d passed, %d declined, %d need review.",
                entries.size(), passed, declined, review);
    }
}
