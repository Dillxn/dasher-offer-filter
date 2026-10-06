package com.local.dasherfilter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One offer the user reports from its ticket ("Report this offer"), as it leaves the phone: the decision's figures,
 * reason, action and fixed outcome category, the lines read from the offer masked twice, the current rules as one
 * compact object, the app's and Android's versions, and when it was decided, to the minute (UTC). Never its learning
 * steps, the exact time, the notification's own time or any account or device identifier. It is JSON so that a misread
 * can become a test; the user's own note travels beside it, never inside it.
 *
 * <p>The read lines are masked as the phone keeps them ({@link PersonalText}: "Order for Jane D." reads "Order for
 * [name]"), then every word that is not offer vocabulary is reduced to its shape ("Chick-fil-A" reads "Xxxxx-xxx-A")
 * and runs of four or more digits (not money) to "#". Offer figures stay, so a misread can still be reproduced.
 * Masking is deterministic and masked text masks to itself, so a stored report can be masked again right before it
 * is sent ({@link #remask}).
 */
final class OfferReport {
    /** What the user says went wrong, chosen in the dialog. */
    enum Problem {
        MISREAD("misread", "Misread"),
        WRONG_DECLINE("wrong_decline", "Wrong decline"),
        WRONG_ACCEPT("wrong_accept", "Wrong accept"),
        OTHER("other", "Other");

        final String key;
        final String label;

        Problem(String key, String label) {
            this.key = key;
            this.label = label;
        }

        /** The feedback service's category: a wrong reading or action is a bug. */
        String category() {
            return this == OTHER ? "other" : "bug";
        }
    }

    /** A read line longer than this is cut, so one huge node cannot crowd out the rest. */
    static final int MAX_LABEL_CHARS = 200;
    private static final Pattern WORD = Pattern.compile("\\p{L}+");
    /** Four or more digits not after "$": ZIP codes, house and phone numbers, never an offer's figures. */
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<![$\\d.,])\\d{4,}");
    /**
     * Words an offer screen uses for pay, travel, stops and its controls. Every other word on a read line (a store, a
     * customer, a street) is reduced to its shape before a report leaves the phone.
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
            "name", "address", "phone", "email", "instructions", "card", "street", "s"));

    /**
     * The report, as pretty-printed JSON.
     *
     * @param android "Android 15 (API 35)": the version alone, never the phone's maker or model
     */
    static String text(Problem problem, String appVersion, long versionCode, String android, FilterSettings rules,
                       DecisionLog.Entry entry) {
        return pretty(json(problem, appVersion, versionCode, android, rules, entry));
    }

    static JSONObject json(Problem problem, String appVersion, long versionCode, String android,
                           FilterSettings rules, DecisionLog.Entry entry) {
        JSONObject json = new JSONObject();
        try {
            json.put("report", "offer")
                    .put("problem", problem.key)
                    .put("app", appVersion + " (" + versionCode + ")")
                    .put("android", android)
                    .put("decided", minute(entry.at))
                    .put("decision", entry.result + " — " + entry.reason + " (" + entry.action.label + ")")
                    .put("read", read(entry))
                    .put("entry", redacted(entry))
                    .put("rules", rulesJson(rules));
        } catch (JSONException impossible) {
            // Only strings, numbers, booleans and objects of them are put.
        }
        return json;
    }

    /** "pay $7.90, 7.2 mi · 21 min · 2 stops, needed $10.80, score 73%": what was read, in a line. */
    static String read(DecisionLog.Entry entry) {
        return "pay " + (entry.facts.payCents == null ? "unknown" : DecisionLog.money(entry.facts.payCents)) + ", "
                + DecisionLog.facts(entry.facts)
                + (entry.requiredCents > 0 ? ", needed " + DecisionLog.money(entry.requiredCents) : "")
                + (entry.scorePercent >= 0 ? ", score " + entry.scorePercent + "%" : "");
    }

    /**
     * An entry as a report carries it: its figures, reason, action and one fixed outcome category, its read lines
     * masked twice, and when it was decided to the minute; none of the learning steps, their times or the exact time.
     * Dasher's notification of the same offer, when folded in, keeps only its offset from the screen's reading.
     */
    static JSONObject redacted(DecisionLog.Entry entry) throws JSONException {
        JSONObject json = entry.toJson();
        json.remove("at");
        json.remove("steps");
        json.put("evidence", new JSONArray(clipped(redact(entry.evidence))));
        json.put("outcome", DecisionLog.outcome(entry).name());
        if (entry.notification != null) {
            JSONObject notice = json.getJSONObject("notification");
            notice.remove("at");
            notice.remove("steps");
            notice.put("secondsAfterScreen", Math.round((entry.notification.at - entry.at) / 1000.0));
        }
        return json;
    }

    /** The current rules, compactly, with the floors the adaptive minimum learned. */
    static JSONObject rulesJson(FilterSettings rules) throws JSONException {
        return new JSONObject().put("enabled", rules.enabled).put("flatCents", rules.flatCents)
                .put("perMileCents", rules.perMileCents).put("perMinuteCents", rules.perMinuteCents)
                .put("perStopCents", rules.perStopCents).put("maxStops", rules.maxStops)
                .put("perItemCents", rules.perItemCents)
                .put("hotspotProximityHundredths", rules.hotspotProximityHundredths)
                .put("risingOffers", rules.risingOffers).put("scoreByArea", rules.scoreByArea)
                .put("lastAcceptedCents", rules.lastAcceptedCents)
                .put("minimumScalePercent", rules.minimumScalePercent)
                .put("inWords", rules.describe() + (rules.enabled ? "" : " (paused)"))
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

    /**
     * A stored report masked again with the current rules, right before it is sent, so a report built by an older
     * version receives later masking repairs. Null when it is not a report this class wrote.
     */
    static String remask(String text) {
        try {
            JSONObject json = new JSONObject(text);
            JSONObject entry = json.optJSONObject("entry");
            if (!"offer".equals(json.optString("report")) || entry == null) return null;
            JSONArray lines = entry.optJSONArray("evidence");
            List<String> read = new ArrayList<>();
            for (int i = 0; lines != null && i < lines.length(); i++) read.add(lines.optString(i, ""));
            entry.put("evidence", new JSONArray(clipped(redact(read))));
            entry.remove("steps");
            entry.remove("at");
            return pretty(json);
        } catch (JSONException malformed) {
            return null;
        }
    }

    /**
     * Each read line masked twice: names, addresses, phone numbers, emails and a customer's own words, as the phone
     * keeps them ({@link PersonalText}), then every word that is not offer vocabulary to its shape. A payment, account
     * or earnings screen leaves nothing.
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
                    OFFER_WORDS.contains(found.toLowerCase(Locale.US)) ? found : shape(found)));
        }
        word.appendTail(out);
        return out.toString();
    }

    private static String shape(String word) {
        StringBuilder shape = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); i++) shape.append(Character.isUpperCase(word.charAt(i)) ? 'X' : 'x');
        return shape.toString();
    }

    private static List<String> clipped(List<String> labels) {
        List<String> out = new ArrayList<>();
        for (String label : labels) {
            if (label == null) continue;
            out.add(label.length() > MAX_LABEL_CHARS ? label.substring(0, MAX_LABEL_CHARS) + "…" : label);
        }
        return out;
    }

    /** "2026-10-06T21:02Z": a time to the minute, in UTC, so it names no time zone and no exact moment. */
    static String minute(long at) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(at));
    }

    private static String pretty(JSONObject json) {
        try {
            return json.toString(1);
        } catch (JSONException impossible) {
            return json.toString();
        }
    }

    private OfferReport() {}
}
