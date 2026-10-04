package com.local.dasherfilter;

import android.content.Context;
import android.content.res.Configuration;
import java.util.TimeZone;

/**
 * One theme choice for the app's own screens. Auto follows the local sun when an already-authorized recent
 * location is available. No fix is requested, location stored or network request made for the theme.
 */
final class Appearance {
    private static final String PREFS = "appearance";
    private static final String NIGHT = "night";
    private static final String MODE = "mode";

    enum Mode {
        DAY("Day"), NIGHT("Night"), SYSTEM("System"), AUTO("Auto");

        final String label;
        Mode(String label) { this.label = label; }

        Mode next() {
            switch (this) {
                case DAY: return NIGHT;
                case NIGHT: return SYSTEM;
                case SYSTEM: return AUTO;
                default: return DAY;
            }
        }
    }

    /** Fresh/default installs use Auto; preserve an explicit day/night choice made in older versions. */
    static Mode mode(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.contains(MODE)) {
            try {
                return Mode.valueOf(prefs.getString(MODE, "AUTO"));
            } catch (IllegalArgumentException | NullPointerException invalid) {
                return Mode.AUTO;
            }
        }
        return prefs.contains(NIGHT) ? (prefs.getBoolean(NIGHT, false) ? Mode.NIGHT : Mode.DAY) : Mode.AUTO;
    }

    static boolean choose(Context context, Mode mode) {
        if (mode == null) throw new IllegalArgumentException("theme mode is required");
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(MODE, mode.name()).remove(NIGHT).commit();
    }

    /** Compatibility for callers asking for an explicit day/night override, not Auto's current result. */
    static Boolean chosen(Context context) {
        Mode mode = mode(context);
        return mode == Mode.NIGHT ? Boolean.TRUE : mode == Mode.DAY ? Boolean.FALSE : null;
    }

    static void choose(Context context, boolean night) {
        choose(context, night ? Mode.NIGHT : Mode.DAY);
    }

    static final class State {
        final Mode mode;
        final boolean night;
        final boolean clockFallback;

        State(Mode mode, boolean night, boolean clockFallback) {
            this.mode = mode;
            this.night = night;
            this.clockFallback = clockFallback;
        }

        String description() {
            String basis = mode == Mode.AUTO ? (clockFallback ? "local clock fallback, day 6 am to 6 pm"
                    : "estimated local sunrise and sunset") : mode == Mode.SYSTEM ? "follows Android" : "fixed";
            return "Theme: " + mode.label + ", " + (night ? "night" : "day") + ", " + basis
                    + ". Tap for " + mode.next().label + ".";
        }
    }

    static State resolve(Context context) {
        return resolve(context, System.currentTimeMillis());
    }

    static State resolve(Context context, long at) {
        Mode mode = mode(context);
        if (mode == Mode.DAY || mode == Mode.NIGHT) return new State(mode, mode == Mode.NIGHT, false);
        if (mode == Mode.SYSTEM) {
            Context app = context.getApplicationContext();
            if (app == null) app = context;
            int config = app.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return new State(mode, config == Configuration.UI_MODE_NIGHT_YES, false);
        }
        // The map's existing consent, switch, permission and freshness rules own access to this cached fix.
        // Round before calculation; do not retain even this coarse position in preferences or diagnostics.
        if (Consent.accepted(context) && AreaMap.enabled(context)) {
            try {
                double[] here = AreaMap.here(context);
                if (here != null) {
                    Boolean night = SolarCycle.night(at, Math.rint(here[0] * 10) / 10,
                            Math.rint(here[1] * 10) / 10);
                    if (night != null) return new State(mode, night, false);
                }
            } catch (RuntimeException unavailable) {
                // A refused/failed Android location service is equivalent to having no current cached fix.
            }
        }
        return new State(mode, SolarCycle.clockNight(at, TimeZone.getDefault()), true);
    }

    static String explanation(Context context) {
        State state = resolve(context);
        return "Auto estimates sunrise and sunset on this phone from the offer map's recent approximate location. "
                + "Without one, it uses 6 am–6 pm local time (" + (state.clockFallback ? "in use now" : "fallback")
                + "). It requests no new fix and sends nothing. System follows Android.";
    }

    /** The configuration that shows the chosen appearance, or null when the phone's own applies. */
    static Configuration override(Context base) {
        State state = resolve(base);
        if (state.mode == Mode.SYSTEM) return null;
        Configuration config = new Configuration();
        int others = base.getResources().getConfiguration().uiMode & ~Configuration.UI_MODE_NIGHT_MASK;
        config.uiMode = others | (state.night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return config;
    }

    private Appearance() {}
}
