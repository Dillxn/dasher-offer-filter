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
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Offer Filter's own half of a split screen at about a third of the screen (the owner: "split screen at 50/50 doesn't
 * leave enough room for seeing gps well inside the dd app"): one strip with the mascot (a tap pauses or resumes, as on
 * the homepage), the latest offer's verdict (in the homepage caption's words), Autopilot's chip and the filter's
 * status, nothing else, so the divider can give Dasher and its map about two thirds. Dragging the divider back gives
 * the whole page again. The status line is also where a setup problem, or the note that this layout needs a tap for
 * background offers, shows; a tap on it then does what the homepage's line would. The chip is the layout's one
 * Autopilot control (the page with its constellation's button is not shown here): a tap opens Autopilot's details, a
 * long press its goal. It stands at the status line's start and costs the strip no height ({@link AutopilotChip.Row}).
 * The strip fills its window, its words centred in it; like the homepage, it scrolls only where a very large font leaves
 * no other way (a long line needing the user, at twice the font, in a strip a third of a phone tall), so no word of
 * that line, or of the verdict, is ever cut off.
 */
@SuppressLint("ViewConstructor")
final class DrivingStrip extends ScrollView {
    /** A split-screen window shorter than this (about a third of most phones) gets the strip, not the page. */
    static final int HEIGHT_DP = 340;
    /** The status line's words at the normal font size. */
    private static final float STATUS_SP = 14;

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

    /**
     * @param toggle the mascot's tap: pause or resume auto-decline (or, with no rule yet, offer the typical minimums,
     *               whose "Set my own" says how to reach the knobs, which the strip does not have)
     * @param chip   Autopilot's chip, wired by the page ({@code MainActivity.newAutopilotChip}), which shows it
     *               Autopilot's status as it shows its own
     */
    DrivingStrip(Context context, Ui ui, Runnable toggle, AutopilotChip chip) {
        super(context);
        this.ui = ui;
        setFillViewport(true);
        setBackgroundColor(ui.page);
        LinearLayout strip = new LinearLayout(context);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
        mascot = new MascotButton(context, ui);
        mascot.setOnClickListener(tapped -> toggle.run());
        strip.addView(mascot, new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)));
        LinearLayout lines = ui.column();
        lines.setPadding(ui.dp(12), 0, 0, 0);
        verdict = ui.text("", 16, ui.ink, true);
        // No line limit either: the caption's words ("Passed below your minimums", "Accept requested, not confirmed")
        // take three lines at twice the font in a narrow strip, and a limit would cut the last of them off without a
        // mark. The strip scrolls where a very large font leaves no other way.
        lines.addView(verdict, Ui.matchWidth());
        status = ui.text("", STATUS_SP, ui.inkSecondary, false);
        status.setMinHeight(ui.dp(48));
        status.setGravity(Gravity.CENTER_VERTICAL);
        // No line limit: the chip's row works out its height from all of the line's words (shrinking them a little,
        // closing their lines up, or putting the chip above them, whichever is shortest), so none is ever cut off; a
        // cut line could hide what needs the user, or the word its tap does.
        status.setOnClickListener(tapped -> {
            Runnable action = statusAction;
            if (action != null) action.run();
        });
        // Autopilot's chip at the status line's start: beside its 48 dp line it takes no height of its own. A line too
        // long for the room beside it makes the two shrink a little (at a larger font) or the row grow by the least it
        // can; no word is cut.
        lines.addView(new AutopilotChip.Row(context, ui, chip, status, STATUS_SP), Ui.matchWidth());
        strip.addView(lines, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // At least the window's height (fillViewport), so the words stand in its middle.
        addView(strip, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
        // The homepage caption's words for the latest offer: one Autopilot let through below the minimums never reads
        // as a full pass, and an acceptance says whose it was.
        String said = latest == null ? (waiting ? "Waiting for offers" : "No offers yet")
                : "Latest · " + (latest.facts.payCents == null ? "Pay unread" : DecisionLog.money(latest.facts.payCents))
                        + " · " + MainActivity.captionOutcome(latest);
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
