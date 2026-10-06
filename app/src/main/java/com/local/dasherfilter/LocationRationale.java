package com.local.dasherfilter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;
import java.util.function.Consumer;

/**
 * One line of why before Android's "Allow all the time" location request for the offer map (BETA-28). Asked only from
 * the map's own Fix row in Settings, once offers came in without a location, never at startup; "Not now" asks
 * nothing. The map works without it while Offer Filter is open. Continue never leads nowhere: once Android will not
 * show its request again (asked before, and no rationale left to give), App info opens instead, with the path to the
 * switch; a refusal says where the switch is for later.
 */
final class LocationRationale {
    static final String TITLE = "Allow location all the time?";
    static final String TEXT = "So offers that come in while you're in Dasher still land on the offer map. On the next "
            + "screen, choose Allow all the time.";
    /** Where the switch is once Android will not ask again, or after a refusal. */
    static final String PATH = "In App info: Permissions → Location → Allow all the time.";
    /** In SetupChecklist.PREFS: Android was asked for background location from here once. */
    static final String ASKED = "background_location_asked";

    private LocationRationale() {}

    /** The reason first; {@code request} (Android's own request) only after Continue, or App info where it cannot. */
    static void ask(Activity activity, Runnable request, Consumer<Intent> open) {
        OwnWindowTouches.show(new AlertDialog.Builder(activity)
                .setTitle(TITLE)
                .setMessage(TEXT)
                .setPositiveButton("Continue", (dialog, which) -> proceed(activity, request, open))
                .setNegativeButton("Not now", null));
    }

    /** Android's request while it will still show one; otherwise App info, where the switch is. */
    static void proceed(Activity activity, Runnable request, Consumer<Intent> open) {
        SharedPreferences prefs = prefs(activity);
        if (!prefs.getBoolean(ASKED, false)
                || activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            prefs.edit().putBoolean(ASKED, true).apply();
            request.run();
            return;
        }
        DiagnosticLog.log(activity, "location", "Android will not ask for all-the-time location again; App info opens");
        Toast.makeText(activity, PATH, Toast.LENGTH_LONG).show();
        open.accept(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.getPackageName())));
    }

    /** Android's answer: a refusal says where the switch is (the next Continue opens App info if Android won't ask). */
    static void answered(Activity activity, boolean granted) {
        if (!granted) Toast.makeText(activity, "Not allowed. " + PATH, Toast.LENGTH_LONG).show();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(SetupChecklist.PREFS, Context.MODE_PRIVATE);
    }
}
