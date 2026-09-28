package com.local.dasherfilter;

import java.text.Normalizer;
import java.util.Locale;

/** Match complete action labels, never prompts or unrelated text. */
final class OfferControls {
    static boolean isButton(String label, String verb) {
        String normalized = Normalizer.normalize(label, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Z}\\s]+", " ").trim().toLowerCase(Locale.US);
        if (normalized.equals(verb) || normalized.equals(verb + " offer") ||
                normalized.equals(verb + " order")) return true;
        return verb.equals("accept") && normalized.matches(
                "accept(?: offer| order)?\\s*[(:,·]?\\s*(?:\\d{1,2}|\\d{1,2}:\\d{2})" +
                        "\\s*(?:s|secs?|seconds)?\\s*(?:remaining)?\\s*\\)?");
    }
}
