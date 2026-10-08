package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Accountless feedback, offer reports and diagnostics, sent to the project's own feedback service (a Supabase Edge
 * Function; backend/anonymous-feedback). No account, name, email, install or device identifier: every submission
 * carries a fresh random token, used only so its parts and their retries are stored once.
 *
 * <p>The protocol: one JSON POST per part, with no Origin header (the service files it as from the app). {@code kind}
 * is feedback, problem or diagnostics; {@code category} one of the service's; the {@code message} (what the user
 * typed, at most {@value #MAX_MESSAGE_CHARS} characters) is required for feedback, so every later part of a feedback
 * submission carries a short continuation line instead. Diagnostics (masked text the user chose to attach, an offer's
 * report, or the opt-in summary after a dash) require {@code diagnosticsConsented} and go in at most
 * {@value #MAX_PARTS} parts of at most {@value #MAX_PART_CHARS} characters, cut at line ends. The service trims every
 * text field, so each part is framed by a header line and an end line that are never whitespace, and the text between
 * them reassembles exactly. A part is acknowledged with 201 and a short reference; the outbox ({@link FeedbackOutbox})
 * keeps a submission until every part is, and maps every other answer honestly ({@link State}).
 *
 * <p>Nothing leaves before the current notice is accepted, and nothing is built or sent on the main thread.
 */
final class Feedback {
    static final String ENDPOINT = "https://nwglojlrzfbsibtoqjzc.supabase.co/functions/v1/offer-filter-feedback";
    static final int MAX_MESSAGE_CHARS = 4000;
    static final int MAX_PARTS = 4;
    static final int MAX_PART_CHARS = 50_000;
    /** The service refuses a request over 150,000 bytes; a part's diagnostics stay well inside it, message and all. */
    static final int MAX_BODY_BYTES = 150_000;
    static final int MAX_PART_BYTES = 120_000;
    /** The opt-in summary after a dash is one part of at most this many characters. */
    static final int MAX_SUMMARY_CHARS = 30_000;
    static final int REFERENCES_KEPT = 10;
    private static final Pattern REFERENCE = Pattern.compile("[0-9A-Za-z]{1,16}");
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{32}");

    /** What a submission is, as the service names it. */
    enum Kind {
        FEEDBACK("feedback"), PROBLEM("problem"), DIAGNOSTICS("diagnostics");

        final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        static Kind named(String wire) {
            for (Kind kind : values()) if (kind.wire.equals(wire)) return kind;
            return FEEDBACK;
        }
    }

    /** The feedback dialog's chips, and what the service calls each. */
    enum Category {
        GENERAL("general", "General"), BUG("bug", "Bug"), IDEA("feature", "Idea"), USABILITY("ux", "Usability"),
        PRIVACY("privacy", "Privacy"), UPDATE("update", "Update");

        final String wire;
        final String label;

        Category(String wire, String label) {
            this.wire = wire;
            this.label = label;
        }
    }

    /** What became of a submission, in the words the user sees. */
    enum State {
        SENDING("Sending…", false),
        SENT("Sent", true),
        OFFLINE("Saved; it will send when you're online", false),
        RATE_LIMITED("Too many sends from this network right now; it will retry", false),
        UNAVAILABLE("Saved; it will send when you're online", false),
        NOT_ANSWERING("Saved; the feedback service isn't answering yet; it will retry", false),
        REJECTED("Couldn't be accepted (too long?)", true),
        NOT_QUEUED("Not sent", true);

        final String said;
        /** No more is to come for this submission. */
        final boolean finished;

        State(String said, boolean finished) {
            this.said = said;
            this.finished = finished;
        }

        /** Still waiting in the outbox for another try. */
        boolean waiting() {
            return this == OFFLINE || this == RATE_LIMITED || this == UNAVAILABLE || this == NOT_ANSWERING;
        }
    }

    /** What happened to one submission (or why one could not be queued). */
    static final class Event {
        final String token;
        final State state;
        final String reference;
        /** Words for the user: the state's own, or why a submission was not queued. */
        final String said;
        final Kind kind;
        /** Typed or chosen by the user (not the opt-in summary after a dash). */
        final boolean user;
        final long at;
        /** What the user typed, handed back (in memory only) when it was refused, so their draft is not lost. */
        final String typed;

        Event(String token, State state, String reference, String said, Kind kind, boolean user) {
            this(token, state, reference, said, kind, user, "");
        }

        Event(String token, State state, String reference, String said, Kind kind, boolean user, String typed) {
            this.token = token == null ? "" : token;
            this.state = state;
            this.reference = reference == null ? "" : reference;
            this.said = said == null || said.isEmpty() ? state.said : said;
            this.kind = kind;
            this.user = user;
            this.at = System.currentTimeMillis();
            this.typed = typed == null ? "" : typed;
        }
    }

    /** Told each event, on the main thread, while a screen listens ({@link #listen}). */
    interface Listener {
        void changed(Event event);
    }

    /** The service's answer to one part. */
    static final class Response {
        final int status;
        final String body;
        /** From a Retry-After header, in milliseconds; 0 when none. */
        final long retryAfterMs;

        Response(int status, String body, long retryAfterMs) {
            this.status = status;
            this.body = body == null ? "" : body;
            this.retryAfterMs = Math.max(0, retryAfterMs);
        }
    }

    /** Posts one request body to {@link #ENDPOINT}. Tests install a fake (ConsentedTestApp) so none reaches it. */
    interface Transport {
        Response post(byte[] body) throws IOException;
    }

    static volatile Transport transport = FeedbackHttp::post;
    /** Builds and queues submissions, never on the main thread, and never behind a send under way. */
    private static final java.util.concurrent.ExecutorService BUILD =
            java.util.concurrent.Executors.newSingleThreadExecutor(work -> {
                Thread thread = new Thread(work, "feedback-build");
                thread.setDaemon(true);
                return thread;
            });

    /** The service's answer, honestly: sent, retry later (and why), or refused for good. */
    static State outcome(int status) {
        if (status >= 200 && status < 300) return State.SENT;
        if (status == 429) return State.RATE_LIMITED;
        if (status == 400 || status == 413 || status == 422) return State.REJECTED;
        if (status >= 500) return State.UNAVAILABLE;
        return State.NOT_ANSWERING;
    }

    /** The short reference an acknowledged part came back with, or "" when it gave none readable. */
    static String reference(Response response) {
        try {
            String reference = new JSONObject(response.body).optString("reference", "");
            return REFERENCE.matcher(reference).matches() ? reference : "";
        } catch (JSONException unreadable) {
            return "";
        }
    }

    // ---- Parts ----

    /** A fresh random token, 32 lowercase hex digits: one per submission, never per phone or install. */
    static String newToken() {
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : random) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return hex.toString();
    }

    static boolean validToken(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    /** "[Offer Filter feedback 1a2b3c4d part 1/3]": a part's first line. */
    static String header(Kind kind, String token, int index, int count) {
        return "[" + AppName.NAME + " " + kind.wire + " " + token.substring(0, 8) + " part " + (index + 1) + "/"
                + count + "]";
    }

    /** "[end part 1/3]": a part's last line. */
    static String footer(int index, int count) {
        return "[end part " + (index + 1) + "/" + count + "]";
    }

    /** One part as sent: its header line, the text, and its end line, so trimming can never touch the text. */
    static String framed(Kind kind, String token, int index, int count, String text) {
        return header(kind, token, index, count) + "\n" + text + "\n" + footer(index, count);
    }

    /** The text of a framed part (between its first and last line): the parts' texts, in order, are the whole. */
    static String unframed(String part) {
        int first = part.indexOf('\n');
        int last = part.lastIndexOf('\n');
        return first < 0 || last <= first ? "" : part.substring(first + 1, last);
    }

    /** What every later part of a feedback submission says, since the service needs a message on each. */
    static String continuation(int index, int count) {
        return "(continued: part " + (index + 1) + " of " + count + ")";
    }

    /**
     * {@code text} in pieces of at most {@code maxChars} characters and {@code maxBytes} bytes as JSON-escaped UTF-8,
     * each cut at a line's end where one is inside its budget (never inside a character pair). Joined in order, the
     * pieces are {@code text} exactly; no text is one piece of "".
     */
    static List<String> chunks(String text, int maxChars, int maxBytes) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        int at = 0;
        int length = text.length();
        while (at < length) {
            int end = at;
            int bytes = 0;
            int lineEnd = -1;
            while (end < length && end - at < maxChars) {
                int cost = jsonBytes(text.charAt(end));
                if (bytes + cost > maxBytes) break;
                bytes += cost;
                end++;
                if (text.charAt(end - 1) == '\n') lineEnd = end;
            }
            int cut = end;
            if (end < length) {
                if (lineEnd > at) cut = lineEnd;
                else if (cut > at + 1 && Character.isLowSurrogate(text.charAt(cut))) cut--;
            }
            if (cut <= at) cut = Math.min(length, at + 1);
            out.add(text.substring(at, cut));
            at = cut;
        }
        return out;
    }

    /** How many bytes {@code c} takes in a JSON string sent as UTF-8, at most. */
    static int jsonBytes(char c) {
        if (c == '"' || c == '\\' || c == '/' || c == '\n' || c == '\r' || c == '\t' || c == '\b' || c == '\f') return 2;
        if (c < 0x20 || c == ' ' || c == ' ') return 6;
        if (c < 0x80) return 1;
        if (c < 0x800) return 2;
        // A surrogate pair is four bytes in all; one alone is replaced, never more than three.
        return 3;
    }

    /**
     * The request for one part of a submission: the fields the service takes, the message on the first part (and a
     * continuation line on a feedback submission's later ones), and the part's framed diagnostics.
     *
     * @param text the part's diagnostics text (unframed), or null for a submission without diagnostics
     */
    static JSONObject request(Kind kind, String category, String message, boolean consented, String token,
                              int index, int count, String text, String appVersion, long versionCode)
            throws JSONException {
        JSONObject json = new JSONObject()
                .put("kind", kind.wire)
                .put("category", category)
                .put("appVersion", appVersion)
                .put("appVersionCode", versionCode)
                .put("diagnosticsConsented", consented && text != null)
                .put("reportToken", token)
                .put("partIndex", index)
                .put("partCount", count);
        String typed = message == null ? "" : message.trim();
        if (index == 0 && !typed.isEmpty()) json.put("message", typed);
        else if (index > 0 && kind == Kind.FEEDBACK) json.put("message", continuation(index, count));
        if (text != null) json.put("diagnostics", framed(kind, token, index, count, text));
        return json;
    }

    static byte[] bytes(JSONObject request) {
        return request.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** What the user typed, as sent: control characters other than line breaks and tabs dropped, cut to the limit. */
    static String typed(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_MESSAGE_CHARS));
        for (int i = 0; i < text.length() && out.length() < MAX_MESSAGE_CHARS; i++) {
            char c = text.charAt(i);
            if (c >= 0x20 || c == '\n' || c == '\t') out.append(c);
        }
        String trimmed = out.toString().trim();
        if (trimmed.length() > MAX_MESSAGE_CHARS) trimmed = trimmed.substring(0, MAX_MESSAGE_CHARS);
        // Never end on half a character pair.
        if (!trimmed.isEmpty() && Character.isHighSurrogate(trimmed.charAt(trimmed.length() - 1))) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    // ---- What the user attaches ----

    /** A submission built ahead of Send (for its preview), so what is previewed is what is sent. */
    static final class Prepared {
        final String token;
        final Kind kind;
        /** The diagnostics' parts, unframed; empty when none are attached. */
        final List<String> parts;
        final String format;

        Prepared(String token, Kind kind, List<String> parts, String format) {
            this.token = token;
            this.kind = kind;
            this.parts = Collections.unmodifiableList(new ArrayList<>(parts));
            this.format = format;
        }

        int count() {
            return Math.max(1, parts.size());
        }

        /** Every part exactly as it will be sent (before the masking re-applied just before sending). */
        String preview(String category, String message, String appVersion, long versionCode) {
            StringBuilder out = new StringBuilder()
                    .append("Kind: ").append(kind.wire).append(" · category: ").append(category).append('\n')
                    .append("App version: ").append(appVersion).append(" (").append(versionCode).append(")\n")
                    .append("Message:\n").append(typed(message).isEmpty() ? "(none)" : typed(message)).append("\n\n");
            if (parts.isEmpty()) {
                out.append("No diagnostics attached.\n");
            } else {
                out.append("Diagnostics (masked, ").append(parts.size()).append(parts.size() == 1 ? " part" : " parts")
                        .append("):\n");
                for (int i = 0; i < parts.size(); i++) {
                    out.append(framed(kind, token, i, parts.size(), parts.get(i))).append('\n');
                }
            }
            return out.toString();
        }
    }

    /**
     * The masked diagnostic report, in at most {@value #MAX_PARTS} parts: the whole report Share report builds (never
     * the one cut for a share sheet), with any recent stop's summary. Over that, the oldest decisions go first; the
     * logs and their excerpts are never what is cut. On a worker thread.
     *
     * @throws IllegalStateException when even with no decisions it would not fit (never seen: the logs are bounded)
     */
    static List<String> diagnosticParts(Context context) {
        Context app = context.getApplicationContext();
        for (int decisions = DiagnosticLog.REPORT_DECISIONS; decisions >= 0; decisions -= 10) {
            // Masked as the outbox masks each part before sending (masked text masks to itself): the preview is it.
            List<String> parts = chunks(PersonalText.maskLine(DiagnosticLog.fullReport(app, decisions)),
                    MAX_PART_CHARS, MAX_PART_BYTES);
            if (parts.size() <= MAX_PARTS) return parts;
        }
        throw new IllegalStateException("diagnostics too large");
    }

    // ---- Submitting ----

    /**
     * Prepares a feedback submission's diagnostics off the main thread (for Preview, or Send): {@code done} gets them
     * on the main thread, or null when they could not be built.
     */
    static void prepareFeedback(Context context, boolean attach, Consumer<Prepared> done) {
        Context app = context.getApplicationContext();
        BUILD.execute(() -> {
            Prepared prepared;
            try {
                prepared = new Prepared(newToken(), Kind.FEEDBACK,
                        attach ? diagnosticParts(app) : Collections.<String>emptyList(), FeedbackOutbox.TEXT);
            } catch (RuntimeException failure) {
                prepared = null;
            }
            Prepared result = prepared;
            MAIN.post(() -> done.accept(result));
        });
    }

    /** Prepares one offer's report off the main thread: {@code done} gets it on the main thread, or null. */
    static void prepareOfferReport(Context context, DecisionLog.Entry entry, OfferReport.Problem problem,
                                   Consumer<Prepared> done) {
        Context app = context.getApplicationContext();
        BUILD.execute(() -> {
            Prepared prepared;
            try {
                String report = OfferReport.text(app, problem, Updater.version(app), versionCode(app), android(),
                        entry);
                prepared = new Prepared(newToken(), Kind.PROBLEM, chunks(report, MAX_PART_CHARS, MAX_PART_BYTES),
                        FeedbackOutbox.OFFER);
            } catch (RuntimeException failure) {
                prepared = null;
            }
            Prepared result = prepared;
            MAIN.post(() -> done.accept(result));
        });
    }

    /**
     * Sends what the user typed in the feedback dialog, with diagnostics only when they chose to attach them
     * ({@code prepared}, built and previewed for this submission, or built now). Queued durably first, then sent at
     * once; {@link Event}s say how it went.
     *
     * @return the submission's token, to tell its events apart
     */
    static String sendFeedback(Context context, Category category, String message, boolean attach,
                               Prepared prepared) {
        Context app = context.getApplicationContext();
        String token = prepared != null ? prepared.token : newToken();
        String typed = typed(message);
        BUILD.execute(() -> {
            try {
                if (!Consent.accepted(app)) {
                    post(new Event(token, State.NOT_QUEUED, "", "Accept the notice first.", Kind.FEEDBACK, true));
                    return;
                }
                List<String> parts = !attach ? Collections.<String>emptyList()
                        : prepared != null && !prepared.parts.isEmpty() ? prepared.parts : diagnosticParts(app);
                FeedbackOutbox.submit(app, FeedbackOutbox.item(token, Kind.FEEDBACK, category.wire, false,
                        FeedbackOutbox.TEXT, typed, attach && !parts.isEmpty(), parts));
            } catch (RuntimeException failure) {
                post(new Event(token, State.NOT_QUEUED, "", "Couldn't prepare it; try again.", Kind.FEEDBACK, true));
            }
        });
        return token;
    }

    /**
     * Sends one offer's report (OfferReport, as diagnostics: the dialog that says what it sends is the user's consent)
     * with their note as the message.
     */
    static String sendOfferReport(Context context, DecisionLog.Entry entry, OfferReport.Problem problem, String note,
                                  Prepared prepared) {
        Context app = context.getApplicationContext();
        String token = prepared != null ? prepared.token : newToken();
        String typed = typed(note);
        BUILD.execute(() -> {
            try {
                if (!Consent.accepted(app)) {
                    post(new Event(token, State.NOT_QUEUED, "", "Accept the notice first.", Kind.PROBLEM, true));
                    return;
                }
                List<String> parts = prepared != null ? prepared.parts : chunks(OfferReport.text(app, problem,
                        Updater.version(app), versionCode(app), android(), entry), MAX_PART_CHARS, MAX_PART_BYTES);
                FeedbackOutbox.submit(app, FeedbackOutbox.item(token, Kind.PROBLEM, problem.category(), false,
                        FeedbackOutbox.OFFER, typed, true, parts));
            } catch (RuntimeException failure) {
                post(new Event(token, State.NOT_QUEUED, "", "Couldn't prepare it; try again.", Kind.PROBLEM, true));
            }
        });
        return token;
    }

    // ---- Diagnostics after each dash: the opt-in ----

    private static final String PREFS = "feedback";
    private static final String AFTER_DASH = "after_dash";
    private static final String AUTOMATIC_EPOCH = "automatic_epoch";
    private static final String REFERENCES = "references";
    private static final String LAST_STATE = "last_state";
    private static final String LAST_SAID = "last_said";
    private static final String LAST_REFERENCE = "last_reference";
    private static final String LAST_AT = "last_at";

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** "Share anonymous diagnostics after each dash": off unless the user turned it on; no update turns it on. */
    static boolean afterDashOn(Context context) {
        return prefs(context).getBoolean(AFTER_DASH, false);
    }

    /**
     * The user's choice. Off discards every automatic summary not yet sent, and what the current dash counted. Any
     * thread, the main one included: the choice holds at once (in memory; written to disk in the background), and the
     * files go on DashSummary's own thread.
     */
    static synchronized void setAfterDash(Context context, boolean on) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = prefs(app);
        boolean was = prefs.getBoolean(AFTER_DASH, false);
        SharedPreferences.Editor edit = prefs.edit().putBoolean(AFTER_DASH, on);
        // A new epoch: a summary of a dash that ended before the change is never queued or sent, even if the switch
        // goes back.
        if (was != on) edit.putLong(AUTOMATIC_EPOCH, prefs.getLong(AUTOMATIC_EPOCH, 0) + 1);
        edit.apply();
        DashSummary.optInChanged(app, on);
        if (was != on) {
            DiagnosticLog.log(app, "feedback", "diagnostics after each dash turned " + (on ? "on" : "off")
                    + (on ? "" : "; unsent ones discarded"));
        }
    }

    /**
     * Which epoch the opt-in is in: it changes whenever the opt-in is turned on or off, and when history is cleared. A
     * summary carries the epoch its dash ended in ({@link FeedbackOutbox#automatic}), and is queued and sent only in it.
     */
    static long automaticEpoch(Context context) {
        return prefs(context).getLong(AUTOMATIC_EPOCH, 0);
    }

    /** An automatic summary of {@code epoch} may be queued or sent: the opt-in is on, and has not changed since. */
    static boolean automaticAllowed(Context context, long epoch) {
        return afterDashOn(context) && automaticEpoch(context) == epoch;
    }

    /**
     * Clear history: every automatic summary not yet sent, and what the current dash counted, go (a summary being built
     * is never queued); the opt-in itself stays. Any thread, the main one included.
     */
    static synchronized void clearAutomatic(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = prefs(app);
        prefs.edit().putLong(AUTOMATIC_EPOCH, prefs.getLong(AUTOMATIC_EPOCH, 0) + 1).apply();
        DashSummary.forgetSoon(app);
    }

    // ---- What the user sees ----

    /** One accepted submission, kept on the phone so its reference can be found again. */
    static final class Sent {
        final long at;
        final String reference;
        final Kind kind;
        final boolean automatic;

        Sent(long at, String reference, Kind kind, boolean automatic) {
            this.at = at;
            this.reference = reference;
            this.kind = kind;
            this.automatic = automatic;
        }

        /** "6 Oct 2026 14:02 · 1a2b3c4d · feedback". */
        String line() {
            String what = automatic ? "after-dash diagnostics" : kind == Kind.PROBLEM ? "offer report" : "feedback";
            return new SimpleDateFormat("d MMM yyyy HH:mm", Locale.US).format(new Date(at)) + " · "
                    + (reference.isEmpty() ? "no reference" : reference) + " · " + what;
        }
    }

    /** The last {@value #REFERENCES_KEPT} accepted submissions, newest first. */
    static List<Sent> references(Context context) {
        List<Sent> out = new ArrayList<>();
        try {
            JSONArray kept = new JSONArray(prefs(context).getString(REFERENCES, "[]"));
            for (int i = 0; i < kept.length() && out.size() < REFERENCES_KEPT; i++) {
                JSONObject one = kept.getJSONObject(i);
                out.add(new Sent(one.getLong("at"), one.optString("ref", ""), Kind.named(one.optString("kind")),
                        one.optBoolean("auto", false)));
            }
        } catch (JSONException corrupt) {
            // Nothing readable kept.
        }
        return out;
    }

    static synchronized void remember(Context context, Sent sent) {
        JSONArray kept = new JSONArray();
        try {
            kept.put(new JSONObject().put("at", sent.at).put("ref", sent.reference).put("kind", sent.kind.wire)
                    .put("auto", sent.automatic));
            for (Sent older : references(context)) {
                if (kept.length() >= REFERENCES_KEPT) break;
                kept.put(new JSONObject().put("at", older.at).put("ref", older.reference).put("kind", older.kind.wire)
                        .put("auto", older.automatic));
            }
        } catch (JSONException impossible) {
            return;
        }
        prefs(context).edit().putString(REFERENCES, kept.toString()).apply();
    }

    /**
     * Settings' line under Send anonymous feedback: what is waiting and why, or the last result ("Sent 6 Oct 14:02 ·
     * 1a2b3c4d"); "" before anything was sent.
     */
    static String status(Context context) {
        SharedPreferences prefs = prefs(context);
        int waiting = FeedbackOutbox.pending(context);
        String said = prefs.getString(LAST_SAID, "");
        State last;
        try {
            last = State.valueOf(prefs.getString(LAST_STATE, ""));
        } catch (IllegalArgumentException none) {
            last = null;
        }
        if (waiting > 0) {
            return waiting + " waiting to send" + (last != null && last.waiting() ? " · " + said : "");
        }
        if (last == State.SENT) {
            String reference = prefs.getString(LAST_REFERENCE, "");
            return "Sent " + new SimpleDateFormat("d MMM HH:mm", Locale.US).format(new Date(prefs.getLong(LAST_AT, 0)))
                    + (reference.isEmpty() ? "" : " · " + reference);
        }
        return last == State.REJECTED ? said : "";
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    /** A finished user submission's event that no screen was listening for: shown when one starts. */
    private static volatile Event unseen;

    /** A screen listens while it is started (never after it stops: no captured screen outlives its own life). */
    static void listen(Listener listener) {
        LISTENERS.add(listener);
    }

    static void unlisten(Listener listener) {
        LISTENERS.remove(listener);
    }

    /** The finished user submission's event no screen saw, once. Main thread. */
    static Event takeUnseen() {
        Event event = unseen;
        unseen = null;
        return event;
    }

    /** Records an event for Settings' line and tells the listening screen, on the main thread. Any thread. */
    static void post(Event event) {
        MAIN.post(() -> deliver(event));
    }

    private static void deliver(Event event) {
        if (LISTENERS.isEmpty()) {
            if (event.user && event.state.finished) unseen = event;
            return;
        }
        for (Listener listener : LISTENERS) listener.changed(event);
    }

    /** Keeps the last result for Settings' line. Any thread. */
    static void noteState(Context context, Event event) {
        prefs(context).edit().putString(LAST_STATE, event.state.name()).putString(LAST_SAID, event.said)
                .putString(LAST_REFERENCE, event.reference).putLong(LAST_AT, event.at).apply();
    }

    // ---- The app ----

    @SuppressWarnings("deprecation")
    static long versionCode(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (android.content.pm.PackageManager.NameNotFoundException impossible) {
            return 1;
        }
    }

    /** "Android 15 (API 35)": the version alone, without the phone's maker. */
    static String android() {
        return "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
    }

    /** Waits for submissions being built and queued, then for the sends they started; for tests. */
    static void flush() {
        try {
            BUILD.submit(() -> { }).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception interruptedOrTimedOut) {
            if (interruptedOrTimedOut instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        FeedbackOutbox.flush();
    }

    /** For tests: as a new process would, forget the unseen event and every listener. */
    static void forgetCache() {
        unseen = null;
        LISTENERS.clear();
    }

    private Feedback() {}
}
