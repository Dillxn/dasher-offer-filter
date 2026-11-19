package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Accepted route context, not a claim to know current GPS or remaining driving time. Saved when an acceptance is seen,
 * and from 0.6.53 also worked out from the decision history: the newest accepted line's route (an add-on's whole route
 * after it, or a standalone offer's own figures) stands in when its acceptance was never saved here, unless the route
 * was cleared or its travel made stale after it (the owner, 19 November 2026: "data driven and declarative, not event
 * driven").
 */
final class ActiveRouteStore {
    private static final String PREFS = "active_route";
    private static final long MAX_AGE_MS = 10_800_000L;
    private static final String SAVED_AT = "at";
    private static final String PAY = "pay";
    private static final String MILES = "miles";
    private static final String MINUTES = "minutes";
    private static final String STOPS = "stops";
    private static final String ITEMS = "items";
    private static final String ITEM_APPLICABLE = "item_count_applicable";
    /** When the route was last cleared (a delivery's end, the dash's): an acceptance before it is no route (0.6.53). */
    private static final String CLEARED_AT = "cleared_at";
    /** When delivery progress made the travel stale: an acceptance before it keeps its pay only (0.6.53). */
    private static final String TRAVEL_STALE_AT = "travel_stale_at";

    /** The newest accepted line's route and acceptance time, for the history version it was worked out from. */
    private static long derivedVersion = Long.MIN_VALUE;
    private static long derivedAt = Long.MIN_VALUE;
    private static OfferSnapshot derivedRoute;

    static void save(Context context, OfferSnapshot route) {
        if (route == null) {
            clear(context);
            return;
        }
        SharedPreferences.Editor editor = prefs(context).edit().clear().putLong(SAVED_AT, System.currentTimeMillis());
        if (route.payCents != null) editor.putInt(PAY, route.payCents);
        if (route.miles != null) editor.putLong(MILES, Double.doubleToLongBits(route.miles));
        if (route.minutes != null) editor.putInt(MINUTES, route.minutes);
        if (route.stops != null) editor.putInt(STOPS, route.stops);
        if (route.items != null) editor.putInt(ITEMS, route.items);
        if (route.itemCountApplicable) editor.putBoolean(ITEM_APPLICABLE, true);
        editor.apply();
    }

    /**
     * The route: the newest accepted line's from history when that acceptance is newer than anything saved, cleared or
     * made stale here, else the saved one; null when there is none, it is over three hours old, or the clock moved
     * backwards.
     */
    static OfferSnapshot load(Context context) {
        SharedPreferences prefs = prefs(context);
        long savedAt = prefs.getLong(SAVED_AT, 0);
        OfferSnapshot fromHistory = fromHistory(context, prefs, savedAt);
        if (fromHistory != null) return fromHistory;
        if (savedAt == 0) return null;
        long age = System.currentTimeMillis() - savedAt;
        if (age < 0 || age > MAX_AGE_MS) {
            clear(context);
            return null;
        }
        return new OfferSnapshot(
                prefs.contains(PAY) ? prefs.getInt(PAY, 0) : null,
                prefs.contains(MILES) ? Double.longBitsToDouble(prefs.getLong(MILES, 0)) : null,
                prefs.contains(MINUTES) ? prefs.getInt(MINUTES, 0) : null,
                prefs.contains(STOPS) ? prefs.getInt(STOPS, 0) : null)
                .withItems(prefs.contains(ITEMS) ? prefs.getInt(ITEMS, 0) : null,
                        prefs.getBoolean(ITEM_APPLICABLE, false));
    }

    /** Progress makes stored travel, stop and item totals stale; pay and observed item applicability stay. */
    static void invalidateTravel(Context context) {
        SharedPreferences prefs = prefs(context);
        boolean derived = fromHistory(context, prefs, prefs.getLong(SAVED_AT, 0)) != null;
        if (!prefs.contains(SAVED_AT) && !derived) return;
        prefs.edit().remove(MILES).remove(MINUTES).remove(STOPS).remove(ITEMS)
                .putLong(TRAVEL_STALE_AT, System.currentTimeMillis()).apply();
        DiagnosticLog.log(context, "route", "delivery progress observed; old travel/stop/item estimates invalidated");
    }

    static void clear(Context context) {
        SharedPreferences prefs = prefs(context);
        boolean derived = fromHistory(context, prefs, prefs.getLong(SAVED_AT, 0)) != null;
        if (prefs.contains(SAVED_AT) || derived) {
            prefs.edit().clear().putLong(CLEARED_AT, System.currentTimeMillis()).apply();
        }
    }

    /**
     * The newest accepted line's route when its acceptance is newer than the saved route, the last clear and three hours
     * ago; its travel left out when progress made travel stale after it. Null otherwise.
     */
    private static OfferSnapshot fromHistory(Context context, SharedPreferences prefs, long savedAt) {
        refresh(context);
        OfferSnapshot route;
        long at;
        synchronized (ActiveRouteStore.class) {
            route = derivedRoute;
            at = derivedAt;
        }
        long age = System.currentTimeMillis() - at;
        if (route == null || at <= savedAt || at <= prefs.getLong(CLEARED_AT, 0) || age < 0 || age > MAX_AGE_MS) {
            return null;
        }
        if (prefs.getLong(TRAVEL_STALE_AT, 0) > at) {
            return new OfferSnapshot(route.payCents, null, null, null).withItems(null, route.itemCountApplicable);
        }
        return route;
    }

    /** Works the newest accepted line's route out again when the history changed (at most 200 lines). */
    private static void refresh(Context context) {
        long version = DecisionLog.version();
        synchronized (ActiveRouteStore.class) {
            if (version == derivedVersion) return;
        }
        long at = Long.MIN_VALUE;
        OfferSnapshot route = null;
        for (DecisionLog.Entry entry : DecisionLog.recent(context, DecisionLog.MAX_ENTRIES)) {
            long accepted = DecisionLog.acceptedAt(entry);
            OfferSnapshot after = entry.addOn ? entry.routeAfter : entry.facts;
            if (accepted > at && after != null) {
                at = accepted;
                route = after;
            }
        }
        synchronized (ActiveRouteStore.class) {
            derivedVersion = version;
            derivedAt = at;
            derivedRoute = route;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private ActiveRouteStore() {}
}
