package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Recent offers as a little skyline, oldest left: each offer is a building as tall as the pay read, the ink rope
 * across it is the pay the rules required, and the flag on its roof says what happened (✓ passed, ✕ declined,
 * ? review). Passed offers have their windows lit. A building that ends below its rope is a decline by the rules as
 * written, so a surprising decline shows either a misread pay or a rule to adjust. An offer whose pay was not read
 * is a signpost at street level. Tapping a building selects it (a spotlight picks it out) and reports it to the
 * listener. There is no axis: the line under the chart gives the chosen offer's pay and what it needed. Buildings
 * rise as offers arrive and a few stars twinkle overhead; with Android's animations off they rest.
 */
@SuppressLint("ViewConstructor")
final class DecisionChartView extends View {
    interface OnSelect {
        void selected(DecisionLog.Entry entry);
    }

    static final int SLOTS = 14;
    /** The chart's inset from each side, in dp. */
    static final int SIDE_DP = 4;
    private static final long RISE_MS = 650;
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
    private long selectedAt;
    private OnSelect listener;
    /** When each shown offer (by its time) began to rise. */
    private final Map<Long, Long> risingSince = new HashMap<>();

    DecisionChartView(android.content.Context context, Ui ui) {
        super(context);
        this.ui = ui;
        label.setTextSize(ui.sp(11));
        label.setColor(ui.inkMuted);
        label.setTextAlign(Paint.Align.CENTER);
        symbol.setTextAlign(Paint.Align.CENTER);
        symbol.setFakeBoldText(true);
        // The symbol belongs to its circle, so it follows the font setting only as far as the circle allows.
        symbol.setTextSize(Math.min(ui.sp(10), ui.dp(11)));
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
        // New offers rise from the street; on first showing, all rise one after another.
        long now = SystemClock.uptimeMillis();
        boolean first = risingSince.isEmpty();
        Map<Long, Long> next = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            Long since = risingSince.get(ordered.get(i).at);
            next.put(ordered.get(i).at, since != null ? since : now + (first ? i * 45L : 0));
        }
        risingSince.clear();
        risingSince.putAll(next);
        entries = ordered;
        selected = previous == null ? -1 : ordered.indexOf(previous);
        setContentDescription(describe(ordered));
        invalidate();
    }

    DecisionLog.Entry selectedEntry() {
        return selected >= 0 && selected < entries.size() ? entries.get(selected) : null;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        // Asked with no limit (the page working out what fits one screen), it answers the least it reads well at.
        int height = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED ? ui.dp(74)
                : resolveSize(ui.dp(160), heightSpec);
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), height);
    }

    @Override protected void onDraw(Canvas canvas) {
        float left = ui.dp(SIDE_DP);
        float right = getWidth() - ui.dp(SIDE_DP);
        float top = ui.dp(26);
        float bottom = getHeight() - ui.dp(12);
        if (entries.isEmpty()) {
            label.setColor(ui.inkSecondary);
            canvas.drawText("No offers recorded yet", getWidth() / 2f, getHeight() / 2f, label);
            return;
        }

        long maxCents = scaleMax();
        float slot = (right - left) / SLOTS;
        float barWidth = Math.min(ui.dp(26), slot - ui.dp(3));
        int firstSlot = SLOTS - entries.size();
        if (selected >= 0 && selected < entries.size()) {
            drawSpotlight(canvas, left + slot * (firstSlot + selected + 0.5f), barWidth, bottom,
                    Motion.settle(selectedAt, 300));
        }
        boolean rising = false;
        for (int i = 0; i < entries.size(); i++) {
            DecisionLog.Entry entry = entries.get(i);
            float center = left + slot * (firstSlot + i + 0.5f);
            Long since = risingSince.get(entry.at);
            float rise = Motion.settle(since == null ? 0 : since, RISE_MS);
            rising |= rise < 1;
            float roof = bottom;
            if (entry.facts.payCents != null) {
                roof = bottom - (bottom - y(entry.facts.payCents, maxCents, top, bottom)) * rise;
                if (rise > 0) {
                    drawBuilding(canvas, center - barWidth / 2f, roof, center + barWidth / 2f, bottom, entry.result);
                }
            } else {
                drawSignpost(canvas, center, bottom);
                roof = bottom - ui.dp(14);
            }
            if (entry.requiredCents > 0) {
                float rope = y(entry.requiredCents, maxCents, top, bottom);
                line.setColor(ui.ink);
                line.setStrokeWidth(ui.dp(2));
                line.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawLine(center - barWidth / 2f - ui.dp(3), rope, center + barWidth / 2f + ui.dp(3), rope, line);
            }
            if (rise <= 0) continue;
            // A flag on a short pole on the roof.
            line.setColor(ui.baseline);
            line.setStrokeWidth(Math.max(1, ui.dp(1.5f)));
            canvas.drawLine(center, roof, center, roof - ui.dp(5), line);
            drawBadge(canvas, center, roof - ui.dp(12), entry.result);
        }
        // The street.
        fill.setColor(ui.gridline);
        rect.set(left, bottom, right, bottom + ui.dp(3));
        canvas.drawRoundRect(rect, ui.dp(1.5f), ui.dp(1.5f), fill);
        Motion.next(this);
    }

    /** A soft beam from the top of the chart down onto the selected building. */
    private void drawSpotlight(Canvas canvas, float center, float barWidth, float bottom, float shown) {
        path.reset();
        path.moveTo(center - ui.dp(4), 0);
        path.lineTo(center + ui.dp(4), 0);
        path.lineTo(center + barWidth / 2f + ui.dp(8), bottom);
        path.lineTo(center - barWidth / 2f - ui.dp(8), bottom);
        path.close();
        fill.setColor((ui.accent & 0x00FFFFFF) | (Math.round(0x24 * shown) << 24));
        canvas.drawPath(path, fill);
        fill.setColor((ui.accent & 0x00FFFFFF) | (Math.round(0xFF * shown) << 24));
        rect.set(center - barWidth / 2f, bottom + ui.dp(6), center + barWidth / 2f, bottom + ui.dp(9));
        canvas.drawRoundRect(rect, ui.dp(2), ui.dp(2), fill);
    }

    /** A building with a rounded roofline and a grid of windows, lit when the offer passed. */
    private void drawBuilding(Canvas canvas, float l, float t, float r, float b, OfferRule.Result result) {
        int color = Ui.resultColor(result);
        float radius = Math.min(ui.dp(4), Math.min((r - l) / 2f, (b - t) / 2f));
        path.reset();
        rect.set(l, t, r, b);
        path.addRoundRect(rect, new float[] {radius, radius, radius, radius, 0, 0, 0, 0}, Path.Direction.CW);
        fill.setColor(color);
        canvas.drawPath(path, fill);
        float window = Math.max(ui.dp(2), Math.min(ui.dp(4), (r - l) / 6f));
        int lit = result == OfferRule.Result.KEEP ? 0xFFFFE9A3 : result == OfferRule.Result.REVIEW ? 0x99FFFFFF
                : 0x40000000;
        fill.setColor(lit);
        float gap = window * 1.2f;
        for (float y = t + ui.dp(6); y + window <= b - ui.dp(4); y += window + gap) {
            for (int column = 0; column < 2; column++) {
                float x = l + (r - l) * (column == 0 ? 0.3f : 0.7f) - window / 2f;
                canvas.drawRect(x, y, x + window, y + window, fill);
            }
        }
    }

    /** An offer whose pay was not read: a signpost at street level instead of a building. */
    private void drawSignpost(Canvas canvas, float center, float bottom) {
        line.setColor(ui.baseline);
        line.setStrokeWidth(ui.dp(2));
        canvas.drawLine(center, bottom, center, bottom - ui.dp(12), line);
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
            float left = ui.dp(SIDE_DP);
            float slot = (getWidth() - ui.dp(SIDE_DP) - left) / SLOTS;
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
        if (index != selected) selectedAt = SystemClock.uptimeMillis();
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
