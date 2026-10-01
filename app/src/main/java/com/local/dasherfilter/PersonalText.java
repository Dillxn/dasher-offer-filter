package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Personal details in Dasher's screen text, masked before any of it is kept on the phone or leaves it: a customer's
 * name ("Deliver to Sam P" reads "Deliver to [name]", "Sam's order" reads "[name]'s order") and the dasher's own (the
 * name above "651 orders completed"), street addresses and city/state/ZIP lines ("[address]", "Apt/Suite:
 * [address]"), phone numbers ("[phone]"), emails ("[email]") and a customer's own drop-off words ("[instructions]").
 * Store and business names, offer words and figures (pay, miles, minutes, stops), times, buttons and shopping items
 * stay, so a report still shows what Dasher drew.
 *
 * <p>Only what is kept is masked: the two diagnostic logs, the decision history's read lines and every report. Offer
 * reading and decisions always use the raw labels. Pure Java and deterministic: the same text always masks the same,
 * and masked text masks to itself.
 */
final class PersonalText {
    static final String NAME = "[name]";
    static final String ADDRESS = "[address]";
    static final String PHONE = "[phone]";
    static final String EMAIL = "[email]";
    static final String INSTRUCTIONS = "[instructions]";

    /** A person's name as Dasher writes it: "Sam P", "Noel G.", "Mary Ann S", "José", "O'Neil". */
    private static final String NAME_WORDS = "\\p{Lu}[\\p{L}'’-]*\\.?(?: \\p{Lu}[\\p{L}'’-]*\\.?){0,2}";
    private static final Pattern NAME_LABEL = Pattern.compile(NAME_WORDS);
    /** Words that can follow a customer heading without being a name. */
    private static final Set<String> NOT_NAMES = new HashSet<>(Arrays.asList(
            "customer", "customers", "dasher", "doordash", "pickup", "pick", "dropoff", "drop", "store", "restaurant",
            "door", "front", "back", "lobby", "desk", "curb", "car", "me", "you", "your", "the", "a", "an", "today",
            "tomorrow", "tonight", "now", "here", "there", "home", "accept", "decline", "call", "message", "text",
            "directions", "navigate", "order", "orders", "delivery", "deliveries", "details", "help", "support",
            "guest", "unknown", "everyone", "someone", "our", "my", "this", "that", "dash"));

    /**
     * Every pattern starts only where its first word can (a lookbehind, an anchor or a word boundary), so a long
     * label is masked in time proportional to its length: a screen's labels are masked on every log write.
     */
    private static final Pattern EMAIL_ADDRESS = Pattern.compile(
            "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");

    /** A label that is a quotation (the customer's own words), or one after a heading's colon: to the label's end. */
    private static final Pattern QUOTED_LABEL = Pattern.compile("(^ *|: *)[\"“][^\\n]*");
    /** The same in a log line of labels: a balanced quotation that ends a label, then any quotation left open. */
    private static final Pattern QUOTED_ITEM = Pattern.compile(
            "(^|\\[|, |: *)[\"“][^\"“”\\n]*[\"”](?= *(?:,|\\]|$))", Pattern.MULTILINE);
    private static final Pattern QUOTE_OPEN_ITEM = Pattern.compile("(^|\\[|, |: *)[\"“][^\\]\\n]*", Pattern.MULTILINE);

    /** Headings Dasher puts before a customer's own drop-off words, which are masked to the label's end. */
    private static final String INSTRUCTION_HEADING = "(?i:leave (?:it )?(?:at|by|in) (?:my |the )?"
            + "(?:front |back |side )?(?:door|porch|desk|lobby|reception|mailroom|gate|building)"
            + "|hand (?:it )?to (?:me|the customer|customer)"
            + "|meet (?:me )?(?:at|outside)(?: (?:my|the))? (?:door|lobby|curb|car|building|entrance)"
            + "|(?:drop[- ]?off|delivery|customer|dasher|special) (?:instructions?|notes?)"
            + "|instructions?|note from (?:the )?customer)";
    private static final Pattern INSTRUCTIONS_LABEL = Pattern.compile(
            "\\b(" + INSTRUCTION_HEADING + ")( *: *)(?!\\[instructions\\])(\\S[^\\n]*)");
    private static final Pattern INSTRUCTIONS_ITEM = Pattern.compile(
            "\\b(" + INSTRUCTION_HEADING + ")( *: *)(?!\\[instructions\\])([^\\]\\s][^\\]\\n]*)");

    /** US numbers: "(513) 555-0100", "513-555-0100", "+1 513 555 0100", "5135550100"; never part of a longer run. */
    private static final Pattern PHONE_NUMBER = Pattern.compile(
            "(?<![\\w$.:#/])(?:\\+?1[ .-]?)?(?:\\(\\d{3}\\)[ .-]?|\\d{3}[ .-]?)\\d{3}[ .-]?\\d{4}(?!\\w)");

    private static final String STATES = "AL|AK|AZ|AR|CA|CO|CT|DE|DC|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN|MS|"
            + "MO|MT|NE|NV|NH|NJ|NM|NY|NC|ND|OH|OK|OR|PA|PR|RI|SC|SD|TN|TX|UT|VT|VA|WA|WV|WI|WY";
    /** "Springfield, OH 45229, USA", "San Francisco, CA 94110", "OH 45229". */
    private static final Pattern CITY_STATE_ZIP = Pattern.compile(
            "(?<![\\p{L}\\d'’.-])(?:\\p{Lu}[\\p{L}'’.-]*(?: \\p{Lu}[\\p{L}'’.-]*){0,3}, ?)?\\b(?:" + STATES + ") "
                    + "\\d{5}(?:-\\d{4})?\\b(?:,? (?:USA|US|United States)\\b)?");

    private static final String DIRECTION = "(?:[NSEW]|NE|NW|SE|SW|North|South|East|West)\\.?";
    private static final String STREET_TYPE = "(?=\\p{Lu})(?i:St|Street|Ave|Av|Avenue|Rd|Road|Blvd|Boulevard|Dr|Drive"
            + "|Ln|Lane|Way|Ct|Court|Pl|Place|Pkwy|Pky|Parkway|Cir|Circle|Hwy|Highway|Ter|Terrace|Trl|Trail|Sq|Square"
            + "|Pike|Plz|Plaza|Aly|Alley|Xing|Crossing|Cv|Cove|Loop|Expy|Expressway|Fwy|Freeway|Tpke|Turnpike)";
    /** A house number and the street's words ending in its type: "100 Example St", "4201 N Example Parkway NW". */
    private static final Pattern STREET = Pattern.compile(
            "(?<![\\w$:.,/#-])\\d{1,6}[A-Za-z]?(?:-\\d{1,5})? (?:" + DIRECTION + " )?"
                    + "(?:(?:\\p{Lu}[\\p{L}'’.-]*|\\d{1,3}(?:st|nd|rd|th)) ){1,4}" + STREET_TYPE + "\\b\\.?"
                    + "(?: (?:[NSEW]|NE|NW|SE|SW)\\b\\.?)?");
    /** "Apt/Suite: 504", "Apt 5B", "Suite 200", "Gate code 1234": the heading stays, the value goes. */
    private static final Pattern UNIT = Pattern.compile(
            "\\b((?i:apt/suite|apt|apartment|suite|ste|unit|bldg|building|room|rm|gate code|door code|access code"
                    + "|entry code|buzzer))\\b(\\.? *:? *#? *)(?!\\[)([A-Za-z]?\\d[\\w-]*|[A-Z]\\b)");

    /** "Deliver to Sam P", "Delivery for Sam", "Drop off for Noel G.", "Customer: Sam P". */
    private static final String CUSTOMER_HEADING = "(?i:deliver(?:ing)? to|delivery for|drop[- ]?off for|order for)"
            + ":?|(?i:customer(?: name)?):";
    private static final Pattern CUSTOMER_NAME = Pattern.compile(
            "\\b(" + CUSTOMER_HEADING + ")( +)(" + NAME_WORDS + ")");
    /** A heading that is a label of its own, with the name as the next label. */
    private static final String CUSTOMER_HEADING_WORDS =
            "(?i:deliver(?:ing)? to|delivery for|drop[- ]?off for|order for|customer|customer name)";
    private static final Pattern CUSTOMER_HEADING_LABEL = Pattern.compile(CUSTOMER_HEADING_WORDS + " *:?");
    /** The customer's name on the order check follows this heading and, when shown, its explanation. */
    private static final String ORDER_CHECK = "(?i:confirm you have the correct order before drop-off)\\.?";
    private static final String ORDER_CHECK_EXPLANATION = "(?i:mix-ups frequently occur at drop-off when there are "
            + "multiple orders in a dash)\\.?";
    private static final Pattern ORDER_CHECK_LABEL = Pattern.compile(ORDER_CHECK);
    private static final Pattern ORDER_CHECK_EXPLANATION_LABEL = Pattern.compile(ORDER_CHECK_EXPLANATION);
    /** Same-label-list context for retained logs: never cross a bracket or a physical line break. */
    private static final String ITEM_START = "(?:^|\\[|, )[ \\t]*";
    private static final String ITEM_SEPARATOR = "[ \\t]*, [ \\t]*";
    private static final String ITEM_END = "(?=[ \\t]*(?:, |\\]|$))";
    private static final Pattern CUSTOMER_NAME_ITEM = Pattern.compile(
            "(" + ITEM_START + CUSTOMER_HEADING_WORDS + "(?:[ \\t]*:)?" + ITEM_SEPARATOR + ")(" + NAME_WORDS + ")"
                    + ITEM_END, Pattern.MULTILINE);
    private static final Pattern ORDER_CHECK_NAME_ITEM = Pattern.compile(
            "(" + ITEM_START + ORDER_CHECK + ITEM_SEPARATOR + "(?:" + ORDER_CHECK_EXPLANATION + ITEM_SEPARATOR
                    + ")?)(" + NAME_WORDS + ")" + ITEM_END, Pattern.MULTILINE);
    /** "Tiaunna's order is ready". */
    private static final Pattern POSSESSIVE = Pattern.compile(
            "\\b(\\p{Lu}[\\p{L}-]*)(['’]s)( +(?i:order|orders|delivery|food|items?|groceries|package|drop[- ]?off))\\b");
    /** "Hi, Sam", "Good evening, Sam P": the dasher's own name. */
    private static final Pattern GREETING = Pattern.compile(
            "\\b((?i:hi|hello|hey|good morning|good afternoon|good evening|welcome back|welcome),? +)(" + NAME_WORDS
                    + ")");
    /** The dasher's own name sits right above this on Dasher's account screen. */
    private static final Pattern COMPLETED = Pattern.compile("^\\d[\\d,]* (?i:orders?|deliveries|dashes) completed");
    private static final Pattern NAME_BEFORE_COMPLETED = Pattern.compile(
            "(^|\\[|, )(" + NAME_WORDS + ")(, \\d[\\d,]* (?i:orders?|deliveries|dashes) completed)", Pattern.MULTILINE);

    /**
     * One label as read (or any one piece of screen text): a customer's own words after a heading run to the end of
     * it.
     */
    static String mask(String label) {
        return apply(label, true);
    }

    /**
     * Labels as read, in their order: each masked as {@link #mask(String)}, and a label that is a name only because of
     * the label beside it (the dasher's name above "651 orders completed", a name after a "Customer" heading) too.
     */
    static List<String> mask(List<String> labels) {
        List<String> out = new ArrayList<>();
        if (labels == null) return out;
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            if (label == null) {
                out.add(null);
                continue;
            }
            String next = i + 1 < labels.size() ? labels.get(i + 1) : null;
            String before = i > 0 ? labels.get(i - 1) : null;
            boolean named = (next != null && COMPLETED.matcher(next.trim()).find())
                    || (before != null && CUSTOMER_HEADING_LABEL.matcher(before.trim()).matches())
                    || followsOrderCheck(labels, i);
            out.add(named && isName(label.trim()) ? NAME : mask(label));
        }
        return out;
    }

    private static boolean followsOrderCheck(List<String> labels, int at) {
        if (at == 0) return false;
        String before = labels.get(at - 1);
        if (before == null) return false;
        if (ORDER_CHECK_LABEL.matcher(before.trim()).matches()) return true;
        if (at < 2 || !ORDER_CHECK_EXPLANATION_LABEL.matcher(before.trim()).matches()) return false;
        String heading = labels.get(at - 2);
        return heading != null && ORDER_CHECK_LABEL.matcher(heading.trim()).matches();
    }

    /**
     * A log line, which may hold labels written as a list ("labels=[Deliver to Sam P, 100 Example St, …]"): a
     * customer's own words run to the end of their label, and a list item's end is its comma or bracket.
     */
    static String maskLine(String line) {
        return apply(line, false);
    }

    private static String apply(String text, boolean wholeLabel) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        String out = EMAIL_ADDRESS.matcher(text).replaceAll(Matcher.quoteReplacement(EMAIL));
        if (wholeLabel) {
            out = QUOTED_LABEL.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(INSTRUCTIONS));
            out = INSTRUCTIONS_LABEL.matcher(out).replaceAll("$1$2" + Matcher.quoteReplacement(INSTRUCTIONS));
        } else {
            out = QUOTED_ITEM.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(INSTRUCTIONS));
            out = QUOTE_OPEN_ITEM.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(INSTRUCTIONS));
            out = INSTRUCTIONS_ITEM.matcher(out).replaceAll("$1$2" + Matcher.quoteReplacement(INSTRUCTIONS));
        }
        out = PHONE_NUMBER.matcher(out).replaceAll(Matcher.quoteReplacement(PHONE));
        out = CITY_STATE_ZIP.matcher(out).replaceAll(Matcher.quoteReplacement(ADDRESS));
        out = STREET.matcher(out).replaceAll(Matcher.quoteReplacement(ADDRESS));
        out = UNIT.matcher(out).replaceAll("$1$2" + Matcher.quoteReplacement(ADDRESS));
        if (!wholeLabel) {
            out = names(CUSTOMER_NAME_ITEM, out, 2, "$1");
            out = names(ORDER_CHECK_NAME_ITEM, out, 2, "$1");
        }
        out = names(CUSTOMER_NAME, out, 3, "$1$2");
        out = names(GREETING, out, 2, "$1");
        out = possessives(out);
        return names(NAME_BEFORE_COMPLETED, out, 2, "$1", "$3");
    }

    /** Replaces the name in {@code group} with {@link #NAME}, unless its first word is not a name ("Customer"). */
    private static String names(Pattern pattern, String text, int group, String before, String... after) {
        Matcher found = pattern.matcher(text);
        StringBuffer out = new StringBuffer();
        while (found.find()) {
            String replacement = isName(found.group(group))
                    ? before + Matcher.quoteReplacement(NAME) + (after.length == 0 ? "" : after[0])
                    : Matcher.quoteReplacement(found.group());
            found.appendReplacement(out, replacement);
        }
        found.appendTail(out);
        return out.toString();
    }

    private static String possessives(String text) {
        Matcher found = POSSESSIVE.matcher(text);
        StringBuffer out = new StringBuffer();
        while (found.find()) {
            found.appendReplacement(out, isName(found.group(1))
                    ? Matcher.quoteReplacement(NAME) + "$2$3" : Matcher.quoteReplacement(found.group()));
        }
        found.appendTail(out);
        return out.toString();
    }

    /** Shaped like a name, and its first word is not one of the words that only look like one. */
    private static boolean isName(String text) {
        if (text == null || !NAME_LABEL.matcher(text).matches()) return false;
        int space = text.indexOf(' ');
        String first = (space < 0 ? text : text.substring(0, space)).replaceAll("[.'’-]+$", "");
        return !NOT_NAMES.contains(first.toLowerCase(Locale.US));
    }

    private PersonalText() {}
}
