package com.local.dasherfilter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit marginal evidence only. Stored route estimates are not current remaining-distance facts. */
final class AddOnOffer {
    final OfferSnapshot active;
    final OfferSnapshot incremental;
    final OfferSnapshot combined;
    /**
     * True only when the combined route mileage came from an explicit "Total … mi" / "… mi total" label, never a derived sum.
     * Likewise explicitTotalPay ("New total $…", "Total pay $…") and explicitTotalMinutes ("Total time …"). A combined value
     * derived from the stored route plus the increment depends on stored context (a partly driven route, an unobserved
     * accept), so it is shown for context but never decides an add-on.
     */
    final boolean explicitTotalMiles, explicitTotalPay, explicitTotalMinutes;
    /**
     * True only when the added pay was displayed as an increment ("+$5.00", "Adds $5.00"). An increment derived as "New total"
     * minus the stored route pay depends on the stored route, so it is shown for context but never decides an add-on.
     */
    final boolean explicitIncrementPay;
    /** Earn by Time labels (or an hourly parsed snapshot): every rule entry point returns REVIEW. */
    final boolean hourly;
    private static final String VALUE = "([0-9]{1,4}(?:[.,][0-9]{1,2})?)(?![0-9.,])";
    private static final String DELTA = "(?:\\+|\\badd(?:s|ed)?|\\badditional|\\bextra|\\bmore)\\s*";
    private static final String ROAD = OfferParser.NOT_A_PLACE;
    private static final String[] PHRASES = {"add to route", "add to your route", "adds to your route", "adds to your current route",
            "add to your current route", "to your current route", "add this order", "add this delivery", "add order", "add-on", "add on",
            "additional order", "another order", "on your route", "along your route"};
    // Only travel increments ("+2.1 mi", "+1 stop") are add-on evidence. A bare "+$2.00" is usually Peak Pay or a boost line.
    private static final Pattern INCREMENT_TOKEN = Pattern.compile("(?i)(?<![\\w$])\\+\\s*(?:\\d{1,3}(?:\\.\\d{1,2})?\\s*(?:mi|miles?)\\b" + ROAD + "|\\d{1,2}\\s*stops?\\b)");
    private static final Pattern COMPONENT = Pattern.compile("(?i)\\b(?:boost\\w*|peak\\s*pay|bonus\\w*|tips?|promo\\w*|promotion\\w*|incentive\\w*|challenge\\w*)\\b");
    // A node naming a pay component ("+$2.00 Peak Pay", "+$3.00 boost", "Customer tip +$4.00") never states the add-on's own
    // increment. "(incl. tips)", "with tip" and "tips included" describe the amount itself and do not count.
    private static final Pattern NAMED_COMPONENT = Pattern.compile("(?i)\\b(?:boost\\w*|peak\\s*pay|bonus\\w*|promo\\w*|promotion\\w*|incentive\\w*|challenge\\w*)\\b|" +
            "(?<!incl\\.\\s|incl\\s|including\\s|with\\s)\\btips?\\b(?!\\s+included)");
    private AddOnOffer(OfferSnapshot active, OfferSnapshot incremental, OfferSnapshot combined, boolean explicitTotalPay, boolean explicitTotalMiles,
                       boolean explicitTotalMinutes, boolean explicitIncrementPay, boolean hourly) {
        this.active = active; this.incremental = incremental; this.combined = combined;
        this.explicitTotalPay = explicitTotalPay && combined.payCents != null; this.explicitTotalMiles = explicitTotalMiles && combined.miles != null;
        this.explicitTotalMinutes = explicitTotalMinutes && combined.minutes != null;
        this.explicitIncrementPay = explicitIncrementPay; this.hourly = hourly;
    }
    /** The combined route with only its displayed totals; derived sums are unknown here. Stops keep the 0.4.5 derived ceiling. */
    OfferSnapshot shownRoute() {
        return new OfferSnapshot(explicitTotalPay ? combined.payCents : null, explicitTotalMiles ? combined.miles : null,
                explicitTotalMinutes ? combined.minutes : null, combined.stops);
    }
    /**
     * Add-on wording, an "Add to route" control, or explicit "+N mi" / "+N stop" travel increments. A bare "+$" amount is not
     * add-on evidence (split Peak Pay / boost nodes look exactly like it), and pay components are never add-on evidence.
     */
    static boolean isLikely(List<String> labels) {
        if (!OfferEvidence.bounded(labels)) return false;
        for (String raw : labels) {
            String n = OfferEvidence.normalize(raw), s = n.toLowerCase(Locale.US);
            for (String phrase : PHRASES) if (s.contains(phrase)) return true;
            // Case-preserved: a capitalized "Mile" is a place name ("+8 Mile & Gratiot"), never an increment.
            if (INCREMENT_TOKEN.matcher(n).find() && !COMPONENT.matcher(s).find()) return true;
        }
        return false;
    }
    static AddOnOffer parse(OfferSnapshot route, OfferSnapshot shown, List<String> labels) {
        OfferSnapshot active = route == null ? new OfferSnapshot(null, null, null, null) : route;
        if (!OfferEvidence.bounded(labels)) return new AddOnOffer(active, new OfferSnapshot(null, null, null, null), new OfferSnapshot(null, null, null, null), false, false, false, false, shown != null && shown.hourly);
        boolean hourly = (shown != null && shown.hourly) || OfferParser.isHourlyMode(labels);
        List<String> ownPay = new ArrayList<>();
        for (String label : labels) if (!NAMED_COMPONENT.matcher(OfferEvidence.normalize(label)).find()) ownPay.add(label);
        Measure dp = read(ownPay, DELTA + "(?:(?:pay|payout)\\s*)?\\$\\s*" + VALUE, "\\$\\s*" + VALUE + "\\s*(?:additional|extra|more)\\b");
        Measure tp = read(labels, "\\b(?:new\\s+)?total(?:\\s+(?:pay|payout))?\\s*[:=]?\\s*\\$\\s*" + VALUE);
        String miles = "(?:mi|miles?)\\b" + ROAD;
        Measure dm = delta(labels, miles), tm = total(labels, "(?:distance|mileage|miles?)", miles);
        Measure dt = delta(labels, "(?:mins?|minutes?)\\b"), tt = total(labels, "(?:time|duration)", "(?:mins?|minutes?)\\b");
        Measure ds = delta(labels, "stops?\\b"), ts = total(labels, "stops?", "stops?\\b");
        Integer pay = dp.money(), totalPay = tp.money();
        boolean explicitPay = pay != null;
        if (hourly || OfferEvidence.malformedMoney(labels) || OfferEvidence.lowerBoundMoney(labels) || dp.conflict || tp.conflict) { pay = null; totalPay = null; explicitPay = false; }
        else if (pay != null && totalPay != null && active.payCents != null && (long) active.payCents + pay != totalPay) { pay = null; totalPay = null; explicitPay = false; }
        boolean shownTotalPay = totalPay != null;
        if (shownTotalPay || pay != null) {
            // Display only: "New total" minus the stored route pay (explicitIncrementPay stays false), or the stored route pay
            // plus the increment (explicitTotalPay stays false).
            if (pay == null && totalPay != null && active.payCents != null && totalPay >= active.payCents) pay = totalPay - active.payCents;
            if (totalPay == null && pay != null) totalPay = sum(active.payCents, pay);
        }
        Double deltaMiles = dm.conflict || tm.conflict ? null : dm.number();
        Double totalMiles = dm.conflict || tm.conflict ? null : tm.number();
        boolean explicitTotal = totalMiles != null;
        Integer minutes = dt.conflict || tt.conflict ? null : dt.integer();
        Integer totalMinutes = dt.conflict || tt.conflict ? null : tt.integer();
        Integer stops = ds.conflict || ts.conflict ? null : ds.integer();
        Integer totalStops = ds.conflict || ts.conflict ? null : ts.integer();
        for (String label : labels) if (OfferEvidence.timeRange(label)) { minutes = null; totalMinutes = null; }
        boolean explicitMinutes = totalMinutes != null;
        // Unlabeled figures never become deltas. Travel totals cannot be subtracted from an old, partly driven route.
        // Derived sums are exact decimals (1.1 + 0.6 = 1.7) so a per-mile ceiling cannot gain a phantom cent.
        if (totalMiles == null && deltaMiles != null && active.miles != null) totalMiles = BigDecimal.valueOf(active.miles).add(BigDecimal.valueOf(deltaMiles)).doubleValue();
        if (totalMinutes == null && minutes != null) totalMinutes = sum(active.minutes, minutes);
        if (totalStops == null && stops != null) totalStops = sum(active.stops, stops);
        return new AddOnOffer(active, new OfferSnapshot(pay, deltaMiles, minutes, stops), new OfferSnapshot(totalPay, totalMiles, totalMinutes, totalStops),
                shownTotalPay, explicitTotal, explicitMinutes, explicitPay, hourly);
    }
    String summary() {
        return "add-on " + (explicitIncrementPay || incremental.payCents == null ? "explicit" : "derived-pay") + " increment {" + incremental.summary() +
                "}; route context {" + combined.summary() + "}" + (hourly ? " (Earn by Time)" : "");
    }
    private static Integer sum(Integer a, Integer b) { if (a == null || b == null || (long) a + b > Integer.MAX_VALUE) return null; return a + b; }
    private static Measure delta(List<String> labels, String unit) {
        return read(labels, DELTA + VALUE + "\\s*" + unit, "(?<![\\d.,+\\-⁄/])\\b" + VALUE + "\\s*" + unit + "\\s*(?:additional|extra|more)\\b");
    }
    private static Measure total(List<String> labels, String name, String unit) {
        return read(labels, "\\b(?:new\\s+)?total(?:\\s+" + name + ")?\\s*[:=]?\\s*" + VALUE + "\\s*" + unit,
                "(?<![\\d.,+\\-⁄/])\\b" + VALUE + "\\s*" + unit + "\\s*(?:in\\s+)?total\\b");
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
