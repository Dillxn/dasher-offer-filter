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
                "Diagnostics after each dash", "Share report", "Place names.", "Tips.", "nothing older than 24 hours",
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
