package com.local.dasherfilter;

import java.util.List;

/**
 * Re-runs the production OfferRule on recorded standalone facts against edited settings. Add-ons, Earn by Time, and
 * unreadable records are not replayable. The decline budget is not replayed; a merchant is only what the record kept.
 */
final class WhatIfReplay {
    final int considered, replayed, notReplayable, addOnsSkipped, wouldPass, wouldDecline, wouldReview, recordedPass, recordedDecline, recordedReview, changed;

    private WhatIfReplay(int[] c) {
        considered = c[0]; replayed = c[1]; notReplayable = c[2]; addOnsSkipped = c[3]; wouldPass = c[4]; wouldDecline = c[5];
        wouldReview = c[6]; recordedPass = c[7]; recordedDecline = c[8]; recordedReview = c[9]; changed = c[10];
    }

    static WhatIfReplay run(List<OfferRecord> records, FilterSettings edited, long fromInclusive, long toExclusive) {
        int[] c = new int[11];
        FilterSettings rules = edited.withMaxDeclinesPerHour(0);
        if (records != null) for (OfferRecord r : records) {
            if (r == null || r.at < fromInclusive || r.at >= toExclusive) continue;
            c[0]++;
            if (r.addOn) { c[2]++; c[3]++; continue; }
            if (r.reasonCode == OfferRule.Code.HOURLY_MODE || r.reasonCode == OfferRule.Code.UNREADABLE) { c[2]++; continue; }
            c[1]++;
            OfferRule.Result would = OfferRule.evaluate(r.snapshot(), null, rules, EvaluationContext.at(r.at).withMerchant(r.store)).result;
            c[would == OfferRule.Result.KEEP ? 4 : would == OfferRule.Result.DECLINE ? 5 : 6]++;
            c[r.verdict == OfferRule.Result.KEEP ? 7 : r.verdict == OfferRule.Result.DECLINE ? 8 : 9]++;
            if (would != r.verdict) c[10]++;
        }
        return new WhatIfReplay(c);
    }

    /** "Against your last 7 days: 12 would pass, 30 would be declined, 5 need review (recorded 10 · 32 · 5); 3 add-ons not replayable." */
    String summary(String period) {
        if (considered == 0) return period + ": no offers recorded yet.";
        StringBuilder b = new StringBuilder(period).append(": ");
        if (replayed == 0) b.append("nothing replayable");
        else b.append(wouldPass).append(" would pass, ").append(wouldDecline).append(" would be declined, ").append(wouldReview)
                .append(" need review (recorded ").append(recordedPass).append(" · ").append(recordedDecline).append(" · ").append(recordedReview).append(")");
        if (addOnsSkipped > 0) b.append("; ").append(addOnsSkipped).append(addOnsSkipped == 1 ? " add-on" : " add-ons").append(" not replayable");
        if (notReplayable > addOnsSkipped) b.append("; ").append(notReplayable - addOnsSkipped).append(" Earn by Time/unreadable not replayable");
        return b.append('.').toString();
    }
}
