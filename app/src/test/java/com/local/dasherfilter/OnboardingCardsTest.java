package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;

/**
 * BETA-11, BETA-22, BETA-28 and BETA-29: the notice's What changed box for re-accepting readers, the one-time What is
 * new card after an update, the one-time Peek card when filtering first runs, and one line of why before background
 * location. Each is short, dismissible and shown only when it applies.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class OnboardingCardsTest extends AndroidAdapterTestBase {
    private static final List<String> LINES = Arrays.asList("Feedback no longer needs a GitHub account.",
            "Setup now leads with restricted settings.");

    @Before public void clean() {
        app.getSharedPreferences(WhatsNewCard.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private static void tick() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
    }

    // ---- What changed (the notice) ----

    @Test public void whatChangedLeadsTheNoticeForAReacceptingReaderOnly() {
        Activity screen = Robolectric.buildActivity(Activity.class).setup().get();
        Ui ui = new Ui(screen);
        ScrollView notice = NoticePage.build(screen, ui, () -> { }, () -> { }, doc -> { }, LINES);
        TextView box = findTextContaining(notice, NoticePage.WHAT_CHANGED);
        assertNotNull(box);
        assertEquals("What changed\n• " + LINES.get(0) + "\n• " + LINES.get(1), box.getText().toString());
        TextView firstPoint = findTextContaining(notice, Consent.POINTS[0][0]);
        View boxView = (View) box.getParent();
        LinearLayout body = (LinearLayout) boxView.getParent();
        assertEquals("at the top of the notice's words", 0, body.indexOfChild(boxView));
        assertTrue("above the points", body.indexOfChild(firstPoint) > 0);

        ScrollView fresh = NoticePage.build(screen, ui, () -> { }, () -> { }, doc -> { },
                Collections.<String>emptyList());
        assertNull("nothing changed for a new install", findTextContaining(fresh, NoticePage.WHAT_CHANGED));
    }

    @Test public void theNoticeShowsNoPlaceholderToAReacceptingReader() {
        ConsentedTestApp.forget(app);
        app.getSharedPreferences(Consent.PREFS, Context.MODE_PRIVATE).edit()
                .putInt(Consent.ACCEPTED_VERSION, Consent.VERSION - 1).commit();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertNotNull(shownTextContaining(content, Consent.TITLE));
            TextView box = shownTextContaining(content, NoticePage.WHAT_CHANGED);
            if (BundledNotes.WRITTEN) {
                assertNotNull("re-accepting: what changed comes first", box);
            } else {
                assertNull("unwritten words never reach a reader", box);
            }
            assertNull(shownTextContaining(content, BundledNotes.PLACEHOLDER));
        }
    }

    // ---- What is new (after an update) ----

    private void installedAt(long first, long last) {
        PackageInfo info = Shadows.shadowOf(app.getPackageManager()).getInternalMutablePackageInfo(app.getPackageName());
        info.firstInstallTime = first;
        info.lastUpdateTime = last;
    }

    private WhatsNewCard card(LinearLayout parent, List<String> notes) {
        Activity screen = (Activity) parent.getContext();
        return new WhatsNewCard(screen, new Ui(screen), parent, version -> notes);
    }

    @Test public void whatIsNewShowsOnceAfterAnUpdateAndNeverAfterANewInstall() {
        Activity screen = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout parent = new LinearLayout(screen);
        installedAt(1000, 1000);
        WhatsNewCard fresh = card(parent, LINES);
        fresh.refresh();
        assertFalse("a new install: nothing is new to it", fresh.shown());

        // The next version arrives as an update.
        app.getSharedPreferences(WhatsNewCard.PREFS, Context.MODE_PRIVATE).edit()
                .putString(WhatsNewCard.SEEN, "0.4.1").commit();
        installedAt(1000, 2000);
        WhatsNewCard updated = card(parent, LINES);
        updated.refresh();
        assertTrue(updated.shown());
        String version = Updater.version(app);
        TextView words = findTextContaining(parent, WhatsNewCard.title(version));
        assertEquals("What is new in " + version + "\n• " + LINES.get(0) + "\n• " + LINES.get(1),
                words.getText().toString());
        Button ok = shownButton(parent, "OK");
        assertNull("shown only while attached", ok);
        screen.setContentView(parent);
        shownButton(parent, "OK").performClick();
        assertFalse("dismissible", updated.shown());
        WhatsNewCard again = card(parent, LINES);
        again.refresh();
        assertFalse("once", again.shown());
    }

    @Test public void anUpdateFromAVersionWithoutTheCardShowsItAndNoNotesShowNothing() {
        Activity screen = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout parent = new LinearLayout(screen);
        installedAt(1000, 5000);
        WhatsNewCard none = card(parent, Collections.<String>emptyList());
        none.refresh();
        assertFalse("no notes for this version: no card", none.shown());
        WhatsNewCard some = card(parent, LINES);
        some.refresh();
        assertTrue("0.4.72 kept no record of it: an update all the same", some.shown());
    }

    // ---- Peek's introduction ----

    @Test public void peeksCardShowsOnceWhenFilteringFirstRunsAndLeadsToItsSwitch() {
        app.getSharedPreferences(PeekIntroCard.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        FilterStore.save(app, new FilterSettings(false, 700, 0, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNull("paused: Peek does nothing yet", shownTextContaining(content, PeekIntroCard.LEAD));
            FilterStore.save(app, FilterStore.load(app).withEnabled(true));
            tick();
            TextView card = shownTextContaining(content, PeekIntroCard.LEAD);
            assertNotNull("the first time filtering is on", card);
            assertEquals("Peek is on: when an offer arrives while Dasher is in the background, " + AppName.NAME
                    + " briefly opens Dasher to read it. Turn off in Settings.", card.getText().toString());
            shownButton(content, PeekIntroCard.SETTINGS).performClick();
            tick();
            assertTrue("the link opens Settings, where its switch is", settingsShown(content));
            // Its switch, with its one line under it (window flows: "Peek pauses while your phone is locked").
            assertNotNull(findButton(content, "Peek at background offers\n" + Peek.LOCKED_NOTE));
            iconButton(content, "Back").performClick();
            tick();
            assertNull("once", shownTextContaining(content, PeekIntroCard.LEAD));
        }
    }

    @Test public void peeksCardStaysAwayWithPeekOffAndOkClosesItForGood() {
        app.getSharedPreferences(PeekIntroCard.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        FilterStore.save(app, new FilterSettings(true, 700, 0, 0, 0, 0));
        FilterStore.setPeek(app, false);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNull("Peek is off: nothing to explain", shownTextContaining(content, PeekIntroCard.LEAD));
            FilterStore.setPeek(app, true);
            tick();
            assertNotNull(shownTextContaining(content, PeekIntroCard.LEAD));
            shownButton(content, "OK").performClick();
            tick();
            assertNull(shownTextContaining(content, PeekIntroCard.LEAD));
        }
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tick();
            assertNull(shownTextContaining(activity.get().findViewById(android.R.id.content), PeekIntroCard.LEAD));
        }
    }

    // ---- Background location ----

    @Test public void allTheTimeLocationIsAskedOnlyAfterItsOneLineOfWhy() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        AreaMap.setEnabled(app, true);
        app.getSharedPreferences("offer_filter_areas", Context.MODE_PRIVATE).edit().putInt("unlocated", 3).commit();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNull("never at startup", Shadows.shadowOf(activity.get()).getLastRequestedPermission());
            iconButton(content, "Settings").performClick();
            tick();
            View fix = shownIcon(content, "The offer map needs location all the time. Fix.");
            assertNotNull(fix);
            fix.performClick();
            AlertDialog why = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(why);
            assertEquals(LocationRationale.TEXT, Shadows.shadowOf(why).getMessage().toString());
            assertNull("nothing asked before Continue", Shadows.shadowOf(activity.get()).getLastRequestedPermission());
            why.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull("Not now asks nothing", Shadows.shadowOf(activity.get()).getLastRequestedPermission());

            fix.performClick();
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            org.robolectric.shadows.ShadowActivity.PermissionsRequest request =
                    Shadows.shadowOf(activity.get()).getLastRequestedPermission();
            assertEquals(Manifest.permission.ACCESS_BACKGROUND_LOCATION, request.requestedPermissions[0]);

            // Refused: where the switch is, for later.
            activity.get().onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                    new int[] {android.content.pm.PackageManager.PERMISSION_DENIED});
            assertEquals("Not allowed. " + LocationRationale.PATH,
                    org.robolectric.shadows.ShadowToast.getTextOfLatestToast());

            // Android will not show its request again (asked before, no rationale left): Continue opens App info.
            Shadows.shadowOf(app).clearNextStartedActivities();
            fix.performClick();
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertSame("no request that would come back unanswered", request,
                    Shadows.shadowOf(activity.get()).getLastRequestedPermission());
            android.content.Intent info = Shadows.shadowOf(app).getNextStartedActivity();
            assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, info.getAction());
            assertEquals("package:" + app.getPackageName(), info.getDataString());
            assertEquals(LocationRationale.PATH, org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
            assertEquals("In App info: Permissions → Location → Allow all the time.", LocationRationale.PATH);

            // While Android still has a reason to give, it is Android that asks again.
            Shadows.shadowOf(app.getPackageManager()).setShouldShowRequestPermissionRationale(
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION, true);
            fix.performClick();
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotSame(request, Shadows.shadowOf(activity.get()).getLastRequestedPermission());
        }
    }

    // ---- The cards' links are 48 dp targets; What is new asks Android once ----

    @Test public void theCardsShortLinksAreFullTouchTargets() {
        Activity screen = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout parent = new LinearLayout(screen);
        screen.setContentView(parent);
        installedAt(1000, 2000);
        WhatsNewCard card = card(parent, LINES);
        card.refresh();
        Button ok = shownButton(parent, "OK");
        assertNotNull(ok);
        Ui ui = new Ui(screen);
        ok.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertTrue("48 dp across: " + ok.getMeasuredWidth(), ok.getMeasuredWidth() >= ui.dp(48));
        assertTrue("48 dp tall: " + ok.getMeasuredHeight(), ok.getMeasuredHeight() >= ui.dp(48));
    }

    @Test public void whatIsNewWorksOutItsLinesOnceNotAtEverySecondsRefresh() {
        Activity screen = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout parent = new LinearLayout(screen);
        installedAt(1000, 2000);
        int[] asked = {0};
        WhatsNewCard card = new WhatsNewCard(screen, new Ui(screen), parent, version -> {
            asked[0]++;
            return LINES;
        });
        for (int second = 0; second < 5; second++) card.refresh();
        assertTrue(card.shown());
        assertEquals("once per page", 1, asked[0]);
    }
}
