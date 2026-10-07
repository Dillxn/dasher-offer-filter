package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Offer Filter's own half of a split screen at about a third of the screen (the owner: "split screen at 50/50 doesn't
 * leave enough room for seeing gps well inside the dd app"): one strip with the mascot (a tap pauses or resumes, as on
 * the homepage), the latest offer's verdict and the filter's status, nothing else, so the divider can give Dasher and
 * its map about two thirds. Dragging the divider back gives the whole page again. The status line is also where a
 * setup problem, or the note that this layout needs a tap for background offers, shows; a tap on it then does what
 * the homepage's line would.
 */
@SuppressLint("ViewConstructor")
final class DrivingStrip extends LinearLayout {
    /** A split-screen window shorter than this (about a third of most phones) gets the strip, not the page. */
    static final int HEIGHT_DP = 340;

    /** Whether this screen gets the strip: one half of a split screen, and short. */
    static boolean wanted(Activity activity) {
        int height = activity.getResources().getConfiguration().screenHeightDp;
        return activity.isInMultiWindowMode() && height > 0 && height < HEIGHT_DP;
    }

    private final Ui ui;
    private final MascotButton mascot;
    private final TextView verdict;
    private final TextView status;
    private Runnable statusAction;

    /** @param toggle the mascot's tap: pause or resume auto-decline (or, with no rule yet, say how to begin) */
    DrivingStrip(Context context, Ui ui, Runnable toggle) {
        super(context);
        this.ui = ui;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundColor(ui.page);
        setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
        mascot = new MascotButton(context, ui);
        mascot.setOnClickListener(tapped -> toggle.run());
        addView(mascot, new LayoutParams(ui.dp(56), ui.dp(56)));
        LinearLayout lines = ui.column();
        lines.setPadding(ui.dp(12), 0, 0, 0);
        verdict = ui.text("", 16, ui.ink, true);
        verdict.setMaxLines(2);
        lines.addView(verdict, Ui.matchWidth());
        status = ui.text("", 14, ui.inkSecondary, false);
        status.setMinHeight(ui.dp(48));
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setMaxLines(3);
        status.setOnClickListener(tapped -> {
            Runnable action = statusAction;
            if (action != null) action.run();
        });
        lines.addView(status, Ui.matchWidth());
        addView(lines, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
    }

    /**
     * What the strip shows now.
     *
     * @param state the filter: on, paused, or no rule yet
     * @param latest the newest offer's line, or null for none
     * @param waiting whether everything is ready for offers ("Waiting for offers" while there is none yet)
     * @param problem what needs the user (a setup problem, the layout's note), or null; its {@code action} is the tap
     */
    void show(FilterHeroView.State state, DecisionLog.Entry latest, boolean waiting, String problem, Runnable action) {
        mascot.show(state);
        String said = latest == null ? (waiting ? "Waiting for offers" : "No offers yet")
                : "Latest · " + (latest.facts.payCents == null ? "Pay unread" : DecisionLog.money(latest.facts.payCents))
                        + " · " + DecisionLog.outcome(latest).said;
        if (!said.contentEquals(verdict.getText())) verdict.setText(said);
        String line = problem != null ? problem : state == FilterHeroView.State.ON ? "Auto-decline is on"
                : state == FilterHeroView.State.PAUSED ? "Paused · nothing is declined"
                : "No rules yet · drag the divider to set one";
        if (!line.contentEquals(status.getText())) status.setText(line);
        statusAction = problem != null ? action : null;
        status.setClickable(statusAction != null);
        status.setTextColor(problem != null ? ui.ink : ui.inkSecondary);
        status.setCompoundDrawablesRelative(problem != null ? new Glyph(Glyph.Shape.SIGN, Ui.CRITICAL, ui.dp(18))
                : null, null, null, null);
        status.setCompoundDrawablePadding(ui.dp(6));
    }

    String verdictText() {
        return verdict.getText().toString();
    }

    String statusText() {
        return status.getText().toString();
    }

    /** The mascot in its ring, in the filter's colour: awake while on, asleep while paused. A tap pauses or resumes. */
    @SuppressLint("ViewConstructor")
    static final class MascotButton extends View {
        private final Ui ui;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF ring = new RectF();
        private final DashPathEffect dashed;
        private FilterHeroView.State state = FilterHeroView.State.OFF;

        MascotButton(Context context, Ui ui) {
            super(context);
            this.ui = ui;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            dashed = new DashPathEffect(new float[] {ui.dp(4), ui.dp(3)}, 0);
            setClickable(true);
            setFocusable(true);
            describe();
        }

        void show(FilterHeroView.State next) {
            if (next == state) return;
            state = next;
            describe();
            invalidate();
        }

        private void describe() {
            setContentDescription(state == FilterHeroView.State.ON ? "Pause auto-decline"
                    : state == FilterHeroView.State.PAUSED ? "Resume auto-decline" : "Set up rules");
        }

        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(cx, cy) - ui.dp(4);
            fill.setColor(isPressed() ? ui.gridline : ui.surface);
            canvas.drawCircle(cx, cy, radius, fill);
            int color = state == FilterHeroView.State.ON ? ui.accent
                    : state == FilterHeroView.State.PAUSED ? Ui.WARNING : ui.inkMuted;
            line.setColor(color);
            line.setStrokeWidth(ui.dp(3));
            line.setPathEffect(state == FilterHeroView.State.PAUSED ? dashed : null);
            ring.set(cx - radius, cy - radius, cx + radius, cy + radius);
            if (state == FilterHeroView.State.OFF) canvas.drawCircle(cx, cy, radius, line);
            else canvas.drawArc(ring, 135, state == FilterHeroView.State.ON ? 320 : 240, false, line);
            line.setPathEffect(null);
            Mascot.face(canvas, Mascot.moodOf(state), cx, cy + ui.dp(1), radius * 1.1f,
                    state == FilterHeroView.State.OFF ? ui.inkMuted : ui.ink);
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }
    }
}
