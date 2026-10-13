package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The stores an offer on Dasher's screen is from: after the trip line ("2 stops (4.2 mi) • 14 min") Dasher lists each
 * pickup store with its address lines under it ("DiBella's Subs", then two address lines; an offer from two stores
 * lists both, each with its address), then the drop-off ("Customer dropoff", "Multiple dropoffs (2 stops)"), with
 * "Pick up 2 orders" for two orders from one store (0.6.10, from the owner's 13 offers of 12 October 2026). Where no
 * address is recognised, the label right after the trip line is taken as the one store (0.6.9's rule).
 *
 * <p>{@link CameBack} uses a single-store offer's store, in memory only; the skip list ({@link SkipList}) declines an
 * offer when any of its stores is on it. No history line or report ever holds a store's name.
 */
final class StoreName {
    private StoreName() {}

    /** Dasher's trip line: "2 stops (4.2 mi) • 14 min". */
    private static final Pattern TRIP = Pattern.compile("(?i)\\b\\d{1,2}\\s+stops?\\s*\\(\\s*\\d+(?:[.,]\\d+)?\\s*(?:mi|km)\\b");
    /** Where the pickups end: the drop-off, the offer's own words or its buttons. */
    private static final Pattern END = Pattern.compile("(?i)^(?:customer drop|multiple drop|pick[ -]?up \\d|guaranteed|"
            + "accept|decline|items? may|declining)");
    private static final Pattern COUNTDOWN = Pattern.compile("^\\d{1,2}:[0-5]\\d$");
    private static final Pattern LETTER = Pattern.compile("\\p{L}");
    /** Words of the offer screen itself, never a store's name. */
    private static final Pattern CHROME = Pattern.compile("(?i)guaranteed|multiple drop|pick[ -]?up|items? may|"
            + "declin|accept|incl\\.|\\btips?\\b|\\bstops?\\b|\\bmi\\b|\\bmins?\\b|\\bitems?\\b|order for|\\[|"
            + "earnings|deliver|\\bshop\\b|red card|busy|customer|dropoff");
    /** A store name longer than this is not taken (a sentence, not a name). */
    static final int MOST_CHARS = 48;

    /** The pickup stores as written, in their order, each once; empty when the screen shows none we can trust. */
    static List<String> stores(List<String> labels) {
        List<String> out = new ArrayList<>();
        if (labels == null) return out;
        int trip = -1;
        for (int i = 0; i < labels.size() && trip < 0; i++) {
            if (labels.get(i) != null && TRIP.matcher(labels.get(i)).find()) trip = i;
        }
        if (trip < 0) return out;
        List<String> keys = new ArrayList<>();
        for (int i = trip + 1; i < labels.size(); i++) {
            String line = trimmed(labels.get(i));
            if (END.matcher(line).find() || COUNTDOWN.matcher(line).matches() || line.startsWith("$")) break;
            if (address(line) || !plausible(line)) continue;
            if (i + 1 < labels.size() && address(trimmed(labels.get(i + 1))) && !keys.contains(key(line))) {
                keys.add(key(line));
                out.add(line);
            }
        }
        if (out.isEmpty() && trip + 1 < labels.size()) {
            String next = trimmed(labels.get(trip + 1));
            if (plausible(next) && !address(next)) out.add(next);
        }
        return out;
    }

    /** The store of an offer from one store, or "" (none read, or more than one). */
    static String read(List<String> labels) {
        List<String> stores = stores(labels);
        return stores.size() == 1 ? stores.get(0) : "";
    }

    private static String trimmed(String label) {
        return label == null ? "" : label.trim();
    }

    /** An address line: masked as one on the phone ({@link PersonalText}), or already masked in a report. */
    private static boolean address(String line) {
        if (line.isEmpty()) return false;
        if (line.contains(PersonalText.ADDRESS)) return true;
        return PersonalText.mask(line).contains(PersonalText.ADDRESS);
    }

    private static boolean plausible(String name) {
        return name.length() >= 2 && name.length() <= MOST_CHARS && LETTER.matcher(name).find()
                && !name.contains("$") && !Character.isDigit(name.charAt(0)) && !COUNTDOWN.matcher(name).matches()
                && !CHROME.matcher(name).find();
    }

    /** The same store however Dasher spaced or cased it. */
    static String key(String name) {
        return name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.US);
    }
}
