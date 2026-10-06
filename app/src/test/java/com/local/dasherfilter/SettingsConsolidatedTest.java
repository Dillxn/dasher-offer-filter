package com.local.dasherfilter;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Settings, consolidated (the user: "remove mins settings and anything redundant whatsoever"): no minimum in it at
 * all, only rows that exist nowhere else, setup rows only while something needs a fix, and the retired report token
 * and Automatic updates switch cleared from older versions.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class SettingsConsolidatedTest extends AndroidAdapterTestBase {
    /** The list row whose first line is {@code title}, or null. */
    private static android.widget.Button row(View view, String title) {
        if (view instanceof android.widget.Button && !(view instanceof Switch)) {
            String text = ((android.widget.Button) view).getText().toString();
            if (text.equals(title) || text.startsWith(title + "\n")) return (android.widget.Button) view;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.Button found = row(group.getChildAt(i), title);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Settings, opened from the header. */
    private static View openSettings(ActivityController<MainActivity> activity) {
        View content = activity.get().findViewById(android.R.id.content);
        iconButton(content, "Settings").performClick();
        settle();
        return content;
    }

    @Test
    public void settingsEndsWithTheRequestedEmblemAndAccessiblePassageAfterLegalLinks() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = openSettings(activity);
            layOut(content);
            ImageView closing = (ImageView) shownIcon(content,
                    "Jesus Loves You. We love each other because He loves us first. 1 John 4:19.");
            assertNotNull(closing);
            assertTrue(closing.isShown());
            assertNotNull("the bundled emblem is available offline", closing.getDrawable());
            assertEquals(ImageView.ScaleType.FIT_CENTER, closing.getScaleType());
            assertFalse("the signature adds no control", closing.isClickable());
            assertFalse(closing.isLongClickable());
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, closing.getImportantForAccessibility());
            ViewGroup body = (ViewGroup) closing.getParent();
            assertEquals("the signature closes Settings", closing, body.getChildAt(body.getChildCount() - 1));
            assertTrue("the emblem fits the available body width", closing.getWidth() <= body.getWidth());
            assertTrue("the emblem is 55% of its previous maximum width", closing.getWidth() <= new Ui(activity.get()).dp(121));
            assertTrue(closing.getHeight() > 0);

            List<TextView> words = new ArrayList<>();
            settingsText(content, words);
            TextView version = findTextView(content, AppName.NAME + " v" + Updater.version(app));
            assertNotNull(version);
            int previous = words.indexOf(version);
            assertTrue(previous >= 0);
            for (LegalTexts.Doc doc : LegalTexts.Doc.values()) {
                TextView link = findTextView(content, doc.title);
                assertNotNull(link);
                int at = words.indexOf(link);
                assertTrue("legal links follow the version", at > previous);
                View ancestor = link;
                while (ancestor.getParent() != body) ancestor = (View) ancestor.getParent();
                assertTrue("the signature follows " + doc.title,
                        body.indexOfChild(ancestor) < body.indexOfChild(closing));
                previous = at;
            }
        }
    }

    private static void settingsText(View view, List<TextView> words) {
        if (!view.isShown()) return;
        if (view instanceof TextView && ((TextView) view).getText().length() > 0) words.add((TextView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) settingsText(group.getChildAt(i), words);
        }
    }

    @Test
    public void settingsHoldsNoMinimumAndNothingTheHomepageAlreadyHas() {
        FilterStore.save(app, new FilterSettings(true, 1300, 385, 41, 475, 3, true, 0));
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION);
        Shadows.shadowOf(app.getPackageManager()).setCanRequestPackageInstalls(true);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = openSettings(activity);
            assertTrue(settingsShown(content));
            assertNull("no field anywhere: every rule is set on the constellation", find(content, EditText.class));
            for (String gone : new String[] {"Minimum pay ($)", "Per mile ($)", "Per minute ($)", "Per stop ($)",
                    "Max stops (1 order = 2)", "0 turns a rule off.", "Save rules", "Adaptive minimum", "Reset",
                    "Score by area", "Automatic updates", "Check for update", "Allow installs", "Forget areas",
                    "Remember where offers come in", "Accessibility", "Notification access", "Alert settings",
                    "DoorDash channel", "Automatic reports (GitHub token)", "Save token", "Send test",
                    "Turn off reports", "Disconnect GitHub", "Tip with Cash App"}) {
                assertNull(gone, findTextView(content, gone));
            }
            assertNull("no paragraph about what is kept", findTextView(content, "stays on this phone for reports"));
            assertNull("all is well: no setup row", shownTextContaining(content, "Fix"));

            // What stays: two switches and the accountless, user-facing rows.
            assertTrue(findButton(content, "Quiet Dasher while declining").isShown());
            assertTrue(findButton(content, "Offer map").isShown());
            assertTrue(row(content, "Updates").isShown());
            assertNotNull(shownButton(content, "Send anonymous feedback"));
            assertNotNull(shownButton(content, "Share report"));
            assertNotNull(shownButton(content, "Clear history"));
            assertNotNull(shownButton(content, "Tip"));
            assertNull("end-user GitHub is retired", findButton(content, "Connect GitHub"));
            assertFalse(findButton(content, "Send problem reports").isShown());
            assertFalse(findButton(content, "Share diagnostics after each dash").isShown());
        }
    }

    @Test
    public void theUpdatesRowSaysWhereUpdatesStandAndATapChecksNow() throws Exception {
        Updater.status(app, "Up to date (0.4.44).");
        Updater.feedReader = (channel, address, token, out, limit) -> {
            throw new IOException("offline in tests");
        };
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = openSettings(activity);
            android.widget.Button row = row(content, "Updates");
            assertEquals("its status under its name", "Updates\nUp to date (0.4.44).", row.getText().toString());
            row.performClick();
            Updater.awaitIdle(5000);
            assertTrue("a tap checks now, by hand", DiagnosticLog.read(app).contains("check start manual=true"));
        } finally {
            Updater.feedReader = UpdateTransport::download;
        }
    }

    @Test
    public void setupRowsShowOnlyWhileSomethingNeedsAFix() {
        Shadows.shadowOf(app.getPackageManager()).setCanRequestPackageInstalls(false);
        AreaMap.setEnabled(app, true);
        NotificationChannel offers = new NotificationChannel("orders", "Orders", NotificationManager.IMPORTANCE_HIGH);
        offers.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null);
        FilterStore.recordDoorDashOfferChannel(app, "orders");
        FilterStore.recordDoorDashChannel(app, offers, true);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = openSettings(activity);
            View dasher = shownIcon(content, "DoorDash's offer alert also sounded. Fix.");
            View installs = shownIcon(content, "Updates can't install. Fix.");
            View location = shownIcon(content, "The offer map needs location. Fix.");
            assertNotNull("Dasher's offer channel rings", dasher);
            assertNotNull("installs are not allowed", installs);
            assertNotNull("the map is on without location", location);

            dasher.performClick();
            Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
            assertEquals(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, opened.getAction());
            assertEquals("com.doordash.driverapp", opened.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE));
            assertEquals("orders", opened.getStringExtra(android.provider.Settings.EXTRA_CHANNEL_ID));
            installs.performClick();
            assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Shadows.shadowOf(app).getNextStartedActivity().getAction());
            location.performClick();
            assertEquals(android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    Shadows.shadowOf(activity.get()).getLastRequestedPermission().requestedPermissions[0]);

            // Each fixed: its row goes.
            FilterStore.recordDoorDashChannel(app,
                    new NotificationChannel("orders", "Orders", NotificationManager.IMPORTANCE_LOW));
            Shadows.shadowOf(app.getPackageManager()).setCanRequestPackageInstalls(true);
            ((Switch) findButton(content, "Offer map")).setChecked(false);
            activity.pause().resume();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1100));
            assertNull(shownIcon(content, "DoorDash's offer alert also sounded. Fix."));
            assertNull(shownIcon(content, "Updates can't install. Fix."));
            assertNull("with the map off, location is not needed", shownIcon(content,
                    "The offer map needs location. Fix."));
        }
    }

    @Test
    public void aChannelAndroidDoesNotDescribeChangesNothing() {
        assertFalse("nothing seen yet: nothing to fix", FilterStore.doorDashChannelAlerts(app));
        NotificationChannel rings = new NotificationChannel("orders", "Orders", NotificationManager.IMPORTANCE_HIGH);
        rings.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null);
        FilterStore.recordDoorDashChannel(app, rings);
        assertFalse("configured sound alone cannot create a Fix", FilterStore.doorDashChannelAlerts(app));
        FilterStore.recordDoorDashChannel(app, rings, true);
        assertTrue(FilterStore.doorDashChannelAlerts(app));
        FilterStore.recordDoorDashChannel(app, null);
        assertTrue("unknown keeps what was last seen", FilterStore.doorDashChannelAlerts(app));
        NotificationChannel quiet = new NotificationChannel("orders", "Orders", NotificationManager.IMPORTANCE_DEFAULT);
        quiet.setSound(null, null);
        quiet.enableVibration(false);
        FilterStore.recordDoorDashChannel(app, quiet);
        assertFalse("no sound and no vibration is silent", FilterStore.doorDashChannelAlerts(app));
    }

    /** A queued report as the outbox writes it: problem reports end ".json", diagnostics "-d.json". */
    private File queued(String name) throws IOException {
        File dir = new File(app.getFilesDir(), "report-outbox");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        File file = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("{\"title\":\"t\",\"body\":\"b\"}".getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    @Test
    public void aReportTokenFromAnOlderVersionIsRemovedWithTheReportsWaitingForIt() throws IOException {
        // Saved by an older version: a pasted token, a refusal it earned, a problem report and a dash's diagnostics.
        android.content.SharedPreferences reports = app.getSharedPreferences("reports", Context.MODE_PRIVATE);
        reports.edit().putString("token", "github_pat_old").putString("last_error", "GitHub rejected the token")
                .commit();
        File problem = queued("1790000000000-000001.json");
        File diagnostics = queued("1790000000001-000002-d.json");

        assertFalse("no reports go with a pasted token any more", ReportOutbox.enabled(app));
        ReportOutbox.flush();
        assertFalse("the token is gone from the phone", reports.contains("token"));
        assertFalse(reports.contains("last_error"));
        assertFalse("the problem report waiting to go with it is discarded", problem.exists());
        assertTrue("diagnostics only ever went through the GitHub connection, and stay", diagnostics.exists());
        assertEquals("Off", ReportOutbox.status(app));
        assertTrue(DiagnosticLog.read(app), DiagnosticLog.read(app).contains("the report token was removed"));

        // Once only: a later use finds nothing to remove.
        ReportOutbox.enabled(app);
        String log = DiagnosticLog.read(app);
        assertEquals(log.indexOf("report token was removed"), log.lastIndexOf("report token was removed"));
    }

    @Test
    public void reportsTheUserTurnedOnThroughTheConnectionStayOnWhenAnOldTokenGoes() throws IOException {
        reportsOn();
        app.getSharedPreferences("reports", Context.MODE_PRIVATE).edit().putString("token", "github_pat_old").commit();
        File problem = queued("1790000000000-000001.json");

        assertTrue("Send problem reports stays on", ReportOutbox.enabled(app));
        ReportOutbox.flush();
        assertFalse(app.getSharedPreferences("reports", Context.MODE_PRIVATE).contains("token"));
        assertFalse("what waited went with the token, so it goes", problem.exists());
        assertTrue(ReportOutbox.status(app).startsWith("On"));
    }

    @Test
    public void anEmptyTokenFromAnOlderVersionGoesQuietly() throws IOException {
        app.getSharedPreferences("reports", Context.MODE_PRIVATE).edit().putString("token", "").commit();
        File problem = queued("1790000000000-000001.json");
        assertFalse(ReportOutbox.enabled(app));
        ReportOutbox.flush();
        assertFalse(app.getSharedPreferences("reports", Context.MODE_PRIVATE).contains("token"));
        assertTrue("nothing was sent with no token, so nothing is discarded for it", problem.exists());
        assertFalse(DiagnosticLog.read(app).contains("report token"));
    }

    @Test
    public void openingTheAppRemovesAnOldTokenToo() {
        app.getSharedPreferences("reports", Context.MODE_PRIVATE).edit().putString("token", "github_pat_old").commit();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertFalse(app.getSharedPreferences("reports", Context.MODE_PRIVATE).contains("token"));
            View content = activity.get().findViewById(android.R.id.content);
            DecisionLog.record(app, declinedEntry());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1100));
            openTicket(content);
            assertNotNull("Report this offer is accountless and needs no prior opt-in",
                    shownButton(content, "Report this offer"));
        }
    }

    @Test
    public void automaticUpdatesTurnedOffInAnOlderVersionAreOnAgain() {
        // An older version's switch, off; nothing has set checks since.
        android.content.SharedPreferences updates = app.getSharedPreferences("updates", Context.MODE_PRIVATE);
        updates.edit().clear().putBoolean("enabled", false).commit();
        assertFalse(Updater.enabled(app));
        Updater.retireSwitch(app);
        assertTrue("always automatic now", Updater.enabled(app));
        assertTrue(DiagnosticLog.read(app), DiagnosticLog.read(app).contains("automatic updates were turned off"));

        // Once only, and checks set since (as tests do) are left alone.
        Updater.setEnabled(app, false);
        Updater.retireSwitch(app);
        assertFalse(Updater.enabled(app));
    }

    @Test
    public void openingTheAppTurnsAnOldUpdatesSwitchBackOn() {
        app.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().clear().putBoolean("enabled", false)
                .commit();
        Updater.feedReader = (channel, address, token, out, limit) -> {
            throw new IOException("offline in tests");
        };
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).create()) {
            assertTrue(Updater.enabled(app));
        } finally {
            Updater.feedReader = UpdateTransport::download;
            Updater.setEnabled(app, false);
        }
    }
}
