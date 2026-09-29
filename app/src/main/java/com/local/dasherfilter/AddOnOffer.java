package com.local.dasherfilter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit marginal evidence only. Stored route estimates are not current remaining-distance facts. */
final class AddOnOffer {
    final OfferSnapshot active;
    final OfferSnapshot incremental;
    final OfferSnapshot combined;
    private static final String VALUE = "([0-9]{1,4}(?:[.,][0-9]{1,2})?)(?![0-9.,])";
    private static final String DELTA = "(?:\\+|\\badd(?:s|ed)?|\\badditional|\\bextra|\\bmore)\\s*";
    private AddOnOffer(OfferSnapshot active, OfferSnapshot incremental, OfferSnapshot combined) { this.active = active; this.incremental = incremental; this.combined = combined; }
    static boolean isLikely(List<String> labels) {
        if (!OfferEvidence.bounded(labels)) return false;
        for (String raw : labels) {
            String s = OfferEvidence.normalize(raw).toLowerCase(Locale.US);
            if (s.contains("add to route") || s.contains("add this order") || s.contains("add order") || s.contains("add-on") || s.contains("add on") || s.contains("additional order") || s.contains("another order") || s.contains("on your route") || s.contains("along your route")) return true;
        }
        return false;
    }
    static AddOnOffer parse(OfferSnapshot route, OfferSnapshot shown, List<String> labels) {
        OfferSnapshot active = route == null ? new OfferSnapshot(null, null, null, null) : route;
        if (!OfferEvidence.bounded(labels)) return new AddOnOffer(active, new OfferSnapshot(null, null, null, null), new OfferSnapshot(null, null, null, null));
        Measure dp = read(labels, DELTA + "(?:(?:pay|payout)\\s*)?\\$\\s*" + VALUE, "\\$\\s*" + VALUE + "\\s*(?:additional|extra|more)\\b");
        Measure tp = read(labels, "\\b(?:new\\s+)?total(?:\\s+(?:pay|payout))?\\s*[:=]?\\s*\\$\\s*" + VALUE);
        Measure dm = delta(labels, "(?:mi|miles?)"), tm = total(labels, "(?:distance|mileage|miles?)", "(?:mi|miles?)");
        Measure dt = delta(labels, "(?:min|minutes?)"), tt = total(labels, "(?:time|duration)", "(?:min|minutes?)");
        Measure ds = delta(labels, "stops?"), ts = total(labels, "stops?", "stops?");
        Integer pay = dp.money(), totalPay = tp.money();
        if (OfferEvidence.malformedMoney(labels) || dp.conflict || tp.conflict) { pay = null; totalPay = null; }
        else if (pay != null && totalPay != null && active.payCents != null && (long) active.payCents + pay != totalPay) { pay = null; totalPay = null; }
        else {
            if (pay == null && totalPay != null && active.payCents != null && totalPay >= active.payCents) pay = totalPay - active.payCents;
            if (totalPay == null && pay != null) totalPay = sum(active.payCents, pay);
        }
        Double miles = dm.conflict || tm.conflict ? null : dm.number();
        Double totalMiles = dm.conflict || tm.conflict ? null : tm.number();
        Integer minutes = dt.conflict || tt.conflict ? null : dt.integer();
        Integer totalMinutes = dt.conflict || tt.conflict ? null : tt.integer();
        Integer stops = ds.conflict || ts.conflict ? null : ds.integer();
        Integer totalStops = ds.conflict || ts.conflict ? null : ts.integer();
        for (String label : labels) if (OfferEvidence.timeRange(label)) { minutes = null; totalMinutes = null; }
        // Unlabeled figures never become deltas. Travel totals cannot be subtracted from an old, partly driven route.
        if (totalMiles == null && miles != null && active.miles != null) totalMiles = active.miles + miles;
        if (totalMinutes == null && minutes != null) totalMinutes = sum(active.minutes, minutes);
        if (totalStops == null && stops != null) totalStops = sum(active.stops, stops);
        return new AddOnOffer(active, new OfferSnapshot(pay, miles, minutes, stops), new OfferSnapshot(totalPay, totalMiles, totalMinutes, totalStops));
    }
    String summary() { return "add-on explicit increment {" + incremental.summary() + "}; route context {" + combined.summary() + "}"; }
    private static Integer sum(Integer a, Integer b) { if (a == null || b == null || (long) a + b > Integer.MAX_VALUE) return null; return a + b; }
    private static Measure delta(List<String> labels, String unit) {
        return read(labels, DELTA + VALUE + "\\s*" + unit + "\\b", "(?<![\\d.,+\\-])\\b" + VALUE + "\\s*" + unit + "\\s*(?:additional|extra|more)\\b");
    }
    private static Measure total(List<String> labels, String name, String unit) {
        return read(labels, "\\b(?:new\\s+)?total(?:\\s+" + name + ")?\\s*[:=]?\\s*" + VALUE + "\\s*" + unit + "\\b");
    }
    private static Measure read(List<String> labels, String... expressions) {
        Measure out = new Measure();
        for (String expression : expressions) {
            Pattern p = Pattern.compile("(?i)" + expression);
            for (String label : labels) {
                Matcher m = p.matcher(OfferEvidence.normalize(label));
                while (m.find()) {
                    try {
                        BigDecimal value = new BigDecimal(m.group(1).replace(',', '.'));
                        if (out.value != null && out.value.compareTo(value) != 0) out.conflict = true;
                        out.value = value;
                    } catch (NumberFormatException error) { out.conflict = true; }
                }
            }
        }
        return out;
    }
    private static final class Measure {
        BigDecimal value; boolean conflict;
        Double number() { return conflict || value == null ? null : value.doubleValue(); }
        Integer integer() { if (conflict || value == null) return null; try { return value.intValueExact(); } catch (ArithmeticException error) { return null; } }
        Integer money() { if (conflict || value == null) return null; try { return value.movePointRight(2).intValueExact(); } catch (ArithmeticException error) { return null; } }
    }
}
