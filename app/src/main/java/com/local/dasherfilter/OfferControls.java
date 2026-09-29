package com.local.dasherfilter;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Match complete action labels, never prompts or unrelated text. "Add to route" is the add-on accept control. */
final class OfferControls {
    private static final Pattern ACCEPT = Pattern.compile(
            "(?:accept(?: offer| order)?|add to route)(?:\\s*[(:,·]?\\s*(?:\\d{1,3}|\\d{1,2}:\\d{2})" +
                    "\\s*(?:s|secs?|seconds)?\\s*(?:remaining|left)?\\s*\\)?)?");

    static boolean isButton(String label, String verb) {
        String normalized = normalize(label);
        if (normalized.equals(verb) || normalized.equals(verb + " offer") ||
                normalized.equals(verb + " order")) return true;
        return verb.equals("accept") && ACCEPT.matcher(normalized).matches();
    }

    static boolean isControl(String label) { return isButton(label, "accept") || isButton(label, "decline"); }

    static String normalize(String label) {
        return Normalizer.normalize(label == null ? "" : label, Normalizer.Form.NFKC)
                .replaceAll("\\p{Cf}", "").replaceAll("[\\p{Z}\\s]+", " ").trim().toLowerCase(Locale.US);
    }

    private OfferControls() {}
}
