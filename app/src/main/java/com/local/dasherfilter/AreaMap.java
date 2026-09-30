package com.local.dasherfilter;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Where offers come in, and what they pay there. Opt-in: while on, each new standalone offer with a known pay is
 * added to the square of about 2 km that the phone was in when it appeared. The position comes from the newest fix
 * the phone already has (no GPS is switched on for this), is used only if it is under ten minutes old, and is
 * rounded to its square before it is kept. Nothing here leaves the phone: squares are not in reports, and the map
 * is drawn without map tiles. A square is where you were when an offer came in, not where its pickup is.
 */
final class AreaMap {
    /** Squares are this many degrees of latitude and longitude: about 2.2 km north-south. */
    static final double CELL_DEGREES = 0.02;
    /** A square needs this many offers with miles before it is ranked. */
    static final int MIN_OFFERS = 3;
    static final int MAX_CELLS = 300;
    static final long FRESH_MS = 10 * 60_000L;
    /** The same offer seen from its notification and then on screen within this long counts once. */
    static final long SAME_OFFER_MS = 3 * 60_000L;
    private static final float WORST_ACCURACY_METERS = 5000;
    private static final double MILES_PER_DEGREE_LATITUDE = 69.05;

    private static final String PREFS = "offer_filter_areas";
    private static final String ENABLED = "enabled";
    private static final String CELLS = "cells";
    private static final String UNLOCATED = "unlocated";
    private static final String LAST_OFFER = "last_offer";
    private static final String LAST_OFFER_AT = "last_offer_at";
    private static final Object LOCK = new Object();
    private static List<Cell> cells;
    private static volatile long version;

    private AreaMap() {}

    /** One square: its offers, their pay, and the pay and miles of those whose miles were read. */
    static final class Cell {
        final int row;
        final int col;
        int offers;
        long payCents;
        int mileOffers;
        long milePayCents;
        double miles;
        int bestPayCents;
        long lastAt;

        Cell(int row, int col) {
            this.row = row;
            this.col = col;
        }

        Cell copy() {
            Cell copy = new Cell(row, col);
            copy.offers = offers;
            copy.payCents = payCents;
            copy.mileOffers = mileOffers;
            copy.milePayCents = milePayCents;
            copy.miles = miles;
            copy.bestPayCents = bestPayCents;
            copy.lastAt = lastAt;
            return copy;
        }

        double latitude() {
            return (row + 0.5) * CELL_DEGREES;
        }

        double longitude() {
            return (col + 0.5) * CELL_DEGREES;
        }

        boolean ranked() {
            return mileOffers >= MIN_OFFERS && miles > 0;
        }

        /** Pooled pay per mile: all pay over all miles, so one short trip cannot dominate. */
        double centsPerMile() {
            return miles > 0 ? milePayCents / miles : 0;
        }

        long averagePayCents() {
            return offers == 0 ? 0 : Math.round((double) payCents / offers);
        }

        String perMile() {
            return String.format(Locale.US, "$%.2f/mi", centsPerMile() / 100);
        }
    }

    // ---- Settings and permissions ----

    static boolean enabled(Context context) {
        // On unless turned off: the homepage's ground is the map. Nothing is kept without location permission.
        return prefs(context).getBoolean(ENABLED, true);
    }

    static void setEnabled(Context context, boolean on) {
        prefs(context).edit().putBoolean(ENABLED, on).apply();
        version++;
    }

