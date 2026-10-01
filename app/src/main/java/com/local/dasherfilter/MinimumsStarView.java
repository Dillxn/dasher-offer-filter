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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The minimums as constellations in the page's sky: a spoke each for pay, per mile, per minute and per stop,
 * with the minimums you set (solid, round stars) and the adaptive minimums learned from offers you accepted or
 * declined by hand (dashed, sparkles). Distance from the middle is the pay each one asks of one example offer, so
 * every spoke shares a dollar scale. Recent offers are small marks on each spoke at what their own pay, per mile,
 * per minute and per stop would pay for the example (● passed, ✕ declined, ○ review): a mark outside a minimum beat
 * it. Every spoke is a floor of the same kind: the set per-stop minimum asks the example's stops × its rate, as the
 * adaptive one does. The shapes glide to new values and the adaptive sparkles breathe; with Android's animations off
 * they rest. By day the stars are drawn in ink on the morning sky, by night they shine. Each spoke is marked by its
 * icon from Settings, with no words or key on the page; screen readers hear the whole of it, and what the example
 * offer needs.
 *
 * <p>The spokes stand {@link #SPREAD} degrees above and below level, to the left and to the right, so a wide sky (or a
 * short header) holds a wide chart. On a whole screen or beside Dasher it is the page's sky itself: the page's
 * {@link SkyStage} puts one very large circle where it fits, its faint rings running on past the page's edges and
 * behind the header, the rings' dollars along the level line on the right, and the chart faded wherever the stage says
 * words or the mascot sit on it (the mascot's disc cut out of it altogether). Its points and icons only ever lie along
 * the spokes, which the stage keeps inside the page and clear of the counts and the buttons. Only a touch on the
 * circle or an icon opens the minimums; the empty sky around it takes none.
 *
 * <p>As the sky, the set minimums are knobs: each spoke's round star, ringed so it reads as something to take hold
 * of (or, with no set minimum on that spoke while another has one, a small hollow knob resting just outside the
 * middle), grows under a finger and can be dragged along its spoke, in steps, with a light tick at each; a small
 * readout beside it says the value while it moves, and letting go saves it as Settings would. A knob keeps its exact
 * value until the finger has moved it half a step along its spoke, so a wobble never snaps it to a step; it is taken
 * only by a finger moving along its spoke, so a scroll of the page or a slide across it is left to the page. Dragged
 * into the middle, the rule is off (a knob resting near the middle must be pushed a clear way further in). The ring
 * scale holds still while a knob moves (it may be pushed a little past the outer ring) and settles to fit when it is
 * let go. With no set minimum at all there are no knobs: a tap sets the rules up. The adaptive minimums are learned, so
 * they cannot be dragged; but while any of them asks more than its saved set minimum, one round button beside the
 * chart (a dashed purple shape passing into a solid blue one) makes them the set minimums, and for a few seconds after
 * undoes that (longer where Android's accessibility timeout asks, and for as long as a screen reader is on it).
 * Screen readers reach each knob as an adjustable control and the button as a button. In a header the chart takes a
 * tap only.
 */
@SuppressLint("ViewConstructor")
final class MinimumsStarView extends View {
    private static final String[] NAMES = {"Pay", "Per mile", "Per minute", "Per stop"};
    /** Each spoke is marked with the same icon as its field in Settings; the names are for screen readers. */
    private static final Glyph.Shape[] ICONS = {Glyph.Shape.COIN, Glyph.Shape.ROAD, Glyph.Shape.CLOCK,
            Glyph.Shape.PIN};
    private static final int ICON_DP = 18;
    /**
     * The spokes stand this many degrees above and below level: top-left, top-right, bottom-right, bottom-left. The
     * same in a header and as the sky, so the shapes look alike wherever the chart is.
     */
    static final float SPREAD = 30;
    private static final float[] ANGLES = {SPREAD - 180, -SPREAD, SPREAD, 180 - SPREAD};
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
    /** At most this many recent offers are marked, as on the skyline. */
    private static final int MARKS = DecisionChartView.SLOTS;
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

    // ---- Knobs and the adopt button (as the sky only). ----

    /** What the page does when a knob is let go, the learned minimums are adopted, or that is undone. */
    interface Changes {
        /** Sets spoke {@code axis}'s minimum to {@code cents} of its own unit (0 turns it off), saving it at once. */
        void setMinimum(int axis, int cents);

        /**
         * Makes the adaptive minimums the saved set ones; answers what Undo puts back (the saved minimums it replaced,
         * -1 for those it left alone), or null when nothing changed.
         */
        int[] adoptLearned();

        /** Puts back the set minimums {@code cents} (pay, per mile, per minute, per stop; -1 leaves one alone). */
        void restore(int[] cents);
    }

    /** A knob moves in these steps of its spoke's own unit: $0.50 of pay, $0.05 a mile, $0.01 a minute, $0.25 a stop. */
    static final int[] STEPS = {50, 5, 1, 25};
    private static final String[] UNITS = {"", "/mi", "/min", "/stop"};
    private static final String[] KNOBS = {"Minimum pay", "Minimum per mile", "Minimum per minute", "Minimum per stop"};
    /** A knob takes a touch this far from its middle (a 48 dp target); where two could, the nearer one does. */
    private static final int KNOB_REACH_DP = 24;
    /** A spoke with no set minimum rests its hollow knob this far out; dragged back inside it, a rule is off. */
    private static final int KNOB_REST_DP = 20;
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
    /** The screen reader's ids for the knobs (0 to 3, by spoke) and the adopt button. */
    static final int ADOPT_ID = NAMES.length;
    private static final int NO_NODE = Integer.MIN_VALUE;

    private Changes changes;
    /** Each spoke's set minimum in its own unit as shown: cents of pay, a mile, a minute, a stop; 0 when off. */
    private final int[] setRates = new int[NAMES.length];
    /** The example offer's miles, minutes and stops as last shown, which turn a knob's rate into pay on the scale... */
    private double exampleMiles = 5;
    private int exampleMinutes = 20;
    private int exampleStops = 2;
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
    /** The four set minimums as saved (the knobs show them as typed in Settings, saved or not). */
    private final int[] savedRates = new int[NAMES.length];
    /** The four set minimums before the learned ones were adopted, while Undo is offered; else null. */
    private int[] undoValues;
    /** The four set minimums the adoption made: any other change to them (a knob, Settings) ends Undo. */
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
    private boolean adoptStale = true;
    /** The circle moved or the view changed size since the button was placed. */
    private boolean layoutMoved = true;
    private final Glyph adoptGlyph;
    private final Glyph undoGlyph;
    private final Nodes nodes = new Nodes();
    private int focusedNode = NO_NODE;
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
        undoGlyph = new Glyph(Glyph.Shape.UNDO, ui.ink, ui.dp(24));
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    /** Knobs and the adopt button save through {@code changes}; without them the chart only takes a tap. */
    void setChanges(Changes changes) {
        this.changes = changes;
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
     * @param rules the rules as shown: as typed in Settings, saved or not
     * @param saved the rules as saved, which the adopt button works from (and the knobs save into)
     * @param example the offer whose miles, minutes and stops turn each minimum into pay; all three must be known
     *     (the screen uses the latest fully read offer, or a typical one)
     * @param recent recent decisions, newest first; standalone offers with pay are marked
     */
    void show(FilterSettings rules, FilterSettings saved, OfferSnapshot example, List<DecisionLog.Entry> recent) {
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
        put(3, rules.perStopCents > 0 ? DecisionLog.money(rules.perStopCents) : null,
                (long) rules.perStopCents * stops,
                best.hasPerStop() ? best.forStops(stops) : 0, best.hasPerStop() ? best.perStop() : null,
                declined.rates.hasPerStop() ? declined.beatStops(stops) : 0,
                declined.rates.hasPerStop() ? "more than " + declined.rates.perStop() : null);
        adaptiveOn = rules.risingOffers;
        int[] rates = rules.minimums();
        for (int i = 0; i < NAMES.length; i++) setRates[i] = Math.max(0, rates[i]);
        exampleMiles = miles;
        exampleMinutes = minutes;
        exampleStops = stops;
        int[] kept = saved.minimums();
        for (int i = 0; i < NAMES.length; i++) savedRates[i] = Math.max(0, kept[i]);
        adoptable = saved.risingOffers && !Arrays.equals(saved.adoptAdaptive().minimums(), kept);
        // Undo would put back more than the adoption once the saved minimums change any other way.
        if (undoValues != null && !Arrays.equals(savedRates, adoptedRates)) stopUndo();
        adoptStale = true;
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
        shownRing = top > 0 ? ringStep(top) : 0;
        // The rings hold still under a moving knob; they settle to fit when it is let go.
        if (!dragging) ringCents = shownRing;
        glideTo();
        setContentDescription(caption() + " " + describe());
        dropGoneFocus();
        invalidate();
        nodesChanged();
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

    /** Cents the set minimum on spoke {@code axis} asks of the example offer; NaN where none is set (for tests). */
    double setAsks(int axis) {
        return set[axis];
    }

    /** Cents the adaptive minimum on spoke {@code axis} asks of the example offer; NaN where none (for tests). */
    double learnedAsks(int axis) {
        return learned[axis];
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
        return SIN * radius + ui.dp(ICON_GAP_DP) + backdropIcon();
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
        float[] tip = point(cx, cy, radius, axis, 1);
        boolean right = axis == 1 || axis == 2;
        boolean below = axis == 2 || axis == 3;
        float size = backdropIcon();
        float middle = tip[0] + (right ? 1 : -1) * ui.dp(ICON_OUT_DP);
        for (int side = 0; side < 2; side++) {
            boolean under = below == (side == 0);
            float top = under ? tip[1] + ui.dp(ICON_GAP_DP) : tip[1] - ui.dp(ICON_GAP_DP) - size;
            out.set(middle - size / 2, top, middle + size / 2, top + size);
            if (!underWords(out) && (side == 0 || !onMascot(out))) return true;
        }
        return false;
    }

    /** As the sky, the icons' boxes as shown now (the adopt button's too), in this view's pixels. */
    void iconsAt(List<RectF> out) {
        if (!backdrop()) return;
        for (int i = 0; i < ICONS.length; i++) {
            RectF box = new RectF();
            if (skyIcon(i, skyX, skyY, skyRadius, box)) out.add(box);
        }
        if (adoptShown()) out.add(new RectF(adoptBox));
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
    }

    /** The rings' dollars as the sky shows them now, left to right (for tests). */
    List<String> levelWords() {
        if (backdrop()) placeLevelLabels(skyX, skyY, skyRadius, Motion.settle(glideStart, GLIDE_MS));
        return new ArrayList<>(levelWords);
    }

    /** As the sky, only a touch on the circle (an icon at a spoke's end, a knob, the button) is for the constellation. */
    private boolean onSky(float x, float y) {
        float reach = skyRadius + ui.dp(8);
        float dx = x - skyX;
        float dy = y - skyY;
        if (dx * dx + dy * dy <= reach * reach || target(x, y) != NO_NODE) return true;
        for (int i = 0; i < ICONS.length; i++) {
            if (!skyIcon(i, skyX, skyY, skyRadius, iconBox)) continue;
            iconBox.inset(-ui.dp(8), -ui.dp(8));
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
     * As the sky, a finger exploring the screen with a screen reader on finds each knob and the button by itself; the
     * rest of the circle is the chart as a whole.
     */
    @Override public boolean dispatchHoverEvent(MotionEvent event) {
        boolean leaving = event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT;
        if (backdrop() && !leaving && !onSky(event.getX(), event.getY())) {
            hover(NO_NODE);
            return false;
        }
        if (knobsOn() && exploring()) {
            int node = leaving ? NO_NODE : target(event.getX(), event.getY());
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
        drawMarks(canvas, cx, cy, radius);
        float glide = Motion.settle(glideStart, GLIDE_MS);
        drawShape(canvas, cx, cy, radius, set, setFrom, setTo, glide, setColor(), true, false);
        drawShape(canvas, cx, cy, radius, learned, learnedFrom, learnedTo, glide, learnedColor(), adaptiveOn, true);
        for (int i = 0; i < NAMES.length; i++) {
            if (beside) drawIconBeside(canvas, i, cx, cy, radius);
            else drawIcon(canvas, i, cx, cy, window, width, ui.dp(ICON_DP));
        }
        nextFrame();
    }

    /** A glide to new values (after a drag, say) draws every frame; the rest is steady motion. */
    private void nextFrame() {
        if (Motion.settle(glideStart, GLIDE_MS) < 1) Motion.settling(this);
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
        // A large circle draws its points a little larger, so they read at a glance.
        detail = Math.max(0.6f, Math.min(1, radius / ui.dp(45)))
                + Math.max(0, Math.min(0.35f, (radius - ui.dp(100)) / ui.dp(150)));
        int layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
        drawGrid(canvas, cx, cy, radius);
        fadeEdges(canvas, cy, radius);
        drawMarks(canvas, cx, cy, radius);
        float glide = Motion.settle(glideStart, GLIDE_MS);
        holdSet();
        drawShape(canvas, cx, cy, radius, drawnSet, drawnFrom, drawnTo, glide, setColor(), true, false);
        drawShape(canvas, cx, cy, radius, learned, learnedFrom, learnedTo, glide, learnedColor(), adaptiveOn, true);
        boolean knobs = knobsOn();
        if (knobs) drawKnobs(canvas, cx, cy, radius, glide);
        for (int i = 0; i < NAMES.length; i++) {
            if (!skyIcon(i, cx, cy, radius, iconBox)) continue;
            icons[i].setBounds(Math.round(iconBox.left), Math.round(iconBox.top), Math.round(iconBox.right),
                    Math.round(iconBox.bottom));
            icons[i].draw(canvas);
        }
        for (Veil veil : veils) drawVeil(canvas, veil.box, veil.round, veil.fade);
        canvas.restoreToCount(layer);
        // The held knob's readout is placed first, so the rings' dollars it would cover step aside for it.
        if (dragging) placeReadout(cx, cy, radius);
        drawLevelLabels(canvas, cx, cy, radius, glide);
        if (!knobs) return;
        // Over everything, never faded: the button, and the knob under the finger (with what it is set to, once it
        // moves).
        if (adoptShown()) drawAdopt(canvas);
        if (dragging) {
            drawHeld(canvas, point(cx, cy, radius, held, heldFraction()), dragValue, true);
        } else if (pressing()) {
            drawHeld(canvas, point(cx, cy, radius, held, knobFraction(held, glide)), setRates[held], false);
        }
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
            float reach = veil.box.width() / 2 + ui.dp(8);
            float dx = Math.max(0, Math.max(box.left - veil.box.centerX(), veil.box.centerX() - box.right));
            float dy = Math.max(0, Math.max(box.top - veil.box.centerY(), veil.box.centerY() - box.bottom));
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
            if (dragging && RectF.intersects(levelBoxes.get(i), pillBox)) continue;
            // Twice, so the halo is soft but full.
            canvas.drawText(levelWords.get(i), levelBoxes.get(i).left, baseline, levelText);
            canvas.drawText(levelWords.get(i), levelBoxes.get(i).left, baseline, levelText);
        }
    }

    /** Works out where the rings' dollars stand (see {@link #drawLevelLabels}), into the level lists. */
    private void placeLevelLabels(float cx, float cy, float radius, float glide) {
        levelBoxes.clear();
        levelWords.clear();
        if (ringCents <= 0) return;
        Paint.FontMetrics metrics = levelText.getFontMetrics();
        float baseline = cy - (metrics.ascent + metrics.descent) / 2;
        holdSet();
        float[] edges = {edgeCrossing(drawnSet, drawnFrom, drawnTo, glide, cx, radius),
                edgeCrossing(learned, learnedFrom, learnedTo, glide, cx, radius)};
        float clear = ui.dp(4) + ui.dp(1) * Math.max(1, detail);
        float before = -Float.MAX_VALUE;
        for (int ring = 2; ring <= 3; ring++) {
            String words = levelLabel(ring);
            float width = levelText.measureText(words);
            float at = cx + radius * ring / 3;
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

    private String levelLabel(int ring) {
        return "$" + (ringCents * ring / 100);
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
        if (ringCents <= 0 || backdrop()) return;
        text.setFakeBoldText(false);
        text.setTextSize(Math.min(ui.sp(10), ui.dp(13)));
        text.setColor(ui.dark ? 0x80FFFFFF : 0xB0214066);
        text.setTextAlign(Paint.Align.CENTER);
        float below = -text.getFontMetrics().ascent + ui.dp(1);
        // On a small circle the two labels would run into each other and the points: only the outer ring's.
        for (int ring = radius < ui.dp(40) ? 3 : 2; ring <= 3; ring++) {
            canvas.drawText("$" + (ringCents * ring / 100), cx, cy - radius * ring / 3 + below, text);
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
            // Offers side by side on a spoke step a little aside so they do not hide one another.
            float aside = ((m % 5) - 2) * ui.dp(MARK_STEP_DP) * detail;
            for (int axis = 0; axis < NAMES.length; axis++) {
                if (Double.isNaN(mark[axis]) || ringCents <= 0) continue;
                float out = (float) Math.min(MARK_REACH, mark[axis] / (ringCents * 3.0));
                float[] at = point(cx, cy, radius, axis, out, aside);
                if (m == 0) {
                    float pulse = Motion.on() ? Motion.loop(2.4f, 0) : 1;
                    line.setPathEffect(null);
                    line.setStrokeWidth(Math.max(1, ui.dp(1)));
                    line.setColor((color & 0x00FFFFFF) | ((int) (0x90 * (1 - pulse)) << 24));
                    canvas.drawCircle(at[0], at[1], (ui.dp(4) + ui.dp(7) * pulse) * detail, line);
                }
                drawMark(canvas, at[0], at[1], result, (color & 0x00FFFFFF) | (alpha << 24));
            }
        }
    }

    /** ● passed, ✕ declined, ○ review: the shape carries the outcome, not only the color. */
    private void drawMark(Canvas canvas, float x, float y, OfferRule.Result result, int color) {
        float size = ui.dp(3.2f) * detail;
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
        line.setStrokeWidth(ui.dp(2) * Math.max(1, detail));
        line.setPathEffect(dashed ? dash : null);
        canvas.drawPath(path, line);
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
        boolean right = axis == 1 || axis == 2;
        boolean below = axis == 2 || axis == 3;
        int size = ui.dp(ICON_DP);
        int left = Math.round(right ? cx + radius + ui.dp(6) : cx - radius - ui.dp(6) - size);
        int top = Math.round(below ? cy + ui.dp(2) : cy - ui.dp(2) - size);
        icons[axis].setBounds(left, top, left + size, top + size);
        icons[axis].draw(canvas);
    }

    /** A spoke's icon just outside the circle at its corner. */
    private void drawIcon(Canvas canvas, int axis, float cx, float cy, float window, float width, int size) {
        boolean right = axis == 1 || axis == 2;
        boolean below = axis == 2 || axis == 3;
        float[] corner = point(cx, cy, window + ui.dp(4), axis, 1);
        int left = Math.round(right ? corner[0] : corner[0] - size);
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
     * Spoke {@code axis} has a knob: its set minimum's, or a hollow one while another spoke has a set minimum. With no
     * set minimum at all there are none, and a tap on the chart (or the line under the mascot) sets the rules up.
     */
    private boolean knobShown(int axis) {
        if (setRates[axis] > 0) return true;
        for (int rate : setRates) if (rate > 0) return true;
        return false;
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
        if (dragValue <= 0 || ringCents <= 0) return restFraction();
        float at = (float) (askCents(held, dragValue) / (ringCents * 3.0));
        return Math.min(at, pushLimit(held) / skyRadius);
    }

    /** Pay the held knob's rate asks of the example offer (as it was when taken), as the set shape draws it. */
    private double askCents(int axis, int rate) {
        switch (axis) {
            case 0: return rate;
            case 1: return OfferRule.mileageCost(rate, dragMiles);
            case 2: return (double) rate * dragMinutes;
            default: return (double) rate * dragStops;
        }
    }

    /** The example offer's amount of spoke {@code axis}'s unit when the knob was taken: 1 for pay, else miles... */
    private double units(int axis) {
        switch (axis) {
            case 0: return 1;
            case 1: return dragMiles;
            case 2: return dragMinutes;
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
     * it, a clear way further in); at most what Settings accepts.
     */
    private int rateAt(int axis, float distance) {
        float out = Math.min(distance, pushLimit(axis));
        if (out <= offAt || ringCents <= 0) return 0;
        double cents = out / skyRadius * ringCents * 3.0;
        long steps = Math.max(1, Math.round(cents / units(axis) / STEPS[axis]));
        return (int) Math.min(FilterSettings.MOST_CENTS, steps * STEPS[axis]);
    }

    /** Half a step of spoke {@code axis}'s knob, in pixels along it, on the scale the drag holds still. */
    private float halfStep(int axis) {
        if (ringCents <= 0 || skyRadius <= 0) return 0;
        return (float) (askCents(axis, STEPS[axis]) / (ringCents * 3.0) * skyRadius / 2);
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
        if (adoptShown()) {
            float dx = x - adoptBox.centerX();
            float dy = y - adoptBox.centerY();
            float button = ui.dp(ADOPT_REACH_DP);
            if (dx * dx + dy * dy <= Math.min(nearest, button * button)) found = ADOPT_ID;
        }
        return found;
    }

    private void keepTouch(boolean keep) {
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(keep);
    }

    /**
     * As the sky, a finger on a knob grows it; moving along the knob's spoke drags it (the page around does not scroll
     * meanwhile), while moving any other way leaves the touch to the page (a scroll) and sets nothing; a tap on the
     * button adopts the learned minimums or undoes that; any other tap on the circle, a knob's included, opens the
     * minimums. In a header, the chart is one button.
     */
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!knobsOn() && !touching) return super.onTouchEvent(event);
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
                if (on != NO_NODE && !adoptPressed) {
                    held = on;
                    grabDistance = knobFraction(on, Motion.settle(glideStart, GLIDE_MS)) * skyRadius;
                    grabOffset = grabDistance - along(on, x, y);
                }
                // Nothing is claimed yet, so the page may still take this touch for a scroll: a knob is taken only
                // once the finger moves along its spoke.
                if (on != NO_NODE) invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (tapping && Math.hypot(x - downX, y - downY) > slop) {
                    tapping = false;
                    if (held >= 0 && alongSpoke(held, x - downX, y - downY)) {
                        takeKnob();
                    } else if (held >= 0) {
                        // Across the spoke: not a drag, and no longer a tap.
                        held = -1;
                        invalidate();
                    }
                }
                if (adoptPressed && target(x, y) != ADOPT_ID) {
                    adoptPressed = false;
                    invalidate();
                }
                if (dragging) moveKnob(along(held, x, y) + grabOffset);
                return true;
            case MotionEvent.ACTION_UP: {
                boolean button = adoptPressed;
                boolean tap = tapping;
                endTouch(true);
                if (button) {
                    playSoundEffect(SoundEffectConstants.CLICK);
                    pressAdopt();
                } else if (tap) {
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
        if (adoptPressed || pressed) {
            adoptPressed = false;
            invalidate();
        }
        if (dragging) letGo(lifted);
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
        dragging = true;
        if (ringCents <= 0) ringCents = FIRST_RING_CENTS;
        dragMiles = exampleMiles;
        dragMinutes = exampleMinutes;
        dragStops = exampleStops;
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
            learnedFrom[i] = learnedTo[i] = learnedFrom[i] + (learnedTo[i] - learnedFrom[i]) * glide;
        }
        if (save) setFrom[axis] = setTo[axis] = value > 0 ? at : 0;
        glideStart = SystemClock.uptimeMillis();
        dragging = false;
        held = -1;
        keepTouch(false);
        ringCents = shownRing;
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
        if (!dragging || held < 0) return;
        float at = heldFraction();
        drawnSet[held] = dragValue > 0 ? askCents(held, dragValue) : Double.NaN;
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
        int color = setColor();
        float breathe = 1 + 0.14f * Motion.wave(3.2f, 0);
        for (int i = 0; i < NAMES.length; i++) {
            if (!knobShown(i) || ((dragging || pressing()) && i == held)) continue;
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
        return rate <= 0 ? "off" : DecisionLog.money(rate) + UNITS[axis];
    }

    /**
     * Where the held knob's readout stands, into the pill box: above the knob, clear of the finger, where that is
     * inside the page and clear of the words and the mascot; else beside it (towards the page's middle, then away),
     * else below it; else above it, kept inside the page.
     */
    private void placeReadout(float cx, float cy, float radius) {
        float[] at = point(cx, cy, radius, held, heldFraction());
        String words = readout(held, dragValue);
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
        String words = readout(held, dragValue);
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
        return knobsOn() && (adoptable || undoValues != null) && placeAdopt();
    }

    /** Undo is offered (for tests). */
    boolean offeringUndo() {
        return undoValues != null;
    }

    /** Where the button stands, in this view's pixels (for tests). */
    RectF adoptBox() {
        return adoptShown() ? new RectF(adoptBox) : null;
    }

    /**
     * Where the button stands: straight below the circle's middle, just outside where the shapes cross that line
     * (else above it), clear of the knobs, the icons, the rings' dollars, the words and the mascot, and within the
     * spokes' height, which the stage keeps inside the page and below the counts. Worked out from where the shapes are
     * heading, so it does not drift while they glide, and not at all while a knob is held; while it offers Undo it
     * stays where it was tapped, unless the circle itself moves.
     */
    private boolean placeAdopt() {
        if (dragging || (!adoptStale && !layoutMoved)) return adoptPlaced;
        if (undoValues != null && adoptPlaced && !layoutMoved) return true;
        adoptStale = false;
        layoutMoved = false;
        float half = ui.dp(ADOPT_DP) / 2f;
        float band = backdropHalfHeight(skyRadius) - half;
        placeLevelLabels(skyX, skyY, skyRadius, 1);
        for (int pass = 0; pass < 2; pass++) {
            for (int side = 1; side >= -1; side -= 2) {
                float from = half + ui.dp(KNOB_REST_DP);
                // First just outside the shapes; failing that, over them.
                if (pass == 0) from = Math.max(from, shapesReach(side) + ui.dp(10) + half);
                for (float d = from; d <= band; d += ui.dp(2)) {
                    adoptBox.set(skyX - half, skyY + side * d - half, skyX + half, skyY + side * d + half);
                    if (adoptFits()) return adoptPlaced = true;
                }
            }
        }
        return adoptPlaced = false;
    }

    /** How far below ({@code side} 1) or above (-1) the middle the shapes' edges cross the line through it. */
    private float shapesReach(int side) {
        int right = side > 0 ? 2 : 1;
        int left = side > 0 ? 3 : 0;
        float reach = 0;
        double[][] values = {set, learned};
        float[][] to = {setTo, learnedTo};
        for (int shape = 0; shape < 2; shape++) {
            boolean any = false;
            for (double value : values[shape]) any |= !Double.isNaN(value);
            if (!any) continue;
            float a = Double.isNaN(values[shape][right]) ? 0 : to[shape][right];
            float b = Double.isNaN(values[shape][left]) ? 0 : to[shape][left];
            if (a + b > 0) reach = Math.max(reach, skyRadius * SIN * 2 * a * b / (a + b));
        }
        return reach;
    }

    private boolean adoptFits() {
        float margin = ui.dp(4);
        if (adoptBox.left < margin || adoptBox.top < margin || adoptBox.right > getWidth() - margin
                || adoptBox.bottom > getHeight() - margin) {
            return false;
        }
        RectF around = new RectF(adoptBox);
        around.inset(-ui.dp(4), -ui.dp(4));
        if (underWords(around) || onMascot(adoptBox)) return false;
        for (RectF label : levelBoxes) if (RectF.intersects(label, around)) return false;
        float clear = ui.dp(KNOB_REACH_DP) + adoptBox.width() / 2;
        for (int i = 0; i < NAMES.length; i++) {
            if (skyIcon(i, skyX, skyY, skyRadius, iconBox) && RectF.intersects(iconBox, around)) return false;
            if (!knobShown(i)) continue;
            float[] at = point(skyX, skyY, skyRadius, i, Double.isNaN(set[i]) ? restFraction() : setTo[i]);
            if (Math.hypot(at[0] - adoptBox.centerX(), at[1] - adoptBox.centerY()) < clear) return false;
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
            parts.add(name + " " + (rates[i] > 0 ? DecisionLog.money(rates[i]) : "off"));
        }
        return String.join(", ", parts);
    }

    /** A screen reader's focus on a knob or the button that is no longer there is let go, and it is told so. */
    private void dropGoneFocus() {
        if (focusedNode == NO_NODE || pressingAdopt) return;
        boolean gone = !knobsOn() || (focusedNode == ADOPT_ID ? !adoptShown() : !knobShown(focusedNode));
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
        undoValues = null;
        super.onDetachedFromWindow();
    }

    // ---- Screen readers: each knob an adjustable control, the button a button. ----

    @SuppressWarnings("deprecation")
    private void say(String words) {
        lastSaid = words;
        announceForAccessibility(words);
    }

    /** "Minimum per mile, $1.50; adaptive $2.37, learned" */
    private String knobSaid(int axis) {
        int rate = setRates[axis];
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
        if (rate == setRates[axis] || changes == null) return false;
        changes.setMinimum(axis, rate);
        say(knobSaid(axis));
        nodesChanged();
        return true;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return knobsOn() ? nodes : super.getAccessibilityNodeProvider();
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
        event.setClassName(node == ADOPT_ID ? Button.class.getName() : SeekBar.class.getName());
        event.setContentDescription(nodeSaid(node));
        event.setSource(this, node);
        event.setEnabled(true);
        getParent().requestSendAccessibilityEvent(this, event);
    }

    private String nodeSaid(int node) {
        return node == ADOPT_ID ? (undoValues != null ? "Undo" : ADOPT_SAID) : knobSaid(node);
    }

    @SuppressWarnings("deprecation")
    private static AccessibilityEvent newEvent(int type) {
        return Build.VERSION.SDK_INT >= 30 ? new AccessibilityEvent(type) : AccessibilityEvent.obtain(type);
    }

    /** A node's box, in this view's pixels. */
    private void nodeBox(int node, Rect out) {
        if (node == ADOPT_ID) {
            float reach = ui.dp(ADOPT_REACH_DP);
            out.set(Math.round(adoptBox.centerX() - reach), Math.round(adoptBox.centerY() - reach),
                    Math.round(adoptBox.centerX() + reach), Math.round(adoptBox.centerY() + reach));
            return;
        }
        float[] at = point(skyX, skyY, skyRadius, node, knobFraction(node, Motion.settle(glideStart, GLIDE_MS)));
        int reach = ui.dp(KNOB_REACH_DP);
        out.set(Math.round(at[0]) - reach, Math.round(at[1]) - reach, Math.round(at[0]) + reach,
                Math.round(at[1]) + reach);
    }

    /** A knob (0 to 3) or the button that is there now. */
    private boolean shownNode(int id) {
        if (id < 0 || id > ADOPT_ID || !knobsOn()) return false;
        return id == ADOPT_ID ? adoptShown() : knobShown(id);
    }

    /** Which knob or button a screen reader is on (for tests); -1 for none. */
    int focusedNode() {
        return focusedNode == NO_NODE ? -1 : focusedNode;
    }

    /** The knobs (ids 0 to 3, by spoke) and the adopt button, for screen readers. */
    private final class Nodes extends AccessibilityNodeProvider {
        @SuppressWarnings("deprecation")
        @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
            View host = MinimumsStarView.this;
            if (id == HOST_VIEW_ID) {
                AccessibilityNodeInfo info = Build.VERSION.SDK_INT >= 30 ? new AccessibilityNodeInfo(host)
                        : AccessibilityNodeInfo.obtain(host);
                onInitializeAccessibilityNodeInfo(info);
                for (int axis = 0; axis < NAMES.length; axis++) if (knobShown(axis)) info.addChild(host, axis);
                if (adoptShown()) info.addChild(host, ADOPT_ID);
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
            if (id == ADOPT_ID) {
                info.setClassName(Button.class.getName());
                info.setClickable(true);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
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
                    if (id != ADOPT_ID || !adoptShown()) return false;
                    pressAdopt();
                    return true;
                case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:
                case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:
                    return id < NAMES.length && step(id, action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                default:
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
