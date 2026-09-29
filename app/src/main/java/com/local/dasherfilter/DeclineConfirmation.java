package com.local.dasherfilter;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Recognize the second decline step even when the original offer remains in the tree, without ever mistaking a fresh offer
 * for it. A decline control is always required. Bare "Back" is navigation, not a cancel control.
 */
final class DeclineConfirmation {
    // "Declining will not affect your acceptance rate" / "Your acceptance rate won't be affected": informational text that
    // DoorDash prints on Flash offer cards, not a question asking to confirm a decline.
    private static final Pattern NO_EFFECT = Pattern.compile(
            "\\b(?:will not|won['’]?t|does not|doesn['’]?t|do not|don['’]?t|not be|never|no)\\s+(?:\\w+\\s+){0,2}?(?:affect|impact|change|lower|hurt|count)");
    /** Unbound check: no proof that the candidate shows the offer that was just declined. */
    static boolean isSurface(List<String> labels, boolean hasAccept) { return isSurface(labels, hasAccept, false); }

    /**
     * sameDeclinedOffer: the adapter proved the candidate's known offer facts match the offer whose first-step decline was
     * requested (DeclineState.isDeclinedOffer). Without that proof, a surface showing offer evidence (money or mileage) and no
     * prompt is never a confirmation: a fresh offer with a nav "Cancel" looks exactly like a sheet laid over the declined one.
     * Nor is one that also shows its own recognized Accept, even with prompt-like text ("Declining will not affect your
     * acceptance rate" on a Flash offer card).
     */
    static boolean isSurface(List<String> labels, boolean hasAccept, boolean sameDeclinedOffer) {
        boolean cancel = false;
        boolean decline = false;
        boolean prompt = false;
        boolean offerEvidence = false;
        for (String label : labels) {
            String value = OfferControls.normalize(label);
            cancel |= value.equals("cancel") || value.equals("go back") || value.equals("keep offer");
            decline |= OfferControls.isButton(value, "decline");
            prompt |= value.contains("are you sure") || (value.endsWith("?") && value.contains("declin")) ||
                    (!NO_EFFECT.matcher(value).find() && (value.contains("decline this") || value.contains("declining this") ||
                            value.contains("acceptance rate will") || value.contains("your acceptance rate may")));
            offerEvidence |= OfferEvidence.hasMoneyToken(label) || OfferParser.hasMileageToken(label);
        }
        // A complete fresh offer (its own recognized Accept plus money or mileage) is never a confirmation unless the adapter
        // proved it shows the declined offer, whatever informational text it carries.
        if (hasAccept && offerEvidence && !sameDeclinedOffer) return false;
        // A lone Decline (no prompt, no cancel) may be a new offer still rendering: incomplete controls, never a confirmation.
        return decline && (prompt || (cancel && (!offerEvidence || sameDeclinedOffer)));
    }

    static int select(List<String> labels, List<String> actions, boolean hasAccept) { return select(labels, actions, hasAccept, false); }

    static int select(List<String> labels, List<String> actions, boolean hasAccept, boolean sameDeclinedOffer) {
        if (!isSurface(labels, hasAccept, sameDeclinedOffer)) return -1;
        int best = -1;
        int bestRank = -1;
        for (int i = 0; i < actions.size(); i++) {
            String value = OfferControls.normalize(actions.get(i));
            if (!OfferControls.isButton(value, "decline")) continue;
            int rank = value.equals("decline offer") ? 2 : value.equals("decline order") ? 1 : 0;
            // Later matches win ties when a sheet overlays the original offer in one tree.
            if (rank >= bestRank) { best = i; bestRank = rank; }
        }
        return best;
    }

    private DeclineConfirmation() {}
}
