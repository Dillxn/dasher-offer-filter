package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * The latest offer at the top of the homepage as a label and a badge, not a sentence (0.6.3; the owner, 12 October
 * 2026: "that sentence needs to be removed at all sizes and replaced with more thoughtful, concise (either via label
 * and or graphic) expression"). Two lines in a box of one fixed size, beside the mascot:
 * <ul>
 * <li>the outcome's badge (the skyline's, {@link OutcomeBadge}), the pay in bold, a short tag only where the badge
 * alone would leave the outcome unsaid ("below min" in Autopilot's purple; "auto";
 * "requested" in amber), and how long ago at the right: "now", "12m", "3h", "4d";</li>
 * <li>the trip under it, "2.1 mi · 13 min", and its stops when the offer is the stored route's (whose road marks the
 * badge's lower right).</li>
 * </ul>
 * An unread figure shows as unread ("$?", "? mi", "? min" in the muted ink), never left out or guessed. With no offer
 * yet: a dashed ring and "Waiting for offers" (everything ready) or "No offers yet".
 *
 * <p>Its size is its own, never its content's or the clock's: as tall as the pay's line and the trip's at the size they
 * are drawn ({@link #heightFor}: 48 dp at the normal font), as wide as the page gives it. Where that is too narrow for
 * everything, the tag moves to the end of the trip's line if it fits there whole (else it goes), then the pay and the
 * trip condense to four fifths of their width, then the age goes, then the trip's end is cut; the pay is never cut. The
 * age keeps the room of its widest ("59m"), so the minutes passing move nothing. The words are drawn at the user's
 * size, at a large font a little smaller (never below three quarters of it nor below the default size, as a setup
 * step's words), so the tile keeps beside the mascot and the dash's counts in a short window.
 *
 * <p>Screen readers hear exactly what the sentence said ({@link MainActivity#verdict}, then its reason) and "Open offer
 * details", then the route's words when the offer is the stored route's: a button whose tap opens the offer's ticket
 * (the page that holds it says how, {@link #setOnClickListener}). With no offer it is only its words and takes no tap.
 * It is no live region: an offer coming in never announces itself here.
 */
@SuppressLint("ViewConstructor")
final class VerdictTile extends View {
    /** What a tap does, as screen readers hear it (and the end of what they hear). */
    static final String ACTION = "Open offer details";
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

    /** The words' sizes at the normal font. */
    private static final float PAY_SP = 16;
    private static final float TAG_SP = 11;
    private static final float AGE_SP = 12;
    private static final float TRIP_SP = 12;
    private static final float EMPTY_SP = 14;
    /** The tile is never shorter than a touch target. */
    private static final float LEAST_DP = 48;
    /** The badge's middle from the start; the pay (and the words with no offer) from here. */
    private static final float BADGE_X_DP = 9;
    private static final float TEXT_X_DP = 22;
    /** Between the pay's line and the trip's; before a tag; before the age, at the least. */
    private static final float LINE_GAP_DP = 2;
    private static final float TAG_GAP_DP = 6;
    private static final float AGE_GAP_DP = 6;
    /** A dot's or the road's middle this far right of the badge's, and up or down. */
    private static final float PIP_OFFSET_DP = 6;
    private static final float ROAD_PIP_DP = 9;
    /** The no-offer ring's radius. */
    private static final float RING_DP = 8;
    /** The narrowest the pay and the trip condense to (of their width) before anything else gives way. */
    private static final float LEAST_CONDENSED = 0.8f;
    private static final String SEPARATOR = " · ";
    /** What the tile's width is worked out for: the widest pay and age of an ordinary offer, and its longest trip. */
    private static final String TEMPLATE_PAY = "$88.88";
    private static final String TEMPLATE_TRIP = "88.8 mi · 88 min";
    /** The widest an age is in its first two days; its room is kept the widest of them. */
    private static final String[] AGES = {"59m", "47h", "13d", "now"};

    private final Ui ui;
    /** The words at the size the tile draws them, never condensed: what the fitting measures with. */
    private final TextPaint payPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tagPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint agePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tripPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint emptyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    /** The pay and the trip as drawn now (condensed, or the pay smaller still, where the width asks). */
    private final TextPaint payDrawn = new TextPaint(Paint.ANTI_ALIAS_FLAG);
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
    private int tagLine;
    private boolean ageShown;
    /** The trip's line as drawn: its labels (the last perhaps cut) and the dots between them, each at its place. */
    private final String[] tripShown = new String[5];
    private final float[] tripX = new float[5];
    private final int[] tripShownColor = new int[5];
    private int tripShownParts;
    /** The trip's labels as drawn, for {@link #shownText}. */
    private final String[] labelsShown = new String[3];
    private int labelsShownCount;
    private float tagX;
    private float payX;
    private float line1;
    private float line2;
    private float badgeY;
    private StaticLayout emptyLayout;
    private String shownText = "";

    VerdictTile(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        payPaint.setTypeface(Typeface.DEFAULT_BOLD);
        payPaint.setTextSize(drawn(metrics, PAY_SP));
        tagPaint.setTypeface(Typeface.DEFAULT_BOLD);
        tagPaint.setTextSize(drawn(metrics, TAG_SP));
        agePaint.setTypeface(Ui.MEDIUM);
        agePaint.setTextSize(drawn(metrics, AGE_SP));
        agePaint.setColor(ui.inkSecondary);
        agePaint.setTextAlign(Paint.Align.RIGHT);
        tripPaint.setTypeface(Typeface.DEFAULT);
        tripPaint.setTextSize(drawn(metrics, TRIP_SP));
        emptyPaint.setTypeface(Ui.MEDIUM);
        emptyPaint.setTextSize(drawn(metrics, EMPTY_SP));
        emptyPaint.setColor(ui.inkSecondary);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(1, ui.dp(1.5f)));
        ring.setColor(ui.inkSecondary);
        ring.setPathEffect(new DashPathEffect(new float[] {ui.dp(3), ui.dp(2.5f)}, 0));
        float room = 0;
        for (String each : AGES) room = Math.max(room, agePaint.measureText(each));
        ageRoom = room;
        tall = heightFor(context);
        templateWide = (int) Math.ceil(Math.max(ui.dp(TEXT_X_DP) + payPaint.measureText(TEMPLATE_PAY)
                + ui.dp(AGE_GAP_DP) + ageRoom, tripPaint.measureText(TEMPLATE_TRIP)));
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
     * The tile's one height, in pixels, for {@code context}'s font and density: its pay's line and its trip's at the
     * size they are drawn, 2 dp apart, and never less than 48 dp (48 at the normal font). What it shows never changes
     * it, so the page lays out the band with it before anything is shown.
     */
    static int heightFor(Context context) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        TextPaint measuring = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        measuring.setTypeface(Typeface.DEFAULT_BOLD);
        measuring.setTextSize(drawn(metrics, PAY_SP));
        float pay = Ui.lineHeight(measuring);
        measuring.setTypeface(Typeface.DEFAULT);
        measuring.setTextSize(drawn(metrics, TRIP_SP));
        float trip = Ui.lineHeight(measuring);
        int least = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, LEAST_DP, metrics));
        float gap = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, LINE_GAP_DP, metrics);
        return Math.max(least, (int) Math.ceil(pay + trip + gap));
    }

    /**
     * The width, in pixels, at which an ordinary offer shows whole with no tag: the widest pay ("$88.88") and age
     * ("59m") on its first line, the longest trip ("88.8 mi · 88 min") under it.
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
     * Works out where everything goes for the width the tile has, in the fixed order of what gives way: the tag to the
     * trip's line (or away), the pay and the trip condensed, the age, the trip's end. Run only when what is shown or
     * the size changes, never while drawing.
     */
    private void arrange() {
        int width = getWidth();
        int height = getHeight();
        if (width == arrangedWidth && arrangedWidth >= 0) return;
        arrangedWidth = width;
        float lineGap = ui.dp(LINE_GAP_DP);
        float payLine = Ui.lineHeight(payPaint);
        float tripLine = Ui.lineHeight(tripPaint);
        float top = (height - (payLine + lineGap + tripLine)) / 2f;
        line1 = top - payPaint.getFontMetrics().ascent;
        line2 = top + payLine + lineGap - tripPaint.getFontMetrics().ascent;
        badgeY = top + payLine / 2f;
        float textX = ui.dp(TEXT_X_DP);
        if (entry == null) {
            int across = Math.max(1, Math.round(width - textX));
            String words = said == null ? NO_OFFERS : said;
            emptyLayout = StaticLayout.Builder.obtain(words, 0, words.length(), emptyPaint, across)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build();
            StringBuilder shown = new StringBuilder();
            for (int line = 0; line < emptyLayout.getLineCount(); line++) {
                int start = emptyLayout.getLineStart(line);
                boolean cut = emptyLayout.getEllipsisCount(line) > 0;
                int end = cut ? start + emptyLayout.getEllipsisStart(line) : emptyLayout.getLineEnd(line);
                String text = words.substring(start, Math.max(start, Math.min(words.length(), end))).trim();
                if (shown.length() > 0 && !text.isEmpty()) shown.append(' ');
                shown.append(text);
                if (cut) shown.append('…');
            }
            shownText = width > textX ? shown.toString() : "";
            return;
        }
        emptyLayout = null;
        float tagGap = ui.dp(TAG_GAP_DP);
        float ageGap = ui.dp(AGE_GAP_DP);
        float payWide = payPaint.measureText(pay);
        float tagWide = tag == null ? 0 : tagPaint.measureText(tag);
        float ageWide = Math.max(ageRoom, agePaint.measureText(age));
        // The trip itself (miles and minutes), without the route's stops, which come only where there is room left.
        float dot = tripPaint.measureText(SEPARATOR);
        float tripWide = tripPaint.measureText(trip[0]) + dot + tripPaint.measureText(trip[1]);
        // First the tag: beside the pay with the age kept; else at the end of the trip's line, whole; else nowhere.
        tagLine = 0;
        if (tag != null) {
            if (textX + payWide + tagGap + tagWide + ageGap + ageWide <= width) tagLine = 1;
            else if (tripWide + tagGap + tagWide <= width) tagLine = 2;
        }
        // Then the pay condensed, down to four fifths of its width, to keep the age; else the age goes. The pay is
        // never cut: past four fifths with the age gone, it is drawn smaller.
        float payRoom = width - textX - (tagLine == 1 ? tagGap + tagWide : 0);
        ageShown = true;
        float payScale = share(payWide, payRoom - ageGap - ageWide);
        if (payScale < LEAST_CONDENSED) {
            ageShown = false;
            payScale = share(payWide, payRoom);
        }
        payDrawn.set(payPaint);
        payDrawn.setColor(payColor);
        if (payScale < LEAST_CONDENSED) {
            payDrawn.setTextScaleX(LEAST_CONDENSED);
            payDrawn.setTextSize(payPaint.getTextSize() * Math.max(0, payRoom) / (payWide * LEAST_CONDENSED));
        } else {
            payDrawn.setTextScaleX(payScale);
        }
        payX = textX;
        tagX = textX + payDrawn.measureText(pay) + tagGap;
        // The trip: condensed down to four fifths of its width, then its end cut; the route's stops only where they
        // fit as it is drawn.
        float tripRoom = width - (tagLine == 2 ? tagGap + tagWide : 0);
        float tripScale = share(tripWide, tripRoom);
        boolean cut = tripScale < LEAST_CONDENSED;
        tripDrawn.set(tripPaint);
        tripDrawn.setTextScaleX(Math.max(LEAST_CONDENSED, tripScale));
        dot = tripDrawn.measureText(SEPARATOR);
        int labels = Math.min(2, tripLabels);
        if (!cut && tripLabels > 2) {
            float all = tripDrawn.measureText(trip[0]);
            for (int i = 1; i < tripLabels; i++) all += dot + tripDrawn.measureText(trip[i]);
            if (all <= tripRoom) labels = tripLabels;
        }
        float ellipsis = tripDrawn.measureText("…");
        float x = 0;
        tripShownParts = 0;
        labelsShownCount = 0;
        for (int i = 0; i < labels; i++) {
            float before = i == 0 ? 0 : dot;
            float wide = tripDrawn.measureText(trip[i]);
            // Whole while the line fits; once it is cut, a label is whole only where the ellipsis still fits after it.
            boolean last = i == labels - 1;
            if (!cut || x + before + wide + (last ? 0 : ellipsis) <= tripRoom) {
                if (i > 0) put(SEPARATOR, x, ui.inkSecondary);
                put(trip[i], x + before, tripColor[i]);
                labelsShown[labelsShownCount++] = trip[i];
                x += before + wide;
                continue;
            }
            // Cut: as much of this label as leaves room for the ellipsis, else the ellipsis alone after the last one.
            int fits = tripDrawn.breakText(trip[i], true, Math.max(0, tripRoom - x - before - ellipsis), null);
            String kept = trip[i].substring(0, fits).trim();
            if (!kept.isEmpty()) {
                if (i > 0) put(SEPARATOR, x, ui.inkSecondary);
                put(kept + "…", x + before, tripColor[i]);
                labelsShown[labelsShownCount++] = kept + "…";
                x += before + tripDrawn.measureText(kept + "…");
            } else if (x + ellipsis <= tripRoom) {
                put("…", x, ui.inkSecondary);
                if (labelsShownCount > 0) labelsShown[labelsShownCount - 1] += "…";
                x += ellipsis;
            }
            break;
        }
        if (tagLine == 2) tagX = x + tagGap;
        shownText = describeShown();
    }

    /** One part of the trip's line as drawn, at {@code x}, in {@code ink}. */
    private void put(String words, float x, int ink) {
        tripShown[tripShownParts] = words;
        tripX[tripShownParts] = x;
        tripShownColor[tripShownParts] = ink;
        tripShownParts++;
    }

    /** The share of its width {@code wide} is drawn at to fit {@code room}: 1 where it fits, less where it does not. */
    private static float share(float wide, float room) {
        if (wide <= 0) return 1f;
        return Math.max(0, Math.min(1f, room / wide));
    }

    /** The labels shown, in reading order, " · " between them (for tests and {@link #shownText}). */
    private String describeShown() {
        StringBuilder shown = new StringBuilder(pay);
        if (tagLine == 1) shown.append(SEPARATOR).append(tag);
        if (ageShown) shown.append(SEPARATOR).append(age);
        for (int i = 0; i < labelsShownCount; i++) shown.append(SEPARATOR).append(labelsShown[i]);
        if (tagLine == 2) shown.append(SEPARATOR).append(tag);
        return shown.toString();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;
        if (arrangedWidth != getWidth()) arrange();
        float badgeX = ui.dp(BADGE_X_DP);
        if (entry == null) {
            canvas.drawCircle(badgeX, getHeight() / 2f, ui.dp(RING_DP), ring);
            if (emptyLayout != null && getWidth() > ui.dp(TEXT_X_DP)) {
                canvas.save();
                canvas.translate(ui.dp(TEXT_X_DP), (getHeight() - emptyLayout.getHeight()) / 2f);
                emptyLayout.draw(canvas);
                canvas.restore();
            }
            return;
        }
        OutcomeBadge.draw(canvas, ui, badgeX, badgeY, badgeColor, symbol, 255);
        float pip = ui.dp(PIP_OFFSET_DP);
        if (pipColor != 0) OutcomeBadge.drawPip(canvas, ui, badgeX + pip, badgeY - pip, pipColor);
        if (onRoute) OutcomeBadge.drawRoadPip(canvas, ui, badgeX + pip, badgeY + pip, ui.dp(ROAD_PIP_DP), ui.ink);
        canvas.drawText(pay, payX, line1, payDrawn);
        if (tagLine != 0) {
            tagPaint.setColor(tagColor);
            canvas.drawText(tag, tagX, tagLine == 1 ? line1 : line2, tagPaint);
        }
        if (ageShown) canvas.drawText(age, getWidth(), line1, agePaint);
        for (int i = 0; i < tripShownParts; i++) {
            tripDrawn.setColor(tripShownColor[i]);
            canvas.drawText(tripShown[i], tripX[i], line2, tripDrawn);
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

    /** The labels drawn, in reading order, " · " between them: the pay, its tag and age, then the trip (for tests). */
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

    /** Where the tag is drawn: 1 beside the pay, 2 at the end of the trip's line, 0 nowhere (for tests). */
    int tagLine() {
        if (arrangedWidth != getWidth()) arrange();
        return tagLine;
    }

    /** The age is drawn (for tests). */
    boolean ageShown() {
        if (arrangedWidth != getWidth()) arrange();
        return entry != null && ageShown;
    }

    /** The pay's text size as drawn, in pixels (for tests: it is never cut, only condensed or, past that, smaller). */
    float payTextSize() {
        if (arrangedWidth != getWidth()) arrange();
        return payDrawn.getTextSize();
    }

    /** The share of their width the pay and the trip are drawn at (1: not condensed; for tests). */
    float payCondensed() {
        if (arrangedWidth != getWidth()) arrange();
        return payDrawn.getTextScaleX();
    }

    float tripCondensed() {
        if (arrangedWidth != getWidth()) arrange();
        return tripDrawn.getTextScaleX();
    }
}
