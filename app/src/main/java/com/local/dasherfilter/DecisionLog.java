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
 * chooses to send. One offer is one line: Dasher's notification of an offer the screen read is folded into the
 * screen's line ({@link OfferPairing}), and two notification incarnations are never one line.
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
        SEEN_ON_SCREEN("No card: the screen had already read this offer", 0),
        NEEDS_REVIEW("No action: needs your review", 0),
        REPLAY("Re-checked after a reconnect or rule change; no action", 0),
        SILENT_CARD("Review card posted without sound", 1),
        DASHER_SOUNDS("Review card posted without sound: Dasher's own offer alert sounds", 1),
        QUIET_PASS_CARD("Passing card posted without sound", 1),
        CARD_BLOCKED("Card blocked by Android; DoorDash notification kept", 1),
        BELL("Passing alert rang", 2),
        CHECK_BELL("Rang once: open Dasher to check it", 2),
        DECLINE_REFUSED("Decline request refused by Android", 2),
        DECLINE_TAPPED("Decline tapped", 3),
        NOTIFICATION_HIDDEN("Notification hidden; order NOT declined", 3),
        NOTIFICATION_DECLINE_SENT("Decline requested from the notification", 3),
        CONFIRMATION_TAPPED("Decline and its confirmation tapped", 4),
        USER_TOOK_OVER("You touched the screen and took over; nothing more tapped", 5);

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
        /** Dasher's notification of this same offer, folded in; kept without screen lines. Null when none. */
        final Entry notification;
        /** Memory only: the card tag of the notification incarnation this line is for. */
        final String alertTag;
        /** Memory only: recorded on a replay, which re-evaluates a post already recorded and is never a new offer. */
        final boolean replay;

        Entry(long at, Source source, boolean addOn, OfferSnapshot facts, long requiredCents, OfferRule.Result result,
              String reason, Action action, boolean autoDecline, List<String> evidence) {
            this(at, source, addOn, facts, requiredCents, result, reason, action, autoDecline, evidence, null, null,
                    false);
        }

        private Entry(long at, Source source, boolean addOn, OfferSnapshot facts, long requiredCents,
                      OfferRule.Result result, String reason, Action action, boolean autoDecline, List<String> evidence,
                      Entry notification, String alertTag, boolean replay) {
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
            this.notification = notification;
            this.alertTag = alertTag;
            this.replay = replay;
        }

        static Entry of(Source source, boolean addOn, OfferSnapshot facts, OfferRule.Decision decision,
                        Action action, boolean autoDecline, List<String> labels) {
            return new Entry(System.currentTimeMillis(), source, addOn, facts, decision.requiredCents,
                    decision.result, decision.reason, action, autoDecline, evidence(labels));
        }

        private boolean sameOffer(Entry other) {
            return source == other.source && addOn == other.addOn && result == other.result
                    && requiredCents == other.requiredCents && facts.fingerprint().equals(other.facts.fingerprint())
                    && Math.abs(at - other.at) <= MERGE_WINDOW_MS && sameIncarnation(other);
        }

        /** Two notification incarnations are two offers; a replay re-evaluates one already recorded. */
        private boolean sameIncarnation(Entry other) {
            return alertTag == null || other.alertTag == null || other.replay || alertTag.equals(other.alertTag);
        }

        private Entry withAction(Action next, boolean autoDecline) {
            return new Entry(at, source, addOn, facts, requiredCents, result, reason, next, autoDecline, evidence,
                    notification, alertTag, false);
        }

        /** This line as of {@code time}: a screen reading is stamped as the history takes it. */
        Entry withTime(long time) {
            return new Entry(time, source, addOn, facts, requiredCents, result, reason, action, autoDecline, evidence,
                    notification, alertTag, replay);
        }

        /** This line for the notification incarnation whose card has {@code tag}. */
        Entry withAlertTag(String tag, boolean replay) {
            return new Entry(at, source, addOn, facts, requiredCents, result, reason, action, autoDecline, evidence,
                    notification, tag, replay);
        }

        /** This line with Dasher's notification of the same offer folded in, without that notification's lines. */
        Entry withNotification(Entry n) {
            Entry nested = new Entry(n.at, n.source, n.addOn, n.facts, n.requiredCents, n.result, n.reason, n.action,
                    n.autoDecline, Collections.emptyList());
            return new Entry(at, source, addOn, facts, requiredCents, result, reason, action, autoDecline, evidence,
                    nested, alertTag, replay);
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
            if (notification != null) json.put("notification", notification.toJson());
            return json;
        }

        static Entry fromJson(JSONObject json) throws JSONException {
            Entry entry = plainFromJson(json);
            JSONObject nested = json.optJSONObject("notification");
            // Always without lines of its own and never nested deeper, whatever the file says.
            return nested == null ? entry : entry.withNotification(plainFromJson(nested));
        }

        private static Entry plainFromJson(JSONObject json) throws JSONException {
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

    /** How the main page counts an offer: passed, filtered (a failing offer the app acted on), or left to review. */
    enum Tally { PASSED, FILTERED, REVIEW }

    /** A failing offer counts as filtered only when the app did something about it; one left to the user is review. */
    static Tally tally(Entry entry) {
        if (entry.result == OfferRule.Result.KEEP) return Tally.PASSED;
        if (entry.result == OfferRule.Result.DECLINE && (entry.action == Action.DECLINE_TAPPED
                || entry.action == Action.CONFIRMATION_TAPPED || entry.action == Action.NOTIFICATION_DECLINE_SENT
                || entry.action == Action.NOTIFICATION_HIDDEN)) {
            return Tally.FILTERED;
        }
        return Tally.REVIEW;
    }

    private static final String TOTALS = "decision_totals";
    /** In {@link #TOTALS}: the stored history was folded once ({@link #foldStoredOnce}). */
    private static final String FOLDED_V1 = "folded_v1";

    /**
     * Every offer recorded since the history was last cleared, by {@link Tally}: kept apart from the history, which
     * holds only the latest {@value #MAX_ENTRIES}.
     */
    static int[] totals(Context context) {
        synchronized (LOCK) {
            // Loaded first, so the one-time fold of older history has corrected the totals before they are read.
            List<Entry> all = loaded(context);
            android.content.SharedPreferences prefs = context.getSharedPreferences(TOTALS, Context.MODE_PRIVATE);
            int[] totals = new int[Tally.values().length];
            if (!prefs.contains(Tally.PASSED.name())) {
                // First use since totals were kept: start from what the history holds.
                for (Entry entry : all) totals[tally(entry).ordinal()]++;
                writeTotals(context, totals);
                return totals;
            }
            for (Tally tally : Tally.values()) totals[tally.ordinal()] = prefs.getInt(tally.name(), 0);
            return totals;
        }
    }

    private static void writeTotals(Context context, int[] totals) {
        android.content.SharedPreferences.Editor edit =
                context.getSharedPreferences(TOTALS, Context.MODE_PRIVATE).edit();
        for (Tally tally : Tally.values()) edit.putInt(tally.name(), totals[tally.ordinal()]);
        edit.apply();
    }

    /** Moves one offer's count from {@code from} (null for a new offer) to {@code to}. Under {@link #LOCK}. */
    private static void recount(Context context, Tally from, Tally to) {
        if (from == to) return;
        int[] totals = totals(context);
        if (from != null) totals[from.ordinal()] = Math.max(0, totals[from.ordinal()] - 1);
        totals[to.ordinal()]++;
        writeTotals(context, totals);
    }

    /** Takes back one count from {@code from}: a line found to be another reading of an offer already counted. */
    private static void uncount(Context context, Tally from) {
        int[] totals = totals(context);
        totals[from.ordinal()] = Math.max(0, totals[from.ordinal()] - 1);
        writeTotals(context, totals);
    }

    private static final Object LOCK = new Object();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();
    /** Oldest first; null until first loaded from disk. Guarded by {@link #LOCK}. */
    private static List<Entry> entries;
    private static volatile long version;

    /** Adds an entry, or upgrades the action on the same offer's latest entry. Never throws. */
    static void record(Context context, Entry entry) {
        record(context, entry, -1);
    }

    /**
     * As {@link #record(Context, Entry)}; a screen offer also takes Dasher's notification of it, and the offer then
     * counts once, with the screen's decision. A new screen line takes the notification read before it
     * ({@link OfferPairing#notificationFor}); a new reading of an offer already on a line takes the notifications
     * recorded since that line ({@link OfferPairing#notificationsSince}): the line keeps the first of them, and the
     * rest are the same offer again and leave the history and the counts.
     *
     * @param secondsLeft the screen's countdown, -1 when none shows
     * @return the notifications this reading took (with their in-memory card tags), oldest first; empty when none
     */
    static List<Entry> record(Context context, Entry entry, int secondsLeft) {
        List<Entry> taken = new ArrayList<>();
        try {
            synchronized (LOCK) {
                List<Entry> all = loaded(context);
                // Before anything changes, so a first count starts from the history as it was.
                totals(context);
                for (int i = all.size() - 1; i >= Math.max(0, all.size() - 6); i--) {
                    Entry previous = all.get(i);
                    if (previous.source != entry.source) continue;
                    if (!previous.sameOffer(entry)) break;
                    // A replay after a reconnect hands its new card tag to the line it re-evaluates.
                    Entry kept = entry.alertTag != null && !entry.alertTag.equals(previous.alertTag)
                            ? previous.withAlertTag(entry.alertTag, false) : previous;
                    boolean upgrade = entry.action != previous.action && entry.action.weight >= previous.action.weight;
                    Entry merged = upgrade ? kept.withAction(entry.action, entry.autoDecline) : kept;
                    List<Integer> since = entry.source == Source.SCREEN
                            ? OfferPairing.notificationsSince(all, i, entry, secondsLeft) : Collections.emptyList();
                    for (int k = since.size() - 1; k >= 0; k--) taken.add(0, all.remove((int) since.get(k)));
                    if (!taken.isEmpty() && merged.notification == null) merged = merged.withNotification(taken.get(0));
                    all.set(i, merged);
                    for (Entry notice : taken) uncount(context, tally(notice));
                    if (upgrade) recount(context, tally(previous), tally(merged));
                    if (upgrade || !taken.isEmpty()) persist(context, all);
                    return taken;
                }
                int notice = entry.source == Source.SCREEN ? OfferPairing.notificationFor(all, entry, secondsLeft) : -1;
                if (notice >= 0) {
                    Entry folded = all.remove(notice);
                    taken.add(folded);
                    Entry added = entry.withNotification(folded);
                    all.add(added);
                    recount(context, tally(folded), tally(added));
                } else {
                    all.add(entry);
                    recount(context, null, tally(entry));
                }
                while (all.size() > MAX_ENTRIES) all.remove(0);
                persist(context, all);
            }
            // A new offer, not a later step of one already recorded: it counts once toward its area, and shows the
            // dash went on after any offer the user declined by hand.
            AreaMap.note(context, entry);
            ManualDeclines.offerSeen(context, entry.facts, System.currentTimeMillis());
        } catch (RuntimeException error) {
            DiagnosticLog.log(context, "decision-log", "record failed: " + error.getClass().getSimpleName());
        }
        return taken;
    }

    /**
     * Folds Dasher's notification of an offer the screen read moments before ({@link OfferPairing#screenFor}) into
     * that offer's line. It is not a new offer: nothing is counted, and nothing about areas or declines by hand.
     *
     * @return the screen offer's line with the notification folded in, or null when there is none to fold into
     */
    static Entry foldIntoScreen(Context context, Entry notice) {
        try {
            synchronized (LOCK) {
                List<Entry> all = loaded(context);
                totals(context);
                int screen = OfferPairing.screenFor(all, notice);
                if (screen < 0) return null;
                Entry folded = all.get(screen).withNotification(notice);
                all.set(screen, folded);
                persist(context, all);
                return folded;
            }
        } catch (RuntimeException error) {
            DiagnosticLog.log(context, "decision-log", "fold failed: " + error.getClass().getSimpleName());
            return null;
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
            writeTotals(context, new int[Tally.values().length]);
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
            Entry n = entry.notification;
            if (n != null) {
                out.append("    notification ").append(noticeWhen(entry))
                        .append(" | ").append(n.result)
                        .append(" | pay ").append(n.facts.payCents == null ? "?" : money(n.facts.payCents))
                        .append(" | ").append(n.reason)
                        .append(" | ").append(n.action.label)
                        .append('\n');
            }
        }
        return out.toString();
    }

    /** When Dasher's folded notification came, against the screen's reading: "14 s earlier" or "1 s later". */
    static String noticeWhen(Entry entry) {
        if (entry.notification == null) return "";
        long ms = entry.at - entry.notification.at;
        long seconds = Math.round(Math.abs(ms) / 1000.0);
        return seconds + " s " + (ms >= 0 ? "earlier" : "later");
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
        if (entries == null) {
            entries = load(file(context));
            foldStoredOnce(context, entries);
        }
        return entries;
    }

    /**
     * Once, on the first load after notifications began to be folded: folds the stored history the same way
     * ({@link OfferPairing#foldHistory}), and takes each folded notification out of the all-time totals. Double counts
     * older than the kept history cannot be found and stay. Under {@link #LOCK}; never reads totals through
     * {@link #totals}, which loads.
     */
    private static void foldStoredOnce(Context context, List<Entry> all) {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences(TOTALS, Context.MODE_PRIVATE);
            if (prefs.getBoolean(FOLDED_V1, false)) return;
            List<Entry> folded = OfferPairing.foldHistory(all);
            if (!folded.isEmpty()) {
                if (prefs.contains(Tally.PASSED.name())) {
                    int[] totals = new int[Tally.values().length];
                    for (Tally tally : Tally.values()) totals[tally.ordinal()] = prefs.getInt(tally.name(), 0);
                    for (Entry notice : folded) {
                        int i = tally(notice).ordinal();
                        totals[i] = Math.max(0, totals[i] - 1);
                    }
                    writeTotals(context, totals);
                }
                persist(context, all);
            }
            prefs.edit().putBoolean(FOLDED_V1, true).apply();
        } catch (RuntimeException error) {
            DiagnosticLog.log(context, "decision-log", "history fold failed: " + error.getClass().getSimpleName());
        }
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
