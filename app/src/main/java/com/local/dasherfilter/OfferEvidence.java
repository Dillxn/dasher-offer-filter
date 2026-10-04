package com.local.dasherfilter;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, deterministic evidence guards. Missing, conflicting, and malformed are not zero. */
final class OfferEvidence {
    static final int MAX_LABELS = 256;
    static final int MAX_LABEL_LENGTH = 4096;
    static final int MAX_TOTAL_LENGTH = 32768;

    private static final Pattern CASH = Pattern.compile("\\$\\s*([+\\-]?[\\d.,]+)");
    private static final Pattern WELL_FORMED_CASH = Pattern.compile("\\d{1,4}(?:[.,]\\d{1,2})?");
    private static final Pattern CASH_RANGE = Pattern.compile("(?i)\\$\\s*[\\d.,]+\\s*(?:[-–—]|to)\\s*\\$?\\s*\\d");
    private static final Pattern DASH_BEFORE_CASH = Pattern.compile(".*[-–—]\\s*\\$.*");
    private static final Pattern TIME_RANGE = Pattern.compile(
            "(?i)\\d+(?:\\.\\d+)?\\s*(?:[-–—]|to)\\s*\\d+(?:\\.\\d+)?\\s*(?:min|minutes?|hr|hours?)\\b");
    private static final Pattern MILE_RANGE = Pattern.compile(
            "(?i)\\d+(?:\\.\\d+)?\\s*(?:[-–—]|to)\\s*\\d+(?:\\.\\d+)?\\s*(?:mi|miles?)\\b");
    private static final Pattern STOP_RANGE = Pattern.compile(
            "(?i)(?:\\d+\\s*(?:[-–—]|to)\\s*\\d+\\s+stops?\\b|\\bstops?\\s*[:=]\\s*\\d+\\s*(?:[-–—]|to)\\s*\\d+)");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.!…]+$");
    private static final Pattern COUNTDOWN = Pattern.compile("(\\d):([0-5]\\d)");

    /** Screens that prove no offer or delivery is in progress. */
    private static final List<String> IDLE_LABELS = Arrays.asList(
            "finding offers", "looking for offers", "looking for orders", "searching for orders",
            "dash now", "start dashing", "dash paused", "resume dash");

    /** NFKC-normalizes, maps U+2212 to '-', strips format characters, and collapses whitespace. */
    static String normalize(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace('\u2212', '-')
                .replaceAll("\\p{Cf}", "")
                .replaceAll("[\\p{Z}\\s]+", " ")
                .trim();
    }

    /** Rejects label sets too large to be a plausible offer screen or notification. */
    static boolean bounded(List<String> labels) {
        if (labels == null || labels.size() > MAX_LABELS) return false;
        int total = 0;
        for (String label : labels) {
            if (label == null) continue;
            total += label.length();
            if (label.length() > MAX_LABEL_LENGTH || total > MAX_TOTAL_LENGTH) return false;
        }
        return true;
    }

    /**
     * True when any dollar figure is signed, ranged, over-precise, or too large. A malformed figure poisons
     * every pay reading on the screen rather than letting a partial match through.
     */
    static boolean malformedMoney(List<String> labels) {
        for (String raw : labels) {
            String line = normalize(raw);
            if (CASH_RANGE.matcher(line).find() || DASH_BEFORE_CASH.matcher(line).matches()) return true;
            Matcher cash = CASH.matcher(line);
            while (cash.find()) {
                if (!WELL_FORMED_CASH.matcher(cash.group(1)).matches()) return true;
            }
        }
        return false;
    }

    static boolean timeRange(String line) {
        return TIME_RANGE.matcher(normalize(line)).find();
    }

    static boolean distanceRange(String line) {
        return MILE_RANGE.matcher(normalize(line)).find();
    }

    static boolean stopRange(String line) {
        return STOP_RANGE.matcher(normalize(line)).find();
    }

    /**
     * Seconds left on the offer's countdown ("0:35" is 35), from the first label that is only a countdown of at most
     * a minute; -1 when none shows. A clock time such as "9:45 PM" is not a countdown.
     */
    static int secondsLeft(List<String> labels) {
        if (labels == null) return -1;
        for (String label : labels) {
            Matcher countdown = COUNTDOWN.matcher(normalize(label));
            if (!countdown.matches()) continue;
            int seconds = Integer.parseInt(countdown.group(1)) * 60 + Integer.parseInt(countdown.group(2));
            if (seconds <= 60) return seconds;
        }
        return -1;
    }

    /** A pause is still part of the current dash, especially while an update is waiting. */
    static boolean isPaused(List<String> labels) {
        if (labels == null) return false;
        for (String raw : labels) {
            String label = TRAILING_PUNCTUATION.matcher(normalize(raw).toLowerCase(Locale.US)).replaceAll("");
            if (label.equals("dash paused") || label.equals("resume dash")) return true;
        }
        return false;
    }

    /** Screens that positively show the dash is over, not a temporary pause. */
    private static final List<String> DASH_OVER_LABELS = Arrays.asList(
            "dash now", "start dashing", "dash ended", "your dash has ended",
            "dash summary");

    /** True when Dasher shows the dash ended. */
    static boolean isDashOver(List<String> labels) {
        if (labels == null) return false;
        for (String raw : labels) {
            String label = normalize(raw).toLowerCase(Locale.US);
            if (DASH_OVER_LABELS.contains(TRAILING_PUNCTUATION.matcher(label).replaceAll(""))) return true;
        }
        return false;
    }

    /**
     * Dasher's home before a dash starts (or after it ends): it shows a button labelled exactly "Dash", as the user's
     * report shows it. A decline held until the dash goes on is dropped there.
     */
    static boolean isPreDashHome(List<String> labels) {
        if (labels == null) return false;
        for (String raw : labels) {
            String label = normalize(raw).toLowerCase(Locale.US);
            if (TRAILING_PUNCTUATION.matcher(label).replaceAll("").equals("dash")) return true;
        }
        return false;
    }

    static boolean isIdle(List<String> labels) {
        if (labels == null) return false;
        boolean thisDash = false;
        boolean dashControls = false;
        boolean offerControl = false;
        for (String raw : labels) {
            String label = TRAILING_PUNCTUATION.matcher(normalize(raw).toLowerCase(Locale.US)).replaceAll("");
            if (IDLE_LABELS.contains(label)) return true;
            if (label.equals("this dash")) thisDash = true;
            if (label.equals("dash preferences") || label.equals("safety tools")) dashControls = true;
            offerControl |= OfferControls.isButton(label, "accept") || OfferControls.isButton(label, "decline");
        }
        // These captured labels together identify the in-dash waiting screen. "This dash" alone also appears in
        // its menu, and "Safety tools" on the pre-dash home; neither alone proves an offer finished.
        return thisDash && dashControls && !offerControl && secondsLeft(labels) < 0
                && !DeclineConfirmation.hasPrompt(labels) && !isPreDashHome(labels) && !DasherScene.showsRoute(labels)
                && !DasherScene.showsEndDashQuestion(labels) && !DasherScene.showsNewOffer(labels);
    }

    /** A post time is fresh when it is at most {@code maxAge} old and no more than 5 s in the future. */
    static boolean fresh(long postedAt, long wallNow, long maxAge) {
        return postedAt > 0 && postedAt <= wallNow + 5000 && wallNow - postedAt <= maxAge;
    }

    private OfferEvidence() {}
}
