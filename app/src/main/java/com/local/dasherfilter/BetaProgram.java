package com.local.dasherfilter;

import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;

/**
 * The public beta's few words in Settings, with no paragraph: a small "Beta" label beside the version in the footer,
 * and a Help row that opens the install and setup help on the website (offerfilter.org/install/), at the user's tap.
 */
final class BetaProgram {
    static final String LABEL = "Beta";
    static final String HELP = "Help";
    static final String HELP_URL = "https://offerfilter.org/install/";
    /** The Help row's second line: where it goes. */
    static final String HELP_DETAIL = "offerfilter.org/install";

    private BetaProgram() {}

    /** The website's install and setup help, opened in the browser. */
    static Intent help() {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(HELP_URL));
    }

    /**
     * Settings' footer line: "Offer Filter v0.4.73 [Beta] · Not a DoorDash app.", the label a small outlined pill
     * that wraps with the words. Screen readers hear "Offer Filter version 0.4.73, beta. Not a DoorDash app."
     */
    static CharSequence footer(Ui ui, String version) {
        SpannableStringBuilder words = new SpannableStringBuilder(AppName.NAME + " v" + version + " ");
        int start = words.length();
        words.append(LABEL);
        words.setSpan(new Pill(ui), start, words.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        words.append(" · Not a DoorDash app.");
        return words;
    }

    static String footerSaid(String version) {
        return AppName.NAME + " version " + version + ", " + LABEL.toLowerCase(java.util.Locale.US)
                + ". Not a DoorDash app.";
    }

    /** The label drawn as a small rounded outline in the link's ink, a little smaller than the words around it. */
    static final class Pill extends ReplacementSpan {
        private final int ink;
        private final float padding;
        private final float stroke;
        private final RectF box = new RectF();

        Pill(Ui ui) {
            ink = ui.link;
            padding = ui.dp(5);
            stroke = Math.max(1, ui.dp(1));
        }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            return Math.round(small(paint).measureText(text, start, end) + 2 * padding + stroke);
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y,
                                   int bottom, Paint paint) {
            Paint label = small(paint);
            float width = label.measureText(text, start, end);
            Paint.FontMetrics metrics = label.getFontMetrics();
            float textTop = y + metrics.ascent;
            float textBottom = y + metrics.descent;
            box.set(x + stroke / 2, textTop - stroke, x + width + 2 * padding + stroke / 2, textBottom + stroke);
            Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeWidth(stroke);
            outline.setColor(ink);
            float radius = box.height() / 2;
            canvas.drawRoundRect(box, radius, radius, outline);
            label.setColor(ink);
            canvas.drawText(text, start, end, x + padding + stroke / 2, y, label);
        }

        private static Paint small(Paint paint) {
            Paint label = new Paint(paint);
            label.setTextSize(paint.getTextSize() * 0.9f);
            label.setFakeBoldText(true);
            return label;
        }
    }
}
