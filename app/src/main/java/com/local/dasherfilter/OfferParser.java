package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses explicit values only. Partial numbers and ranges never become exact offer facts. */
final class OfferParser {
    private static final Pattern MONEY = Pattern.compile("\\$\\s*(\\d{1,4}(?:[.,]\\d{1,2})?)(?![\\d.,])");
    private static final String MILE_VALUE =
            "(?<![\\d.,+\\-])\\b(\\d{1,3}(?:\\.\\d{1,2})?)\\s*(?:mi|miles?)\\b";
    private static final Pattern MILES = Pattern.compile("(?i)" + MILE_VALUE);
    private static final Pattern TOTAL_MILES = Pattern.compile(
            "(?i)(?:total(?: trip)?(?: distance| mileage)?\\s*[:=]?\\s*)" + MILE_VALUE);
    private static final Pattern MILES_TOTAL = Pattern.compile("(?i)" + MILE_VALUE + "\\s*(?:in\\s+)?total\\b");
    private static final Pattern MILE_RANGE = Pattern.compile(
            "(?i)\\d+(?:\\.\\d+)?\\s*[-–—]\\s*\\d+(?:\\.\\d+)?\\s*(?:mi|miles?)\\b");
    private static final Pattern MINUTES = Pattern.compile(
            "(?i)(?<![\\d.,+\\-])\\b(?:(\\d{1,2})\\s*(?:hr|hour)s?\\s*)?(\\d{1,3})\\s*(?:min|minute)s?\\b");
    private static final Pattern STOPS = Pattern.compile("(?i)(?<![\\d.,+\\-])\\b(\\d{1,2})\\s+stops?\\b");
    private static final Pattern STOPS_FIRST = Pattern.compile(
            "(?i)\\b(?:total\\s+)?stops?\\s*[:=]\\s*(\\d{1,2})\\b(?![.,]\\d)");
    private static final Pattern STOP_RANGE = Pattern.compile(
            "(?i)(?:\\d+\\s*[-–—]\\s*\\d+\\s+stops?\\b|\\bstops?\\s*[:=]\\s*\\d+\\s*[-–—]\\s*\\d+)");
    private static final Pattern ROUTE_COUNTS = Pattern.compile(
            "(?i)^(\\d{1,2})\\s+pick[ -]?ups?\\s*(?:[,•·+&/|]|and)\\s*"
                    + "(\\d{1,2})\\s+(?:customer\\s+)?drop[ -]?offs?$");
    /** "Multiple dropoffs (2 stops)": a count of one kind of stop, not the route's total. */
    private static final Pattern STOP_BREAKDOWN = Pattern.compile(
            "(?i)(?:pick[ -]?ups?|drop[ -]?offs?)\\s*\\(\\s*\\d{1,2}\\s+stops?\\s*\\)");
    private static final Pattern METRIC_NUMBER = Pattern.compile("\\d{1,3}(?:\\.\\d{1,2})?");
    private static final Pattern METRIC_UNIT = Pattern.compile(
            "(?i)(?:mi\\.?|miles?|stops?|pick[ -]?ups?|(?:customer\\s+)?drop[ -]?offs?)[:=]?");
    /** "+$2.00": an amount added to something else, never a total on its own. */
    private static final Pattern INCREMENT = Pattern.compile("\\+\\s*\\$");
    private static final Pattern TOTAL_LABEL = Pattern.compile("(?i)total(?: distance| mileage)?[:=]?");
    private static final String TRAILING_SEPARATOR = "[:=]$";

    static OfferSnapshot parse(List<String> visibleText) {
        return parse(visibleText, Collections.emptyList());
    }

    /**
     * @param visibleText normalized screen or notification labels; pay and duration are read only from these
     * @param metricParts distance/stop phrases re-assembled from sibling nodes by {@link #joinMetricSiblings}
     */
    static OfferSnapshot parse(List<String> visibleText, List<String> metricParts) {
        if (!OfferEvidence.bounded(visibleText) || !OfferEvidence.bounded(metricParts)) {
            return OfferSnapshot.UNKNOWN;
        }
        List<String> lines = distinctNormalized(visibleText, new ArrayList<>());
        List<String> metrics = distinctNormalized(metricParts, new ArrayList<>(lines));
        Integer pay = OfferEvidence.malformedMoney(lines) ? null : parsePay(lines);
        return new OfferSnapshot(pay, parseMiles(metrics), parseMinutes(lines), parseStops(metrics));
    }

