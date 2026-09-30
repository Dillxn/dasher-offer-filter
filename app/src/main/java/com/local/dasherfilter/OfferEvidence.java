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
    private static final Pattern CASH_RANGE = Pattern.compile("\\$\\s*[\\d.,]+\\s*[-–—]\\s*\\$?\\s*\\d");
    private static final Pattern DASH_BEFORE_CASH = Pattern.compile(".*[-–—]\\s*\\$.*");
    private static final Pattern TIME_RANGE = Pattern.compile(
            "(?i)\\d+(?:\\.\\d+)?\\s*[-–—]\\s*\\d+(?:\\.\\d+)?\\s*(?:min|minutes?|hr|hours?)\\b");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.!…]+$");

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

    /** Screens that show the dash is over or on a break, not just between offers. */
    private static final List<String> DASH_OVER_LABELS = Arrays.asList(
            "dash now", "start dashing", "dash paused", "resume dash", "dash ended", "your dash has ended",
            "dash summary");

    /** True when Dasher shows the dash ended or paused. */
    static boolean isDashOver(List<String> labels) {
        if (labels == null) return false;
        for (String raw : labels) {
            String label = normalize(raw).toLowerCase(Locale.US);
            if (DASH_OVER_LABELS.contains(TRAILING_PUNCTUATION.matcher(label).replaceAll(""))) return true;
        }
        return false;
    }

    static boolean isIdle(List<String> labels) {
        if (labels == null) return false;
        for (String raw : labels) {
            String label = normalize(raw).toLowerCase(Locale.US);
            if (IDLE_LABELS.contains(TRAILING_PUNCTUATION.matcher(label).replaceAll(""))) return true;
        }
        return false;
    }

    /** A post time is fresh when it is at most {@code maxAge} old and no more than 5 s in the future. */
    static boolean fresh(long postedAt, long wallNow, long maxAge) {
        return postedAt > 0 && postedAt <= wallNow + 5000 && wallNow - postedAt <= maxAge;
    }

    private OfferEvidence() {}
}
