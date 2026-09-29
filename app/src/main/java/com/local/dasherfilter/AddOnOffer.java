package com.local.dasherfilter;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Interprets an add-on as a marginal change to the already accepted route. */
final class AddOnOffer {
    private static final String MONEY = "(\\d{1,4}(?:[.,]\\d{1,2})?)";
    private static final String NUMBER = "(\\d{1,3}(?:\\.\\d{1,2})?)";
    private static final Pattern DELTA_MONEY_BEFORE = Pattern.compile(
            "(?i)(?:\\+|add(?:s|ed)?|additional|extra|more)(?:\\s+(?:pay|payout))?\\s*\\$\\s*" + MONEY);
    private static final Pattern DELTA_MONEY_AFTER = Pattern.compile(
            "(?i)\\$\\s*" + MONEY + "\\s*(?:additional|extra|more)\\b");
    private static final Pattern TOTAL_MONEY = Pattern.compile(
            "(?i)(?:new\\s+)?total(?:\\s+(?:pay|payout))?\\s*[:=]?\\s*\\$\\s*" + MONEY);

    private static final Pattern DELTA_MILES_BEFORE = Pattern.compile(
            "(?i)(?:\\+|add(?:s|ed)?|additional|extra|more)\\s*" + NUMBER + "\\s*(?:mi|miles?)\\b");
    private static final Pattern DELTA_MILES_AFTER = Pattern.compile(
            "(?i)" + NUMBER + "\\s*(?:mi|miles?)\\s*(?:additional|extra|more)\\b");
    private static final Pattern TOTAL_MILES = Pattern.compile(
            "(?i)(?:new\\s+)?total(?:\\s+(?:distance|mileage|miles?))?\\s*[:=]?\\s*" +
                    NUMBER + "\\s*(?:mi|miles?)\\b");

    private static final Pattern DELTA_MINUTES_BEFORE = Pattern.compile(
            "(?i)(?:\\+|add(?:s|ed)?|additional|extra|more)\\s*(\\d{1,3})\\s*(?:min|minutes?)\\b");
    private static final Pattern DELTA_MINUTES_AFTER = Pattern.compile(
            "(?i)(\\d{1,3})\\s*(?:min|minutes?)\\s*(?:additional|extra|more)\\b");
    private static final Pattern TOTAL_MINUTES = Pattern.compile(
            "(?i)(?:new\\s+)?total(?:\\s+(?:time|duration))?\\s*[:=]?\\s*(\\d{1,3})\\s*(?:min|minutes?)\\b");

    private static final Pattern DELTA_STOPS_BEFORE = Pattern.compile(
            "(?i)(?:\\+|add(?:s|ed)?|additional|extra|more)\\s*(\\d{1,2})\\s*stops?\\b");
    private static final Pattern DELTA_STOPS_AFTER = Pattern.compile(
            "(?i)(\\d{1,2})\\s*stops?\\s*(?:additional|extra|more)\\b");
    private static final Pattern TOTAL_STOPS = Pattern.compile(
            "(?i)(?:new\\s+)?total(?:\\s+stops?)?\\s*[:=]?\\s*(\\d{1,2})\\s*stops?\\b");

    final OfferSnapshot active;
    final OfferSnapshot incremental;
    final OfferSnapshot combined;

    private AddOnOffer(OfferSnapshot active, OfferSnapshot incremental, OfferSnapshot combined) {
        this.active = active;
        this.incremental = incremental;
        this.combined = combined;
    }

    static boolean isLikely(List<String> labels) {
        for (String label : labels) {
            if (label == null) continue;
            String value = label.trim().toLowerCase(Locale.US);
            if (value.contains("add to route") || value.contains("add this order") ||
                    value.contains("add order") || value.contains("add-on") ||
                    value.contains("add on") || value.contains("additional order") ||
                    value.contains("another order") || value.contains("on your route") ||
                    value.contains("along your route") || value.contains("stacked order")) {
                return true;
            }
        }
        return false;
    }

