package com.local.dasherfilter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explicit marginal evidence only. A bare figure on an add-on screen is never assumed to be an increment, and old
 * accepted-route travel totals are never subtracted from new totals: the route may already be partly driven.
 */
final class AddOnOffer {
    private static final String VALUE = "([0-9]{1,4}(?:[.,][0-9]{1,2})?)(?![0-9.,])";
    private static final String DELTA = "(?:\\+|\\badd(?:s|ed)?|\\badditional|\\bextra|\\bmore)\\s*";
    private static final String[] ADD_ON_PHRASES = {
            "add to route", "add this order", "add order", "add-on", "add on", "additional order",
            "another order", "on your route", "along your route"};

    /** Accepted route context before this add-on; fields are null when unknown. */
    final OfferSnapshot active;
    /** Explicitly added pay, miles, minutes, and stops. */
    final OfferSnapshot incremental;
    /** The route after accepting: explicit totals, or active context plus explicit increments. */
    final OfferSnapshot combined;

    private AddOnOffer(OfferSnapshot active, OfferSnapshot incremental, OfferSnapshot combined) {
        this.active = active;
        this.incremental = incremental;
        this.combined = combined;
    }

    static boolean isLikely(List<String> labels) {
        if (!OfferEvidence.bounded(labels)) return false;
        for (String raw : labels) {
            String label = OfferEvidence.normalize(raw).toLowerCase(Locale.US);
            for (String phrase : ADD_ON_PHRASES) {
                if (label.contains(phrase)) return true;
            }
        }
        return false;
    }

    /**
     * @param route accepted route context, or null when none is known
     * @param labels visible add-on labels
     */
    static AddOnOffer parse(OfferSnapshot route, List<String> labels) {
        OfferSnapshot active = route == null ? OfferSnapshot.UNKNOWN : route;
        if (!OfferEvidence.bounded(labels)) return new AddOnOffer(active, OfferSnapshot.UNKNOWN, OfferSnapshot.UNKNOWN);

        Measure addedPay = read(labels,
                DELTA + "(?:(?:pay|payout)\\s*)?\\$\\s*" + VALUE,
                "\\$\\s*" + VALUE + "\\s*(?:additional|extra|more)\\b");
        Measure totalPay = read(labels, "\\b(?:new\\s+)?total(?:\\s+(?:pay|payout))?\\s*[:=]?\\s*\\$\\s*" + VALUE);
        Measure addedMiles = delta(labels, "(?:mi|miles?)");
        Measure totalMiles = total(labels, "(?:distance|mileage|miles?)", "(?:mi|miles?)");
        Measure addedMinutes = delta(labels, "(?:min|minutes?)");
        Measure totalMinutes = total(labels, "(?:time|duration)", "(?:min|minutes?)");
        Measure addedStops = delta(labels, "stops?");
        Measure totalStops = total(labels, "stops?", "stops?");

        Integer pay = addedPay.money();
        Integer payTotal = totalPay.money();
        if (OfferEvidence.malformedMoney(labels) || addedPay.conflict || totalPay.conflict) {
            pay = null;
            payTotal = null;
        } else if (pay != null && payTotal != null && active.payCents != null
                && (long) active.payCents + pay != payTotal) {
            // An increment and a total that disagree with the accepted route leave both unknown.
            pay = null;
            payTotal = null;
        } else {
            // Pay does not shrink as the route progresses, so it is the one total that may be differenced.
            if (pay == null && payTotal != null && active.payCents != null && payTotal >= active.payCents) {
                pay = payTotal - active.payCents;
            }
            if (payTotal == null && pay != null) payTotal = sum(active.payCents, pay);
        }

        boolean milesConflict = addedMiles.conflict || totalMiles.conflict;
        Double miles = milesConflict ? null : addedMiles.number();
        Double milesTotal = milesConflict ? null : totalMiles.number();
        boolean minutesConflict = addedMinutes.conflict || totalMinutes.conflict || anyTimeRange(labels);
        Integer minutes = minutesConflict ? null : addedMinutes.integer();
        Integer minutesTotal = minutesConflict ? null : totalMinutes.integer();
        boolean stopsConflict = addedStops.conflict || totalStops.conflict;
        Integer stops = stopsConflict ? null : addedStops.integer();
        Integer stopsTotal = stopsConflict ? null : totalStops.integer();
        if (stops != null && stopsTotal != null && active.stops != null && (long) active.stops + stops != stopsTotal) {
            // As with pay: added stops and a new total that disagree with the accepted route leave both unknown.
            stops = null;
            stopsTotal = null;
        }

        // Totals may be composed from known context plus explicit increments, never the other way around.
        if (milesTotal == null && miles != null && active.miles != null) milesTotal = active.miles + miles;
        if (minutesTotal == null && minutes != null) minutesTotal = sum(active.minutes, minutes);
        if (stopsTotal == null && stops != null) stopsTotal = sum(active.stops, stops);

        return new AddOnOffer(active,
                new OfferSnapshot(pay, miles, minutes, stops),
                new OfferSnapshot(payTotal, milesTotal, minutesTotal, stopsTotal));
    }

    String summary() {
        return "add-on explicit increment {" + incremental.summary() + "}; route context {" + combined.summary() + "}";
    }

    private static boolean anyTimeRange(List<String> labels) {
        for (String label : labels) {
            if (OfferEvidence.timeRange(label)) return true;
        }
        return false;
    }

    private static Integer sum(Integer a, Integer b) {
        if (a == null || b == null || (long) a + b > Integer.MAX_VALUE) return null;
        return a + b;
    }

    private static Measure delta(List<String> labels, String unit) {
        return read(labels,
                DELTA + VALUE + "\\s*" + unit + "\\b",
                "(?<![\\d.,+\\-])\\b" + VALUE + "\\s*" + unit + "\\s*(?:additional|extra|more)\\b");
    }

    private static Measure total(List<String> labels, String name, String unit) {
        return read(labels, "\\b(?:new\\s+)?total(?:\\s+" + name + ")?\\s*[:=]?\\s*" + VALUE + "\\s*" + unit + "\\b");
    }

    /** Collects every match of every expression; two different values make the measure a conflict. */
    private static Measure read(List<String> labels, String... expressions) {
        Measure out = new Measure();
        for (String expression : expressions) {
            Pattern pattern = Pattern.compile("(?i)" + expression);
            for (String label : labels) {
                Matcher matcher = pattern.matcher(OfferEvidence.normalize(label));
                while (matcher.find()) out.observe(matcher.group(1));
            }
        }
        return out;
    }

    private static final class Measure {
        BigDecimal value;
        boolean conflict;

        void observe(String text) {
            try {
                BigDecimal next = new BigDecimal(text.replace(',', '.'));
                if (value != null && value.compareTo(next) != 0) conflict = true;
                value = next;
            } catch (NumberFormatException malformed) {
                conflict = true;
            }
        }

        Double number() {
            return conflict || value == null ? null : value.doubleValue();
        }

        Integer integer() {
            if (conflict || value == null) return null;
            try {
                return value.intValueExact();
            } catch (ArithmeticException fractional) {
                return null;
            }
        }

        Integer money() {
            if (conflict || value == null) return null;
            try {
                return value.movePointRight(2).intValueExact();
            } catch (ArithmeticException subCent) {
                return null;
            }
        }
    }
}
