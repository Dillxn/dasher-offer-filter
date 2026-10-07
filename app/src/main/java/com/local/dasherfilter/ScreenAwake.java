package com.local.dasherfilter;

import android.app.KeyguardManager;
import android.content.Context;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.telephony.TelephonyManager;
import java.util.function.BooleanSupplier;

/**
 * Holds off the automatic screen timeout during an active dash (the owner: "Don't let it lock mid-dash"), whatever app
 * is in front: Dasher, a map, Offer Filter or anything else, so a background offer can still be peeked at. Only with
 * the current notice accepted, auto-decline on with a rule, a dash under way and not paused in Dasher, the phone on and
 * unlocked, no call, battery saver off and the battery above {@link #LOW_BATTERY_PERCENT} % or charging, and only on
 * positive signs of the dash: something of it seen within {@link #FRESH_MS} (an offer or its notification, Dasher's
 * wait for offers, a pickup or delivery screen), or Dasher in front showing one of those now; never on a stored route
 * alone, and never after Dasher's home before a dash was read until the dash is seen again. So a dash whose end was
 * never seen, or a route left stored, does not keep an unlocked phone on for long. The service cannot use an
 * activity's KEEP_SCREEN_ON flag, so its screen lock is a short lease, renewed only after these checks. There is no
 * wake-up flag, user-activity extension, or unlock operation: it never wakes or unlocks the phone, and the power button
 * still wins (the screen going off ends it until the phone is unlocked again). It looks at no app's windows or words
 * but Dasher's own (whether Dasher's window is in front, and what the screen reader last read of it). All calls and
 * the supplied checks run on the main thread.
 */
final class ScreenAwake {
    static final long CHECK_MS = 1_000;
    static final long LEASE_MS = 10_000;
    /** At or below this charge, and not charging, the screen is let time out (battery saver on does the same). */
    static final int LOW_BATTERY_PERCENT = 20;
    /**
     * The dash must have been seen this recently (an offer or its notification, Dasher's wait for offers, a pickup or
     * delivery screen), unless Dasher in front shows it now: well short of the half hour after which a dash counts as
     * over, and of the hours a stored route may last.
     */
    static final long FRESH_MS = 15 * 60_000L;

    private final Context context;
    private final BooleanSupplier filtering;
    private final BooleanSupplier dashShowing;
    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock lease;
    private boolean running;
    private boolean screenOff;
    /** What the log last said of the lease ("held", or why not), so each change is said once. */
    private String said = "";
    private final Runnable check = this::refresh;

    /** @param filtering whether the screen reader is up to filter (connected, not stopped or failed) */
    ScreenAwake(Context context, BooleanSupplier filtering) {
        this(context, filtering, () -> false);
    }

    /**
     * @param dashShowing whether Dasher is readable in front (full screen, or its half of a split) and the screen
     *     reader's last read of it found a screen of the dash (an offer, its wait for offers, a pickup or delivery
     *     screen): the dash is on screen now, however long ago it was last seen to change
     */
    ScreenAwake(Context context, BooleanSupplier filtering, BooleanSupplier dashShowing) {
        this.context = context;
        this.filtering = filtering;
        this.dashShowing = dashShowing;
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
        say("released: screen reading stopped");
    }

    /** A deliberate power-button press cannot be undone by a queued event or renewal. */
    void screenOff() {
        screenOff = true;
        main.removeCallbacks(check);
        release();
        say("released: screen off");
    }

    /** Android reports a screen-on; eligibility still requires an unlocked phone and an active dash. */
    void screenOn() {
        screenOff = false;
        if (running) refresh();
    }

    /** Whether the lease is held now (tests). */
    boolean held() {
        return lease != null && lease.isHeld();
    }

