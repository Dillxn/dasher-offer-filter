package com.local.dasherfilter;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Offer recognition never promotes a customer message, promotion, or account notification. */
final class NotificationOffer {
    private static final String[] NOT_OFFER_PHRASES = {
            "new message", "message from", "weekly earnings", "earnings summary", "deposit", "payout sent",
            "balance", "scheduled dash", "schedule reminder", "promotion", "dash now and skip"};
    private static final String[] OFFER_PHRASES = {
            "new order", "new offer", "delivery offer", "delivery opportunity", "new delivery", "order request",
            "accept by", "tap to accept"};
    private static final Pattern OFFER_HEADLINE = Pattern.compile("(?i)^new (?:order|delivery)(?:\\b|$)");

    static boolean isLikelyOffer(List<String> labels) {
        if (!OfferEvidence.bounded(labels)) return false;
        // A merchant's name may contain "Balance", "Deposit" or "Promotion". Dasher's explicit headline wins;
        // a customer quoting those words inside a message does not become an offer.
        for (String label : labels) {
            if (OFFER_HEADLINE.matcher(OfferEvidence.normalize(label)).find()) return true;
        }
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
