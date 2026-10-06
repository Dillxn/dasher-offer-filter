package com.local.dasherfilter;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Over everything while an update installs: it says so and takes no input (Back included) until the new version
 * opens. Android's result can fail to arrive (an OEM quirk, a lost broadcast), which would hold the screen for the
 * whole ten-minute install window, so after {@link #ESCAPE_AFTER_MS} a "Still updating? Continue" button gives the
 * screen back. Continuing only lifts the cover (for this installation, in this process); the installation itself is
 * left to Android, and if it finishes the new version opens as usual.
 */
final class UpdatingCover {
    static final long ESCAPE_AFTER_MS = 60_000;
    static final String ESCAPE = "Still updating? Continue";
    /** The installation (its hand-over time) the user continued past; a recreated screen does not cover again. */
    private static long continuedPast;

    private final Context context;
    private final LinearLayout cover;
    private final Button escape;
    private long shownFor;

    UpdatingCover(Activity activity, Ui ui, FrameLayout root) {
        context = activity;
        cover = ui.column();
        cover.setGravity(Gravity.CENTER);
        cover.setBackgroundColor(ui.page);
        cover.setVisibility(View.GONE);
        // Clickable: every touch lands here and goes no further.
        cover.setClickable(true);
        cover.setFocusable(true);
        cover.addView(new UpdatingView(activity, ui), Ui.matchWidth());
        TextView title = ui.text("Updating " + AppName.NAME + "…", 20, ui.ink, true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, ui.dp(12), 0, 0);
        cover.addView(title, Ui.matchWidth());
        TextView note = ui.text("It opens again by itself in a moment.", 15, ui.inkSecondary, false);
        note.setGravity(Gravity.CENTER_HORIZONTAL);
        note.setPadding(ui.dp(24), ui.dp(6), ui.dp(24), 0);
        cover.addView(note, Ui.matchWidth());
        escape = ui.button(ESCAPE, false, this::continuePast);
        escape.setVisibility(View.GONE);
        LinearLayout.LayoutParams escapeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        escapeParams.gravity = Gravity.CENTER_HORIZONTAL;
        escapeParams.topMargin = ui.dp(24);
        cover.addView(escape, escapeParams);
        cover.setContentDescription("Updating " + AppName.NAME + ". It opens again by itself in a moment.");
        root.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Whether the cover is up (Back then does nothing). */
    boolean shown() {
        return cover.getVisibility() == View.VISIBLE;
    }

    /** Follows the updater: up while an installation is under way, its way out after a minute. */
    void refresh() {
        long since = Updater.installingSince(context);
        boolean updating = since > 0 && since != continuedPast;
        if (updating != shown()) {
            cover.setVisibility(updating ? View.VISIBLE : View.GONE);
            if (updating) {
                cover.setAlpha(0f);
                cover.animate().alpha(1f).setDuration(250);
                cover.announceForAccessibility("Updating " + AppName.NAME);
            }
        }
        shownFor = updating ? since : 0;
        long age = System.currentTimeMillis() - since;
        boolean late = updating && (age >= ESCAPE_AFTER_MS || age < 0);
        if (late != (escape.getVisibility() == View.VISIBLE)) {
            escape.setVisibility(late ? View.VISIBLE : View.GONE);
            if (late) cover.announceForAccessibility(ESCAPE);
        }
    }

    private void continuePast() {
        if (shownFor <= 0) return;
        continuedPast = shownFor;
        long seconds = Math.max(0, (System.currentTimeMillis() - shownFor) / 1000);
        DiagnosticLog.log(context, "update", "updating cover lifted by the user after " + seconds
                + " s with no install result; Android may still finish the installation");
        refresh();
    }

    /** For tests: a new process, which covers any installation again. */
    static void forget() {
        continuedPast = 0;
    }
}
