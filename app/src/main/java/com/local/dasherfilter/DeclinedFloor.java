package com.local.dasherfilter;

/**
 * Retired (0.5.0): what offers declined by hand once taught the adaptive minimum. Nothing is learned from a decline
 * any more, and no rule reads these values. This deprecated shell keeps older callers compiling until their own work
 * packages remove them: it holds the old record, says whether it is empty, and computes nothing.
 */
@Deprecated
final class DeclinedFloor {
    static final DeclinedFloor NONE = new DeclinedFloor(0, AcceptedBest.NONE);

    final int payCents;
    final AcceptedBest rates;

    DeclinedFloor(int payCents, AcceptedBest rates) {
        this.payCents = Math.max(0, payCents);
        this.rates = rates == null ? AcceptedBest.NONE : rates;
    }

    boolean isEmpty() {
        return payCents <= 0 && rates.isEmpty();
    }

    /** No declined payout is beaten any more: 0. */
    long beatPay() {
        return 0;
    }

    /** Nothing learned to list: empty. */
    String summary() {
        return "";
    }

    /** Nothing is learned from a decline by hand any more: always the rules' own (empty) record, unchanged. */
    static DeclinedFloor raisedBy(FilterSettings rules, OfferSnapshot declined) {
        return rules.declined;
    }
}
