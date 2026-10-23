package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * The site's address and the latest offer at the top of the homepage, beside the mascot (0.6.3, as a label and a
 * badge, not a sentence; the owner, 12 October 2026: "that sentence needs to be removed at all sizes and replaced with
 * more thoughtful, concise (either via label and or graphic) expression"). From 0.6.38 its first line is the address,
 * {@value #BRAND}, in bold where the pay was (the owner, 23 October 2026: "right beside the mascot logo in the place
 * the offer value is now we need OfferFilter.org printed"), so anyone who sees the screen, or a picture of it, learns
 * where the app comes from. The latest offer moved to the line under it, nothing of it dropped. Two lines in a box of
 * one fixed size:
 * <ul>
 * <li>the address, at the size the pay had (24 sp, the owner's mockup), from the badge's left edge;</li>
 * <li>the outcome's badge (the skyline's, {@link OutcomeBadge}), the pay in bold, a short tag only where the badge
 * alone would leave the outcome unsaid ("below min" in Autopilot's purple; "auto"; "requested" in amber), then the
 * trip, "2.1 mi · 13 min", how long ago, "now", "12m", "3h", "4d", and its stops when the offer is the stored route's
 * (whose road marks the badge's lower right), each only where it fits.</li>
 * </ul>
 * An unread figure shows as unread ("$?", "? mi", "? min" in the muted ink), never left out or guessed. With no offer
 * yet: a dashed ring and "Waiting for offers" (everything ready) or "No offers yet" under the address.
 *
 * <p>Its size is its own, never its content's or the clock's: as tall as the address's line and the offer's at the
 * size they are drawn ({@link #heightFor}: 48 dp at the normal font), as wide as the page gives it. Where the offer's
 * line is too narrow for everything, the tag goes, then the pay and the trip condense to four fifths of their width,
 * then the trip's end is cut; the age and the route's stops come only where they fit as the line is drawn, the age
 * first. The pay is never cut, and nor is the address: past four fifths of its width, each is drawn smaller. The words
 * are drawn at the user's size, at a large font a little smaller (never below three quarters of it nor below the
 * default size, as a setup step's words), so the tile keeps beside the mascot in a short window.
 *
 * <p>Screen readers hear exactly what the sentence said ({@link MainActivity#verdict}, then its reason) and "Open offer
 * details", then the route's words when the offer is the stored route's: a button whose tap opens the offer's ticket
 * (the page that holds it says how, {@link #setOnClickListener}). They hear the app's name already from the page's
 * heading, so the address is not read out. With no offer it is only its words and takes no tap. It is no live region:
 * an offer coming in never announces itself here.
 */
@SuppressLint("ViewConstructor")
final class VerdictTile extends View {
    /** What a tap does, as screen readers hear it (and the end of what they hear). */
    static final String ACTION = "Open offer details";
    /** The address on the first line (0.6.38), as the owner wrote it. */
    static final String BRAND = "OfferFilter.org";
    /** No offer yet, everything ready to filter. */
    static final String WAITING = "Waiting for offers";
    /** No offer yet, something not ready. */
    static final String NO_OFFERS = "No offers yet";
    /** The tags: passed only by Autopilot's bar below the minimums, accepted by the app, its Accept only requested. */
    static final String TAG_BELOW = "below min";
    static final String TAG_AUTO = "auto";
    static final String TAG_REQUESTED = "requested";
    /** The outcome's words (MainActivity.captionOutcome) for an offer the app accepted. */
    static final String AUTOMATICALLY_ACCEPTED = "Automatically accepted";

    /** The words' sizes at the normal font: the address as the pay was until 0.6.37, the offer's line all alike. */
    static final float BRAND_SP = 24;
    private static final float LINE_SP = 14;
    /** The tile is never shorter than a touch target. */
    private static final float LEAST_DP = 48;
    /** The address from here; the badge's middle; the offer's words (and the words with no offer) from here. */
    private static final float BRAND_X_DP = 6;
    private static final float BADGE_X_DP = 14;
    private static final float TEXT_X_DP = 28;
    /** Between the address's line and the offer's; before a tag. */
    private static final float LINE_GAP_DP = 2;
    private static final float TAG_GAP_DP = 5;
    /** A dot's or the road's middle this far right of the badge's, and up or down. */
    private static final float PIP_OFFSET_DP = 5;
    private static final float ROAD_PIP_DP = 8;
    /** The no-offer ring's radius. */
    private static final float RING_DP = 6;
    /** The narrowest the words condense to (of their width) before anything else gives way. */
    private static final float LEAST_CONDENSED = 0.8f;
    private static final String SEPARATOR = " · ";
    /** What the tile's width is worked out for: the widest pay and age of an ordinary offer, and its longest trip. */
    private static final String TEMPLATE_PAY = "$88.88";
    private static final String TEMPLATE_TRIP = "88.8 mi · 88 min";
    /** The widest an age is in its first two days; its room is kept the widest of them. */
    private static final String[] AGES = {"59m", "47h", "13d", "now"};
    /** The parts of the offer's line, by their paint. */
    private static final int PAY = 0;
    private static final int TAG = 1;
    private static final int TRIP = 2;

    private final Ui ui;
    /** The words at the size the tile draws them, never condensed: what the fitting measures with. */
    private final TextPaint brandPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint payPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tagPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tripPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint emptyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    /** The words as drawn now (condensed, or the address and the pay smaller still, where the width asks). */
    private final TextPaint brandDrawn = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint payDrawn = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tagDrawn = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tripDrawn = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int tall;
    private final int templateWide;
    private final float ageRoom;

    /** What is shown: the offer (null: none yet), and the key it was worked out for. */
    private DecisionLog.Entry entry;
    private String shownKey;
    private boolean ready;
    private String said;
    private String outcome;
    private int badgeColor;
    private OutcomeBadge.Symbol symbol;
    private int pipColor;
    private boolean onRoute;
    private String pay;
    private int payColor;
    private String tag;
    private int tagColor;
    private String age;
    /** The trip's labels (miles, minutes, and the route's stops), each in its own ink. */
    private final String[] trip = new String[3];
    private final int[] tripColor = new int[3];
    private int tripLabels;
    /** The fitting, for the width it was worked out for. */
    private int arrangedWidth = -1;
    private boolean tagShown;
    private boolean ageShown;
    /** The offer's line as drawn: the pay, the tag, the trip's labels (the last perhaps cut), the dots, each placed. */
    private final String[] lineShown = new String[12];
    private final float[] lineX = new float[12];
    private final int[] lineColor = new int[12];
    private final int[] lineKind = new int[12];
    private int lineParts;
    /** The offer's labels as drawn, for {@link #shownText}. */
    private final String[] labelsShown = new String[6];
    private int labelsShownCount;
    private float brandX;
    private float line1;
    private float line2;
    private float badgeY;
    private String emptyShown;
    private String shownText = "";

    VerdictTile(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        brandPaint.setTypeface(Typeface.DEFAULT_BOLD);
        brandPaint.setTextSize(drawn(metrics, BRAND_SP));
        brandPaint.setColor(ui.ink);
        payPaint.setTypeface(Typeface.DEFAULT_BOLD);
        payPaint.setTextSize(drawn(metrics, LINE_SP));
        tagPaint.setTypeface(Typeface.DEFAULT_BOLD);
        tagPaint.setTextSize(drawn(metrics, LINE_SP));
        tripPaint.setTypeface(Typeface.DEFAULT);
        tripPaint.setTextSize(drawn(metrics, LINE_SP));
        emptyPaint.setTypeface(Ui.MEDIUM);
        emptyPaint.setTextSize(drawn(metrics, LINE_SP));
        emptyPaint.setColor(ui.inkSecondary);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(1, ui.dp(1.5f)));
        ring.setColor(ui.inkSecondary);
        ring.setPathEffect(new DashPathEffect(new float[] {ui.dp(3), ui.dp(2.5f)}, 0));
        float room = 0;
        for (String each : AGES) room = Math.max(room, tripPaint.measureText(each));
        ageRoom = room;
        tall = heightFor(context);
        float dot = tripPaint.measureText(SEPARATOR);
        templateWide = (int) Math.ceil(Math.max(ui.dp(BRAND_X_DP) + brandPaint.measureText(BRAND),
                ui.dp(TEXT_X_DP) + payPaint.measureText(TEMPLATE_PAY) + dot + tripPaint.measureText(TEMPLATE_TRIP)
                        + dot + ageRoom));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_NONE);
        tappable(false);
        show(null, false, 0, null);
    }

    /**
     * {@code sp} as the tile draws it: the user's size, at a large font a little smaller, never below three quarters
     * of it nor below the default size ({@link SetupRow#LEAST_SCALE}, as a setup step's words at their least).
     */
    static float drawn(DisplayMetrics metrics, float sp) {
        float full = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics);
        float unscaled = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, sp, metrics);
        return Math.min(full, Math.max(full * SetupRow.LEAST_SCALE, unscaled));
    }

    /**
     * The tile's one height, in pixels, for {@code context}'s font and density: its address's line and its offer's at
     * the size they are drawn, 2 dp apart, and never less than 48 dp (48 at the normal font, as before 0.6.38 with the
     * pay where the address is). What it shows never changes it, so the page lays out the band with it before anything
     * is shown.
     */
    static int heightFor(Context context) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        TextPaint measuring = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        measuring.setTypeface(Typeface.DEFAULT_BOLD);
        measuring.setTextSize(drawn(metrics, BRAND_SP));
        float brand = Ui.lineHeight(measuring);
        measuring.setTypeface(Typeface.DEFAULT);
        measuring.setTextSize(drawn(metrics, LINE_SP));
        float line = Ui.lineHeight(measuring);
        int least = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, LEAST_DP, metrics));
        float gap = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, LINE_GAP_DP, metrics);
        return Math.max(least, (int) Math.ceil(brand + line + gap));
    }

    /**
     * The width, in pixels, at which the address and an ordinary offer show whole with no tag: the address on its line,
     * and the widest pay ("$88.88"), the longest trip ("88.8 mi · 88 min") and the widest age ("59m") under it.
     */
    int templateWidth() {
        return templateWide;
    }

    /**
     * Shows {@code latest} (null: no offer yet, whose words say whether everything is {@code ready}), how long ago as
     * of {@code now}, and the stored {@code route} (null: none) when it is that offer. Nothing is redrawn, laid out or
     * told to screen readers again while what the tile shows stays the same.
     */
    void show(DecisionLog.Entry latest, boolean ready, long now, OfferSnapshot route) {
        boolean routed = latest != null && route != null && route.fingerprint().equals(latest.facts.fingerprint());
        String ago = latest == null ? null : age(now - latest.at);
        String nextSaid;
        String key;
        if (latest == null) {
            nextSaid = ready ? WAITING : NO_OFFERS;
            key = nextSaid;
        } else {
            nextSaid = MainActivity.verdict(latest, now) + ". " + MainActivity.verdictReason(latest) + ". " + ACTION
                    + (routed ? " " + MainActivity.routeSaid(route) : "");
            key = latest.at + "/" + latest.facts.fingerprint() + "/" + MainActivity.captionOutcome(latest) + "/"
                    + DecisionLog.outcome(latest) + "/" + ago + "/" + (routed ? stopsLabel(route) : "-");
        }
        if (!nextSaid.contentEquals(String.valueOf(getContentDescription()))) setContentDescription(nextSaid);
        said = nextSaid;
        entry = latest;
        this.ready = ready;
        tappable(latest != null);
        if (key.equals(shownKey)) return;
        shownKey = key;
        if (latest == null) {
            outcome = null;
            symbol = null;
            badgeColor = 0;
            pipColor = 0;
            onRoute = false;
            pay = null;
            tag = null;
            age = null;
            tripLabels = 0;
        } else {
            DecisionLog.Outcome result = DecisionLog.outcome(latest);
            outcome = MainActivity.captionOutcome(latest);
            boolean below = AutopilotText.PASSED_BELOW_MINIMUMS.equals(outcome);
            // An offer passed below the minimums by Autopilot's bar never reads as a full pass: an amber tick.
            badgeColor = below ? Ui.WARNING : Ui.outcomeColor(result);
            symbol = OutcomeBadge.symbol(result);
            // No purple dot (0.6.6): "below min" in purple already says it.
            pipColor = 0;
            onRoute = routed;
            OfferSnapshot facts = latest.facts;
            pay = facts.payCents == null ? "$?" : DecisionLog.money(facts.payCents);
            payColor = facts.payCents == null ? ui.inkMuted : ui.ink;
            if (below) {
                tag = TAG_BELOW;
                tagColor = AutopilotChip.purple(ui);
            } else if (AUTOMATICALLY_ACCEPTED.equals(outcome)) {
                tag = TAG_AUTO;
                tagColor = ui.inkSecondary;
            } else if (result == DecisionLog.Outcome.REQUESTED) {
                tag = TAG_REQUESTED;
                tagColor = AutopilotChip.amber(ui);
            } else {
                tag = null;
            }
            age = ago;
            tripLabels = 0;
            label(facts.miles == null ? "? mi" : MainActivity.miles(facts.miles) + " mi", facts.miles == null);
            label(facts.minutes == null ? "? min" : facts.minutes + " min", facts.minutes == null);
            if (routed) label(stopsLabel(route), route.stops == null);
        }
        arrangedWidth = -1;
        arrange();
        invalidate();
    }

    /** The trip's next label, in the muted ink while it is {@code unread}. */
    private void label(String words, boolean unread) {
        trip[tripLabels] = words;
        tripColor[tripLabels] = unread ? ui.inkMuted : ui.inkSecondary;
        tripLabels++;
    }

    /** The stored route's stops as the trip's line adds them: "2 stops", "1 stop", "? stops" unread. */
    private static String stopsLabel(OfferSnapshot route) {
        return route.stops == null ? "? stops" : route.stops + (route.stops == 1 ? " stop" : " stops");
    }

    /**
     * How long ago, as short as it can be said: "now" under a minute, then "12m", "3h" (under two days), "4d" (the
     * spoken words' own steps, {@link MainActivity#ago}).
     */
    static String age(long ms) {
        long minutes = Math.max(0, ms) / 60_000;
        if (minutes < 1) return "now";
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        if (hours < 48) return hours + "h";
        return hours / 24 + "d";
    }

    /** A tap opens the offer, only while there is one: its pressable look and its button role go with it. */
    private void tappable(boolean tappable) {
        if (isClickable() != tappable) setClickable(tappable);
        if (isFocusable() != tappable) setFocusable(tappable);
        boolean pressable = getBackground() != null;
        if (pressable != tappable) setBackground(tappable ? ui.pressable(12) : null);
    }

    /** The page's tap (it opens the latest offer's ticket); it takes effect only while there is an offer. */
    @Override public void setOnClickListener(OnClickListener listener) {
        super.setOnClickListener(listener);
        // Setting a listener makes a view clickable: with no offer, the tile stays only its words.
        tappable(entry != null);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize(templateWide, widthSpec), resolveSize(tall, heightSpec));
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        arrangedWidth = -1;
        arrange();
    }

    /**
     * Works out where everything goes for the width the tile has: the address, condensed or smaller where it must be,
     * never cut; then the offer's line, in the fixed order of what gives way: the tag, the pay and the trip condensed,
     * the trip's end; the age and the route's stops only where they fit. Run only when what is shown or the size
     * changes, never while drawing.
     */
    private void arrange() {
        int width = getWidth();
        int height = getHeight();
        if (width == arrangedWidth && arrangedWidth >= 0) return;
        arrangedWidth = width;
        float lineGap = ui.dp(LINE_GAP_DP);
        float brandLine = Ui.lineHeight(brandPaint);
        float offerLine = Ui.lineHeight(tripPaint);
        float top = (height - (brandLine + lineGap + offerLine)) / 2f;
        line1 = top - brandPaint.getFontMetrics().ascent;
        line2 = top + brandLine + lineGap - tripPaint.getFontMetrics().ascent;
        badgeY = top + brandLine + lineGap + offerLine / 2f;
        // The address, from the badge's left edge: condensed down to four fifths of its width, then drawn smaller.
        brandX = ui.dp(BRAND_X_DP);
        fit(brandDrawn, brandPaint, brandPaint.measureText(BRAND), width - brandX);
        float textX = ui.dp(TEXT_X_DP);
        float room = width - textX;
        if (entry == null) {
            String words = said == null ? NO_OFFERS : said;
            emptyShown = room > 0
                    ? TextUtils.ellipsize(words, emptyPaint, room, TextUtils.TruncateAt.END).toString() : "";
            shownText = emptyShown;
            lineParts = 0;
            return;
        }
        emptyShown = null;
        float tagGap = ui.dp(TAG_GAP_DP);
        float dot = tripPaint.measureText(SEPARATOR);
        float payWide = payPaint.measureText(pay);
        float tagWide = tag == null ? 0 : tagGap + tagPaint.measureText(tag);
        // The line's core: the pay, the tag and the trip itself (miles and minutes). The tag goes where the core with
        // it would be condensed past four fifths of its width; then the core is condensed, down to four fifths.
        float tripWide = dot + tripPaint.measureText(trip[0]) + dot + tripPaint.measureText(trip[1]);
        tagShown = tag != null && share(payWide + tagWide + tripWide, room) >= LEAST_CONDENSED;
        float core = payWide + (tagShown ? tagWide : 0) + tripWide;
        float scale = share(core, room);
        boolean cut = scale < LEAST_CONDENSED;
        scale = Math.max(LEAST_CONDENSED, scale);
        payDrawn.set(payPaint);
        payDrawn.setColor(payColor);
        payDrawn.setTextScaleX(scale);
        // The pay is never cut: past four fifths of its width, it is drawn smaller, alone.
        float payDrawnWide = payDrawn.measureText(pay);
        if (payDrawnWide > room) {
            payDrawn.setTextSize(payPaint.getTextSize() * Math.max(0, room) / payDrawnWide);
            payDrawnWide = payDrawn.measureText(pay);
        }
        tagDrawn.set(tagPaint);
        tagDrawn.setColor(tagColor);
        tagDrawn.setTextScaleX(scale);
        tripDrawn.set(tripPaint);
        tripDrawn.setTextScaleX(scale);
        dot = tripDrawn.measureText(SEPARATOR);
        lineParts = 0;
        labelsShownCount = 0;
        put(pay, 0, payColor, PAY);
        labelsShown[labelsShownCount++] = pay;
        float x = payDrawnWide;
        if (tagShown) {
            x += ui.dp(TAG_GAP_DP) * scale;
            put(tag, x, tagColor, TAG);
            labelsShown[labelsShownCount++] = tag;
            x += tagDrawn.measureText(tag);
        }
        // The trip's labels in their order: miles, minutes, the age, the route's stops; the last two only whole.
        String[] words = {trip[0], trip[1], age, tripLabels > 2 ? trip[2] : null};
        int[] inks = {tripColor[0], tripColor[1], ui.inkSecondary, tripColor[2]};
        int count = tripLabels > 2 ? 4 : 3;
        int labels = 2;
        if (!cut) {
            float all = x + dot + tripDrawn.measureText(words[0]) + dot + tripDrawn.measureText(words[1]);
            while (labels < count) {
                float more = all + dot + tripDrawn.measureText(words[labels]);
                if (more > room) break;
                all = more;
                labels++;
            }
        }
        ageShown = labels >= 3;
        float ellipsis = tripDrawn.measureText("…");
        for (int i = 0; i < labels && payDrawnWide < room; i++) {
            float wide = tripDrawn.measureText(words[i]);
            // Whole while the line fits; once it is cut, a label is whole only where the ellipsis still fits after it.
            boolean last = i == labels - 1;
            if (!cut || x + dot + wide + (last ? 0 : ellipsis) <= room) {
                put(SEPARATOR, x, ui.inkSecondary, TRIP);
                put(words[i], x + dot, inks[i], TRIP);
                labelsShown[labelsShownCount++] = words[i];
                x += dot + wide;
                continue;
            }
            // Cut: as much of this label as leaves room for the ellipsis, else the ellipsis alone after the last one.
            int fits = tripDrawn.breakText(words[i], true, Math.max(0, room - x - dot - ellipsis), null);
            String kept = words[i].substring(0, fits).trim();
            if (!kept.isEmpty()) {
                put(SEPARATOR, x, ui.inkSecondary, TRIP);
                put(kept + "…", x + dot, inks[i], TRIP);
                labelsShown[labelsShownCount++] = kept + "…";
            } else if (x + ellipsis <= room) {
                put("…", x, ui.inkSecondary, TRIP);
                labelsShown[labelsShownCount - 1] += "…";
            }
            break;
        }
        for (int i = 0; i < lineParts; i++) lineX[i] += textX;
        shownText = describeShown();
    }

    /** {@code drawn} as {@code paint}, condensed to fit {@code wide} into {@code room}, smaller past four fifths. */
    private static void fit(TextPaint drawn, TextPaint paint, float wide, float room) {
        float scale = share(wide, room);
        drawn.set(paint);
        if (scale < LEAST_CONDENSED) {
            drawn.setTextScaleX(LEAST_CONDENSED);
            drawn.setTextSize(paint.getTextSize() * Math.max(0, room) / (wide * LEAST_CONDENSED));
        } else {
            drawn.setTextScaleX(scale);
        }
    }

    /** One part of the offer's line as drawn, at {@code x}, in {@code ink}, with the paint of its {@code kind}. */
    private void put(String words, float x, int ink, int kind) {
        lineShown[lineParts] = words;
        lineX[lineParts] = x;
        lineColor[lineParts] = ink;
        lineKind[lineParts] = kind;
        lineParts++;
    }

    /** The share of its width {@code wide} is drawn at to fit {@code room}: 1 where it fits, less where it does not. */
    private static float share(float wide, float room) {
        if (wide <= 0) return 1f;
        return Math.max(0, Math.min(1f, room / wide));
    }

    /** The offer's labels shown, in reading order, " · " between them (for tests and {@link #shownText}). */
    private String describeShown() {
        StringBuilder shown = new StringBuilder();
        for (int i = 0; i < labelsShownCount; i++) {
            if (i > 0) shown.append(SEPARATOR);
            shown.append(labelsShown[i]);
        }
        return shown.toString();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;
        if (arrangedWidth != getWidth()) arrange();
        canvas.drawText(BRAND, brandX, line1, brandDrawn);
        float badgeX = ui.dp(BADGE_X_DP);
        float textX = ui.dp(TEXT_X_DP);
        if (entry == null) {
            canvas.drawCircle(badgeX, badgeY, ui.dp(RING_DP), ring);
            if (emptyShown != null && !emptyShown.isEmpty()) canvas.drawText(emptyShown, textX, line2, emptyPaint);
            return;
        }
        OutcomeBadge.draw(canvas, ui, badgeX, badgeY, badgeColor, symbol, 255);
        float pip = ui.dp(PIP_OFFSET_DP);
        if (pipColor != 0) OutcomeBadge.drawPip(canvas, ui, badgeX + pip, badgeY - pip, pipColor);
        if (onRoute) OutcomeBadge.drawRoadPip(canvas, ui, badgeX + pip, badgeY + pip, ui.dp(ROAD_PIP_DP), ui.ink);
        for (int i = 0; i < lineParts; i++) {
            TextPaint paint = lineKind[i] == PAY ? payDrawn : lineKind[i] == TAG ? tagDrawn : tripDrawn;
            paint.setColor(lineColor[i]);
            canvas.drawText(lineShown[i], lineX[i], line2, paint);
        }
    }

    /** A button that opens the offer's ticket while there is an offer; only its words while there is none. */
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName((isClickable() ? android.widget.Button.class : android.widget.TextView.class).getName());
        if (isClickable()) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, ACTION));
        }
    }

    // ---- For tests ----

    /**
     * The offer's labels drawn, in reading order, " · " between them: the pay, its tag, then the trip and age; with no
     * offer, its words (for tests). The address is always drawn, whole ({@link #brandTextSize}).
     */
    String shownText() {
        if (arrangedWidth != getWidth()) arrange();
        return shownText;
    }

    /** The outcome shown in words (MainActivity.captionOutcome); null with no offer (for tests). */
    String outcome() {
        return outcome;
    }

    /** The badge's colour and symbol (null with no offer: the dashed ring; for tests). */
    int badgeColor() {
        return badgeColor;
    }

    OutcomeBadge.Symbol symbol() {
        return symbol;
    }

    /** The dot at the badge's upper right, 0 for none (Autopilot's purple: passed below the minimums; for tests). */
    int pipColor() {
        return pipColor;
    }

    /** The road at the badge's lower right: the offer is the stored route's (for tests). */
    boolean routeMarked() {
        return onRoute;
    }

    /** The outcome's tag, whether it is drawn or not (null for none; for tests). */
    String tag() {
        return tag;
    }

    /** The tag is drawn, after the pay (for tests). */
    boolean tagShown() {
        if (arrangedWidth != getWidth()) arrange();
        return entry != null && tagShown;
    }

    /** The age is drawn (for tests). */
    boolean ageShown() {
        if (arrangedWidth != getWidth()) arrange();
        return entry != null && ageShown;
    }

    /** The address's text size as drawn, in pixels (for tests: never cut, only condensed or, past that, smaller). */
    float brandTextSize() {
        if (arrangedWidth != getWidth()) arrange();
        return brandDrawn.getTextSize();
    }

    /** The pay's text size as drawn, in pixels (for tests: it is never cut, only condensed or, past that, smaller). */
    float payTextSize() {
        if (arrangedWidth != getWidth()) arrange();
        return payDrawn.getTextSize();
    }

    /** The share of their width the offer's line is drawn at (1: not condensed; for tests). */
    float lineCondensed() {
        if (arrangedWidth != getWidth()) arrange();
        return tripDrawn.getTextScaleX();
    }
}
