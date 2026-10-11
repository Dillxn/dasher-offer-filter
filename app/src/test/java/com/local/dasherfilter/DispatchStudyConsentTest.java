package com.local.dasherfilter;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;
import android.widget.Switch;
import java.io.File;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
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
        DispatchStudyStore.confirm(app, false); DispatchStudyStore.flush(); FeedbackOutbox.flush();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private AlertDialog open() { dialogs.study(); return (AlertDialog) ShadowDialog.getLatestDialog(); }
    private Switch choice(AlertDialog d) { return find(d.getWindow().getDecorView(), Switch.class); }
    private void confirm(AlertDialog d) {
        assertEquals("Save choice", d.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle(); DispatchStudyStore.flush();
    }
    @Test public void checkedPresentationPreviewAndCancelCannotEnableAnything() {
        AlertDialog d = open(); assertTrue(choice(d).isChecked()); assertFalse(DispatchStudyStore.enabled(app));
        assertFalse(DispatchStudyStore.prefs(app).contains("version"));
        shownButton(d.getWindow().getDecorView(), "Preview study data").performClick();
        DispatchStudyStore.flush(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(DispatchStudyStore.enabled(app));
        d.cancel(); Shadows.shadowOf(Looper.getMainLooper()).idle(); assertFalse(DispatchStudyStore.enabled(app));
        assertFalse(DispatchStudyStore.checkedDraft(app));
    }
    @Test public void affirmativeConfirmEnablesOnlyNewScopeAndNeverAutomaticSharing() {
        AlertDialog d = open(); confirm(d);
        assertTrue(DispatchStudyStore.enabled(app)); assertFalse(Feedback.afterDashOn(app));
        assertEquals("", DispatchStudyStore.section(app));
        assertTrue(FeedbackDialogs.STUDY_COPY.contains("nothing starts until you tap Save choice"));
        assertTrue(FeedbackDialogs.STUDY_COPY.contains("already in flight"));
    }
    @Test public void uncheckedConfirmationAndLaterReopeningPreserveRefusal() {
        AlertDialog d = open(); choice(d).setChecked(false); confirm(d);
        assertFalse(DispatchStudyStore.enabled(app));
        assertFalse(choice(open()).isChecked());
    }
    @Test public void priorExplicitAfterDashOptOutIsNeverReenabledOrPrechecked() {
        Feedback.setAfterDash(app, false);
        assertFalse(choice(open()).isChecked()); assertFalse(Feedback.afterDashOn(app));
        AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog(); choice(d).setChecked(true); confirm(d);
        assertTrue(DispatchStudyStore.enabled(app)); assertFalse(Feedback.afterDashOn(app));
    }
    @Test public void existingAfterDashOptInIsNotStudyConsentAndStaysSeparateOnWithdrawal() {
        Feedback.setAfterDash(app, true); assertFalse(DispatchStudyStore.enabled(app));
        confirm(open()); assertTrue(Feedback.afterDashOn(app));
        AlertDialog d = open(); choice(d).setChecked(false); confirm(d);
        assertFalse(DispatchStudyStore.enabled(app)); assertTrue(Feedback.afterDashOn(app));
    }
    @Test public void dismissOrDestroyOfTheDraftCannotGrantConsent() {
        open().dismiss(); assertFalse(DispatchStudyStore.enabled(app));
        open(); dialogs.destroy(); assertFalse(DispatchStudyStore.enabled(app));
    }
    @Test public void acceptingNewNoticeDoesNotExpandAnExistingUsersConsent() {
        app.getSharedPreferences(Consent.PREFS, Context.MODE_PRIVATE).edit()
                .putInt(Consent.ACCEPTED_VERSION, 15).commit();
        DispatchStudyStore.confirm(app, true); assertFalse(DispatchStudyStore.enabled(app));
        Consent.accept(app); assertFalse(DispatchStudyStore.enabled(app));
        assertFalse(new File(app.getFilesDir(), DispatchStudyStore.FILE).exists());
    }
}