    @SuppressWarnings("deprecation") // No activity owns the window in front; this bounded service lease is intentional.
    private void refresh() {
        main.removeCallbacks(check);
        if (!running || screenOff) {
            release();
            return;
        }
        String why;
        try {
            // The notice and the phone's own state first: nothing more is asked while any of them says no.
            why = whyNot(context, dashShowing);
            if (why == null && !filtering.getAsBoolean()) why = "screen reading stopped";
            if (why == null) {
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
            // A refused permission or unreadable phone state is never reason to keep it awake.
            why = "phone state unknown";
            release();
        }
        say(why == null ? "held: a dash is under way (any app in front)" : "released: " + why);
        if (running && !screenOff) main.postDelayed(check, CHECK_MS);
    }

    /** Each change of the lease, once, in the log: held, or why not (fixed words only). */
    private void say(String state) {
        if (state.equals(said)) return;
        said = state;
        DiagnosticLog.log(context, "awake", "screen timeout " + state);
    }

    /**
     * Shared with the homepage's own window flag (Offer Filter in front, so the dash must have been seen within
     * {@link #FRESH_MS}); this never changes brightness or the phone's timeout setting.
     */
    static boolean wanted(Context context) {
        return whyNot(context) == null;
    }

    /** {@link #whyNot(Context, BooleanSupplier)} with no screen of the dash in front. */
    static String whyNot(Context context) {
        return whyNot(context, () -> false);
    }

    /**
     * Why the screen is not held on now (fixed words), or null when it is: the notice not accepted, auto-decline off or
     * without a rule, no dash under way or the dash paused in Dasher, Dasher's home read since the dash was last seen,
     * the screen off or the phone locked, a call, battery saver on, the battery at {@link #LOW_BATTERY_PERCENT} % or
     * less and not charging, or nothing of the dash seen for {@link #FRESH_MS} with no screen of it in front.
     *
     * @param dashShowing asked last, and only when nothing of the dash was seen within {@link #FRESH_MS}
     */
    static String whyNot(Context context, BooleanSupplier dashShowing) {
        if (!Consent.accepted(context)) return "the notice isn't accepted";
        FilterSettings rules = FilterStore.load(context);
        if (!rules.enabled) return "auto-decline is paused";
        if (!rules.hasAnyRule()) return "no rules are set";
        if (!Dashing.on(context)) return "no dash under way";
        if (Dashing.isPaused(context)) return "the dash is paused";
        if (Dashing.homeShown()) return "Dasher's home shows no dash";
        try {
            PowerManager power = context.getSystemService(PowerManager.class);
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            if (power == null || !power.isInteractive()) return "screen off";
            if (keyguard == null || keyguard.isKeyguardLocked()) return "locked";
            AudioManager audio = context.getSystemService(AudioManager.class);
            if (audio == null || audio.getMode() != AudioManager.MODE_NORMAL) return "a call";
            // As Peek does: phone state needs no permission before Android 12; audio mode guards newer phones.
            if (Build.VERSION.SDK_INT < 31 && legacyCall(context)) return "a call";
            if (power.isPowerSaveMode()) return "battery saver is on";
            if (lowBattery(context)) return "battery low";
        } catch (RuntimeException unknown) {
            return "phone state unknown";
        }
        // Only fresh signs of the dash hold it (never a stored route alone): a dash whose end was never seen does not
        // keep an unlocked phone on for long.
        if (!Dashing.sawDashWithin(FRESH_MS) && !dashShowing.getAsBoolean()) {
            return "nothing of the dash seen for " + FRESH_MS / 60_000 + " minutes";
        }
        return null;
    }

    /** The battery at {@link #LOW_BATTERY_PERCENT} % or less and not charging; false when Android does not say. */
    private static boolean lowBattery(Context context) {
        BatteryManager battery = context.getSystemService(BatteryManager.class);
        if (battery == null || battery.isCharging()) return false;
        int level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        // Android says MIN_VALUE (or, before Android 9, 0) when it cannot tell: not taken for a low battery.
        return level > 0 && level <= LOW_BATTERY_PERCENT;
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
