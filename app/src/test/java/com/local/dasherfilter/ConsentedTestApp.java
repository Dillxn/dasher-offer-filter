package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;

/**
 * The app every Robolectric test runs in (src/test/resources/robolectric.properties): the first-run notice already
 * accepted, as on a phone in use, so tests written before the notice behave as they did. ConsentGateTest starts from
 * a phone that has not accepted it ({@link #forget}). Written straight to the prefs, so no test sees a log line for it.
 */
public class ConsentedTestApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        accept(this);
    }

    static void accept(Context context) {
        context.getSharedPreferences(Consent.PREFS, Context.MODE_PRIVATE).edit()
                .putInt(Consent.ACCEPTED_VERSION, Consent.VERSION).commit();
    }

    /** As before the notice was ever shown: a fresh install, or one updated from a version without it. */
    static void forget(Context context) {
        context.getSharedPreferences(Consent.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
    }
}
