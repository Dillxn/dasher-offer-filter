package com.local.dasherfilter;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, deterministic evidence guards. Missing, conflicting, and malformed are not zero. */
final class OfferEvidence {
    private static final Pattern CASH = Pattern.compile("\\$\\s*([+\\-]?[\\d.,]+)");
    private static final Pattern CASH_RANGE = Pattern.compile("\\$\\s*[\\d.,]+\\s*[-–—]\\s*\\$?\\s*\\d");
    // "20-30 min", "21 to 35 mins", "1–2 hrs": a range is never an exact duration.
    private static final Pattern TIME_RANGE = Pattern.compile("(?i)\\d+(?:\\.\\d+)?\\s*(?:[-–—]|to)\\s*\\d+(?:\\.\\d+)?\\s*(?:mins?|minutes?|hrs?|hours?)\\b");
    private static final Pattern MONEY_TOKEN = Pattern.compile("\\$\\s*\\d");
    private static final String INCREMENT = "\\s*\\+\\s*\\d{1,3}(?:\\.\\d{1,2})?\\s*(?:mi|miles?|mins?|minutes?|stops?)\\b";
    private static final Pattern PLUS_AFTER = Pattern.compile("(?i)^(?!" + INCREMENT + ")\\s*\\+");
    // "$7.50+", "+ tips", "plus tips", "before tips", "excl. tips", "tips not included": tips come on top of the shown amount.
    private static final Pattern LOWER_BOUND = Pattern.compile("(?i)\\$\\s*\\d{1,4}(?:[.,]\\d{1,2})?(?!" + INCREMENT + ")\\s*\\+|(?:\\+|\\bplus)\\s*tips?\\b|" +
            "\\b(?:before|excl\\.?|excluding|without|not\\s+including)\\s+(?:customer\\s+)?tips?\\b|\\btips?\\s+(?:not\\s+included|extra|additional)\\b");

    static String normalize(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace('\u2212', '-').replaceAll("\\p{Cf}", "")
                .replaceAll("[\\p{Z}\\s]+", " ").trim();
    }
    static boolean bounded(List<String> labels) {
        if (labels == null || labels.size() > 256) return false;
        int total = 0;
        for (String label : labels) {
            if (label == null) continue;
            if (label.length() > 4096 || (total += label.length()) > 32768) return false;
        }
        return true;
    }
    static boolean malformedMoney(List<String> labels) {
        for (String raw : labels) {
            String line = normalize(raw);
            if (CASH_RANGE.matcher(line).find() || line.matches(".*[-–—]\\s*\\$.*")) return true;
            Matcher m = CASH.matcher(line);
            while (m.find()) {
                if (!m.group(1).matches("\\d{1,4}(?:[.,]\\d{1,2})?")) return true;
            }
        }
        return false;
    }
    static boolean timeRange(String line) { return TIME_RANGE.matcher(normalize(line)).find(); }
    static boolean hasMoneyToken(String line) { return MONEY_TOKEN.matcher(normalize(line)).find(); }
    /** "$7.50+", "$7.50 + $2.00", "+ tips" or "before tips": the displayed amount is a floor or a sum, not the exact payout. */
    static boolean lowerBound(String line) { return LOWER_BOUND.matcher(normalize(line)).find(); }
    /** Text right after a money token starts with '+' that is not a separate "+2 mi"-style increment. */
    static boolean plusFollows(String remainder) { return PLUS_AFTER.matcher(remainder).find(); }
    static boolean lowerBoundMoney(List<String> labels) { for (String label : labels) if (lowerBound(label)) return true; return false; }
    static boolean isIdle(List<String> labels) {
        if (labels == null) return false;
        for (String raw : labels) {
            String s = normalize(raw).toLowerCase(Locale.US).replaceAll("[.!…]+$", "");
            if (s.equals("finding offers") || s.equals("looking for offers") ||
                    s.equals("looking for orders") || s.equals("searching for orders") ||
                    s.equals("dash now") || s.equals("start dashing") || s.equals("dash paused") ||
                    s.equals("resume dash")) return true;
        }
        return false;
    }
    static boolean fresh(long postedAt, long wallNow, long maxAge) {
        return postedAt > 0 && postedAt <= wallNow + 5000 && wallNow - postedAt <= maxAge;
    }
    private OfferEvidence() {}
}
