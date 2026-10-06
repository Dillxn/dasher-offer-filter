package com.local.dasherfilter;

/**
 * Readings that cannot describe a real standalone offer: "1 min", "0.1 mi", or "1 stop" for an order that is always
 * a pickup and a drop-off. Such an offer is never counted as a sample of the market (Autopilot's window) or drawn as
 * the example offer; its decision is unchanged (a known failure still declines it).
 */
final class OfferSanity {
    static final int PLAUSIBLE_MINUTES = 5;
    static final double PLAUSIBLE_MILES = 0.5;
    static final int PLAUSIBLE_STOPS = 2;

    /** Whether any known figure is below what a real standalone offer shows; unknown figures never count. */
    static boolean looksMisread(OfferSnapshot offer) {
        return (offer.minutes != null && offer.minutes < PLAUSIBLE_MINUTES)
                || (offer.miles != null && offer.miles < PLAUSIBLE_MILES)
                || (offer.stops != null && offer.stops < PLAUSIBLE_STOPS);
    }

    private OfferSanity() {}
}
