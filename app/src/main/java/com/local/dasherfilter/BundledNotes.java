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
    static final boolean WRITTEN = true;
    /** How a line still to be written starts. */
    static final String PLACEHOLDER = "[DOCS PACKAGE:";

    private static final Map<Integer, List<String>> NOTICE = new LinkedHashMap<>();
    private static final Map<String, List<String>> RELEASES = new LinkedHashMap<>();

    static {
        // What changed in the notice since the one before it. Two or three short lines, plain words. Notice 14 is
        // first published with 0.5.0; its reader last accepted notice 13.
        // Notice 13 and earlier texts called the repository older versions filed reports in "private"; it is public
        // (the owner's decision, 6 October 2026), and the first line corrects that for everyone who read them.
        notice(14,
                "Feedback needs no account and GitHub is gone. Reports older versions sent went to the project's "
                        + "public GitHub repository, not a private one.",
                "Optional Autopilot moves your cutoff up or down and may pass offers below your minimums to you. "
                        + "Dasher's last acceptance rate is kept, used 7 days at most.",
                "Mid-dash your unlocked screen won't time out, and Peek can check an offer that came while locked. "
                        + "Dash diagnostics are opt-in. The Terms name Ohio law.");
        // What is new in this version, shown once after the update. Two or three short lines, plain words. Keyed by
        // the versionName that ships (BundledNotesTest requires the current versionName's entry). What the code does,
        // never an outcome no phone has shown yet.
        release("0.5.0",
                "New Autopilot (the Auto button) adjusts how much of your minimums an offer must pay, toward a goal "
                        + "you pick: acceptance rate 70%, 50%, or pay first.",
                "Peek now taps Dasher's own notification when an offer won't show and checks an offer that came "
                        + "while locked; mid-dash your unlocked screen won't time out.",
                "Lighter on Dasher: recognized maps are skipped and screens with no offer are read less often. "
                        + "Feedback needs no account: Settings → Send anonymous feedback.");
        // 0.4.73 never shipped and nothing builds under its name any more: it has no entry (and so no card).
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
