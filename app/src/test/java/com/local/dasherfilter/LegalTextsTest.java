package com.local.dasherfilter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The texts the app shows are the repository's TERMS.md, PRIVACY.md and LICENSE, word for word. */
public final class LegalTextsTest {
    @Test public void theBundledTextsAreTheRepositorysFilesWordForWord() throws IOException {
        for (LegalTexts.Doc doc : LegalTexts.Doc.values()) {
            assertEquals(doc.file + " and the app's copy differ: run tools/legal_texts.py", read(doc.file),
                    doc.text());
        }
    }

    @Test public void theTermsAndPrivacyRemainDraftsAndTheLicenseIsStandardMIT() {
        for (LegalTexts.Doc doc : new LegalTexts.Doc[] {LegalTexts.Doc.TERMS, LegalTexts.Doc.PRIVACY}) {
            assertTrue(doc.file, doc.text().contains("Not legal advice; have a lawyer review before public release."));
            assertFalse(doc.file, doc.text().contains("{app}"));
            assertTrue(doc.file, doc.text().contains(AppName.NAME));
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
        String privacy = LegalTexts.Doc.PRIVACY.text();
        for (String flow : new String[] {"Update checks.", "GitHub connection", "Reports, only if you turn them on",
                "Diagnostics after each dash", "Share report", "Place names.", "Navigation, only when you tap it.",
                "Tips.", "24-hour retention window",
                "rounded to about half a kilometre", "masked"}) {
            assertTrue(flow, privacy.contains(flow));
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
                "Aggregate outcome counts are kept separately", "Up to 30 queued reports",
                "Queued reports have no age-based expiry", "The logs' 24-hour window does not expire these copies",
                "Older entries are pruned during app use", "It does not remove queued reports or copies already sent",
                "A request already in flight may still arrive", "Uninstalling removes the app's local data",
                "It does not remove reports or other copies already shared", "does not run again merely because this notice changes"}) {
            assertTrue(fact, privacy.contains(fact));
        }
        assertFalse(privacy.contains("Uninstalling the app removes everything it kept."));
    }

    @Test public void privacyNamesExternalFlowsAndTheLimitsOfMasking() {
        String privacy = LegalTexts.Doc.PRIVACY.text();
        for (String fact : new String[] {"historical area's center coordinates", "Maps or Waze", "Gas and gas-price choices",
                "GitHub, Render, Google, Anthropic and OpenAI", "Masking is pattern-based and can miss unfamiliar wording",
                "Notes you type with Report this offer are not masked", "A report does not guarantee review or a fix",
                "masked again with the current rules immediately before sending"}) {
            assertTrue(fact, privacy.contains(fact));
        }
        assertFalse(privacy.contains("no server that collects your data"));
    }

    @Test public void draftStatusAndUnresolvedPrivateContactCannotLookLikeLaunchClearance() {
        String privacy = LegalTexts.Doc.PRIVACY.text();
        assertTrue(privacy.contains("public project identity is Dillxn"));
        assertTrue(privacy.contains("https://github.com/Dillxn/offer-filter-site/issues"));
        assertTrue(privacy.contains("Posts there are public"));
        assertTrue(privacy.contains("Do not attach diagnostic reports"));
        assertTrue(privacy.contains("A private contact for privacy, deletion and security requests is not yet configured"));
        assertTrue(privacy.contains("Public update downloads do not require a GitHub connection"));
        assertTrue(privacy.contains("retention and deletion schedule for those copies has not yet been confirmed"));
        for (LegalTexts.Doc doc : new LegalTexts.Doc[] {LegalTexts.Doc.TERMS, LegalTexts.Doc.PRIVACY}) {
            assertTrue(doc.file, doc.text().startsWith("# " + AppName.NAME));
            assertTrue(doc.file, doc.text().contains("Draft of 6 October 2026."));
            assertTrue(doc.file, doc.text().contains("No attorney review is claimed"));
        }
        assertTrue(LegalTexts.Doc.TERMS.text().contains("To the extent the law allows"));
        assertTrue(LegalTexts.Doc.TERMS.text().contains("These terms do not limit the rights granted by the MIT License"));
    }

    @Test public void anUnknownNameOpensTheTerms() {
        assertEquals(LegalTexts.Doc.PRIVACY, LegalTexts.Doc.named("PRIVACY"));
        assertEquals(LegalTexts.Doc.TERMS, LegalTexts.Doc.named(null));
        assertEquals(LegalTexts.Doc.TERMS, LegalTexts.Doc.named("nonsense"));
    }

    /** A file at the repository's root: the tests run from app/ (Gradle) or from the root. */
    static String read(String name) throws IOException {
        File file = new File("../" + name);
        if (!file.isFile()) file = new File(name);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
