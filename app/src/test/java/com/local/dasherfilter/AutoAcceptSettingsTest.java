package com.local.dasherfilter;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.View;
import android.widget.Switch;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;

import static org.junit.Assert.*;

/** Accepting deliveries is never enabled by an upgrade or by dismissing the explanation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class AutoAcceptSettingsTest extends AndroidAdapterTestBase {
    private Switch toggle(ActivityController<MainActivity> activity) {
        View content = activity.get().findViewById(android.R.id.content);
        iconButton(content, "Settings").performClick();
        settle();
        View row = findButton(content, "Auto-accept matching offers");
        assertTrue(row instanceof Switch);
        return (Switch) row;
    }

    @Test public void offByDefaultAndCancellingDoesNotEnableIt() {
        assertFalse(FilterStore.autoAcceptEnabled(app));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            Switch row = toggle(activity);
            assertFalse(row.isChecked());
            row.performClick();
            assertFalse(row.isChecked());
            assertFalse(FilterStore.autoAcceptEnabled(app));
            AlertDialog notice = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(notice.isShowing());
            notice.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
            assertFalse(FilterStore.autoAcceptEnabled(app));
            row.performClick();
            ShadowAlertDialog.getLatestAlertDialog().cancel();
            assertFalse(FilterStore.autoAcceptEnabled(app));
        }
    }

    @Test public void explicitConfirmationPersistsAndTurningOffNeedsNoDialog() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            Switch row = toggle(activity);
            row.performClick();
            ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            settle();
            assertTrue(row.isChecked());
            assertTrue(FilterStore.autoAcceptEnabled(app));
        }
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            Switch row = toggle(activity);
            assertTrue(row.isChecked());
            row.performClick();
            assertFalse(row.isChecked());
            assertFalse(FilterStore.autoAcceptEnabled(app));
        }
    }
}
