package com.local.dasherfilter;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * What only a split screen needs on the homepage (the owner: "the user isn't juggling multiple windows (dasher, filter,
 * gps) and can just flow"). Beside Dasher, once ever: a hint that dragging the divider toward Offer Filter gives
 * Dasher's map more room (Offer Filter's half then becomes a strip, {@link DrivingStrip}). It lies over the bottom of
 * the page for a few seconds and takes no touches and no room, so the page under it is laid out as without it. During
 * a dash beside another app (a map, say), with Peek on: a readiness line saying background offers need a tap in this
 * layout, with Swap, which puts Dasher in Offer Filter's own half so the map stays ({@link DasherSplit#swapIn}).
 */
final class SplitLines {
    static final String HINT = "Drag the divider toward " + AppName.NAME + " to give Dasher's map more room";
    static final String LAYOUT_NOTE = "Background offers need a tap in this layout — use Dasher full screen, or Maps "
            + "with Dasher beside";
    /** The layout note, short, for the strip's one status line. */
    static final String LAYOUT_NOTE_SHORT = "Background offers need a tap here · Swap in Dasher";
    /** How long the hint stays up; it is never shown again. */
    static final long HINT_MS = 10_000;
    private static final String PREFS = "split_lines";
    private static final String HINT_SHOWN = "divider_hint_shown";

    private final Activity activity;
    private final View page;
    private final TextView hint;
    private final LinearLayout noteRow;
    private final Runnable hideHint = this::hideHint;
    /** The hint is up on this page (for {@link #HINT_MS}); it was shown, so it never comes again. */
    private boolean hintUp;
    private boolean noteWanted;

    /**
     * @param root the window's frame: the hint lies over the page in it
     * @param page the main page: the hint shows only while it does
     * @param lines the page's readiness lines: the layout note is one of them
     * @param swap the layout note's Swap: Dasher into Offer Filter's own half
     */
    SplitLines(Activity activity, Ui ui, FrameLayout root, View page, LinearLayout lines, Runnable swap) {
        this.activity = activity;
        this.page = page;
        hint = ui.text(HINT, 14, ui.ink, false);
        hint.setBackground(ui.rounded(ui.surface, ui.gridline, 16));
        hint.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
        hint.setGravity(Gravity.CENTER_VERTICAL);
        hint.setCompoundDrawablesRelative(new Glyph(Glyph.Shape.SPLIT, ui.accent, ui.dp(20)), null, null, null);
        hint.setCompoundDrawablePadding(ui.dp(10));
        hint.setElevation(ui.dp(4));
        // Words only: a touch goes to the page under it, and TalkBack reads it as it appears (a live region only while
        // it is up, showHint).
        hint.setClickable(false);
        hint.setFocusable(false);
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        at.setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(16));
        root.addView(hint, at);
        noteRow = row(ui, lines, LAYOUT_NOTE, "Swap", swap);
    }

    /** One readiness line: a small sign, the words, and the word a tap does; the whole line is the button. */
    private static LinearLayout row(Ui ui, LinearLayout parent, String words, String action, Runnable tap) {
        LinearLayout row = ui.row();
        row.setBackground(ui.pressable(16));
        row.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6));
        row.setMinimumHeight(ui.dp(48));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(words + ". " + action + ".");
        row.setOnClickListener(tapped -> tap.run());
        View glyph = new View(parent.getContext());
        glyph.setBackground(new Glyph(Glyph.Shape.SIGN, Ui.WARNING, ui.dp(20)));
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(glyph, new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
        TextView text = ui.text(words, 14, ui.ink, false);
        text.setPadding(ui.dp(10), 0, ui.dp(8), 0);
        row.addView(text, Ui.weighted());
        row.addView(ui.text(action, 15, ui.accent, true));
        row.setVisibility(View.GONE);
        parent.addView(row, Ui.matchWidth());
        return row;
    }

    /**
     * @param besideDasher split with Dasher seen in the other half
     * @param dasherInstalled whether Dasher is installed, as the page last asked Android
     */
    void refresh(boolean besideDasher, boolean dasherInstalled) {
        boolean split = DasherSplit.inSplit(activity);
        // Once ever: the first page beside Dasher shows it, and it is never shown again.
        if (split && besideDasher && !hintUp && pageShown() && !hintShown(activity)) {
            hintUp = true;
            markHintShown(activity);
            hint.removeCallbacks(hideHint);
            hint.postDelayed(hideHint, HINT_MS);
            DiagnosticLog.log(activity, "split", "divider hint shown (once)");
        }
        noteWanted = noteWanted(activity, split, besideDasher, dasherInstalled);
        pageChanged();
    }

    /** The page was swapped (Settings, the notice): the hint lies only over the main page. */
    void pageChanged() {
        showHint(hintUp && pageShown() && DasherSplit.inSplit(activity));
        show(noteRow, noteWanted);
    }

    private void hideHint() {
        hintUp = false;
        showHint(false);
    }

    /**
     * The hint up or away. Only while it is up is it a polite live region, so TalkBack reads it as it appears; hidden,
     * it is none, and the page holds no live region that could speak by itself (as Autopilot's quiet rule asks: nothing
     * on the page announces itself while the user drives).
     */
    private void showHint(boolean shown) {
        if (shown == (hint.getVisibility() == View.VISIBLE)) return;
        if (shown) {
            hint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            hint.setVisibility(View.VISIBLE);
        } else {
            hint.setVisibility(View.GONE);
            hint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        }
    }

    private boolean pageShown() {
        return page.getVisibility() == View.VISIBLE;
    }

    private static void show(View view, boolean shown) {
        int visibility = shown ? View.VISIBLE : View.GONE;
        if (view.getVisibility() != visibility) view.setVisibility(visibility);
    }

    /** Split beside another app (not Dasher) during a dash, with Peek on and Dasher installed. */
    static boolean noteWanted(Activity activity, boolean split, boolean besideDasher, boolean dasherInstalled) {
        return split && !besideDasher && dasherInstalled && FilterStore.peek(activity) && Dashing.now(activity);
    }

    boolean hintShowing() {
        return hint.getVisibility() == View.VISIBLE;
    }

    boolean noteShowing() {
        return noteRow.getVisibility() == View.VISIBLE;
    }

    static boolean hintShown(Context context) {
        return prefs(context).getBoolean(HINT_SHOWN, false);
    }

    /** The strip was reached, or the hint shown: it is never shown again. */
    static void markHintShown(Context context) {
        if (!hintShown(context)) prefs(context).edit().putBoolean(HINT_SHOWN, true).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
