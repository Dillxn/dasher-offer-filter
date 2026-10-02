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
 * opened from the first-run notice and from Settings' footer. Its few marks of Markdown are drawn as headings and
 * bullets; everything else is the file's own words. Back returns to where it was opened.
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
     * The text, paragraph by paragraph: "# " is the file's own title (the header already names the page), "## " a
     * heading, "- " a bullet; a blank line ends a paragraph.
     */
    static void render(Ui ui, LinearLayout body, String text) {
        StringBuilder paragraph = new StringBuilder();
        for (String line : (text + "\n").split("\n", -1)) {
            boolean ends = line.isEmpty() || line.startsWith("#") || line.startsWith("- ");
            if (ends && paragraph.length() > 0) {
                body.addView(paragraph(ui, paragraph.toString()), spaced(ui, 12));
                paragraph.setLength(0);
            }
            if (line.isEmpty() || line.startsWith("# ")) continue;
            if (line.startsWith("## ")) {
                TextView heading = ui.text(line.substring(3), 17, ui.ink, true);
                if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
                body.addView(heading, spaced(ui, 26));
            } else if (line.startsWith("- ")) {
                LinearLayout bullet = ui.row();
                bullet.setGravity(android.view.Gravity.TOP);
                TextView dot = ui.text("•", 15, ui.inkSecondary, false);
                dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                bullet.addView(dot, new LinearLayout.LayoutParams(ui.dp(18), ViewGroup.LayoutParams.WRAP_CONTENT));
                bullet.addView(paragraph(ui, line.substring(2)), Ui.weighted());
                body.addView(bullet, spaced(ui, 8));
            } else {
                if (paragraph.length() > 0) paragraph.append(' ');
                paragraph.append(line);
            }
        }
    }

    private static TextView paragraph(Ui ui, String words) {
        TextView text = ui.text(words, 15, ui.ink, false);
        text.setTypeface(Typeface.DEFAULT);
        text.setTextIsSelectable(true);
        return text;
    }

    private static LinearLayout.LayoutParams spaced(Ui ui, int topDp) {
        LinearLayout.LayoutParams params = Ui.matchWidth();
        params.topMargin = ui.dp(topDp);
        return params;
    }
}
