package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The offer's score beside the filter tab's slim peek, so a glance tells what it scored (0.6.21, the owner, a map
 * beside Dasher: "on this screen I want to be able to see at a small glance the score of the offer as it's being
 * declined"): "✕ 52%" in red while the app declines it, "✓ 88%" in green when it passes, "? 74%" in amber when it is
 * the user's to decide, the score being its pay as a percent of what the minimums ask. A small pill in the status
 * colour; touches pass through it to Dasher. One auto-decline would decline but does not (paused, 0.6.35, or the
 * user's own change while it showed) is "✕ 52%" outlined in red: what the app would do, with nothing tapped.
 */
@SuppressLint("ViewConstructor")
final class ScoreTag extends View {
    static final int HEIGHT_DP = 28;

    private final Ui ui;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();
    private int score = -1;
    private OfferRule.Result result;
    private boolean would;

    ScoreTag(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        text.setTypeface(Ui.MEDIUM);
        text.setFakeBoldText(true);
        text.setTextSize(Math.min(ui.sp(14), ui.dp(18)));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(ui.dp(2));
    }

    /** The score and what came of the offer: DECLINE (the app declines it), KEEP or REVIEW. */
    void show(int score, OfferRule.Result result) {
        show(score, result, false);
    }

    /** As above; {@code would}, a DECLINE the app would make but does not: nothing is tapped. */
    void show(int score, OfferRule.Result result, boolean would) {
        would = would && result == OfferRule.Result.DECLINE;
        if (score == this.score && result == this.result && would == this.would) return;
        this.score = score;
        this.result = result;
        this.would = would;
        setContentDescription(would ? "Would decline: it scores " + score + "%"
                : result == OfferRule.Result.DECLINE ? "Declined: it scored " + score + "%"
                : result == OfferRule.Result.KEEP ? "Passes: it scores " + score + "%"
                : "Yours to decide: it scores " + score + "%");
        requestLayout();
        invalidate();
    }

    /** What it says, as drawn. */
    String label() {
        String mark = result == OfferRule.Result.DECLINE ? "✕" : result == OfferRule.Result.KEEP ? "✓" : "?";
        return mark + " " + score + "%";
    }

    int score() {
        return score;
    }

    OfferRule.Result result() {
        return result;
    }

    /** Whether it shows a decline the app would make but does not (outlined). */
    boolean would() {
        return would;
    }

    /** The pill's colour: red for a decline, green for a pass, amber for the user's to decide. */
    int color() {
        return result == OfferRule.Result.DECLINE ? Ui.CRITICAL : result == OfferRule.Result.KEEP ? Ui.GOOD
                : Ui.WARNING;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = Math.round(text.measureText(label()) + ui.dp(20));
        setMeasuredDimension(width, ui.dp(HEIGHT_DP));
    }

    @Override protected void onDraw(Canvas canvas) {
        float height = getHeight();
        float round = height / 2;
        pill.set(0, 0, getWidth(), height);
        int color = color();
        if (would) {
            // Outlined on the page's own colour, so it reads as what would happen, not what did.
            float inset = ring.getStrokeWidth() / 2;
            fill.setColor(ui.surface);
            canvas.drawRoundRect(pill, round, round, fill);
            pill.inset(inset, inset);
            ring.setColor(color);
            canvas.drawRoundRect(pill, round - inset, round - inset, ring);
            text.setColor(color);
        } else {
            fill.setColor(color);
            canvas.drawRoundRect(pill, round, round, fill);
            text.setColor(Ui.onStatus(color));
        }
        Paint.FontMetrics metrics = text.getFontMetrics();
        canvas.drawText(label(), ui.dp(10), round - (metrics.ascent + metrics.descent) / 2, text);
    }
}
