package com.local.dasherfilter;

import android.content.Context;
import android.content.res.Configuration;

/**
 * Day or night for Offer Filter's own screens, chosen by tapping the sun or the moon on the main page. Until the
 * first tap it follows the phone's dark mode.
 */
final class Appearance {
    private static final String PREFS = "appearance";
    private static final String NIGHT = "night";

    /** True for night, false for day, null to follow the phone. */
    static Boolean chosen(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.contains(NIGHT) ? prefs.getBoolean(NIGHT, false) : null;
    }

    static void choose(Context context, boolean night) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(NIGHT, night).commit();
    }

    /** The configuration that shows the chosen appearance, or null when the phone's own applies. */
    static Configuration override(Context base) {
        Boolean night = chosen(base);
        if (night == null) return null;
        Configuration config = new Configuration();
        int others = base.getResources().getConfiguration().uiMode & ~Configuration.UI_MODE_NIGHT_MASK;
        config.uiMode = others | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return config;
    }

    private Appearance() {}
}
