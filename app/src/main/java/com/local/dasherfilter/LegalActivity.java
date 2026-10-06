package com.local.dasherfilter;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * One of the bundled texts (the terms of use, the privacy text or the licence), read in the app with no connection:
 * opened from the first-run notice and from Settings' footer. Its Markdown is drawn ({@link LegalMarkdown}): headings,
 * lists, bold and italic words and links, never a literal mark; everything else is the file's own words. Back returns
 * to where it was opened.
 */
public final class LegalActivity extends Activity {
    static final String DOC = "doc";

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        OwnWindowTouches.onTouch(event);
        return super.dispatchTouchEvent(event);
    }

    static Intent intent(Context context, LegalTexts.Doc doc) {
        return new Intent(context, LegalActivity.class).putExtra(DOC, doc.name());
    }

    /** Day or night as chosen with the sun and moon on the main page. */
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        android.content.res.Configuration chosen = Appearance.override(base);
        if (chosen != null) applyOverrideConfiguration(chosen);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Ui ui = new Ui(this);
        LegalTexts.Doc doc = LegalTexts.Doc.named(getIntent().getStringExtra(DOC));
        LinearLayout page = ui.column();

        LinearLayout header = ui.row();
        header.setBackground(new Scenery(Scenery.Part.SKY, ui));
        header.setPadding(ui.dp(8), ui.dp(18), ui.dp(12), ui.dp(12));
        header.setMinimumHeight(ui.dp(84));
        ImageButton back = new ImageButton(this);
        back.setImageDrawable(new Glyph(Glyph.Shape.BACK, ui.ink, ui.dp(24)));
        back.setContentDescription("Back");
        back.setBackground(ui.rounded(ui.surface, ui.dark ? 0x40FFFFFF : 0x330B0B0B, 26));
        back.setOnClickListener(tapped -> finish());
        header.addView(back, new LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)));
        TextView title = ui.text(doc.title, 22, ui.ink, true);
        title.setPadding(ui.dp(8), 0, 0, 0);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        header.addView(title, Ui.weighted());
        page.addView(header, Ui.matchWidth());

        LinearLayout body = ui.column();
        body.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(28));
        render(ui, body, doc.text());
        page.addView(body, Ui.matchWidth());
        View ground = new View(this);
        ground.setBackground(new Scenery(Scenery.Part.GROUND, ui));
        ground.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        page.addView(ground, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(120)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page, Ui.matchWidth());
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(ui.page);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        MainActivity.styleSystemBars(this, ui);
        MainActivity.fitToSystemBars(root);
    }

    /**
     * The text, block by block ({@link LegalMarkdown}): the file's own "# " title is left out (the header already names
     * the page), "## " and deeper headings are headings, "- " and "1. " lines are list items (nested by their
     * indent), "> " a quote, and the rest paragraphs. Bold, italic, code and links inside them are drawn, never shown
     * as marks; web addresses and emails are links.
     */
    static void render(Ui ui, LinearLayout body, String text) {
        for (LegalMarkdown.Block block : LegalMarkdown.blocks(text)) {
            switch (block.kind) {
                case TITLE:
                    break;
                case HEADING:
                case SUBHEADING: {
                    boolean top = block.kind == LegalMarkdown.Kind.HEADING;
                    TextView heading = ui.text("", top ? 17 : 15, ui.ink, true);
                    heading.setText(LegalMarkdown.inline(block.text));
                    if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
                    body.addView(heading, spaced(ui, top ? 26 : 18));
                    break;
                }
                case BULLET:
                case NUMBERED: {
                    LinearLayout item = ui.row();
                    item.setGravity(android.view.Gravity.TOP);
                    item.setPaddingRelative(ui.dp(18) * Math.min(block.depth, 3), 0, 0, 0);
                    boolean numbered = block.kind == LegalMarkdown.Kind.NUMBERED;
                    TextView mark = ui.text(numbered ? block.marker : block.depth > 0 ? "◦" : "•", 15,
                            ui.inkSecondary, false);
                    mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    item.addView(mark, new LinearLayout.LayoutParams(ui.dp(numbered ? 26 : 18),
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                    item.addView(paragraph(ui, block.text, ui.ink), Ui.weighted());
                    body.addView(item, spaced(ui, 8));
                    break;
                }
                case QUOTE: {
                    TextView quote = paragraph(ui, block.text, ui.inkSecondary);
                    quote.setPaddingRelative(ui.dp(14), 0, 0, 0);
                    body.addView(quote, spaced(ui, 12));
                    break;
                }
                case RULE: {
                    View rule = new View(ui.context);
                    rule.setBackgroundColor(ui.baseline);
                    rule.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ui.dp(1)));
                    line.topMargin = ui.dp(18);
                    body.addView(rule, line);
                    break;
                }
                default:
                    body.addView(paragraph(ui, block.text, ui.ink), spaced(ui, 12));
            }
        }
    }

    /** Selectable words; a link in them opens at a tap. */
    private static TextView paragraph(Ui ui, String markdown, int color) {
        TextView text = ui.text("", 15, color, false);
        text.setTypeface(Typeface.DEFAULT);
        android.text.SpannableStringBuilder words = LegalMarkdown.inline(markdown);
        text.setText(words);
        text.setLinkTextColor(ui.link);
        text.setTextIsSelectable(true);
        if (words.getSpans(0, words.length(), android.text.style.URLSpan.class).length > 0) {
            // After making it selectable: long-press still selects, and a tap on a link opens it.
            text.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        }
        return text;
    }

    private static LinearLayout.LayoutParams spaced(Ui ui, int topDp) {
        LinearLayout.LayoutParams params = Ui.matchWidth();
        params.topMargin = ui.dp(topDp);
        return params;
    }
}
