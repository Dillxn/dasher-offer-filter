package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The mascot, a funnel standing upright in a brush-drawn ring, with the last 24 hours in three quiet counts below:
 * passed, filtered and left to review. The mascot alone pauses or resumes auto-decline (or,
 * with no rule yet, points to the knobs), and it squishes a little while pressed. The ring says the state: nearly closed
 * while on, two-thirds and amber while paused, a faint dotted circle while off; it draws itself when the state
 * changes. On, the mascot breathes, blinks, now and then waves, and has its sieve; each offer decided while the page
 * is up is played out as it went (a ticket through the spout, bounced off the sieve, or resting on it, with the
 * skyline's badge); paused, it sleeps (dashed, amber, drifting "z"s); off, it is grey and still. "Filtered"
 * follows the observed decline outcome; a hidden notification counts as review, never filtered.
 *
 * <p>In the page's sky ({@link SkyStage}) the mascot and the counts are placed apart, over the constellation behind
 * them: the stage says where each stands, and only a touch (or a screen reader's finger) on the mascot or the counts is
 * theirs; anywhere else in this view it goes on to the constellation below.
 */
@SuppressLint("ViewConstructor")
final class FilterHeroView extends FrameLayout {
    enum State { ON, PAUSED, OFF }

    /** The drawing's own size; narrower screens scale it down whole. */
    private static final int DESIGN_WIDTH_DP = 320;
    private static final int DESIGN_HEIGHT_DP = 268;
    /** The drawing (ring, mascot, ticket) takes the design's top; the counts under it keep their own size. */
    private static final int ART_HEIGHT_DP = 196;
    private static final int COUNTS_HEIGHT_DP = 66;
    /** The least share of its size the drawing shrinks to when a screen is short. */
    private static final float MIN_SHARE = 0.3f;
    private static final float ON_SWEEP = 324;
    private static final float PAUSED_SWEEP = 228;
    private static final long RING_DRAW_MS = 1100;

    /** The ring's outer edge, brush included, from its middle, in the design's dp. */
    private static final int RING_OUTER_DP = 88;
    /** The ring's middle, down from the top of the design, in dp. */
    private static final int RING_MIDDLE_DP = 104;

    /** Stars around the ring: dp from the middle across, dp down, and size. */
    private static final float[][] TWINKLES = {
            {-98, 40, 5}, {-116, 118, 3.5f}, {-80, 170, 3}, {100, 30, 4}, {120, 96, 5.5f}, {88, 162, 3.5f}};

    private final Ui ui;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final DashPathEffect pausedDash;
    private final DashPathEffect dotted;
    private final Enso ring = new Enso();
    /** How long an offer just decided takes to play out. */
    static final long OFFER_MS = 2600;
    /** The offer being played out, and since when (uptime); null for none. */
    private DecisionLog.Outcome offer;
    private long offerFrom;
    /** The body's and the rim's shading, kept while their colour (and the body's place) stay the same. */
    private Shader bodyShade;
    private int bodyShadeColor;
    private float bodyShadeX;
    private Shader rimShade;
    private int rimShadeColor;
    /** When the ring began drawing itself (uptime), for the current state. */
    private long ringFrom;
    /** What a tap does, as screen readers announce it: "Pause auto-decline", for example. */
    private String action;
    private State state = State.OFF;
    /** This dash's (or the last dash's) counts, and every offer's since the history was cleared. */
    private int passed;
    private int filtered;
    private int review;
    private int[] totals = new int[3];
    private String dashLabel = "No dash yet";
    /** Whether the app is watching a dash; the page shows it with searchlights, screen readers hear it here. */
    private boolean watching;
    /** Placed by the page's sky: where the mascot's ring stands and how large, and where the counts stand. */
    private boolean placed;
    private float mascotX;
    private float mascotY;
    private float mascotRadius;
    private final RectF countsBox = new RectF();
    private float countsSpacing;
    /** Where the counts are drawn this frame, which the twinkling stars keep out of. */
    private final RectF countsDrawn = new RectF();
    // Real controls over the drawing keep touch, keyboard and accessibility boundaries identical.
    private final Button mascotControl;
    private final Button[] countControls = new Button[3];
    private Consumer<DecisionLog.Tally> onCount;

    FilterHeroView(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        pausedDash = new DashPathEffect(new float[] {ui.dp(7), ui.dp(5)}, 0);
        dotted = new DashPathEffect(new float[] {ui.dp(2), ui.dp(6)}, 0);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        mascotControl = control();
        mascotControl.setAccessibilityDelegate(new AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (action != null) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLICK, action));
            }
        });
        for (int i = 0; i < countControls.length; i++) {
            final DecisionLog.Tally tally = DecisionLog.Tally.values()[i];
            countControls[i] = control();
            countControls[i].setOnClickListener(tapped -> {
                if (onCount != null) onCount.accept(tally);
            });
        }
        describe();
    }

    private Button control() {
        Button button = new Button(getContext());
        button.setBackground(ui.pressable(12));
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        addView(button);
        return button;
    }

    void setOnMascotClickListener(OnClickListener listener) {
        mascotControl.setOnClickListener(listener);
    }

    void setOnCountClickListener(Consumer<DecisionLog.Tally> listener) {
        onCount = listener;
    }

    /** Programmatic mascot activation remains the same as its own native button. */
    @Override public boolean performClick() { return mascotControl.performClick(); }

    Button mascotControl() { return mascotControl; }
    Button countControl(DecisionLog.Tally tally) { return countControls[tally.ordinal()]; }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        layoutControls();
    }

    private void layoutControls() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        float scale = scale();
        float art = ui.dp(ART_HEIGHT_DP) * scale;
        float x = placed ? mascotX : sideBySide() ? art / 2f : getWidth() / 2f;
        float y = placed ? mascotY : (sideBySide() ? (getHeight() - art) / 2f : artTop(scale))
                + ui.dp(RING_MIDDLE_DP) * scale;
        float radius = placed ? mascotRadius : ui.dp(RING_OUTER_DP) * scale;
        float reach = Math.max(ui.dp(24), radius);
        layoutControl(mascotControl, new RectF(x - reach, y - reach, x + reach, y + reach));
        RectF counts = new RectF();
        float spacing = countsAt(counts);
        // Midpoints between the drawn columns divide the targets, including their totals.
        for (int i = 0; i < countControls.length; i++) {
            float left = i == 0 ? counts.left : counts.centerX() + (i - 1.5f) * spacing;
            float right = i == 2 ? counts.right : counts.centerX() + (i - 0.5f) * spacing;
            layoutControl(countControls[i], new RectF(left, counts.top, right, counts.bottom));
        }
    }

    private void layoutControl(View control, RectF box) {
        int left = Math.max(0, Math.round(box.left)), top = Math.max(0, Math.round(box.top));
        int right = Math.min(getWidth(), Math.round(box.right));
        int bottom = Math.min(getHeight(), Math.round(box.bottom));
        control.measure(MeasureSpec.makeMeasureSpec(Math.max(0, right - left), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, bottom - top), MeasureSpec.EXACTLY));
        control.layout(left, top, right, bottom);
    }

    State state() {
        return state;
    }

    /**
     * Draws the mascot's ring, of outer {@code radius}, around ({@code x}, {@code y}), and the counts in {@code counts}
     * with their columns {@code spacing} apart, all in this view's pixels. Touches elsewhere in the view pass through.
     */
    void place(float x, float y, float radius, RectF counts, float spacing) {
        boolean moved = !placed || x != mascotX || y != mascotY || radius != mascotRadius
                || !counts.equals(countsBox) || spacing != countsSpacing;
        placed = true;
        mascotX = x;
        mascotY = y;
        mascotRadius = radius;
        countsBox.set(counts);
        countsSpacing = spacing;
        if (moved) {
            layoutControls();
            invalidate();
        }
    }

    /** Back to drawing the mascot with its counts beside or under it, filling the view. */
    void unplace() {
        if (!placed) return;
        placed = false;
        layoutControls();
        invalidate();
    }

    boolean placed() {
        return placed;
    }

    /** Where the placed mascot's middle is, in this view's pixels. */
    float mascotX() {
        return mascotX;
    }

    float mascotY() {
        return mascotY;
    }

    /** The placed mascot's ring, outer edge, from its middle. */
    float mascotRadius() {
        return mascotRadius;
    }

    /** How tall the counts are: the heading, the numbers and the totals under them. */
    float countsHeight() {
        return ui.dp(COUNTS_HEIGHT_DP);
    }

    /** How far apart the counts' three columns stand in {@code room} of width. */
    float countsSpacing(float room) {
        return Math.min(ui.dp(96), room * 0.3f);
    }

    /** How wide the three counts are with their columns {@code spacing} apart. */
    float countsWidth(float spacing) {
        return 2 * spacing + ui.dp(64);
    }

    /** What a tap does, in words for screen readers. */
    void setAction(String action) {
        this.action = action;
        describe();
    }

    String action() {
        return action;
    }

    /** How much of the ring has drawn itself, 0 to 1; at once when Android's animations are off. */
    float ringDrawn() {
        return state == State.OFF ? 1f : Motion.settle(ringFrom, RING_DRAW_MS);
    }

    Mascot.Mood mood() {
        return Mascot.moodOf(state);
    }

    /**
     * @param dash       passed, filtered and review counts for the dash named by {@code dashLabel}
     * @param totals     the same for every offer since the history was cleared
     * @param dashLabel  "This dash", "Last dash" or "No dash yet"
     */
    void set(State state, int[] dash, int[] totals, String dashLabel) {
        if (state != this.state) ringFrom = SystemClock.uptimeMillis();
        this.state = state;
        this.passed = dash[0];
        this.filtered = dash[1];
        this.review = dash[2];
        this.totals = totals.clone();
        this.dashLabel = dashLabel;
        describe();
        invalidate();
    }

    void setWatching(boolean on) {
        if (on == watching) return;
        watching = on;
        describe();
    }

    private void describe() {
        String mode = state == State.ON ? "Auto-decline on" : state == State.PAUSED ? "Auto-decline paused"
                : "Auto-decline off";
        setContentDescription(String.format(Locale.US,
                "%s%s. %s: %d offers, %d passed, %d filtered, %d to review. In all: %d passed, %d filtered, %d to review.",
                mode, watching ? ", watching for offers" : "", dashLabel, passed + filtered + review, passed, filtered,
                review, totals[0], totals[1], totals[2]));
        mascotControl.setContentDescription(mode + (watching ? ", watching for offers" : "") + ". "
                + (action == null ? "" : action + "."));
        int[] counts = {passed, filtered, review};
        String[] names = {"passed offers", "filtered offers", "offers left to review"};
        String[] actions = {"Show latest passed offer", "Show latest filtered offer", "Show latest offer left to review"};
        for (int i = 0; i < countControls.length; i++) {
            countControls[i].setContentDescription(dashLabel + ": " + counts[i] + " " + names[i]
                    + ". " + totals[i] + " total. " + actions[i] + ".");
        }
    }

    private float designWidth(float width) {
        return Math.max(width, ui.dp(DESIGN_WIDTH_DP));
    }

    /**
     * As tall as the design at this width, or whatever the page gives it on one screen; asked with no limit (the
     * page working out what fits), it answers the least it reads well at.
     */
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        float scale = width / designWidth(width);
        int design = Math.round(ui.dp(ART_HEIGHT_DP) * scale) + ui.dp(COUNTS_HEIGHT_DP);
        // Side by side, the drawing and the counts share one short row.
        int least = Math.max(ui.dp(COUNTS_HEIGHT_DP) + ui.dp(16),
                Math.round(ui.dp(ART_HEIGHT_DP) * scale * MIN_SHARE));
        int height = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED ? least
                : resolveSize(design, heightSpec);
        setMeasuredDimension(width, height);
    }

    /**
     * Wide and short (a phone's one-screen page), the drawing stands at the left with the counts beside it; tall
     * enough, the counts sit under it.
     */
    boolean sideBySide() {
        return getHeight() > 0 && getHeight() < ui.dp(ART_HEIGHT_DP) * 0.8f + ui.dp(COUNTS_HEIGHT_DP)
                && getWidth() >= getHeight() * 1.9f;
    }

    /** The drawing's scale: the design fitted to the width, and to the height left above (or beside) the counts. */
    private float scale() {
        float byWidth = getWidth() / designWidth(getWidth());
        if (getHeight() <= 0) return byWidth;
        if (sideBySide()) return Math.min(getHeight() / (float) ui.dp(ART_HEIGHT_DP), getWidth() * 0.44f / ui.dp(ART_HEIGHT_DP));
        float byHeight = (getHeight() - ui.dp(COUNTS_HEIGHT_DP)) / (float) ui.dp(ART_HEIGHT_DP);
        return Math.max(0.2f, Math.min(byWidth, byHeight));
    }

    /** Where the drawing starts, so the drawing and the counts sit centred in the height given. */
    private float artTop(float scale) {
        return Math.max(0, (getHeight() - ui.dp(ART_HEIGHT_DP) * scale - ui.dp(COUNTS_HEIGHT_DP)) / 2);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (placed) {
            drawPlaced(canvas);
            if (state != State.OFF) Motion.next(this);
            return;
        }
        float scale = scale();
        boolean side = sideBySide();
        float artSize = ui.dp(ART_HEIGHT_DP) * scale;
        // Side by side, the drawing's own width is one design height, centred in its square at the left.
        float designWidth = side ? ui.dp(ART_HEIGHT_DP) : getWidth() / scale;
        float artTop = side ? (getHeight() - artSize) / 2 : artTop(scale);
        canvas.save();
        canvas.translate(0, artTop);
        canvas.scale(scale, scale);
        float cx = designWidth / 2;
        float spacing = countsAt(countsDrawn);
        // Tilting the phone slides the ring and its stars (far) against an offer's ticket (near).
        slide(canvas, -4);
        drawRing(canvas, cx);
        drawTwinkles(canvas, cx, false, 0, artTop, scale);
        canvas.restore();
        drawOfferPlayed(canvas, cx, true);
        drawMascot(canvas, cx);
        drawOfferPlayed(canvas, cx, false);
        canvas.restore();
        drawCounts(canvas, countsDrawn.centerX(), spacing, countsDrawn.top);
        if (state != State.OFF) Motion.next(this);
    }

    /**
     * Where the counts stand, in this view's pixels, into {@code out}; answers how far apart their columns are. Placed,
     * where the sky put them; otherwise beside the drawing (wide and short) or under it. They keep the page's text size
     * however small the drawing is.
     */
    float countsAt(RectF out) {
        if (placed) {
            out.set(countsBox);
            return countsSpacing;
        }
        float scale = scale();
        boolean side = sideBySide();
        float artSize = ui.dp(ART_HEIGHT_DP) * scale;
        float x = side ? artSize + (getWidth() - artSize) / 2f : getWidth() / 2f;
        float spacing = countsSpacing(side ? getWidth() - artSize : getWidth());
        float top = side ? (getHeight() - ui.dp(COUNTS_HEIGHT_DP)) / 2f : artTop(scale) + artSize;
        float wide = countsWidth(spacing);
        out.set(x - wide / 2, top, x + wide / 2, top + countsHeight());
        return spacing;
    }

    /**
     * Placed in the sky: the ring, the mascot and its ticket scaled to the ring's radius around its middle; the few
     * stars that twinkle around it only on the side away from the constellation; the counts where they were placed.
     */
    private void drawPlaced(Canvas canvas) {
        float scale = mascotRadius / ui.dp(RING_OUTER_DP);
        countsDrawn.set(countsBox);
        canvas.save();
        canvas.translate(mascotX, mascotY);
        canvas.scale(scale, scale);
        canvas.translate(0, -ui.dp(RING_MIDDLE_DP));
        slide(canvas, -4);
        drawRing(canvas, 0);
        drawTwinkles(canvas, 0, true, mascotX, mascotY - ui.dp(RING_MIDDLE_DP) * scale, scale);
        canvas.restore();
        drawOfferPlayed(canvas, 0, true);
        drawMascot(canvas, 0);
        drawOfferPlayed(canvas, 0, false);
        canvas.restore();
        drawCounts(canvas, countsBox.centerX(), countsSpacing, countsBox.top);
    }

    private void slide(Canvas canvas, float depth) {
        canvas.save();
        canvas.translate(Tilt.x() * ui.dp(depth), Tilt.y() * ui.dp(depth) * 0.6f);
    }

    private int stateColor() {
        return state == State.ON ? ui.accent : state == State.PAUSED ? Ui.WARNING : ui.inkMuted;
    }

    /** The brush-drawn ring the mascot stands in, over a faint wash; the brush breathes a little while on. */
    private void drawRing(Canvas canvas, float cx) {
        float cy = ui.dp(RING_MIDDLE_DP);
        float radius = ui.dp(82);
        if (state == State.OFF) {
            line.setPathEffect(dotted);
            line.setColor(withAlpha(ui.inkMuted, 0x80));
            line.setStrokeWidth(Math.max(1, ui.dp(1.5f)));
            canvas.drawCircle(cx, cy, radius, line);
            line.setPathEffect(null);
            return;
        }
        fill.setColor(withAlpha(stateColor(), 0x12));
        canvas.drawCircle(cx, cy, radius - ui.dp(9), fill);
        float breathe = state == State.ON ? 1 + 0.06f * Motion.wave(6f, 0) : 1;
        ring.draw(canvas, withAlpha(stateColor(), state == State.ON ? 0xD9 : 0xBF), ui.page, cx, cy, radius,
                ui.dp(9) * breathe, state == State.ON ? ON_SWEEP : PAUSED_SWEEP, ringDrawn());
    }

    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    /**
     * Small stars around the ring that brighten and fade in turn; none while off, and none on the counts. With
     * {@code leftOnly}, only those on its left, so none is taken for one of the constellation's sparkles on its right.
     * The drawing is scaled by {@code scale} from ({@code x}, {@code y}) in this view.
     */
    private void drawTwinkles(Canvas canvas, float cx, boolean leftOnly, float x, float y, float scale) {
        if (state == State.OFF) return;
        int color = ui.dark ? 0xFFE9E2C8 : 0xFFE0B94F;
        float margin = ui.dp(6);
        for (int i = 0; i < TWINKLES.length; i++) {
            float[] star = TWINKLES[i];
            if (leftOnly && star[0] > 0) continue;
            float atX = x + (cx + ui.dp(star[0])) * scale;
            float atY = y + ui.dp(star[1]) * scale;
            if (atX > countsDrawn.left - margin && atX < countsDrawn.right + margin
                    && atY > countsDrawn.top - margin && atY < countsDrawn.bottom + margin) {
                continue;
            }
            float twinkle = 0.5f + 0.5f * Motion.wave(2.4f + i * 0.45f, i * 0.23f);
            fill.setColor(withAlpha(color, (int) (40 + 150 * twinkle)));
            sparkle(canvas, cx + ui.dp(star[0]), ui.dp(star[1]), ui.dp(star[2]) * (0.55f + 0.45f * twinkle));
        }
    }

    private void sparkle(Canvas canvas, float x, float y, float half) {
        float waist = half * 0.22f;
        path.reset();
        path.moveTo(x, y - half);
        path.quadTo(x + waist, y - waist, x + half, y);
        path.quadTo(x + waist, y + waist, x, y + half);
        path.quadTo(x - waist, y + waist, x - half, y);
        path.quadTo(x - waist, y - waist, x, y - half);
        path.close();
        canvas.drawPath(path, fill);
    }

    /**
     * Plays out an offer just decided, as it went: its ticket drops in under a small parachute and passes out of the
     * spout (passed), bounces off the sieve (declined), or rests on it (left for review, or to the user), with the
     * skyline's badge for it. Only while on and with animations on; otherwise nothing moves.
     */
    void showOffer(DecisionLog.Outcome outcome) {
        if (outcome == null || state != State.ON || !Motion.on()) return;
        offer = outcome;
        offerFrom = SystemClock.uptimeMillis();
        invalidate();
    }

    /** The offer being played out now, or null (for tests). */
    DecisionLog.Outcome playing() {
        return offerProgress() < 0 ? null : offer;
    }

    /** How far through its playing the offer is (0–1), or -1 for none. */
    private float offerProgress() {
        if (offer == null || state != State.ON) return -1;
        long since = SystemClock.uptimeMillis() - offerFrom;
        return since < 0 || since >= OFFER_MS ? -1 : since / (float) OFFER_MS;
    }

    /**
     * The offer being played out: the parts the funnel hides ({@code behind}: a ticket going through), or those in
     * front of it (a ticket on the sieve, the badge). Its last part fades.
     */
    private void drawOfferPlayed(Canvas canvas, float cx, boolean behind) {
        float t = offerProgress();
        if (t < 0) return;
        slide(canvas, 8);
        float rimY = ui.dp(72);
        float onSieve = rimY - ui.dp(9);
        float land = 0.32f;
        boolean through = offer == DecisionLog.Outcome.PASSED || offer == DecisionLog.Outcome.ACCEPTED;
        int fade = (int) (255 * Math.min(1f, (1f - t) / 0.18f));
        if (t < land) {
            // Down under its parachute onto the sieve.
            if (!behind) {
                float p = easeOut(t / land);
                float sway = (1 - p) * Motion.wave(3.5f, 0);
                drawTicket(canvas, cx + ui.dp(6) * sway, lerp(ui.dp(16), onSieve, p), 255, 1 - p, 1f, 5 * sway);
            }
        } else {
            float p = (t - land) / (1 - land);
            if (through) {
                // Through the sieve (hidden by the funnel), out of the spout to the ground beneath it, and the badge.
                float sink = p / 0.16f;
                if (behind && sink < 1) {
                    drawTicket(canvas, cx, lerp(onSieve, rimY + ui.dp(24), sink * sink), 255, 0, 1f, 0);
                }
                float out = (p - 0.2f) / 0.35f;
                if (out >= 0) {
                    float y = lerp(ui.dp(154), ui.dp(170), easeOut(Math.min(1f, out)));
                    if (behind) drawTicket(canvas, cx, y, fade, 0, 0.75f, 0);
                    else drawBadgePopping(canvas, cx + ui.dp(24), y - ui.dp(4), p - 0.45f, fade);
                }
            } else if (offer == DecisionLog.Outcome.DECLINED) {
                // Caught: it bounces off the sieve and away, the badge where it landed.
                if (!behind) {
                    float b = Math.min(1f, p / 0.7f);
                    float y = onSieve - ui.dp(44) * (float) Math.sin(b * Math.PI * 0.8f) + ui.dp(24) * b * b;
                    drawTicket(canvas, cx + ui.dp(58) * b, y, (int) (255 * (1 - b)), 0, 1f - 0.3f * b, 55 * b);
                    drawBadgePopping(canvas, cx, rimY - ui.dp(28), p, fade);
                }
            } else if (!behind) {
                // Left on the sieve for the dasher.
                drawTicket(canvas, cx, onSieve, fade, 0, 1f, 0);
                drawBadgePopping(canvas, cx + ui.dp(19), onSieve - ui.dp(11), p - 0.05f, fade);
            }
        }
        canvas.restore();
    }

    /** The offer's badge, {@code since} (a share of the rest of the play) after it appears: it pops, then settles. */
    private void drawBadgePopping(Canvas canvas, float x, float y, float since, int alpha) {
        if (since < 0) return;
        float grow = Math.min(1f, since / 0.12f);
        float scale = grow < 1 ? grow * 1.15f : 1.15f - 0.15f * Math.min(1f, (since - 0.12f) / 0.1f);
        canvas.save();
        canvas.scale(scale, scale, x, y);
        OutcomeBadge.draw(canvas, ui, x, y, offer, alpha);
        canvas.restore();
    }

    /**
     * An offer ticket at ({@code x}, {@code y}): a little card with "$", under a parachute shown at {@code chute}
     * (0–1), at {@code alpha}, scaled and turned by {@code turn} degrees about its middle.
     */
    private void drawTicket(Canvas canvas, float x, float y, int alpha, float chute, float scale, float turn) {
        if (alpha <= 0) return;
        canvas.save();
        canvas.rotate(turn, x, y);
        canvas.scale(scale, scale, x, y);
        line.setPathEffect(null);
        line.setStrokeWidth(Math.max(1, ui.dp(1)));
        if (chute > 0) {
            int shown = (int) (alpha * chute);
            line.setColor(withAlpha(ui.baseline, shown));
            float dome = y - ui.dp(22);
            canvas.drawLine(x - ui.dp(12), dome, x - ui.dp(10), y - ui.dp(8), line);
            canvas.drawLine(x + ui.dp(12), dome, x + ui.dp(10), y - ui.dp(8), line);
            rect.set(x - ui.dp(13), dome - ui.dp(13), x + ui.dp(13), dome + ui.dp(13));
            fill.setColor(withAlpha(ui.dark ? 0xFF3D5A85 : 0xFFA9C8F2, shown));
            canvas.drawArc(rect, 180, 180, true, fill);
        }
        rect.set(x - ui.dp(15), y - ui.dp(8), x + ui.dp(15), y + ui.dp(8));
        fill.setColor(withAlpha(ui.surface, alpha));
        canvas.drawRoundRect(rect, ui.dp(3), ui.dp(3), fill);
        line.setColor(withAlpha(ui.baseline, alpha));
        canvas.drawRoundRect(rect, ui.dp(3), ui.dp(3), line);
        text.setFakeBoldText(true);
        text.setTextSize(ui.dp(9));
        text.setColor(withAlpha(ui.inkSecondary, alpha));
        canvas.drawText("$", x, y + ui.dp(3), text);
        canvas.restore();
    }

    private static float easeOut(float t) {
        return 1 - (1 - t) * (1 - t);
    }

    /**
     * The mascot: a glazed funnel, lit from the upper left with a shine down its left side, whose rim shows its inside
     * (a sieve while on), narrowing to a collar and a rounded spout, with mittened arms and a face, over a soft
     * shadow. While on, now and then an arm waves.
     */
    private void drawMascot(Canvas canvas, float cx) {
        int color = stateColor();
        boolean off = state == State.OFF;
        float breathe = off ? 0 : Motion.wave(4.5f, 0);
        // Pressed, the mascot squishes down a little, like a button.
        float squish = mascotControl.isPressed() ? 0.93f : 1f;
        float rimY = ui.dp(72);
        float half = ui.dp(62);
        float neckY = ui.dp(146);
        float neck = ui.dp(11);
        float spoutY = ui.dp(164);
        float groundY = ui.dp(177);

        // Its shadow, a little narrower as it breathes in.
        float shadow = ui.dp(24) * squish * (1 - 0.04f * breathe);
        rect.set(cx - shadow, groundY - ui.dp(3.5f), cx + shadow, groundY + ui.dp(3.5f));
        fill.setShader(null);
        fill.setColor(ui.dark ? 0x59000000 : withAlpha(ui.ink, 0x14));
        canvas.drawOval(rect, fill);

        canvas.save();
        canvas.scale((1 + 0.012f * breathe) * squish, (1 + 0.012f * breathe) * squish, cx, ui.dp(150));
        float armY = ui.dp(104);
        float side = half - (half - neck) * (armY - rimY) / (neckY - rimY);
        int glint = blend(color, 0xFFFFFFFF, 0.55f);
        float wave = waveAngle();
        drawArm(canvas, cx - side, armY, -1, 0, color, glint);
        if (wave == 0) drawArm(canvas, cx + side, armY, 1, 0, color, glint);

        float round = ui.dp(3.5f);
        path.reset();
        path.moveTo(cx - half, rimY);
        path.lineTo(cx - neck, neckY);
        path.lineTo(cx - neck, spoutY - round);
        path.quadTo(cx - neck, spoutY, cx - neck + round, spoutY);
        path.lineTo(cx + neck - round, spoutY);
        path.quadTo(cx + neck, spoutY, cx + neck, spoutY - round);
        path.lineTo(cx + neck, neckY);
        path.lineTo(cx + half, rimY);
        path.close();
        fill.setShader(bodyGlaze(cx, half, color, off));
        canvas.drawPath(path, fill);
        fill.setShader(null);
        // The shine: a long stroke down the left side, a gap, a dot.
        int shine = withAlpha(0xFFFFFFFF, ui.dark ? (off ? 0x1C : 0x30) : (off ? 0x99 : 0xC8));
        line.setPathEffect(null);
        line.setColor(shine);
        line.setStrokeWidth(ui.dp(3.5f));
        canvas.drawLine(shineX(cx, half, neck, 0.13f), lerp(rimY, neckY, 0.13f),
                shineX(cx, half, neck, 0.40f), lerp(rimY, neckY, 0.40f), line);
        fill.setColor(shine);
        canvas.drawCircle(shineX(cx, half, neck, 0.51f), lerp(rimY, neckY, 0.51f), ui.dp(1.9f), fill);
        line.setColor(color);
        line.setStrokeWidth(ui.dp(2.5f));
        line.setPathEffect(state == State.PAUSED ? pausedDash : null);
        canvas.drawPath(path, line);
        line.setPathEffect(null);

        // The collar where the body meets the spout.
        rect.set(cx - neck - ui.dp(2.5f), neckY - ui.dp(1.5f), cx + neck + ui.dp(2.5f), neckY + ui.dp(4.5f));
        fill.setColor(blend(ui.surface, color, off ? 0.2f : 0.38f));
        canvas.drawRoundRect(rect, ui.dp(3), ui.dp(3), fill);
        line.setStrokeWidth(ui.dp(2));
        canvas.drawRoundRect(rect, ui.dp(3), ui.dp(3), line);
        // A waving arm is in front of the body.
        if (wave != 0) drawArm(canvas, cx + side, armY, 1, wave, color, glint);

        // The rim, its inside darker toward the near wall.
        rect.set(cx - half, rimY - ui.dp(12), cx + half, rimY + ui.dp(12));
        fill.setShader(rimDepth(rimY, color, off));
        canvas.drawOval(rect, fill);
        fill.setShader(null);
        if (state == State.ON) {
            fill.setColor(withAlpha(color, 0xA0));
            for (int row = -1; row <= 1; row++) {
                for (int col = -4; col <= 4; col++) {
                    float x = cx + col * ui.dp(12) + (row == 0 ? ui.dp(6) : 0);
                    float y = rimY + row * ui.dp(5);
                    float dx = (x - cx) / (half - ui.dp(9));
                    float dy = (y - rimY) / ui.dp(8);
                    if (dx * dx + dy * dy <= 1) canvas.drawCircle(x, y, ui.dp(1.6f), fill);
                }
            }
        }
        line.setStrokeWidth(ui.dp(2.5f));
        line.setPathEffect(state == State.PAUSED ? pausedDash : null);
        canvas.drawOval(rect, line);
        line.setPathEffect(null);
        // A glint on the far lip.
        line.setColor(shine);
        line.setStrokeWidth(ui.dp(2));
        rect.inset(ui.dp(5), ui.dp(3.5f));
        canvas.drawArc(rect, 208, 34, false, line);

        // A blink every few seconds while awake.
        boolean blink = state == State.ON && Motion.on() && Motion.loop(5.3f, 0) > 0.965f;
        Mascot.Mood face = wave != 0 ? Mascot.Mood.CHEER : blink ? Mascot.Mood.BLINK : mood();
        Mascot.face(canvas, face, cx, ui.dp(110), ui.dp(40), off ? ui.inkMuted : ui.ink);
        canvas.restore();

        if (state == State.PAUSED) {
            float t = Motion.on() ? Motion.loop(3.2f, 0) : 0.3f;
            text.setFakeBoldText(true);
            text.setColor(withAlpha(ui.inkSecondary, (int) (255 * (1 - t))));
            text.setTextSize(ui.dp(12) + ui.dp(6) * t);
            canvas.drawText("z", cx + ui.dp(36) + ui.dp(10) * t, ui.dp(92) - ui.dp(28) * t, text);
        }
    }

    /**
     * One arm from the shoulder at ({@code x}, {@code y}), out to {@code dir}'s side, turned {@code lift} degrees, a
     * round mitt with a {@code glint} at its end.
     */
    private void drawArm(Canvas canvas, float x, float y, int dir, float lift, int color, int glint) {
        canvas.save();
        if (lift != 0) canvas.rotate(lift, x, y);
        line.setPathEffect(null);
        line.setColor(color);
        line.setStrokeWidth(ui.dp(3));
        path.reset();
        path.moveTo(x, y);
        path.quadTo(x + dir * ui.dp(13), y + ui.dp(5), x + dir * ui.dp(16), y + ui.dp(18));
        canvas.drawPath(path, line);
        float handX = x + dir * ui.dp(16.5f);
        float handY = y + ui.dp(21.5f);
        fill.setColor(color);
        canvas.drawCircle(handX, handY, ui.dp(4.2f), fill);
        fill.setColor(glint);
        canvas.drawCircle(handX - ui.dp(1.3f), handY - ui.dp(1.3f), ui.dp(1.3f), fill);
        canvas.restore();
    }

    /**
     * Now and then while on, the right arm lifts and waves for about a second and a half (the face beaming): its turn
     * in degrees, 0 at rest and always with animations off.
     */
    private float waveAngle() {
        if (state != State.ON || !Motion.on()) return 0;
        float t = Motion.loop(11f, 0.6f);
        float span = 0.13f;
        if (t > span) return 0;
        float p = t / span;
        float up = (float) Math.sin(p * Math.PI);
        return -up * (75 + 15 * (float) Math.sin(p * Math.PI * 6));
    }

    /** Where the shine runs, a share {@code at} of the way down the body's left side, a little inside it. */
    private float shineX(float cx, float half, float neck, float at) {
        return cx - lerp(half, neck, at) + ui.dp(9) * (1 - 0.35f * at);
    }

    /** The body's glaze: lighter on the left, deeper on the right; made again only when its colour or place change. */
    private Shader bodyGlaze(float cx, float half, int color, boolean off) {
        if (bodyShade == null || bodyShadeColor != color || bodyShadeX != cx) {
            bodyShade = new LinearGradient(cx - half, 0, cx + half, 0, blend(ui.surface, color, off ? 0.05f : 0.09f),
                    blend(ui.surface, color, off ? 0.14f : 0.30f), Shader.TileMode.CLAMP);
            bodyShadeColor = color;
            bodyShadeX = cx;
        }
        return bodyShade;
    }

    /** The rim's inside: the far wall lighter, the near wall darker. */
    private Shader rimDepth(float rimY, int color, boolean off) {
        if (rimShade == null || rimShadeColor != color) {
            rimShade = new LinearGradient(0, rimY - ui.dp(12), 0, rimY + ui.dp(12),
                    blend(ui.surface, color, off ? 0.08f : 0.16f), blend(ui.surface, color, off ? 0.2f : 0.42f),
                    Shader.TileMode.CLAMP);
            rimShadeColor = color;
        }
        return rimShade;
    }

    private static float lerp(float from, float to, float at) {
        return from + (to - from) * at;
    }

    /** {@code from} moved a share {@code at} of the way to {@code to}, opaque. */
    private static int blend(int from, int to, float at) {
        int r = Math.round(lerp((from >> 16) & 0xFF, (to >> 16) & 0xFF, at));
        int g = Math.round(lerp((from >> 8) & 0xFF, (to >> 8) & 0xFF, at));
        int b = Math.round(lerp(from & 0xFF, to & 0xFF, at));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * "THIS DASH · 6" over three counts, each a badge (✓ ✕ ?) and its number with, under it, the all-time total.
     * The badges say which is which; the words are in the description screen readers read.
     */
    private void drawCounts(Canvas canvas, float cx, float spacing, float top) {
        text.setFakeBoldText(true);
        text.setTextSize(Math.min(ui.sp(10), ui.dp(14)));
        text.setLetterSpacing(0.12f);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(ui.inkMuted);
        int offers = passed + filtered + review;
        String heading = dashLabel.startsWith("No") ? dashLabel : dashLabel + " · " + offers;
        canvas.drawText(heading.toUpperCase(Locale.US), cx, top + ui.dp(8) - text.getFontMetrics().ascent / 2, text);
        text.setLetterSpacing(0);
        float row = top + ui.dp(30);
        drawCount(canvas, cx - spacing, row, OfferRule.Result.KEEP, passed, totals[0], state != State.OFF);
        drawCount(canvas, cx, row, OfferRule.Result.DECLINE, filtered, totals[1], state == State.ON);
        drawCount(canvas, cx + spacing, row, OfferRule.Result.REVIEW, review, totals[2], state != State.OFF);
    }

    private void drawCount(Canvas canvas, float x, float top, OfferRule.Result result, int count, int total,
                           boolean live) {
        int color = Ui.resultColor(result);
        // The number and its word size with the font setting, shrunk only as far as their space needs.
        text.setFakeBoldText(true);
        float numberSize = Math.min(ui.sp(18), ui.dp(26));
        text.setTextSize(numberSize);
        String number = Integer.toString(count);
        float badge = ui.dp(8);
        float numberWidth = text.measureText(number);
        float rowWidth = badge * 2 + ui.dp(6) + numberWidth;
        float left = x - rowWidth / 2;
        fill.setColor(withAlpha(color, live ? 0xFF : 0x60));
        canvas.drawCircle(left + badge, top, badge, fill);
        Paint.FontMetrics metrics = text.getFontMetrics();
        text.setColor(live ? ui.ink : ui.inkMuted);
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(number, left + badge * 2 + ui.dp(6), top - (metrics.ascent + metrics.descent) / 2, text);
        text.setTextSize(ui.dp(9));
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(withAlpha(Ui.onStatus(color), live ? 0xFF : 0xB0));
        canvas.drawText(Ui.resultSymbol(result), left + badge, top + text.getTextSize() / 3, text);
        text.setFakeBoldText(false);
        text.setTextSize(Math.min(ui.sp(11), ui.dp(15)));
        text.setColor(ui.inkMuted);
        canvas.drawText(total + " total", x, top + ui.dp(24) + text.getTextSize() / 2, text);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }
}
