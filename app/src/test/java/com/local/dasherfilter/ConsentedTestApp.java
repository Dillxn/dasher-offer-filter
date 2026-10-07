package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;

/**
 * The app every Robolectric test runs in (src/test/resources/robolectric.properties): the first-run notice already
 * accepted, as on a phone in use, so tests written before the notice behave as they did. ConsentGateTest starts from
 * a phone that has not accepted it ({@link #forget}). Written straight to the prefs, so no test sees a log line for it.
 * So is the one-time clean-up after the payment-page fix (DiagnosticLog.cleanUpOnce), as done: PrivacyCleanupTest
 * starts from a phone updated from an older version.
 *
 * <p>Every test also gets a fake feedback service ({@link FakeFeedbackTransport}): no test can reach the real one,
 * and what each sends can be looked at. The feedback classes forget what an earlier test left in memory.
 */
public class ConsentedTestApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        accept(this);
        getSharedPreferences("offer_filter_diagnostics", Context.MODE_PRIVATE).edit()
                .putBoolean(DiagnosticLog.CLEANED_UP, true).commit();
        // A phone in use has read Peek's one-time introduction (OnboardingCardsTest starts without it) and allows
        // updates from the app (the setup checklist's last step; the tests of that step turn it off).
        getSharedPreferences(PeekIntroCard.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(PeekIntroCard.SEEN, true).commit();
        org.robolectric.Shadows.shadowOf(getPackageManager()).setCanRequestPackageInstalls(true);
        Feedback.transport = new FakeFeedbackTransport();
        Feedback.forgetCache();
        FeedbackOutbox.forgetCache();
        FeedbackOutbox.clock = System::currentTimeMillis;
        FeedbackJobService.forgetCache();
        FeedbackDialogs.forgetDraft();
        DashSummary.forgetCache();
        StopReports.forgetCache();
        // The signs of a dash kept in memory for the screen hold: no test inherits an earlier one's.
        Dashing.forgetCache();
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
