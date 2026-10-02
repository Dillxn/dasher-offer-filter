package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;

/** One bounded failure marker: exception category only, never a message, stack, screen labels or account data. */
final class ScannerFailure {
    static final String PREFS = "scanner_failure";
    static void remember(Context context, RuntimeException error) {
        String name = error.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "");
        if (name.length() > 80) name = name.substring(0, 80);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("kind", name).commit();
    }
    static String take(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String kind = prefs.getString("kind", "");
        if (!kind.isEmpty()) prefs.edit().clear().apply();
        return kind;
    }
    static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
    private ScannerFailure() {}
}
