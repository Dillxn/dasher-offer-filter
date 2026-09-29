package com.local.dasherfilter;

import java.util.Locale;

final class OfferRule {
    enum Result { DECLINE, KEEP, REVIEW }
    static final class Decision {
        final Result result;
        final long requiredCents;
        final String reason;
        Decision(Result result, long requiredCents, String reason) { this.result = result; this.requiredCents = requiredCents; this.reason = reason; }
        String summary() {
            if (requiredCents == 0) return result + ": " + reason;
            return String.format(Locale.US, "%s: required at least $%.2f (%s)", result, requiredCents / 100.0, reason);
        }
    }
    static Decision evaluate(OfferSnapshot offer, FilterSettings settings) {
        boolean missing = settings.maxStops > 0 && offer.stops == null;
        if (settings.maxStops > 0 && offer.stops != null && offer.stops > settings.maxStops)
            return new Decision(Result.DECLINE, 0, offer.stops + " stops exceeds maximum " + settings.maxStops);
        boolean needsPay = settings.flatCents > 0 || settings.perMileCents > 0 || settings.perMinuteCents > 0 || settings.extraStopCents > 0 || settings.risingOffers;
        if (needsPay && offer.payCents == null) return new Decision(Result.REVIEW, 0, "pay not found");
        long required = Math.max(0, settings.flatCents);
        String reason = "flat minimum";
        if (settings.perMileCents > 0) {
            if (offer.miles == null) missing = true;
            else { long byMiles = mileage(settings.perMileCents, offer.miles); if (byMiles > required) { required = byMiles; reason = "dollars per mile"; } }
        }
        if (settings.perMinuteCents > 0) {
            if (offer.minutes == null) missing = true;
            else { long byMinutes = (long) settings.perMinuteCents * offer.minutes; if (byMinutes > required) { required = byMinutes; reason = "dollars per minute"; } }
        }
        if (settings.extraStopCents > 0) {
            if (offer.stops == null) missing = true;
            else { int extra = Math.max(0, offer.stops - 2); required = add(required, (long) settings.extraStopCents * extra); if (extra > 0) reason += " and extra stops"; }
        }
        if (settings.risingOffers && settings.lastAcceptedCents > 0 && (long) settings.lastAcceptedCents + 1 > required) {
            required = (long) settings.lastAcceptedCents + 1;
            reason = String.format(Locale.US, "must beat last accepted payout $%.2f", settings.lastAcceptedCents / 100.0);
        }
        if (offer.payCents != null && offer.payCents < required) return new Decision(Result.DECLINE, required, reason);
        if (missing) return new Decision(Result.REVIEW, required, "an enabled value was not found");
        return new Decision(Result.KEEP, required, "meets enabled rules");
    }
    static Decision evaluateAddOn(AddOnOffer addOn, FilterSettings settings) {
        FilterSettings routeRules = new FilterSettings(settings.enabled, settings.flatCents, settings.perMileCents, settings.perMinuteCents, settings.extraStopCents, settings.maxStops, false, settings.lastAcceptedCents);
        Decision combined = evaluate(addOn.combined, routeRules);
        if (combined.result == Result.DECLINE) return new Decision(Result.DECLINE, combined.requiredCents, "combined route fails: " + combined.reason);
        OfferSnapshot delta = addOn.incremental;
        boolean missing = delta.payCents == null;
        long variable = 0;
        if (settings.perMileCents > 0) { if (delta.miles == null) missing = true; else variable = Math.max(variable, mileage(settings.perMileCents, delta.miles)); }
        if (settings.perMinuteCents > 0) { if (delta.minutes == null) missing = true; else variable = Math.max(variable, (long) settings.perMinuteCents * delta.minutes); }
        if (settings.extraStopCents > 0) {
            if (delta.stops == null || addOn.active.stops == null) missing = true;
            else {
                long before = Math.max(0L, (long) addOn.active.stops - 2);
                long after = Math.max(0L, (long) addOn.active.stops + delta.stops - 2);
                variable = add(variable, (long) settings.extraStopCents * (after - before));
            }
        }
        if (delta.payCents != null && delta.payCents < variable) return new Decision(Result.DECLINE, variable, "add-on marginal economics");
        if (combined.result == Result.REVIEW || missing) return new Decision(Result.REVIEW, variable, "add-on has missing or ambiguous incremental/route evidence");
        return new Decision(Result.KEEP, variable, "explicit add-on and combined route meet rules");
    }
    private static long mileage(int rate, double miles) {
        try { return java.math.BigDecimal.valueOf(miles).multiply(java.math.BigDecimal.valueOf(rate)).setScale(0, java.math.RoundingMode.CEILING).longValueExact(); }
        catch (ArithmeticException error) { return Long.MAX_VALUE; }
    }
    private static long add(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }
    private OfferRule() {}
}
