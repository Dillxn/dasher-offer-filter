package com.local.dasherfilter;

import java.util.List;
import java.util.Locale;

/** Offer recognition never promotes a customer message, promotion, or account notification. */
final class NotificationOffer {
    static boolean isLikelyOffer(List<String> labels) {
        if (!OfferEvidence.bounded(labels)) return false;
        String text = String.join(" ", labels).toLowerCase(Locale.US);
        if (text.contains("new message") || text.contains("message from") ||
                text.contains("weekly earnings") || text.contains("earnings summary") ||
                text.contains("deposit") || text.contains("payout sent") || text.contains("balance") ||
                text.contains("scheduled dash") || text.contains("schedule reminder") ||
                text.contains("promotion") || text.contains("dash now and skip")) return false;
        if (text.contains("new order") || text.contains("new offer") ||
                text.contains("delivery offer") || text.contains("delivery opportunity") ||
                text.contains("new delivery") || text.contains("order request") ||
                text.contains("accept by") || text.contains("tap to accept") || AddOnOffer.isLikely(labels)) return true;
        OfferSnapshot offer = OfferParser.parse(labels);
        return offer.payCents != null && (offer.miles != null || offer.stops != null);
    }
    private NotificationOffer() {}
}
