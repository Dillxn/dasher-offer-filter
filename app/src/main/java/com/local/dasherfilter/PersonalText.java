package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Personal details in Dasher's screen text, masked before any of it is kept on the phone or leaves it: a customer's
 * name ("Deliver to Sam P" reads "Deliver to [name]", "Sam's order" reads "[name]'s order") and the dasher's own (the
 * name above "651 orders completed", before a badge such as "[icon] Pro Shopper", or heading Dasher's side menu),
 * street addresses and city/state/ZIP lines ("[address]", "Apt/Suite: [address]"), phone numbers ("[phone]"), emails
 * ("[email]"), a customer's own drop-off words ("[instructions]"), card numbers and a card's security code, expiry or
 * PIN ("[card]"), and the streets turn-by-turn navigation names ("Turn left onto [street]"). Store and business
 * names, offer words and figures (pay, miles, minutes, stops), times, buttons and shopping items stay, so a report
 * still shows what Dasher drew. Unframed text after an instruction is ambiguous and remains masked until a complete
 * control, figure or diagnostic record establishes a boundary, even if that text was actually a store's name.
 *
 * <p>A payment, account or earnings screen is not masked but never kept at all ({@link #accountScreen}): Dasher's
 * wallet page shows a card's number, expiry and security code, and a report once carried one.
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
    static final String CARD = "[card]";
    static final String STREET = "[street]";
    private static final String[] MASK_TOKENS = {NAME, ADDRESS, PHONE, EMAIL, INSTRUCTIONS, CARD, STREET};
    /** What a log keeps of a payment, account or earnings screen: this note, and none of its words. */
    static final String NOT_KEPT = "an account or payment screen (not kept)";
    /** The same in place of a list of labels that an event's line would otherwise carry. */
    static final String LABELS_NOT_KEPT = "[" + NOT_KEPT + "]";
    static final String UNKNOWN_NOT_KEPT = "unrecognized Dasher screen (text not kept)";

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
     * Words only a payment, account or earnings screen shows (Dasher's wallet and card pages, its earnings and payout
     * pages, tax forms): a screen with any of them, as a word or phrase in any label, is never kept. "1099" counts
     * unless a capitalised word or a number follows it, as in a house number ("1099 Example St").
     */
    static final List<String> ACCOUNT_MARKERS = Arrays.asList(
            "CVV", "CVC", "Security code", "Expiry", "Exp. date", "Expires", "Valid thru", "Card number",
            "Copy card number", "Lock card", "Unlock card",
            "Crimson", "DasherDirect", "Fast Pay", "Available balance", "Balance",
            "Earnings history", "Payout", "Pay out", "Bank account", "Routing number", "Account number",
            "SSN", "Social Security", "Tax", "1099", "Direct deposit",
            "Wallet", "Card details", "PIN", "Account details", "Personal information",
            "Payment methods", "Credit card", "Debit card", "Expiration date", "Banking");
    private static final Pattern ACCOUNT_MARKER = accountMarker();

    private static Pattern accountMarker() {
        StringBuilder words = new StringBuilder();
        for (String marker : ACCOUNT_MARKERS) {
            if (marker.equals("1099")) continue;
            if (words.length() > 0) words.append('|');
            words.append(Pattern.quote(marker).replace(" ", "\\E[\\p{Z}\\s]+\\Q"));
        }
        // A few spellings of the same words: plurals, "Dasher Direct", "Exp date", "Valid through". "Earnings" is an
        // earnings page's, never the "Earnings Mode" switch nor every offer card's own "Guaranteed earnings for
        // completing the offer." (0.5.1's report: each offer screen was dropped as a payment one).
        words.append("|dasher[\\p{Z}\\s]*direct|exp\\.?[\\p{Z}\\s]+date|valid[\\p{Z}\\s]+through|taxes|balances"
                + "|payouts|wallets|earnings(?![\\p{Z}\\s]+(?:mode|for[\\p{Z}\\s]+completing)\\b)");
        return Pattern.compile("(?<![\\p{L}\\d])(?i:" + words + ")(?![\\p{L}\\d])"
                + "|(?<![\\w$.,/#-])1099(?:-[A-Za-z]{1,4})?(?![\\w.,/]|-\\d| [\\p{Lu}\\d])");
    }

    /**
     * Every pattern starts only where its first word can (a lookbehind, an anchor or a word boundary), so a long
     * label is masked in time proportional to its length: a screen's labels are masked on every log write.
     */
    private static final Pattern EMAIL_ADDRESS = Pattern.compile(
            "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");

    /** A label that is a quotation (the customer's own words), or one after a heading's colon: to the label's end. */
    private static final Pattern QUOTED_LABEL = Pattern.compile("(^ *|: *)[\"“][\\s\\S]*");
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
            "\\b(" + INSTRUCTION_HEADING + ")([ \\t]*:[ \\t\\r\\n]*)(\\S[\\s\\S]*)");
    private static final Pattern INSTRUCTION_HEADING_LABEL = Pattern.compile(INSTRUCTION_HEADING + " *:?");
    private static final Pattern UNQUOTED_INSTRUCTIONS_LABEL = Pattern.compile(
            "^( *" + INSTRUCTION_HEADING + ")([ \\t\\r\\n]+)(\\S[\\s\\S]*)");
    // A colon retains the old heading behavior. Without one, require the beginning of a label/list item.
    private static final Pattern INSTRUCTIONS_ITEM = Pattern.compile(
            "(?:\\b" + INSTRUCTION_HEADING + "[ \\t]*:[ \\t\\r\\n]*|(?:^|\\[|, )" + INSTRUCTION_HEADING
                    + "(?:,[ \\t\\r\\n]+|[ \\t\\r\\n]+))(?=\\S)", Pattern.MULTILINE);
    private static final Pattern LOG_ENTRY = Pattern.compile("(" + DiagnosticLog.TIMESTAMP_WORDS
            + ") \\[[a-z][a-z0-9_-]*\\] ");
    /** DashSummary.excerpts replaces each real entry's timestamp with elapsed time from the dash's start. */
    private static final Pattern RELATIVE_LOG_ENTRY = Pattern.compile(
            "[+-]\\d++:[0-5]\\d:[0-5]\\d \\[[a-z][a-z0-9_-]*\\] ");
    /** Only complete controls/figures end a legacy instruction; "Call me when..." is still private text. */
    private static final Pattern INSTRUCTION_CONTROL = Pattern.compile(
            "(?i:call|message|directions|navigate|back|help|continue|done|accept|decline|take (?:a )?photo"
                    + "|confirm (?:pickup|delivery)|complete (?:pickup|delivery)(?: steps)?)");
    private static final String INSTRUCTION_METRIC = "\\d{1,4}(?:[.,]\\d{1,2})? *"
            + "(?i:stops?|mi|miles?|ft|min|mins|minutes|hr|hrs|hours)";
    private static final Pattern INSTRUCTION_FIGURE = Pattern.compile(
            "(?i:(?:(?:deliver |pick ?up )?by )?\\d{1,2}:\\d{2}(?: ?[ap]m)?)"
                    + "|\\+?\\$\\d++(?:[,.]\\d++)*+(?: (?i:peak pay))?"
                    + "|" + INSTRUCTION_METRIC + "(?:[ ()•·]++" + INSTRUCTION_METRIC + ")*+[ )]*+");

    /**
     * A card number: 13 to 19 digits, with single spaces or dashes between groups ("4111 1111 1111 1111",
     * "5555-5555-5555-4444", "378282246310005"), never part of a longer run of digits.
     */
    private static final Pattern CARD_NUMBER = Pattern.compile("(?<![\\w.,]|\\d[ -])\\d(?:[ -]?\\d){12,18}(?![ -]?\\d|\\w)");
    /** The headings of a card's secrets: its security code, its expiry and its PIN. */
    private static final String CARD_HEADING = "(?i:cvv2?|cvc2?|security code|expiry(?: date)?|expiration(?: date)?"
            + "|exp\\.?(?: date)?|expires|valid thru|valid through|pin(?: code)?)";
    /** "CVV 123", "Exp 12/34", "Expires: 12/2034", "PIN 4321": the heading stays, the value goes. A time stays. */
    private static final Pattern CARD_VALUE = Pattern.compile("(?<![\\p{L}\\d])(" + CARD_HEADING + ")(?![\\p{L}\\d])"
            + "(\\.? *:? *)(\\d(?:[\\d/-]*\\d)?)(?![\\w/-]|[:.,]\\d)");
    /** A heading that is a label of its own, with the value as the next label. */
    private static final Pattern CARD_HEADING_LABEL = Pattern.compile(CARD_HEADING + "\\.? *:?");
    /** The same in a log line of labels: "[CVV, 123]". */
    private static final Pattern CARD_VALUE_ITEM = Pattern.compile("(^|\\[|, )(" + CARD_HEADING + "\\.? *:?)(, )"
            + "(?!\\[card\\])([^,\\]\\n]*\\d[^,\\]\\n]*)(?=,|\\]|$)", Pattern.MULTILINE);
    private static final Pattern DIGIT = Pattern.compile("\\d");

    /** US numbers: "(513) 555-0100", "513-555-0100", "+1 513 555 0100", "5135550100"; never part of a longer run. */
    private static final Pattern PHONE_NUMBER = Pattern.compile(
            "(?<![\\w$.:#/])(?:\\+?1[ .-]?)?(?:\\(\\d{3}\\)[ .-]?|\\d{3}[ .-]?)\\d{3}[ .-]?\\d{4}(?!\\w)");

    private static final String STATES = "AL|AK|AZ|AR|CA|CO|CT|DE|DC|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN|MS|"
            + "MO|MT|NE|NV|NH|NJ|NM|NY|NC|ND|OH|OK|OR|PA|PR|RI|SC|SD|TN|TX|UT|VT|VA|WA|WV|WI|WY";
    /** A town before a state and ZIP, or before a ZIP and state: "Springfield, ". */
    private static final String TOWN = "(?:\\p{Lu}[\\p{L}'’.-]*(?: \\p{Lu}[\\p{L}'’.-]*){0,3}, ?)?";
    /** "Springfield, OH 45229, USA", "San Francisco, CA 94110", "OH 45229". */
    private static final Pattern CITY_STATE_ZIP = Pattern.compile(
            "(?iu)(?<![\\p{L}\\d'’.-])" + TOWN + "\\b(?:" + STATES + ") "
                    + "\\d{5}(?:-\\d{4})?\\b(?:,? (?:USA|US|United States)\\b)?");
    /** The ZIP before the state, as Dasher writes some customers' towns: "Springfield, 45229 OH", "45229 OH". */
    private static final Pattern CITY_ZIP_STATE = Pattern.compile(
            "(?iu)(?<![\\p{L}\\d'’.$#/-])" + TOWN + "\\d{5}(?:-\\d{4})? (?:" + STATES + ")\\b"
                    + "(?:,? (?:USA|US|United States)\\b)?");

    private static final String DIRECTION = "(?:[NSEW]|NE|NW|SE|SW|North|South|East|West)\\.?";
    private static final String STREET_TYPE = "(?=\\p{Lu})(?i:St|Street|Ave|Av|Avenue|Rd|Road|Blvd|Boulevard|Dr|Drive"
            + "|Ln|Lane|Way|Ct|Court|Pl|Place|Pkwy|Pky|Parkway|Cir|Circle|Hwy|Highway|Ter|Terrace|Trl|Trail|Sq|Square"
            + "|Pike|Plz|Plaza|Aly|Alley|Xing|Crossing|Cv|Cove|Loop|Expy|Expressway|Fwy|Freeway|Tpke|Turnpike)";
    /** A street's words ending in its type: "Example St", "N Example Parkway NW", "5th Ave". */
    private static final String STREET_WORDS = "(?:" + DIRECTION + " )?"
            + "(?:(?:\\p{Lu}[\\p{L}'’.-]*|\\d{1,3}(?:st|nd|rd|th)) ){1,4}" + STREET_TYPE + "\\b\\.?"
            + "(?: (?:[NSEW]|NE|NW|SE|SW)\\b\\.?)?";
    /** A house number and the street's words ending in its type: "100 Example St", "4201 N Example Parkway NW". */
    private static final Pattern HOUSE_AND_STREET = Pattern.compile(
            "(?<![\\w$:.,/#-])\\d{1,6}[A-Za-z]?(?:-\\d{1,5})? " + STREET_WORDS);
    /** "Apt/Suite: 504", "Apt 5B", "Suite 200", "Gate code 1234": the heading stays, the value goes. */
    private static final Pattern UNIT = Pattern.compile(
            "\\b((?i:apt/suite|apt|apartment|suite|ste|unit|bldg|building|room|rm|gate code|door code|access code"
                    + "|entry code|buzzer))\\b(\\.? *:? *#? *)(?!\\[)([A-Za-z]?\\d[\\w-]*|[A-Z]\\b)");

    /**
     * Turn-by-turn navigation names streets with no house number: after "onto", "on", "toward", "towards" and "via"
     * ("Turn left onto Elm Rd", "Continue on Oak Ave", "Exit onto I-71 N", "Toward Exit 40"), each street is masked up
     * to the label's end, or to a distance, a time or a joining word ("for", "then"), which stay.
     */
    private static final String STREET_LEAD = "(?<![\\p{L}\\d])((?i:onto|on|towards|toward|via))( +)(?!\\[)";
    private static final String STREET_STOP = "(?i: (?:for|in|then|and)\\b)| [•·|(]"
            + "| \\d{1,4}(?:[.,]\\d{1,2})? ?(?i:ft|mi|km|m|yd|min|mins|minutes|hr|hrs|h)\\b";
    private static final Pattern NAVIGATION_STREET_LABEL = Pattern.compile(
            STREET_LEAD + "([\\p{Lu}\\d][^\\n]*?)(?=" + STREET_STOP + "| *$)");
    private static final Pattern NAVIGATION_STREET_ITEM = Pattern.compile(
            STREET_LEAD + "([\\p{Lu}\\d][^,\\]\\n]*?)(?=" + STREET_STOP + "| *,| *\\]| *$)", Pattern.MULTILINE);
    /**
     * A label in navigation that is a street alone: "Elm Rd", "N Main St", "5th Ave", "Oak Avenue". Only types no
     * store's name ends in are taken ("Taco Place", "Town Square" and "Elm Way" stay).
     */
    private static final String STREET_ALONE_WORDS = "(?:" + DIRECTION + " )?"
            + "(?:(?:\\p{Lu}[\\p{L}'’.-]*|\\d{1,3}(?:st|nd|rd|th)) ){1,4}(?=\\p{Lu})(?i:St|Street|Ave|Av|Avenue|Rd|Road"
            + "|Blvd|Boulevard|Dr|Drive|Ln|Lane|Ct|Pl|Pkwy|Pky|Parkway|Cir|Hwy|Highway|Ter|Trl|Sq|Plz|Aly|Xing|Cv"
            + "|Expy|Expressway|Fwy|Freeway|Tpke|Turnpike)\\b\\.?(?: (?:[NSEW]|NE|NW|SE|SW)\\b\\.?)?";
    private static final Pattern STREET_LABEL = Pattern.compile(STREET_ALONE_WORDS);
    private static final Pattern STREET_ALONE_ITEM = Pattern.compile(
            "(^|\\[|, )" + STREET_ALONE_WORDS + "(?=,|\\]|$)", Pattern.MULTILINE);
    /** Combined navigation aliases, including a street already masked by an older release. */
    private static final String ROUTE_WORDS = "(?i:(?:(?:I|US|SR|" + STATES + ")[- ]?\\d{1,4}"
            + "|(?:state|county) (?:route|road) \\d{1,4})(?: (?:[NSEW]|NE|NW|SE|SW))?)";
    private static final String STREET_OR_ROUTE = "(?:" + STREET_ALONE_WORDS + "|" + ROUTE_WORDS + "|\\[street\\])";
    private static final Pattern NAVIGATION_ALIASES = Pattern.compile(
            "(^|\\[|, |(?<![\\p{L}\\d])(?i:onto|on|towards|toward|via) +)" + STREET_OR_ROUTE
            + "(?: */ *" + STREET_OR_ROUTE + "| *\\(" + STREET_OR_ROUTE + "\\))++"
            + "(?=" + STREET_STOP + "| *,| *\\]| *$)");
    /** Navigation, as {@link DasherScene#showsNavigation} knows it in a log line: a speed in mph and a distance. */
    private static final Pattern SPEED_WORD = Pattern.compile("(?<![\\p{L}])mph\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISTANCE_WORD = Pattern.compile(
            "(?<![\\d.,])\\b\\d{1,4}(?:[.,]\\d{1,2})? ?(?:ft|mi)\\b", Pattern.CASE_INSENSITIVE);
    /** A screens-log line of turn-by-turn navigation, as {@link DiagnosticLog#logNavigation} writes it. */
    private static final String NAVIGATION_TAG = " [navigation] ";

    /** "Deliver to Sam P", "Delivery for Sam", "Drop off for Noel G.", "Customer: Sam P". */
    private static final String CUSTOMER_HEADING = "(?i:deliver(?:ing)? to|delivery for|drop[- ]?off for|order for|verify items for)"
            + ":?|(?i:customer(?: name)?):";
    private static final Pattern CUSTOMER_NAME = Pattern.compile(
            "\\b(" + CUSTOMER_HEADING + ")( +|, +)(" + NAME_WORDS + ")");
    /** A heading that is a label of its own, with the name as the next label. */
    private static final Pattern CUSTOMER_HEADING_LABEL = Pattern.compile(
            "(?i:deliver(?:ing)? to|delivery for|drop[- ]?off for|order for|verify items for|customer|customer name) *:?");
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
    /** A dasher's badge, which follows their own name: "[icon] Pro Shopper", "Top Dasher", "Platinum". */
    private static final String BADGE_WORDS = "(?:\\[icon\\] *)?(?i:pro shopper|top dasher|platinum|gold|silver)";
    private static final Pattern BADGE = Pattern.compile(BADGE_WORDS);
    private static final Pattern NAME_BEFORE_BADGE = Pattern.compile(
            "(^|\\[|, )(" + NAME_WORDS + ")(, " + BADGE_WORDS + ")(?=,|\\]|$)", Pattern.MULTILINE);
    /**
     * Dasher's side menu opens with the dasher's own name, then "You're dashing now" (while dashing) or its first
     * items, "Home" and "Schedule".
     */
    private static final String DASHING_NOW_WORDS = "(?i:you['’]re dashing now)";
    private static final Pattern DASHING_NOW = Pattern.compile(DASHING_NOW_WORDS);
    private static final Pattern SIDE_MENU = Pattern.compile("(?i:side menu)");
    private static final Pattern MENU_ITEM = Pattern.compile("(?i:home|schedule)");
    private static final Pattern NAME_BEFORE_DASHING_NOW = Pattern.compile(
            "(^|\\[|, )(" + NAME_WORDS + ")(, " + DASHING_NOW_WORDS + ")(?=,|\\]|$)", Pattern.MULTILINE);
    private static final Pattern NAME_BEFORE_MENU_ITEM = Pattern.compile(
            "(^|\\[|, )(" + NAME_WORDS + ")(, (?i:home|schedule))(?=,|\\]|$)", Pattern.MULTILINE);
    private static final Pattern SIDE_MENU_IN_LINE = Pattern.compile("(?<![\\p{L}])(?i:side menu|you['’]re dashing now)"
            + "(?![\\p{L}])");

    /**
     * Whether these labels show a payment, account or earnings screen ({@link #ACCOUNT_MARKERS}, case-insensitive,
     * as a word or phrase in any label): such a screen is never kept, not even masked.
     */
    static boolean accountScreen(Collection<String> labels) {
        if (labels == null) return false;
        for (String label : labels) {
            if (label != null && ACCOUNT_MARKER.matcher(label).find()) return true;
        }
        return false;
    }

    /** Whether one piece of screen text (a label, or a line of them) holds a payment, account or earnings marker. */
    static boolean accountText(String text) {
        return text != null && ACCOUNT_MARKER.matcher(text).find();
    }

    /**
     * Labels as a log line carries them: masked ({@link #mask(List, boolean)}), or {@link #LABELS_NOT_KEPT} when they
     * show a payment, account or earnings screen.
     */
    static String kept(List<String> labels) {
        if (accountScreen(labels)) return LABELS_NOT_KEPT;
        return recognizedDashScreen(labels) ? String.valueOf(mask(labels)) : "[" + UNKNOWN_NOT_KEPT + "]";
    }

    /** Unknown/partial screens are not diagnostic text: a CVV alone has no payment marker to mask. */
    static boolean recognizedDashScreen(List<String> labels) {
        if (labels == null || accountScreen(labels)) return false;
        if (OfferEvidence.isIdle(labels) || AcceptedOfferTracker.isDeliveryScreen(labels)
                || DasherScene.showsNavigation(labels) || DeclineConfirmation.hasPrompt(labels)) return true;
        boolean accept = false, decline = false;
        for (String label : labels) {
            if (label == null) continue;
            String text = OfferEvidence.normalize(label);
            String lower = text.toLowerCase(Locale.US);
            if (lower.startsWith("pick up by ") || lower.startsWith("pickup by ")
                    || lower.equals("complete pickup steps") || lower.equals("complete delivery steps")) return true;
            accept |= text.equalsIgnoreCase("Accept");
            decline |= text.equalsIgnoreCase("Decline");
        }
        return accept && decline;
    }

    /**
     * One label as read (or any one piece of screen text): a customer's own words after a heading run to the end of
     * it.
     */
    static String mask(String label) {
        return apply(label, true, false);
    }

    /**
     * Labels as read, in their order: each masked as {@link #mask(String)}, and a label that is a name, a card's secret
     * or a street only because of the labels beside it (the dasher's name above "651 orders completed", a name after a
     * "Customer" heading, the value after "CVV") too. Navigation's streets are masked when the labels show navigation
     * (a speed in mph and a distance).
     */
    static List<String> mask(List<String> labels) {
        return mask(labels, DasherScene.showsNavigation(labels));
    }

    /**
     * As {@link #mask(List)}, with {@code navigation} saying whether these are turn-by-turn navigation's labels (its
     * labels and its metric parts are masked apart, and either may hold the speed or the distance).
     */
    static List<String> mask(List<String> labels, boolean navigation) {
        List<String> out = new ArrayList<>();
        if (labels == null) return out;
        boolean sideMenu = false;
        for (String label : labels) {
            if (label == null) continue;
            String trimmed = label.trim();
            if (SIDE_MENU.matcher(trimmed).matches() || DASHING_NOW.matcher(trimmed).matches()) sideMenu = true;
        }
        boolean instructions = false;
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            if (label == null) {
                out.add(null);
                continue;
            }
            String trimmed = label.trim();
            boolean heading = INSTRUCTION_HEADING_LABEL.matcher(trimmed).matches();
            if (instructionBoundary(trimmed)) instructions = false;
            if (instructions && !heading && !trimmed.isEmpty()) {
                out.add(INSTRUCTIONS);
                continue;
            }
            if (heading || INSTRUCTIONS_LABEL.matcher(trimmed).find()
                    || UNQUOTED_INSTRUCTIONS_LABEL.matcher(trimmed).matches()) instructions = true;
            String next = i + 1 < labels.size() && labels.get(i + 1) != null ? labels.get(i + 1).trim() : null;
            String before = i > 0 && labels.get(i - 1) != null ? labels.get(i - 1).trim() : null;
            boolean named = (next != null && (COMPLETED.matcher(next).find() || BADGE.matcher(next).matches()
                    || DASHING_NOW.matcher(next).matches() || (sideMenu && MENU_ITEM.matcher(next).matches())))
                    || (before != null && CUSTOMER_HEADING_LABEL.matcher(before).matches());
            if (named && isName(trimmed)) {
                out.add(NAME);
            } else if (before != null && CARD_HEADING_LABEL.matcher(before).matches()
                    && DIGIT.matcher(label).find()) {
                out.add(CARD);
            } else {
                String masked = apply(label, true, navigation);
                // In navigation, a label that is a street alone ("Elm Rd"), with nothing else masked in it.
                boolean street = navigation && masked.equals(label) && STREET_LABEL.matcher(trimmed).matches();
                out.add(street ? STREET : masked);
            }
        }
        return out;
    }

    /**
     * A log line, which may hold labels written as a list ("labels=[Deliver to Sam P, 100 Example St, …]"): a
     * customer's own words remain private across ambiguous list delimiters and line breaks. Complete controls,
     * figures and emitted record framing end that context. Other masks run line by line; navigation's streets are
     * masked in a navigation line (marked "[navigation]", or showing a speed in mph and a distance).
     */
    static String maskLine(String line) {
        return maskLine(line, false);
    }

    /**
     * As {@link #maskLine(String)}, with {@code navigation} saying it is navigation's. A physical line break in an
     * older instruction is not a new record: mask that body before applying the other per-line masks.
     */
    static String maskLine(String line, boolean navigation) {
        if (line == null || line.isEmpty()) return line == null ? "" : line;
        line = instructionItems(line);
        if (line.indexOf('\n') < 0) return apply(line, false, navigation || isNavigationLine(line));
        StringBuilder out = new StringBuilder(line.length());
        int start = 0;
        while (start <= line.length()) {
            int end = line.indexOf('\n', start);
            String one = line.substring(start, end < 0 ? line.length() : end);
            out.append(apply(one, false, navigation || isNavigationLine(one)));
            if (end < 0) break;
            out.append('\n');
            start = end + 1;
        }
        return out.toString();
    }

    /** One line of text: marked as navigation's, or showing a speed in mph and a distance. */
    private static boolean isNavigationLine(String line) {
        return line.contains(NAVIGATION_TAG)
                || (SPEED_WORD.matcher(line).find() && DISTANCE_WORD.matcher(line).find());
    }

    private static String apply(String text, boolean wholeLabel, boolean navigation) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        String out = EMAIL_ADDRESS.matcher(text).replaceAll(Matcher.quoteReplacement(EMAIL));
        if (wholeLabel) {
            out = QUOTED_LABEL.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(INSTRUCTIONS));
            out = INSTRUCTIONS_LABEL.matcher(out).replaceAll("$1$2" + Matcher.quoteReplacement(INSTRUCTIONS));
            Matcher instruction = UNQUOTED_INSTRUCTIONS_LABEL.matcher(out);
            if (instruction.matches() && !keptInstructionLabel(instruction.group(3).trim())) {
                out = instruction.group(1) + instruction.group(2) + INSTRUCTIONS;
            }
        } else {
            out = QUOTED_ITEM.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(INSTRUCTIONS));
            out = QUOTE_OPEN_ITEM.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(INSTRUCTIONS));
            out = instructionItems(out);
        }
        // Card numbers before phone numbers, which could otherwise take a piece of one.
        out = cardNumbers(out);
        out = CARD_VALUE.matcher(out).replaceAll("$1$2" + Matcher.quoteReplacement(CARD));
        if (!wholeLabel) out = CARD_VALUE_ITEM.matcher(out).replaceAll("$1$2$3" + Matcher.quoteReplacement(CARD));
        out = PHONE_NUMBER.matcher(out).replaceAll(Matcher.quoteReplacement(PHONE));
        // The house and street before the town: a town's pattern takes a street's last words for a town ("53 East 4th
        // Street, 45202 OH", a store's address on Dasher's offer card, kept "53 East 4th").
        out = HOUSE_AND_STREET.matcher(out).replaceAll(Matcher.quoteReplacement(ADDRESS));
        out = CITY_STATE_ZIP.matcher(out).replaceAll(Matcher.quoteReplacement(ADDRESS));
        out = CITY_ZIP_STATE.matcher(out).replaceAll(Matcher.quoteReplacement(ADDRESS));
        out = UNIT.matcher(out).replaceAll("$1$2" + Matcher.quoteReplacement(ADDRESS));
        if (navigation) {
            out = NAVIGATION_ALIASES.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(STREET));
            out = (wholeLabel ? NAVIGATION_STREET_LABEL : NAVIGATION_STREET_ITEM).matcher(out)
                    .replaceAll("$1$2" + Matcher.quoteReplacement(STREET));
            if (!wholeLabel) out = STREET_ALONE_ITEM.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(STREET));
        }
        out = names(CUSTOMER_NAME, out, 3, "$1$2");
        out = names(GREETING, out, 2, "$1");
        out = possessives(out);
        out = names(NAME_BEFORE_COMPLETED, out, 2, "$1", "$3");
        if (wholeLabel) return out;
        out = names(NAME_BEFORE_BADGE, out, 2, "$1", "$3");
        out = names(NAME_BEFORE_DASHING_NOW, out, 2, "$1", "$3");
        return SIDE_MENU_IN_LINE.matcher(out).find() ? names(NAME_BEFORE_MENU_ITEM, out, 2, "$1", "$3") : out;
    }

    private static boolean keptInstructionLabel(String label) {
        return INSTRUCTION_HEADING_LABEL.matcher(label).matches() || instructionBoundary(label);
    }

    private static boolean instructionBoundary(String label) {
        return INSTRUCTION_CONTROL.matcher(label).matches() || INSTRUCTION_FIGURE.matcher(label).matches();
    }

    /**
     * Older logs used List.toString, so a comma within a customer's words is not a reliable label boundary. Keep
     * masking until a complete control/figure, or the list/line end. Bracketed placeholders belong to the body,
     * including an [email] just inserted above; they must not expose the instruction's remaining words.
     */
    private static String instructionItems(String text) {
        Matcher headings = INSTRUCTIONS_ITEM.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        int search = 0;
        while (headings.find(search)) {
            int start = headings.end();
            // A heading with no body must not consume the next real log entry just because its separator is a newline.
            if (headings.group().indexOf('\n') >= 0 && logEntryAt(text, start)) {
                search = start;
                continue;
            }
            int end = instructionItemEnd(text, start);
            String first = text.substring(start, end).trim();
            if (keptInstructionLabel(first)
                    || (first.isEmpty() && (end == text.length() || text.charAt(end) != ','))) {
                search = Math.max(start, end);
                continue;
            }
            while (end < text.length() && text.charAt(end) == ',' && end + 1 < text.length()
                    && text.charAt(end + 1) == ' ') {
                int next = end + 2;
                int nextEnd = instructionItemEnd(text, next);
                if (keptInstructionLabel(text.substring(next, nextEnd).trim())) break;
                end = nextEnd;
            }
            out.append(text, copied, start).append(INSTRUCTIONS);
            copied = end;
            search = end;
        }
        return out.append(text, copied, text.length()).toString();
    }

    /**
     * Unknown brackets and unframed newlines are still instruction text. Only our complete mask tokens are skipped;
     * a stray bracket cannot stop masking or hide the next complete numeric/control item inside a fake nesting level.
     */
    private static int instructionItemEnd(String text, int start) {
        for (int at = start; at < text.length(); at++) {
            char value = text.charAt(at);
            if (value == '[') {
                for (String token : MASK_TOKENS) {
                    if (text.startsWith(token, at)) {
                        at += token.length() - 1;
                        break;
                    }
                }
            } else if (value == ']' && instructionListEnd(text, at + 1)) {
                return at;
            } else if (value == '\n' && (logEntryAt(text, at + 1) || screensSectionAt(text, at))) {
                return at;
            } else if (value == ',' && at + 1 < text.length() && text.charAt(at + 1) == ' ') {
                return at;
            }
        }
        return text.length();
    }

    private static boolean logEntryAt(String text, int start) {
        Matcher entry = LOG_ENTRY.matcher(text).region(start, text.length());
        return (entry.lookingAt() && DiagnosticLog.isTimestamp(entry.group(1)))
                || RELATIVE_LOG_ENTRY.matcher(text).region(start, text.length()).lookingAt();
    }

    /** The one section separator appended after the raw log by DiagnosticLog.fullReport(). */
    private static boolean screensSectionAt(String text, int start) {
        return text.startsWith("\n\n== Dasher's other screens (newest)\n", start);
    }

    private static boolean instructionListEnd(String text, int start) {
        int at = start;
        while (at < text.length() && text.charAt(at) == ' ') at++;
        if (at == text.length() || text.startsWith("metricParts=[", at)) return true;
        // A closing bracket alone is ambiguous. The following real record or producer section confirms framing.
        while (at < text.length() && (text.charAt(at) == '\n' || text.charAt(at) == '\r')) {
            if (screensSectionAt(text, at)) return true;
            at++;
        }
        return at == text.length() || logEntryAt(text, at);
    }

    /**
     * Each card number as {@link #CARD}. Only an explicit app timestamp ("epoch ms: 1727654321000")
     * is exempt; a number's first digit alone cannot establish that it is not personal data.
     */
    private static String cardNumbers(String text) {
        Matcher found = CARD_NUMBER.matcher(text);
        if (!found.find()) return text;
        StringBuffer out = new StringBuffer();
        do {
            String number = found.group();
            String prefix = "epoch ms: ";
            boolean millis = number.length() == 13 && number.chars().allMatch(Character::isDigit)
                    && found.start() >= prefix.length()
                    && text.regionMatches(found.start() - prefix.length(), prefix, 0, prefix.length());
            found.appendReplacement(out, Matcher.quoteReplacement(millis ? number : CARD));
        } while (found.find());
        found.appendTail(out);
        return out.toString();
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
