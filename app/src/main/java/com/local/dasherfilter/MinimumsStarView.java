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

/**
 * The minimums as constellations in the page's sky: pay, per mile, per minute, per stop, an upright
 * final-stop hotspot proximity spoke, and pay per observed total item pointing down. The fifth measures 1 / actual final-stop distance in miles, never pay or
 * the driver's current distance from a hotspot. Missing distances are not plotted and never inferred.
 * The original four spokes retain their existing learning; pay/item also learns from confirmed manual accepts
 * with an observed total count. Set minimums are solid with round stars; learned minimums are dashed with sparkles.
 * Distance from the middle is the pay each one asks of one example offer, so
 * the monetary spokes share a dollar scale when their quantities are observed. The fifth uses a fixed display conversion only (1 inverse mile
 * at the $10 radius), and its own readout states inverse miles and the equivalent maximum distance. The latest or
 * skyline-selected offer has small marks on each spoke at what its pay, per mile, per minute and per stop would pay
 * for the example (● passed, ✕ declined, ○ review): a mark outside a minimum beat
 * it. Every spoke is a floor of the same kind: the set per-stop minimum asks the example's stops × its rate, as the
 * adaptive one does. The shapes glide to new values and the adaptive sparkles breathe; with Android's animations off
 * they rest. By day the stars are drawn in ink on the morning sky, by night they shine. Each spoke is marked by its
 * icon and a short plain-language label; screen readers hear the whole of it and what the example offer needs.
 *
 * <p>The spokes stand {@link #SPREAD} degrees above and below level, to the left and to the right, so a wide sky (or a
 * short header) holds a wide chart. On a whole screen or beside Dasher it is the page's sky itself: the page's
 * {@link SkyStage} puts one very large circle where it fits, its faint rings running on past the page's edges and
 * behind the header, the rings' dollars along the level line on the right, and the chart faded wherever the stage says
 * words or the mascot sit on it (the mascot's disc cut out of it altogether). Its points and icons only ever lie along
 * the spokes, which the stage keeps inside the page and clear of the counts and the buttons. Only a touch on the
 * circle, an icon or the badge is the constellation's; the empty sky around it takes none.
 *
 * <p>As the sky, the set minimums are knobs: each spoke's round star, ringed so it reads as something to take hold
 * of (or, on a spoke with no set minimum, a small hollow knob resting just outside the middle: with no rule at all,
 * six of them, so the first rule is a drag), grows under a finger and can be dragged along its spoke, in steps, with a
 * light tick at each; pressing or focusing shows its name and value before moving it, and letting go saves it at once. A knob
 * keeps its exact value until the finger has moved it half a step along its spoke, so a wobble never snaps it to a
 * step; it is taken only by a finger moving along its spoke, so a scroll of the page or a slide across it is left to
 * the page. Dragged into the middle, the rule is off (a knob resting near the middle must be pushed a clear way further
 * in). The ring scale holds still while a knob moves (it may be pushed a little past the outer ring) and settles to fit
 * when it is let go. The adaptive minimums are learned, so they cannot be dragged; but while any of them asks more than
 * its saved set minimum, one round button beside the chart (a dashed purple shape passing into a solid blue one) makes
 * them the set minimums, and for a few seconds after undoes that (longer where Android's accessibility timeout asks,
 * and for as long as a screen reader is on it). Beside it a round button with a sparkle turns the adaptive minimum on
 * (lit) and off; held, it asks to reset what was learned. By the per-stop spoke's icon a small badge says the max stops
 * ("≤3"; hollow, "≤∞", while there is none): a tap steps it off, 2, 3 … 10, a drag across it steps it either way.
 * Screen readers reach each knob and the badge as adjustable controls, the buttons as a button and switches (the
 * sparkle's Reset among its actions). In a header the chart takes a tap only.
 *
 * <p>The displayed offer's points are joined in the spokes' order, leaving out a spoke it did not say. Other offers
 * remain in history and can be selected on the skyline; their hidden polygons receive no touches or accessibility focus. A second round button beside the adopt
 * button's place (an offer's shape around the minimums' smaller one) turns score by area on and off ({@link
 * AreaScore}); screen readers reach it as a switch. By area the chart is drawn in normalized space: every active
 * minimum (the set one, or the adaptive one where that asks more and is on) stands at the same radius, 100%, so the
 * minimums' area is a filled polygon in the middle; over it, as strictly, the set minimums are the solid shape through
 * the knobs and the adaptive ones the dashed shape with sparkles, each at its share of its spoke's floor (R × set ÷
 * floor, R × learned ÷ floor), with no dashed shape where nothing is learned (and, while the adaptive minimum is on,
 * a faint dashed outline just outside the set shape instead, in either mode); the rings stand 50% apart, labeled at 100% and the outer ring; an
 * offer's points stand at its ratio on each spoke ({@code pay ÷ the floor that spoke puts on that offer}), so its
 * polygon's area against the minimums' is its score squared, exactly, within the outer ring. The newest offer's polygon
 * (or the one chosen on the skyline) stands out; its score remains in the offer's ticket and spoken description. The knobs still set the minimums,
 * in their own units: while one is held the scale holds still (the minimums' polygon follows the knob along its spoke, its
 * readout says its units, and the chosen offer's score follows what it asks), and when it is let go everything
 * settles to the new minimums, each again at 100%.
 *
 * <p>As the sky, every marked offer is also the way to its ticket: a tap (never a drag) within 24 dp of one of its
 * marks opens it (the nearest mark's offer, where several could), and failing that a tap inside its polygon does (the
 * topmost polygon under the finger: the one standing out, else the newest). The page opens it exactly as a tap on its
 * building in the skyline does, so both show it chosen; while its ticket is open its polygon and marks stand out, in
 * either mode, and a finger going down on an offer picks it out lightly first. The knobs and the buttons keep their
 * touches first; a tap on the circle that is on no offer is the chart's own (in a short window it moves the chart
 * between the header and the sky; elsewhere it does nothing). The marked offers are
 * selected from the skyline. Screen readers reach the one displayed offer after the knobs and buttons ("Offer
 * $9.75, 3.3 mi, 18 min, 2 stops, declined"), and a double-tap opens its ticket. In a header the chart takes a tap
 * only, as before.
 */
