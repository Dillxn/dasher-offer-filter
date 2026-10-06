package com.local.dasherfilter;

import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * The homepage's "Update ready · Install now" line: a verified update an automatic check held back (for a dash whose
 * end was never seen, or a declined install), while no dash is on (nothing of one seen in the last half hour, no
 * route, no offer tracked) and updates from this app are allowed (otherwise the setup checklist's Allow updates step
 * shows instead). Its tap is the user's own check: the fresh feed and every APK check run again, and Android asks to
 * confirm. It never installs anything by itself.
 */
final class UpdateReadyRow {
    static final String WORDS = "Update ready";
    static final String ACTION = "Install now";

    private final Activity activity;
    private final SetupRow row;

    UpdateReadyRow(Activity activity, Ui ui, LinearLayout parent) {
        this.activity = activity;
        row = new SetupRow(activity, ui, parent, this::install);
    }

    /** Shown while a held update waits and no dash is on; {@code installsAllowed} as the setup checklist asked. */
    void refresh(boolean installsAllowed) {
        boolean show = Updater.heldVersion(activity) != null && installsAllowed && !Updater.installing(activity)
                && !dashOn(activity);
        if (show) row.show(SetupRow.Mark.UPDATE, 0, WORDS, ACTION);
        else row.hide();
    }

    boolean shown() {
        return row.shown();
    }

    /** Anything of a dash under way: the line waits, as the automatic install does. */
    static boolean dashOn(android.content.Context context) {
        return Dashing.on(context) || ActiveRouteStore.load(context) != null
                || OfferNotificationService.hasActiveOffer();
    }

    private void install() {
        DiagnosticLog.log(activity, "update", "Install now tapped on the homepage for held "
                + Updater.heldVersion(activity));
        Toast.makeText(activity, "Getting the update ready…", Toast.LENGTH_SHORT).show();
        Updater.check(activity, true, null);
    }
}
