package com.local.dasherfilter;

import android.app.Activity;
import android.app.AlertDialog;

/**
 * One line of why before Android's "Allow all the time" location request for the offer map (BETA-28). Asked only from
 * the map's own Fix row in Settings, once offers came in without a location, never at startup; "Not now" asks
 * nothing. The map works without it while Offer Filter is open.
 */
final class LocationRationale {
    static final String TITLE = "Allow location all the time?";
    static final String TEXT = "So offers that come in while you're in Dasher still land on the offer map. On the next "
            + "screen, choose Allow all the time.";

    private LocationRationale() {}

    /** The reason first; {@code request} (Android's own request) only after Continue. */
    static void ask(Activity activity, Runnable request) {
        OwnWindowTouches.show(new AlertDialog.Builder(activity)
                .setTitle(TITLE)
                .setMessage(TEXT)
                .setPositiveButton("Continue", (dialog, which) -> request.run())
                .setNegativeButton("Not now", null));
    }
}
