package com.local.dasherfilter;

import java.util.List;

/** Recognize the second step even when the original offer remains in the tree. */
final class DeclineConfirmation {
    static boolean isSurface(List<String> labels, boolean hasAccept) {
        boolean cancel = false;
        boolean decline = false;
        boolean prompt = false;
        for (String label : labels) {
            String value = OfferControls.normalize(label);
            cancel |= value.equals("cancel") || value.equals("go back") ||
                    value.equals("back") || value.equals("keep offer");
            decline |= OfferControls.isButton(value, "decline");
            prompt |= value.contains("are you sure") || value.contains("decline this") ||
                    value.contains("declining this") || value.contains("acceptance rate will") ||
                    value.contains("your acceptance rate may");
        }
        return prompt || (decline && (cancel || !hasAccept));
    }

    static int select(List<String> labels, List<String> actions, boolean hasAccept) {
        if (!isSurface(labels, hasAccept)) return -1;
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
}
