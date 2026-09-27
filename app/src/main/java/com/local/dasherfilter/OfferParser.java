package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses only values shown explicitly on the offer screen. Ambiguity yields null. */
final class OfferParser {
    private static final Pattern MONEY = Pattern.compile("\\$\\s*(\\d{1,4}(?:[.,]\\d{1,2})?)");
    private static final Pattern MILES = Pattern.compile("(?i)\\b(\\d{1,3}(?:\\.\\d{1,2})?)\\s*(?:mi|miles)\\b");
    private static final Pattern MINUTES = Pattern.compile("(?i)\\b(?:(\\d{1,2})\\s*(?:hr|hour)s?\\s*)?(\\d{1,3})\\s*(?:min|minute)s?\\b");
    private static final Pattern STOPS = Pattern.compile("(?i)\\b(\\d{1,2})\\s+stops?\\b");

    static OfferSnapshot parse(List<String> visibleText) {
        List<String> lines = new ArrayList<>();
        for (String value : visibleText) {
            if (value != null) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty() && !lines.contains(trimmed)) lines.add(trimmed);
            }
        }
        return new OfferSnapshot(parsePay(lines), parseMiles(lines),
                parseMinutes(lines), parseStops(lines));
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
                    lower.contains("total pay") || lower.contains("earnings");
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
        for (String line : lines) {
            Matcher matcher = MILES.matcher(line);
            while (matcher.find()) found.add(Double.parseDouble(matcher.group(1)));
        }
        return found.size() == 1 ? found.iterator().next() : null;
    }

    private static Integer parseMinutes(List<String> lines) {
        Set<Integer> found = new HashSet<>();
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.US);
            if (!(lower.contains("estimated") || lower.contains("est.") ||
                    lower.contains("duration") || lower.contains("total time"))) continue;
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
            Matcher matcher = STOPS.matcher(line);
            while (matcher.find()) found.add(Integer.parseInt(matcher.group(1)));
        }
        return found.size() == 1 ? found.iterator().next() : null;
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
