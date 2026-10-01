package com.local.dasherfilter;

import android.app.KeyguardManager;
import android.app.UiModeManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Peek, the user's choice ("peek mode should have a toggle and be on by default"). Dasher's notification of a
 * background offer names only the store ("New Delivery! New Order: Go to Taco Bell"), never the pay, miles or time,
 * so it can never be judged from the notification; only Dasher's screen shows the offer. When such an offer comes while
 * Dasher is hidden and the phone is unlocked, the screen reader brings Dasher up for a moment, and the offer is read
 * and decided exactly as any offer on screen (a failing one gets the immediate Decline, its confirmation, the sound
 * turned down and the hand-back, all as before); once the app's own decline of it completed, the user is taken back to
 * the app they were in. A passing or unclear offer stays on screen for the user, except while they navigate (the
 * user's decision): then Peek goes back to the map at once and the offer's card carries what was read. It is the one
 * exception to "the app never opens Dasher by itself", and only while the Peek switch (Settings) is on.
 *
 * <p>Before Dasher is opened, the touch watch goes up and the phone must be quiet for {@link #QUIET_MS} (no touch, no
 * keyboard; the user's decision), {@link #QUIET_WAIT_MS} at most. A peek ends with Dasher left as it is after anything
 * of the user's, a decline handed back, refused or not confirmed, the screen off or locked, a call, or {@link #MAX_MS}.
 * It goes back only on positive proof: the app's own decline of the peeked offer completed (its question confirmed, or
 * an add-on's single Decline) and a read shows a screen Dasher's own words explain (the wait for offers, the dash
 * over, or the route the declined offer came during) with none of an offer's facts; or, with no offer, {@link
 * #NO_OFFER_MS} after such a screen; or, while navigating, a passing or unclear offer.
 *
 * <p>This is one peek's state as the scanner thread keeps it (on {@link #now}, elapsed time: uptime stops in deep
 * sleep), what Peek counts across peeks, and the "[peek]" lines any thread may write. The app in front is kept in
 * memory for one peek only, and the log names only its kind ({@link Front#kind}), never its package.
 */
final class Peek {
    /** No touch and no keyboard this long before Dasher is opened. */
    static final long QUIET_MS = 700;
    /** That quiet is waited for this long at most; else no peek ("you were using the phone"). */
    static final long QUIET_WAIT_MS = 3_000;
    /** Dasher's window must appear this long after its launch intent was started (cold starts take 2-5 s). */
    static final long OPEN_MS = 6_000;
    /** With Dasher's screen recognised and no offer on it this long, the peek goes back (the offer expired). */
    static final long NO_OFFER_MS = 4_000;
    /** A peek, including any offers it follows, ends this long after it opened; Dasher stays as it is. */
    static final long MAX_MS = 20_000;
    /** The next peek begins at least this long after the last one ended. */
    static final long GAP_MS = 5_000;
    /** At most {@link #CAP} peeks (and offers a peek followed) in {@link #CAP_WINDOW_MS}. */
    static final int CAP = 6;
    static final long CAP_WINDOW_MS = 10 * 60_000L;
    /**
     * A peek that left an offer with the user holds its notification's key this long (an offer's lifetime, as the
     * notification path counts it), unless the screen sees an offer end first: DoorDash re-posts an offer's
     * notification as it ages, which would otherwise bring Dasher up again for it.
     */
    static final long LEFT_WITH_YOU_MS = OfferAlertState.LIFETIME_MS;
    /** A post older than this is not peeked at: its offer may be gone by the time Dasher is up. */
    static final long POST_AGE_MS = 10_000;
    /** A screen offer read this recently that pairs with the post: the user saw it ({@link OfferPairing}). */
    static final long SCREEN_READ_MS = OfferPairing.NOTICE_LAG_MS;
    /** No peek this long after an acceptance or an Accept tap was seen. */
    static final long AFTER_ACCEPT_MS = 60_000;
    /** An Accept or Decline tap this soon after Dasher appeared in a peek teaches nothing. */
    static final long EARLY_TAP_MS = 1_000;
    /** After going back, whether the app came back is looked at this often, this long. */
    static final long BACK_CHECK_EVERY_MS = 250;
    static final long BACK_CHECK_MS = 1_500;
    /** In a row, these pause Peek: peeks that found no offer, and peeks whose Dasher never came up. */
    static final int EMPTY_TO_PAUSE = 3;
    static final int FAILED_TO_PAUSE = 2;
    /** An application window covering less of the display than this is not one full-screen app. */
    static final double FILLS = 0.95;

    /** Navigation apps, by a fixed list: with one in front, the user is navigating. */
    static final List<String> NAVIGATION = Arrays.asList("com.google.android.apps.maps", "com.waze");
    /** System screens that stand in front of an app for a moment (a chooser, a permission, a picker): no peek. */
    static final List<String> GUESTS = Arrays.asList("android", "com.android.intentresolver",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.documentsui", "com.google.android.documentsui",
            "com.android.providers.media.module", "com.google.android.providers.media.module",
            "com.google.android.gms");

    /** Peek's clock: elapsed time, which runs in deep sleep too. Tests may replace it. */
    static volatile LongSupplier clock = SystemClock::elapsedRealtime;

    static long now() {
        return clock.getAsLong();
    }

    private static final long NEVER = Long.MIN_VALUE;

    /** What the notification path knows of the post that may be peeked at. */
    static final class Request {
        final String key;
        final long postTime;
        /** The store Dasher's notification names, as written; empty when it names none. */
        final String store;
        /** A replay of a post already handled (a reconnect, a rules change): never peeked at. */
        final boolean replay;
        /** The post that began this offer's notification, not an update of one already announced. */
        final boolean fresh;
        /** The card of the notification incarnation, rung once if the peek does not happen. */
        final String alertTag;

        Request(String key, long postTime, String store, boolean replay, boolean fresh, String alertTag) {
            this.key = key == null ? "" : key;
            this.postTime = postTime;
            this.store = store == null ? "" : store;
            this.replay = replay;
            this.fresh = fresh;
            this.alertTag = alertTag;
        }

        /** This one post: its key and its time. */
        String post() {
            return key + "@" + postTime;
        }

        /** For the log: the store, or "a DoorDash offer" when the notification names none. */
        String what() {
            return store.isEmpty() ? "a DoorDash offer" : store;
        }
    }

    /** Where the user was, and so how a peek goes back there. */
    enum Back {
        /** Another app: its launcher activity, as a launcher starts it. */
        APP,
        /** The home screen: Android's Home action. */
        HOME,
        /** Offer Filter's own screen: its task brought to the front. */
        OURS
    }

    /** The app in front when a peek began: kept in memory for that peek only. */
    static final class Front {
        final Back back;
        /** Its package: never logged. */
        final String pkg;
        /** Its launcher activity, for {@link Back#APP}; null otherwise. */
        final ComponentName launcher;
        /** Its window's ID as Android listed it, to tell a new window in front while Dasher opens. */
        final int windowId;

        Front(Back back, String pkg, ComponentName launcher, int windowId) {
            this.back = back;
            this.pkg = pkg;
            this.launcher = launcher;
            this.windowId = windowId;
        }

        boolean navigation() {
            return back == Back.APP && NAVIGATION.contains(pkg);
        }

        /** What the log says it was: its kind, never its name. */
        String kind() {
            switch (back) {
                case HOME:
                    return "the home screen";
                case OURS:
                    return AppName.NAME;
                default:
                    return navigation() ? "a navigation app" : "another app";
            }
        }
    }

    /** How far a peek has come. */
    enum Phase {
        /** None under way. */
        NONE,
        /** The touch watch is up and the phone must be quiet; Dasher not opened yet. */
        ARMING,
        /** Dasher's launch intent was started; its window not seen yet. */
        OPENING,
        /** Dasher seen on screen; no offer declined (yet). */
        UP,
        /** The peeked offer's first Decline was tapped. */
        DECLINING,
        /** Its question's Decline was tapped too. */
        CONFIRMED
    }

    private Phase phase = Phase.NONE;
    private Request request;
    private Front front;
    private ComponentName dasher;
    private long armedAt = NEVER;
    private long quietSince = NEVER;
    private long openedAt = NEVER;
    private long upAt = NEVER;
    private long lastUpAt = NEVER;
    /** When the offer it follows now came (its start, or a chained offer's); the absolute deadline stays at openedAt. */
    private long followingSince = NEVER;
    private long lastEndedAt = NEVER;
    private final ArrayDeque<Long> starts = new ArrayDeque<>();
    private long generation;
    private long actions;
    private OfferSnapshot offer = OfferSnapshot.UNKNOWN;
    private boolean addOn;
    private boolean routeBefore;
    private boolean offerSign;
    private long recognisedAt = NEVER;
    private boolean lastRecognised;
    private boolean leftWithUser;
    private long offerEndsAt = NEVER;
    private int emptyInARow;
    private int failedInARow;
    private String heldKey = "";
    private long heldUntil = NEVER;
    private final LinkedHashSet<String> peekedPosts = new LinkedHashSet<>();
    private static final int KEPT_POSTS = 16;

    /**
     * Why the notification path's own conditions do not allow a peek, or null when they do. Checked on the main thread
     * before the card is posted (silently when a peek will be tried); the rest is the screen reader's.
     *
     * @param foreground whether Dasher is on screen, as the notification path just confirmed it
     * @param screenReadAgo how long ago a screen offer that pairs with this post was read (ms), or -1 for none
     */
    static String refusal(Context context, Request request, FilterSettings settings, boolean foreground,
                          long screenReadAgo) {
        if (request.replay) return "a replay after a reconnect or a rules change, not a fresh post";
        if (!request.fresh) return "an update of an offer already announced, not a fresh post";
        if (!FilterStore.peek(context)) return "Peek is off in Settings";
        if (!Consent.accepted(context)) return "the notice isn't accepted yet";
        if (!settings.enabled) return "auto-decline is paused";
        if (!settings.hasAnyRule()) return "no rules are set";
        if (foreground) return "Dasher is on screen";
        long age = System.currentTimeMillis() - request.postTime;
        if (age < -1_000) return "the notification's time is in the future";
        if (age > POST_AGE_MS) return "the notification is " + seconds(age) + " old";
        if (screenReadAgo >= 0) return "Dasher's screen showed this offer " + seconds(screenReadAgo) + " ago";
        if (Updater.installing(context)) return "an update is installing";
        return null;
    }

    /** Why this peek's own state does not allow one now, or null when it does. */
    String refusal(Request request, long now) {
        if (active()) return "a peek is under way";
        if (lastEndedAt != NEVER && now - lastEndedAt < GAP_MS) {
            return "the last peek ended " + seconds(now - lastEndedAt) + " ago (" + GAP_MS / 1000 + " s apart at least)";
        }
        dropOldStarts(now);
        if (starts.size() >= CAP) return CAP + " peeks in the last " + CAP_WINDOW_MS / 60_000 + " minutes already";
        if (peekedPosts.contains(request.post())) return "this notification was peeked at already";
        if (request.key.equals(heldKey) && now < heldUntil) {
            return "the offer the last peek left with you may still be up on this notification";
        }
        return null;
    }

    private void dropOldStarts(long now) {
        while (!starts.isEmpty() && now - starts.peekFirst() >= CAP_WINDOW_MS) starts.pollFirst();
    }

    /** The touch watch goes up; Dasher opens once the phone is quiet. */
    void arm(Request request, Front front, ComponentName dasher, long now) {
        this.request = request;
        this.front = front;
        this.dasher = dasher;
        this.armedAt = now;
        this.quietSince = now;
        this.openedAt = NEVER;
        this.upAt = NEVER;
        this.followingSince = NEVER;
        this.offer = OfferSnapshot.UNKNOWN;
        this.addOn = false;
        this.routeBefore = false;
        this.offerSign = false;
        this.recognisedAt = NEVER;
        this.lastRecognised = false;
        this.leftWithUser = false;
        this.offerEndsAt = NEVER;
        this.phase = Phase.ARMING;
    }

    /** A touch while arming: the quiet starts again from it. */
    void touchedWhileArming(long at) {
        if (phase == Phase.ARMING) quietSince = Math.max(quietSince, at);
    }

    /** Whether the phone has been quiet long enough to open Dasher. */
    boolean quiet(long now) {
        return phase == Phase.ARMING && now - quietSince >= QUIET_MS;
    }

    /** Whether the quiet is past waiting for. */
    boolean quietTimedOut(long now) {
        return phase == Phase.ARMING && now - armedAt >= QUIET_WAIT_MS;
    }

    /** Dasher's launch intent was started. */
    void opened(long now, long generation, long actions) {
        this.openedAt = now;
        this.followingSince = now;
        this.generation = generation;
        this.actions = actions;
        this.phase = Phase.OPENING;
        dropOldStarts(now);
        starts.addLast(now);
        peekedPosts.add(request.post());
        while (peekedPosts.size() > KEPT_POSTS) peekedPosts.remove(peekedPosts.iterator().next());
    }

    boolean active() {
        return phase != Phase.NONE;
    }

    Phase phase() {
        return phase;
    }

    Request request() {
        return request;
    }

    Front front() {
        return front;
    }

    ComponentName dasher() {
        return dasher;
    }

    long armedAt() {
        return armedAt;
    }

    long openedAt() {
        return openedAt;
    }

    long upAt() {
        return upAt;
    }

    long followingSince() {
        return followingSince;
    }

    long generation() {
        return generation;
    }

    long actions() {
        return actions;
    }

    /** Dasher's window was seen: true the first time. */
    boolean up(long now) {
        if (phase != Phase.OPENING) return false;
        phase = Phase.UP;
        upAt = now;
        lastUpAt = now;
        failedInARow = 0;
        return true;
    }

    /** Whether a tap at {@code at} came within {@link #EARLY_TAP_MS} of Dasher appearing in a peek. */
    boolean earlyTap(long at) {
        return lastUpAt != NEVER && at >= lastUpAt - 100 && at - lastUpAt <= EARLY_TAP_MS;
    }

    /** A read showed a sign of an offer (its Accept or Decline, a question, or a screen too big to read). */
    void offerSign() {
        if (phase == Phase.UP || phase == Phase.DECLINING || phase == Phase.CONFIRMED) offerSign = true;
    }

    boolean sawOffer() {
        return offerSign;
    }

    /**
     * What this read of Dasher made of its screen while no offer was read: one Dasher's words explain (the wait for
     * offers, idle, the dash over, a delivery) or not. The wait for an offer starts at the first explained one.
     */
    void screen(boolean recognised, long now) {
        lastRecognised = recognised;
        if (recognised && recognisedAt == NEVER && phase == Phase.UP) recognisedAt = now;
    }

    boolean lastRecognised() {
        return lastRecognised;
    }

    long recognisedAt() {
        return recognisedAt;
    }

    /** A newer offer's notification came: the peek follows whatever Dasher shows next. */
    void generation(long generation) {
        this.generation = generation;
    }

    /**
     * Whether an offer read now is the one this peek is for: before it declined one, any offer read; after, one whose
     * facts do not contradict the offer it declined.
     */
    boolean owns(OfferSnapshot facts) {
        if (phase == Phase.OPENING || phase == Phase.UP) return true;
        return active() && !facts.contradicts(offer);
    }

    /** Whether another offer may be followed now: one declined before it, and within {@link #CAP}. */
    boolean mayFollow(long now) {
        if (phase != Phase.DECLINING && phase != Phase.CONFIRMED && phase != Phase.UP) return false;
        dropOldStarts(now);
        return openedAt != NEVER && now - openedAt < MAX_MS && starts.size() < CAP;
    }

    /** Another offer came while this peek was under way, nothing of the user's seen: the peek follows it. */
    void follow(long now) {
        starts.addLast(now);
        followingSince = now;
        offer = OfferSnapshot.UNKNOWN;
        addOn = false;
        offerSign = true;
        recognisedAt = NEVER;
        phase = Phase.UP;
    }

    /** The peeked offer's first Decline was tapped (again, after Dasher's glitch: its question is awaited afresh). */
    void declined(OfferSnapshot facts, boolean addOn, boolean routeBefore) {
        if (!active()) return;
        this.offer = facts;
        this.addOn = addOn;
        this.routeBefore = routeBefore;
        this.offerSign = true;
        this.phase = Phase.DECLINING;
    }

    /** Its question's Decline was tapped. */
    void confirmed() {
        if (phase == Phase.DECLINING) phase = Phase.CONFIRMED;
    }

    /**
     * Whether the peeked offer's decline is complete as far as the app can do it: its question confirmed, or an
     * add-on's Decline (Dasher asks nothing then and goes straight back to the route).
     */
    boolean declineComplete() {
        return phase == Phase.CONFIRMED || (phase == Phase.DECLINING && addOn);
    }

    /** Whether the declined offer came during a route (an add-on, or a route stored): Dasher goes back to it. */
    boolean routeAfter() {
        return addOn || routeBefore;
    }

    /** A passing or unclear offer was left with the user (on screen, or on its card). */
    void leftWithUser() {
        leftWithUser = true;
    }

    /**
     * The peek is over. One that did not go back after Dasher was up left an offer with the user: its key is held for
     * {@link #LEFT_WITH_YOU_MS}. Counts toward pausing Peek: a peek that found no offer, one whose Dasher never came up.
     *
     * @return why Peek should pause now, or null
     */
    String end(boolean wentBack, boolean openFailed, long now) {
        if (!active()) return null;
        boolean opened = openedAt != NEVER;
        if (opened && !openFailed && upAt != NEVER && offerSign && (!wentBack || leftWithUser)) {
            heldKey = request.key;
            // Until the offer's own countdown runs out, when one was read.
            heldUntil = offerEndsAt != NEVER ? Math.min(offerEndsAt, now + LEFT_WITH_YOU_MS) : now + LEFT_WITH_YOU_MS;
        }
        String pause = null;
        if (openFailed) {
            failedInARow++;
            if (failedInARow >= FAILED_TO_PAUSE) {
                pause = "Dasher did not come up for " + failedInARow + " peeks in a row (the phone may block apps "
                        + "opening from the background)";
                failedInARow = 0;
            }
        } else if (upAt != NEVER) {
            emptyInARow = offerSign ? 0 : emptyInARow + 1;
            if (emptyInARow >= EMPTY_TO_PAUSE) {
                pause = emptyInARow + " peeks in a row found no offer";
                emptyInARow = 0;
            }
        }
        if (opened) lastEndedAt = now;
        phase = Phase.NONE;
        request = null;
        dasher = null;
        return pause;
    }

    /** The offer read shows {@code secondsLeft} on its countdown (-1 for none): it is gone by then. */
    void countdown(int secondsLeft, long now) {
        if (secondsLeft >= 0) offerEndsAt = now + secondsLeft * 1000L + 2_000;
    }

    /** The front app is forgotten once the look after going back is done. */
    void forgetFront() {
        if (!active()) front = null;
    }

    /** The screen saw the last offer end (Dasher idle, a delivery, the dash over): no key is held for it any more. */
    void offerEnded() {
        heldKey = "";
        heldUntil = NEVER;
    }

    /** " (Dasher up 0.4 s after it was opened; 2.6 s in all)", for an outcome's line. */
    String timing(long now) {
        if (openedAt == NEVER) return "";
        String up = upAt == NEVER ? "Dasher not seen up"
                : "Dasher up " + seconds(upAt - openedAt) + " after it was opened";
        return " (" + up + "; " + seconds(now - openedAt) + " in all)";
    }

    static String seconds(long ms) {
        return String.format(Locale.US, "%.1f s", Math.max(0, ms) / 1000.0);
    }

    /**
     * What a card carries after a peek went back with a passing or unclear offer: "Passes: $12.50 · 5.1 mi · 22 min",
     * "Unclear: $9.60 · 2 stops (pay not found)".
     */
    static String cardText(OfferRule.Result result, OfferSnapshot read, String reason) {
        String pay = read.payCents != null ? DecisionLog.money(read.payCents)
                : read.payAtMostCents != null ? "up to " + DecisionLog.money(read.payAtMostCents) : "pay not read";
        String facts = DecisionLog.facts(read);
        String line = pay + (read.miles == null && read.minutes == null && read.stops == null ? "" : " · " + facts);
        return result == OfferRule.Result.KEEP ? "Passes: " + line
                : "Unclear: " + line + (reason == null || reason.isEmpty() ? "" : " (" + reason + ")");
    }

    // ---- The log (any thread) ----

    /** The last skip logged and how often it came again since; a new app (a new process) starts afresh. */
    private static Object skipsOf;
    private static String lastSkip = "";
    private static int skipRepeats;

    /** "[peek] skipped: why", once: the same reason again is counted, and the count logged before the next line. */
    static void skipped(Context context, String why) {
        synchronized (Peek.class) {
            forgetIfNewApp(context);
            if (why.equals(lastSkip)) {
                skipRepeats++;
                return;
            }
            flushSkips(context);
            lastSkip = why;
            DiagnosticLog.log(context, "peek", "skipped: " + why + state(context));
        }
    }

    /** A "[peek]" line that is not a skip: a peek opening Dasher, how one ended, what came after. */
    static void log(Context context, String line) {
        synchronized (Peek.class) {
            forgetIfNewApp(context);
            flushSkips(context);
            lastSkip = "";
            DiagnosticLog.log(context, "peek", line + state(context));
        }
    }

    /** " car=no lock=no": whether the phone was in car mode, and locked, as each line was written. */
    private static String state(Context context) {
        String car = "?";
        String lock = "?";
        try {
            UiModeManager modes = context.getSystemService(UiModeManager.class);
            if (modes != null) car = carMode(modes) ? "yes" : "no";
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            if (keyguard != null) lock = keyguard.isKeyguardLocked() ? "yes" : "no";
        } catch (RuntimeException unknown) {
            // Said as unknown.
        }
        return " car=" + car + " lock=" + lock;
    }

    static boolean carMode(UiModeManager modes) {
        return modes.getCurrentModeType() == Configuration.UI_MODE_TYPE_CAR;
    }

    private static void flushSkips(Context context) {
        if (skipRepeats > 0) {
            DiagnosticLog.log(context, "peek", "(the same skip for " + skipRepeats + " more "
                    + (skipRepeats == 1 ? "offer)" : "offers)"));
        }
        skipRepeats = 0;
    }

    private static void forgetIfNewApp(Context context) {
        Object app = context.getApplicationContext();
        if (app == skipsOf) return;
        skipsOf = app;
        lastSkip = "";
        skipRepeats = 0;
    }
}
