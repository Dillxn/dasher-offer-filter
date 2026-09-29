package com.local.dasherfilter;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Match complete action labels, never prompts or unrelated text. */
final class OfferControls {
    /** "Accept" with an optional countdown, e.g. "Accept (30)", "Accept, 30 seconds", "Accept 0:30". */
    private static final Pattern ACCEPT_WITH_COUNTDOWN = Pattern.compile(
            "accept(?: offer| order)?\\s*[(:,·]?\\s*(?:\\d{1,2}|\\d{1,2}:\\d{2})"
                    + "\\s*(?:s|secs?|seconds)?\\s*(?:remaining)?\\s*\\)?");

    /** True when {@code label} is exactly {@code verb}, "{@code verb} offer", or "{@code verb} order". */
    static boolean isButton(String label, String verb) {
        String normalized = normalize(label);
        if (normalized.equals(verb) || normalized.equals(verb + " offer") || normalized.equals(verb + " order")) {
            return true;
        }
        return verb.equals("accept") && ACCEPT_WITH_COUNTDOWN.matcher(normalized).matches();
    }

    /** NFKC-normalized, whitespace-collapsed, lower-case label. */
    static String normalize(String label) {
        return Normalizer.normalize(label, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Z}\\s]+", " ")
                .trim()
                .toLowerCase(Locale.US);
    }

    private OfferControls() {}
}
