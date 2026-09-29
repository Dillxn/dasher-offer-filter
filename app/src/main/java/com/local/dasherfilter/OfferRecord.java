package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.List;

/**
 * One on-device history row: displayed facts, verdict, and what the app requested. Store is only a notification merchant
 * or an avoid-list term, never raw labels, customer names, or addresses. Actions are requests/observations, not outcomes.
 */
final class OfferRecord {
    enum Source { SCREEN, NOTIFICATION }
    static final int DECLINE_REQUESTED = 1, CONFIRM_REQUESTED = 1 << 1, NOTIF_DECLINE_SENT = 1 << 2, NOTIF_HIDDEN = 1 << 3,
            PASS_ALERT = 1 << 4, PASS_BELL = 1 << 5, REVIEW_CARD = 1 << 6, ALERT_UNAVAILABLE = 1 << 7,
            PAUSED_SHADOW = 1 << 8, ACCEPT_OBSERVED = 1 << 9;
    static final int KNOWN_ACTIONS = (1 << 10) - 1;
    static final int MAX_REASON = 160, MAX_STORE = 60;
    final long id;
    final long at;
    final Source source;
    final Integer payCents;
    final Double miles;
    final Integer minutes;
    final Integer stops;
    final boolean addOn;
    final OfferRule.Result verdict;
    final long requiredCents;
    final OfferRule.Code reasonCode;
    final String reason;
    final int actions;
    final String store;

    OfferRecord(long id, long at, Source source, Integer payCents, Double miles, Integer minutes, Integer stops, boolean addOn,
                OfferRule.Result verdict, long requiredCents, OfferRule.Code reasonCode, String reason, int actions, String store) {
        OfferSnapshot facts = new OfferSnapshot(payCents, miles, minutes, stops);
        this.id = id; this.at = at; this.source = source == null ? Source.SCREEN : source;
        this.payCents = facts.payCents; this.miles = facts.miles; this.minutes = facts.minutes; this.stops = facts.stops;
        this.addOn = addOn; this.verdict = verdict == null ? OfferRule.Result.REVIEW : verdict;
        this.requiredCents = Math.max(0L, requiredCents);
        this.reasonCode = reasonCode == null ? OfferRule.Code.UNSPECIFIED : reasonCode;
        this.reason = clip(reason == null ? "" : reason, MAX_REASON); this.actions = actions & KNOWN_ACTIONS; this.store = clip(store, MAX_STORE);
    }
    /** A new record from an evaluation. store: notification merchant, else the matched avoid term, else null. */
    static OfferRecord of(long id, long at, Source source, OfferSnapshot offer, boolean addOn, OfferRule.Decision decision, String store) {
        OfferSnapshot o = offer == null ? new OfferSnapshot(null, null, null, null) : offer;
        String s = store != null && !store.trim().isEmpty() ? store : decision == null ? null : decision.avoidedStore;
        return new OfferRecord(id, at, source, o.payCents, o.miles, o.minutes, o.stops, addOn,
                decision == null ? OfferRule.Result.REVIEW : decision.result, decision == null ? 0 : decision.requiredCents,
                decision == null ? OfferRule.Code.UNSPECIFIED : decision.code, decision == null ? "" : decision.reason, 0, s);
    }
    /** Same record (id, time, actions) with a newer evaluation. A known store is kept when the new one is unknown. */
    OfferRecord withEvaluation(OfferSnapshot offer, boolean addOn, OfferRule.Decision decision, String newStore) {
        OfferRecord next = of(id, at, source, offer, addOn, decision, newStore);
        return new OfferRecord(id, at, source, next.payCents, next.miles, next.minutes, next.stops, addOn, next.verdict, next.requiredCents,
                next.reasonCode, next.reason, actions, next.store != null ? next.store : store);
    }
    OfferRecord withAction(int flag) { return withActions(actions | flag); }
    OfferRecord withActions(int value) {
        return new OfferRecord(id, at, source, payCents, miles, minutes, stops, addOn, verdict, requiredCents, reasonCode, reason, value, store);
    }
    boolean has(int flag) { return (actions & flag) != 0; }
    OfferSnapshot snapshot() { return new OfferSnapshot(payCents, miles, minutes, stops); }
    /** A decline was requested from DoorDash (screen tap or notification action). Not proof it was declined. */
    boolean declineRequested() { return has(DECLINE_REQUESTED) || has(NOTIF_DECLINE_SENT); }
    /** Counts toward the opt-in hourly decline budget: requests and hides alike. */
    boolean declineRequestCounted() { return declineRequested() || has(NOTIF_HIDDEN); }
    boolean hiddenOnly() { return has(NOTIF_HIDDEN) && !has(NOTIF_DECLINE_SENT); }

