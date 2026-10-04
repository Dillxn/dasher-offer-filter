package com.local.dasherfilter;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit item units only. Repeated accessibility labels never add up into an invented basket size. */
final class ItemCount {
    // A bounded label can still contain thousands of separators. Possessive groups avoid a regex stack overflow.
    private static final String NUMBER = "([+\\-]?\\d++(?:[.,]\\d++)*+(?:\\s*(?:[-–—]|to|of)\\s*[+\\-]?\\d++(?:[.,]\\d++)*+)?\\s*\\+?)";
    private static final Pattern AFTER = Pattern.compile("(?i)(?<![\\p{Alnum}.,+\\-–—])" + NUMBER
            + "\\s+(?:(unique|distinct|total|additional|extra|more)\\s+)?items?\\b");
    private static final Pattern BEFORE = Pattern.compile("(?i)\\b((?:new\\s+)?total\\s+|additional\\s+|extra\\s+|added\\s+)?"
            + "items?\\s*[:=]\\s*" + NUMBER);
    private static final Pattern TOTAL_BEFORE = Pattern.compile("(?i)\\b(?:new\\s+)?total\\s+items?\\s+" + NUMBER);
    private static final Pattern DECLARATION = Pattern.compile("(?i)\\b(?:items?|shopping)\\b"
            + "|\\bshop\\s*(?:&|and)\\s*deliver\\b|\\bshop\\s+for\\b");
    private static final Pattern INEXACT_PREFIX = Pattern.compile("(?i)(?:\\b(?:up to|at least|at most|over|under|about"
            + "|around|approx(?:imately)?|est(?:imate[ds]?)?|min(?:imum)?|max(?:imum)?|more than|less than|fewer than|of|per|each|remaining|found|collected|completed)"
            + "\\s*[:=]?|[<>≤≥~≈/\\d.,+\\-–—])\\s*$");
    private static final Pattern UNIQUE_PREFIX = Pattern.compile("(?i)\\b(?:unique|distinct)\\s*$");
    private static final Pattern TOTAL_PREFIX = Pattern.compile("(?i)\\b(?:new\\s+)?total\\s*[:=]?\\s*$");
    private static final Pattern ADDED_PREFIX = Pattern.compile("(?i)(?:\\b(?:add|adds|added|additional|extra|more)|\\+)\\s*$");
    private static final Pattern TOTAL_SUFFIX = Pattern.compile("(?i)^\\s*(?:in\\s+)?total\\b");
    private static final Pattern ADDED_SUFFIX = Pattern.compile("(?i)^\\s*(?:additional|extra|more)\\b");
    private static final Pattern ORDER_COMPONENT = Pattern.compile("(?i)\\b(?:(?:first|second|third|fourth|fifth|"
            + "\\d+(?:st|nd|rd|th))\\s+order|order\\s*#?\\s*\\d+)\\b|\\border\\s*[:=]");
    private static final Pattern NEW_OFFER_PREFIX = Pattern.compile("(?i)^new\\s+(?:order|delivery)\\b"
            + "(?:\\s+offer)?\\s*[:=]?\\s*");
    private static final Pattern EXACT_SUFFIX = Pattern.compile("(?i)^\\s*(?:$|[,;•·|]|"
            + "\\(\\s*\\d+\\s+(?:unique|distinct)(?:\\s+items?)?\\s*\\)|"
            + "(?:in\\s+)?total\\b|(?:additional|extra|more)\\b)");
    private static final Pattern INEXACT_SUFFIX = Pattern.compile("(?i)^\\s*(?:/\\s*\\d|"
            + "[,;:(]?\\s*(?:remaining|found|collected|completed|estimated|approximately|of)\\b)");

    final boolean applicable;
    final Integer count;
    final Integer added;
    final Integer total;
    final boolean addedConflict;
    final boolean totalConflict;

    private ItemCount(boolean applicable, Count count, Count added, Count total) {
        this.applicable = applicable;
        this.count = count.value();
        this.added = added.value();
        this.total = total.value();
        this.addedConflict = added.invalid;
        this.totalConflict = total.invalid;
    }

