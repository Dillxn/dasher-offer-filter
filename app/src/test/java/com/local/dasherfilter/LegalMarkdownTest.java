package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.content.Intent;
import android.graphics.Typeface;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

/**
 * BETA-18: the legal pages a stranger is asked to read draw their Markdown (headings, lists, bold, links) instead of
 * showing literal asterisks.
 */
@RunWith(RobolectricTestRunner.class)
public class LegalMarkdownTest {

    private static String spanned(Spanned text, Class<?> type, Object style) {
        StringBuilder found = new StringBuilder();
        for (Object span : text.getSpans(0, text.length(), type)) {
            if (style != null && span instanceof StyleSpan && ((StyleSpan) span).getStyle() != (Integer) style) continue;
            if (found.length() > 0) found.append('|');
            found.append(text.subSequence(text.getSpanStart(span), text.getSpanEnd(span)));
        }
        return found.toString();
    }

    @Test public void boldItalicCodeAndLinksAreDrawnNotShown() {
        Spanned bold = LegalMarkdown.inline("With **Attach masked diagnostics** on (off by default)");
        assertEquals("With Attach masked diagnostics on (off by default)", bold.toString());
        assertEquals("Attach masked diagnostics", spanned(bold, StyleSpan.class, Typeface.BOLD));

        Spanned mixed = LegalMarkdown.inline("Use __Share report__, *not* _this_ or `code`; see [the site](https://offerfilter.org/install/).");
        assertEquals("Use Share report, not this or code; see the site.", mixed.toString());
        assertEquals("Share report", spanned(mixed, StyleSpan.class, Typeface.BOLD));
        assertEquals("not|this", spanned(mixed, StyleSpan.class, Typeface.ITALIC));
        assertEquals("code", spanned(mixed, TypefaceSpan.class, null));
        URLSpan[] links = mixed.getSpans(0, mixed.length(), URLSpan.class);
        assertEquals(1, links.length);
        assertEquals("https://offerfilter.org/install/", links[0].getURL());
        assertEquals("the site", mixed.subSequence(mixed.getSpanStart(links[0]), mixed.getSpanEnd(links[0])).toString());

        Spanned nested = LegalMarkdown.inline("**Bold with *italic* inside**");
        assertEquals("Bold with italic inside", nested.toString());
        assertEquals("italic", spanned(nested, StyleSpan.class, Typeface.ITALIC));
    }

    @Test public void unmatchedAndEscapedMarksStayAsWritten() {
        assertEquals("5 * 3 = 15", LegalMarkdown.inline("5 * 3 = 15").toString());
        assertEquals("a_b_c and snake_case stay", LegalMarkdown.inline("a_b_c and snake_case stay").toString());
        assertEquals("*literal* stars", LegalMarkdown.inline("\\*literal\\* stars").toString());
        assertEquals("an open ** mark", LegalMarkdown.inline("an open ** mark").toString());
        assertEquals("[not a link] (here)", LegalMarkdown.inline("[not a link] (here)").toString());
    }

    @Test public void plainAddressesAndEmailsBecomeLinks() {
        Spanned text = LegalMarkdown.inline("Use the form on offerfilter.org or write to help@example.org.");
        Set<String> urls = new HashSet<>();
        for (URLSpan link : text.getSpans(0, text.length(), URLSpan.class)) urls.add(link.getURL());
        assertTrue(urls.toString(), urls.contains("http://offerfilter.org") || urls.contains("https://offerfilter.org"));
        assertTrue(urls.toString(), urls.contains("mailto:help@example.org"));
        Spanned angle = LegalMarkdown.inline("Open <https://offerfilter.org/install/> now");
        assertEquals("Open https://offerfilter.org/install/ now", angle.toString());
    }

    @Test public void blocksKeepHeadingsListsNumbersQuotesAndContinuations() {
        List<LegalMarkdown.Block> blocks = LegalMarkdown.blocks("# Title\n\nFirst line\njoined line.\n\n"
                + "## Heading ##\n### Sub\n- one\n  continued\n  - nested\n* star item\n1. first\n12) twelfth\n"
                + "> quoted\n\n---\nlast");
        List<String> shown = new ArrayList<>();
        for (LegalMarkdown.Block block : blocks) shown.add(block.toString());
        assertEquals(Arrays.asList("TITLE: Title", "PARAGRAPH: First line joined line.", "HEADING: Heading",
                "SUBHEADING: Sub", "BULLET: one continued", "BULLET/1: nested", "BULLET: star item",
                "NUMBERED 1.: first", "NUMBERED 12.: twelfth", "QUOTE: quoted", "RULE: ", "PARAGRAPH: last"), shown);
    }

    /** Every bundled text on its page: headings for screen readers, its bold drawn, and no mark left showing. */
    @Test public void everyBundledPageDrawsItsMarkdown() {
        for (LegalTexts.Doc doc : LegalTexts.Doc.values()) {
            Intent intent = LegalActivity.intent(RuntimeEnvironment.getApplication(), doc);
            try (ActivityController<LegalActivity> page = Robolectric.buildActivity(LegalActivity.class, intent)
                    .setup()) {
                List<TextView> texts = new ArrayList<>();
                collect(page.get().findViewById(android.R.id.content), texts);
                int headings = 0;
                StringBuilder all = new StringBuilder();
                for (TextView text : texts) {
                    String words = text.getText().toString();
                    all.append(words).append('\n');
                    assertFalse(doc + " shows a literal mark: " + words, words.contains("**") || words.contains("__")
                            || words.contains("](") || words.startsWith("#") || words.startsWith("- "));
                    if (text.isAccessibilityHeading()) headings++;
                }
                if (doc != LegalTexts.Doc.LICENSE) assertTrue(doc + " headings: " + headings, headings >= 5);
                if (doc == LegalTexts.Doc.PRIVACY) {
                    TextView feedback = containing(texts, "Use Send anonymous feedback in Settings");
                    assertNotNull(all.toString(), feedback);
                    Spanned words = (Spanned) feedback.getText();
                    assertTrue(spanned(words, StyleSpan.class, Typeface.BOLD).contains("Send anonymous feedback"));
                    URLSpan[] links = words.getSpans(0, words.length(), URLSpan.class);
                    assertEquals(1, links.length);
                    assertTrue(links[0].getURL(), links[0].getURL().endsWith("offerfilter.org"));
                    assertTrue("a tap opens it", feedback.getMovementMethod() instanceof LinkMovementMethod);
                    assertTrue("still selectable", feedback.isTextSelectable());
                }
            }
        }
    }

    /** Only real addresses become links: the texts' own names (Offer Filter, Dasher, MIT) do not. */
    @Test public void onlyAddressesAreLinkedInTheBundledTexts() {
        Set<String> urls = new HashSet<>();
        for (LegalTexts.Doc doc : LegalTexts.Doc.values()) {
            for (LegalMarkdown.Block block : LegalMarkdown.blocks(doc.text())) {
                Spanned words = LegalMarkdown.inline(block.text);
                for (URLSpan link : words.getSpans(0, words.length(), URLSpan.class)) {
                    urls.add(words.subSequence(words.getSpanStart(link), words.getSpanEnd(link)).toString());
                }
            }
        }
        for (String url : urls) {
            assertTrue(url, url.endsWith("offerfilter.org") || url.endsWith(".onrender.com") || url.contains("@"));
        }
    }

    private static TextView containing(List<TextView> texts, String words) {
        for (TextView text : texts) if (text.getText().toString().contains(words)) return text;
        return null;
    }

    private static void collect(View view, List<TextView> out) {
        if (view instanceof TextView) out.add((TextView) view);
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), out);
        }
    }
}
