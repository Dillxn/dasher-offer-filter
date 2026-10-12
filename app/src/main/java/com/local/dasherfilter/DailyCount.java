package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The anonymous daily count (the owner, 12 October 2026: "i want to keep track of if anyone besides me downloads the
 * app"): at most once each UTC day, one request to the feedback service ({@link Feedback#ENDPOINT}, through
 * {@link Feedback#transport}, so with no Origin header, as every request of the app's) saying only that an install of
 * this version is in use today, and whether the service has counted this install before. Its body is exactly
 * {@code {"kind":"count","event":"daily","countToken":…,"first":…,"appVersion":…,"appVersionCode":…}}
 * ({@link #body}): no message, diagnostics, category, report token, part, device, Android version, maker, locale, time
 * zone or identifier of any kind. The token is random, made fresh for each UTC day and sent again only to retry that
 * day's count, so the service stores the day's count once however often it is retried, and no two days' counts can be
 * tied together by it; it is deleted once the day's count is finished, or the next time this class looks after its
 * day is over, whatever the notice and the switch say then. {@code first} is true until the service has accepted a
 * count from this install, and false from then on, so the service's daily totals tell new installs from ones counted
 * before without anything that names either. The service keeps daily totals by version (and, briefly, the day's
 * token and a one-way value of the network, so each counts once: PRIVACY.md).
 *
 * <p>On by default (disclosed in the notice) and turned off by one switch in Settings, {@value #SWITCH}. Nothing is
 * sent before the current notice is accepted, while the switch is off, or twice in a UTC day, and nothing on the main
 * thread: the work runs on this class's own thread, the job's included ({@link DailyCountJobService}, a persisted job
 * about once a day on any network). The triggers ({@link #trigger}): accepting the notice, the app opening, either
 * service connecting, the app updated and the phone started; each only hands the work to that thread. The UTC day is
 * read from the wall clock carried forward on the boot's own clock from the last finished count, while that was in
 * this boot ({@link #now}), so the phone's date set forward or back by hand, or corrected by the network, sends no
 * second count the service would take for another; across a reboot, or when the boot cannot be told, the wall clock
 * alone. The switch turned off with the day's count tried and not finished finishes the day as it is (its reply may
 * have been lost after the service counted it), so turning it on again that day sends nothing until the next.
 *
 * <p>The service's answer ({@link #outcome}): any 2xx finishes the day's count, and the install has been counted; 400,
 * 413, 415, 422 or 429 finishes the day with no retry, and the install is not marked counted (the function deployed
 * before counts answers 400 to every count, so an install keeps saying {@code first} until a function that counts
 * accepts it); a server error, no connection or any other answer is tried again later that day with the same token,
 * by the job alone, no sooner than {@link #FIRST_RETRY_MS} and then with its exponential backoff: a trigger leaves a
 * waiting retry to it, so opening the app often does not press a service that asked for time. Each change of outcome
 * is one line of fixed words in the diagnostic log (never the token or the body), the same one at most once a day; the
 * last is kept among the log's latest states.
 */
final class DailyCount {
    static final String PREFS = "daily_count";
    /** The switch: on unless the user turned it off. */
    static final String ON = "on";
    /** The UTC day (days since 1 January 1970, UTC) whose count is finished: counted, not accepted, or switched off. */
    static final String DONE_DAY = "done_day";
    /**
     * When that day's count was finished: as {@link #now} had it, on the boot's own clock at the same moment, and
     * Android's boot count then. A later look in this boot carries the day forward from there on a clock no one sets.
     */
    static final String DONE_AT = "done_at";
    static final String DONE_ELAPSED = "done_elapsed";
    static final String DONE_BOOT = "done_boot";
    /** The day's token, kept only to retry that day's count, and the UTC day it was made for. */
    static final String TOKEN = "token";
    static final String TOKEN_DAY = "token_day";
    /** The service has accepted a count from this install: {@code first} is false from then on. */
    static final String COUNTED = "counted";
    static final long NONE = Long.MIN_VALUE;
    static final long DAY_MS = 24 * 3_600_000L;
    /** A count that will be retried waits at least this long first; the job's backoff doubles it from there. */
    static final long FIRST_RETRY_MS = 10 * 60_000L;
    /** The next day's job comes at a random time in this long after the UTC day begins, so not every phone at once. */
    static final long NEXT_DAY_SPREAD_MS = 2 * 3_600_000L;
    static final String SWITCH = "Send an anonymous daily count";
    static final String SWITCH_LINE = "The app's version and whether it was counted before, once a day";
    /** The diagnostic log's tag for these lines, and the topic each outcome is kept under. */
    static final String LOG = "count";
    private static final String TOPIC = "daily";
    private static final Pattern TOKEN_SHAPE = Pattern.compile("[0-9a-f]{32}");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Sends one at a time, never on the main thread: the triggers' work and the job's. */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "daily-count");
        thread.setDaemon(true);
        return thread;
    });
    /** Whether the triggers do anything: off in tests (ConsentedTestApp), so only DailyCountTest sees a count. */
    static volatile boolean automatic = true;
    /** The wall clock the UTC day is read from; tests move it. */
    static volatile LongSupplier clock = System::currentTimeMillis;
    /** The boot's own clock (elapsed time, which runs in deep sleep), which carries the day forward. */
    static final LongSupplier BOOT_CLOCK = () -> SystemClock.elapsedRealtime();
    /** {@link #BOOT_CLOCK}; tests move it. */
    static volatile LongSupplier elapsed = BOOT_CLOCK;

    /** What became of one look at the day's count. */
    enum Outcome {
        /** Nothing sent: the notice not accepted, the switch off, or today's count already finished. */
        NONE,
        /** Accepted (any 2xx): today is finished, and the install has been counted. */
        COUNTED,
        /** Not accepted (400, 413, 415, 422 or 429): today is finished, with no retry, and the install not counted. */
        REFUSED,
        /** No connection, a server error or any other answer: tried again later today, with the same token. */
        RETRY
    }

    // ---- Pure parts ----

    /** The service's answer to a count, as the app takes it. */
    static Outcome outcome(int status) {
        if (status >= 200 && status < 300) return Outcome.COUNTED;
        if (status == 400 || status == 413 || status == 415 || status == 422 || status == 429) return Outcome.REFUSED;
        return Outcome.RETRY;
    }

    /** The UTC day of {@code wallMs}: days since 1 January 1970, UTC, whatever the phone's time zone. */
    static long utcDay(long wallMs) {
        return Math.floorDiv(wallMs, DAY_MS);
    }

    /** From {@code wallMs} until the next UTC day begins, and {@code spreadMs} more. */
    static long untilNextDay(long wallMs, long spreadMs) {
        return (utcDay(wallMs) + 1) * DAY_MS - wallMs + Math.max(0, spreadMs);
    }

    /** A fresh random token, 32 lowercase hex digits: one per UTC day, never per phone or install. */
    static String newToken() {
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : random) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return hex.toString();
    }

    static boolean validToken(String token) {
        return token != null && TOKEN_SHAPE.matcher(token).matches();
    }

    /** The whole request: these six fields and nothing else. */
    static JSONObject body(String token, boolean first, String appVersion, long versionCode) {
        try {
            return new JSONObject()
                    .put("kind", "count")
                    .put("event", "daily")
                    .put("countToken", token)
                    .put("first", first)
                    .put("appVersion", appVersion == null ? "unknown" : appVersion)
                    .put("appVersionCode", versionCode);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The log's words for an outcome: fixed, with the answer's status at most, never the token or the body. */
    static String said(Outcome outcome, int status) {
        switch (outcome) {
            case COUNTED:
                return "daily count sent";
            case REFUSED:
                return "daily count not accepted (status " + status + "): not retried today";
            case RETRY:
                return status <= 0 ? "daily count will retry: no connection" : "daily count will retry: status "
                        + status;
            default:
                return "daily count not sent";
        }
    }

    // ---- The switch ----

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** {@value #SWITCH}: on unless the user turned it off. */
    static boolean on(Context context) {
        return prefs(context).getBoolean(ON, true);
    }

    /**
     * The user's choice. Any thread, the main one included: it holds at once (in memory; written to disk in the
     * background), and the rest goes on this class's thread, after any count under way: off cancels the job and
     * discards the day's token, and finishes the day when its count was tried and not finished (the service may have
     * counted it, its answer lost), so on again that day sends nothing until the next; on looks at today's count at
     * once.
     */
    static void setOn(Context context, boolean on) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = prefs(app);
        if (prefs.getBoolean(ON, true) == on) return;
        prefs.edit().putBoolean(ON, on).apply();
        WORKER.execute(() -> {
            try {
                if (on) {
                    DiagnosticLog.log(app, LOG, "daily count turned on");
                    countAndSchedule(app);
                } else {
                    DailyCountJobService.cancel(app);
                    turnedOff(app);
                    DiagnosticLog.log(app, LOG, "daily count turned off; today's token discarded");
                }
            } catch (RuntimeException unexpected) {
                // On a background thread an escaping exception would take the whole app, screen reader included.
                DiagnosticLog.log(app, LOG, "daily count failed: " + unexpected.getClass().getSimpleName());
            }
        });
    }

    /**
     * The switch turned off: the day's token goes, and a day whose count was tried (its token is the day's) and is not
     * finished is finished as it is. A retry might have counted it twice otherwise: the first try may have reached the
     * service and been counted, its answer lost.
     */
    private static synchronized void turnedOff(Context app) {
        SharedPreferences prefs = prefs(app);
        long today = utcDay(now(app, prefs));
        SharedPreferences.Editor off = prefs.edit().remove(TOKEN).remove(TOKEN_DAY);
        if (prefs.getLong(TOKEN_DAY, NONE) == today && prefs.getLong(DONE_DAY, NONE) != today) {
            finish(app, prefs, off, today);
        }
        off.commit();
    }

    // ---- The day ----

    /**
     * The time the day's count goes by: the wall clock, unless the last finished count was in this boot, when it is that
     * count's time carried forward on the boot's own clock. So setting the phone's date forward and back by hand, or a
     * network correction of it, sends no second count the same day (the service dates each count by its own clock and
     * cannot tie a new token to the first); the day still turns once a day of real time has passed. Across a reboot, or
     * when the boot cannot be told, the wall clock alone. On this class's thread (a prefs read and Android's boot count).
     */
    static long now(Context app, SharedPreferences prefs) {
        return now(app, prefs, elapsed.getAsLong());
    }

    /** {@link #now} at {@code elapsedMs} on the boot's own clock. */
    private static long now(Context app, SharedPreferences prefs, long elapsedMs) {
        long wall = clock.getAsLong();
        int then = prefs.getInt(DONE_BOOT, -1);
        if (then < 0 || then != boot(app) || !prefs.contains(DONE_ELAPSED) || !prefs.contains(DONE_AT)) return wall;
        long since = elapsedMs - prefs.getLong(DONE_ELAPSED, 0);
        return since >= 0 ? prefs.getLong(DONE_AT, wall) + since : wall;
    }

    /** Android's count of boots, or -1 when it cannot be read. */
    private static int boot(Context app) {
        try {
            return Settings.Global.getInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }

    /**
     * Finishes {@code day} in {@code edit}, with its token, and keeps when: as {@link #now} has it and on the boot's own
     * clock at the same moment, with the boot. A count whose answer came after the day ended still finishes its own
     * day; the next one is due at once then.
     */
    private static void finish(Context app, SharedPreferences prefs, SharedPreferences.Editor edit, long day) {
        long at = elapsed.getAsLong();
        edit.putLong(DONE_DAY, day).putLong(DONE_AT, now(app, prefs, at)).putLong(DONE_ELAPSED, at)
                .putInt(DONE_BOOT, boot(app)).remove(TOKEN).remove(TOKEN_DAY);
    }

    /**
     * A token whose UTC day is over can retry nothing: it goes, whatever the notice and the switch say, so a code never
     * outlives its day on the phone (a raised notice can keep counts waiting for days).
     */
    private static synchronized void forgetOldToken(Context app) {
        SharedPreferences prefs = prefs(app);
        if (!prefs.contains(TOKEN) && !prefs.contains(TOKEN_DAY)) return;
        if (prefs.getLong(TOKEN_DAY, NONE) != utcDay(now(app, prefs))) {
            prefs.edit().remove(TOKEN).remove(TOKEN_DAY).commit();
        }
    }

    /**
     * Today's count was tried, is to be retried and its job waits (or runs): the retry is the job's, with Android's
     * backoff, which a trigger's own try would set back to its first wait.
     */
    private static boolean retryWaits(Context app) {
        SharedPreferences prefs = prefs(app);
        long today = utcDay(now(app, prefs));
        if (prefs.getLong(DONE_DAY, NONE) == today) return false;
        if (prefs.getLong(TOKEN_DAY, NONE) != today || !validToken(prefs.getString(TOKEN, ""))) return false;
        return DailyCountJobService.waiting(app);
    }

    // ---- Sending ----

    /**
     * The notice accepted, the app opened, a service connected, the app updated or the phone started: today's count goes
     * if it is due, and the job that sends the next one is kept scheduled. Any thread; nothing waits for the disk or the
     * network here.
     */
    static void trigger(Context context) {
        if (!automatic) return;
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                countAndSchedule(app);
            } catch (RuntimeException unexpected) {
                DiagnosticLog.log(app, LOG, "daily count failed: " + unexpected.getClass().getSimpleName());
            }
        });
    }

    /** Runs the job's work on this class's thread, after any count under way. */
    static void runOnWorker(Runnable work) {
        WORKER.execute(work);
    }

    /**
     * Today's count if due, then the job: a retry soon after a count to try again, else the next day's unless one is
     * already waiting. A retry already waiting is left to the job and its backoff. Before the notice is accepted nothing
     * is sent or scheduled (only a token whose day is over goes); with the switch off the job goes. On this class's
     * thread.
     */
    private static void countAndSchedule(Context app) {
        forgetOldToken(app);
        if (!Consent.accepted(app)) return;
        if (!on(app)) {
            DailyCountJobService.cancel(app);
            return;
        }
        if (retryWaits(app)) return;
        Outcome outcome = sendIfDue(app);
        if (outcome == Outcome.RETRY) DailyCountJobService.schedule(app, FIRST_RETRY_MS);
        else DailyCountJobService.scheduleIfNone(app, nextDayDelay(app));
    }

    /** How long until the next day's job: the next UTC day ({@link #now}), at a random time in its first hours. */
    static long nextDayDelay(Context context) {
        Context app = context.getApplicationContext();
        return untilNextDay(now(app, prefs(app)), (long) (RANDOM.nextDouble() * NEXT_DAY_SPREAD_MS));
    }

    /**
     * Sends today's count, unless the notice is not accepted, the switch is off or today's count is finished, and keeps
     * what the answer means. Never on the main thread: the request can take up to a minute.
     */
    static synchronized Outcome sendIfDue(Context context) {
        Context app = context.getApplicationContext();
        forgetOldToken(app);
        if (!Consent.accepted(app) || !on(app)) return Outcome.NONE;
        SharedPreferences prefs = prefs(app);
        long today = utcDay(now(app, prefs));
        if (prefs.getLong(DONE_DAY, NONE) == today) return Outcome.NONE;
        String token = prefs.getString(TOKEN, "");
        if (prefs.getLong(TOKEN_DAY, NONE) != today || !validToken(token)) {
            // A new day, a new token: yesterday's, if it was never finished, goes unused.
            token = newToken();
            prefs.edit().putString(TOKEN, token).putLong(TOKEN_DAY, today).commit();
        }
        boolean first = !prefs.getBoolean(COUNTED, false);
        byte[] request = body(token, first, Updater.version(app), Feedback.versionCode(app)).toString()
                .getBytes(StandardCharsets.UTF_8);
        Outcome outcome;
        int status;
        try {
            Feedback.Response response = Feedback.transport.post(request);
            status = response.status;
            outcome = outcome(status);
        } catch (IOException offline) {
            status = 0;
            outcome = Outcome.RETRY;
        }
        if (outcome != Outcome.RETRY) {
            // Finished for today either way: the token goes with it. Only an acceptance counts the install.
            SharedPreferences.Editor finished = prefs.edit();
            finish(app, prefs, finished, today);
            if (outcome == Outcome.COUNTED) finished.putBoolean(COUNTED, true);
            finished.commit();
        }
        DiagnosticLog.logOnChange(app, LOG, TOPIC, said(outcome, status));
        return outcome;
    }

    /** Today's count ({@link #now}) is finished: counted, not accepted or switched off. Off the main thread. */
    static boolean finishedToday(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = prefs(app);
        return prefs.getLong(DONE_DAY, NONE) == utcDay(now(app, prefs));
    }

    /** Waits for the counts and job work queued so far; for tests. */
    static void flush() {
        try {
            WORKER.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } catch (Exception interruptedOrTimedOut) {
            if (interruptedOrTimedOut instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    private DailyCount() {}
}
