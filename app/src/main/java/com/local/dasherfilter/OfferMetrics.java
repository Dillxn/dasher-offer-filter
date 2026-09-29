package com.local.dasherfilter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Display efficiency from explicit facts only: $/mi needs pay and miles, $/hr needs pay and minutes. Nothing is estimated. */
final class OfferMetrics {
    /** Cents per mile, rounded half-up; null unless pay is known and miles > 0. */
    static Long centsPerMile(Integer payCents, Double miles) {
        if (payCents == null || miles == null || !Double.isFinite(miles) || miles <= 0) return null;
        return BigDecimal.valueOf(payCents).divide(BigDecimal.valueOf(miles), 0, RoundingMode.HALF_UP).longValue();
    }
    /** Cents per hour, rounded half-up; null unless pay is known and minutes > 0. */
    static Long centsPerHour(Integer payCents, Integer minutes) {
        if (payCents == null || minutes == null || minutes <= 0) return null;
        return BigDecimal.valueOf(payCents).multiply(BigDecimal.valueOf(60)).divide(BigDecimal.valueOf(minutes), 0, RoundingMode.HALF_UP).longValue();
    }
    static Long centsPerMile(OfferSnapshot o) { return o == null ? null : centsPerMile(o.payCents, o.miles); }
    static Long centsPerHour(OfferSnapshot o) { return o == null ? null : centsPerHour(o.payCents, o.minutes); }

    /** "$12.50" */
    static String money(long cents) { BigDecimal v = BigDecimal.valueOf(cents, 2); return (v.signum() < 0 ? "-$" : "$") + v.abs().toPlainString(); }
    /** "4.2", "6.0", "4.25": at least one and at most two decimals. */
    static String miles(double miles) {
        BigDecimal v = BigDecimal.valueOf(miles).stripTrailingZeros();
        if (v.scale() < 1) v = v.setScale(1, RoundingMode.UNNECESSARY);
        if (v.scale() > 2) v = v.setScale(2, RoundingMode.HALF_UP);
        return v.toPlainString();
    }
    /** Hundredths of a mile (600) as "6.0". */
    static String milesHundredths(int hundredths) { return miles(BigDecimal.valueOf(hundredths, 2).doubleValue()); }
    /** "$2.98/mi" */
    static String perMile(long centsPerMile) { return money(centsPerMile) + "/mi"; }
    /** "$31/hr": whole dollars, half-up. */
    static String perHourWhole(long centsPerHour) { return "$" + BigDecimal.valueOf(centsPerHour, 2).setScale(0, RoundingMode.HALF_UP).toPlainString() + "/hr"; }
    /** "$25.00/hr": a rule rate, exact. */
    static String perHour(long centsPerHour) { return money(centsPerHour) + "/hr"; }
    static String stops(int stops) { return stops + (stops == 1 ? " stop" : " stops"); }

    /** "$12.50 · 4.2 mi · 24 min · 2 stops · $2.98/mi · $31/hr", omitting every unknown part. */
    static String summary(OfferSnapshot o) {
        if (o == null) return "";
        List<String> parts = new ArrayList<>();
        if (o.payCents != null) parts.add(money(o.payCents));
        if (o.miles != null) parts.add(miles(o.miles) + " mi");
        if (o.minutes != null) parts.add(o.minutes + " min");
        if (o.stops != null) parts.add(stops(o.stops));
        Long perMile = centsPerMile(o), perHour = centsPerHour(o);
        if (perMile != null) parts.add(perMile(perMile));
        if (perHour != null) parts.add(perHourWhole(perHour));
        return String.join(" · ", parts);
    }
    /** "$2.98/mi · $31/hr" or "" when neither is known. */
    static String efficiency(OfferSnapshot o) {
        List<String> parts = new ArrayList<>();
        Long perMile = centsPerMile(o), perHour = centsPerHour(o);
        if (perMile != null) parts.add(perMile(perMile));
        if (perHour != null) parts.add(perHourWhole(perHour));
        return String.join(" · ", parts);
    }
    private OfferMetrics() {}
}
