package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAudioManager;

import static org.junit.Assert.*;

/** Abrupt process death and an OS refusing volume changes are different from a successful mute. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, shadows = OfferSilencerRecoveryTest.FaultAudioManager.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class OfferSilencerRecoveryTest {
    private Application app;
    private AudioManager audio;
    private OfferSilencer silencer;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        audio = app.getSystemService(AudioManager.class);
        FaultAudioManager.crashAfterSet = false;
        FaultAudioManager.ignoreSet = false;
        FaultAudioManager.refuseSet = false;
        prefs().edit().clear().commit();
        OfferSilencer.forgetCache();
        Updater.setEnabled(app, false);
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        Shadows.shadowOf(audio).setActivePlaybackConfigurationsFor(Collections.singletonList(
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()), false);
        silencer = new OfferSilencer(app, new Handler(Looper.getMainLooper()), () -> true);
    }

    @After public void teardown() {
        FaultAudioManager.crashAfterSet = false;
        FaultAudioManager.ignoreSet = false;
        FaultAudioManager.refuseSet = false;
        silencer.stop();
    }

    @Test public void processDeathInsideVolumeSetStillLeavesEnoughPersistedEvidenceToRestore() {
        FaultAudioManager.crashAfterSet = true;
        try {
            silencer.start();
            fail("simulated abrupt death after Android changed volume");
        } catch (SimulatedProcessDeath expected) {
            // Neither the silencer's post-call readback nor normal stop ran.
        }
        FaultAudioManager.crashAfterSet = false;
        assertTrue("the intended level was saved before setting it", prefs().contains("set_" + AudioManager.STREAM_ALARM));
        OfferSilencer.restore(app);
        assertEquals(5, audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertTrue(prefs().getAll().isEmpty());
    }

    @Test public void ignoredVolumeChangeIsReportedAndNeverClaimedAsSilenced() {
        DiagnosticLog.clear(app);
        FaultAudioManager.ignoreSet = true;
        silencer.start();
        assertEquals(5, audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertTrue(prefs().getAll().isEmpty());
        String report = DiagnosticLog.read(app);
        assertTrue(report, report.contains("silencer: volume unchanged (alarm)"));
        assertFalse(report, report.contains("silenced alarm"));
    }

    @Test public void aLevelBelowTheSavedFloorIsRestoredButAUsersHigherLevelIsPreserved() {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0);
        prefs().edit().putInt("stream_" + AudioManager.STREAM_ALARM, 5)
                .putInt("set_" + AudioManager.STREAM_ALARM, 2).commit();
        OfferSilencer.restore(app);
        assertEquals(5, audio.getStreamVolume(AudioManager.STREAM_ALARM));
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 4, 0);
        prefs().edit().putInt("stream_" + AudioManager.STREAM_ALARM, 5)
                .putInt("set_" + AudioManager.STREAM_ALARM, 2).commit();
        OfferSilencer.restore(app);
        assertEquals(4, audio.getStreamVolume(AudioManager.STREAM_ALARM));
    }

    @Test public void bootRestoresWithoutWaitingForAccessibilityToReconnect() {
        saveInterruptedAlarm();
        new UpdateReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertEquals(5, audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertTrue(prefs().getAll().isEmpty());
    }

    @Test public void packageReplacementRestoresWithoutWaitingForAccessibilityToReconnect() {
        saveInterruptedAlarm();
        new UpdateReceiver().onReceive(app, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        assertEquals(5, audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertTrue(prefs().getAll().isEmpty());
    }

    @Test public void deniedRestoreKeepsTheReceiptUntilAndroidAllowsIt() {
        saveInterruptedAlarm();
        FaultAudioManager.refuseSet = true;
        OfferSilencer.restore(app);
        assertEquals(1, audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertEquals(5, prefs().getInt("stream_" + AudioManager.STREAM_ALARM, -1));
        FaultAudioManager.refuseSet = false;
        OfferSilencer.restore(app);
        assertEquals(5, audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertTrue(prefs().getAll().isEmpty());
    }

    @Test public void ignoredRestoreKeepsTheReceiptUntilTheVolumeActuallyReturns() {
        saveInterruptedAlarm();
        FaultAudioManager.ignoreSet = true;
        OfferSilencer.restore(app);
        assertEquals(1, audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertEquals(5, prefs().getInt("stream_" + AudioManager.STREAM_ALARM, -1));
        FaultAudioManager.ignoreSet = false;
        OfferSilencer.restore(app);
        assertEquals(5, audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertTrue(prefs().getAll().isEmpty());
    }

    @Test public void unavailableAudioManagerDoesNotForgetTheInterruptedVolume() {
        saveInterruptedAlarm();
        OfferSilencer.restore(new ContextWrapper(app) {
            @Override public Object getSystemService(String name) {
                return Context.AUDIO_SERVICE.equals(name) ? null : super.getSystemService(name);
            }
        });
        assertEquals(5, prefs().getInt("stream_" + AudioManager.STREAM_ALARM, -1));
        OfferSilencer.restore(app);
        assertEquals(5, audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertTrue(prefs().getAll().isEmpty());
    }

    private void saveInterruptedAlarm() {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0);
        prefs().edit().putInt("stream_" + AudioManager.STREAM_ALARM, 5)
                .putInt("set_" + AudioManager.STREAM_ALARM, 1).commit();
    }

    private SharedPreferences prefs() { return app.getSharedPreferences("silencer", Context.MODE_PRIVATE); }

    private static final class SimulatedProcessDeath extends Error {}

    @Implements(AudioManager.class)
    public static class FaultAudioManager extends ShadowAudioManager {
        static boolean crashAfterSet;
        static boolean ignoreSet;
        static boolean refuseSet;

        @Implementation @Override public void setStreamVolume(int stream, int index, int flags) {
            if (refuseSet && stream == AudioManager.STREAM_ALARM) throw new SecurityException("volume denied");
            if (ignoreSet && stream == AudioManager.STREAM_ALARM) return;
            super.setStreamVolume(stream, index, flags);
            if (crashAfterSet && stream == AudioManager.STREAM_ALARM) throw new SimulatedProcessDeath();
        }
    }
}
