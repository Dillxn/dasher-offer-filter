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

    @Test public void theBundledTextsAreTheRepositorysFilesWordForWord() throws IOException {
        for (LegalTexts.Doc doc : LegalTexts.Doc.values()) {
            assertEquals(doc.file + " and the app's copy differ: run tools/legal_texts.py", read(doc.file),
                    doc.text());
        }
    }

    @Test public void theTermsAndPrivacyArePublishedBetaTextsAndTheLicenseIsStandardMIT() {
        String terms = LegalTexts.Doc.TERMS.text();
        String privacy = LegalTexts.Doc.PRIVACY.text();
        assertTrue(terms, terms.startsWith("# " + AppName.NAME + " terms of use\n\nBeta terms, effective 7 October 2026 "
                + "· for " + AppName.NAME + " 0.5.0\n\n"));
        assertTrue(privacy, privacy.startsWith("# " + AppName.NAME + " privacy\n\nBeta privacy policy, effective 7 "
                + "October 2026 · for " + AppName.NAME + " 0.5.0\n\n"));
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
                "if nothing of a dash is seen for 8 hours while Dasher is not in front and the screen is off"}) {
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
                "no account, name, email, IP address, user agent, install or device identifier"}) {
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
                "It is kept whether Autopilot is on or off, and a reading older than 7 days is discarded.",
                "no text of the question is kept with it",
                "that offer's line in the decision history is marked so, and Autopilot leaves that offer out of its "
                        + "acceptance-rate count",
                "a note of its last change (time, old and new bar, a fixed reason)",
                "the acceptance rate it carried forward from Dasher's reading at its last checkpoint",
                "they can also hold the decline question's masked words with its percentage",
                "Your acceptance rate leaves the phone only in a report you share (**Share report**), in masked "
                        + "diagnostics you attach to feedback, and in **Report this offer**",
                "It is never in the summary after each dash",
                "Autopilot's bar and why it changed (never your acceptance rate)",
                "The fingerprint kept with a reading never leaves the phone.",
                "Report this offer leaves it out.",
                "Clear history removes the reading, Autopilot's working figures and its last-change note.",
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
                "Your rules and Autopilot settings remain."}) {
            assertTrue(fact, privacy.contains(fact));
        }
        assertTrue("Report this offer in its dialog's words", privacy.contains(reportThisOffer()));
        assertTrue(FeedbackDialogs.OFFER_REPORT_SAYS.contains(reportThisOffer()));
        assertTrue("Clear history's own words name the reading too",
                MainActivity.CLEAR_HISTORY.contains("Autopilot's acceptance-rate reading"));
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
                // Never starve Dasher: paused reads nothing; maps are skipped.
                "While auto-decline is paused, or no rule is set, it reads nothing of Dasher's screen; Android's list "
                        + "of windows alone places its tab.",
                "The inside of a map that Dasher draws is normally skipped, not read.",
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
                // The screen hold with any app in front.
                "the screen is kept from timing out whatever app is in front, but only while something of the dash was "
                        + "seen in the last 15 minutes or Dasher shows it",
                "the battery at 20% or less and not charging", "pausing auto-decline ends the hold",
                // After an update; the notification's ranking.
                "to reopen Offer Filter only over its own screen or the home screen",
                "whether it can pop up or sound, whether it sounded, and whether Do Not Disturb lets it through",
                // Onboarding's reads, its kept progress, and the update hold.
                "how Offer Filter was installed", "Android's restricted-settings record for Offer Filter",
                "whether Android limits its background battery use", "It reads no other app's settings.",
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
        assertTrue(privacy.contains("older than 7 days is discarded"));
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
