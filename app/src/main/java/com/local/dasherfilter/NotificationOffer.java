package com.local.dasherfilter;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Offer recognition never promotes a customer message, promotion, or account notification; a merchant name cannot veto a
 * real offer, but a mixed notification is review-only.
 */
final class NotificationOffer {
    /** NOT_OFFER: ignore. OFFER: evaluate normally. REVIEW_ONLY: an offer marker next to non-offer wording; never decline, hide, or ring. */
    enum Kind { NOT_OFFER, OFFER, REVIEW_ONLY }
    private static final int MAX_MERCHANT = 60;
    // Whole leading phrases that identify a non-offer notification regardless of later words.
    private static final String[] NEVER_OFFER_PREFIXES = {"new message", "message from", "weekly earnings", "earnings summary",
            "your weekly earnings", "payout sent", "your payout", "your deposit", "dash now and skip", "scheduled dash", "schedule reminder"};
    // Chat and customer-message wording anywhere: a message that mentions an order is still a message.
    private static final String[] MESSAGE_PHRASES = {"new message", "message from", "sent you a message", "sent a message", "customer message",
            "message about", "replied to", "left you a note", "left a note"};
    // Phrases DoorDash uses for the offer itself. "order from" / "offer from" are not markers: customer messages use them.
    private static final String[] OFFER_MARKERS = {"new order", "new offer", "delivery offer", "delivery opportunity", "new delivery",
            "order request", "accept by", "tap to accept", "flash offer", "tap to view offer", "offer details"};
    // Non-offer wording. Without a marker it excludes; with a marker the notification is review-only ("New order: Balance Bowls").
    private static final String[] SOFT_EXCLUSIONS = {"weekly earnings", "earnings summary", "deposit", "payout sent", "balance",
            "scheduled dash", "schedule reminder", "promotion", "dash now and skip", "cancelled", "canceled"};
    private static final Set<String> ABBREVIATIONS = new HashSet<>(Arrays.asList("mr", "mrs", "ms", "dr", "st", "jr", "sr", "mt", "ft", "co", "inc", "ltd", "bros", "no", "ave", "blvd"));

    /** Only a clean OFFER goes through the decline/hide/ring path; review-only notifications need the classify() caller. */
    static boolean isLikelyOffer(List<String> labels) { return classify(labels) == Kind.OFFER; }

    static Kind classify(List<String> labels) {
        if (!OfferEvidence.bounded(labels)) return Kind.NOT_OFFER;
        for (String label : labels) {
            String s = OfferEvidence.normalize(label).toLowerCase(Locale.US);
            for (String prefix : NEVER_OFFER_PREFIXES) if (s.startsWith(prefix)) return Kind.NOT_OFFER;
        }
        String text = OfferEvidence.normalize(String.join(" ", labels)).toLowerCase(Locale.US);
        for (String phrase : MESSAGE_PHRASES) if (text.contains(phrase)) return Kind.NOT_OFFER;
        boolean marker = AddOnOffer.isLikely(labels);
        for (String m : OFFER_MARKERS) marker |= text.contains(m);
        boolean excluded = false;
        for (String exclusion : SOFT_EXCLUSIONS) excluded |= text.contains(exclusion);
        if (marker) return excluded ? Kind.REVIEW_ONLY : Kind.OFFER;
        if (excluded) return Kind.NOT_OFFER;
        OfferSnapshot offer = OfferParser.parse(labels);
        return offer.payCents != null && (offer.miles != null || offer.stops != null) ? Kind.OFFER : Kind.NOT_OFFER;
    }

    /**
     * The merchant named by an offer notification ("Go to Chick-fil-A", "offer from Mr. Pollo. Tap to view…"), as displayed,
     * at most 60 characters, ending at a sentence end, ':' or a separator; "" when none is named. Call it only for a
     * notification classified as an offer, so message text is never stored as a store.
     */
    static String merchant(List<String> labels) {
        if (labels == null || !OfferEvidence.bounded(labels)) return "";
        for (String raw : labels) {
            String label = OfferEvidence.normalize(raw);
            for (String marker : new String[]{"go to ", "offer from ", "order from "}) {
                int at = indexOfIgnoreCase(label, marker);
                if (at < 0) continue;
                String name = untilSentenceEnd(label.substring(at + marker.length()));
                if (!name.isEmpty()) return name.length() > MAX_MERCHANT ? name.substring(0, MAX_MERCHANT).trim() : name;
            }
        }
        return "";
    }

    private static String untilSentenceEnd(String s) {
        int end = s.length();
        int tap = indexOfIgnoreCase(s, " tap ");
        if (tap >= 0) end = tap;
        for (int i = 0; i < end; i++) {
            char c = s.charAt(i);
            if (c == '•' || c == '·' || c == '|' || c == ':' || c == ';' || c == '\n') { end = i; break; }
            if ((c == '.' || c == '!' || c == '?') && (i + 1 >= s.length() || Character.isWhitespace(s.charAt(i + 1)) || s.charAt(i + 1) == '.')) {
                if (c == '.' && abbreviation(s, i)) continue;
                end = i; break;
            }
        }
        return s.substring(0, end).replaceAll("[\\s.,;:!?·•|\\-–—]+$", "").trim();
    }
    private static boolean abbreviation(String s, int dot) {
        int start = dot;
        while (start > 0 && !Character.isWhitespace(s.charAt(start - 1))) start--;
        String word = s.substring(start, dot).toLowerCase(Locale.US);
        if (word.isEmpty()) return false;
        if (word.contains(".") || (word.length() == 1 && Character.isLetter(word.charAt(0)))) return true;   // "P.F." initials
        return ABBREVIATIONS.contains(word);
    }
    private static int indexOfIgnoreCase(String text, String needle) {
        for (int i = 0; i + needle.length() <= text.length(); i++) if (text.regionMatches(true, i, needle, 0, needle.length())) return i;
        return -1;
    }
    private NotificationOffer() {}
}
