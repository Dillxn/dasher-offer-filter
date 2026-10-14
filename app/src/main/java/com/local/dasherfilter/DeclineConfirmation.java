package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
     * Dasher's decline questions whose wording the owner's own reports have shown (0.6.18; the owner chose "Only confirm
     * wording seen in your own reports, and pause on anything else", 14 October 2026), as {@link #promptKey} keys them.
     * Only a question whose every prompt line is one of these is ever confirmed; any other is handed back and pauses
     * auto-decline ({@link TimeModeGuard#UNKNOWN_QUESTION_LINE}). Add a wording only when a report shows it.
     */
    static final List<String> KNOWN_PROMPTS = Collections.unmodifiableList(Arrays.asList(
            "are you sure you want to decline this offer"));

    /**
     * A prompt line's key: {@link OfferControls#normalize normalized} (case, spacing), its final question mark, ellipsis
     * or full stops (and the spaces around them) dropped. Nothing else is dropped: a known line with words added is
     * another line.
     */
    static String promptKey(String label) {
        return OfferControls.normalize(label).replaceAll("[\\s?？….]+$", "");
    }

    /**
     * The question's prompt lines: each label that asks something ("are you sure" or a question mark in it, or ending in
     * "…" or "..."); with none, each label {@link #hasPrompt} recognizes as being about declining. Buttons are never prompt
     * lines (a question's buttons are not its words), and neither are its statements beside a question (Dasher's "Does
     * not lower acceptance rate").
     */
    static List<String> promptLines(List<String> labels) {
        List<String> asks = new ArrayList<>();
        List<String> about = new ArrayList<>();
        for (String label : labels) {
            String value = OfferControls.normalize(label);
            if (value.isEmpty()) continue;
            if (value.contains("are you sure") || value.contains("?") || value.contains("？") || value.endsWith("…")
                    || value.endsWith("...")) {
                asks.add(label);
            } else if (hasPrompt(Collections.singletonList(label))) {
                about.add(label);
            }
        }
        return asks.isEmpty() ? about : asks;
    }

    /** Whether the question on screen is one whose wording is known (every prompt line in {@link #KNOWN_PROMPTS}). */
    static boolean knownPrompt(List<String> labels) {
        List<String> lines = promptLines(labels);
        if (lines.isEmpty()) return false;
        for (String line : lines) {
            if (!KNOWN_PROMPTS.contains(promptKey(line))) return false;
        }
        return true;
    }

    /** The prompt lines of a question that is not known, as read (to be masked before they are logged). */
    static String unknownPrompt(List<String> labels) {
        List<String> lines = promptLines(labels);
        return lines.isEmpty() ? "(no question words)" : String.join(" / ", lines);
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
