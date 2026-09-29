package com.local.dasherfilter;

import java.util.List;
import java.util.Locale;

/** Conservative recognition of DoorDash notifications that appear to describe an offer. */
final class NotificationOffer {
    static boolean isLikelyOffer(List<String> labels) {
        StringBuilder joined = new StringBuilder();
        for (String label : labels) {
            if (label == null) continue;
            String value = label.trim().toLowerCase(Locale.US);
            if (value.isEmpty()) continue;
            if (joined.length() > 0) joined.append(' ');
            joined.append(value);
        }
        String text = joined.toString();
        boolean strongOfferWording = text.contains("new order") || text.contains("new offer") ||
                text.contains("delivery offer") || text.contains("delivery opportunity") ||
                text.contains("new delivery") || text.contains("order request") ||
                text.contains("accept by") || text.contains("tap to accept");
        if (strongOfferWording) return true;

        boolean obviouslyNotOffer = text.contains("weekly earnings") || text.contains("earnings summary") ||
                text.contains("deposit") || text.contains("payout sent") || text.contains("balance") ||
                text.contains("scheduled dash") || text.contains("schedule reminder") ||
                text.contains("new message from") || text.contains("promotion");
        if (obviouslyNotOffer) return false;

        OfferSnapshot offer = OfferParser.parse(labels);
        return offer.payCents != null && (offer.miles != null || offer.stops != null);
    }

    private NotificationOffer() {}
}