    /**
     * Joins adjacent sibling labels such as {@code "4.2"}, {@code "mi"} into {@code "4.2 mi"}. Only a bare number
     * directly beside a bare unit is joined, so unrelated numbers elsewhere on the screen stay separate.
     */
    static List<String> joinMetricSiblings(List<String> siblings) {
        List<String> combined = new ArrayList<>();
        if (!OfferEvidence.bounded(siblings)) return combined;
        for (int i = 0; i + 1 < siblings.size(); i++) {
            String left = OfferEvidence.normalize(siblings.get(i));
            String right = OfferEvidence.normalize(siblings.get(i + 1));
            String metric = "";
            if (METRIC_NUMBER.matcher(left).matches() && METRIC_UNIT.matcher(right).matches()) {
                metric = left + " " + right.replaceAll(TRAILING_SEPARATOR, "");
            } else if (METRIC_UNIT.matcher(left).matches() && METRIC_NUMBER.matcher(right).matches()) {
                metric = right + " " + left.replaceAll(TRAILING_SEPARATOR, "");
            }
            if (!metric.isEmpty()) {
                combined.add(metric);
                if (i > 0 && TOTAL_LABEL.matcher(OfferEvidence.normalize(siblings.get(i - 1))).matches()) {
                    combined.add("Total " + metric);
                }
                i++; // The unit sibling has been consumed.
                continue;
            }
            if (TOTAL_LABEL.matcher(left).matches() && MILES.matcher(right).find()) combined.add("Total " + right);
        }
        return combined;
    }

    private static List<String> distinctNormalized(List<String> values, List<String> into) {
        for (String value : values) {
            String normalized = OfferEvidence.normalize(value);
            if (!normalized.isEmpty() && !into.contains(normalized)) into.add(normalized);
        }
        return into;
    }

