package com.local.dasherfilter;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses only values shown explicitly on the offer screen. Ambiguity yields null. */
final class OfferParser {
    private static final Pattern MONEY = Pattern.compile("\\$\\s*(\\d{1,4}(?:[.,]\\d{1,2})?)");
    private static final String MILE_VALUE = "(?<![\\d.,+\\-])\\b(\\d{1,3}(?:\\.\\d{1,2})?)\\s*(?:mi|miles?)\\b";
    private static final Pattern MILES = Pattern.compile("(?i)" + MILE_VALUE);
    private static final Pattern TOTAL_MILES = Pattern.compile("(?i)(?:total(?: trip)?(?: distance| mileage)?\\s*[:=]?\\s*)" + MILE_VALUE);
    private static final Pattern MILES_TOTAL = Pattern.compile("(?i)" + MILE_VALUE + "\\s*(?:in\\s+)?total\\b");
    private static final Pattern MILE_RANGE = Pattern.compile("(?i)\\d+(?:\\.\\d+)?\\s*[-–—]\\s*\\d+(?:\\.\\d+)?\\s*(?:mi|miles?)\\b");
    private static final Pattern MINUTES = Pattern.compile("(?i)\\b(?:(\\d{1,2})\\s*(?:hr|hour)s?\\s*)?(\\d{1,3})\\s*(?:min|minute)s?\\b");
    private static final Pattern STOPS = Pattern.compile("(?i)(?<![\\d.,+\\-])\\b(\\d{1,2})\\s+stops?\\b");
    private static final Pattern STOPS_FIRST = Pattern.compile("(?i)\\b(?:total\\s+)?stops?\\s*[:=]\\s*(\\d{1,2})\\b(?![.,]\\d)");
    private static final Pattern STOP_RANGE = Pattern.compile("(?i)(?:\\d+\\s*[-–—]\\s*\\d+\\s+stops?\\b|\\bstops?\\s*[:=]\\s*\\d+\\s*[-–—]\\s*\\d+)");
    private static final Pattern ROUTE_COUNTS = Pattern.compile("(?i)^(\\d{1,2})\\s+pick[ -]?ups?\\s*(?:[,•·+&/|]|and)\\s*(\\d{1,2})\\s+(?:customer\\s+)?drop[ -]?offs?$");
    private static final Pattern METRIC_NUMBER = Pattern.compile("\\d{1,3}(?:\\.\\d{1,2})?");
    private static final Pattern METRIC_UNIT = Pattern.compile("(?i)(?:mi\\.?|miles?|stops?|pick[ -]?ups?|(?:customer\\s+)?drop[ -]?offs?)[:=]?");

    static OfferSnapshot parse(List<String> visibleText) {
        return parse(visibleText, Collections.emptyList());
    }

    static OfferSnapshot parse(List<String> visibleText, List<String> metricParts) {
        List<String> lines = new ArrayList<>();
        for (String value : visibleText) {
            if (value != null) {
                String trimmed = normalize(value);
                if (!trimmed.isEmpty() && !lines.contains(trimmed)) lines.add(trimmed);
            }
        }
        List<String> metrics = new ArrayList<>(lines);
        for (String part : metricParts) {
            String normalized = normalize(part);
            if (!normalized.isEmpty() && !metrics.contains(normalized)) metrics.add(normalized);
        }
        // Joining labels must never change the already-verified payout reader.
        return new OfferSnapshot(parsePay(lines), parseMiles(metrics),
                parseMinutes(lines), parseStops(metrics));
    }

