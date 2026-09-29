package com.local.dasherfilter;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Always-on, on-device history of offer decisions, so a surprising decline can be explained afterwards. It keeps
 * the parsed numbers, the rule outcome and the action taken, plus only the screen lines that carried a number or
 * a pay label: no names or addresses. Bounded to {@link #MAX_ENTRIES}; leaves the phone only in a report the user
 * chooses to send.
 */
final class DecisionLog {
    static final int MAX_ENTRIES = 200;
    private static final long MERGE_WINDOW_MS = 120_000;
    private static final int MAX_EVIDENCE_LINES = 10;
    private static final int MAX_EVIDENCE_CHARS = 120;
    private static final String FILE = "decision-log.json";
    /** Lines the parser can read a figure from, or a pay/add-on label; "+" only before "$" (not phone numbers). */
    private static final Pattern EVIDENCE = Pattern.compile("(?i)\\$|\\d\\s*(?:mi|miles?|mins?|minutes?"
            + "|stops?|pick[ -]?ups?|drop[ -]?offs?)\\b|guaranteed|total pay|incl\\. tips"
            + "|add to route|add-on|additional order");
    private static final int REPORT_EVIDENCE_LINES = 4;

    enum Source { SCREEN, NOTIFICATION }

    /** What the app did about a decision. A heavier action replaces a lighter one on the same offer. */
    enum Action {
        PAUSED("No action: auto-decline paused", 0),
        PASSES("No action: passes your rules", 0),
        NEEDS_REVIEW("No action: needs your review", 0),
        REPLAY("Re-checked after a reconnect or rule change; no action", 0),
        SILENT_CARD("Silent review card posted", 1),
        QUIET_PASS_CARD("Passing card posted without sound", 1),
        CARD_BLOCKED("Card blocked by Android; DoorDash notification kept", 1),
        BELL("Passing alert rang", 2),
        DECLINE_REFUSED("Decline request refused by Android", 2),
        DECLINE_TAPPED("Decline tapped", 3),
        NOTIFICATION_HIDDEN("Notification hidden; order NOT declined", 3),
        NOTIFICATION_DECLINE_SENT("Decline requested from the notification", 3),
        CONFIRMATION_TAPPED("Decline and its confirmation tapped", 4);

        final String label;
        final int weight;

        Action(String label, int weight) {
            this.label = label;
            this.weight = weight;
        }

        static Action named(String name) {
            try {
                return valueOf(name);
            } catch (IllegalArgumentException | NullPointerException unknown) {
                return REPLAY;
            }
        }
    }

    static final class Entry {
        final long at;
        final Source source;
        final boolean addOn;
        final OfferSnapshot facts;
        final long requiredCents;
        final OfferRule.Result result;
        final String reason;
        final Action action;
        final boolean autoDecline;
        final List<String> evidence;

        Entry(long at, Source source, boolean addOn, OfferSnapshot facts, long requiredCents, OfferRule.Result result,
              String reason, Action action, boolean autoDecline, List<String> evidence) {
            this.at = at;
            this.source = source;
            this.addOn = addOn;
            this.facts = facts;
            this.requiredCents = requiredCents;
            this.result = result;
            this.reason = reason == null ? "" : reason;
            this.action = action;
            this.autoDecline = autoDecline;
            this.evidence = Collections.unmodifiableList(new ArrayList<>(evidence));
        }

        static Entry of(Source source, boolean addOn, OfferSnapshot facts, OfferRule.Decision decision,
                        Action action, boolean autoDecline, List<String> labels) {
            return new Entry(System.currentTimeMillis(), source, addOn, facts, decision.requiredCents,
                    decision.result, decision.reason, action, autoDecline, evidence(labels));
        }

        private boolean sameOffer(Entry other) {
            return source == other.source && addOn == other.addOn && result == other.result
                    && requiredCents == other.requiredCents && facts.fingerprint().equals(other.facts.fingerprint())
                    && Math.abs(at - other.at) <= MERGE_WINDOW_MS;
        }

        private Entry withAction(Action next, boolean autoDecline) {
            return new Entry(at, source, addOn, facts, requiredCents, result, reason, next, autoDecline, evidence);
        }

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject()
                    .put("at", at).put("source", source.name()).put("addOn", addOn)
                    .put("required", requiredCents).put("result", result.name()).put("reason", reason)
                    .put("action", action.name()).put("autoDecline", autoDecline)
                    .put("evidence", new JSONArray(evidence));
            if (facts.payCents != null) json.put("pay", facts.payCents);
            if (facts.miles != null) json.put("miles", facts.miles);
            if (facts.minutes != null) json.put("minutes", facts.minutes);
            if (facts.stops != null) json.put("stops", facts.stops);
            return json;
        }

        static Entry fromJson(JSONObject json) throws JSONException {
            OfferSnapshot facts = new OfferSnapshot(
                    json.has("pay") ? json.getInt("pay") : null,
                    json.has("miles") ? json.getDouble("miles") : null,
                    json.has("minutes") ? json.getInt("minutes") : null,
                    json.has("stops") ? json.getInt("stops") : null);
            List<String> evidence = new ArrayList<>();
            JSONArray lines = json.optJSONArray("evidence");
            for (int i = 0; lines != null && i < lines.length(); i++) evidence.add(lines.getString(i));
            return new Entry(json.getLong("at"), Source.valueOf(json.getString("source")), json.optBoolean("addOn"),
                    facts, json.optLong("required"), OfferRule.Result.valueOf(json.getString("result")),
                    json.optString("reason"), Action.named(json.optString("action")),
                    json.optBoolean("autoDecline"), evidence);
        }
    }

    private static final Object LOCK = new Object();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();
    /** Oldest first; null until first loaded from disk. Guarded by {@link #LOCK}. */
    private static List<Entry> entries;
    private static volatile long version;

    /** Adds an entry, or upgrades the action on the same offer's latest entry. Never throws. */
    static void record(Context context, Entry entry) {
        try {
            synchronized (LOCK) {
                List<Entry> all = loaded(context);
                for (int i = all.size() - 1; i >= Math.max(0, all.size() - 6); i--) {
                    Entry previous = all.get(i);
                    if (previous.source != entry.source) continue;
                    if (!previous.sameOffer(entry)) break;
                    if (entry.action == previous.action || entry.action.weight < previous.action.weight) return;
                    all.set(i, previous.withAction(entry.action, entry.autoDecline));
                    persist(context, all);
                    return;
                }
                all.add(entry);
                while (all.size() > MAX_ENTRIES) all.remove(0);
                persist(context, all);
            }
        } catch (RuntimeException error) {
            DiagnosticLog.log(context, "decision-log", "record failed: " + error.getClass().getSimpleName());
        }
    }

    /** Up to {@code limit} entries, newest first. */
    static List<Entry> recent(Context context, int limit) {
        synchronized (LOCK) {
            List<Entry> all = loaded(context);
            List<Entry> out = new ArrayList<>();
            for (int i = all.size() - 1; i >= 0 && out.size() < limit; i--) out.add(all.get(i));
            return out;
        }
    }

    /** Changes whenever the history changes, so a screen can skip redrawing an unchanged list. */
    static long version() {
        return version;
    }

    static void clear(Context context) {
        synchronized (LOCK) {
            entries = new ArrayList<>();
            version++;
            File file = file(context);
            WRITER.execute(() -> {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            });
        }
    }

    /** Plain-text history for a diagnostics report, newest first. */
    static String report(Context context, int limit) {
        List<Entry> recent = recent(context, limit);
        if (recent.isEmpty()) return "No decisions recorded yet.\n";
        SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        StringBuilder out = new StringBuilder();
        for (Entry entry : recent) {
            out.append(time.format(new Date(entry.at)))
                    .append(" | ").append(entry.source.name().toLowerCase(Locale.US))
                    .append(entry.addOn ? " add-on" : "")
                    .append(" | ").append(entry.result)
                    .append(" | pay ").append(entry.facts.payCents == null ? "?" : money(entry.facts.payCents))
                    .append(" | needed ").append(entry.requiredCents == 0 ? "-" : money(entry.requiredCents))
                    .append(" | ").append(facts(entry.facts))
                    .append(" | ").append(entry.reason)
                    .append(" | ").append(entry.action.label)
                    .append(entry.autoDecline ? "" : " | auto-decline paused")
                    .append('\n');
            if (!entry.evidence.isEmpty()) {
                List<String> read = entry.evidence.subList(0, Math.min(REPORT_EVIDENCE_LINES, entry.evidence.size()));
                out.append("    read: ").append(read).append('\n');
            }
        }
        return out.toString();
    }

    /** The labels worth keeping: those carrying a figure or a pay/add-on label. */
    static List<String> evidence(List<String> labels) {
        List<String> out = new ArrayList<>();
        if (labels == null) return out;
        for (String label : labels) {
            if (label == null || !EVIDENCE.matcher(label).find()) continue;
            String clean = OfferEvidence.normalize(label);
            out.add(clean.length() > MAX_EVIDENCE_CHARS ? clean.substring(0, MAX_EVIDENCE_CHARS) + "…" : clean);
            if (out.size() == MAX_EVIDENCE_LINES) break;
        }
        return out;
    }

    static String money(long cents) {
        return String.format(Locale.US, "$%.2f", cents / 100.0);
    }

    /** "$7" for whole dollars, otherwise "$7.50". */
    static String shortMoney(long cents) {
        return cents % 100 == 0 ? "$" + cents / 100 : money(cents);
    }

    /** "7.2 mi · 21 min · 2 stops", listing only known values. */
    static String facts(OfferSnapshot facts) {
        List<String> parts = new ArrayList<>();
        if (facts.miles != null) parts.add(trimZero(facts.miles) + " mi");
        if (facts.minutes != null) parts.add(facts.minutes + " min");
        if (facts.stops != null) parts.add(facts.stops + (facts.stops == 1 ? " stop" : " stops"));
        return parts.isEmpty() ? "no distance, time or stops read" : String.join(" · ", parts);
    }

    private static String trimZero(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** Waits briefly for pending writes; used before reading the file directly and by tests. */
    static void flush() {
        try {
            WRITER.submit(() -> { }).get(2, TimeUnit.SECONDS);
        } catch (Exception interruptedOrTimedOut) {
            if (interruptedOrTimedOut instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    /** Drops the in-memory copy so the next read reloads from disk, as a process restart would. */
    static void forgetCache() {
        synchronized (LOCK) {
            entries = null;
        }
    }

    private static List<Entry> loaded(Context context) {
        if (entries == null) entries = load(file(context));
        return entries;
    }

    private static List<Entry> load(File file) {
        List<Entry> out = new ArrayList<>();
        if (!file.isFile()) return out;
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
            JSONArray array = new JSONArray(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            for (int i = Math.max(0, array.length() - MAX_ENTRIES); i < array.length(); i++) {
                out.add(Entry.fromJson(array.getJSONObject(i)));
            }
        } catch (IOException | JSONException | IllegalArgumentException corrupt) {
            out.clear();
        }
        return out;
    }

    /** Called under {@link #LOCK}; entries are immutable, so the writer thread serializes a snapshot. */
    private static void persist(Context context, List<Entry> all) {
        version++;
        List<Entry> snapshot = new ArrayList<>(all);
        File file = file(context);
        WRITER.execute(() -> {
            JSONArray array = new JSONArray();
            try {
                for (Entry entry : snapshot) array.put(entry.toJson());
            } catch (JSONException error) {
                return;
            }
            byte[] bytes = array.toString().getBytes(StandardCharsets.UTF_8);
            File temp = new File(file.getParentFile(), FILE + ".tmp");
            try (OutputStream out = new FileOutputStream(temp)) {
                out.write(bytes);
            } catch (IOException error) {
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            temp.renameTo(file);
        });
    }

    private static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE);
    }

    private DecisionLog() {}
}
