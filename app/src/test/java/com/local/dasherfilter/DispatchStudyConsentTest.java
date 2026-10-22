package com.local.dasherfilter;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;
import android.widget.Switch;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

/**
 * The offer timing study's consent, which from 0.6.34 is Share anonymous diagnostics' (the one switch for every
 * diagnostic): only its own Turn on grants it, nothing else can, and no earlier, narrower choice is read as it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class DispatchStudyConsentTest extends AndroidAdapterTestBase {
    private ActivityController<Activity> screen;
    private FeedbackDialogs dialogs;
    @Before public void startStudyScreen() {
        DispatchStudyStore.resetProcessForTest();
        screen = Robolectric.buildActivity(Activity.class).setup();
        dialogs = new FeedbackDialogs(screen.get(), new Ui(screen.get()), () -> {});
        dialogs.start();
    }
    @After public void closeStudyScreen() {
        dialogs.destroy(); screen.close();
        Feedback.setDiagnostics(app, false); DispatchStudyStore.flush(); FeedbackOutbox.flush();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private AlertDialog open() { dialogs.diagnostics(); return (AlertDialog) ShadowDialog.getLatestDialog(); }
    private void turnOn(AlertDialog d) {
        assertEquals("Turn on", d.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle(); DispatchStudyStore.flush();
    }
    private void notNow(AlertDialog d) {
        assertEquals("Not now", d.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
        d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle(); DispatchStudyStore.flush();
    }
    private boolean on() { return Feedback.diagnosticsOn(app) || DispatchStudyStore.enabled(app); }
    private static boolean shows(android.view.View view, String words) {
        if (view instanceof android.widget.TextView
                && ((android.widget.TextView) view).getText().toString().contains(words)) return true;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (shows(group.getChildAt(i), words)) return true;
        }
        return false;
    }
    /** The notice's own diagnostics switch, as a reader would find it. */
    private Switch noticeSwitch() {
        return (Switch) NoticePage.build(screen.get(), new Ui(screen.get()), () -> {}, () -> {}, doc -> {})
                .findViewById(NoticePage.DIAGNOSTICS_ID);
    }
    /** A phone updated from 0.6.33: the separate choices as it kept them, and the study's numbers on disk. */
    private void updatedFrom0633(boolean afterDash, boolean study) throws Exception {
        Feedback.prefs(app).edit().clear().putBoolean(Feedback.RETIRED_AFTER_DASH, afterDash).commit();
        DispatchStudyStore.prefs(app).edit().clear().putInt("version", 1).putBoolean("enabled", study)
                .putLong("epoch", 3).commit();
        try (FileOutputStream out = new FileOutputStream(new File(app.getFilesDir(), DispatchStudyStore.FILE))) {
            out.write("{\"epoch\":3,\"dropped\":0,\"rows\":[]}".getBytes("UTF-8"));
        }
    }

    @Test public void presentationPreviewAndCancelCannotEnableAnything() {
        AlertDialog d = open(); assertFalse(on());
        assertFalse(DispatchStudyStore.prefs(app).contains("enabled"));
        d.cancel(); Shadows.shadowOf(Looper.getMainLooper()).idle(); assertFalse(on());
        // The feedback dialog's Preview, with the switch off: no diagnostics, and nothing turned on.
        dialogs.feedback(null, null);
        AlertDialog feedback = (AlertDialog) ShadowDialog.getLatestDialog();
        shownButton(feedback.getWindow().getDecorView(), "Preview what is sent").performClick();
        for (int i = 0; i < 200 && ShadowDialog.getLatestDialog() == feedback; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            try { Thread.sleep(25); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
        }
        AlertDialog preview = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotSame(feedback, preview);
        assertTrue(shows(preview.getWindow().getDecorView(), "No diagnostics attached."));
        assertFalse(on());
    }
    @Test public void turnOnEnablesTheWholeStatedScopeInAFreshStudy() {
        AlertDialog d = open(); turnOn(d);
        assertTrue(Feedback.diagnosticsOn(app)); assertTrue(DispatchStudyStore.enabled(app));
        assertEquals("", DispatchStudyStore.section(app));
        // It says all it covers: the summary after each dash, the timing study among it, and feedback's report.
        assertTrue(FeedbackDialogs.DIAGNOSTICS_SAYS.contains("After each dash, one summary"));
        assertTrue(FeedbackDialogs.DIAGNOSTICS_SAYS.contains("how long you waited for offers against the app's "
                + "estimate"));
        assertTrue(FeedbackDialogs.DIAGNOSTICS_SAYS.contains("With feedback you send"));
        assertTrue(FeedbackDialogs.DIAGNOSTICS_SAYS.contains("Turn it off any time"));
    }
    @Test public void notNowAndLaterReopeningPreserveRefusal() {
        notNow(open()); assertFalse(on());
        AlertDialog again = open(); assertNotNull(again); assertFalse(on());
        again.dismiss(); Shadows.shadowOf(Looper.getMainLooper()).idle(); assertFalse(on());
        assertFalse(noticeSwitch().isChecked());
    }
    @Test public void anEarlierOptOutIsNeverReenabledOrPrechecked() throws Exception {
        updatedFrom0633(false, true);
        assertFalse(Feedback.diagnosticsOn(app)); DispatchStudyStore.flush();
        assertFalse(DispatchStudyStore.enabled(app)); assertFalse(noticeSwitch().isChecked());
        assertFalse(Feedback.prefs(app).contains(Feedback.RETIRED_AFTER_DASH));
        assertFalse(new File(app.getFilesDir(), DispatchStudyStore.FILE).exists());
    }
    @Test public void anEarlierAfterDashOptInIsNotTheOneSwitchAndWithdrawalForgets() throws Exception {
        updatedFrom0633(true, false);
        long epoch = Feedback.automaticEpoch(app);
        assertFalse(Feedback.diagnosticsOn(app)); assertFalse(noticeSwitch().isChecked());
        assertFalse(DispatchStudyStore.enabled(app));
        turnOn(open()); long study = DispatchStudyStore.epoch(app);
        assertTrue(on());
        // Nothing the earlier switch queued may go, even with the one switch on: its epoch is long gone.
        assertFalse(Feedback.automaticAllowed(app, epoch));
        assertTrue(Feedback.automaticAllowed(app, Feedback.automaticEpoch(app)));
        Feedback.setDiagnostics(app, false); DispatchStudyStore.flush();
        assertFalse(on()); assertNotEquals(study, DispatchStudyStore.epoch(app));
        assertFalse(new File(app.getFilesDir(), DispatchStudyStore.FILE).exists());
    }
    @Test public void dismissOrDestroyOfTheDialogCannotGrantConsent() {
        open().dismiss(); Shadows.shadowOf(Looper.getMainLooper()).idle(); assertFalse(on());
        open(); dialogs.destroy(); Shadows.shadowOf(Looper.getMainLooper()).idle(); assertFalse(on());
    }
    @Test public void acceptingNewNoticeDoesNotExpandAnExistingUsersConsent() throws Exception {
        updatedFrom0633(true, true);
        app.getSharedPreferences(Consent.PREFS, Context.MODE_PRIVATE).edit()
                .putInt(Consent.ACCEPTED_VERSION, 15).commit();
        Consent.accept(app); assertFalse(on());
        DispatchStudyStore.flush(); FeedbackOutbox.flush();
        assertFalse(on());
        assertFalse(new File(app.getFilesDir(), DispatchStudyStore.FILE).exists());
    }
}
