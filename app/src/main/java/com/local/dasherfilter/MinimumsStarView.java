package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The minimums as a constellation in the page's sky: three spokes, pay (up and to the left), per mile (up and to the
 * right) and per hour of trip time (down and to the right, the clock). Distance from the middle is the pay each asks of
 * one example offer, so the three share a dollar scale, and the set minimums are the solid blue shape through their
 * stars. Down and to the left the per-stop spoke's pin is kept only to anchor the max stops badge ("≤3"; hollow, "≤∞",
 * while there is none): a tap steps it off, 2, 3 … 10, a drag across it steps it either way. While Autopilot holds its
 * bar anywhere but 100%, a dashed purple shape at that share of each minimum shows what it asks now (purple means
 * Autopilot). The latest or skyline-selected offer has small marks on each spoke at what its pay, its pay per mile and
 * its pay per hour would pay for the example (● passed, ✕ declined, ○ review): a mark outside a minimum beat it. The
 * shapes glide to new values; with Android's animations off they rest. By day the stars are drawn in ink on the morning
 * sky, by night they shine. Each spoke is marked by its icon and a short plain-language label; screen readers hear the
 * whole of it and what the example offer needs at exactly the minimums.
 *
 * <p>The spokes stand {@link #SPREAD} degrees above and below level, to the left and to the right, so a wide sky holds
 * a wide chart. It stands in the page's sky in a box of its own, above the skyline and above or beside the map
 * ({@link FluidLayout}), at every window size: the page puts the largest circle there that keeps the spokes' icons inside
 * the box ({@link #compose}), its faint rings fading out where they run on past the box's edges, the rings' dollars
 * along the level line on the right. Its points and icons only ever lie along the spokes. Only a touch on the circle,
 * an icon, the badge or the button is the constellation's; the empty sky around it takes none.
 *
 * <p>As the sky, the set minimums are knobs: each spoke's round star, ringed so it reads as something to take hold of
 * (or, on a spoke with no set minimum, a small hollow knob resting just outside the middle), grows under a finger and
 * can be dragged
 * along its spoke, in steps, with a light tick at each; pressing or focusing shows its name and value before moving it,
 * and letting go saves it at once. A knob keeps its exact value until the finger has moved it half a step along its
 * spoke, so a wobble never snaps it to a step; it is taken only by a finger moving along its spoke, so a scroll of the
 * page or a slide across it is left to the page. Dragged into the middle, the rule is off (a knob resting near the
 * middle must be pushed a clear way further in). The ring scale holds still while a knob moves (it may be pushed a
 * little past the outer ring) and settles to fit when it is let go.
 *
 * <p>One round 48 dp button is Autopilot: "Auto" over its bar ("82%"), or over "Off", ringed in purple while on (amber
 * while the acceptance rate is below the goal, as the page's chip is), in grey while off. A tap is the
 * page's ({@link Changes#toggleAutopilot}: off while on; while off, the goal chooser), a long press asks for the goal
 * at any time; it has no drag. The button's words and ring follow the bar quietly: nothing is announced when Autopilot
 * moves it. Screen readers reach each knob and the badge as adjustable controls and the button as a switch, with the
 * long press and the details among its actions.
 *
 * <p>The displayed offer's points are joined in the spokes' order, leaving out a spoke it did not say. Other offers
 * remain in history and can be selected on the skyline; their hidden polygons receive no touches or accessibility
 * focus.
 *
 * <p>As the sky, every marked offer is also the way to its ticket: a tap (never a drag) within 24 dp of one of its
 * marks opens it, and failing that a tap inside its polygon does. The page opens it exactly as a tap on its building in
 * the skyline does, so both show it chosen; while its ticket is open its polygon and marks stand out, and a finger
 * going down on an offer picks it out lightly first. The knobs, the badge and the button keep their touches first; a
 * tap on the circle that is on no offer goes back to the newest offer while an older one is chosen, and otherwise does
 * nothing. Screen readers reach the one displayed offer after the knobs and buttons
 * ("Offer $9.75, 3.3 mi, 18 min, 2 stops, declined"), and a double-tap opens its ticket.
 */
@SuppressLint("ViewConstructor")
final class MinimumsStarView extends View {
    /** The six stable axis indexes keep their screen readers' ids; only pay, per mile and per hour have knobs. */
    private static final String[] NAMES = {"Pay", "Per mile", "Per hour", "Per stop", "Hotspot", "Per item"};
    /** The names drawn by the icons: the three minimums, and max stops by the pin; hotspot and per item are gone. */
    static final String[] AXIS_LABELS = {"Payout $", "Pay / mile", "Pay / hour", "Max stops", "", ""};
    /** Each spoke's icon (the per-stop pin anchors the max stops badge); the names are for screen readers. */
    private static final Glyph.Shape[] ICONS = {Glyph.Shape.COIN, Glyph.Shape.ROAD, Glyph.Shape.CLOCK,
            Glyph.Shape.PIN};
    /** The spokes with a knob and a minimum, in the order they are drawn and joined. */
    static final int[] SPOKES = {AreaScore.PAY, AreaScore.MILE, AreaScore.MINUTE};
    private static final int ICON_DP = 18;
    /**
     * The spokes stand this many degrees above and below level: top-left, top-right, bottom-right (and the pin at the
     * bottom-left).
     */
    static final float SPREAD = AreaScore.SPREAD;
    /** The spokes' angles, shared with the score. */
    private static final float[] ANGLES = AreaScore.ANGLES;
    private static final float COS = (float) Math.cos(Math.toRadians(SPREAD));
    private static final float SIN = (float) Math.sin(Math.toRadians(SPREAD));
    /** A pay no rule can ask more than, for working out what an example offer needs. */
    private static final int ANY_PAY = Integer.MAX_VALUE;
    /** Night-sky colors; by day the page's own accent, Autopilot's purple and the outcome colors read on the sky. */
    private static final int NIGHT_SET = 0xFFA9CBFF;
    private static final int NIGHT_AUTOPILOT = 0xFFDCC2FF;
    private static final int NIGHT_PASSED = 0xFF8BE08B;
    private static final int NIGHT_DECLINED = 0xFFFF8F87;
    private static final int NIGHT_REVIEW = 0xFFFFD27A;
    /** Only the offers on the skyline are marked (those with pay that are not add-ons), so each has its building. */
    private static final int MARKS = DecisionChartView.SLOTS;
    /** A tap this near one of an offer's marks (a 48 dp target) is for that offer; where two could, the nearer. */
    private static final int MARK_TAP_DP = 24;
    /** Each recent offer's own polygon: about a twentieth of its color, in a faint outline. */
    private static final int OFFER_FILL = 0x0D000000;
    private static final int OFFER_LINE = 0x38000000;
    /** Offers far above every minimum stretch the scale only this far, so the minimums stay readable. */
    private static final double OFFER_STRETCH = 1.35;
    private static final long GLIDE_MS = 700;
    /** An offer above the outer ring is marked just past it, at most this share of the way out... */
    private static final double MARK_REACH = 1.02;
    /** ...and offers side by side on a spoke step this many dp aside from each other (at full detail). */
    private static final float MARK_STEP_DP = 2.2f;
    /** As the page's sky, the icons at the spokes' ends are a little larger. */
    private static final int BACKDROP_ICON_DP = 22;
    /**
     * As the sky, an icon stands this far above the end of an upper spoke (below a lower one), its middle this far out
     * to the side, so the circle can run on past the page's edges while its spokes' ends and icons stay inside it.
     */
    private static final int ICON_GAP_DP = 12;
    private static final int ICON_OUT_DP = 4;
    /** As the sky, the rings fade out over this much of the view's top and bottom edges rather than stop at them. */
    private static final int EDGE_FADE_DP = 28;
    private final Ui ui;
    private final TextPaint axisText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] axisLabels = new RectF[AreaScore.AXES];
    /** How much of each spoke's name is there (1: wholly, 0: gone; {@link #placeAxisLabels}). */
    private final float[] axisShown = new float[AreaScore.AXES];
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final DashPathEffect dash;
    /** Cents each set minimum asks of the example offer; NaN where there is no such minimum. */
    private final double[] setCents = new double[NAMES.length];
    /** Cents Autopilot's bar asks on each spoke ({@code bar × setCents ÷ 100}); NaN where none, or at a bar of 100. */
    private final double[] barCents = new double[NAMES.length];
    /** The same as drawn: the set shape and Autopilot's dashed one, in cents of the example offer; NaN for none. */
    private final double[] set = new double[NAMES.length];
    private final double[] auto = new double[NAMES.length];
    /** Where each point is heading and where it set out from, as fractions of the outer ring. */
    private final float[] setTo = new float[NAMES.length];
    private final float[] setFrom = new float[NAMES.length];
    private final float[] autoTo = new float[NAMES.length];
    private final float[] autoFrom = new float[NAMES.length];
    private long glideStart;
    /**
     * Recent offers, newest first: result, then their value on each spoke in cents for the example offer (NaN where the
     * offer did not say), when each was recorded, its score under the rules as shown (-1 where none), and where each
     * mark glides from and to (shares of the outer ring).
     */
    private final List<OfferRule.Result> markResults = new ArrayList<>();
    private final List<double[]> marks = new ArrayList<>();
    private final List<Long> markTimes = new ArrayList<>();
    private final List<Integer> markScores = new ArrayList<>();
    private final List<OfferSnapshot> markFacts = new ArrayList<>();
    private final List<DecisionLog.Entry> markEntries = new ArrayList<>();
    private final List<float[]> markFrom = new ArrayList<>();
    private final List<float[]> markTo = new ArrayList<>();
    /** The offer chosen on the skyline (by when it was recorded); -1: the newest. */
    private long emphasizedAt = -1;
    /** Actual newest input entry, before excluding add-ons or missing pay; never substitute an older plotted offer. */
    private long newestEntryAt = -1;
    /** The offer whose ticket is open (by when it was recorded): its polygon and marks stand out; -1 for none. */
    private long openAt = -1;
    /** The offer a finger went down on (by when it was recorded), picked out lightly until it lifts; -1 for none. */
    private long pressedAt = -1;
    /** What the page does when a marked offer is tapped; null where the chart takes a tap only. */
    private OfferTaps offerTaps;
    /** Dollars between rings; three rings. */
    private long ringCents;
    /** The rules as shown, for the chosen offer's score (worked out afresh while a knob is held). */
    private FilterSettings shownRules = FilterSettings.of(false, 0, 0, 0, 0);
    /** The cents at the outer ring (three rings), and what the values just shown ask for; held still under a knob. */
    private double outer;
    private double shownOuter;
    private int rings = 3;
    private String needs = "";
    /** What screen readers last learned of the knobs, the badge and the offers: they are told when that changes. */
    private String nodesKey = "";
    private final Glyph[] icons = new Glyph[ICONS.length];
    /** How much of their full size the stars and marks are drawn at: less on a small circle. */
    private float detail = 1;
    /** Where the page put the circle (a radius of 0 until it has). */
    private float skyX;
    private float skyY;
    private float skyRadius;
    private final Paint edgeFade = new Paint();
    private final Paint sideFade = new Paint();
    private float edgeFadeFor = Float.NaN;
    private float sideFadeFor = Float.NaN;
    /** As the sky: the rings' dollars (bold, in a soft halo of the sky's color) and where each was last drawn. */
    private final TextPaint levelText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<RectF> levelBoxes = new ArrayList<>();
    private final List<String> levelWords = new ArrayList<>();
    private final List<Float> levelShown = new ArrayList<>();
    private final RectF iconBox = new RectF();

    // ---- Autopilot, as the button shows it. ----

    /** Autopilot on, the bar deciding offers now, and the acceptance rate below the goal. */
    private boolean autopilotOn;
    private int autopilotBar = FilterSettings.BAR_AT_MINIMUMS;
    private boolean belowGoal;

    // ---- Knobs, the badge and the Autopilot button (as the sky only). ----

    /** What the page does when a knob is let go, the badge steps, or the Autopilot button is used. */
    interface Changes {
        /**
         * Sets spoke {@code axis}'s minimum to {@code cents} of its own unit (pay, a mile, a minute of trip time;
         * 0 turns it off), saving it at once.
         */
        void setMinimum(int axis, int cents);

        /** Sets the max stops (0: no limit), saving it at once. */
        default void setMaxStops(int stops) {}

        /** The Autopilot button's tap: Autopilot off while on; while off, the goal chooser (or why it cannot be on). */
        default void toggleAutopilot() {}

        /** The Autopilot button's long press (a screen reader's long click too): the goal chooser. */
        default void chooseAutopilotGoal() {}

        /** The Autopilot button's details action, for screen readers: Autopilot's details. */
        default void showAutopilotDetails() {}
    }

    /** What the page does when a marked offer is tapped (or a screen reader opens it). */
    interface OfferTaps {
        /** Opens {@code entry}'s ticket, as a tap on its building in the skyline does. */
        void open(DecisionLog.Entry entry);

        /** Chooses the newest offer again, as a tap on its building does (without opening its ticket). */
        default void chooseNewest() {}
    }

    /**
     * A knob moves in these steps of its spoke's own unit: $0.50 of pay, $0.05 a mile, $0.01 a minute ($0.60 an hour).
     * The retired spokes keep their places.
     */
    static final int[] STEPS = {50, 5, 1, 25, 5, 5};
    private static final String[] KNOBS = {"Minimum pay", "Minimum per mile", "Minimum per hour of trip time"};
    /** A knob takes a touch this far from its middle (a 48 dp target); where two could, the nearer one does. */
    private static final int KNOB_REACH_DP = 24;
    /**
     * A spoke with no set minimum rests its hollow knob this far out (far enough that the hollow knobs, on a fresh
     * page, stand apart); dragged back inside it, a rule is off.
     */
    static final int KNOB_REST_DP = 32;
    /** Too near the view's edge there, a hollow knob steps out along its spoke this far at a time... */
    private static final int REST_STEP_DP = 4;
    /** ...keeping its middle at least this far inside the view, so a finger finds it. */
    private static final int REST_CLEAR_DP = 10;
    /** A knob set so low it rests inside that place turns off only when pushed this much further in. */
    private static final int OFF_PUSH_DP = 12;
    /** A finger moving within this many degrees of a knob's spoke (either way) drags it; any other way is the page's. */
    private static final double TAKE_WITHIN_DEGREES = 40;
    /** A knob may be pushed this share of the outer ring's reach; the scale grows to fit it when it is let go. */
    private static final float PUSH = 1.12f;
    /** The scale knobs move on while nothing at all is on the chart yet: $5 a ring. */
    private static final long FIRST_RING_CENTS = 500;
    /** The Autopilot button: drawn this wide, taking a touch this far from its middle. */
    private static final int BUTTON_DP = 48;
    private static final int BUTTON_REACH_DP = 24;
    /**
     * The screen reader's ids: the knobs keep their spokes' (0 to 2; 3, 4 and 5 are never shown), then the
     * Autopilot button ({@link #SCORE_ID}, the old score toggle's) and the max stops badge. {@link #ADOPT_ID} and
     * {@link #ADAPTIVE_ID} are reserved for the retired adopt button and adaptive toggle and never shown.
     */
    static final int ADOPT_ID = NAMES.length;
    static final int SCORE_ID = NAMES.length + 1;
    static final int ADAPTIVE_ID = NAMES.length + 2;
    static final int STOPS_ID = NAMES.length + 3;
    /** Each marked offer's id: this plus its place among them, newest first. */
    static final int OFFER_ID = NAMES.length + 4;
    static final String STOPS_SAID = "Max stops";
    /**
     * The Autopilot button's middle stands up to this far left of the circle's (dp), where the max stops badge leaves
     * room, so the per-hour name beside its clock keeps clear of it on a smaller circle.
     */
    private static final int BUTTON_LEFT_DP = 10;
    /**
     * The Autopilot button is wholly there on a circle of at least this radius, in dp, and gone on one of at most the
     * second, growing from its middle in between ({@link #placeButton}).
     */
    private static final int BUTTON_WHOLE_DP = 82;
    private static final int BUTTON_FROM_DP = 74;
    /** The spokes' names are wholly there on a circle of the least radius the page uses, gone at this radius (dp). */
    private static final int NAMES_FROM_DP = 44;
    /** A name, or a ring's dollars, is gone once what it keeps clear of comes this many dp onto it. */
    private static final int NAME_FADE_DP = 8;
    /** The badge's steps after off: 2 stops (one order) to this many. */
    static final int MOST_STOPS = 10;
    /** A finger moving across the badge steps it once every this many dp. */
    private static final int STOPS_STEP_DP = 18;
    /** The hollow knobs beckon this long, in two swells. */
    private static final long BECKON_MS = 1600;
    private static final int NO_NODE = Integer.MIN_VALUE;

    private Changes changes;
    /** Each spoke's set minimum in its own unit as shown: cents of pay, a mile, a minute; 0 when off. */
    private final int[] setRates = new int[NAMES.length];
    /** The example offer's miles, minutes and stops as last shown, which turn a knob's rate into pay on the scale... */
    private double exampleMiles = 5;
    private int exampleMinutes = 20;
    private int exampleStops = 2;
    private OfferSnapshot exampleFacts = new OfferSnapshot(null, 5.0, 20, 2);
    private OfferSnapshot dragFacts = exampleFacts;
    /** ...and as they stood when the held knob was taken, which it keeps until it is let go. */
    private double dragMiles = 5;
    private int dragMinutes = 20;
    /**
     * A finger is on the sky, still a tap (it has not moved), on the knob {@code held} (-1 for none), dragging it, and
     * where it went down.
     */
    private boolean touching;
    private boolean tapping;
    private int held = -1;
    private boolean dragging;
    private float downX;
    private float downY;
    /** The knob's distance out along its spoke less the finger's, when it was taken, so it does not jump. */
    private float grabOffset;
    /** How far out the held knob stood when the finger went down; within half a step of it, it keeps its value. */
    private float grabDistance;
    /** How far out the held knob turns off: inside the resting place, or further in for a knob that set out there. */
    private float offAt;
    /** The rate the held knob is at: snapped, in its spoke's unit; 0 is off. */
    private int dragValue;
    /** The ring step the values last shown ask for, taken up when a drag (which holds the scale still) ends. */
    private long shownRing;
    private final int slop;
    /** The set shape as drawn now, the held knob's spoke standing where the knob is. */
    private final double[] drawnSet = new double[NAMES.length];
    private final float[] drawnFrom = new float[NAMES.length];
    private final float[] drawnTo = new float[NAMES.length];
    private final TextPaint pillText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pillBox = new RectF();
    /** Where each hollow knob rests, in pixels out along its spoke ({@link #placeRests}); stale after a change. */
    private final float[] restAt = new float[NAMES.length];
    private boolean restStale = true;
    /** The Autopilot button: its middle, the box it is drawn in (smaller while it fades), and a finger on it. */
    private float buttonX;
    private float buttonY;
    private final RectF scoreBox = new RectF();
    private boolean autoPressed;
    private boolean autoHeld;
    private final Runnable holdAuto = () -> {
        if (!autoPressed) return;
        autoPressed = false;
        autoHeld = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
        if (changes != null) changes.chooseAutopilotGoal();
    };
    private final TextPaint buttonText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    /** The max stops badge: the saved limit (0 for none), where it stands, a finger on it, dragging it, and to what. */
    private int maxStops;
    private final RectF stopsBox = new RectF();
    private final TextPaint badgeText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private boolean stopsPressed;
    private boolean stopsDragging;
    private int stopsValue;
    /** When the hollow knobs last beckoned (uptime); 0 for never. */
    private long beckonedAt;
    private final Nodes nodes = new Nodes();
    private int focusedNode = NO_NODE;
    /** The offer a screen reader's focus is on (by when it was recorded), so it goes when that offer moves or goes. */
    private long focusedOfferAt = -1;
    private int hoveredNode = NO_NODE;
    private final Rect nodeRect = new Rect();
    private final int[] onScreen = new int[2];
    private String lastSaid = "";

    MinimumsStarView(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        dash = new DashPathEffect(new float[] {ui.dp(5), ui.dp(4)}, 0);
        for (int i = 0; i < ICONS.length; i++) icons[i] = new Glyph(ICONS[i], ui.inkSecondary, ui.dp(ICON_DP));
        for (int i = 0; i < axisLabels.length; i++) axisLabels[i] = new RectF();
        Arrays.fill(setCents, Double.NaN);
        Arrays.fill(barCents, Double.NaN);
        Arrays.fill(set, Double.NaN);
        Arrays.fill(auto, Double.NaN);
        axisText.setTypeface(Ui.MEDIUM);
        axisText.setTextSize(Math.min(ui.sp(11), ui.dp(13)));
        axisText.setTextAlign(Paint.Align.LEFT);
        axisText.setColor(ui.dark ? 0xFFE0E8EE : 0xFF394D5A);
        axisText.setShadowLayer(ui.dp(3), 0, 0, ui.dark ? 0xFF0D1428 : 0xFFE7EEF2);
        edgeFade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        sideFade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        levelText.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD));
        levelText.setTextSize(Math.min(ui.sp(12), ui.dp(16)));
        levelText.setColor(ui.dark ? 0xC8FFFFFF : 0xD0214066);
        // The sky's own color at its middle, so the halo reads as clear sky around the words.
        levelText.setShadowLayer(ui.dp(3), 0, 0, ui.dark ? 0xFF0D1428 : 0xFFE7EEF2);
        pillText.setTypeface(levelText.getTypeface());
        pillText.setTextSize(Math.min(ui.sp(14), ui.dp(18)));
        pillText.setTextAlign(Paint.Align.CENTER);
        buttonText.setTypeface(levelText.getTypeface());
        buttonText.setTextAlign(Paint.Align.CENTER);
        badgeText.setTypeface(levelText.getTypeface());
        badgeText.setTextSize(Math.min(ui.sp(14), ui.dp(18)));
        badgeText.setTextAlign(Paint.Align.CENTER);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    /** Knobs, the badge and the Autopilot button save through {@code changes}; without them the chart only takes a tap. */
    void setChanges(Changes changes) {
        this.changes = changes;
    }

    /** As the sky, a tap on a marked offer (and a screen reader's click on it) opens it through {@code taps}. */
    void setOfferTaps(OfferTaps taps) {
        offerTaps = taps;
    }

    private int setColor() {
        return ui.dark ? NIGHT_SET : ui.accent;
    }

    /** Autopilot's purple on the sky: the dashed shape. */
    private int autopilotColor() {
        return ui.dark ? NIGHT_AUTOPILOT : AutopilotChip.purple(ui);
    }

    private int markColor(OfferRule.Result result) {
        if (!ui.dark) return Ui.resultColor(result);
        return result == OfferRule.Result.KEEP ? NIGHT_PASSED
                : result == OfferRule.Result.DECLINE ? NIGHT_DECLINED : NIGHT_REVIEW;
    }

    /** Whether {@code axis} is one of the three minimums' spokes. */
    private static boolean spoke(int axis) {
        return axis == AreaScore.PAY || axis == AreaScore.MILE || axis == AreaScore.MINUTE;
    }

    /**
     * @param rules the rules as saved, Autopilot's bar among them
     * @param example the offer whose miles, minutes and stops turn each minimum into pay; all three must be known
     *     (the screen uses the latest fully read offer, or a typical one)
     * @param recent recent decisions, newest first; standalone offers with pay are marked
     */
    void show(FilterSettings rules, OfferSnapshot example, List<DecisionLog.Entry> recent) {
        int[] rates = rules.minimums();
        Arrays.fill(setCents, Double.NaN);
        Arrays.fill(setRates, 0);
        for (int axis : SPOKES) {
            setRates[axis] = Math.max(0, rates[axis]);
            java.math.BigDecimal ask = AreaScore.fixedFloor(axis, setRates[axis], example);
            if (ask != null && ask.signum() > 0) setCents[axis] = ask.doubleValue();
        }
        maxStops = Math.max(0, rules.maxStops);
        shownRules = rules;
        autopilotOn = rules.autopilot;
        autopilotBar = rules.minimumScalePercent;
        exampleMiles = example.miles;
        exampleMinutes = example.minutes;
        exampleStops = example.stops;
        exampleFacts = example;
        restStale = true;
        needs = needs(rules, example);
        markOffers(recent, rules, example);
        barShape();
        rescale();
        setContentDescription(caption() + " " + describe());
        dropGoneFocus();
        invalidate();
        // Screen readers are told when the knobs, the badge or the offers changed; never for the bar alone, which
        // Autopilot moves by itself.
        String key = rules.rulesKey() + "/" + markTimes + "/" + markResults + "/" + newestEntryAt;
        if (!key.equals(nodesKey)) {
            nodesKey = key;
            nodesChanged();
        }
    }

    /**
     * Autopilot as the button shows it: on or off, the bar deciding offers now, and the acceptance rate below the goal
     * (an amber ring: the one rule the page's chip follows too, {@link AutopilotText.Status#belowGoal}). Quiet: the
     * button and the dashed shape follow, and nothing is announced, as Autopilot moves the bar by itself while the user
     * drives.
     */
    void setAutopilot(boolean on, int bar, boolean belowGoal) {
        int clamped = Math.max(1, Math.min(200, bar));
        if (on == autopilotOn && clamped == autopilotBar && belowGoal == this.belowGoal) return;
        boolean moved = clamped != autopilotBar;
        autopilotOn = on;
        autopilotBar = clamped;
        this.belowGoal = belowGoal;
        if (moved) {
            barShape();
            rescale();
        }
        invalidate();
    }

    /** Autopilot's dashed shape: the bar's share of each set minimum; none at exactly 100%. */
    private void barShape() {
        for (int i = 0; i < NAMES.length; i++) {
            barCents[i] = autopilotBar == FilterSettings.BAR_AT_MINIMUMS || Double.isNaN(setCents[i]) ? Double.NaN
                    : setCents[i] * autopilotBar / 100.0;
        }
    }

    /**
     * The ring scale the values shown ask for: three rings just past the highest minimum (or what the bar asks above
     * it), stretched a little towards offers above them; held still while a knob is held.
     */
    private void rescale() {
        double top = 0;
        for (int i = 0; i < NAMES.length; i++) {
            if (!Double.isNaN(setCents[i])) top = Math.max(top, setCents[i]);
            if (!Double.isNaN(barCents[i])) top = Math.max(top, barCents[i]);
        }
        double offers = 0;
        for (double[] mark : marks) {
            for (double cents : mark) if (Double.isFinite(cents)) offers = Math.max(offers, cents);
        }
        top = top > 0 ? Math.max(top, Math.min(offers, top * OFFER_STRETCH)) : offers;
        shownRing = top > 0 ? ringStep(top) : 0;
        shownOuter = shownRing * 3.0;
        // The rings hold still under a moving knob; they settle to fit when it is let go.
        if (!dragging) takeScale();
        inUnits();
        glideTo();
    }

    /**
     * Each recent standalone offer with pay, at what its own pay, pay per mile and pay per hour would pay for the
     * example offer. Only the offers on the skyline (its {@link #MARKS} newest), so each mark's ticket opens as its
     * building's does.
     */
    private void markOffers(List<DecisionLog.Entry> recent, FilterSettings rules, OfferSnapshot example) {
        newestEntryAt = recent == null || recent.isEmpty() ? -1 : recent.get(0).at;
        List<Long> before = new ArrayList<>(markTimes);
        List<float[]> drawn = new ArrayList<>();
        float glide = Motion.settle(glideStart, GLIDE_MS);
        for (int m = 0; m < markTo.size(); m++) drawn.add(markShown(m, glide));
        markResults.clear();
        marks.clear();
        markTimes.clear();
        markScores.clear();
        markFacts.clear();
        markEntries.clear();
        markFrom.clear();
        markTo.clear();
        if (recent == null) return;
        for (DecisionLog.Entry entry : recent.subList(0, Math.min(MARKS, recent.size()))) {
            OfferSnapshot offer = entry.facts;
            if (entry.addOn || offer.payCents == null || offer.payCents <= 0) continue;
            double pay = offer.payCents;
            double[] cents = new double[NAMES.length];
            Arrays.fill(cents, Double.NaN);
            cents[AreaScore.PAY] = pay;
            if (offer.miles != null && offer.miles > 0) cents[AreaScore.MILE] = pay * example.miles / offer.miles;
            if (offer.minutes != null && offer.minutes > 0) {
                cents[AreaScore.MINUTE] = pay * example.minutes / offer.minutes;
            }
            marks.add(cents);
            markResults.add(entry.result);
            markTimes.add(entry.at);
            markScores.add(AreaScore.scorePercent(rules, offer));
            markFacts.add(offer);
            markEntries.add(entry);
            // Glides from where the same offer was drawn, if it was.
            int was = before.indexOf(entry.at);
            markFrom.add(was >= 0 && was < drawn.size() ? drawn.get(was) : null);
            markTo.add(null);
        }
    }

    /** Where mark {@code m} is drawn now on each spoke, as shares of the outer ring. */
    private float[] markShown(int m, float glide) {
        float[] to = markTo.get(m);
        float[] from = markFrom.get(m);
        if (from == null || to == null) return to == null ? new float[NAMES.length] : to.clone();
        float[] at = new float[NAMES.length];
        for (int i = 0; i < at.length; i++) at[i] = from[i] + (to[i] - from[i]) * glide;
        return at;
    }

    /** The scale just worked out becomes the one drawn (never while a knob is held). */
    private void takeScale() {
        ringCents = shownRing;
        outer = shownOuter;
        rings = 3;
    }

    /** The minimums and Autopilot's shape as drawn: in cents of the example offer, the scale's own unit. */
    private void inUnits() {
        System.arraycopy(setCents, 0, set, 0, set.length);
        System.arraycopy(barCents, 0, auto, 0, auto.length);
    }

    /** Starts the points gliding from where they are now to the new values, if any moved. */
    private void glideTo() {
        float progress = Motion.settle(glideStart, GLIDE_MS);
        boolean moved = glideStart == 0;
        for (int i = 0; i < NAMES.length; i++) {
            moved |= fraction(set[i]) != setTo[i] || fraction(auto[i]) != autoTo[i];
        }
        float[][] nextMarks = new float[marks.size()][];
        for (int m = 0; m < marks.size(); m++) {
            nextMarks[m] = new float[NAMES.length];
            for (int i = 0; i < NAMES.length; i++) {
                double value = marks.get(m)[i];
                nextMarks[m][i] = Double.isNaN(value) || outer <= 0 ? 0 : (float) Math.min(MARK_REACH, value / outer);
            }
            float[] from = markFrom.get(m);
            moved |= from != null && !Arrays.equals(from, nextMarks[m]);
        }
        for (int m = 0; m < marks.size(); m++) {
            markTo.set(m, nextMarks[m]);
            if (!moved || markFrom.get(m) == null) markFrom.set(m, nextMarks[m]);
        }
        if (!moved) return;
        for (int i = 0; i < NAMES.length; i++) {
            setFrom[i] = setFrom[i] + (setTo[i] - setFrom[i]) * progress;
            autoFrom[i] = autoFrom[i] + (autoTo[i] - autoFrom[i]) * progress;
            setTo[i] = fraction(set[i]);
            autoTo[i] = fraction(auto[i]);
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
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.US, "%.1f", value);
    }

    /**
     * "An offer like 24 min · 6 mi · 2 stops needs $18.75 at your minimums.", or that it is declined for its stops, or
     * that any pay passes: the minimums as given, at exactly 100% (Autopilot's bar moves by itself and is not part of
     * it), applied to the example offer.
     */
    static String needs(FilterSettings rules, OfferSnapshot example) {
        String like = "An offer like " + example.minutes + " min · " + trim(example.miles) + " mi · " + example.stops
                + (example.stops == 1 ? " stop" : " stops");
        if (rules.maxStops > 0 && example.stops > rules.maxStops) {
            return like + " is declined: at most " + rules.maxStops + (rules.maxStops == 1 ? " stop." : " stops.");
        }
        OfferRule.Decision decision = OfferRule.evaluate(
                new OfferSnapshot(ANY_PAY, example.miles, example.minutes, example.stops),
                rules.withMinimumScalePercent(FilterSettings.BAR_AT_MINIMUMS));
        if (decision.result == OfferRule.Result.DECLINE) return like + " is declined by your rules.";
        if (decision.requiredCents > 0 && decision.requiredCents < ANY_PAY) {
            return like + " needs " + DecisionLog.money(decision.requiredCents) + " at your minimums.";
        }
        return like + (decision.result == OfferRule.Result.REVIEW ? " needs more offer details." : " passes at any pay.");
    }

    /** The line under the star: what the example offer needs. */
    String caption() {
        boolean anyMinimum = false;
        for (int axis : SPOKES) anyMinimum |= !Double.isNaN(setCents[axis]);
        return anyMinimum ? needs : needs + " No minimums yet.";
    }

    /** Cents the set minimum on spoke {@code axis} asks of the example offer; NaN where none is set (for tests). */
    double setAsks(int axis) {
        return setCents[axis];
    }

    /** Cents Autopilot's bar asks on spoke {@code axis} of the example offer; NaN where none (for tests). */
    double barAsks(int axis) {
        return barCents[axis];
    }

    /** "pay $4.00", "per mile $1.00", "per hour of trip time 15 dollars per hour", or "... off". */
    private String spokeSaid(int axis) {
        String name = axis == AreaScore.MINUTE ? "per hour of trip time" : NAMES[axis].toLowerCase(Locale.US);
        return name + " " + (setRates[axis] > 0 ? spokenRate(axis, setRates[axis]) : "off");
    }

    private String describe() {
        List<String> spokes = new ArrayList<>();
        for (int axis : SPOKES) spokes.add(spokeSaid(axis));
        String described = "Your minimums: " + String.join(", ", spokes) + (maxStops > 0 ? "; at most " + maxStops
                + (maxStops == 1 ? " stop." : " stops.") : "; no max stops.");
        int chosen = strongShape();
        if (chosen >= 0 && markScores.get(chosen) >= 0) {
            described += " " + (markTimes.get(chosen) == newestEntryAt ? "The newest offer" : "The chosen offer")
                    + " scores " + markScores.get(chosen) + "% of your minimums.";
            DecisionLog.Entry entry = markEntries.get(chosen);
            if (entry.model >= DecisionLog.MODEL && entry.scorePercent >= 0
                    && entry.scorePercent != markScores.get(chosen)) {
                described += " At decision it scored " + entry.scorePercent + "%.";
            }
        }
        described += " Farther out means higher pay or pay rates. Solid blue is your minimums; while Autopilot's bar is"
                + " not 100%, a dashed purple shape shows what it asks now; colored shapes are offers.";
        if (marks.isEmpty()) return described;
        return described + " The constellation shows the latest or selected offer. Choose an older offer on the skyline.";
    }

    // ---- The page's sky: one circle, placed by the page in the constellation's own box. ----

    /**
     * Draws the constellation as the page's sky: its circle of {@code radius} around ({@code x}, {@code y}), in this
     * view's pixels.
     */
    void compose(float x, float y, float radius) {
        boolean moved = x != skyX || y != skyY || radius != skyRadius;
        skyX = x;
        skyY = y;
        skyRadius = radius;
        if (moved) {
            restStale = true;
            invalidate();
        }
    }

    /** Drawn as the page's sky: the page has placed its circle (nothing is drawn until it has). */
    boolean backdrop() {
        return skyRadius > 0;
    }

    /** The circle's radius as the page's sky, in pixels; 0 when it is not the sky. */
    float skyRadius() {
        return backdrop() ? skyRadius : 0;
    }

    /** The circle's middle as the page's sky, in this view's pixels. */
    float skyX() {
        return skyX;
    }

    float skyY() {
        return skyY;
    }

    private float backdropIcon() {
        return ui.dp(BACKDROP_ICON_DP);
    }

    /**
     * As the sky, where spoke {@code axis}'s icon stands, into {@code out}: above an upper spoke's end and below a lower
     * one's; where the view has no room there, on the spoke's other side instead. The per-stop pin is there only with
     * the badge it anchors (the knobs on); hotspot and per item have none.
     */
    private boolean skyIcon(int axis, float cx, float cy, float radius, RectF out) {
        if (axis < 0 || axis >= ICONS.length) return false;
        if (axis == AreaScore.STOP && !knobsOn()) return false;
        float[] tip = point(cx, cy, radius, axis, 1);
        boolean right = axis == AreaScore.MILE || axis == AreaScore.MINUTE;
        boolean below = axis == AreaScore.MINUTE || axis == AreaScore.STOP;
        float size = backdropIcon();
        float middle = tip[0] + (right ? 1 : -1) * ui.dp(ICON_OUT_DP);
        for (int side = 0; side < 2; side++) {
            boolean under = below == (side == 0);
            float top = under ? tip[1] + ui.dp(ICON_GAP_DP) : tip[1] - ui.dp(ICON_GAP_DP) - size;
            out.set(middle - size / 2, top, middle + size / 2, top + size);
            if (out.top >= ui.dp(2) && out.bottom <= getHeight() - ui.dp(2)) return true;
        }
        return false;
    }

    /** As the sky, the icons' boxes as shown now (the Autopilot button's and the badge's too), in this view's pixels. */
    void iconsAt(List<RectF> out) {
        if (!backdrop()) return;
        for (int i = 0; i < ICONS.length; i++) {
            RectF box = new RectF();
            if (skyIcon(i, skyX, skyY, skyRadius, box)) out.add(box);
        }
        if (placeButton() > 0) out.add(new RectF(scoreBox));
        if (stopsShown()) out.add(new RectF(stopsBox));
    }

    /** As the sky, where the rings' dollars and the spokes' names stand now, halo and all, in this view's pixels. */
    void wordsAt(List<RectF> out) {
        if (!backdrop()) return;
        placeLevelLabels(skyX, skyY, skyRadius);
        for (RectF box : levelBoxes) {
            RectF halo = new RectF(box);
            halo.inset(-ui.dp(3), -ui.dp(3));
            out.add(halo);
        }
        placeAxisLabels();
        for (RectF box : axisLabels) if (!box.isEmpty()) {
            RectF halo = new RectF(box);
            halo.inset(-ui.dp(2), -ui.dp(2));
            out.add(halo);
        }
    }

    /** The constellation spells out each icon's name beside it. */
    String axisLabelText(int axis) {
        return axis >= 0 && axis < AXIS_LABELS.length ? AXIS_LABELS[axis] : "";
    }

    /** Where spoke {@code axis}'s name stands now, in this view's pixels; null while it is gone (for tests). */
    RectF axisLabelBox(int axis) {
        if (axis < 0 || axis >= axisLabels.length) return null;
        placeAxisLabels();
        return axisLabels[axis].isEmpty() ? null : new RectF(axisLabels[axis]);
    }

    /** How much of spoke {@code axis}'s name is there now: 1 wholly, 0 gone, between while it fades (for tests). */
    float axisLabelShown(int axis) {
        if (axis < 0 || axis >= axisLabels.length) return 0;
        placeAxisLabels();
        return axisShown[axis];
    }

    /**
     * Where each spoke's name stands, and how much of it is there. Each has one place at every size: beside its icon,
     * on the side towards the middle (pay's on its right, per mile's and per hour's on their left), and the max stops
     * name over the badge and its pin. No knob, shape or offer's mark ever stands there (they stand on the spokes, which
     * run from the icons' other sides), so as the window's size changes a name moves only with its icon; where the
     * circle grows too small for the names, or the Autopilot button, the badge, the rings' dollars or a name before it
     * comes onto its place, it fades out rather than move (the icons and screen readers still say what each spoke is).
     */
    private void placeAxisLabels() {
        for (RectF box : axisLabels) box.setEmpty();
        Arrays.fill(axisShown, 0);
        if (!backdrop() || getWidth() <= 0 || getHeight() <= 0) return;
        float small = within(skyRadius - ui.dp(NAMES_FROM_DP), ui.dp(FluidLayout.RADIUS_LEAST_DP - NAMES_FROM_DP));
        if (small <= 0) return;
        boolean knobs = knobsOn();
        float button = knobs ? placeButton() : 0;
        boolean badge = knobs && stopsShown();
        placeLevelLabels(skyX, skyY, skyRadius);
        float height = Math.max(ui.dp(11), Ui.lineHeight(axisText));
        float gap = ui.dp(3);
        float margin = ui.dp(4);
        RectF padded = new RectF();
        for (int axis : AreaScore.DRAW_ORDER) {
            if (AXIS_LABELS[axis].isEmpty() || !skyIcon(axis, skyX, skyY, skyRadius, iconBox)) continue;
            float width = Math.max(ui.dp(1), axisText.measureText(AXIS_LABELS[axis]));
            float left = axis == AreaScore.PAY ? iconBox.right + gap
                    : axis == AreaScore.STOP ? iconBox.left : iconBox.left - gap - width;
            float top = axis == AreaScore.STOP
                    ? Math.min(iconBox.top, badge ? stopsBox.top : iconBox.top) - margin - height
                    : iconBox.centerY() - height / 2;
            left = Math.max(margin, Math.min(getWidth() - margin - width, left));
            RectF box = axisLabels[axis];
            box.set(left, top, left + width, top + height);
            padded.set(box);
            padded.inset(-ui.dp(2), -ui.dp(2));
            // Its own room: inside the view, off the icons and the knobs.
            float room = Math.min(box.top - margin, getHeight() - margin - box.bottom);
            for (int i = 0; i < ICONS.length; i++) {
                if (skyIcon(i, skyX, skyY, skyRadius, iconBox)) room = Math.min(room, apart(padded, iconBox));
            }
            if (knobs) for (int i : SPOKES) {
                float[] at = point(skyX, skyY, skyRadius, i, knobFraction(i, 1));
                room = Math.min(room, outside(padded, at[0], at[1]) - ui.dp(9));
            }
            float shown = Math.min(small, nameFade(room));
            // Then what it gives way to: the badge (always wholly there), and each of the rest only as much as it is.
            if (badge) shown = Math.min(shown, nameFade(apart(padded, stopsBox)));
            if (button > 0) {
                float off = outside(padded, buttonX, buttonY) - ui.dp(BUTTON_DP) / 2f;
                shown = Math.min(shown, Math.max(nameFade(off), 1 - button));
            }
            for (int i = 0; i < levelBoxes.size(); i++) {
                shown = Math.min(shown, Math.max(nameFade(apart(padded, levelBoxes.get(i))), 1 - levelShown.get(i)));
            }
            for (int before : AreaScore.DRAW_ORDER) {
                if (before == axis) break;
                if (axisLabels[before].isEmpty()) continue;
                shown = Math.min(shown, Math.max(nameFade(apart(padded, axisLabels[before])), 1 - axisShown[before]));
            }
            axisShown[axis] = shown;
            if (shown <= 0) box.setEmpty();
        }
    }

    /** How far apart two boxes stand: the gap between them, or, where they overlap, minus how far they overlap. */
    private static float apart(RectF a, RectF b) {
        return Math.max(Math.max(b.left - a.right, a.left - b.right), Math.max(b.top - a.bottom, a.top - b.bottom));
    }

    /** How far ({@code x}, {@code y}) stands outside {@code box}; 0 inside it. */
    private static float outside(RectF box, float x, float y) {
        float dx = Math.max(0, Math.max(box.left - x, x - box.right));
        float dy = Math.max(0, Math.max(box.top - y, y - box.bottom));
        return (float) Math.hypot(dx, dy);
    }

    /** {@code amount} of {@code range}, from 0 to 1. */
    private static float within(float amount, float range) {
        return range <= 0 ? (amount >= 0 ? 1 : 0) : Math.max(0, Math.min(1, amount / range));
    }

    /**
     * How much of a name (or a ring's dollars) is there with {@code room} to spare around it (counted from a little
     * outside it): wholly while what it keeps clear of does not touch it, fading as that comes onto it, gone once it is
     * {@link #NAME_FADE_DP} onto it.
     */
    private float nameFade(float room) {
        return within(room + ui.dp(NAME_FADE_DP + 2), ui.dp(NAME_FADE_DP));
    }

    /** The names, each as much as it is there; under the badge and the button, so one fading out goes behind them. */
    private void drawAxisLabels(Canvas canvas) {
        placeAxisLabels();
        int alpha = axisText.getAlpha();
        for (int i = 0; i < axisLabels.length; i++) {
            RectF box = axisLabels[i];
            if (box.isEmpty()) continue;
            axisText.setAlpha(Math.round(alpha * axisShown[i]));
            canvas.drawText(AXIS_LABELS[i], box.left, box.top - axisText.ascent(), axisText);
        }
        axisText.setAlpha(alpha);
    }

    /** The rings' dollars as the sky shows them now, left to right (for tests). */
    List<String> levelWords() {
        if (backdrop()) placeLevelLabels(skyX, skyY, skyRadius);
        return new ArrayList<>(levelWords);
    }

    /**
     * As the sky, only a touch on the circle (an icon at a spoke's end, a knob, the button, the badge, an offer's mark)
     * is for the constellation.
     */
    private boolean onSky(float x, float y) {
        float reach = skyRadius + ui.dp(8);
        float dx = x - skyX;
        float dy = y - skyY;
        if (dx * dx + dy * dy <= reach * reach || target(x, y) != NO_NODE || offerAt(x, y) >= 0) return true;
        return onIcon(x, y, ui.dp(8));
    }

    /** Whether ({@code x}, {@code y}) is on a spoke's icon as the sky shows it, or within {@code around} of one. */
    private boolean onIcon(float x, float y, float around) {
        for (int i = 0; i < ICONS.length; i++) {
            if (!skyIcon(i, skyX, skyY, skyRadius, iconBox)) continue;
            iconBox.inset(-around, -around);
            if (iconBox.contains(x, y)) return true;
        }
        return false;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (backdrop() && event.getActionMasked() == MotionEvent.ACTION_DOWN && !onSky(event.getX(), event.getY())) {
            return false;
        }
        return super.dispatchTouchEvent(event);
    }

    /**
     * As the sky, a finger exploring the screen with a screen reader on finds each knob, the badge, the button and each
     * marked offer (as a tap would pick it) by itself; the rest of the circle is the chart as a whole.
     */
    @Override public boolean dispatchHoverEvent(MotionEvent event) {
        boolean leaving = event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT;
        if (backdrop() && !leaving && !onSky(event.getX(), event.getY())) {
            hover(NO_NODE);
            return false;
        }
        if ((knobsOn() || offersOn()) && exploring()) {
            int node = leaving ? NO_NODE : target(event.getX(), event.getY());
            if (!leaving && node == NO_NODE) {
                int offer = offerAt(event.getX(), event.getY());
                if (offer >= 0) node = OFFER_ID + offer;
            }
            hover(node);
            if (node != NO_NODE) return true;
        }
        return super.dispatchHoverEvent(event);
    }

    /** Whatever the page gives it (its own box); asked with no limit, a circle of the least radius the page uses. */
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int least = Math.round(2 * ui.dp(FluidLayout.RADIUS_LEAST_DP) + 2 * ui.dp(ICON_GAP_DP + BACKDROP_ICON_DP));
        setMeasuredDimension(width, resolveSize(least, heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        if (!backdrop()) return;
        drawSky(canvas);
        nextFrame();
    }

    /** A glide to new values (after a drag, say) draws every frame; the rest is steady motion. */
    private void nextFrame() {
        if (Motion.settle(glideStart, GLIDE_MS) < 1 || beckoning()) Motion.settling(this);
        else Motion.next(this);
    }

    /**
     * As the page's sky: the rings, spokes, offers, shapes and icons in one layer, the rings fading out at the view's
     * edges; then, over it, the rings' dollars in their halos, and the controls.
     */
    private void drawSky(Canvas canvas) {
        float cx = skyX;
        float cy = skyY;
        float radius = skyRadius;
        detail = skyDetail();
        int layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
        drawGrid(canvas, cx, cy, radius);
        fadeEdges(canvas, cx, cy, radius);
        drawOfferShapes(canvas, cx, cy, radius);
        drawMarks(canvas, cx, cy, radius);
        float glide = Motion.settle(glideStart, GLIDE_MS);
        holdSet();
        drawMinimums(canvas, cx, cy, radius, glide);
        drawChosenOutline(canvas, cx, cy, radius);
        boolean knobs = knobsOn();
        if (knobs) drawKnobs(canvas, cx, cy, radius, glide);
        for (int i = 0; i < ICONS.length; i++) {
            if (!skyIcon(i, cx, cy, radius, iconBox)) continue;
            icons[i].setBounds(Math.round(iconBox.left), Math.round(iconBox.top), Math.round(iconBox.right),
                    Math.round(iconBox.bottom));
            icons[i].draw(canvas);
        }
        canvas.restoreToCount(layer);
        // The held knob's readout is placed first, so the rings' dollars it would cover step aside for it.
        if (readoutAxis() >= 0) placeReadout(cx, cy, radius);
        drawLevelLabels(canvas, cx, cy, radius);
        if (!knobs) return;
        // Over everything: the names (each as much as it is there), the badge and the button over them, and the knob
        // under the finger (with what it is set to, once it moves).
        drawAxisLabels(canvas);
        if (stopsShown()) drawStops(canvas);
        float button = placeButton();
        if (button > 0) drawAutopilot(canvas, button);
        if (dragging) {
            drawHeld(canvas, point(cx, cy, radius, held, heldFraction()), dragValue, true);
        } else if (pressing()) {
            drawHeld(canvas, point(cx, cy, radius, held, knobFraction(held, glide)), setRates[held], true);
        } else if (readoutAxis() >= 0) {
            int axis = readoutAxis();
            drawHeld(canvas, point(cx, cy, radius, axis, knobFraction(axis, glide)), setRates[axis], true);
        }
    }

    /** As the sky, the share of their full size the points are drawn at: a large circle draws them a little larger. */
    private float skyDetail() {
        return Math.max(0.6f, Math.min(1, skyRadius / ui.dp(45)))
                + Math.max(0, Math.min(0.35f, (skyRadius - ui.dp(100)) / ui.dp(150)));
    }

    /**
     * Where the circle runs past the view's edges (its box's: the rim may stand wider than the spokes' icons), its rings
     * fade out over the last stretch before the edge rather than stop at it.
     */
    private void fadeEdges(Canvas canvas, float cx, float cy, float radius) {
        float band = ui.dp(EDGE_FADE_DP);
        float height = getHeight();
        float width = getWidth();
        if (cy - radius < band || cy + radius > height - band) {
            if (edgeFadeFor != height) {
                edgeFadeFor = height;
                float top = band / height;
                edgeFade.setShader(new android.graphics.LinearGradient(0, 0, 0, height,
                        new int[] {0xFF000000, 0x00000000, 0x00000000, 0xFF000000},
                        new float[] {0, Math.min(0.5f, top), Math.max(0.5f, 1 - top), 1},
                        android.graphics.Shader.TileMode.CLAMP));
            }
            canvas.drawRect(0, 0, width, height, edgeFade);
        }
        if (cx - radius < band || cx + radius > width - band) {
            if (sideFadeFor != width) {
                sideFadeFor = width;
                float side = band / width;
                sideFade.setShader(new android.graphics.LinearGradient(0, 0, width, 0,
                        new int[] {0xFF000000, 0x00000000, 0x00000000, 0xFF000000},
                        new float[] {0, Math.min(0.5f, side), Math.max(0.5f, 1 - side), 1},
                        android.graphics.Shader.TileMode.CLAMP));
            }
            canvas.drawRect(0, 0, width, height, sideFade);
        }
    }

    /**
     * As the sky, the middle and outer rings' dollars along the level line on the right, each just inside its ring, as
     * much of each as is there; one under the held knob's readout is left out while it shows.
     */
    private void drawLevelLabels(Canvas canvas, float cx, float cy, float radius) {
        placeLevelLabels(cx, cy, radius);
        Paint.FontMetrics metrics = levelText.getFontMetrics();
        float baseline = cy - (metrics.ascent + metrics.descent) / 2;
        levelText.setTextAlign(Paint.Align.LEFT);
        int alpha = levelText.getAlpha();
        for (int i = 0; i < levelBoxes.size(); i++) {
            if (readoutAxis() >= 0 && RectF.intersects(levelBoxes.get(i), pillBox)) continue;
            levelText.setAlpha(Math.round(alpha * levelShown.get(i)));
            // Twice, so the halo is soft but full.
            canvas.drawText(levelWords.get(i), levelBoxes.get(i).left, baseline, levelText);
            canvas.drawText(levelWords.get(i), levelBoxes.get(i).left, baseline, levelText);
        }
        levelText.setAlpha(alpha);
    }

    /**
     * Works out where the rings' dollars stand (see {@link #drawLevelLabels}), into the level lists, with how much of
     * each is there. Each has one place at every size, just inside its ring on the level line, so as the window's size
     * changes it moves only with the circle; a shape's edge may run under it (its halo keeps it clear to read), and
     * where a spoke's icon, the view's edge or the inner ring's dollars come onto it, it fades out rather than move.
     */
    private void placeLevelLabels(float cx, float cy, float radius) {
        levelBoxes.clear();
        levelWords.clear();
        levelShown.clear();
        if (outer <= 0) return;
        Paint.FontMetrics metrics = levelText.getFontMetrics();
        float baseline = cy - (metrics.ascent + metrics.descent) / 2;
        RectF inner = null;
        float innerShown = 0;
        for (int ring : LABELED_RINGS) {
            String words = levelLabel(ring);
            float width = levelText.measureText(words);
            float right = cx + radius * ring / rings - ui.dp(5);
            RectF box = new RectF(right - width, baseline + metrics.ascent, right, baseline + metrics.descent);
            float room = Math.min(box.left - ui.dp(6), getWidth() - ui.dp(6) - box.right);
            for (int i = 0; i < ICONS.length; i++) {
                if (skyIcon(i, cx, cy, radius, iconBox)) room = Math.min(room, apart(box, iconBox));
            }
            float shown = nameFade(room);
            if (inner != null) {
                shown = Math.min(shown, Math.max(nameFade(box.left - inner.right - ui.dp(8)), 1 - innerShown));
            }
            if (shown > 0) {
                levelBoxes.add(box);
                levelWords.add(words);
                levelShown.add(shown);
            }
            inner = box;
            innerShown = shown;
        }
    }

    /** A ring's label: its dollars ("$12"). */
    private String levelLabel(int ring) {
        return "$" + (ringCents * ring / 100);
    }

    /** The rings that are labeled: the middle and the outer ring. */
    private static final int[] LABELED_RINGS = {2, 3};

    /** Faint rings and the three spokes. */
    private void drawGrid(Canvas canvas, float cx, float cy, float radius) {
        line.setPathEffect(null);
        line.setStrokeWidth(Math.max(1, ui.dp(1)));
        // Keep the useful reference ring; the rest is a quiet guide behind the offer and minimums.
        boolean adjusting = readoutAxis() >= 0;
        for (int ring = 1; ring <= rings; ring++) {
            boolean reference = ring == rings;
            line.setColor(ui.dark ? (reference || adjusting ? 0x24FFFFFF : 0x0EFFFFFF)
                    : (reference || adjusting ? 0x180B2A55 : 0x0A0B2A55));
            canvas.drawCircle(cx, cy, radius * ring / rings, line);
        }
        line.setColor(ui.dark ? 0x20FFFFFF : 0x160B2A55);
        for (int i : SPOKES) {
            float[] tip = point(cx, cy, radius, i, 1);
            canvas.drawLine(cx, cy, tip[0], tip[1], line);
        }
    }

    /**
     * Where {@code fraction} of the way along spoke {@code axis} is, moved {@code aside} pixels square to the spoke
     * (clockwise positive).
     */
    private float[] point(float cx, float cy, float radius, int axis, double fraction, float aside) {
        double angle = Math.toRadians(ANGLES[axis]);
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        return new float[] {(float) (cx + cos * radius * fraction) - sin * aside,
                (float) (cy + sin * radius * fraction) + cos * aside};
    }

    private float[] point(float cx, float cy, float radius, int axis, double fraction) {
        return point(cx, cy, radius, axis, fraction, 0);
    }

    /** How far out a value sits on the scale (cents), the outer ring being 1. */
    private float fraction(double value) {
        if (Double.isNaN(value) || outer <= 0) return 0;
        return (float) Math.min(1, value / outer);
    }

    /** The displayed offer's observed marks; a touch or open ticket adds emphasis without revealing old overlays. */
    private void drawMarks(Canvas canvas, float cx, float cy, float radius) {
        float glide = Motion.settle(glideStart, GLIDE_MS);
        for (int m = 0; m < marks.size(); m++) if (displayedOffer(m)) {
            drawOfferMarks(canvas, cx, cy, radius, m, glide, m == opened() ? 2 : m == pressed() ? 1 : 0);
        }
    }

    /** Offers side by side on a spoke step a little aside so they do not hide one another. */
    private float markAside(int m, float detail) {
        return ((m % 5) - 2) * ui.dp(MARK_STEP_DP) * detail;
    }

    /**
     * Offer {@code m}'s marks: {@code lift} 0 as they are, 1 lightly picked out in a soft halo (a finger on it), 2
     * larger and in full in a halo (its ticket open).
     */
    private void drawOfferMarks(Canvas canvas, float cx, float cy, float radius, int m, float glide, int lift) {
        double[] mark = marks.get(m);
        float[] shown = markShown(m, glide);
        OfferRule.Result result = markResults.get(m);
        int color = markColor(result) & 0x00FFFFFF;
        int alpha = m == 0 || lift > 0 ? 0xFF : Math.max(0x60, 0xE0 - m * 0x0C);
        float aside = markAside(m, detail);
        for (int axis : SPOKES) {
            if (Double.isNaN(mark[axis]) || outer <= 0) continue;
            float[] at = point(cx, cy, radius, axis, shown[axis], aside);
            if (lift > 0) {
                fill.setColor(color | (lift == 2 ? 0x40000000 : 0x2C000000));
                canvas.drawCircle(at[0], at[1], ui.dp(lift == 2 ? 8 : 10) * detail, fill);
            }
            if (m == 0 && lift > 0) {
                float pulse = Motion.on() ? Motion.loop(2.4f, 0) : 1;
                line.setPathEffect(null);
                line.setStrokeWidth(Math.max(1, ui.dp(1)));
                line.setColor(color | ((int) (0x90 * (1 - pulse)) << 24));
                canvas.drawCircle(at[0], at[1], (ui.dp(4) + ui.dp(7) * pulse) * detail, line);
            }
            drawMark(canvas, at[0], at[1], result, color | (alpha << 24), lift == 2 ? 1.45f : 1);
        }
    }

    /** ● passed, ✕ declined, ○ review: the shape carries the outcome, not only the color; at {@code scale}. */
    private void drawMark(Canvas canvas, float x, float y, OfferRule.Result result, int color, float scale) {
        float size = ui.dp(3.2f) * detail * scale;
        if (result == OfferRule.Result.KEEP) {
            fill.setColor(color);
            canvas.drawCircle(x, y, size, fill);
            return;
        }
        line.setPathEffect(null);
        line.setColor(color);
        line.setStrokeWidth(ui.dp(1.6f) * Math.min(scale, 1.25f));
        if (result == OfferRule.Result.DECLINE) {
            canvas.drawLine(x - size, y - size, x + size, y + size, line);
            canvas.drawLine(x - size, y + size, x + size, y - size, line);
        } else {
            canvas.drawCircle(x, y, size, line);
        }
    }

    /**
     * The minimums: the set shape (solid, filled, with its stars, under the knobs); then, while Autopilot's bar is not
     * 100%, its dashed purple shape at that share of each minimum, what Autopilot asks now.
     */
    private void drawMinimums(Canvas canvas, float cx, float cy, float radius, float glide) {
        drawShape(canvas, cx, cy, radius, drawnSet, drawnFrom, drawnTo, glide, setColor(), false);
        drawShape(canvas, cx, cy, radius, auto, autoFrom, autoTo, glide, autopilotColor(), true);
    }

    /**
     * Clockwise drawn order; an empty half-plane closes through the center, matching the score's neighbors. This
     * avoids negative-area cancellation and self-crossings for sparse sets.
     */
    private static List<float[]> orderedPolygon(float[][] axes, float cx, float cy) {
        List<float[]> points = new ArrayList<>();
        int first = -1;
        int previous = -1;
        int count = 0;
        for (float[] point : axes) if (point != null) count++;
        for (int axis : AreaScore.DRAW_ORDER) {
            if (axes[axis] == null) continue;
            if (first < 0) first = axis;
            else if (count >= 3 && emptySector(previous, axis)) points.add(new float[] {cx, cy});
            points.add(axes[axis]);
            previous = axis;
        }
        if (count >= 3 && previous != first && first >= 0 && emptySector(previous, first)) {
            points.add(new float[] {cx, cy});
        }
        return points;
    }

    private static boolean emptySector(int from, int to) {
        float gap = ((ANGLES[to] - ANGLES[from]) % 360 + 360) % 360;
        return AreaScore.closesThroughCenter(from, to) && gap > 180;
    }

    /**
     * A minimums shape through the three spokes as heading ({@code to}) or drawn now: a spoke with no value at the
     * middle, so one minimum alone is a line out from it; null when there is none at all.
     */
    private List<float[]> shapePolygon(double[] values, float[] from, float[] to, float glide, float cx, float cy,
                                       float radius) {
        boolean any = false;
        for (int axis : SPOKES) any |= !Double.isNaN(values[axis]);
        if (!any) return null;
        float[][] axes = new float[NAMES.length][];
        for (int axis : SPOKES) {
            float shown = Double.isNaN(values[axis]) ? 0 : from[axis] + (to[axis] - from[axis]) * glide;
            axes[axis] = point(cx, cy, radius, axis, shown);
        }
        return orderedPolygon(axes, cx, cy);
    }

    /** The latest or skyline-selected offer's polygon; hidden history stays out of the everyday constellation. */
    private void drawOfferShapes(Canvas canvas, float cx, float cy, float radius) {
        float glide = Motion.settle(glideStart, GLIDE_MS);
        for (int m = 0; m < marks.size(); m++) if (displayedOffer(m)) {
            drawOfferShape(canvas, cx, cy, radius, m, glide, true, m == pressed());
        }
    }

    /** The ticket's offer, or the skyline's selection or latest: the one offer shown. */
    private int strongShape() {
        // An open unplottable offer must not reveal a different offer behind its ticket.
        return backdrop() && openAt >= 0 ? opened() : emphasized();
    }

    /** One presentation predicate shared by drawing, touch targets and accessibility nodes. History stays intact. */
    private boolean displayedOffer(int m) {
        return m >= 0 && m < marks.size() && m == strongShape();
    }

    /** The marked offer whose ticket is open, as the sky shows it; -1 for none. */
    private int opened() {
        return openAt < 0 || !backdrop() ? -1 : markTimes.indexOf(openAt);
    }

    /** The marked offer a finger is on; -1 for none. */
    private int pressed() {
        return pressedAt < 0 ? -1 : markTimes.indexOf(pressedAt);
    }

    /** The chosen offer's outline once more, over the minimums' filled shape, so it reads where they cross. */
    private void drawChosenOutline(Canvas canvas, float cx, float cy, float radius) {
        int chosen = strongShape();
        if (chosen < 0) return;
        List<float[]> points = offerPolygon(cx, cy, radius, chosen, Motion.settle(glideStart, GLIDE_MS));
        if (points.size() < 2) return;
        tracePath(points);
        line.setPathEffect(null);
        line.setColor((markColor(markResults.get(chosen)) & 0x00FFFFFF) | 0xC0000000);
        line.setStrokeWidth(ui.dp(1.8f) * Math.max(1, detail));
        canvas.drawPath(path, line);
    }

    /** {@code points} as the closed path to draw. */
    private void tracePath(List<float[]> points) {
        path.reset();
        for (int i = 0; i < points.size(); i++) {
            if (i == 0) path.moveTo(points.get(i)[0], points.get(i)[1]);
            else path.lineTo(points.get(i)[0], points.get(i)[1]);
        }
        path.close();
    }

    /** Offer {@code m}'s polygon: faint, a little stronger under a finger ({@code pressed}), or {@code strong}. */
    private void drawOfferShape(Canvas canvas, float cx, float cy, float radius, int m, float glide, boolean strong,
                                boolean pressed) {
        List<float[]> points = offerPolygon(cx, cy, radius, m, glide);
        if (points.size() < 2) return;
        tracePath(points);
        int color = markColor(markResults.get(m)) & 0x00FFFFFF;
        if (points.size() > 2) {
            int alpha = strong ? (pressed ? 0x28000000 : 0x12000000) : pressed ? 0x1C000000 : OFFER_FILL;
            fill.setColor(color | alpha);
            canvas.drawPath(path, fill);
        }
        line.setPathEffect(null);
        line.setColor(color | (strong ? 0xE0000000 : pressed ? 0x90000000 : OFFER_LINE));
        line.setStrokeWidth(strong ? ui.dp(1.8f) * Math.max(1, detail)
                : pressed ? ui.dp(1.4f) * Math.max(1, detail) : Math.max(1, ui.dp(0.8f)));
        canvas.drawPath(path, line);
    }

    /** Mark {@code m}'s points as drawn now, on the spokes it has a value for, in their order. */
    private List<float[]> offerPolygon(float cx, float cy, float radius, int m, float glide) {
        float[][] axes = new float[NAMES.length][];
        double[] mark = marks.get(m);
        float[] shown = markShown(m, glide);
        for (int i : SPOKES) {
            if (!Double.isNaN(mark[i]) && outer > 0) axes[i] = point(cx, cy, radius, i, shown[i]);
        }
        return orderedPolygon(axes, cx, cy);
    }

    /** The offer chosen on the skyline, else the newest; -1 for none. */
    private int emphasized() {
        long selectedAt = emphasizedAt < 0 ? newestEntryAt : emphasizedAt;
        return selectedAt < 0 ? -1 : markTimes.indexOf(selectedAt);
    }

    /** The skyline selection ({@code null}: latest) is the one displayed, with its spoken score. */
    void emphasize(DecisionLog.Entry entry) {
        long at = entry == null ? -1 : entry.at;
        if (at == emphasizedAt) return;
        emphasizedAt = at;
        setContentDescription(caption() + " " + describe());
        dropGoneFocus();
        invalidate();
        nodesChanged();
    }

    /**
     * The offer whose ticket is open ({@code null}: none): as the sky, its polygon and marks stand out over the rest
     * until the ticket closes.
     */
    void showTicket(DecisionLog.Entry entry) {
        long at = entry == null ? -1 : entry.at;
        if (at == openAt) return;
        openAt = at;
        setContentDescription(caption() + " " + describe());
        dropGoneFocus();
        invalidate();
        nodesChanged();
    }

    /**
     * The chosen offer's score as the chart works it out from the rules as shown; while a knob is held, from the
     * minimum the knob asks; -1 when none can be worked out (for tests too).
     */
    int emphasizedScore() {
        int chosen = strongShape();
        if (chosen < 0) return -1;
        if (!dragging || held < 0) return markScores.get(chosen);
        int[] rates = shownRules.minimums();
        rates[held] = dragValue;
        return AreaScore.scorePercent(shownRules.withMinimums(rates[AreaScore.PAY], rates[AreaScore.MILE],
                rates[AreaScore.MINUTE]), markFacts.get(chosen));
    }

    /**
     * A minimums shape through the three spokes: the set one solid, filled lightly, with a round star at each set
     * minimum; Autopilot's ({@code dashed}) only its dashed outline, with a small dot at each point.
     */
    private void drawShape(Canvas canvas, float cx, float cy, float radius, double[] values, float[] from, float[] to,
                           float glide, int color, boolean dashed) {
        List<float[]> outline = shapePolygon(values, from, to, glide, cx, cy, radius);
        if (outline == null) return;
        tracePath(outline);
        if (!dashed && outline.size() > 2) {
            fill.setColor((color & 0x00FFFFFF) | 0x30000000);
            canvas.drawPath(path, fill);
        }
        line.setColor(color);
        line.setStrokeWidth(ui.dp(2) * Math.max(1, detail));
        line.setPathEffect(dashed ? dash : null);
        canvas.drawPath(path, line);
        line.setPathEffect(null);
        for (int i : SPOKES) {
            if (Double.isNaN(values[i])) continue;
            float[] at = point(cx, cy, radius, i, from[i] + (to[i] - from[i]) * glide);
            fill.setColor(color);
            if (dashed) {
                canvas.drawCircle(at[0], at[1], ui.dp(2.6f) * detail, fill);
                continue;
            }
            fill.setColor((color & 0x00FFFFFF) | 0x3A000000);
            canvas.drawCircle(at[0], at[1], ui.dp(8) * detail, fill);
            fill.setColor(color);
            canvas.drawCircle(at[0], at[1], ui.dp(3.8f) * detail, fill);
            fill.setColor(0xFFFFFFFF);
            canvas.drawCircle(at[0], at[1], ui.dp(1.6f) * detail, fill);
        }
    }

    // ---- Knobs: the set minimums, dragged along their spokes (as the sky only). ----

    /** Knobs, the badge and the button are on: as the page's sky, once the page has placed it and said how it saves. */
    private boolean knobsOn() {
        return backdrop() && changes != null;
    }

    /**
     * Spoke {@code axis} has a knob: its set minimum's, or a hollow one resting just outside the middle. With no rule
     * at all the three hollow knobs are how the first one is set, by a drag. The retired spokes have none.
     */
    private boolean knobShown(int axis) {
        return spoke(axis);
    }

    /** The hollow knobs swell out twice, as a pointer to where a first rule is set (and screen readers hear why). */
    void beckon() {
        beckonedAt = SystemClock.uptimeMillis();
        invalidate();
    }

    /** The hollow knobs are swelling, with Android's animations on. */
    private boolean beckoning() {
        long since = SystemClock.uptimeMillis() - beckonedAt;
        return beckonedAt > 0 && Motion.on() && since >= 0 && since < BECKON_MS;
    }

    /** Whether the knobs beckoned and are swelling now (for tests: true until it is over, animations on or off). */
    boolean beckoned() {
        long since = SystemClock.uptimeMillis() - beckonedAt;
        return beckonedAt > 0 && since >= 0 && since < BECKON_MS;
    }

    /** A finger rests on a knob that has not moved yet: it is drawn large, as it will be while dragged. */
    private boolean pressing() {
        return touching && held >= 0 && !dragging;
    }

    /** Where spoke {@code axis}'s hollow knob rests, as a share of the radius ({@link #placeRests}). */
    private float restFraction(int axis) {
        if (skyRadius <= 0) return 0;
        if (restStale) placeRests();
        return restAt[axis] / skyRadius;
    }

    /**
     * Where each hollow knob rests: {@link #KNOB_REST_DP} out along its spoke, or, where that place is too near the
     * view's edge, the nearest place further out along it with its middle at least {@link #REST_CLEAR_DP} inside the
     * view, so a first rule can always be dragged; failing that, where it was.
     */
    private void placeRests() {
        restStale = false;
        float least = ui.dp(KNOB_REST_DP);
        for (int axis : SPOKES) {
            restAt[axis] = least;
            if (skyRadius <= 0) continue;
            float most = Math.min(skyRadius * 0.8f, pushLimit(axis));
            RectF around = new RectF();
            float clear = ui.dp(REST_CLEAR_DP);
            for (float d = least; d <= most; d += ui.dp(REST_STEP_DP)) {
                float[] at = point(skyX, skyY, skyRadius, axis, d / skyRadius);
                around.set(at[0] - clear, at[1] - clear, at[0] + clear, at[1] + clear);
                if (around.left < 0 || around.top < 0 || around.right > getWidth() || around.bottom > getHeight()) {
                    continue;
                }
                restAt[axis] = d;
                break;
            }
        }
    }

    /** How far out spoke {@code axis}'s knob stands now, as a share of the radius: at its set minimum, or resting. */
    private float knobFraction(int axis, float glide) {
        if (dragging && axis == held) return heldFraction();
        if (Double.isNaN(set[axis])) return restFraction(axis);
        return setFrom[axis] + (setTo[axis] - setFrom[axis]) * glide;
    }

    /** Where the held knob stands, on the scale held still for the drag, at most as far as it may be pushed. */
    private float heldFraction() {
        if (dragValue <= 0 || outer <= 0) return restFraction(held);
        float at = (float) (askCents(held, dragValue) / outer);
        return Math.min(at, pushLimit(held) / skyRadius);
    }

    /** Pay the held knob's rate asks of the example offer (as it was when taken), as the set shape draws it. */
    private double askCents(int axis, int rate) {
        java.math.BigDecimal ask = AreaScore.fixedFloor(axis, rate, dragFacts);
        return ask == null ? 0 : ask.doubleValue();
    }

    /** The example offer's amount of spoke {@code axis}'s unit when the knob was taken: 1 for pay, else miles... */
    private double units(int axis) {
        switch (axis) {
            case AreaScore.MILE: return dragMiles;
            case AreaScore.MINUTE: return dragMinutes;
            default: return 1;
        }
    }

    /** A finger's distance out along spoke {@code axis}, square onto it. */
    private float along(int axis, float x, float y) {
        double angle = Math.toRadians(ANGLES[axis]);
        return (float) ((x - skyX) * Math.cos(angle) + (y - skyY) * Math.sin(angle));
    }

    /** How far out along spoke {@code axis} a knob may be pushed: a little past the outer ring, inside the page. */
    private float pushLimit(int axis) {
        double angle = Math.toRadians(ANGLES[axis]);
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        float margin = ui.dp(10);
        float most = skyRadius * PUSH;
        if (cos > 0) most = Math.min(most, (getWidth() - margin - skyX) / cos);
        if (cos < 0) most = Math.min(most, (margin - skyX) / cos);
        if (sin > 0) most = Math.min(most, (getHeight() - margin - skyY) / sin);
        if (sin < 0) most = Math.min(most, (margin - skyY) / sin);
        return Math.max(ui.dp(KNOB_REST_DP) + 1, most);
    }

    /**
     * The rate the held knob {@code distance} out along spoke {@code axis} sets: its pay on the shared scale, as the
     * example offer's rate, to the nearest step; 0 (off) inside the resting place (or, for a knob that set out inside
     * it, a clear way further in); at most {@link FilterSettings#MOST_CENTS}.
     */
    private int rateAt(int axis, float distance) {
        float out = Math.min(distance, pushLimit(axis));
        if (out <= offAt || outer <= 0) return 0;
        double cents = out / skyRadius * outer;
        long steps = Math.max(1, Math.round(cents / units(axis) / STEPS[axis]));
        return (int) Math.min(FilterSettings.MOST_CENTS, steps * STEPS[axis]);
    }

    /** Half a step of spoke {@code axis}'s knob, in pixels along it, on the scale the drag holds still. */
    private float halfStep(int axis) {
        if (outer <= 0 || skyRadius <= 0) return 0;
        return (float) (askCents(axis, STEPS[axis]) / outer * skyRadius / 2);
    }

    /** Where spoke {@code axis}'s knob stands now, in this view's pixels; null where there is none (for tests). */
    float[] knobAt(int axis) {
        if (!knobsOn() || !knobShown(axis)) return null;
        return point(skyX, skyY, skyRadius, axis, knobFraction(axis, Motion.settle(glideStart, GLIDE_MS)));
    }

    /** The dollars between rings (for tests). */
    long ringCents() {
        return ringCents;
    }

    /** A knob is being dragged (for tests). */
    boolean dragging() {
        return dragging;
    }

    /** The knob a finger rests on, drawn large, before it moves (for tests); -1 for none. */
    int pressedKnob() {
        return pressing() ? held : -1;
    }

    /** What screen readers were last told of a change (for tests). */
    String lastSaid() {
        return lastSaid;
    }

    /**
     * The knob, badge or button a touch at ({@code x}, {@code y}) is for, the nearest within reach; NO_NODE for none.
     * Screen readers reach each one by swiping too.
     */
    private int target(float x, float y) {
        if (!knobsOn()) return NO_NODE;
        float reach = ui.dp(KNOB_REACH_DP);
        float nearest = reach * reach;
        int found = NO_NODE;
        int knob = knobNear(x, y);
        if (knob >= 0) {
            found = knob;
            nearest = knobDistance(knob, x, y);
        }
        if (autopilotShown()) {
            float button = ui.dp(BUTTON_REACH_DP);
            float dx = x - scoreBox.centerX();
            float dy = y - scoreBox.centerY();
            if (dx * dx + dy * dy <= Math.min(nearest, button * button)) {
                nearest = dx * dx + dy * dy;
                found = SCORE_ID;
            }
        }
        if (stopsShown()) {
            // A 48 dp target at least around the badge; a finger on the badge itself is on it however near a knob.
            float around = Math.max(0, ui.dp(BUTTON_DP) - stopsBox.height()) / 2;
            float dx = Math.max(0, Math.max(stopsBox.left - x, x - stopsBox.right));
            float dy = Math.max(0, Math.max(stopsBox.top - y, y - stopsBox.bottom));
            if (dx <= around && dy <= around && dx * dx + dy * dy <= nearest) found = STOPS_ID;
        }
        return found;
    }

    /**
     * The knob (pay, per mile or per hour) whose middle, as drawn now, is within a finger's reach of ({@code x},
     * {@code y}), the nearest; -1 for none, and wherever the chart has no knobs. Words over it make no difference.
     */
    int knobNear(float x, float y) {
        if (!knobsOn()) return -1;
        float reach = ui.dp(KNOB_REACH_DP);
        float nearest = reach * reach;
        int found = -1;
        for (int axis : SPOKES) {
            float distance = knobDistance(axis, x, y);
            if (distance <= nearest) {
                nearest = distance;
                found = axis;
            }
        }
        return found;
    }

    /** The square of the distance from ({@code x}, {@code y}) to spoke {@code axis}'s knob as drawn now. */
    private float knobDistance(int axis, float x, float y) {
        float[] at = point(skyX, skyY, skyRadius, axis, knobFraction(axis, Motion.settle(glideStart, GLIDE_MS)));
        return (x - at[0]) * (x - at[0]) + (y - at[1]) * (y - at[1]);
    }

    // ---- Marked offers: a tap on one opens its ticket (as the sky only). ----

    /** As the sky, a tap on a marked offer opens it: the page has said how. */
    private boolean offersOn() {
        return backdrop() && offerTaps != null;
    }

    /** The marked offers in the order a touch finds them, as they are drawn from the top down. */
    private List<Integer> topDown() {
        List<Integer> order = new ArrayList<>();
        for (int m = 0; m < marks.size(); m++) if (displayedOffer(m)) order.add(m);
        return order;
    }

    /** Where offer {@code m}'s mark on spoke {@code axis} is drawn now as the sky, in this view's pixels; else null. */
    private float[] markPoint(int m, int axis, float glide) {
        if (!displayedOffer(m) || Double.isNaN(marks.get(m)[axis]) || outer <= 0) return null;
        return point(skyX, skyY, skyRadius, axis, markShown(m, glide)[axis], markAside(m, skyDetail()));
    }

    /**
     * The marked offer a touch at ({@code x}, {@code y}) is for: the one with a mark (as drawn now) nearest within
     * reach, else the topmost polygon under the finger; -1 for none. A spoke's icon keeps its own touch.
     */
    private int offerAt(float x, float y) {
        if (!offersOn() || outer <= 0 || marks.isEmpty() || onIcon(x, y, 0)) return -1;
        float glide = Motion.settle(glideStart, GLIDE_MS);
        float reach = ui.dp(MARK_TAP_DP);
        float nearest = reach * reach;
        int found = -1;
        List<Integer> order = topDown();
        for (int m : order) {
            for (int axis : SPOKES) {
                float[] at = markPoint(m, axis, glide);
                if (at == null) continue;
                float dx = x - at[0];
                float dy = y - at[1];
                // Where two marks are as near, the one drawn on top.
                if (dx * dx + dy * dy < nearest || (found < 0 && dx * dx + dy * dy <= nearest)) {
                    nearest = dx * dx + dy * dy;
                    found = m;
                }
            }
        }
        if (found >= 0) return found;
        for (int m : order) {
            List<float[]> polygon = offerPolygon(skyX, skyY, skyRadius, m, glide);
            if (polygon.size() >= 3 && inside(polygon, x, y)) return m;
        }
        return -1;
    }

    /** Whether ({@code x}, {@code y}) lies inside a closed polygon. */
    private static boolean inside(List<float[]> polygon, float x, float y) {
        boolean in = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            float[] a = polygon.get(i);
            float[] b = polygon.get(j);
            if ((a[1] > y) != (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) in = !in;
        }
        return in;
    }

    /** Opens marked offer {@code m}'s ticket through the page, as a tap on its building does. */
    private boolean openOffer(int m) {
        if (offerTaps == null || !displayedOffer(m) || m >= markEntries.size()) return false;
        offerTaps.open(markEntries.get(m));
        return true;
    }

    /** A visible, selectable mark on spoke {@code axis} (for interaction tests). */
    float[] markAt(int m, int axis) {
        if (!backdrop() || m < 0 || m >= marks.size()) return null;
        float[] at = markPoint(m, axis, Motion.settle(glideStart, GLIDE_MS));
        return at;
    }

    /** The marked offer whose ticket is open, newest first (for tests); -1 for none. */
    int openedOffer() {
        return opened();
    }

    /** The marked offer a finger is on (for tests); -1 for none. */
    int pressedOffer() {
        return pressed();
    }

    private void keepTouch(boolean keep) {
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(keep);
    }

    /**
     * As the sky, a finger on a knob grows it; moving along the knob's spoke drags it (the page around does not scroll
     * meanwhile), while moving any other way leaves the touch to the page (a scroll) and sets nothing; a tap on the
     * Autopilot button is the page's (Autopilot off, or the goal chooser) and holding it asks for the goal, a finger
     * moving off it doing neither; a tap on a marked offer (on no knob or button) opens its ticket; a tap on the max
     * stops badge steps it and a drag across it steps it either way; any other tap on the circle, a knob's included,
     * goes back to the newest offer while an older one is chosen, else is the chart's own click.
     */
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!knobsOn() && !offersOn() && !touching) return super.onTouchEvent(event);
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                touching = true;
                tapping = true;
                held = -1;
                dragging = false;
                downX = x;
                downY = y;
                int on = target(x, y);
                autoPressed = on == SCORE_ID;
                autoHeld = false;
                stopsPressed = on == STOPS_ID;
                stopsDragging = false;
                // Held, the Autopilot button asks for the goal.
                if (autoPressed) postDelayed(holdAuto, ViewConfiguration.getLongPressTimeout());
                if (on >= 0 && on < NAMES.length) {
                    held = on;
                    grabDistance = knobFraction(on, Motion.settle(glideStart, GLIDE_MS)) * skyRadius;
                    grabOffset = grabDistance - along(on, x, y);
                }
                // The knobs and the controls first; else the offer under the finger, picked out lightly.
                int offer = on == NO_NODE ? offerAt(x, y) : -1;
                pressedAt = offer >= 0 ? markTimes.get(offer) : -1;
                // Nothing is claimed yet, so the page may still take this touch for a scroll: a knob is taken only
                // once the finger moves along its spoke.
                if (on != NO_NODE || offer >= 0) invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (tapping && Math.hypot(x - downX, y - downY) > slop) {
                    tapping = false;
                    if (autoPressed) {
                        // The button has no drag: a finger moving off it is neither a tap nor a hold.
                        autoPressed = false;
                        removeCallbacks(holdAuto);
                        invalidate();
                    }
                    if (stopsPressed && Math.abs(x - downX) >= Math.abs(y - downY)) {
                        // Across the badge: a drag that steps it, the page holding still meanwhile.
                        stopsDragging = true;
                        stopsValue = maxStops;
                        keepTouch(true);
                    } else if (stopsPressed) {
                        stopsPressed = false;
                        invalidate();
                    }
                    if (held >= 0 && alongSpoke(held, x - downX, y - downY)) {
                        takeKnob();
                    } else if (held >= 0) {
                        // Across the spoke: not a drag, and no longer a tap.
                        held = -1;
                        invalidate();
                    }
                    if (pressedAt >= 0) {
                        // A drag, not a tap: the offer is let go.
                        pressedAt = -1;
                        invalidate();
                    }
                }
                if (dragging) moveKnob(along(held, x, y) + grabOffset);
                if (stopsDragging) moveStops(x - downX);
                return true;
            case MotionEvent.ACTION_UP: {
                boolean button = autoPressed && tapping;
                // A hold that asked for the goal is done: the finger lifting is no tap.
                boolean asked = autoHeld;
                boolean stops = stopsPressed && tapping;
                boolean tap = tapping;
                long tappedAt = tap ? pressedAt : -1;
                endTouch(true);
                if (button) {
                    playSoundEffect(SoundEffectConstants.CLICK);
                    pressAutopilot();
                } else if (stops) {
                    playSoundEffect(SoundEffectConstants.CLICK);
                    setStops(nextStops(maxStops));
                } else if (tappedAt >= 0) {
                    // An offer that went meanwhile opens nothing.
                    int offer = markTimes.indexOf(tappedAt);
                    if (offer >= 0) {
                        playSoundEffect(SoundEffectConstants.CLICK);
                        openOffer(offer);
                    }
                } else if (tap && !asked && emphasizedAt >= 0 && offerTaps != null && offersOn()) {
                    // A tap off every offer while an older one is chosen goes back to the newest.
                    playSoundEffect(SoundEffectConstants.CLICK);
                    offerTaps.chooseNewest();
                } else if (tap && !asked) {
                    performClick();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                endTouch(false);
                return true;
            default:
                return true;
        }
    }

    /** The finger lifted ({@code lifted}) or the touch was taken away: a held knob is let go, saved only if lifted. */
    private void endTouch(boolean lifted) {
        boolean pressed = pressing();
        touching = false;
        tapping = false;
        removeCallbacks(holdAuto);
        if (autoPressed || stopsPressed || pressed || pressedAt >= 0) {
            autoPressed = false;
            stopsPressed = false;
            pressedAt = -1;
            invalidate();
        }
        if (stopsDragging) {
            stopsDragging = false;
            keepTouch(false);
            if (lifted) setStops(stopsValue);
            invalidate();
        }
        if (dragging) letGo(lifted);
        held = -1;
    }

    /** Whether a finger's move ({@code dx}, {@code dy}) runs along spoke {@code axis}, one way or the other. */
    static boolean alongSpoke(int axis, float dx, float dy) {
        double angle = Math.toRadians(ANGLES[axis]);
        double along = Math.abs(dx * Math.cos(angle) + dy * Math.sin(angle));
        return along >= Math.hypot(dx, dy) * Math.cos(Math.toRadians(TAKE_WITHIN_DEGREES));
    }

    @Override public boolean performClick() {
        return super.performClick();
    }

    /**
     * The finger moved along a knob's spoke: it is a drag, not a tap, the page around does not scroll meanwhile, and
     * the rings hold still until it is let go.
     */
    private void takeKnob() {
        dragging = true;
        if (outer <= 0) {
            ringCents = FIRST_RING_CENTS;
            outer = ringCents * 3.0;
            rings = 3;
        }
        dragMiles = exampleMiles;
        dragMinutes = exampleMinutes;
        dragFacts = exampleFacts;
        dragValue = setRates[held];
        // A knob set so low that it rests inside the resting place turns off only when pushed a clear way further in.
        offAt = setRates[held] > 0 ? Math.min(ui.dp(KNOB_REST_DP), grabDistance - ui.dp(OFF_PUSH_DP))
                : ui.dp(KNOB_REST_DP);
        keepTouch(true);
        invalidate();
    }

    /**
     * The held knob, {@code distance} out along its spoke: a tick at each step it crosses. Within half a step of where
     * it was taken it keeps its exact value (which need not be on a step), so a wobble, or a knob put back where it
     * was, changes nothing; past that, both neighbouring steps are in reach.
     */
    private void moveKnob(float distance) {
        int value = Math.abs(distance - grabDistance) < halfStep(held) ? setRates[held] : rateAt(held, distance);
        if (value != dragValue) {
            dragValue = value;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        invalidate();
    }

    /**
     * The held knob let go: everything glides on from where it is drawn, the knob included, and its value is saved
     * if {@code save} and it changed; the rings then settle to fit.
     */
    private void letGo(boolean save) {
        int axis = held;
        int value = dragValue;
        float at = heldFraction();
        float glide = Motion.settle(glideStart, GLIDE_MS);
        for (int i = 0; i < NAMES.length; i++) {
            setFrom[i] = setTo[i] = setFrom[i] + (setTo[i] - setFrom[i]) * glide;
            autoFrom[i] = autoTo[i] = autoFrom[i] + (autoTo[i] - autoFrom[i]) * glide;
        }
        for (int m = 0; m < markTo.size(); m++) {
            float[] shown = markShown(m, glide);
            markFrom.set(m, shown);
            markTo.set(m, shown.clone());
        }
        if (save) setFrom[axis] = setTo[axis] = value > 0 ? at : 0;
        glideStart = SystemClock.uptimeMillis();
        dragging = false;
        held = -1;
        keepTouch(false);
        takeScale();
        inUnits();
        restStale = true;
        if (save && value != setRates[axis] && changes != null) changes.setMinimum(axis, value);
        glideTo();
        invalidate();
        nodesChanged();
    }

    /** The set shape as it is drawn now, into the drawn arrays: while a knob is held, its spoke is where the knob is. */
    private void holdSet() {
        System.arraycopy(set, 0, drawnSet, 0, set.length);
        System.arraycopy(setFrom, 0, drawnFrom, 0, setFrom.length);
        System.arraycopy(setTo, 0, drawnTo, 0, setTo.length);
        if (!dragging || held < 0) return;
        float at = heldFraction();
        drawnSet[held] = dragValue > 0 ? askCents(held, dragValue) : Double.NaN;
        drawnFrom[held] = at;
        drawnTo[held] = at;
    }

    /**
     * The knobs at rest: a set minimum's round star made a little larger, in a white rim and a faint ring of its own
     * color, so it reads as a thumb to take hold of rather than a mark; a spoke with none, a small hollow knob in the
     * same faint ring just outside the middle.
     */
    private void drawKnobs(Canvas canvas, float cx, float cy, float radius, float glide) {
        for (int i : SPOKES) {
            if (i == readoutAxis()) continue;
            int color = setColor();
            float[] at = point(cx, cy, radius, i, knobFraction(i, glide));
            line.setPathEffect(null);
            line.setColor((color & 0x00FFFFFF) | 0x50000000);
            line.setStrokeWidth(ui.dp(1.2f));
            canvas.drawCircle(at[0], at[1], ui.dp(10.5f) * detail, line);
            if (Double.isNaN(set[i])) {
                line.setColor((color & 0x00FFFFFF) | 0xC0000000);
                line.setStrokeWidth(ui.dp(1.8f));
                canvas.drawCircle(at[0], at[1], ui.dp(5) * detail, line);
                if (beckoning()) {
                    // Two swells, each a ring growing out of the knob's own and fading.
                    long half = BECKON_MS / 2;
                    float swell = ((SystemClock.uptimeMillis() - beckonedAt) % half) / (float) half;
                    line.setColor((color & 0x00FFFFFF) | ((int) (0xD0 * (1 - swell)) << 24));
                    line.setStrokeWidth(ui.dp(2));
                    canvas.drawCircle(at[0], at[1], ui.dp(10.5f) * detail * (1 + 1.3f * swell), line);
                }
                continue;
            }
            float dot = ui.dp(4.6f) * detail;
            fill.setColor(ui.dark ? 0xE0FFFFFF : 0xFFFFFFFF);
            canvas.drawCircle(at[0], at[1], dot + ui.dp(1.8f) * detail, fill);
            fill.setColor(color);
            canvas.drawCircle(at[0], at[1], dot, fill);
            fill.setColor(0xFFFFFFFF);
            canvas.drawCircle(at[0], at[1], ui.dp(1.8f) * detail, fill);
        }
    }

    /**
     * The knob under the finger, large, at {@code at}, set to {@code value}; while it is dragged ({@code readout}), with
     * what it is set to beside it ("$1.55/mi", or "off").
     */
    private void drawHeld(Canvas canvas, float[] at, int value, boolean readout) {
        int color = setColor();
        fill.setColor((color & 0x00FFFFFF) | 0x38000000);
        canvas.drawCircle(at[0], at[1], ui.dp(18), fill);
        if (value > 0) {
            fill.setColor(color);
            canvas.drawCircle(at[0], at[1], ui.dp(7.5f), fill);
            fill.setColor(0xFFFFFFFF);
            canvas.drawCircle(at[0], at[1], ui.dp(2.8f), fill);
        } else {
            line.setPathEffect(null);
            line.setColor(color);
            line.setStrokeWidth(ui.dp(2));
            canvas.drawCircle(at[0], at[1], ui.dp(7), line);
        }
        if (readout) drawReadout(canvas);
    }

    /** "$4.00", "$1.00/mi", "$15/hr" (a minute's cents × 60), or "off". */
    static String readout(int axis, int rate) {
        if (rate <= 0) return "off";
        if (axis == AreaScore.MINUTE) return DecisionLog.shortMoney(rate * 60L) + "/hr";
        if (axis == AreaScore.MILE) return DecisionLog.money(rate) + "/mi";
        return DecisionLog.money(rate);
    }

    /** "$4.00", "$1.00", or for the per-hour knob "15 dollars per hour" ("15 dollars 60 cents per hour"). */
    private static String spokenRate(int axis, int rate) {
        if (axis == AreaScore.MINUTE) return spokenDollars(rate * 60L) + " per hour";
        return DecisionLog.money(rate);
    }

    /** "15 dollars", "1 dollar", "15 dollars 60 cents", "60 cents". */
    static String spokenDollars(long cents) {
        long dollars = cents / 100;
        long rest = cents % 100;
        String said = dollars + (dollars == 1 ? " dollar" : " dollars");
        if (rest == 0) return said;
        String change = rest + (rest == 1 ? " cent" : " cents");
        return dollars == 0 ? change : said + " " + change;
    }

    /** The currently adjusted or explored axis; pressing shows meaning before a value is changed. */
    private int readoutAxis() {
        if ((dragging || pressing()) && held >= 0) return held;
        return knobsOn() && focusedNode >= 0 && focusedNode < NAMES.length && knobShown(focusedNode)
                ? focusedNode : -1;
    }

    private String focusedReadout() {
        int axis = readoutAxis();
        if (axis < 0) return "";
        return AXIS_LABELS[axis] + " · " + readout(axis, dragging && axis == held ? dragValue : setRates[axis]);
    }

    /** Keep the readout inside the sky, including large fonts or unusually long numeric values. */
    private String fittedReadout() {
        pillText.setTextSize(Math.min(ui.sp(14), ui.dp(18)));
        return Ui.fit(pillText, focusedReadout(), Math.max(0, getWidth() - ui.dp(30)), 0.85f).toString();
    }

    /**
     * Where the held knob's readout stands, into the pill box: above the knob, clear of the finger, where that is
     * inside the page; else beside it (towards the page's middle, then away), else below it; else above it, kept inside
     * the page.
     */
    private void placeReadout(float cx, float cy, float radius) {
        int axis = readoutAxis();
        float fraction = dragging ? heldFraction() : knobFraction(axis, Motion.settle(glideStart, GLIDE_MS));
        float[] at = point(cx, cy, radius, axis, fraction);
        String words = fittedReadout();
        float height = Math.max(ui.dp(26), Ui.lineHeight(pillText) + ui.dp(8));
        float width = pillText.measureText(words) + ui.dp(22);
        float gap = ui.dp(30);
        float inward = at[0] < getWidth() / 2f ? 1 : -1;
        float[][] places = {
                {at[0] - width / 2, at[1] - gap - height},
                {inward > 0 ? at[0] + gap : at[0] - gap - width, at[1] - height / 2},
                {inward > 0 ? at[0] - gap - width : at[0] + gap, at[1] - height / 2},
                {at[0] - width / 2, at[1] + gap}};
        for (int i = 0; i <= places.length; i++) {
            float[] place = places[i % places.length];
            float left = Math.max(ui.dp(4), Math.min(getWidth() - ui.dp(4) - width, place[0]));
            float top = Math.max(ui.dp(2), Math.min(getHeight() - ui.dp(2) - height, place[1]));
            pillBox.set(left, top, left + width, top + height);
            if (i == places.length) return;
            boolean moved = Math.abs(left - place[0]) > ui.dp(24) || Math.abs(top - place[1]) > ui.dp(1);
            if (!moved) return;
        }
    }

    /** The held knob's readout ("$1.55/mi", or "off") in a small pill where {@link #placeReadout} put it. */
    private void drawReadout(Canvas canvas) {
        String words = fittedReadout();
        float height = pillBox.height();
        fill.setColor(setColor());
        canvas.drawRoundRect(pillBox, height / 2, height / 2, fill);
        pillText.setColor(ui.dark ? 0xFF0D1428 : 0xFFFFFFFF);
        Paint.FontMetrics metrics = pillText.getFontMetrics();
        canvas.drawText(words, pillBox.centerX(), pillBox.centerY() - (metrics.ascent + metrics.descent) / 2,
                pillText);
    }

    // ---- The max stops badge, by the per-stop pin. ----

    /** The max stops badge: shown as the sky while its pin is. */
    boolean stopsShown() {
        return knobsOn() && placeStops();
    }

    /** Where the max stops badge stands, in this view's pixels (for tests); null when it is not shown. */
    RectF stopsBox() {
        return stopsShown() ? new RectF(stopsBox) : null;
    }

    /** Where the per-stop pin the badge is anchored to stands, in this view's pixels (for tests); null without it. */
    RectF stopsPinBox() {
        if (!stopsShown()) return null;
        RectF pin = new RectF();
        return skyIcon(AreaScore.STOP, skyX, skyY, skyRadius, pin) ? pin : null;
    }

    /** What the max stops badge says now: "≤3", or "≤∞" with no limit (for tests too). */
    String stopsWords() {
        return stopsWords(stopsDragging ? stopsValue : maxStops);
    }

    private static String stopsWords(int stops) {
        return stops > 0 ? "≤" + stops : "≤∞";
    }

    /** What a tap on the badge sets: the next of off, 2, 3 … {@link #MOST_STOPS}, then off again. */
    static int nextStops(int stops) {
        return stops >= MOST_STOPS ? 0 : stops < 2 ? 2 : stops + 1;
    }

    /** One step up (to at most {@link #MOST_STOPS}) or down (to off) from {@code stops}; the same where it ends. */
    static int stepStops(int stops, boolean up) {
        if (up) return stops >= MOST_STOPS ? stops : stops < 2 ? 2 : stops + 1;
        return stops <= 0 ? 0 : stops <= 2 ? 0 : Math.min(MOST_STOPS, stops - 1);
    }

    /** The badge's place among off, 2 … {@link #MOST_STOPS} (0 to 9), and the stops at a place. */
    private static int stopsIndex(int stops) {
        return stops < 2 ? 0 : Math.min(MOST_STOPS, stops) - 1;
    }

    private static int stopsAt(int index) {
        return index <= 0 ? 0 : index + 1;
    }

    /**
     * Where the max stops badge stands, into its box: beside the per-stop pin, towards the middle, level with it, or as
     * much higher as keeps its whole 48 dp target inside the view. One place at every size, clear of every knob (none
     * stands on the pin's spoke, and the pay and per-hour spokes run from the middle away from it), so as the window's
     * size changes it moves only with the pin. While a finger drags it, it stays where it was. False while the pin is
     * not shown.
     */
    private boolean placeStops() {
        if (!backdrop()) return false;
        String words = stopsWords();
        float height = Math.max(ui.dp(26), Ui.lineHeight(badgeText) + ui.dp(8));
        float width = Math.max(height + ui.dp(6), badgeText.measureText(words) + ui.dp(18));
        if (stopsDragging && stopsBox.width() > 0) {
            stopsBox.right = stopsBox.left + width;
            return true;
        }
        if (!skyIcon(AreaScore.STOP, skyX, skyY, skyRadius, iconBox)) return false;
        float middle = Math.min(iconBox.centerY(), getHeight() - Math.max(ui.dp(BUTTON_DP), height) / 2);
        float left = iconBox.right + ui.dp(8);
        stopsBox.set(left, middle - height / 2, left + width, middle + height / 2);
        return true;
    }

    /**
     * The max stops badge: filled in the set minimums' color with the limit ("≤3"); with none, hollow, "≤∞". A finger
     * on it grows it a little.
     */
    private void drawStops(Canvas canvas) {
        int value = stopsDragging ? stopsValue : maxStops;
        int color = setColor();
        RectF box = new RectF(stopsBox);
        if (stopsPressed || stopsDragging) box.inset(-ui.dp(3), -ui.dp(3));
        float corner = box.height() / 2;
        if (value > 0) {
            fill.setColor(color);
            canvas.drawRoundRect(box, corner, corner, fill);
            badgeText.setColor(ui.dark ? 0xFF0D1428 : 0xFFFFFFFF);
        } else {
            // The sky's own color under it, so the hollow badge reads over the rings and marks.
            fill.setColor(ui.dark ? 0xC00D1428 : 0xC0E7EEF2);
            canvas.drawRoundRect(box, corner, corner, fill);
            line.setPathEffect(null);
            line.setColor((color & 0x00FFFFFF) | 0xB0000000);
            line.setStrokeWidth(ui.dp(1.5f));
            float inset = line.getStrokeWidth() / 2;
            box.inset(inset, inset);
            canvas.drawRoundRect(box, corner - inset, corner - inset, line);
            badgeText.setColor((color & 0x00FFFFFF) | 0xD0000000);
        }
        Paint.FontMetrics metrics = badgeText.getFontMetrics();
        canvas.drawText(stopsWords(value), box.centerX(), box.centerY() - (metrics.ascent + metrics.descent) / 2,
                badgeText);
    }

    /** A finger {@code dx} across the badge from where it went down: a step for every few dp, a tick at each. */
    private void moveStops(float dx) {
        int steps = Math.round(dx / ui.dp(STOPS_STEP_DP));
        int value = steps == 0 ? maxStops
                : stopsAt(Math.max(0, Math.min(MOST_STOPS - 1, stopsIndex(maxStops) + steps)));
        if (value != stopsValue) {
            stopsValue = value;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        invalidate();
    }

    /** Max stops set on the badge (a tap, a drag or a screen reader), saved at once, and said. */
    private boolean setStops(int stops) {
        if (stops == maxStops || changes == null) {
            invalidate();
            return false;
        }
        changes.setMaxStops(stops);
        say(stopsSaid(stops));
        dropGoneFocus();
        invalidate();
        nodesChanged();
        return true;
    }

    /** "Max stops, 3" or "Max stops, off". */
    private static String stopsSaid(int stops) {
        return STOPS_SAID + ", " + (stops > 0 ? Integer.toString(stops) : "off");
    }

    // ---- The Autopilot button. ----

    /** The Autopilot button: shown as the sky, and used, while it is wholly there ({@link #placeButton}). */
    boolean autopilotShown() {
        return knobsOn() && placeButton() >= 1;
    }

    /** Where the Autopilot button stands, in this view's pixels (for tests); null when it is not wholly there. */
    RectF autopilotBox() {
        return autopilotShown() ? new RectF(scoreBox) : null;
    }

    /** How much of the Autopilot button is there: 1 wholly (and used), 0 gone, between while it fades (for tests). */
    float autopilotAmount() {
        return placeButton();
    }

    /** Where the Autopilot button is drawn, in this view's pixels, smaller while it fades (for tests); null when gone. */
    RectF autopilotDrawn() {
        return placeButton() > 0 ? new RectF(scoreBox) : null;
    }

    /** Recent offer {@code m}'s polygon (newest first) where it is heading, in this view's pixels (for tests). */
    List<float[]> offerShape(int m) {
        return offerPolygon(skyX, skyY, skyRadius, m, 1);
    }

    /** The set minimums' shape where it is heading, in this view's pixels (for tests); empty with no minimum. */
    List<float[]> minimumShape() {
        List<float[]> shape = shapePolygon(set, setFrom, setTo, 1, skyX, skyY, skyRadius);
        return shape == null ? new ArrayList<>() : shape;
    }

    /** Autopilot's dashed shape where it is heading, in this view's pixels (for tests); empty at a bar of 100%. */
    List<float[]> autopilotShape() {
        List<float[]> shape = shapePolygon(auto, autoFrom, autoTo, 1, skyX, skyY, skyRadius);
        return shape == null ? new ArrayList<>() : shape;
    }

    /**
     * How much of the Autopilot button is there (1: wholly, and used; 0: gone), and where it stands, into its box. It has
     * one place at every size, about where the old row of three buttons had its middle: below the circle's middle, a
     * little to the left (or further right, as far as the max stops badge needs), on the side of the line the pay and
     * per-hour spokes make through the middle that has no knob, with its reach and a knob's clear of that line, and so
     * of every knob, set or resting, and of every shape and offer's mark (they all stand on the spokes, on the line or
     * beyond it). As the window's size changes it moves only with the circle: on a circle too small for it, it shrinks and
     * fades away over a range of sizes ({@link #BUTTON_WHOLE_DP} down to {@link #BUTTON_FROM_DP} dp of radius), and
     * as the view's edge would cut it, rather than move, Autopilot's chip at the top of the page standing in for it.
     */
    private float placeButton() {
        if (!knobsOn()) return 0;
        float half = ui.dp(BUTTON_DP) / 2f;
        float aside = -ui.dp(BUTTON_LEFT_DP);
        if (placeStops()) aside = Math.max(aside, stopsBox.right + ui.dp(4) + half - skyX);
        // Square to the line the pay and per-hour spokes make through the middle, SPREAD degrees off level.
        float below = (ui.dp(KNOB_REACH_DP) + half + SIN * aside) / COS;
        buttonX = skyX + aside;
        buttonY = skyY + below;
        float amount = within(skyRadius - ui.dp(BUTTON_FROM_DP), ui.dp(BUTTON_WHOLE_DP - BUTTON_FROM_DP));
        float room = Math.min(Math.min(buttonX, getWidth() - buttonX), Math.min(buttonY, getHeight() - buttonY));
        amount = Math.min(amount, within(room, half));
        float drawn = half * amount;
        scoreBox.set(buttonX - drawn, buttonY - drawn, buttonX + drawn, buttonY + drawn);
        return amount;
    }

    /**
     * The Autopilot button, a round 48 dp button like the header's: "Auto" (9 sp) over its bar ("82%") or "Off";
     * ringed 2 dp in Autopilot's purple while on, amber while below the goal (as the chip), 1 dp grey while off. While it
     * comes in or goes it is drawn smaller and fainter, from its middle.
     */
    private void drawAutopilot(Canvas canvas, float amount) {
        float x = buttonX;
        float y = buttonY;
        float radius = ui.dp(BUTTON_DP) / 2f;
        int saved = canvas.getSaveCount();
        if (amount < 1) {
            canvas.saveLayerAlpha(x - radius, y - radius, x + radius, y + radius, Math.round(255 * amount));
            canvas.scale(amount, amount, x, y);
        }
        fill.setColor(autoPressed ? (ui.dark ? 0xFF2E2E2C : 0xFFE4E3DE) : ui.surface);
        canvas.drawCircle(x, y, radius, fill);
        int ring = buttonRing();
        line.setPathEffect(null);
        line.setColor(ring);
        line.setStrokeWidth(autopilotOn ? ui.dp(2) : Math.max(1, ui.dp(1)));
        canvas.drawCircle(x, y, radius - line.getStrokeWidth() / 2, line);
        buttonText.setColor(autopilotOn ? ring : ui.inkSecondary);
        float room = radius * 1.6f;
        buttonText.setTextSize(Math.min(ui.sp(9), ui.dp(11)));
        fitButtonText(AutopilotText.BUTTON_TITLE, room);
        canvas.drawText(AutopilotText.BUTTON_TITLE, x, y - ui.dp(5), buttonText);
        String label = AutopilotText.buttonLabel(autopilotOn, autopilotBar);
        buttonText.setTextSize(Math.min(ui.sp(12), ui.dp(14)));
        fitButtonText(label, room);
        canvas.drawText(label, x, y + ui.dp(12), buttonText);
        canvas.restoreToCount(saved);
    }

    /**
     * The button's ring color: grey while off, amber while the acceptance rate is below the goal, else Autopilot's
     * purple; the chip's colors, by the chip's rule (also for tests).
     */
    int buttonRing() {
        return !autopilotOn ? (ui.dark ? 0x40FFFFFF : 0x330B0B0B)
                : belowGoal ? AutopilotChip.amber(ui) : AutopilotChip.purple(ui);
    }

    /** Shrinks the button's text, if it must, to fit {@code room} across (a large font, "150%"). */
    private void fitButtonText(String words, float room) {
        float width = buttonText.measureText(words);
        if (width > room && width > 0) buttonText.setTextSize(buttonText.getTextSize() * room / width);
    }

    /** The button's tap: the page turns Autopilot off, or (while off) asks for the goal; its words follow. */
    private void pressAutopilot() {
        if (changes == null) return;
        changes.toggleAutopilot();
        invalidate();
    }

    /**
     * A screen reader's focus on a knob, a button or an offer that is no longer there (an offer that moved along, a
     * newer one taking its place) is let go, and it is told so.
     */
    private void dropGoneFocus() {
        if (focusedNode == NO_NODE) return;
        boolean gone = !shownNode(focusedNode) || (focusedNode >= OFFER_ID
                && markTimes.get(focusedNode - OFFER_ID) != focusedOfferAt);
        if (!gone) return;
        int was = focusedNode;
        focusedNode = NO_NODE;
        sendNodeEvent(was, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        restStale = true;
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(holdAuto);
        super.onDetachedFromWindow();
    }

    // ---- Screen readers: each knob and the badge an adjustable control, the Autopilot button a switch. ----

    @SuppressWarnings("deprecation")
    private void say(String words) {
        lastSaid = words;
        announceForAccessibility(words);
    }

    /** "Minimum per mile, $1.50", "Minimum per hour of trip time, 15 dollars per hour", "Minimum pay, off". */
    private String knobSaid(int axis) {
        int rate = setRates[axis];
        return KNOBS[axis] + ", " + (rate > 0 ? spokenRate(axis, rate) : "off");
    }

    /** One step up or down on a knob, saved at once, and said. */
    private boolean step(int axis, boolean up) {
        int rate = setRates[axis];
        int step = STEPS[axis];
        long next = up ? ((long) rate / step + 1) * step : (((long) rate + step - 1) / step - 1) * step;
        next = Math.max(0, Math.min(FilterSettings.MOST_CENTS, next));
        return setByReader(axis, (int) next);
    }

    private boolean setByReader(int axis, int rate) {
        if (!spoke(axis) || rate == setRates[axis] || changes == null) return false;
        changes.setMinimum(axis, rate);
        say(knobSaid(axis));
        nodesChanged();
        return true;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return knobsOn() || offersOn() ? nodes : super.getAccessibilityNodeProvider();
    }

    private boolean exploring() {
        AccessibilityManager manager = getContext().getSystemService(AccessibilityManager.class);
        return manager != null && manager.isEnabled() && manager.isTouchExplorationEnabled();
    }

    private void hover(int node) {
        if (node == hoveredNode) return;
        int was = hoveredNode;
        hoveredNode = node;
        if (node != NO_NODE) sendNodeEvent(node, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
        if (was != NO_NODE) sendNodeEvent(was, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
    }

    /** Tells screen readers the knobs, the badge, the button or the offers changed. */
    private void nodesChanged() {
        AccessibilityManager manager = getContext().getSystemService(AccessibilityManager.class);
        if (manager == null || !manager.isEnabled() || getParent() == null) return;
        AccessibilityEvent event = newEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        event.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
        event.setSource(this);
        event.setPackageName(getContext().getPackageName());
        getParent().requestSendAccessibilityEvent(this, event);
    }

    private void sendNodeEvent(int node, int type) {
        AccessibilityManager manager = getContext().getSystemService(AccessibilityManager.class);
        if (manager == null || !manager.isEnabled() || getParent() == null) return;
        AccessibilityEvent event = newEvent(type);
        event.setPackageName(getContext().getPackageName());
        event.setClassName(node >= OFFER_ID ? Button.class.getName()
                : node == SCORE_ID ? Switch.class.getName() : SeekBar.class.getName());
        if (node == SCORE_ID) event.setChecked(autopilotOn);
        // An offer that went meanwhile (a focus being let go) has nothing left to say.
        if (node < OFFER_ID || node - OFFER_ID < marks.size()) event.setContentDescription(nodeSaid(node));
        event.setSource(this, node);
        event.setEnabled(true);
        getParent().requestSendAccessibilityEvent(this, event);
    }

    private String nodeSaid(int node) {
        if (node >= OFFER_ID) return offerSaid(node - OFFER_ID);
        if (node == SCORE_ID) {
            return AutopilotText.buttonDescription(autopilotOn, autopilotBar, shownRules.autopilotGoalPercent);
        }
        if (node == STOPS_ID) return stopsSaid(maxStops);
        return knobSaid(node);
    }

    /**
     * "Offer $9.75, 3.3 mi, 18 min, 2 stops, declined, scores 49% of your minimums": what is known of it, and its
     * outcome; where that is not what the rules said, the rules' verdict too ("left to you, rules said decline"). An
     * offer that passed only by Autopilot's lowered bar says so ("passed below your minimums").
     */
    private String offerSaid(int m) {
        OfferSnapshot offer = markFacts.get(m);
        List<String> parts = new ArrayList<>();
        parts.add("Offer " + DecisionLog.money(offer.payCents));
        if (offer.miles != null) parts.add(trim(offer.miles) + " mi");
        if (offer.minutes != null) parts.add(offer.minutes + " min");
        if (offer.stops != null) parts.add(offer.stops + (offer.stops == 1 ? " stop" : " stops"));
        OfferRule.Result result = markResults.get(m);
        DecisionLog.Outcome outcome = DecisionLog.outcome(markEntries.get(m));
        if (outcome.isVerdict(result)) {
            parts.add(result == OfferRule.Result.KEEP
                    ? (AutopilotText.passedBelowMinimums(markEntries.get(m))
                            ? AutopilotText.PASSED_BELOW_MINIMUMS.toLowerCase(Locale.US) : "passed")
                    : result == OfferRule.Result.DECLINE ? "declined" : "left for review");
        } else {
            parts.add(outcome.said.toLowerCase(Locale.US));
            parts.add("rules said " + (result == OfferRule.Result.KEEP ? "pass"
                    : result == OfferRule.Result.DECLINE ? "decline" : "review"));
        }
        if (markScores.get(m) >= 0) {
            parts.add("scores " + markScores.get(m) + "% of your minimums now");
            DecisionLog.Entry entry = markEntries.get(m);
            if (entry.model >= DecisionLog.MODEL && entry.scorePercent >= 0
                    && entry.scorePercent != markScores.get(m)) {
                parts.add("scored " + entry.scorePercent + "% when decided");
            }
        }
        return String.join(", ", parts);
    }

    @SuppressWarnings("deprecation")
    private static AccessibilityEvent newEvent(int type) {
        return Build.VERSION.SDK_INT >= 30 ? new AccessibilityEvent(type) : AccessibilityEvent.obtain(type);
    }

    /** A node's box, in this view's pixels. */
    private void nodeBox(int node, Rect out) {
        if (node >= OFFER_ID) {
            // Around the offer's marks, as far as a tap on them reaches, inside the view.
            float glide = Motion.settle(glideStart, GLIDE_MS);
            RectF box = null;
            for (int axis : SPOKES) {
                float[] at = markPoint(node - OFFER_ID, axis, glide);
                if (at == null) continue;
                if (box == null) box = new RectF(at[0], at[1], at[0], at[1]);
                else box.union(at[0], at[1]);
            }
            if (box == null) box = new RectF(skyX, skyY, skyX, skyY);
            box.inset(-ui.dp(MARK_TAP_DP), -ui.dp(MARK_TAP_DP));
            out.set(Math.max(0, Math.round(box.left)), Math.max(0, Math.round(box.top)),
                    Math.min(getWidth(), Math.round(box.right)), Math.min(getHeight(), Math.round(box.bottom)));
            return;
        }
        if (node == STOPS_ID) {
            float grow = Math.max(0, ui.dp(BUTTON_DP) - stopsBox.height()) / 2;
            out.set(Math.round(stopsBox.left - grow), Math.round(stopsBox.top - grow),
                    Math.round(stopsBox.right + grow), Math.round(stopsBox.bottom + grow));
            return;
        }
        if (node == SCORE_ID) {
            float reach = ui.dp(BUTTON_REACH_DP);
            out.set(Math.round(scoreBox.centerX() - reach), Math.round(scoreBox.centerY() - reach),
                    Math.round(scoreBox.centerX() + reach), Math.round(scoreBox.centerY() + reach));
            return;
        }
        float[] at = point(skyX, skyY, skyRadius, node, knobFraction(node, Motion.settle(glideStart, GLIDE_MS)));
        int reach = ui.dp(KNOB_REACH_DP);
        out.set(Math.round(at[0]) - reach, Math.round(at[1]) - reach, Math.round(at[0]) + reach,
                Math.round(at[1]) + reach);
    }

    /** A knob (0 to 2), the button, the badge or a marked offer that is there now; never 3, 4, 5, 6 or 8. */
    private boolean shownNode(int id) {
        if (id >= OFFER_ID) return offersOn() && outer > 0 && displayedOffer(id - OFFER_ID);
        if (!knobsOn()) return false;
        if (id == SCORE_ID) return autopilotShown();
        if (id == STOPS_ID) return stopsShown();
        return knobShown(id);
    }

    /** Which knob or button a screen reader is on (for tests); -1 for none. */
    int focusedNode() {
        return focusedNode == NO_NODE ? -1 : focusedNode;
    }

    /**
     * The knobs (ids 0 to 2, by spoke), the max stops badge, the Autopilot button and then the marked offers, newest
     * first ({@link #OFFER_ID} on), for screen readers.
     */
    private final class Nodes extends AccessibilityNodeProvider {
        @SuppressWarnings("deprecation")
        @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
            View host = MinimumsStarView.this;
            if (id == HOST_VIEW_ID) {
                AccessibilityNodeInfo info = Build.VERSION.SDK_INT >= 30 ? new AccessibilityNodeInfo(host)
                        : AccessibilityNodeInfo.obtain(host);
                onInitializeAccessibilityNodeInfo(info);
                for (int axis : SPOKES) if (shownNode(axis)) info.addChild(host, axis);
                if (stopsShown()) info.addChild(host, STOPS_ID);
                if (autopilotShown()) info.addChild(host, SCORE_ID);
                for (int m = 0; m < marks.size(); m++) if (shownNode(OFFER_ID + m)) info.addChild(host, OFFER_ID + m);
                return info;
            }
            if (!shownNode(id)) return null;
            AccessibilityNodeInfo info = Build.VERSION.SDK_INT >= 30 ? new AccessibilityNodeInfo(host, id)
                    : AccessibilityNodeInfo.obtain(host, id);
            info.setPackageName(getContext().getPackageName());
            info.setParent(host);
            info.setEnabled(true);
            info.setVisibleToUser(true);
            // Focusable, so a screen reader stops on each knob and the button rather than taking them for parts of
            // the clickable chart around them.
            info.setFocusable(true);
            if (Build.VERSION.SDK_INT >= 28) info.setScreenReaderFocusable(true);
            nodeBox(id, nodeRect);
            info.setBoundsInParent(nodeRect);
            getLocationOnScreen(onScreen);
            nodeRect.offset(onScreen[0], onScreen[1]);
            info.setBoundsInScreen(nodeRect);
            info.setContentDescription(nodeSaid(id));
            if (focusedNode == id) {
                info.setAccessibilityFocused(true);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
            } else {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
            }
            if (id >= OFFER_ID) {
                // "Offer $9.75, 3.3 mi, declined, button. Double-tap to show details."
                info.setClassName(Button.class.getName());
                info.setClickable(true);
                info.setSelected(id - OFFER_ID == opened());
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,
                        "show details"));
                return info;
            }
            if (id == SCORE_ID) {
                // "Autopilot, on. Bar 82 percent of your minimums. Goal: …, switch": a tap turns it on or off, a long
                // press changes the goal, and the details are among its actions. No range: nothing here drags.
                info.setClassName(Switch.class.getName());
                info.setCheckable(true);
                info.setChecked(autopilotOn);
                if (Build.VERSION.SDK_INT >= 30) info.setStateDescription(autopilotOn ? "On" : "Off");
                info.setClickable(true);
                info.setLongClickable(true);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,
                        AutopilotText.buttonAction(autopilotOn)));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK,
                        AutopilotText.ACTION_CHANGE_GOAL));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AutopilotText.DETAILS_ACTION_ID,
                        AutopilotText.ACTION_DETAILS));
                return info;
            }
            if (id == STOPS_ID) {
                // "Max stops, 3, at most 3 stops": adjustable, one step per swipe; a double-tap steps on as a tap does.
                info.setClassName(SeekBar.class.getName());
                info.setRangeInfo(Build.VERSION.SDK_INT >= 30
                        ? new AccessibilityNodeInfo.RangeInfo(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0,
                                MOST_STOPS, Math.min(maxStops, MOST_STOPS))
                        : AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0,
                                MOST_STOPS, Math.min(maxStops, MOST_STOPS)));
                if (Build.VERSION.SDK_INT >= 30) {
                    info.setStateDescription(maxStops > 0 ? "at most " + maxStops + " stops" : "no limit");
                }
                if (maxStops < MOST_STOPS) {
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                }
                if (maxStops > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                info.setClickable(true);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
                return info;
            }
            // A knob: adjustable in dollars (dollars an hour for the per-hour knob, which moves in cents a minute).
            int rate = setRates[id];
            boolean hourly = id == AreaScore.MINUTE;
            float most = FilterSettings.MOST_CENTS * (hourly ? 60f : 1f) / 100f;
            float current = rate * (hourly ? 60f : 1f) / 100f;
            info.setClassName(SeekBar.class.getName());
            info.setRangeInfo(Build.VERSION.SDK_INT >= 30
                    ? new AccessibilityNodeInfo.RangeInfo(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0, most,
                            current)
                    : AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0, most,
                            current));
            if (Build.VERSION.SDK_INT >= 30) info.setStateDescription(readout(id, rate));
            if (rate < FilterSettings.MOST_CENTS) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            }
            if (rate > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
            return info;
        }

        @Override public boolean performAction(int id, int action, Bundle arguments) {
            if (id == HOST_VIEW_ID) return performAccessibilityAction(action, arguments);
            if (!shownNode(id)) return false;
            switch (action) {
                case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS: {
                    if (focusedNode == id) return false;
                    focusedNode = id;
                    focusedOfferAt = id >= OFFER_ID ? markTimes.get(id - OFFER_ID) : -1;
                    invalidate();
                    sendNodeEvent(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                    return true;
                }
                case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                    if (focusedNode != id) return false;
                    focusedNode = NO_NODE;
                    invalidate();
                    sendNodeEvent(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                    return true;
                case AccessibilityNodeInfo.ACTION_CLICK:
                    if (id >= OFFER_ID) return openOffer(id - OFFER_ID);
                    if (id == SCORE_ID) {
                        pressAutopilot();
                        return true;
                    }
                    if (id == STOPS_ID) return setStops(nextStops(maxStops));
                    return false;
                case AccessibilityNodeInfo.ACTION_LONG_CLICK:
                    if (id != SCORE_ID || changes == null) return false;
                    changes.chooseAutopilotGoal();
                    return true;
                case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:
                case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD: {
                    boolean up = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
                    if (id == STOPS_ID) return setStops(stepStops(maxStops, up));
                    return spoke(id) && step(id, up);
                }
                case AutopilotText.DETAILS_ACTION_ID:
                    if (id != SCORE_ID || changes == null) return false;
                    changes.showAutopilotDetails();
                    return true;
                default:
                    if (action == android.R.id.accessibilityActionSetProgress && spoke(id) && arguments != null) {
                        float dollars = arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, -1);
                        if (!(dollars >= 0)) return false;
                        // The per-hour knob is set in dollars an hour and kept in cents a minute.
                        double cents = Math.min(FilterSettings.MOST_CENTS,
                                dollars * 100.0 / (id == AreaScore.MINUTE ? 60 : 1));
                        long rate = Math.round(cents / STEPS[id]) * STEPS[id];
                        return setByReader(id, (int) Math.min(FilterSettings.MOST_CENTS, rate));
                    }
                    return false;
            }
        }
    }
}
