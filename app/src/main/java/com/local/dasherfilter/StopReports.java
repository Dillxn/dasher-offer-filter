package com.local.dasherfilter;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * When the app stopped unexpectedly (crashed, or Android found it not responding), a short summary of it, kept on the
 * phone for 24 hours: the kind of stop, the minute (UTC), whether the app was in front, and where in the code it
 * happened (an exception's type and the top of its stack, or the top of the main thread in an ANR's trace). Never an
 * exception's message, a trace's other text or anything read from a screen.
 *
 * <p>Read on the next start ({@link #checkSoon}) from Android's record of how the app's process exited (API 30+),
 * matched with the note this process wrote as it crashed ({@link #install}). With diagnostics after each dash on, the
 * summaries go with the next one; otherwise the homepage offers to send a report, and only the user's Send sends it.
 */
final class StopReports {
    static final long KEEP_MS = 24 * 3_600_000L;
    private static final int MAX_KEPT = 5;
    private static final int MAX_FRAMES = 8;
    /** A crash note and Android's record of the same stop are this close in time at most. */
    private static final long MATCH_MS = 60_000L;
    static final String NOTE = "last-stop.json";
    private static final String PREFS = "stop_reports";
    private static final String KEPT = "kept";
    private static final String SEEN_UNTIL = "seen_until";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "stop-reports");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile boolean installed;
    private static volatile Object checkedFor;

    /** One stop, as kept and as a report carries it. */
    static final class Stop {
        final long at;
        final String kind;
        final String where;
        final boolean acknowledged;

        Stop(long at, String kind, String where, boolean acknowledged) {
            this.at = at;
            this.kind = kind;
            this.where = where;
            this.acknowledged = acknowledged;
        }

        String line() {
            return OfferReport.minute(at) + " · " + kind + (where.isEmpty() ? "" : "\n" + where);
        }
    }

    /**
     * Notes a crash of this process before Android ends it: the exception's type, causes and top frames, never its
     * message. Once per process; the previous handler (Android's) runs right after.
     */
    static void install(Context context) {
        if (installed) return;
        synchronized (StopReports.class) {
            if (installed) return;
            installed = true;
            File note = new File(context.getApplicationContext().getFilesDir(), NOTE);
            String version = Updater.version(context.getApplicationContext());
            Thread.setDefaultUncaughtExceptionHandler(handler(note, version,
                    Thread.getDefaultUncaughtExceptionHandler()));
        }
    }

    /** Writes the note, then hands the crash on to {@code previous} (Android's, which ends the process). */
    static Thread.UncaughtExceptionHandler handler(File note, String version,
                                                   Thread.UncaughtExceptionHandler previous) {
        return (thread, error) -> {
            try {
                write(note, version, error);
            } catch (Throwable ignored) {
                // Never in the way of the crash itself.
            }
            if (previous != null) previous.uncaughtException(thread, error);
        };
    }

    static void write(File note, String version, Throwable error) throws IOException, JSONException {
        JSONObject json = new JSONObject().put("at", System.currentTimeMillis()).put("version", version)
                .put("where", where(error));
        try (FileOutputStream out = new FileOutputStream(note)) {
            out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }

    /** "java.lang.IllegalStateException\n  at com.…(File.java:12)\n  caused by …": types and frames, no messages. */
    static String where(Throwable error) {
        StringBuilder out = new StringBuilder(error.getClass().getName());
        StackTraceElement[] stack = error.getStackTrace();
        for (int i = 0; i < Math.min(MAX_FRAMES, stack.length); i++) out.append("\n  at ").append(stack[i]);
        Throwable cause = error.getCause();
        for (int depth = 0; cause != null && cause != error && depth < 3; depth++, cause = cause.getCause()) {
            out.append("\n  caused by ").append(cause.getClass().getName());
            StackTraceElement[] causeStack = cause.getStackTrace();
            if (causeStack.length > 0) out.append(" at ").append(causeStack[0]);
        }
        return out.toString();
    }

    /** Reads how the last process ended, off the main thread, once per process. */
    static void checkSoon(Context context) {
        Context app = context.getApplicationContext();
        if (checkedFor == app) return;
        checkedFor = app;
        WORKER.execute(() -> {
            try {
                check(app);
            } catch (RuntimeException unexpected) {
                // Only a summary; the next start looks again.
            }
        });
    }

    /** Android's record of the last stops, and this app's note, as kept summaries. On a worker. */
    static void check(Context context) {
        Context app = context.getApplicationContext();
        long now = System.currentTimeMillis();
        SharedPreferences prefs = prefs(app);
        long seenUntil = prefs.getLong(SEEN_UNTIL, 0);
        List<Stop> found = new ArrayList<>();
        JSONObject note = note(app);
        long newest = seenUntil;
        boolean noteUsed = false;
        if (Build.VERSION.SDK_INT >= 30) {
            ActivityManager activities = app.getSystemService(ActivityManager.class);
            List<ApplicationExitInfo> exits = activities == null ? new ArrayList<>()
                    : activities.getHistoricalProcessExitReasons(app.getPackageName(), 0, 16);
            for (ApplicationExitInfo exit : exits) {
                long at = exit.getTimestamp();
                if (at <= seenUntil || now - at > KEEP_MS || at > now + MATCH_MS) continue;
                newest = Math.max(newest, at);
                String kind;
                String where = "";
                switch (exit.getReason()) {
                    case ApplicationExitInfo.REASON_CRASH:
                        kind = "crash";
                        if (note != null && Math.abs(note.optLong("at") - at) <= MATCH_MS) {
                            where = note.optString("where", "");
                            noteUsed = true;
                        }
                        break;
                    case ApplicationExitInfo.REASON_CRASH_NATIVE:
                        kind = "native crash";
                        break;
                    case ApplicationExitInfo.REASON_ANR:
                        kind = "not responding (ANR)";
                        where = mainThread(exit);
                        break;
                    default:
                        continue;
                }
                kind += exit.getImportance() <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
                        ? ", app in front" : ", app in the background";
                found.add(new Stop(at, kind, where, false));
            }
        } else if (note != null) {
            // Before Android 11 there is no record of exits: the app's own crash note alone.
            long at = note.optLong("at");
            if (at > seenUntil && now - at <= KEEP_MS) {
                found.add(new Stop(at, "crash", note.optString("where", ""), false));
                newest = Math.max(newest, at);
                noteUsed = true;
            }
        }
        // Android lists the newest first; kept oldest first.
        found.sort((a, b) -> Long.compare(a.at, b.at));
        synchronized (StopReports.class) {
            List<Stop> kept = kept(app, now);
            kept.addAll(found);
            save(app, kept, Math.max(newest, prefs.getLong(SEEN_UNTIL, 0)), true);
        }
        if (note != null && (noteUsed || now - note.optLong("at") > KEEP_MS)) {
            //noinspection ResultOfMethodCallIgnored
            new File(app.getFilesDir(), NOTE).delete();
        }
    }

    /** The top frames of the main thread in an ANR's trace; nothing else of it. */
    static String mainThread(ApplicationExitInfo exit) {
        if (Build.VERSION.SDK_INT < 30) return "";
        StringBuilder out = new StringBuilder();
        try (InputStream trace = exit.getTraceInputStream()) {
            if (trace == null) return "";
            BufferedReader lines = new BufferedReader(new InputStreamReader(trace, StandardCharsets.UTF_8));
            String line;
            boolean main = false;
            int frames = 0;
            int read = 0;
            while ((line = lines.readLine()) != null && read++ < 20_000) {
                if (!main) {
                    main = line.startsWith("\"main\"");
                    continue;
                }
                if (line.trim().isEmpty() || frames >= MAX_FRAMES) break;
                String trimmed = line.trim();
                if (!trimmed.startsWith("at ")) continue;
                // A frame names code only: "at com.example.Class.method(File.java:12)".
                int end = trimmed.indexOf(')');
                out.append(out.length() == 0 ? "main thread" : "").append("\n  ")
                        .append(end > 0 ? trimmed.substring(0, end + 1) : trimmed);
                frames++;
            }
        } catch (IOException | RuntimeException unreadable) {
            return out.toString();
        }
        return out.toString();
    }

    /** The kept summaries of the last 24 hours, oldest first. */
    static List<Stop> kept(Context context, long now) {
        List<Stop> out = new ArrayList<>();
        try {
            JSONArray stored = new JSONArray(prefs(context).getString(KEPT, "[]"));
            for (int i = 0; i < stored.length(); i++) {
                JSONObject one = stored.getJSONObject(i);
                long at = one.getLong("at");
                if (now - at > KEEP_MS || at - now > KEEP_MS) continue;
                out.add(new Stop(at, one.optString("kind"), one.optString("where"), one.optBoolean("ack")));
            }
        } catch (JSONException corrupt) {
            // Nothing readable kept.
        }
        return out;
    }

    /** @param now committed before returning (on a worker), else written in the background (on the main thread) */
    private static void save(Context context, List<Stop> stops, long seenUntil, boolean now) {
        JSONArray stored = new JSONArray();
        int from = Math.max(0, stops.size() - MAX_KEPT);
        try {
            for (int i = from; i < stops.size(); i++) {
                Stop stop = stops.get(i);
                stored.put(new JSONObject().put("at", stop.at).put("kind", stop.kind).put("where", stop.where)
                        .put("ack", stop.acknowledged));
            }
        } catch (JSONException impossible) {
            return;
        }
        SharedPreferences.Editor edit = prefs(context).edit().putString(KEPT, stored.toString())
                .putLong(SEEN_UNTIL, seenUntil);
        if (now) edit.commit();
        else edit.apply();
    }

    private static JSONObject note(Context app) {
        File file = new File(app.getFilesDir(), NOTE);
        if (!file.isFile()) return null;
        try {
            return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (IOException | JSONException unreadable) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return null;
        }
    }

    /** Whether the homepage should offer to send a report: a stop in the last day not yet offered. */
    static boolean unacknowledged(Context context) {
        for (Stop stop : kept(context, System.currentTimeMillis())) if (!stop.acknowledged) return true;
        return false;
    }

    /** The user saw the offer (sent a report, or closed it), or a summary went with diagnostics: not offered again. */
    static void acknowledge(Context context) {
        synchronized (StopReports.class) {
            List<Stop> stops = kept(context, System.currentTimeMillis());
            List<Stop> seen = new ArrayList<>();
            for (Stop stop : stops) seen.add(new Stop(stop.at, stop.kind, stop.where, true));
            save(context, seen, prefs(context).getLong(SEEN_UNTIL, 0), false);
        }
    }

    /** "== Recent stops" for a diagnostic report: every kept summary of the last day, or "" when there is none. */
    static String section(Context context) {
        List<Stop> stops = kept(context, System.currentTimeMillis());
        if (stops.isEmpty()) return "";
        StringBuilder out = new StringBuilder("\n\n== Recent stops (last 24 hours; types and code locations only)\n");
        for (Stop stop : stops) out.append(stop.line()).append('\n');
        return out.toString();
    }

    /** Clear history: the summaries and any crash note go. */
    static void clear(Context context) {
        Context app = context.getApplicationContext();
        synchronized (StopReports.class) {
            prefs(app).edit().remove(KEPT).apply();
        }
        //noinspection ResultOfMethodCallIgnored
        new File(app.getFilesDir(), NOTE).delete();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Waits for a check under way; for tests. */
    static void flush() {
        try {
            WORKER.submit(() -> { }).get(5, TimeUnit.SECONDS);
        } catch (Exception interruptedOrTimedOut) {
            if (interruptedOrTimedOut instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    /** As a new process would: the next start reads the record again. For tests. */
    static void forgetCache() {
        checkedFor = null;
    }

    private StopReports() {}
}
