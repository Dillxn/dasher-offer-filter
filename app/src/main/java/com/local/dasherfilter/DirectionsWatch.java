package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Directions watch (0.6.11, diagnostics only): for {@link #WATCH_MS} after the user's tap on Dasher's Directions,
 * or any tap of theirs on Dasher during a delivery, the classes Dasher's window changes name, Android's list of windows
 * at {@link #LOOKS_MS}, and one verdict: Dasher dropped back to the screen it showed before the tap (in a new window,
 * Dasher re-created it, as after its process restarted; in the same one, the page closed itself), stayed on another, or
 * another app or the system came in front. From the events Android already delivered and its list of windows: no node
 * of Dasher's is read for it, so it runs while paused too. It decides, taps and counts nothing.
 *
 * <p>Why: the owner, 13 October 2026, on 0.6.10: "in app dasher nav maps is still broken in various scenarios. just had
 * to type in address manually on the last dash", and "never shows the map just shows multiple pages opening and then
 * drops back". His report showed eleven unrecognised Dasher screens in 25 s and could not say what they were.
 *
 * <p>Since 0.6.24 Dasher's own in-app navigation page coming up begins a watch too ({@link #armAtPage}), whatever
 * watch is under way: on 13 October the watch the receipt photo's tap began ended 4 s after that page came up, before
 * it dropped back; and on 15 October ("freakin nav, man. still broken", 00:51 EDT) the report came after the log had
 * rolled past it, so the log keeps such a watch for a day when it went wrong ({@link DiagnosticLog#fitLog}).
 *
 * <p>Any thread: Dasher's window changes come on the main thread, the looks on the scanner.
 */
final class DirectionsWatch {
    static final String SOURCE = "directions";
    static final long WATCH_MS = 20_000L;
    /** At most one watch a minute. */
    static final long ARM_EVERY_MS = 60_000L;
    /** Class lines per watch at most. */
    static final int LINES = 12;
    /** When Android's list of windows is logged, after the tap. */
    static final long[] LOOKS_MS = {2_000L, 5_000L, 10_000L, 20_000L};

    private static final long NEVER = Long.MIN_VALUE;

    /** How a navigation page's watch begins in the log (0.6.24): what {@link DiagnosticLog#fitLog} keeps a day. */
    static final String PAGE_WATCH = "watching " + WATCH_MS / 1000 + " s after Dasher opened ";
    /** How its verdict reads when the page stayed: such a watch is not kept past the rest of the log. */
    static final String STAYED = "stayed on ";

    private long armedAt = NEVER;
    /** When the newest watch a navigation page began, began (uptime), or {@link #NEVER}. */
    private long pageArmedAt = NEVER;
    private String before;
    private Set<Integer> known = Collections.emptySet();
    private String last;
    private boolean lastNew;
    private boolean moved;
    private int lines;
    private boolean verdict;

    /**
     * The user's tap at {@code at} (uptime): a watch begins, unless one began less than {@link #ARM_EVERY_MS} ago.
     *
     * @param before the class Dasher's last window change named before the tap (short), or null
     * @param knownWindows Dasher's window IDs known before the tap
     * @return the line to log for it, or null when no watch began
     */
    synchronized String arm(long at, String before, Set<Integer> knownWindows, boolean directions) {
        if (armedAt != NEVER && at - armedAt < ARM_EVERY_MS && at >= armedAt) return null;
        begin(at, before, knownWindows);
        return "watching " + WATCH_MS / 1000 + " s after your tap on " + (directions ? "Directions" : "Dasher")
                + " during a delivery; on " + named(before);
    }

    /**
     * Dasher's window changed at {@code at} from {@code before} to {@code page}, its own in-app navigation page
     * ({@link #isNavigationPage}): a watch begins there (0.6.24), whatever watch is under way, unless one a navigation
     * page began is still under way (Dasher opening its page again and again is one watch's lines).
     *
     * @param before the class Dasher's last window change named before the page (short), or null
     * @param knownWindows Dasher's window IDs known before the page
     * @return the line to log for it, or null when no watch began
     */
    synchronized String armAtPage(long at, String page, String before, Set<Integer> knownWindows) {
        if (page == null || page.equals(before)) return null;
        if (pageArmedAt != NEVER && at >= pageArmedAt && at - pageArmedAt <= WATCH_MS) return null;
        begin(at, before, knownWindows);
        pageArmedAt = at;
        return PAGE_WATCH + page + "; on " + named(before);
    }

    private void begin(long at, String before, Set<Integer> knownWindows) {
        armedAt = at;
        this.before = before;
        known = knownWindows == null ? Collections.<Integer>emptySet() : new HashSet<>(knownWindows);
        last = before;
        lastNew = false;
        moved = false;
        lines = 0;
        verdict = false;
    }

    private static String named(String screen) {
        return screen == null ? "an unnamed screen" : screen;
    }

    /**
     * Whether a class Dasher's window change names (short) is Dasher's own in-app navigation page: an activity whose
     * name says navigation, such as the drop-off leg's GoToLocationInAppNavigationActivity (13 October 2026).
     */
    static boolean isNavigationPage(String shortClass) {
        return shortClass != null && shortClass.endsWith("Activity") && shortClass.contains("Navigation");
    }

    /** When the watch under way began (uptime), or {@link Long#MIN_VALUE} for none: a look carries it. */
    synchronized long armedAt() {
        return armedAt;
    }

    /** Whether a watch is under way at {@code at}. */
    synchronized boolean watching(long at) {
        return armedAt != NEVER && at >= armedAt && at - armedAt <= WATCH_MS;
    }

    /**
     * One of Dasher's window changes at {@code at}, as its event names it: the lines to log (none outside a watch, or
     * past {@link #LINES}), the verdict among them when Dasher is back on the class it showed before the tap.
     *
     * @param framework whether the class is Android's own (a dialog), which names no screen of Dasher's
     */
    synchronized List<String> window(long at, String shortClass, int windowId, boolean framework) {
        List<String> out = new ArrayList<>();
        if (!watching(at) || shortClass == null) return out;
        boolean fresh = windowId < 0 || !known.contains(windowId);
        if (lines < LINES) {
            lines++;
            out.add(String.format(Locale.US, "+%.1f s %s (%s window)", (at - armedAt) / 1000.0, shortClass,
                    fresh ? "new" : "known"));
        }
        if (framework) return out;
        if (!verdict && moved && before != null && before.equals(shortClass)) {
            verdict = true;
            out.add("dropped back to " + shortClass + " after " + Math.max(0, (at - armedAt) / 1000) + " s (window "
                    + (fresh ? "new" : "known") + ")");
        }
        if (!shortClass.equals(before)) moved = true;
        last = shortClass;
        lastNew = fresh;
        return out;
    }

    /**
     * A look {@code after} ms into the watch begun at {@code armed}: the lines to log, the verdict too at the last
     * look when none was logged. None for a watch no longer under way.
     *
     * @param windows the window field ({@link SplitWindows#field}) with the screen's state
     * @param front what is in front, in fixed words ("Dasher Home", "a navigation app", "another app", "home",
     *     "system")
     * @param dasherFront whether Dasher is in front
     */
    synchronized List<String> look(long armed, long after, String windows, String front, boolean dasherFront) {
        List<String> out = new ArrayList<>();
        if (armed != armedAt || armed == NEVER) return out;
        out.add("+" + after / 1000 + " s: " + windows + "; in front: " + front);
        if (after < WATCH_MS || verdict) return out;
        verdict = true;
        out.add(dasherFront ? STAYED + named(last)
                + (last != null && !last.equals(before) ? " (window " + (lastNew ? "new" : "known") + ")" : "")
                : front + " in front");
        return out;
    }

    /** Whether {@code words} are exactly "Directions": a control's own words (trimmed, any case). */
    static boolean isDirections(CharSequence words) {
        return words != null && OfferControls.normalize(words.toString()).equals("directions");
    }
}
