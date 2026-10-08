package com.local.dasherfilter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The texts the app shows are the repository's TERMS.md, PRIVACY.md and LICENSE, word for word. */
public final class LegalTextsTest {
    /**
     * Notes written for the author while a text is drafted, as the website's legal build refuses them
     * (offer-filter-site tools/build-legal.py, DRAFT_MARKERS): the published texts carry an effective date instead.
     */
    private static final Pattern DRAFT_MARKERS = Pattern.compile("have a lawyer|not legal advice|no attorney review"
            + "|draft of \\d|not yet configured|to add before public release|\\b(?:TODO|TBD|FIXME)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final String CONTACT = "privacy@offerfilter.org";
    private static final String SOURCE = "<https://github.com/Dillxn/dasher-offer-filter>";
    /**
     * The beta texts' effective date, the one place the tests name it (ConsentGateTest reads it here). A release that
     * changes them sets it to the day the texts are published: in TERMS.md's and PRIVACY.md's second line and here,
     * then runs tools/legal_texts.py. 0.5.1's texts are dated the same day as 0.5.0's; 0.5.2's the day after.
     */
    static final String EFFECTIVE = "8 October 2026";
    /** The version the beta texts are for, in their second line. */
    static final String FOR_VERSION = "0.5.2";

    @Test public void theBundledTextsAreTheRepositorysFilesWordForWord() throws IOException {
        for (LegalTexts.Doc doc : LegalTexts.Doc.values()) {
            assertEquals(doc.file + " and the app's copy differ: run tools/legal_texts.py", read(doc.file),
                    doc.text());
        }
    }

    @Test public void theTermsAndPrivacyArePublishedBetaTextsAndTheLicenseIsStandardMIT() {
        String terms = LegalTexts.Doc.TERMS.text();
        String privacy = LegalTexts.Doc.PRIVACY.text();
        assertTrue(terms, terms.startsWith("# " + AppName.NAME + " terms of use\n\nBeta terms, effective " + EFFECTIVE
                + " · for " + AppName.NAME + " " + FOR_VERSION + "\n\n"));
        assertTrue(privacy, privacy.startsWith("# " + AppName.NAME + " privacy\n\nBeta privacy policy, effective "
                + EFFECTIVE + " · for " + AppName.NAME + " " + FOR_VERSION + "\n\n"));
        assertTrue("honest that this is a beta", terms.contains(AppName.NAME + " is beta software"));
        assertTrue(privacy.contains(AppName.NAME + " is beta software"));
        for (LegalTexts.Doc doc : new LegalTexts.Doc[] {LegalTexts.Doc.TERMS, LegalTexts.Doc.PRIVACY}) {
            String text = doc.text();
            for (String line : text.split("\n")) {
                assertFalse(doc.file + " still carries a drafting note: " + line, DRAFT_MARKERS.matcher(line).find());
            }
            // Published as beta texts, never as reviewed ones: no word about a lawyer either way.
            String lower = text.toLowerCase(Locale.US);
            assertFalse(doc.file, lower.contains("lawyer") || lower.contains("attorney") || lower.contains("legal review"));
            assertFalse(doc.file, text.contains("{app}"));
            assertTrue(doc.file, text.contains(AppName.NAME));
            // The website's 0.5.0 release gate (offer-filter-site tools/test-launch-help.cjs): Autopilot is described
            // and the retired area mode's and the never-shipped 0.4.73's wording are gone.
            assertTrue(doc.file, text.contains("Autopilot"));
            assertFalse(doc.file, text.contains("compensating"));
            assertFalse(doc.file, text.contains("0.4.73"));
        }
    }

    @Test public void theTermsAndPrivacyTextSayWhatTheNoticeSays() {
        String terms = LegalTexts.Doc.TERMS.text();
        assertTrue(terms.contains("Auto-accept starts off."));
        assertTrue(terms.contains("If you separately enable it in Settings"));
        assertTrue(terms.contains("committing you to that delivery"));
        assertFalse(terms.contains("It never taps Accept."));
        assertTrue(terms.contains("By tapping I understand and accept"));
        assertTrue(terms.contains("Automatic declines may dramatically lower your DoorDash acceptance rate."));
        assertTrue(terms.contains("choose to use " + AppName.NAME + " at your own risk"));
        assertTrue(terms.contains("DoorDash could limit or deactivate your account"));
        assertTrue(terms.contains("without warranties of any kind"));
        assertTrue(terms.contains("Do not handle your phone while driving"));
        assertTrue(terms.contains("Feedback and offer reports are accountless and are sent only when you tap Send."));
        assertTrue(terms.contains("Share anonymous diagnostics after each dash (off by default)"));
        assertTrue(terms.contains("unsent submissions wait on the phone for at most 7 days"));
        assertTrue(terms.contains("the feedback service keeps them for 90 days"));
        // 0.5.0's minimums, Autopilot and auto-accept (finalSpec.privacyConsent, TERMS).
        for (String fact : new String[] {"It compares each offer with the minimums you set: pay, pay per mile and pay "
                + "per hour of Dasher's time estimate, plus an optional maximum number of stops.",
                "Autopilot is off until you turn it on.", "between 50% and 150%",
                "using your recent offers, how often they come and the acceptance rate Dasher shows when you decline",
                // OfferRule.evaluate: a bar above 100% declines what meets the minimums but not the bar.
                "Above 100% it declines offers that meet your minimums.",
                "Offers it lets through below your minimums are left to you.",
                "a complete standalone offer that meets 100% of your minimums and any higher Autopilot bar",
                "Autopilot's acceptance-rate goal is a best effort, not a promise: Autopilot cannot accept offers for "
                        + "you, and your acceptance rate rises only when you accept offers.",
                "the app makes no promise to preserve your acceptance rate",
                // Peek's recovery, the unlock catch-up and its pause; the screen hold; the update hold.
                "Peek may tap Dasher's own offer notification once", "Peek waits up to 60 seconds for you to unlock it",
                "when you unlock within 40 seconds of it", "Peek never turns off its Settings switch",
                "a Back to map button can take you back; it opens your map only when you tap it",
                "it keeps your unlocked screen from timing out, whatever app is in front",
                "It never wakes or unlocks the phone; the power button still turns the screen off. This uses more "
                        + "battery.",
                "if nothing of a dash is seen for 8 hours while Dasher is not in front and the screen is off",
                // 0.5.1: the minimums grow (Growth), with its limits, Undo and the switch.
                "Autopilot also raises your minimums once its bar has stayed at " + Growth.LEAST_BAR + "% or more for "
                        + Growth.OFFERS + " offers over at least " + Growth.LEAST_DAYS + " days",
                "all of them by the same share, at most " + Growth.MOST_PERCENT + "% at a time",
                "while its bar comes down so offers are asked about the same right after",
                "It never lowers them; you can.",
                "A note on the homepage says what grew, with Undo, until you tap it or your minimums change another "
                        + "way.",
                "Raised minimums can mean more declines.",
                "Turn this off with " + AutopilotText.GROW_SWITCH + " in Autopilot's details; while Autopilot is "
                        + "off, your minimums never grow."}) {
            assertTrue(fact, terms.contains(fact));
        }
        for (String retired : new String[] {"strict or compensating area mode", "minimums percentage",
                "passes your current rules"}) {
            assertFalse(retired, terms.contains(retired));
        }
        String privacy = LegalTexts.Doc.PRIVACY.text();
        for (String flow : new String[] {"Update checks.", "**Send anonymous feedback**, only when you tap Send",
                "**Attach masked diagnostics** on (off by default, chosen for each submission)",
                "**Report this offer**, only when you tap Send",
                "**Share anonymous diagnostics after each dash**, only if you turn it on in Settings",
                "it starts off, and no update turns it on", "Offer Filter stopped unexpectedly last time",
                "Where feedback goes.", "Share report", "Place names.", "Navigation, only when you tap it.",
                "Help, only when you tap it in Settings", "Tips.", "24-hour retention window",
                "rounded to about half a kilometre", "masked"}) {
            assertTrue(flow, privacy.contains(flow));
        }
        // GitHub is named only as what older versions did and this version deletes once.
        for (String retired : new String[] {"Connect GitHub", "GitHub account", "Reports, only if you turn them on",
                "not kept in a persistent on-phone outbox", "private GitHub repository", "private repository"}) {
            assertFalse(retired, privacy.contains(retired) || terms.contains(retired));
        }
        assertTrue(terms.contains("free and open-source software under the MIT License"));
        String license = LegalTexts.Doc.LICENSE.text();
        assertTrue(license.startsWith("MIT License\n"));
        assertTrue(license.contains("Copyright (c) 2026 Dillxn"));
        assertTrue(license.contains("Permission is hereby granted, free of charge"));
        assertFalse(license.contains("All rights reserved"));
        assertFalse(license.contains("No licence is granted"));
        assertTrue(LegalTexts.Doc.LICENSE.text().contains("WITHOUT WARRANTY OF ANY KIND"));
    }

    @Test public void privacyDistinguishesRetentionAndDeletionOfLocalAndSharedCopies() {
        String privacy = LegalTexts.Doc.PRIVACY.text();
        for (String fact : new String[] {"latest 200 decisions", "history has no age-based expiry",
                "Aggregate outcome counts are kept separately", "Older entries are pruned during app use",
                "At most 5 submissions wait, each at most 7 days; one still unsent after 7 days is discarded unsent",
                "Submissions are kept on the feedback service for 90 days, then deleted",
                "The last 10 references of accepted submissions", "It is kept for 24 hours; Clear history removes it",
                "after-dash summaries not yet sent", "stay until they are sent or 7 days pass",
                "Neither cleanup can remove copies that were already sent",
                "Uninstalling removes the app's local data, including submissions still waiting and the kept references",
                "It does not remove submissions the feedback service already received"}) {
            assertTrue(fact, privacy.contains(fact));
        }
        assertFalse(privacy.contains("Uninstalling the app removes everything it kept."));
        assertFalse(privacy.contains("Up to 30 queued reports"));
    }

    @Test public void privacyNamesExternalFlowsAndTheLimitsOfMasking() {
        String privacy = LegalTexts.Doc.PRIVACY.text();
        for (String fact : new String[] {"historical area's center coordinates", "Maps or Waze", "Gas and gas-price choices",
                "Render, Cloudflare, Supabase, Google, Anthropic and OpenAI", "no ads or analytics",
                "Masking is pattern-based and can miss unfamiliar wording",
                "notes you type with Report this offer, are not masked", "A submission does not guarantee review or a fix",
                "masked again with the current rules immediately before sending",
                "Anthropic's Claude or OpenAI's ChatGPT/Codex", "Cloudflare and Supabase necessarily receive ordinary "
                + "connection metadata", "this is not a guarantee of anonymity",
                "Never coordinates, place names, or an install or device identifier",
                "no account, name, email, IP address, user agent, install or device identifier",
                // The developer's own view of the providers' request logs, and the homepage's own place lookup.
                "The developer can see the request logs Supabase keeps for the project, which can record each "
                        + "request's IP address",
                "To name the squares on the map and the place you are in", "the phone's own approximate position "
                        + "among them", "which can include where you opened the app",
                // What an older version's history lines keep after 0.5.0 deleted the learned minimums.
                "Decisions an older version recorded keep the reasons and notes they were recorded with, which can "
                        + "name an amount its adaptive minimum had learned"}) {
            assertTrue(fact, privacy.contains(fact));
        }
        assertFalse(privacy.contains("no server that collects your data"));
    }

    /**
     * Dasher's acceptance rate (WP5) and Autopilot's state: what is read, what is kept and how long, where it goes and
     * where it never goes, and what Clear history removes; Report this offer in its dialog's own words (WP7).
     */
    @Test public void privacySaysWhatAutopilotAndTheAcceptanceRateKeepAndSend() {
        String privacy = LegalTexts.Doc.PRIVACY.text();
        for (String fact : new String[] {"## Autopilot and your acceptance rate",
                "On Dasher's decline question (\"Are you sure you want to decline this offer?\"), whether the decline is "
                        + "the app's or yours and whether Autopilot is on or off: the acceptance-rate percentage Dasher "
                        + "shows, and whether Dasher says declining that offer does not lower your acceptance rate.",
                "Only the latest acceptance-rate reading: a whole percent, when Dasher showed it, and the offer it was "
                        + "shown for as a numeric fingerprint",
                "It is kept whether Autopilot is on or off.",
                // AutopilotStore.reading deletes an old reading only when something next reads it.
                "A reading older than 7 days is never used or sent: it is deleted the next time the app looks at it "
                        + "(when you open the app, Autopilot plans or a report is built), and until then stays on the "
                        + "phone unused.",
                "no text of the question is kept with it",
                "that offer's line in the decision history is marked so, and Autopilot leaves that offer out of its "
                        + "acceptance-rate count",
                "a note of its last change (time, old and new bar, a fixed reason)",
                "a pending reason for its next change (a fixed word, such as that you turned it on)",
                "the acceptance rate it carried forward from Dasher's reading at its last checkpoint",
                "they can also hold the decline question's masked words with its percentage",
                // The offer rate Autopilot works out from the wait records rides in its plan lines.
                "Autopilot's plans with the acceptance rate and the offer rate (offers an hour) they count with",
                "Autopilot's log lines carry only the offer rate (offers an hour) it works out from them",
                "Your acceptance rate leaves the phone only in a report you share (**Share report**), in masked "
                        + "diagnostics you attach to feedback, and in **Report this offer**",
                "It is never in the summary after each dash",
                "Autopilot's bar and why it changed (never your acceptance rate)",
                "The fingerprint kept with a reading never leaves the phone.",
                "Report this offer leaves it out.",
                "Clear history removes the reading, Autopilot's working figures, its last-change note, the note of "
                        + "its last growth (and with it the Undo) and its other notes above.",
                // The rules, the history's new fields and the 0.5.0 migration.
                "Autopilot's settings: whether it is on, your acceptance goal (70%, 50% or pay first), its current bar",
                "the bar it was judged at and whether Autopilot set that bar",
                "whether Dasher said declining it does not lower your acceptance rate",
                "used for the wait estimate and by Autopilot to estimate how often offers arrive",
                "0.5.0 deletes the learned minimums and the held hand-decline record from the phone the first time it "
                        + "runs",
                // Where the reading travels.
                "your current rules and Autopilot's state, including the latest acceptance-rate reading",
                "with the latest acceptance-rate reading", "plus Autopilot's acceptance-rate reading",
                "Your rules, Autopilot's settings and the app's last status line"}) {
            assertTrue(fact, privacy.contains(fact));
        }
        assertFalse("an old reading is deleted only when next looked at",
                privacy.contains("reading older than 7 days is discarded"));
        assertTrue("Report this offer in its dialog's words", privacy.contains(reportThisOffer()));
        assertTrue(FeedbackDialogs.OFFER_REPORT_SAYS.contains(reportThisOffer()));
        assertTrue("Clear history's own words name the reading too",
                MainActivity.CLEAR_HISTORY.contains("Autopilot's acceptance-rate reading"));
        // 0.5.1: what the minimums' growth keeps, its switch and what Clear history removes of it.
        for (String fact : new String[] {"and when your current minimums took effect",
                "whether Let my minimums grow is on (it is unless you turn it off)",
                "it also raises your minimums, at most 10% at a time, once the bars it recorded in your decision "
                        + "history have stayed at 103% or more for 30 offers over at least 2 days, but not while it is "
                        + "recovering toward your acceptance goal; how much they grow comes only from those bars, never "
                        + "from what you accepted or declined",
                "Autopilot keeps a note of the last growth: when, by how much, how many offers on how many days it "
                        + "rested on, your minimums and its bar before and after, and whether its note on the "
                        + "homepage (with Undo) is still waiting",
                "each growth of your minimums and its Undo (your minimums and the bar before and after)",
                "the note of its last growth (and with it the Undo)",
                "Turn off Let my minimums grow in Autopilot's details to keep your minimums where you set them."}) {
            assertTrue(fact, privacy.contains(fact));
        }
        assertTrue("and Clear history's own words name the note",
                MainActivity.CLEAR_HISTORY.contains("its note that your minimums grew"));
        // What 0.5.0 retired is named only as deleted, never as kept.
        for (String retired : new String[] {"Reset clears the learned minimums", "learned minimums remain",
                "what the adaptive minimum learned from offers you accepted or declined (their pay and per-mile",
                "pending hand-decline observation", "final-stop-to-hotspot distance",
                "numeric hotspot distance if ever available", "Automatic accept requests do not train it",
                "training personal minimums", "Pay per item uses only"}) {
            assertFalse(retired, privacy.contains(retired));
        }
    }

    /** What each 0.5.0 path reads, keeps and sends: Peek, the screen hold, Back to map, paused reading, setup. */
    @Test public void privacyCoversThePathsThisReleaseAdds() {
        String privacy = LegalTexts.Doc.PRIVACY.text();
        for (String fact : new String[] {
                // Never starve Dasher: paused reads nothing; only the maps it recognizes are skipped (MapNodes.isMap),
                // never while a decline-error recovery or an automatic Accept is verified (pruneMaps), and whether
                // Dasher's maps are recognized is unverified on phones.
                "While auto-decline is paused, or no rule is set, it reads nothing of Dasher's screen; Android's list "
                        + "of windows alone places its tab.",
                "Map views it recognizes inside Dasher's screens are skipped, not read, except while it checks a failed "
                        + "decline or an automatic Accept, or when a map's own label shows a sign of an offer; whether "
                        + "Dasher's maps are recognized depends on how Dasher draws them.",
                // Peek: the previous app in memory only, Dasher's own notification tap once, the unlock catch-up.
                "or one that arrived while the phone was locked, right after you unlock it (within 40 seconds of its "
                        + "notification, while that notification is still up)",
                "may send Dasher's own offer-notification tap once for that offer, an intent Dasher itself made",
                "During Peek, the previous app's identifier and launcher component stay only in memory.",
                "The previous app's identity is never saved or sent",
                // The Back to map chip.
                "only to offer a Back to map button when that app is a navigation app (Google Maps, Google Maps Go or "
                        + "Waze)",
                "until it has shown for 12 seconds, the offer did not end within 90 seconds",
                "A peek held for the unlock keeps them at most 60 seconds.",
                // The post kept for the unlock has no timer of its own: it waits in memory for the next unlock.
                "in memory until the next unlock (looked at then only if its notification is still up and at most 40 "
                        + "seconds old), or until you tap one of the app's cards, a newer offer takes its place or "
                        + "screen reading stops.",
                // The touch watch: a time only, in memory.
                "When a finger lands on the screen, as a time only.",
                "It never learns a touch's position or what was touched",
                "Touch times stay in memory; the logs say only that a touch came",
                // The screen hold with any app in front.
                "the screen is kept from timing out whatever app is in front, but only while something of the dash was "
                        + "seen in the last 15 minutes or Dasher shows it",
                "the battery at 20% or less and not charging", "pausing auto-decline ends the hold",
                // After an update; the notification's ranking, and what the logs note of Dasher's channel
                // (OfferNotificationService.logChannel).
                "to reopen Offer Filter only over its own screen or the home screen",
                "whether it can pop up or sound, whether it sounded, and whether Do Not Disturb lets it through",
                "Its logs note that channel's sound, vibration and importance settings, whether the notification asks "
                        + "to fill the screen, and the names (never the values) of the notification's fields.",
                // Onboarding's reads, its kept progress, and the update hold.
                "how Offer Filter was installed", "Android's restricted-settings record for Offer Filter",
                "whether Android limits its background battery use",
                "Apart from how Android ranks and sets up Dasher's offer notifications (above) and, for reports, "
                        + "Dasher's version and how its launcher screen starts, it reads no other app's settings.",
                // What else is kept: the latest states, the last status line, the offer map's squares, the route's
                // lazy expiry, the sound to put back, the downloaded update, the after-dash summary's dash length.
                "A few latest states, in fixed words, for reports", "At most 12 are kept",
                "The app's last status line, with its date and time", "Clear history does not remove it.",
                "each with its number of offers, their total and best pay",
                "A route is never used more than three hours after it was observed; it is deleted the next time the "
                        + "app looks at it after that.",
                "the level to put back, so the sound is restored even after a crash",
                "which notice you accepted and when", "and the downloaded update itself",
                "the app's readiness, settings and last status line, the offer map's counts (never a position)",
                "how long the dash lasted (to the nearest 5 minutes) and how it ended",
                // The privacy contact's mail goes through mail services.
                "and the email services that carry a message you send to the privacy contact below",
                "Your message passes through your own email provider",
                "Setup progress on this phone", "acted on only within ten minutes",
                "so a changed clock cannot release an update held for a dash early",
                "why it waits (a dash, Android's confirmation, or an installation Android blocked)",
                "you end it in Dasher with its End dash",
                // Before the notice: three looks that read no words (updates keep working before it).
                "with three exceptions that read no words",
                "since updates are checked for and installed before the notice is accepted too",
                "or for 8 hours with nothing of a dash seen, Dasher not in front and the screen off"}) {
            assertTrue(fact, privacy.contains(fact));
        }
        for (String wrong : new String[] {"normally skipped", "or after 40 seconds", "It reads no other app's settings.",
                "for at most three hours since the route was observed"}) {
            assertFalse(wrong, privacy.contains(wrong));
        }
    }

    @Test public void theTextsNameTheirPrivateContactGoverningLawAndPublicRepository() {
        String terms = LegalTexts.Doc.TERMS.text();
        String privacy = LegalTexts.Doc.PRIVACY.text();
        assertTrue(privacy.contains("public project identity is Dillxn"));
        assertTrue(privacy.contains("posts there are public"));
        assertTrue(privacy.contains("Do not attach diagnostic reports"));
        assertTrue(privacy.contains("No account is required."));
        // The private contact, for privacy, data deletion and security only; feedback stays anonymous.
        String contact = "For privacy, data-deletion and security requests only, email " + CONTACT + ".";
        assertTrue(privacy.contains(contact));
        assertTrue(terms.contains(contact));
        assertTrue(privacy.contains("Unlike feedback, email is not anonymous"));
        assertTrue(privacy.contains("Send everything else as anonymous feedback."));
        assertTrue(privacy.contains("Use **Send anonymous feedback** in Settings"));
        // Ohio law, and every limit the law allows kept.
        assertTrue(terms.contains("## Governing law\n\nThese terms are governed by the laws of the State of Ohio, "
                + "United States."));
        assertTrue(terms.contains("To the extent the law allows"));
        assertTrue(terms.contains("These terms do not limit the rights granted by the MIT License"));
        // The project's GitHub repository is public: reports filed before 0.5.0 went there; none go now.
        assertTrue(privacy.contains("Versions before 0.5.0 could connect to GitHub and file problem reports and "
                + "diagnostics there, as issues in the project's GitHub repository (" + SOURCE + "). That repository "
                + "is public"));
        assertTrue(privacy.contains("This version sends nothing to GitHub."));
        assertTrue(terms.contains("Its source code is public, in the project's GitHub repository: " + SOURCE + "."));
        assertFalse(privacy.contains("offer-filter-site"));
        assertFalse(terms.contains("offer-filter-site"));
    }

    /** The texts' figures are the code's: a change to one of these fails here until the texts say it too. */
    @Test public void theFiguresInTheTextsAreTheCodes() {
        String terms = LegalTexts.Doc.TERMS.text();
        String privacy = LegalTexts.Doc.PRIVACY.text();
        assertEquals(7L * 24 * 60 * 60_000, AutopilotStore.AR_MAX_AGE_MS);
        assertTrue(privacy.contains("older than 7 days is never used or sent"));
        assertEquals(300, AreaMap.MAX_CELLS);
        assertTrue(privacy.contains("up to 300 historical squares"));
        assertEquals(50, FilterStore.BAR_MIN);
        assertEquals(150, FilterStore.BAR_MAX);
        assertTrue(terms.contains("between 50% and 150%"));
        assertTrue(privacy.contains("from 50% to 150%"));
        assertEquals(10, AutopilotStore.EXTRA_MAX);
        assertTrue(privacy.contains("a correction of at most 10 points"));
        assertEquals(25, Autopilot.CHECK_EVERY);
        assertTrue(privacy.contains("replaced after 25 more counted offers"));
        assertEquals(40_000, Peek.UNLOCK_POST_MS);
        assertTrue(privacy.contains("within 40 seconds"));
        assertEquals(60_000, Peek.RESUME_MS);
        assertTrue(terms.contains("up to 60 seconds"));
        assertEquals(12_000, OfferFilterService.CHIP_SHOW_MS);
        assertEquals(90_000, OfferAlertState.LIFETIME_MS);
        assertEquals(15 * 60_000L, ScreenAwake.FRESH_MS);
        assertTrue(terms.contains("in the last 15 minutes"));
        assertEquals(20, ScreenAwake.LOW_BATTERY_PERCENT);
        assertTrue(terms.contains("the battery is at 20% or less and not charging"));
        assertEquals(8 * 60 * 60_000L, UpdateHold.CEILING_MS);
        assertTrue(privacy.contains("for up to two minutes"));
        assertEquals(120_000, AutoAccept.SUPPRESS_MS);
        assertEquals(10 * 60_000L, RestrictedSettingsGuide.RETURN_WITHIN_MS);
        assertTrue(BetaProgram.HELP_URL.startsWith("https://offerfilter.org/"));
        assertTrue(privacy.contains("your browser opens the install and setup page on offerfilter.org"));
    }

    @Test public void anUnknownNameOpensTheTerms() {
        assertEquals(LegalTexts.Doc.PRIVACY, LegalTexts.Doc.named("PRIVACY"));
        assertEquals(LegalTexts.Doc.TERMS, LegalTexts.Doc.named(null));
        assertEquals(LegalTexts.Doc.TERMS, LegalTexts.Doc.named("nonsense"));
    }

    /** What Report this offer's dialog says it sends of the rules and Autopilot (FeedbackDialogs, WP7). */
    private static String reportThisOffer() {
        return "your current rules and Autopilot's state (on or off, goal, bar, mode and last change, and the "
                + "acceptance rate it counts with: Dasher's latest, carried forward, or its own estimate)";
    }

    /** A file at the repository's root: the tests run from app/ (Gradle) or from the root. */
    static String read(String name) throws IOException {
        File file = new File("../" + name);
        if (!file.isFile()) file = new File(name);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
