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
        // updates from the app (the Update ready line's Allow updates; the tests of that line turn it off).
        getSharedPreferences(PeekIntroCard.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(PeekIntroCard.SEEN, true).commit();
        org.robolectric.Shadows.shadowOf(getPackageManager()).setCanRequestPackageInstalls(true);
        Feedback.transport = new FakeFeedbackTransport();
        Feedback.forgetCache();
        FeedbackOutbox.forgetCache();
        FeedbackOutbox.clock = System::currentTimeMillis;
        FeedbackJobService.forgetCache();
        // The anonymous daily count goes from every app opening and service connection on a phone: here only when a
        // test turns it on (DailyCountTest), so no other suite sees a count among its requests.
        DailyCount.automatic = false;
        DailyCount.clock = System::currentTimeMillis;
        DailyCount.elapsed = DailyCount.BOOT_CLOCK;
        DailyCountJobService.forgetCache();
        FeedbackDialogs.forgetDraft();
        DashSummary.forgetCache();
        StopReports.forgetCache();
        // The signs of a dash kept in memory for the screen hold: no test inherits an earlier one's.
        Dashing.forgetCache();
        // Autopilot plans, and takes Dasher's acceptance rate from its decline question, on a thread of its own on a
        // phone: here on the calling thread, so no test leaves that work running into the next one, and nothing an
        // earlier test planned or read is remembered (the tests of that thread set their own executor).
        AutopilotRuntime.executorForTests = Runnable::run;
        AutopilotRuntime.forgetCache();
        // Dasher's activities are described for a report on a thread of their own on a phone: here on the calling
        // thread, for the same reason (LaunchWatchAdapterTest holds that work back to see the scanner never does it).
        OfferFilterService.describerForTests = Runnable::run;
        // From 0.6.2 Autopilot is on by default. Tests written before that run as an install whose user turned it off
        // (or kept it off); AutopilotDefaultTest turns the default back on to test it.
        FilterStore.autopilotOnByDefault = false;
        // The pictures in the round are decoded on a thread of their own on a phone: here on the calling thread, from
        // the app's resources, and none kept from an earlier test, so every drawing is the same whatever ran before.
        Art.executorForTests = Runnable::run;
        Art.source = Art.RESOURCES;
        Art.flatForTests = false;
        Art.forgetCache();
        // Setup's page (0.6.26) takes the whole screen while screen reading or notification access is off, as it is in
        // every test that does not turn them on: tests written before it see the homepage as they did (SetupFlowTest,
        // and the few tests of setup's page elsewhere, turn it back on).
        SetupFlow.pageShows = false;
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