    /** Join only a number and a recognized unit in adjacent children of one container. */
    static List<String> joinMetricSiblings(List<String> siblings) {
        List<String> combined = new ArrayList<>();
        for (int i = 0; i + 1 < siblings.size(); i++) {
            String left = normalize(siblings.get(i));
            String right = normalize(siblings.get(i + 1));
            String metric = "";
            if (METRIC_NUMBER.matcher(left).matches() && METRIC_UNIT.matcher(right).matches()) {
                metric = left + " " + right.replaceAll("[:=]$", "");
            } else if (METRIC_UNIT.matcher(left).matches() && METRIC_NUMBER.matcher(right).matches()) {
                metric = right + " " + left.replaceAll("[:=]$", "");
            }
            if (!metric.isEmpty()) {
                combined.add(metric);
                if (i > 0 && normalize(siblings.get(i - 1)).matches("(?i)total(?: distance| mileage)?[:=]?")) {
                    combined.add("Total " + metric);
                }
                // A unit belongs to this number, not also to the next sibling's count.
                i++;
                continue;
            }
            if (left.matches("(?i)total(?: distance| mileage)?[:=]?") && MILES.matcher(right).find()) {
                combined.add("Total " + right);
            }
        }
        return combined;
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Z}\\s]+", " ").trim();
    }

    private static Integer parsePay(List<String> lines) {
        Set<Integer> labeled = new HashSet<>();
        Set<Integer> all = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String lower = line.toLowerCase(Locale.US);
            if (lower.contains("/hr") || lower.contains("per hour") ||
                    lower.contains("/mi") || lower.contains("per mile")) continue;
            boolean payLabel = lower.contains("guaranteed") ||
                    lower.contains("total pay");
            Matcher matcher = MONEY.matcher(line);
            while (matcher.find()) {
                int amount = cents(matcher.group(1));
                all.add(amount);
                if (payLabel) labeled.add(amount);
            }
            // Accessibility trees often put the label and amount in adjacent nodes.
            if (payLabel && !MONEY.matcher(line).find() && i + 1 < lines.size()) {
                Matcher next = MONEY.matcher(lines.get(i + 1));
                if (next.find()) {
                    int amount = cents(next.group(1));
                    if (!next.find()) labeled.add(amount);
                }
            }
        }
        if (labeled.size() == 1) return labeled.iterator().next();
        if (!labeled.isEmpty()) return null;
        return all.size() == 1 ? all.iterator().next() : null;
    }

    private static Double parseMiles(List<String> lines) {
        Set<Double> found = new HashSet<>();
        Set<Double> totals = new HashSet<>();
        for (String line : lines) {
            if (MILE_RANGE.matcher(line).find()) return null;
            Matcher matcher = MILES.matcher(line);
            while (matcher.find()) found.add(Double.parseDouble(matcher.group(1)));
            Matcher before = TOTAL_MILES.matcher(line);
            while (before.find()) totals.add(Double.parseDouble(before.group(1)));
            Matcher after = MILES_TOTAL.matcher(line);
            while (after.find()) totals.add(Double.parseDouble(after.group(1)));
        }
        if (!totals.isEmpty()) return totals.size() == 1 ? totals.iterator().next() : null;
        return found.size() == 1 ? found.iterator().next() : null;
    }

    private static Integer parseMinutes(List<String> lines) {
        Set<Integer> found = new HashSet<>();
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.US);
            boolean explicitTime = lower.contains("estimated") || lower.contains("est.") ||
                    lower.contains("duration") || lower.contains("total time");
            boolean compactOfferMetric = MILES.matcher(line).find() || STOPS.matcher(line).find();
            if (!explicitTime && !compactOfferMetric) continue;
            Matcher matcher = MINUTES.matcher(line);
            while (matcher.find()) {
                int hours = matcher.group(1) == null ? 0 : Integer.parseInt(matcher.group(1));
                found.add(hours * 60 + Integer.parseInt(matcher.group(2)));
            }
        }
        return found.size() == 1 ? found.iterator().next() : null;
    }

    private static Integer parseStops(List<String> lines) {
        Set<Integer> found = new HashSet<>();
        for (String line : lines) {
            if (STOP_RANGE.matcher(line).find()) return null;
            Matcher matcher = STOPS.matcher(line);
            while (matcher.find()) found.add(Integer.parseInt(matcher.group(1)));
            Matcher first = STOPS_FIRST.matcher(line);
            while (first.find()) found.add(Integer.parseInt(first.group(1)));
            Matcher counts = ROUTE_COUNTS.matcher(line);
            if (counts.matches()) {
                found.add(Integer.parseInt(counts.group(1)) + Integer.parseInt(counts.group(2)));
            }
        }
        if (found.size() != 1) return null;
        int stops = found.iterator().next();
        return stops > 0 ? stops : null;
    }

    private static int cents(String amount) {
        String normalized = amount.replace(',', '.');
        int point = normalized.indexOf('.');
        if (point < 0) return Integer.parseInt(normalized) * 100;
        int whole = Integer.parseInt(normalized.substring(0, point));
        String decimals = normalized.substring(point + 1);
        return whole * 100 + Integer.parseInt(decimals) * (decimals.length() == 1 ? 10 : 1);
    }
}
