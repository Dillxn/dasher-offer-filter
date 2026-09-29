package com.local.dasherfilter;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Prevents repeated taps on one offer without delaying a different offer. */
final class DeclineState {
    // Countdown ticks ("32 secs", "0:32 left", "Expires in 0:32", "Add to route 77") are not offer identity.
    // A clock time followed by AM/PM is a deadline, not a countdown, and stays part of the identity.
    // A label that is only a small number with symbols ("29", "(32)", ":32", "⏱ 32", "32%") is a tick, never identity.
    private static final Pattern COUNTDOWN = Pattern.compile(
            "(?i)\\b\\d{1,2}:\\d{2}\\b(?!\\s*[ap]\\.?\\s?m\\b)|\\b\\d{1,3}\\s*(?:s|secs?|seconds)\\b|\\bsec(?:ond)?s?\\b|\\bleft\\b|\\bexpir\\w*|\\bremaining\\b|" +
            "\\brespond\\w*|^[^\\p{L}\\d$]*\\d{1,3}[^\\p{L}\\d$]*$");
    private String tappedOffer = "";
    /** Facts of the offer whose first-step decline was requested; survives offerGone(), cleared only by reset(). */
    private OfferSnapshot declinedOffer;
    private long lastTapAt;
    private int attempts;
    private long confirmationUntil;
    private long lastConfirmationAt = -1;
    private int confirmationAttempts;

    static String offerKey(OfferSnapshot offer, List<String> labels) {
        StringBuilder identity = new StringBuilder(offer.fingerprint());
        for (String label : labels) {
            String normalized = OfferEvidence.normalize(label).toLowerCase(Locale.US);
            if (normalized.startsWith("accept") || normalized.startsWith("decline") || OfferControls.isControl(normalized) ||
                    COUNTDOWN.matcher(normalized).find()) continue;
            identity.append('\n').append(label);
        }
        return identity.toString();
    }

    boolean mayDecline(String offerKey, long now) {
        return !offerKey.equals(tappedOffer) ||
                (attempts < 4 && now - lastTapAt >= 250);
    }

    void declineSent(String offerKey, long now) { declineSent(offerKey, null, now); }

    /** offer: the parsed facts of the declined offer, so confirmation authority is bound to that offer only. */
    void declineSent(String offerKey, OfferSnapshot offer, long now) {
        declinedOffer = offer;
        if (!offerKey.equals(tappedOffer)) attempts = 0;
        tappedOffer = offerKey;
        attempts++;
        lastTapAt = now;
        confirmationUntil = now + 10000;
        lastConfirmationAt = -1;
        confirmationAttempts = 0;
    }

    /**
     * The candidate shows the declined offer: every fact the candidate shows was also shown, with the same value, by the
     * declined offer (a sheet can hide facts, never add them), and the shared facts include the pay or at least two facts.
     * A candidate showing a fact the declined offer lacked is a different offer; a single shared stop count or distance is
     * never proof. A null candidate fact is neither a conflict nor proof of identity.
     */
    boolean isDeclinedOffer(OfferSnapshot candidate) {
        OfferSnapshot d = declinedOffer;
        if (d == null || candidate == null) return false;
        int shared = 0;
        Object[][] pairs = {{d.payCents, candidate.payCents}, {d.miles, candidate.miles}, {d.minutes, candidate.minutes}, {d.stops, candidate.stops}};
        for (Object[] pair : pairs) {
            if (pair[1] == null) continue;
            if (pair[0] == null || !pair[0].equals(pair[1])) return false;
            shared++;
        }
        boolean payShared = candidate.payCents != null;
        return payShared || shared >= 2;
    }

    /**
     * A readable offer with explicit facts that is not proven to be the declined offer revokes pending confirmation
     * authority, even when no new notification arrived (a sheet over the same offer keeps it). Returns true when revoked.
     * The new offer's own first decline stays immediate.
     */
    boolean offerObserved(OfferSnapshot offer, long now) {
        if (!hasPendingConfirmation(now) || offer == null || !offer.hasFacts() || isDeclinedOffer(offer)) return false;
        revokeConfirmation();
        return true;
    }

    boolean hasPendingConfirmation(long now) { return now < confirmationUntil; }

    boolean mayConfirm(long now) {
        return hasPendingConfirmation(now) && confirmationAttempts < 4 &&
                (lastConfirmationAt < 0 || now - lastConfirmationAt >= 250);
    }

    void confirmationSent(long now) {
        lastConfirmationAt = now;
        confirmationAttempts++;
    }

    /**
     * No offer controls are visible. The next identical offer may be declined again. A transition before any confirmation
     * keeps the authority (the sheet may still be opening); once a confirmation was requested and the sheet has closed, the
     * decline flow is over and the authority ends, so it can never reach a later offer.
     */
    void offerGone() {
        tappedOffer = "";
        attempts = 0;
        if (confirmationAttempts > 0) revokeConfirmation();
    }

    void reset() {
        tappedOffer = "";
        declinedOffer = null;
        revokeConfirmation();
    }

    private void revokeConfirmation() {
        confirmationUntil = 0;
        lastConfirmationAt = -1;
        confirmationAttempts = 0;
    }
}
