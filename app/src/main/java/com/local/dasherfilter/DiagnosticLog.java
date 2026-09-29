package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small on-device diagnostic ring buffer. Nothing is uploaded automatically. */
final class DiagnosticLog {
    private static final String PREFS = "offer_filter_diagnostics";
    private static final String ENABLED = "enabled";
    private static final String FILE = "offer-filter-diagnostics.log";
    private static final int MAX_BYTES = 128 * 1024;
    private static final int KEEP_BYTES = 96 * 1024;
    private static final Object LOCK = new Object();

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(ENABLED, enabled).apply();
        if (enabled) log(context, "diagnostics", "capture enabled");
    }

    static void log(Context context, String source, String message) {
        if (!isEnabled(context)) return;
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String safe = message == null ? "" : message.replace('', ' ').replace('
', ' ');
        byte[] line = (time + " [" + source + "] " + safe + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (LOCK) {
            File file = file(context);
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line);
            } catch (Exception ignored) {
                return;
            }
            if (file.length() > MAX_BYTES) trim(file);
        }
    }

    static String read(Context context) {
        synchronized (LOCK) {
            File file = file(context);
            if (!file.exists()) return "No diagnostic entries yet.";
            try (FileInputStream in = new FileInputStream(file);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
                return out.toString(StandardCharsets.UTF_8.name());
            } catch (Exception error) {
                return "Could not read diagnostics: " + error.getClass().getSimpleName();
            }
        }
    }

    static String report(Context context) {
        return "Offer Filter " + Updater.version(context) + " diagnostics\n" +
                "Generated locally. Screen text is captured only while diagnostic logging is enabled.\n\n" +
                read(context);
    }

    static void clear(Context context) {
        synchronized (LOCK) {
            File file = file(context);
            if (file.exists()) file.delete();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE);
    }

    private static void trim(File file) {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
            byte[] all = out.toByteArray();
            int start = Math.max(0, all.length - KEEP_BYTES);
            while (start < all.length && all[start] != '\n') start++;
            if (start < all.length) start++;
            try (FileOutputStream reset = new FileOutputStream(file, false)) {
                reset.write(all, start, all.length - start);
            }
        } catch (Exception ignored) {
        }
    }

    private DiagnosticLog() {}
}
