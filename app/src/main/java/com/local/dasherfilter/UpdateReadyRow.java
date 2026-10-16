package com.local.dasherfilter;

import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * The homepage's "Update ready" line: a verified update an automatic check held back (for a dash whose end was never
 * seen, a declined install, or updates from this app not allowed yet), while no dash is on (nothing of one seen in the
 * last half hour, no route, no offer tracked). With updates allowed its tap, Install now, is the user's own check: the
 * fresh feed and every APK check run again, and Android asks to confirm. Without, its tap, Allow updates, opens
 * Android's switch for them ("Install unknown apps"): from 0.6.26 that switch is asked for only once an update is
 * ready, since until then it does nothing (the owner: "all ui on screen needs to be justified by what is currently
 * functional"). It never installs anything by itself.
 */
final class UpdateReadyRow {
    static final String WORDS = "Update ready";
    static final String ACTION = "Install now";
    static final String ALLOW = "Allow updates";

    private final Activity activity;
    private final SetupRow row;
    private boolean allowed;

    UpdateReadyRow(Activity activity, Ui ui, LinearLayout parent) {
        this.activity = activity;
        row = new SetupRow(activity, ui, parent, this::tapped);
    }

    /** Shown while a held update waits and no dash is on; {@code installsAllowed} as the page asked Android lately. */
    void refresh(boolean installsAllowed) {
        allowed = installsAllowed;
        boolean show = Updater.heldVersion(activity) != null && !Updater.installing(activity) && !dashOn(activity);
        if (show) row.show(SetupRow.Mark.UPDATE, 0, WORDS, installsAllowed ? ACTION : ALLOW);
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

    private void tapped() {
        if (allowed) install();
        else allowUpdates();
    }

    /** Android's switch for updates from this app, its name in a toast; back with it on, the line says Install now. */
    private void allowUpdates() {
        DiagnosticLog.log(activity, "update", "Allow updates tapped on the homepage for held "
                + Updater.heldVersion(activity));
        try {
            activity.startActivity(UpdateNotices.allowUpdates(activity));
            Toast.makeText(activity, SetupFlow.ALLOW_SOURCE, Toast.LENGTH_LONG).show();
        } catch (RuntimeException error) {
            DiagnosticLog.log(activity, "update", "Android could not open Install unknown apps: "
                    + error.getClass().getSimpleName());
            Toast.makeText(activity, "Android couldn't open that screen.", Toast.LENGTH_LONG).show();
        }
    }

    private void install() {
        DiagnosticLog.log(activity, "update", "Install now tapped on the homepage for held "
                + Updater.heldVersion(activity));
        Toast.makeText(activity, "Getting the update ready…", Toast.LENGTH_SHORT).show();
        Updater.check(activity, true, null);
    }
}