    /** Honest outcome wording: requests, hides, and observations are never called declines or earnings. */
    String outcome() {
        List<String> parts = new ArrayList<>();
        if (has(ACCEPT_OBSERVED)) parts.add("Accept observed");
        if (has(DECLINE_REQUESTED)) parts.add(has(CONFIRM_REQUESTED) ? "Decline requested (confirmation requested)" : "Decline requested");
        if (has(NOTIF_DECLINE_SENT)) parts.add("Decline requested from notification");
        if (hiddenOnly()) parts.add("Notification hidden — order NOT declined");
        if (has(PAUSED_SHADOW)) parts.add("Auto-decline off — would have requested decline");
        if (has(PASS_BELL)) parts.add("Passing alert (sound requested)");
        else if (has(PASS_ALERT)) parts.add("Passing alert (quiet)");
        if (has(REVIEW_CARD)) parts.add("Silent review card");
        if (has(ALERT_UNAVAILABLE)) parts.add("Alert unavailable — original notification kept");
        if (parts.isEmpty()) parts.add(verdict == OfferRule.Result.KEEP ? "Passed — no action" : verdict == OfferRule.Result.REVIEW ? "Needs review — no action" : "No action taken");
        return String.join(" · ", parts);
    }

    /** One tab-separated line; tabs, newlines and backslashes in text are escaped. */
    String serialize() {
        return "v1\t" + id + "\t" + at + "\t" + (source == Source.NOTIFICATION ? "N" : "S") + "\t" + n(payCents) + "\t" + (miles == null ? "" : miles.toString()) +
                "\t" + n(minutes) + "\t" + n(stops) + "\t" + (addOn ? "1" : "0") + "\t" + verdict.name() + "\t" + requiredCents + "\t" + reasonCode.name() +
                "\t" + actions + "\t" + escape(store == null ? "" : store) + "\t" + escape(reason);
    }
    /** Tolerant: null for any malformed line (the caller skips it). Unknown reason codes read as UNSPECIFIED. */
    static OfferRecord parse(String line) {
        if (line == null || line.length() > 4096) return null;
        String[] f = line.split("\t", -1);
        if (f.length != 15 || !f[0].equals("v1")) return null;
        try {
            Source source = f[3].equals("N") ? Source.NOTIFICATION : f[3].equals("S") ? Source.SCREEN : null;
            if (source == null || !(f[8].equals("0") || f[8].equals("1"))) return null;
            Double miles = f[5].isEmpty() ? null : Double.valueOf(f[5]);
            if (miles != null && (!Double.isFinite(miles) || miles < 0)) return null;
            String store = unescape(f[13]);
            return new OfferRecord(Long.parseLong(f[1]), Long.parseLong(f[2]), source, i(f[4]), miles, i(f[6]), i(f[7]), f[8].equals("1"),
                    OfferRule.Result.valueOf(f[9]), Long.parseLong(f[10]), OfferRule.Code.parse(f[11]), unescape(f[14]), Integer.parseInt(f[12]),
                    store.isEmpty() ? null : store);
        } catch (RuntimeException error) { return null; }
    }
    private static String n(Integer v) { return v == null ? "" : v.toString(); }
    private static Integer i(String v) { return v.isEmpty() ? null : Integer.valueOf(v); }
    private static String clip(String s, int max) {
        if (s == null) return null;
        String clean = s.replaceAll("[\\p{Cc}\\p{Cf}]+", " ").trim();
        return clean.length() > max ? clean.substring(0, max).trim() : clean;
    }
    static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '\\') b.append("\\\\"); else if (c == '\t') b.append("\\t"); else if (c == '\n') b.append("\\n"); else if (c == '\r') b.append("\\r"); else b.append(c);
        }
        return b.toString();
    }
    static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c != '\\') { b.append(c); continue; }
            if (++k >= s.length()) throw new IllegalArgumentException("dangling escape");
            char e = s.charAt(k);
            if (e == '\\') b.append('\\'); else if (e == 't') b.append('\t'); else if (e == 'n') b.append('\n'); else if (e == 'r') b.append('\r');
            else throw new IllegalArgumentException("bad escape");
        }
        return b.toString();
    }
}
