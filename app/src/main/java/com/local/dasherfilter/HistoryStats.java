package com.local.dasherfilter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Counts for one local day from on-device history. Wording stays honest: decline requests, hides, and observed accepts
 * are counted as what they are, never as confirmed declines or earnings.
 */
final class HistoryStats {
    final int total, screenOffers, notificationOffers, declineRequests, passed, needsReview, hiddenOnly, acceptObserved, pausedShadow, addOns;
    final long acceptObservedPayCents, declineRequestedPayCents;
    /** Pay-weighted $/mi over screen offers with known pay and miles (sum pay / sum miles); null when none. */
    final Long avgCentsPerMile;
    final int avgPerMileOffers;
    final int payMissing, milesMissing, minutesMissing, stopsMissing;

    private HistoryStats(List<OfferRecord> records) {
        int screen = 0, notification = 0, requests = 0, keep = 0, review = 0, hidden = 0, accepted = 0, shadow = 0, addOn = 0;
        int noPay = 0, noMiles = 0, noMinutes = 0, noStops = 0, perMileCount = 0;
        long acceptedPay = 0, requestedPay = 0, payForMiles = 0;
        BigDecimal miles = BigDecimal.ZERO;
        for (OfferRecord r : records) {
            if (r.source == OfferRecord.Source.SCREEN) screen++; else notification++;
            if (r.declineRequested()) { requests++; if (r.payCents != null) requestedPay += r.payCents; }
            if (r.verdict == OfferRule.Result.KEEP) keep++;
            if (r.verdict == OfferRule.Result.REVIEW) review++;
            if (r.hiddenOnly()) hidden++;
            if (r.has(OfferRecord.ACCEPT_OBSERVED)) { accepted++; if (r.payCents != null) acceptedPay += r.payCents; }
            if (r.has(OfferRecord.PAUSED_SHADOW)) shadow++;
            if (r.addOn) addOn++;
            if (r.payCents == null) noPay++;
            if (r.miles == null) noMiles++;
            if (r.minutes == null) noMinutes++;
            if (r.stops == null) noStops++;
            if (r.source == OfferRecord.Source.SCREEN && !r.addOn && r.payCents != null && r.miles != null && r.miles > 0) {
                perMileCount++; payForMiles += r.payCents; miles = miles.add(BigDecimal.valueOf(r.miles));
            }
        }
        total = records.size(); screenOffers = screen; notificationOffers = notification; declineRequests = requests; passed = keep;
        needsReview = review; hiddenOnly = hidden; acceptObserved = accepted; pausedShadow = shadow; addOns = addOn;
        acceptObservedPayCents = acceptedPay; declineRequestedPayCents = requestedPay;
        payMissing = noPay; milesMissing = noMiles; minutesMissing = noMinutes; stopsMissing = noStops;
        avgPerMileOffers = perMileCount;
        avgCentsPerMile = perMileCount == 0 || miles.signum() <= 0 ? null : BigDecimal.valueOf(payForMiles).divide(miles, 0, RoundingMode.HALF_UP).longValue();
    }

    /** Records at or after local midnight of nowWall's day in zone (and not in the future beyond now). */
    static HistoryStats today(List<OfferRecord> records, long nowWall, ZoneId zone) {
        return between(records, startOfDay(nowWall, zone), nowWall + 1);
    }
    static HistoryStats between(List<OfferRecord> records, long fromInclusive, long toExclusive) {
        List<OfferRecord> in = new ArrayList<>();
        if (records != null) for (OfferRecord r : records) if (r != null && r.at >= fromInclusive && r.at < toExclusive) in.add(r);
        return new HistoryStats(in);
    }
    static long startOfDay(long nowWall, ZoneId zone) {
        return Instant.ofEpochMilli(nowWall).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** "Decline requested on $43.50 of offers", "Average $1.85/mi across 12 screen offers", "minutes not shown on 5 of 18 offers". */
    List<String> insights(FilterSettings settings) {
        List<String> out = new ArrayList<>();
        if (declineRequests > 0) out.add("Decline requested on " + OfferMetrics.money(declineRequestedPayCents) + " of offers");
        if (acceptObserved > 0) out.add("Accept observed on " + acceptObserved + (acceptObserved == 1 ? " offer" : " offers") + " showing " + OfferMetrics.money(acceptObservedPayCents));
        if (avgCentsPerMile != null) out.add("Average " + OfferMetrics.perMile(avgCentsPerMile) + " across " + avgPerMileOffers + " screen " + (avgPerMileOffers == 1 ? "offer" : "offers"));
        if (hiddenOnly > 0) out.add(hiddenOnly + (hiddenOnly == 1 ? " notification" : " notifications") + " hidden only — those orders were NOT declined");
        out.addAll(ruleHealth(settings));
        return out;
    }
    /** For each enabled rule's input that was missing: "minutes not shown on 5 of 18 offers". */
    List<String> ruleHealth(FilterSettings s) {
        List<String> out = new ArrayList<>();
        if (s == null || total == 0) return out;
        if (s.hasPayRule() && payMissing > 0) out.add(health("pay", payMissing));
        if ((s.perMileCents > 0 || s.maxMilesHundredths > 0) && milesMissing > 0) out.add(health("miles", milesMissing));
        if ((s.perMinuteCents > 0 || s.perHourCents > 0) && minutesMissing > 0) out.add(health("minutes", minutesMissing));
        if ((s.extraStopCents > 0 || s.maxStops > 0) && stopsMissing > 0) out.add(health("stops", stopsMissing));
        return out;
    }
    private String health(String field, int missing) { return field + " not shown on " + missing + " of " + total + (total == 1 ? " offer" : " offers"); }
}
