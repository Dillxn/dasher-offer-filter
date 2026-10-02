package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Place names for the homepage's scene (the neighbourhood you are in, and near the best areas), from the phone's own
 * place lookup: Android's geocoder, which on most phones asks Google's servers. Only approximate positions are asked
 * about, rounded to about half a kilometre, once while cached. At most 300 names are kept on the phone. Off only when
 * {@link #setEnabled} turns it off (no switch; the default is on): then nothing is asked and no names show.
 */
final class Places {
    /** Degrees a position is rounded to before it is asked about: about half a kilometre. */
    static final double ROUNDING = 0.005;
    static final int MAX_NAMES = 300;
    private static final int MAX_PENDING = 8;
    private static final String PREFS = "places";
    private static final String ENABLED = "enabled";
    private static final String NAME = "name:";
    private static final String ORDER = "recent_names";

    /** Asks the phone's place lookup about one rounded position; replaced only by tests. */
    interface Lookup {
        List<Address> at(Context context, double latitude, double longitude) throws IOException;
    }

    @SuppressWarnings("deprecation")
    static final Lookup GEOCODER = (context, latitude, longitude) ->
            new Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1);
    static volatile Lookup lookup = GEOCODER;

    private static final ExecutorService LOOKUP = Executors.newSingleThreadExecutor();
    private static final Object LOCK = new Object();
    private static final Map<String, Long> ASKING = new HashMap<>();
    private static final LinkedHashMap<String, String> CACHE = new LinkedHashMap<>(16, 0.75f, true);
    private static SharedPreferences loaded;
    private static long generation;
    private static volatile long version;

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** On unless {@link #setEnabled} turned it off. */
    static boolean enabled(Context context) {
        return prefs(context).getBoolean(ENABLED, true);
    }

    static void setEnabled(Context context, boolean on) {
        synchronized (LOCK) {
            prefs(context).edit().putBoolean(ENABLED, on).apply();
            if (!on) {
                generation++;
                ASKING.clear();
            }
            version++;
        }
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
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || Math.abs(latitude) > 90
                || Math.abs(longitude) > 180) return null;
        String key = key(latitude, longitude);
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(app);
            if (!prefs.getBoolean(ENABLED, true)) return null;
            load(prefs);
            String name = CACHE.get(key);
            if (name != null) return name.isEmpty() ? null : name;
            if (!Geocoder.isPresent() || ASKING.containsKey(key) || ASKING.size() >= MAX_PENDING) return null;
            long request = generation;
            ASKING.put(key, request);
            LOOKUP.execute(() -> lookUp(app, key, request));
        }
        return null;
    }

    private static void lookUp(Context context, String key, long request) {
        synchronized (LOCK) {
            if (!current(context, key, request)) return;
        }
        String[] parts = key.split(",");
        double latitude = Double.parseDouble(parts[0]);
        double longitude = Double.parseDouble(parts[1]);
        String name = null;
        try {
            List<Address> found = lookup.at(context, latitude, longitude);
            if (found != null && !found.isEmpty()) name = nameOf(found.get(0));
        } catch (IOException | RuntimeException unavailable) {
            // No service or no connection: asked again next time, not remembered as nameless.
            synchronized (LOCK) {
                if (current(context, key, request)) ASKING.remove(key);
            }
            return;
        }
        synchronized (LOCK) {
            // Clear history or switching names off also invalidates a geocoder call already in flight.
            if (!current(context, key, request)) return;
            CACHE.put(key, name == null ? "" : name);
            trim();
            persist(prefs(context));
            ASKING.remove(key);
            version++;
        }
    }

    private static boolean current(Context context, String key, long request) {
        return request == generation && Long.valueOf(request).equals(ASKING.get(key))
                && prefs(context).getBoolean(ENABLED, true);
    }

    /** Load and bound an older unbounded store once. Cache hits never scan preferences or write to disk. */
    private static void load(SharedPreferences prefs) {
        if (loaded == prefs) return;
        generation++;
        ASKING.clear();
        CACHE.clear();
        loaded = prefs;
        Map<String, ?> all = prefs.getAll();
        List<String> older = new ArrayList<>();
        for (String key : all.keySet()) if (key.startsWith(NAME) && all.get(key) instanceof String) {
            older.add(key.substring(NAME.length()));
        }
        Collections.sort(older);
        for (String key : older) CACHE.put(key, (String) all.get(NAME + key));
        Object order = all.get(ORDER);
        if (order instanceof String) {
            for (String key : ((String) order).split(";")) if (CACHE.containsKey(key)) CACHE.get(key);
        }
        trim();
        persist(prefs);
    }

    private static void trim() {
        while (CACHE.size() > MAX_NAMES) CACHE.remove(CACHE.keySet().iterator().next());
    }

    /** Persist recent-use order when a name arrives; repeat homepage reads stay entirely in memory. */
    private static void persist(SharedPreferences prefs) {
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : prefs.getAll().keySet()) if (key.startsWith(NAME)
                && !CACHE.containsKey(key.substring(NAME.length()))) edit.remove(key);
        for (Map.Entry<String, String> entry : CACHE.entrySet()) edit.putString(NAME + entry.getKey(), entry.getValue());
        edit.putString(ORDER, String.join(";", CACHE.keySet())).apply();
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

    /** Forgets every cached position/name and pending result, keeping whether names are on. */
    static void forget(Context context) {
        synchronized (LOCK) {
            generation++;
            ASKING.clear();
            CACHE.clear();
            loaded = null;
            SharedPreferences prefs = prefs(context);
            SharedPreferences.Editor edit = prefs.edit().remove(ORDER);
            for (String key : prefs.getAll().keySet()) if (key.startsWith(NAME)) edit.remove(key);
            edit.apply();
            version++;
        }
    }

    private Places() {}
}
