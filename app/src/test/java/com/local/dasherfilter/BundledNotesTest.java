package com.local.dasherfilter;

import static org.junit.Assert.*;

import java.util.ArrayList;
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
     * The 0.5.0 words are written: two or three lines for whoever accepted notice 13 (the last one before 0.5.0), two
     * or three for the 0.5.0 card, the headline changes in each; 0.4.73, which never shipped (the build carried its
     * name until the release took 0.5.0's), has no entry and no card. Whoever read notice 13 was told reports went to a
     * private repository: the box corrects it (it is public). The card says what the code does, never an outcome no
     * phone has shown (Dasher kept responsive, fewer missed offers), and only the maps it recognizes are skipped.
     */
    @Test public void theWordsFor050AreWrittenAnd0473ShowsNothing() {
        assertTrue(BundledNotes.WRITTEN);
        List<String> fourteen = notice(14);
        assertTrue(fourteen.toString(), fourteen.size() >= 2 && fourteen.size() <= 3);
        String notice = String.join(" ", fourteen);
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
        assertFalse("0.4.73 never shipped and nothing builds as it: no entry", BundledNotes.hasRelease("0.4.73"));
        assertTrue(BundledNotes.whatsNew("0.4.73").isEmpty());
        for (String line : BundledNotes.allLines()) {
            assertFalse("the app's name only through AppName: " + line, line.contains("Offer Filter"));
            assertFalse(line, line.contains("0.4.73"));
        }
    }

    /**
     * The 0.5.1 words are written (the owner's decision of 7 October 2026, "Your minimums grow"): two or three lines for
     * whoever accepted notice 14 (0.5.0), naming the growth, its limit, Undo, the declines it can mean and its switch;
     * two or three for the 0.5.1 card, the one this build (0.5.1, code 81) shows, saying what the code does (103% for
     * 30 offers over 2 days, at most 10%, Undo, all minimums together, max stops never) and never an outcome. A reader
     * of notice 13 sees 14's lines, then 15's.
     */
    @Test public void theWordsFor051AreWritten() {
        assertEquals(15, Consent.VERSION);
        List<String> fifteen = BundledNotes.noticeChanges(14);
        assertEquals(fifteen, BundledNotes.noticeChanges(Consent.VERSION - 1));
        assertTrue(fifteen.toString(), fifteen.size() >= 2 && fifteen.size() <= 3);
        String notice = String.join(" ", fifteen);
        for (String headline : new String[] {"raises your minimums", "paid above them for a while",
                "at most " + Growth.MOST_PERCENT + "% at a time", "Undo", "more declines", AutopilotText.GROW_SWITCH,
                "last growth"}) {
            assertTrue(headline, notice.contains(headline));
        }
        List<String> both = new ArrayList<>(notice(14));
        both.addAll(fifteen);
        assertEquals("a reader of notice 13: 14's lines, then 15's", both, BundledNotes.noticeChanges(13));

        List<String> news = BundledNotes.whatsNew("0.5.1");
        assertTrue(news.toString(), news.size() >= 2 && news.size() <= 3);
        String card = String.join(" ", news);
        for (String headline : new String[] {"Your minimums grow", Growth.LEAST_BAR + "% or more",
                Growth.OFFERS + " offers over " + Growth.LEAST_DAYS + " days",
                "at most " + Growth.MOST_PERCENT + "% at a time", "Undo", "All the minimums you set grow together",
                "max stops never changes", "lower its bar to protect your acceptance rate", "Turn growth off"}) {
            assertTrue(headline, card.contains(headline));
        }
        for (String outcome : new String[] {"earn more", "more pay", "you'll make", "guarantee", "better offers"}) {
            assertFalse(outcome, card.toLowerCase(java.util.Locale.US).contains(outcome)
                    || notice.toLowerCase(java.util.Locale.US).contains(outcome));
        }
    }

    /** 0.5.2 (the owner's report of 7 October 2026): what its fixes do, in plain words; the notice stays at 15. */
    @Test public void theWordsFor052AreWritten() {
        assertEquals("nothing new is read, kept or sent: no new notice", 15, Consent.VERSION);
        List<String> news = BundledNotes.whatsNew("0.5.2");
        assertTrue(news.toString(), news.size() >= 2 && news.size() <= 3);
        String card = String.join(" ", news);
        for (String headline : new String[] {"never shows", "Dasher's own notification", "5 s", "split screen",
                "no longer counts as your touch", "holds back Peek", "read sooner", "directions keep talking"}) {
            assertTrue(headline, card.contains(headline));
        }
        for (String outcome : new String[] {"never miss", "every offer", "guarantee", "always"}) {
            assertFalse(outcome, card.toLowerCase(java.util.Locale.US).contains(outcome));
        }
    }

    /** 0.5.3 (the owner, 8 October 2026): Directions during a delivery, and the one-tap split. */
    @Test public void theWordsFor053AreWritten() {
        assertEquals("nothing new is read, kept or sent: no new notice", 15, Consent.VERSION);
        List<String> news = BundledNotes.whatsNew("0.5.3");
        assertTrue(news.toString(), news.size() >= 2 && news.size() <= 3);
        String card = String.join(" ", news);
        for (String headline : new String[] {"Directions work during a delivery", "re-post", "your map",
                "one tap", "recent apps"}) {
            assertTrue(headline, card.contains(headline));
        }
    }

    /** 0.5.4 (the owner, 8 October 2026): Dasher's navigation hidden under a new start screen. */
    @Test public void theWordsFor054AreWritten() {
        assertEquals(15, Consent.VERSION);
        List<String> news = BundledNotes.whatsNew("0.5.4");
        assertTrue(news.toString(), news.size() >= 2 && news.size() <= 3);
        String card = String.join(" ", news);
        for (String headline : new String[] {"as its own icon opens it", "navigation", "Searching for offers",
                "split button", "Peek"}) {
            assertTrue(headline, card.contains(headline));
        }
    }

    /** 0.5.5 (the owner, 7 October 2026): one fluid homepage. */
    @Test public void theWordsFor055AreWritten() {
        assertEquals(15, Consent.VERSION);
        List<String> news = BundledNotes.whatsNew("0.5.5");
        assertTrue(news.toString(), news.size() >= 2 && news.size() <= 3);
        String card = String.join(" ", news);
        for (String headline : new String[] {"every window size", "radar", "offer map", "gradually",
                "verdict", "at the top"}) {
            assertTrue(headline, card.contains(headline));
        }
        assertEquals("this build is 0.5.5: its card is the one written for it", news,
                BundledNotes.whatsNew(Updater.version(RuntimeEnvironment.getApplication())));
    }

    /** The lines notice {@code version} added, as a reader of the one before it sees them. */
    private static List<String> notice(int version) {
        List<String> since = new ArrayList<>(BundledNotes.noticeChanges(version - 1, true));
        List<String> after = BundledNotes.noticeChanges(version, true);
        return new ArrayList<>(since.subList(0, since.size() - after.size()));
    }
}