@SuppressLint("ViewConstructor")
final class MinimumsStarView extends View {
    private static final String[] NAMES = {"Pay", "Per mile", "Per minute", "Per stop",
            "Final-stop hotspot proximity", "Per item"};
    static final String[] AXIS_LABELS = {"Payout $", "Pay / mile", "Pay / min", "Pay / stop", "Hotspot unavailable", "Pay / item"};
    /** Each spoke is marked with an icon; the names are for screen readers. */
    private static final Glyph.Shape[] ICONS = {Glyph.Shape.COIN, Glyph.Shape.ROAD, Glyph.Shape.CLOCK,
            Glyph.Shape.PIN, Glyph.Shape.HOTSPOT, Glyph.Shape.BAG};
    private static final int ICON_DP = 18;
    /**
     * The spokes stand this many degrees above and below level: top-left, top-right, bottom-right, bottom-left. The
     * same in a header and as the sky, so the shapes look alike wherever the chart is.
     */
    static final float SPREAD = AreaScore.SPREAD;
    /** The spokes' angles, shared with the area score so its pairs of neighbors are the ones drawn. */
    private static final float[] ANGLES = AreaScore.ANGLES;
    private static final float COS = (float) Math.cos(Math.toRadians(SPREAD));
    private static final float SIN = (float) Math.sin(Math.toRadians(SPREAD));
    /** A pay no rule can ask more than, for working out what an example offer needs. */
    private static final int ANY_PAY = Integer.MAX_VALUE;
    /** Night-sky colors; by day the page's own accent, learned and outcome colors read on the pale sky. */
    private static final int NIGHT_SET = 0xFFA9CBFF;
    private static final int NIGHT_LEARNED = 0xFFDCC2FF;
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
    /** By area, the rings stand this far apart (50%), and the outer one between these. */
    private static final double AREA_RING = 0.5;
    private static final double AREA_LEAST = 1.5;
    private static final double AREA_MOST = 2.5;
    /** Offers far above every minimum stretch the scale only this far, so the minimums stay readable. */
    private static final double OFFER_STRETCH = 1.35;
    private static final long GLIDE_MS = 700;
    /** An offer above the outer ring is marked just past it, at most this share of the way out... */
    private static final double MARK_REACH = 1.02;
    /** ...and offers side by side on a spoke step this many dp aside from each other (at full detail). */
    private static final float MARK_STEP_DP = 2.2f;
    /** The smallest circle, in dp, the constellation is drawn in when a screen is short. */
    private static final int MIN_WINDOW_DP = 40;
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
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint axisText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] axisLabels = new RectF[AreaScore.AXES];
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path diamond = new Path();
    private final DashPathEffect dash;
    /** Cents the minimum asks of the example offer; NaN where there is no such minimum. */
    private final double[] setCents = new double[NAMES.length];
    private final double[] learnedCents = new double[NAMES.length];
    /** The same in the scale's units (cents, strict; shares of the floor, by area); NaN where there is none. */
    private final double[] set = new double[NAMES.length];
    private final double[] learned = new double[NAMES.length];
    /** Where each point is heading and where it set out from, as fractions of the outer ring. */
    private final float[] setTo = new float[NAMES.length];
    private final float[] setFrom = new float[NAMES.length];
    private final float[] learnedTo = new float[NAMES.length];
    private final float[] learnedFrom = new float[NAMES.length];
    private long glideStart;
    /**
     * Recent offers, newest first: result, then their value on each spoke in the scale's units (NaN where the offer
     * did not say, or by area where the spoke has no minimum), when each was recorded, its score by area under the
     * rules as shown (-1 where none), and where each mark glides from and to (shares of the outer ring).
     */
    private final List<OfferRule.Result> markResults = new ArrayList<>();
    private final List<double[]> marks = new ArrayList<>();
    private final List<Long> markTimes = new ArrayList<>();
    private final List<Integer> markScores = new ArrayList<>();
    private final List<OfferSnapshot> markFacts = new ArrayList<>();
    private final List<DecisionLog.Entry> markEntries = new ArrayList<>();
    private final List<float[]> markFrom = new ArrayList<>();
    private final List<float[]> markTo = new ArrayList<>();
    /** The offer chosen on the skyline (by when it was recorded), whose polygon stands out by area; -1: the newest. */
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

    // ---- Score by area: the chart in normalized space, where every active minimum stands at one radius. ----

    /** The rules as shown are scored by area. */
    private boolean byArea;
    /** The rules as shown, for the chosen offer's score (worked out afresh while a knob is held). */
    private FilterSettings shownRules = new FilterSettings(false, 0, 0, 0, 0, 0);
    /**
     * Cents per unit of the scale on each spoke: 1 when strict (the scale is in cents); by area, the floor that spoke
     * puts on the example offer (so the minimums all stand at 1), or for a spoke with no minimum, the strict scale's
     * cents per unit, so a knob dragged out from rest moves as it would strictly. The scale's units at the outer ring,
     * and how many rings it has. All three hold still while a knob is held.
     */
    private final double[] unit = {1, 1, 1, 1, 1, 1};
    private final double[] shownUnit = {1, 1, 1, 1, 1, 1};
    private double outer;
    private double shownOuter;
    private int rings = 3;
    private int shownRings = 3;
    /** By area, the minimums' polygon: 1 on each active spoke, NaN elsewhere; and where its points glide. */
    private final double[] areaMin = new double[NAMES.length];
    private final float[] areaFrom = new float[NAMES.length];
    private final float[] areaTo = new float[NAMES.length];
    private String needs = "";
    private final String[] setText = new String[NAMES.length];
    private final String[] learnedText = new String[NAMES.length];
    private boolean adaptiveOn;
    private final Glyph[] icons = new Glyph[ICONS.length];
    /**
     * In a short window's header the icons stand beside the circle rather than at its corners, so the circle can be
     * as tall as the header allows.
     */
    private boolean beside;
    /** How much of their full size the stars, sparkles and marks are drawn at: less on a small circle. */
    private float detail = 1;
    /** Where the page's sky put the circle (a radius of 0 until it has), and where words or the mascot sit on it. */
    private float skyX;
    private float skyY;
    private float skyRadius;
    private final List<Veil> veils = new ArrayList<>();
    private final Paint veilPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgeFade = new Paint();
    private float edgeFadeFor = Float.NaN;
    private final RectF veilBox = new RectF();
    /** As the sky: the rings' dollars (bold, in a soft halo of the sky's color) and where each was last drawn. */
    private final TextPaint levelText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<RectF> levelBoxes = new ArrayList<>();
    private final List<String> levelWords = new ArrayList<>();
    private final RectF iconBox = new RectF();
    /** A moved max-stops badge keeps its pin beside it; placement itself always starts from the spoke endpoint. */
    private final RectF stopsIcon = new RectF();
    private boolean placingStops;

    // ---- Knobs and the adopt button (as the sky only). ----

    /** What the page does when a knob is let go, the learned minimums are adopted, or that is undone. */
    interface Changes {
        /** Sets spoke {@code axis}'s minimum to {@code cents} of its own unit (0 turns it off), saving it at once. */
        void setMinimum(int axis, int cents);

        /** Explains the unavailable hotspot measurement; an existing rule may be turned off only by explicit choice. */
        default void explainHotspotUnavailable() {}

        /**
         * Makes the adaptive minimums the saved set ones; answers what Undo puts back (the saved minimums it replaced,
         * -1 for those it left alone), or null when nothing changed.
         */
        int[] adoptLearned();

        /** Puts back the set minimums {@code cents} (pay, per mile, per minute, per stop; -1 leaves one alone). */
        void restore(int[] cents);

        /** Turns score by area on or off, saving it at once. */
        default void setScoreByArea(boolean on) {}

        /** Chooses the offers-versus-profit preference; it maps only to the existing minimums percentage. */
        default void setTradeoffPercent(int percent) {}

        /** Sets the max stops (0: no limit), saving it at once. */
        default void setMaxStops(int stops) {}

        /** Turns the adaptive minimum on or off, saving it at once; what it learned is kept. */
        default void setAdaptive(boolean on) {}

        /** Asks the user (a confirm) whether to forget what the adaptive minimum learned, and does it if so. */
        default void resetLearned() {}
    }

    /** What the page does when a marked offer is tapped (or a screen reader opens it). */
    interface OfferTaps {
        /** Opens {@code entry}'s ticket, as a tap on its building in the skyline does. */
        void open(DecisionLog.Entry entry);

        /** Chooses the newest offer again, as a tap on its building does (without opening its ticket). */
        default void chooseNewest() {}
    }

    /** A knob moves in these steps of its spoke's own unit: $0.50 of pay, $0.05 a mile, $0.01 a minute, $0.25 a stop. */
    static final int[] STEPS = {50, 5, 1, 25, 5, 5};
    /** Display conversion only: 1 inverse mile shares the $10 radius in strict mode; never used to score. */
    private static final double HOTSPOT_DISPLAY_UNIT = 10;
    private static final String[] UNITS = {"", "/mi", "/min", "/stop", "/mi", "/item"};
    private static final String[] KNOBS = {"Minimum pay", "Minimum per mile", "Minimum per minute",
            "Minimum per stop", "Minimum final-stop hotspot proximity", "Minimum per item"};
    /** A knob takes a touch this far from its middle (a 48 dp target); where two could, the nearer one does. */
    private static final int KNOB_REACH_DP = 24;
    /**
     * A spoke with no set minimum rests its hollow knob this far out (far enough that six hollow knobs, on a fresh
     * page, stand apart); dragged back inside it, a rule is off.
     */
    static final int KNOB_REST_DP = 32;
    /** A knob set so low it rests inside that place turns off only when pushed this much further in. */
    private static final int OFF_PUSH_DP = 12;
    /** A finger moving within this many degrees of a knob's spoke (either way) drags it; any other way is the page's. */
    private static final double TAKE_WITHIN_DEGREES = 40;
    /** A knob may be pushed this share of the outer ring's reach; the scale grows to fit it when it is let go. */
    private static final float PUSH = 1.12f;
    /** The scale knobs move on while nothing at all is on the chart yet: $5 a ring. */
    private static final long FIRST_RING_CENTS = 500;
    /**
     * The adopt button: drawn this wide, taking a touch this far from its middle, and offering Undo this long (or as
     * long as Android's accessibility timeout asks, and while a screen reader is on it).
     */
    private static final int ADOPT_DP = 48;
    private static final int ADOPT_REACH_DP = 24;
    static final long UNDO_MS = 8000;
    static final String ADOPT_SAID = "Make the learned minimums your set minimums";
    /**
     * The screen reader's ids for the knobs (0 to 5, by spoke), then the adopt button, the score by area toggle, the
     * adaptive minimum's toggle and the max stops badge.
     */
    static final int ADOPT_ID = NAMES.length;
    static final int SCORE_ID = NAMES.length + 1;
    static final int ADAPTIVE_ID = NAMES.length + 2;
    static final int STOPS_ID = NAMES.length + 3;
    /** Each marked offer's id: this plus its place among them, newest first. */
    static final int OFFER_ID = NAMES.length + 4;
    static final String SCORE_SAID = "Score by area";
    static final String ADAPTIVE_SAID = "Adaptive minimum";
    static final String RESET_SAID = "Reset learned minimums";
    static final String STOPS_SAID = "Max stops";
    /** Screen readers' own actions on the adaptive minimum's toggle (ids clear of Android's). */
    static final int TOGGLE_ACTION = 0x4F460001;
    static final int RESET_ACTION = 0x4F460002;
    /** The round buttons stand in a row (score by area, the adaptive minimum, adopt), their middles this far apart. */
    private static final int BUTTONS_APART_DP = 60;
    /** The badge's steps after off: 2 stops (one order) to this many. */
    static final int MOST_STOPS = 10;
    /** A finger moving across the badge steps it once every this many dp. */
    private static final int STOPS_STEP_DP = 18;
    /** Across the existing mode button, one percentage point per step. */
    static final int MINIMUM_SCALE_STEP_DP = 8;
    /** The hollow knobs beckon this long, in two swells. */
    private static final long BECKON_MS = 1600;
    private static final int NO_NODE = Integer.MIN_VALUE;

    private Changes changes;
    /** Each spoke's set minimum in its own unit as shown: cents of pay, a mile, a minute, a stop; 0 when off. */
    private final int[] setRates = new int[NAMES.length];
    /** The example offer's miles, minutes and stops as last shown, which turn a knob's rate into pay on the scale... */
    private double exampleMiles = 5;
    private int exampleMinutes = 20;
    private int exampleStops = 2;
    private OfferSnapshot exampleFacts = new OfferSnapshot(null, 5.0, 20, 2);
    private OfferSnapshot referenceExample = exampleFacts;
    private OfferSnapshot dragFacts = exampleFacts;
    private FilterSettings savedRules;
    private List<DecisionLog.Entry> shownRecent = java.util.Collections.emptyList();
    /** Drag calibration only while no item count is known; never an offer count or plotted pay requirement. */
    private static final int ITEM_EDIT_UNITS = 10;
    private int dragItems = ITEM_EDIT_UNITS;
    /** ...and as they stood when the held knob was taken, which it keeps until it is let go. */
    private double dragMiles = 5;
    private int dragMinutes = 20;
    private int dragStops = 2;
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
    /** Some adaptive minimum asks more than its saved set one (and the saved adaptive minimum is on). */
    private boolean adoptable;
    /** The set minimums as saved. */
    private final int[] savedRates = new int[NAMES.length];
    /** The set minimums before the learned ones were adopted, while Undo is offered; else null. */
    private int[] undoValues;
    /** The set minimums the adoption made: any other change to them (a knob, say) ends Undo. */
    private final int[] adoptedRates = new int[NAMES.length];
    private final Runnable undoEnds = () -> {
        undoValues = null;
        adoptStale = true;
        dropGoneFocus();
        invalidate();
        nodesChanged();
    };
    private boolean adoptPressed;
    /** The button's press is under way: it is between adopt and Undo, not gone. */
    private boolean pressingAdopt;
    private final RectF adoptBox = new RectF();
    private boolean adoptPlaced;
    /** The score by area toggle: where it stands, whether the row of buttons found a place, and a finger on it. */
    private final RectF scoreBox = new RectF();
    private boolean togglePlaced;
    private boolean buttonsPlaced;
    private boolean scorePressed;
    private boolean scaleDragging;
    private int scaleValue = 100;
    private final TextPaint scaleText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Glyph areaGlyph;
    /** By area, the chosen offer's score: its words and where it stands. */
    private final TextPaint scoreText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF labelBox = new RectF();
    private boolean adoptStale = true;
    /** The circle moved or the view changed size since the button was placed. */
    private boolean layoutMoved = true;
    private final Glyph adoptGlyph;
    private final Glyph undoGlyph;
    /** The adaptive minimum's toggle: where it stands, whether it found a place, a finger on it, and held long. */
    private final RectF adaptiveBox = new RectF();
    private boolean adaptivePlaced;
    private boolean adaptivePressed;
    private boolean adaptiveHeld;
    private final Runnable holdAdaptive = () -> {
        if (!adaptivePressed) return;
        adaptivePressed = false;
        adaptiveHeld = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
        if (changes != null) changes.resetLearned();
    };
    /** The max stops badge: the saved limit (0 for none), where it stands, a finger on it, dragging it, and to what. */
    private int maxStops;
    private final RectF stopsBox = new RectF();
    private final TextPaint badgeText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint adaptiveText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private boolean stopsPressed;
    private boolean stopsDragging;
    private int stopsValue;
    /** The badge stood where it does while the buttons were placed (clear of it). */
    private boolean stopsForButtons;
    /** When the hollow knobs last beckoned (uptime); 0 for never. */
    private long beckonedAt;
    /** What a screen reader's double-tap on the chart does, while it is a button (in a short window); null for none. */
    private String clickLabel;
    private final Nodes nodes = new Nodes();
    private int focusedNode = NO_NODE;
    /** The offer a screen reader's focus is on (by when it was recorded), so it goes when that offer moves or goes. */
    private long focusedOfferAt = -1;
    private int hoveredNode = NO_NODE;
    private final Rect nodeRect = new Rect();
    private final int[] onScreen = new int[2];
    private String lastSaid = "";

    /** A place on the sky where words or the mascot sit, and how far (0 to 1) the constellation fades under it. */
    static final class Veil {
        final RectF box;
        final boolean round;
        final float fade;

        Veil(RectF box, boolean round, float fade) {
            this.box = new RectF(box);
            this.round = round;
            this.fade = fade;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Veil)) return false;
            Veil veil = (Veil) other;
            return box.equals(veil.box) && round == veil.round && fade == veil.fade;
        }

        @Override public int hashCode() {
            return box.hashCode() * 31 + (round ? 1 : 0);
        }
    }

    MinimumsStarView(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        dash = new DashPathEffect(new float[] {ui.dp(5), ui.dp(4)}, 0);
        for (int i = 0; i < ICONS.length; i++) icons[i] = new Glyph(ICONS[i], ui.inkSecondary, ui.dp(ICON_DP));
        for (int i = 0; i < axisLabels.length; i++) axisLabels[i] = new RectF();
        axisText.setTypeface(Ui.MEDIUM);
        axisText.setTextSize(Math.min(ui.sp(11), ui.dp(13)));
        axisText.setTextAlign(Paint.Align.LEFT);
        axisText.setColor(ui.dark ? 0xFFE0E8EE : 0xFF394D5A);
        axisText.setShadowLayer(ui.dp(3), 0, 0, ui.dark ? 0xFF0D1428 : 0xFFE7EEF2);
        veilPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        edgeFade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        levelText.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD));
        levelText.setTextSize(Math.min(ui.sp(12), ui.dp(16)));
        levelText.setColor(ui.dark ? 0xC8FFFFFF : 0xD0214066);
        // The sky's own color at its middle, so the halo reads as clear sky around the words.
        levelText.setShadowLayer(ui.dp(3), 0, 0, ui.dark ? 0xFF0D1428 : 0xFFE7EEF2);
        pillText.setTypeface(levelText.getTypeface());
        pillText.setTextSize(Math.min(ui.sp(14), ui.dp(18)));
        pillText.setTextAlign(Paint.Align.CENTER);
        adoptGlyph = new Glyph(Glyph.Shape.ADOPT, ui.ink, ui.dp(24));
        areaGlyph = new Glyph(Glyph.Shape.AREA, ui.ink, ui.dp(24));
        scoreText.setTypeface(levelText.getTypeface());
        scoreText.setTextSize(Math.min(ui.sp(12), ui.dp(15)));
        scoreText.setTextAlign(Paint.Align.CENTER);
        scaleText.setTypeface(levelText.getTypeface());
        scaleText.setTextAlign(Paint.Align.CENTER);
        undoGlyph = new Glyph(Glyph.Shape.UNDO, ui.ink, ui.dp(24));
        adaptiveText.setTypeface(levelText.getTypeface());
        adaptiveText.setTextAlign(Paint.Align.CENTER);
        badgeText.setTypeface(levelText.getTypeface());
        badgeText.setTextSize(Math.min(ui.sp(14), ui.dp(18)));
        badgeText.setTextAlign(Paint.Align.CENTER);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    /** Knobs and the adopt button save through {@code changes}; without them the chart only takes a tap. */
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

    private int learnedColor() {
        return adaptiveOn ? (ui.dark ? NIGHT_LEARNED : ui.learned) : 0xFF8E8A9C;
    }

    private int markColor(OfferRule.Result result) {
        if (!ui.dark) return Ui.resultColor(result);
        return result == OfferRule.Result.KEEP ? NIGHT_PASSED
                : result == OfferRule.Result.DECLINE ? NIGHT_DECLINED : NIGHT_REVIEW;
    }

    /** As {@link #show(FilterSettings, FilterSettings, OfferSnapshot, List)}, with {@code rules} as saved. */
    void show(FilterSettings rules, OfferSnapshot example, List<DecisionLog.Entry> recent) {
        show(rules, rules, example, recent);
    }

    /**
     * @param rules the rules as shown
     * @param saved the rules as saved, which the adopt button works from (and the knobs save into)
     * @param example the offer whose miles, minutes and stops turn each minimum into pay; all three must be known
     *     (the screen uses the latest fully read offer, or a typical one)
     * @param recent recent decisions, newest first; standalone offers with pay are marked
     */
    void show(FilterSettings rules, FilterSettings saved, OfferSnapshot example, List<DecisionLog.Entry> recent) {
        referenceExample = example;
        savedRules = saved;
        shownRecent = recent == null ? java.util.Collections.emptyList() : new ArrayList<>(recent);
        // The editable route example stays stable; the item axis applies only to the offer actually displayed.
        // Selecting shopping history after an ordinary offer must not erase its item axis, or vice versa.
        long displayedAt = openAt >= 0 ? openAt : emphasizedAt >= 0 ? emphasizedAt
                : shownRecent.isEmpty() ? -1 : shownRecent.get(0).at;
        for (DecisionLog.Entry entry : shownRecent) if (entry.at == displayedAt) {
            example = example.withItems(entry.facts.items, entry.facts.itemCountApplicable);
            break;
        }
        double miles = example.miles;
        int minutes = example.minutes;
        int stops = example.stops;
        AreaScore.Floors floors = AreaScore.floors(rules, example);
        AcceptedBest best = rules.best;
        DeclinedFloor declined = rules.declined;
        put(0, rules.flatCents > 0 ? DecisionLog.money(rules.flatCents) : null, amount(floors.fixedCents[AreaScore.PAY]),
                amount(floors.acceptedCents[AreaScore.PAY]),
                "more than " + DecisionLog.money(rules.lastAcceptedCents),
                amount(floors.declinedCents[AreaScore.PAY]), "more than " + DecisionLog.money(declined.payCents));
        put(1, rules.perMileCents > 0 ? DecisionLog.money(rules.perMileCents) : null,
                amount(floors.fixedCents[AreaScore.MILE]),
                amount(floors.acceptedCents[AreaScore.MILE]), best.hasPerMile() ? best.perMile() : null,
                amount(floors.declinedCents[AreaScore.MILE]),
                declined.rates.hasPerMile() ? "more than " + declined.rates.perMile() : null);
        put(2, rules.perMinuteCents > 0 ? DecisionLog.money(rules.perMinuteCents) : null,
                amount(floors.fixedCents[AreaScore.MINUTE]),
                amount(floors.acceptedCents[AreaScore.MINUTE]), best.hasPerMinute() ? best.perMinute() : null,
                amount(floors.declinedCents[AreaScore.MINUTE]),
                declined.rates.hasPerMinute() ? "more than " + declined.rates.perMinute() : null);
        put(3, rules.perStopCents > 0 ? DecisionLog.money(rules.perStopCents) : null,
                amount(floors.fixedCents[AreaScore.STOP]),
                amount(floors.acceptedCents[AreaScore.STOP]), best.hasPerStop() ? best.perStop() : null,
                amount(floors.declinedCents[AreaScore.STOP]),
                declined.rates.hasPerStop() ? "more than " + declined.rates.perStop() : null);
        put(AreaScore.HOTSPOT, rules.hotspotProximityHundredths > 0
                        ? readout(AreaScore.HOTSPOT, rules.hotspotProximityHundredths) : null,
                (long) (rules.hotspotProximityHundredths * HOTSPOT_DISPLAY_UNIT), 0, null, 0, null);
        put(AreaScore.ITEM, rules.perItemCents > 0 ? readout(AreaScore.ITEM, rules.perItemCents) : null,
                amount(floors.fixedCents[AreaScore.ITEM]), amount(floors.acceptedCents[AreaScore.ITEM]),
                best.hasPerItem() ? best.perItem() : null, 0, null);
        adaptiveOn = rules.risingOffers;
        byArea = rules.scoreByArea;
        maxStops = Math.max(0, rules.maxStops);
        shownRules = rules;
        int[] rates = rules.minimums();
        for (int i = 0; i < NAMES.length; i++) setRates[i] = Math.max(0, rates[i]);
        exampleMiles = miles;
        exampleMinutes = minutes;
        exampleStops = stops;
        exampleFacts = example;
        int[] kept = saved.minimums();
        for (int i = 0; i < NAMES.length; i++) savedRates[i] = Math.max(0, kept[i]);
        adoptable = saved.risingOffers && !Arrays.equals(saved.adoptAdaptive().minimums(), kept);
        // Undo would put back more than the adoption once the saved minimums change any other way.
        if (undoValues != null && !Arrays.equals(savedRates, adoptedRates)) stopUndo();
        adoptStale = true;
        needs = needs(rules, example);
        markOffers(recent, example, rules);

        double top = 0;
        for (int i = 0; i < NAMES.length; i++) {
            if (!Double.isNaN(setCents[i])) top = Math.max(top, setCents[i]);
            if (!Double.isNaN(learnedCents[i])) top = Math.max(top, learnedCents[i]);
        }
        double offers = 0;
        for (double[] mark : strictMarks) {
            for (double cents : mark) if (Double.isFinite(cents)) offers = Math.max(offers, cents);
        }
        if (top > 0) top = Math.max(top, top * rules.minimumScalePercent / 100.0);
        top = top > 0 ? Math.max(top, Math.min(offers, top * OFFER_STRETCH)) : offers;
        shownRing = top > 0 ? ringStep(top) : 0;
        scaleTo(rules, example);
        // The rings and the scale hold still under a moving knob; they settle to fit when it is let go.
        if (!dragging) takeScale();
        inUnits();
        glideTo();
        setContentDescription(caption() + " " + describe());
        dropGoneFocus();
        invalidate();
        nodesChanged();
    }

    /** A spoke's set minimum and its adaptive one: the higher of what accepted and declined offers taught. */
    private void put(int axis, String setLabel, double setAsk, double acceptedCents, String acceptedLabel,
                     double declinedCents, String declinedLabel) {
        setCents[axis] = setLabel != null && setAsk > 0 ? setAsk : Double.NaN;
        setText[axis] = setLabel;
        boolean fromDecline = declinedCents > acceptedCents;
        double cents = fromDecline ? declinedCents : acceptedCents;
        String label = fromDecline ? declinedLabel : acceptedLabel;
        learnedCents[axis] = label != null && cents > 0 && cents < Long.MAX_VALUE ? cents : Double.NaN;
        learnedText[axis] = Double.isNaN(learnedCents[axis]) ? null : label;
    }

    private static double amount(java.math.BigDecimal value) {
        return value == null ? 0 : value.doubleValue();
    }

    private static boolean upright(int axis) {
        return axis == AreaScore.HOTSPOT || axis == AreaScore.ITEM;
    }

    static boolean hasItems(OfferSnapshot offer) {
        return offer != null && offer.itemCountApplicable && offer.items != null && offer.items > 0;
    }

    /** Observed quantities only. An absent count never becomes a one-item order. */
    static String itemsLabel(OfferSnapshot offer) {
        if (offer == null || !offer.itemCountApplicable) return "";
        if (!hasItems(offer)) return "Item count unavailable";
        String count = offer.items + (offer.items == 1 ? " item" : " items");
        if (offer.payCents == null) return count;
        boolean rounded = offer.payCents % offer.items != 0;
        String rate = String.format(java.util.Locale.US, "$%.2f/item", offer.payCents / (100.0 * offer.items));
        return count + " · " + (rounded ? "≈" : "") + rate;
    }

    /** A missing shopping count and an inapplicable item rule have different meanings. */
    static String itemState(OfferSnapshot offer) {
        String observed = itemsLabel(offer);
        return observed.isEmpty() ? "Item rule not applicable: no shopping or items shown" : observed;
    }

    private OfferSnapshot selectedFacts() {
        // The editable example may borrow route quantities from an older readable offer. Never combine its pay
        // with a newly selected unreadable offer's item count, or imply a rate that was not observed.
        long selectedAt = openAt >= 0 ? openAt : emphasizedAt >= 0 ? emphasizedAt : newestEntryAt;
        for (DecisionLog.Entry entry : shownRecent) if (entry.at == selectedAt) return entry.facts;
        return OfferSnapshot.UNKNOWN;
    }

    /** Each recent offer's cents on each spoke for the example offer, as the strict chart places it. */
    private final List<double[]> strictMarks = new ArrayList<>();

    /**
     * Each recent standalone offer with pay: strictly, at what its own rates would pay for the example offer; by area,
     * at its ratio on each spoke with a minimum, {@code pay ÷ the floor that spoke puts on this offer} (as its score
     * takes it), so its polygon's area against the minimums' is its score. Only the offers on the skyline (its
     * {@link #MARKS} newest), so each mark's ticket opens as its building's does.
     */
    private void markOffers(List<DecisionLog.Entry> recent, OfferSnapshot example, FilterSettings rules) {
        newestEntryAt = recent == null || recent.isEmpty() ? -1 : recent.get(0).at;
        List<Long> before = new ArrayList<>(markTimes);
        List<float[]> drawn = new ArrayList<>();
        float glide = Motion.settle(glideStart, GLIDE_MS);
        for (int m = 0; m < markTo.size(); m++) drawn.add(markShown(m, glide));
        markResults.clear();
        marks.clear();
        strictMarks.clear();
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
            double[] cents = {
                    pay,
                    offer.miles != null && offer.miles > 0 ? pay * example.miles / offer.miles : Double.NaN,
                    offer.minutes != null && offer.minutes > 0 ? pay * example.minutes / offer.minutes : Double.NaN,
                    offer.stops != null && offer.stops > 0 ? pay * example.stops / offer.stops : Double.NaN,
                    hotspotDisplayValue(offer.finalStopHotspotMiles),
                    hasItems(offer) && hasItems(example) ? pay * example.items / offer.items : Double.NaN};
            strictMarks.add(cents);
            AreaScore.Floors floors = AreaScore.floors(rules, offer);
            marks.add(byArea ? AreaScore.ratios(floors, offer.payCents) : cents);
            markResults.add(entry.result);
            markTimes.add(entry.at);
            markScores.add(AreaScore.percent(floors, offer.payCents, rules.minimumScalePercent));
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

    /**
     * The scale the values just shown ask for: strictly, three rings of {@link #shownRing}; by area, rings 50% apart
     * out to as far as the offers reach (from 150% to 250%; the minimums at 100%), each spoke's unit being the floor it
     * puts on the example offer.
     */
    private void scaleTo(FilterSettings rules, OfferSnapshot example) {
        double strictOuter = (shownRing > 0 ? shownRing : FIRST_RING_CENTS) * 3.0;
        if (!byArea) {
            Arrays.fill(shownUnit, 1);
            shownOuter = shownRing * 3.0;
            shownRings = 3;
            return;
        }
        AreaScore.Floors floors = AreaScore.floors(rules, example);
        double reach = Math.max(1, rules.minimumScalePercent / 100.0);
        for (int i = 0; i < NAMES.length; i++) {
            boolean floor = floors.active[i] && (i == AreaScore.HOTSPOT || floors.cents[i] != null);
            shownUnit[i] = !floor ? 0 : i == AreaScore.HOTSPOT
                    ? rules.hotspotProximityHundredths * HOTSPOT_DISPLAY_UNIT : floors.cents[i].doubleValue();
            if (floor && !adaptiveOn && !Double.isNaN(learnedCents[i])) {
                reach = Math.max(reach, learnedCents[i] / shownUnit[i]);
            }
        }
        double offers = 0;
        for (double[] mark : marks) for (double r : mark) if (!Double.isNaN(r)) offers = Math.max(offers, r);
        double most = Math.min(AREA_MOST, Math.max(reach, offers) * 1.05);
        shownRings = (int) Math.max(AREA_LEAST / AREA_RING, Math.ceil(most / AREA_RING - 1e-9));
        shownOuter = shownRings * AREA_RING;
        for (int i = 0; i < NAMES.length; i++) if (shownUnit[i] <= 0) shownUnit[i] = strictOuter / shownOuter;
    }

    /** The scale just worked out becomes the one drawn (never while a knob is held). */
    private void takeScale() {
        ringCents = shownRing;
        System.arraycopy(shownUnit, 0, unit, 0, unit.length);
        outer = shownOuter;
        rings = shownRings;
    }

    /**
     * The minimums in the scale's units; by area, the minimums' polygon at 1 on each active spoke, and an adaptive
     * minimum that is off only where a set one gives its spoke a scale.
     */
    private void inUnits() {
        boolean[] active = AreaScore.floors(shownRules, exampleFacts).active;
        for (int i = 0; i < NAMES.length; i++) {
            set[i] = setCents[i] / unit[i];
            learned[i] = learnedCents[i] / unit[i];
            areaMin[i] = byArea && active[i] && (i != AreaScore.ITEM || hasItems(exampleFacts)) ? 1 : Double.NaN;
            if (byArea && !active[i]) learned[i] = Double.NaN;
        }
    }

    /** Starts the points gliding from where they are now to the new values, if any moved. */
    private void glideTo() {
        float progress = Motion.settle(glideStart, GLIDE_MS);
        boolean moved = glideStart == 0;
        for (int i = 0; i < NAMES.length; i++) {
            float nextSet = fraction(set[i]);
            float nextLearned = fraction(learned[i]);
            moved |= nextSet != setTo[i] || nextLearned != learnedTo[i] || fraction(areaMin[i]) != areaTo[i];
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
            learnedFrom[i] = learnedFrom[i] + (learnedTo[i] - learnedFrom[i]) * progress;
            areaFrom[i] = areaFrom[i] + (areaTo[i] - areaFrom[i]) * progress;
            setTo[i] = fraction(set[i]);
            learnedTo[i] = fraction(learned[i]);
            areaTo[i] = fraction(areaMin[i]);
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

    static boolean knownHotspotDistance(Double miles) {
        return miles != null && Double.isFinite(miles) && miles >= 0;
    }

    static String distanceText(double miles) {
        if (miles > 0 && miles < 0.01) return "<0.01";
        return String.format(java.util.Locale.US, "%.2f", miles).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    static String hotspotSaid(Double miles) {
        if (!knownHotspotDistance(miles)) return "Final-stop distance to nearest hotspot unavailable";
        if (miles == 0) return "Final stop at nearest hotspot, inverse distance unbounded";
        return "Final stop " + distanceText(miles) + " miles from nearest hotspot, "
                + String.format(java.util.Locale.US, "%.2f inverse miles", 1 / miles);
    }

    private static double hotspotDisplayValue(Double miles) {
        return knownHotspotDistance(miles) ? 100 * HOTSPOT_DISPLAY_UNIT / miles : Double.NaN;
    }

    /**
     * "An offer like 24 min · 6 mi · 2 stops needs $18.75.", or that it is declined for its stops, or that any pay
     * passes: the rules as given, applied to the example offer.
     */
    static String needs(FilterSettings rules, OfferSnapshot example) {
        String like = "An offer like " + example.minutes + " min · " + trim(example.miles) + " mi · " + example.stops
                + (example.stops == 1 ? " stop" : " stops");
        if (hasItems(example)) like += " · " + example.items + (example.items == 1 ? " item" : " items");
        if (rules.maxStops > 0 && example.stops > rules.maxStops) {
            return like + " is declined: at most " + rules.maxStops + (rules.maxStops == 1 ? " stop." : " stops.");
        }
        OfferRule.Decision decision = OfferRule.evaluate(
                new OfferSnapshot(ANY_PAY, example.miles, example.minutes, example.stops)
                        .withFinalStopHotspotMiles(example.finalStopHotspotMiles)
                        .withItems(example.items, example.itemCountApplicable), rules);
        if (decision.result == OfferRule.Result.DECLINE) return like + " is declined by your rules.";
        if (decision.result == OfferRule.Result.REVIEW
                && (rules.perItemCents > 0 || rules.risingOffers && rules.best.hasPerItem())
                && example.itemCountApplicable && !hasItems(example)) {
            return like + " needs the item count before it can be filtered.";
        }
        if (decision.result == OfferRule.Result.REVIEW && rules.hotspotProximityHundredths > 0
                && !knownHotspotDistance(example.finalStopHotspotMiles)) {
            return like + " needs the final stop’s distance from the nearest hotspot before its hotspot rule can be checked.";
        }
        if (decision.requiredCents > 0 && decision.requiredCents < ANY_PAY) {
            return like + " needs " + DecisionLog.money(decision.requiredCents)
                    + (rules.scoreByArea ? " to score " + rules.minimumScalePercent + "%." : ".");
        }
        return like + (decision.result == OfferRule.Result.REVIEW ? " needs more offer details." : " passes at any pay.");
    }

    /** The line under the star: what the example offer needs. */
    String caption() {
        boolean anyMinimum = false;
        for (int i = 0; i < NAMES.length; i++) {
            anyMinimum |= !Double.isNaN(setCents[i]) || !Double.isNaN(learnedCents[i]);
        }
        return anyMinimum ? needs : needs + " No minimums yet.";
    }

    /** Cents the set minimum on spoke {@code axis} asks of the example offer; NaN where none is set (for tests). */
    double setAsks(int axis) {
        return setCents[axis];
    }

    /** Cents the adaptive minimum on spoke {@code axis} asks of the example offer; NaN where none (for tests). */
    double learnedAsks(int axis) {
        return learnedCents[axis];
    }

    private String describe() {
        List<String> spokes = new ArrayList<>();
        for (int i : AreaScore.DRAW_ORDER) {
            if (i == AreaScore.HOTSPOT || i == AreaScore.ITEM) {
                spokes.add(knobSaid(i).replaceAll("\\.$", ""));
                continue;
            }
            spokes.add(NAMES[i] + ": " + (setText[i] == null ? "no set minimum" : "set " + setText[i]) + ", "
                    + (learnedText[i] == null ? "no adaptive minimum yet" : "adaptive " + learnedText[i]));
        }
        String described = "Minimums, set and adaptive now. " + String.join(". ", spokes) + "."
                + (anyLearned() ? "" : " Adaptive minimum: nothing learned yet.")
                + (adaptiveOn ? "" : " Adaptive minimum is off, so the adaptive values are not applied.")
                + (byArea ? " " + SCORE_SAID + (shownRules.minimumScalePercent == 100
                        ? " is on: an offer passes when its shape covers at least the minimums' area"
                        : " is on: an offer passes at " + shownRules.minimumScalePercent + "% fitness or more")
                        + (shownRules.maxStops > 0 ? ", and declines above " + shownRules.maxStops
                        + (shownRules.maxStops == 1 ? " stop." : " stops.") : ".") : "");
        int chosen = strongShape();
        if (chosen >= 0 && markScores.get(chosen) >= 0) {
            described += " " + (markTimes.get(chosen) == newestEntryAt ? "The newest offer" : "The chosen offer") + " scores "
                    + markScores.get(chosen) + "% by area under the current minimums.";
            int recorded = markEntries.get(chosen).scorePercent;
            if (recorded >= 0 && recorded != markScores.get(chosen)) {
                described += " At decision it scored " + recorded + "%.";
            }
        }
        if (!byArea) described += " The hotspot spoke uses inverse miles: closer is farther out; "
                + "its 1 per mile shares the $10 ring radius for display only.";
        described += " Offers versus profit " + shownTradeoffPercent() + "%: left means more offers and lower "
                + "minimums; right means more profit and higher minimums. Effective minimums "
                + shownRules.minimumScalePercent + "%. Saved and learned values stay unchanged; max stops stays fixed."
                + (byArea ? " The required score is " + shownRules.minimumScalePercent + "%." : "");
        described += " Farther out means higher payout or pay rates, or a final stop nearer the hotspot."
                + " Solid blue is your set minimums; dashed purple is learned minimums; colored shapes are offers."
                + " Drag the offers/profit control sideways to adjust the tradeoff.";
        if (marks.isEmpty()) return described;
        return described + " The constellation shows the latest or selected offer. Choose an older offer on the skyline.";
    }

    // ---- Layout: the constellation's circle, with a spoke icon at each corner (or beside it, in a header). ----

    /** Draws it for a short window's header: the icons beside the circle, the circle as tall as the header. */
    void setBeside(boolean on) {
        if (on == beside) return;
        beside = on;
        requestLayout();
        invalidate();
    }

    boolean beside() {
        return beside;
    }

    // ---- The page's sky: one large circle, placed by the page, behind the mascot and the counts. ----

    /**
     * Draws the constellation as the page's sky: its circle of {@code radius} around ({@code x}, {@code y}), in this
     * view's pixels, faded under each of {@code over}.
     */
    void compose(float x, float y, float radius, List<Veil> over) {
        boolean moved = x != skyX || y != skyY || radius != skyRadius || !veils.equals(over);
        skyX = x;
        skyY = y;
        skyRadius = radius;
        veils.clear();
        veils.addAll(over);
        if (moved) {
            layoutMoved = true;
            invalidate();
        }
    }

    /** Drawn as the page's sky, rather than in a header or a row of its own. */
    boolean backdrop() {
        return !beside && skyRadius > 0;
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

    /** As the sky, the height from the circle's middle to the outer edge of an icon at a spoke's end. */
    float backdropHalfHeight(float radius) {
        return backdropAbove(radius);
    }

    /** Hotspot proximity points up and pay per item points down; the original four keep their angles. */
    float backdropAbove(float radius) {
        return radius + ui.dp(ICON_GAP_DP) + backdropIcon();
    }

    float backdropBelow(float radius) {
        // The lower rim already belongs to the scene. The item icon can sit just inside it, so a sixth label
        // must not add another full icon row to a short split window or push the map off a large-font screen.
        return radius + ui.dp(2);
    }

    /** As the sky, the width from the circle's middle to the outer edge of an icon at a spoke's end. */
    float backdropHalfWidth(float radius) {
        return COS * radius + ui.dp(ICON_OUT_DP) + backdropIcon() / 2;
    }

    /** As the sky, the height from the circle's middle to the ends of its spokes. */
    static float spokeHalfHeight(float radius) {
        return SIN * radius;
    }

    /**
     * As the sky, where spoke {@code axis}'s icon stands, into {@code out}: above an upper spoke's end and below a lower
     * one's; where a line of words crosses that place, on the spoke's other side instead (above a lower spoke's end,
     * clear of the mascot). Answers false when words cross both places: the icon waits, hidden, until the line goes.
     */
    private boolean skyIcon(int axis, float cx, float cy, float radius, RectF out) {
        if (axis == AreaScore.STOP && !placingStops && !stopsIcon.isEmpty()) {
            out.set(stopsIcon);
            return true;
        }
        float[] tip = point(cx, cy, radius, axis, 1);
        boolean right = axis == AreaScore.MILE || axis == AreaScore.MINUTE;
        boolean below = axis == AreaScore.MINUTE || axis == AreaScore.STOP || axis == AreaScore.ITEM;
        float size = backdropIcon();
        float middle = tip[0] + (upright(axis) ? 0 : (right ? 1 : -1) * ui.dp(ICON_OUT_DP));
        for (int side = 0; side < 2; side++) {
            boolean under = below == (side == 0);
            float top = under ? tip[1] + ui.dp(ICON_GAP_DP) : tip[1] - ui.dp(ICON_GAP_DP) - size;
            out.set(middle - size / 2, top, middle + size / 2, top + size);
            if (out.top >= ui.dp(2) && out.bottom <= getHeight() - ui.dp(2)
                    && !underWords(out) && (side == 0 || !onMascot(out))
                    && (axis != AreaScore.ITEM || itemIconClear(out))) return true;
        }
        if (axis == AreaScore.ITEM) {
            // The bottom of a short sky can carry a setup line or the shopping offer's observed point.
            // Keep its meaning visible beside the downward spoke, never on top of the point or its knob.
            float preferredTop = tip[1] - ui.dp(ICON_GAP_DP) - size;
            float across = size / 2 + ui.dp(KNOB_REACH_DP + 4);
            for (int rise = 0; rise <= 3; rise++) {
                for (int column : new int[] {0, 1, -1, 2, -2}) {
                    float left = middle - size / 2 + column * across;
                    float top = preferredTop - rise * ui.dp(24);
                    out.set(left, top, left + size, top + size);
                    if (out.left >= ui.dp(4) && out.right <= getWidth() - ui.dp(4) && out.top >= ui.dp(4)
                            && out.bottom <= getHeight() - ui.dp(4) && !underWords(out) && !onMascot(out)
                            && itemIconClear(out)) return true;
                }
            }
        }
        if (axis == AreaScore.STOP) {
            // The upright fifth spoke can raise the mascot over the old above-icon fallback. Keep the max-stops
            // control reachable by moving its icon a little inward/upward, clear of both words and the mascot.
            float preferredTop = tip[1] - ui.dp(ICON_GAP_DP) - size;
            for (int rise = 0; rise <= 3; rise++) {
                for (int inward = 1; inward <= 4; inward++) {
                    float left = middle - size / 2 + inward * ui.dp(24);
                    float top = preferredTop - rise * ui.dp(24);
                    out.set(left, top, left + size, top + size);
                    if (out.left >= ui.dp(4) && out.right <= getWidth() - ui.dp(4) && out.top >= ui.dp(4)
                            && out.bottom <= getHeight() - ui.dp(4) && !underWords(out) && !onMascot(out)
                            && iconClearOfKnobs(out)) return true;
                }
            }
        }
        return false;
    }

    private boolean itemIconClear(RectF box) {
        if (!iconClearOfKnobs(box)) return false;
        int chosen = strongShape();
        if (chosen < 0) return true;
        float[] at = markPoint(chosen, AreaScore.ITEM, Motion.settle(glideStart, GLIDE_MS));
        if (at == null) return true;
        float pad = ui.dp(12) * skyDetail();
        return box.right <= at[0] - pad || box.left >= at[0] + pad
                || box.bottom <= at[1] - pad || box.top >= at[1] + pad;
    }

    private boolean iconClearOfKnobs(RectF box) {
        float glide = Motion.settle(glideStart, GLIDE_MS);
        float clear = ui.dp(KNOB_REACH_DP);
        for (int axis = 0; axis < NAMES.length; axis++) {
            float[] at = point(skyX, skyY, skyRadius, axis, knobFraction(axis, glide));
            float dx = Math.max(0, Math.max(box.left - at[0], at[0] - box.right));
            float dy = Math.max(0, Math.max(box.top - at[1], at[1] - box.bottom));
            if (dx * dx + dy * dy < clear * clear) return false;
        }
        return true;
    }

    /** As the sky, the icons' boxes as shown now (the adopt button's too), in this view's pixels. */
    void iconsAt(List<RectF> out) {
        if (!backdrop()) return;
        if (knobsOn()) placeStops();
        for (int i = 0; i < ICONS.length; i++) {
            RectF box = new RectF();
            if (skyIcon(i, skyX, skyY, skyRadius, box)) out.add(box);
        }
        if (adoptShown()) out.add(new RectF(adoptBox));
        if (scoreToggleShown()) out.add(new RectF(scoreBox));
        if (adaptiveShown()) out.add(new RectF(adaptiveBox));
        if (stopsShown()) out.add(new RectF(stopsBox));
    }

    /** As the sky, where the rings' dollars stand now, halo and all, in this view's pixels. */
    void wordsAt(List<RectF> out) {
        if (!backdrop()) return;
        placeLevelLabels(skyX, skyY, skyRadius, Motion.settle(glideStart, GLIDE_MS));
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

    /** The expanded constellation spells out each icon; the collapsed header keeps its one expand target. */
    String axisLabelText(int axis) { return axis >= 0 && axis < AXIS_LABELS.length ? AXIS_LABELS[axis] : ""; }

    RectF axisLabelBox(int axis) {
        if (axis < 0 || axis >= axisLabels.length) return null;
        placeAxisLabels();
        return axisLabels[axis].isEmpty() ? null : new RectF(axisLabels[axis]);
    }

    private void placeAxisLabels() {
        for (RectF box : axisLabels) box.setEmpty();
        // A constrained sky can also draw the miniature header-sized constellation without setting beside.
        if (!backdrop() || skyRadius < ui.dp(48) || getWidth() <= 0 || getHeight() <= 0) return;
        if (knobsOn()) { placeStops(); placeButtons(); }
        RectF[] iconBoxes = new RectF[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) {
            RectF icon = new RectF();
            if (skyIcon(i, skyX, skyY, skyRadius, icon)) iconBoxes[i] = icon;
        }
        float height = Math.max(ui.dp(11), Ui.lineHeight(axisText));
        float gap = ui.dp(3);
        for (int axis : AreaScore.DRAW_ORDER) {
            float width = Math.max(ui.dp(1), axisText.measureText(AXIS_LABELS[axis]));
            RectF icon = iconBoxes[axis];
            float[] tip = point(skyX, skyY, skyRadius, axis, 1);
            float anchorX = icon == null ? tip[0] : icon.centerX();
            float anchorY = icon == null ? tip[1] : icon.centerY();
            float halfIcon = icon == null ? 0 : icon.height() / 2;
            boolean placed = false;
            // First attach the name directly below/above/beside its icon.
            float[][] near = {{anchorX, anchorY + halfIcon + gap + height / 2},
                    {anchorX, anchorY - halfIcon - gap - height / 2},
                    {anchorX + halfIcon + gap + width / 2, anchorY},
                    {anchorX - halfIcon - gap - width / 2, anchorY}};
            for (float[] at : near) {
                if (tryAxisLabel(axis, at[0], at[1], width, height, iconBoxes)) { placed = true; break; }
            }
            // Setup text can cover a spoke's tip. Keep its name on the same ray, nearer the middle instead.
            for (float fraction : new float[] {0.9f, 0.75f, 0.6f, 0.45f}) {
                if (placed) break;
                float[] at = point(skyX, skyY, skyRadius, axis, fraction);
                for (int side : new int[] {-1, 1}) {
                    if (tryAxisLabel(axis, at[0], at[1] + side * (height / 2 + ui.dp(7)),
                            width, height, iconBoxes)) { placed = true; break; }
                }
            }
            // A very short expanded sky may need the closest free place on that side of the circle.
            if (!placed) {
                RectF best = null;
                double closest = Double.MAX_VALUE;
                float step = Math.max(ui.dp(14), height + gap);
                for (float y = gap + height / 2; y < getHeight() - height / 2 - gap; y += step) {
                    for (float x = gap + width / 2; x < getWidth() - width / 2 - gap; x += ui.dp(24)) {
                        double distance = Math.hypot(x - anchorX, y - anchorY);
                        if (distance >= closest || !tryAxisLabel(axis, x, y, width, height, iconBoxes)) continue;
                        closest = distance;
                        best = new RectF(axisLabels[axis]);
                        axisLabels[axis].setEmpty();
                    }
                }
                if (best != null) axisLabels[axis].set(best);
            }
        }
    }

    private boolean tryAxisLabel(int axis, float x, float y, float width, float height, RectF[] iconBoxes) {
        float margin = ui.dp(4);
        x = Math.max(margin + width / 2, Math.min(getWidth() - margin - width / 2, x));
        RectF box = new RectF(x - width / 2, y - height / 2, x + width / 2, y + height / 2);
        if (box.top < margin || box.bottom > getHeight() - margin || box.width() > getWidth() - 2 * margin
                || underWords(box) || onMascot(box)) return false;
        // The sun/moon is another round veil, not a text row or the mascot, and must stay clear too.
        for (Veil veil : veils) if (veil.round && RectF.intersects(box, veil.box)) return false;
        RectF padded = new RectF(box);
        padded.inset(-ui.dp(2), -ui.dp(2));
        for (RectF icon : iconBoxes) if (icon != null && RectF.intersects(padded, icon)) return false;
        for (int i = 0; i < axisLabels.length; i++) {
            if (i != axis && !axisLabels[i].isEmpty() && RectF.intersects(padded, axisLabels[i])) return false;
        }
        for (RectF level : levelBoxes) if (RectF.intersects(padded, level)) return false;
        if (buttonsPlaced && (RectF.intersects(padded, scoreBox) || RectF.intersects(padded, adaptiveBox)
                || (adoptable && RectF.intersects(padded, adoptBox)))) return false;
        if (!stopsBox.isEmpty() && RectF.intersects(padded, stopsBox)) return false;
        if (knobsOn()) for (int i = 0; i < NAMES.length; i++) {
            float[] at = point(skyX, skyY, skyRadius, i, knobFraction(i, 1));
            float dx = Math.max(0, Math.max(padded.left - at[0], at[0] - padded.right));
            float dy = Math.max(0, Math.max(padded.top - at[1], at[1] - padded.bottom));
            if (Math.hypot(dx, dy) < ui.dp(9)) return false;
        }
        axisLabels[axis].set(box);
        return true;
    }

    private void drawAxisLabels(Canvas canvas) {
        placeAxisLabels();
        int color = axisText.getColor();
        for (int i = 0; i < axisLabels.length; i++) {
            RectF box = axisLabels[i];
            axisText.setColor(i == AreaScore.HOTSPOT ? ui.inkSecondary : color);
            if (!box.isEmpty()) canvas.drawText(AXIS_LABELS[i], box.left, box.top - axisText.ascent(), axisText);
        }
        axisText.setColor(color);
    }

    /** The rings' dollars as the sky shows them now, left to right (for tests). */
    List<String> levelWords() {
        if (backdrop()) placeLevelLabels(skyX, skyY, skyRadius, Motion.settle(glideStart, GLIDE_MS));
        return new ArrayList<>(levelWords);
    }

    /**
     * As the sky, only a touch on the circle (an icon at a spoke's end, a knob, the button, an offer's mark) is for
     * the constellation.
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
     * As the sky, a finger exploring the screen with a screen reader on finds each knob, the buttons and each marked
     * offer (as a tap would pick it) by itself; the rest of the circle is the chart as a whole.
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

    /** The width a header gives it at {@code heightDp} tall: the circle, and an icon with a little room each side. */
    static int besideWidthDp(int heightDp) {
        return heightDp - 8 + 2 * (ICON_DP + 8);
    }

    /** Room under the circle; there is no key, the icons and the colors being the same as everywhere else. */
    private float keyHeight() {
        return ui.dp(4);
    }

    private float nameWidth() {
        return ui.dp(ICON_DP);
    }

    /** The circle's radius: as large as fits with the icons outside it at the spokes' ends, up to 130 dp. */
    private float windowRadius(float width) {
        float room = (width / 2 - nameWidth() - ui.dp(2)) / COS - ui.dp(6);
        return Math.max(ui.dp(MIN_WINDOW_DP), Math.min(ui.dp(130), room));
    }

    /**
     * As tall as the circle at this width needs, or whatever the page gives it on one screen; asked with no limit
     * (the page working out what fits), it answers the least it reads well at.
     */
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        float window = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED ? MIN_WINDOW_DP * ui.dp(1)
                : windowRadius(width);
        setMeasuredDimension(width, resolveSize(Math.round(2 * window + ui.dp(8) + keyHeight()), heightSpec));
    }

    /** The circle's radius at this size: as wide as fits, and no taller than the height given. */
    private float window(float width, float height) {
        float byHeight = (height - ui.dp(8) - keyHeight()) / 2;
        return Math.max(ui.dp(MIN_WINDOW_DP) * 0.8f, Math.min(windowRadius(width), byHeight));
    }

    @Override protected void onDraw(Canvas canvas) {
        if (backdrop()) {
            drawSky(canvas);
            nextFrame();
            return;
        }
        float width = getWidth();
        float window = window(width, getHeight());
        float cx = width / 2;
        // The circle and its key, centred in the height given.
        float top = Math.max(0, (getHeight() - (2 * window + ui.dp(8) + keyHeight())) / 2);
        float cy = top + window + ui.dp(4);
        float radius = window - ui.dp(12);
        if (beside) {
            cy = getHeight() / 2f;
            radius = Math.max(ui.dp(12), Math.min(getHeight() / 2f - ui.dp(4), width / 2f - ui.dp(ICON_DP + 8)));
        }
        detail = Math.max(0.6f, Math.min(1, radius / ui.dp(45)));

        drawGrid(canvas, cx, cy, radius);
        drawOfferShapes(canvas, cx, cy, radius);
        drawMarks(canvas, cx, cy, radius);
        float glide = Motion.settle(glideStart, GLIDE_MS);
        holdSet();
        drawMinimums(canvas, cx, cy, radius, glide);
        drawChosenOutline(canvas, cx, cy, radius);
        for (int i = 0; i < NAMES.length; i++) {
            if (beside) drawIconBeside(canvas, i, cx, cy, radius);
            else drawIcon(canvas, i, cx, cy, window, width, ui.dp(ICON_DP));
        }
        nextFrame();
    }

    /** A glide to new values (after a drag, say) draws every frame; the rest is steady motion. */
    private void nextFrame() {
        if (Motion.settle(glideStart, GLIDE_MS) < 1 || beckoning()) Motion.settling(this);
        else Motion.next(this);
    }

    /**
     * As the page's sky: the rings, spokes, offers, shapes and icons in one layer, the rings fading out at the view's
     * top and bottom edges, and everything faded where words or the mascot sit (the mascot's disc cut out); then, over
     * it, the rings' dollars in their halos.
     */
    private void drawSky(Canvas canvas) {
        float cx = skyX;
        float cy = skyY;
        float radius = skyRadius;
        detail = skyDetail();
        int layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
        drawGrid(canvas, cx, cy, radius);
        fadeEdges(canvas, cy, radius);
        drawOfferShapes(canvas, cx, cy, radius);
        drawMarks(canvas, cx, cy, radius);
        float glide = Motion.settle(glideStart, GLIDE_MS);
        holdSet();
        drawMinimums(canvas, cx, cy, radius, glide);
        drawChosenOutline(canvas, cx, cy, radius);
        boolean knobs = knobsOn();
        if (knobs) drawKnobs(canvas, cx, cy, radius, glide);
        // The selected offer's ticket carries its score; the persistent percentage here controls the minimums.
        if (knobs) placeStops();
        for (int i = 0; i < NAMES.length; i++) {
            if (!skyIcon(i, cx, cy, radius, iconBox)) continue;
            icons[i].setBounds(Math.round(iconBox.left), Math.round(iconBox.top), Math.round(iconBox.right),
                    Math.round(iconBox.bottom));
            icons[i].draw(canvas);
        }
        for (Veil veil : veils) drawVeil(canvas, veil.box, veil.round, veil.fade);
        canvas.restoreToCount(layer);
        // The held knob's readout is placed first, so the rings' dollars it would cover step aside for it.
        if (readoutAxis() >= 0) placeReadout(cx, cy, radius);
        drawLevelLabels(canvas, cx, cy, radius, glide);
        if (!knobs) return;
        // Over everything, never faded: the buttons, and the knob under the finger (with what it is set to, once it
        // moves).
        if (scoreToggleShown()) drawScoreToggle(canvas);
        if (adaptiveShown()) drawAdaptive(canvas);
        if (adoptShown()) drawAdopt(canvas);
        if (stopsShown()) drawStops(canvas);
        drawAxisLabels(canvas);
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

    /** Whether {@code box} meets any of the words the stage laid over the sky. */
    private boolean underWords(RectF box) {
        for (Veil veil : veils) if (!veil.round && RectF.intersects(veil.box, box)) return true;
        return false;
    }

    /** Whether the point ({@code x}, {@code y}) lies under any of the words the stage laid over the sky. */
    private boolean underWords(float x, float y) {
        for (Veil veil : veils) if (!veil.round && veil.box.contains(x, y)) return true;
        return false;
    }

    /** Whether {@code box} comes within a little of the mascot's disc (the round veil cut out in full). */
    private boolean onMascot(RectF box) {
        for (Veil veil : veils) {
            if (!veil.round || veil.fade < 1) continue;
            float reach = veil.box.width() / 2 + ui.dp(10);
            float dx = Math.max(0, Math.max(box.left - veil.box.centerX(), veil.box.centerX() - box.right));
            float dy = Math.max(0, Math.max(box.top - veil.box.centerY(), veil.box.centerY() - box.bottom));
            if (dx * dx + dy * dy < reach * reach) return true;
        }
        return false;
    }

    /** The mascot owns its full ring plus its touch halo, ten dp beyond the inner painted-out disc. */
    private boolean onMascot(float x, float y) {
        for (Veil veil : veils) {
            if (!veil.round || veil.fade < 1) continue;
            float reach = veil.box.width() / 2 + ui.dp(10);
            float dx = x - veil.box.centerX();
            float dy = y - veil.box.centerY();
            if (dx * dx + dy * dy < reach * reach) return true;
        }
        return false;
    }

    /** Where the circle runs past the view's top or bottom, its rings fade out over the last stretch before the edge. */
    private void fadeEdges(Canvas canvas, float cy, float radius) {
        float band = ui.dp(EDGE_FADE_DP);
        float height = getHeight();
        if (cy - radius >= band && cy + radius <= height - band) return;
        if (edgeFadeFor != height) {
            edgeFadeFor = height;
            float top = band / height;
            edgeFade.setShader(new android.graphics.LinearGradient(0, 0, 0, height,
                    new int[] {0xFF000000, 0x00000000, 0x00000000, 0xFF000000},
                    new float[] {0, Math.min(0.5f, top), Math.max(0.5f, 1 - top), 1},
                    android.graphics.Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, 0, getWidth(), height, edgeFade);
    }

    /**
     * Fades the constellation under {@code box}, in three steps from its edge inwards so the fade has no hard edge. A
     * round veil faded in full (the mascot's disc) is cut out altogether inside its feathered edge.
     */
    private void drawVeil(Canvas canvas, RectF box, boolean round, float fade) {
        float feather = ui.dp(7);
        if (round && fade >= 1) {
            float reach = box.width() / 2;
            float[] alphas = {0.35f, 0.5f, 1f};
            for (int step = 0; step < 3; step++) {
                float at = reach + feather * (1 - step);
                if (at <= 0) continue;
                veilPaint.setColor(Math.round(255 * alphas[step]) << 24);
                canvas.drawCircle(box.centerX(), box.centerY(), at, veilPaint);
            }
            return;
        }
        float each = 1 - (float) Math.cbrt(1 - Math.max(0, Math.min(0.99f, fade)));
        veilPaint.setColor(Math.round(255 * each) << 24);
        for (int step = 0; step < 3; step++) {
            float inset = feather * (step - 1);
            if (round) {
                float reach = box.width() / 2 - inset;
                if (reach > 0) canvas.drawCircle(box.centerX(), box.centerY(), reach, veilPaint);
            } else {
                veilBox.set(box);
                veilBox.inset(inset, inset);
                if (veilBox.width() > 0 && veilBox.height() > 0) {
                    float corner = Math.min(ui.dp(16), veilBox.height() / 2);
                    canvas.drawRoundRect(veilBox, corner, corner, veilPaint);
                }
            }
        }
    }

    /**
     * As the sky, the middle and outer rings' dollars along the level line on the right, each just inside its ring,
     * or just outside it where a shape's edge crosses there; a ring whose dollars would leave the page, run into
     * another's, or find a shape's edge on both sides goes without.
     */
    private void drawLevelLabels(Canvas canvas, float cx, float cy, float radius, float glide) {
        placeLevelLabels(cx, cy, radius, glide);
        Paint.FontMetrics metrics = levelText.getFontMetrics();
        float baseline = cy - (metrics.ascent + metrics.descent) / 2;
        levelText.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i < levelBoxes.size(); i++) {
            if (readoutAxis() >= 0 && RectF.intersects(levelBoxes.get(i), pillBox)) continue;
            // Twice, so the halo is soft but full.
            canvas.drawText(levelWords.get(i), levelBoxes.get(i).left, baseline, levelText);
            canvas.drawText(levelWords.get(i), levelBoxes.get(i).left, baseline, levelText);
        }
    }

    /** Works out where the rings' dollars stand (see {@link #drawLevelLabels}), into the level lists. */
    private void placeLevelLabels(float cx, float cy, float radius, float glide) {
        levelBoxes.clear();
        levelWords.clear();
        if (outer <= 0) return;
        Paint.FontMetrics metrics = levelText.getFontMetrics();
        float baseline = cy - (metrics.ascent + metrics.descent) / 2;
        holdSet();
        float[] edges = byArea ? new float[] {rightCrossing(minimumPolygon(cx, cy, radius, glide), cx, cy)}
                : new float[] {edgeCrossing(drawnSet, drawnFrom, drawnTo, glide, cx, radius),
                        edgeCrossing(learned, learnedFrom, learnedTo, glide, cx, radius)};
        float clear = ui.dp(4) + ui.dp(1) * Math.max(1, detail);
        float before = -Float.MAX_VALUE;
        for (int ring : labelRings(false)) {
            String words = levelLabel(ring);
            float width = levelText.measureText(words);
            float at = cx + radius * ring / rings;
            for (int side = 0; side < 2; side++) {
                float left = side == 0 ? at - ui.dp(5) - width : at + ui.dp(5);
                RectF box = new RectF(left, baseline + metrics.ascent, left + width, baseline + metrics.descent);
                if (box.left < before + ui.dp(8) || box.right > getWidth() - ui.dp(6)) continue;
                boolean onEdge = false;
                for (float edge : edges) onEdge |= !Float.isNaN(edge) && edge > box.left - clear && edge < box.right + clear;
                if (onEdge) continue;
                levelBoxes.add(box);
                levelWords.add(words);
                before = box.right;
                break;
            }
        }
    }

    /**
     * Where a shape's right-hand edge (from the upper right spoke's point to the lower right's) crosses the level line
     * as drawn now; NaN when the shape is not drawn.
     */
    private float edgeCrossing(double[] values, float[] from, float[] to, float glide, float cx, float radius) {
        boolean any = false;
        for (double value : values) any |= !Double.isNaN(value);
        if (!any) return Float.NaN;
        float upper = Double.isNaN(values[1]) ? 0 : from[1] + (to[1] - from[1]) * glide;
        float lower = Double.isNaN(values[2]) ? 0 : from[2] + (to[2] - from[2]) * glide;
        if (upper + lower <= 0) return cx;
        return cx + radius * COS * 2 * upper * lower / (upper + lower);
    }

    /** A ring's label: its dollars strictly ("$12"), its share of the minimums by area ("150%"). */
    private String levelLabel(int ring) {
        if (byArea) return Math.round(ring * AREA_RING * 100) + "%";
        return "$" + (ringCents * ring / 100);
    }

    /**
     * The rings that are labeled: strictly the middle and outer rings (only the outer on a small circle); by area the
     * minimums' ring, 100%, and the one at 200% (or 150%, the outer one when there are only three).
     */
    private int[] labelRings(boolean small) {
        if (byArea) {
            int hundred = (int) Math.round(1 / AREA_RING);
            return small ? new int[] {hundred} : new int[] {hundred, Math.min(rings, 2 * hundred)};
        }
        return small ? new int[] {3} : new int[] {2, 3};
    }

    /** Faint rings and spokes, with the rings' dollars in the gap between the two top spokes. */
    private void drawGrid(Canvas canvas, float cx, float cy, float radius) {
        line.setPathEffect(null);
        line.setStrokeWidth(Math.max(1, ui.dp(1)));
        // Keep the useful reference ring; the rest is a quiet guide behind the offer and minimums.
        boolean adjusting = readoutAxis() >= 0 || scaleDragging;
        for (int ring = 1; ring <= rings; ring++) {
            boolean reference = byArea ? ring == Math.round(1 / AREA_RING) : ring == rings;
            line.setColor(ui.dark ? (reference || adjusting ? 0x24FFFFFF : 0x0EFFFFFF)
                    : (reference || adjusting ? 0x180B2A55 : 0x0A0B2A55));
            canvas.drawCircle(cx, cy, radius * ring / rings, line);
        }
        line.setColor(ui.dark ? 0x20FFFFFF : 0x160B2A55);
        for (int i = 0; i < NAMES.length; i++) {
            float[] tip = point(cx, cy, radius, i, 1);
            canvas.drawLine(cx, cy, tip[0], tip[1], line);
        }
        if (outer <= 0 || backdrop()) return;
        text.setFakeBoldText(false);
        text.setTextSize(Math.min(ui.sp(10), ui.dp(13)));
        text.setColor(ui.dark ? 0x80FFFFFF : 0xB0214066);
        text.setTextAlign(Paint.Align.CENTER);
        float baseline = -(text.getFontMetrics().ascent + text.getFontMetrics().descent) / 2;
        // Between the upright and upper-right spokes, so dollar labels never sit on the proximity axis.
        for (int ring : labelRings(radius < ui.dp(40))) {
            float out = radius * ring / rings;
            canvas.drawText(levelLabel(ring), cx + out * 0.5f, cy - out * COS + baseline, text);
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

    /** How far out a value sits on the scale (cents strictly, shares of the floor by area), the outer ring being 1. */
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
        for (int axis = 0; axis < NAMES.length; axis++) {
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
     * The minimums: strictly, the set shape (solid, filled) and the adaptive one (dashed). By area, the minimums' area
     * first, a filled polygon over the active spokes where every minimum stands at 100% (each spoke's floor being the
     * higher of the set and the adaptive minimum, while that is on; while a knob is held, its spoke where the knob
     * asks), in a fainter outline; then over it, as strictly, the set shape (solid, with its stars, under the knobs)
     * at its share of that floor, and the adaptive one (dashed, with its sparkles) at its own. Where nothing is learned
     * there is no adaptive shape; while the adaptive minimum is on, a faint dashed outline just outside the set shape
     * says it is on and has learned nothing yet.
     */
    private void drawMinimums(Canvas canvas, float cx, float cy, float radius, float glide) {
        if (!byArea) {
            boolean scaled = shownScalePercent() != 100;
            if (scaled) drawEffectiveMinimum(canvas, minimumPolygon(cx, cy, radius, glide));
            drawShape(canvas, cx, cy, radius, drawnSet, drawnFrom, drawnTo, glide, setColor(), !scaled, false, true);
            drawShape(canvas, cx, cy, radius, learned, learnedFrom, learnedTo, glide, learnedColor(), adaptiveOn && !scaled, true,
                    true);
            drawNothingLearned(canvas, cx, cy, radius, glide);
            return;
        }
        List<float[]> area = minimumPolygon(cx, cy, radius, glide);
        drawEffectiveMinimum(canvas, area);
        drawShape(canvas, cx, cy, radius, drawnSet, drawnFrom, drawnTo, glide, setColor(), false, false, true);
        drawShape(canvas, cx, cy, radius, learned, learnedFrom, learnedTo, glide, learnedColor(), false, true, true);
        drawNothingLearned(canvas, cx, cy, radius, glide);
    }

    /** The effective boundary moves with the one scale; saved stars and learned sparkles keep their raw values. */
    private void drawEffectiveMinimum(Canvas canvas, List<float[]> area) {
        if (area.size() >= 2) {
            path.reset();
            for (int i = 0; i < area.size(); i++) {
                if (i == 0) path.moveTo(area.get(i)[0], area.get(i)[1]);
                else path.lineTo(area.get(i)[0], area.get(i)[1]);
            }
            path.close();
            int color = setColor();
            fill.setColor((color & 0x00FFFFFF) | 0x38000000);
            canvas.drawPath(path, fill);
            // Fainter than the shapes drawn over it, so the adaptive shape's dashes read where it is the edge.
            line.setPathEffect(null);
            line.setColor((color & 0x00FFFFFF) | 0x80000000);
            line.setStrokeWidth(ui.dp(1.2f) * Math.max(1, detail));
            canvas.drawPath(path, line);
        }
    }

    /** Whether the adaptive minimum has learned anything, on any spoke. */
    private boolean anyLearned() {
        for (double cents : learnedCents) if (!Double.isNaN(cents)) return true;
        return false;
    }

    /**
     * The adaptive minimum is on but has learned nothing: a faint dashed outline in its color, just outside the set
     * shape (where the learned shape would first show), so it reads as on and waiting rather than missing.
     */
    private void drawNothingLearned(Canvas canvas, float cx, float cy, float radius, float glide) {
        if (!adaptiveOn || anyLearned() || radius <= 0) return;
        boolean any = false;
        for (double value : drawnSet) any |= !Double.isNaN(value);
        if (!any) return;
        float out = ui.dp(4) * detail / radius;
        path.reset();
        for (int i = 0; i < AreaScore.HOTSPOT; i++) {
            float shown = Double.isNaN(drawnSet[i]) ? 0 : drawnFrom[i] + (drawnTo[i] - drawnFrom[i]) * glide;
            float[] at = point(cx, cy, radius, i, shown + out);
            if (i == 0) path.moveTo(at[0], at[1]);
            else path.lineTo(at[0], at[1]);
        }
        path.close();
        line.setColor((learnedColor() & 0x00FFFFFF) | 0x73000000);
        line.setStrokeWidth(ui.dp(1.2f) * Math.max(1, detail));
        line.setPathEffect(dash);
        canvas.drawPath(path, line);
        line.setPathEffect(null);
    }

    /**
     * By area, the minimums' polygon as drawn now: a point on each active spoke at 100%, gliding; while a knob is
     * held, its spoke at what the knob asks (or the adaptive minimum there, if that asks more and is on), on the scale
     * held still for the drag.
     */
    private List<float[]> minimumPolygon(float cx, float cy, float radius, float glide) {
        float[][] axes = new float[NAMES.length][];
        for (int i = 0; i < NAMES.length; i++) {
            float at;
            boolean on;
            if (byArea) {
                at = areaFrom[i] + (areaTo[i] - areaFrom[i]) * glide;
                on = !Double.isNaN(areaMin[i]);
            } else {
                on = !Double.isNaN(drawnSet[i]) || (adaptiveOn && !Double.isNaN(learned[i]));
                at = Double.isNaN(drawnSet[i]) ? 0 : drawnFrom[i] + (drawnTo[i] - drawnFrom[i]) * glide;
                if (adaptiveOn && !Double.isNaN(learned[i])) {
                    at = Math.max(at, learnedFrom[i] + (learnedTo[i] - learnedFrom[i]) * glide);
                }
            }
            if (dragging && i == held && outer > 0 && (i != AreaScore.ITEM || hasItems(exampleFacts))) {
                double ask = dragValue > 0 ? askCents(held, dragValue) / unit[held] : 0;
                if (adaptiveOn && !Double.isNaN(learned[i])) ask = Math.max(ask, learned[i]);
                on = ask > 0;
                at = (float) Math.min(1, ask / outer);
            }
            if (on) axes[i] = point(cx, cy, radius, i, at * shownScalePercent() / 100f);
        }
        return orderedPolygon(axes, cx, cy);
    }

    /**
     * Clockwise drawn order; an empty half-plane closes through the center, matching AreaScore's fan triangles.
     * This avoids negative-area cancellation and self-crossings for sparse sets including the upright spoke.
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

    /** The rightmost point where a closed polygon crosses the level line right of the middle; NaN where none does. */
    private static float rightCrossing(List<float[]> polygon, float cx, float cy) {
        float most = Float.NaN;
        for (int i = 0; i < polygon.size() && polygon.size() >= 2; i++) {
            float[] a = polygon.get(i);
            float[] b = polygon.get((i + 1) % polygon.size());
            if (a[1] == b[1] || (a[1] - cy) * (b[1] - cy) > 0) continue;
            float x = a[0] + (cy - a[1]) * (b[0] - a[0]) / (b[1] - a[1]);
            if (x > cx && (Float.isNaN(most) || x > most)) most = x;
        }
        return most;
    }

    /**
     * How far below ({@code side} 1) or above (-1) the middle a closed polygon's edges cross the upright line at
     * {@code x}; 0 where none does.
     */
    private static float uprightReach(List<float[]> polygon, float x, float cy, int side) {
        float reach = 0;
        for (int i = 0; i < polygon.size() && polygon.size() >= 2; i++) {
            float[] a = polygon.get(i);
            float[] b = polygon.get((i + 1) % polygon.size());
            if (a[0] == b[0] || (a[0] - x) * (b[0] - x) > 0) continue;
            float y = a[1] + (x - a[0]) * (b[1] - a[1]) / (b[0] - a[0]);
            reach = Math.max(reach, (y - cy) * side);
        }
        return reach;
    }

    /** The latest or skyline-selected offer's polygon; hidden history stays out of the everyday constellation. */
    private void drawOfferShapes(Canvas canvas, float cx, float cy, float radius) {
        float glide = Motion.settle(glideStart, GLIDE_MS);
        for (int m = 0; m < marks.size(); m++) if (displayedOffer(m)) {
            drawOfferShape(canvas, cx, cy, radius, m, glide, true, m == pressed());
        }
    }

    /** The ticket's offer, or the skyline's selection/latest: the one offer shown in either scoring mode. */
    private int strongShape() {
        // An open unplottable offer must not reveal a different offer behind its ticket.
        return backdrop() && openAt >= 0 ? opened() : emphasized();
    }

    /** One presentation predicate shared by drawing, touch targets and accessibility nodes. History stays intact. */
    private boolean displayedOffer(int m) {
        return m >= 0 && m < marks.size() && m == strongShape();
    }

    /** The marked offer whose ticket is open, as the sky shows it (a header's chart stays as it was); -1 for none. */
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
        path.reset();
        for (int i = 0; i < points.size(); i++) {
            if (i == 0) path.moveTo(points.get(i)[0], points.get(i)[1]);
            else path.lineTo(points.get(i)[0], points.get(i)[1]);
        }
        path.close();
        line.setPathEffect(null);
        line.setColor((markColor(markResults.get(chosen)) & 0x00FFFFFF) | 0xC0000000);
        line.setStrokeWidth(ui.dp(1.8f) * Math.max(1, detail));
        canvas.drawPath(path, line);
    }

    /** Offer {@code m}'s polygon: faint, a little stronger under a finger ({@code pressed}), or {@code strong}. */
    private void drawOfferShape(Canvas canvas, float cx, float cy, float radius, int m, float glide, boolean strong,
                                boolean pressed) {
        List<float[]> points = offerPolygon(cx, cy, radius, m, glide);
        if (points.size() < 2) return;
        path.reset();
        for (int i = 0; i < points.size(); i++) {
            if (i == 0) path.moveTo(points.get(i)[0], points.get(i)[1]);
            else path.lineTo(points.get(i)[0], points.get(i)[1]);
        }
        path.close();
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
        for (int i = 0; i < NAMES.length; i++) {
            if (!Double.isNaN(mark[i]) && outer > 0) axes[i] = point(cx, cy, radius, i, shown[i]);
        }
        return orderedPolygon(axes, cx, cy);
    }

    /** The offer whose polygon stands out by area: the one chosen on the skyline, else the newest; -1 for none. */
    private int emphasized() {
        long selectedAt = emphasizedAt < 0 ? newestEntryAt : emphasizedAt;
        return selectedAt < 0 ? -1 : markTimes.indexOf(selectedAt);
    }

    /** The skyline selection ({@code null}: latest) is the one displayed in either mode, with its spoken score. */
    void emphasize(DecisionLog.Entry entry) {
        long at = entry == null ? -1 : entry.at;
        if (at == emphasizedAt) return;
        emphasizedAt = at;
        refreshItemReference();
        setContentDescription(caption() + " " + describe());
        dropGoneFocus();
        invalidate();
        nodesChanged();
    }

    /**
     * The offer whose ticket is open ({@code null}: none): as the sky, in either mode its polygon and marks stand out
     * over the rest until the ticket closes.
     */
    void showTicket(DecisionLog.Entry entry) {
        long at = entry == null ? -1 : entry.at;
        if (at == openAt) return;
        openAt = at;
        refreshItemReference();
        setContentDescription(caption() + " " + describe());
        dropGoneFocus();
        invalidate();
        nodesChanged();
    }

    private void refreshItemReference() {
        if (savedRules != null && !dragging) show(shownRules, savedRules, referenceExample, shownRecent);
    }

    /**
     * The chosen offer's score as the chart labels it, worked out from the rules as shown; while a knob is held, from
     * the minimum the knob asks; -1 when none can be worked out (for tests too).
     */
    int emphasizedScore() {
        int chosen = strongShape();
        if (chosen < 0) return -1;
        if (!dragging || held < 0) return markScores.get(chosen);
        int[] rates = shownRules.minimums();
        rates[held] = dragValue;
        return AreaScore.percent(shownRules.withMinimums(rates), markFacts.get(chosen));
    }

    /** The chosen offer's score ("121%") in a small pill in its color, just outside its polygon's farthest point. */
    private void drawScoreLabel(Canvas canvas, float cx, float cy, float radius) {
        int chosen = emphasized();
        int score = emphasizedScore();
        String words = currentScoreLabel();
        if (chosen < 0 || score < 0 || !placeScoreLabel(cx, cy, radius, chosen, words)) return;
        int color = markColor(markResults.get(chosen));
        float height = labelBox.height();
        fill.setColor(color);
        canvas.drawRoundRect(labelBox, height / 2, height / 2, fill);
        scoreText.setColor(ui.dark ? 0xFF0D1428 : Ui.onStatus(color));
        Paint.FontMetrics metrics = scoreText.getFontMetrics();
        canvas.drawText(words, labelBox.centerX(), labelBox.centerY() - (metrics.ascent + metrics.descent) / 2,
                scoreText);
    }

    /** The constellation uses today's rules; the skyline and ticket retain the decision's recorded score. */
    String currentScoreLabel() {
        int chosen = strongShape();
        int score = emphasizedScore();
        if (chosen < 0 || score < 0) return "";
        int recorded = markEntries.get(chosen).scorePercent;
        return (recorded >= 0 && recorded != score ? "Now " : "") + score + "%";
    }

    /**
     * Where the chosen offer's score stands, into the label box: just outside its polygon's farthest point (then the
     * next farthest), inside the page and clear of words, the mascot, the icons, the rings' dollars and the buttons.
     */
    private boolean placeScoreLabel(float cx, float cy, float radius, int m, String words) {
        float glide = Motion.settle(glideStart, GLIDE_MS);
        double[] mark = marks.get(m);
        float[] shown = markShown(m, glide);
        float height = Math.max(ui.dp(20), Ui.lineHeight(scoreText) + ui.dp(4));
        float width = scoreText.measureText(words) + ui.dp(12);
        Integer[] order = new Integer[NAMES.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Float.compare(shown[b], shown[a]));
        // Outside a point (the farthest first), else just inside one, else in the polygon's middle.
        float sumX = 0;
        float sumY = 0;
        int points = 0;
        for (int way = 1; way >= -1; way -= 2) {
            for (int axis : order) {
                if (Double.isNaN(mark[axis])) continue;
                float[] tip = point(cx, cy, radius, axis, shown[axis]);
                if (way > 0) {
                    sumX += tip[0];
                    sumY += tip[1];
                    points++;
                }
                double angle = Math.toRadians(ANGLES[axis]);
                float out = ui.dp(8) + (float) Math.hypot(width / 2 * Math.cos(angle), height / 2 * Math.sin(angle));
                float x = tip[0] + way * (float) Math.cos(angle) * out;
                float y = tip[1] + way * (float) Math.sin(angle) * out;
                labelBox.set(x - width / 2, y - height / 2, x + width / 2, y + height / 2);
                if (labelFits()) return true;
            }
        }
        if (points == 0) return false;
        labelBox.set(sumX / points - width / 2, sumY / points - height / 2, sumX / points + width / 2,
                sumY / points + height / 2);
        return labelFits();
    }

    private boolean labelFits() {
        float margin = ui.dp(2);
        if (labelBox.left < margin || labelBox.top < margin || labelBox.right > getWidth() - margin
                || labelBox.bottom > getHeight() - margin) {
            return false;
        }
        if (underWords(labelBox) || onMascot(labelBox)) return false;
        for (RectF level : levelBoxes) if (RectF.intersects(level, labelBox)) return false;
        for (int i = 0; i < ICONS.length; i++) {
            if (skyIcon(i, skyX, skyY, skyRadius, iconBox) && RectF.intersects(iconBox, labelBox)) return false;
        }
        // Clear of the minimums' points (knobs, stars and sparkles) where they stand.
        float clear = ui.dp(10) * Math.max(1, detail);
        float glide = Motion.settle(glideStart, GLIDE_MS);
        for (int i = 0; i < NAMES.length; i++) {
            float[][] spots = {
                    Double.isNaN(areaMin[i]) ? null : point(skyX, skyY, skyRadius, i, areaTo[i]),
                    Double.isNaN(set[i]) && !(knobsOn() && knobShown(i)) ? null
                            : point(skyX, skyY, skyRadius, i, knobsOn() ? knobFraction(i, glide) : setTo[i]),
                    Double.isNaN(learned[i]) ? null : point(skyX, skyY, skyRadius, i, learnedTo[i])};
            for (float[] spot : spots) {
                if (spot == null) continue;
                float dx = Math.max(0, Math.max(labelBox.left - spot[0], spot[0] - labelBox.right));
                float dy = Math.max(0, Math.max(labelBox.top - spot[1], spot[1] - labelBox.bottom));
                if (dx * dx + dy * dy < clear * clear) return false;
            }
        }
        if (knobsOn() && buttonsPlaced && (RectF.intersects(scoreBox, labelBox)
                || (adaptivePlaced && RectF.intersects(adaptiveBox, labelBox))
                || (adoptPlaced && (adoptable || undoValues != null) && RectF.intersects(adoptBox, labelBox)))) {
            return false;
        }
        return !(stopsShown() && RectF.intersects(stopsBox, labelBox));
    }

    private void drawShape(Canvas canvas, float cx, float cy, float radius, double[] values, float[] from, float[] to,
                           float glide, int color, boolean filled, boolean dashed, boolean polygon) {
        int known = 0;
        for (double value : values) if (!Double.isNaN(value)) known++;
        if (known == 0) return;
        float[][] points = new float[values.length][];
        for (int i = 0; i < values.length; i++) {
            float shown = Double.isNaN(values[i]) ? 0 : from[i] + (to[i] - from[i]) * glide;
            points[i] = point(cx, cy, radius, i, shown);
        }
        float[][] polygonAxes = points.clone();
        for (int i = 0; i < values.length; i++) {
            if (Double.isNaN(values[i]) && (byArea || i == AreaScore.HOTSPOT || i == AreaScore.ITEM)) polygonAxes[i] = null;
        }
        List<float[]> outline = orderedPolygon(polygonAxes, cx, cy);
        path.reset();
        for (int i = 0; i < outline.size(); i++) {
            if (i == 0) path.moveTo(outline.get(i)[0], outline.get(i)[1]);
            else path.lineTo(outline.get(i)[0], outline.get(i)[1]);
        }
        path.close();
        if (filled && polygon) {
            fill.setColor((color & 0x00FFFFFF) | (dashed ? 0x22000000 : 0x30000000));
            canvas.drawPath(path, fill);
        }
        line.setColor(color);
        line.setStrokeWidth(ui.dp(2) * Math.max(1, detail));
        line.setPathEffect(dashed ? dash : null);
        if (polygon) canvas.drawPath(path, line);
        line.setPathEffect(null);
        // The adaptive sparkles breathe slowly.
        float breathe = dashed ? 1 + 0.14f * Motion.wave(3.2f, 0) : 1;
        for (int i = 0; i < values.length; i++) {
            if (Double.isNaN(values[i])) continue;
            float[] at = points[i];
            fill.setColor((color & 0x00FFFFFF) | 0x3A000000);
            canvas.drawCircle(at[0], at[1], ui.dp(8) * breathe * detail, fill);
            fill.setColor(color);
            if (dashed) {
                drawSparkle(canvas, at[0], at[1], ui.dp(6.5f) * breathe * detail, fill);
            } else {
                canvas.drawCircle(at[0], at[1], ui.dp(3.8f) * detail, fill);
                fill.setColor(0xFFFFFFFF);
                canvas.drawCircle(at[0], at[1], ui.dp(1.6f) * detail, fill);
            }
        }
    }

    /** A four-pointed sparkle, the adaptive minimums' mark, filled or outlined as {@code paint} draws. */
    private void drawSparkle(Canvas canvas, float x, float y, float half, Paint paint) {
        float waist = half * 0.22f;
        diamond.reset();
        diamond.moveTo(x, y - half);
        diamond.quadTo(x + waist, y - waist, x + half, y);
        diamond.quadTo(x + waist, y + waist, x, y + half);
        diamond.quadTo(x - waist, y + waist, x - half, y);
        diamond.quadTo(x - waist, y - waist, x, y - half);
        diamond.close();
        canvas.drawPath(diamond, paint);
    }

    /** In a header, a spoke's icon beside the circle on its side, above or below the middle as its spoke points. */
    private void drawIconBeside(Canvas canvas, int axis, float cx, float cy, float radius) {
        boolean right = axis == AreaScore.MILE || axis == AreaScore.MINUTE;
        boolean below = axis == AreaScore.MINUTE || axis == AreaScore.STOP || axis == AreaScore.ITEM;
        int size = ui.dp(ICON_DP);
        int left = Math.round(upright(axis) ? cx - size / 2f
                : right ? cx + radius + ui.dp(6) : cx - radius - ui.dp(6) - size);
        int top = Math.round(axis == AreaScore.HOTSPOT ? Math.max(0, cy - radius - ui.dp(2))
                : axis == AreaScore.ITEM ? Math.min(getHeight() - size, cy + radius + ui.dp(2)) : below ? cy + ui.dp(2) : cy - ui.dp(2) - size);
        icons[axis].setBounds(left, top, left + size, top + size);
        icons[axis].draw(canvas);
    }

    /** A spoke's icon just outside the circle at its corner. */
    private void drawIcon(Canvas canvas, int axis, float cx, float cy, float window, float width, int size) {
        boolean right = axis == AreaScore.MILE || axis == AreaScore.MINUTE;
        boolean below = axis == AreaScore.MINUTE || axis == AreaScore.STOP || axis == AreaScore.ITEM;
        float[] corner = point(cx, cy, window + ui.dp(4), axis, 1);
        int left = Math.round(upright(axis) ? corner[0] - size / 2f
                : right ? corner[0] : corner[0] - size);
        int top = Math.round(below ? corner[1] : corner[1] - size);
        left = Math.max(0, Math.min(Math.round(width) - size, left));
        top = Math.max(0, top);
        icons[axis].setBounds(left, top, left + size, top + size);
        icons[axis].draw(canvas);
    }

    // ---- Knobs: the set minimums, dragged along their spokes (as the sky only). ----

    /** Knobs and the adopt button are on: as the page's sky (a whole screen, or beside Dasher), never in a header. */
    private boolean knobsOn() {
        return backdrop() && changes != null;
    }

    /**
     * Spoke {@code axis} has a knob: its set minimum's, or a hollow one resting just outside the middle. With no rule
     * at all the six hollow knobs are how the first one is set, by a drag.
     */
    private boolean knobShown(int axis) {
        return axis >= 0 && axis < NAMES.length;
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

    /** Where a hollow knob rests, as a share of the radius. */
    private float restFraction() {
        return skyRadius > 0 ? ui.dp(KNOB_REST_DP) / skyRadius : 0;
    }

    /** How far out spoke {@code axis}'s knob stands now, as a share of the radius: at its set minimum, or resting. */
    private float knobFraction(int axis, float glide) {
        if (dragging && axis == held) return heldFraction();
        if (Double.isNaN(set[axis])) return restFraction();
        return setFrom[axis] + (setTo[axis] - setFrom[axis]) * glide;
    }

    /** Where the held knob stands, on the scale held still for the drag, at most as far as it may be pushed. */
    private float heldFraction() {
        if (dragValue <= 0 || outer <= 0) return restFraction();
        float at = (float) (askCents(held, dragValue) / unit[held] / outer);
        return Math.min(at, pushLimit(held) / skyRadius);
    }

    /** Pay the held knob's rate asks of the example offer (as it was when taken), as the set shape draws it. */
    private double askCents(int axis, int rate) {
        if (axis == AreaScore.HOTSPOT) return rate * HOTSPOT_DISPLAY_UNIT;
        if (axis == AreaScore.ITEM && !hasItems(dragFacts)) return (double) rate * ITEM_EDIT_UNITS;
        return amount(AreaScore.fixedFloor(axis, rate, dragFacts));
    }

    /** The example offer's amount of spoke {@code axis}'s unit when the knob was taken: 1 for pay, else miles... */
    private double units(int axis) {
        switch (axis) {
            case 0: return 1;
            case 1: return dragMiles;
            case 2: return dragMinutes;
            case AreaScore.HOTSPOT: return HOTSPOT_DISPLAY_UNIT;
            case AreaScore.ITEM: return dragItems;
            default: return dragStops;
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
        double cents = out / skyRadius * outer * unit[axis];
        long steps = Math.max(1, Math.round(cents / units(axis) / STEPS[axis]));
        return (int) Math.min(FilterSettings.MOST_CENTS, steps * STEPS[axis]);
    }

    /** Half a step of spoke {@code axis}'s knob, in pixels along it, on the scale the drag holds still. */
    private float halfStep(int axis) {
        if (outer <= 0 || skyRadius <= 0) return 0;
        return (float) (askCents(axis, STEPS[axis]) / unit[axis] / outer * skyRadius / 2);
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
     * The knob or button a touch at ({@code x}, {@code y}) is for, the nearest within reach; NO_NODE for none. A knob
     * faded under a line of words is left to that line, which stands in front and takes the touch (screen readers
     * still reach it by swiping).
     */
    private int target(float x, float y) {
        if (!knobsOn()) return NO_NODE;
        float glide = Motion.settle(glideStart, GLIDE_MS);
        float reach = ui.dp(KNOB_REACH_DP);
        float nearest = reach * reach;
        int found = NO_NODE;
        for (int i = 0; i < NAMES.length; i++) {
            if (!knobShown(i)) continue;
            float[] at = point(skyX, skyY, skyRadius, i, knobFraction(i, glide));
            if (underWords(at[0], at[1])) continue;
            float dx = x - at[0];
            float dy = y - at[1];
            if (dx * dx + dy * dy <= nearest) {
                nearest = dx * dx + dy * dy;
                found = i;
            }
        }
        float button = ui.dp(ADOPT_REACH_DP);
        boolean[] shown = {adoptShown(), scoreToggleShown(), adaptiveShown()};
        RectF[] boxes = {adoptBox, scoreBox, adaptiveBox};
        int[] ids = {ADOPT_ID, SCORE_ID, ADAPTIVE_ID};
        for (int b = 0; b < boxes.length; b++) {
            if (!shown[b]) continue;
            float dx = x - boxes[b].centerX();
            float dy = y - boxes[b].centerY();
            if (dx * dx + dy * dy <= Math.min(nearest, button * button)) {
                nearest = dx * dx + dy * dy;
                found = ids[b];
            }
        }
        if (stopsShown()) {
            // A 48 dp target at least around the badge; a finger on the badge itself is on it however near a knob.
            float around = Math.max(0, ui.dp(ADOPT_DP) - stopsBox.height()) / 2;
            float dx = Math.max(0, Math.max(stopsBox.left - x, x - stopsBox.right));
            float dy = Math.max(0, Math.max(stopsBox.top - y, y - stopsBox.bottom));
            if (dx <= around && dy <= around && dx * dx + dy * dy <= nearest) found = STOPS_ID;
        }
        return found;
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
     * reach, else the topmost polygon under the finger; -1 for none. A spoke's icon keeps its own touch; a mark faded
     * under a line of words, or cut out under the mascot, is left to them, as is a polygon there.
     */
    private int offerAt(float x, float y) {
        if (!offersOn() || outer <= 0 || marks.isEmpty() || onIcon(x, y, 0)) return -1;
        float glide = Motion.settle(glideStart, GLIDE_MS);
        float reach = ui.dp(MARK_TAP_DP);
        float nearest = reach * reach;
        int found = -1;
        List<Integer> order = topDown();
        for (int m : order) {
            for (int axis = 0; axis < NAMES.length; axis++) {
                float[] at = markPoint(m, axis, glide);
                if (at == null || underWords(at[0], at[1]) || onMascot(at[0], at[1])) continue;
                float dx = x - at[0];
                float dy = y - at[1];
                // Where two marks are as near, the one drawn on top.
                if (dx * dx + dy * dy < nearest || (found < 0 && dx * dx + dy * dy <= nearest)) {
                    nearest = dx * dx + dy * dy;
                    found = m;
                }
            }
        }
        if (found >= 0 || underWords(x, y) || onMascot(x, y)) return found;
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

    /** A visible, selectable mark on spoke {@code axis}, excluding text/mascot masks (for interaction tests). */
    float[] markAt(int m, int axis) {
        if (!backdrop() || m < 0 || m >= marks.size()) return null;
        float[] at = markPoint(m, axis, Motion.settle(glideStart, GLIDE_MS));
        return at == null || underWords(at[0], at[1]) || onMascot(at[0], at[1]) ? null : at;
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
     * button adopts the learned minimums or undoes that; a tap on a marked offer (on no knob or button) opens its
     * ticket; a tap on the adaptive minimum's toggle turns it on or off and holding it asks to reset what it learned; a
     * tap on the max stops badge steps it and a drag across it steps it either way; any other tap on the circle, a
     * knob's included, goes back to the newest offer while an older one is chosen, else is the chart's own click. In
     * a header, the chart is one button.
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
                adoptPressed = on == ADOPT_ID;
                scorePressed = on == SCORE_ID;
                adaptivePressed = on == ADAPTIVE_ID;
                adaptiveHeld = false;
                stopsPressed = on == STOPS_ID;
                stopsDragging = false;
                scaleDragging = false;
                // Held, the adaptive minimum's toggle asks to reset what it learned.
                if (adaptivePressed) postDelayed(holdAdaptive, ViewConfiguration.getLongPressTimeout());
                if (on >= 0 && on < NAMES.length) {
                    held = on;
                    grabDistance = knobFraction(on, Motion.settle(glideStart, GLIDE_MS)) * skyRadius;
                    grabOffset = grabDistance - along(on, x, y);
                    // A vertical ScrollView would intercept this upright spoke before its first MOVE reached us.
                    // Reserve only a touch starting on this knob; an off-axis motion releases the page again.
                    if (upright(on)) keepTouch(true);
                }
                // The knobs and the buttons first; else the offer under the finger, picked out lightly.
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
                    if (scorePressed && Math.abs(x - downX) >= Math.abs(y - downY)) {
                        scaleDragging = true;
                        scaleValue = shownTradeoffPercent();
                        keepTouch(true);
                    } else if (scorePressed) {
                        scorePressed = false;
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
                    if (held == AreaScore.HOTSPOT) {
                        // There is no production measurement reader. Keep saved rules, but do not offer a
                        // draggable control that can quietly make all compensated scores unreadable.
                        keepTouch(false);
                        held = -1;
                        invalidate();
                    } else if (held >= 0 && alongSpoke(held, x - downX, y - downY)) {
                        takeKnob();
                    } else if (held >= 0) {
                        // Across the spoke: not a drag, and no longer a tap.
                        if (upright(held)) keepTouch(false);
                        held = -1;
                        invalidate();
                    }
                    if (pressedAt >= 0) {
                        // A drag, not a tap: the offer is let go.
                        pressedAt = -1;
                        invalidate();
                    }
                }
                if (adoptPressed && target(x, y) != ADOPT_ID) {
                    adoptPressed = false;
                    invalidate();
                }
                if (scorePressed && !scaleDragging && target(x, y) != SCORE_ID) {
                    scorePressed = false;
                    invalidate();
                }
                if (adaptivePressed && target(x, y) != ADAPTIVE_ID) {
                    adaptivePressed = false;
                    removeCallbacks(holdAdaptive);
                    invalidate();
                }
                if (dragging) moveKnob(along(held, x, y) + grabOffset);
                if (stopsDragging) moveStops(x - downX);
                if (scaleDragging) moveMinimumScale(x - downX);
                return true;
            case MotionEvent.ACTION_UP: {
                boolean button = adoptPressed;
                boolean toggle = scorePressed && tapping && !scaleDragging;
                boolean adaptive = adaptivePressed && !adaptiveHeld;
                // A hold that asked to reset is done: the finger lifting is no tap.
                boolean asked = adaptiveHeld;
                boolean stops = stopsPressed && tapping;
                boolean tap = tapping;
                boolean hotspot = tap && held == AreaScore.HOTSPOT;
                long tappedAt = tap ? pressedAt : -1;
                endTouch(true);
                if (hotspot) {
                    explainHotspot();
                } else if (button) {
                    playSoundEffect(SoundEffectConstants.CLICK);
                    pressAdopt();
                } else if (toggle) {
                    playSoundEffect(SoundEffectConstants.CLICK);
                    pressScore();
                } else if (adaptive) {
                    playSoundEffect(SoundEffectConstants.CLICK);
                    pressAdaptive();
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
        removeCallbacks(holdAdaptive);
        if (scaleDragging) {
            scaleDragging = false;
            keepTouch(false);
            if (lifted) setMinimumScale(scaleValue);
            invalidate();
        }
        if (adoptPressed || scorePressed || adaptivePressed || stopsPressed || pressed || pressedAt >= 0) {
            adoptPressed = false;
            scorePressed = false;
            adaptivePressed = false;
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
        if (upright(held)) keepTouch(false);
        held = -1;
    }

    /** Whether a finger's move ({@code dx}, {@code dy}) runs along spoke {@code axis}, one way or the other. */
    private static boolean alongSpoke(int axis, float dx, float dy) {
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
        if (held == AreaScore.HOTSPOT) return;
        dragging = true;
        if (outer <= 0) {
            ringCents = FIRST_RING_CENTS;
            Arrays.fill(unit, 1);
            outer = ringCents * 3.0;
            rings = 3;
        }
        dragMiles = exampleMiles;
        dragMinutes = exampleMinutes;
        dragStops = exampleStops;
        dragFacts = exampleFacts;
        dragItems = hasItems(exampleFacts) ? exampleFacts.items : ITEM_EDIT_UNITS;
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
        // By area, the minimums' polygon sets out from where the held knob left its spoke.
        float areaAt = Float.NaN;
        if (byArea && (axis != AreaScore.ITEM || hasItems(exampleFacts))) {
            double ask = value > 0 ? askCents(axis, value) / unit[axis] : 0;
            if (adaptiveOn && !Double.isNaN(learned[axis])) ask = Math.max(ask, learned[axis]);
            areaAt = (float) Math.min(1, ask / outer);
        }
        for (int i = 0; i < NAMES.length; i++) {
            setFrom[i] = setTo[i] = setFrom[i] + (setTo[i] - setFrom[i]) * glide;
            learnedFrom[i] = learnedTo[i] = learnedFrom[i] + (learnedTo[i] - learnedFrom[i]) * glide;
            areaFrom[i] = areaTo[i] = areaFrom[i] + (areaTo[i] - areaFrom[i]) * glide;
        }
        for (int m = 0; m < markTo.size(); m++) {
            float[] shown = markShown(m, glide);
            markFrom.set(m, shown);
            markTo.set(m, shown.clone());
        }
        if (save) setFrom[axis] = setTo[axis] = value > 0 ? at : 0;
        if (save && !Float.isNaN(areaAt)) areaFrom[axis] = areaTo[axis] = areaAt;
        glideStart = SystemClock.uptimeMillis();
        dragging = false;
        held = -1;
        keepTouch(false);
        takeScale();
        inUnits();
        adoptStale = true;
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
        if (!dragging || held < 0 || (held == AreaScore.ITEM && !hasItems(exampleFacts))) return;
        float at = heldFraction();
        drawnSet[held] = dragValue > 0 ? askCents(held, dragValue) / unit[held] : Double.NaN;
        drawnFrom[held] = at;
        drawnTo[held] = at;
    }

    /**
     * The knobs at rest, over the adaptive sparkles (which may sit on the same spot): a set minimum's round star made a
     * little larger, in a white rim and a faint ring of its own color, so it reads as a thumb to take hold of rather
     * than a mark; a spoke with none, a small hollow knob in the same faint ring just outside the middle. Where a
     * knob sits on its spoke's sparkle (as after adopting), the sparkle's outline shows over it.
     */
    private void drawKnobs(Canvas canvas, float cx, float cy, float radius, float glide) {
        float breathe = 1 + 0.14f * Motion.wave(3.2f, 0);
        for (int i = 0; i < NAMES.length; i++) {
            if (!knobShown(i) || i == readoutAxis()) continue;
            int color = i == AreaScore.HOTSPOT ? ui.inkSecondary : setColor();
            float fraction = knobFraction(i, glide);
            float[] at = point(cx, cy, radius, i, fraction);
            line.setPathEffect(null);
            line.setColor((color & 0x00FFFFFF) | 0x50000000);
            line.setStrokeWidth(ui.dp(1.2f));
            canvas.drawCircle(at[0], at[1], ui.dp(10.5f) * detail, line);
            if (Double.isNaN(set[i])) {
                line.setColor((color & 0x00FFFFFF) | 0xC0000000);
                line.setStrokeWidth(ui.dp(1.8f));
                canvas.drawCircle(at[0], at[1], ui.dp(5) * detail, line);
                if (i != AreaScore.HOTSPOT && beckoning()) {
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
            if (Double.isNaN(learned[i])) continue;
            float sparkle = learnedFrom[i] + (learnedTo[i] - learnedFrom[i]) * glide;
            if (Math.abs(sparkle - fraction) * radius > ui.dp(2) * detail) continue;
            float[] spot = point(cx, cy, radius, i, sparkle);
            line.setColor(learnedColor());
            line.setStrokeWidth(ui.dp(1.2f));
            drawSparkle(canvas, spot[0], spot[1], ui.dp(6.5f) * breathe * detail, line);
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

    /** "$7.50", "$1.55/mi", "$0.42/min", "$2.25/stop", or "off". */
    static String readout(int axis, int rate) {
        if (rate <= 0) return "off";
        if (axis == AreaScore.HOTSPOT) return String.format(java.util.Locale.US, "%.2f/mi · ≤%s mi",
                rate / 100.0, distanceText(100.0 / rate));
        return DecisionLog.money(rate) + UNITS[axis];
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
        if (axis == AreaScore.HOTSPOT) return "Hotspot unavailable · tap for details";
        if (axis == AreaScore.ITEM) {
            String observed = itemsLabel(selectedFacts());
            if (observed.isEmpty()) observed = "not applicable";
            String saved = "Min " + readout(axis, dragging && axis == held ? dragValue : setRates[axis]);
            String learned = shownRules.best.hasPerItem() ? " · Learned " + shownRules.best.perItemLabel()
                    + (adaptiveOn ? "" : " (off)") : "";
            return saved + learned + " · " + observed;
        }
        String name = AXIS_LABELS[axis];
        return name + " · " + readout(axis, dragging && axis == held ? dragValue : setRates[axis]);
    }

    /** Keep the selected item state inside the sky, including large fonts or unusually long numeric values. */
    private String fittedReadout() {
        pillText.setTextSize(Math.min(ui.sp(14), ui.dp(18)));
        return Ui.fit(pillText, focusedReadout(), Math.max(0, getWidth() - ui.dp(30)), 0.85f).toString();
    }

    /**
     * Where the held knob's readout stands, into the pill box: above the knob, clear of the finger, where that is
     * inside the page and clear of the words and the mascot; else beside it (towards the page's middle, then away),
     * else below it; else above it, kept inside the page.
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
            if (!moved && !underWords(pillBox) && !onMascot(pillBox)) return;
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

    // ---- The adopt button: the learned minimums made the set ones, then Undo for a few seconds. ----

    /** Shown as the sky while an adaptive minimum asks more than its set one, or while Undo is offered. */
    boolean adoptShown() {
        return knobsOn() && (adoptable || undoValues != null) && placeButtons() && adoptPlaced;
    }

    /** The score by area toggle: shown as the sky whenever some minimum is on. */
    boolean scoreToggleShown() {
        return knobsOn() && anyMinimumOn() && placeButtons() && togglePlaced;
    }

    /** Some spoke has a minimum under the rules as shown: something to score. */
    private boolean anyMinimumOn() {
        for (boolean on : AreaScore.active(shownRules)) if (on) return true;
        return false;
    }

    /** The adaptive minimum's toggle: shown as the sky, always (the adaptive minimum is a rule of its own). */
    boolean adaptiveShown() {
        return knobsOn() && placeButtons() && adaptivePlaced;
    }

    /** The max stops badge: shown as the sky while the per-stop spoke's icon is. */
    boolean stopsShown() {
        return knobsOn() && placeStops();
    }

    /** Where the adaptive minimum's toggle stands, in this view's pixels (for tests); null when it is not shown. */
    RectF adaptiveBox() {
        return adaptiveShown() ? new RectF(adaptiveBox) : null;
    }

    /** Where the max stops badge stands, in this view's pixels (for tests); null when it is not shown. */
    RectF stopsBox() {
        return stopsShown() ? new RectF(stopsBox) : null;
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
     * Where the max stops badge stands, into its box: beside the per-stop spoke's icon, towards the middle (else
     * outwards, below or above it), inside the page and clear of the words, the mascot, the other icons, the rings'
     * dollars and the knobs' reach; failing all of those, towards the middle all the same. While a finger drags it, it
     * stays where it was. False while that icon is hidden (a line of words over both its places).
     */
    private boolean placeStops() {
        if (!stopsDragging) stopsIcon.setEmpty();
        placingStops = true;
        try {
            return placeStopsFromSpoke();
        } finally {
            placingStops = false;
        }
    }

    private boolean placeStopsFromSpoke() {
        if (!backdrop()) return false;
        String words = stopsWords();
        float height = Math.max(ui.dp(26), Ui.lineHeight(badgeText) + ui.dp(8));
        float width = Math.max(height + ui.dp(6), badgeText.measureText(words) + ui.dp(18));
        if (stopsDragging && stopsBox.width() > 0) {
            stopsBox.right = stopsBox.left + width;
            return true;
        }
        if (!skyIcon(3, skyX, skyY, skyRadius, iconBox)) return false;
        float iconLeft = iconBox.left;
        float iconRight = iconBox.right;
        float iconTop = iconBox.top;
        float iconBottom = iconBox.bottom;
        float middle = iconBox.centerY();
        RectF anchor = new RectF(iconBox);
        float gap = ui.dp(8);
        // The per-stop spoke points down and to the left: towards the middle is to the right.
        float[][] places = {
                {iconRight + gap, middle - height / 2},
                {iconLeft - gap - width, middle - height / 2},
                {(iconLeft + iconRight) / 2 - width / 2, iconBottom + gap},
                {(iconLeft + iconRight) / 2 - width / 2, iconTop - gap - height}};
        for (float[] place : places) {
            stopsBox.set(place[0], place[1], place[0] + width, place[1] + height);
            if (badgeFits() && pairStopsIcon(anchor)) return true;
        }
        for (int step = 1; step <= 20; step++) {
            for (int side : new int[] {-1, 1}) {
                for (int across = 0; across <= 12; across++) {
                    float left = iconRight + gap + across * ui.dp(18);
                    float top = middle - height / 2 + side * step * ui.dp(12);
                    stopsBox.set(left, top, left + width, top + height);
                    if (badgeFits() && pairStopsIcon(anchor)) return true;
                }
            }
        }
        return false;
    }

    /** A distant clear badge position carries the pin with it, as one visual control; at most four local looks. */
    private boolean pairStopsIcon(RectF original) {
        if (Math.hypot(stopsBox.centerX() - original.centerX(), stopsBox.centerY() - original.centerY())
                <= ui.dp(80)) return true;
        float size = backdropIcon();
        float gap = ui.dp(8);
        float[][] places = {
                {stopsBox.left - gap - size, stopsBox.centerY() - size / 2},
                {stopsBox.right + gap, stopsBox.centerY() - size / 2},
                {stopsBox.centerX() - size / 2, stopsBox.top - gap - size},
                {stopsBox.centerX() - size / 2, stopsBox.bottom + gap}};
        RectF candidate = new RectF();
        RectF other = new RectF();
        for (float[] place : places) {
            candidate.set(place[0], place[1], place[0] + size, place[1] + size);
            if (candidate.left < ui.dp(4) || candidate.right > getWidth() - ui.dp(4)
                    || candidate.top < ui.dp(4) || candidate.bottom > getHeight() - ui.dp(4)
                    || underWords(candidate) || onMascot(candidate) || !iconClearOfKnobs(candidate)) continue;
            boolean clear = true;
            for (int axis = 0; axis < NAMES.length; axis++) {
                if (axis != AreaScore.STOP && skyIcon(axis, skyX, skyY, skyRadius, other)
                        && RectF.intersects(candidate, other)) clear = false;
            }
            for (RectF label : levelBoxes) if (RectF.intersects(candidate, label)) clear = false;
            if (clear) {
                stopsIcon.set(candidate);
                return true;
            }
        }
        return false;
    }

    private boolean badgeFits() {
        float margin = ui.dp(4);
        if (stopsBox.left < margin || stopsBox.top < margin || stopsBox.right > getWidth() - margin
                || stopsBox.bottom > getHeight() - margin) {
            return false;
        }
        if (underWords(stopsBox) || onMascot(stopsBox)) return false;
        RectF icon = new RectF();
        for (int i = 0; i < ICONS.length; i++) {
            if (skyIcon(i, skyX, skyY, skyRadius, icon) && RectF.intersects(icon, stopsBox)) return false;
        }
        for (RectF label : levelBoxes) if (RectF.intersects(label, stopsBox)) return false;
        float clear = ui.dp(KNOB_REACH_DP);
        float glide = Motion.settle(glideStart, GLIDE_MS);
        for (int i = 0; i < NAMES.length; i++) {
            float[] at = point(skyX, skyY, skyRadius, i, knobFraction(i, glide));
            float dx = Math.max(0, Math.max(stopsBox.left - at[0], at[0] - stopsBox.right));
            float dy = Math.max(0, Math.max(stopsBox.top - at[1], at[1] - stopsBox.bottom));
            if (dx * dx + dy * dy < clear * clear) return false;
        }
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

    /**
     * The adaptive minimum's toggle, a round button like the others with a sparkle: on, the sparkle is filled in the
     * adaptive minimums' color and the button ringed in it; off, an outline.
     */
    private void drawAdaptive(Canvas canvas) {
        float x = adaptiveBox.centerX();
        float y = adaptiveBox.centerY();
        float radius = adaptiveBox.width() / 2;
        fill.setColor(adaptivePressed ? (ui.dark ? 0xFF2E2E2C : 0xFFE4E3DE) : ui.surface);
        canvas.drawCircle(x, y, radius, fill);
        int learned = ui.dark ? NIGHT_LEARNED : ui.learned;
        line.setPathEffect(null);
        if (adaptiveOn) {
            line.setColor(learned);
            line.setStrokeWidth(ui.dp(2));
        } else {
            line.setColor(ui.dark ? 0x40FFFFFF : 0x330B0B0B);
            line.setStrokeWidth(Math.max(1, ui.dp(1)));
        }
        canvas.drawCircle(x, y, radius - line.getStrokeWidth() / 2, line);
        // Keep the existing 48 dp control and gestures; name the purple shape without adding a legend row.
        adaptiveText.setColor(adaptiveOn ? learned : ui.inkSecondary);
        adaptiveText.setTextSize(Math.min(ui.sp(10), ui.dp(11)));
        float labelWidth = adaptiveText.measureText("Learned");
        if (labelWidth > radius * 1.65f) adaptiveText.setTextSize(adaptiveText.getTextSize()
                * radius * 1.65f / labelWidth);
        canvas.drawText("Learned", x, y - ui.dp(3), adaptiveText);
        String state = adaptiveOn ? "On" : "Off";
        float stateWidth = adaptiveText.measureText(state);
        float iconX = x - stateWidth / 2 - ui.dp(4);
        if (adaptiveOn) {
            fill.setColor(learned);
            drawSparkle(canvas, iconX, y + ui.dp(7), ui.dp(3), fill);
        } else {
            line.setColor(ui.inkSecondary);
            line.setStrokeWidth(ui.dp(1));
            drawSparkle(canvas, iconX, y + ui.dp(7), ui.dp(3), line);
        }
        canvas.drawText(state, x + ui.dp(4), y + ui.dp(11), adaptiveText);
    }

    /** The toggle: the adaptive minimum on or off, saved at once (what it learned is kept), and said. */
    private void pressAdaptive() {
        if (changes == null) return;
        boolean on = !adaptiveOn;
        changes.setAdaptive(on);
        say(on ? ADAPTIVE_SAID + " on. It learns from confirmed manual acceptances and declines. Automatic accepts do not raise it."
                : ADAPTIVE_SAID + " off. What it learned is kept.");
        dropGoneFocus();
        invalidate();
        nodesChanged();
    }

    /** Undo is offered (for tests). */
    boolean offeringUndo() {
        return undoValues != null;
    }

    /** Where the button stands, in this view's pixels (for tests). */
    RectF adoptBox() {
        return adoptShown() ? new RectF(adoptBox) : null;
    }

    /** Where the score by area toggle stands, in this view's pixels (for tests); null when it is not shown. */
    RectF scoreToggleBox() {
        return scoreToggleShown() ? new RectF(scoreBox) : null;
    }

    /** The chart is drawn scored by area (for tests). */
    boolean byArea() {
        return byArea;
    }

    /** Recent offer {@code m}'s polygon (newest first) where it is heading, in this view's pixels (for tests). */
    List<float[]> offerShape(int m) {
        return offerPolygon(skyX, skyY, skyRadius, m, 1);
    }

    /** By area, the minimums' polygon where it is heading, in this view's pixels (for tests). */
    List<float[]> minimumShape() {
        return minimumPolygon(skyX, skyY, skyRadius, 1);
    }

    /**
     * Where the round buttons stand: in a row, left to right the score by area toggle, the adaptive minimum's toggle
     * (straight below the circle's middle) and the adopt button's place (kept free even while that button is not shown,
     * so the others never move when it comes or goes), just outside where the shapes cross there (else above the
     * middle), clear of the knobs, the icons, the max stops badge, the rings' dollars, the words and the mascot, and
     * within the spokes' height, which the stage keeps inside the page and below the counts. Where no such row fits,
     * each finds a place of its own. Worked out from where the shapes are heading, so they do not drift while they
     * glide, and not at all while a knob is held; while Undo is offered they stay where they were tapped, unless the
     * circle itself moves.
     */
    private boolean placeButtons() {
        if (dragging || scaleDragging || (!adoptStale && !layoutMoved)) return buttonsPlaced;
        if (undoValues != null && buttonsPlaced && !layoutMoved) return true;
        adoptStale = false;
        layoutMoved = false;
        float half = ui.dp(ADOPT_DP) / 2f;
        float apart = ui.dp(BUTTONS_APART_DP);
        float band = backdropHalfHeight(skyRadius) - half;
        placeLevelLabels(skyX, skyY, skyRadius, 1);
        stopsForButtons = placeStops();
        RectF[] row = {scoreBox, adaptiveBox, adoptBox};
        boolean placed = false;
        for (int pass = 0; pass < 2 && !placed; pass++) {
            for (int side = 1; side >= -1 && !placed; side -= 2) {
                float from = half + ui.dp(KNOB_REST_DP);
                // First just outside the shapes; failing that, over them.
                if (pass == 0) {
                    from = Math.max(from, shapesReach(side, new float[] {skyX - apart - half, skyX - apart,
                            skyX - apart / 2, skyX, skyX + apart / 2, skyX + apart, skyX + apart + half})
                            + ui.dp(10) + half);
                }
                for (float d = from; d <= band; d += ui.dp(2)) {
                    float y = skyY + side * d;
                    for (int b = 0; b < row.length; b++) {
                        float x = skyX + (b - 1) * apart;
                        row[b].set(x - half, y - half, x + half, y + half);
                    }
                    if (buttonFits(scoreBox) && buttonFits(adaptiveBox, scoreBox)
                            && buttonFits(adoptBox, adaptiveBox)) {
                        placed = true;
                        break;
                    }
                }
            }
        }
        togglePlaced = adaptivePlaced = adoptPlaced = placed;
        if (!placed) {
            togglePlaced = placeAlone(scoreBox, half, band);
            adaptivePlaced = placeAlone(adaptiveBox, half, band, togglePlaced ? scoreBox : null);
            adoptPlaced = placeAlone(adoptBox, half, band, togglePlaced ? scoreBox : null,
                    adaptivePlaced ? adaptiveBox : null);
        }
        return buttonsPlaced = togglePlaced || adaptivePlaced || adoptPlaced;
    }

    /** One button in a place of its own, below the middle (else above it), clear of {@code others}. */
    private boolean placeAlone(RectF box, float half, float band, RectF... others) {
        for (int pass = 0; pass < 2; pass++) {
            for (int side = 1; side >= -1; side -= 2) {
                float from = half + ui.dp(KNOB_REST_DP);
                if (pass == 0) from = Math.max(from, shapesReach(side, new float[] {skyX}) + ui.dp(10) + half);
                for (float d = from; d <= band; d += ui.dp(2)) {
                    for (int column : new int[] {0, -1, 1, -2, 2}) {
                        float x = skyX + column * ui.dp(BUTTONS_APART_DP);
                        box.set(x - half, skyY + side * d - half, x + half, skyY + side * d + half);
                        if (buttonFits(box, others)) return true;
                    }
                }
            }
        }
        // Six spokes can occupy both traditional rows in a short split. Search the remaining open sky,
        // retaining the same 48 dp target and every collision guard instead of hiding an existing control.
        RectF best = null;
        double nearest = Double.MAX_VALUE;
        float step = ui.dp(8);
        for (float y = ui.dp(4) + half; y <= getHeight() - ui.dp(4) - half; y += step) {
            for (float x = ui.dp(4) + half; x <= getWidth() - ui.dp(4) - half; x += step) {
                box.set(x - half, y - half, x + half, y + half);
                if (!buttonFits(box, others)) continue;
                double distance = Math.hypot(x - skyX, y - (skyY + band));
                if (distance < nearest) {
                    nearest = distance;
                    best = new RectF(box);
                }
            }
        }
        if (best != null) { box.set(best); return true; }
        return false;
    }

    /**
     * How far below ({@code side} 1) or above (-1) the middle the shapes' edges cross the upright lines at {@code xs},
     * as they are heading: the set and adaptive shapes, and by area the minimums' polygon too.
     */
    private float shapesReach(int side, float[] xs) {
        List<List<float[]>> shapes = new ArrayList<>();
        if (byArea) {
            float[][] axes = new float[NAMES.length][];
            for (int i = 0; i < NAMES.length; i++) {
                if (!Double.isNaN(areaMin[i])) axes[i] = point(skyX, skyY, skyRadius, i, areaTo[i]);
            }
            shapes.add(orderedPolygon(axes, skyX, skyY));
        }
        // By area the set shape lies within the minimums' area; the adaptive one may not (while it is off).
        double[][] values = {set, learned};
        float[][] to = {setTo, learnedTo};
        for (int shape = 0; shape < 2; shape++) {
            boolean any = false;
            for (double value : values[shape]) any |= !Double.isNaN(value);
            if (!any) continue;
            float[][] axes = new float[NAMES.length][];
            for (int i = 0; i < NAMES.length; i++) {
                if (Double.isNaN(values[shape][i]) && (byArea || i == AreaScore.HOTSPOT || i == AreaScore.ITEM)) continue;
                axes[i] = point(skyX, skyY, skyRadius, i, Double.isNaN(values[shape][i]) ? 0 : to[shape][i]);
            }
            shapes.add(orderedPolygon(axes, skyX, skyY));
        }
        float reach = 0;
        for (List<float[]> shape : shapes) {
            for (float x : xs) reach = Math.max(reach, uprightReach(shape, x, skyY, side));
        }
        return reach;
    }

    /**
     * Whether a button at {@code box} fits: in the page, clear of words, the mascot, labels, icons, the max stops
     * badge, knobs and {@code others}.
     */
    private boolean buttonFits(RectF box, RectF... others) {
        float margin = ui.dp(4);
        if (box.left < margin || box.top < margin || box.right > getWidth() - margin
                || box.bottom > getHeight() - margin) {
            return false;
        }
        RectF around = new RectF(box);
        around.inset(-ui.dp(4), -ui.dp(4));
        if (underWords(around) || onMascot(box)) return false;
        for (RectF other : others) if (other != null && RectF.intersects(other, around)) return false;
        if (stopsForButtons && RectF.intersects(stopsBox, around)) return false;
        for (RectF label : levelBoxes) if (RectF.intersects(label, around)) return false;
        float clear = ui.dp(KNOB_REACH_DP) + box.width() / 2;
        for (int i = 0; i < NAMES.length; i++) {
            if (skyIcon(i, skyX, skyY, skyRadius, iconBox) && RectF.intersects(iconBox, around)) return false;
            if (!knobShown(i)) continue;
            float[] at = point(skyX, skyY, skyRadius, i, Double.isNaN(set[i]) ? restFraction() : setTo[i]);
            if (Math.hypot(at[0] - box.centerX(), at[1] - box.centerY()) < clear) return false;
        }
        return true;
    }

    /** A round button like the header's, with the adopt (or undo) icon. */
    private void drawAdopt(Canvas canvas) {
        float x = adoptBox.centerX();
        float y = adoptBox.centerY();
        float radius = adoptBox.width() / 2;
        fill.setColor(adoptPressed ? (ui.dark ? 0xFF2E2E2C : 0xFFE4E3DE) : ui.surface);
        canvas.drawCircle(x, y, radius, fill);
        line.setPathEffect(null);
        line.setColor(ui.dark ? 0x40FFFFFF : 0x330B0B0B);
        line.setStrokeWidth(Math.max(1, ui.dp(1)));
        canvas.drawCircle(x, y, radius - line.getStrokeWidth() / 2, line);
        // The adopt icon's dashed shape in the learned minimums' color, its solid one in the set minimums'.
        adoptGlyph.setAccents(learnedColor(), setColor());
        Glyph glyph = undoValues != null ? undoGlyph : adoptGlyph;
        int size = glyph.getBounds().width();
        int left = Math.round(x - size / 2f);
        int top = Math.round(y - size / 2f);
        glyph.setBounds(left, top, left + size, top + size);
        glyph.draw(canvas);
    }

    /**
     * The compact offers-versus-profit slider handle. A horizontal drag changes the tradeoff preference; a tap still
     * changes strict/area scoring. The ring continues to show area mode without making the raw minimums percentage the
     * user's primary control.
     */
    private void drawScoreToggle(Canvas canvas) {
        float x = scoreBox.centerX();
        float y = scoreBox.centerY();
        float radius = scoreBox.width() / 2;
        fill.setColor(scorePressed ? (ui.dark ? 0xFF2E2E2C : 0xFFE4E3DE) : ui.surface);
        canvas.drawCircle(x, y, radius, fill);
        line.setPathEffect(null);
        if (byArea) {
            line.setColor(setColor());
            line.setStrokeWidth(ui.dp(2));
        } else {
            line.setColor(ui.dark ? 0x40FFFFFF : 0x330B0B0B);
            line.setStrokeWidth(Math.max(1, ui.dp(1)));
        }
        canvas.drawCircle(x, y, radius - line.getStrokeWidth() / 2, line);
        int color = byArea ? setColor() : ui.inkSecondary;
        int tradeoff = shownTradeoffPercent();
        scaleText.setTextSize(Math.min(ui.sp(9), ui.dp(10)));
        scaleText.setColor(color);
        canvas.drawText(byArea ? "Area" : "Each", x, y - ui.dp(6), scaleText);
        scaleText.setTextSize(Math.min(ui.sp(10), ui.dp(11)));
        scaleText.setColor(color);
        canvas.drawText(tradeoff < 45 ? "Offers" : tradeoff > 55 ? "Profit" : "Balance",
                x, y + ui.dp(12), scaleText);
    }

    /** The actual effective rule boundary; while dragging, preview only the deterministic sparse-history mapping. */
    private int shownScalePercent() {
        return scaleDragging ? EarningsPreferences.fallbackScale(scaleValue) : shownRules.minimumScalePercent;
    }

    /** The user-facing slider preference; an older install starts where its existing scale most closely maps. */
    private int shownTradeoffPercent() {
        return scaleDragging ? scaleValue
                : EarningsPreferences.tradeoff(getContext(), shownRules.minimumScalePercent);
    }

    int minimumScalePercent() { return shownRules.minimumScalePercent; }

    private void moveMinimumScale(float dx) {
        int start = EarningsPreferences.tradeoff(getContext(), shownRules.minimumScalePercent);
        int percent = Math.max(0, Math.min(100,
                start + Math.round(dx / Math.max(1, ui.dp(MINIMUM_SCALE_STEP_DP)))));
        if (percent != scaleValue) {
            scaleValue = percent;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        invalidate();
    }

    private boolean setMinimumScale(int percent) {
        percent = Math.max(0, Math.min(100, percent));
        if (changes == null || percent == shownTradeoffPercent()) return false;
        changes.setTradeoffPercent(percent);
        int effective = FilterStore.load(getContext()).minimumScalePercent;
        say("Offers versus profit " + percent + "%. "
                + (percent < 50 ? "More offers and lower minimums." : percent > 50
                ? "More profit and higher minimums." : "Balanced.")
                + " Effective minimums " + effective + "%. Saved and learned values stay unchanged.");
        invalidate();
        nodesChanged();
        return true;
    }

    /** The toggle: score by area on or off, saved at once; screen readers hear which, and what it means. */
    private void pressScore() {
        if (changes == null) return;
        boolean on = !byArea;
        changes.setScoreByArea(on);
        say(on ? SCORE_SAID + " on. An offer passes at a score of " + shownRules.minimumScalePercent + "% or more."
                + (shownRules.maxStops > 0 ? " Max stops still declines." : "")
                : SCORE_SAID + " off. An offer must meet every minimum.");
        dropGoneFocus();
        invalidate();
        nodesChanged();
    }

    /**
     * The button: adopt the learned minimums (Undo is offered for a while), or undo that. Screen readers hear what to do
     * first, then the minimums as saved; adopting also says that add-ons are now held to them, which the adaptive
     * minimum never did.
     */
    private void pressAdopt() {
        if (changes == null) return;
        // The button stays where it is, a screen reader's focus on it too, while it turns into Undo and back.
        pressingAdopt = true;
        try {
            if (undoValues != null) {
                int[] previous = undoValues;
                stopUndo();
                changes.restore(previous);
                say("Set minimums put back. " + spoken(savedRates) + ".");
            } else {
                int[] previous = changes.adoptLearned();
                if (previous != null) {
                    undoValues = previous;
                    System.arraycopy(savedRates, 0, adoptedRates, 0, savedRates.length);
                    timeUndo();
                    say("Learned minimums set. Double-tap to undo. " + spoken(savedRates)
                            + ". Add-ons are held to these too.");
                }
            }
        } finally {
            pressingAdopt = false;
        }
        dropGoneFocus();
        invalidate();
        nodesChanged();
    }

    /**
     * How long Undo is offered once nobody is on it: Android's accessibility timeout for a control with an icon and
     * words (API 29 on, where the user may ask for longer); before that, with a screen reader exploring, until
     * something else ends it (-1), as Android's own undo bars do; else {@link #UNDO_MS}.
     */
    private long undoMillis() {
        AccessibilityManager manager = getContext().getSystemService(AccessibilityManager.class);
        if (Build.VERSION.SDK_INT >= 29) {
            if (manager == null) return UNDO_MS;
            return manager.getRecommendedTimeoutMillis((int) UNDO_MS, AccessibilityManager.FLAG_CONTENT_ICONS
                    | AccessibilityManager.FLAG_CONTENT_CONTROLS | AccessibilityManager.FLAG_CONTENT_TEXT);
        }
        return exploring() ? -1 : UNDO_MS;
    }

    /** Starts Undo's time over; it does not run while a screen reader's focus is on the button. */
    private void timeUndo() {
        removeCallbacks(undoEnds);
        if (undoValues == null || focusedNode == ADOPT_ID) return;
        long millis = undoMillis();
        if (millis >= 0) postDelayed(undoEnds, millis);
    }

    private void stopUndo() {
        if (undoValues == null) return;
        removeCallbacks(undoEnds);
        undoValues = null;
        adoptStale = true;
        dropGoneFocus();
    }

    /** "Pay $14.21, per mile $2.37, per minute $0.60, per stop off" */
    private static String spoken(int[] rates) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < NAMES.length; i++) {
            String name = i == 0 ? NAMES[i] : NAMES[i].toLowerCase(java.util.Locale.US);
            parts.add(name + " " + (i == AreaScore.HOTSPOT ? readout(i, rates[i])
                    : rates[i] > 0 ? DecisionLog.money(rates[i]) : "off"));
        }
        return String.join(", ", parts);
    }

    /**
     * A screen reader's focus on a knob, a button or an offer that is no longer there (an offer that moved along, a
     * newer one taking its place) is let go, and it is told so.
     */
    private void dropGoneFocus() {
        if (focusedNode == NO_NODE || pressingAdopt) return;
        boolean gone = !shownNode(focusedNode) || (focusedNode >= OFFER_ID
                && markTimes.get(focusedNode - OFFER_ID) != focusedOfferAt);
        if (!gone) return;
        int was = focusedNode;
        focusedNode = NO_NODE;
        sendNodeEvent(was, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        layoutMoved = true;
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(undoEnds);
        removeCallbacks(holdAdaptive);
        undoValues = null;
        super.onDetachedFromWindow();
    }

    // ---- Screen readers: each knob an adjustable control, the button a button. ----

    /** What a screen reader's double-tap on the chart does while it is a button (a short window); null for none. */
    void setClickLabel(String label) {
        clickLabel = label;
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (clickLabel != null && isClickable()) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,
                    clickLabel));
        }
    }

    @SuppressWarnings("deprecation")
    private void say(String words) {
        lastSaid = words;
        announceForAccessibility(words);
    }

    /** "Minimum per mile, $1.50; adaptive $2.37, learned" */
    private String knobSaid(int axis) {
        int rate = setRates[axis];
        if (axis == AreaScore.ITEM) {
            OfferSnapshot offer = selectedFacts();
            String observed = itemState(offer);
            boolean hasLearned = shownRules.best.hasPerItem();
            if (offer != null && offer.itemCountApplicable && !hasItems(offer)
                    && (rate > 0 || adaptiveOn && hasLearned)) {
                observed += "; review needed for the active minimum";
            }
            String learned = hasLearned ? "; adaptive " + shownRules.best.perItemLabel()
                    + (adaptiveOn ? ", learned" : ", learned, not applied") : "; no learned item minimum yet";
            return KNOBS[axis] + ", " + readout(axis, rate) + ". " + observed
                    + ". Based on the observed total items, not unique products" + learned + ".";
        }
        if (axis == AreaScore.HOTSPOT) {
            int chosen = strongShape();
            Double miles = chosen < 0 ? null : markFacts.get(chosen).finalStopHotspotMiles;
            String threshold = rate > 0 ? String.format(java.util.Locale.US, "%.2f inverse miles, "
                    + "final stop at most %s miles from nearest hotspot", rate / 100.0, distanceText(100.0 / rate))
                    : "off";
            return KNOBS[axis] + ", " + threshold + ". " + hotspotSaid(miles)
                    + ". Automatic measurement unavailable: the app cannot read the final stop and actual hotspots."
                    + (rate > 0 ? " The saved rule is still active and needs review when this distance is unknown."
                            + " Tap for details or to turn the hotspot rule off." : " Tap for details.")
                    + " No adaptive minimum on this spoke.";
        }
        String said = KNOBS[axis] + ", " + (rate > 0 ? DecisionLog.money(rate) : "off");
        if (learnedText[axis] == null) return said;
        return said + "; adaptive " + learnedText[axis] + (adaptiveOn ? ", learned" : ", learned, not applied");
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
        if (axis == AreaScore.HOTSPOT) return false;
        if (rate == setRates[axis] || changes == null) return false;
        changes.setMinimum(axis, rate);
        say(knobSaid(axis));
        nodesChanged();
        return true;
    }

    private void explainHotspot() {
        say("Hotspot measurement unavailable. The app cannot read the final stop and actual hotspots.");
        if (changes != null) changes.explainHotspotUnavailable();
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

    /** Tells screen readers the knobs or the button changed. */
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
        event.setClassName(node == ADOPT_ID || node == AreaScore.HOTSPOT || node >= OFFER_ID ? Button.class.getName()
                : node == SCORE_ID || node == ADAPTIVE_ID ? Switch.class.getName() : SeekBar.class.getName());
        if (node == SCORE_ID) event.setChecked(byArea);
        if (node == ADAPTIVE_ID) event.setChecked(adaptiveOn);
        // An offer that went meanwhile (a focus being let go) has nothing left to say.
        if (node < OFFER_ID || node - OFFER_ID < marks.size()) event.setContentDescription(nodeSaid(node));
        event.setSource(this, node);
        event.setEnabled(true);
        getParent().requestSendAccessibilityEvent(this, event);
    }

    private String nodeSaid(int node) {
        if (node >= OFFER_ID) return offerSaid(node - OFFER_ID);
        if (node == SCORE_ID) return "Offers versus profit " + shownTradeoffPercent()
                + "%. Effective minimums " + shownRules.minimumScalePercent + "%. More offers and lower minimums "
                + "to the left; more profit and higher minimums to the right. " + SCORE_SAID
                + (byArea ? " on." : " off.") + " Drag sideways to adjust; tap to change scoring mode.";
        if (node == ADAPTIVE_ID) return ADAPTIVE_SAID;
        if (node == STOPS_ID) return stopsSaid(maxStops);
        return node == ADOPT_ID ? (undoValues != null ? "Undo" : ADOPT_SAID) : knobSaid(node);
    }

    /**
     * "Offer $9.75, 3.3 mi, 18 min, 2 stops, declined, scores 49% by area": what is known of it, and its outcome; where
     * that is not what the rules said, the rules' verdict too ("left to you, rules said decline").
     */
    private String offerSaid(int m) {
        OfferSnapshot offer = markFacts.get(m);
        List<String> parts = new ArrayList<>();
        parts.add("Offer " + DecisionLog.money(offer.payCents));
        if (offer.miles != null) parts.add(trim(offer.miles) + " mi");
        if (offer.minutes != null) parts.add(offer.minutes + " min");
        if (offer.stops != null) parts.add(offer.stops + (offer.stops == 1 ? " stop" : " stops"));
        if (knownHotspotDistance(offer.finalStopHotspotMiles) || shownRules.hotspotProximityHundredths > 0) {
            parts.add(hotspotSaid(offer.finalStopHotspotMiles));
        }
        OfferRule.Result result = markResults.get(m);
        DecisionLog.Outcome outcome = DecisionLog.outcome(markEntries.get(m));
        if (outcome.isVerdict(result)) {
            parts.add(result == OfferRule.Result.KEEP ? "passed"
                    : result == OfferRule.Result.DECLINE ? "declined" : "left for review");
        } else {
            parts.add(outcome.said.toLowerCase(java.util.Locale.US));
            parts.add("rules said " + (result == OfferRule.Result.KEEP ? "pass"
                    : result == OfferRule.Result.DECLINE ? "decline" : "review"));
        }
        parts.add(itemState(offer));
        if (markScores.get(m) >= 0) {
            parts.add("now scores " + markScores.get(m) + "% by area under the current minimums");
            int recorded = markEntries.get(m).scorePercent;
            if (recorded >= 0 && recorded != markScores.get(m)) parts.add("at decision scored " + recorded + "%");
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
            for (int axis = 0; axis < NAMES.length; axis++) {
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
            float grow = Math.max(0, ui.dp(ADOPT_DP) - stopsBox.height()) / 2;
            out.set(Math.round(stopsBox.left - grow), Math.round(stopsBox.top - grow),
                    Math.round(stopsBox.right + grow), Math.round(stopsBox.bottom + grow));
            return;
        }
        if (node == ADOPT_ID || node == SCORE_ID || node == ADAPTIVE_ID) {
            RectF box = node == ADOPT_ID ? adoptBox : node == SCORE_ID ? scoreBox : adaptiveBox;
            float reach = ui.dp(ADOPT_REACH_DP);
            out.set(Math.round(box.centerX() - reach), Math.round(box.centerY() - reach),
                    Math.round(box.centerX() + reach), Math.round(box.centerY() + reach));
            return;
        }
        float[] at = point(skyX, skyY, skyRadius, node, knobFraction(node, Motion.settle(glideStart, GLIDE_MS)));
        int reach = ui.dp(KNOB_REACH_DP);
        out.set(Math.round(at[0]) - reach, Math.round(at[1]) - reach, Math.round(at[0]) + reach,
                Math.round(at[1]) + reach);
    }

    /** A knob (0 to 5), a button, a toggle, the badge or a marked offer that is there now. */
    private boolean shownNode(int id) {
        if (id >= OFFER_ID) return offersOn() && outer > 0 && displayedOffer(id - OFFER_ID);
        if (id < 0 || id > STOPS_ID || !knobsOn()) return false;
        if (id == SCORE_ID) return scoreToggleShown();
        if (id == ADAPTIVE_ID) return adaptiveShown();
        if (id == STOPS_ID) return stopsShown();
        return id == ADOPT_ID ? adoptShown() : knobShown(id);
    }

    /** Which knob or button a screen reader is on (for tests); -1 for none. */
    int focusedNode() {
        return focusedNode == NO_NODE ? -1 : focusedNode;
    }

    /**
     * The knobs (ids 0 to 5, by spoke), the max stops badge, the adaptive minimum's toggle, the adopt button, the score
     * by area toggle and then the marked offers, newest first ({@link #OFFER_ID} on), for screen readers.
     */
    private final class Nodes extends AccessibilityNodeProvider {
        @SuppressWarnings("deprecation")
        @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
            View host = MinimumsStarView.this;
            if (id == HOST_VIEW_ID) {
                AccessibilityNodeInfo info = Build.VERSION.SDK_INT >= 30 ? new AccessibilityNodeInfo(host)
                        : AccessibilityNodeInfo.obtain(host);
                onInitializeAccessibilityNodeInfo(info);
                for (int axis : AreaScore.DRAW_ORDER) if (shownNode(axis)) info.addChild(host, axis);
                if (stopsShown()) info.addChild(host, STOPS_ID);
                if (adaptiveShown()) info.addChild(host, ADAPTIVE_ID);
                if (adoptShown()) info.addChild(host, ADOPT_ID);
                if (scoreToggleShown()) info.addChild(host, SCORE_ID);
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
            if (id == ADOPT_ID) {
                info.setClassName(Button.class.getName());
                info.setClickable(true);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
                return info;
            }
            if (id == SCORE_ID) {
                // One compact control: tap changes mode; adjustable actions change the offers/profit preference.
                info.setClassName(Switch.class.getName());
                info.setCheckable(true);
                info.setChecked(byArea);
                if (Build.VERSION.SDK_INT >= 30) info.setStateDescription(byArea ? "Area scoring" : "Strict scoring");
                int tradeoff = shownTradeoffPercent();
                info.setRangeInfo(Build.VERSION.SDK_INT >= 30
                        ? new AccessibilityNodeInfo.RangeInfo(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0,
                                100, tradeoff)
                        : AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0,
                                100, tradeoff));
                info.setClickable(true);
                String toggle = byArea ? "Use strict minimums" : "Use score by area";
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,
                        toggle));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(TOGGLE_ACTION, toggle));
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
                if (tradeoff < 100) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                if (tradeoff > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                return info;
            }
            if (id == ADAPTIVE_ID) {
                // "Adaptive minimum, switch, on", with Reset among its actions (a long press, by touch).
                String toggle = adaptiveOn ? "Turn adaptive minimum off" : "Turn adaptive minimum on";
                info.setClassName(Switch.class.getName());
                info.setCheckable(true);
                info.setChecked(adaptiveOn);
                if (Build.VERSION.SDK_INT >= 30) info.setStateDescription(adaptiveOn ? "On" : "Off");
                info.setClickable(true);
                info.setLongClickable(true);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,
                        toggle));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(TOGGLE_ACTION, toggle));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(RESET_ACTION, RESET_SAID));
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
            if (id == AreaScore.HOTSPOT) {
                info.setClassName(Button.class.getName());
                info.setClickable(true);
                if (Build.VERSION.SDK_INT >= 30) info.setStateDescription("Measurement unavailable");
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,
                        "Why hotspot measurement is unavailable"));
                return info;
            }
            int rate = setRates[id];
            info.setClassName(SeekBar.class.getName());
            float most = FilterSettings.MOST_CENTS / 100f;
            info.setRangeInfo(Build.VERSION.SDK_INT >= 30
                    ? new AccessibilityNodeInfo.RangeInfo(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0, most,
                            rate / 100f)
                    : AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0, most,
                            rate / 100f));
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
                    int was = focusedNode;
                    focusedNode = id;
                    focusedOfferAt = id >= OFFER_ID ? markTimes.get(id - OFFER_ID) : -1;
                    invalidate();
                    sendNodeEvent(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                    // Undo holds while a screen reader is on it, and its time starts over once it moves on.
                    if (id == ADOPT_ID) removeCallbacks(undoEnds);
                    else if (was == ADOPT_ID) timeUndo();
                    return true;
                }
                case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                    if (focusedNode != id) return false;
                    focusedNode = NO_NODE;
                    invalidate();
                    sendNodeEvent(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                    if (id == ADOPT_ID) timeUndo();
                    return true;
                case AccessibilityNodeInfo.ACTION_CLICK:
                    if (id == AreaScore.HOTSPOT) {
                        explainHotspot();
                        return true;
                    }
                    if (id >= OFFER_ID) return openOffer(id - OFFER_ID);
                    if (id == SCORE_ID) {
                        pressScore();
                        return true;
                    }
                    if (id == ADAPTIVE_ID) {
                        pressAdaptive();
                        return true;
                    }
                    if (id == STOPS_ID) return setStops(nextStops(maxStops));
                    if (id != ADOPT_ID || !adoptShown()) return false;
                    pressAdopt();
                    return true;
                case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:
                case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD: {
                    boolean up = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
                    if (id == STOPS_ID) return setStops(stepStops(maxStops, up));
                    if (id == SCORE_ID) return setMinimumScale(shownTradeoffPercent() + (up ? 1 : -1));
                    return id < NAMES.length && step(id, up);
                }
                case TOGGLE_ACTION:
                    if (id == SCORE_ID) {
                        pressScore();
                        return true;
                    }
                    if (id != ADAPTIVE_ID) return false;
                    pressAdaptive();
                    return true;
                case RESET_ACTION:
                    if (id != ADAPTIVE_ID || changes == null) return false;
                    changes.resetLearned();
                    return true;
                default:
                    if (action == android.R.id.accessibilityActionSetProgress && id == SCORE_ID
                            && arguments != null) {
                        float percent = arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, -1);
                        if (!Float.isFinite(percent) || percent < 0 || percent > 100) return false;
                        return setMinimumScale(Math.round(percent));
                    }
                    if (action == android.R.id.accessibilityActionSetProgress && id < NAMES.length
                            && arguments != null) {
                        float dollars = arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, -1);
                        if (!(dollars >= 0)) return false;
                        long rate = Math.round(Math.min(FilterSettings.MOST_CENTS, dollars * 100.0) / STEPS[id])
                                * STEPS[id];
                        return setByReader(id, (int) Math.min(FilterSettings.MOST_CENTS, rate));
                    }
                    return false;
            }
        }
    }
}
