#!/usr/bin/env python3
"""Writes app/src/main/java/com/local/dasherfilter/LegalTexts.java from TERMS.md, PRIVACY.md and LICENSE.

Run it after editing any of the three files (LegalTextsTest fails until the app's copy matches them). Each file becomes
a Java text block, word for word, with the app's name written as {app} (filled in from AppName.NAME), long lines
wrapped with the text-block line-continuation escape so the source stays readable. The app shows these texts with no
connection: build-local.sh packages no assets, so they live in the code.
"""
import re
import sys
from pathlib import Path

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]
NAME = re.search(r'NAME = "([^"]+)"', (root / "app/src/main/java/com/local/dasherfilter/AppName.java")
                 .read_text(encoding="utf-8")).group(1)
INDENT = " " * 12
WIDTH = 118 - len(INDENT) - 2  # room for " \"

docs = [
    ("TERMS", "Terms of use", "TERMS.md"),
    ("PRIVACY", "Privacy", "PRIVACY.md"),
    ("LICENSE", "License", "LICENSE"),
]


def block(text):
    assert "\\" not in text and '"""' not in text, "text blocks cannot hold these"
    assert text.endswith("\n")
    assert "{app}" not in text
    text = text.replace(NAME, "{app}")
    out = []
    for line in text[:-1].split("\n"):
        assert line == line.rstrip(), "trailing space: " + line
        if not line:
            out.append("")
            continue
        if len(line) <= WIDTH:
            out.append(INDENT + line)
            continue
        words = line.split(" ")
        current = ""
        pieces = []
        for word in words:
            candidate = word if not current else current + " " + word
            if len(candidate) + 1 > WIDTH and current:
                pieces.append(current + " ")
                current = word
            else:
                current = candidate
        pieces.append(current)
        for piece in pieces[:-1]:
            out.append(INDENT + piece + "\\")
        out.append(INDENT + pieces[-1])
    return '"""\n' + "\n".join(out) + '\n' + INDENT + '"""'


constants = []
for constant, title, file in docs:
    text = (root / file).read_text(encoding="utf-8")
    constants.append(f"    private static final String {constant}_TEXT = {block(text)};\n")

java = '''package com.local.dasherfilter;

/**
 * The terms of use, the privacy text and the licence, bundled so the app shows them with no connection: TERMS.md,
 * PRIVACY.md and LICENSE word for word (LegalTextsTest holds them to the files), with the app's name filled in from
 * {@link AppName}. Written by tools/legal_texts.py from the files: edit the files and run it, never this.
 */
final class LegalTexts {
    /** One of the texts: its short title (the page's header and the link to it) and the file it comes from. */
    enum Doc {
        TERMS("Terms of use", "TERMS.md", TERMS_TEXT),
        PRIVACY("Privacy", "PRIVACY.md", PRIVACY_TEXT),
        LICENSE("License", "LICENSE", LICENSE_TEXT);

        final String title;
        final String file;
        private final String text;

        Doc(String title, String file, String text) {
            this.title = title;
            this.file = file;
            this.text = text;
        }

        /** The text as the file has it, the app's name filled in. */
        String text() {
            return text.replace("{app}", AppName.NAME);
        }

        /** The text an intent names, or the terms for a name that is none of them. */
        static Doc named(String name) {
            for (Doc doc : values()) if (doc.name().equals(name)) return doc;
            return TERMS;
        }
    }

''' + "\n".join(constants) + '''
    private LegalTexts() {}
}
'''
(root / "app/src/main/java/com/local/dasherfilter/LegalTexts.java").write_text(java, encoding="utf-8")
print("written")
