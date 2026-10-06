package com.local.dasherfilter;

import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;

import static org.junit.Assert.*;

/** The Activity owns the advanced panel: stopped, hidden, dismissed and recreated windows cannot save. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35, 36}, qualifiers = "w411dp-h914dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class EarningsPanelIntegrationTest extends AndroidAdapterTestBase {
    @Test public void leavingSettingsDismissesPanelAndInvalidatesOldSaveButton() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            AlertDialog panel = openPanel(content);
            find(panel.getWindow().getDecorView(), EditText.class).setText("1.23");
            View save = findButton(panel.getWindow().getDecorView(), "Save driving cost");
            iconDescribed(content, "Back").performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(panel.isShowing());
            save.performClick();
            assertNull(EarningsStore.config(app).vehicleCostCentsPerMile);
            assertFalse(EarningsStore.config(app).enabled);
        }
    }

    @Test public void stoppingTheActivityDismissesPanelAndItsPendingOptIn() {
        assertTrue(EarningsStore.saveConfig(app, new EarningsModel.Config(false, 50.0, 80, 120)));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            AlertDialog panel = openPanel(content);
            View enable = findButton(panel.getWindow().getDecorView(), "Enable automatic adjustment…");
            assertNotNull(enable);
            enable.performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog confirmation = ShadowAlertDialog.getLatestAlertDialog();
            assertNotSame(panel, confirmation);
            assertTrue(confirmation.isShowing());
            Button accept = confirmation.getButton(AlertDialog.BUTTON_POSITIVE);
            activity.pause().stop();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(panel.isShowing());
            assertFalse(confirmation.isShowing());
            accept.performClick();
            enable.performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(EarningsStore.config(app).enabled);
            assertFalse(FilterStore.autoAcceptEnabled(app));
        }
    }

    @Test public void recreateRejectsTheOldPanelAndNewPanelCanSave() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            AlertDialog old = openPanel(content);
            find(old.getWindow().getDecorView(), EditText.class).setText("1.23");
            View stale = findButton(old.getWindow().getDecorView(), "Save driving cost");
            activity.recreate();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(old.isShowing());
            stale.performClick();
            assertNull(EarningsStore.config(app).vehicleCostCentsPerMile);
            content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            // Settings is restored by MainActivity; the row remains the one authorized entry point.
            Button row = shownButton(content, "Costs and estimates");
            if (row == null) {
                iconDescribed(content, "Settings").performClick();
                row = shownButton(content, "Costs and estimates");
            }
            assertNotNull(row);
            row.performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog fresh = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(fresh.isShowing());
            assertNotSame(old, fresh);
            find(fresh.getWindow().getDecorView(), EditText.class).setText("0.50");
            findButton(fresh.getWindow().getDecorView(), "Save driving cost").performClick();
            assertEquals(50.0, EarningsStore.config(app).vehicleCostCentsPerMile, 0.0001);
            assertFalse(EarningsStore.config(app).enabled);
            stale.performClick();
            assertEquals(50.0, EarningsStore.config(app).vehicleCostCentsPerMile, 0.0001);
            fresh.dismiss();
        }
    }

    private static AlertDialog openPanel(View content) {
        iconDescribed(content, "Settings").performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Button row = shownButton(content, "Costs and estimates");
        assertNotNull(row);
        row.performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        AlertDialog panel = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(panel);
        assertTrue(panel.isShowing());
        return panel;
    }
}
