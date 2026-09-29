package com.local.dasherfilter;

import java.util.List;
import java.util.Locale;

/** Offer recognition never promotes a customer message, promotion, or account notification. */
final class NotificationOffer {
    private static final String[] NOT_OFFER_PHRASES = {
            "new message", "message from", "weekly earnings", "earnings summary", "deposit", "payout sent",
            "balance", "scheduled dash", "schedule reminder", "promotion", "dash now and skip"};
    private static final String[] OFFER_PHRASES = {
            "new order", "new offer", "delivery offer", "delivery opportunity", "new delivery", "order request",
            "accept by", "tap to accept"};

    static boolean isLikelyOffer(List<String> labels) {
        if (!OfferEvidence.bounded(labels)) return false;
        String text = String.join(" ", labels).toLowerCase(Locale.US);
        if (containsAny(text, NOT_OFFER_PHRASES)) return false;
        if (containsAny(text, OFFER_PHRASES) || AddOnOffer.isLikely(labels)) return true;
        OfferSnapshot offer = OfferParser.parse(labels);
        return offer.payCents != null && (offer.miles != null || offer.stops != null);
    }

    private static boolean containsAny(String text, String[] phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) return true;
        }
        return false;
    }

    private NotificationOffer() {}
}