    static AddOnOffer parse(OfferSnapshot active, OfferSnapshot shown, List<String> labels) {
        Integer deltaPay = uniqueMoney(labels, DELTA_MONEY_BEFORE, DELTA_MONEY_AFTER);
        Integer totalPay = uniqueMoney(labels, TOTAL_MONEY);
        Double deltaMiles = uniqueDouble(labels, DELTA_MILES_BEFORE, DELTA_MILES_AFTER);
        Double totalMiles = uniqueDouble(labels, TOTAL_MILES);
        Integer deltaMinutes = uniqueInt(labels, DELTA_MINUTES_BEFORE, DELTA_MINUTES_AFTER);
        Integer totalMinutes = uniqueInt(labels, TOTAL_MINUTES);
        Integer deltaStops = uniqueInt(labels, DELTA_STOPS_BEFORE, DELTA_STOPS_AFTER);
        Integer totalStops = uniqueInt(labels, TOTAL_STOPS);

        IntPair pay = resolveInt(active.payCents, shown.payCents, deltaPay, totalPay);
        DoublePair miles = resolveDouble(active.miles, shown.miles, deltaMiles, totalMiles);
        IntPair minutes = resolveInt(active.minutes, shown.minutes, deltaMinutes, totalMinutes);
        IntPair stops = resolveInt(active.stops, shown.stops, deltaStops, totalStops);

        return new AddOnOffer(active,
                new OfferSnapshot(pay.delta, miles.delta, minutes.delta, stops.delta),
                new OfferSnapshot(pay.total, miles.total, minutes.total, stops.total));
    }

    String summary() {
        return "add-on incremental {" + incremental.summary() + "}; combined {" + combined.summary() + "}";
    }

    private static IntPair resolveInt(Integer active, Integer fallbackDelta, Integer explicitDelta, Integer explicitTotal) {
        Integer delta = explicitDelta;
        Integer total = explicitTotal;
        if (delta != null && total != null && active != null && active + delta != total) return new IntPair(null, null);
        if (delta == null && total != null && active != null && total >= active) delta = total - active;
        if (total == null && delta != null && active != null) total = active + delta;
        if (delta == null && total == null) {
            delta = fallbackDelta;
            if (delta != null && active != null) total = active + delta;
        }
        if (total == null && explicitTotal != null) total = explicitTotal;
        return new IntPair(nonNegative(delta), nonNegative(total));
    }

    private static DoublePair resolveDouble(Double active, Double fallbackDelta, Double explicitDelta, Double explicitTotal) {
        Double delta = explicitDelta;
        Double total = explicitTotal;
        if (delta != null && total != null && active != null && Math.abs((active + delta) - total) > 0.02) {
            return new DoublePair(null, null);
        }
        if (delta == null && total != null && active != null && total + 0.0001 >= active) delta = total - active;
        if (total == null && delta != null && active != null) total = active + delta;
        if (delta == null && total == null) {
            delta = fallbackDelta;
            if (delta != null && active != null) total = active + delta;
        }
        if (total == null && explicitTotal != null) total = explicitTotal;
        return new DoublePair(nonNegative(delta), nonNegative(total));
    }

    private static Integer uniqueMoney(List<String> labels, Pattern... patterns) {
        Set<Integer> values = new HashSet<>();
        for (String label : labels) {
            if (label == null) continue;
            for (Pattern pattern : patterns) {
                Matcher m = pattern.matcher(label);
                while (m.find()) values.add(cents(m.group(1)));
            }
        }
        return values.size() == 1 ? values.iterator().next() : null;
    }

    private static Integer uniqueInt(List<String> labels, Pattern... patterns) {
        Set<Integer> values = new HashSet<>();
        for (String label : labels) {
            if (label == null) continue;
            for (Pattern pattern : patterns) {
                Matcher m = pattern.matcher(label);
                while (m.find()) values.add(Integer.parseInt(m.group(1)));
            }
        }
        return values.size() == 1 ? values.iterator().next() : null;
    }

    private static Double uniqueDouble(List<String> labels, Pattern... patterns) {
        Set<Double> values = new HashSet<>();
        for (String label : labels) {
            if (label == null) continue;
            for (Pattern pattern : patterns) {
                Matcher m = pattern.matcher(label);
                while (m.find()) values.add(Double.parseDouble(m.group(1)));
            }
        }
        return values.size() == 1 ? values.iterator().next() : null;
    }

    private static int cents(String amount) {
        String normalized = amount.replace(',', '.');
        int point = normalized.indexOf('.');
        if (point < 0) return Integer.parseInt(normalized) * 100;
        int whole = Integer.parseInt(normalized.substring(0, point));
        String decimals = normalized.substring(point + 1);
        return whole * 100 + Integer.parseInt(decimals) * (decimals.length() == 1 ? 10 : 1);
    }

    private static Integer nonNegative(Integer value) { return value != null && value >= 0 ? value : null; }
    private static Double nonNegative(Double value) { return value != null && value >= -0.0001 ? Math.max(0, value) : null; }

    private static final class IntPair {
        final Integer delta;
        final Integer total;
        IntPair(Integer delta, Integer total) { this.delta = delta; this.total = total; }
    }

    private static final class DoublePair {
        final Double delta;
        final Double total;
        DoublePair(Double delta, Double total) { this.delta = delta; this.total = total; }
    }
}