    /** Approximate location is all this needs; precise is not asked for. */
    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Android 10+ may withhold location while another app is in front unless it is allowed all the time. */
    static boolean hasBackgroundPermission(Context context) {
        return Build.VERSION.SDK_INT < 29 || context.checkSelfPermission(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Offers noted while on but without a fresh location, so the screen can say so rather than guess. */
    static int unlocated(Context context) {
        return prefs(context).getInt(UNLOCATED, 0);
    }

    static long version() {
        return version;
    }

    // ---- Recording ----

    /**
     * Adds a newly recorded offer to the square the phone is in. Add-ons, offers without a pay, and the second
     * sighting of the same offer are skipped; with no fresh location the offer is only counted as unlocated.
     */
    static void note(Context context, DecisionLog.Entry entry) {
        try {
            if (entry.addOn || entry.facts.payCents == null || entry.facts.payCents <= 0) return;
            if (!enabled(context)) return;
            SharedPreferences prefs = prefs(context);
            String offer = entry.facts.payCents + "|" + entry.facts.miles + "|" + entry.facts.minutes + "|"
                    + entry.facts.stops;
            long now = System.currentTimeMillis();
            synchronized (LOCK) {
                long lastAt = prefs.getLong(LAST_OFFER_AT, 0);
                if (offer.equals(prefs.getString(LAST_OFFER, "")) && now - lastAt >= 0
                        && now - lastAt < SAME_OFFER_MS) {
                    return;
                }
                prefs.edit().putString(LAST_OFFER, offer).putLong(LAST_OFFER_AT, now).apply();
                double[] here = here(context);
                if (here == null) {
                    prefs.edit().putInt(UNLOCATED, prefs.getInt(UNLOCATED, 0) + 1).apply();
                    version++;
                    return;
                }
                List<Cell> all = loaded(context);
                int row = (int) Math.floor(here[0] / CELL_DEGREES);
                int col = (int) Math.floor(here[1] / CELL_DEGREES);
                Cell cell = null;
                for (Cell candidate : all) {
                    if (candidate.row == row && candidate.col == col) cell = candidate;
                }
                if (cell == null) {
                    cell = new Cell(row, col);
                    all.add(cell);
                }
                int pay = entry.facts.payCents;
                cell.offers++;
                cell.payCents += pay;
                cell.bestPayCents = Math.max(cell.bestPayCents, pay);
                cell.lastAt = now;
                Double miles = entry.facts.miles;
                if (miles != null && miles > 0 && Double.isFinite(miles)) {
                    cell.mileOffers++;
                    cell.milePayCents += pay;
                    cell.miles += miles;
                }
                while (all.size() > MAX_CELLS) all.remove(oldest(all));
                persist(context, all);
            }
        } catch (RuntimeException error) {
            DiagnosticLog.log(context, "areas", "note failed: " + error.getClass().getSimpleName());
        }
    }

    private static int oldest(List<Cell> all) {
        int oldest = 0;
        for (int i = 1; i < all.size(); i++) {
            if (all.get(i).lastAt < all.get(oldest).lastAt) oldest = i;
        }
        return oldest;
    }

    /**
     * The newest position the phone already has from any source, as {latitude, longitude}; null when there is no
     * permission, no fix under {@link #FRESH_MS} old, or only a very rough one.
     */
    static double[] here(Context context) {
        if (!hasPermission(context)) return null;
        LocationManager manager = context.getSystemService(LocationManager.class);
        if (manager == null) return null;
        Location best = null;
        List<String> providers;
        try {
            providers = manager.getAllProviders();
        } catch (RuntimeException error) {
            return null;
        }
        for (String provider : providers) {
            try {
                Location fix = manager.getLastKnownLocation(provider);
                if (fix != null && (best == null || age(fix) < age(best))) best = fix;
            } catch (SecurityException | IllegalArgumentException refused) {
                // A provider this permission level may not read, such as GPS with approximate location.
            }
        }
        if (best == null) return null;
        long age = age(best);
        if (age > FRESH_MS || age < -60_000L) return null;
        if (best.hasAccuracy() && best.getAccuracy() > WORST_ACCURACY_METERS) return null;
        double latitude = best.getLatitude();
        double longitude = best.getLongitude();
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || Math.abs(latitude) > 85
                || Math.abs(longitude) > 180 || (latitude == 0 && longitude == 0)) {
            return null;
        }
        return new double[] {latitude, longitude};
    }

    private static long age(Location fix) {
        if (fix.getElapsedRealtimeNanos() > 0) {
            return (SystemClock.elapsedRealtimeNanos() - fix.getElapsedRealtimeNanos()) / 1_000_000L;
        }
        return System.currentTimeMillis() - fix.getTime();
    }

    // ---- Reading ----

    /** Every square, as copies. */
    static List<Cell> cells(Context context) {
        synchronized (LOCK) {
            List<Cell> out = new ArrayList<>();
            for (Cell cell : loaded(context)) out.add(cell.copy());
            return out;
        }
    }

    /** Squares with enough offers, best pay per mile first (more offers first on a tie). */
    static List<Cell> ranked(List<Cell> all) {
        List<Cell> ranked = new ArrayList<>();
        for (Cell cell : all) if (cell.ranked()) ranked.add(cell);
        Collections.sort(ranked, (a, b) -> {
            int byRate = Double.compare(b.centsPerMile(), a.centsPerMile());
            return byRate != 0 ? byRate : Integer.compare(b.mileOffers, a.mileOffers);
        });
        return ranked;
    }

    static int totalOffers(List<Cell> all) {
        int total = 0;
        for (Cell cell : all) total += cell.offers;
        return total;
    }

    /** "2.1 mi NE of you", "Around you" within about a mile, or "" with no position to measure from. */
    static String from(double[] here, Cell cell) {
        double[] way = offset(here, cell);
        if (way == null) return "";
        if (way[0] < 1) return "Around you";
        return String.format(Locale.US, "%.1f mi %s of you", way[0], compassPoint(way[1]));
    }

    /** How far and which way a square's middle is from here: {miles, degrees clockwise from north}; null unknown. */
    static double[] offset(double[] here, Cell cell) {
        if (here == null) return null;
        double north = (cell.latitude() - here[0]) * MILES_PER_DEGREE_LATITUDE;
        double east = (cell.longitude() - here[1]) * MILES_PER_DEGREE_LATITUDE * Math.cos(Math.toRadians(here[0]));
        return new double[] {Math.hypot(north, east), (Math.toDegrees(Math.atan2(east, north)) + 360) % 360};
    }

    static String compassPoint(double bearing) {
        String[] points = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return points[(int) Math.round(bearing / 45) % 8];
    }

    /** Removes every square and the unlocated count. */
    static void forget(Context context) {
        synchronized (LOCK) {
            cells = new ArrayList<>();
            prefs(context).edit().remove(CELLS).remove(UNLOCATED).remove(LAST_OFFER).remove(LAST_OFFER_AT).apply();
            version++;
        }
    }

    /** One line for the diagnostic report: counts only, never a position. */
    static String summary(Context context) {
        List<Cell> all = cells(context);
        return "Offer areas: " + (enabled(context) ? "on" : "off") + "; " + all.size() + " squares; "
                + totalOffers(all) + " offers; " + unlocated(context) + " without a location; location permission "
                + (hasPermission(context) ? "granted" : "not granted") + "; all the time "
                + (hasBackgroundPermission(context) ? "granted" : "not granted");
    }

    static void forgetCache() {
        synchronized (LOCK) {
            cells = null;
        }
    }

    // ---- Storage ----

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static List<Cell> loaded(Context context) {
        if (cells != null) return cells;
        List<Cell> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(CELLS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                Cell cell = new Cell(item.getInt("r"), item.getInt("c"));
                cell.offers = Math.max(0, item.optInt("n"));
                cell.payCents = Math.max(0, item.optLong("p"));
                cell.mileOffers = Math.max(0, item.optInt("mn"));
                cell.milePayCents = Math.max(0, item.optLong("mp"));
                double miles = item.optDouble("mi", 0);
                cell.miles = Double.isFinite(miles) && miles > 0 ? miles : 0;
                cell.bestPayCents = Math.max(0, item.optInt("b"));
                cell.lastAt = item.optLong("t");
                out.add(cell);
            }
        } catch (JSONException damaged) {
            out.clear();
        }
        cells = out;
        return out;
    }

    private static void persist(Context context, List<Cell> all) {
        JSONArray array = new JSONArray();
        try {
            for (Cell cell : all) {
                array.put(new JSONObject().put("r", cell.row).put("c", cell.col).put("n", cell.offers)
                        .put("p", cell.payCents).put("mn", cell.mileOffers).put("mp", cell.milePayCents)
                        .put("mi", cell.miles).put("b", cell.bestPayCents).put("t", cell.lastAt));
            }
        } catch (JSONException impossible) {
            return;
        }
        prefs(context).edit().putString(CELLS, array.toString()).apply();
        version++;
    }
}
