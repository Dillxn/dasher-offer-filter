package com.local.dasherfilter;

import android.app.KeyguardManager;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.telephony.TelephonyManager;
import java.util.function.BooleanSupplier;

/**
 * Prevents an automatic screen timeout only during visible filtering. The service cannot use an activity's
 * KEEP_SCREEN_ON flag, so its screen lock is a short lease, renewed only after the current eligibility checks.
 * There is no wake-up flag, user-activity extension, or unlock operation. Android's power button still wins.
 * All calls and the supplied visibility check run on the main thread; the latter reads window metadata only.
 */
final class ScreenAwake {
    static final long CHECK_MS = 1_000;
    static final long LEASE_MS = 10_000;

    private final Context context;
    private final BooleanSupplier visibleFiltering;
    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock lease;
    private boolean running;
    private boolean screenOff;
    private final Runnable check = this::refresh;

    ScreenAwake(Context context, BooleanSupplier visibleFiltering) {
        this.context = context;
        this.visibleFiltering = visibleFiltering;
    }

    /** Idempotent: service events do not create extra timers or renew a lease without checking it. */
    void start() {
        if (running) return;
        running = true;
        refresh();
    }

    /** Service interruption or teardown immediately releases its claim and removes every renewal. */
    void stop() {
        running = false;
        main.removeCallbacks(check);
        release();
    }

    /** A deliberate power-button press cannot be undone by a queued event or renewal. */
    void screenOff() {
        screenOff = true;
        main.removeCallbacks(check);
        release();
    }

    /** Android reports a screen-on; eligibility still requires an unlocked, visible, active dash. */
    void screenOn() {
        screenOff = false;
        if (running) refresh();
    }

    @SuppressWarnings("deprecation") // No activity owns Dasher's window; this bounded service lease is intentional.
    private void refresh() {
        main.removeCallbacks(check);
        if (!running || screenOff) {
            release();
            return;
        }
        try {
            if (wanted(context) && visibleFiltering.getAsBoolean()) {
                if (lease == null) {
                    PowerManager power = context.getSystemService(PowerManager.class);
                    if (power != null) {
                        lease = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                                "OfferFilter:visible-filter");
                        lease.setReferenceCounted(false);
                    }
                }
                // Every renewal has a timeout; never use an unbounded acquire. Android schedules its release on
                // a handler, so this is not a guarantee against a stalled app or system scheduler.
                if (lease != null) lease.acquire(LEASE_MS);
            } else {
                release();
            }
        } catch (RuntimeException unavailable) {
            // A refused permission, unreadable phone state or window failure is never reason to keep it awake.
            release();
        }
        if (running && !screenOff) main.postDelayed(check, CHECK_MS);
    }

    /** Shared with the homepage's own window flag; this never changes brightness or the phone's timeout setting. */
    static boolean wanted(Context context) {
        if (!Consent.accepted(context)) return false;
        FilterSettings rules = FilterStore.load(context);
        if (!rules.enabled || !rules.hasAnyRule() || !Dashing.on(context) || Dashing.isPaused(context)) return false;
        try {
            PowerManager power = context.getSystemService(PowerManager.class);
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            if (power == null || !power.isInteractive() || keyguard == null || keyguard.isKeyguardLocked()) return false;
            AudioManager audio = context.getSystemService(AudioManager.class);
            if (audio == null || audio.getMode() != AudioManager.MODE_NORMAL) return false;
            // As Peek does: phone state needs no permission before Android 12; audio mode guards newer phones.
            if (Build.VERSION.SDK_INT < 31 && legacyCall(context)) return false;
            return true;
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean legacyCall(Context context) {
        try {
            TelephonyManager phone = context.getSystemService(TelephonyManager.class);
            return phone != null && phone.getCallState() != TelephonyManager.CALL_STATE_IDLE;
        } catch (RuntimeException unavailable) {
            // No additional phone permission: the audio-mode check above still applies.
            return false;
        }
    }

    private void release() {
        if (lease == null) return;
        try {
            if (lease.isHeld()) lease.release();
        } catch (RuntimeException alreadyGone) {
            // The timeout or Android may already have released it.
        }
    }
}
