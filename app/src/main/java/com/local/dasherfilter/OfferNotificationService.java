package com.local.dasherfilter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Classifies DoorDash offer notifications in the background. Never launches an activity itself: notification evidence
 * and screen evidence are separate authorities. An offer that cannot be judged is left to the user; with Peek on
 * ({@link Peek}), the screen reader is asked to bring Dasher up for a moment to read it, never for DoorDash's re-post of
 * an offer the user had in Dasher ({@link TrackedOffer#heldInDasherUntil}). It gets a card of its own, which
 * rings once while Dasher is in the background (silently when Android shows Dasher's own alert for it sounded), unless
 * Dasher's own notification of it really alerts the user: listed, tappable, and ranked by Android to pop up or sound by
 * itself ({@link #alertsByItself}). Never a payless card beside Dasher's own that alerts (the owner's request), and
 * never an offer left with no alert at all when Dasher's own is silenced. One offer is one history line: a
 * notification of an offer the screen reads is folded into the screen's line, and its card is cleared then. Native
 * passing/unknown alerts are never dismissed by this presentation decision.
 */
public final class OfferNotificationService extends NotificationListenerService {
    /** Current eligible notification incarnations, published to the scanner without sharing the mutable tracker. */
    private final java.util.Set<String> livePeekCards = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * The incarnations a post kept for the unlock may still open Dasher for: as {@link #livePeekCards}, except that
     * Dasher's update of the same offer (refused a peek of its own only for not being a fresh post) leaves it here. The
     * main thread takes an incarnation out of this one first, then out of {@link #livePeekCards}.
     */
    private final java.util.Set<String> unlockCards = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicLong CARD_SERIAL = new java.util.concurrent.atomic.AtomicLong();

    static boolean peekCurrent(Peek.Request request) {
        OfferNotificationService service = active;
        return service != null && request != null && request.alertTag != null
                && service.livePeekCards.contains(request.alertTag);
    }

    /**
     * A post kept for the unlock, looked at again after it (scanner thread): whether its incarnation may still be peeked
     * at. Dasher's update of the same offer while the phone was locked does not revoke it (the owner: "if the user has
     * locked their phone and then it dings and they unlock it it should automatically pull it up if unlocked in time");
     * anything else that revokes a peek does. When it may, the incarnation is current for the peek again.
     */
    static boolean renewForUnlock(Peek.Request request) {
        OfferNotificationService service = active;
        if (service == null || request == null || request.alertTag == null) return false;
        if (!service.unlockCards.contains(request.alertTag)) return false;
        service.livePeekCards.add(request.alertTag);
        // The main thread may have revoked it meanwhile (it takes it out of the unlock's set first).
        if (service.unlockCards.contains(request.alertTag)) return true;
        service.livePeekCards.remove(request.alertTag);
        return false;
    }

    /** Main thread: an incarnation may no longer be peeked at, after the unlock either. */
    private void revokePeek(String alertTag) {
        unlockCards.remove(alertTag);
        livePeekCards.remove(alertTag);
    }

    /**
     * Dasher's latest post of each live offer incarnation, by its card's tag, for the screen reader (any thread): the
     * key, the post's time and Dasher's own tap intent, never any of its words. Replaced in place as each post of the
     * offer is handled (whether or not it may be peeked at: this is no peek token), and gone only with the
     * incarnation ({@link #remove}, {@link #clearTracked}). A missing record never says the offer's notification is
     * gone: only Android's own listing says that.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, DasherPost> dasherPosts =
            new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * The incarnations (by their card's tag) whose Dasher notification tap the app has sent once already, whether a
     * peek's, a card watch's or a card's own first: the screen reader never sends one a second time for that offer.
     */
    private final java.util.Set<String> ownTapsSent = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** One incarnation's latest post, immutable. */
    private static final class DasherPost {
        final String key;
        final long postTime;
        final PendingIntent contentIntent;
        /** Whether its tap may be sent to present it: anything but a known failure the notification path declines. */
        final boolean presentable;

        DasherPost(String key, long postTime, PendingIntent contentIntent, boolean presentable) {
            this.key = key;
            this.postTime = postTime;
            this.contentIntent = contentIntent;
            this.presentable = presentable;
        }
    }

    /**
     * Whether Dasher's notification of an offer is still posted, as Android lists it now: posted, gone (Android lists
     * nothing on its key, or an older post), replaced by a later post on its key (only {@link #postStillUp} says so;
     * for {@link #offerPosted} a later post is the offer's notification still up), or not known.
     */
    enum Posted { POSTED, GONE, REPLACED, UNKNOWN }

    /** Dasher's own tap on an offer notification still posted, or why there is none to send. */
    static final class DasherTap {
        /** Dasher's own intent, to send; null when there is none. */
        final PendingIntent intent;
        /** Why none ("notification gone", "replaced", "no activity intent", "notification unknown"); null with one. */
        final String skip;
        /** The notification is still posted, but its tap cannot be sent. */
        final boolean postedWithoutTap;

        private DasherTap(PendingIntent intent, String skip, boolean postedWithoutTap) {
            this.intent = intent;
            this.skip = skip;
            this.postedWithoutTap = postedWithoutTap;
        }

        static DasherTap of(PendingIntent intent) {
            return new DasherTap(intent, null, false);
        }

        static DasherTap none(String why, boolean posted) {
            return new DasherTap(null, why, posted);
        }
    }

    /**
     * Whether Dasher's notification of this peeked offer's incarnation is still posted: Android lists its key with
     * this incarnation's latest post (or a later one not handled yet). Without a record of the incarnation (it ended,
     * or the listener lost it in a reconnect), Android's listing of the peeked post itself decides. Only Android's own
     * listing says it is gone. Asks Android only, never Dasher. Any thread.
     */
    static Posted offerPosted(Peek.Request request) {
        OfferNotificationService service = active;
        if (service == null || request == null || request.alertTag == null) return Posted.UNKNOWN;
        DasherPost post = service.dasherPosts.get(request.alertTag);
        boolean recorded = post != null && post.key.equals(request.key);
        long since = recorded ? post.postTime : request.postTime;
        StatusBarNotification listed;
        try {
            listed = service.listed(request.key);
        } catch (RuntimeException unknown) {
            return Posted.UNKNOWN;
        }
        return listed != null && listed.getPostTime() >= since ? Posted.POSTED : Posted.GONE;
    }

    /**
     * Whether Dasher's notification of this post's offer is still posted, unreplaced: a post refused for the lock is
     * looked at again after the unlock only then. Android's listing decides: gone when it lists nothing on the key (or
     * an older post); still posted when it lists this very post (its key and post time), or Dasher's later post of the
     * same offer, handled as an update of its incarnation (its latest post on record, as Android lists it); replaced
     * when any other later post took the key (a new offer on it, or a post not handled yet). Any thread.
     */
    static Posted postStillUp(Peek.Request request) {
        OfferNotificationService service = active;
        if (service == null || request == null || request.alertTag == null) return Posted.UNKNOWN;
        StatusBarNotification listed;
        try {
            listed = service.listed(request.key);
        } catch (RuntimeException unknown) {
            return Posted.UNKNOWN;
        }
        if (listed == null || listed.getPostTime() < request.postTime) return Posted.GONE;
        if (listed.getPostTime() == request.postTime) return Posted.POSTED;
        DasherPost post = service.dasherPosts.get(request.alertTag);
        return post != null && post.key.equals(request.key) && post.postTime == listed.getPostTime()
                ? Posted.POSTED : Posted.REPLACED;
    }

    /** Dasher's own tap on the peeked offer's notification, when that very post is still up. Any thread. */
    static DasherTap dasherTap(Peek.Request request) {
        if (request == null) return DasherTap.none("notification unknown", false);
        return dasherTap(request.alertTag, request.key, null);
    }

    /**
     * Dasher's own tap for the offer of the card with {@code alertTag}, when its notification's latest post is still
     * up; the card's copy of Dasher's intent ({@code cardsOwn}) only when the post holds none. Any thread.
     */
    static DasherTap cardTap(String alertTag, PendingIntent cardsOwn) {
        return dasherTap(alertTag, null, cardsOwn);
    }

    private static DasherTap dasherTap(String alertTag, String key, PendingIntent fallback) {
        OfferNotificationService service = active;
        if (service == null || alertTag == null) return DasherTap.none("notification unknown", false);
        DasherPost post = service.dasherPosts.get(alertTag);
        if (post == null || (key != null && !post.key.equals(key))) {
            // No record of the incarnation's latest post: never a tap without one. Gone only when Android lists none.
            String listedKey = key != null ? key : keyOf(alertTag);
            boolean gone = false;
            try {
                gone = listedKey != null && service.listed(listedKey) == null;
            } catch (RuntimeException unknown) {
                // Not known: said as unknown.
            }
            return DasherTap.none(gone ? "notification gone" : "notification unknown", false);
        }
        StatusBarNotification listed;
        try {
            listed = service.listed(post.key);
        } catch (RuntimeException unknown) {
            return DasherTap.none("notification unknown", false);
        }
        if (listed == null) return DasherTap.none("notification gone", false);
        if (listed.getPostTime() != post.postTime || !post.presentable) return DasherTap.none("replaced", false);
        PendingIntent own = post.contentIntent != null ? post.contentIntent : fallback;
        if (!DasherOwnIntent.fromDasher(own)) return DasherTap.none("no activity intent", true);
        return DasherTap.of(own);
    }

    /**
     * The one send of Dasher's own notification tap for the offer of the card with {@code alertTag}: true the first
     * time (the caller sends it now), false once it was sent for that offer by any path. Any thread.
     */
    static boolean claimOwnTap(String alertTag) {
        OfferNotificationService service = active;
        return service != null && alertTag != null && service.ownTapsSent.add(alertTag);
    }

    /** The notification key inside an incarnation's card tag ("offer-<time>-<serial>-<key>"), or null for another tag. */
    private static String keyOf(String alertTag) {
        if (alertTag == null || !alertTag.startsWith("offer-")) return null;
        String[] parts = alertTag.split("-", 4);
        return parts.length == 4 && !parts[3].isEmpty() ? parts[3] : null;
    }

    /** Android's listing of Dasher's notification with this key now, or null when it lists none; throws when unknown. */
    private StatusBarNotification listed(String key) {
        StatusBarNotification[] current = getActiveNotifications(new String[] {key});
        if (current == null) throw new IllegalStateException("no listing");
        for (StatusBarNotification source : current) {
            if (isFromOwnUsersDasher(source) && key.equals(source.getKey())) return source;
        }
        return null;
    }

    /** This incarnation's post just handled is what the screen reader looks at from now on. */
    private void publishPost(TrackedOffer offer, StatusBarNotification source, boolean presentable) {
        if (source.getPostTime() != offer.state.postedAt) return;
        dasherPosts.put(offer.alertTag, new DasherPost(source.getKey(), source.getPostTime(),
                source.getNotification().contentIntent, presentable));
    }

    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    private static final int MAX_TRACKED_OFFERS = 16;
    private static final int MAX_NOTIFICATION_LABELS = 32;
    private static final int MAX_NOTIFICATION_LABEL_CHARS = 2048;
    private static final int MAX_LOGGED_EXTRA_KEYS = 40;
    /** A store name longer than this is not shown on our card. */
    private static final int MAX_STORE_CHARS = 40;
    private static final String[] TEXT_EXTRAS = {
            Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT};

    private static volatile OfferNotificationService active;
    private static volatile boolean offerOutstanding;
    private static volatile long generation;
    /** When Dasher's Decline action on a notification was last sent (uptime), 0 for never. */
    private static volatile long declineActionAt;

    private static final int MAX_POSTED_KEYS = 32;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Keyed by source notification key; insertion order makes the first entry the oldest. */
    private final LinkedHashMap<String, TrackedOffer> tracked = new LinkedHashMap<>();
    /**
     * Keys of Dasher's notifications Android shows now, as far as this listener has seen: a post on one of them is
     * an update to Android, which an only-alert-once notification makes silently.
     */
    private final LinkedHashSet<String> postedKeys = new LinkedHashSet<>();
    /**
     * What the screen had read, by notification key, when the listener last lost its incarnations (a reconnect):
     * a replay of that same post (same key and post time) is then known read again. Main thread only; process-wide,
     * since Android may rebind a new instance of this service.
     */
    private static final Map<String, ReadMemory> readBeforeReconnect = new LinkedHashMap<>();

    /** The screen's reading of one incarnation, kept across a listener reconnect. */
    private static final class ReadMemory {
        final long readThrough;
        final OfferSnapshot facts;
        final boolean ended;
        /** {@code SystemClock.elapsedRealtime()} when it was kept. */
        final long keptAt;

        ReadMemory(OfferAlertState state, long keptAt) {
            this.readThrough = state.readThrough;
            this.facts = state.readOnScreen;
            this.ended = state.endedOnScreen;
            this.keptAt = keptAt;
        }
    }

    /** Per-source-notification state plus the tag of the Offer Filter card that mirrors it. */
    private static final class TrackedOffer {
        final OfferAlertState state;
        final String alertTag;
        final String nativeKey;
        final String merchant;
        /** The store as Dasher's notification names it ("Taco Bell"), for our card; empty when it names none. */
        String store = "";
        Runnable expiry;
        /** How long after the previous post on this key the latest one came (wall clock), for the log. */
        long gapMs;
        /** Whether the latest post began this incarnation, and was not a replay: a fresh post, which Peek may read. */
        boolean freshPost;
        /** Dasher's own tap intent on its latest post (our card's fallback), and whether Android showed it sounded. */
        PendingIntent contentIntent;
        boolean dasherSounded;
        /**
         * Android ranks Dasher's latest post of it as alerting the user by itself ({@link #alertsByItself}): it pops up
         * on screen, or sounds, and Do Not Disturb lets it through. Only then does a payless card give way to it; when
         * Dasher's offer alerts are silenced (as Settings' Fix row for DoorDash's offer channel may lead to), held back
         * by Do Not Disturb, or Android does not say, the card is still how the offer is heard.
         */
        boolean dasherAlerts;
        /** Our notification was asked to sound; keep that sole alert unless the native one is known heard. */
        boolean ownAlerted;
        /** A quiet generic card was omitted because the current native alert supplies its tap. */
        boolean nativeCard;
        /** Its card was posted silently while a peek is tried: what the card rings with if the peek does not happen. */
        QuietCard quietCard;
        /** A peek went back with this offer passing or unclear: its card carries what was read; updates change nothing. */
        boolean readCard;
        boolean readCardLogged;
        /**
         * Its card says Dasher didn't show this offer when it opened ({@link #UNSHOWN_TEXT}), or that it couldn't be
         * read ({@link #UNREAD_TEXT}): a later post of the same offer that still cannot be judged changes nothing (the
         * card is never swapped for a payless one).
         */
        boolean unshownCard;
        /**
         * A post of it was handled while Dasher was on screen (never a replay): the user had this offer in Dasher, drawn
         * or still drawing. With the screen's reading of it ({@link OfferAlertState#readOnScreen}), what makes a re-post
         * of it held from Peek ({@link #heldInDasherUntil}).
         */
        boolean inDasher;
        /**
         * Until when ({@code SystemClock.elapsedRealtime()}) this incarnation is never peeked at, 0 for none: it is
         * DoorDash re-posting an offer the user had in Dasher (read on screen, or posted while Dasher was in front)
         * whose end the screen never saw, or one a delivery or pickup screen followed ({@link #routeAfter}), as it does
         * while an offer ages ({@link OfferAlertState#newOfferReason}), up to {@link OfferPairing#OFFER_MS} after that
         * offer's first post. Opening Dasher for it would pull Dasher back over the map the user just opened from it
         * (the owner: Navigate's map does not stay).
         */
        long heldInDasherUntil;
        /**
         * A delivery, pickup or route screen of Dasher's, with no offer on it, was read within
         * {@link OfferPairing#OFFER_MS} of this incarnation's first post, and neither the wait for offers nor the dash's
         * end since ({@link #screenShowedRoute}): the user may have accepted this offer (from Dasher's notification, or
         * by a tap Dasher never reported), so the screen showing it gone is no proof it was declined.
         */
        boolean routeAfter;
        /**
         * Until when ({@code SystemClock.elapsedRealtime()}) a post on this key may be the notification of an offer the
         * user accepted, 0 for none: an earlier incarnation the user had in Dasher (read on screen, or posted while
         * Dasher was in front) that a delivery or pickup screen followed ({@link #routeAfter}), up to
         * {@link OfferPairing#OFFER_MS} after its first post. Such a post is never declined through Dasher's
         * notification, nor hidden ({@link #mayBeAccepted}).
         */
        long mayBeAcceptedUntil;

        TrackedOffer(long now, StatusBarNotification source, String merchant) {
            this.state = new OfferAlertState(now, source.getPostTime());
            this.nativeKey = source.getKey();
            this.alertTag = "offer-" + now + "-" + CARD_SERIAL.incrementAndGet() + "-" + source.getKey();
            this.merchant = merchant;
        }
    }

    /** What a card posted quietly for a peek needs to be announced as usual, ringing once, if the peek does not happen. */
    private static final class QuietCard {
        final OfferSnapshot facts;
        final OfferRule.Decision decision;
        final List<String> labels;
        final boolean addOn;
        final boolean enabled;
        final String signature;

        QuietCard(OfferSnapshot facts, OfferRule.Decision decision, List<String> labels, boolean addOn, boolean enabled,
                  String signature) {
            this.facts = facts;
            this.decision = decision;
            this.labels = labels;
            this.addOn = addOn;
            this.enabled = enabled;
            this.signature = signature;
        }
    }

    static boolean isConnected() {
        return active != null;
    }

    /** True while any DoorDash offer notification is being tracked; used to defer automatic updates. */
    static boolean hasActiveOffer() {
        return offerOutstanding;
    }

    /**
     * Incremented for each newly tracked offer, except the same store's re-post while Dasher is on screen (the offer
     * on screen); revokes older screen decline-confirmation authority.
     */
    static long generation() {
        return generation;
    }

    private long staleNotices;
    private long staleLoggedAt;
    private static volatile long backgroundGeneration;
    private static volatile Notice lastNotice;
    private static final class Notice {
        final List<String> labels;
        final long at;
        final long generation;
        final boolean foreground;
        Notice(List<String> labels, long generation, boolean foreground) {
            this.labels = new ArrayList<>(labels);
            this.at = SystemClock.uptimeMillis();
            this.generation = generation;
            this.foreground = foreground;
        }
    }
    static long freshBackgroundGeneration() { return backgroundGeneration; }
    static long generationForRead(long startedGeneration, OfferSnapshot facts, List<String> labels, long began) {
        Notice notice = lastNotice;
        long current = generation();
        return notice != null && notice.foreground && notice.generation == current && current == startedGeneration + 1
                && notice.at >= began && OfferFilterService.sameForegroundNotice(notice.labels, facts, labels, 0)
                ? current : startedGeneration;
    }

    private static void nextGeneration() {
        generation++;
    }

    /**
     * Whether a Decline from a notification was requested within {@code ms}: Dasher's decline question then is not
     * the user's own.
     */
    static boolean declineActionWithin(long ms) {
        long at = declineActionAt;
        long now = SystemClock.uptimeMillis();
        return at > 0 && now >= at && now - at <= ms;
    }

    /** For tests: no Decline action was sent (the uptime clock starts again with each test). */
    static void forgetDeclineAction() {
        declineActionAt = 0;
    }

    /**
     * Sends Dasher's own Decline action, marked as requested first: the screen reader (on its own thread) may read
     * Dasher reacting to it before {@code send()} even returns, and must never take that for the user's own decline,
     * or the offer for one the user accepted. A send that fails stays marked, which only means nothing is learned for
     * a minute.
     */
    static void sendDecline(PendingIntent decline) throws PendingIntent.CanceledException {
        declineActionAt = SystemClock.uptimeMillis();
        decline.send();
    }

    static boolean hasAccess(Context context) {
        ComponentName component = new ComponentName(context, OfferNotificationService.class);
        if (Build.VERSION.SDK_INT >= 27) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            return manager != null && manager.isNotificationListenerAccessGranted(component);
        }
        String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
        for (String value : enabled.split(":")) {
            if (component.equals(ComponentName.unflattenFromString(value))) return true;
        }
        return false;
    }

    /** Re-evaluates current offer notifications against newly saved rules, without ringing or acting. */
    static void rulesChanged() {
        OfferNotificationService service = active;
        if (service != null) service.handler.post(service::reconcile);
    }

    /**
     * The screen read the offer this notification incarnation announced: its card is cleared (never Dasher's own
     * notification), and later updates of it are judged knowing the screen has it.
     */
    static void readOnScreen(String alertTag, OfferSnapshot facts) {
        OfferNotificationService service = active;
        if (service == null || alertTag == null) return;
        onMain(service, () -> {
            for (TrackedOffer offer : service.tracked.values()) {
                if (!alertTag.equals(offer.alertTag)) continue;
                offer.state.markRead(facts);
                service.revokePeek(alertTag);
                OfferAlerts.clear(service, alertTag);
                DiagnosticLog.log(service, "alert", "card cleared: the screen read this offer");
            }
        });
    }

    /**
     * The screen showed Dasher idle, a delivery or the dash over: every offer it had read is gone, so a re-post on
     * one of their keys is a new offer.
     */
    static void screenOfferEnded() {
        OfferNotificationService service = active;
        if (service == null) return;
        onMain(service, () -> {
            for (TrackedOffer offer : service.tracked.values()) {
                if (offer.state.readOnScreen != null) offer.state.endedOnScreen = true;
            }
        });
    }

    /**
     * The screen showed a delivery, pickup or route screen of Dasher's with no offer on it (any thread): an offer whose
     * notification came within {@link OfferPairing#OFFER_MS} before may be one the user accepted, from Dasher's
     * notification or by a tap Dasher never reported, read on screen or never ({@link TrackedOffer#routeAfter}). A
     * re-post of it is never peeked at, and, when the user had it in Dasher, never declined through Dasher's
     * notification. Only the wait for offers or the dash's end ({@link #screenShowedNoRoute}) says otherwise.
     */
    static void screenShowedRoute() {
        OfferNotificationService service = active;
        if (service == null) return;
        long at = SystemClock.elapsedRealtime();
        onMain(service, () -> {
            for (TrackedOffer offer : service.tracked.values()) {
                long since = at - offer.state.createdAt;
                if (since >= 0 && since < OfferPairing.OFFER_MS) offer.routeAfter = true;
            }
        });
    }

    /**
     * The screen showed the wait for offers, or the dash's end or Dasher's home, with no delivery on it (any thread):
     * no offer of the minute before is one the user accepted.
     */
    static void screenShowedNoRoute() {
        OfferNotificationService service = active;
        if (service == null) return;
        onMain(service, () -> {
            for (TrackedOffer offer : service.tracked.values()) {
                offer.routeAfter = false;
                offer.mayBeAcceptedUntil = 0;
            }
        });
    }

    private static void onMain(OfferNotificationService service, Runnable work) {
        if (Looper.myLooper() == Looper.getMainLooper()) work.run();
        else service.handler.post(work);
    }

    @Override public void onListenerConnected() {
        active = this;
        OfferAlerts.ensureChannel(this);
        OfferAlerts.clearAll(this);
        Updater.schedule(this);
        // What the retired GitHub connection and its report queues left goes once, off this thread.
        LegacyReportingCleanup.cleanUpSoon(this);
        StopReports.install(this);
        StopReports.checkSoon(this);
        DashSummary.checkSoon(this);
        // Feedback still waiting (its job lost to a crash, a reboot or a stop) goes now, off this thread.
        FeedbackOutbox.resume(this);
        DiagnosticLog.log(this, "notification", "listener connected; replay is noninterrupting");
        reconcile();
        Updater.check(this, UpdateCadence.Trigger.CONNECTED, null);
    }

    @Override public void onListenerDisconnected() {
        clearTracked();
        if (active == this) active = null;
        DiagnosticLog.log(this, "notification", "listener disconnected; delivery monitoring unavailable");
        try {
            requestRebind(new ComponentName(this, OfferNotificationService.class));
        } catch (RuntimeException error) {
            DiagnosticLog.log(this, "notification", "rebind request failed");
        }
    }

    @Override public void onDestroy() {
        clearTracked();
        handler.removeCallbacksAndMessages(null);
        if (active == this) active = null;
        super.onDestroy();
    }

    @Override public void onNotificationPosted(StatusBarNotification source, RankingMap ranking) {
        handle(source, ranking, false);
    }

    @Override public void onNotificationPosted(StatusBarNotification source) {
        handle(source, getCurrentRanking(), false);
    }

    @Override public void onNotificationRemoved(StatusBarNotification source) {
        if (!isFromOwnUsersDasher(source)) return;
        postedKeys.remove(source.getKey());
        TrackedOffer offer = tracked.get(source.getKey());
        if (offer != null && offer.state.removalMatches(source.getPostTime())) remove(source.getKey(), offer);
    }

    /** Replays every active notification; replay never rings, declines, or hides anything. */
    private void reconcile() {
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all == null) return;
            for (StatusBarNotification source : all) handle(source, getCurrentRanking(), true);
        } catch (RuntimeException error) {
            DiagnosticLog.log(this, "notification", "reconcile failed: " + error.getClass().getSimpleName());
        }
    }

    /**
     * @param replay true for reconnect and rule-change re-evaluation: nothing rings, and no decline action is sent
     *               or DoorDash notification hidden on a replay
     */
    private void handle(StatusBarNotification source, RankingMap ranking, boolean replay) {
        if (!isFromOwnUsersDasher(source)) return;
        Notification notification = source.getNotification();
        if (notification == null || (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            invalidatePeekForKey(source.getKey());
            return;
        }
        // Until the first-run notice is accepted, nothing is read, posted, hidden or declined; accepting replays what
        // is still shown. The one exception: a fresh post on Dasher's offer channel (its channel alone, never its
        // words) posts the reminder that the app is paused, once per notice version.
        if (!Consent.accepted(this)) {
            invalidatePeekForKey(source.getKey());
            if (!replay && ConsentReminder.onOfferChannel(this, notification.getChannelId())) {
                ConsentReminder.postIfPaused(this, "Dasher's notification");
                // Peek waits for the notice too: said from the channel alone, as the reminder is.
                if (FilterStore.peek(this)) Peek.skipped(this, "the notice isn't accepted yet");
            }
            return;
        }
        // To Android, a post on a key it still shows is an update.
        boolean update = !postedKeys.add(source.getKey());
        while (postedKeys.size() > MAX_POSTED_KEYS) postedKeys.remove(postedKeys.iterator().next());
        if (!OfferEvidence.fresh(source.getPostTime(), System.currentTimeMillis(), OfferAlertState.LIFETIME_MS)) {
            invalidatePeekForKey(source.getKey());
            staleNotices++;
            long now = SystemClock.elapsedRealtime();
            if (staleNotices == 1 || now - staleLoggedAt >= 60_000) {
                DiagnosticLog.log(this, "notification", "ignored stale/future notification (" + staleNotices + " since last valid post)");
                staleLoggedAt = now;
            }
            return;
        }
        if (staleNotices > 1) DiagnosticLog.log(this, "notification", "ignored " + staleNotices + " stale/future notifications");
        staleNotices = 0;
        try {
            List<String> labels = labels(notification);
            if (!NotificationOffer.isLikelyOffer(labels)) {
                invalidatePeekForKey(source.getKey());
                return;
            }
            boolean foreground = OfferFilterService.isDasherOnScreenNow();
            TrackedOffer offer = track(source, merchant(labels), labels.toString(), labels, foreground, replay);
            offer.store = store(labels);
            FilterStore.recordDoorDashOfferChannel(this, notification.getChannelId());
            if (offer.readCard && !replay) {
                revokePeek(offer.alertTag);
                // A peek read this offer and went back with it on its card: Dasher's updates of it change nothing.
                if (!offer.readCardLogged) {
                    offer.readCardLogged = true;
                    DiagnosticLog.log(this, "notification", "update of an offer Peek put on its card; no change");
                }
                return;
            }

            // A notification's text may be cut short: its pay is never bounded, so nothing is declined or hidden on a
            // bound.
            OfferSnapshot facts = OfferParser.parse(metricLabels(labels)).withoutPayBound();
            FilterSettings settings = FilterStore.load(this);
            OfferRule.Decision decision = decide(facts, labels, settings);
            String signature = labels + "|" + decision.summary();
            Ranking rank = ranking(ranking, source.getKey());
            boolean dasherSounded = dasherAlertSounded(rank, source, update);
            offer.dasherSounded |= dasherSounded;
            // As Android ranks Dasher's latest post of it; a post it does not rank is not known to alert.
            offer.dasherAlerts = alertsByItself(rank);
            offer.contentIntent = notification.contentIntent;
            // A same-key update with sufficient figures is already judged. It revokes the earlier payless
            // request while that request is still waiting for quiet (a peek already opened goes by the post below).
            if (decision.result != OfferRule.Result.REVIEW || !settings.enabled || foreground || replay) {
                invalidatePeekForKey(source.getKey());
            }
            // This post of the offer, in place of the last, for a peek under way or a card tapped (any update of it,
            // Dasher on screen too, peekable or not).
            publishPost(offer, source, decision.result != OfferRule.Result.DECLINE);
            if (offer.state.duplicate(signature, decision.result)) {
                // Sound evidence may arrive on an otherwise unchanged update while Peek is waiting.
                offer.state.rang |= dasherSounded;
                if (decision.result != OfferRule.Result.DECLINE && !offer.unshownCard) {
                    NativeAlert nativeAlert = nativeAlert(offer);
                    // A payless card never stands beside Dasher's own actionable notification (the owner, #13) while
                    // that notification alerts by itself; a passing one gives way to it only when no ring of ours is
                    // the sole alert.
                    boolean payless = decision.result == OfferRule.Result.REVIEW && offer.dasherAlerts;
                    if (nativeAlert == NativeAlert.ACTIONABLE
                            && (payless || !offer.ownAlerted || offer.dasherSounded)) {
                        OfferAlerts.clear(this, offer.alertTag);
                        offer.nativeCard = true;
                    } else if (offer.nativeCard && (nativeAlert == NativeAlert.UNTAPPABLE
                            || nativeAlert == NativeAlert.UNKNOWN)) {
                        // Text equality does not mean the native tap survived. Restore the ordinary fallback,
                        // preserving a pending quiet Peek and the existing once-per-offer sound budget.
                        DecisionLog.Action fallback = announce(offer.contentIntent, offer, facts, decision,
                                signature, replay, foreground, offer.dasherSounded, offer.quietCard != null);
                        DecisionLog.record(this, DecisionLog.Entry.of(DecisionLog.Source.NOTIFICATION,
                                AddOnOffer.isLikely(labels), decision.basis, decision, fallback,
                                settings.enabled, labels).withAlertTag(offer.alertTag, replay));
                    }
                }
                if (!replay && !offer.state.repostLogged) {
                    offer.state.repostLogged = true;
                    DiagnosticLog.log(this, "notification", "re-posted unchanged " + offer.gapMs / 1000
                            + " s after its last post; the same offer, no new card");
                }
                return;
            }
            DiagnosticLog.log(this, "notification", "parsed " + facts.summary() + "; " + decision.summary());
            logChannel(rank == null ? null : rank.getChannel(), notification, dasherSounded);
            // Configured sound is not evidence that a post alerted. Replays may clear a known Silent channel,
            // but only a fresh post Android indicated sounded can add the Settings Fix.
            FilterStore.recordDoorDashChannel(this, rank == null ? null : rank.getChannel(), !replay && dasherSounded);
            boolean addOn = AddOnOffer.isLikely(labels);

            if (offer.state.coveredByScreen(decision.result, decision.basis, foreground, replay,
                    source.getPostTime())) {
                revokePeek(offer.alertTag);
                offer.state.settle(signature, decision.result, offer.state.readOnScreen);
                OfferAlerts.clear(this, offer.alertTag);
                DiagnosticLog.log(this, "notification", "update of an offer already read on screen; no card");
                Dashing.seen(this);
                if (foreground) OfferFilterService.requestCheckFromNotification();
                return;
            }
            if (!replay && foreground && !offer.state.displayed && decision.result != OfferRule.Result.DECLINE) {
                // Dasher's notification of the offer the screen read moments ago: one offer, so no card or line.
                DecisionLog.Entry screen = DecisionLog.foldIntoScreen(this, DecisionLog.Entry.of(
                        DecisionLog.Source.NOTIFICATION, addOn, decision.basis, decision,
                        DecisionLog.Action.SEEN_ON_SCREEN, settings.enabled, labels));
                if (screen != null) {
                    revokePeek(offer.alertTag);
                    offer.state.settle(signature, decision.result, screen.facts);
                    OfferAlerts.clear(this, offer.alertTag);
                    DiagnosticLog.log(this, "notification", "same offer the screen read "
                            + Math.round((screen.notification.at - screen.at) / 1000.0) + " s earlier; no card");
                    Dashing.seen(this);
                    OfferFilterService.requestCheckFromNotification();
                    return;
                }
            }

            // Peek is decided before the card: the notification cannot judge this offer (it names the store, not the
            // pay), and when a peek will be tried the card, if one is due at all, is posted silently (no ring, so no
            // sound given back over the decline to come); it rings once only if the peek does not happen.
            Peek.Request peek = null;
            if (decision.result == OfferRule.Result.REVIEW) {
                Peek.Request request = new Peek.Request(source.getKey(), source.getPostTime(), offer.store, replay,
                        offer.freshPost, offer.alertTag);
                long readAgo = replay || foreground ? -1 : DecisionLog.screenReadAgo(this, DecisionLog.Entry.of(
                        DecisionLog.Source.NOTIFICATION, addOn, decision.basis, decision,
                        DecisionLog.Action.CHECK_BELL, settings.enabled, labels));
                String why = OfferFilterService.peekRefusal(this, request, settings, foreground, readAgo);
                // A re-post of the offer the user had in Dasher (and left it for a map, say): never brought back
                // over them; Dasher's own notification, and the card when one is due, are the way in.
                if (why == null && offer.heldInDasherUntil > SystemClock.elapsedRealtime()) why = HELD_IN_DASHER;
                if (why == null) {
                    peek = request;
                } else {
                    // Dasher's update of the same offer, still payless (refused a peek of its own only for not being
                    // a fresh post): a peek waiting for quiet goes, as before, but a post kept for the unlock still
                    // may open Dasher for the offer after the unlock.
                    invalidatePeekForKey(source.getKey(), !request.fresh && !replay);
                    Peek.skipped(this, why);
                }
            }

            DecisionLog.Action action;
            if (peek != null) {
                offer.quietCard = new QuietCard(facts, decision, labels, addOn, settings.enabled, signature);
                action = announce(notification.contentIntent, offer, facts, decision, signature, replay, foreground,
                        offer.dasherSounded, true);
            } else if (decision.result != OfferRule.Result.DECLINE) {
                action = announce(notification.contentIntent, offer, facts, decision, signature, replay, foreground,
                        offer.dasherSounded, false);
            } else if (OfferFilterService.userHasOffer(facts)) {
                action = leaveToUser(offer, decision, signature, "offer taken over by the user");
            } else if (!replay && (mayBeAccepted(offer, SystemClock.elapsedRealtime())
                    || OfferFilterService.acceptedLately(facts))) {
                // The user may have accepted this offer (an Accept the screen reader saw or recorded in the last minute,
                // or the offer they had in Dasher followed by a delivery or pickup screen): Dasher's own Decline is
                // never sent for it, and its notification is never hidden (a replay does neither anyway).
                action = leaveToUser(offer, decision, signature, "an offer you may have accepted");
            } else {
                action = filter(source, notification, offer, decision, signature, replay);
            }
            Dashing.seen(this);
            DecisionLog.record(this, DecisionLog.Entry.of(DecisionLog.Source.NOTIFICATION,
                    addOn, decision.basis, decision, action, settings.enabled, labels)
                    .withAlertTag(offer.alertTag, replay));
            if (peek != null) {
                unlockCards.add(peek.alertTag);
                livePeekCards.add(peek.alertTag);
                OfferFilterService.peekAt(this, peek);
            }
        } catch (RuntimeException error) {
            invalidatePeekForKey(source.getKey());
            DiagnosticLog.log(this, "notification",
                    "payload/handler rejected; original retained: " + error.getClass().getSimpleName());
            // An oversized notification is refused on purpose; only real failures count in the opt-in summary.
            if (!(error instanceof OversizedNotification)) DashSummary.error(this, "notification", error);
        }
    }

    /**
     * Main thread: a replacement no longer eligible for Peek revokes the earlier quiet-wait request. The offer's post
     * record ({@link #dasherPosts}) stays: a peek already opened and a card's tap still go by it, and Android's
     * listing, not this, says when the offer's notification is gone.
     */
    private void invalidatePeekForKey(String key) {
        invalidatePeekForKey(key, false);
    }

    /**
     * @param updateOfSameOffer the replacement is Dasher's update of the same offer, refused only for not being a fresh
     *     post: a post kept for the unlock may still open Dasher for it ({@link #renewForUnlock})
     */
    private void invalidatePeekForKey(String key, boolean updateOfSameOffer) {
        TrackedOffer offer = tracked.get(key);
        if (offer != null) {
            if (updateOfSameOffer) livePeekCards.remove(offer.alertTag);
            else revokePeek(offer.alertTag);
            offer.quietCard = null;
        }
    }

    private OfferRule.Decision decide(OfferSnapshot facts, List<String> labels, FilterSettings settings) {
        if (!settings.enabled) {
            return new OfferRule.Decision(OfferRule.Result.REVIEW, 0,
                    "auto-decline is off; inspect this offer manually", facts);
        }
        // No rule left pauses auto-decline, as the screen reader takes it: with nothing to meet, no offer is shown to
        // pass (never the pass chime for an offer nothing was read of), and no payless card stands beside Dasher's own.
        if (!settings.hasAnyRule()) return new OfferRule.Decision(OfferRule.Result.REVIEW, 0, NO_RULE, facts);
        if (AddOnOffer.isLikely(labels)) {
            return OfferRule.evaluateAddOn(AddOnOffer.parse(ActiveRouteStore.load(this), labels), settings);
        }
        return OfferRule.evaluate(facts, settings);
    }

    /**
     * Returns the state for this notification incarnation, starting a new one when the key is new, its previous
     * incarnation expired, the merchant changed, or (never on a replay) the same store's re-post is a new offer
     * ({@link OfferAlertState#newOfferReason}).
     */
    private TrackedOffer track(StatusBarNotification source, String merchant, String text, List<String> labels, boolean foreground,
                               boolean replay) {
        long now = SystemClock.elapsedRealtime();
        // The Handler expiry runs on uptime, which stops in deep sleep; elapsed time is authoritative.
        removeExpired(now);
        String key = source.getKey();
        TrackedOffer offer = tracked.get(key);
        long heldUntil = 0;
        long acceptedUntil = 0;
        if (offer != null && !merchant.isEmpty() && !offer.merchant.isEmpty() && !merchant.equals(offer.merchant)) {
            // Another store is another offer. DoorDash's update of the same offer may change the words after "Go to"
            // all the same (" · 1 item" added): the store named the same way, the post keeps what held the offer.
            if (sameStore(offer.merchant, merchant)) {
                heldUntil = holdOf(offer);
                acceptedUntil = acceptedWindowOf(offer);
            }
            remove(key, offer);
            offer = null;
        }
        // A replay re-evaluates a post already handled: never a new offer.
        String newOffer = offer == null || replay
                ? null : offer.state.newOfferReason(text, source.getPostTime(), foreground);
        // The screen saw the last offer end and Dasher is on screen: this post is usually the notification of the
        // offer on screen now, which comes up to ~10 s after the screen read (and perhaps declined) it. A new
        // incarnation, but it must not revoke the confirmation of a decline under way for that offer.
        boolean sameOfferOnScreen = foreground && OfferFilterService.sameForegroundOfferNotification(labels);
        if (newOffer != null) {
            DiagnosticLog.log(this, "notification", "the same store re-posted: a new offer (" + newOffer + ")");
            // DoorDash re-posts an offer's notification as it ages: the offer the user had in Dasher, whose end the
            // screen never saw, or one a delivery or pickup screen followed (the user may have accepted it), may be
            // what this re-post is (a minute from its first post at most). It is never peeked at then, and a re-post
            // of a re-post keeps that.
            heldUntil = holdOf(offer);
            acceptedUntil = acceptedWindowOf(offer);
            remove(key, offer);
            offer = null;
        }
        boolean began = offer == null;
        if (offer == null) {
            if (tracked.size() >= MAX_TRACKED_OFFERS) {
                String oldest = tracked.keySet().iterator().next();
                remove(oldest, tracked.get(oldest));
            }
            TrackedOffer created = new TrackedOffer(now, source, merchant);
            created.expiry = () -> {
                if (tracked.get(key) == created) remove(key, created);
            };
            tracked.put(key, created);
            handler.postDelayed(created.expiry, OfferAlertState.LIFETIME_MS);
            // A next offer: under the silencer's lock, so a decline in progress turns no stream down over it.
            if (!sameOfferOnScreen && !replay) {
                OfferSilencer.nextOffer(this, OfferNotificationService::nextGeneration);
                if (!foreground) backgroundGeneration = generation();
            }
            if (!replay) lastNotice = new Notice(labels, generation(), foreground);
            if (replay) recallRead(key, source.getPostTime(), created.state, now);
            if (heldUntil > now) created.heldInDasherUntil = heldUntil;
            if (acceptedUntil > now) created.mayBeAcceptedUntil = acceptedUntil;
            offer = created;
        }
        // Dasher on screen as its notification came: the user has this offer in Dasher.
        if (foreground && !replay) offer.inDasher = true;
        offer.gapMs = source.getPostTime() - offer.state.postedAt;
        offer.state.postedAt = Math.max(offer.state.postedAt, source.getPostTime());
        offer.state.text = text;
        offer.freshPost = began && !replay;
        offerOutstanding = true;
        return offer;
    }

    /**
     * Until when a later post on this incarnation's key is held from Peek ({@link TrackedOffer#heldInDasherUntil}): the
     * hold it carries, or, for an offer the user had in Dasher whose end the screen never saw, or one a delivery or
     * pickup screen followed ({@link TrackedOffer#routeAfter}), a minute from its first post.
     */
    private static long holdOf(TrackedOffer offer) {
        long until = offer.heldInDasherUntil;
        if (offer.routeAfter || (!offer.state.endedOnScreen && (offer.inDasher || offer.state.readOnScreen != null))) {
            until = Math.max(until, offer.state.createdAt + OfferPairing.OFFER_MS);
        }
        return until;
    }

    /**
     * Until when a later post on this incarnation's key may be the notification of an offer the user accepted
     * ({@link TrackedOffer#mayBeAcceptedUntil}): what it carries, or, for an offer the user had in Dasher that a
     * delivery or pickup screen followed, a minute from its first post.
     */
    private static long acceptedWindowOf(TrackedOffer offer) {
        long until = offer.mayBeAcceptedUntil;
        if (userHadItThenRoute(offer)) until = Math.max(until, offer.state.createdAt + OfferPairing.OFFER_MS);
        return until;
    }

    /** The user had this offer in Dasher (read on screen, or posted while Dasher was in front), and a route followed. */
    private static boolean userHadItThenRoute(TrackedOffer offer) {
        return offer.routeAfter && (offer.inDasher || offer.state.readOnScreen != null);
    }

    /**
     * Whether a post of this incarnation may be the notification of an offer the user accepted: never declined through
     * Dasher's notification, nor hidden ({@link #handle}).
     */
    private static boolean mayBeAccepted(TrackedOffer offer, long now) {
        return offer.mayBeAcceptedUntil > now || userHadItThenRoute(offer);
    }

    /**
     * Whether two of DoorDash's "Go to …" texts name the same store: equal, or one the other with more words after it
     * ("taco bell · 1 item" after "taco bell").
     */
    static boolean sameStore(String before, String after) {
        if (before.isEmpty() || after.isEmpty()) return false;
        return namesWithMore(after, before) || namesWithMore(before, after);
    }

    /** Whether {@code text} is {@code store}, or {@code store} followed by more that does not continue its last word. */
    private static boolean namesWithMore(String text, String store) {
        return text.startsWith(store)
                && (text.length() == store.length() || !Character.isLetterOrDigit(text.charAt(store.length())));
    }

    /**
     * A known-failing offer: request DoorDash's own safe Decline action, or else only hide the notification.
     *
     * @return what was done, for the decision log
     */
    private DecisionLog.Action filter(StatusBarNotification source, Notification notification, TrackedOffer offer,
                                      OfferRule.Decision decision, String signature, boolean replay) {
        OfferAlerts.clear(this, offer.alertTag);
        DecisionLog.Action action = DecisionLog.Action.NOTIFICATION_DECLINE_SENT;
        if (!offer.state.actionRequested) {
            PendingIntent decline = replay ? null : declineAction(notification);
            if (decline != null) {
                offer.state.actionRequested = true;
                try {
                    sendDecline(decline);
                    DiagnosticLog.log(this, "notification",
                            "notification Decline action REQUESTED; awaiting DoorDash removal, not yet verified");
                } catch (PendingIntent.CanceledException | RuntimeException error) {
                    action = DecisionLog.Action.DECLINE_REFUSED;
                    DiagnosticLog.log(this, "notification", "Decline action failed; original retained");
                }
            } else if (replay) {
                action = DecisionLog.Action.REPLAY;
                DiagnosticLog.log(this, "notification", "known filtered offer on replay; no action taken");
            } else {
                action = DecisionLog.Action.NOTIFICATION_HIDDEN;
                DiagnosticLog.log(this, "notification", "known filtered offer; no safe background decline action. "
                        + "Hidden notification does NOT decline order.");
                cancelNotification(source.getKey());
            }
        }
        offer.state.delivered(signature, decision.result, false);
        FilterStore.setLastStatus(this, "Filtered background offer: " + decision.summary() + "\n"
                + (offer.state.actionRequested
                        ? "Decline requested; completion unverified."
                        : "Notification hidden only; order not automatically declined."));
        return action;
    }

    /**
     * The offer is the user's (they touched the screen during its decline, or may have accepted it): its notification
     * is neither declined nor hidden.
     *
     * @param whose why, for the log (fixed words)
     */
    private DecisionLog.Action leaveToUser(TrackedOffer offer, OfferRule.Decision decision, String signature,
                                           String whose) {
        offer.state.delivered(signature, decision.result, false);
        DiagnosticLog.log(this, "notification", whose + "; notification left alone");
        return DecisionLog.Action.USER_TOOK_OVER;
    }

    /**
     * A passing or unclassified offer: post the matching Offer Filter card, keeping DoorDash's original. A card for
     * an offer that could not be judged does not ring when Dasher's own offer alert sounds: the user hears that. And
     * it is not posted at all while Dasher's own notification of the offer is there to be tapped and alerts by itself
     * (the owner: ".72 still gives redundant notifications on dashes (says dasher notification didn't have price
     * info...etc)"): a payless card adds nothing to it. DoorDash's own offer channel, as phones report it, has no sound
     * at the highest importance: its notification pops up while Dasher rings by itself, whatever Android's record of
     * the post's sound says. The card shows (ringing once, as before) while Dasher's notification is gone or cannot be
     * tapped (or Android cannot say which), as the way into the offer, and while it cannot alert by itself
     * ({@link #alertsByItself}: silenced, held back by Do Not Disturb, or not ranked), as how the offer is heard.
     *
     * <p>With a peek about to read it ({@code peeking}), the card, when one is due, is posted silently: no ring, and so
     * no sound given back over the decline that may follow ({@link OfferSilencer#yieldToPassingAlert}). It rings once
     * only if the peek does not happen ({@link #peekNotTaken}).
     *
     * @param dasherSounds whether Android shows Dasher's own alert for this post sounded ({@link #dasherAlertSounded})
     * @return what was done, for the decision log
     */
    private DecisionLog.Action announce(PendingIntent contentIntent, TrackedOffer offer, OfferSnapshot facts,
                                        OfferRule.Decision decision, String signature, boolean replay,
                                        boolean foreground, boolean dasherSounds, boolean peeking) {
        boolean review = decision.result == OfferRule.Result.REVIEW;
        // An offer passing only because Autopilot's bar is below 100 is the user's to accept: its card goes as one to
        // check (its channel, ring and title; never the pass chime) and says why, with the figures the notification
        // showed. Unlike a payless card it carries what was read, so it never gives way to Dasher's own as one would.
        boolean belowMinimums = decision.result == OfferRule.Result.KEEP && decision.belowMinimums;
        OfferRule.Result shown = announced(decision);
        if (review && offer.unshownCard && OfferAlerts.showing(this, offer.alertTag)) {
            // Its card already says Dasher didn't show this offer when it opened: a later post of the offer that still
            // cannot be judged changes nothing (never a payless card in its place).
            offer.state.rang |= dasherSounds;
            offer.state.delivered(signature, decision.result, dasherSounds);
            if (foreground) OfferFilterService.requestCheckFromNotification();
            return DecisionLog.Action.SILENT_CARD;
        }
        boolean ring = !peeking && offer.state.shouldRing(shown, foreground, replay);
        boolean dasherRings = ring && dasherSounds;
        // The native sound counts even while a pending Peek has not asked its own card to ring.
        offer.state.rang |= dasherSounds;
        if (dasherRings) {
            ring = false;
            // The native alert was heard even if our card is currently blocked. A later allowed post must not
            // spend this offer's ring a second time, but a failed post still must not count as displayed.
            DiagnosticLog.log(this, "alert", "no ring: Android shows Dasher's own alert for this offer sounded");
        }
        NativeAlert nativeAlert = nativeAlert(offer);
        // The owner (#13): a payless card never stands beside Dasher's own actionable notification while that
        // notification alerts by itself (whatever Android's record of the post's sound says); a passing one gives way
        // to it when it need not ring, unless a ring of ours is its sole alert.
        boolean payless = review && nativeAlert == NativeAlert.ACTIONABLE && offer.dasherAlerts;
        if (review && nativeAlert == NativeAlert.ACTIONABLE && !offer.dasherAlerts) {
            DiagnosticLog.log(this, "alert", "card beside Dasher's notification: Android does not show it alerting by "
                    + "itself (silenced, held back by Do Not Disturb, or not ranked)");
        }
        if (payless || (!ring && (!offer.ownAlerted || dasherSounds) && nativeAlert == NativeAlert.ACTIONABLE)) {
            // The original already provides the way into this offer. Keep it, and keep Peek's incarnation alive,
            // without posting another generic card or invoking a native dismissal callback.
            OfferAlerts.clear(this, offer.alertTag);
            offer.nativeCard = true;
            offer.state.delivered(signature, decision.result, dasherSounds);
            if (foreground) OfferFilterService.requestCheckFromNotification();
            DiagnosticLog.log(this, "alert", payless
                    ? "actionable native alert retained; payless card omitted: Dasher's own alerts by itself"
                    : "actionable native alert retained; duplicate generic card omitted");
            return DecisionLog.Action.NATIVE_ALERT;
        }
        String detail = review ? reviewText(facts, decision, peeking)
                : belowMinimums ? AutopilotText.belowMinimumsCard(facts, decision.scorePercent,
                        decision.minimumScalePercent)
                : facts.summary() + "; " + decision.summary();
        boolean posted = OfferAlerts.notifyOffer(this, offer.alertTag, contentIntent, shown, detail, ring,
                offer.store);
        if (posted) {
            offer.nativeCard = false;
            offer.ownAlerted |= ring;
        }
        // Dasher's own alert sounding for it uses up its one ring, as ours would have.
        if (posted) offer.state.delivered(signature, decision.result, ring || dasherRings);
        if (review) {
            FilterStore.setLastStatus(this, "Background offer requires review: " + decision.reason + ".\n" + (peeking
                    ? AppName.NAME + " is opening Dasher to read it (Peek)."
                    : "Open Dasher and " + AppName.NAME + " judges it."));
        }
        if (foreground) OfferFilterService.requestCheckFromNotification();
        if (!posted) return DecisionLog.Action.CARD_BLOCKED;
        if (peeking) return DecisionLog.Action.PEEK_CARD;
        if (dasherRings) return DecisionLog.Action.DASHER_SOUNDS;
        if (shown == OfferRule.Result.REVIEW) {
            return ring ? DecisionLog.Action.CHECK_BELL : DecisionLog.Action.SILENT_CARD;
        }
        return ring ? DecisionLog.Action.BELL : DecisionLog.Action.QUIET_PASS_CARD;
    }

    /**
     * How an offer's decision is announced (its card's channel, ring and title; the slim bar's tint over Dasher): as
     * decided, except that an offer passing only because Autopilot's bar is below 100
     * ({@link OfferRule.Decision#belowMinimums}) is announced as REVIEW. It is the user's to accept: never the pass
     * chime on {@link OfferAlerts#CHANNEL_ID}, never green, and never accepted automatically ({@link AutoAccept}).
     */
    static OfferRule.Result announced(OfferRule.Decision decision) {
        return decision.result == OfferRule.Result.KEEP && decision.belowMinimums ? OfferRule.Result.REVIEW
                : decision.result;
    }

    private enum NativeAlert { ACTIONABLE, UNTAPPABLE, ABSENT, UNKNOWN }

    /** Unknown listing differs from a verified removal/replacement of this incarnation. */
    private NativeAlert nativeAlert(TrackedOffer offer) {
        try {
            StatusBarNotification[] current = getActiveNotifications(new String[] {offer.nativeKey});
            if (current == null) return NativeAlert.UNKNOWN;
            if (current.length != 1 || !isFromOwnUsersDasher(current[0])
                    || !offer.nativeKey.equals(current[0].getKey())
                    || current[0].getPostTime() != offer.state.postedAt) return NativeAlert.ABSENT;
            Notification nativeAlert = current[0].getNotification();
            return nativeAlert != null && nativeAlert.contentIntent != null
                    ? NativeAlert.ACTIONABLE : NativeAlert.UNTAPPABLE;
        } catch (RuntimeException unknown) {
            return NativeAlert.UNKNOWN;
        }
    }

    /**
     * What the card of an offer its notification cannot judge says: plainly that the notification shows no pay (or what
     * else it lacks), and that Offer Filter judges it on Dasher's screen, which it is opening when it peeks.
     */
    static String reviewText(OfferSnapshot facts, OfferRule.Decision decision, boolean peeking) {
        String what = facts.payCents == null && facts.payAtMostCents == null
                ? "Dasher's notification shows no pay."
                : "Dasher's notification doesn't show enough to judge it: " + decision.reason + ".";
        return what + (peeking ? " " + AppName.NAME + " is opening Dasher to read it."
                : " Open Dasher and " + AppName.NAME + " judges it.");
    }

    /**
     * A peek of this incarnation's offer did not happen (refused, the phone not quiet, or Dasher never came up): the
     * offer is announced as usual, so its card, when one is due under the no-payless-card rule ({@link #announce}:
     * Dasher's notification gone, untappable or not known, or not alerting by itself), rings once as it would have,
     * unless the screen read the offer meanwhile or the card is gone (the user tapped it). Any thread.
     */
    static void peekNotTaken(String alertTag) {
        OfferNotificationService service = active;
        if (service == null || alertTag == null) return;
        onMain(service, () -> service.cardAfterNoPeek(alertTag));
    }

    private void cardAfterNoPeek(String alertTag) {
        if (!Consent.accepted(this)) return;
        TrackedOffer offer = trackedBy(alertTag);
        if (offer == null || offer.quietCard == null) return;
        QuietCard card = offer.quietCard;
        offer.quietCard = null;
        if (offer.state.readOnScreen != null || (!OfferAlerts.showing(this, alertTag)
                && !(offer.nativeCard && nativeAlert(offer) != NativeAlert.ABSENT))) return;
        // The rules can change while Peek waits for quiet. Do not revive a card judged under obsolete minimums (or
        // any other changed decision); replay the current notification silently instead.
        FilterSettings current = FilterStore.load(this);
        OfferRule.Decision now = decide(card.facts, card.labels, current);
        if (current.enabled != card.enabled || !now.summary().equals(card.decision.summary())) {
            OfferAlerts.clear(this, alertTag);
            reconcile();
            return;
        }
        DecisionLog.Action action = announce(offer.contentIntent, offer, card.facts, card.decision, card.signature,
                false, false, offer.dasherSounded, false);
        DecisionLog.record(this, DecisionLog.Entry.of(DecisionLog.Source.NOTIFICATION, card.addOn,
                card.decision.basis, card.decision, action, card.enabled, card.labels).withAlertTag(alertTag, false));
    }

    /** Why a re-post of an offer the user had in Dasher is not peeked at ({@link TrackedOffer#heldInDasherUntil}). */
    static final String HELD_IN_DASHER = "a re-post of the offer you had in Dasher (it may still be up)";

    /** An offer's notification with no rule set: nothing to meet, so it is left to the user ({@link #decide}). */
    static final String NO_RULE = "no rule is set";

    /** What the card of an offer a peek opened Dasher for, and Dasher never drew, says. */
    static final String UNSHOWN_TEXT = "Dasher didn't show this offer when it opened. Tap to open it.";

    /**
     * What the card says instead when the peek's time ran out over a screen too big to read, or Dasher's question, with
     * none of the offer's figures read: perhaps the offer was there, unread.
     */
    static final String UNREAD_TEXT = "Couldn't read this offer on Dasher's screen. Tap to open it.";

    /**
     * A peek opened Dasher for this incarnation's offer and Dasher never drew it (or the peek's time ran out with its
     * notification still posted): its card says so, in place of the quiet one, and its tap tries Dasher's own
     * notification intent first. It rings under the usual rules (once per offer, never after Dasher's own alert
     * sounded, never over Dasher on screen). Nothing when the screen read the offer, the notification is gone or the
     * notice is not accepted. Any thread; {@code result} gets "rang", "silent", "blocked" or "none" on the main thread.
     */
    static void peekUnshown(String alertTag, java.util.function.Consumer<String> result) {
        peekUnshown(alertTag, false, result);
    }

    /** @param unread the screen may have shown the offer, unread ({@link #UNREAD_TEXT}): not "never shown" */
    static void peekUnshown(String alertTag, boolean unread, java.util.function.Consumer<String> result) {
        OfferNotificationService service = active;
        if (service == null || alertTag == null) {
            if (result != null) result.accept("none");
            return;
        }
        onMain(service, () -> {
            String card;
            try {
                card = service.cardOfUnshownOffer(alertTag, unread ? UNREAD_TEXT : UNSHOWN_TEXT);
            } catch (RuntimeException error) {
                DiagnosticLog.log(service, "alert", "card for an unshown offer failed: " + error.getClass().getSimpleName());
                card = "blocked";
            }
            if (result != null) result.accept(card);
        });
    }

    private String cardOfUnshownOffer(String alertTag, String text) {
        if (!Consent.accepted(this)) return "none";
        TrackedOffer offer = trackedBy(alertTag);
        if (offer == null || offer.state.readOnScreen != null || offer.readCard) return "none";
        // A passing card already carries what the notification said, and a known failure gets none.
        if (offer.state.result == OfferRule.Result.KEEP || offer.state.result == OfferRule.Result.DECLINE) return "none";
        if (nativeAlert(offer) == NativeAlert.ABSENT) return "none";
        QuietCard quiet = offer.quietCard;
        offer.quietCard = null;
        boolean foreground = OfferFilterService.isDasherOnScreenNow();
        boolean ring = offer.state.shouldRing(OfferRule.Result.REVIEW, foreground, false) && !offer.dasherSounded;
        // It says what happened, so it is seen even when it need not ring (Dasher's own alert rang, or Dasher is in
        // front, showing no offer: never a ring over it): it pops up with no sound of its own, never a stale card
        // waiting quietly in the shade.
        boolean posted = OfferAlerts.post(this, new OfferAlerts.Card(alertTag, OfferRule.Result.REVIEW, text)
                .dasherOwn(offer.contentIntent).ring(ring).shown(true).store(offer.store)
                .preferDasherOwn(true));
        if (!posted) return "blocked";
        offer.nativeCard = false;
        offer.unshownCard = true;
        offer.ownAlerted |= ring;
        offer.state.delivered(offer.state.signature, OfferRule.Result.REVIEW, ring);
        if (quiet != null) {
            DecisionLog.record(this, DecisionLog.Entry.of(DecisionLog.Source.NOTIFICATION, quiet.addOn,
                    quiet.decision.basis, quiet.decision, ring ? DecisionLog.Action.CHECK_BELL
                            : DecisionLog.Action.SILENT_CARD, quiet.enabled, quiet.labels)
                    .withAlertTag(alertTag, false));
        }
        return ring ? "rang" : "silent";
    }

    /**
     * A peek read an offer that passes or is unclear while the user navigates, and went back to the map (the user's
     * decision): the offer's card carries what was read and rings once, as an ordinary card would; when it need not
     * ring (Dasher's own alert, or an earlier card, already rang for it) it still pops up, with no sound of its own. It
     * counts down with the offer's own countdown when one was read. Its notification's later updates change nothing.
     * Any thread; without the listener, the card is posted on its own.
     *
     * @param foldedTag asked on the main thread: the card tag of the notification the screen's reading of this offer
     *     folded in (its incarnation), or null when none was; the offer is then matched by what was read
     * @param endsAt when the offer's countdown runs out (wall clock), as read; 0 when not known
     */
    static void peekCard(Context context, OfferSnapshot read, OfferRule.Result result, String text) {
        peekCard(context, null, read, result, text, 0);
    }

    static void peekCard(Context context, java.util.function.Supplier<String> foldedTag, OfferSnapshot read,
                         OfferRule.Result result, String text, long endsAt) {
        if (!Consent.accepted(context)) return;
        OfferNotificationService service = active;
        if (service == null) {
            OfferAlerts.post(context, new OfferAlerts.Card("peek-" + System.currentTimeMillis(), result, text)
                    .ring(true).endsAt(endsAt));
            return;
        }
        onMain(service, () -> service.cardOfPeekedOffer(foldedTag == null ? null : foldedTag.get(), read, result,
                text, endsAt));
    }

    private void cardOfPeekedOffer(String foldedTag, OfferSnapshot read, OfferRule.Result result, String text,
                                   long endsAt) {
        if (!Consent.accepted(this)) return;
        // The incarnation whose notification the screen's reading folded in; else one whose reading agrees with it.
        TrackedOffer found = foldedTag == null ? null : trackedBy(foldedTag);
        if (found == null) {
            for (TrackedOffer offer : tracked.values()) {
                OfferSnapshot onScreen = offer.state.readOnScreen;
                if (onScreen != null && (onScreen.fingerprint().equals(read.fingerprint())
                        || onScreen.agreesWith(read))) {
                    found = offer;
                }
            }
        }
        // A navigation return uses the same per-offer budget as the first card and its later updates.
        boolean ring = (found == null || found.state.shouldRing(result, false, false))
                && (found == null || !found.dasherSounded);
        String tag = found != null ? found.alertTag : "peek-" + System.currentTimeMillis();
        boolean posted = OfferAlerts.post(this, new OfferAlerts.Card(tag, result, text)
                .dasherOwn(found == null ? null : found.contentIntent).ring(ring).shown(true)
                .store(found == null ? "" : found.store).endsAt(endsAt));
        if (found != null && posted) {
            found.readCard = true;
            found.quietCard = null;
            found.state.delivered(found.state.signature, result, true);
        }
    }

    private TrackedOffer trackedBy(String alertTag) {
        for (TrackedOffer offer : tracked.values()) {
            if (alertTag.equals(offer.alertTag)) return offer;
        }
        return null;
    }

    private void removeExpired(long now) {
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, TrackedOffer> entry : tracked.entrySet()) {
            if (entry.getValue().state.expired(now)) expired.add(entry.getKey());
        }
        for (String key : expired) remove(key, tracked.get(key));
    }

    private void remove(String key, TrackedOffer offer) {
        if (offer == null) return;
        tracked.remove(key);
        revokePeek(offer.alertTag);
        dasherPosts.remove(offer.alertTag);
        ownTapsSent.remove(offer.alertTag);
        if (offer.expiry != null) handler.removeCallbacks(offer.expiry);
        OfferAlerts.clear(this, offer.alertTag);
        offerOutstanding = !tracked.isEmpty();
    }

    private void clearTracked() {
        long now = SystemClock.elapsedRealtime();
        for (Map.Entry<String, TrackedOffer> entry : tracked.entrySet()) {
            TrackedOffer offer = entry.getValue();
            if (offer.expiry != null) handler.removeCallbacks(offer.expiry);
            OfferAlerts.clear(this, offer.alertTag);
            // Kept for the replay after a reconnect, so an offer the screen read gets no card or line again.
            if (offer.state.readOnScreen != null) {
                readBeforeReconnect.put(entry.getKey(), new ReadMemory(offer.state, now));
            }
        }
        while (readBeforeReconnect.size() > MAX_TRACKED_OFFERS) {
            readBeforeReconnect.remove(readBeforeReconnect.keySet().iterator().next());
        }
        tracked.clear();
        unlockCards.clear();
        livePeekCards.clear();
        dasherPosts.clear();
        ownTapsSent.clear();
        postedKeys.clear();
        offerOutstanding = false;
    }

    /**
     * On a replay after a reconnect: when the screen had read this very post (same key, same post time), the new
     * incarnation knows it again, so the post gets no card or line. A newer post, or one kept too long ago, is unread.
     */
    private static void recallRead(String key, long postTime, OfferAlertState state, long now) {
        ReadMemory memory = readBeforeReconnect.remove(key);
        if (memory == null || memory.readThrough != postTime || now < memory.keptAt
                || now - memory.keptAt >= OfferAlertState.LIFETIME_MS) {
            return;
        }
        state.readOnScreen = memory.facts;
        state.readThrough = memory.readThrough;
        state.endedOnScreen = memory.ended;
        state.cardCleared = true;
    }

    private static boolean isFromOwnUsersDasher(StatusBarNotification source) {
        return source != null && DASHER_PACKAGE.equals(source.getPackageName())
                && Process.myUserHandle().equals(source.getUser());
    }

    /**
     * DoorDash's Decline action, only when it is provably a DoorDash-created broadcast or service intent. Action
     * type inspection is public only from API 31; older devices must not guess and launch an activity.
     */
    private static PendingIntent declineAction(Notification notification) {
        if (Build.VERSION.SDK_INT < 31 || notification.actions == null) return null;
        for (Notification.Action action : notification.actions) {
            if (action == null || action.title == null || action.actionIntent == null) continue;
            PendingIntent intent = action.actionIntent;
            if (OfferControls.isButton(action.title.toString(), "decline")
                    && DASHER_PACKAGE.equals(intent.getCreatorPackage()) && !intent.isActivity()) {
                return intent;
            }
        }
        return null;
    }

    /** Only standard displayed text is evidence; arbitrary extras are never read as offer values. */
    private static List<String> labels(Notification notification) {
        List<String> out = new ArrayList<>();
        Bundle extras = notification.extras;
        if (extras == null) return out;
        for (String key : TEXT_EXTRAS) {
            Object value = extras.get(key);
            if (value instanceof CharSequence) addLabel(out, value.toString());
        }
        Object lines = extras.get(Notification.EXTRA_TEXT_LINES);
        if (lines instanceof CharSequence[]) {
            for (CharSequence line : (CharSequence[]) lines) {
                if (line != null) addLabel(out, line.toString());
            }
        }
        return out;
    }

    private static void addLabel(List<String> labels, String value) {
        if (labels.size() >= MAX_NOTIFICATION_LABELS || value.length() > MAX_NOTIFICATION_LABEL_CHARS) {
            throw new OversizedNotification();
        }
        String clean = OfferEvidence.normalize(value);
        if (!clean.isEmpty() && !labels.contains(clean)) labels.add(clean);
    }

    /** A notification too large to read safely; it is left alone, as intended, not reported as a failure. */
    static final class OversizedNotification extends IllegalArgumentException {
        OversizedNotification() {
            super("oversized notification");
        }
    }

    /** The lower-cased text after "go to ", e.g. "chick-fil-a"; empty when absent. */
    private static String merchant(List<String> labels) {
        for (String label : labels) {
            String lower = label.toLowerCase(Locale.US);
            int index = lower.indexOf("go to ");
            if (index >= 0) return lower.substring(index + "go to ".length()).trim();
        }
        return "";
    }

    /**
     * The store Dasher's "New Order: Go to …" names, as written, for the title of our card ("Taco Bell offer"); empty
     * when none, or when it is too long to be a store's name. It names a new offer, never one accepted.
     */
    static String store(List<String> labels) {
        for (String label : labels) {
            int index = label.toLowerCase(Locale.US).indexOf("go to ");
            if (index < 0) continue;
            String name = label.substring(index + "go to ".length()).trim();
            return name.length() <= MAX_STORE_CHARS ? name : "";
        }
        return "";
    }

    /** Drops the merchant and headline lines so a store name cannot contribute offer metrics. */
    private static List<String> metricLabels(List<String> labels) {
        List<String> out = new ArrayList<>();
        for (String label : labels) {
            String lower = label.toLowerCase(Locale.US);
            if (lower.contains("go to ") || lower.startsWith("new delivery")) continue;
            out.add(label);
        }
        return out;
    }

    /** Android's ranking of the source notification, or null when Android does not say. */
    private static Ranking ranking(RankingMap map, String key) {
        if (map == null) return null;
        try {
            Ranking rank = new Ranking();
            return map.getRanking(key, rank) ? rank : null;
        } catch (RuntimeException unreadable) {
            // Unknown, as when Android does not say: the offer is still announced as before.
            return null;
        }
    }

    /**
     * Whether Android shows that Dasher's own post of this offer made a sound, so our card need not ring too (the
     * user's decision). From Android 10 Android says when it last alerted audibly for the notification: at this post,
     * give or take a second. Before that, every condition Android sounds on must hold: the post matches the
     * interruption filter (Do Not Disturb does not hold it back), Android ranks it at default importance or above,
     * its channel has a sound, and it is neither an only-alert-once update of a notification Android still shows nor
     * a group child that alerts through its summary. False when Android does not say, so ours rings once as before.
     *
     * @param update whether Android still showed a notification with this key when this one came
     */
    static boolean dasherAlertSounded(Ranking rank, StatusBarNotification source, boolean update) {
        if (rank == null || source == null) return false;
        if (Build.VERSION.SDK_INT >= 29) {
            long alerted = rank.getLastAudiblyAlertedMillis();
            return alerted > 0 && alerted >= source.getPostTime() - 1_000;
        }
        Notification notification = source.getNotification();
        NotificationChannel channel = rank.getChannel();
        if (notification == null || channel == null || channel.getSound() == null) return false;
        boolean onlyOnce = update && (notification.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0;
        boolean quietChild = notification.getGroup() != null
                && notification.getGroupAlertBehavior() == Notification.GROUP_ALERT_SUMMARY;
        return rank.matchesInterruptionFilter() && rank.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT
                && !onlyOnce && !quietChild;
    }

    /**
     * Whether Android ranks this post of Dasher's as alerting the user by itself: Do Not Disturb lets it through, and it
     * pops up on screen (high importance or above), or it sounds (default importance or above, on a channel with a
     * sound). DoorDash's own offer channel, as phones report it, has no sound at the highest importance: its
     * notification pops up while Dasher rings by itself. Blocked, Silent or minimized (below default importance), a
     * channel at default importance with no sound, a post Do Not Disturb holds back, or a post Android does not rank
     * does not: a card of ours is then how the offer is heard. Channel settings only, never the post's words.
     */
    static boolean alertsByItself(Ranking rank) {
        if (rank == null || !rank.matchesInterruptionFilter()) return false;
        int importance = rank.getImportance();
        if (importance >= NotificationManager.IMPORTANCE_HIGH) return true;
        NotificationChannel channel = rank.getChannel();
        return importance >= NotificationManager.IMPORTANCE_DEFAULT && channel != null && channel.getSound() != null;
    }

    /**
     * Records the source channel's actual sound/vibration settings and extra key names (never their values): each is
     * a state, logged once when it changes (and at least daily), per channel.
     */
    private void logChannel(NotificationChannel channel, Notification notification, boolean dasherSounded) {
        if (!DiagnosticLog.isEnabled(this)) return;
        String id = String.valueOf(notification.getChannelId());
        DiagnosticLog.logOnChange(this, "notification-channel", id, "id=" + id
                + " actualSound=" + (channel == null ? "unknown" : channel.getSound() != null)
                + " actualVibration=" + (channel == null ? "unknown" : channel.shouldVibrate())
                + " importance=" + (channel == null ? "unknown" : channel.getImportance())
                + " fullScreenIntent=" + (notification.fullScreenIntent != null)
                + " postSounded=" + dasherSounded);
        if (notification.extras != null) {
            List<String> keys = new ArrayList<>(notification.extras.keySet());
            Collections.sort(keys);
            if (keys.size() > MAX_LOGGED_EXTRA_KEYS) keys = keys.subList(0, MAX_LOGGED_EXTRA_KEYS);
            DiagnosticLog.logOnChange(this, "notification-meta", id, "extra keys only=" + keys);
        }
    }
}
