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
        assertTrue("historical notice 15 is preserved", BundledNotes.hasNotice(15));
        List<String> fifteen = notice(15);
        assertTrue(fifteen.toString(), fifteen.size() >= 2 && fifteen.size() <= 3);
        String notice = String.join(" ", fifteen);
        for (String headline : new String[] {"raises your minimums", "paid above them for a while",
                "at most " + Growth.MOST_PERCENT + "% at a time", "Undo", "more declines", AutopilotText.GROW_SWITCH,
                "last growth"}) {
            assertTrue(headline, notice.contains(headline));
        }
        List<String> both = new ArrayList<>(notice(14));
        both.addAll(fifteen);
        both.addAll(notice(16));
        both.addAll(notice(17));
        both.addAll(notice(18));
        both.addAll(notice(19));
        both.addAll(notice(20));
        both.addAll(notice(21));
        both.addAll(notice(22));
        both.addAll(notice(23));
        both.addAll(notice(24));
        both.addAll(notice(25));
        assertEquals("a reader of notice 13: all later notices in order", both, BundledNotes.noticeChanges(13));

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
        assertTrue("these release notes retain historical notice 15", BundledNotes.hasNotice(15));
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
        assertTrue("these release notes retain historical notice 15", BundledNotes.hasNotice(15));
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
        assertTrue("historical notice 15 is preserved", BundledNotes.hasNotice(15));
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
        assertTrue("historical notice 15 is preserved", BundledNotes.hasNotice(15));
        List<String> news = BundledNotes.whatsNew("0.5.5");
        assertTrue(news.toString(), news.size() >= 2 && news.size() <= 3);
        String card = String.join(" ", news);
        for (String headline : new String[] {"every window size", "radar", "offer map", "gradually",
                "verdict", "at the top"}) {
            assertTrue(headline, card.contains(headline));
        }
    }

    /** 0.5.6: installed navigation apps and the replacement anonymous-feedback service. */
    @Test public void theWordsFor056AreWritten() {
        List<String> news = BundledNotes.whatsNew("0.5.6");
        assertTrue(news.toString(), news.size() >= 2 && news.size() <= 3);
        String card = String.join(" ", news);
        for (String headline : new String[] {"Google Maps", "Waze", "browser fallback",
                "replacement feedback service", "No account is needed"}) {
            assertTrue(headline, card.contains(headline));
        }
    }

    /** 0.5.7 narrows diagnostic text without adding a data flow or a new notice. */
    @Test public void theWordsFor057AreWritten() {
        assertTrue("historical notice 15 is preserved", BundledNotes.hasNotice(15));
        List<String> news = BundledNotes.whatsNew("0.5.7");
        assertTrue(news.toString(), news.size() >= 2 && news.size() <= 3);
        String card = String.join(" ", news);
        for (String headline : new String[] {"combined street and route labels", "unquoted delivery instructions",
                "Pay, miles, minutes and stop counts", "Review diagnostics before sharing"}) {
            assertTrue(headline, card.contains(headline));
        }
    }

    /** 0.5.8 tightens existing diagnostic masking and explains conservative loss at ambiguous boundaries. */
    @Test public void theWordsFor058AreWritten() {
        assertTrue("historical notice 15 is preserved", BundledNotes.hasNotice(15));
        List<String> news = BundledNotes.whatsNew("0.5.8");
        assertTrue(news.toString(), news.size() >= 2 && news.size() <= 3);
        String card = String.join(" ", news);
        for (String headline : new String[] {"malformed account-screen prefixes",
                "delivery-instruction continuations", "may hide extra text", "Review diagnostics before sharing"}) {
            assertTrue(headline, card.contains(headline));
        }
    }

    /** 0.5.10 checks cancellation and the time limit at download completion; the notice stays at 15. */
    @Test public void theWordsFor0510AreWritten() {
        assertTrue("these release notes retain historical notice 15", BundledNotes.hasNotice(15));
        List<String> news = BundledNotes.whatsNew("0.5.10");
        assertTrue(news.toString(), news.size() >= 2 && news.size() <= 3);
        String card = String.join(" ", news);
        for (String headline : new String[] {"recheck cancellation", "download finishes", "time limit", "rejected"}) {
            assertTrue(headline, card.contains(headline));
        }
    }

    @Test public void theWordsFor0511RemainWritten() {
        List<String> news = BundledNotes.whatsNew("0.5.11");
        assertEquals(3, news.size());
        String card = String.join(" ", news);
        for (String fact : new String[] {"pay, miles and trip time", "triangle stays steady",
                "confirmed full-screen", "Send", "own saved choice", "unsent reports", "preview"}) {
            assertTrue(fact, card.contains(fact));
        }

    }

    @Test public void prospectiveStudyHasItsOwnRenewedNoticeAndPreservesOldReleaseNotes() {
        assertTrue(BundledNotes.hasNotice(16));
        List<String> changed = notice(16);
        assertEquals(3, changed.size());
        String words = String.join(" ", changed);
        for (String fact : new String[] {"before outcomes", "24 hours", "checked draft", "tap Save choice",
                "opt-outs", "automatic-sharing choices", "unsent reports", "Preview"}) assertTrue(fact, words.contains(fact));
    }

    @Test public void restorationHasItsOwnNoticeAndCurrentReleaseWithoutImplyingStudyOptIn() {
        String release = String.join(" ", BundledNotes.whatsNew("0.6.0"));
        for (String fact : new String[] {"Settings → Mode", "Strict or Area", "separate Learned", "confirmed manual acceptances",
                "Teal shows learned bests", "purple shows Autopilot" ,
                "undo adoption", "0.5.11 repairs", "not accepted"}) assertTrue(fact, release.contains(fact));
        String notice = String.join(" ", notice(17));
        for (String fact : new String[] {"initially off", "Strict remains", "Clear history", "saved minimums stay",
                "timing study", "neither learning nor sharing"}) assertTrue(fact, notice.contains(fact));
    }

    /**
     * 0.6.1 (the owner, 11 and 12 October 2026): learning on by default, and new highs raise the learned minimums,
     * never the user's own, with Adopt.
     */
    @Test public void learningOnByDefaultHasItsOwnNoticeAndRelease() {
        String notice = String.join(" ", notice(18));
        for (String fact : new String[] {"on by default", "beats your best pay", "raises the learned minimums",
                "more declines", "stay as you set them", "Adopt", "Learn button"}) {
            assertTrue(fact, notice.contains(fact));
        }
        assertFalse(notice.contains("Undo"));
        String release = String.join(" ", BundledNotes.whatsNew("0.6.1"));
        for (String fact : new String[] {"raises the learned minimums", "Adopt", "stay as you set them",
                "new dash's first pickup", "Moving a knob", "take back", "on by default", "Learn button",
                "long press", "Autopilot is the chip", "recent apps"}) {
            assertTrue(fact, release.contains(fact));
        }
        for (String outcome : new String[] {"earn more", "more pay", "you'll make", "guarantee", "better offers"}) {
            assertFalse(outcome, (release + notice).toLowerCase(java.util.Locale.US).contains(outcome));
        }
    }

    /**
     * 0.6.2 (the owner, 12 October 2026): Autopilot on by default, turned on once for installs updating, and the offer
     * clock. Its notice carries the clock too, since a reader of notice 18 gets no What's-new card with it.
     */
    @Test public void autopilotOnByDefaultHasItsOwnNoticeAndRelease() {
        assertTrue("historical notice 19 is preserved", BundledNotes.hasNotice(19));
        String notice = String.join(" ", notice(19));
        for (String fact : new String[] {"Autopilot is on by default", "turns it on once", "the goal you chose before",
                "70% acceptance rate", "turns it off", FilterStore.BAR_MIN + "% and " + FilterStore.BAR_MAX + "%",
                "declines offers that meet them", "yours to decide", "at most " + Growth.MOST_PERCENT + "% at a time",
                "Undo", "by time of day", "as a percent of your minimums now"}) {
            assertTrue(fact, notice.contains(fact));
        }
        String release = String.join(" ", BundledNotes.whatsNew("0.6.2"));
        for (String fact : new String[] {"Autopilot is on by default", "turns it on once", "chip at the top",
                OfferClock.TITLE, "middle score", "three hours", "as a percent of your minimums now",
                "history, not a promise"}) {
            assertTrue(fact, release.contains(fact));
        }
        for (String outcome : new String[] {"earn more", "more pay", "you'll make", "guarantee", "better offers",
                "busiest", "best time"}) {
            assertFalse(outcome, (release + notice).toLowerCase(java.util.Locale.US).contains(outcome));
        }
    }

    /**
     * 0.6.3 (the owner, 12 October 2026: "i want to keep track of if anyone besides me downloads the app"): the
     * anonymous daily count, on by default, in a reader of notice 19's What changed box: what it sends, the daily
     * totals the service keeps and for how long (the notice's own point below the box says what else it keeps
     * briefly, never "only daily totals"), and its switch in Settings by the switch's own words. That reader gets no
     * What's-new card with it, so the box names the release's other homepage and split changes too.
     */
    @Test public void theDailyCountHasItsOwnNotice() {
        assertTrue(Consent.VERSION >= 20);
        List<String> twenty = notice(20);
        assertTrue(twenty.toString(), twenty.size() >= 2 && twenty.size() <= 3);
        String notice = String.join(" ", twenty);
        for (String fact : new String[] {"Once a day", "one anonymous count", "how many people use it",
                "its version and whether this install was counted before",
                "Nothing about your offers, phone or account goes with it",
                "the service keeps daily totals for 400 days",
                "Settings → " + DailyCount.SWITCH + " turns it off", "Strict or Area", "Split"}) {
            assertTrue(fact, notice.contains(fact));
        }
        List<String> since19 = new ArrayList<>(twenty);
        since19.addAll(notice(21));
        since19.addAll(notice(22));
        since19.addAll(notice(23));
        since19.addAll(notice(24));
        since19.addAll(notice(25));
        assertEquals("a reader of notice 19 sees 20's lines, then 21's, 22's, 23's, 24's and 25's", since19,
                BundledNotes.noticeChanges(19, true));
        for (String wrong : new String[] {"identifier", "track", "location", "earn more", "guarantee",
                "only daily totals"}) {
            assertFalse(wrong, notice.toLowerCase(java.util.Locale.US).contains(wrong));
        }
    }

    /**
     * 0.6.3 (the owner, 12 October 2026): the latest offer as a label and every picture kept in a small split window
     * ("that sentence needs to be removed at all sizes"), Split opening Dasher first, the scoring mode on the homepage
     * ("the mode button being on the homepage"), a peek's take-back teaching, the coloured tab beside this app and the
     * daily count's switch, each said once, with no promise of better offers.
     */
    @Test public void release063SaysWhatChanged() {
        List<String> lines = BundledNotes.whatsNew("0.6.3");
        assertEquals(lines.toString(), 3, lines.size());
        String release = String.join(" ", lines);
        for (String fact : new String[] {"The latest offer is a label", "radar, skyline, offer map and counts",
                "Split opens Dasher full screen first", "Strict or Area is a chip", "no Mode row",
                "take-back from a peek's decline teaches", "tab shows beside this app",
                "daily anonymous count has a Settings switch"}) {
            assertTrue(fact, release.contains(fact));
        }
        for (String wrong : new String[] {"earn more", "more pay", "you'll make", "guarantee", "better offers",
                "track", "identifier", "location"}) {
            assertFalse(wrong, release.toLowerCase(java.util.Locale.US).contains(wrong));
        }
    }

    /**
     * 0.6.4 (the owner, 12 October 2026: "split screen button didnt work"): Split fixed by the split pane, recent apps at
     * once where a phone won't split, and the 11:06 stop's notice and listener reconnect, with no promise of offers.
     */
    @Test public void release064SaysWhatChanged() {
        List<String> lines = BundledNotes.whatsNew("0.6.4");
        assertEquals(lines.toString(), 2, lines.size());
        String release = String.join(" ", lines);
        for (String fact : new String[] {"Split works again", "small pane beside the screen", "opens Dasher in its half",
                "recent apps open at once", "stops this app reading Dasher mid-dash", "one notification says so",
                "notification access reconnects itself"}) {
            assertTrue(fact, release.contains(fact));
        }
        for (String wrong : new String[] {"earn more", "more pay", "you'll make", "guarantee", "better offers",
                "track", "identifier", "location"}) {
            assertFalse(wrong, release.toLowerCase(java.util.Locale.US).contains(wrong));
        }
    }

    /**
     * 0.6.14 (the owner, 14 October 2026: "i don't want the pay per stop to not count the pickup"): pay per stop counts
     * every stop, the pickup included ($5 a stop: $10 a single order, $15 three stops); the number unchanged.
     */
    @Test public void release0614SaysWhatChanged() {
        List<String> lines = BundledNotes.whatsNew("0.6.14");
        assertEquals(lines.toString(), 5, lines.size());
        assertEquals("the card says what notice 24 says, then the skyline", notice(24), lines.subList(0, 4));
        assertTrue(lines.get(4), lines.get(4).contains("buildings and trees are back"));
        String release = String.join(" ", lines);
        for (String fact : new String[] {"Pay per stop", "every stop", "the pickup included",
                "$5 a stop asks $10 of a single order and $15 of three stops", "wasn't changed",
                "a single order now asks twice what it did", "set it to what you want per stop", "best pay per stop"}) {
            assertTrue(fact, release.contains(fact));
        }
        for (String wrong : new String[] {"earn more", "more pay", "you'll make", "guarantee", "after the first pickup",
                "halved"}) {
            assertFalse(wrong, release.toLowerCase(java.util.Locale.US).contains(wrong));
        }
    }

    /**
     * 0.6.15 (the owner, 14 October 2026, of the download count's blind spots: "let's fix em"): downloads are counted at
     * the website's download link; nothing in the app changes, so no notice.
     */
    @Test public void release0615SaysWhatChanged() {
        List<String> lines = BundledNotes.whatsNew("0.6.15");
        assertEquals(lines.toString(), 1, lines.size());
        assertTrue(lines.get(0), lines.get(0).contains("counted at the download link"));
        assertTrue(lines.get(0), lines.get(0).length() <= 160);
    }

    /**
     * 0.6.16 (the owner, 14 October 2026: "let's also upgrade the app's 2d graphics to our 3d art, but we must not
     * degrade performance, only enhance it"): the homepage's pictures are the website's 3D art; nothing is read, kept or
     * sent differently, so no notice.
     */
    @Test public void release0616SaysWhatChanged() {
        List<String> lines = BundledNotes.whatsNew("0.6.16");
        assertEquals(lines.toString(), 1, lines.size());
        assertTrue(lines.get(0), lines.get(0).contains("3D art"));
        assertTrue(lines.get(0), lines.get(0).length() <= 160);
    }

    /**
     * 0.6.17 (the owner, 14 October 2026: "can we ascertain whether we are breaking terms or not?", then "Say it
     * plainly"): the account warning says what DoorDash's terms ban; the card is notice 25's lines.
     */
    @Test public void theCurrentReleaseSaysWhatChanged() {
        assertEquals("0.6.17", Updater.version(RuntimeEnvironment.getApplication()));
        assertEquals(25, Consent.VERSION);
        List<String> lines = BundledNotes.whatsNew("0.6.17");
        assertEquals("the card says what notice 25 says", notice(25), lines);
    }

    /** 0.6.17's notice (25): DoorDash's terms ban tools like this one; nothing about how it works changed. */
    @Test public void notice25SaysWhatDoorDashsTermsBan() {
        List<String> lines = BundledNotes.noticeChanges(24);
        assertEquals(notice(25), lines);
        assertEquals(lines.toString(), 2, lines.size());
        String notice = String.join(" ", lines);
        for (String fact : new String[] {"DoorDash's terms ban automated tools that read or collect data from its "
                + "apps", "this app is one", "limit or deactivate your account",
                "Nothing about how the app works has changed"}) {
            assertTrue(fact, notice.contains(fact));
        }
        for (String line : lines) {
            assertTrue(line, line.length() <= 160);
            assertFalse(line, line.contains("Offer Filter") || line.contains("may break"));
        }
    }

    /** 0.6.13: a customer's name alone on a pickup screen is masked in reports; nothing else changes. */
    @Test public void release0613SaysWhatChanged() {
        List<String> lines = BundledNotes.whatsNew("0.6.13");
        assertEquals(lines.toString(), 1, lines.size());
        assertTrue(lines.get(0), lines.get(0).contains("masked in reports"));
    }

    /** 0.6.14's notice (24): every stop counts, the pickup included, and the number was not changed. */
    @Test public void notice24SaysThePickupCounts() {
        List<String> lines = notice(24);
        assertEquals(lines.toString(), 4, lines.size());
        assertTrue(lines.get(3), lines.get(3).contains("taps Dasher's ⚙ once"));
        String notice = String.join(" ", lines);
        for (String fact : new String[] {"Pay per stop", "every stop Dasher shows, the pickup included",
                "$5 a stop asks $10 of a single order and $15 of three stops", "Your number wasn't changed",
                "best pay per stop"}) {
            assertTrue(fact, notice.contains(fact));
        }
        for (String line : lines) {
            assertTrue(line, line.length() <= 160);
            assertFalse(line, line.contains("Offer Filter"));
        }
    }

    /**
     * 0.6.12 (the owner, 13 October 2026: "swap overall payout for pay per stop"): pay per stop, the number unchanged, the
     * learned best per stop; never a promise of more pay. Its rule C counting (each stop but the pickup) was superseded
     * by 0.6.14, so the card no longer describes it.
     */
    @Test public void release0612SaysWhatChanged() {
        List<String> lines = BundledNotes.whatsNew("0.6.12");
        assertEquals(lines.toString(), 4, lines.size());
        String release = String.join(" ", lines);
        for (String fact : new String[] {"pay per stop", "more stops must pay more", "gaps on the homepage",
                "No second card of ours beside Dasher's own",
                "time-of-day chart comes back", "didn't change", "best pay per stop"}) {
            assertTrue(fact, release.contains(fact));
        }
        for (String wrong : new String[] {"earn more", "more pay", "you'll make", "guarantee", "after the first pickup",
                "exactly as before"}) {
            assertFalse(wrong, release.toLowerCase(java.util.Locale.US).contains(wrong));
        }
    }

    /** 0.6.12's notice (23): the pay minimum per stop, the number unchanged, learning's best; no rule C counting. */
    @Test public void notice23SaysThePayMinimumIsPerStop() {
        assertTrue(Consent.VERSION >= 23);
        List<String> lines = notice(23);
        String notice = String.join(" ", lines);
        for (String fact : new String[] {"pay per stop", "more stops must pay more", "your number didn't change",
                "best pay per stop"}) {
            assertTrue(fact, notice.contains(fact));
        }
        assertFalse(notice, notice.contains("after the first pickup"));
        assertFalse(notice, notice.contains("exactly as before"));
    }

    /**
     * 0.6.11 (the owner, 13 October 2026: "if an accepted offer's score beats the autopilot ask, the ask needs to jump
     * to the new higher score"; "when either app is in a maps mode for nav the other when in split needs to resize";
     * "the acceptance rating bar width is causing a line break"): each said as what a user sees.
     */
    @Test public void release0611SaysWhatChanged() {
        List<String> lines = BundledNotes.whatsNew("0.6.11");
        assertEquals(lines.toString(), 3, lines.size());
        String release = String.join(" ", lines);
        for (String fact : new String[] {"follows your accepts", "rises to that score", "rest of the dash",
                "split screen", "more room", "one line"}) {
            assertTrue(fact, release.contains(fact));
        }
        for (String wrong : new String[] {"earn more", "more pay", "you'll make", "guarantee", "20%"}) {
            assertFalse(wrong, release.toLowerCase(java.util.Locale.US).contains(wrong));
        }
    }

    /**
     * 0.6.11's notice (22): an offer the user accepts holds Autopilot's bar for the dash, past the goal, and what ends
     * it; the divider moved during a delivery, put back, and left alone once the user moves it.
     */
    @Test public void notice22SaysWhatHoldsTheBarAndWhatMovesTheDivider() {
        assertTrue(Consent.VERSION >= 22);
        List<String> lines = notice(22);
        assertEquals(lines.toString(), 3, lines.size());
        String notice = String.join(" ", lines);
        for (String fact : new String[] {"accept an offer yourself", "rises to that score", "rest of the dash",
                "past what your acceptance goal allows", "next dash", "change your goal or minimums", "split screen",
                "during a delivery", "moves the divider", "puts it back", "Move it yourself", "stops for that dash"}) {
            assertTrue(fact, notice.contains(fact));
        }
    }

    /**
     * 0.6.10 (the owner, 13 October 2026: "yeah I want all its recommendations"): the skip list and the cost per mile,
     * said as what a user does, never as a suggested rate or a promise of more pay.
     */
    @Test public void release0610SaysWhatChanged() {
        List<String> lines = BundledNotes.whatsNew("0.6.10");
        assertEquals(lines.toString(), 2, lines.size());
        String release = String.join(" ", lines);
        for (String fact : new String[] {"Skip a store", "from now on", "Skipped stores", "cost per mile",
                "off unless you set it", "Autopilot's details"}) {
            assertTrue(fact, release.contains(fact));
        }
        for (String wrong : new String[] {"irs", "72.5", "earn more", "more pay", "you'll make", "guarantee"}) {
            assertFalse(wrong, release.toLowerCase(java.util.Locale.US).contains(wrong));
        }
    }

    /**
     * 0.6.10's notice (21): the skip list as a reason the app declines, and the cost per mile as one of Autopilot's
     * inputs, off by default and never a suggested rate.
     */
    @Test public void notice21SaysWhatNowDeclinesAndWhatAutopilotWeighs() {
        assertTrue(Consent.VERSION >= 21);
        String notice = String.join(" ", BundledNotes.noticeChanges(20));
        for (String fact : new String[] {"skip list", "whatever they pay", "Skipped stores", "two stores",
                "can't be read", "cost per mile", "off unless you set it", "Autopilot's details"}) {
            assertTrue(fact, notice.contains(fact));
        }
        assertFalse(notice.contains("IRS"));
    }

    /** 0.6.9: the came-back note, said as what the tickets show, never as a promise of the same order. */
    @Test public void release069SaysWhatChanged() {
        List<String> lines = BundledNotes.whatsNew("0.6.9");
        assertEquals(lines.toString(), 2, lines.size());
        String release = String.join(" ", lines);
        for (String fact : new String[] {"same store", "last half hour", "both tickets say so", "how much later",
                "readable over the offer's outline", "which end is latest"}) {
            assertTrue(fact, release.contains(fact));
        }
        for (String wrong : new String[] {"same order", "earn more", "more pay", "you'll make", "guarantee",
                "better offers", "skip"}) {
            assertFalse(wrong, release.toLowerCase(java.util.Locale.US).contains(wrong));
        }
    }

    /**
     * 0.6.8 (the owner, 13 October 2026: "calmer homepage update didnt look like mockup you did"): the homepage as the
     * mockup drew it, said as what a user sees.
     */
    @Test public void release068SaysWhatChanged() {
        List<String> lines = BundledNotes.whatsNew("0.6.8");
        assertEquals(lines.toString(), 2, lines.size());
        String release = String.join(" ", lines);
        for (String fact : new String[] {"looks like the mockup", "side by side over a wide map",
                "buttons beside the latest offer", "pay is bigger", "even with Autopilot off", "by its knob"}) {
            assertTrue(fact, release.contains(fact));
        }
        for (String wrong : new String[] {"earn more", "more pay", "you'll make", "guarantee", "better offers"}) {
            assertFalse(wrong, release.toLowerCase(java.util.Locale.US).contains(wrong));
        }
    }

    /**
     * 0.6.7 (the owner, 13 October 2026, of 0.6.4's split pane: "it's still worse than before we tried to fix split"):
     * Split back to 0.6.2's one start of Dasher beside this app.
     */
    @Test public void release067SaysWhatChanged() {
        List<String> lines = BundledNotes.whatsNew("0.6.7");
        assertEquals(lines.toString(), 2, lines.size());
        String release = String.join(" ", lines);
        for (String fact : new String[] {"how 0.6.2 did it", "opens Dasher beside this app", "no small pane",
                "recent apps open"}) {
            assertTrue(fact, release.contains(fact));
        }
        for (String wrong : new String[] {"earn more", "more pay", "you'll make", "guarantee", "better offers"}) {
            assertFalse(wrong, release.toLowerCase(java.util.Locale.US).contains(wrong));
        }
    }

    /**
     * 0.6.6 (the owner, 12 October 2026: "that home screen is so busy … consolidate the number of ways we express
     * information"): the calmer homepage, said as what moved where.
     */
    @Test public void release066SaysWhatChanged() {
        List<String> lines = BundledNotes.whatsNew("0.6.6");
        assertEquals(lines.toString(), 2, lines.size());
        String release = String.join(" ", lines);
        for (String fact : new String[] {"each thing once", "one bar", "% of your minimums", "at its knob",
                "the map names"}) {
            assertTrue(fact, release.contains(fact));
        }
        for (String wrong : new String[] {"earn more", "more pay", "you'll make", "guarantee", "better offers"}) {
            assertFalse(wrong, release.toLowerCase(java.util.Locale.US).contains(wrong));
        }
    }

    /**
     * 0.6.5 (the owner, 13 October 2026, on #DeclineNow's recommendations: "yeah I want all its recommendations"): the
     * Earn by Time guard, said as what it does, with no promise about the dash or pay.
     */
    @Test public void release065SaysWhatChanged() {
        List<String> lines = BundledNotes.whatsNew("0.6.5");
        assertEquals(lines.toString(), 2, lines.size());
        String release = String.join(" ", lines);
        for (String fact : new String[] {"Earn by Time", "another decline would end your dash", "left to you",
                "auto-decline pauses until you resume it", "ends the dash right after a decline"}) {
            assertTrue(fact, release.contains(fact));
        }
        for (String wrong : new String[] {"earn more", "more pay", "you'll make", "guarantee", "better offers",
                "never ends", "protects your dash"}) {
            assertFalse(wrong, release.toLowerCase(java.util.Locale.US).contains(wrong));
        }
    }

    /** The lines notice {@code version} added, as a reader of the one before it sees them. */
    private static List<String> notice(int version) {
        List<String> since = new ArrayList<>(BundledNotes.noticeChanges(version - 1, true));
        List<String> after = BundledNotes.noticeChanges(version, true);
        return new ArrayList<>(since.subList(0, since.size() - after.size()));
    }
}