    /**
     * A single amount on a "Guaranteed"/"Total pay" line (or the line right after a bare label) wins. Otherwise
     * the screen must show exactly one amount and no "+$" increment. Rate figures ("/hr", "per mile", ...) and
     * increments are never pay.
     */
    private static Integer parsePay(List<String> lines) {
        Set<Integer> labeled = new HashSet<>();
        Set<Integer> all = new HashSet<>();
        boolean sawIncrement = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String lower = line.toLowerCase(Locale.US);
            if (isRate(lower)) continue;
            boolean payLabel = lower.contains("guaranteed") || lower.contains("total pay");
            Matcher matcher = MONEY.matcher(line);
            boolean lineHasMoney = false;
            while (matcher.find()) {
                if (isIncrement(line, matcher.start())) {
                    sawIncrement = true;
                    continue;
                }
                lineHasMoney = true;
                int amount = cents(matcher.group(1));
                all.add(amount);
                if (payLabel) labeled.add(amount);
            }
            if (payLabel && !lineHasMoney && i + 1 < lines.size()) {
                Integer labelValue = soleTotalAmount(lines.get(i + 1));
                if (labelValue != null) labeled.add(labelValue);
            }
        }
        if (!labeled.isEmpty()) return onlyValue(labeled);
        // Whether an unlabeled amount already includes a "+$" bonus is unknown, so neither is taken as pay.
        return sawIncrement ? null : onlyValue(all);
    }

    /** True when the "$" at {@code dollarIndex} is written as "+$…": an amount added to something else. */
    private static boolean isIncrement(String line, int dollarIndex) {
        int i = dollarIndex - 1;
        while (i >= 0 && Character.isWhitespace(line.charAt(i))) i--;
        return i >= 0 && line.charAt(i) == '+';
    }

    /** The line's only amount, unless the line is a rate or an increment, which can never be a pay total. */
    private static Integer soleTotalAmount(String line) {
        if (isRate(line.toLowerCase(Locale.US)) || INCREMENT.matcher(line).find()) return null;
        Matcher matcher = MONEY.matcher(line);
        if (!matcher.find()) return null;
        int amount = cents(matcher.group(1));
        return matcher.find() ? null : amount;
    }

    private static boolean isRate(String lower) {
        return lower.contains("/hr") || lower.contains("per hour") || lower.contains("/mi")
                || lower.contains("per mile") || lower.contains("/min") || lower.contains("per minute");
    }

    /** An explicit total distance wins over leg distances; otherwise exactly one distance must be shown. */
    private static Double parseMiles(List<String> lines) {
        Set<Double> found = new HashSet<>();
        Set<Double> totals = new HashSet<>();
        for (String line : lines) {
            if (MILE_RANGE.matcher(line).find()) return null;
            collectDoubles(MILES.matcher(line), found);
            collectDoubles(TOTAL_MILES.matcher(line), totals);
            collectDoubles(MILES_TOTAL.matcher(line), totals);
        }
        if (!totals.isEmpty()) return onlyValue(totals);
        return onlyValue(found);
    }

    /**
     * Duration counts only on lines that label it as the offer's time, or on compact metric lines such as
     * {@code "2 stops (7.2 mi) • 21 min"}. Wait times and countdowns are never delivery duration.
     */
    private static Integer parseMinutes(List<String> lines) {
        Set<Integer> found = new HashSet<>();
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.US);
            if (lower.contains("wait") || lower.contains("accept by") || lower.contains("remaining")) continue;
            boolean explicitTime = lower.contains("estimated") || lower.contains("est.")
                    || lower.contains("duration") || lower.contains("total time");
            boolean compactOfferMetric = MILES.matcher(line).find() || STOPS.matcher(line).find();
            if (!explicitTime && !compactOfferMetric) continue;
            if (OfferEvidence.timeRange(line)) return null;
            Matcher matcher = MINUTES.matcher(line);
            while (matcher.find()) {
                int hours = matcher.group(1) == null ? 0 : Integer.parseInt(matcher.group(1));
                found.add(hours * 60 + Integer.parseInt(matcher.group(2)));
            }
        }
        return onlyValue(found);
    }

    private static Integer parseStops(List<String> lines) {
        Set<Integer> found = new HashSet<>();
        for (String line : lines) {
            if (STOP_RANGE.matcher(line).find()) return null;
            if (STOP_BREAKDOWN.matcher(line).find()) continue;
            collectIntegers(STOPS.matcher(line), found);
            collectIntegers(STOPS_FIRST.matcher(line), found);
            Matcher counts = ROUTE_COUNTS.matcher(line);
            if (counts.matches()) found.add(Integer.parseInt(counts.group(1)) + Integer.parseInt(counts.group(2)));
        }
        Integer stops = onlyValue(found);
        return stops != null && stops > 0 ? stops : null;
    }

    private static void collectDoubles(Matcher matcher, Set<Double> into) {
        while (matcher.find()) into.add(Double.parseDouble(matcher.group(1)));
    }

    private static void collectIntegers(Matcher matcher, Set<Integer> into) {
        while (matcher.find()) into.add(Integer.parseInt(matcher.group(1)));
    }

    /** The sole value, or null when the evidence is absent or conflicting. */
    private static <T> T onlyValue(Set<T> values) {
        return values.size() == 1 ? values.iterator().next() : null;
    }

    /** Converts a pre-validated {@code \d{1,4}([.,]\d{1,2})?} amount to cents. */
    private static int cents(String amount) {
        String normalized = amount.replace(',', '.');
        int point = normalized.indexOf('.');
        if (point < 0) return Integer.parseInt(normalized) * 100;
        int whole = Integer.parseInt(normalized.substring(0, point));
        String decimals = normalized.substring(point + 1);
        return whole * 100 + Integer.parseInt(decimals) * (decimals.length() == 1 ? 10 : 1);
    }

    private OfferParser() {}
}
