package com.local.dasherfilter;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * BETA-11 and BETA-22's bundled words, written by the docs package. Its one switch, {@link BundledNotes#WRITTEN},
 * must match the words: this fails while any placeholder remains once it is true, and while it is still false after
 * every placeholder was replaced. Until then nothing is shown, so no placeholder can reach a user.
 */
@RunWith(RobolectricTestRunner.class)
public class BundledNotesTest {

    private static boolean placeholdersRemain() {
        for (String line : BundledNotes.allLines()) if (line.contains(BundledNotes.PLACEHOLDER)) return true;
        return false;
    }

    @Test public void theDocsPackageSwitchMatchesTheWords() {
        if (BundledNotes.WRITTEN) {
            for (String line : BundledNotes.allLines()) {
                assertFalse("a placeholder is left: " + line, line.contains(BundledNotes.PLACEHOLDER));
            }
        } else {
            assertTrue("every placeholder was replaced: set BundledNotes.WRITTEN to true", placeholdersRemain());
        }
    }

    @Test public void theCurrentNoticeAndVersionHaveTheirEntry() {
        String version = Updater.version(RuntimeEnvironment.getApplication());
        assertTrue("notice " + Consent.VERSION + " needs its What changed lines", BundledNotes.hasNotice(Consent.VERSION));
        assertTrue(version + " needs its What is new lines", BundledNotes.hasRelease(version));
        for (String line : BundledNotes.allLines()) {
            assertFalse("one short line each: " + line, line.trim().isEmpty() || line.contains("\n")
                    || line.length() > 160);
        }
    }

    @Test public void nothingIsShownUntilTheWordsAreWritten() {
        if (BundledNotes.WRITTEN) return;
        String version = Updater.version(RuntimeEnvironment.getApplication());
        assertTrue(BundledNotes.noticeChanges(Consent.VERSION - 1).isEmpty());
        assertTrue(BundledNotes.whatsNew(version).isEmpty());
    }

    @Test public void whatChangedIsForSomeoneWhoAcceptedAnOlderNoticeOnly() {
        List<String> latest = BundledNotes.noticeChanges(Consent.VERSION - 1, true);
        assertFalse(latest.isEmpty());
        assertTrue("a new install reads the whole notice", BundledNotes.noticeChanges(0, true).isEmpty());
        assertTrue("the current notice accepted: nothing", BundledNotes.noticeChanges(Consent.VERSION, true).isEmpty());
        List<String> older = BundledNotes.noticeChanges(1, true);
        assertTrue("every later notice's lines, oldest first", older.containsAll(latest));
        assertEquals(latest, older.subList(older.size() - latest.size(), older.size()));
        assertEquals(Arrays.asList(), BundledNotes.whatsNew("0.0.1", true));
    }

    /**
     * The 0.5.0 words are written: two or three lines for whoever accepted notice 13 (the last one published), two or
     * three for the 0.5.0 card, the headline changes in each, and that card is the one this build (0.5.0, code 80)
     * shows; 0.4.73, which never shipped (the build carried its name until the release took 0.5.0's), has no entry and
     * no card. Whoever read notice 13 was told reports went to a private repository: the box corrects it (it is
     * public). The card says what the code does, never an outcome no phone has shown (Dasher kept responsive, fewer
     * missed offers), and only the maps it recognizes are skipped.
     */
    @Test public void theWordsFor050AreWrittenAnd0473ShowsNothing() {
        assertTrue(BundledNotes.WRITTEN);
        List<String> changed = BundledNotes.noticeChanges(13);
        assertTrue(changed.toString(), changed.size() >= 2 && changed.size() <= 3);
        assertEquals(changed, BundledNotes.noticeChanges(Consent.VERSION - 1));
        String notice = String.join(" ", changed);
        for (String headline : new String[] {"Feedback needs no account", "GitHub is gone",
                "public GitHub repository, not a private one", "Autopilot", "up or down", "acceptance rate",
                "7 days", "screen won't time out", "Peek", "Dash diagnostics are opt-in", "Ohio"}) {
            assertTrue(headline, notice.contains(headline));
        }
        List<String> news = BundledNotes.whatsNew("0.5.0");
        assertTrue(news.toString(), news.size() >= 2 && news.size() <= 3);
        String card = String.join(" ", news);
        for (String headline : new String[] {"Autopilot", "acceptance rate 70%, 50%, or pay first", "Peek",
                "while locked", "screen won't time out", "recognized maps are skipped", "Send anonymous feedback"}) {
            assertTrue(headline, card.contains(headline));
        }
        for (String outcome : new String[] {"stays responsive", "Fewer missed", "aren't read", "catches offers"}) {
            assertFalse(outcome, card.contains(outcome) || notice.contains(outcome));
        }
        assertEquals("this build is 0.5.0: its card is the one written for it", news,
                BundledNotes.whatsNew(Updater.version(RuntimeEnvironment.getApplication())));
        assertFalse("0.4.73 never shipped and nothing builds as it: no entry", BundledNotes.hasRelease("0.4.73"));
        assertTrue(BundledNotes.whatsNew("0.4.73").isEmpty());
        for (String line : BundledNotes.allLines()) {
            assertFalse("the app's name only through AppName: " + line, line.contains("Offer Filter"));
            assertFalse(line, line.contains("0.4.73"));
        }
    }
}
