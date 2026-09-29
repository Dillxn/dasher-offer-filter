package com.local.dasherfilter;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Whole-phrase avoid-list matching on normalized text. A match is a known failure; store rules never pass an offer. */
final class StoreMatcher {
    static final int MAX_TERMS = 50, MIN_TERM_LENGTH = 2, MAX_TERM_LENGTH = 60;
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern APOSTROPHES_AND_PERIODS = Pattern.compile("['’‘`´ʼ.]");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern LETTER = Pattern.compile("\\p{L}");
    // Normalized screen labels that are never the offer's merchant: map hotspots, promotions, customer/drop-off lines.
    private static final Pattern NOT_A_MERCHANT = Pattern.compile("^(?:hot ?spots?|busy(?: area)?|very busy|zone|map|promos?|promotions?|peak pay|challenges?|bonus|earn|" +
            "dash|dashing|scheduled?|directions?|navigate|navigation|customers?|deliver to|delivery to|drop ?off|dropoff)\\b");

    /** NFKC, strip accents, lowercase, drop apostrophes/periods, "&" -> "and", collapse everything else to single spaces. */
    static String normalizeTerm(String raw) {
        if (raw == null) return "";
        String s = APOSTROPHES_AND_PERIODS.matcher(raw).replaceAll("");      // before NFKC: U+00B4 decomposes to a space + mark
        s = Normalizer.normalize(s, Normalizer.Form.NFKC);
        s = stripMarks(s).toLowerCase(Locale.US);
        s = stripMarks(s);                                                    // lowercase can introduce marks (U+0130)
        s = APOSTROPHES_AND_PERIODS.matcher(s).replaceAll("").replace("&", " and ");
        return NON_ALPHANUMERIC.matcher(s).replaceAll(" ").trim();
    }
    private static String stripMarks(String s) { return MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll(""); }

    /** Null when the raw term is usable; otherwise a short helper-text message. */
    static String termError(String raw) {
        String n = normalizeTerm(raw);
        if (n.length() < MIN_TERM_LENGTH) return "Use at least 2 letters or digits.";
        if (n.length() > MAX_TERM_LENGTH) return "Keep store names under 60 characters.";
        int alphanumerics = 0;
        for (int i = 0; i < n.length(); i++) if (Character.isLetterOrDigit(n.charAt(i))) alphanumerics++;
        if (alphanumerics < 2) return "Use at least 2 letters or digits.";
        // A number alone ("76", "7-11") would match countdowns, prices and street numbers.
        return LETTER.matcher(n).find() ? null : "Include the store name's letters, not only numbers.";
    }
    static boolean validTerm(String raw) { return termError(raw) == null; }

    /** Normalized, valid, de-duplicated, at most 50 terms, in input order. Invalid terms are dropped. */
    static List<String> sanitize(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        Set<String> out = new LinkedHashSet<>();
        for (String term : raw) {
            if (out.size() >= MAX_TERMS) break;
            if (term == null || term.length() > 4096 || !validTerm(term)) continue;
            out.add(normalizeTerm(term));
        }
        return Collections.unmodifiableList(new ArrayList<>(out));
    }
    /** Splits user input on newlines, commas, or semicolons, then sanitizes. */
    static List<String> parseTerms(String text) {
        if (text == null || text.trim().isEmpty()) return Collections.emptyList();
        List<String> parts = new ArrayList<>();
        for (String part : text.split("[\\n\\r,;]+")) if (!part.trim().isEmpty()) parts.add(part.trim());
        return sanitize(parts);
    }

    /** Whole normalized label, or whole phrase at a word boundary inside it. */
    static boolean matches(String normalizedTerm, String label) {
        if (normalizedTerm == null || normalizedTerm.isEmpty() || label == null) return false;
        String n = normalizeTerm(label);
        return n.equals(normalizedTerm) || (" " + n + " ").contains(" " + normalizedTerm + " ");
    }

    /**
     * First avoid term matching the merchant or a plausible merchant label, or null. Screen labels that are controls, carry
     * no letters (countdowns, prices), or start like a map hotspot, promotion, or customer/drop-off line are never matched.
     * Pass no labels for add-on screens: the current route's store can appear there, so only a notification merchant may apply.
     */
    static String firstMatch(List<String> normalizedTerms, String merchant, List<String> labels) {
        if (normalizedTerms == null || normalizedTerms.isEmpty()) return null;
        List<String> candidates = new ArrayList<>();
        if (merchant != null && !merchant.trim().isEmpty()) candidates.add(normalizeTerm(merchant));
        if (labels != null) for (String label : labels) {
            if (label == null || label.length() > 4096 || OfferControls.isControl(label)) continue;
            String n = normalizeTerm(label);
            if (LETTER.matcher(n).find() && !NOT_A_MERCHANT.matcher(n).find()) candidates.add(n);
        }
        for (String term : normalizedTerms) {
            if (term == null || term.isEmpty() || !LETTER.matcher(term).find()) continue;
            String padded = " " + term + " ";
            for (String c : candidates) if (c.equals(term) || (" " + c + " ").contains(padded)) return term;
        }
        return null;
    }
    private StoreMatcher() {}
}
