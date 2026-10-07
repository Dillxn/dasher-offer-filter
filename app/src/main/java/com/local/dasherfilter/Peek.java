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
import java.util.concurrent.atomic.AtomicReference;
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
 * <p>Before Dasher is opened, the touch watch goes up and must see the phone quiet for {@link #QUIET_MS} (no touch, no
 * keyboard; the user's decision), {@link #QUIET_WAIT_MS} at most. A peek ends with Dasher left as it is after anything
 * of the user's, a decline handed back, refused or not confirmed, a call, or {@link #MAX_MS}. It goes back only on
 * positive proof: the app's own decline of the peeked offer completed (its question confirmed, or an add-on's single
 * Decline) and a read shows a screen Dasher's own words explain (the wait for offers, the dash over, or the route the
 * declined offer came during) with none of an offer's facts; or, with no offer, {@link #NO_OFFER_MS} after such a
 * screen once Dasher removed the offer's notification (never while it is still posted); or, while navigating, a
 * passing or unclear offer, or an offer Dasher never drew.
 *
 * <p>Dasher brought up by its launcher fetches a background offer itself, and sometimes never draws it, or draws it
 * without its details (the 0.4.72 report: details only after entering split screen). With Dasher up
 * {@link #PRESENT_MS}, none of the offer's figures drawn (its controls or headline alone are not them) and the offer's
 * notification still posted, Dasher's own notification tap is sent once; still none of its figures
 * {@link #OWN_TAP_WAIT_MS} later, the peek ends ({@link Outcome#UNSHOWN}) and the offer's card says so.
 *
 * <p>The screen turning off or the phone locking suspends an opened peek (memory only) for {@link #RESUME_MS}: an
 * unlock in time resumes it from a fresh read, the locked time not counted. A fresh post refused only for the lock
 * is looked at again at the unlock, up to {@link #UNLOCK_POST_MS} after it was posted. Peek pauses itself, for
 * {@link #PAUSE_MS} or until the next dash starts, only after {@link #EMPTY_TO_PAUSE} withdrawn offers or
 * {@link #FAILED_TO_PAUSE} launches that never came up in a row (within {@link #STREAK_MS}); after a runtime failure,
 * until the next dash starts or the user taps Resume. The Settings switch stays the user's own.
 *
 * <p>This is one peek's state as the scanner thread keeps it (on {@link #now}, elapsed time: uptime stops in deep
 * sleep), what Peek counts across peeks, and the "[peek]" lines any thread may write. The app in front is kept in
 * memory for one peek only, and the log names only its kind ({@link Front#kind}), never its package.
 */
final class Peek {
    /** No touch and no keyboard this long before Dasher is opened. */
    static final long QUIET_MS = 700;
    /**
     * A touch the watch saw while arming may still be under way this long after it landed: Android tells the watch of
     * a gesture's first finger landing only, never of a finger kept down (a pan of the map, a pinch, a long press). So
     * the quiet starts this long after it, never mid-gesture.
     */
    static final long GESTURE_MS = 2_000;
    /** That quiet is waited for this long at most; else no peek ("you were using the phone"). */
    static final long QUIET_WAIT_MS = 3_000;
    /** Dasher's window must appear this long after its launch intent was started (cold starts take 2-5 s). */
    static final long OPEN_MS = 6_000;
    /**
     * With Dasher's screen recognised and no offer on it this long, and the offer's notification gone, the peek goes
     * back (the offer was withdrawn). While the notification is still posted it never goes back for want of an offer.
     */
    static final long NO_OFFER_MS = 4_000;
    /**
     * Dasher up this long with none of the offer's figures drawn while its notification is still posted: Dasher's own
     * notification tap is sent, once (Dasher's launcher resumes its task; the notification's own tap asks it for the
     * offer).
     */
    static final long PRESENT_MS = 2_500;
    /**
     * Signs of the offer being drawn without its figures (a control or its label, its headline) this recent: it may
     * still be drawing, so Dasher's own notification tap waits this long after the first such sign.
     */
    static final long DRAWING_MS = 1_000;
    /** Still none of the offer's figures this long after Dasher's own notification tap: the offer never showed. */
    static final long OWN_TAP_WAIT_MS = 5_000;
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
    /**
     * A fresh post refused only because the screen was off or the phone locked is peeked at after the unlock if its
     * notification is still posted, unreplaced, and the post is no older than this.
     */
    static final long UNLOCK_POST_MS = 40_000;
    /** A peek the screen turning off or the lock interrupted is kept in memory this long for the unlock. */
    static final long RESUME_MS = 60_000;
    /** A screen offer read this recently that pairs with the post: the user saw it ({@link OfferPairing}). */
    static final long SCREEN_READ_MS = OfferPairing.NOTICE_LAG_MS;
    /** No peek this long after an acceptance or an Accept tap was seen. */
    static final long AFTER_ACCEPT_MS = 60_000;
    /** An Accept or Decline tap this soon after Dasher appeared in a peek teaches nothing. */
    static final long EARLY_TAP_MS = 1_000;
    /** After going back, whether the app came back is looked at this often, this long. */
    static final long BACK_CHECK_EVERY_MS = 250;
    static final long BACK_CHECK_MS = 1_500;
    /**
     * In a row, these pause Peek for a while: peeks whose offer was withdrawn (its notification gone, Dasher's empty
     * screen read), and peeks whose Dasher never came up. Locks, touches, calls, splits, settings, timeouts and offers
     * Dasher never drew never count.
     */
    static final int EMPTY_TO_PAUSE = 3;
    static final int FAILED_TO_PAUSE = 2;
    /** "In a row" holds within this long only; a dash's start begins it afresh. */
    static final long STREAK_MS = 30 * 60_000L;
    /** A pause Peek puts on itself lasts this long at most, or until the next dash starts. */
    static final long PAUSE_MS = 15 * 60_000L;
    /** An application window covering less of the display than this is not one full-screen app. */
    static final double FILLS = 0.95;

    /**
     * Navigation apps, by a fixed list (package identity only, never logged): with one in front, the user is
     * navigating. Google Maps, Waze and Google Maps Go.
     */
    static final List<String> NAVIGATION = Arrays.asList("com.google.android.apps.maps", "com.waze",
            "com.google.android.apps.mapslite");
    /** System screens that stand in front of an app for a moment (a chooser, a permission, a picker): no peek. */
    static final List<String> GUESTS = Arrays.asList("android", "com.android.intentresolver",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.documentsui", "com.google.android.documentsui",
            "com.android.providers.media.module", "com.google.android.providers.media.module",
            "com.google.android.gms");

    /**
     * What Peek's Settings row says under its name: a locked phone is never peeked at (during a dash the screen is not
     * let time out, so only the user's own lock pauses it; an offer that came meanwhile is looked at again at the unlock).
     */
    static final String LOCKED_NOTE = "Peek pauses while your phone is locked";

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

    /** How an opened peek ended, for what Peek counts toward pausing itself. */
    enum Outcome {
        /** The peeked offer's own decline completed and the user was taken back. */
        DECLINED_BACK,
        /** An offer was read and left with the user: on screen, or on its card after going back to the map. */
        LEFT_WITH_USER,
        /**
         * Dasher removed the offer's notification and showed a recognised empty screen: an empty peek, unless the lock
         * came between the offer and the peek's look at it ({@link #lockCameBetween}: the offer may have gone while the
         * phone was locked), which neither counts nor resets the run.
         */
        WITHDRAWN,
        /** Dasher never drew the offer, even after its own notification tap: its card says so. */
        UNSHOWN,
        /** Anything of the user's, the lock or screen-off, a call, a split, a setting turned off. */
        INTERRUPTED,
        /** {@link #MAX_MS} ran out with the offer's notification still posted (or not known gone). */
        TIMEOUT,
        /** Dasher's window never came up. */
        OPEN_FAILED
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
    /** The oldest post this peek may open Dasher for ({@link #POST_AGE_MS}, or after an unlock more). */
    private long maxPostAge = POST_AGE_MS;
    /** When this peek first read any of an offer's facts or both its controls; {@link #NEVER} for not yet. */
    private long factsAt = NEVER;
    /** When Dasher's own notification tap was sent (or found untappable): the offer's wait for showing starts. */
    private long ownTapAt = NEVER;
    private boolean presentationTried;
    private boolean ownTapRequested;
    /** A recognised empty screen was read during this peek. */
    private boolean recognisedSeen;
    /** The user touched the screen before Dasher's window first appeared: only the automatic decline goes back. */
    private boolean openingTouched;
    /** A touch during the quiet wait (since it was armed). */
    private boolean touchedWhileArming;
    /** When the touch watch was first seen up while arming; {@link #NEVER} until then. */
    private long watchUpAt = NEVER;
    /** While the lock or screen-off holds it: since when; {@link #NEVER} otherwise. */
    private long suspendedAt = NEVER;
    /** When an unlock resumed it; {@link #NEVER} for a peek never suspended. */
    private long resumedAt = NEVER;
    /** A post the lock kept back, looked at after the unlock (up to {@link #UNLOCK_POST_MS} old): the lock delayed it. */
    private boolean lockDelayed;
    /** A return held for the quiet after the unlock. */
    private boolean returnWaiting;
    private boolean noOfferLogged;
    private boolean factsLogged;
    /** Consecutive counted outcomes, by when (elapsed time), oldest first. */
    private final ArrayDeque<Long> emptyStreak = new ArrayDeque<>();
    private final ArrayDeque<Long> failedStreak = new ArrayDeque<>();
    /** The dash (its start, wall clock; 0 for none) the streaks belong to. */
    private long streakDash = NEVER;
    /** When a read first showed signs of the offer being drawn without its figures; {@link #NEVER} for none yet. */
    private long drawingSince = NEVER;
    /**
     * A read showed what may be the whole offer without its figures read: a screen too big to read, or Dasher's
     * question. Dasher's own notification tap is never sent over it, and the offer is not taken for one never shown.
     */
    private boolean unreadOffer;
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
        return refusal(context, request, settings, foreground, screenReadAgo, POST_AGE_MS);
    }

    /**
     * As {@link #refusal(Context, Request, FilterSettings, boolean, long)}, for a post up to {@code maxAgeMs} old:
     * {@link #UNLOCK_POST_MS} for a post looked at again after an unlock.
     */
    static String refusal(Context context, Request request, FilterSettings settings, boolean foreground,
                          long screenReadAgo, long maxAgeMs) {
        if (request.replay) return "a replay after a reconnect or a rules change, not a fresh post";
        if (!request.fresh) return "an update of an offer already announced, not a fresh post";
        if (!FilterStore.peek(context)) return "Peek is off in Settings";
        String paused = pausedWhy(context);
        if (paused != null) return "Peek is paused for now (" + paused + ")";
        if (!Consent.accepted(context)) return "the notice isn't accepted yet";
        if (!settings.enabled) return "auto-decline is paused";
        if (!settings.hasAnyRule()) return "no rules are set";
        if (foreground) return "Dasher is on screen";
        long age = System.currentTimeMillis() - request.postTime;
        if (age < -1_000) return "the notification's time is in the future";
        if (age > maxAgeMs) return "the notification is " + seconds(age) + " old";
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
        arm(request, front, dasher, now, POST_AGE_MS);
    }

    /** As {@link #arm(Request, Front, ComponentName, long)}, for a post up to {@code maxPostAgeMs} old. */
    void arm(Request request, Front front, ComponentName dasher, long now, long maxPostAgeMs) {
        this.maxPostAge = maxPostAgeMs;
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
        this.factsAt = NEVER;
        this.drawingSince = NEVER;
        this.unreadOffer = false;
        this.ownTapAt = NEVER;
        this.presentationTried = false;
        this.ownTapRequested = false;
        this.recognisedSeen = false;
        this.openingTouched = false;
        this.touchedWhileArming = false;
        this.watchUpAt = NEVER;
        this.suspendedAt = NEVER;
        this.resumedAt = NEVER;
        this.lockDelayed = false;
        this.returnWaiting = false;
        this.noOfferLogged = false;
        this.factsLogged = false;
        this.phase = Phase.ARMING;
    }

    /**
     * A touch while arming: the quiet starts again {@link #GESTURE_MS} after it landed (its finger may be down until
     * then, which the watch never hears).
     */
    void touchedWhileArming(long at) {
        if (phase != Phase.ARMING || at == NEVER) return;
        if (at > armedAt) touchedWhileArming = true;
        quietSince = Math.max(quietSince, at + GESTURE_MS);
    }

    /** The keyboard is listed now: the quiet starts again from now. */
    void keyboardWhileArming(long now) {
        if (phase != Phase.ARMING) return;
        touchedWhileArming = true;
        quietSince = Math.max(quietSince, now);
    }

    /** Whether a touch came during this quiet wait (else a wait that ran out was for want of the touch watch). */
    boolean touchedWhileArming() {
        return touchedWhileArming;
    }

    /**
     * The touch watch was seen up, having come up at {@code elapsed}: the quiet is counted from then, never from
     * before the watch could see a touch.
     *
     * @return the first time, how long after arming it came up; -1 after that
     */
    long watchUp(long elapsed) {
        if (phase != Phase.ARMING) return -1;
        quietSince = Math.max(quietSince, elapsed);
        if (watchUpAt != NEVER) return -1;
        watchUpAt = Math.max(armedAt, elapsed);
        return watchUpAt - armedAt;
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

    /** The oldest its post may be when Dasher is opened for it. */
    long maxPostAge() {
        return maxPostAge;
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
        failedStreak.clear();
        return true;
    }

    /**
     * The user touched the screen while Dasher opened, before its window first appeared (the touch was meant for the
     * app they were in): the peek goes on, counting what the user does from {@code actions}, and only the automatic
     * decline may still go back.
     */
    void openingTouched(long actions) {
        openingTouched = true;
        this.actions = actions;
    }

    boolean openingTouched() {
        return openingTouched;
    }

    /** What the user does is counted again from {@code actions} (an unlock resumed the peek). */
    void rebase(long actions) {
        this.actions = actions;
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
     * offers, idle, the dash over, a delivery) or not. Only consecutive explained reads count as an empty-screen
     * interval: a loading or unknown screen restarts that interval without extending the whole peek's deadline.
     */
    void screen(boolean recognised, long now) {
        lastRecognised = recognised;
        if (!recognised) recognisedAt = NEVER;
        else if (recognisedAt == NEVER && phase == Phase.UP) recognisedAt = now;
        if (recognised && phase == Phase.UP) recognisedSeen = true;
    }

    /** Whether a recognised empty screen was read during this peek. */
    boolean recognisedSeen() {
        return recognisedSeen;
    }

    boolean lastRecognised() {
        return lastRecognised;
    }

    long recognisedAt() {
        return recognisedAt;
    }

    /** A full uninterrupted interval of recognised screens with no offer, checked again after a fresh read. */
    boolean noOfferWaited(long now) {
        return phase == Phase.UP && !offerSign && lastRecognised && recognisedAt != NEVER
                && now - recognisedAt >= NO_OFFER_MS;
    }

    /**
     * A read showed any of an offer's figures (pay, a bound on it, miles, minutes, stops, items): the offer is drawn.
     * Its controls or its headline alone are not figures ({@link #drawing}).
     *
     * @return true the first time this peek
     */
    boolean facts(long now) {
        if (factsAt != NEVER || (phase != Phase.UP && phase != Phase.DECLINING && phase != Phase.CONFIRMED)) {
            return false;
        }
        factsAt = now;
        return true;
    }

    boolean factsRead() {
        return factsAt != NEVER;
    }

    long factsAt() {
        return factsAt;
    }

    /** Once per peek: whether the "[peek] no offer" line was already written (it is from now on). */
    boolean noOfferLogged() {
        boolean was = noOfferLogged;
        noOfferLogged = true;
        return was;
    }

    /**
     * A read showed signs of the offer being drawn (a control or its label, its headline) without any of its figures:
     * Dasher's own notification tap waits {@link #DRAWING_MS} after the first such sign, in case they follow.
     */
    void drawing(long now) {
        if (phase == Phase.UP && drawingSince == NEVER) drawingSince = now;
    }

    /** A read showed a screen too big to read, or Dasher's question: perhaps the whole offer ({@link #unreadOffer}). */
    void unreadOffer() {
        if (phase == Phase.UP || phase == Phase.DECLINING || phase == Phase.CONFIRMED) unreadOffer = true;
    }

    boolean offerUnread() {
        return unreadOffer;
    }

    /**
     * Whether Dasher's own notification tap is due: Dasher up {@link #PRESENT_MS} with none of the offer's figures
     * read (an empty or unrecognised screen, or only its controls or headline, settled {@link #DRAWING_MS}), never a
     * screen too big to read or Dasher's question, and the tap not tried yet this peek.
     */
    boolean presentationDue(long now) {
        long due = presentationDueAt();
        return due != NEVER && now >= due;
    }

    /** When Dasher's own notification tap is due, or {@link #NEVER} when it is not to be tried. */
    long presentationDueAt() {
        if (phase != Phase.UP || factsAt != NEVER || unreadOffer || presentationTried || upAt == NEVER) return NEVER;
        long due = upAt + PRESENT_MS;
        return drawingSince == NEVER ? due : Math.max(due, drawingSince + DRAWING_MS);
    }

    /** Dasher's own notification tap was sent: never again this peek. */
    void ownTapSent(long now) {
        presentationTried = true;
        ownTapRequested = true;
        ownTapAt = now;
    }

    /**
     * Dasher's own notification tap was not sent, and is not tried again this peek.
     *
     * @param waitForOffer the offer's notification is still posted but cannot be tapped: the wait for the offer to
     *     show runs all the same
     */
    void ownTapSkipped(long now, boolean waitForOffer) {
        presentationTried = true;
        if (waitForOffer) ownTapAt = now;
    }

    boolean ownTapRequested() {
        return ownTapRequested;
    }

    long ownTapAt() {
        return ownTapAt;
    }

    /**
     * Dasher never drew the offer: {@link #OWN_TAP_WAIT_MS} after its own notification tap, none of its figures (its
     * controls or headline alone are not the offer shown), and never a screen too big to read or Dasher's question.
     */
    boolean unshown(long now) {
        long due = unshownAt();
        return due != NEVER && now >= due;
    }

    /** When the offer counts as never shown, or {@link #NEVER} while that does not apply. */
    long unshownAt() {
        if (phase != Phase.UP || ownTapAt == NEVER || factsAt != NEVER || unreadOffer) return NEVER;
        return ownTapAt + OWN_TAP_WAIT_MS;
    }

    /** The offer's notification is gone: it is no longer waited for as never shown (a withdrawn offer is not). */
    void offerGone() {
        ownTapAt = NEVER;
    }

    /** Whether the whole opened peek's time ({@link #MAX_MS} from its first open, locked time aside) is over. */
    boolean pastDeadline(long now) {
        return openedAt != NEVER && now - openedAt >= MAX_MS;
    }

    // ---- The lock ----

    /** The screen turned off or the phone locked: nothing of this peek runs until an unlock resumes it. */
    void suspend(long now) {
        if (active() && phase != Phase.ARMING && suspendedAt == NEVER) suspendedAt = now;
    }

    boolean suspended() {
        return suspendedAt != NEVER;
    }

    long suspendedAt() {
        return suspendedAt;
    }

    /**
     * An unlock in time resumes it: every time it keeps moves on by the time it was suspended, so the locked time
     * counts toward none of its waits or its deadline. An empty screen read before the lock proves nothing after it:
     * the empty-screen interval starts again from the first fresh read (nothing was read meanwhile).
     */
    void resume(long now) {
        if (suspendedAt == NEVER) return;
        long shift = Math.max(0, now - suspendedAt);
        openedAt = shifted(openedAt, shift);
        upAt = shifted(upAt, shift);
        followingSince = shifted(followingSince, shift);
        factsAt = shifted(factsAt, shift);
        drawingSince = shifted(drawingSince, shift);
        ownTapAt = shifted(ownTapAt, shift);
        recognisedAt = NEVER;
        lastRecognised = false;
        suspendedAt = NEVER;
        resumedAt = now;
        returnWaiting = false;
    }

    private static long shifted(long at, long shift) {
        return at == NEVER ? NEVER : at + shift;
    }

    /** When an unlock resumed this peek, {@link #NEVER} for one never suspended. */
    long resumedAt() {
        return resumedAt;
    }

    /** This peek opens Dasher for a post the lock kept back, looked at after the unlock. */
    void lockDelayed() {
        lockDelayed = true;
    }

    /**
     * Whether the lock came between the offer and this peek's look at it: the peek was held for the unlock, or it is for
     * a post the lock kept back. Its offer may have gone while the phone was locked, so it is never an empty peek.
     */
    boolean lockCameBetween() {
        return resumedAt != NEVER || lockDelayed;
    }

    /** A return held until the touch watch has seen the phone quiet after the unlock. */
    void returnWaiting(boolean waiting) {
        returnWaiting = waiting;
    }

    boolean returnWaiting() {
        return returnWaiting;
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
     * {@link #LEFT_WITH_YOU_MS}. Only two outcomes count toward pausing Peek, each in a row within {@link #STREAK_MS}
     * and within one dash: {@link Outcome#WITHDRAWN} and {@link Outcome#OPEN_FAILED}. A peek that read any sign of an
     * offer begins the empty count afresh; the other outcomes neither count nor reset it.
     *
     * @param wentBack whether it took the user back
     * @param dashStart the dash under way's start ({@link Dashing#currentStart}), 0 for none: another dash begins the
     *     counts afresh
     * @return why Peek should pause now, or null
     */
    String end(Outcome outcome, boolean wentBack, long now, long dashStart) {
        if (!active()) return null;
        boolean opened = openedAt != NEVER;
        boolean openFailed = outcome == Outcome.OPEN_FAILED;
        if (opened && !openFailed && upAt != NEVER && offerSign && (!wentBack || leftWithUser)) {
            heldKey = request.key;
            // Until the offer's own countdown runs out, when one was read.
            heldUntil = offerEndsAt != NEVER ? Math.min(offerEndsAt, now + LEFT_WITH_YOU_MS) : now + LEFT_WITH_YOU_MS;
        }
        if (streakDash != dashStart) {
            emptyStreak.clear();
            failedStreak.clear();
            streakDash = dashStart;
        }
        String pause = null;
        if (opened) {
            if (offerSign || factsAt != NEVER) emptyStreak.clear();
            if (outcome == Outcome.OPEN_FAILED) {
                if (counted(failedStreak, now) >= FAILED_TO_PAUSE) {
                    pause = "Dasher did not come up for " + failedStreak.size() + " peeks in a row (the phone may "
                            + "block apps opening from the background)";
                    failedStreak.clear();
                }
            } else if (outcome == Outcome.WITHDRAWN && !lockCameBetween()) {
                // A peek the lock held, or one for a post the lock kept back, never counts (nor resets the run): the
                // offer may have gone while the phone was locked.
                if (counted(emptyStreak, now) >= EMPTY_TO_PAUSE) {
                    pause = emptyStreak.size() + " offers in a row were gone by the time Dasher showed";
                    emptyStreak.clear();
                }
            } else if (outcome == Outcome.DECLINED_BACK || outcome == Outcome.LEFT_WITH_USER) {
                emptyStreak.clear();
            }
            lastEndedAt = now;
        }
        phase = Phase.NONE;
        request = null;
        dasher = null;
        suspendedAt = NEVER;
        returnWaiting = false;
        return pause;
    }

    /** One more in a row, those older than {@link #STREAK_MS} forgotten: how many there are now. */
    private static int counted(ArrayDeque<Long> streak, long now) {
        while (!streak.isEmpty() && now - streak.peekFirst() >= STREAK_MS) streak.pollFirst();
        streak.addLast(now);
        return streak.size();
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

    /**
     * What a card carries after a peek went back with a passing or unclear add-on: the add-on's own figures, as Dasher
     * shows them, "Add-on passes: +$3.50 · +2.1 mi · +8 min", "Add-on unclear: added pay not read · +2 mi (add-on
     * details unclear)". Nothing is inferred: what the add-on does not say is not on its card.
     */
    static String addOnCardText(OfferRule.Result result, OfferSnapshot added, String reason) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        parts.add(added.payCents != null ? "+" + DecisionLog.money(added.payCents) : "added pay not read");
        if (added.miles != null) {
            double miles = added.miles;
            parts.add("+" + (miles == Math.rint(miles) ? String.valueOf((long) miles) : String.valueOf(miles)) + " mi");
        }
        if (added.minutes != null) parts.add("+" + added.minutes + " min");
        if (added.stops != null) parts.add("+" + added.stops + (added.stops == 1 ? " stop" : " stops"));
        String line = String.join(" · ", parts);
        if (result == OfferRule.Result.KEEP) return "Add-on passes: " + line;
        String why = reason == null || reason.isEmpty() ? "" : MainActivity.plainReason(reason);
        return "Add-on unclear: " + line + (why.isEmpty() ? "" : " (" + why.substring(0, 1).toLowerCase(Locale.US)
                + why.substring(1) + ")");
    }

    // ---- Pausing itself (any thread) ----

    /** A pause Peek put on itself: why, until when (Peek's clock), and the dash it began in; for one app only. */
    private static final class Pause {
        final Object app;
        final String why;
        final long until;
        final long dashStart;

        Pause(Object app, String why, long until, long dashStart) {
            this.app = app;
            this.why = why;
            this.until = until;
            this.dashStart = dashStart;
        }
    }

    private static final AtomicReference<Pause> PAUSE = new AtomicReference<>();

    /**
     * Peek pauses itself (memory only) for {@link #PAUSE_MS}, or until the next dash starts, whichever is first. The
     * Settings switch is never touched: it stays the user's own choice.
     */
    static void pause(Context context, String why) {
        PAUSE.set(new Pause(context.getApplicationContext(), why, now() + PAUSE_MS, Dashing.currentStart(context)));
    }

    /**
     * After a runtime failure, which would only come again: Peek pauses itself (memory only) until the next dash
     * starts or the user taps Resume, with no time limit. The Settings switch is never touched.
     */
    static void pauseUntilNextDash(Context context, String why) {
        PAUSE.set(new Pause(context.getApplicationContext(), why, Long.MAX_VALUE, Dashing.currentStart(context)));
    }

    /**
     * Why Peek has paused itself, or null when it has not: the pause is over {@link #PAUSE_MS} after it began (never by
     * time after a runtime failure, {@link #pauseUntilNextDash}), or once another dash started (said once in the log).
     */
    static String pausedWhy(Context context) {
        Pause current = PAUSE.get();
        if (current == null) return null;
        if (current.app != context.getApplicationContext()) {
            // Another app (a new process in tests): its pause is not this one's.
            PAUSE.compareAndSet(current, null);
            return null;
        }
        String over = null;
        if (now() >= current.until) {
            over = PAUSE_MS / 60_000 + " minutes passed";
        } else {
            long dash = Dashing.currentStart(context);
            if (dash != 0 && dash != current.dashStart) over = "a new dash started";
        }
        if (over == null) return current.why;
        if (PAUSE.compareAndSet(current, null)) log(context, "pause over: " + over + "; Peek works again");
        return null;
    }

    /** The user tapped Resume on the homepage: the pause Peek put on itself ends now. */
    static void resumeNow(Context context) {
        Pause current = PAUSE.get();
        if (current == null || !PAUSE.compareAndSet(current, null)) return;
        log(context, "pause over: you tapped Resume");
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
            DashSummary.peek(context, "skipped: " + why);
        }
    }

    /** A "[peek]" line that is not a skip: a peek opening Dasher, how one ended, what came after. */
    static void log(Context context, String line) {
        synchronized (Peek.class) {
            forgetIfNewApp(context);
            flushSkips(context);
            lastSkip = "";
            DiagnosticLog.log(context, "peek", line + state(context));
            DashSummary.peek(context, line);
        }
    }

    /**
     * A "[peek]" line that leaves the count of a repeated skip alone: it may come between the repeats of one skip (an
     * offer that came while the phone was locked, each one after the skip that says so).
     */
    static void note(Context context, String line) {
        synchronized (Peek.class) {
            forgetIfNewApp(context);
            DiagnosticLog.log(context, "peek", line + state(context));
            DashSummary.peek(context, line);
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
