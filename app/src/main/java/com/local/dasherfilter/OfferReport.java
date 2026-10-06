package com.local.dasherfilter;

import android.content.Context;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
 * reason, action and fixed outcome category, the lines read from the offer masked twice, the current rules and
 * Autopilot's state as one compact object ({@link #rulesJson}: the three minimums, max stops, Autopilot's switch, goal,
 * bar and mode, and the acceptance rate it counts with), the app's and Android's versions, and when it was decided, to
 * the minute (UTC). Never its outcome steps, the exact time, the notification's own time, any screen text beyond its
 * masked read lines, or any account or device identifier. It is JSON so that a misread can become a test; the user's
 * own note travels beside it, never inside it. It leaves only when the user taps Send on a dialog that says what it
 * sends ({@link FeedbackDialogs#OFFER_REPORT_SAYS}).
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

    /** The rules object's keys, in order (finalSpec.privacyConsent, REPORTS): nothing else is ever in it. */
    static final List<String> RULES_KEYS = Collections.unmodifiableList(Arrays.asList(
            "enabled", "flatCents", "perMileCents", "perMinuteCents", "maxStops",
            "autopilot", "autopilotGoal", "barPercent", "autopilotMode", "recovering", "extra",
            "arPercent", "arSource", "arAgeMinutes", "lastChange", "context"));
    /** What {@link #rulesJson} says of when the rules were read. */
    private static final String CONTEXT =
            "current rules when report was built; historical baselines are not reconstructed";

    /**
     * The report as it is sent, with the rules and Autopilot's state as they are now (the same state the shared
     * report's Autopilot line describes). Off the main thread: it reads the stored rules and Autopilot's state.
     *
     * @param android "Android 15 (API 35)": the version alone, never the phone's maker or model
     */
    static String text(Context context, Problem problem, String appVersion, long versionCode, String android,
                       DecisionLog.Entry entry) {
        return text(problem, appVersion, versionCode, android, AutopilotRuntime.status(context,
                AutopilotRuntime.wallClock.getAsLong(), OfferFilterService.isConnected()), entry);
    }

    /**
     * The report, as pretty-printed JSON, with {@code rules} alone: Autopilot's switch, goal and bar as these rules
     * say, and nothing it worked out or read (no mode, no acceptance rate, no last change).
     *
     * @param android "Android 15 (API 35)": the version alone, never the phone's maker or model
     */
    static String text(Problem problem, String appVersion, long versionCode, String android, FilterSettings rules,
                       DecisionLog.Entry entry) {
        return text(problem, appVersion, versionCode, android, rulesAlone(rules), entry);
    }

    /** The report, as pretty-printed JSON, with the rules and Autopilot's state {@code autopilot} describes. */
    static String text(Problem problem, String appVersion, long versionCode, String android,
                       AutopilotText.Status autopilot, DecisionLog.Entry entry) {
        return pretty(json(problem, appVersion, versionCode, android, autopilot, entry));
    }

    static JSONObject json(Problem problem, String appVersion, long versionCode, String android,
                           FilterSettings rules, DecisionLog.Entry entry) {
        return json(problem, appVersion, versionCode, android, rulesAlone(rules), entry);
    }

    static JSONObject json(Problem problem, String appVersion, long versionCode, String android,
                           AutopilotText.Status autopilot, DecisionLog.Entry entry) {
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
                    .put("rules", rulesJson(autopilot));
        } catch (JSONException impossible) {
            // Only strings, numbers, booleans and objects of them are put.
        }
        return json;
    }

    /** Rules with nothing Autopilot worked out or read: no plan, reading or last change. */
    private static AutopilotText.Status rulesAlone(FilterSettings rules) {
        return new AutopilotText.Status(rules, true, null, null, null, 0L);
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
     * masked twice, and when it was decided to the minute; none of its outcome steps, their times or the exact time.
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

    /** The current rules alone, compactly ({@link #rulesJson(AutopilotText.Status)} with nothing Autopilot knows). */
    static JSONObject rulesJson(FilterSettings rules) throws JSONException {
        return rulesJson(rulesAlone(rules));
    }

    /**
     * The current rules and Autopilot's state, compactly, with exactly the keys of {@link #RULES_KEYS}: whether
     * auto-decline is on; the three minimums (per minute in cents per minute, shown in the app as per hour) and max
     * stops; Autopilot's switch, goal (70, 50, or 0 for pay first) and bar; the mode of its latest plan for these rules
     * (null before one), whether it is recovering toward the goal and its stall correction; the acceptance rate it
     * counts with (carried forward from Dasher's latest reading, or its own estimate, to the hundredth; -1 when
     * unknown), where that came from (DASHER, ESTIMATE or UNKNOWN) and how many minutes ago Dasher showed it (-1
     * without a reading); and the last bar change ({from, to, reason, minutesAgo}, the reason a fixed name such as
     * RECOVERY; null when none). Numbers and fixed words only: no screen text, no time of day.
     */
    static JSONObject rulesJson(AutopilotText.Status autopilot) throws JSONException {
        FilterSettings rules = autopilot.rules;
        String mode = autopilot.modeName();
        AutopilotStore.Change change = autopilot.lastChange;
        return new JSONObject()
                .put("enabled", rules.enabled)
                .put("flatCents", rules.flatCents)
                .put("perMileCents", rules.perMileCents)
                .put("perMinuteCents", rules.perMinuteCents)
                .put("maxStops", rules.maxStops)
                .put("autopilot", rules.autopilot)
                .put("autopilotGoal", rules.autopilotGoalPercent)
                .put("barPercent", rules.minimumScalePercent)
                .put("autopilotMode", mode == null ? JSONObject.NULL : mode)
                .put("recovering", autopilot.recovering)
                .put("extra", autopilot.extra)
                .put("arPercent", autopilot.arHundredths < 0 ? -1 : autopilot.arHundredths / 100.0)
                .put("arSource", autopilot.arSource.name())
                .put("arAgeMinutes", autopilot.arAgeMinutes())
                .put("lastChange", change == null ? JSONObject.NULL : new JSONObject()
                        .put("from", change.from)
                        .put("to", change.to)
                        .put("reason", change.why)
                        .put("minutesAgo", Math.max(0, autopilot.wallNow - change.at) / 60_000))
                .put("context", CONTEXT);
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