    static ItemCount parse(List<String> labels) {
        Count count = new Count(), added = new Count(), total = new Count();
        boolean applicable = false;
        boolean components = false;
        if (!OfferEvidence.bounded(labels)) return new ItemCount(false, count, added, total);
        for (String raw : labels) {
            String label = OfferEvidence.normalize(raw).toLowerCase(Locale.US);
            applicable |= DECLARATION.matcher(label).find();
            components |= orderComponent(label);
            Matcher after = AFTER.matcher(label);
            while (after.find()) {
                String prefix = label.substring(0, after.start());
                String suffix = label.substring(after.end());
                String qualifier = after.group(2);
                if ("unique".equals(qualifier) || "distinct".equals(qualifier)
                        || uniquePrefix(prefix)) continue;
                boolean isAdded = after.group(1).trim().startsWith("+")
                        || ADDED_PREFIX.matcher(prefix).find() || ADDED_SUFFIX.matcher(suffix).find()
                        || "additional".equals(qualifier) || "extra".equals(qualifier) || "more".equals(qualifier);
                boolean isTotal = "total".equals(qualifier) || TOTAL_PREFIX.matcher(prefix).find()
                        || TOTAL_SUFFIX.matcher(suffix).find();
                if (!isTotal && (orderComponent(prefix) || orderComponent(suffix))) {
                    components = true;
                    continue;
                }
                observe(after.group(1), prefix, suffix, isAdded, isTotal, count, added, total);
            }
            Matcher before = BEFORE.matcher(label);
            while (before.find()) {
                if (uniquePrefix(label.substring(0, before.start()))) continue;
                String qualifier = before.group(1) == null ? "" : before.group(1);
                if (!qualifier.contains("total") && orderComponent(label)) {
                    components = true;
                    continue;
                }
                observe(before.group(2), label.substring(0, before.start()), label.substring(before.end()),
                        qualifier.matches("(?:additional|extra|added)\\s+"), qualifier.contains("total"),
                        count, added, total);
            }
            Matcher totalBefore = TOTAL_BEFORE.matcher(label);
            while (totalBefore.find()) {
                if (uniquePrefix(label.substring(0, totalBefore.start()))) continue;
                observe(totalBefore.group(1), label.substring(0, totalBefore.start()), label.substring(totalBefore.end()),
                        false, true, count, added, total);
            }
        }
        // Equal counts on two explicitly named orders are not duplicate readings of one total. Only an explicit
        // route total can resolve them; never sum, multiply or silently choose one order's count.
        if (components) added.seen = null;
        return new ItemCount(applicable, components ? total : count, added, total);
    }

    static boolean orderComponent(String label) {
        // Dasher's established notification headline describes the whole offer, not one order in a stack.
        return ORDER_COMPONENT.matcher(NEW_OFFER_PREFIX.matcher(label).replaceFirst("")).find();
    }

    private static boolean uniquePrefix(String prefix) {
        return UNIQUE_PREFIX.matcher(TOTAL_PREFIX.matcher(prefix).replaceFirst("")).find();
    }

    private static void observe(String number, String prefix, String suffix, boolean isAdded, boolean isTotal,
                                Count count, Count added, Count total) {
        String digits = number.trim();
        if (isAdded && digits.startsWith("+")) digits = digits.substring(1).trim();
        String exactPrefix = isAdded ? ADDED_PREFIX.matcher(prefix).replaceFirst("") : prefix;
        if (isTotal) exactPrefix = TOTAL_PREFIX.matcher(exactPrefix).replaceFirst("");
        String exactSuffix = isTotal ? TOTAL_SUFFIX.matcher(suffix).replaceFirst("") : suffix;
        if (isAdded) exactSuffix = ADDED_SUFFIX.matcher(exactSuffix).replaceFirst("");
        boolean exact = !(isAdded && isTotal) && digits.matches("[0-9]{1,4}")
                && !INEXACT_PREFIX.matcher(exactPrefix).find()
                && EXACT_SUFFIX.matcher(exactSuffix).find() && !INEXACT_SUFFIX.matcher(exactSuffix).find();
        Integer value = exact ? Integer.valueOf(digits) : null;
        if (value != null && value <= 0) value = null;
        // An explicit increment is not the standalone offer's total. Its add-on parser owns that meaning.
        if (!isAdded) count.observe(value);
        if (isAdded) added.observe(value);
        if (isTotal) total.observe(value);
    }

    private static final class Count {
        Integer seen;
        boolean invalid;
        void observe(Integer next) {
            if (next == null || seen != null && !seen.equals(next)) invalid = true;
            if (next != null) seen = next;
        }
        Integer value() { return invalid ? null : seen; }
    }

    private ItemCount() { throw new AssertionError(); }
}
