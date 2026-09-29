package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded on-device offer history: at most 1000 records and 14 days. Upsert by id, so an append-only log of serialized
 * lines replays to the latest version of each record. Pure (no I/O); the Android store owns the file.
 */
final class OfferHistory {
    static final int MAX_RECORDS = 1000;
    static final long MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000;
    static final long MAX_FUTURE_MS = 24L * 60 * 60 * 1000;
    private static final Comparator<OfferRecord> CHRONOLOGICAL = (a, b) -> a.at != b.at ? Long.compare(a.at, b.at) : Long.compare(a.id, b.id);
    private final Map<Long, OfferRecord> records = new HashMap<>();
    private long lastId;

    /** Inserts or replaces the record with the same id, then prunes against now. */
    synchronized void upsert(OfferRecord record, long now) {
        if (record == null) return;
        records.put(record.id, record);
        lastId = Math.max(lastId, record.id);
        prune(now);
    }
    synchronized OfferRecord get(long id) { return records.get(id); }
    /** Adds flags to an existing record; returns the updated record or null when it no longer exists. */
    synchronized OfferRecord addActions(long id, int flags, long now) {
        OfferRecord r = records.get(id);
        if (r == null) return null;
        OfferRecord next = r.withAction(flags);
        upsert(next, now);
        return next;
    }
    /** A unique, increasing id seeded from the wall clock. */
    synchronized long nextId(long now) { lastId = Math.max(lastId + 1, now); return lastId; }
    synchronized int size() { return records.size(); }
    synchronized void clear() { records.clear(); }
    /** Oldest first. */
    synchronized List<OfferRecord> chronological() { List<OfferRecord> out = new ArrayList<>(records.values()); out.sort(CHRONOLOGICAL); return out; }
    synchronized List<OfferRecord> newestFirst() { List<OfferRecord> out = chronological(); java.util.Collections.reverse(out); return out; }
    /** Records with fromInclusive <= at < toExclusive, oldest first. */
    synchronized List<OfferRecord> between(long fromInclusive, long toExclusive) {
        List<OfferRecord> out = new ArrayList<>();
        for (OfferRecord r : chronological()) if (r.at >= fromInclusive && r.at < toExclusive) out.add(r);
        return out;
    }
    /** Drops records older than 14 days or implausibly far in the future, then the oldest beyond 1000. */
    synchronized void prune(long now) {
        records.values().removeIf(r -> now - r.at > MAX_AGE_MS || r.at - now > MAX_FUTURE_MS);
        if (records.size() > MAX_RECORDS) {
            List<OfferRecord> sorted = chronological();
            for (int i = 0; i < sorted.size() - MAX_RECORDS; i++) records.remove(sorted.get(i).id);
        }
    }
    /** One line per record, oldest first. */
    synchronized String serialize() {
        StringBuilder b = new StringBuilder();
        for (OfferRecord r : chronological()) b.append(r.serialize()).append('\n');
        return b.toString();
    }
    /** Tolerant replay: malformed lines are skipped, later lines replace earlier ones with the same id. */
    static OfferHistory parse(String text, long now) {
        OfferHistory history = new OfferHistory();
        if (text == null || text.isEmpty()) return history;
        for (String line : text.split("\n")) {
            OfferRecord r = OfferRecord.parse(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
            if (r != null) { history.records.put(r.id, r); history.lastId = Math.max(history.lastId, r.id); }
        }
        history.prune(now);
        return history;
    }
    /** Share-sheet CSV (explicit user action only). Store and reason are quoted; no raw labels exist to export. */
    synchronized String csv(java.time.ZoneId zone) {
        StringBuilder b = new StringBuilder("time,source,pay,miles,minutes,stops,add_on,verdict,required,reason_code,reason,store,outcome\n");
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone);
        for (OfferRecord r : chronological()) {
            b.append(f.format(java.time.Instant.ofEpochMilli(r.at))).append(',').append(r.source == OfferRecord.Source.NOTIFICATION ? "notification" : "screen").append(',')
                    .append(r.payCents == null ? "" : OfferMetrics.money(r.payCents)).append(',').append(r.miles == null ? "" : OfferMetrics.miles(r.miles)).append(',')
                    .append(r.minutes == null ? "" : r.minutes.toString()).append(',').append(r.stops == null ? "" : r.stops.toString()).append(',')
                    .append(r.addOn ? "yes" : "no").append(',').append(r.verdict.name()).append(',').append(r.requiredCents == 0 ? "" : OfferMetrics.money(r.requiredCents)).append(',')
                    .append(r.reasonCode.name()).append(',').append(quote(r.reason)).append(',').append(quote(r.store == null ? "" : r.store)).append(',').append(quote(r.outcome())).append('\n');
        }
        return b.toString();
    }
    private static String quote(String s) {
        String safe = s.isEmpty() || "=+-@".indexOf(s.charAt(0)) < 0 ? s : "'" + s;   // spreadsheet formula injection guard
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
