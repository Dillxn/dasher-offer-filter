package com.local.dasherfilter;

import java.util.Arrays;
import java.util.List;

/**
 * Recognizes the second step of a decline, even when the original offer remains in the tree. A lone Decline
 * button is not enough: a new offer drawn before its pay and Accept button looks exactly like that, so a
 * confirmation needs a prompt ("Are you sure…", "Decline offer?") or a way back out ("Cancel", "Go back").
 */
final class DeclineConfirmation {
    private static final List<String> BACK_OUT = Arrays.asList(
            "cancel", "go back", "back", "keep offer", "keep order", "never mind", "no thanks", "not now");

    static boolean isSurface(List<String> labels) {
        boolean cancel = false;
        boolean decline = false;
        for (String label : labels) {
            String value = OfferControls.normalize(label);
            cancel |= BACK_OUT.contains(value);
            decline |= OfferControls.isButton(value, "decline");
        }
        return hasPrompt(labels) || (decline && cancel);
    }

    /** A question about declining, as opposed to a mere Back or Cancel button. */
    static boolean hasPrompt(List<String> labels) {
        for (String label : labels) {
            String value = OfferControls.normalize(label);
            if (value.contains("are you sure") || value.contains("decline this")
                    || value.contains("declining this") || value.contains("declining orders")
                    || value.contains("declining offers") || value.contains("acceptance rate will")
                    || value.contains("your acceptance rate may")
                    || (value.startsWith("decline") && value.endsWith("?"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Index into {@code actions} of the confirming Decline button, or -1. The last one in tree order wins: a sheet
     * drawn over the original offer comes after it, whatever either button says.
     */
    static int select(List<String> labels, List<String> actions) {
        if (!isSurface(labels)) return -1;
        for (int i = actions.size() - 1; i >= 0; i--) {
            if (OfferControls.isButton(OfferControls.normalize(actions.get(i)), "decline")) return i;
        }
        return -1;
    }

    private DeclineConfirmation() {}
}
