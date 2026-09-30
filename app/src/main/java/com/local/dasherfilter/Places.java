package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Place names for the homepage's scene (the neighbourhood you are in, and near the best areas), from the phone's own
 * place lookup: Android's geocoder, which on most phones asks Google's servers. Only approximate positions are asked
 * about, rounded to about half a kilometre, each at most once; the names are kept on the phone. Off only when
 * {@link #setEnabled} turns it off (no switch; the default is on): then nothing is asked and no names show.
 */
final class Places {
    /** Degrees a position is rounded to before it is asked about: about half a kilometre. */
    static final double ROUNDING = 0.005;
    private static final String PREFS = "places";
    private static final String ENABLED = "enabled";
    private static final String NAME = "name:";

    /** Asks the phone's place lookup about one rounded position; replaced only by tests. */
    interface Lookup {
        List<Address> at(Context context, double latitude, double longitude) throws IOException;
    }

    @SuppressWarnings("deprecation")
    static final Lookup GEOCODER = (context, latitude, longitude) ->
            new Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1);
    static volatile Lookup lookup = GEOCODER;

    private static final ExecutorService LOOKUP = Executors.newSingleThreadExecutor();
    private static final Set<String> ASKING = new HashSet<>();
    private static volatile long version;

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** On unless {@link #setEnabled} turned it off. */
    static boolean enabled(Context context) {
        return prefs(context).getBoolean(ENABLED, true);
    }

    static void setEnabled(Context context, boolean on) {
        prefs(context).edit().putBoolean(ENABLED, on).apply();
        version++;
    }

    /** Changes whenever a name arrives, so the screen redraws. */
    static long version() {
        return version;
    }

    /**
     * The name of the place around {@code latitude}, {@code longitude}: its neighbourhood, else its street, else its
     * town. Null while unknown; the first ask starts a lookup in the background.
     */
    static String name(Context context, double latitude, double longitude) {
        if (!enabled(context)) return null;
        String key = key(latitude, longitude);
        SharedPreferences prefs = prefs(context);
        if (prefs.contains(NAME + key)) {
            String name = prefs.getString(NAME + key, "");
            return name.isEmpty() ? null : name;
        }
        if (!Geocoder.isPresent()) return null;
        Context app = context.getApplicationContext();
        synchronized (ASKING) {
            if (!ASKING.add(key)) return null;
        }
        LOOKUP.execute(() -> lookUp(app, key));
        return null;
    }

    private static void lookUp(Context context, String key) {
        String[] parts = key.split(",");
        double latitude = Double.parseDouble(parts[0]);
        double longitude = Double.parseDouble(parts[1]);
        String name = null;
        try {
            List<Address> found = lookup.at(context, latitude, longitude);
            if (found != null && !found.isEmpty()) name = nameOf(found.get(0));
        } catch (IOException | RuntimeException unavailable) {
            // No service or no connection: asked again next time, not remembered as nameless.
            synchronized (ASKING) {
                ASKING.remove(key);
            }
            return;
        }
        prefs(context).edit().putString(NAME + key, name == null ? "" : name).apply();
        synchronized (ASKING) {
            ASKING.remove(key);
        }
        version++;
    }

    /** A neighbourhood reads best in a picture; then a street, then a town. */
    static String nameOf(Address address) {
        for (String candidate : new String[] {address.getSubLocality(), address.getThoroughfare(),
                address.getLocality()}) {
            if (candidate != null && !candidate.trim().isEmpty()) return candidate.trim();
        }
        return null;
    }

    static String key(double latitude, double longitude) {
        return String.format(Locale.US, "%.3f,%.3f", Math.round(latitude / ROUNDING) * ROUNDING,
                Math.round(longitude / ROUNDING) * ROUNDING);
    }

    /** Forgets every name (keeping whether names are on), for tests and when place names are turned off. */
    static void forget(Context context) {
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : prefs.getAll().keySet()) if (key.startsWith(NAME)) edit.remove(key);
        edit.apply();
        version++;
    }

    private Places() {}
}
