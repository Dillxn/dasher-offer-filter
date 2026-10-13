package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;

/**
 * The stores the user skips (0.6.10; the #DeclineNow study's store skip list, the owner: "yeah I want all its
 * recommendations"). A store is added from its offer's ticket ("Skip DiBella's Subs from now on") and removed there
 * ("Stop skipping …") or in Settings → Skipped stores. A standalone offer with any of its stores on the list is declined
 * ({@link OfferRule#skipped}) through the normal decline path and all its guards, and never auto-accepted; an offer
 * whose store is not read is judged by the normal rules, and add-ons are unchanged. Autopilot counts such an offer as
 * failing at every bar.
 *
 * <p>The list is kept like the rules (its own prefs file, which Clear history leaves). The stores of recent offers, for
 * their tickets' buttons, are kept in memory only ({@link #remember}), never written.
 */
final class SkipList {
    private SkipList() {}

    private static final String PREFS = "skip_list";
    private static final String STORES = "stores";
    /** At most this many stores on the list. */
    static final int MOST = 100;
    /** Offers whose stores are remembered for their tickets, newest kept. */
    private static final int REMEMBERED = 200;

    /** The list as last loaded or changed; null until first loaded. */
    private static volatile List<String> cached;
    private static final Map<String, List<String>> RECENT = new LinkedHashMap<String, List<String>>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
            return size() > REMEMBERED;
        }
    };

    /** The skipped stores as the user added them, oldest first. */
    static List<String> stores(Context context) {
        List<String> list = cached;
        if (list != null) return list;
        synchronized (SkipList.class) {
            if (cached != null) return cached;
            List<String> loaded = new ArrayList<>();
            try {
                JSONArray stored = new JSONArray(prefs(context).getString(STORES, "[]"));
                for (int i = 0; i < stored.length(); i++) {
                    String name = stored.optString(i, "").trim();
                    if (!name.isEmpty() && indexOf(loaded, name) < 0) loaded.add(name);
                }
            } catch (JSONException unreadable) {
                // An unreadable list is an empty one.
            }
            cached = Collections.unmodifiableList(loaded);
            return cached;
        }
    }

    /** Whether {@code store} is on the list, however Dasher spaced or cased it. */
    static boolean contains(Context context, String store) {
        return store != null && indexOf(stores(context), store) >= 0;
    }

    /** The first of {@code read} on the list, or null (none read, or none on it). */
    static String skipped(Context context, List<String> read) {
        if (read == null || read.isEmpty()) return null;
        List<String> list = stores(context);
        if (list.isEmpty()) return null;
        for (String store : read) if (indexOf(list, store) >= 0) return store;
        return null;
    }

    static void add(Context context, String store) {
        if (store == null || store.trim().isEmpty()) return;
        synchronized (SkipList.class) {
            List<String> next = new ArrayList<>(stores(context));
            if (indexOf(next, store) >= 0) return;
            next.add(store.trim());
            while (next.size() > MOST) next.remove(0);
            save(context, next);
        }
        DiagnosticLog.log(context, "skip-list", "a store added (" + stores(context).size() + " on the list)");
    }

    static void remove(Context context, String store) {
        synchronized (SkipList.class) {
            List<String> next = new ArrayList<>(stores(context));
            int at = indexOf(next, store);
            if (at < 0) return;
            next.remove(at);
            save(context, next);
        }
        DiagnosticLog.log(context, "skip-list", "a store removed (" + stores(context).size() + " on the list)");
    }

    private static void save(Context context, List<String> list) {
        prefs(context).edit().putString(STORES, new JSONArray(list).toString()).apply();
        cached = Collections.unmodifiableList(new ArrayList<>(list));
    }

    /** The stores read on an offer's screen, kept in memory for its ticket. */
    static void remember(OfferSnapshot facts, List<String> stores) {
        if (facts == null || stores == null || stores.isEmpty()) return;
        synchronized (RECENT) {
            RECENT.put(facts.fingerprint(), Collections.unmodifiableList(new ArrayList<>(stores)));
        }
    }

    /** The stores read on the offer with these facts, while the app remembers them; empty otherwise. */
    static List<String> storesOf(OfferSnapshot facts) {
        if (facts == null) return Collections.emptyList();
        synchronized (RECENT) {
            List<String> stores = RECENT.get(facts.fingerprint());
            return stores == null ? Collections.<String>emptyList() : stores;
        }
    }

    private static int indexOf(List<String> list, String store) {
        String key = StoreName.key(store);
        for (int i = 0; i < list.size(); i++) if (StoreName.key(list.get(i)).equals(key)) return i;
        return -1;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Forgets the cached list and the remembered stores (tests). */
    static void forgetForTests() {
        cached = null;
        synchronized (RECENT) {
            RECENT.clear();
        }
    }
}
