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
 * Recent offers as one bar each, oldest left, on one scale (0.6.6; the owner, 12 October 2026: "consolidate the number
 * of ways we express information"): each bar is the offer's recorded score, percent of the minimums, the number the
 * filter decides with, and its colour is what actually happened ({@link DecisionLog#outcome}): green passed, amber
 * passed below the minimums or left for review (or to you), red filtered. The solid line is the minimums (100%), and a
 * dashed purple line Autopilot's bar while it is not 100%; the lines are today's rules. An offer whose score was not
 * read (or came from the retired rules) is a hollow dashed bar with "?". One badge marks the selected offer (the
 * newest, unless another was chosen), and the stored route's offer (0.6.3, {@link #setRoute}) has a small road beside
 * that badge. Pay in dollars stays on the verdict and on each ticket. Under the bars, the dash's counts are the chart's
 * key ("DASH ✓ 1 ✕ 0 ? 1", the band's counts before 0.6.6), and a tap on one opens its latest offer
 * ({@link OnCount}). Tapping a bar selects it (its badge picks it out) and reports it to the listener; an offer picked
 * on the constellation is selected the same way ({@link #choose}). Bars rise; with Android's animations off they rest.
 */
@SuppressLint("ViewConstructor")
final class DecisionChartView extends View {
    interface OnSelect {
        void selected(DecisionLog.Entry entry);
    }

    /** A tap on one of the key's counts: passed, filtered or left to review. */
    interface OnCount {
        void count(DecisionLog.Tally tally);
    }

    static final int SLOTS = 14;
    /** The chart's inset from each side, in dp. */
    static final int SIDE_DP = 4;
    private static final long RISE_MS = 650;
    /** A visible chevron marks larger scores; one outlier must not flatten the ordinary score range. */
    static final int SCORE_DISPLAY_CAP = 400;

    private final Ui ui;
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
    private OnCount countListener;
    /** The key's counts (passed, filtered, review) for the dash named by {@link #keyLabel}; the all-time totals. */
    private final int[] keyCounts = new int[3];
    private final int[] keyTotals = new int[3];
    private String keyLabel = "No dash yet";
    private boolean keyLive = true;
    /** The key's three counts' boxes as last drawn, for taps. */
    private final RectF[] keyBoxes = {new RectF(), new RectF(), new RectF()};
    private final DashPathEffect barDash;
    private boolean areaMode;
    private int minimumScalePercent = 100;
    /** What screen readers hear with no offer yet (nothing is drawn but the street). */
    static final String EMPTY_SAID = "No offers recorded yet.";
    /** When each shown offer (by its time) began to rise. */
    private final Map<Long, Long> risingSince = new HashMap<>();
    /** The stored route's offer, by its facts (null: none), and which shown offer it is (-1: none of them). */
    private String routeFingerprint;
    private int routed = -1;
    /**
     * The road beside a route's flag: its size, and how far right of and below the flag's middle it stands (clear of
     * a quiet mark, on the edge of the selected offer's full badge, above the roof and clear of the flag's pole).
     */
    private static final float ROAD_MARK_DP = 10;
    private static final float ROAD_MARK_RIGHT_DP = 10;
    private static final float ROAD_MARK_DOWN_DP = 4;

    DecisionChartView(android.content.Context context, Ui ui) {
        super(context);
        this.ui = ui;
        scoreDash = new DashPathEffect(new float[] {Math.max(1, ui.dp(4)), Math.max(1, ui.dp(3))}, 0);
        barDash = new DashPathEffect(new float[] {Math.max(1, ui.dp(2)), Math.max(1, ui.dp(2))}, 0);
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

    void setOnCount(OnCount listener) {
        this.countListener = listener;
    }

    /**
     * The key's counts: {@code dash} passed, filtered and review for the dash {@code label} names ("This dash", "Last
     * dash" or "No dash yet"), {@code totals} the same since the history was cleared (said, not drawn); {@code live}
     * false while auto-decline is off (the badges dim).
     */
    void setCounts(int[] dash, int[] totals, String label, boolean live) {
        boolean same = java.util.Arrays.equals(dash, keyCounts) && java.util.Arrays.equals(totals, keyTotals)
                && label.equals(keyLabel) && live == keyLive;
        if (same) return;
        System.arraycopy(dash, 0, keyCounts, 0, 3);
        System.arraycopy(totals, 0, keyTotals, 0, 3);
        keyLabel = label;
        keyLive = live;
        setContentDescription(describe(entries));
        invalidate();
    }

    /** What the key's count for {@code tally} says, for screen readers ("This dash: 1 passed, 67 in all"). */
    String keySaid() {
        boolean none = keyLabel.startsWith("No");
        return keyLabel + ": " + (none ? "none yet" : keyCounts[0] + " passed, " + keyCounts[1] + " filtered, "
                + keyCounts[2] + " left to review") + ". In all: " + keyTotals[0] + " passed, " + keyTotals[1]
                + " filtered, " + keyTotals[2] + " left to review.";
    }

    /** Where the key's count for {@code tally} stands, in this view's pixels; empty before the view has a size. */
    RectF keyBox(DecisionLog.Tally tally) {
        placeKey();
        return new RectF(keyBoxes[tally.ordinal()]);
    }

    /**
     * The key's three columns under the bars, after its heading where there is room for it: each column the badge,
     * 4 dp and a number as wide as "88" at least. Sets {@link #keyBoxes} and returns the heading's width (0 without).
     */
    private float placeKey() {
        float left = ui.dp(SIDE_DP);
        float right = getWidth() - ui.dp(SIDE_DP);
        if (right <= left) {
            for (RectF box : keyBoxes) box.setEmpty();
            return 0;
        }
        label.setTypeface(Ui.MEDIUM);
        label.setLetterSpacing(0.12f);
        label.setTextSize(Math.min(ui.sp(10), ui.dp(13)));
        String heading = keyLabel.startsWith("Last") ? "LAST" : "DASH";
        float headingWide = label.measureText(heading) + ui.dp(6);
        label.setLetterSpacing(0);
        label.setTextSize(Math.min(ui.sp(14), ui.dp(17)));
        float badge = keyBadge();
        float count = badge * 2 + ui.dp(5) + label.measureText("88");
        // The counts as they read now ("–" before any, "3", "12"), for whether "latest →" fits beside them.
        boolean none = keyLabel.startsWith("No");
        float widest = label.measureText("–");
        for (int k = 0; k < 3 && !none; k++) widest = Math.max(widest, label.measureText(Integer.toString(keyCounts[k])));
        float shownCount = badge * 2 + ui.dp(5) + widest;
        // "latest →" at the row's end (0.6.8, the owner's mockup) where the counts leave it room (0.6.9: the counts
        // as they read, not as wide as "88", so a narrow chart beside Dasher shows it too).
        label.setTextSize(Math.min(ui.sp(11), ui.dp(14)));
        float latest = label.measureText(LATEST) + ui.dp(8);
        label.setTextSize(Math.min(ui.sp(14), ui.dp(17)));
        latestShown = (right - left - headingWide - latest) / 3f >= shownCount + ui.dp(4);
        float room = right - left - (latestShown ? latest : 0);
        float column = Math.max(0, (room - headingWide) / 3f);
        boolean roomForHeading = column >= (latestShown ? shownCount : count);
        float start = left + (roomForHeading ? headingWide : 0);
        if (!roomForHeading) column = room / 3f;
        // The counts side by side as a key, not spread across the street.
        column = Math.min(column, count + ui.dp(14));
        for (int k = 0; k < 3; k++) keyBoxes[k].set(start + k * column, plotBottom(), start + (k + 1) * column,
                getHeight());
        label.setTypeface(null);
        return roomForHeading ? headingWide : 0;
    }

    private float keyBadge() {
        return Math.min(ui.dp(8), Math.max(ui.dp(4), (getHeight() - plotBottom()) / 3f));
    }

    /** At the key's end: which end of the street is newest. */
    static final String LATEST = "latest →";
    private boolean latestShown;

    /** The lines for these rules: the minimums at 100%, and Autopilot's bar, dashed, when it is not 100%. */
    void setRules(FilterSettings rules) {
        if (minimumScalePercent == rules.minimumScalePercent && areaMode == rules.scoreByArea) return;
        areaMode = rules.scoreByArea;
        minimumScalePercent = rules.minimumScalePercent;
        setContentDescription(describe(entries));
        invalidate();
    }

    /** A bar stands only for a score this version's rules worked out: never a retired one. */
    private static boolean tree(DecisionLog.Entry entry) {
        return entry.scorePercent >= 0 && entry.model >= 2;
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
        routed = routedIndex();
        setContentDescription(describe(ordered));
        invalidate();
    }

    /**
     * The stored route, by its offer's facts ({@link OfferSnapshot#fingerprint}; null: none): the newest shown offer
     * with those facts has a small road beside its flag, and its spoken entry ends "On a route." (0.6.3: the route's
     * own line under the header goes while its offer is in the skyline).
     */
    void setRoute(String fingerprint) {
        if (java.util.Objects.equals(fingerprint, routeFingerprint)) return;
        routeFingerprint = fingerprint;
        routed = routedIndex();
        setContentDescription(describe(entries));
        invalidate();
    }

    /** The newest shown offer that is the stored route's, -1 for none. */
    private int routedIndex() {
        if (routeFingerprint == null) return -1;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (routeFingerprint.equals(entries.get(i).facts.fingerprint())) return i;
        }
        return -1;
    }

    /** Which shown offer (oldest first, as drawn) is the stored route's: -1 for none (for tests). */
    int routeIndex() {
        return routed;
    }

    /**
     * Where the stored route's road stands once its building has risen, its middle in this view's pixels; null with
     * no route among the shown offers (for tests).
     */
    float[] routeMarkAt() {
        float[] flag = flagAt(routed);
        if (flag == null) return null;
        return new float[] {flag[0] + ui.dp(ROAD_MARK_RIGHT_DP), flag[1] + ui.dp(ROAD_MARK_DOWN_DP)};
    }

    DecisionLog.Entry selectedEntry() {
        return selected >= 0 && selected < entries.size() ? entries.get(selected) : null;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        // Asked with no limit (the page working out what fits one screen), it answers the least it reads well at.
        int height = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED ? ui.dp(FluidLayout.CHART_LEAST_DP)
                : resolveSize(ui.dp(160), heightSpec);
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), height);
    }

    @Override protected void onDraw(Canvas canvas) {
        float left = ui.dp(SIDE_DP);
        float right = getWidth() - ui.dp(SIDE_DP);
        float top = plotTop();
        float bottom = plotBottom();
        if (right <= left || bottom <= top) return;
        drawKey(canvas, left, right);
        fill.setColor(ui.gridline);
        rect.set(left, bottom, right, bottom + ui.dp(2));
        canvas.drawRoundRect(rect, ui.dp(1), ui.dp(1), fill);
        // No offer yet: the empty street and the key, no words (the verdict at the top says so already; screen readers
        // hear it from the description).
        if (entries.isEmpty()) return;

        long maxScore = scoreScaleMax();
        float slot = (right - left) / SLOTS;
        float barWidth = buildingWidth(slot);
        // The spotlight moving to the bar just tapped draws every frame; the rest is steady motion.
        float spotlight = Motion.settle(selectedAt, 300);
        int marked = badged();
        // 0.6.8: no spotlight beam (the owner's mockup): the badge alone marks the selected bar.
        // The lines under the bars, so a bar's top stays visible where it crosses one.
        drawThresholdLines(canvas, left, right);
        for (int i = 0; i < entries.size(); i++) {
            DecisionLog.Entry entry = entries.get(i);
            float center = buildingCenter(i, slot);
            Long since = risingSince.get(entry.at);
            float rise = Motion.settle(since == null ? 0 : since, RISE_MS);
            if (rise <= 0) continue;
            float roof;
            if (tree(entry)) {
                roof = bottom - (bottom - y(entry.scorePercent, maxScore, top, bottom)) * rise;
                drawBar(canvas, center - barWidth / 2f, roof, center + barWidth / 2f, bottom, barColor(entry));
                if (entry.scorePercent > SCORE_DISPLAY_CAP) {
                    line.setColor(barColor(entry));
                    line.setStrokeWidth(Math.max(1, ui.dp(1)));
                    float half = barWidth * 0.4f;
                    canvas.drawLine(center - half, roof - ui.dp(2), center, roof - ui.dp(4), line);
                    canvas.drawLine(center, roof - ui.dp(4), center + half, roof - ui.dp(2), line);
                }
            } else {
                roof = unreadTop(top, bottom);
                drawUnread(canvas, center - barWidth / 2f, roof, center + barWidth / 2f, bottom);
            }
            if (i != marked) continue;
            drawBadge(canvas, center, roof - ui.dp(12), DecisionLog.outcome(entry), i == selected);
            // The stored route's offer, when it is the marked one: a small road at its badge's lower right.
            if (i == routed) {
                float flag = Math.max(roof - ui.dp(12), ui.dp(10));
                OutcomeBadge.drawRoadPip(canvas, ui, center + ui.dp(ROAD_MARK_RIGHT_DP),
                        flag + ui.dp(ROAD_MARK_DOWN_DP), ui.dp(ROAD_MARK_DP), ui.ink);
            }
        }
        drawThresholdLabels(canvas, left, right);
        if (spotlight < 1) Motion.settling(this);
        else Motion.next(this);
    }

    /** The one offer with a badge: the selected one, else the newest. */
    private int badged() {
        return selected >= 0 && selected < entries.size() ? selected : entries.size() - 1;
    }

    /**
     * What happened, as the bar's colour: green passed at or above the minimums (or accepted), amber passed below them,
     * left for review or left to you, red filtered.
     */
    private int barColor(DecisionLog.Entry entry) {
        DecisionLog.Outcome outcome = DecisionLog.outcome(entry);
        if (outcome == DecisionLog.Outcome.DECLINED) return Ui.resultColor(OfferRule.Result.DECLINE);
        if (outcome == DecisionLog.Outcome.ACCEPTED) return Ui.resultColor(OfferRule.Result.KEEP);
        if (outcome == DecisionLog.Outcome.PASSED && !AutopilotText.passedBelowMinimums(entry)) {
            return Ui.resultColor(OfferRule.Result.KEEP);
        }
        return Ui.resultColor(OfferRule.Result.REVIEW);
    }

    /** Autopilot's purple for its bar's line. */
    private int barLineColor() {
        return ui.dark ? 0xFFC5B3FF : AutopilotChip.purple(ui);
    }

    /** A bar with a rounded top. */
    private void drawBar(Canvas canvas, float l, float t, float r, float b, int color) {
        float radius = Math.min(ui.dp(3), Math.min((r - l) / 2f, Math.max(0, b - t) / 2f));
        path.reset();
        rect.set(l, t, r, b);
        path.addRoundRect(rect, new float[] {radius, radius, radius, radius, 0, 0, 0, 0}, Path.Direction.CW);
        fill.setColor(color);
        canvas.drawPath(path, fill);
    }

    /** Where an unread score's hollow bar stands: a third of the way to 100%. */
    private float unreadTop(float top, float bottom) {
        return bottom - (bottom - y(100, scoreScaleMax(), top, bottom)) / 3f;
    }

    /** An offer whose score was not read: a hollow dashed bar with "?" over it. */
    private void drawUnread(Canvas canvas, float l, float t, float r, float b) {
        line.setColor(ui.inkSecondary);
        line.setStrokeWidth(Math.max(1, ui.dp(1)));
        line.setPathEffect(barDash);
        rect.set(l + line.getStrokeWidth() / 2, t, r - line.getStrokeWidth() / 2, b);
        canvas.drawRect(rect, line);
        line.setPathEffect(null);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(Math.min(ui.sp(10), ui.dp(13)));
        label.setColor(ui.inkSecondary);
        canvas.drawText("?", (l + r) / 2, t - ui.dp(2), label);
        label.setTextSize(ui.sp(11));
        label.setColor(ui.inkMuted);
    }

    private void drawThresholdLines(Canvas canvas, float left, float right) {
        line.setStrokeWidth(Math.max(1, ui.dp(1)));
        line.setStrokeCap(Paint.Cap.BUTT);
        line.setColor(ui.inkMuted);
        float hundred = hundredY();
        canvas.drawLine(left, hundred, right, hundred, line);
        if (minimumScalePercent != 100) {
            line.setColor(barLineColor());
            line.setPathEffect(scoreDash);
            canvas.drawLine(left, scoreThresholdY(), right, scoreThresholdY(), line);
            line.setPathEffect(null);
        }
    }

    /** "100%" at the right end, over the minimums' line; the bar's own line goes unnamed (Autopilot's chip says it). */
    private void drawThresholdLabels(Canvas canvas, float left, float right) {
        label.setTextAlign(Paint.Align.RIGHT);
        label.setTypeface(Ui.MEDIUM);
        label.setTextSize(Math.min(ui.sp(10), ui.dp(13)));
        label.setColor(ui.inkMuted);
        float baseline = hundredY() - ui.dp(3) - label.descent();
        float wide = label.measureText("100%");
        float textLeft = right - ui.dp(2) - wide;
        // A bar risen through the label (0.6.9): the label keeps a backdrop of the page so it stays readable.
        if (barReaches(textLeft - ui.dp(3), right, baseline + label.descent())) {
            fill.setColor((ui.page & 0x00FFFFFF) | 0xE0000000);
            float pad = ui.dp(3);
            canvas.drawRoundRect(textLeft - pad, baseline + label.ascent() - ui.dp(1), right - ui.dp(2) + pad,
                    baseline + label.descent() + ui.dp(1), pad * 2, pad * 2, fill);
        }
        canvas.drawText("100%", right - ui.dp(2), baseline, label);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(null);
        label.setTextSize(ui.sp(11));
    }

    /** Whether a bar standing between {@code from} and {@code to} across rises above {@code y}. */
    private boolean barReaches(float from, float to, float y) {
        if (entries.isEmpty()) return false;
        float slot = slotWidth();
        float half = buildingWidth(slot) / 2f;
        for (int i = 0; i < entries.size(); i++) {
            float center = buildingCenter(i, slot);
            if (center + half >= from && center - half <= to && roofAt(i) < y) return true;
        }
        return false;
    }

    /** The minimums' line: 100% on the scale. */
    float hundredY() {
        return y(100, scoreScaleMax(), plotTop(), plotBottom());
    }

    /**
     * The key under the bars: the dash's heading ("DASH", or "LAST" for the last one) and its three counts, each a
     * badge and a number, in one row; the counts' boxes are kept for taps.
     */
    private void drawKey(Canvas canvas, float left, float right) {
        float middle = (plotBottom() + getHeight()) / 2f + ui.dp(1);
        boolean none = keyLabel.startsWith("No");
        float headingWide = placeKey();
        float badge = keyBadge();
        if (headingWide > 0) {
            label.setTypeface(Ui.MEDIUM);
            label.setTextAlign(Paint.Align.LEFT);
            label.setTextSize(Math.min(ui.sp(10), ui.dp(13)));
            label.setLetterSpacing(0.12f);
            label.setColor(ui.inkMuted);
            canvas.drawText(keyLabel.startsWith("Last") ? "LAST" : "DASH", left + ui.dp(2),
                    middle - (label.ascent() + label.descent()) / 2, label);
            label.setLetterSpacing(0);
        }
        OfferRule.Result[] kinds = {OfferRule.Result.KEEP, OfferRule.Result.DECLINE, OfferRule.Result.REVIEW};
        for (int k = 0; k < 3; k++) {
            float x = keyBoxes[k].left;
            int color = Ui.resultColor(kinds[k]);
            fill.setColor(keyLive ? color : ((color & 0x00FFFFFF) | 0x60000000));
            canvas.drawCircle(x + badge + ui.dp(2), middle, badge, fill);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTextSize(Math.min(ui.dp(8), badge * 1.5f));
            label.setColor(Ui.onStatus(color));
            canvas.drawText(Ui.resultSymbol(kinds[k]), x + badge + ui.dp(2), middle + label.getTextSize() / 3, label);
            label.setTextAlign(Paint.Align.LEFT);
            label.setTypeface(Ui.MEDIUM);
            label.setTextSize(Math.min(ui.sp(14), ui.dp(17)));
            label.setColor(none ? ui.inkMuted : ui.ink);
            String number = none ? "–" : Integer.toString(keyCounts[k]);
            canvas.drawText(number, x + 2 * badge + ui.dp(5), middle - (label.ascent() + label.descent()) / 2, label);
        }
        if (latestShown && !entries.isEmpty()) {
            label.setTypeface(null);
            label.setTextAlign(Paint.Align.RIGHT);
            label.setTextSize(Math.min(ui.sp(11), ui.dp(14)));
            label.setColor(ui.inkMuted);
            canvas.drawText(LATEST, right, middle - (label.ascent() + label.descent()) / 2, label);
        }
        label.setTypeface(null);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(ui.sp(11));
        label.setColor(ui.inkMuted);
    }

    /** The badge ({@link OutcomeBadge}), kept whole inside the view however tall its bar. */
    private void drawBadge(Canvas canvas, float x, float y, DecisionLog.Outcome outcome, boolean selected) {
        if (selected) OutcomeBadge.draw(canvas, ui, x, Math.max(y, ui.dp(10)), outcome, 255);
        else OutcomeBadge.drawQuiet(canvas, ui, x, Math.max(y, ui.dp(10)), outcome);
    }

    /** The top of the offer's bar once risen (an unread score's hollow bar included). */
    private float roofAt(int index) {
        DecisionLog.Entry entry = entries.get(index);
        return tree(entry) ? y(entry.scorePercent, scoreScaleMax(), plotTop(), plotBottom())
                : unreadTop(plotTop(), plotBottom());
    }

    /**
     * Where the badge of the offer at {@code index} (oldest first, as drawn) stands once its bar has risen, in this
     * view's pixels; null where there is none (only the selected offer, else the newest, has one; for tests).
     */
    float[] flagAt(int index) {
        if (index < 0 || index >= entries.size() || getWidth() <= 0 || index != badged()) return null;
        return new float[] {buildingCenter(index, slotWidth()), Math.max(roofAt(index) - ui.dp(12), ui.dp(10))};
    }

    /** Settled score bar: center X, top Y, baseline Y and width. An unknown or retired score has none. */
    float[] treeAt(int index) {
        if (index < 0 || index >= entries.size() || getWidth() <= 0 || !tree(entries.get(index))) return null;
        float slot = slotWidth();
        return new float[] {buildingCenter(index, slot), roofAt(index), plotBottom(), buildingWidth(slot)};
    }

    boolean treeClippedAt(int index) {
        return index >= 0 && index < entries.size() && tree(entries.get(index))
                && entries.get(index).scorePercent > SCORE_DISPLAY_CAP;
    }

    /** The colour of the offer's bar (for tests); 0 where there is none. */
    int barColorAt(int index) {
        if (index < 0 || index >= entries.size() || !tree(entries.get(index))) return 0;
        return barColor(entries.get(index));
    }

    /** Whether the offer at {@code index} shows the hollow "?" bar of a score that was not read. */
    boolean unreadAt(int index) {
        return index >= 0 && index < entries.size() && !tree(entries.get(index));
    }

    int scoreThresholdPercent() { return minimumScalePercent; }
    float scoreThresholdY() { return y(minimumScalePercent, scoreScaleMax(), plotTop(), plotBottom()); }

    private float plotTop() { return Math.min(ui.dp(26), getHeight() * 0.40f); }
    private float plotBottom() {
        // The key grows with font size, while a compact chart still keeps its bars above it.
        return Math.max(plotTop(), getHeight() - Math.max(ui.dp(22), ui.sp(14) * 1.3f + ui.dp(4)));
    }
    private float slotWidth() { return Math.max(0, getWidth() - ui.dp(SIDE_DP * 2)) / (float) SLOTS; }
    private float buildingWidth(float slot) { return Math.max(1, Math.min(ui.dp(16), slot * 0.6f)); }
    private float buildingCenter(int index, float slot) {
        return ui.dp(SIDE_DP) + slot * (SLOTS - entries.size() + index + 0.5f);
    }

    /**
     * The highest point, in this view's pixels, that a bar, line or badge reaches once risen between {@code from} and
     * {@code to} across; the view's height where none stands.
     */
    float highestWithin(float from, float to) {
        float highest = getHeight();
        if (entries.isEmpty() || getWidth() <= 0) return highest;
        float left = ui.dp(SIDE_DP);
        float right = getWidth() - ui.dp(SIDE_DP);
        if (to < left || from > right) return highest;
        float slot = (right - left) / SLOTS;
        float barWidth = buildingWidth(slot);
        float reach = Math.max(barWidth / 2f, ui.dp(9));
        highest = Math.min(highest, Math.min(scoreThresholdY(), hundredY()) - ui.dp(1));
        // "100%" sits over the line at the right end.
        if (to >= right - ui.dp(30)) highest = Math.min(highest, hundredY() - ui.dp(3) - ui.sp(10) * 1.2f);
        int marked = badged();
        for (int i = 0; i < entries.size(); i++) {
            float center = buildingCenter(i, slot);
            float rightReach = i == routed
                    ? Math.max(reach, ui.dp(ROAD_MARK_RIGHT_DP) + ui.dp(ROAD_MARK_DP) / 2f + ui.dp(1)) : reach;
            if (center + rightReach < from || center - reach > to) continue;
            float roof = roofAt(i) - (treeClippedAt(i) ? ui.dp(5) : 0) - (unreadAt(i) ? ui.sp(10) * 1.2f : 0);
            highest = Math.min(highest, roof);
            if (i == marked) highest = Math.min(highest, Math.max(roofAt(i) - ui.dp(12), ui.dp(10)) - ui.dp(9));
        }
        return highest;
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
        if (event.getY() >= plotBottom()) {
            // The key's own tap: never the chart's click, which would open the selected offer over the count's.
            if (event.getAction() == MotionEvent.ACTION_UP) {
                placeKey();
                for (int k = 0; k < 3; k++) {
                    if (keyBoxes[k].contains(event.getX(), event.getY())) {
                        countTapped(DecisionLog.Tally.values()[k]);
                        break;
                    }
                }
            }
            return true;
        }
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

    /** The key's count for {@code tally} tapped (or chosen by a screen reader). */
    void countTapped(DecisionLog.Tally tally) {
        if (countListener != null) countListener.count(tally);
    }

    @Override public boolean performClick() {
        return super.performClick();
    }

    /** The skyline is also the history browser for screen readers, without drawing more controls. */
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (entries.isEmpty()) {
            for (int k = 0; k < COUNT_ACTIONS.length; k++) {
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(COUNT_ACTION_IDS[k], COUNT_ACTIONS[k]));
            }
            return;
        }
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
        // The key's counts, as the band's count buttons offered them before 0.6.6.
        for (int k = 0; k < COUNT_ACTIONS.length; k++) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(COUNT_ACTION_IDS[k], COUNT_ACTIONS[k]));
        }
    }

    /** The key's counts' actions for screen readers, in {@link DecisionLog.Tally}'s order. */
    static final String[] COUNT_ACTIONS = {
            "Show latest passed offer", "Show latest filtered offer", "Show latest offer left to review"};
    static final int[] COUNT_ACTION_IDS = {0x01040001, 0x01040002, 0x01040003};

    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        for (int k = 0; k < COUNT_ACTION_IDS.length; k++) {
            if (action == COUNT_ACTION_IDS[k]) {
                countTapped(DecisionLog.Tally.values()[k]);
                return true;
            }
        }
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
     * "Chart of the last 5 offers: 2 passed (1 below your minimums), 1 accepted, 1 declined, 1 left to you, 1 need
     * review.": as the flags say, what became of each offer; accepted, left to you and passes only by Autopilot's
     * lowered bar only when there are any.
     */
    private String describe(List<DecisionLog.Entry> entries) {
        if (entries.isEmpty()) return EMPTY_SAID + " " + keySaid();
        int[] counts = new int[DecisionLog.Outcome.values().length];
        int below = 0;
        for (DecisionLog.Entry entry : entries) {
            DecisionLog.Outcome outcome = DecisionLog.outcome(entry);
            counts[outcome.ordinal()]++;
            if (outcome == DecisionLog.Outcome.PASSED && AutopilotText.passedBelowMinimums(entry)) below++;
        }
        int accepted = counts[DecisionLog.Outcome.ACCEPTED.ordinal()];
        int yours = counts[DecisionLog.Outcome.YOURS.ordinal()];
        int requested = counts[DecisionLog.Outcome.REQUESTED.ordinal()];
        String summary = String.format(Locale.US,
                "Chart of the last %d offers: %d passed%s, %s%d declined, %s%d need review.",
                entries.size(), counts[DecisionLog.Outcome.PASSED.ordinal()],
                below > 0 ? " (" + below + " below your minimums)" : "",
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
        return summary + selectedDescription() + " " + AutopilotText.SKYLINE_DESCRIPTION
                + (minimumScalePercent != 100 ? " Dashed line: " + AutopilotText.skylineBarRail(minimumScalePercent)
                + "." : "")
                + (unknown > 0 ? " " + unknown + " recorded scores unavailable: hollow bars." : "")
                + (retired > 0 ? " " + retired + " scores from earlier rules: hollow bars." : "")
                + (clipped > 0 ? " " + clipped + " bars exceed " + SCORE_DISPLAY_CAP
                + "% and end in an overflow chevron; tap for the recorded score." : "")
                + " " + keySaid();
    }

    private String selectedDescription() {
        DecisionLog.Entry entry = selectedEntry();
        if (entry == null) return "";
        DecisionLog.Outcome outcome = DecisionLog.outcome(entry);
        // Passed only by Autopilot's lowered bar: said so, never as a full pass.
        String state = outcome == DecisionLog.Outcome.YOURS ? "left to you"
                : outcome == DecisionLog.Outcome.PASSED && AutopilotText.passedBelowMinimums(entry)
                ? AutopilotText.PASSED_BELOW_MINIMUMS.toLowerCase(Locale.US)
                : outcome.name().toLowerCase(Locale.US);
        return " Selected offer " + (selected + 1) + " of " + entries.size() + ": "
                + (entry.facts.payCents == null ? "payout unknown" : DecisionLog.money(entry.facts.payCents))
                + ", " + DecisionLog.facts(entry.facts) + ", " + state
                + (tree(entry) ? entry.model >= 3 && "area".equals(entry.scoreKind)
                        ? ", recorded area score " + entry.scorePercent + "%."
                        : ", score " + entry.scorePercent + "% of your minimums."
                : entry.scorePercent >= 0 ? ", score from earlier area rules." : ", score unknown.")
                + (selected == routed ? " On a route." : "");
    }
}
