package com.local.dasherfilter;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The store an offer on Dasher's screen is from (0.6.9): the label right after the trip line, as Dasher draws it
 * ("2 stops (4.2 mi) • 14 min", "DiBella's Subs", then the address). It has no authority: nothing is declined or
 * accepted by it. {@link CameBack} keeps it in memory only, and no history line or report ever holds it.
 */
final class StoreName {
    private StoreName() {}

    /** Dasher's trip line: "2 stops (4.2 mi) • 14 min". */
    private static final Pattern TRIP = Pattern.compile("(?i)\\b\\d{1,2}\\s+stops?\\s*\\(\\s*\\d+(?:[.,]\\d+)?\\s*(?:mi|km)\\b");
    /** "Pick up 2 orders": more than one order to collect, so possibly more than one store. */
    private static final Pattern PICKUPS = Pattern.compile("(?i)\\bpick[ -]?up\\s+(\\d{1,2})\\s+orders?\\b");
    private static final Pattern COUNTDOWN = Pattern.compile("^\\d{1,2}:[0-5]\\d$");
    private static final Pattern LETTER = Pattern.compile("\\p{L}");
    /** Words of the offer screen itself, never a store's name. */
    private static final Pattern CHROME = Pattern.compile("(?i)guaranteed|multiple drop|pick[ -]?up|items? may|"
            + "declin|accept|incl\\.|\\btips?\\b|\\bstops?\\b|\\bmi\\b|\\bmins?\\b|\\bitems?\\b|order for|\\[|"
            + "earnings|deliver|\\bshop\\b|red card|busy");
    /** A store name longer than this is not taken (a sentence, not a name). */
    static final int MOST_CHARS = 48;

    /** The store's name as written, or "" when the screen shows none we can trust. */
    static String read(List<String> labels) {
        if (labels == null) return "";
        for (int i = 0; i + 1 < labels.size(); i++) {
            String line = labels.get(i);
            if (line == null || !TRIP.matcher(line).find()) continue;
            String next = labels.get(i + 1) == null ? "" : labels.get(i + 1).trim();
            return plausible(next) ? next : "";
        }
        return "";
    }

    private static boolean plausible(String name) {
        return name.length() >= 2 && name.length() <= MOST_CHARS && LETTER.matcher(name).find()
                && !name.contains("$") && !Character.isDigit(name.charAt(0)) && !COUNTDOWN.matcher(name).matches()
                && !CHROME.matcher(name).find();
    }

    /** Whether the offer has one pickup: no "Pick up 2 orders" (a stacked offer may come from several stores). */
    static boolean singleStore(List<String> labels) {
        if (labels == null) return false;
        for (String line : labels) {
            if (line == null) continue;
            Matcher pickups = PICKUPS.matcher(line);
            if (pickups.find() && Integer.parseInt(pickups.group(1)) > 1) return false;
        }
        return true;
    }

    /** The same store however Dasher spaced or cased it. */
    static String key(String name) {
        return name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.US);
    }
}
