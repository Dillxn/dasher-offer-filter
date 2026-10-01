package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.function.Consumer;

/**
 * The first-run notice ({@link Consent}), the whole homepage until it is accepted: a strip of sky with its title and
 * the mascot, a few short points, links to the terms and the privacy text, and Not now beside I understand. It takes
 * every touch, so nothing behind it can be reached.
 */
final class NoticePage {
    private NoticePage() {}

    static ScrollView build(Context context, Ui ui, Runnable accept, Runnable notNow,
                            Consumer<LegalTexts.Doc> read) {
        LinearLayout page = ui.column();

        LinearLayout header = ui.row();
        header.setBackground(new Scenery(Scenery.Part.SKY, ui));
        header.setPadding(ui.dp(20), ui.dp(22), ui.dp(16), ui.dp(14));
        header.setMinimumHeight(ui.dp(96));
        TextView title = ui.text(Consent.TITLE, 24, ui.ink, true);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        header.addView(title, Ui.weighted());
        header.addView(new Face(context, ui), new LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)));
        page.addView(header, Ui.matchWidth());

        LinearLayout body = ui.column();
        body.setPadding(ui.dp(20), ui.dp(2), ui.dp(20), ui.dp(16));
        for (String[] point : Consent.POINTS) {
            SpannableStringBuilder words = new SpannableStringBuilder(point[0]);
            words.setSpan(new StyleSpan(Typeface.BOLD), 0, words.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            words.setSpan(new ForegroundColorSpan(ui.ink), 0, words.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            words.append(' ').append(point[1]);
            TextView line = ui.text("", 16, ui.inkSecondary, false);
            line.setText(words);
            LinearLayout.LayoutParams params = Ui.matchWidth();
            params.topMargin = ui.dp(14);
            body.addView(line, params);
        }
        TextView agreement = ui.text(Consent.AGREEMENT, 13, ui.inkSecondary, false);
        LinearLayout.LayoutParams agreementParams = Ui.matchWidth();
        agreementParams.topMargin = ui.dp(22);
        body.addView(agreement, agreementParams);
        LinearLayout links = ui.row();
        links.addView(ui.link(LegalTexts.Doc.TERMS.title, () -> read.accept(LegalTexts.Doc.TERMS)));
        links.addView(ui.link(LegalTexts.Doc.PRIVACY.title, () -> read.accept(LegalTexts.Doc.PRIVACY)));
        // Level with the text above: the links' own padding sits in the margin.
        LinearLayout.LayoutParams linksParams = Ui.matchWidth();
        linksParams.setMarginStart(-ui.dp(12));
        body.addView(links, linksParams);
        ui.buttonPair(body, ui.button(Consent.NOT_NOW, false, notNow), ui.button(Consent.ACCEPT, true, accept));
        page.addView(body, Ui.matchWidth());

        // The ground takes what the screen has left, so the page ends on the road as the others do.
        View spacer = new View(context);
        spacer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        page.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        View ground = new View(context);
        ground.setBackground(new Scenery(Scenery.Part.GROUND, ui));
        ground.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        page.addView(ground, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(110)));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(ui.page);
        // Every touch lands here and goes no further.
        scroll.setClickable(true);
        scroll.addView(page, Ui.matchWidth());
        return scroll;
    }

    /** The mascot, calm, in a soft ring: decoration only. */
    @SuppressLint("ViewConstructor")
    private static final class Face extends View {
        private final Ui ui;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Face(Context context, Ui ui) {
            super(context);
            this.ui = ui;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(cx, cy);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((ui.accent & 0x00FFFFFF) | 0x1F000000);
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(3));
            paint.setColor(ui.accent);
            canvas.drawCircle(cx, cy, radius - ui.dp(3), paint);
            Mascot.face(canvas, Mascot.Mood.HAPPY, cx, cy + ui.dp(2), radius * 1.05f, ui.ink);
        }
    }
}
