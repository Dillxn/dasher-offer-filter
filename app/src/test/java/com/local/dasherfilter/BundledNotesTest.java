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
}
