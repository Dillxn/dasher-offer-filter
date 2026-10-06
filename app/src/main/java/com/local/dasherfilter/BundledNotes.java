package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Short notes bundled with each release, written by the docs package, read with no connection:
 * <ul>
 * <li>what changed in the notice, by the {@link Consent#VERSION} that brought it: a "What changed" box at the top of
 * the notice for someone who accepted an older one (never for a new install, which reads the whole notice anyway);</li>
 * <li>what is new in a version, by its version name: a one-time card on the homepage after an update to it.</li>
 * </ul>
 * Until the docs package writes the final words, the lines are marked placeholders and nothing is shown:
 * {@link #WRITTEN} stays false. The docs package replaces every placeholder and sets WRITTEN to true in the same
 * change; BundledNotesTest then fails while any placeholder remains, while the current notice version or app version
 * has no entry, or while every line is written but WRITTEN is still false. A few short lines each; no paragraph.
 */
final class BundledNotes {
    /** The docs package's one switch: true once every line below is final (and none is a placeholder). */
    static final boolean WRITTEN = false;
    /** How a line still to be written starts. */
    static final String PLACEHOLDER = "[DOCS PACKAGE:";

    private static final Map<Integer, List<String>> NOTICE = new LinkedHashMap<>();
    private static final Map<String, List<String>> RELEASES = new LinkedHashMap<>();

    static {
        // What changed in the notice since the one before it. Two or three short lines, plain words.
        notice(14,
                "[DOCS PACKAGE: what changed in notice 14, line 1 (e.g. feedback no longer needs GitHub)]",
                "[DOCS PACKAGE: line 2, or delete this line]");
        // What is new in this version, shown once after the update. Two or three short lines, plain words.
        release("0.4.73",
                "[DOCS PACKAGE: what is new in 0.4.73, line 1]",
                "[DOCS PACKAGE: line 2, or delete this line]");
    }

    private BundledNotes() {}

    private static void notice(int version, String... lines) {
        NOTICE.put(version, Collections.unmodifiableList(Arrays.asList(lines)));
    }

    private static void release(String versionName, String... lines) {
        RELEASES.put(versionName, Collections.unmodifiableList(Arrays.asList(lines)));
    }

    /** What changed since the notice {@code accepted} was accepted; nothing for a new install or while unwritten. */
    static List<String> noticeChanges(int accepted) {
        return noticeChanges(accepted, WRITTEN);
    }

    /**
     * As {@link #noticeChanges(int)}, with whether the words are final given: the lines of every notice after
     * {@code accepted} up to the current one, oldest first. {@code accepted} 0 is a new install: nothing.
     */
    static List<String> noticeChanges(int accepted, boolean written) {
        List<String> lines = new ArrayList<>();
        if (!written || accepted <= 0 || accepted >= Consent.VERSION) return lines;
        for (Map.Entry<Integer, List<String>> notice : NOTICE.entrySet()) {
            if (notice.getKey() > accepted && notice.getKey() <= Consent.VERSION) lines.addAll(notice.getValue());
        }
        return lines;
    }

    /** What is new in {@code versionName}; nothing while unwritten or with no entry. */
    static List<String> whatsNew(String versionName) {
        return whatsNew(versionName, WRITTEN);
    }

    static List<String> whatsNew(String versionName, boolean written) {
        List<String> lines = written ? RELEASES.get(versionName) : null;
        return lines == null ? Collections.<String>emptyList() : lines;
    }

    /** Every line, notices then releases, for the docs package's checks. */
    static List<String> allLines() {
        List<String> all = new ArrayList<>();
        for (List<String> lines : NOTICE.values()) all.addAll(lines);
        for (List<String> lines : RELEASES.values()) all.addAll(lines);
        return all;
    }

    static boolean hasNotice(int version) {
        return NOTICE.containsKey(version);
    }

    static boolean hasRelease(String versionName) {
        return RELEASES.containsKey(versionName);
    }
}
