package com.local.dasherfilter;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Always-on, on-device history of offer decisions, so a surprising decline can be explained afterwards. It keeps
 * the parsed numbers, the rule outcome and the action taken, plus only the screen lines that carried a number or
 * a pay label, masked ({@link PersonalText}): no names or addresses. Bounded to {@link #MAX_ENTRIES}; leaves the phone
 * only in a report the user chooses to send, or in diagnostics after a dash the user turned on. One offer is one line: Dasher's notification of an offer the screen read is folded into the
 * screen's line ({@link OfferPairing}), and two notification incarnations are never one line.
 */
final class DecisionLog {
    static final int MAX_ENTRIES = 200;
    private static final long MERGE_WINDOW_MS = 120_000;
    private static final int MAX_EVIDENCE_LINES = 10;
    private static final int MAX_EVIDENCE_CHARS = 120;
    private static final String FILE = "decision-log.json";
    /** Lines the parser can read a figure from, or a pay/add-on label; "+" only before "$" (not phone numbers). */
    private static final Pattern EVIDENCE = Pattern.compile("(?i)\\$|\\d\\s*(?:mi|miles?|mins?|minutes?"
            + "|stops?|pick[ -]?ups?|drop[ -]?offs?)\\b|guaranteed|total pay|incl\\. tips"
            + "|add to route|add-on|additional order");
    private static final int REPORT_EVIDENCE_LINES = 4;

    enum Source { SCREEN, NOTIFICATION }

    /** What the app did about a decision. A heavier action replaces a lighter one on the same offer. */
    enum Action {
        PAUSED("No action: auto-decline paused", 0),
        PASSES("No action: passes your rules", 0),
        SEEN_ON_SCREEN("No card: the screen had already read this offer", 0),
        NEEDS_REVIEW("No action: needs your review", 0),
        REPLAY("Re-checked after a reconnect or rule change; no action", 0),
        NATIVE_ALERT("Dasher notification kept; duplicate card omitted", 1),
        SILENT_CARD("Review card posted without sound", 1),
        /** Posted silently while Peek opens Dasher to read the offer; it rings once only if the peek does not happen. */
        PEEK_CARD("Card posted without sound while Peek checks whether it can open Dasher", 1),
        DASHER_SOUNDS("Review card posted without sound: Dasher's own offer alert sounds", 1),
        QUIET_PASS_CARD("Passing card posted without sound", 1),
        CARD_BLOCKED("Card blocked by Android; DoorDash notification kept", 1),
        BELL("Passing alert rang", 2),
        CHECK_BELL("Rang once: open Dasher to check it", 2),
        DECLINE_REFUSED("Decline request refused by Android", 2),
        DECLINE_TAPPED("Decline tapped", 3),
        NOTIFICATION_HIDDEN("Notification hidden; order NOT declined", 3),
        NOTIFICATION_DECLINE_SENT("Decline requested from the notification", 3),
        CONFIRMATION_TAPPED("Decline and its confirmation tapped", 4),
        USER_TOOK_OVER("You touched the screen and took over; nothing more tapped", 5),
        /**
         * Dasher's question was never confirmed (Android refused every try, Dasher did not act on them, or it closed
         * a confirmed question and still showed the offer), so the app gave up and left the offer to the user.
         */
        CONFIRMATION_NOT_TAPPED("Dasher's question not confirmed; left to you", 5);

        final String label;
        final int weight;

        Action(String label, int weight) {
            this.label = label;
            this.weight = weight;
        }

        static Action named(String name) {
            try {
                return valueOf(name);
            } catch (IllegalArgumentException | NullPointerException unknown) {
                return REPLAY;
            }
        }
    }

    /**
     * One step of what became of an offer after its decision (accepted, declined by hand, or neither, and why), kept
     * on that offer's line for its history and the acceptance-rate count. Nothing is learned from any of them (0.5.0).
     * Steps never change the offer's action; an observed acceptance updates its tally and totals. They never leave the
     * phone in an automatic report. Every kind an older version wrote still parses; the retired learning kinds read
     * with neutral words.
     */
    enum StepKind {
        ACCEPTED_OBSERVED("Accepted; display only"),
        TAP_NOT_RECOGNIZED("A tap on Dasher while this offer showed was neither Accept nor Decline"),
        ACCEPT_TAPPED("You tapped Accept"),
        AUTO_ACCEPT_NOT_SENT("Automatic Accept not sent; left to you"),
        AUTO_ACCEPT_REQUESTED("Automatic Accept requested; not confirmed"),
        AUTO_ACCEPT_UNCONFIRMED("Automatic acceptance unconfirmed; left to you"),
        ACCEPT_UNCONFIRMED("No delivery screen within 15 s of your Accept tap"),
        /** Accepted by the user: a seen Accept tap, or the offer closing into a delivery (0.5.0). */
        ACCEPTED("Accepted"),
        /** Accepted after the app's own automatic Accept request: provenance only (0.5.0). */
        ACCEPTED_AUTOMATIC("Accepted after an automatic Accept request"),
        /** Written before 0.5.0 (the adaptive minimum learned from it); reads as an acceptance. */
        ACCEPTED_LEARNED("Accepted"),
        /** Written before 0.5.0; reads as an acceptance. */
        ACCEPTED_NOT_LEARNED("Accepted"),
        /** Written before 0.5.0; reads as an acceptance. */
        ACCEPTED_BEST_SAVED("Accepted"),
        /** Written before 0.5.0; reads as an acceptance. */
        ACCEPTED_MINIMUMS_UNCHANGED("Accepted"),
        ACCEPTED_ADD_ON("Accepted add-on"),
        NOT_ACCEPTED("Not accepted"),
        /**
         * What followed the offer did not count it as accepted, and why (the evidence was not enough). Not a verdict on
         * the line: a seen Accept tap before it still counts it when a delivery screen followed
         * ({@link DecisionLog#accepted}), so the words name only what followed.
         */
        NOT_LEARNED("Not counted from what followed"),
        DECLINE_TAPPED("You tapped Decline on it"),
        DECLINE_QUESTION("Dasher asked to confirm declining it; " + AppName.NAME + " did not decline it"),
        DECLINE_COUNTED("Counted as your Decline"),
        DECLINE_DROPPED("Not counted as your Decline"),
        /** Written before 0.5.0 (a decline by hand that raised the adaptive minimum). */
        DECLINE_TAUGHT("Your Decline (older version)"),
        /** Written before 0.5.0. */
        DECLINE_NOT_TAUGHT("Your Decline (older version)"),
        /**
         * Dasher's decline question said declining this offer does not lower the acceptance rate: the offer leaves
         * the acceptance-rate count. A mark only: it changes no outcome, tally or total.
         */
        AR_EXEMPT("Dasher said declining it does not lower your acceptance rate");

        final String label;

        StepKind(String label) {
            this.label = label;
        }
    }

    /** An outcome step: what, when (wall clock), and a detail of fixed words and numbers only, never screen text. */
    static final class Step {
        final StepKind kind;
        final long at;
        final String detail;

        Step(StepKind kind, long at, String detail) {
            this.kind = kind;
            this.at = at;
            this.detail = detail == null ? "" : detail;
        }

        String text() {
            return kind.label + (detail.isEmpty() ? "" : ": " + detail);
        }
    }

    /** Steps kept on one line at most; the oldest go first. */
    static final int MAX_STEPS = 8;

    /** The rules model a line decided now is under (0.5.0: three minimums and the bar). */
    static final int MODEL = 2;
    /** What a line from an older version (no "model" in its JSON) was decided under; its score is a retired one. */
    static final int LEGACY_MODEL = 1;

    static final class Entry {
        final long at;
        final Source source;
        final boolean addOn;
        final OfferSnapshot facts;
        final long requiredCents;
        final OfferRule.Result result;
        final String reason;
        final Action action;
        final boolean autoDecline;
        final List<String> evidence;
        /** Dasher's notification of this same offer, folded in; kept without screen lines. Null when none. */
        final Entry notification;
        /** Memory only: the card tag of the notification incarnation this line is for. */
        final String alertTag;
        /** Memory only: recorded on a replay, which re-evaluates a post already recorded and is never a new offer. */
        final boolean replay;
        /** What became of this offer afterwards, oldest first ({@link #MAX_STEPS} at most). */
        final List<Step> steps;
        /**
         * The offer's score under the rules it was decided by, as a whole percent: pay as a percent of what the
         * minimums asked ({@link AreaScore#scorePercent}) on a {@link #MODEL} line, the retired area score on a
         * {@link #LEGACY_MODEL} line. -1 when it could not be worked out, for an add-on, or for a line recorded before
         * scores were kept.
         */
        final int scorePercent;
        /** Read on Dasher's screen because Peek brought Dasher up for it ({@link Peek}); shown as "(peeked)". */
        final boolean peeked;
        /** The bar the decision used, in percent of the minimums; 100 when the line does not say (older lines). */
        final int barPercent;
        /** Autopilot was on when it was decided, so Autopilot had set that bar. */
        final boolean autopilot;
        /**
         * The rules model it was decided under: {@link #MODEL} for a line this version made, {@link #LEGACY_MODEL} for
         * one an older version wrote (score by area, learned minimums, the minimums scale).
         */
        final int model;

        Entry(long at, Source source, boolean addOn, OfferSnapshot facts, long requiredCents, OfferRule.Result result,
              String reason, Action action, boolean autoDecline, List<String> evidence) {
            this(at, source, addOn, facts, requiredCents, result, reason, action, autoDecline, evidence, null, null,
                    false, Collections.<Step>emptyList(), -1, false, FilterSettings.BAR_AT_MINIMUMS, false, MODEL);
        }

        private Entry(long at, Source source, boolean addOn, OfferSnapshot facts, long requiredCents,
                      OfferRule.Result result, String reason, Action action, boolean autoDecline, List<String> evidence,
                      Entry notification, String alertTag, boolean replay, List<Step> steps, int scorePercent,
                      boolean peeked, int barPercent, boolean autopilot, int model) {
            this.at = at;
            this.source = source;
            this.addOn = addOn;
            this.facts = facts;
            this.requiredCents = requiredCents;
            this.result = result;
            this.reason = reason == null ? "" : reason;
            this.action = action;
            this.autoDecline = autoDecline;
            this.evidence = Collections.unmodifiableList(new ArrayList<>(evidence));
            this.notification = notification;
            this.alertTag = alertTag;
            this.replay = replay;
            this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
            this.scorePercent = scorePercent < 0 ? -1 : scorePercent;
            this.peeked = peeked;
            this.barPercent = Math.max(1, Math.min(200, barPercent));
            this.autopilot = autopilot;
            this.model = Math.max(LEGACY_MODEL, model);
        }

        /** A line for a decision made now: its score, and the bar it used and whether Autopilot set it. */
        static Entry of(Source source, boolean addOn, OfferSnapshot facts, OfferRule.Decision decision,
                        Action action, boolean autoDecline, List<String> labels) {
            return new Entry(System.currentTimeMillis(), source, addOn, facts, decision.requiredCents,
                    decision.result, decision.reason, action, autoDecline, evidence(labels)).withScore(
                    addOn ? -1 : decision.scorePercent).withBar(decision.minimumScalePercent, decision.autopilot);
        }

        /** This line, read because Peek brought Dasher up for it, or not. */
        Entry peeked(boolean on) {
            if (on == peeked) return this;
            return new Entry(at, source, addOn, facts, requiredCents, result, reason, action, autoDecline, evidence,
                    notification, alertTag, replay, steps, scorePercent, on, barPercent, autopilot, model);
        }

        /** This line with the offer's score as a whole percent (-1 for none). */
        Entry withScore(int percent) {
            return new Entry(at, source, addOn, facts, requiredCents, result, reason, action, autoDecline, evidence,
                    notification, alertTag, replay, steps, percent, peeked, barPercent, autopilot, model);
        }

        /** This line decided at {@code bar} percent of the minimums, with Autopilot {@code auto} (on: it set the bar). */
        Entry withBar(int bar, boolean auto) {
            return new Entry(at, source, addOn, facts, requiredCents, result, reason, action, autoDecline, evidence,
                    notification, alertTag, replay, steps, scorePercent, peeked, bar, auto, model);
        }

        private boolean sameOffer(Entry other) {
            return source == other.source && addOn == other.addOn && result == other.result
                    && requiredCents == other.requiredCents && facts.fingerprint().equals(other.facts.fingerprint())
                    && Math.abs(at - other.at) <= MERGE_WINDOW_MS && sameIncarnation(other);
        }

        /** Two notification incarnations are two offers; a replay re-evaluates one already recorded. */
        private boolean sameIncarnation(Entry other) {
            return alertTag == null || other.alertTag == null || other.replay || alertTag.equals(other.alertTag);
        }

        private Entry withAction(Action next, boolean autoDecline) {
            return new Entry(at, source, addOn, facts, requiredCents, result, reason, next, autoDecline, evidence,
                    notification, alertTag, false, steps, scorePercent, peeked, barPercent, autopilot, model);
        }

        /** This line as of {@code time}: a screen reading is stamped as the history takes it. */
        Entry withTime(long time) {
            return new Entry(time, source, addOn, facts, requiredCents, result, reason, action, autoDecline, evidence,
                    notification, alertTag, replay, steps, scorePercent, peeked, barPercent, autopilot, model);
        }

        /** This line for the notification incarnation whose card has {@code tag}. */
        Entry withAlertTag(String tag, boolean replay) {
            return new Entry(at, source, addOn, facts, requiredCents, result, reason, action, autoDecline, evidence,
                    notification, tag, replay, steps, scorePercent, peeked, barPercent, autopilot, model);
        }

        /**
         * This line with Dasher's notification of the same offer folded in, without that notification's lines (its
         * score, bar and model stay its own).
         */
        Entry withNotification(Entry n) {
            Entry nested = new Entry(n.at, n.source, n.addOn, n.facts, n.requiredCents, n.result, n.reason, n.action,
                    n.autoDecline, Collections.<String>emptyList(), null, null, false, Collections.<Step>emptyList(),
                    n.scorePercent, false, n.barPercent, n.autopilot, n.model);
            return new Entry(at, source, addOn, facts, requiredCents, result, reason, action, autoDecline, evidence,
                    nested, alertTag, replay, steps, scorePercent, peeked, barPercent, autopilot, model);
        }

        /** This line with one more step; an exact repeat of the last step is not added again. */
        Entry withStep(Step step) {
            if (!steps.isEmpty()) {
                Step last = steps.get(steps.size() - 1);
                if (last.kind == step.kind && last.detail.equals(step.detail)) return this;
            }
            List<Step> next = new ArrayList<>(steps);
            next.add(step);
            while (next.size() > MAX_STEPS) next.remove(0);
            return new Entry(at, source, addOn, facts, requiredCents, result, reason, action, autoDecline, evidence,
                    notification, alertTag, replay, next, scorePercent, peeked, barPercent, autopilot, model);
        }

        /**
         * The line as stored. A {@link #MODEL} line also keeps "bar", "auto" and "model"; a {@link #LEGACY_MODEL} line
         * keeps the shape an older version wrote. The retired hotspot distance is never written.
         */
        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject()
                    .put("at", at).put("source", source.name()).put("addOn", addOn)
                    .put("required", requiredCents).put("result", result.name()).put("reason", reason)
                    .put("action", action.name()).put("autoDecline", autoDecline)
                    .put("evidence", new JSONArray(evidence));
            if (facts.payCents != null) json.put("pay", facts.payCents);
            if (facts.miles != null) json.put("miles", facts.miles);
            if (facts.minutes != null) json.put("minutes", facts.minutes);
            if (facts.stops != null) json.put("stops", facts.stops);
            if (facts.items != null) json.put("items", facts.items);
            if (facts.itemCountApplicable) json.put("itemCountApplicable", true);
            if (scorePercent >= 0) json.put("score", scorePercent);
            if (model >= MODEL) json.put("bar", barPercent).put("auto", autopilot).put("model", model);
            if (peeked) json.put("peeked", true);
            if (notification != null) json.put("notification", notification.toJson());
            if (!steps.isEmpty()) {
                JSONArray kept = new JSONArray();
                for (Step step : steps) {
                    kept.put(new JSONObject().put("kind", step.kind.name()).put("at", step.at)
                            .put("detail", step.detail));
                }
                json.put("steps", kept);
            }
            return json;
        }

        static Entry fromJson(JSONObject json) throws JSONException {
            Entry entry = plainFromJson(json).peeked(json.optBoolean("peeked"));
            JSONObject nested = json.optJSONObject("notification");
            // Always without lines of its own and never nested deeper, whatever the file says.
            if (nested != null) entry = entry.withNotification(plainFromJson(nested));
            JSONArray kept = json.optJSONArray("steps");
            for (int i = 0; kept != null && i < kept.length(); i++) {
                // A step this version does not know (written by a newer one) is skipped; the line is kept.
                try {
                    JSONObject step = kept.getJSONObject(i);
                    entry = entry.withStep(new Step(StepKind.valueOf(step.getString("kind")), step.getLong("at"),
                            step.optString("detail")));
                } catch (JSONException | IllegalArgumentException unknown) {
                    // Skipped.
                }
            }
            return entry;
        }

        private static Entry plainFromJson(JSONObject json) throws JSONException {
            // An older line's hotspot distance is still read (it is part of what that line recorded), never written.
            OfferSnapshot facts = new OfferSnapshot(
                    json.has("pay") ? json.getInt("pay") : null,
                    json.has("miles") ? json.getDouble("miles") : null,
                    json.has("minutes") ? json.getInt("minutes") : null,
                    json.has("stops") ? json.getInt("stops") : null, null,
                    json.has("finalStopHotspotMiles") && !json.isNull("finalStopHotspotMiles")
                            ? json.optDouble("finalStopHotspotMiles", Double.NaN) : null,
                    itemCount(json.opt("items")), Boolean.TRUE.equals(json.opt("itemCountApplicable")));
            List<String> evidence = new ArrayList<>();
            JSONArray lines = json.optJSONArray("evidence");
            // Lines a version before masking kept are masked as they are read, and stored so on the next write.
            for (int i = 0; lines != null && i < lines.length(); i++) {
                evidence.add(lines.getString(i));
            }
            evidence = PersonalText.accountScreen(evidence) ? new ArrayList<>() : PersonalText.mask(evidence);
            // A line without "model" was written before 0.5.0; one without "bar" was decided at the minimums.
            int model = json.has("model") ? json.optInt("model", LEGACY_MODEL) : LEGACY_MODEL;
            int bar = json.has("bar") ? json.optInt("bar", FilterSettings.BAR_AT_MINIMUMS)
                    : FilterSettings.BAR_AT_MINIMUMS;
            return new Entry(json.getLong("at"), Source.valueOf(json.getString("source")), json.optBoolean("addOn"),
                    facts, json.optLong("required"), OfferRule.Result.valueOf(json.getString("result")),
                    json.optString("reason"), Action.named(json.optString("action")),
                    json.optBoolean("autoDecline"), evidence, null, null, false, Collections.<Step>emptyList(),
                    json.optInt("score", -1), false, bar, json.optBoolean("auto", false), model);
        }

        /** An observed count is a positive integer, never a coerced string, fractional value or inferred zero. */
        private static Integer itemCount(Object value) {
            if (!(value instanceof Number)) return null;
            double count = ((Number) value).doubleValue();
            return Double.isFinite(count) && count > 0 && count <= Integer.MAX_VALUE && count == Math.rint(count)
                    ? (int) count : null;
        }
    }

    /** Whether the line carries a step of this kind. */
    static boolean hasStep(Entry entry, StepKind kind) {
        for (Step step : entry.steps) {
            if (step.kind == kind) return true;
        }
        return false;
    }

    /** How the main page counts an offer: passed, filtered (a failing offer the app acted on), or left to review. */
    enum Tally { PASSED, FILTERED, REVIEW }

    /**
     * What became of an offer, as its ticket's stamp and its flag on the skyline say it: the outcome, not the rules'
     * verdict alone (the ticket keeps that as a line of its own).
     */
    enum Outcome {
        /** The rules let it through. */
        PASSED("PASSED", "Passed"),
        /** The app's decline went through as far as it can tell: Decline tapped, its confirmation tapped, or requested. */
        DECLINED("DECLINED", "Declined"),
        /** Left for the user's review: the rules could not judge it. */
        REVIEW("REVIEW", "Review"),
        /**
         * Left to the user: auto-decline paused, the user took over, Android refused the decline, Dasher's question
         * was not confirmed, or a known failure the app did not decline (a hidden notification declines nothing).
         */
        YOURS("YOURS", "Left to you"),
        /** Later counted or seen as accepted: a step "Accepted…", or a seen Accept tap then a delivery. */
        ACCEPTED("ACCEPTED", "Accepted"),
        /** An automatic click was requested; Dasher has not yet shown explicit delivery progress. */
        REQUESTED("REQUESTED", "Accept requested, not confirmed");

        /** The stamp's word. */
        final String word;
        /** What screen readers hear. */
        final String said;

        Outcome(String word, String said) {
            this.word = word;
            this.said = said;
        }

        /** Whether this outcome says what the rules said (so the ticket need not say the rules' verdict again). */
        boolean isVerdict(OfferRule.Result result) {
            return this == PASSED ? result == OfferRule.Result.KEEP : this == DECLINED
                    ? result == OfferRule.Result.DECLINE : this == REVIEW && result == OfferRule.Result.REVIEW;
        }
    }

    /**
     * The offer's outcome. Accepted wins over everything (the user's acceptance came last). Otherwise an offer left to
     * the user (paused, taken over, refused, its confirmation not tapped) is theirs whatever the rules said; a known
     * failure is declined only when the app's decline went through as far as it can tell (Decline tapped, its
     * confirmation tapped, or requested from the notification), and is the user's otherwise; the rest is as the rules
     * said.
     */
    static Outcome outcome(Entry entry) {
        if (accepted(entry)) return Outcome.ACCEPTED;
        // Request and failure are outcomes of their own, never a green acceptance inferred from the rules passing.
        for (int i = entry.steps.size() - 1; i >= 0; i--) {
            StepKind kind = entry.steps.get(i).kind;
            if (kind == StepKind.AUTO_ACCEPT_NOT_SENT || kind == StepKind.AUTO_ACCEPT_UNCONFIRMED) return Outcome.YOURS;
            if (kind == StepKind.AUTO_ACCEPT_REQUESTED) return Outcome.REQUESTED;
        }
        if (!entry.autoDecline || entry.action == Action.PAUSED || entry.action == Action.USER_TOOK_OVER
                || entry.action == Action.DECLINE_REFUSED || entry.action == Action.CONFIRMATION_NOT_TAPPED) {
            return Outcome.YOURS;
        }
        switch (entry.result) {
            case KEEP:
                return Outcome.PASSED;
            case DECLINE:
                return entry.action == Action.DECLINE_TAPPED || entry.action == Action.CONFIRMATION_TAPPED
                        || entry.action == Action.NOTIFICATION_DECLINE_SENT ? Outcome.DECLINED : Outcome.YOURS;
            default:
                return Outcome.REVIEW;
        }
    }

    /**
     * Whether a step counted the offer as accepted ("Accepted…", by the user or after the app's automatic request, and
     * the kinds older versions wrote), or saw the user's Accept tap on it followed by a delivery screen (one not counted
     * as such, because a delivery was already under way, say), with no sign of a Decline between the two. An
     * {@link StepKind#AR_EXEMPT} mark changes nothing here.
     */
    static boolean accepted(Entry entry) {
        boolean tapped = false;
        for (Step step : entry.steps) {
            switch (step.kind) {
                case ACCEPTED:
                case ACCEPTED_AUTOMATIC:
                case ACCEPTED_LEARNED:
                case ACCEPTED_NOT_LEARNED:
                case ACCEPTED_BEST_SAVED:
                case ACCEPTED_MINIMUMS_UNCHANGED:
                case ACCEPTED_ADD_ON:
                case ACCEPTED_OBSERVED:
                    return true;
                case ACCEPT_TAPPED:
                    tapped = true;
                    break;
                case DECLINE_TAPPED:
                case DECLINE_QUESTION:
                case DECLINE_COUNTED:
                case NOT_ACCEPTED:
                case AUTO_ACCEPT_REQUESTED:
                case AUTO_ACCEPT_UNCONFIRMED:
                    tapped = false;
                    break;
                case NOT_LEARNED:
                    if (tapped && AcceptedOfferTracker.deliveryScreenFollowed(step.detail)) return true;
                    break;
                default:
                    break;
            }
        }
        return false;
    }

    /** A failing offer counts as filtered only when the app did something about it; one left to the user is review. */
    static Tally tally(Entry entry) {
        Outcome outcome = outcome(entry);
        if (outcome == Outcome.PASSED || outcome == Outcome.ACCEPTED) return Tally.PASSED;
        return outcome == Outcome.DECLINED ? Tally.FILTERED : Tally.REVIEW;
    }

    private static final String TOTALS = "decision_totals";
    /** In {@link #TOTALS}: the stored history was folded once ({@link #foldStoredOnce}). */
    private static final String FOLDED_V1 = "folded_v1";
    private static final String OUTCOME_TALLIES_V1 = "outcome_tallies_v1";

    /**
     * Every offer recorded since the history was last cleared, by {@link Tally}: kept apart from the history, which
     * holds only the latest {@value #MAX_ENTRIES}.
     */
    static int[] totals(Context context) {
        synchronized (LOCK) {
            // Loaded first, so the one-time fold of older history has corrected the totals before they are read.
            List<Entry> all = loaded(context);
            android.content.SharedPreferences prefs = context.getSharedPreferences(TOTALS, Context.MODE_PRIVATE);
            int[] totals = new int[Tally.values().length];
            if (!prefs.contains(Tally.PASSED.name())) {
                // First use since totals were kept: start from what the history holds.
                for (Entry entry : all) totals[tally(entry).ordinal()]++;
                writeTotals(context, totals);
                prefs.edit().putBoolean(OUTCOME_TALLIES_V1, true).apply();
                return totals;
            }
            for (Tally tally : Tally.values()) totals[tally.ordinal()] = prefs.getInt(tally.name(), 0);
            if (!prefs.getBoolean(OUTCOME_TALLIES_V1, false)) {
                // Only retained evidence can repair old all-time counters; older history is not invented.
                for (Entry entry : all) {
                    Tally before = legacyTally(entry), after = tally(entry);
                    if (before == after) continue;
                    totals[before.ordinal()] = Math.max(0, totals[before.ordinal()] - 1);
                    totals[after.ordinal()]++;
                }
                writeTotals(context, totals);
                prefs.edit().putBoolean(OUTCOME_TALLIES_V1, true).apply();
            }
            return totals;
        }
    }

    private static Tally legacyTally(Entry entry) {
        if (entry.result == OfferRule.Result.KEEP) return Tally.PASSED;
        if (entry.result == OfferRule.Result.DECLINE && (entry.action == Action.DECLINE_TAPPED
                || entry.action == Action.CONFIRMATION_TAPPED || entry.action == Action.NOTIFICATION_DECLINE_SENT
                || entry.action == Action.NOTIFICATION_HIDDEN)) return Tally.FILTERED;
        return Tally.REVIEW;
    }

    private static void writeTotals(Context context, int[] totals) {
        android.content.SharedPreferences.Editor edit =
                context.getSharedPreferences(TOTALS, Context.MODE_PRIVATE).edit();
        for (Tally tally : Tally.values()) edit.putInt(tally.name(), totals[tally.ordinal()]);
        edit.apply();
    }

    /** Moves one offer's count from {@code from} (null for a new offer) to {@code to}. Under {@link #LOCK}. */
    private static void recount(Context context, Tally from, Tally to) {
        if (from == to) return;
        int[] totals = totals(context);
        if (from != null) totals[from.ordinal()] = Math.max(0, totals[from.ordinal()] - 1);
        totals[to.ordinal()]++;
        writeTotals(context, totals);
    }

    /** Takes back one count from {@code from}: a line found to be another reading of an offer already counted. */
    private static void uncount(Context context, Tally from) {
        int[] totals = totals(context);
        totals[from.ordinal()] = Math.max(0, totals[from.ordinal()] - 1);
        writeTotals(context, totals);
    }

    private static final Object LOCK = new Object();
    /** Reads and atomic replacement share this lock; never held while acquiring LOCK. */
    private static final Object FILE_LOCK = new Object();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();
    /** Oldest first; null until first loaded from disk. Guarded by {@link #LOCK}. */
    private static List<Entry> entries;
    private static volatile long version;

    /** Adds an entry, or upgrades the action on the same offer's latest entry. Never throws. */
    static void record(Context context, Entry entry) {
        record(context, entry, -1);
    }

    /**
     * As {@link #record(Context, Entry)}; a screen offer also takes Dasher's notification of it, and the offer then
     * counts once, with the screen's decision. A new screen line takes the notification read before it
     * ({@link OfferPairing#notificationFor}); a new reading of an offer already on a line takes the notifications
     * recorded since that line ({@link OfferPairing#notificationsSince}): the line keeps the first of them, and the
     * rest are the same offer again and leave the history and the counts.
     *
     * @param secondsLeft the screen's countdown, -1 when none shows
     * @return the notifications this reading took (with their in-memory card tags), oldest first; empty when none
     */
    static List<Entry> record(Context context, Entry entry, int secondsLeft) {
        return record(context, entry, secondsLeft, false);
    }

    /**
     * Records the first reading of a proven new screen incarnation separately even when its numbers match the
     * prior offer. Only the scanner's fresh-countdown/new-offer evidence sets this flag; subsequent reads and
     * confirmation upgrades use the ordinary overload. Notification pairing and once-per-offer counts still apply.
     */
    static List<Entry> record(Context context, Entry entry, int secondsLeft, boolean newScreenInstance) {
        List<Entry> taken = new ArrayList<>();
        try {
            synchronized (LOCK) {
                List<Entry> all = loaded(context);
                // Before anything changes, so a first count starts from the history as it was.
                totals(context);
                for (int i = all.size() - 1; i >= Math.max(0, all.size() - 6); i--) {
                    if (newScreenInstance && entry.source == Source.SCREEN) break;
                    Entry previous = all.get(i);
                    if (previous.source != entry.source) continue;
                    if (!previous.sameOffer(entry)) break;
                    // A replay after a reconnect hands its new card tag to the line it re-evaluates.
                    Entry kept = entry.alertTag != null && !entry.alertTag.equals(previous.alertTag)
                            ? previous.withAlertTag(entry.alertTag, false) : previous;
                    boolean upgrade = entry.action != previous.action && entry.action.weight >= previous.action.weight;
                    Entry merged = upgrade ? kept.withAction(entry.action, entry.autoDecline) : kept;
                    boolean peekedNow = entry.peeked && !merged.peeked;
                    if (peekedNow) merged = merged.peeked(true);
                    List<Integer> since = entry.source == Source.SCREEN
                            ? OfferPairing.notificationsSince(all, i, entry, secondsLeft) : Collections.emptyList();
                    for (int k = since.size() - 1; k >= 0; k--) taken.add(0, all.remove((int) since.get(k)));
                    if (!taken.isEmpty() && merged.notification == null) merged = merged.withNotification(taken.get(0));
                    all.set(i, merged);
                    for (Entry notice : taken) uncount(context, tally(notice));
                    if (upgrade) recount(context, tally(previous), tally(merged));
                    if (upgrade || peekedNow || !taken.isEmpty()) persist(context, all);
                    return taken;
                }
                int notice = entry.source == Source.SCREEN ? OfferPairing.notificationFor(all, entry, secondsLeft) : -1;
                if (notice >= 0) {
                    Entry folded = all.remove(notice);
                    taken.add(folded);
                    Entry added = entry.withNotification(folded);
                    all.add(added);
                    recount(context, tally(folded), tally(added));
                } else {
                    all.add(entry);
                    recount(context, null, tally(entry));
                }
                while (all.size() > MAX_ENTRIES) all.remove(0);
                persist(context, all);
            }
            // A new offer, not a later step of one already recorded: it counts once toward its area.
            AreaMap.note(context, entry);
        } catch (RuntimeException error) {
            DiagnosticLog.log(context, "decision-log", "record failed: " + error.getClass().getSimpleName());
        }
        return taken;
    }

    /** Explicit late completion evidence may correct only the app's just-given-up outcome, never a user's takeover. */
    static void correctConfirmation(Context context, Entry declined) {
        synchronized (LOCK) {
            List<Entry> all = loaded(context);
            for (int i = all.size() - 1; i >= 0; i--) {
                Entry previous = all.get(i);
                if (!previous.sameOffer(declined)) continue;
                if (previous.action != Action.CONFIRMATION_NOT_TAPPED) return;
                Entry corrected = previous.withAction(Action.CONFIRMATION_TAPPED, previous.autoDecline);
                recount(context, tally(previous), tally(corrected));
                all.set(i, corrected);
                persist(context, all);
                return;
            }
        }
    }

    /**
     * Folds Dasher's notification of an offer the screen read moments before ({@link OfferPairing#screenFor}) into
     * that offer's line. It is not a new offer: nothing is counted, and nothing about areas.
     *
     * @return the screen offer's line with the notification folded in, or null when there is none to fold into
     */
    static Entry foldIntoScreen(Context context, Entry notice) {
        try {
            synchronized (LOCK) {
                List<Entry> all = loaded(context);
                totals(context);
                int screen = OfferPairing.screenFor(all, notice);
                if (screen < 0) return null;
                Entry folded = all.get(screen).withNotification(notice);
                all.set(screen, folded);
                persist(context, all);
                return folded;
            }
        } catch (RuntimeException error) {
            DiagnosticLog.log(context, "decision-log", "fold failed: " + error.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * How long ago (ms) a screen offer was read that this notification would fold into ({@link OfferPairing#screenFor}):
     * the user saw that offer moments ago, so Peek leaves its lagging notification alone. -1 when none.
     */
    static long screenReadAgo(Context context, Entry notice) {
        try {
            synchronized (LOCK) {
                List<Entry> all = loaded(context);
                int screen = OfferPairing.screenFor(all, notice);
                return screen < 0 ? -1 : Math.max(0, notice.at - all.get(screen).at);
            }
        } catch (RuntimeException error) {
            return -1;
        }
    }

    /**
     * Adds a step to the screen line of the offer with these facts, recorded at most {@code withinMs} ago: the newest
     * line with exactly these facts, else the newest whose facts agree with them. The line's action never changes, and
     * nothing is counted toward areas. Its tally (and the totals) follow the outcome its steps now give
     * ({@link #outcome}); a mark such as {@link StepKind#AR_EXEMPT} changes neither.
     *
     * @return whether a line took the step
     */
    static boolean markStep(Context context, OfferSnapshot facts, StepKind kind, String detail, long withinMs) {
        if (facts == null) return false;
        long now = System.currentTimeMillis();
        try {
            synchronized (LOCK) {
                List<Entry> all = loaded(context);
                int found = -1;
                for (int pass = 0; pass < 2 && found < 0; pass++) {
                    for (int i = all.size() - 1; i >= 0; i--) {
                        Entry entry = all.get(i);
                        if (entry.at < now - withinMs) break;
                        if (entry.source != Source.SCREEN) continue;
                        boolean match = pass == 0 ? entry.facts.fingerprint().equals(facts.fingerprint())
                                : entry.facts.agreesWith(facts);
                        if (match) {
                            found = i;
                            break;
                        }
                    }
                }
                if (found < 0) return false;
                Entry previous = all.get(found);
                Entry marked = previous.withStep(new Step(kind, now, detail));
                if (marked == previous) return true;
                recount(context, tally(previous), tally(marked));
                all.set(found, marked);
                persist(context, all);
                return true;
            }
        } catch (RuntimeException error) {
            DiagnosticLog.log(context, "decision-log", "step failed: " + error.getClass().getSimpleName());
            return false;
        }
    }

    /** Up to {@code limit} entries, newest first. */
    static List<Entry> recent(Context context, int limit) {
        synchronized (LOCK) {
            List<Entry> all = loaded(context);
            List<Entry> out = new ArrayList<>();
            for (int i = all.size() - 1; i >= 0 && out.size() < limit; i--) out.add(all.get(i));
            return out;
        }
    }

    /** Changes whenever the history changes, so a screen can skip redrawing an unchanged list. */
    static long version() {
        return version;
    }

    static void clear(Context context) {
        synchronized (LOCK) {
            entries = new ArrayList<>();
            writeTotals(context, new int[Tally.values().length]);
            context.getSharedPreferences(TOTALS, Context.MODE_PRIVATE).edit().putBoolean(OUTCOME_TALLIES_V1, true).apply();
            version++;
            File file = file(context);
            WRITER.execute(() -> {
                synchronized (FILE_LOCK) {
                    new AtomicFile(file).delete();
                    new File(file.getPath() + ".tmp").delete();
                }
            });
        }
    }

    /** Plain-text history for a diagnostics report, newest first. */
    static String report(Context context, int limit) {
        List<Entry> recent = recent(context, limit);
        if (recent.isEmpty()) return "No decisions recorded yet.\n";
        SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        StringBuilder out = new StringBuilder();
        for (Entry entry : recent) {
            out.append(time.format(new Date(entry.at)))
                    .append(" | ").append(entry.source.name().toLowerCase(Locale.US))
                    .append(entry.peeked ? " (peeked)" : "")
                    .append(entry.addOn ? " add-on" : "")
                    .append(" | ").append(entry.result)
                    .append(" | pay ").append(entry.facts.payCents == null ? "?" : money(entry.facts.payCents))
                    .append(" | needed ").append(entry.requiredCents == 0 ? "-" : money(entry.requiredCents))
                    .append(score(entry))
                    .append(bar(entry))
                    .append(" | ").append(facts(entry.facts))
                    .append(" | ").append(entry.reason)
                    .append(" | ").append(entry.action.label)
                    .append(" | outcome ").append(outcome(entry).word)
                    .append(hasStep(entry, StepKind.AR_EXEMPT) ? " | ar-exempt" : "")
                    .append(entry.autoDecline ? "" : " | auto-decline paused")
                    .append('\n');
            if (!entry.evidence.isEmpty()) {
                List<String> read = entry.evidence.subList(0, Math.min(REPORT_EVIDENCE_LINES, entry.evidence.size()));
                out.append("    read: ").append(PersonalText.mask(read)).append('\n');
            }
            Entry n = entry.notification;
            if (n != null) {
                out.append("    notification ").append(noticeWhen(entry))
                        .append(" | ").append(n.result)
                        .append(" | pay ").append(n.facts.payCents == null ? "?" : money(n.facts.payCents))
                        .append(score(n))
                        .append(" | ").append(n.reason)
                        .append(" | ").append(n.action.label)
                        .append('\n');
            }
            // What became of the offer, as the summary after a dash words it ("then"): nothing is learned from it.
            SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
            for (Step step : entry.steps) {
                out.append("    then ").append(clock.format(new Date(step.at))).append(' ').append(step.text())
                        .append('\n');
            }
        }
        return out.toString();
    }

    /**
     * " | score 85%" for a line's score as a percent of the minimums, " | area score 121%" for an older line's retired
     * area score; "" when it has none.
     */
    private static String score(Entry entry) {
        if (entry.scorePercent < 0) return "";
        return (entry.model >= MODEL ? " | score " : " | area score ") + entry.scorePercent + "%";
    }

    /**
     * " | bar 82% auto" when the line was decided at a bar other than exactly the minimums or with Autopilot on
     * ("auto": Autopilot set it); "" otherwise and for an older line, whose bar is not known.
     */
    private static String bar(Entry entry) {
        if (entry.model < MODEL || (entry.barPercent == FilterSettings.BAR_AT_MINIMUMS && !entry.autopilot)) return "";
        return " | bar " + entry.barPercent + "%" + (entry.autopilot ? " auto" : "");
    }

    /** When Dasher's folded notification came, against the screen's reading: "14 s earlier" or "1 s later". */
    static String noticeWhen(Entry entry) {
        if (entry.notification == null) return "";
        long ms = entry.at - entry.notification.at;
        long seconds = Math.round(Math.abs(ms) / 1000.0);
        return seconds + " s " + (ms >= 0 ? "earlier" : "later");
    }

    /**
     * The labels worth keeping: those carrying a figure or a pay/add-on label, masked ({@link PersonalText}) before
     * they are cut, so no name or address is kept. The decision itself was made from the raw labels.
     */
    static List<String> evidence(List<String> labels) {
        List<String> out = new ArrayList<>();
        if (labels == null || PersonalText.accountScreen(labels)) return out;
        for (String label : labels) {
            if (label == null || !EVIDENCE.matcher(label).find()) continue;
            String clean = PersonalText.mask(OfferEvidence.normalize(label));
            out.add(clean.length() > MAX_EVIDENCE_CHARS ? clean.substring(0, MAX_EVIDENCE_CHARS) + "…" : clean);
            if (out.size() == MAX_EVIDENCE_LINES) break;
        }
        return out;
    }

    static String money(long cents) {
        return String.format(Locale.US, "$%.2f", cents / 100.0);
    }

    /** "$7" for whole dollars, otherwise "$7.50". */
    static String shortMoney(long cents) {
        return cents % 100 == 0 ? "$" + cents / 100 : money(cents);
    }

    /** "7.2 mi · 21 min · 2 stops", listing only known values. */
    static String facts(OfferSnapshot facts) {
        List<String> parts = new ArrayList<>();
        if (facts.miles != null) parts.add(trimZero(facts.miles) + " mi");
        if (facts.minutes != null) parts.add(facts.minutes + " min");
        if (facts.stops != null) parts.add(facts.stops + (facts.stops == 1 ? " stop" : " stops"));
        if (facts.items != null) parts.add(facts.items + (facts.items == 1 ? " item" : " items"));
        else if (facts.itemCountApplicable) parts.add("item count unknown");
        return parts.isEmpty() ? "no distance, time or stops read" : String.join(" · ", parts);
    }

    private static String trimZero(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** Waits briefly for pending writes; used before reading the file directly and by tests. */
    static void flush() {
        try {
            WRITER.submit(() -> { }).get(2, TimeUnit.SECONDS);
        } catch (Exception interruptedOrTimedOut) {
            if (interruptedOrTimedOut instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    /** Drops the in-memory copy so the next read reloads from disk, as a process restart would. */
    static void forgetCache() {
        synchronized (LOCK) {
            entries = null;
        }
    }

    private static List<Entry> loaded(Context context) {
        if (entries == null) {
            entries = load(context, file(context));
            foldStoredOnce(context, entries);
        }
        return entries;
    }

    /**
     * Once, on the first load after notifications began to be folded: folds the stored history the same way
     * ({@link OfferPairing#foldHistory}), and takes each folded notification out of the all-time totals. Double counts
     * older than the kept history cannot be found and stay. Under {@link #LOCK}; never reads totals through
     * {@link #totals}, which loads.
     */
    private static void foldStoredOnce(Context context, List<Entry> all) {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences(TOTALS, Context.MODE_PRIVATE);
            if (prefs.getBoolean(FOLDED_V1, false)) return;
            List<Entry> folded = OfferPairing.foldHistory(all);
            if (!folded.isEmpty()) {
                if (prefs.contains(Tally.PASSED.name())) {
                    int[] totals = new int[Tally.values().length];
                    for (Tally tally : Tally.values()) totals[tally.ordinal()] = prefs.getInt(tally.name(), 0);
                    for (Entry notice : folded) {
                        int i = tally(notice).ordinal();
                        totals[i] = Math.max(0, totals[i] - 1);
                    }
                    writeTotals(context, totals);
                }
                persist(context, all);
            }
            prefs.edit().putBoolean(FOLDED_V1, true).apply();
        } catch (RuntimeException error) {
            DiagnosticLog.log(context, "decision-log", "history fold failed: " + error.getClass().getSimpleName());
        }
    }

    private static List<Entry> load(Context context, File file) {
        List<Entry> out = new ArrayList<>();
        synchronized (FILE_LOCK) {
            AtomicFile atomic = new AtomicFile(file);
            if (!file.exists() && !new File(file.getPath() + ".bak").exists()) return out;
            try (InputStream in = atomic.openRead(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    bytes.write(buffer, 0, count);
                    if (bytes.size() > 4 * 1024 * 1024) throw new IOException("history exceeds bound");
                }
                JSONArray array = new JSONArray(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                int skipped = 0;
                for (int i = Math.max(0, array.length() - MAX_ENTRIES); i < array.length(); i++) {
                    try { out.add(Entry.fromJson(array.getJSONObject(i))); }
                    catch (JSONException | IllegalArgumentException corruptEntry) { skipped++; }
                }
                if (skipped > 0) DiagnosticLog.log(context, "decision-log", "unreadable history entries skipped: " + skipped);
            } catch (IOException | JSONException | IllegalArgumentException corrupt) {
                DiagnosticLog.log(context, "decision-log", "history could not be recovered: " + corrupt.getClass().getSimpleName());
            }
        }
        return out;
    }

    /** Called under LOCK; immutable entries are serialized off the decision thread. */
    private static void persist(Context context, List<Entry> all) {
        version++;
        List<Entry> snapshot = new ArrayList<>(all);
        Context app = context.getApplicationContext();
        File file = file(app);
        WRITER.execute(() -> {
            synchronized (FILE_LOCK) {
                AtomicFile atomic = new AtomicFile(file);
                FileOutputStream out = null;
                try {
                    JSONArray array = new JSONArray();
                    for (Entry entry : snapshot) array.put(entry.toJson());
                    out = atomic.startWrite();
                    out.write(array.toString().getBytes(StandardCharsets.UTF_8));
                    out.getFD().sync();
                    atomic.finishWrite(out);
                } catch (IOException | JSONException | RuntimeException failure) {
                    if (out != null) atomic.failWrite(out);
                    DiagnosticLog.log(app, "decision-log", "history write failed: " + failure.getClass().getSimpleName());
                }
            }
        });
    }

    private static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE);
    }

    private DecisionLog() {}
}
