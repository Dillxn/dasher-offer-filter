package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Recent offers as a little skyline, oldest left: each offer is a building as tall as the pay read, in the color of
 * what the rules said, with a tree beside it as tall as its recorded score (pay as a percent of the minimums).
 * Dollars and percentages have independent scales and share the street as zero. A dashed rail marks the current bar
 * ("Bar 82%"), and a solid one, only with a minimum pay, what that minimum asks at the bar ("Pay $3.28 min",
 * {@code ⌈bar × flat ÷ 100⌉}). The short ink tick across each building is the pay its rules required when decided. Its
 * flag says what became of it ({@link DecisionLog#outcome}): ✓ passed, ✕ declined (the app's decline went through),
 * ? review, a shopping bag accepted, and a person left to you (taken over, paused, refused, its confirmation not
 * tapped, or only its notification hidden), in a neutral grey. Passed offers have their windows lit. The guides never
 * replace the outcome. An unread score, and a score from the retired rules (a line an older version decided), gets an
 * open street-level marker, never a tree. An offer whose pay was not read is a signpost at street level. Tapping a
 * building selects it (a spotlight picks it out) and reports it to the listener; an offer picked on the constellation
 * is selected the same way ({@link #choose}). Buildings and trees rise together; with Android's animations off they
 * rest.
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
    /** A visible chevron marks larger scores; one outlier must not flatten the ordinary score range. */
    static final int SCORE_DISPLAY_CAP = 400;
    private static final long[] NICE_DOLLARS = {10, 15, 20, 25, 30, 40, 50, 60, 80, 100, 150, 200, 300, 500, 1000};

    private final Ui ui;
    /** The least height it reads well at, when the page asks with no limit; less in a short window. */
    private int leastDp = 64;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DashPathEffect scoreDash;
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private List<DecisionLog.Entry> entries = Collections.emptyList();
    private int selected = -1;
    private long selectedAt;
    private OnSelect listener;
    private long payoutMinimumCents;
    private int flatCents;
    private int minimumScalePercent = 100;
    /** When each shown offer (by its time) began to rise. */
    private final Map<Long, Long> risingSince = new HashMap<>();

    DecisionChartView(android.content.Context context, Ui ui) {
        super(context);
        this.ui = ui;
        scoreDash = new DashPathEffect(new float[] {Math.max(1, ui.dp(4)), Math.max(1, ui.dp(3))}, 0);
        label.setTextSize(ui.sp(11));
        label.setColor(ui.inkMuted);
        label.setTextAlign(Paint.Align.CENTER);
        line.setStyle(Paint.Style.STROKE);
        setClickable(true);
        setFocusable(true);
    }

    void setOnSelect(OnSelect listener) {
        this.listener = listener;
    }

    /**
     * The rails for these rules: the dashed one at the bar, and the solid one, only with a minimum pay, at what it
     * asks at the bar ({@code ⌈bar × flat ÷ 100⌉}).
     */
    void setRules(FilterSettings rules) {
        int flat = Math.max(0, rules.flatCents);
        long payout = flat > 0 ? OfferRule.scaledCost(flat, rules.minimumScalePercent) : 0;
        if (payoutMinimumCents == payout && flatCents == flat && minimumScalePercent == rules.minimumScalePercent) {
            return;
        }
        payoutMinimumCents = payout;
        flatCents = flat;
        minimumScalePercent = rules.minimumScalePercent;
        setContentDescription(describe(entries));
        invalidate();
    }

    /** A tree stands only for a score this version's rules worked out: never a retired one. */
    private static boolean tree(DecisionLog.Entry entry) {
        return entry.scorePercent >= 0 && entry.model >= DecisionLog.MODEL;
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
        // An observed outcome replaces the immutable entry, but it is still the same selected offer.
        for (int i = 0; selected < 0 && previous != null && i < ordered.size(); i++) {
            if (ordered.get(i).at == previous.at) selected = i;
        }
        setContentDescription(describe(ordered));
        invalidate();
    }

    DecisionLog.Entry selectedEntry() {
        return selected >= 0 && selected < entries.size() ? entries.get(selected) : null;
    }

    void setLeastDp(int dp) {
        leastDp = dp;
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        // Asked with no limit (the page working out what fits one screen), it answers the least it reads well at.
        int height = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED ? ui.dp(leastDp)
                : resolveSize(ui.dp(160), heightSpec);
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), height);
    }

    @Override protected void onDraw(Canvas canvas) {
        float left = ui.dp(SIDE_DP);
        float right = getWidth() - ui.dp(SIDE_DP);
        float top = plotTop();
        float bottom = plotBottom();
        if (right <= left || bottom <= top) return;
        if (entries.isEmpty()) {
            label.setColor(ui.inkSecondary);
            canvas.drawText("No offers recorded yet", getWidth() / 2f, getHeight() / 2f, label);
            return;
        }

        long maxCents = scaleMax();
        long maxScore = scoreScaleMax();
        float slot = (right - left) / SLOTS;
        float barWidth = buildingWidth(slot);
        int firstSlot = SLOTS - entries.size();
        // The spotlight moving to the building just tapped draws every frame; the rest is steady motion.
        float spotlight = Motion.settle(selectedAt, 300);
        if (selected >= 0 && selected < entries.size()) {
            drawSpotlight(canvas, left + slot * (firstSlot + selected + 0.5f), barWidth, bottom, spotlight);
        }
        // The street sits underneath measured-zero marks, so a zero tree stays visibly distinct from unknown.
        fill.setColor(ui.gridline);
        rect.set(left, bottom, right, bottom + ui.dp(3));
        canvas.drawRoundRect(rect, ui.dp(1.5f), ui.dp(1.5f), fill);
        // Draw the guides under the data so the score crown and building roof remain visible at a crossing.
        drawThresholdLines(canvas, left, right);
        for (int i = 0; i < entries.size(); i++) {
            DecisionLog.Entry entry = entries.get(i);
            float center = buildingCenter(i, slot);
            float treeCenter = treeCenter(i, slot);
            Long since = risingSince.get(entry.at);
            float rise = Motion.settle(since == null ? 0 : since, RISE_MS);
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
                canvas.drawLine(center - barWidth / 2f, rope, center + barWidth / 2f, rope, line);
            }
            if (tree(entry)) {
                float tip = bottom - (bottom - y(entry.scorePercent, maxScore, top, bottom)) * rise;
                if (rise > 0) {
                    drawTree(canvas, treeCenter, tip, bottom, treeWidth(slot));
                    if (entry.scorePercent > SCORE_DISPLAY_CAP) {
                        line.setColor(treeColor());
                        line.setStrokeWidth(Math.max(1, ui.dp(1)));
                        float half = treeWidth(slot) * 0.4f;
                        canvas.drawLine(treeCenter - half, tip - ui.dp(2), treeCenter, tip - ui.dp(4), line);
                        canvas.drawLine(treeCenter, tip - ui.dp(4), treeCenter + half, tip - ui.dp(2), line);
                    }
                }
            } else {
                // Unavailable (or from the retired rules) and measured zero are distinct: open ring versus a flat mark.
                line.setColor(ui.inkSecondary);
                line.setStrokeWidth(Math.max(1, ui.dp(1)));
                canvas.drawCircle(treeCenter, bottom - ui.dp(2), Math.min(ui.dp(2), treeWidth(slot) / 3f), line);
            }
            if (rise <= 0) continue;
            // A flag on a short pole on the roof.
            line.setColor(ui.baseline);
            line.setStrokeWidth(Math.max(1, ui.dp(1.5f)));
            canvas.drawLine(center, roof, center, roof - ui.dp(5), line);
            drawBadge(canvas, center, roof - ui.dp(12), DecisionLog.outcome(entry), i == selected);
        }
        drawThresholdLabels(canvas, left, right);
        if (spotlight < 1) Motion.settling(this);
        else Motion.next(this);
    }

    private int treeColor() { return ui.dark ? 0xFF9DD6B6 : 0xFF32664F; }
    private int payoutColor() { return ui.dark ? 0xFFF1C58B : 0xFF865423; }

    /** A slim evergreen: its very tip is the score, its trunk ends at the shared zero baseline. */
    private void drawTree(Canvas canvas, float x, float tip, float bottom, float width) {
        float height = bottom - tip;
        line.setColor(treeColor());
        line.setStrokeWidth(Math.max(1, Math.min(ui.dp(1.5f), width / 4f)));
        line.setStrokeCap(Paint.Cap.ROUND);
        if (height <= 0) {
            canvas.drawLine(x - width / 3f, bottom, x + width / 3f, bottom, line);
            return;
        }
        canvas.drawLine(x, tip, x, bottom, line);
        float crown = height * 0.84f;
        path.reset();
        path.moveTo(x, tip);
        path.lineTo(x + width * 0.30f, tip + crown * 0.43f);
        path.lineTo(x + width * 0.15f, tip + crown * 0.43f);
        path.lineTo(x + width * 0.43f, tip + crown * 0.72f);
        path.lineTo(x + width * 0.24f, tip + crown * 0.72f);
        path.lineTo(x + width / 2f, tip + crown);
        path.lineTo(x - width / 2f, tip + crown);
        path.lineTo(x - width * 0.24f, tip + crown * 0.72f);
        path.lineTo(x - width * 0.43f, tip + crown * 0.72f);
        path.lineTo(x - width * 0.15f, tip + crown * 0.43f);
        path.lineTo(x - width * 0.30f, tip + crown * 0.43f);
        path.close();
        fill.setColor(treeColor());
        canvas.drawPath(path, fill);
    }

    private void drawThresholdLines(Canvas canvas, float left, float right) {
        line.setStrokeWidth(Math.max(1, ui.dp(1)));
        line.setStrokeCap(Paint.Cap.BUTT);
        if (payoutMinimumCents > 0) {
            line.setColor(payoutColor());
            canvas.drawLine(left, payoutThresholdY(), right, payoutThresholdY(), line);
        }
        line.setColor(treeColor());
        line.setPathEffect(scoreDash);
        canvas.drawLine(left, scoreThresholdY(), right, scoreThresholdY(), line);
        line.setPathEffect(null);
    }

    private void drawThresholdLabels(Canvas canvas, float left, float right) {
        String pay = AutopilotText.skylinePayRail(flatCents, minimumScalePercent);
        if (payoutMinimumCents > 0 && pay != null) drawRailLabel(canvas, pay, left, false, payoutColor());
        drawRailLabel(canvas, AutopilotText.skylineBarRail(minimumScalePercent), right, true, treeColor());
    }

    /** A reserved caption strip keeps every roof flag and tree visible, even in the 52dp skyline. */
    private void drawRailLabel(Canvas canvas, String text, float edge, boolean end, int color) {
        label.setTextAlign(end ? Paint.Align.RIGHT : Paint.Align.LEFT);
        label.setTypeface(Ui.MEDIUM);
        label.setTextSize(ui.sp(10));
        float available = Math.max(1, (getWidth() - ui.dp(SIDE_DP * 2 + 8)) / 2f);
        float width = label.measureText(text);
        if (width > available) label.setTextSize(label.getTextSize() * available / width);
        float baseline = getHeight() - ui.dp(3) - label.descent();
        float x = edge + (end ? -ui.dp(3) : ui.dp(3));
        label.setColor(color);
        canvas.drawText(text, x, baseline, label);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(null);
        label.setTextSize(ui.sp(11));
    }

    /** A soft beam from the top of the chart down onto the selected building. */
    private void drawSpotlight(Canvas canvas, float center, float barWidth, float bottom, float shown) {
        path.reset();
        path.moveTo(center - ui.dp(4), 0);
        path.lineTo(center + ui.dp(4), 0);
        path.lineTo(center + barWidth / 2f + ui.dp(8), bottom);
        path.lineTo(center - barWidth / 2f - ui.dp(8), bottom);
        path.close();
        fill.setColor((ui.accent & 0x00FFFFFF) | (Math.round(0x12 * shown) << 24));
        canvas.drawPath(path, fill);
        fill.setColor((ui.accent & 0x00FFFFFF) | (Math.round(0xFF * shown) << 24));
        rect.set(center - barWidth / 2f, getHeight() - ui.dp(1.5f), center + barWidth / 2f, getHeight());
        canvas.drawRoundRect(rect, ui.dp(0.75f), ui.dp(0.75f), fill);
    }

    /** A building with a rounded roofline and a grid of windows, lit when the offer passed. */
    private void drawBuilding(Canvas canvas, float l, float t, float r, float b, OfferRule.Result result) {
        // Buildings belong to the landscape. The selected outcome badge carries the strong status colour.
        int color = result == OfferRule.Result.KEEP ? (ui.dark ? 0xFF557B63 : 0xFF78927D)
                : result == OfferRule.Result.DECLINE ? (ui.dark ? 0xFF87645E : 0xFFAA8C81)
                : (ui.dark ? 0xFF998454 : 0xFFBDAC85);
        float radius = Math.min(ui.dp(4), Math.min((r - l) / 2f, (b - t) / 2f));
        path.reset();
        rect.set(l, t, r, b);
        path.addRoundRect(rect, new float[] {radius, radius, radius, radius, 0, 0, 0, 0}, Path.Direction.CW);
        fill.setColor(color);
        canvas.drawPath(path, fill);
        float window = Math.max(ui.dp(2), Math.min(ui.dp(4), (r - l) / 6f));
        int lit = result == OfferRule.Result.KEEP ? 0xCCFFE9A3 : result == OfferRule.Result.REVIEW ? 0x70FFFFFF
                : 0x28000000;
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

    /** The flag's badge ({@link OutcomeBadge}), kept whole inside the view however tall its building. */
    private void drawBadge(Canvas canvas, float x, float y, DecisionLog.Outcome outcome, boolean selected) {
        if (selected) OutcomeBadge.draw(canvas, ui, x, Math.max(y, ui.dp(10)), outcome, 255);
        else OutcomeBadge.drawQuiet(canvas, ui, x, Math.max(y, ui.dp(10)), outcome);
    }

    /**
     * Where the flag of the offer at {@code index} (oldest first, as drawn) stands once its building has risen, in this
     * view's pixels; null where there is none (for tests).
     */
    float[] flagAt(int index) {
        if (index < 0 || index >= entries.size() || getWidth() <= 0) return null;
        float top = plotTop();
        float bottom = plotBottom();
        float slot = slotWidth();
        DecisionLog.Entry entry = entries.get(index);
        float center = buildingCenter(index, slot);
        float roof = entry.facts.payCents != null ? y(entry.facts.payCents, scaleMax(), top, bottom)
                : bottom - ui.dp(14);
        return new float[] {center, Math.max(roof - ui.dp(12), ui.dp(10))};
    }

    /** Settled score tree: center X, tip Y, baseline Y and width. An unknown or retired score has no tree. */
    float[] treeAt(int index) {
        if (index < 0 || index >= entries.size() || getWidth() <= 0 || !tree(entries.get(index))) return null;
        float slot = slotWidth();
        return new float[] {treeCenter(index, slot), y(entries.get(index).scorePercent, scoreScaleMax(),
                plotTop(), plotBottom()), plotBottom(), treeWidth(slot)};
    }

    boolean treeClippedAt(int index) {
        return index >= 0 && index < entries.size() && tree(entries.get(index))
                && entries.get(index).scorePercent > SCORE_DISPLAY_CAP;
    }

    long payoutThresholdCents() { return payoutMinimumCents; }

    float payoutThresholdY() {
        return payoutMinimumCents > 0 ? y(payoutMinimumCents, scaleMax(), plotTop(), plotBottom()) : Float.NaN;
    }

    int scoreThresholdPercent() { return minimumScalePercent; }
    float scoreThresholdY() { return y(minimumScalePercent, scoreScaleMax(), plotTop(), plotBottom()); }

    private float plotTop() { return Math.min(ui.dp(26), getHeight() * 0.40f); }
    private float plotBottom() {
        // The 10sp caption grows with font size, while a compact chart still keeps its data above it.
        return Math.max(plotTop(), getHeight() - Math.max(ui.dp(15), ui.sp(10) * 1.3f + ui.dp(3)));
    }
    private float slotWidth() { return Math.max(0, getWidth() - ui.dp(SIDE_DP * 2)) / (float) SLOTS; }
    private float buildingWidth(float slot) { return Math.max(1, Math.min(ui.dp(21), slot * 0.66f)); }
    private float treeWidth(float slot) { return Math.max(1, Math.min(ui.dp(9), slot * 0.28f)); }
    private float buildingCenter(int index, float slot) {
        return ui.dp(SIDE_DP) + slot * (SLOTS - entries.size() + index + 0.34f);
    }
    private float treeCenter(int index, float slot) {
        return ui.dp(SIDE_DP) + slot * (SLOTS - entries.size() + index + 0.82f);
    }

    /**
     * The highest point, in this view's pixels, that a building, tree, rail or flag reaches once risen between
     * {@code from} and {@code to} across; the view's height where none stands.
     */
    float highestWithin(float from, float to) {
        float highest = getHeight();
        if (entries.isEmpty() || getWidth() <= 0) return highest;
        float left = ui.dp(SIDE_DP);
        float right = getWidth() - ui.dp(SIDE_DP);
        if (to < left || from > right) return highest;
        float top = plotTop();
        float bottom = plotBottom();
        long maxCents = scaleMax();
        float slot = (right - left) / SLOTS;
        float barWidth = buildingWidth(slot);
        float reach = Math.max(barWidth / 2f, ui.dp(9));
        highest = Math.min(highest, scoreThresholdY() - ui.dp(1));
        if (payoutMinimumCents > 0) highest = Math.min(highest, payoutThresholdY() - ui.dp(1));
        // Both captions are below the street, so neither can be higher than a plotted rail.
        for (int i = 0; i < entries.size(); i++) {
            DecisionLog.Entry entry = entries.get(i);
            float center = buildingCenter(i, slot);
            if (center + reach >= from && center - reach <= to) {
                float roof = entry.facts.payCents != null ? y(entry.facts.payCents, maxCents, top, bottom)
                        : bottom - ui.dp(14);
                highest = Math.min(highest, Math.max(roof - ui.dp(12), ui.dp(10)) - ui.dp(9));
                if (entry.requiredCents > 0) highest = Math.min(highest, y(entry.requiredCents, maxCents, top, bottom)
                        - ui.dp(1));
            }
            float[] tree = treeAt(i);
            if (tree != null && tree[0] + tree[3] / 2f >= from && tree[0] - tree[3] / 2f <= to) {
                highest = Math.min(highest, tree[1] - (treeClippedAt(i) ? ui.dp(5) : 0));
            }
        }
        return highest;
    }

    /** The smallest "nice" dollar ceiling at or above every pay and requirement shown. */
    private long scaleMax() {
        long max = payoutMinimumCents;
        for (DecisionLog.Entry entry : entries) {
            if (entry.facts.payCents != null) max = Math.max(max, entry.facts.payCents);
            max = Math.max(max, Math.min(entry.requiredCents, SCALE_CAP_CENTS));
        }
        for (long dollars : NICE_DOLLARS) {
            if (dollars * 100 >= max) return dollars * 100;
        }
        return Math.max(SCALE_CAP_CENTS, payoutMinimumCents);
    }

    /** Percent scale does not depend on payouts or dollar requirements. Unknowns contribute nothing. */
    private long scoreScaleMax() {
        int max = Math.max(100, minimumScalePercent);
        for (DecisionLog.Entry entry : entries) {
            if (tree(entry)) max = Math.max(max, Math.min(SCORE_DISPLAY_CAP, entry.scorePercent));
        }
        for (int ceiling : new int[] {150, 200, 250, 300, SCORE_DISPLAY_CAP}) {
            if (ceiling >= max) return ceiling;
        }
        return SCORE_DISPLAY_CAP;
    }

    private static float y(long cents, long maxCents, float top, float bottom) {
        return bottom - (bottom - top) * Math.min(Math.max(0, cents), maxCents) / (float) maxCents;
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

    /** The skyline is also the history browser for screen readers, without drawing more controls. */
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (entries.isEmpty()) return;
        int current = selected >= 0 ? selected : entries.size() - 1;
        info.setClassName("android.widget.SeekBar");
        info.setScrollable(entries.size() > 1);
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0, entries.size() - 1, current));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.ACTION_CLICK, "Show offer details"));
        if (current > 0) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, "Previous offer"));
        if (current + 1 < entries.size()) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "Next offer"));
        if (entries.size() > 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                || action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                || action == android.R.id.accessibilityActionSetProgress) {
            if (entries.isEmpty()) return false;
            int current = selected >= 0 ? selected : entries.size() - 1;
            int next;
            if (action == android.R.id.accessibilityActionSetProgress) {
                if (args == null || !args.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) return false;
                float value = args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);
                if (!Float.isFinite(value) || value < 0 || value > entries.size() - 1) return false;
                next = Math.round(value);
            } else next = current + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1);
            if (next < 0 || next >= entries.size()) return false;
            select(next);
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }

    @Override public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SELECTED && selectedEntry() != null) {
            event.setContentDescription(selectedDescription());
            event.setItemCount(entries.size());
            event.setCurrentItemIndex(selected);
        }
    }

    void select(int index) {
        if (index != selected) selectedAt = SystemClock.uptimeMillis();
        selected = index;
        setContentDescription(describe(entries));
        invalidate();
        if (listener != null) listener.selected(entries.get(index));
    }

    /**
     * Selects {@code entry}'s building as a tap on it does (an offer picked on the constellation, say); false, with
     * nothing changed, when it is not in the skyline.
     */
    boolean choose(DecisionLog.Entry entry) {
        int index = entries.indexOf(entry);
        for (int i = 0; i < entries.size() && index < 0 && entry != null; i++) {
            if (entries.get(i).at == entry.at) index = i;
        }
        if (index < 0) return false;
        select(index);
        return true;
    }

    /**
     * "Chart of the last 5 offers: 1 passed, 1 accepted, 1 declined, 1 left to you, 1 need review.": as the flags say,
     * what became of each offer; accepted and left to you only when there are any.
     */
    private String describe(List<DecisionLog.Entry> entries) {
        int[] counts = new int[DecisionLog.Outcome.values().length];
        for (DecisionLog.Entry entry : entries) counts[DecisionLog.outcome(entry).ordinal()]++;
        int accepted = counts[DecisionLog.Outcome.ACCEPTED.ordinal()];
        int yours = counts[DecisionLog.Outcome.YOURS.ordinal()];
        int requested = counts[DecisionLog.Outcome.REQUESTED.ordinal()];
        String summary = String.format(Locale.US, "Chart of the last %d offers: %d passed, %s%d declined, %s%d need review.",
                entries.size(), counts[DecisionLog.Outcome.PASSED.ordinal()],
                accepted > 0 ? accepted + " accepted, " : "", counts[DecisionLog.Outcome.DECLINED.ordinal()],
                (yours > 0 ? yours + " left to you, " : "")
                        + (requested > 0 ? requested + " accept requested but unconfirmed, " : ""),
                counts[DecisionLog.Outcome.REVIEW.ordinal()]);
        int unknown = 0;
        int retired = 0;
        int clipped = 0;
        for (DecisionLog.Entry entry : entries) {
            if (entry.scorePercent < 0) unknown++;
            else if (!tree(entry)) retired++;
            else if (entry.scorePercent > SCORE_DISPLAY_CAP) clipped++;
        }
        return summary + selectedDescription() + " Building height is pay in dollars. "
                + AutopilotText.SKYLINE_DESCRIPTION + " Dashed line: " + AutopilotText.skylineBarRail(minimumScalePercent)
                + "." + (payoutMinimumCents > 0 ? " Solid line: your minimum pay at the bar, "
                + DecisionLog.money(payoutMinimumCents) + "." : " No minimum pay is set.")
                + " Short building ticks are each offer's required pay when decided."
                + (unknown > 0 ? " " + unknown + " recorded scores unavailable: open markers, no trees." : "")
                + (retired > 0 ? " " + retired + " scores from retired rules: open markers, no trees." : "")
                + (clipped > 0 ? " " + clipped + " trees exceed " + SCORE_DISPLAY_CAP
                + "% and end in an overflow chevron; tap for the recorded score." : "");
    }

    private String selectedDescription() {
        DecisionLog.Entry entry = selectedEntry();
        if (entry == null) return "";
        DecisionLog.Outcome outcome = DecisionLog.outcome(entry);
        String state = outcome == DecisionLog.Outcome.YOURS ? "left to you"
                : outcome.name().toLowerCase(Locale.US);
        return " Selected offer " + (selected + 1) + " of " + entries.size() + ": "
                + (entry.facts.payCents == null ? "payout unknown" : DecisionLog.money(entry.facts.payCents))
                + ", " + DecisionLog.facts(entry.facts) + ", " + state
                + (tree(entry) ? ", score " + entry.scorePercent + "% of your minimums."
                : entry.scorePercent >= 0 ? ", score from retired rules." : ", score unknown.");
    }
}
