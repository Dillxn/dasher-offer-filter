package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One problem report, rendered as a GitHub issue: a short human summary plus a JSON block holding exactly what the
 * app read, so the fixer can turn it into a failing test. Reports are filed only when the user has entered a report
 * token; see {@link ReportOutbox}.
 */
final class ProblemReport {
    /** Every report title starts with this; the fixer workflow acts only on such issues. */
    static final String TITLE_PREFIX = "[offer-report]";
    /** GitHub rejects issue bodies over 65,536 characters. */
    static final int MAX_BODY_CHARS = 60_000;
    private static final int MAX_LABEL_CHARS = 200;
    private static final Pattern WORD = Pattern.compile("\\p{L}+");
    /** Four or more digits not after "$": ZIP codes, house and phone numbers, never an offer's figures. */
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<![$\\d.,])\\d{4,}");
    /**
     * Words an offer screen uses for pay, travel, stops and its controls. Every other word on a screen line (a
     * store, a customer, a street) is masked before a report leaves the phone.
     */
    private static final Set<String> OFFER_WORDS = new HashSet<>(Arrays.asList(
            "pay", "payout", "guaranteed", "guarantee", "incl", "including", "includes", "tip", "tips", "total",
            "earn", "earnings", "estimated", "est", "bonus", "peak", "promo", "challenge", "extra", "base",
            "customer", "dasher", "accept", "accepted", "decline", "declined", "declining", "reject", "add", "added",
            "adding", "route", "offer", "offers", "order", "orders", "deliver", "delivery", "deliveries", "pickup",
            "pickups", "pick", "up", "dropoff", "dropoffs", "drop", "off", "stop", "stops", "multiple", "single",
            "item", "items", "mi", "mile", "miles", "km", "min", "mins", "minute", "minutes", "hr", "hrs", "hour",
            "hours", "sec", "secs", "second", "seconds", "remaining", "left", "expires", "expiring", "away",
            "distance", "time", "due", "by", "in", "to", "for", "of", "the", "a", "an", "and", "or", "with", "your",
            "you", "this", "that", "it", "is", "are", "be", "not", "no", "yes", "back", "go", "cancel",
            "confirm", "keep", "never", "mind", "sure", "want", "do", "lower", "acceptance", "rate", "completion",
            "new", "shop", "now", "at", "from", "per", "avg", "average", "store", "restaurant",
            "dash", "finding", "arrive", "arrived", "on", "while", "until", "more", "less", "than",
            // PersonalText's placeholders ("[name]'s order"), which name what was masked and hide nothing.
            "name", "address", "phone", "email", "instructions", "s"));

    enum Kind {
        /** A visible offer with Accept and Decline that the app could not classify. */
        UNREADABLE_OFFER("Unreadable offer"),
        /** The screen reader threw while reading a Dasher screen. */
        SCAN_ERROR("Screen read error"),
        /** The notification handler threw on a DoorDash notification. */
        NOTIFICATION_ERROR("Notification error"),
        /** A declined offer or its confirmation was still on screen seconds after the decline was requested. */
        DECLINE_STUCK("Decline didn't finish"),
        /** The user tapped Report on an offer. */
        USER_REPORT("You reported"),
        /** The user checked that reports reach the fixer; nothing to fix. */
        TEST("Test report");

        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    final Kind kind;
    final String title;
    final String body;
    /** Stable across repeats of the same problem, so one problem files one issue. */
    final String signature;

    private ProblemReport(Kind kind, String title, String body, String signature) {
        this.kind = kind;
        this.title = title;
        this.body = body;
        this.signature = signature;
    }

    /**
     * @param entry  the decision in question, if any
     * @param labels everything read from the offer screen at the time (empty when not available)
     * @param error  a thrown error, if any
     * @param note   the user's own words, if any
     * @param recent recent decisions for context, newest first
     */
    static ProblemReport build(Kind kind, String appVersion, FilterSettings rules, DecisionLog.Entry entry,
                               List<String> labels, Throwable error, String note, List<DecisionLog.Entry> recent) {
        List<String> screen = PersonalText.recognizedDashScreen(labels) ? redact(labels)
                : java.util.Collections.singletonList(PersonalText.UNKNOWN_NOT_KEPT);
        String title = TITLE_PREFIX + " " + kind.label + ": " + headline(kind, entry, error);
        StringBuilder summary = new StringBuilder()
                .append("Filed by " + AppName.NAME + " ").append(appVersion).append(".\n\n")
                .append("**What happened:** ").append(kind.label).append('\n');
        if (note != null && !note.trim().isEmpty()) summary.append("**User says:** ").append(note.trim()).append('\n');
        if (entry != null) {
            summary.append("**Decision:** ").append(entry.result).append(" — ").append(entry.reason)
                    .append(" (").append(entry.action.label).append(")\n")
                    .append("**Read:** pay ").append(pay(entry.facts)).append(", ")
                    .append(DecisionLog.facts(entry.facts))
                    .append(entry.requiredCents > 0 ? ", needed " + DecisionLog.money(entry.requiredCents) : "")
                    .append(entry.scorePercent >= 0 ? ", score " + entry.scorePercent + "%" : "")
                    .append('\n');
        }
        if (error != null) summary.append("**Error:** ").append(error.getClass().getName()).append('\n');
        summary.append("**Rules:** ").append(rules.describe()).append(rules.enabled ? "" : " (paused)").append("\n\n")
                .append("The JSON below is what the app read; use `labels` (or the entry's `evidence`) as a test "
                        + "fixture.\n\n");

        String signature = signature(kind, appVersion, entry, labels, error);
        JSONObject data = new JSONObject();
        try {
            data.put("kind", kind.name()).put("appVersion", appVersion).put("signature", signature)
                    .put("rules", rulesJson(rules));
            if (note != null && !note.trim().isEmpty()) data.put("note", note.trim());
            if (entry != null) data.put("entry", redacted(entry));
            data.put("labels", new JSONArray(clipped(screen)));
            if (error != null) data.put("error", errorJson(error));
            JSONArray context = new JSONArray();
            for (DecisionLog.Entry item : recent) context.put(redacted(item));
            data.put("recent", context);
        } catch (JSONException impossible) {
            // Only strings, numbers and booleans are put, so this cannot happen.
        }
        String json = "```json\n" + safeJson(data) + "\n```\n";
        String body = summary + json;
        if (body.length() > MAX_BODY_CHARS) body = shrink(summary.toString(), data);
        return new ProblemReport(kind, title, body, signature);
    }

    private static String headline(Kind kind, DecisionLog.Entry entry, Throwable error) {
        if (kind == Kind.TEST) return "pipeline check";
        if (error != null) return error.getClass().getSimpleName();
        if (entry == null) return "no decision";
        return Ui.resultLabel(entry.result) + " " + pay(entry.facts)
                + (entry.requiredCents > 0 ? " (needed " + DecisionLog.money(entry.requiredCents) + ")" : "");
    }

    private static String pay(OfferSnapshot facts) {
        return facts.payCents == null ? "unknown pay" : DecisionLog.money(facts.payCents);
    }

    private static JSONObject rulesJson(FilterSettings rules) throws JSONException {
        return new JSONObject().put("enabled", rules.enabled).put("flatCents", rules.flatCents)
                .put("perMileCents", rules.perMileCents).put("perMinuteCents", rules.perMinuteCents)
                .put("perStopCents", rules.perStopCents).put("maxStops", rules.maxStops)
                .put("perItemCents", rules.perItemCents)
                .put("hotspotProximityHundredths", rules.hotspotProximityHundredths)
                .put("risingOffers", rules.risingOffers).put("scoreByArea", rules.scoreByArea)
                .put("lastAcceptedCents", rules.lastAcceptedCents)
                .put("minimumScalePercent", rules.minimumScalePercent)
                .put("context", "current rules when report was built; historical baselines are not reconstructed")
                .put("declinedByHand", new JSONObject().put("payCents", rules.declined.payCents)
                        .put("minutePay", rules.declined.rates.minutePay).put("minutes", rules.declined.rates.minutes)
                        .put("milePay", rules.declined.rates.milePay).put("miles", rules.declined.rates.miles)
                        .put("stopPay", rules.declined.rates.stopPay).put("stops", rules.declined.rates.stops))
                .put("bestAccepted", new JSONObject()
                        .put("minutePay", rules.best.minutePay).put("minutes", rules.best.minutes)
                        .put("milePay", rules.best.milePay).put("miles", rules.best.miles)
                        .put("stopPay", rules.best.stopPay).put("stops", rules.best.stops)
                        .put("itemPay", rules.best.itemPay).put("items", rules.best.items));
    }

    private static JSONObject errorJson(Throwable error) throws JSONException {
        JSONArray frames = new JSONArray();
        StackTraceElement[] stack = error.getStackTrace();
        for (int i = 0; i < Math.min(12, stack.length); i++) frames.put(stack[i].toString());
        return new JSONObject().put("type", error.getClass().getName())
                .put("message", redact(String.valueOf(error.getMessage())).replaceAll("\\d", "#")).put("stack", frames);
    }

    /**
     * Each screen line masked twice: first names, addresses, phone numbers, emails and a customer's own words, as
     * the phone keeps them ({@link PersonalText}: "Order for Jane D. $7.90" becomes "Order for [name] $7.90"), then
     * every word that is not offer vocabulary, keeping its shape ("Chick-fil-A" becomes "Xxxxx-xxx-A"). Offer figures
     * stay, so a misread can still be reproduced; runs of four or more digits become "#".
     */
    static List<String> redact(List<String> labels) {
        List<String> out = new ArrayList<>();
        if (labels == null || PersonalText.accountScreen(labels)) return out;
        for (String label : PersonalText.mask(labels)) {
            if (label != null) out.add(redact(label));
        }
        return out;
    }

    static String redact(String line) {
        Matcher digits = LONG_NUMBER.matcher(line);
        StringBuffer numbersMasked = new StringBuffer();
        while (digits.find()) digits.appendReplacement(numbersMasked, digits.group().replaceAll("\\d", "#"));
        digits.appendTail(numbersMasked);
        Matcher word = WORD.matcher(numbersMasked);
        StringBuffer out = new StringBuffer();
        while (word.find()) {
            String found = word.group();
            word.appendReplacement(out, Matcher.quoteReplacement(
                    OFFER_WORDS.contains(found.toLowerCase(Locale.US)) ? found : mask(found)));
        }
        word.appendTail(out);
        return out.toString();
    }

    private static String mask(String word) {
        StringBuilder shape = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); i++) shape.append(Character.isUpperCase(word.charAt(i)) ? 'X' : 'x');
        return shape.toString();
    }

    /**
     * An entry as a report carries it: masked lines and one fixed outcome category, but none of the detailed
     * learning/action steps kept on the phone. The category distinguishes a rule PASS from an Accept request,
     * a confirmed acceptance, or an offer left to the user without exporting step timing or detail.
     */
    private static JSONObject redacted(DecisionLog.Entry entry) throws JSONException {
        JSONObject json = entry.toJson()
                .put("evidence", new JSONArray(redact(entry.evidence)))
                .put("outcome", DecisionLog.outcome(entry).name());
        json.remove("steps");
        return json;
    }

    private static List<String> clipped(List<String> labels) {
        List<String> out = new ArrayList<>();
        for (String label : labels) {
            if (label == null) continue;
            out.add(label.length() > MAX_LABEL_CHARS ? label.substring(0, MAX_LABEL_CHARS) + "…" : label);
        }
        return out;
    }

    /** Drops context, then labels, until the body fits GitHub's limit. */
    private static String shrink(String summary, JSONObject data) {
        data.remove("recent");
        String body = summary + "```json\n" + safeJson(data) + "\n```\n";
        if (body.length() <= MAX_BODY_CHARS) return body;
        data.remove("labels");
        body = summary + "```json\n" + safeJson(data) + "\n```\n";
        return body.length() <= MAX_BODY_CHARS ? body : body.substring(0, MAX_BODY_CHARS);
    }

    /** Pretty JSON with any backtick fence in the data defused, so the block cannot end early. */
    private static String safeJson(JSONObject data) {
        try {
            return data.toString(2).replace("```", "'''");
        } catch (JSONException error) {
            return data.toString();
        }
    }

    /**
     * One problem, one report per app version: an error by its type and where it was thrown, a decision by which
     * facts were missing and why, and otherwise the screen's wording with digits masked.
     */
    static String signature(Kind kind, String appVersion, DecisionLog.Entry entry, List<String> labels,
                            Throwable error) {
        StringBuilder shape = new StringBuilder(kind.name()).append('|').append(appVersion);
        if (error != null) {
            shape.append('|').append(error.getClass().getName());
            StackTraceElement[] stack = error.getStackTrace();
            if (stack.length > 0) shape.append('|').append(stack[0].getClassName()).append('.')
                    .append(stack[0].getMethodName());
        } else if (entry != null) {
            OfferSnapshot facts = entry.facts;
            shape.append('|').append(entry.result).append('|').append(entry.reason.replaceAll("\\d", "#"))
                    .append('|').append(entry.addOn).append('|').append(facts.payCents == null)
                    .append(facts.miles == null).append(facts.minutes == null).append(facts.stops == null);
        } else {
            for (String label : redact(labels)) {
                shape.append('|').append(label.replaceAll("\\d+", "#").toLowerCase(Locale.US));
            }
        }
        return Integer.toHexString(shape.toString().hashCode());
    }
}
