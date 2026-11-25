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
 * Recent offers as a little skyline, oldest left (the owner, 14 October 2026: "i miss the building/tree skyline motif
 * for the chart"; on the card he chose B, "as before" 0.6.6): each offer is a building as tall as the pay read, in
 * dollars, with a slim tree beside it as tall as its recorded score (percent of the minimums, the number the filter
 * decides with). Dollars and percent have scales of their own and share the street as zero. A building's colour is what
 * actually happened ({@link DecisionLog#outcome}): green passed (or accepted), amber passed below the minimums or left
 * for review (or to you), red filtered; passed offers have their windows lit. The solid tan line is the top pay among
 * the offers shown, level with the tallest building ("Top $17.60"; 0.6.67, before it what pay per stop asked of a
 * single order at the bar); the dashed line in the trees' green is the minimums ("Mins 100%"); and a dashed purple line
 * Autopilot's bar, named with what holds it ("Platinum bar 37%"; from 0.6.64 today's bar alone, flat across the chart;
 * from 0.6.40 to 0.6.63 the bar each offer was judged at, stepping where it moved, with a word or two at its newest
 * steps on why). The marked offer's tree says its score at its tip ("81%"). An offer whose pay was not read is a
 * hollow dashed building with "?"; a score not read (or from the retired rules) has no tree, an open ring at its foot
 * instead. From 0.6.69 a solid blue line runs over them all: pay per active hour of your last accepts as of each offer
 * ({@link ActiveHourPay}), on a dollars-an-hour scale of its own from the street, a dot where an accept moved it, and
 * named at its newest level ("$31/active hr").
 * One badge marks the selected offer (the newest, unless another was chosen), on its roof, and the stored route's offer
 * (0.6.3, {@link #setRoute}) has a small road beside that badge. Under the street, the dash's counts are the chart's
 * key ("DASH ✓ 1 ✕ 0 ? 1"), and a tap on one opens its latest offer ({@link OnCount}). Tapping a building or its tree
 * selects it and reports it to the listener; an offer picked on the constellation is selected the same way
 * ({@link #choose}). Buildings and trees rise together; with Android's animations off they rest. From 0.6.16 the
 * buildings and the trees' crowns are the website's in the round ({@link Art}: a building's roof and a slice of its
 * front stretched down to the street, a crown stretched to its tree), flat until their pictures are ready; the windows
 * stay drawn, all of a frame's together.
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
    /** Pay above $1,000 is drawn at the top of the scale. */
    private static final long SCALE_CAP_CENTS = 100_000;
    private static final long[] NICE_DOLLARS = {10, 15, 20, 25, 30, 40, 50, 60, 80, 100, 150, 200, 300, 500, 1000};

    private final Ui ui;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DashPathEffect scoreDash;
    private final RectF rect = new RectF();
    private final Path path = new Path();
    /** A flat building's corners: its roof's two rounded, its foot's two square. */
    private final float[] roofRadii = new float[8];
    /** This frame's windows, lit, half-lit and dark, as the middles of squares drawn together after the buildings. */
    private final float[][] windows = {new float[96], new float[96], new float[96]};
    private final int[] windowFloats = new int[3];
    private float windowSide;
    private final Paint windowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final int[] WINDOW_COLORS = {0xCCFFE9A3, 0x70FFFFFF, 0x28000000};
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
    private int minimumScalePercent = 100;
    /** Autopilot is on now: after the newest offer, the bar line runs on at today's bar (0.6.40). */
    private boolean autopilotNow;
    /** The bar's kept changes, oldest first ({@link AutopilotStore#changes}): the words where the bar line steps. */
    private List<AutopilotStore.Change> barChanges = Collections.emptyList();
    /** The bar line's words as last drawn, for the description and tests: "accepted", "hold over", … */
    private final List<String> barWords = new ArrayList<>();
    /** Where those words were drawn, backdrops and all (for {@link #highestWithin}). */
    private final List<RectF> barWordBoxes = new ArrayList<>();
    /**
     * Where the lines' names and the marked offer's score were last drawn, backdrops and all (0.6.67, for
     * {@link #highestWithin}).
     */
    private final List<RectF> labelBoxes = new ArrayList<>();
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
        windowPaint.setStrokeCap(Paint.Cap.SQUARE);
        setClickable(true);
        setFocusable(true);
        Art.watch(this);
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

    /**
     * The lines for these rules: the minimums at 100% and Autopilot's bar, dashed, when it is not 100%. The tan line is
     * the top pay among the offers shown (0.6.67, {@link #setEntries}), not these rules'.
     */
    void setRules(FilterSettings rules) {
        boolean sameFrame = currentRules != null && currentRules.scoringKey().equals(rules.scoringKey());
        currentRules = rules;
        if (sameFrame && minimumScalePercent == rules.minimumScalePercent
                && independentAutopilot == rules.hasAutopilotAxes() && autopilotNow == rules.autopilot) {
            return;
        }
        independentAutopilot = rules.hasAutopilotAxes();
        autopilotNow = rules.autopilot;
        minimumScalePercent = rules.minimumScalePercent;
        setContentDescription(describe(entries));
        invalidate();
    }

    /**
     * What holds Autopilot's bar where it is, in a word ({@link AutopilotText#barLineWord}: "Platinum", "Accept" …;
     * null when not known), for its line's name: "Platinum bar 37%" (0.6.67; the owner, 22 November 2026: "I am
     * platinum and it is not obvious to me at a glance of the chart").
     */
    void setBarReason(String reason) {
        if (java.util.Objects.equals(reason, barReason)) return;
        barReason = reason;
        setContentDescription(describe(entries));
        invalidate();
    }

    /** The bar's kept changes, oldest first ({@link AutopilotStore#changes}), for the words on the bar line. */
    void setBarChanges(List<AutopilotStore.Change> oldestFirst) {
        List<AutopilotStore.Change> next = oldestFirst == null ? Collections.<AutopilotStore.Change>emptyList()
                : oldestFirst;
        if (sameChanges(next, barChanges)) return;
        barChanges = new ArrayList<>(next);
        setContentDescription(describe(entries));
        invalidate();
    }

    private static boolean sameChanges(List<AutopilotStore.Change> a, List<AutopilotStore.Change> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            AutopilotStore.Change x = a.get(i), y = b.get(i);
            if (x.at != y.at || x.from != y.from || x.to != y.to || !x.why.equals(y.why) || x.released != y.released) {
                return false;
            }
        }
        return true;
    }

    private boolean independentAutopilot;
    /**
     * The top pay among the offers shown, in cents, where the tan line stands (0.6.67; the owner, 22 November 2026:
     * "tan line should show highest paid offer on graph"; before, what pay per stop asked of a 2-stop order at the
     * bar); 0 with no pay read.
     */
    private long topPayCents;
    /** What holds Autopilot's bar where it is, in a word ({@link #setBarReason}); null when not known. */
    private String barReason;
    /**
     * Pay per active hour as of each shown offer, oldest first ({@link ActiveHourPay#rolling}; 0.6.69): the blue line.
     */
    private ActiveHourPay.Point[] rates = new ActiveHourPay.Point[0];
    /** The minimums' line's name (0.6.67; "100%" before). */
    static final String MINS_LABEL = "Mins 100%";

    /** Your current rules (0.6.64): every tree is scored against them, so trees from different times line up. */
    private FilterSettings currentRules;

    /** A tree stands only for a score this version's rules worked out: never a retired one. */
    private boolean tree(DecisionLog.Entry entry) {
        return entry.model >= 2 && treeScore(entry) >= 0;
    }

    /**
     * How tall an offer's tree stands (0.6.64; the owner, 20 November 2026: "I just want the graph to accurately
     * reflect min score"): its score against your minimums now, so a tree above the bar's line would pass now and one
     * below would not. The ticket keeps the score it was decided by. An add-on, or a line without pay, keeps its own.
     */
    int treeScore(DecisionLog.Entry entry) {
        int now = AutopilotText.scoreNow(entry, currentRules);
        return now >= 0 ? now : entry.scorePercent;
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
        long top = 0;
        for (DecisionLog.Entry entry : ordered) {
            if (entry.facts.payCents != null) top = Math.max(top, entry.facts.payCents);
        }
        topPayCents = top;
        // The rolling rate as of each shown offer, its window reaching into the older history too.
        rates = ActiveHourPay.rolling(newestFirst, ordered.size());
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

        long maxCents = scaleMax();
        long maxScore = scoreScaleMax();
        float slot = (right - left) / SLOTS;
        float barWidth = buildingWidth(slot);
        float treeWidth = treeWidth(slot);
        float spotlight = Motion.settle(selectedAt, 300);
        int marked = badged();
        float markedRoof = Float.NaN;
        float markedRise = 0;
        // 0.6.8: no spotlight beam: the badge alone marks the selected building.
        // The lines under the buildings and trees, so a roof or a tip stays visible where it crosses one.
        drawThresholdLines(canvas, left, right);
        for (int i = 0; i < entries.size(); i++) {
            DecisionLog.Entry entry = entries.get(i);
            float center = buildingCenter(i, slot);
            float treeCenter = treeCenter(i, slot);
            Long since = risingSince.get(entry.at);
            float rise = Motion.settle(since == null ? 0 : since, RISE_MS);
            if (rise <= 0) continue;
            float roof;
            if (entry.facts.payCents != null) {
                roof = bottom - (bottom - y(entry.facts.payCents, maxCents, top, bottom)) * rise;
                drawBuilding(canvas, center - barWidth / 2f, roof, center + barWidth / 2f, bottom, entry);
            } else {
                roof = unreadTop(top, bottom);
                drawUnread(canvas, center - barWidth / 2f, roof, center + barWidth / 2f, bottom);
            }
            if (tree(entry)) {
                float tip = bottom - (bottom - y(treeScore(entry), maxScore, top, bottom)) * rise;
                drawTree(canvas, treeCenter, tip, bottom, treeWidth);
                if (treeScore(entry) > SCORE_DISPLAY_CAP) {
                    line.setColor(treeColor());
                    line.setStrokeWidth(Math.max(1, ui.dp(1)));
                    float half = treeWidth * 0.4f;
                    canvas.drawLine(treeCenter - half, tip - ui.dp(2), treeCenter, tip - ui.dp(4), line);
                    canvas.drawLine(treeCenter, tip - ui.dp(4), treeCenter + half, tip - ui.dp(2), line);
                }
            } else {
                // Unavailable (or from the retired rules) and measured zero are distinct: an open ring at the foot.
                line.setColor(ui.inkSecondary);
                line.setStrokeWidth(Math.max(1, ui.dp(1)));
                canvas.drawCircle(treeCenter, bottom - ui.dp(2), Math.min(ui.dp(2), treeWidth / 3f), line);
            }
            if (i == marked) {
                markedRoof = roof;
                markedRise = rise;
            }
        }
        drawWindows(canvas);
        // Pay per active hour over the buildings and trees (0.6.69).
        drawRateLine(canvas, left, right);
        // Autopilot's bar over the street (0.6.40): its level is what each tree is weighed against.
        List<BarRun> runs = barRuns();
        drawBarLine(canvas, runs);
        // The lines' words, then the badge over everything, so no label's backdrop covers it.
        float badgeX = Float.isNaN(markedRoof) ? Float.NaN : buildingCenter(marked, slot);
        // Where it stands over its settled roof, kept as the building rises, so it never jumps mid-rise.
        float[] flag = Float.isNaN(markedRoof) ? null : flagAt(marked);
        float badgeY = flag == null ? Float.NaN : Math.max(markedRoof + flag[1] - roofAt(marked), ui.dp(10));
        drawThresholdLabels(canvas, left, right, badgeX, badgeY, marked, markedRise >= 1f);
        drawBarWords(canvas, runs);
        if (!Float.isNaN(markedRoof)) {
            drawBadge(canvas, badgeX, badgeY, DecisionLog.outcome(entries.get(marked)), marked == selected);
            // The stored route's offer, when it is the marked one: a small road at its badge's lower right.
            if (marked == routed) {
                OutcomeBadge.drawRoadPip(canvas, ui, badgeX + ui.dp(ROAD_MARK_RIGHT_DP),
                        badgeY + ui.dp(ROAD_MARK_DOWN_DP), ui.dp(ROAD_MARK_DP), ui.ink);
            }
        }
        if (spotlight < 1) Motion.settling(this);
        else Motion.next(this);
    }

    /** The one offer with a badge: the selected one, else the newest. */
    private int badged() {
        return selected >= 0 && selected < entries.size() ? selected : entries.size() - 1;
    }

    /**
     * Where the marked offer's badge stands: 12 dp over its roof at {@code centerX}; but where the badge ({@code full}:
     * the selected offer's, else the quiet dot), or the stored route's road at its lower right ({@code road}), would
     * cover its own tree's tip ({@code tipX}, {@code tip}; NaN for no tree), it moves the shorter way clear of it: up
     * over the tip, or down onto its roof under it (0.6.67: the owner's 81% tree went up under its badge and road and
     * read as reaching 100%; lifted there, the badge would have sat on the 100% line instead). Never above the view's
     * top margin, nor down past the street.
     */
    private float badgeCenterY(float centerX, float roof, float tipX, float tip, boolean full, boolean road) {
        float y = Math.max(roof - ui.dp(12), ui.dp(10));
        if (Float.isNaN(tip)) return y;
        float reach = full ? ui.dp(9) : ui.dp(4.5f);
        // How far above (negative) and below the badge's middle the shapes over the tree's column reach.
        float high = Float.NaN, low = Float.NaN;
        if (Math.abs(tipX - centerX) < reach + ui.dp(1)) {
            high = -reach;
            low = reach;
        }
        if (road) {
            float half = ui.dp(ROAD_MARK_DP) / 2f + ui.dp(1);
            if (Math.abs(tipX - centerX - ui.dp(ROAD_MARK_RIGHT_DP)) < half + ui.dp(1)) {
                float roadHigh = ui.dp(ROAD_MARK_DOWN_DP) - half;
                float roadLow = ui.dp(ROAD_MARK_DOWN_DP) + half;
                high = Float.isNaN(high) ? roadHigh : Math.min(high, roadHigh);
                low = Float.isNaN(low) ? roadLow : Math.max(low, roadLow);
            }
        }
        float gap = ui.dp(2);
        if (Float.isNaN(low) || tip <= y + high - gap || tip >= y + low + gap) return y;
        float up = tip - low - gap;
        float down = tip - high + gap;
        boolean upFits = up >= ui.dp(10);
        boolean downFits = down + low <= plotBottom();
        if (upFits && (!downFits || y - up <= down - y)) return up;
        return downFits ? down : y;
    }

    /**
     * What happened, in three groups: green passed at or above the minimums (or accepted), amber passed below them, left
     * for review or left to you, red filtered (the strong colour; {@link #facadeColor} mutes it for a building).
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

    /**
     * The offer's building, as 0.6.3 drew it: a rounded roof and two columns of windows, its colour what happened
     * (muted, as the landscape's), its windows lit when the offer passed or was accepted, half-lit when left for review
     * (or to you), dark when filtered. From 0.6.16 in the round ({@link #drawRoundBuilding}) where its picture is ready.
     * Its windows are kept for {@link #drawWindows}.
     */
    private void drawBuilding(Canvas canvas, float l, float t, float r, float b, DecisionLog.Entry entry) {
        int group = barColor(entry);
        if (!drawRoundBuilding(canvas, l, t, r, b, group)) {
            float radius = Math.min(ui.dp(4), Math.min((r - l) / 2f, Math.max(0, b - t) / 2f));
            for (int i = 0; i < 4; i++) roofRadii[i] = radius;
            path.reset();
            rect.set(l, t, r, b);
            path.addRoundRect(rect, roofRadii, Path.Direction.CW);
            fill.setColor(facadeColor(group));
            canvas.drawPath(path, fill);
        }
        float window = Math.max(ui.dp(2), Math.min(ui.dp(4), (r - l) / 6f));
        int lit = group == Ui.resultColor(OfferRule.Result.KEEP) ? 0
                : group == Ui.resultColor(OfferRule.Result.DECLINE) ? 2 : 1;
        float gap = window * 1.2f;
        windowSide = window;
        for (float y = t + ui.dp(6); y + window <= b - ui.dp(4); y += window + gap) {
            addWindow(lit, l + (r - l) * 0.3f, y + window / 2f);
            addWindow(lit, l + (r - l) * 0.7f, y + window / 2f);
        }
    }

    /**
     * The building in the round (0.6.16): the website's rounded block, its front the building's colour, seen a little
     * from above so its roof shows; its roof's picture at the building's width and a slice of its front stretched
     * down to the street (a low building's roof cut at the street). False, drawing nothing, until it is ready.
     */
    private boolean drawRoundBuilding(Canvas canvas, float l, float t, float r, float b, int group) {
        Art.Sprite block = group == Ui.resultColor(OfferRule.Result.KEEP)
                ? (ui.dark ? ArtSprites.BLOCK_KEEP_NIGHT : ArtSprites.BLOCK_KEEP)
                : group == Ui.resultColor(OfferRule.Result.DECLINE)
                ? (ui.dark ? ArtSprites.BLOCK_DECLINE_NIGHT : ArtSprites.BLOCK_DECLINE)
                : ui.dark ? ArtSprites.BLOCK_REVIEW_NIGHT : ArtSprites.BLOCK_REVIEW;
        if (b <= t || !Art.ready(ui, block)) return false;
        float f = (r - l) / (block.pointX(Art.RIGHT) - block.pointX(Art.LEFT));
        float left = l - block.pointX(Art.LEFT) * f;
        float right = left + block.w * f;
        float top = t - block.y * f;
        float capEnd = t + (block.pointY(Art.CAP_END) - block.y) * f;
        if (b <= capEnd) return Art.drawRows(ui, canvas, block, 0, block.y + (b - t) / f, left, top, right, b, null);
        Art.drawRows(ui, canvas, block, 0, block.pointY(Art.CAP_END), left, top, right, capEnd, null);
        float slice = block.pointY(Art.SLICE);
        return Art.drawRows(ui, canvas, block, slice - 1, slice + 1, left, capEnd, right, b, null);
    }

    /** Keeps a window's middle for {@link #drawWindows}: {@code lit} 0 lit, 1 half-lit, 2 dark. */
    private void addWindow(int lit, float x, float y) {
        float[] points = windows[lit];
        int used = windowFloats[lit];
        if (used + 2 > points.length) {
            points = java.util.Arrays.copyOf(points, points.length * 2);
            windows[lit] = points;
        }
        points[used] = x;
        points[used + 1] = y;
        windowFloats[lit] = used + 2;
    }

    /**
     * Every building's windows, as squares the buildings gave their middles for: one draw for each of lit, half-lit
     * and dark, where each window was one of its own (no building or tree stands over another's windows).
     */
    private void drawWindows(Canvas canvas) {
        windowPaint.setStrokeWidth(windowSide);
        for (int lit = 0; lit < windows.length; lit++) {
            if (windowFloats[lit] == 0) continue;
            windowPaint.setColor(WINDOW_COLORS[lit]);
            canvas.drawPoints(windows[lit], 0, windowFloats[lit], windowPaint);
            windowFloats[lit] = 0;
        }
    }

    /** A group's colour as a building of the landscape: muted, as 0.6.3 muted them. */
    private int facadeColor(int group) {
        if (group == Ui.resultColor(OfferRule.Result.KEEP)) return ui.dark ? 0xFF557B63 : 0xFF78927D;
        if (group == Ui.resultColor(OfferRule.Result.DECLINE)) return ui.dark ? 0xFF87645E : 0xFFAA8C81;
        return ui.dark ? 0xFF998454 : 0xFFBDAC85;
    }

    private int treeColor() { return ui.dark ? 0xFF9DD6B6 : 0xFF32664F; }
    private int payoutColor() { return ui.dark ? 0xFFF1C58B : 0xFF865423; }

    /**
     * A slim evergreen: its very tip is the score, its trunk ends at the street. From 0.6.16 its crown is the website's
     * in the round, stretched to the tree, where its picture is ready.
     */
    private void drawTree(Canvas canvas, float x, float tip, float bottom, float width) {
        float height = bottom - tip;
        line.setColor(treeColor());
        line.setStrokeWidth(Math.max(1, Math.min(ui.dp(1.5f), width / 4f)));
        line.setStrokeCap(Paint.Cap.ROUND);
        if (height <= 0) {
            canvas.drawLine(x - width / 3f, bottom, x + width / 3f, bottom, line);
            line.setStrokeCap(Paint.Cap.BUTT);
            return;
        }
        float crown = height * 0.84f;
        Art.Sprite pine = ui.dark ? ArtSprites.PINE_NIGHT : ArtSprites.PINE;
        if (Art.ready(ui, pine)) {
            // The trunk from inside the crown, then the crown: its tip at the score, its foot as wide as the tree.
            canvas.drawLine(x, tip + crown * 0.6f, x, bottom, line);
            line.setStrokeCap(Paint.Cap.BUTT);
            float across = width / (pine.pointX(Art.FOOT_RIGHT) - pine.pointX(Art.FOOT_LEFT));
            float down = crown / (pine.pointY(Art.FOOT) - pine.y);
            Art.drawInto(ui, canvas, pine, x - pine.x * across, tip - pine.y * down, x + (pine.w - pine.x) * across,
                    tip + (pine.h - pine.y) * down, null);
            return;
        }
        canvas.drawLine(x, tip, x, bottom, line);
        line.setStrokeCap(Paint.Cap.BUTT);
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

    /** Where an unread pay's hollow building stands: a third of the way to 100%. */
    private float unreadTop(float top, float bottom) {
        return bottom - (bottom - y(100, scoreScaleMax(), top, bottom)) / 3f;
    }

    /** An offer whose pay was not read: a hollow dashed building with "?" over it. */
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
        if (topPayCents > 0) {
            line.setColor(payoutColor());
            canvas.drawLine(left, topPayY(), right, topPayY(), line);
        }
        line.setColor(treeColor());
        line.setPathEffect(scoreDash);
        float hundred = hundredY();
        canvas.drawLine(left, hundred, right, hundred, line);
        line.setPathEffect(null);
    }

    /** Dollars-an-hour ceilings for the blue line's scale ({@link #rateScaleMax}). */
    private static final long[] RATE_DOLLARS = {20, 25, 30, 40, 50, 60, 80, 100, 150, 200, 300, 500, 1000};
    /** The radius of the blue line's dots, where an accept moved it; its name keeps this much clear of the line too. */
    private static final float RATE_DOT_DP = 2;
    /** The blue line's points as last laid out ({@link #layOutRates}), left to right, its ends at the chart's edges. */
    private float[] rateXs = new float[0];
    private float[] rateYs = new float[0];
    private int ratePoints;

    /** The blue line's colour: the links' blue, apart from the trees' green, the tan line and Autopilot's purple. */
    private int rateColor() {
        return ui.link;
    }

    /** The newest shown offer's pay per active hour; {@link ActiveHourPay.Point#NONE} before any accept. */
    private ActiveHourPay.Point newestRate() {
        ActiveHourPay.Point point = rates.length == 0 ? null : rates[rates.length - 1];
        return point == null ? ActiveHourPay.Point.NONE : point;
    }

    /**
     * The blue line's scale, in cents an hour: the smallest of {@link #RATE_DOLLARS} at or above half again the highest
     * rate shown, so the line stays in the lower two thirds, clear of the badge and the lines' names along the top.
     */
    private long rateScaleMax() {
        long max = 0;
        for (ActiveHourPay.Point point : rates) {
            if (point != null && point.known()) max = Math.max(max, point.centsPerHour);
        }
        long want = Math.max(1, max + max / 2);
        for (long dollars : RATE_DOLLARS) if (dollars * 100 >= want) return dollars * 100;
        return want;
    }

    /**
     * Lays the blue line out between {@code left} and {@code right} ({@link #rateXs}, {@link #rateYs}): from the first
     * shown offer with a rate (from the chart's left edge when that is the oldest shown) through each offer's, between
     * its building and its tree, then on to the right edge at the newest rate. False, with nothing laid out, before any
     * accept is known.
     */
    private boolean layOutRates(float left, float right) {
        ratePoints = 0;
        int count = Math.min(entries.size(), rates.length);
        if (count == 0 || getWidth() <= 0 || right <= left) return false;
        int first = -1;
        for (int i = 0; i < count && first < 0; i++) if (rates[i] != null && rates[i].known()) first = i;
        if (first < 0) return false;
        if (rateXs.length < count + 2) {
            rateXs = new float[count + 2];
            rateYs = new float[count + 2];
        }
        long scale = rateScaleMax();
        float slot = slotWidth();
        float top = plotTop(), bottom = plotBottom();
        float lastY = Float.NaN;
        for (int i = first; i < count; i++) {
            if (rates[i] == null || !rates[i].known()) continue;
            lastY = y(rates[i].centsPerHour, scale, top, bottom);
            if (ratePoints == 0 && i == 0) addRatePoint(left, lastY);
            addRatePoint(rateCenter(i, slot), lastY);
        }
        addRatePoint(right, lastY);
        return true;
    }

    private void addRatePoint(float x, float y) {
        rateXs[ratePoints] = x;
        rateYs[ratePoints] = y;
        ratePoints++;
    }

    /** Where an offer's point on the blue line stands across: between its building and its tree. */
    private float rateCenter(int index, float slot) {
        return ui.dp(SIDE_DP) + slot * (SLOTS - entries.size() + index + 0.58f);
    }

    /**
     * Pay per active hour, a solid blue line over the buildings and trees (0.6.69), with a dot where an accept moved it
     * ({@link ActiveHourPay.Point#moved}).
     */
    private void drawRateLine(Canvas canvas, float left, float right) {
        if (!layOutRates(left, right)) return;
        line.setColor(rateColor());
        line.setStrokeWidth(Math.max(1, ui.dp(2)));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        path.reset();
        path.moveTo(rateXs[0], rateYs[0]);
        for (int k = 1; k < ratePoints; k++) path.lineTo(rateXs[k], rateYs[k]);
        canvas.drawPath(path, line);
        line.setStrokeJoin(Paint.Join.MITER);
        line.setStrokeCap(Paint.Cap.BUTT);
        fill.setColor(rateColor());
        long scale = rateScaleMax();
        float slot = slotWidth();
        for (int i = 0; i < Math.min(entries.size(), rates.length); i++) {
            ActiveHourPay.Point point = rates[i];
            if (point == null || !point.known() || !point.moved) continue;
            canvas.drawCircle(rateCenter(i, slot), y(point.centsPerHour, scale, plotTop(), plotBottom()),
                    ui.dp(RATE_DOT_DP), fill);
        }
    }

    /** The blue line's height at {@code x} across, as last laid out; NaN off its ends or with none. */
    private float rateYAt(float x) {
        if (ratePoints == 0 || x < rateXs[0] || x > rateXs[ratePoints - 1]) return Float.NaN;
        for (int k = 1; k < ratePoints; k++) {
            if (x <= rateXs[k]) {
                float span = rateXs[k] - rateXs[k - 1];
                float t = span <= 0 ? 1 : (x - rateXs[k - 1]) / span;
                return rateYs[k - 1] + (rateYs[k] - rateYs[k - 1]) * t;
            }
        }
        return rateYs[ratePoints - 1];
    }

    /**
     * The blue line's highest point ({@code highest}: the least y) or its lowest between {@code from} and {@code to}
     * across, as last laid out; NaN where it does not run there.
     */
    private float rateWithin(float from, float to, boolean highest) {
        if (ratePoints == 0) return Float.NaN;
        float a = Math.max(from, rateXs[0]);
        float b = Math.min(to, rateXs[ratePoints - 1]);
        if (a > b) return Float.NaN;
        float atA = rateYAt(a), atB = rateYAt(b);
        float best = highest ? Math.min(atA, atB) : Math.max(atA, atB);
        for (int k = 0; k < ratePoints; k++) {
            if (rateXs[k] < a || rateXs[k] > b) continue;
            best = highest ? Math.min(best, rateYs[k]) : Math.max(best, rateYs[k]);
        }
        return best;
    }

    /**
     * Whether the blue line, its dots included, passes through the box from {@code l} to {@code r} across and from
     * {@code t} to {@code b} down.
     */
    private boolean rateRunsThrough(float l, float r, float t, float b) {
        float high = rateWithin(l, r, true);
        if (Float.isNaN(high)) return false;
        float low = rateWithin(l, r, false);
        float reach = ui.dp(RATE_DOT_DP);
        return high - reach <= b && low + reach >= t;
    }

    /**
     * The blue line's name at its newest level ({@link ActiveHourPay#label}, "$31/active hr"): at the right end over the
     * line, else under it, then sliding inward past what is in the way, over (or under) every point of its own line
     * beneath it, never where another of {@code lines} runs through it; left out where nothing is clear (screen readers
     * still hear it). Placed last, after the other lines' names.
     */
    private void placeRateLabel(Canvas canvas, float left, float right, List<RectF> taken, float[] lines) {
        ActiveHourPay.Point newest = newestRate();
        if (ratePoints == 0 || !newest.known()) return;
        String text = ActiveHourPay.label(newest.centsPerHour);
        useLineLabelPaint();
        float wide = label.measureText(text);
        for (int tries = 0; tries < 4; tries++) {
            boolean under = tries % 2 == 1;
            int steps = tries < 2 ? 1 : 6;
            float x = right - ui.dp(2);
            for (int step = 0; step < steps; step++) {
                float edge = rateWithin(x - wide - ui.dp(3), x + ui.dp(3), !under);
                if (Float.isNaN(edge)) break;
                float lineY = under ? edge + ui.dp(RATE_DOT_DP) : edge - ui.dp(RATE_DOT_DP);
                RectF box = lineLabelBox(text, x, lineY, true, under);
                if (box.left < left - ui.dp(2) || box.right > right + ui.dp(2) || box.top < 0
                        || box.bottom > plotBottom() + ui.dp(2)) {
                    break;
                }
                boolean crossed = false;
                for (float other : lines) crossed |= crosses(box, other);
                if (crossed) break;
                RectF hit = firstHit(box, taken);
                if (hit == null) {
                    taken.add(box);
                    labelBoxes.add(box);
                    rateLabelShown = text;
                    drawLineLabel(canvas, text, x, lineY, true, under, rateColor());
                    return;
                }
                x = hit.left - ui.dp(4);
            }
        }
    }

    /** The blue line's name as last drawn ("$31/active hr"), null where it was left out (for tests). */
    private String rateLabelShown;

    String rateLabelShown() {
        return rateLabelShown;
    }

    /**
     * The blue line said: "pay per active hour of the offers you accepted, by Dasher's time estimates, over your last 10
     * accepts: $31.40 an hour as of the newest offer, from $28.10 at the oldest shown"; null before any accept.
     */
    private String rateSaid() {
        ActiveHourPay.Point newest = newestRate();
        if (!newest.known()) return null;
        ActiveHourPay.Point oldest = null;
        for (ActiveHourPay.Point point : rates) {
            if (point != null && point.known()) {
                oldest = point;
                break;
            }
        }
        String said = "pay per active hour of the offers you accepted, by Dasher's time estimates, "
                + (newest.accepts == 1 ? "over your last accept" : "over your last " + newest.accepts + " accepts")
                + ": " + DecisionLog.money(newest.centsPerHour) + " an hour as of the newest offer";
        if (oldest != null && oldest.centsPerHour != newest.centsPerHour) {
            said += ", from " + DecisionLog.money(oldest.centsPerHour) + " at the oldest shown";
        }
        return said;
    }

    /**
     * One level of the bar line: from {@code from} to {@code to} across, at {@code bar}; {@code at} the offer's time
     * whose recorded bar it is, or {@link Long#MAX_VALUE} for today's bar after the newest offer.
     */
    private static final class BarRun {
        final float from, to;
        final int bar;
        final long at;

        BarRun(float from, float to, int bar, long at) {
            this.from = from;
            this.to = to;
            this.bar = bar;
            this.at = at;
        }
    }

    /**
     * The bar line's levels, left to right (0.6.40; the owner, 23 October 2026: "we should track it's level rather than
     * it be static across the bar"): each offer Autopilot judged, at the bar it was judged at, across its slot; then,
     * while Autopilot is on, today's bar from just past the newest offer's tree to the end where it differs. An offer
     * judged with Autopilot off has none: the line has a gap there.
     */
    private List<BarRun> barRuns() {
        // 0.6.64: the trees are scored against your minimums now, so the line is today's bar alone, across the chart.
        List<BarRun> runs = new ArrayList<>();
        if (entries.isEmpty() || !autopilotNow) return runs;
        runs.add(new BarRun(ui.dp(SIDE_DP), getWidth(), minimumScalePercent, Long.MAX_VALUE));
        return runs;
    }

    /**
     * Autopilot's bar, dashed purple over the buildings and trees: each level of {@link #barRuns} (none drawn at
     * exactly 100%, where the minimums' line stands) and a step between touching levels that differ.
     */
    private void drawBarLine(Canvas canvas, List<BarRun> runs) {
        if (runs.isEmpty()) return;
        float top = plotTop(), bottom = plotBottom();
        long maxScore = scoreScaleMax();
        line.setColor(barLineColor());
        line.setStrokeWidth(Math.max(1, ui.dp(2)));
        line.setStrokeCap(Paint.Cap.BUTT);
        line.setPathEffect(scoreDash);
        for (int i = 0; i < runs.size(); i++) {
            BarRun run = runs.get(i);
            float y = y(lineLevel(run.bar), maxScore, top, bottom);
            if (run.bar != minimumsLevel()) canvas.drawLine(run.from, y, run.to, y, line);
            BarRun next = i + 1 < runs.size() ? runs.get(i + 1) : null;
            if (next != null && next.bar != run.bar && Math.abs(next.from - run.to) < 1f) {
                canvas.drawLine(run.to, y, run.to, y(lineLevel(next.bar), maxScore, top, bottom), line);
            }
        }
        line.setPathEffect(null);
    }

    /**
     * Where the bar line steps, a word or two on why, from the bar's kept changes between the two offers (or since
     * the newest): "accepted", "hold over", "1 in 5" … (the owner: "if auto bar was lowered I should see why on the
     * orders graph"). Under a step down and over a step up, beside it; the newest {@value #BAR_WORDS_MOST} steps that
     * have words, none over another.
     */
    private void drawBarWords(Canvas canvas, List<BarRun> runs) {
        barWords.clear();
        barWordBoxes.clear();
        if (runs.size() < 2 || barChanges.isEmpty()) return;
        float top = plotTop(), bottom = plotBottom();
        long maxScore = scoreScaleMax();
        float end = getWidth() - ui.dp(SIDE_DP);
        label.setTypeface(Ui.MEDIUM);
        label.setTextSize(Math.min(ui.sp(10), ui.dp(13)));
        // The lines' names and the marked score keep their places (drawThresholdLabels comes first).
        List<RectF> taken = new ArrayList<>();
        taken.addAll(labelBoxes);
        for (int i = runs.size() - 1; i > 0 && barWords.size() < BAR_WORDS_MOST; i--) {
            BarRun before = runs.get(i - 1), after = runs.get(i);
            if (before.bar == after.bar || Math.abs(after.from - before.to) >= 1f) continue;
            AutopilotStore.Change change = changeBetween(before.at, after.at, after.bar);
            String word = change == null ? null : AutopilotText.barStepWord(change);
            if (word == null) continue;
            float wide = label.measureText(word);
            boolean down = after.bar < before.bar;
            float levelY = y(lineLevel(after.bar), maxScore, top, bottom);
            float baseline = down ? levelY + ui.dp(3) - label.ascent() : levelY - ui.dp(3) - label.descent();
            float x = after.from + ui.dp(3);
            if (x + wide > end - ui.dp(2)) x = after.from - ui.dp(3) - wide;
            RectF box = new RectF(x - ui.dp(3), baseline + label.ascent() - ui.dp(1), x + wide + ui.dp(3),
                    baseline + label.descent() + ui.dp(1));
            boolean clear = true;
            for (RectF other : taken) if (RectF.intersects(other, box)) clear = false;
            if (!clear) continue;
            taken.add(box);
            barWordBoxes.add(box);
            barWords.add(0, word);
            fill.setColor((ui.page & 0x00FFFFFF) | 0xE0000000);
            canvas.drawRoundRect(box, ui.dp(3), ui.dp(3), fill);
            label.setTextAlign(Paint.Align.LEFT);
            label.setColor(barLineColor());
            canvas.drawText(word, x, baseline, label);
        }
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(null);
        label.setTextSize(ui.sp(11));
        label.setColor(ui.inkMuted);
    }

    /**
     * Where a line's words stand (as {@link #drawLineLabel} draws them; {@code under}: under their line), with their
     * backdrop's margin. Measured with the label paint as it is set.
     */
    private RectF lineLabelBox(String text, float x, float lineY, boolean end, boolean under) {
        useLineLabelPaint();
        float wide = label.measureText(text);
        float baseline = under ? lineY + ui.dp(3) - label.ascent() : lineY - ui.dp(3) - label.descent();
        float textLeft = end ? x - wide : x;
        return new RectF(textLeft - ui.dp(3), baseline + label.ascent() - ui.dp(1), textLeft + wide + ui.dp(3),
                baseline + label.descent() + ui.dp(1));
    }

    /** The bar line's words at most: the newest steps only, so the street stays readable. */
    static final int BAR_WORDS_MOST = 3;

    /**
     * The kept change that moved the bar between the offer decided at {@code after} and the next one, decided at
     * {@code before} ({@link Long#MAX_VALUE}: now): the latest there that landed on {@code to}, else the latest there.
     */
    private AutopilotStore.Change changeBetween(long after, long before, int to) {
        AutopilotStore.Change landed = null, latest = null;
        for (AutopilotStore.Change change : barChanges) {
            if (change.at <= after || change.at > before) continue;
            latest = change;
            if (change.to == to) landed = change;
        }
        return landed != null ? landed : latest;
    }

    /**
     * The bar line said (0.6.67, one level since 0.6.64): "Autopilot's Platinum bar, 37%: an offer whose tree reaches
     * it would pass now"; null where it is not drawn.
     */
    private String barSaid() {
        if (!barNamed()) return null;
        return "Autopilot's " + (barReason == null ? "" : barReason + " ") + "bar, " + minimumScalePercent
                + "%: an offer whose tree reaches it would pass now";
    }

    /** The bar line's words as last drawn, oldest first (for tests and the description). */
    List<String> barWords() {
        return Collections.unmodifiableList(barWords);
    }

    /**
     * The marked offer's score and the lines' names (0.6.67; the owner, 22 November 2026: "I am platinum and it is not
     * obvious to me at a glance of the chart what you just explained"), each where nothing placed before it stands,
     * the badge (at {@code badgeX}, {@code badgeY}; NaN without one) first: the marked offer's tree says its score at
     * its tip, "81%" ({@link #scoreLabelBox}, drawn once it has risen); the green dashed line is {@value #MINS_LABEL}
     * at its right end; the tan line "Top $17.60" ({@link #payLabel}) at its left end; and Autopilot's purple line
     * says what holds it, "Platinum bar 37%" ({@link #barLabel}), at its left end, else at its right. A name stands
     * over its line, else under it, sliding inward past what is in the way, never where another line runs through it,
     * and is left out where it finds no clear place (the chip and Autopilot's details still say it). The marked tree's
     * tip is kept clear of every name. This replaces the first 0.6.67's "100%", "Auto 35%" and "Pay $2.84 min".
     */
    private void drawThresholdLabels(Canvas canvas, float left, float right, float badgeX, float badgeY, int marked,
                                     boolean markedRisen) {
        labelBoxes.clear();
        List<RectF> taken = new ArrayList<>();
        if (!Float.isNaN(badgeX)) {
            float reach = marked == selected ? ui.dp(9) : ui.dp(4.5f);
            taken.add(new RectF(badgeX - reach, badgeY - reach, badgeX + reach, badgeY + reach));
            if (marked == routed) {
                float half = ui.dp(ROAD_MARK_DP) / 2f + ui.dp(1);
                float x = badgeX + ui.dp(ROAD_MARK_RIGHT_DP), y = badgeY + ui.dp(ROAD_MARK_DOWN_DP);
                taken.add(new RectF(x - half, y - half, x + half, y + half));
            }
        }
        float[] tree = treeAt(marked);
        if (tree != null) {
            float tip = tree[1] - (treeClippedAt(marked) ? ui.dp(4) : 0);
            String score = treeScore(entries.get(marked)) + "%";
            RectF box = scoreLabelBox(score, tree, tip, taken);
            if (box != null) {
                taken.add(box);
                labelBoxes.add(box);
                if (markedRisen) drawScoreLabel(canvas, score, box);
            }
            float half = tree[3] / 2f + ui.dp(1);
            taken.add(new RectF(tree[0] - half, tip - ui.dp(1), tree[0] + half, tip + ui.dp(6)));
        }
        float hundred = hundredY();
        float barY = barNamed() ? barY() : Float.NaN;
        float payY = topPayCents > 0 ? topPayY() : Float.NaN;
        float[] lines = {hundred, barY, payY};
        placeLineLabel(canvas, MINS_LABEL, hundred, treeColor(), left, right, taken, lines, true, false);
        if (!Float.isNaN(payY)) {
            placeLineLabel(canvas, payLabel(), payY, payoutColor(), left, right, taken, lines, false, true);
        }
        if (!Float.isNaN(barY)) {
            placeLineLabel(canvas, barLabel(), barY, barLineColor(), left, right, taken, lines, false, true);
        }
        rateLabelShown = null;
        placeRateLabel(canvas, left, right, taken, lines);
        resetLabelPaint();
    }

    /**
     * Where the marked offer's score stands (0.6.67): over its tree's tip ({@code tree} as {@link #treeAt} gives it,
     * {@code tip} its top), moved in from the view's edge where it would run off it, else beside the tip on its left,
     * then its right, else just below the tip on either side (where the badge sits over the tip), wherever it meets
     * nothing in {@code taken} and stays in view; null where none is clear.
     */
    private RectF scoreLabelBox(String text, float[] tree, float tip, List<RectF> taken) {
        useLineLabelPaint();
        float wide = label.measureText(text);
        float half = tree[3] / 2f;
        float beside = tip + ui.dp(2) - (label.ascent() + label.descent()) / 2f;
        float below = tip + ui.dp(1) - label.ascent();
        float over = Math.max(ui.dp(4), Math.min(tree[0] - wide / 2f, getWidth() - ui.dp(4) - wide));
        float[][] places = {
                {over, tip - ui.dp(3) - label.descent()},
                {tree[0] - half - ui.dp(4) - wide, beside},
                {tree[0] + half + ui.dp(4), beside},
                {tree[0] - half - ui.dp(4) - wide, below},
                {tree[0] + half + ui.dp(4), below}};
        for (float[] place : places) {
            RectF box = new RectF(place[0] - ui.dp(3), place[1] + label.ascent() - ui.dp(1),
                    place[0] + wide + ui.dp(3), place[1] + label.descent() + ui.dp(1));
            if (box.left < 0 || box.right > getWidth() || box.top < 0 || box.bottom > plotBottom() + ui.dp(2)) continue;
            if (firstHit(box, taken) == null) return box;
        }
        return null;
    }

    /** The marked offer's score in {@code box} ({@link #scoreLabelBox}), in the trees' green on the page's backdrop. */
    private void drawScoreLabel(Canvas canvas, String text, RectF box) {
        useLineLabelPaint();
        label.setTextAlign(Paint.Align.LEFT);
        label.setColor(treeColor());
        fill.setColor((ui.page & 0x00FFFFFF) | 0xE0000000);
        canvas.drawRoundRect(box, ui.dp(3), ui.dp(3), fill);
        canvas.drawText(text, box.left + ui.dp(3), box.bottom - ui.dp(1) - label.descent(), label);
    }

    /**
     * Draws a line's name ({@code text}, its line at {@code lineY}) at the first clear place, trying each of
     * {@code rightEnds} in turn (true: the right end): at that end over its line, else under it; then sliding inward
     * past what it meets in {@code taken}, over it, else under it; never out of the plot's reach, nor where another of
     * {@code lines} (NaN: none) runs through it. Adds its box to {@code taken} and {@link #labelBoxes}; false where no
     * place is clear.
     */
    private boolean placeLineLabel(Canvas canvas, String text, float lineY, int color, float left, float right,
                                   List<RectF> taken, float[] lines, boolean... rightEnds) {
        for (boolean atRight : rightEnds) {
            for (int tries = 0; tries < 4; tries++) {
                boolean under = tries % 2 == 1;
                int steps = tries < 2 ? 1 : 6;
                float x = atRight ? right - ui.dp(2) : left + ui.dp(2);
                for (int step = 0; step < steps; step++) {
                    RectF box = lineLabelBox(text, x, lineY, atRight, under);
                    if (box.left < left - ui.dp(2) || box.right > right + ui.dp(2) || box.top < 0
                            || box.bottom > plotBottom() + ui.dp(2)) {
                        break;
                    }
                    boolean crossed = false;
                    for (float other : lines) crossed |= crosses(box, other);
                    if (crossed) break;
                    RectF hit = firstHit(box, taken);
                    if (hit == null) {
                        taken.add(box);
                        labelBoxes.add(box);
                        drawLineLabel(canvas, text, x, lineY, atRight, under, color);
                        return true;
                    }
                    x = atRight ? hit.left - ui.dp(4) : hit.right + ui.dp(4);
                }
            }
        }
        return false;
    }

    /** The first of {@code taken} that {@code box} meets; null for none. */
    private static RectF firstHit(RectF box, List<RectF> taken) {
        for (RectF other : taken) if (RectF.intersects(box, other)) return other;
        return null;
    }

    /** Whether Autopilot's line is drawn: on, with offers, and off 100% (where the minimums' line stands). */
    private boolean barNamed() {
        return autopilotNow && !entries.isEmpty() && lineLevel(minimumScalePercent) != minimumsLevel();
    }

    /** Where Autopilot's line stands. */
    private float barY() {
        return y(lineLevel(minimumScalePercent), scoreScaleMax(), plotTop(), plotBottom());
    }

    /**
     * Autopilot's line named with what holds it (0.6.67; {@link #setBarReason}): "Platinum bar 37%", "Accept bar 81%",
     * or "Bar 37%" where nothing is known to hold it.
     */
    String barLabel() {
        return (barReason == null ? "Bar " : barReason + " bar ") + minimumScalePercent + "%";
    }

    /** Whether a dashed line at {@code y} (NaN: none) runs through {@code box}. */
    private static boolean crosses(RectF box, float y) {
        return !Float.isNaN(y) && y >= box.top && y <= box.bottom;
    }

    private void useLineLabelPaint() {
        label.setTypeface(Ui.MEDIUM);
        label.setTextSize(Math.min(ui.sp(10), ui.dp(13)));
    }

    private void resetLabelPaint() {
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(null);
        label.setTextSize(ui.sp(11));
        label.setColor(ui.inkMuted);
    }

    /** The tan line's words: the top pay among these offers, "Top $17.60". */
    String payLabel() {
        return "Top " + DecisionLog.money(topPayCents);
    }

    /**
     * A line's label just over it ({@code under}: just under it), at {@code x} (its right end when {@code end});
     * where a building or tree rises through it (0.6.9), it keeps a backdrop of the page so it stays readable.
     */
    private void drawLineLabel(Canvas canvas, String text, float x, float lineY, boolean end, boolean under,
                               int color) {
        label.setTextAlign(end ? Paint.Align.RIGHT : Paint.Align.LEFT);
        label.setTypeface(Ui.MEDIUM);
        label.setTextSize(Math.min(ui.sp(10), ui.dp(13)));
        label.setColor(color);
        float baseline = under ? lineY + ui.dp(3) - label.ascent() : lineY - ui.dp(3) - label.descent();
        float wide = label.measureText(text);
        float textLeft = end ? x - wide : x;
        // A backdrop where a building or tree rises through the words, or the blue line runs through them (0.6.69).
        if (barReaches(textLeft - ui.dp(3), textLeft + wide + ui.dp(3), baseline + label.descent())
                || rateRunsThrough(textLeft - ui.dp(3), textLeft + wide + ui.dp(3), baseline + label.ascent() - ui.dp(1),
                baseline + label.descent() + ui.dp(1))) {
            fill.setColor((ui.page & 0x00FFFFFF) | 0xE0000000);
            float pad = ui.dp(3);
            canvas.drawRoundRect(textLeft - pad, baseline + label.ascent() - ui.dp(1), textLeft + wide + pad,
                    baseline + label.descent() + ui.dp(1), pad * 2, pad * 2, fill);
        }
        canvas.drawText(text, x, baseline, label);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(null);
        label.setTextSize(ui.sp(11));
        label.setColor(ui.inkMuted);
    }

    /** Whether a building or tree standing between {@code from} and {@code to} across rises above {@code y}. */
    private boolean barReaches(float from, float to, float y) {
        if (entries.isEmpty()) return false;
        float slot = slotWidth();
        float half = buildingWidth(slot) / 2f;
        for (int i = 0; i < entries.size(); i++) {
            float center = buildingCenter(i, slot);
            if (center + half >= from && center - half <= to && roofAt(i) < y) return true;
            float[] tree = treeAt(i);
            if (tree != null && tree[0] + tree[3] / 2f >= from && tree[0] - tree[3] / 2f <= to && tree[1] < y) {
                return true;
            }
        }
        return false;
    }

    /**
     * The minimums' line: 100% of the minimums (0.6.68; the owner, 25 November 2026: the tree graph "should still show
     * a line for 100% best mins area score as a point of reference for the 100% of best offer's score"). Trees stand
     * against the best offer you accepted, so this line stands at {@link AreaScore#minimumsLevel}: 100% with no best
     * score below the minimums, 123% for a best accept scoring 81% of them.
     */
    float hundredY() {
        return y(minimumsLevel(), scoreScaleMax(), plotTop(), plotBottom());
    }

    /** Where the minimums stand on the trees' scale ({@link AreaScore#minimumsLevel}). */
    int minimumsLevel() {
        return AreaScore.minimumsLevel(currentRules);
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

    /** The top of the offer's building once risen (an unread pay's hollow building included). */
    private float roofAt(int index) {
        DecisionLog.Entry entry = entries.get(index);
        return entry.facts.payCents != null ? y(entry.facts.payCents, scaleMax(), plotTop(), plotBottom())
                : unreadTop(plotTop(), plotBottom());
    }

    /**
     * Where the badge of the offer at {@code index} (oldest first, as drawn) stands once its building has risen, in this
     * view's pixels; null where there is none (only the selected offer, else the newest, has one; for tests).
     */
    float[] flagAt(int index) {
        if (index < 0 || index >= entries.size() || getWidth() <= 0 || index != badged()) return null;
        float x = buildingCenter(index, slotWidth());
        float[] tree = treeAt(index);
        float tip = tree == null ? Float.NaN : tree[1] - (treeClippedAt(index) ? ui.dp(4) : 0);
        return new float[] {x, badgeCenterY(x, roofAt(index), tree == null ? Float.NaN : tree[0], tip,
                index == selected, index == routed)};
    }

    /** Settled score tree: center X, tip Y, baseline Y and width. An unknown or retired score has no tree. */
    float[] treeAt(int index) {
        if (index < 0 || index >= entries.size() || getWidth() <= 0 || !tree(entries.get(index))) return null;
        float slot = slotWidth();
        return new float[] {treeCenter(index, slot), y(treeScore(entries.get(index)), scoreScaleMax(), plotTop(),
                plotBottom()), plotBottom(), treeWidth(slot)};
    }

    /** Settled building: center X, roof Y, baseline Y and width; null where the pay was not read (for tests). */
    float[] buildingAt(int index) {
        if (index < 0 || index >= entries.size() || getWidth() <= 0 || entries.get(index).facts.payCents == null) {
            return null;
        }
        float slot = slotWidth();
        return new float[] {buildingCenter(index, slot), roofAt(index), plotBottom(), buildingWidth(slot)};
    }

    boolean treeClippedAt(int index) {
        return index >= 0 && index < entries.size() && tree(entries.get(index))
                && treeScore(entries.get(index)) > SCORE_DISPLAY_CAP;
    }

    /** The colour of the offer's building (for tests); 0 where its pay was not read. */
    int barColorAt(int index) {
        if (index < 0 || index >= entries.size() || entries.get(index).facts.payCents == null) return 0;
        return facadeColor(barColor(entries.get(index)));
    }

    long topPayCents() { return topPayCents; }

    float topPayY() {
        return topPayCents > 0 ? y(topPayCents, scaleMax(), plotTop(), plotBottom()) : Float.NaN;
    }

    /** Whether the offer at {@code index} shows the hollow "?" building of a pay that was not read. */
    boolean unreadAt(int index) {
        return index >= 0 && index < entries.size() && entries.get(index).facts.payCents == null;
    }

    int scoreThresholdPercent() { return minimumScalePercent; }
    float scoreThresholdY() { return y(minimumScalePercent, scoreScaleMax(), plotTop(), plotBottom()); }

    private float plotTop() { return Math.min(ui.dp(26), getHeight() * 0.40f); }
    private float plotBottom() {
        // The key grows with font size, while a compact chart still keeps its bars above it.
        return Math.max(plotTop(), getHeight() - Math.max(ui.dp(22), ui.sp(14) * 1.3f + ui.dp(4)));
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
     * The highest point, in this view's pixels, that a building, tree, line, label or badge reaches once risen between
     * {@code from} and {@code to} across; the view's height where none stands.
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
        float labelHigh = ui.dp(3) + Math.min(ui.sp(10), ui.dp(13)) * 1.2f;
        highest = Math.min(highest, Math.min(scoreThresholdY(), hundredY()) - ui.dp(1));
        // The bar line's levels where they cross the span (0.6.40), and its words.
        long maxScore = scoreScaleMax();
        for (BarRun run : barRuns()) {
            if (run.to >= from && run.from <= to) {
                highest = Math.min(highest, y(lineLevel(run.bar), maxScore, plotTop(), plotBottom()) - ui.dp(1));
            }
        }
        for (RectF box : barWordBoxes) if (box.right >= from && box.left <= to) highest = Math.min(highest, box.top);
        // The lines' names and the marked score (0.6.67), where they were last drawn; before the first drawing, where
        // they stand at most: the minimums' name over its line at the right end, the top pay's over its line at the left.
        for (RectF box : labelBoxes) if (box.right >= from && box.left <= to) highest = Math.min(highest, box.top);
        if (labelBoxes.isEmpty() && to >= right - ui.dp(60)) highest = Math.min(highest, hundredY() - labelHigh);
        if (topPayCents > 0) {
            highest = Math.min(highest, topPayY() - ui.dp(1));
            if (labelBoxes.isEmpty() && from <= left + ui.dp(90)) highest = Math.min(highest, topPayY() - labelHigh);
        }
        // The blue line (0.6.69) and its dots; before the first drawing, its name over its newest level at the right end.
        if (layOutRates(left, right)) {
            float rate = rateWithin(from, to, true);
            if (!Float.isNaN(rate)) highest = Math.min(highest, rate - ui.dp(RATE_DOT_DP));
            if (labelBoxes.isEmpty() && to >= right - ui.dp(90)) {
                highest = Math.min(highest, rateYs[ratePoints - 1] - ui.dp(RATE_DOT_DP) - labelHigh);
            }
        }
        int marked = badged();
        for (int i = 0; i < entries.size(); i++) {
            float center = buildingCenter(i, slot);
            float rightReach = i == routed
                    ? Math.max(reach, ui.dp(ROAD_MARK_RIGHT_DP) + ui.dp(ROAD_MARK_DP) / 2f + ui.dp(1)) : reach;
            if (center + rightReach >= from && center - reach <= to) {
                highest = Math.min(highest, roofAt(i) - (unreadAt(i) ? ui.sp(10) * 1.2f : 0));
                float[] flag = i == marked ? flagAt(i) : null;
                if (flag != null) highest = Math.min(highest, flag[1] - ui.dp(9));
            }
            float[] tree = treeAt(i);
            if (tree != null && tree[0] + tree[3] / 2f >= from && tree[0] - tree[3] / 2f <= to) {
                highest = Math.min(highest, tree[1] - (treeClippedAt(i) ? ui.dp(5) : 0));
            }
        }
        return highest;
    }

    /** The smallest "nice" dollar ceiling at or above every pay shown (the tan line stands at the top one). */
    private long scaleMax() {
        long max = 0;
        for (DecisionLog.Entry entry : entries) {
            if (entry.facts.payCents != null) max = Math.max(max, Math.min(entry.facts.payCents, SCALE_CAP_CENTS));
        }
        for (long dollars : NICE_DOLLARS) {
            if (dollars * 100 >= max) return dollars * 100;
        }
        return SCALE_CAP_CENTS;
    }

    /** Percent scale does not depend on payouts or dollar requirements. Unknowns contribute nothing. */
    private long scoreScaleMax() {
        int max = Math.max(Math.max(100, minimumsLevel()), lineLevel(minimumScalePercent));
        for (DecisionLog.Entry entry : entries) {
            if (tree(entry)) max = Math.max(max, Math.min(SCORE_DISPLAY_CAP, treeScore(entry)));
            if (entry.autopilot) max = Math.max(max, Math.min(SCORE_DISPLAY_CAP, lineLevel(entry.barPercent)));
        }
        for (int ceiling : new int[] {150, 200, 250, 300, SCORE_DISPLAY_CAP}) {
            if (ceiling >= max) return ceiling;
        }
        return SCORE_DISPLAY_CAP;
    }

    /**
     * Where the dashed purple line sits: at the bar itself (0.6.64; 0.6.62 and 0.6.63 measured scores against the bar
     * and drew this line at the minimums' level instead).
     */
    static int lineLevel(int bar) {
        return bar <= 0 ? 100 : bar;
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
                below > 0 ? " (" + below + " below " + AutopilotText.hundred(currentRules) + ")" : "",
                accepted > 0 ? accepted + " accepted, " : "", counts[DecisionLog.Outcome.DECLINED.ordinal()],
                (yours > 0 ? yours + " left to you, " : "")
                        + (requested > 0 ? requested + " accept requested but unconfirmed, " : ""),
                counts[DecisionLog.Outcome.REVIEW.ordinal()]);
        int unknown = 0;
        int addOns = 0;
        int retired = 0;
        int clipped = 0;
        boolean independentScores = independentAutopilot;
        for (DecisionLog.Entry entry : entries) {
            independentScores |= AutopilotText.usesAutopilotScore(entry);
            if (entry.addOn && entry.scorePercent < 0) addOns++;
            else if (entry.scorePercent < 0) unknown++;
            else if (!tree(entry)) retired++;
            else if (entry.scorePercent > SCORE_DISPLAY_CAP) clipped++;
        }
        String bar = barSaid();
        String rate = rateSaid();
        return summary + selectedDescription() + " " + AutopilotText.skylineDescription(independentScores, currentRules)
                + (bar != null ? " Purple dashed line: " + bar + "." : "")
                + (rate != null ? " Blue line: " + rate + "." : "")
                + (topPayCents > 0 ? " Tan line: the top pay among these offers, " + DecisionLog.money(topPayCents)
                + "." : "")
                + (unknown > 0 ? " " + unknown + " recorded scores unavailable: no tree." : "")
                + (addOns > 0 ? " " + addOns + " add-ons are checked against added travel and the whole route, "
                + "without a standalone score or tree." : "")
                + (retired > 0 ? " " + retired + " scores from earlier rules: no tree." : "")
                + (clipped > 0 ? " " + clipped + " trees exceed " + SCORE_DISPLAY_CAP
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
                ? AutopilotText.passedBelowWords(entry).toLowerCase(Locale.US)
                : outcome.name().toLowerCase(Locale.US);
        return " Selected offer " + (selected + 1) + " of " + entries.size() + ": "
                + (entry.facts.payCents == null ? "payout unknown" : DecisionLog.money(entry.facts.payCents))
                + ", " + DecisionLog.facts(entry.facts) + ", " + state
                + (tree(entry) ? AutopilotText.usesAutopilotScore(entry)
                        ? ", recorded Autopilot score " + entry.scorePercent + "%."
                        : entry.model >= 3 && "area".equals(entry.scoreKind)
                        ? ", recorded area score " + entry.scorePercent + "%."
                        : ", score " + entry.scorePercent + "% of " + AutopilotText.hundred(entry.scoringRules) + "."
                : entry.addOn && entry.scorePercent < 0 ? ", add-on: no standalone score."
                : entry.scorePercent >= 0 ? ", score from earlier area rules." : ", score unknown.")
                + (selected == routed ? " On a route." : "")
                + rateThen(selected);
    }

    /** " Pay per active hour as of it: $31.40." for the offer at {@code index}; empty before any accept (0.6.69). */
    private String rateThen(int index) {
        ActiveHourPay.Point point = index >= 0 && index < rates.length ? rates[index] : null;
        if (point == null || !point.known()) return "";
        return " Pay per active hour as of it: " + DecisionLog.money(point.centsPerHour) + ".";
    }
}
