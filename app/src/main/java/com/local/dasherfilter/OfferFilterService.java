package com.local.dasherfilter;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.UiModeManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Reads only the visible Dasher window and may request Decline on a readable, known-failing offer. A notification
 * is never authority to click another offer, and a successful click is a request, not a confirmed decline. In split
 * screen, Dasher's half is read even while the user is in the other half, so an offer there is judged at once.
 *
 * <p>Threads. Reading a screen means asking Android for its windows and walking up to {@link #MAX_SCAN_NODES} nodes,
 * each a call into Dasher's process: a fraction of a second on a slow phone, for every one of Dasher's many events.
 * Offer Filter's own screen shares the main thread, so all of that runs on the service's own scanner thread
 * instead: every look at the windows, every read, every tap, the decline confirmation, the touch takeover and the
 * sound guard. The main thread only notes each event and hands it over; a read already queued is not queued again.
 * What other threads may ask (whether Dasher is on screen, whether the user took an offer over) is published by the
 * scanner as immutable snapshots. What touches the history, the last status, the declines learned by hand and our
 * cards goes back to the main thread in order, where Dasher's notifications are handled, so a screen reading and a
 * notification of the same offer are folded one after the other, never at once. Views (the tab, the guide, the touch
 * watch) and toasts are only ever touched on the main thread.
 *
 * <p>Offer reads first. Every call into Dasher waits for Dasher's own UI thread, which is busiest exactly while an offer
 * animates in, so the scanner never makes an offer's read wait for work that can wait. A window change, and any event
 * of Dasher's while an offer or confirmation is up or a decline is under way (or one was seen in the last
 * {@link #HOT_MS}), is handed to the front of the scanner's queue. What is not essential (the window watch, reading
 * around a click, the after-offer captures and settling) does not run while an offer or confirmation is up or a decline
 * is under way, runs only after the read an event asked for, and stops at its next call into Dasher when Dasher sends
 * another event. After the first Decline tap, Dasher's question is looked for every {@link #CONFIRM_POLL_MS} for
 * {@link #CONFIRM_POLL_WINDOW_MS} without reading any window twice, and tapped the moment it is found.
 *
 * <p>The user's decline. From the first Decline tap, the offer is handed back the moment the user acts on it: a touch
 * that is not the echo of one of the app's own taps ({@link OwnTaps}), a click Dasher reports on any node the app did
 * not tap, or the offer showing again after its screen gave way to its question with no tap of the question's Decline
 * taken ({@link DeclineEpisode}). In split screen with the bounds of Dasher's half known, a touch counts only on
 * Dasher's half (the user's decision): one Offer Filter's own screen reports too was on its own half, and while that is
 * being judged nothing is tapped on the offer. Dasher's question is tried {@link DeclineState#MAX_CONFIRMATION_TRIES}
 * times at most, {@link #CONFIRM_RETRY_MS} apart, and then left to the user; an offer whose screen gave way is never
 * declined again, except once after Dasher closed the question the app confirmed and kept showing the offer.
 *
 * <p>Split screen. Dasher's half is read only while it is really in view beside the app the user is in
 * ({@link SplitWindows#covered}), and every screen line carries where Dasher was ({@link SplitWindows#field}).
 *
 * <p>Peek ({@link Peek}). The one time the app opens Dasher by itself: Dasher's notification brought an offer it cannot
 * judge while Dasher is hidden, the phone unlocked and the Peek switch on. Dasher's own launch intent is started from
 * here, the offer is read and decided as any offer on screen, and once the app's own decline of it completed, the app
 * that was in front is opened again. Anything of the user's during it (a touch, a tap on Dasher, the decline handed
 * back), a passing or unclear offer, another offer, the screen off or locked, or its time running out leaves Dasher up.
 */
public final class OfferFilterService extends AccessibilityService {
    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    private static final long OVERLAY_CHECK_MS = 1_500L;
    private static final long RECHECK_WINDOW_MS = 3000;
    private static final long RECHECK_INTERVAL_MS = 200;
    /**
     * While nothing is up, Dasher's map and clocks change its screen many times a second. The first change after a
     * quiet spell this long is read at once; the later changes of a burst wait for one read this long after the last
     * one, the last change included. A window change, any sign of an offer or confirmation on the last read, a decline
     * under way or a screen still settling is read at once.
     */
    static final long QUIET_SCAN_GAP_MS = 150;
    /**
     * While Dasher is on screen, the windows are looked at this often (leaving it sends no event of Dasher's), unless a
     * read looked moments ago.
     */
    static final long WINDOW_WATCH_MS = 500;
    /**
     * A read slower than this is logged, so a report shows the phone's real timings: every one while an offer or
     * confirmation is up or a decline is under way, otherwise once a minute at most.
     */
    static final long SLOW_SCAN_MS = 150;
    static final long SLOW_SCAN_LOG_EVERY_MS = 60_000;
    /**
     * An offer or confirmation seen this recently keeps every event of Dasher's urgent: its read goes to the front of
     * the scanner's queue, ahead of anything that can wait.
     */
    static final long HOT_MS = 3_000;
    /** After the first Decline tap, Dasher's question is looked for this often, for this long. */
    static final long CONFIRM_POLL_MS = 100;
    static final long CONFIRM_POLL_WINDOW_MS = 3_000;
    /** A quiet window can gain a delayed question without an accessibility event; read it at most once a second. */
    static final long CONFIRM_QUIET_READ_MS = 1_000;
    /**
     * Besides the window a read just read, Dasher's question is looked for in this many of Dasher's other windows at
     * most (the newest first), each read to this many nodes at most: a dialog is a few nodes, never a whole screen.
     */
    private static final int MAX_CONFIRM_WINDOWS = 2;
    static final int MAX_CONFIRM_NODES = 200;
    /**
     * A read waiting for Dasher's question stops when a window change comes this many times in a row at most (the
     * question's own window, perhaps): the read for that change, first in the queue, reads the new window at once.
     */
    private static final int MAX_CUT_READS = 2;
    /** A confirmation waits for the touch watch to come up this long at most, then is tapped all the same. */
    static final long WATCH_HOLD_MS = 50;
    /**
     * A touch this soon after one of the app's own taps (or during it) is that tap's echo, not the user's: Android can
     * report the app's own action to the touch watch as a touch outside it. Each tap has a window of its own.
     */
    static final long OWN_ACTION_ECHO_MS = OwnTaps.TOUCH_ECHO_MS;
    /**
     * In split screen, a touch the watch reports is in Offer Filter's own half when Offer Filter's screen had a finger
     * land at the same time: both reports of one touch carry its time, so they are this far apart at most.
     */
    static final long SAME_TOUCH_MS = 50;
    /**
     * In split screen, how long a touch the watch reports waits for Offer Filter's screen to report it too (Android
     * tells both on the main thread, in either order). Meanwhile no tap of the app's goes to the offer being declined;
     * a touch Offer Filter's screen never reported is then the user's, on Dasher's half (or another app's: Android
     * does not say where a touch outside the watch landed in another app's window).
     */
    static final long OWN_HALF_WAIT_MS = 100;
    /**
     * A try at Dasher's question that Android refused, or that Dasher did not act on, is tried again this long after
     * after a refused try; taken requests wait 2–3 seconds for Dasher, each followed by a fresh read.
     */
    static final long CONFIRM_RETRY_MS = 300;
    /** Reading around a click held back by Dasher's events is tried this many times, this long at most. */
    private static final int CLICK_READ_TRIES = 3;
    private static final long CLICK_READ_WAIT_MS = 2_000;
    /** Settling what came after an offer waits this long while an offer is up or a decline is under way. */
    private static final long BUSY_RETRY_MS = 250;
    private static final int MAX_SCAN_DEPTH = 60;
    private static final int MAX_SCAN_NODES = 1500;
    private static final int MAX_CLICK_TARGET_ANCESTORS = 8;
    private static final int MAX_ACCEPT_LABEL_ANCESTORS = 4;
    /** A click on the very node the app tapped this soon after is that tap's echo (see {@link OwnTaps}). */
    private static final long OWN_TARGET_ECHO_MS = OwnTaps.TARGET_ECHO_MS;
    /** A manual Decline counts for the offer seen on screen at most this long ago. */
    private static final long MANUAL_DECLINE_OFFER_AGE_MS = 90_000;
    /** Below a tapped node, its labels are looked for this deep and in this many nodes at most. */
    private static final int MAX_TAP_SUBTREE_DEPTH = 3;
    private static final int MAX_TAP_SUBTREE_NODES = 24;
    /** A tap away from any offer is logged (its shape only, no words) at most this often. */
    private static final long BARE_TAP_LOG_MS = 10_000;
    /** Dasher's screens after an offer left are kept in the screens log this long after, this many at most. */
    private static final long AFTERMATH_CAPTURE_MS = 30_000;
    private static final int AFTERMATH_LINES = 3;
    /**
     * Dasher's other screens are kept only while a dash is on ({@link Dashing#on}) or this soon after an offer, so
     * the screens log holds the dash and nothing of the phone's other uses of Dasher (its wallet, its earnings).
     */
    static final long CAPTURE_AFTER_OFFER_MS = 10 * 60_000L;
    /** A decline the notification path requested this recently makes Dasher's decline question not the user's. */
    private static final long NOTIFICATION_DECLINE_MS = 60_000;
    /** How far back an offer's history line still takes the steps of what the user did with it. */
    private static final long STEP_WINDOW_MS = 10 * 60_000L;
    /** A declined offer or its confirmation still showing this long after the decline tap is reported as stuck. */
    static final long STUCK_MS = 5_000;
    /** How long a takeover lasts at most; offers expire well before this. */
    static final long TAKEOVER_MS = 120_000;
    /** Taps that prove delivery progress, making stored travel estimates stale. */
    private static final List<String> PROGRESS_TAPS = Arrays.asList(
            "confirm pickup", "complete pickup", "complete delivery", "confirm dropoff");

    /** The touch watch, as the main thread last left it. */
    private static final int WATCH_DOWN = 0;
    private static final int WATCH_UP = 1;
    /** Android would not add it: nothing to wait for (a decline goes on without it, as it always has). */
    private static final int WATCH_REFUSED = 2;
    /** Offer Filter's own window IDs remembered at most; more and they are forgotten (and learned again). */
    private static final int MAX_OWN_WINDOWS = 32;
    /** No time yet. */
    private static final long NEVER = Long.MIN_VALUE;

    private static volatile OfferFilterService active;
    /** For tests: reads run on this looper (the main looper, say) instead of the service's own thread. */
    static volatile Looper scanLooperForTests;
    /** For tests: runs on the scanner thread as it begins handing an offer back after a touch. */
    static volatile Runnable takeoverBeginsForTests;
    /**
     * For tests: runs right before each node is fetched from Dasher (a child or a parent, each a call into Dasher's
     * process), so a phone whose Dasher is slow to answer can be simulated.
     */
    static volatile Runnable nodeFetchForTests;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean scannerFaulted;
    private boolean restartStateLoaded;
    private HandlerThread scannerThread;
    /** The scanner thread's handler: everything below marked "scanner" is touched only there. */
    private Handler scanner;
    /** Set on the main thread when the service stops; nothing automatic starts after it. */
    private volatile boolean stopped;
    /**
     * For tests: the windows Android lists, so a slow phone can be simulated. Called on the scanner thread, and by
     * {@link #isDasherOnScreenNow} on the notification path's.
     */
    volatile Supplier<List<AccessibilityWindowInfo>> windowSource = this::getWindows;
    /** Activity boundary is replaceable in adapter tests, like the accessibility window source. */
    volatile java.util.function.Consumer<Intent> peekStarter = this::startActivity;

    // ---- Published for other threads: written only on the scanner thread, each an immutable value ----

    /** What the last look at the windows found. */
    private volatile Screen screen = Screen.UNKNOWN;
    /** The offer the user took over by touching the screen: nothing automatic happens to it again. */
    private volatile Takeover takeover = Takeover.NONE;
    /** The offer whose decline is under way (the touch watch is wanted), else null. */
    private volatile OfferSnapshot decliningOffer;
    /** Every action of the user's up to this count has been handed back; written after the takeover is published. */
    private volatile long userActionsTaken;
    /** When the first Decline tap of the decline under way was made (uptime): a click before it is not on it. */
    private volatile long declineBeganAt = Long.MAX_VALUE;
    /**
     * When Dasher was last seen in one half of a split screen, on the screen-on clock ({@link #screenOnTime}: time
     * with the screen off does not count), 0 for never.
     */
    private static volatile long dasherBesideAt;
    /**
     * How long that sighting holds while the screen is on: a moment under the shade or in recent apps does not count
     * as gone, and neither does any time with the screen off (unlocking finds Offer Filter beside Dasher still).
     */
    static final long BESIDE_MS = 20_000;
    /** When the screen went off (uptime), 0 while it is on; and how long it was off before that, all told. */
    private static volatile long screenOffSince;
    private static volatile long screenOffTotal;
    /** When Offer Filter's own screen last had a finger land (its event time, uptime): it was not on Dasher's half. */
    private static volatile long ownScreenTouchAt = NEVER;

    // ---- Handed over: set on the main thread, taken on the scanner thread ----

    /**
     * The user's touches and clicks during a decline, counted on the main thread; the scanner hands the offer back
     * before any further tap.
     */
    private final AtomicLong userActions = new AtomicLong();
    /** Whether the last of them was a click Dasher reported (else a touch), and what it was, for the log. */
    private volatile boolean userActionWasClick;
    private volatile String userActionWhat = "";
    /** {@link #WATCH_DOWN}, {@link #WATCH_UP} or {@link #WATCH_REFUSED}: written on the main thread after each change. */
    private volatile int watchState = WATCH_DOWN;
    /** How often Android would not add the touch watch: written on the main thread only. */
    private volatile int watchRefusals;
    /** A look at the windows owed to the notification path, which saw Dasher gone before the scanner did. */
    private final AtomicBoolean lookQueued = new AtomicBoolean();
    /**
     * Offer Filter's own windows, by ID: never Dasher, so their roots are never asked for (only Offer Filter's own main
     * thread can give them, and it may be busy drawing). Window IDs are not reused. Any thread may read it.
     */
    private final Set<Integer> ownWindows = ConcurrentHashMap.newKeySet();
    private final Set<Integer> otherWindows = ConcurrentHashMap.newKeySet();
    private final Set<Integer> overlayWindows = ConcurrentHashMap.newKeySet();
    private volatile AccessibilityWindowInfo lastReadableWindow;
    private int remoteFetches;
    /**
     * Dasher's events since the scanner last took them: one hand-off for any number of them, queued nowhere
     * ({@link #QUEUED_NONE}), at the back of the scanner's queue, or at its front (an event that may be an offer).
     */
    private final AtomicInteger queued = new AtomicInteger(QUEUED_NONE);
    private static final int QUEUED_NONE = 0;
    private static final int QUEUED_BACK = 1;
    private static final int QUEUED_FRONT = 2;
    /** Whether a window change (not only content changes) is among them. */
    private final AtomicBoolean changeNoted = new AtomicBoolean();
    /** The clicks among them, in order. */
    private final ConcurrentLinkedQueue<Click> clicks = new ConcurrentLinkedQueue<>();
    /** When the first of them came (uptime), for the slow-read log. */
    private volatile long eventsQueuedAt;
    /** Every event of Dasher's so far: work that is not essential stops at its next step when this changes. */
    private final AtomicLong dasherEvents = new AtomicLong();
    /** Every window change so far (not content changes or clicks): a read waiting for Dasher's question stops on it. */
    private final AtomicLong windowChanges = new AtomicLong();
    /**
     * Published by the scanner after each read: an offer or confirmation is up or a decline is under way, and until
     * when (uptime) an offer seen lately keeps Dasher's events urgent.
     */
    private volatile boolean busyPublished;
    private volatile long hotUntil = Long.MIN_VALUE / 2;
    /**
     * The app's own taps, each with its echo window: a touch during one or within {@link #OWN_ACTION_ECHO_MS} after it
     * is its echo, and so is a click on the node it tapped. Written on the scanner, read by the touch watch and by the
     * main thread's look at each click.
     */
    private final OwnTaps ownTaps = new OwnTaps();
    /** When the user's last touch during a decline landed, and how long after the app's own tap (ms, -1 none). */
    private volatile long touchSinceOwnAction = -1;
    /**
     * Whether a peek is under way, so the touch watch is up for all of it and a click on Dasher counts (written on the
     * scanner thread), and when it began (uptime): a touch or click before it is not during it.
     */
    private volatile boolean peekWatched;
    private volatile long peekBeganAt = Long.MAX_VALUE;
    /** Published immediately by the main thread, before the scanner can open an app. */
    private volatile long quietTouchAt = Long.MIN_VALUE;
    /** A return is queued for one guarded main-loop task; the touch watch stays up until that task starts it. */
    private volatile boolean peekReturning;
    /** The user's touches and clicks during peeks, counted on the main thread: any during a peek leaves Dasher up. */
    private final AtomicLong peekActions = new AtomicLong();
    /**
     * How far the peek under way has come, for the main thread (written on the scanner): while it arms, a touch only
     * means the phone is not quiet; while Dasher opens, it counts once Dasher is up.
     */
    private volatile Peek.Phase peekPhase = Peek.Phase.NONE;
    /** Cameras an app has open now, as Android's availability callback says (no permission needed). */
    private final Set<String> camerasInUse = ConcurrentHashMap.newKeySet();
    private CameraManager.AvailabilityCallback cameraWatch;

    // ---- Main thread only ----

    private TouchWatch touchWatch;
    private DasherOverlay overlay;
    /**
     * Touches in split screen not judged yet: each its time and when (uptime) it is judged the user's unless Offer
     * Filter's own screen reported it first.
     */
    private final java.util.ArrayDeque<long[]> heldTouches = new java.util.ArrayDeque<>();
    /** How many there are (written on the main thread): while any, no tap of the app's goes to the declined offer. */
    private volatile int touchesHeld;
    private final Runnable judgeTouches = this::judgeHeldTouches;
    /** The screen turning off and on: a sighting of Dasher beside does not age while it is off; a peek ends. */
    private final BroadcastReceiver screenState = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            boolean on = !Intent.ACTION_SCREEN_OFF.equals(intent.getAction());
            screenTurned(on, SystemClock.uptimeMillis());
            if (screenAwake != null) {
                if (on) screenAwake.screenOn(); else screenAwake.screenOff();
            }
            if (!stopped) scanner.postAtFrontOfQueue(() -> {
                if (!on) suspendScreenReading();
                else if (!scannerFaulted) scanNow(SystemClock.uptimeMillis(), "screen on");
            });
        }
    };
    private boolean screenStateWatched;
    private ScreenAwake screenAwake;

    /**
     * Dasher's own windows, by ID, as reads found them (written on the scanner thread, any thread may read): the log's
     * window field names Dasher's window from Android's list alone, without asking Dasher.
     */
    private final Set<Integer> dasherWindowIds = ConcurrentHashMap.newKeySet();

    // ---- Scanner thread only ----

    /** The last look's window field ({@link SplitWindows#field}), for the screen lines. */
    private String readWin = "win=unknown";
    /** Why Dasher's half beside the active app was last left unread (logged once per change), "" for none. */
    private String coverNoted = "";
    private final DeclineState declineState = new DeclineState();
    private final DeclineErrorRecovery declineError = new DeclineErrorRecovery();
    /** Published before a first-step request; a toast keeps this identity, never whatever offer is current later. */
    private volatile ErrorAttempt errorAttempt;
    private final Runnable errorRecoveryCheck = () -> {
        if (!stopped && declineError.pending()) scanNow(SystemClock.uptimeMillis(), "decline error recovery");
    };
    /** The decline under way, as far as Dasher's question goes: never a second first Decline once it was seen. */
    private final DeclineEpisode episode = new DeclineEpisode();
    /** The peek at a background offer under way, if any ({@link Peek}). */
    private final Peek peek = new Peek();
    /** When an acceptance or the user's Accept tap was last seen (Peek's clock), {@link #NEVER} for never. */
    private long lastAcceptAt = NEVER;
    /** After going back: the app gone back to, and when, while whether it is in front again is looked at. */
    private Peek.Front backCheckFront;
    private long backCheckFrom;
    /** The timing of the peek that just ended, for its "returned" line. */
    private String lastPeekTiming;
    private final AcceptedOfferTracker acceptedTracker = new AcceptedOfferTracker();
    private long ownTapAt = Long.MIN_VALUE / 2;
    /** The node the app last tapped, so its echo is known even when it comes late. */
    private AccessibilityNodeInfo ownTapTarget;
    /** The last offer read's Accept and Decline controls, and when (uptime): a tap on one names it. */
    private AccessibilityNodeInfo offerAcceptTarget;
    private AccessibilityNodeInfo offerDeclineTarget;
    private long offerTargetsAt = Long.MIN_VALUE / 2;
    /**
     * When the first read after that offer showed neither of its controls nor their labels (uptime), {@link #NEVER}
     * while it shows: a click after it is not on them.
     */
    private long offerTargetsEndedAt = NEVER;
    private long bareTapLoggedAt = Long.MIN_VALUE / 2;
    /** Which offer's aftermath the screens log is keeping, and how many of its screens it kept. */
    private long aftermathOf = -1;
    private int aftermathLines;
    private OfferSilencer silencer;
    private long recheckUntil;
    private boolean recheckPending;
    private boolean quietScanPending;
    private long quietScanEventAt;
    /** When the last read of a burst of content changes while nothing was up ended (uptime). */
    private long quietReadEndAt = Long.MIN_VALUE / 2;
    private boolean watchPending;
    /** When the windows were last looked at (uptime). */
    private long lastLookAt = Long.MIN_VALUE / 2;
    /** Whether the touch watch was last asked to run, and when it was last asked to come up (uptime). */
    private boolean touchWatchOn;
    /** The refusals of the touch watch already taken into account. */
    private int watchRefusalsSeen;
    private long watchAskedAt = Long.MIN_VALUE / 2;
    /**
     * A confirmation not tapped yet because the touch watch was not up: tapped the moment it is, or once it has been
     * asked for {@link #WATCH_HOLD_MS}, whichever comes first (its authority is checked again then).
     */
    private Scan heldConfirmation;
    /** When the first Decline tap of the offer being declined was made, and when its question was found (uptime). */
    private long firstTapAt = NEVER;
    private long confirmationFoundAt = NEVER;
    /** Until when (uptime) Dasher's question is looked for every {@link #CONFIRM_POLL_MS}; {@link #NEVER} for not. */
    private long confirmPollUntil = NEVER;
    private boolean confirmPollPending;
    /** Dasher's events as the last read began: a poll reads when they, or the windows, changed since. */
    private long lastReadEvents = -1;
    /** A read is due {@link #CONFIRM_RETRY_MS} after the last try at Dasher's question. */
    private boolean confirmRetryPending;
    /** Whether this read showed a question about declining (taken for the declined offer's or not). */
    private boolean readShowsQuestion;
    /** Whether this read left a window that might be Dasher's question unread (too many, too big, or no root). */
    private boolean questionLookIncomplete;
    /** Dasher's other windows this read listed and read, looking for the question. */
    private int otherWindowsListed;
    private int otherWindowsRead;
    /** Reads that looked for the question since the first Decline tap, and the last "not found" logged and repeats. */
    private int questionLooks;
    private String questionMissed = "";
    private int questionMissRepeats;
    /** Why the app's last tap was refused, for the log. */
    private String lastRefusal = "";
    /** Reads waiting for Dasher's question cut short in a row by a window change. */
    private int cutReads;
    /** The window changes so far as this read began, and how many nodes it reads at most. */
    private long readWindowChanges;
    private int readCap = MAX_SCAN_NODES;
    /** What the last look at the windows found, as {@link #windowsSignature}. */
    private long lastLookWindows;
    /** When a read last showed an offer or confirmation, or a decline was under way (uptime). */
    private long offerSeenAt = Long.MIN_VALUE / 2;
    /** When a read last found a readable offer, its Accept and Decline both (uptime); on the scanner. */
    private long offerReadAt = NEVER;
    /** Clicks still to be read around, after the read their events asked for. */
    private final java.util.ArrayDeque<LateClick> lateClicks = new java.util.ArrayDeque<>();
    private boolean lateClicksPending;
    /** This read's time so far in listing the windows, fetching roots and walking nodes (ns). */
    private long readWindowsNanos;
    private long readRootNanos;
    private long readTraversalNanos;
    /** When the last read ended (uptime). */
    private long lastScanEndAt = Long.MIN_VALUE / 2;
    /** What started this read, and how long it waited after that (ms), for the decline line. */
    private String readTrigger = "";
    private long readWaitedMs;
    /** Notification generation when the last decline was requested; a newer offer revokes confirmation. */
    private long declineGeneration;
    /** The global minimum scale that authorized the last first-step request. */
    private int declineMinimumScale = 100;
    /** The item floor used by the last first-step request, including when that rule was off. */
    private int declinePerItemCents;
    /** The offer whose Decline was last tapped: only its own confirmation may be tapped. */
    private OfferSnapshot declinedOffer = OfferSnapshot.UNKNOWN;
    /** Its decision-log entry, upgraded when the confirmation is tapped too. */
    private DecisionLog.Entry declinedEntry;
    private String declinedKey = "";
    private long declinedAt;
    private String authorityEndReason = "";
    private long lastReadDurationMs;
    private long currentReadStartedAt;
    private long firstTapWindows;
    private long previousCountdownAt;
    private int previousCountdown = -1;
    /** A proven fresh countdown keeps its history boundary until its first screen record. */
    private boolean newScreenInstancePending;
    private long takeoverGeneration;
    private volatile long lateCompletionUntil;
    private long lateCompletionUserActions;
    private int lateCompletionTries;
    private long takeoverDisplayUntil;
    private boolean takeoverDisplayEligible;
    private boolean declinedHadRoute;
    private boolean declinedDuringRoute;
    private OfferSnapshot boundedOffer;
    private long boundedUntil;
    private volatile ReadIdentity readIdentity;
    private Boolean rulesEnabled;
    private String evidenceWithoutControls = "";
    private String lastCompletedTrigger = "";

    private static final class ReadIdentity {
        final OfferSnapshot facts;
        final List<String> labels;
        final long at;
        ReadIdentity(OfferSnapshot facts, List<String> labels, long at) {
            this.facts = facts;
            this.labels = new ArrayList<>(labels);
            this.at = at;
        }
    }

    static boolean sameForegroundOfferNotification(List<String> labels) {
        OfferFilterService service = active;
        ReadIdentity read = service == null ? null : service.readIdentity;
        return read != null && sameForegroundNotice(labels, read.facts, read.labels,
                SystemClock.uptimeMillis() - read.at);
    }

    static boolean sameForegroundNotice(List<String> notice, OfferSnapshot facts, List<String> screen, long age) {
        OfferSnapshot seen = OfferParser.parse(notice).withoutPayBound();
        if (seen.contradicts(facts) || age < 0 || age > OfferPairing.OFFER_MS) return false;
        if (seen.payCents != null) return seen.agreesWith(facts) && age <= OfferPairing.NOTICE_LAG_MS;
        String store = OfferNotificationService.store(notice).toLowerCase(java.util.Locale.US);
        String screenStore = OfferNotificationService.store(screen).toLowerCase(java.util.Locale.US);
        // A named different merchant is positive contradiction; a screen with no merchant wording is unknown.
        if (!store.isEmpty() && !screenStore.isEmpty() && !store.equals(screenStore)) return false;
        if (!store.isEmpty()) {
            if (store.equals(screenStore)) return true;
            for (String label : screen) {
                if (OfferEvidence.normalize(label).toLowerCase(java.util.Locale.US).equals(store)) return true;
            }
        }
        return age <= OfferPairing.NOTICE_LAG_MS;
    }

    private void endAuthority(String reason, boolean reset) {
        errorAttempt = null;
        declineError.end();
        scanner.removeCallbacks(errorRecoveryCheck);
        if (declineState.confirmationUntil() != 0 && authorityEndReason.isEmpty()) {
            authorityEndReason = reason;
            confirmLog("authority ended: " + reason);
        }
        if (reset) declineState.reset(); else declineState.endConfirmation();
        scanner.removeCallbacks(declineRetry);
    }

    private void recoverLateCompletion(long now, String evidence) {
        if (lateCompletionUntil == 0 || now > lateCompletionUntil || userActions.get() != lateCompletionUserActions) {
            lateCompletionUntil = 0;
            return;
        }
        if (declinedEntry != null) {
            DecisionLog.Entry completed = declinedEntry;
            onMain(() -> DecisionLog.correctConfirmation(this, completed));
            confirmLog("Dasher completed after the not-confirmed verdict (" + evidence + "); corrected the outcome after try "
                    + lateCompletionTries);
        }
        lateCompletionUntil = 0;
    }
    /** Whether the latest read showed the declined offer or its own confirmation, so its ring may be turned down. */
    private boolean declinedOfferShowing;
    /** Whether the latest read showed an offer or a confirmation. */
    private boolean offerOnScreen;
    /**
     * Whether the latest read showed any sign of an offer: pay, a distance, time or stops, Accept or Decline even
     * without a button, a confirmation, or a screen too big to read. Its next changes are then read at once.
     */
    private boolean offerEvidence;
    private boolean quietTakenOver;
    /**
     * What the last read made of Dasher's screen, for the tab and guide over it: the guide shows only while it is
     * {@link DasherScene#WAITING}, the tab tucks away during an offer or a delivery.
     */
    private DasherScene scene = DasherScene.UNKNOWN;
    /** This read's labels once it has read a screen without finding it too big, else null. */
    private List<String> sceneLabels;
    /** Whether this read was skipped: Android did not give the active window. */
    private boolean readSkipped;
    /** The last state the overlay was given, and when (uptime). */
    private OverlayState overlayGiven;
    private long overlayGivenAt;
    /** A window transition hides old overlay controls until a read started afterward classifies the new screen. */
    private final AtomicLong overlayTransition = new AtomicLong();
    private long overlayApprovedTransition;
    /** The last declined offer already reported as stuck. */
    private String reportedStuck = "";
    /** The last offer already reported as unreadable, so repeated reads of it file nothing more. */
    private String reportedOffer = "";
    private String lastStatus = "";
    private String lastDiagnosticSignature = "";
    /** This read's cost so far: nodes visited and windows listed. */
    private int scanNodes;
    private int scanWindows;
    private long slowLoggedAt = Long.MIN_VALUE / 2;
    /** For tests: how many window roots the scanner has asked Android for. */
    int rootFetches;

    /** Re-scans while a screen is still settling or a decline confirmation is pending. */
    private final Runnable recheck = new Runnable() {
        @Override public void run() {
            recheckPending = false;
            // A read for Dasher's events is queued: it reads the newest screen, and keeps reading after it.
            if (stopped || queued.get() != QUEUED_NONE) return;
            long now = SystemClock.uptimeMillis();
            boolean more = checkOffer("recheck", now, MAX_SCAN_NODES);
            if (more && (now < recheckUntil || declineState.hasPendingConfirmation(now))) scheduleRecheck();
            watchWindows();
        }
    };
    /** The read owed to the later changes of a burst of content changes while nothing was up. */
    private final Runnable quietScan = new Runnable() {
        @Override public void run() {
            quietScanPending = false;
            if (stopped) return;
            scanNow(quietScanEventAt, "content burst");
            quietReadEndAt = lastScanEndAt;
        }
    };
    /**
     * While Dasher is on screen, a light look that it still is (no read of its content): switching apps sends no
     * event of Dasher's, and the tab over Dasher goes by what this finds. Skipped while reads are looking anyway, and
     * whenever a read Dasher's events asked for is queued. It is not essential: while an offer or confirmation is up
     * or a decline is under way it asks Dasher nothing (only Android's list of windows, and a read follows at once when
     * that list changed), and otherwise it stops at its next step when Dasher sends an event.
     */
    private final Runnable windowWatch = new Runnable() {
        @Override public void run() {
            watchPending = false;
            if (stopped || scannerFaulted) return;
            if (!phoneReadable()) { suspendScreenReading(); return; }
            long now = SystemClock.uptimeMillis();
            if (queued.get() != QUEUED_NONE) {
                watchWindows(now + WINDOW_WATCH_MS);
                return;
            }
            if (now - lastLookAt < WINDOW_WATCH_MS) {
                watchWindows();
                return;
            }
            if (busy(now)) {
                if (windowsChangedSinceLastLook()) scanNow(now, "windows changed");
                watchWindows(now + WINDOW_WATCH_MS);
                return;
            }
            lookAndPlace(true);
            watchWindows();
        }
    };
    /** The notification path saw Dasher gone before the scanner did: look now, so the tab and the snapshot follow. */
    private final Runnable lookAgain = new Runnable() {
        @Override public void run() {
            lookQueued.set(false);
            if (stopped) return;
            lookAndPlace(false);
            watchWindows();
        }
    };
    /** The touch watch came up: a confirmation held back for it is tapped now. */
    private final Runnable watchCameUp = () -> {
        if (!stopped) tapHeldConfirmation("touch watch up");
    };
    /** The touch watch is still not up {@link #WATCH_HOLD_MS} after it was asked for: the confirmation goes on. */
    private final Runnable watchHoldOver = () -> {
        if (!stopped) tapHeldConfirmation("touch watch not up after " + WATCH_HOLD_MS + " ms");
    };
    /**
     * After the first Decline tap, every {@link #CONFIRM_POLL_MS}: Dasher's question is looked for. A read that
     * Dasher's events asked for comes first and looks anyway; otherwise Android's list of windows is asked for (not
     * Dasher), and the windows are read (each at most once, and only so far) when that list, or Dasher's events,
     * changed since the last read began. A quiet window gets a bounded read once a second in case Dasher omitted
     * its event. Ends after {@link #CONFIRM_POLL_WINDOW_MS}, or once the question is tapped.
     */
    private final Runnable confirmPoll = new Runnable() {
        @Override public void run() {
            confirmPollPending = false;
            if (stopped) return;
            long now = SystemClock.uptimeMillis();
            if (!polling(now)) {
                endConfirmationPoll(now);
                return;
            }
            if (queued.get() == QUEUED_NONE
                    && (dasherEvents.get() != lastReadEvents || windowsChangedSinceLastLook()
                    || now - lastScanEndAt >= CONFIRM_QUIET_READ_MS)) {
                // Once the question is tapped, the offer closing is read again every moment, as after any read.
                if (checkOffer("confirmation poll", now, MAX_CONFIRM_NODES)) scheduleRecheck();
                watchWindows();
            }
            schedulePoll();
        }
    };
    /** {@link #CONFIRM_RETRY_MS} after a try at Dasher's question: read, so a retry (or giving up) is never late. */
    private final Runnable confirmRetry = () -> {
        confirmRetryPending = false;
        if (stopped) return;
        long now = SystemClock.uptimeMillis();
        if (!declineState.hasPendingConfirmation(now) || declineState.confirmationTries() == 0) return;
        if (checkOffer("confirmation retry", now, MAX_SCAN_NODES)) scheduleRecheck();
        watchWindows();
    };
    /** A taken first-step request stalled: read afresh before its patient, bounded retry, even without an event. */
    private final Runnable declineRetry = () -> {
        if (stopped) return;
        long now = SystemClock.uptimeMillis();
        if (!awaitingConfirmation(now) || !episode.active(now) || episode.left()) return;
        if (checkOffer("first-step retry", now, MAX_SCAN_NODES)) scheduleRecheck();
        watchWindows();
    };
    /** {@link DeclineEpisode#GLITCH_MS} after the declined offer showed again once its question was confirmed. */
    private final Runnable episodeCheck = () -> {
        if (stopped) return;
        long now = SystemClock.uptimeMillis();
        if (!episode.holding(now)) return;
        if (checkOffer("offer still showing", now, MAX_SCAN_NODES)) scheduleRecheck();
        watchWindows();
    };
    /** Clicks left for after the read their events asked for. */
    private final Runnable lateClicksRun = () -> {
        lateClicksPending = false;
        if (!stopped) readLateClicks();
    };
    /**
     * On the main thread: puts the touch watch up or down as the scanner last asked, whatever order these runs come
     * in, and publishes whether a touch would now be noticed.
     */
    private final Runnable syncWatch = new Runnable() {
        @Override public void run() {
            boolean wanted = !stopped && (decliningOffer != null || peekWatched
                    || SystemClock.uptimeMillis() < lateCompletionUntil);
            if (wanted) touchWatch.start();
            else touchWatch.stop();
            boolean up = touchWatch.isWatching();
            watchState = up ? WATCH_UP : wanted ? WATCH_REFUSED : WATCH_DOWN;
            // Android would not add it: the scanner asks again at its next read (a count, not a message, so a read
            // first in the queue sees it all the same).
            if (wanted && !up) watchRefusals++;
            if (stopped) return;
            if (up) scanner.postAtFrontOfQueue(watchCameUp);
        }
    };
    /** Takes every event noted since the last hand-off: its clicks, then one read for all of them. */
    private final Runnable queuedEvents = new Runnable() {
        @Override public void run() {
            // Before the hand-off is marked taken: the main thread sets the time only when it queues the next one.
            long at = eventsQueuedAt;
            int state = queued.getAndSet(QUEUED_NONE);
            boolean change = changeNoted.getAndSet(false);
            List<Click> taken = new ArrayList<>();
            for (Click click = clicks.poll(); click != null; click = clicks.poll()) taken.add(click);
            onEvents(change, taken, at);
            // Read after read at the front, nothing else would ever run (Android's news of Dasher's ring, the
            // timers): when events came during a read taken from the front, the next read waits for what is due
            // behind it. What waits there and can wait (the window watch, settling, clicks) sees that read queued
            // and steps aside.
            if (state == QUEUED_FRONT && queued.compareAndSet(QUEUED_FRONT, QUEUED_BACK)) {
                scanner.removeCallbacks(this);
                scanner.post(this);
            }
        }
    };
    private final Runnable syncAutomation = this::syncAutomation;
    /** A peek's deadlines, and its looks while Dasher opens. */
    private final Runnable peekTick = this::peekTick;
    /**
     * What came after an offer left alone: an unrecognised screen settling, or the minute running out. It can wait:
     * while an offer or confirmation is up, a decline is under way, or a read Dasher's events asked for is queued, it
     * comes back a moment later.
     */
    private final Runnable aftermathTick = new Runnable() {
        @Override public void run() {
            if (stopped) return;
            long now = SystemClock.uptimeMillis();
            // Timeout proves only that nothing was learned; a new offer's urgent reads must not postpone it forever.
            if (acceptedTracker.expire(now)) {
                applyNotes();
                return;
            }
            if (busy(now) || queued.get() != QUEUED_NONE) {
                scanner.postAtTime(this, now + BUSY_RETRY_MS);
                return;
            }
            noteNotificationDecline();
            acceptedTracker.tick(now, screen.dasherReadable);
            applyNotes();
        }
    };

    static boolean isConnected() {
        return active != null && !active.scannerFaulted;
    }

    /** Whether the user took over an offer these facts could belong to, so the notification path leaves it alone. */
    static boolean userHasOffer(OfferSnapshot facts) {
        OfferFilterService service = active;
        if (service == null) return false;
        // In this order, the reverse of the scanner's (takeover, then the decline ended, then the touch taken): a
        // touch the scanner has not taken yet still finds the offer under way, or else the takeover is already there.
        long taken = service.userActionsTaken;
        OfferSnapshot declining = service.decliningOffer;
        // Touched or tapped a moment ago, and the scanner has not handed the offer back yet: it is the user's already.
        // So is a touch in split screen still being judged (Offer Filter's half, or Dasher's).
        boolean acted = service.userActions.get() != taken || service.touchesHeld > 0;
        if (acted && declining != null && !facts.contradicts(declining)) return true;
        return service.takeover.covers(facts, SystemClock.uptimeMillis());
    }

    /**
     * Whether Dasher is in the other half of a split screen, as last seen within {@link #BESIDE_MS} of screen-on time:
     * the homepage then shows no map of its own, and no "Put Dasher beside" button.
     */
    static boolean dasherBeside() {
        long at = dasherBesideAt;
        return active != null && at != 0 && screenOnTime(SystemClock.uptimeMillis()) - at < BESIDE_MS;
    }

    /** Uptime less the time the screen was off: what a sighting of Dasher beside ages by. */
    static long screenOnTime(long uptime) {
        long offSince = screenOffSince;
        long off = screenOffTotal + (offSince != 0 && uptime > offSince ? uptime - offSince : 0);
        return Math.max(1, uptime - off);
    }

    /** On the main thread, as Android says the screen turned on or off. */
    static void screenTurned(boolean on, long uptime) {
        long offSince = screenOffSince;
        if (on && offSince != 0) {
            screenOffTotal += Math.max(0, uptime - offSince);
            screenOffSince = 0;
        } else if (!on && offSince == 0) {
            screenOffSince = Math.max(1, uptime);
        }
    }

    /**
     * On the main thread, as a finger lands on Offer Filter's own screen ({@link MainActivity}): in split screen, that
     * touch was not on Dasher's half, so it hands no offer back. The touch watch may report the same touch before or
     * after this.
     *
     * @param at the touch's own time (uptime), as Android stamped it
     */
    static void ownScreenTouched(long at) {
        ownScreenTouchAt = at;
        OfferFilterService service = active;
        if (service != null) service.ownTouchArrived(at);
    }

    /**
     * Offer Filter's screen came back, or went into or out of split screen: the windows are looked at now, not at
     * Dasher's next event (switching apps sends none of Dasher's), so a sighting of Dasher beside is current; then
     * {@code then} runs on the main thread. Not while an offer or confirmation is up or a decline is under way, or a
     * read is queued: reads look then anyway. Nothing before the first-run notice is accepted.
     */
    static void lookSoon(Runnable then) {
        OfferFilterService service = active;
        if (service == null || service.stopped) return;
        service.scanner.post(() -> {
            if (!service.stopped && Consent.accepted(service) && service.queued.get() == QUEUED_NONE
                    && !service.busy(SystemClock.uptimeMillis())) {
                service.lookAndPlace(true);
                service.watchWindows();
            }
            if (then != null) service.onMain(then);
        });
    }

    /**
     * Where Dasher is, for an alert's log line: the window field ({@link SplitWindows#field}) from Android's list of
     * windows now (Dasher's and Offer Filter's windows known by their IDs from earlier reads; nothing is asked of any
     * app), and whether the screen is on and locked: "win=hidden/-/system/0 screen=off locked=yes".
     */
    static String windowsNow(Context context) {
        String win = "win=unknown";
        OfferFilterService service = active;
        if (service != null) {
            try {
                List<AccessibilityWindowInfo> listed = service.windowSource.get();
                if (listed == null) listed = Collections.emptyList();
                win = SplitWindows.field(listed, service::knownOwner, service.display());
            } catch (RuntimeException unavailable) {
                win = "win=unknown";
            }
        }
        String on = "?";
        String locked = "?";
        try {
            PowerManager power = context.getSystemService(PowerManager.class);
            if (power != null) on = power.isInteractive() ? "on" : "off";
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            if (keyguard != null) locked = keyguard.isKeyguardLocked() ? "yes" : "no";
        } catch (RuntimeException unavailable) {
            // Said as unknown.
        }
        return win + " screen=" + on + " locked=" + locked;
    }

    /**
     * Whether Dasher is on screen (the active window, or its half of a split screen), as the scanner thread last saw
     * it: on every read, and every {@link #WINDOW_WATCH_MS} while Dasher is on screen. Asks Android nothing, so any
     * thread may call it at any time; it may be up to {@link #WINDOW_WATCH_MS} late in seeing Dasher go, which the
     * updater (which waits anyway) does not mind. The notification path asks {@link #isDasherOnScreenNow}.
     */
    static boolean isDasherForeground() {
        OfferFilterService service = active;
        return service != null && service.screen.dasherReadable;
    }

    /**
     * Whether Dasher is on screen now, for the notification path, which rings only while it is not. When the scanner
     * last saw Dasher gone, that holds (and rings at once). When it last saw Dasher on screen, that is confirmed on the
     * calling thread by the same look at the windows, which writes nothing: leaving Dasher sends no event of Dasher's,
     * so the last look may be up to {@link #WINDOW_WATCH_MS} old. It can only turn "on screen" into "not": a look that
     * fails counts as not on screen. When it disagrees, the scanner is asked to look too.
     */
    static boolean isDasherOnScreenNow() {
        OfferFilterService service = active;
        if (service == null || !service.screen.dasherReadable) return false;
        boolean onScreen;
        try {
            onScreen = service.knownDasherVisible(-1);
        } catch (RuntimeException unreadable) {
            onScreen = false;
        }
        if (!onScreen && !service.stopped && service.lookQueued.compareAndSet(false, true)) {
            service.scanner.post(service.lookAgain);
        }
        return onScreen;
    }

    /** Whether Dasher may be on screen: as last seen, or the service has not looked yet since it connected. */
    static boolean dasherMayBeOnScreen() {
        OfferFilterService service = active;
        if (service == null) return false;
        Screen seen = service.screen;
        return !seen.known || seen.dasherReadable;
    }

    /**
     * At the user's tap on Split with Dasher: asks Android to split the screen, as its own Split screen accessibility
     * shortcut does. Never called otherwise.
     *
     * @return whether Android took the request
     */
    static boolean splitScreen() {
        return globalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
    }

    /**
     * At the user's tap on Split with Dasher, when the phone would not split the screen for us: opens recent apps,
     * where the user starts split screen from Offer Filter's card. Never called otherwise.
     *
     * @return whether Android took the request
     */
    static boolean openRecents() {
        return globalAction(GLOBAL_ACTION_RECENTS);
    }

    private static boolean globalAction(int action) {
        OfferFilterService service = active;
        if (service == null) return false;
        try {
            return service.performGlobalAction(action);
        } catch (RuntimeException refused) {
            return false;
        }
    }

    /**
     * Asks for a fresh screen read, e.g. after a notification or a rules change. Never opens Dasher. A notification
     * may be an offer's, so the read goes ahead of anything else queued.
     */
    static void requestCheckFromNotification() {
        OfferFilterService service = active;
        if (service == null) return;
        long at = SystemClock.uptimeMillis();
        service.scanner.postAtFrontOfQueue(() -> {
            if (!service.stopped) service.scanNow(at, "check");
        });
    }

    @Override public void onCreate() {
        super.onCreate();
        Looper looper = scanLooperForTests;
        if (looper == null) {
            // Above background work, as Android's own UI threads are: a read and a decline are what the user waits on.
            scannerThread = new HandlerThread("offer-scanner", android.os.Process.THREAD_PRIORITY_FOREGROUND);
            scannerThread.start();
            looper = scannerThread.getLooper();
        }
        scanner = new Handler(looper) {
            @Override public void dispatchMessage(android.os.Message message) {
                try { super.dispatchMessage(message); }
                catch (RuntimeException error) { scannerFailed(error); }
            }
        };
        touchWatch = new TouchWatch(this, this::touched);
        overlay = new DasherOverlay(this);
        silencer = new OfferSilencer(this, scanner, this::mayQuiet);
        // Puts back any sound left turned down if the app died during a decline.
        OfferSilencer.restore(this);
        // What an older version kept of Dasher's screens is cleaned up once, off the main thread.
        DiagnosticLog.cleanUpSoon(this);
        watchBlockedStarts();
    }

    /**
     * From Android 16: Android says when it blocked one of this app's activity starts from the background, so a peek
     * whose Dasher never came up can be told from a slow one in a report.
     */
    private void watchBlockedStarts() {
        if (Build.VERSION.SDK_INT < 36) return;
        Context app = getApplicationContext();
        try {
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy())
                    .detectBlockedBackgroundActivityLaunch()
                    .penaltyListener(Runnable::run, violation -> Peek.log(app, "Android blocked the start"))
                    .build());
        } catch (RuntimeException unavailable) {
            // Only a report's detail.
        }
    }

    /** Main thread: which cameras an app has open, so a peek never comes over a camera in use. */
    private void watchCameras() {
        if (cameraWatch != null) return;
        try {
            CameraManager cameras = getSystemService(CameraManager.class);
            if (cameras == null) return;
            cameraWatch = new CameraManager.AvailabilityCallback() {
                @Override public void onCameraAvailable(String id) {
                    camerasInUse.remove(id);
                }

                @Override public void onCameraUnavailable(String id) {
                    camerasInUse.add(id);
                }
            };
            cameras.registerAvailabilityCallback(cameraWatch, main);
        } catch (RuntimeException unavailable) {
            cameraWatch = null;
        }
    }

    @Override protected void onServiceConnected() {
        scannerFaulted = false;
        restartStateLoaded = false;
        active = this;
        if (screenAwake == null) screenAwake = new ScreenAwake(this,
                () -> !stopped && screen.dasherReadable && knownDasherVisible(-1));
        screenAwake.start();
        watchScreenState();
        watchCameras();
        Updater.schedule(this);
        DiagnosticLog.log(this, "accessibility", "connected; opens Dasher by itself only to peek at a background offer"
                + " (Peek " + (FilterStore.peek(this) ? "on" : "off") + ")");
        onScanner(() -> {
            if (stopped) return;
            if (!Consent.accepted(this)) {
                // Until the first-run notice is accepted nothing of Dasher's is read. Only when an update is waiting to
                // reopen the app is it asked which app is in front (no content read), so it never opens over Dasher.
                status("Waiting for the notice in the app to be accepted; nothing is read or declined until then.");
                // The one thing posted meanwhile, once per notice version: the app is paused until it is opened.
                ConsentReminder.postIfPaused(this, "accessibility connected");
                if (Updater.relaunchPending(this)) {
                    boolean dasher;
                    try {
                        dasher = see(false, null).dasherRoot != null;
                    } catch (RuntimeException unreadable) {
                        dasher = true;
                    }
                    if (!dasher) onMain(() -> Updater.relaunchAfterUpdate(this));
                }
                return;
            }
            restoreRestartState();
            lookSafely();
            status("Accessibility connected. Only visible offer screens can be fully evaluated.");
            // Offer Filter was on screen when an update began: open it again, never over Dasher.
            boolean dasher = screen.dasherReadable;
            onMain(() -> {
                if (!dasher) Updater.relaunchAfterUpdate(this);
            });
            watchWindows();
        });
        Updater.check(this, UpdateCadence.Trigger.CONNECTED, null);
    }

    /** Consent may be accepted after connection; the first permitted read restores suppression before any tap. */
    private void restoreRestartState() {
        if (restartStateLoaded) return;
        restartStateLoaded = true;
        String failure = ScannerFailure.take(this);
        if (!failure.isEmpty()) DiagnosticLog.log(this, "accessibility",
                "screen reading reconnected after a scanner failure (" + failure + "); old taps were discarded");
        RestartSuppression.Saved saved = RestartSuppression.load(this);
        if (saved != null) {
            long at = SystemClock.uptimeMillis() - saved.ageMs;
            takeover = new Takeover(saved.offer, at);
            takeoverGeneration = OfferNotificationService.generation();
            previousCountdown = saved.countdown;
            previousCountdownAt = at;
            DiagnosticLog.log(this, "takeover", "earlier offer left to you after screen reading restarted; no tap authority restored");
        }
    }

    /**
     * Called on the main thread for each of Dasher's events: only notes it and hands it to the scanner thread. A
     * click's facts are read here, since Android recycles the event when this returns.
     */
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        // Until the first-run notice is accepted, nothing is read, decided or tapped.
        if (!Consent.accepted(this) || scannerFaulted) return;
        if (!phoneReadable()) {
            scanner.postAtFrontOfQueue(this::suspendScreenReading);
            return;
        }
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            // Toasts have no source node. Classify while Android owns the event; keep no raw message or event.
            ErrorAttempt attempt = errorAttempt;
            if (attempt != null && isDasherPackage(event.getPackageName())
                    && "android.widget.Toast".contentEquals(nonNull(event.getClassName()))
                    && event.getParcelableData() == null && event.getText().size() == 1
                    && isErrorWord(event.getText().get(0))) {
                long eventAt = event.getEventTime();
                long receivedAt = SystemClock.uptimeMillis();
                // Publish before queuing: an already-running read must see the error before it can replace this
                // request or interpret an idle screen as its successful completion. A fast toast can arrive while
                // performAction is still in flight; accepted is checked later, on the scanner.
                if (eventAt >= attempt.at && eventAt <= receivedAt
                        && receivedAt - eventAt <= DeclineErrorRecovery.ERROR_FRESH_MS
                        && attempt.errorAt.compareAndSet(-1, eventAt)) {
                    scanner.postAtFrontOfQueue(this::consumeDeclineError);
                }
            }
            return; // A toast is not a window transition, notification offer, or user click.
        }
        boolean windowsChanged = type == AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        if (windowsChanged && ownOverlayChange(event)) return;
        if (!windowsChanged && !isDasherPackage(event.getPackageName())) return;
        if (windowsChanged || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            overlayTransition.incrementAndGet();
            onMain(() -> { if (overlay != null) overlay.suspend(); });
        }
        if (screenAwake != null) screenAwake.start();
        long at = SystemClock.uptimeMillis();
        Click click = type == AccessibilityEvent.TYPE_VIEW_CLICKED ? Click.of(event, at) : null;
        // A click of the user's during a decline hands it back at once, like a touch (before any further tap); one
        // during a peek leaves Dasher up.
        if (click != null) {
            if (lateCompletionUntil != 0) lateCompletionUntil = 0;
            clickDuringDecline(click);
            clickDuringPeek(click);
        }
        boolean content = type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        boolean change = click == null && !content;
        // First: work that is not essential, under way on the scanner, stops at its next step.
        if (change) windowChanges.incrementAndGet();
        dasherEvents.incrementAndGet();
        if (onScannerThread()) {
            onEvents(change, click == null ? Collections.<Click>emptyList() : Collections.singletonList(click), at);
            return;
        }
        // Noted before the hand-off is queued, so the scanner taking the hand-off always sees it.
        if (click != null) clicks.add(click);
        else if (change) changeNoted.set(true);
        // A window change may be an offer or its confirmation, and so may anything while an offer is (or lately was)
        // up or a decline is under way: read ahead of everything else queued.
        handOff(change || busyPublished || at < hotUntil, at);
    }

    /** Our touch watch, tab and guide never change which Dasher screen is being read. */
    private boolean ownOverlayChange(AccessibilityEvent event) {
        int id = event.getWindowId();
        if (realWindowId(id) && overlayWindows.contains(id)) return true;
        try {
            List<AccessibilityWindowInfo> windows = windowSource.get();
            if (windows != null) for (AccessibilityWindowInfo window : windows) {
                if (window.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && realWindowId(window.getId())
                        && (isOwnWindow(window.getId()) || (window.getId() == id
                        && getPackageName().contentEquals(nonNull(event.getPackageName()))))) {
                    if (overlayWindows.size() >= MAX_OWN_WINDOWS) overlayWindows.clear();
                    overlayWindows.add(window.getId());
                    if (window.getId() == id) return true;
                }
            }
        } catch (RuntimeException ignored) { /* An unknown change still gets a read. */ }
        return false;
    }

    /**
     * Queues the scanner's hand-off of Dasher's events: once for any number of them (it reads the newest screen
     * anyway), at the back of its queue, or at the front when {@code urgent} (moved there if it was queued behind).
     */
    private void handOff(boolean urgent, long at) {
        while (true) {
            int state = queued.get();
            if (state == QUEUED_FRONT || (state == QUEUED_BACK && !urgent)) return;
            if (!queued.compareAndSet(state, urgent ? QUEUED_FRONT : QUEUED_BACK)) continue;
            if (state == QUEUED_NONE) eventsQueuedAt = at;
            if (urgent) {
                scanner.removeCallbacks(queuedEvents);
                scanner.postAtFrontOfQueue(queuedEvents);
            } else {
                scanner.post(queuedEvents);
            }
            return;
        }
    }

    @Override public void onInterrupt() {
        errorAttempt = null; // Revoke Back immediately, even while the scanner is inside a slow read.
        if (screenAwake != null) screenAwake.stop();
        onScanner(() -> {
            scanner.removeCallbacks(recheck);
            recheckPending = false;
            endAuthority("accessibility interrupted", true);
            acceptedTracker.reset();
            scanner.removeCallbacks(aftermathTick);
            syncAutomation();
            status("Accessibility interrupted; waiting for a new readable offer.");
        });
    }

    @Override public boolean onUnbind(Intent intent) {
        stop();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        stop();
        super.onDestroy();
    }

    /** For tests: Dasher was just seen in the other half of a split screen, or (0) never. */
    static void sawDasherBeside(long uptime) {
        dasherBesideAt = uptime == 0 ? 0 : screenOnTime(uptime);
    }

    /** For tests: the screen on, and no time off counted. */
    static void forgetScreenState() {
        screenOffSince = 0;
        screenOffTotal = 0;
        ownScreenTouchAt = NEVER;
    }

    /** For tests: the filter tab over Dasher. */
    DasherOverlay overlay() {
        return overlay;
    }

    /** For tests: the looper reads run on. */
    Looper scanLooper() {
        return scanner.getLooper();
    }

    /**
     * On the main thread: whether the screen is on now, and its turning off and on from here, so a sighting of Dasher
     * beside does not age while the screen is off.
     */
    private void watchScreenState() {
        if (screenStateWatched) return;
        long now = SystemClock.uptimeMillis();
        try {
            PowerManager power = getSystemService(PowerManager.class);
            screenTurned(power == null || power.isInteractive(), now);
        } catch (RuntimeException unknown) {
            screenTurned(true, now);
        }
        IntentFilter turns = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        turns.addAction(Intent.ACTION_SCREEN_ON);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenState, turns, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenState, turns);
            screenStateWatched = true;
        } catch (RuntimeException refused) {
            DiagnosticLog.log(this, "split", "screen on/off not watched: " + refused.getClass().getSimpleName());
        }
    }

    /** On the main thread: everything of ours leaves the screen, the sound comes back, and the scanner ends. */
    private void stop() {
        boolean first = !stopped;
        stopped = true;
        if (screenAwake != null) screenAwake.stop();
        if (active == this) active = null;
        if (screenStateWatched) {
            screenStateWatched = false;
            try {
                unregisterReceiver(screenState);
            } catch (RuntimeException alreadyGone) {
                // Gone with the service.
            }
        }
        if (overlay != null) overlay.hide();
        if (touchWatch != null) touchWatch.stop();
        if (cameraWatch != null) {
            try {
                getSystemService(CameraManager.class).unregisterAvailabilityCallback(cameraWatch);
            } catch (RuntimeException alreadyGone) {
                // Gone with the service.
            }
            cameraWatch = null;
        }
        main.removeCallbacks(judgeTouches);
        heldTouches.clear();
        touchesHeld = 0;
        watchState = WATCH_DOWN;
        // Put back at once, whatever the scanner is doing; it puts back again as it finishes, and turns nothing down
        // again (the silencer checks the stop under its lock).
        OfferSilencer.restore(this);
        if (!first || scanner == null) return;
        scanner.removeCallbacksAndMessages(null);
        onScanner(() -> {
            endAuthority("service stopped", true);
            acceptedTracker.reset();
            peekOver("ended because screen reading stopped");
            forgetBackCheck();
            syncAutomation();
        });
        if (scannerThread != null) scannerThread.quitSafely();
    }

    private boolean onScannerThread() {
        return Looper.myLooper() == scanner.getLooper();
    }

    /** Runs on the scanner thread: at once when already there, else queued in order. */
    private void onScanner(Runnable work) {
        if (onScannerThread()) {
            try { work.run(); } catch (RuntimeException error) { scannerFailed(error); }
        }
        else scanner.post(work);
    }

    /** Fail closed if a callback outside the normal read guard throws; the looper remains alive for a reconnect. */
    private void scannerFailed(RuntimeException error) {
        if (scannerFaulted) return;
        scannerFaulted = true;
        errorAttempt = null;
        declineError.end();
        scanner.removeCallbacksAndMessages(null);
        queued.set(QUEUED_NONE);
        clicks.clear();
        declineState.reset();
        episode.end();
        acceptedTracker.reset();
        heldConfirmation = null;
        decliningOffer = null;
        declinedOfferShowing = false;
        busyPublished = false;
        peekWatched = false;
        recheckPending = false;
        watchPending = false;
        quietScanPending = false;
        confirmRetryPending = false;
        confirmPollPending = false;
        confirmPollUntil = NEVER;
        peekReturning = false;
        try { peek.end(false, false, Peek.now()); peek.forgetFront(); } catch (RuntimeException ignored) { }
        peekPhase = Peek.Phase.NONE;
        screen = Screen.NOT_SHOWN;
        try { ScannerFailure.remember(this, error); } catch (RuntimeException ignored) { /* Still stop every tap. */ }
        try { DiagnosticLog.log(this, "accessibility", "scanner stopped: " + error.getClass().getSimpleName()); }
        catch (RuntimeException ignored) { /* Failure reporting cannot restart automation. */ }
        try { silencer.stop(); } catch (RuntimeException ignored) { /* Restore again on main. */ }
        onMain(() -> {
            try { OfferSilencer.restore(this); } catch (RuntimeException ignored) { }
            try { if (screenAwake != null) screenAwake.stop(); } catch (RuntimeException ignored) { }
            try { if (touchWatch != null) touchWatch.stop(); } catch (RuntimeException ignored) { }
            try { if (overlay != null) overlay.hide(); } catch (RuntimeException ignored) { }
            try {
                FilterStore.setLastStatus(this, "Screen reading stopped after an error. Turn screen reading off and on to reconnect.");
                Toast.makeText(this, "Screen reading stopped; reconnect it in " + AppName.NAME, Toast.LENGTH_LONG).show();
            } catch (RuntimeException ignored) { }
        });
    }

    /** Power/keyguard metadata only; no windows or app roots while the display is off or locked. */
    private boolean phoneReadable() {
        try {
            PowerManager power = getSystemService(PowerManager.class);
            KeyguardManager keyguard = getSystemService(KeyguardManager.class);
            return (power == null || power.isInteractive()) && (keyguard == null || !keyguard.isKeyguardLocked());
        } catch (RuntimeException unavailable) { return false; }
    }

    private void suspendScreenReading() {
        long now = SystemClock.uptimeMillis();
        if (declineState.hasPendingConfirmation(now) || episode.active(now)) {
            handBack(HandBack.SCREEN_OFF, "screen off or locked", now);
        }
        scanner.removeCallbacks(windowWatch);
        watchPending = false;
        scanner.removeCallbacks(recheck);
        recheckPending = false;
        scanner.removeCallbacks(quietScan);
        quietScanPending = false;
        scanner.removeCallbacks(confirmRetry);
        confirmRetryPending = false;
        scanner.removeCallbacks(watchHoldOver);
        scanner.removeCallbacks(episodeCheck);
        scanner.removeCallbacks(aftermathTick);
        heldConfirmation = null;
        endAuthority("screen off", true);
        episode.end();
        endConfirmationPoll(SystemClock.uptimeMillis());
        peekOver("ended because the screen turned off");
        screen = Screen.NOT_SHOWN;
        syncAutomation();
        syncOverlay();
    }

    /** Runs on the main thread: at once when already there, else queued in order. */
    private void onMain(Runnable work) {
        if (Looper.myLooper() == Looper.getMainLooper()) work.run();
        else main.post(work);
    }

    /** Runs on the main thread: at once when already there, else ahead of everything queued there. */
    private void onMainFirst(Runnable work) {
        if (Looper.myLooper() == Looper.getMainLooper()) work.run();
        else main.postAtFrontOfQueue(work);
    }

    // ---- Scheduling (scanner thread) ----

    /**
     * Dasher's events, handed over as one: the clicks among them, then one read. A click while an offer or
     * confirmation is up or a decline is under way is named at once by what its event says and by the last offer
     * read's controls (no reading around it); any other click is read around after the read. The read is at once,
     * except a content change while nothing is up that comes within {@link #QUIET_SCAN_GAP_MS} of the last such read:
     * it waits for one read at the end of that gap, which takes in every change until then.
     *
     * @param change whether a window change is among them
     */
    private void onEvents(boolean change, List<Click> taken, long at) {
        if (stopped) return;
        long now = SystemClock.uptimeMillis();
        for (Click click : taken) {
            if (busy(now)) observeClick(click, false);
            else lateClicks.addLast(new LateClick(click));
        }
        boolean content = !change && taken.isEmpty();
        if (content && quiet(now)) {
            if (quietScanPending) return;
            long due = quietReadEndAt + QUIET_SCAN_GAP_MS;
            if (now < due) {
                quietScanPending = true;
                quietScanEventAt = at;
                scanner.postAtTime(quietScan, due);
                return;
            }
            // The first change after a quiet spell: read at once, an offer drawn by it included.
            scanNow(at, "content");
            quietReadEndAt = lastScanEndAt;
            return;
        }
        scanNow(at, change ? "change" : !taken.isEmpty() ? "click" : "content");
        readLateClicks();
    }

    /**
     * Nothing is up: the last read showed no sign of an offer or confirmation, and no decline or settling screen is
     * pending.
     */
    private boolean quiet(long now) {
        return (!offerEvidence || quietTakenOver) && !recheckPending && !declineState.hasPendingConfirmation(now);
    }

    /**
     * An offer or confirmation is up (the last read showed one, or any of an offer's facts or controls), or a decline
     * is under way.
     */
    private boolean busy(long now) {
        return (!quietTakenOver && (offerOnScreen || offerEvidence)) || declineState.hasPendingConfirmation(now);
    }

    /** {@link #busy}, or an offer or confirmation was seen within {@link #HOT_MS}. */
    private boolean hot(long now) {
        return busy(now) || now - offerSeenAt < HOT_MS;
    }

    /** Reads now, and keeps reading while the screen settles. */
    private void scanNow(long eventAt, String trigger) {
        scanner.removeCallbacks(quietScan);
        quietScanPending = false;
        scanner.removeCallbacks(recheck);
        recheckPending = false;
        recheckUntil = SystemClock.uptimeMillis() + RECHECK_WINDOW_MS;
        if (checkOffer(trigger, eventAt, MAX_SCAN_NODES)) scheduleRecheck();
        watchWindows();
    }

    /**
     * Reads again {@link #RECHECK_INTERVAL_MS} from now. Not while Dasher's question is polled for after the first
     * Decline tap: the poll reads instead, and only what changed.
     */
    private void scheduleRecheck() {
        if (recheckPending || polling(SystemClock.uptimeMillis())) return;
        recheckPending = true;
        scanner.postDelayed(recheck, RECHECK_INTERVAL_MS);
    }

    /** Keeps looking at the windows while Dasher is on screen: {@link #WINDOW_WATCH_MS} after the last look. */
    private void watchWindows() {
        watchWindows(SystemClock.uptimeMillis());
    }

    /** As {@link #watchWindows()}, not before {@code notBefore} (uptime). */
    private void watchWindows(long notBefore) {
        if (watchPending || stopped || scannerFaulted || !phoneReadable() || active != this || screen.area == null) return;
        watchPending = true;
        scanner.postAtTime(windowWatch, Math.max(lastLookAt + WINDOW_WATCH_MS, notBefore));
    }

    /**
     * A look at the windows, then the tab and guide placed by it. When {@code yielding}, it is cut short at Dasher's
     * next event (whose read looks anyway), and then places nothing.
     */
    private void lookAndPlace(boolean yielding) {
        BooleanSupplier stop = null;
        if (yielding) {
            long events = dasherEvents.get();
            stop = () -> dasherEvents.get() != events;
        }
        try {
            if (look(stop) == null) return;
        } catch (RuntimeException unreadable) {
            if (!Screen.NOT_SHOWN.equals(screen)) screen = Screen.NOT_SHOWN;
        }
        syncOverlay();
    }

    /**
     * Whether Android's list of windows differs from what the last look found (another app in front, the shade, a
     * dialog of Dasher's). It asks Android only, never Dasher; a list that cannot be had counts as changed.
     */
    private boolean windowsChangedSinceLastLook() {
        try {
            List<AccessibilityWindowInfo> listed = windowSource.get();
            return windowsSignature(listed == null ? Collections.<AccessibilityWindowInfo>emptyList() : listed)
                    != lastLookWindows;
        } catch (RuntimeException unreadable) {
            return true;
        }
    }

    /**
     * A fingerprint of the windows that matter to a read (apps' windows, the split divider, system surfaces), from
     * what Android listed: their kinds, IDs, layers, which is active and where they are. Offer Filter's own are left
     * out, and so are overlays (ours, the touch watch).
     */
    private long windowsSignature(List<AccessibilityWindowInfo> listed) {
        long signature = 17;
        Rect bounds = new Rect();
        for (AccessibilityWindowInfo window : listed) {
            int type = window.getType();
            if (type != AccessibilityWindowInfo.TYPE_APPLICATION && type != AccessibilityWindowInfo.TYPE_SYSTEM
                    && type != AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) {
                continue;
            }
            if (isOwnWindow(window.getId())) continue;
            window.getBoundsInScreen(bounds);
            signature = signature * 31 + java.util.Objects.hash(type, window.getId(), window.getLayer(),
                    window.isActive(), bounds.left, bounds.top, bounds.right, bounds.bottom);
        }
        return signature;
    }

    // ---- Dasher's question after the first Decline tap (scanner thread) ----

    /** A decline of ours is under way and its question has not been tapped yet. */
    private boolean awaitingConfirmation(long now) {
        return declineState.hasPendingConfirmation(now) && !declineState.confirmationTapped();
    }

    /** Whether the question is being polled for. */
    private boolean polling(long now) {
        return confirmPollUntil != NEVER && now < confirmPollUntil && awaitingConfirmation(now);
    }

    /** From a Decline tap: the question is looked for every {@link #CONFIRM_POLL_MS}, instead of a full re-read. */
    private void startConfirmationPoll(long tappedAt) {
        confirmPollUntil = tappedAt + CONFIRM_POLL_WINDOW_MS;
        scanner.removeCallbacks(recheck);
        recheckPending = false;
        schedulePoll();
    }

    private void schedulePoll() {
        if (confirmPollPending || stopped || confirmPollUntil == NEVER) return;
        confirmPollPending = true;
        long now = SystemClock.uptimeMillis();
        scanner.postAtTime(confirmPoll, Math.min(now + CONFIRM_POLL_MS, Math.max(now, confirmPollUntil)));
    }

    /** The poll is over: a decline still waiting for its question goes back to being read again every moment. */
    private void endConfirmationPoll(long now) {
        confirmPollUntil = NEVER;
        scanner.removeCallbacks(confirmPoll);
        confirmPollPending = false;
        if (awaitingConfirmation(now)) scheduleRecheck();
    }

    /** A confirmation held back for the touch watch: tapped now, its authority checked again. */
    private void tapHeldConfirmation(String why) {
        Scan held = heldConfirmation;
        if (held == null) return;
        heldConfirmation = null;
        scanner.removeCallbacks(watchHoldOver);
        readTrigger = why;
        boolean more = handleConfirmation(held, FilterStore.load(this), SystemClock.uptimeMillis());
        syncAutomation();
        if (more) scheduleRecheck();
    }

    /**
     * {@link #look}, where no read is under way to report a failure: a look that fails counts as Dasher not on screen
     * (as it did before reads had a thread of their own), so nothing of ours stays over it.
     */
    private void lookSafely() {
        try {
            look();
        } catch (RuntimeException unreadable) {
            if (!Screen.NOT_SHOWN.equals(screen)) screen = Screen.NOT_SHOWN;
        }
    }

    /**
     * The filter tab over Dasher follows Dasher on and off the screen, and into its half of a split screen. The tab
     * and guide are views, so the main thread places them; they are given a new state when it changes, and at least
     * every {@link #OVERLAY_CHECK_MS} so the tab and guide stay current.
     */
    private void syncOverlay() {
        long transition = overlayTransition.get();
        Screen seen = screen;
        Rect area = active == this ? seen.area : null;
        // Positive window metadata is enough to remove a gone overlay; showing controls requires fresh labels.
        if (area != null && overlayApprovedTransition != transition) return;
        OverlayState next = new OverlayState(area, area != null && seen.split, scene);
        long now = SystemClock.uptimeMillis();
        if (next.equals(overlayGiven) && now - overlayGivenAt < OVERLAY_CHECK_MS) return;
        overlayGiven = next;
        overlayGivenAt = now;
        onMain(() -> {
            if (!stopped && !scannerFaulted && overlay != null && overlayTransition.get() == transition) {
                overlay.sync(next.area, next.split, next.scene);
            }
        });
    }

    // ---- What the user does (click facts arrive as values; touches as a flag) ----

    /**
     * A click Dasher reported: the user's Accept or Decline, Offer Filter's own tap coming back, or something else.
     * Each near an offer goes in the screens log with its shape only, so a report shows whether Dasher reports
     * the user's taps at all. A click may expose a card code without its page heading; none of its raw labels is kept.
     *
     * @param walk whether the nodes around the click may be read (nothing is up); without it, only what the event
     *     and its node say, and whether the node is the last offer's control
     * @return false when that reading was cut short by Dasher's next event: nothing was done, try again later
     */
    private boolean observeClick(Click click, boolean walk) {
        long now = click.at;
        ClickEvidence tap = readClick(click, now, walk);
        if (tap == null) return false;
        boolean near = now - offerTargetsAt <= MANUAL_DECLINE_OFFER_AGE_MS;
        if (tap.own || near) {
            // Shape only: a tap can expose a payment field without the page marker needed to identify it.
            DiagnosticLog.logScreen(this, () -> "tap ("
                    + (tap.own ? AppName.NAME + "'s own" : "not " + AppName.NAME + "'s")
                    + ") " + tap.describe(false));
        } else if (now - bareTapLoggedAt >= BARE_TAP_LOG_MS) {
            bareTapLoggedAt = now;
            DiagnosticLog.logScreen(this, () -> "tap (not " + AppName.NAME + "'s) " + tap.describe(false));
        }
        // A tap within a second of Dasher appearing in a peek may have been meant for the app the user was in: it
        // teaches nothing (it still blocks peeks for a minute, as any Accept does).
        boolean early = (tap.accept() || tap.decline()) && peek.earlyTap(elapsedOf(now));
        if (tap.accept()) lastAcceptAt = Peek.now();
        if (early) {
            Peek.log(this, "your " + (tap.accept() ? "Accept" : "Decline") + " tap within "
                    + Peek.EARLY_TAP_MS / 1000 + " s of Dasher appearing teaches nothing");
        } else if (tap.accept()) {
            OfferSnapshot accepting = acceptedTracker.acceptClicked(now);
            // Each step toward learning from an accepted offer goes in the log, so a report shows where it stops.
            DiagnosticLog.log(this, "accept", accepting != null
                    ? "Accept tap seen on " + accepting.summary() + "; waiting up to 15 s for a delivery screen"
                    : "Accept tap seen, but no offer with readable pay was on screen in the last 90 s: nothing to learn");
        } else if (tap.decline()) {
            // The user's own Decline: held until Dasher moves on (the wait for offers, or another offer), then it may
            // teach that the rules were too lenient; going back to the offer or accepting it counts nothing.
            acceptedTracker.declineTapped(now);
        } else if (tap.endDash()) {
            // The user's "End dash" on Dasher's "End your current dash?": the dash ends once its screen goes away.
            acceptedTracker.endDashTapped(now);
        } else if (!tap.own && near && (offerTargetsEndedAt == NEVER || now < offerTargetsEndedAt)) {
            acceptedTracker.tapNotRecognized(now);
        }
        if (!tap.own && progressTap(tap)) ActiveRouteStore.invalidateTravel(this);
        applyNotes();
        return true;
    }

    /**
     * Reads around the clicks left for after their read, oldest first, while no read of Dasher's events is waiting
     * and nothing is up. A reading cut short by Dasher's next event is tried again after that event's read, a few
     * times within {@link #CLICK_READ_WAIT_MS}; after that, or once an offer is up, the click is named without it.
     */
    private void readLateClicks() {
        while (!lateClicks.isEmpty() && !stopped) {
            long now = SystemClock.uptimeMillis();
            LateClick late = lateClicks.peekFirst();
            boolean mayWalk = late.tries < CLICK_READ_TRIES && now - late.click.at < CLICK_READ_WAIT_MS;
            if (mayWalk && queued.get() != QUEUED_NONE) {
                postLateClicks();
                return;
            }
            lateClicks.pollFirst();
            boolean walk = mayWalk && !busy(now);
            late.tries++;
            if (!observeClick(late.click, walk)) {
                lateClicks.addFirst(late);
                postLateClicks();
                return;
            }
        }
    }

    /** At the back of the scanner's queue: after the reads Dasher's events asked for. */
    private void postLateClicks() {
        if (lateClicksPending || stopped) return;
        lateClicksPending = true;
        scanner.post(lateClicksRun);
    }

    /** A tap on a pickup or delivery step: the stored route's travel is stale. */
    private static boolean progressTap(ClickEvidence tap) {
        List<String> labels = new ArrayList<>(tap.eventText);
        if (!tap.above.isEmpty()) labels.add(tap.above.get(0));
        labels.addAll(tap.below);
        for (String label : labels) {
            if (PROGRESS_TAPS.contains(OfferEvidence.normalize(label).toLowerCase(Locale.US))) return true;
        }
        return false;
    }

    /**
     * What a click says, read on the scanner thread from the facts taken as it came: the labels on its node, on up to
     * three nodes above it (where an Android View's button text is) and on a few below it (where Jetpack Compose puts
     * a button's label), and whether the node is the Accept or Decline control the last offer read found. A click on
     * the node Offer Filter tapped moments ago is that tap's echo; nothing around it is read then. Without {@code walk}
     * (an offer is up) nothing around it is read either: each node is a call into Dasher, whose UI thread is drawing
     * the offer.
     *
     * @return null when reading around it was cut short by Dasher's next event
     */
    private ClickEvidence readClick(Click click, long now, boolean walk) {
        AccessibilityNodeInfo source = click.source;
        // The node the app tapped, or (with no node) a time in one of its taps' windows; an echo comes after the tap,
        // so a click from before it (read around later than the tap) is not its echo.
        boolean own = ownTaps.clickEcho(source, now, OfferFilterService::sameNodes) != null;
        // The last offer's controls name a click only until a read found that offer gone: a later click on a node
        // with the same identity (Dasher may reuse it on its next screen) is not on that offer.
        boolean fresh = now - offerTargetsAt <= MANUAL_DECLINE_OFFER_AGE_MS
                && (offerTargetsEndedAt == NEVER || now < offerTargetsEndedAt);
        boolean isAccept = fresh && source != null && offerAcceptTarget != null && sameNode(source, offerAcceptTarget);
        boolean isDecline = fresh && source != null && offerDeclineTarget != null
                && sameNode(source, offerDeclineTarget);
        List<String> above = new ArrayList<>();
        List<String> below = new ArrayList<>();
        String sourceClass = "";
        try {
            if (source != null) {
                CharSequence name = source.getClassName();
                if (name != null) {
                    String full = name.toString();
                    sourceClass = full.substring(full.lastIndexOf('.') + 1);
                }
            }
            // Offer Filter's own tap coming back costs no further calls into Dasher, and nor does a click while an
            // offer is up: the node's own labels are already here.
            AccessibilityNodeInfo node = own ? null : source;
            long events = dasherEvents.get();
            BooleanSupplier stop = () -> dasherEvents.get() != events;
            for (int depth = 0; depth < MAX_ACCEPT_LABEL_ANCESTORS && node != null; depth++) {
                addTapLabel(above, node.getText());
                addTapLabel(above, node.getContentDescription());
                if (!walk || depth + 1 >= MAX_ACCEPT_LABEL_ANCESTORS) break;
                if (stop.getAsBoolean()) return null;
                beforeNodeFetch();
                node = node.getParent();
            }
            if (source != null && !own && walk && !readBelow(source, below, stop)) return null;
        } catch (RuntimeException unreadable) {
            // What was read before the node went away is kept.
        }
        return new ClickEvidence(click.text, click.description, source != null, sourceClass, isAccept, isDecline,
                own, ownTapTarget == null ? -1 : Math.max(0, now - ownTapAt), above, below);
    }

    /**
     * The labels of the visible nodes below {@code source}, breadth first, a few levels and nodes at most.
     *
     * @return false when {@code stop} said to stop before a node was fetched
     */
    private static boolean readBelow(AccessibilityNodeInfo source, List<String> labels, BooleanSupplier stop) {
        List<AccessibilityNodeInfo> level = Collections.singletonList(source);
        int visited = 0;
        for (int depth = 0; depth < MAX_TAP_SUBTREE_DEPTH && !level.isEmpty(); depth++) {
            List<AccessibilityNodeInfo> next = new ArrayList<>();
            for (AccessibilityNodeInfo parent : level) {
                for (int i = 0; i < parent.getChildCount() && visited < MAX_TAP_SUBTREE_NODES; i++) {
                    if (stop.getAsBoolean()) return false;
                    beforeNodeFetch();
                    AccessibilityNodeInfo child = parent.getChild(i);
                    if (child == null) continue;
                    visited++;
                    if (!child.isVisibleToUser()) continue;
                    addTapLabel(labels, child.getText());
                    addTapLabel(labels, child.getContentDescription());
                    next.add(child);
                }
            }
            level = next;
        }
        return true;
    }

    private static void addTapLabel(List<String> labels, CharSequence value) {
        if (value == null) return;
        String label = OfferEvidence.normalize(value.toString());
        if (!label.isEmpty() && !labels.contains(label)) labels.add(label);
    }

    private static void beforeNodeFetch() {
        Runnable hook = nodeFetchForTests;
        if (hook != null) hook.run();
    }

    /** {@link #sameNode} for {@link OwnTaps}, which holds the nodes as opaque values. */
    private static boolean sameNodes(Object a, Object b) {
        return a instanceof AccessibilityNodeInfo && b instanceof AccessibilityNodeInfo
                && sameNode((AccessibilityNodeInfo) a, (AccessibilityNodeInfo) b);
    }

    /** Whether two nodes are the same node of the same window, as Android identifies them. */
    private static boolean sameNode(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
        try {
            return a == b || a.equals(b);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * On the main thread, as the touch watch reports a touch: a decline's ({@link #touchedDuringDecline}), a peek's
     * ({@link #touchedDuringPeek}), or both.
     *
     * @param touchAt when the touch landed (uptime), as Android stamped it
     */
    private void touched(long touchAt) {
        boolean peeking = peekWatched;
        // Without a peek, every touch the watch reports is a decline's, as it always was.
        if (!peeking || decliningOffer != null) touchedDuringDecline(touchAt);
        if (peeking) touchedDuringPeek(touchAt);
    }

    /**
     * On the main thread: a touch during a peek, anywhere (the app the user was in, Dasher, a notification), that is not
     * the echo of one of the app's own taps. Dasher stays up: the peek never goes back after anything of the user's.
     * Counted first, so a peek about to go back on the scanner thread sees it.
     */
    private void touchedDuringPeek(long touchAt) {
        if (stopped) return;
        Peek.Phase phase = peekPhase;
        if (phase == Peek.Phase.ARMING) {
            // Dasher is not open yet: the user is using the phone, and the quiet starts again.
            long at = elapsedOf(touchAt);
            quietTouchAt = at;
            scanner.post(() -> peek.touchedWhileArming(at));
            return;
        }
        if (touchAt < peekBeganAt || ownTaps.touchEcho(touchAt) != null) return;
        peekActions.incrementAndGet();
        // While Dasher opens, it counts once Dasher is up (and not at all if Dasher never comes up).
        if (phase != Peek.Phase.OPENING) {
            scanner.postAtFrontOfQueue(() -> peekOver("left Dasher up because you touched the screen"));
        }
    }

    /**
     * On the main thread, as Dasher reports a click during a peek: one that is not the echo of one of the app's own
     * taps is the user's, and Dasher stays up. Only what the event already holds is looked at.
     */
    private void clickDuringPeek(Click click) {
        Peek.Phase phase = peekPhase;
        if (stopped || !peekWatched || phase == Peek.Phase.ARMING || click.at < peekBeganAt) return;
        if (ownTaps.clickEcho(click.source, click.at, OfferFilterService::sameNodes) != null) return;
        peekActions.incrementAndGet();
        if (phase != Peek.Phase.OPENING) {
            scanner.postAtFrontOfQueue(() -> peekOver("left Dasher up because you tapped Dasher"));
        }
    }

    /** An uptime (a touch's or a click's own time) on Peek's elapsed clock. */
    private static long elapsedOf(long uptime) {
        return Peek.now() - Math.max(0, SystemClock.uptimeMillis() - uptime);
    }

    /**
     * On the main thread, at the touch itself: the offer is the user's from this moment. The scanner hands it back
     * before any further tap, even in the middle of a read; the watch is removed after this touch is delivered. A touch
     * during one of the app's own taps, or within {@link #OWN_ACTION_ECHO_MS} after that tap returned, is its echo
     * (Android can report the app's own action as a touch outside the watch): it is logged and changes nothing. Each
     * tap has a window of its own, and a later tap never stretches an earlier one's.
     *
     * <p>In split screen with the bounds of Dasher's half known (the user's decision), only a touch on Dasher's half
     * hands the offer back: one that Offer Filter's own screen reported at the same time was on Offer Filter's half, and
     * changes nothing. The watch and Offer Filter's screen are told of a touch in either order, so a touch Offer
     * Filter's screen has not reported yet is held: no tap of the app's goes to the declined offer until it is judged,
     * at that report (Offer Filter's half) or {@link #OWN_HALF_WAIT_MS} after it landed (the user's). With Dasher full
     * screen, or the bounds of its half unknown, every touch is the user's, as before.
     *
     * @param touchAt when the touch landed (uptime), as Android stamped it
     */
    private void touchedDuringDecline(long touchAt) {
        OwnTaps.Tap echo = ownTaps.touchEcho(touchAt);
        if (echo != null) {
            DiagnosticLog.log(this, "accessibility", "touch ignored: own-action echo, " + (touchAt - echo.began)
                    + " ms after " + AppName.NAME + "'s tap");
            return;
        }
        Screen seen = screen;
        if (seen.split && seen.bounded) {
            if (sameTouch(ownScreenTouchAt, touchAt)) {
                touchOnOwnHalf(touchAt);
                return;
            }
            long due = SystemClock.uptimeMillis() + OWN_HALF_WAIT_MS;
            heldTouches.addLast(new long[] {touchAt, due});
            touchesHeld = heldTouches.size();
            if (heldTouches.size() == 1) main.postAtTime(judgeTouches, due);
            return;
        }
        usersTouch(touchAt, "");
    }

    /** The touch is the user's: the scanner hands the offer back before any further tap (main thread). */
    private void usersTouch(long touchAt, String where) {
        lateCompletionUntil = 0;
        OwnTaps.Tap last = ownTaps.last();
        long since = last != null && touchAt >= last.began ? touchAt - last.began : -1;
        touchSinceOwnAction = since;
        DiagnosticLog.log(this, "accessibility", "touch during decline: the user's" + where
                + (since >= 0 ? ", " + since + " ms after " + AppName.NAME + "'s last tap" : ""));
        userActionWasClick = false;
        userActionWhat = "";
        userActions.incrementAndGet();
        scanner.postAtFrontOfQueue(this::takeOverIfTouched);
    }

    /** Whether two reports of a touch (the watch's and Offer Filter's screen's) are of the same touch. */
    private static boolean sameTouch(long a, long b) {
        return a != NEVER && b != NEVER && Math.abs(a - b) <= SAME_TOUCH_MS;
    }

    /** A touch in split screen on Offer Filter's own half: not on Dasher, so the decline goes on (main thread). */
    private void touchOnOwnHalf(long touchAt) {
        OwnTaps.Tap last = ownTaps.last();
        long since = last != null && touchAt >= last.began ? touchAt - last.began : -1;
        DiagnosticLog.log(this, "accessibility", "touch on " + AppName.NAME + "'s half of the split screen, not "
                + "Dasher's: the decline goes on" + (since >= 0 ? " (" + since + " ms after " + AppName.NAME
                + "'s last tap)" : ""));
    }

    /** Offer Filter's own screen had a finger land: a touch held for it was on Offer Filter's half (main thread). */
    private void ownTouchArrived(long at) {
        boolean released = false;
        java.util.Iterator<long[]> held = heldTouches.iterator();
        while (held.hasNext()) {
            long[] touch = held.next();
            if (!sameTouch(touch[0], at)) continue;
            held.remove();
            touchOnOwnHalf(touch[0]);
            released = true;
        }
        if (!released) return;
        touchesHeld = heldTouches.size();
        main.removeCallbacks(judgeTouches);
        if (!heldTouches.isEmpty()) main.postAtTime(judgeTouches, heldTouches.peekFirst()[1]);
        if (!stopped) scanner.postAtFrontOfQueue(this::touchesJudged);
    }

    /**
     * {@link #OWN_HALF_WAIT_MS} after a touch in split screen that Offer Filter's screen never reported: the user's, on
     * Dasher's half (main thread). Counted before it stops being held, so no tap of the app's slips in between.
     */
    private void judgeHeldTouches() {
        long now = SystemClock.uptimeMillis();
        boolean released = false;
        while (!heldTouches.isEmpty() && heldTouches.peekFirst()[1] <= now) {
            long[] touch = heldTouches.pollFirst();
            if (sameTouch(ownScreenTouchAt, touch[0])) {
                touchOnOwnHalf(touch[0]);
                released = true;
            } else {
                usersTouch(touch[0], " (split screen, not on " + AppName.NAME + "'s half)");
            }
        }
        touchesHeld = heldTouches.size();
        if (!heldTouches.isEmpty()) main.postAtTime(judgeTouches, heldTouches.peekFirst()[1]);
        if (released && !stopped) scanner.postAtFrontOfQueue(this::touchesJudged);
    }

    /**
     * Scanner thread: a touch that held back the app's taps was on Offer Filter's half. A confirmation held for it is
     * tapped now, its authority checked again; otherwise the declined offer is read again.
     */
    private void touchesJudged() {
        if (stopped || touchesHeld > 0) return;
        if (heldConfirmation != null) {
            tapHeldConfirmation("touch judged on " + AppName.NAME + "'s half");
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (!declineUnderWay(now)) return;
        if (checkOffer("touch judged", now, MAX_SCAN_NODES)) scheduleRecheck();
        watchWindows();
    }

    /**
     * On the main thread, as Dasher reports a click: during a decline, a click that is not the echo of one of the
     * app's own taps is the user's ("View offer details", "Go back", "Cancel", the offer card, any control), and
     * hands the offer back at once, as a touch does. It is judged by the click's node (any node the app did not tap is
     * the user's, however soon after a tap of the app's) and, without a node, by its time against each of the app's
     * taps. Only what the event already holds is looked at: nothing is asked of Dasher here.
     */
    private void clickDuringDecline(Click click) {
        if (stopped || decliningOffer == null || click.at < declineBeganAt) return;
        if (ownTaps.clickEcho(click.source, click.at, OfferFilterService::sameNodes) != null) return;
        userActionWhat = click.shape();
        userActionWasClick = true;
        OwnTaps.Tap last = ownTaps.last();
        touchSinceOwnAction = last != null && click.at >= last.began ? click.at - last.began : -1;
        userActions.incrementAndGet();
        scanner.postAtFrontOfQueue(this::takeOverIfTouched);
    }

    private void takeOverIfTouched() {
        long seen = userActions.get();
        if (seen == userActionsTaken) return;
        userTookOver();
        // Only now that the takeover is published: until then the notification path goes by the touch itself.
        userActionsTaken = seen;
    }

    /** A decline of the app's is under way: its confirmation authority, or the wait over a closed question. */
    private boolean declineUnderWay(long now) {
        return declineState.hasPendingConfirmation(now) || episode.holding(now);
    }

    /**
     * Any touch or click of the user's while a decline is in progress hands the offer back to the user: its
     * confirmation is not tapped, the offer is not declined again, and the sound comes back. A confirmation already
     * tapped cannot be undone.
     */
    private void userTookOver() {
        Runnable hook = takeoverBeginsForTests;
        if (hook != null) hook.run();
        long now = SystemClock.uptimeMillis();
        if (!declineUnderWay(now)) {
            syncAutomation();
            return;
        }
        handBack(userActionWasClick ? HandBack.CLICK : HandBack.TOUCH, userActionWhat, now);
    }

    /** Why a decline is handed back to the user. */
    private enum HandBack {
        /** A touch on the screen that was not the echo of a tap of the app's. */
        TOUCH,
        /** A click Dasher reported on a node the app did not tap. */
        CLICK,
        /** The declined offer showing again after its screen gave way to its question, with no confirmation taken. */
        BACK_TO_OFFER,
        /** Dasher's question could not be tapped, or Dasher did not act on it: the app gives up. */
        NOT_TAPPED,
        /** The user's new minimum scale superseded the rules that authorized this decline. */
        MINIMUMS_CHANGED,
        /** A changed item floor superseded the rules that authorized this decline. */
        ITEM_MINIMUM_CHANGED,
        /** A screen-off or lock boundary invalidated the visible offer and its pending taps. */
        SCREEN_OFF,
        /** Dasher explicitly reported a failed request; no later idle frame can upgrade it optimistically. */
        DASHER_ERROR
    }

    /**
     * The offer is the user's from now on: nothing more is tapped on it (a takeover, which ends as any does), the touch
     * watch goes down and the sound comes back. The history says the user took over (unless the decline was confirmed
     * before they acted), or, when the app gave up, that Dasher's question was not confirmed and the offer is theirs.
     *
     * @param detail what happened, for the log
     */
    private void handBack(HandBack why, String detail, long now) {
        boolean alreadyConfirmed = declineState.confirmationTapped();
        boolean givenUp = why == HandBack.NOT_TAPPED || why == HandBack.DASHER_ERROR;
        takeover = new Takeover(declinedOffer, now, givenUp
                ? DecisionLog.Action.CONFIRMATION_NOT_TAPPED : DecisionLog.Action.USER_TOOK_OVER);
        RestartSuppression.remember(this, declinedOffer, previousCountdown);
        takeoverGeneration = OfferNotificationService.generation();
        takeoverDisplayEligible = why != HandBack.NOT_TAPPED && why != HandBack.MINIMUMS_CHANGED
                && why != HandBack.ITEM_MINIMUM_CHANGED
                && why != HandBack.SCREEN_OFF && why != HandBack.DASHER_ERROR && declinedEntry != null
                && !declinedEntry.addOn && !declinedDuringRoute;
        takeoverDisplayUntil = now + 60_000;
        if (why == HandBack.NOT_TAPPED && alreadyConfirmed) {
            lateCompletionUntil = now + 5_000;
            lateCompletionUserActions = userActions.get();
            lateCompletionTries = declineState.confirmationTries();
        } else lateCompletionUntil = 0;
        episode.end();
        endAuthority("left to user: " + why.name().toLowerCase(java.util.Locale.US), true);
        scanner.removeCallbacks(recheck);
        recheckPending = false;
        heldConfirmation = null;
        scanner.removeCallbacks(watchHoldOver);
        scanner.removeCallbacks(confirmRetry);
        confirmRetryPending = false;
        scanner.removeCallbacks(episodeCheck);
        endConfirmationPoll(now);
        syncAutomation();
        if (lateCompletionUntil > now) scanner.postAtTime(syncAutomation, lateCompletionUntil);
        // The user's own, unless the decline was confirmed before they acted. Given up, the decline did not go through
        // (Dasher still asks, or still shows the offer), whether or not its confirmation was tapped: left to the user.
        if (declinedEntry != null && (givenUp || !alreadyConfirmed)) {
            recordRead(new DecisionLog.Entry(declinedEntry.at, declinedEntry.source,
                    declinedEntry.addOn, declinedEntry.facts, declinedEntry.requiredCents, declinedEntry.result,
                    declinedEntry.reason, givenUp ? DecisionLog.Action.CONFIRMATION_NOT_TAPPED
                            : DecisionLog.Action.USER_TOOK_OVER, true, declinedEntry.evidence)
                    .withScore(declinedEntry.scorePercent).peeked(declinedEntry.peeked), -1, false);
        }
        long since = touchSinceOwnAction;
        String after = since >= 0 ? " (" + since + " ms after " + AppName.NAME + "'s last tap)" : "";
        String halted = "; automatic decline stopped" + (alreadyConfirmed ? " after its confirmation was tapped" : "");
        String toast = alreadyConfirmed ? "Decline was already confirmed"
                : AppName.NAME + " stopped tapping this offer";
        switch (why) {
            case TOUCH:
                DiagnosticLog.log(this, "accessibility", "screen touched" + after + halted);
                status(alreadyConfirmed
                        ? "You touched the screen after the decline was confirmed; nothing more will be tapped."
                        : "You touched the screen, so auto-decline stopped for this offer.");
                break;
            case CLICK:
                DiagnosticLog.log(this, "accessibility", "your tap on Dasher" + after + " (" + detail + ")" + halted);
                status(alreadyConfirmed
                        ? "You tapped Dasher after the decline was confirmed; nothing more will be tapped."
                        : "You tapped Dasher, so auto-decline stopped for this offer.");
                break;
            case BACK_TO_OFFER:
                DiagnosticLog.log(this, "accessibility", "you went back to the offer from Dasher's question ("
                        + detail + ")" + halted);
                status("You went back to the offer, so auto-decline stopped for it.");
                toast = AppName.NAME + " stopped tapping this offer";
                break;
            case MINIMUMS_CHANGED:
            case ITEM_MINIMUM_CHANGED:
                DiagnosticLog.log(this, "rules", (why == HandBack.MINIMUMS_CHANGED
                        ? "minimum scale changed " : "per-item minimum changed ") + detail + halted);
                status(alreadyConfirmed
                        ? "Minimums changed after a confirmation was requested; nothing more will be tapped."
                        : "Minimums changed; this earlier decline is left to you.");
                toast = "Minimums changed; automatic taps stopped for this offer";
                break;
            case DASHER_ERROR:
                DiagnosticLog.log(this, "confirm", "Dasher reported a decline error; completion is unconfirmed");
                status("Dasher reported an error; this decline is unconfirmed and left to you.");
                toast = "Dasher reported a decline error; offer left to you";
                break;
            case SCREEN_OFF:
                DiagnosticLog.log(this, "accessibility", "screen off or locked" + halted);
                status("Screen off or locked; this earlier offer is left to you.");
                toast = "Screen off or locked; automatic taps stopped";
                break;
            default:
                DiagnosticLog.log(this, "confirm", "confirmation not tapped: " + detail + "; the offer is left to you");
                status("Dasher's question could not be confirmed, so this offer is left to you.");
                toast = AppName.NAME + " couldn't confirm this decline";
                break;
        }
        String shown = toast;
        onMain(() -> Toast.makeText(this, shown, Toast.LENGTH_SHORT).show());
        // A peek never goes back over a decline handed back or given up: Dasher stays up with the offer.
        peekOver("left Dasher up because " + (why == HandBack.TOUCH ? "you touched the screen during the decline"
                : why == HandBack.CLICK ? "you tapped Dasher during the decline"
                : why == HandBack.BACK_TO_OFFER ? "you went back to the offer from Dasher's question"
                : why == HandBack.MINIMUMS_CHANGED ? "the minimum scale changed"
                : why == HandBack.ITEM_MINIMUM_CHANGED ? "the per-item minimum changed"
                : why == HandBack.SCREEN_OFF ? "the screen turned off or locked"
                : why == HandBack.DASHER_ERROR ? "Dasher reported a decline error"
                : "Dasher's question was not confirmed"));
    }

    /** A new global buffer never inherits the old scale's in-flight decline authority. */
    private boolean minimumScaleChanged(int currentScale, long now) {
        if (currentScale == declineMinimumScale
                || (!declineState.hasPendingConfirmation(now) && !episode.active(now))) return false;
        handBack(HandBack.MINIMUMS_CHANGED, "from " + declineMinimumScale + "% to " + currentScale + "%", now);
        return true;
    }

    /** A new scoped item rule cannot reuse a decline authorized under a different rate. */
    private boolean minimumRulesChanged(FilterSettings current, long now) {
        if (minimumScaleChanged(current.minimumScalePercent, now)) return true;
        if (current.perItemCents == declinePerItemCents
                || (!declineState.hasPendingConfirmation(now) && !episode.active(now))) return false;
        handBack(HandBack.ITEM_MINIMUM_CHANGED,
                "from " + declinePerItemCents + " to " + current.perItemCents + " cents per item", now);
        return true;
    }

    /** No initial request was made with stale minimums; judge a fresh screen with the newly saved rules. */
    private void staleMinimumScaleRead() {
        long now = SystemClock.uptimeMillis();
        if (!minimumRulesChanged(FilterStore.load(this), now)) {
            status("Minimums changed while reading; checking the offer again.");
        }
        scanner.post(() -> { if (!stopped) scanNow(SystemClock.uptimeMillis(), "minimums changed"); });
    }

    /**
     * Whether these facts could be the offer the user took over. Anything that does not contradict it counts, so a
     * partly drawn frame of that offer (pay without the route line, or the reverse) is never declined.
     */
    private boolean isTakenOver(OfferSnapshot offer, long now) {
        return takeover.covers(offer, now);
    }

    private void forgetTakeover(String reason) {
        if (takeover != Takeover.NONE) {
            DiagnosticLog.log(this, "takeover", "ended: " + reason);
            takeover = Takeover.NONE;
            RestartSuppression.clear(this);
        }
    }

    // ---- Peek (scanner thread) ----

    /**
     * Why the notification path's conditions do not allow a peek of this post, or null when they do (main thread, before
     * its card is posted: silently when a peek will be tried). Screen reading must be running too.
     *
     * @param screenReadAgo how long ago a screen offer that pairs with this post was read (ms), or -1 for none
     */
    static String peekRefusal(Context context, Peek.Request request, FilterSettings settings, boolean foreground,
                              long screenReadAgo) {
        String why = Peek.refusal(context, request, settings, foreground, screenReadAgo);
        OfferFilterService service = active;
        if (why == null && (service == null || service.stopped)) why = "screen reading is off";
        return why;
    }

    /**
     * Peek, from the notification path (main thread), once {@link #peekRefusal} allowed it and its card was posted
     * silently: the scanner looks at the rest and, the phone quiet, opens Dasher ({@link #startPeek}). When it does not,
     * one "[peek] skipped" line says why and the card rings once, as it would have.
     */
    static void peekAt(Context context, Peek.Request request) {
        OfferFilterService service = active;
        if (service == null || service.stopped) {
            Peek.skipped(context, "screen reading is off");
            OfferNotificationService.peekNotTaken(request.alertTag);
            return;
        }
        service.scanner.post(() -> service.startPeek(request));
    }

    /** A tap on one of Offer Filter's cards opened Dasher (main thread): a peek under way never goes back. */
    static void cardTapped() {
        OfferFilterService service = active;
        if (service == null || service.stopped) return;
        service.peekActions.incrementAndGet();
        service.scanner.postAtFrontOfQueue(() -> service.peekOver("left Dasher up because you tapped the offer's card"));
    }

    /**
     * Arms a peek when everything else allows it ({@link Peek}): no peek under way, the last one ended
     * {@link Peek#GAP_MS} ago and fewer than {@link Peek#CAP} lately, this post never peeked at, the phone on,
     * unlocked, not on a call, the microphone, camera and screen pinning free, no split being set up, no update
     * installing, no takeover in force and no acceptance lately; one full-screen app in front that is not Dasher, can
     * be gone back to, and is not a system screen standing in for a moment. Then the touch watch goes up, and Dasher
     * opens once the phone is quiet ({@link #peekTick}).
     */
    private void startPeek(Peek.Request request) {
        try {
            if (stopped || scannerFaulted) {
                peekNotTaken(request, "screen reading stopped");
                return;
            }
            long now = Peek.now();
            String why = Peek.refusal(this, request, FilterStore.load(this), screen.dasherReadable, -1);
            if (why == null && !OfferNotificationService.peekCurrent(request)) why = "the offer notification was removed or replaced";
            if (why == null) why = peek.refusal(request, now);
            if (why == null) why = phoneRefusal();
            if (why == null) why = busyRefusal(now);
            if (why == null && screen.dasherReadable) why = "Dasher is on screen";
            Peek.Front front = null;
            if (why == null) {
                Look look;
                try {
                    look = look();
                } catch (RuntimeException unreadable) {
                    look = null;
                }
                syncOverlay();
                why = look == null ? "the windows could not be read" : screenRefusal(look);
                if (why == null) {
                    front = front(look);
                    why = frontRefusal(front, look);
                }
            }
            ComponentName dasher = why == null ? launcher(DASHER_PACKAGE) : null;
            if (why == null && dasher == null) why = "Dasher has no launch intent";
            if (why != null) {
                peekNotTaken(request, why);
                return;
            }
            logDasherLauncher(dasher);
            peek.arm(request, front, dasher, now);
            // The touch watch goes up now, before Dasher is opened: the phone must be quiet first.
            syncAutomation();
            schedulePeekTick();
        } catch (RuntimeException error) {
            peekFailed("starting", error);
            if (!peek.active()) OfferNotificationService.peekNotTaken(request.alertTag);
        }
    }

    /** No peek for this post: one counted "[peek] skipped" line, and its card rings once as it would have. */
    private void peekNotTaken(Peek.Request request, String why) {
        Peek.skipped(this, why);
        OfferNotificationService.peekNotTaken(request.alertTag);
    }

    /** A step of a peek failed: logged, and the peek ends with Dasher left as it is. */
    private void peekFailed(String step, RuntimeException error) {
        FilterStore.setPeek(this, false);
        Peek.log(this, "failed while " + step + ": " + error.getClass().getSimpleName());
        status("Peek paused after an error. Turn Peek back on in Settings when ready.");
        boolean arming = peek.phase() == Peek.Phase.ARMING;
        Peek.Request request = peek.request();
        try {
            finishPeek(false, false);
        } catch (RuntimeException again) {
            // Logged above; nothing more to do.
        }
        if (arming && request != null) OfferNotificationService.peekNotTaken(request.alertTag);
    }

    /** Why the phone is not as a peek needs it (the screen on and unlocked, no call), or null when it is. */
    private String phoneRefusal() {
        try {
            PowerManager power = getSystemService(PowerManager.class);
            if (power != null && !power.isInteractive()) return "the screen is off";
            KeyguardManager keyguard = getSystemService(KeyguardManager.class);
            if (keyguard != null && keyguard.isKeyguardLocked()) return "the phone is locked";
        } catch (RuntimeException unknown) {
            return "the screen's state could not be read";
        }
        return inCall() ? "a call is under way" : null;
    }

    /**
     * Whether a call rings or is under way: the audio mode is not normal, as the sound guard reads it
     * ({@link OfferSilencer}), or, before Android 12, where it needs no permission, the phone's call state says so.
     */
    @SuppressWarnings("deprecation")
    private boolean inCall() {
        try {
            AudioManager audio = getSystemService(AudioManager.class);
            if (audio != null && audio.getMode() != AudioManager.MODE_NORMAL) return true;
        } catch (RuntimeException unknown) {
            return true;
        }
        if (Build.VERSION.SDK_INT < 31) {
            try {
                TelephonyManager phone = getSystemService(TelephonyManager.class);
                if (phone != null && phone.getCallState() != TelephonyManager.CALL_STATE_IDLE) return true;
            } catch (RuntimeException unreadable) {
                // Not readable here: the audio mode said there is no call.
            }
        }
        return false;
    }

    /**
     * Why the user is busy with the phone in a way a peek must not interrupt, or null: the microphone or the camera in
     * use (neither needs a permission to know), the screen pinned, a split with Dasher being set up, an update
     * installing, a takeover in force, or an acceptance (or Accept tap) seen lately.
     */
    private String busyRefusal(long now) {
        try {
            AudioManager audio = getSystemService(AudioManager.class);
            if (audio != null && !audio.getActiveRecordingConfigurations().isEmpty()) return "the microphone is in use";
            if (!camerasInUse.isEmpty()) return "the camera is in use";
            ActivityManager activities = getSystemService(ActivityManager.class);
            if (activities != null && activities.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) {
                return "the screen is pinned";
            }
        } catch (RuntimeException unknown) {
            return "the phone's state could not be read";
        }
        if (DasherSplit.pending()) return "a split screen with Dasher is being set up";
        if (Updater.installing(this)) return "an update is installing";
        long uptime = SystemClock.uptimeMillis();
        if (takeover != Takeover.NONE && uptime - takeover.at < TAKEOVER_MS) {
            return "you took over an offer " + Peek.seconds(uptime - takeover.at) + " ago";
        }
        if (lastAcceptAt != NEVER && now - lastAcceptAt < Peek.AFTER_ACCEPT_MS) {
            return "you accepted an offer " + Peek.seconds(now - lastAcceptAt) + " ago";
        }
        return null;
    }

    /**
     * Why the screen is not one full-screen app a peek may stand in front of for a moment, or null: split (the divider,
     * or the active application window covering less than {@link Peek#FILLS} of the display), Dasher on screen or in a
     * picture-in-picture or floating window, a keyboard, a picture-in-picture window active, or another app's pop-up
     * or floating window.
     */
    private String screenRefusal(Look look) {
        if (look.split || look.dasherBeside) return "the screen is split";
        if (look.dasherRoot != null) return "Dasher is on screen";
        Rect display = display();
        Rect bounds = new Rect();
        for (AccessibilityWindowInfo window : look.windows) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                window.getBoundsInScreen(bounds);
                if (!bounds.isEmpty()) display.union(bounds);
            }
        }
        for (AccessibilityWindowInfo window : look.windows) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return "the keyboard is up";
        }
        if (look.activeApp != null) {
            look.activeApp.getBoundsInScreen(bounds);
            if (!fills(bounds, display)) return "the app in front does not fill the screen";
        }
        for (AccessibilityWindowInfo window : look.windows) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || isOwnWindow(window.getId())) continue;
            boolean pip = window.isInPictureInPictureMode();
            AccessibilityNodeInfo root = look.roots.containsKey(window) ? look.roots.get(window) : null;
            if (pip) {
                if (window.isActive()) return "a picture-in-picture window is active";
                if (root == null) root = windowRoot(window, true);
                if (isDasher(root)) return "Dasher is in a picture-in-picture window";
                continue;
            }
            window.getBoundsInScreen(bounds);
            if (fills(bounds, display)) continue;
            if (window.isActive()) return "the app in front does not fill the screen";
            if (root == null) root = windowRoot(window, true);
            if (root != null && getPackageName().contentEquals(nonNull(root.getPackageName()))) continue;
            return isDasher(root) ? "Dasher is in a floating window" : "a pop-up or floating window is on screen";
        }
        return null;
    }

    /** Whether a window covers at least {@link Peek#FILLS} of the display. */
    private static boolean fills(Rect bounds, Rect display) {
        Rect covered = new Rect(bounds);
        if (!covered.intersect(display)) return false;
        double area = (double) display.width() * display.height();
        return area > 0 && (double) covered.width() * covered.height() >= Peek.FILLS * area;
    }

    /**
     * The app in front, for a peek to go back to: the active application window's (Offer Filter's own, the default home
     * screen, or another app), or null when Android does not say (no application window is active, as under the shade).
     */
    private Peek.Front front(Look look) {
        AccessibilityWindowInfo app = look.activeApp;
        if (app == null) return null;
        String own = getPackageName();
        if (isOwnWindow(app.getId())) return new Peek.Front(Peek.Back.OURS, own, null, app.getId());
        AccessibilityNodeInfo root = look.roots.get(app);
        // Ordinary navigation scans cache non-Dasher windows without fetching their roots. Peek needs only this
        // one app's package, once, to remember how to return; it never traverses the other app's content.
        if (root == null) root = windowRoot(app, true);
        CharSequence name = root == null ? null : root.getPackageName();
        if (name == null || name.length() == 0) return null;
        String pkg = name.toString();
        if (pkg.equals(own)) return new Peek.Front(Peek.Back.OURS, own, null, app.getId());
        if (pkg.equals(defaultHome())) return new Peek.Front(Peek.Back.HOME, pkg, null, app.getId());
        return new Peek.Front(Peek.Back.APP, pkg, launcher(pkg), app.getId());
    }

    /** Why a peek cannot go back to the app in front, or null when it can. */
    private String frontRefusal(Peek.Front front, Look look) {
        if (front == null) return "the app in front is unknown";
        if (isDasherPackage(front.pkg)) return "Dasher is on screen";
        if (Peek.GUESTS.contains(front.pkg)) return "a system screen is in front (a chooser, a permission or a picker)";
        if (front.back == Peek.Back.APP && front.launcher == null) {
            return "the app in front has no launcher entry to go back to";
        }
        return null;
    }

    /**
     * The default home screen's package, as Android resolves its home entry; null when none is the default (Android
     * would ask). AOSP's Settings has a home entry of its own (its FallbackHome), never the default.
     */
    private String defaultHome() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo resolved = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return resolved == null || resolved.activityInfo == null ? null : resolved.activityInfo.packageName;
        } catch (RuntimeException unknown) {
            return null;
        }
    }

    /** An app's launcher activity, as its launch intent names it; null when it has none. */
    private ComponentName launcher(String pkg) {
        try {
            Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
            List<ResolveInfo> choices = getPackageManager().queryIntentActivities(launch, 0);
            if (choices != null) for (ResolveInfo choice : choices) {
                ActivityInfo info = choice.activityInfo;
                if (info != null && info.enabled && info.exported && pkg.equals(info.packageName)) {
                    return new ComponentName(info.packageName, info.name);
                }
            }
            return null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /**
     * An app's launcher activity started as a launcher starts it: by its component, with no package (on Android 8-10 a
     * package-bearing intent can stack a fresh start screen on top), in its own task as it was (nothing cleared or
     * reset), and as no user action of the app in front (so it does not drop into picture-in-picture over it).
     */
    static Intent launcherIntent(ComponentName activity) {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(activity)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
    }

    /** Once per Dasher version: how its launcher activity starts (its launch mode, task and clearing), for a report. */
    @SuppressWarnings("deprecation")
    private void logDasherLauncher(ComponentName dasher) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(DASHER_PACKAGE, PackageManager.GET_ACTIVITIES);
            ActivityInfo found = null;
            if (info.activities != null) {
                for (ActivityInfo activity : info.activities) {
                    if (dasher.getClassName().equals(activity.name)) found = activity;
                }
            }
            String what = "Dasher " + info.versionName + " launcher " + dasher.getShortClassName()
                    + (found == null ? " (not listed)" : " launchMode=" + found.launchMode + " taskAffinity="
                    + found.taskAffinity + " clearTaskOnLaunch="
                    + ((found.flags & ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH) != 0));
            DiagnosticLog.logOnChange(this, "peek", "dasher-launcher", what);
        } catch (PackageManager.NameNotFoundException | RuntimeException unknown) {
            // Only a report's detail.
        }
    }

    /** The peek's next tick: while arming every 100 ms, while Dasher opens every 250 ms, then at its deadlines. */
    private void schedulePeekTick() {
        scanner.removeCallbacks(peekTick);
        if (stopped || !peek.active()) return;
        long now = Peek.now();
        long due;
        switch (peek.phase()) {
            case ARMING:
                due = now + 100;
                break;
            case OPENING:
                due = Math.min(now + 250, peek.openedAt() + Peek.OPEN_MS);
                break;
            default:
                due = peek.openedAt() + Peek.MAX_MS;
                if (peek.phase() == Peek.Phase.UP && !peek.sawOffer() && peek.lastRecognised()
                        && peek.recognisedAt() != Long.MIN_VALUE) {
                    due = Math.min(due, peek.recognisedAt() + Peek.NO_OFFER_MS);
                }
                break;
        }
        scanner.postDelayed(peekTick, Math.max(100, due - now));
    }

    /** A peek's tick, its every step guarded: a failure is logged and ends the peek with Dasher left as it is. */
    private void peekTick() {
        if (stopped || !peek.active() || peekReturning) return;
        try {
            peekStep();
        } catch (RuntimeException error) {
            peekFailed("ticking", error);
        }
    }

    private void peekStep() {
        long now = Peek.now();
        switch (peek.phase()) {
            case ARMING:
                arming(now);
                return;
            case OPENING:
                opening(now);
                return;
            default:
                break;
        }
        if (now - peek.openedAt() >= Peek.MAX_MS) {
            peekOver("ended because " + Peek.MAX_MS / 1000 + " s passed");
            return;
        }
        String interrupted = peekInterrupted();
        if (interrupted != null) {
            peekOver(interrupted);
            return;
        }
        boolean noOffer = peek.phase() == Peek.Phase.UP && !peek.sawOffer()
                && peek.recognisedAt() != Long.MIN_VALUE && now - peek.recognisedAt() >= Peek.NO_OFFER_MS;
        if (noOffer) {
            if (queued.get() != QUEUED_NONE) {
                // A read Dasher's events asked for is waiting: it reads first, and this comes back after it.
                scanner.postDelayed(peekTick, CONFIRM_POLL_MS);
                return;
            }
            if (checkOffer("peek", SystemClock.uptimeMillis(), MAX_SCAN_NODES)) scheduleRecheck();
            watchWindows();
            if (peek.phase() == Peek.Phase.UP && !peek.sawOffer() && peek.lastRecognised()) {
                peekBack("no offer showed within " + Peek.NO_OFFER_MS / 1000 + " s of Dasher's screen", false, null);
                return;
            }
        }
        schedulePeekTick();
    }

    /**
     * Arming: Dasher opens once the phone has been quiet {@link Peek#QUIET_MS} (no touch the watch saw, no keyboard
     * listed), with the touch watch up (or not up {@link #WATCH_HOLD_MS} after it was asked for, as a confirmation
     * would go on); never when Android refused the watch, and not when the quiet does not come within
     * {@link Peek#QUIET_WAIT_MS}: the user was using the phone.
     */
    private void arming(long now) {
        Peek.Request request = peek.request();
        peek.touchedWhileArming(quietTouchAt);
        if (watchState == WATCH_REFUSED) {
            abandonArming(request, "the touch watch could not be put up");
            return;
        }
        String why = Peek.refusal(this, request, FilterStore.load(this), false, -1);
        if (why == null && !OfferNotificationService.peekCurrent(request)) why = "the offer notification was removed or replaced";
        if (why == null) why = phoneRefusal();
        if (why == null) why = busyRefusal(now);
        if (why != null) {
            abandonArming(request, why);
            return;
        }
        boolean keyboard = keyboardListed();
        if (keyboard) peek.touchedWhileArming(now);
        if (peek.quietTimedOut(now) && !peek.quiet(now)) {
            abandonArming(request, "you were using the phone");
            return;
        }
        boolean watchReady = watchState == WATCH_UP;
        if (!watchReady && now - peek.armedAt() >= WATCH_HOLD_MS) {
            abandonArming(request, "the touch watch was not ready");
            return;
        }
        if (!peek.quiet(now) || !watchReady || keyboard) {
            schedulePeekTick();
            return;
        }
        // The user may have switched apps, entered a split or opened a system surface during the quiet wait.
        Look current = look();
        String screenWhy = screenRefusal(current);
        Peek.Front currentFront = screenWhy == null ? front(current) : null;
        if (screenWhy == null) screenWhy = frontRefusal(currentFront, current);
        if (screenWhy == null && !peek.front().pkg.equals(currentFront.pkg)) {
            screenWhy = "the app in front changed during the quiet wait";
        }
        if (screenWhy != null) {
            abandonArming(request, screenWhy);
            return;
        }
        // A window lookup may take time. Honour a touch delivered while it was in progress too.
        long openingAt = Peek.now();
        peek.touchedWhileArming(quietTouchAt);
        if (!peek.quiet(openingAt)) {
            schedulePeekTick();
            return;
        }
        String finalPhone = phoneRefusal();
        if (!Consent.accepted(this) || !FilterStore.peek(this) || !FilterStore.load(this).enabled
                || finalPhone != null || watchState != WATCH_UP || keyboardListed()
                || !OfferNotificationService.peekCurrent(request)) {
            abandonArming(request, "the phone or Peek settings changed before opening");
            return;
        }
        Intent dasher = launcherIntent(peek.dasher());
        // Publish authority before the Binder call: it can block while touches or new posts arrive on the main
        // thread. Nothing that happens during that call may be swallowed by taking a new baseline afterwards.
        peek.opened(Peek.now(), OfferNotificationService.generation(), peekActions.get());
        peekBeganAt = SystemClock.uptimeMillis();
        syncAutomation();
        try {
            peekStarter.accept(dasher);
        } catch (RuntimeException refused) {
            Peek.log(this, "Android would not open Dasher (" + refused.getClass().getSimpleName() + ")");
            finishPeek(false, true);
            OfferNotificationService.peekNotTaken(request.alertTag);
            return;
        }
        // A peeked offer counts only what is read during the peek: nothing of the wait for offers before it, and no
        // decline held by hand goes on through it.
        acceptedTracker.peekBegan();
        Peek.log(this, "opening Dasher for " + request.what() + " (was in front: " + peek.front().kind() + ")");
        schedulePeekTick();
    }

    /** No peek after all, before Dasher was opened: one skip line, the card rings once, the watch goes down. */
    private void abandonArming(Peek.Request request, String why) {
        finishPeek(false, false);
        peekNotTaken(request, why);
    }

    /** Whether a keyboard window is listed now (Android's list only). */
    private boolean keyboardListed() {
        try {
            List<AccessibilityWindowInfo> listed = windowSource.get();
            if (listed == null) return true;
            for (AccessibilityWindowInfo window : listed) {
                if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
            }
        } catch (RuntimeException unreadable) {
            return true;
        }
        return false;
    }

    /**
     * While Dasher opens: only Android's list of windows is looked at, never an app's content. A new application
     * window in front of the one the user was in is read (Dasher's, or not); with none {@link Peek#OPEN_MS} after the
     * launch, the peek ends: the phone may block apps opening from the background. Its card rings once then, no key is
     * held, and nothing the user touched meanwhile is taken for a choice about Dasher.
     */
    private void opening(long now) {
        String interrupted = !Consent.accepted(this) ? "the notice isn't accepted yet"
                : !FilterStore.peek(this) ? "Peek was turned off"
                : !FilterStore.load(this).enabled ? "auto-decline was paused" : phoneRefusal();
        if (interrupted != null) {
            Peek.Request request = peek.request();
            peekOver("ended because " + interrupted);
            if (request != null) OfferNotificationService.peekNotTaken(request.alertTag);
            return;
        }
        if (now - peek.openedAt() >= Peek.OPEN_MS) {
            Peek.Request request = peek.request();
            Peek.log(this, "ended: Dasher did not come up within " + Peek.OPEN_MS / 1000 + " s (the phone may block "
                    + "apps opening from the background)" + peek.timing(now));
            finishPeek(false, true);
            if (request != null) OfferNotificationService.peekNotTaken(request.alertTag);
            return;
        }
        if (newWindowInFront() && queued.get() == QUEUED_NONE) {
            if (checkOffer("peek: a new window in front", SystemClock.uptimeMillis(), MAX_SCAN_NODES)) {
                scheduleRecheck();
            }
            watchWindows();
        }
        schedulePeekTick();
    }

    /** Whether Android lists an active application window other than the one the user was in (no app is asked). */
    private boolean newWindowInFront() {
        try {
            List<AccessibilityWindowInfo> listed = windowSource.get();
            if (listed == null) return false;
            for (AccessibilityWindowInfo window : listed) {
                if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && window.isActive()) {
                    return realWindowId(window.getId()) && window.getId() != peek.front().windowId;
                }
            }
        } catch (RuntimeException unreadable) {
            return false;
        }
        return false;
    }

    /**
     * What ended a peek before it could go back, as its log line; null while nothing has: anything of the user's, Peek
     * or auto-decline turned off, the screen off or locked, a call.
     */
    private String peekInterrupted() {
        if (peekActions.get() != peek.actions()) {
            return "left Dasher up because you touched the screen or tapped Dasher";
        }
        if (!Consent.accepted(this)) return "ended because the notice isn't accepted yet";
        if (!FilterStore.peek(this)) return "left Dasher up because Peek was turned off";
        if (!FilterStore.load(this).enabled) return "left Dasher up because auto-decline was paused";
        String phone = phoneRefusal();
        return phone == null ? null : "ended because " + phone;
    }

    /**
     * What a read's look means for a peek: Dasher up (anything the user touched while it opened then leaves it up),
     * Dasher gone from the screen after it came up (the user went elsewhere), or the screen split.
     */
    private void peekSaw(Look look, long now) {
        if (peekReturning) return;
        if (!peek.active() || peek.phase() == Peek.Phase.ARMING) return;
        if (look.split) {
            peekOver("ended because the screen was split");
        } else if (look.dasherRoot == null) {
            if (peek.phase() != Peek.Phase.OPENING) peekOver("ended because Dasher left the screen");
        } else if (peek.up(Peek.now())) {
            if (peekActions.get() != peek.actions()) {
                peekOver("left Dasher up because you touched the screen as it opened");
                return;
            }
            schedulePeekTick();
        }
    }

    /**
     * Whether an offer read now is the peek's own (the first offer read, the one it declined showing still, or a next
     * one it follows): its history line says "(peeked)". A newer offer's notification, or another offer on screen after
     * the peeked one was declined, is followed while nothing of the user's was seen and within {@link Peek#CAP};
     * beyond that, the peek ends with Dasher up.
     */
    private boolean peekReads(OfferSnapshot offer) {
        if (peekReturning) return false;
        if (!peek.active() || peek.phase() == Peek.Phase.ARMING) return false;
        long now = Peek.now();
        long generation = OfferNotificationService.generation();
        if (generation != peek.generation()) peek.generation(generation);
        if (peek.owns(offer)) return true;
        if (peekActions.get() == peek.actions() && peek.mayFollow(now)) {
            peek.follow(now);
            Peek.log(this, "another offer came: following it");
            schedulePeekTick();
            return true;
        }
        peekOver("left Dasher up because another offer appeared");
        return false;
    }

    /**
     * Whether the user is navigating (the user's decision): a route stored, a navigation app in front when the peek
     * began, or the phone in car mode. A passing or unclear offer then goes back to the map at once with its card.
     */
    private boolean navigating() {
        if (ActiveRouteStore.load(this) != null) return true;
        Peek.Front front = peek.front();
        if (front != null && front.navigation()) return true;
        try {
            UiModeManager modes = getSystemService(UiModeManager.class);
            return modes != null && Peek.carMode(modes);
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    /**
     * A passing or unclear offer the peek read: while navigating, back to the map at once and the offer's card carries
     * what was read and rings once (the user's decision); otherwise Dasher stays up for the user.
     */
    private void peekLeavesOffer(OfferRule.Result result, OfferSnapshot read, String reason, boolean paused) {
        peek.leftWithUser();
        String why = paused ? "auto-decline is paused" : result == OfferRule.Result.KEEP
                ? "the offer passes your rules" : "the offer needs your review";
        if (!paused && navigating()) {
            String text = Peek.cardText(result, read, reason);
            peekBack(why + "; you are navigating, so its card carries it", true, () ->
                    OfferNotificationService.peekCard(this, read, result, text));
            return;
        }
        peekOver("left Dasher up because " + why);
    }

    /**
     * Whether a read of Dasher without an offer's controls proves the peeked offer's decline complete: the app's own
     * decline went all the way (its question confirmed, or an add-on's Decline), Dasher's question is not up and its
     * decline is not waiting to see whether Dasher closed it for good, none of an offer's facts show, and the screen
     * is one Dasher's own words explain: the wait for offers, the dash over, or the route the declined offer came
     * during. An empty, partly drawn or unrecognised screen proves nothing.
     */
    private boolean declineProven(List<String> labels, boolean facts, long now) {
        if (!peek.declineComplete() || readShowsQuestion || facts || labels.isEmpty() || episode.holding(now)) {
            return false;
        }
        if (DasherScene.showsNewOffer(labels) || DasherScene.showsEndDashQuestion(labels)) return false;
        if (DasherScene.showsWaiting(labels) || OfferEvidence.isDashOver(labels)) return true;
        return peek.routeAfter() && (DasherScene.showsRoute(labels) || AcceptedOfferTracker.isDeliveryScreen(labels));
    }

    /** Whether a screen without an offer is one Dasher's words explain: waiting, idle, the dash over, a delivery. */
    private static boolean explained(List<String> labels, boolean facts) {
        if (facts || labels.isEmpty() || DasherScene.showsNewOffer(labels)
                || DasherScene.showsEndDashQuestion(labels)) {
            return false;
        }
        return DasherScene.showsWaiting(labels) || OfferEvidence.isDashOver(labels) || DasherScene.showsRoute(labels)
                || AcceptedOfferTracker.isDeliveryScreen(labels);
    }

    /**
     * Takes the user back to where they were when the peek began, unless something of theirs, Peek or auto-decline
     * turned off, the screen, the lock or a call came first, or Dasher is no longer in front. A newer offer's
     * notification means one more offer is coming: the peek follows it instead. Before going back, everything of the
     * decline is put away (the sound, its confirmation and episode, the touch watch, the tab), so a touch in the app
     * the user goes back to is theirs alone. Another app goes back by its launcher activity, as a launcher starts it;
     * the home screen by Android's Home action; Offer Filter by bringing its own task to the front.
     *
     * @param because why it goes back, for the log
     * @param dasherInFront whether the read under way found Dasher in front just now; otherwise it is looked at
     * @param then run once it went back (the card of an offer left with the user), or null
     */
    private void peekBack(String because, boolean dasherInFront, Runnable then) {
        try {
            peekBackNow(because, dasherInFront, then);
        } catch (RuntimeException error) {
            peekFailed("returning", error);
        }
    }

    private void peekBackNow(String because, boolean dasherInFront, Runnable then) {
        if (peekReturning) return;
        long now = Peek.now();
        String interrupted = peekInterrupted();
        if (interrupted != null) {
            peekOver(interrupted);
            return;
        }
        long generation = OfferNotificationService.generation();
        if (generation != peek.generation()) {
            peek.generation(generation);
            if (peekActions.get() == peek.actions() && peek.mayFollow(now)) {
                peek.follow(now);
                Peek.log(this, "another offer came: following it");
                schedulePeekTick();
                return;
            }
            peekOver("left Dasher up because another offer came");
            return;
        }
        if (!dasherInFront) {
            Look look;
            try {
                look = look();
            } catch (RuntimeException unreadable) {
                look = null;
            }
            if (look == null || !look.dasherActive || look.split) {
                peekOver("ended because Dasher is no longer in front");
                return;
            }
        }
        // Looking up an app root can take seconds. Fresh window metadata and the touch counter must still agree.
        List<AccessibilityWindowInfo> fresh = windowSource.get();
        if (fresh == null || windowsSignature(fresh) != lastLookWindows) {
            peekOver("ended because the foreground windows changed before returning");
            return;
        }
        interrupted = peekInterrupted();
        if (interrupted != null) {
            peekOver(interrupted);
            return;
        }
        long returningActions = peek.actions();
        long returningGeneration = OfferNotificationService.generation();
        Peek.Front front = peek.front();
        // Everything of the decline put away first: nothing of it is left over the app the user goes back to.
        endAuthority("peek return", false);
        episode.end();
        lateCompletionUntil = 0;
        readIdentity = null;
        declinedOfferShowing = false;
        heldConfirmation = null;
        scanner.removeCallbacks(recheck);
        recheckPending = false;
        scanner.removeCallbacks(confirmRetry);
        confirmRetryPending = false;
        endConfirmationPoll(SystemClock.uptimeMillis());
        silencer.stop();
        if (!Screen.NOT_SHOWN.equals(screen)) screen = Screen.NOT_SHOWN;
        syncOverlay();
        long returningWindows = lastLookWindows;
        peekReturning = true;
        // Main is where Android delivers touches. Keeping the watch until this single task performs its final
        // checks means neither a slow scanner cleanup nor a window lookup can make a user's touch disappear.
        // No other main-loop callback can run between removing the watch and requesting the return.
        onMainFirst(() -> returnPeekOnMain(front, because, then, returningActions, returningGeneration,
                returningWindows));
    }

    private void returnPeekOnMain(Peek.Front front, String because, Runnable then, long actions, long generation,
                                  long windows) {
        if (!peekReturning) return;
        boolean done = false;
        String cancelled = null;
        try {
            List<AccessibilityWindowInfo> fresh = windowSource.get();
            if (stopped || peekActions.get() != actions || OfferNotificationService.generation() != generation
                    || !Consent.accepted(this) || !FilterStore.peek(this) || !FilterStore.load(this).enabled
                    || phoneRefusal() != null || fresh == null || windowsSignature(fresh) != windows) {
                cancelled = "the user, phone, settings, windows or offer changed";
            } else {
                touchWatch.stop();
                watchState = WATCH_DOWN;
                peekWatched = false;
                switch (front.back) {
                    case HOME:
                        done = globalActionHere(GLOBAL_ACTION_HOME);
                        break;
                    case OURS:
                        done = ourTaskToFront();
                        break;
                    default:
                        done = startHere(launcherIntent(front.launcher));
                        break;
                }
            }
        } catch (RuntimeException error) {
            cancelled = "Android refused the return (" + error.getClass().getSimpleName() + ")";
        }
        boolean returned = done;
        String cancellation = cancelled;
        onScanner(() -> {
            if (!peekReturning) return;
            peekReturning = false;
            finishPeek(returned, false);
            syncOverlay();
            if (cancellation != null) {
                Peek.log(this, "return cancelled: " + cancellation);
                return;
            }
            if (!returned) {
                Peek.log(this, "could not go back to " + front.kind() + ": Dasher stays up" + peekTimingAfter(Peek.now()));
                if (then != null) then.run();
                return;
            }
            Peek.log(this, "returned to " + front.kind() + ": " + because + peekTimingAfter(Peek.now()));
            if (then != null) then.run();
            backCheckFrom = Peek.now();
            backCheckFront = front;
            scanner.removeCallbacks(peekBackCheck);
            scanner.postDelayed(peekBackCheck, Peek.BACK_CHECK_EVERY_MS);
        });
    }

    /** The timing kept from the peek that just ended, for its "returned" line. */
    private String peekTimingAfter(long now) {
        String timing = lastPeekTiming;
        return timing == null ? "" : timing;
    }

    /** Offer Filter's own task brought to the front, as it was; its launcher intent when Android lists none. */
    private boolean ourTaskToFront() {
        try {
            ActivityManager activities = getSystemService(ActivityManager.class);
            if (activities != null) {
                for (ActivityManager.AppTask task : activities.getAppTasks()) {
                    ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                    ComponentName base = info == null ? null : info.baseActivity;
                    if (base == null || MainActivity.class.getName().equals(base.getClassName())) {
                        task.moveToFront();
                        return true;
                    }
                }
            }
        } catch (RuntimeException refused) {
            // Started as a launcher would, below.
        }
        return startHere(launcherIntent(new ComponentName(this, MainActivity.class)));
    }

    /**
     * After going back, every {@link Peek#BACK_CHECK_EVERY_MS} for {@link Peek#BACK_CHECK_MS}: whether the app the user
     * was in is in front again (one look at Android's windows and the active window's app), said once by its kind.
     */
    private final Runnable peekBackCheck = new Runnable() {
        @Override public void run() {
            Peek.Front front = backCheckFront;
            if (stopped || front == null) return;
            long after = Peek.now() - backCheckFrom;
            try {
                if (backInFront(front)) {
                    Peek.log(OfferFilterService.this, "back in " + front.kind() + " after " + after + " ms");
                    forgetBackCheck();
                    return;
                }
            } catch (RuntimeException unreadable) {
                // Looked at again, or said below.
            }
            if (after >= Peek.BACK_CHECK_MS) {
                Peek.log(OfferFilterService.this, "returned, but another app is in front");
                forgetBackCheck();
                return;
            }
            scanner.postDelayed(this, Peek.BACK_CHECK_EVERY_MS);
        }
    };

    private void forgetBackCheck() {
        backCheckFront = null;
        peek.forgetFront();
    }

    /** Whether the active application window is the app a peek went back to. */
    private boolean backInFront(Peek.Front front) {
        List<AccessibilityWindowInfo> listed = windowSource.get();
        if (listed == null) return false;
        for (AccessibilityWindowInfo window : listed) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isActive()) continue;
            if (front.back == Peek.Back.OURS && isOwnWindow(window.getId())) return true;
            AccessibilityNodeInfo root = windowRoot(window, true);
            CharSequence name = root == null ? null : root.getPackageName();
            return name != null && front.pkg.contentEquals(name);
        }
        return false;
    }

    /**
     * The peek is over without going back: Dasher stays as it is. One line with its timing; nothing when none. Before
     * Dasher was opened it is no peek at all: the skip line says why and the card rings once.
     */
    private void peekOver(String outcome) {
        if (!peek.active()) return;
        if (peek.phase() == Peek.Phase.ARMING) {
            String why = outcome.replaceFirst("^(ended|left Dasher up) because ", "");
            abandonArming(peek.request(), why);
            return;
        }
        Peek.log(this, outcome + peek.timing(Peek.now()));
        finishPeek(false, false);
    }

    /**
     * Ends the peek: the watch and its timers go, and Peek pauses when peeks keep finding no offer or Dasher keeps
     * not coming up (Settings' switch turns it on again).
     */
    private void finishPeek(boolean wentBack, boolean openFailed) {
        long now = Peek.now();
        peekReturning = false;
        lastPeekTiming = peek.timing(now);
        String pause = peek.end(wentBack, openFailed, now);
        if (!wentBack) peek.forgetFront();
        peekBeganAt = Long.MAX_VALUE;
        scanner.removeCallbacks(peekTick);
        syncAutomation();
        if (pause != null) {
            FilterStore.setPeek(this, false);
            Peek.log(this, "paused: " + pause + "; Peek at background offers is off until you turn it on in Settings");
            status("Peek paused: " + pause + ". Turn it on again in Settings.");
        }
    }

    private boolean startHere(Intent intent) {
        if (intent == null) return false;
        try {
            peekStarter.accept(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException refused) {
            return false;
        }
    }

    private boolean globalActionHere(int action) {
        try {
            return performGlobalAction(action);
        } catch (RuntimeException refused) {
            return false;
        }
    }

    // ---- Reading (scanner thread) ----

    /**
     * @param maxNodes how many nodes of the window Dasher can be read in are read at most; below
     *     {@link #MAX_SCAN_NODES} (a poll for Dasher's question), a window with more is left unread, not judged
     * @return true when another check should follow shortly
     */
    private boolean checkOffer(String trigger, long eventAt, int maxNodes) {
        if (scannerFaulted) return false;
        if (!phoneReadable()) { suspendScreenReading(); return false; }
        // A guarded return is already queued on main; no new read/tap may cross that boundary.
        if (peekReturning) return false;
        // Every read starts here (a notification's, a rules change's, a recheck's): none before the notice is accepted.
        if (!Consent.accepted(this)) return false;
        restoreRestartState();
        long started = SystemClock.uptimeMillis();
        long overlayAtStart = overlayTransition.get();
        long startedNanos = System.nanoTime();
        currentReadStartedAt = started;
        boolean hotAtStart = hot(started);
        scanNodes = 0;
        scanWindows = 0;
        remoteFetches = 0;
        readWindowsNanos = 0;
        readRootNanos = 0;
        readTraversalNanos = 0;
        lastReadEvents = dasherEvents.get();
        readWindowChanges = windowChanges.get();
        readTrigger = trigger;
        readWaitedMs = Math.max(0, started - eventAt);
        readCap = maxNodes;
        DasherScene before = scene;
        sceneLabels = null;
        readSkipped = false;
        readShowsQuestion = false;
        questionLookIncomplete = false;
        otherWindowsListed = 0;
        otherWindowsRead = 0;
        try {
            return checkReadableOffer();
        } catch (RuntimeException error) {
            endAuthority("screen read failed: " + error.getClass().getSimpleName(), true);
            DiagnosticLog.log(this, "accessibility",
                    "scan rejected; no further action: " + error.getClass().getSimpleName());
            ReportOutbox.fileAutomatic(this, ProblemReport.Kind.SCAN_ERROR, null, null, error);
            status("Screen read failed. No automatic action until a new readable screen.");
            return false;
        } finally {
            lastScanEndAt = SystemClock.uptimeMillis();
            if (offerEvidence && readWaitedMs > 0 && lastReadDurationMs > 0) {
                DiagnosticLog.log(this, "scan", "offer read waited " + readWaitedMs + " ms behind a "
                        + lastCompletedTrigger + " read of " + lastReadDurationMs + " ms");
            }
            lastReadDurationMs = lastScanEndAt - started;
            lastCompletedTrigger = trigger;
            declineState.readDuration(lastReadDurationMs);
            episode.readDuration(lastReadDurationMs);
            // After the read's decision and tap: what the tab and guide make of the screen, and the slow-read line.
            scene = sceneOfRead(before);
            if (sceneLabels != null && !readSkipped && overlayTransition.get() == overlayAtStart) {
                if (overlayApprovedTransition != overlayAtStart) overlayGiven = null;
                overlayApprovedTransition = overlayAtStart;
            }
            if (!quietTakenOver && (offerOnScreen || offerEvidence)) offerSeenAt = lastScanEndAt;
            syncAutomation();
            syncOverlay();
            noteSlowScan((System.nanoTime() - startedNanos) / 1_000_000L, trigger, started - eventAt,
                    hotAtStart || busy(lastScanEndAt));
        }
    }

    /**
     * What this read made of the screen. Any sign of an offer is an offer. A skipped read knows nothing new: an offer
     * seen before still counts as up, anything else becomes unknown. After the read was handled, so an idle screen has
     * already cleared the route it ends.
     */
    private DasherScene sceneOfRead(DasherScene before) {
        if (offerOnScreen || offerEvidence) return DasherScene.OFFER;
        if (readSkipped) return before == DasherScene.OFFER ? DasherScene.OFFER : DasherScene.UNKNOWN;
        if (sceneLabels == null) return DasherScene.UNKNOWN;
        return DasherScene.of(sceneLabels, ActiveRouteStore.load(this) != null);
    }

    /**
     * One compact line for a slow read, with where its time went (listing the windows, fetching roots, walking the
     * nodes): what the phone really costs, for the next report. Every one while an offer or confirmation is (or was
     * just) up or a decline is under way; while nothing is up, once a minute at most.
     */
    private void noteSlowScan(long tookMs, String trigger, long waitedMs, boolean offerUp) {
        if (tookMs < SLOW_SCAN_MS) return;
        long now = SystemClock.uptimeMillis();
        if (!offerUp) {
            if (now - slowLoggedAt < SLOW_SCAN_LOG_EVERY_MS) return;
            slowLoggedAt = now;
        }
        DiagnosticLog.log(this, "scan", "slow read: " + tookMs + " ms, " + scanNodes + " nodes, " + scanWindows
                + " windows, after " + trigger + " (waited " + Math.max(0, waitedMs) + " ms); windows "
                + readWindowsNanos / 1_000_000L + " ms, root " + readRootNanos / 1_000_000L + " ms, traversal "
                + readTraversalNanos / 1_000_000L + " ms; remote fetches " + remoteFetches + " (≥2 ms)"
                + (offerUp ? "; offer up" : ""));
    }

    /**
     * The touch watch runs exactly while a decline this service requested is in progress (from the Decline tap until
     * its confirmation closes, the offer is taken over, or the authority lapses) or a peek is under way. The silencer
     * runs within a decline, and only while the declined offer or its confirmation is what the screen shows.
     */
    private void syncAutomation() {
        if (silencer == null) return;
        long now = SystemClock.uptimeMillis();
        if (declineState.confirmationLapsed(now) && authorityEndReason.isEmpty()) {
            authorityEndReason = "offer countdown expired";
            confirmLog("authority ended: " + authorityEndReason);
            if (episode.questionWasSeen() && !episode.wasConfirmed() && declinedEntry != null) {
                DecisionLog.Entry expired = new DecisionLog.Entry(declinedEntry.at, declinedEntry.source,
                        declinedEntry.addOn, declinedEntry.facts, declinedEntry.requiredCents, declinedEntry.result,
                        declinedEntry.reason, DecisionLog.Action.CONFIRMATION_NOT_TAPPED, true, declinedEntry.evidence)
                        .withScore(declinedEntry.scorePercent).peeked(declinedEntry.peeked);
                onMain(() -> DecisionLog.record(this, expired));
            }
        }
        boolean declining = !stopped && declineUnderWay(now);
        OfferSnapshot under = declining ? declinedOffer : null;
        // Before the offer is published: the main thread's look at a click goes by both.
        declineBeganAt = declining ? firstTapAt : Long.MAX_VALUE;
        if (decliningOffer != under) decliningOffer = under;
        // For the main thread, which puts Dasher's events at the front of the scanner's queue while these hold.
        boolean busy = busy(now);
        if (busy) offerSeenAt = now;
        busyPublished = busy;
        hotUntil = offerSeenAt + HOT_MS;
        // A peek watches for the user's touches from its start to its end: any leaves Dasher up.
        boolean peeking = !stopped && peek.active() && (!peekReturning || peekWatched);
        Peek.Phase phase = stopped ? Peek.Phase.NONE : peek.phase();
        if (peekPhase != phase) peekPhase = phase;
        if (peekWatched != peeking) peekWatched = peeking;
        boolean watch = declining || peeking || now < lateCompletionUntil;
        int refusals = watchRefusals;
        if (refusals != watchRefusalsSeen) {
            // Android would not add the watch: it is not on, and is asked for again now while declining or peeking.
            watchRefusalsSeen = refusals;
            touchWatchOn = false;
        }
        if (watch != touchWatchOn) {
            touchWatchOn = watch;
            if (watch) watchAskedAt = now;
            // Ahead of whatever the main thread has queued (the history line, the tab): a touch from the decline tap
            // on must be noticed. Each run puts the watch as last asked, so the order of these runs does not matter.
            onMainFirst(syncWatch);
        }
        boolean quiet = declining && mayQuiet() && FilterStore.silenceWhileDeclining(this);
        // During a peek only Dasher's ring is turned down (the alarm stream): the app the user was in, a map's spoken
        // directions say, plays on media.
        if (quiet) silencer.start(peeking);
        else silencer.stop();
    }

    /**
     * Whether the declined offer's ring may be turned down: quiet only while that offer itself (or its confirmation)
     * is on screen, never over a next offer, even an unreadable one, and never after the service stopped. On the
     * scanner thread; the silencer asks again under its lock right before each stream goes down, so a next offer's
     * notification in between (handled on the main thread) leaves every stream up.
     */
    private boolean mayQuiet() {
        return !stopped && declinedOfferShowing && declineGeneration == OfferNotificationService.generation()
                && declineState.hasPendingConfirmation(SystemClock.uptimeMillis());
    }

    private boolean checkReadableOffer() {
        takeOverIfTouched();
        long now = SystemClock.uptimeMillis();
        // A notification from here on may be a newer offer: it revokes the confirmation of a decline tapped below.
        long generation = OfferNotificationService.generation();
        FilterSettings settings = FilterStore.load(this);
        minimumRulesChanged(settings, now);
        if (rulesEnabled == null || rulesEnabled != settings.enabled) {
            DiagnosticLog.log(this, "rules", "auto-decline " + (settings.enabled ? "resumed" : "paused") + " (settings)");
            rulesEnabled = settings.enabled;
        }
        if (!settings.enabled) endAuthority("auto-decline paused", true);
        offerOnScreen = false;
        offerEvidence = false;
        quietTakenOver = false;
        Look look = look();
        if (consumeDeclineError()) return declineError.pending();
        if (!look.activeKnown) {
            declineError.notBlank();
            readSkipped = true;
            return settings.enabled;
        }
        peekSaw(look, now);
        AccessibilityNodeInfo root = look.dasherRoot;
        if (root == null) {
            if (declineError.pending()) handBack(HandBack.NOT_TAPPED, "Dasher left the foreground during error recovery", now);
            if (declineState.hasPendingConfirmation(now)) noteLook("question not read: Dasher's window is unavailable");
            // Dasher off screen for a moment (the shade, recent apps, our own screen): an Accept tap waiting for its
            // delivery screen, and what came after an offer left, are kept; only what was on screen is forgotten.
            acceptedTracker.forgetVisible();
            offerAcceptTarget = null;
            offerDeclineTarget = null;
            return declineState.hasPendingConfirmation(now);
        }
        // Waiting for Dasher's question, a read of the window it is not in yet stops at a window change (the question's
        // own window, perhaps): the read for that change, first in the queue, reads the new window at once. A few in a
        // row at most, so a stream of window changes cannot keep every read from finishing.
        BooleanSupplier stop = null;
        if (awaitingConfirmation(now) && cutReads < MAX_CUT_READS) {
            long changes = readWindowChanges;
            stop = () -> windowChanges.get() != changes;
        } else if (!busyPublished && cutReads < MAX_CUT_READS) {
            long events = lastReadEvents;
            stop = () -> dasherEvents.get() != events && queued.get() == QUEUED_FRONT;
        }
        Scan scan = read(root, readCap, stop);
        if (consumeDeclineError()) return declineError.pending();
        scan.where = look.split ? "Dasher's half of the split screen" : "the active window";
        if (scan.abandoned) {
            declineError.notBlank();
            cutReads++;
            readSkipped = true;
            DiagnosticLog.log(this, "scan", "read cut short by a window change while the question is awaited, after "
                    + scan.visited + " nodes, " + (SystemClock.uptimeMillis() - now) + " ms");
            return true;
        }
        cutReads = 0;
        boolean pending = declineState.hasPendingConfirmation(now);
        boolean lookForQuestion = pending || episode.active(now);
        if (scan.truncated && readCap < MAX_SCAN_NODES) {
            declineError.notBlank();
            // A poll for Dasher's question reads a window only so far: a bigger one is left to the full reads. The
            // question may be in a window of its own beside it, though (0.4.42 looked nowhere else): look there.
            Scan elsewhere = lookForQuestion ? questionElsewhere(look) : null;
            if (elsewhere != null && pending) {
                if (ownsConfirmation(elsewhere)) {
                    offerOnScreen = true;
                    offerEvidence = true;
                    readShowsQuestion = DeclineConfirmation.hasPrompt(elsewhere.text);
                    return handleConfirmation(elsewhere, settings, now);
                }
                confirmLog(notTheDeclinedOffers(elsewhere));
            }
            if (awaitingConfirmation(now)) {
                noteQuestionMissing(scan.where + " is over " + readCap + " nodes, left to a full read; " + othersRead());
            }
            readSkipped = true;
            return true;
        }
        // A screen too big to read might be an offer: the guide stays off it too, and its changes are read at once.
        offerOnScreen = scan.truncated;
        offerEvidence = scan.truncated;
        if (scan.truncated) {
            declineError.notBlank();
            declinedOfferShowing = false;
            // Perhaps an offer: a peek does not go back over it for want of one.
            peek.offerSign();
            status("Offer screen exceeded safe read limits; no automatic action.");
            return false;
        }
        sceneLabels = scan.text;
        episode.readDuration(SystemClock.uptimeMillis() - currentReadStartedAt);

        OfferSnapshot offer = OfferParser.parse(scan.text, scan.metricParts);
        Scan confirmation = confirmationScan(scan, lookForQuestion, look);
        if (consumeDeclineError()) return declineError.pending();
        readShowsQuestion = confirmation != null && DeclineConfirmation.hasPrompt(confirmation.text);
        offerOnScreen = confirmation != null || scan.accept != null || scan.decline != null;
        if (handleDeclineError(scan, look, confirmation, offer, now)) return declineError.pending();
        // Anything of an offer, even partly drawn, and Dasher's next changes are read at once: they may complete it.
        offerEvidence = offerOnScreen || scan.acceptLabel || scan.declineLabel
                || DasherScene.showsNewOffer(scan.text)
                || AcceptedOfferTracker.offerFacts(offer, scan.text);
        // An offer's Accept or Decline, or a question: a peek no longer goes back for want of an offer. Figures alone
        // (a dash's earnings, navigation's distances) are not one.
        if (offerOnScreen || scan.acceptLabel || scan.declineLabel) peek.offerSign();
        // The declined offer's screen gave way (to its question, say): neither its Accept nor its facts show. The same
        // offer showing again from here on is the user going back to it, or Dasher closing a question we confirmed.
        if (episode.active(now) && scan.accept == null && !scan.acceptLabel && !offer.agreesWith(episode.offer())) {
            episode.screenLeft(now, readWindowChanges != firstTapWindows);
        } else if (scan.accept != null) {
            episode.offerPresent();
        }
        if (confirmation == null) {
            if (awaitingConfirmation(now)) {
                noteQuestionMissing(scan.where + " shows " + (scan.accept != null ? "the offer" : "no offer") + "; "
                        + othersRead());
            }
            // Our confirmation closed: no later dialog inherits the authority, even inside the window.
            if (declineState.confirmationSettled(now)) {
                confirmLog("Dasher closed the question " + (now - declineState.lastConfirmationAt())
                        + " ms after try " + declineState.confirmationTries());
                endAuthority("confirmed question closed", false);
            }
        } else if (!pending) {
            // Nothing to confirm. An offer that merely has a Back or Cancel button is still an offer to judge.
            if (isOfferScreen(confirmation)) confirmation = null;
        } else if (!ownsConfirmation(confirmation)) {
            confirmLog(notTheDeclinedOffers(confirmation));
            endAuthority("different offer", false);
            status("A different offer is showing; the earlier decline cannot confirm it. Judging it on its own.");
            confirmation = null;
        }
        if (confirmation != null) return handleConfirmation(confirmation, settings, now);

        if (scan.accept == null || scan.decline == null) {
            if (offerEvidence) noteEvidenceWithoutControls(offer);
            return handleOtherScreen(scan, offer, settings, now);
        }
        Dashing.seen(this);
        // The standalone parser deliberately ignores +$ / added-distance labels. A visible increment is still
        // offer evidence; parse without a stored route so it cannot supply figures missing from the screen.
        boolean incrementalFacts = AddOnOffer.isLikely(scan.text)
                && actionFact(AddOnOffer.parse(null, scan.text).incremental);
        if (!actionFact(offer) && !incrementalFacts) {
            noteLook("offer controls visible but figures are still being drawn; waiting");
            return settings.enabled;
        }
        if (scan.accept.equals(scan.decline)) {
            status("Ambiguous shared button target; no action.");
            return false;
        }
        offerReadAt = now;
        readIdentity = new ReadIdentity(offer, scan.text, SystemClock.uptimeMillis());
        generation = OfferNotificationService.generationForRead(generation, offer, scan.text, currentReadStartedAt);
        boolean handled = handleOffer(scan, offer, settings, now, generation);
        // After the offer was handled (and any decline tapped): a dash's diagnostics count its offers.
        DashDiagnostics.offerSeen(this);
        return handled;
    }

    private static boolean isErrorWord(CharSequence label) {
        return label != null && label.length() <= 32
                && "error".equals(OfferEvidence.normalize(label.toString()).toLowerCase(Locale.US)
                        .replaceAll("[.!…]+$", ""));
    }

    /** Consume the request-bound main-thread latch before a slow read can act on its earlier view of the world. */
    private boolean consumeDeclineError() {
        ErrorAttempt attempt = errorAttempt;
        if (attempt == null || !attempt.accepted || attempt.errorConsumed) return false;
        long eventAt = attempt.errorAt.get();
        if (eventAt < 0) return false;
        attempt.errorConsumed = true; // Scanner-owned; handBack can safely end this authority below.
        long now = SystemClock.uptimeMillis();
        // The event was fresh and bound to this successful request when received. A slow read crossing its
        // deadline (or a changed tap guard) removes recovery authority, not the evidence that it failed.
        // An ended/replaced request, however, must never apply its evidence to a subsequent offer.
        if (attempt != errorAttempt) return false;
        if (attempt.confirmation || !recoveryAuthority(attempt, now)
                || now - eventAt > DeclineErrorRecovery.ERROR_FRESH_MS) {
            handBack(HandBack.DASHER_ERROR, "Dasher reported an error after a decline request", now);
            return true;
        }
        if (!declineError.error(attempt, eventAt, now)) return false;
        DiagnosticLog.logOnChange(this, "confirm", "decline-error", "Dasher reported an error after a Decline request");
        // A read under way may have started before the error. Make a fresh complete observation next.
        declineError.notBlank();
        scheduleErrorRecovery();
        return true;
    }

    /** Still this automatic first step, never a manual decline, add-on or already seen confirmation. */
    private boolean recoveryAuthority(ErrorAttempt attempt, long now) {
        return errorRequestCurrent(attempt, now) && !attempt.confirmation
                && declineState.confirmationTries() == 0 && episode.active(now)
                && !episode.questionWasSeen() && !episode.wasConfirmed();
    }

    private boolean errorRequestCurrent(ErrorAttempt attempt, long now) {
        if (attempt == null || attempt != errorAttempt || !attempt.accepted || stopped || scannerFaulted
                || !Consent.accepted(this) || !phoneReadable() || takeover != Takeover.NONE
                || userActions.get() != attempt.actions || touchesHeld > 0
                || OfferNotificationService.generation() != attempt.generation
                || !declineState.hasPendingConfirmation(now)) return false;
        FilterSettings current = FilterStore.load(this);
        return current.enabled && current.minimumScalePercent == attempt.scale
                && current.perItemCents == attempt.perItemCents;
    }

    /**
     * An error signal blocks terminal-map interpretation. Only an empty/map-labelled, control-free full read can
     * authorize Back; an ambiguous idle/delivery map is left to the user after the short evidence window.
     */
    private boolean handleDeclineError(Scan scan, Look look, Scan confirmation, OfferSnapshot offer, long now) {
        if (!declineError.pending()) return false;
        ErrorAttempt attempt = errorAttempt;
        if (!recoveryAuthority(attempt, now) || declineError.expired(now)) {
            handBack(HandBack.NOT_TAPPED, "decline error recovery stopped; the offer could not be revalidated", now);
            return true;
        }
        if (confirmation != null) {
            // A question is handled by the normal confirmation path; never dismiss it with Back.
            declineError.clearSignal();
            return false;
        }
        if (questionLookIncomplete) {
            declineError.notBlank();
            scheduleErrorRecovery();
            return true;
        }
        if (scan.accept != null && scan.decline != null) return false; // handleOffer revalidates a return.
        boolean safeBlank = !scan.actionable && !scan.acceptLabel && !scan.declineLabel && !anyFact(offer)
                && !scan.truncated && look.dasherActive && !look.split && blankMapLabels(scan.text);
        if (!safeBlank) declineError.notBlank();
        if (declineError.returning()) {
            scheduleErrorRecovery();
            return true;
        }
        if (declineError.exhausted()) {
            handBack(HandBack.NOT_TAPPED, "Dasher's decline error repeated after two recovery attempts", now);
            return true;
        }
        if (!safeBlank || !declineError.blankReady(now)) {
            scheduleErrorRecovery();
            return true;
        }
        // Global Back acts on the focused app, unlike a node click in an inactive split half.
        if (lastReadEvents != dasherEvents.get() || readWindowChanges != windowChanges.get()
                || windowsChangedSinceLastLook() || !foregroundDasherForBack(look.dasherWindow)
                || !recoveryAuthority(attempt, SystemClock.uptimeMillis())
                || declineError.expired(SystemClock.uptimeMillis())
                || lastReadEvents != dasherEvents.get() || readWindowChanges != windowChanges.get()) {
            handBack(HandBack.NOT_TAPPED, "the screen changed before decline error recovery", now);
            return true;
        }
        declineError.backRequested(SystemClock.uptimeMillis()); // Consume first, even if Android refuses.
        boolean requested = globalActionHere(GLOBAL_ACTION_BACK);
        DiagnosticLog.log(this, "confirm", "decline error recovery Back " + (requested ? "requested" : "refused")
                + "; no decline completion inferred");
        if (!requested) {
            handBack(HandBack.NOT_TAPPED, "Android refused Back for the failed decline", SystemClock.uptimeMillis());
        } else {
            status("Dasher reported a decline error; checking the offer again after Back.");
            scheduleErrorRecovery();
        }
        return true;
    }

    private void scheduleErrorRecovery() {
        scanner.removeCallbacks(errorRecoveryCheck);
        scanner.postDelayed(errorRecoveryCheck, 150);
    }

    private static boolean blankMapLabels(List<String> labels) {
        for (String label : labels) {
            String word = OfferEvidence.normalize(label).toLowerCase(Locale.US);
            if (!word.isEmpty() && !word.equals("map") && !word.equals("map view")
                    && !word.equals("google map") && !isErrorWord(word)) return false;
        }
        return true;
    }

    private boolean foregroundDasherForBack(AccessibilityWindowInfo readWindow) {
        if (readWindow == null || !phoneReadable()) return false;
        List<AccessibilityWindowInfo> windows;
        try { windows = windowSource.get(); } catch (RuntimeException unavailable) { return false; }
        if (windows == null) return false;
        AccessibilityWindowInfo activeApp = null;
        int apps = 0;
        for (AccessibilityWindowInfo window : windows) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER
                    || window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    || (window.getType() == AccessibilityWindowInfo.TYPE_SYSTEM && window.isActive())) return false;
            if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                apps++;
                if (window.isActive()) activeApp = window;
            }
        }
        return apps == 1 && activeApp != null && activeApp.isFocused() && activeApp.getId() == readWindow.getId()
                && knownDasherVisible(readWindow.getId()) && activeWindowUncovered(windows, activeApp);
    }

    /**
     * Second step of a decline this service requested. Pausing or a newer offer revokes the authority. Every look that
     * finds it, and every try at its Decline, goes in the log. Its Decline is tried
     * {@link DeclineState#MAX_CONFIRMATION_TRIES} times at most (refused by Android, or taken and not acted on by
     * Dasher), 300 ms after refusals or 2–3 seconds after taken requests; then the offer is left to the user.
     */
    private boolean handleConfirmation(Scan confirmation, FilterSettings settings, long now) {
        if (consumeDeclineError()) return declineError.pending();
        if (minimumRulesChanged(FilterStore.load(this), SystemClock.uptimeMillis())) return false;
        diagnostic("confirmation", confirmation, null, null);
        declinedOfferShowing = true;
        if (!declineState.hasPendingConfirmation(now)) {
            if (episode.active(now) && !OfferParser.parse(confirmation.text, confirmation.metricParts)
                    .contradicts(episode.offer())) {
                // The question of the app's own decline, its authority over: never the user's decline. One the app
                // never managed to tap before its time ran out is left to the user, and the log says so.
                reportIfStuck(confirmation, now);
                if (authorityEndReason.isEmpty()) {
                    authorityEndReason = "offer countdown expired";
                    confirmLog("authority ended: " + authorityEndReason);
                }
                noteLook("declined offer's question seen " + (now - firstTapAt)
                        + " ms after the first Decline, after its authority ended (" + authorityEndReason + "); not tapped");
                if (settings.enabled && !episode.wasConfirmed() && declineState.confirmationLapsed(now)) {
                    handBack(HandBack.NOT_TAPPED, "its question remained after the offer countdown expired", now);
                }
                return false;
            }
            // Dasher asks about declining with no decline of ours under way: the user tapped Decline on an offer we
            // left alone (never an echo of a tap of ours, or of a decline the notification path requested). Nothing
            // is tapped here.
            if (DeclineConfirmation.hasPrompt(confirmation.text) && now - ownTapAt > OWN_TARGET_ECHO_MS
                    && !OfferNotificationService.declineActionWithin(NOTIFICATION_DECLINE_MS)) {
                acceptedTracker.declineQuestion(now);
                applyNotes();
            }
            return false;
        }
        if (!settings.enabled) {
            confirmLog("confirmation skipped: auto-decline is paused");
            return false;
        }
        if (declineGeneration != OfferNotificationService.generation()) {
            endAuthority("newer offer notification", true);
            confirmLog("confirmation skipped: a newer offer's notification came");
            status("A new notification arrived; old decline confirmation authority revoked.");
            return false;
        }
        questionLooks++;
        // A real owned question resumes its ordinary confirmation lifecycle. Back recovery has ended, but keep
        // the request's error latch until the confirmation request replaces it; a late error can still arrive.
        declineError.end();
        if (episode.active(now)) episode.questionSeen(now);
        if (countdown(confirmation.text) == 0 && !episode.wasConfirmed()) {
            handBack(HandBack.NOT_TAPPED, "its question ran out to 0:00 without a confirmation", now);
            return false;
        }
        int selected = DeclineConfirmation.select(confirmation.text, confirmation.declineLabels);
        reportIfStuck(confirmation, now);
        long at = SystemClock.uptimeMillis();
        int tries = declineState.confirmationTries();
        if (declineState.confirmationExhausted(at)) {
            handBack(HandBack.NOT_TAPPED, tries + " tries at its Decline in " + confirmation.where
                    + ", and Dasher still asks " + (at - declineState.lastTryAt()) + " ms after the last", now);
            return false;
        }
        String found = "found in " + confirmation.where;
        if (selected < 0) {
            noteLook("question " + found + ", but no Decline in it can be tapped (" + confirmation.declineLabels.size()
                    + " tappable)");
            return true;
        }
        if (!declineState.mayConfirm(at)) {
            noteLook("question " + found + (tries < DeclineState.MAX_CONFIRMATION_TRIES
                    ? "; try " + (tries + 1) + " is due " + declineState.retryDelay() + " ms after try " + tries
                    : "; waiting to see whether Dasher acts on try " + tries));
            scheduleConfirmRetry();
            return true;
        }
        boolean first = !declineState.confirmationTapped();
        if (first && confirmationFoundAt == NEVER) confirmationFoundAt = at;
        if (watchState == WATCH_DOWN && at - watchAskedAt < WATCH_HOLD_MS) {
            // The touch watch is not up yet (the main thread is busy): a touch now would go unnoticed and the user's
            // finger could land after our tap. Tapped the moment the watch is up, but never held longer than
            // WATCH_HOLD_MS after it was asked for: the touch is still checked right before the tap.
            noteLook("question " + found + "; held for the touch watch, asked " + (at - watchAskedAt) + " ms ago");
            heldConfirmation = confirmation;
            scanner.removeCallbacks(watchHoldOver);
            scanner.postAtTime(watchHoldOver, watchAskedAt + WATCH_HOLD_MS);
            return true;
        }
        heldConfirmation = null;
        scanner.removeCallbacks(watchHoldOver);
        AccessibilityNodeInfo target = confirmation.declineTargets.get(selected);
        boolean readable = dasherStillReadable(target);
        if (consumeDeclineError()) return declineError.pending();
        ErrorAttempt confirmationRequest = new ErrorAttempt(declineGeneration, userActions.get(), declineMinimumScale,
                declinePerItemCents, SystemClock.uptimeMillis(), true);
        errorAttempt = confirmationRequest;
        Tap tap = readable ? ownTap(target, declinedOffer, declineMinimumScale, declinePerItemCents) : Tap.REFUSED;
        if (tap == Tap.TAPPED) confirmationRequest.accepted = true;
        else if (errorAttempt == confirmationRequest) errorAttempt = null;
        if (tap == Tap.RULES_CHANGED) { staleMinimumScaleRead(); return false; }
        if (tap == Tap.HELD) {
            // A touch in split screen is being judged: tapped once it is found to be on Offer Filter's half (the
            // offer is handed back if it was on Dasher's), its authority checked again then.
            noteLook("question " + found + "; held while a touch in split screen is judged");
            heldConfirmation = confirmation;
            return true;
        }
        if (tap == Tap.TAKEN_OVER) confirmLog("confirmation skipped: the offer is the user's");
        if (tap == Tap.TAKEN_OVER || stopped) return false;
        long tappedAt = SystemClock.uptimeMillis();
        String attempt = "confirmation try " + (tries + 1) + "/" + DeclineState.MAX_CONFIRMATION_TRIES + " (read after "
                + readTrigger + ", look " + questionLooks + "): " + found;
        if (tap == Tap.TAPPED) {
            declineState.readDuration(tappedAt - currentReadStartedAt);
            declineState.confirmationSent(tappedAt);
            episode.confirmed();
            // The peeked offer's decline is fully requested: the next read without it takes the user back.
            if (peek.phase() == Peek.Phase.DECLINING && peek.owns(declinedOffer)) peek.confirmed();
            confirmLog(attempt + "; requested");
            if (first && firstTapAt != NEVER) {
                DiagnosticLog.log(this, "accessibility", "confirmation found " + (confirmationFoundAt - firstTapAt)
                        + " ms and tapped " + (tappedAt - firstTapAt) + " ms after the first Decline tap (read after "
                        + readTrigger + (watchState == WATCH_UP ? "" : "; touch watch not up") + ")");
            }
            if (declinedEntry != null) {
                recordRead(new DecisionLog.Entry(declinedEntry.at, declinedEntry.source,
                        declinedEntry.addOn, declinedEntry.facts, declinedEntry.requiredCents, declinedEntry.result,
                        declinedEntry.reason, DecisionLog.Action.CONFIRMATION_TAPPED, true, declinedEntry.evidence)
                        .withScore(declinedEntry.scorePercent).peeked(declinedEntry.peeked), -1, false);
            }
            status("Decline confirmation requested; waiting for Dasher to close the offer.");
        } else {
            declineState.confirmationRefused(tappedAt);
            confirmLog(attempt + "; refused: " + lastRefusal);
            if (declineState.confirmationExhausted(tappedAt)) {
                handBack(HandBack.NOT_TAPPED, DeclineState.MAX_CONFIRMATION_TRIES + " tries at its Decline in "
                        + confirmation.where + "; last refused (" + lastRefusal + ")"
                        + (declineState.confirmationTapped() ? "; an earlier try was taken" : "; all refused"), now);
                return false;
            }
        }
        // Read again when a retry is due, whether or not Dasher sends an event: closed, retried, or given up.
        scheduleConfirmRetry();
        return true;
    }

    /** A fresh read when the last try's refusal/closing interval ends, whatever else reads before it. */
    private void scheduleConfirmRetry() {
        long last = declineState.lastTryAt();
        if (stopped || last < 0) return;
        scanner.removeCallbacks(confirmRetry);
        confirmRetryPending = true;
        scanner.postAtTime(confirmRetry, last + declineState.retryDelay());
    }

    /** A line about Dasher's question that always goes in the log (a try, a skip that happens once). */
    private void confirmLog(String line) {
        flushLooks();
        DiagnosticLog.log(this, "confirm", line);
    }

    /**
     * A line about looking for Dasher's question that may come every read (every 100 ms while it is polled for): the
     * same line again is counted, not repeated, and the count goes in the log before the next different line.
     */
    private void noteLook(String line) {
        if (line.equals(questionMissed)) {
            questionMissRepeats++;
            return;
        }
        flushLooks();
        questionMissed = line;
        DiagnosticLog.log(this, "confirm", line);
    }

    private void flushLooks() {
        if (questionMissRepeats > 0) {
            DiagnosticLog.log(this, "confirm", "(the same line for " + questionMissRepeats + " more "
                    + (questionMissRepeats == 1 ? "read)" : "reads)"));
        }
        questionMissRepeats = 0;
        questionMissed = "";
    }

    /** A read while the question is awaited found none: where it looked, and why it found none. */
    private void noteQuestionMissing(String outcome) {
        questionLooks++;
        noteLook("question not found: " + outcome);
    }

    /** What this read made of Dasher's other windows while looking for the question, for the log. */
    private String othersRead() {
        if (otherWindowsListed == 0) return "no other window listed";
        return otherWindowsRead + " of Dasher's other windows read"
                + (questionLookIncomplete ? ", some left unread (too many, too big, or no root)" : "");
    }

    /**
     * Whether a confirmation-shaped surface is the confirmation of the offer we declined. A surface whose facts
     * contradict that offer is a different offer. A surface that still has an Accept button is the declined offer
     * with a dialog drawn over it, so it must positively show that offer; otherwise it is the next offer, perhaps
     * not fully drawn, and is judged on its own.
     */
    private boolean ownsConfirmation(Scan surface) {
        OfferSnapshot shown = OfferParser.parse(surface.text, surface.metricParts);
        if (shown.contradicts(declinedOffer)) return false;
        return surface.accept == null || shown.agreesWith(declinedOffer);
    }

    /** The log line for a question-shaped surface that {@link #ownsConfirmation} did not take for the declined offer's. */
    private String notTheDeclinedOffers(Scan surface) {
        boolean differs = OfferParser.parse(surface.text, surface.metricParts).contradicts(declinedOffer);
        return "confirmation skipped: found in " + surface.where + ", but it is not the declined offer's ("
                + (differs ? "its facts differ" : "it shows Accept without the declined offer's facts") + ")";
    }

    /** Distinct Accept and Decline targets with no question about declining: an offer, not a dialog. */
    private static boolean isOfferScreen(Scan scan) {
        return scan.accept != null && scan.decline != null && !scan.accept.equals(scan.decline)
                && !DeclineConfirmation.hasPrompt(scan.text);
    }

    /** A Dasher screen without both offer controls: delivery progress, idle, or an offer still loading. */
    private boolean handleOtherScreen(Scan scan, OfferSnapshot offer, FilterSettings settings, long now) {
        if (consumeDeclineError()) return declineError.pending();
        // A shopping declaration/count alone may be an offer still drawing over the idle map. It cannot
        // authorize a tap, establish completion, clear a stored route, or become an acceptance observation.
        if (!actionFact(offer) && AcceptedOfferTracker.itemOfferEvidence(offer, withParts(scan))) {
            if (!scan.acceptLabel && !scan.declineLabel) {
                if (offerTargetsEndedAt == NEVER) offerTargetsEndedAt = now;
                acceptedTracker.afterScreen(scan.text, true, now);
            }
            return settings.enabled;
        }
        if (OfferEvidence.isIdle(scan.text) || AcceptedOfferTracker.isDeliveryScreen(scan.text)
                || OfferEvidence.isDashOver(scan.text)) {
            if (episode.questionWasSeen() && !episode.wasConfirmed() && declineState.confirmationLapsed(now)
                    && declinedEntry != null) {
                recordRead(new DecisionLog.Entry(declinedEntry.at, declinedEntry.source, declinedEntry.addOn,
                        declinedEntry.facts, declinedEntry.requiredCents, declinedEntry.result, declinedEntry.reason,
                        DecisionLog.Action.CONFIRMATION_NOT_TAPPED, true, declinedEntry.evidence)
                        .withScore(declinedEntry.scorePercent).peeked(declinedEntry.peeked), -1, false);
            }
            // No offer is up: a re-post of a notification for an offer read before is a new offer.
            OfferNotificationService.screenOfferEnded();
            // Nor one a peek left with the user: its notification's key may be peeked at again.
            peek.offerEnded();
        }
        boolean offerGone = scan.accept == null && scan.decline == null && !scan.acceptLabel && !scan.declineLabel;
        // The first read without the last offer's controls: a click from now on is not on them.
        if (offerGone && offerTargetsEndedAt == NEVER) offerTargetsEndedAt = now;
        // An offer being drawn, less the figures the screen explains itself (navigation's, the dash summary's).
        boolean facts = AcceptedOfferTracker.offerFacts(offer, withParts(scan));
        if (peek.active() && peek.phase() != Peek.Phase.ARMING) {
            // Only on positive proof does the peek go back: the peeked offer's decline complete, and a screen Dasher's
            // own words explain, with none of an offer's facts (never an empty, partly drawn or unrecognised one).
            boolean explained = offerGone && explained(scan.text, facts);
            long recognisedBefore = peek.recognisedAt();
            peek.screen(explained, Peek.now());
            if (offerGone && declineProven(scan.text, facts, now)) {
                peekBack("the offer was declined", true, null);
            } else if (peek.recognisedAt() != recognisedBefore) {
                // With no offer yet, the wait for one starts at this first screen Dasher's words explain.
                schedulePeekTick();
            }
        }
        // The wait for offers, the dash's end or its home, with no sign of a delivery: a stored route is over (it
        // would otherwise hold the guide, the tab, update installs and what an acceptance needs, for hours).
        if (AcceptedOfferTracker.showsNoRoute(scan.text)) {
            ActiveRouteStore.clear(this);
            // Back to the wait for offers (or the dash's end): the decline is over, and the same offer again is new.
            episode.end();
        }
        OfferSnapshot missed = acceptedTracker.missedAcceptance(now);
        if (missed != null) {
            List<String> shown = new ArrayList<>(scan.text);
            DiagnosticLog.log(this, "accept", () -> "Not learned: no delivery screen recognized within 15 s after "
                    + "Accept on " + missed.summary() + "; screen now: " + PersonalText.kept(shown));
        }
        AcceptedOfferTracker.Acceptance accepted = acceptedTracker.observeOtherScreen(scan.text, facts, now);
        if (accepted != null) {
            recordAcceptance(accepted, "you tapped Accept, and Dasher showed a delivery screen");
            applyNotes();
            return false;
        }
        // Neither Accept nor Decline shows: the offer left alone has left the screen. What Dasher shows next is kept
        // in the screens log for a little while, and decides whether the user took it.
        long left = -1;
        if (!scan.acceptLabel && !scan.declineLabel) {
            left = acceptedTracker.leftFor(now);
            if (left < 0 && acceptedTracker.watchedSince() >= 0) left = 0;
            if (acceptedTracker.watchedSince() != aftermathOf) {
                aftermathOf = acceptedTracker.watchedSince();
                aftermathLines = 0;
            }
            noteNotificationDecline();
            acceptedTracker.afterScreen(scan.text, facts, now);
        }
        applyNotes();
        if (OfferEvidence.isIdle(scan.text) || OfferEvidence.isDashOver(scan.text)) {
            captureOtherScreen(scan, now, OfferEvidence.isPaused(scan.text) ? "dash paused" : "idle");
        }
        // Diagnostics after a dash (the user's opt-in) are filed when Dasher shows it ended; a cheap check when off.
        DashDiagnostics.screen(this, scan.text);
        if (OfferEvidence.isDashOver(scan.text)) {
            onMain(() -> ManualDeclines.dashEnded(this));
            Dashing.ended(this);
        } else if (OfferEvidence.isPaused(scan.text)) {
            Dashing.paused(this);
        } else if (OfferEvidence.isIdle(scan.text) || AcceptedOfferTracker.isDeliveryScreen(scan.text)) {
            Dashing.seen(this);
        } else if (OfferEvidence.isPreDashHome(scan.text)) {
            // Dasher's home before a dash (its "Dash" button): a decline held until the dash goes on was about
            // stopping. Whether a dash is on is left to the screens that say so.
            onMain(() -> ManualDeclines.dashEnded(this));
        }
        if (OfferEvidence.isIdle(scan.text)) {
            boolean pending = declineState.hasPendingConfirmation(now);
            ActiveRouteStore.clear(this);
            recoverLateCompletion(now, "wait for offers");
            endAuthority("idle", true);
            episode.end();
            forgetTakeover("idle");
            readIdentity = null;
            boundedOffer = null;
            RestartSuppression.clear(this);
            status(OfferEvidence.isPaused(scan.text) ? "Dasher is paused; this dash is still on."
                    : OfferEvidence.isDashOver(scan.text) ? "Dasher's dash ended."
                    : pending
                    ? "Dasher returned to the idle screen after decline; no active offer visible."
                    : "Dasher is finding offers.");
            return false;
        }
        // Declining an add-on returns straight to the delivery: no confirmation is coming after that.
        if (DasherScene.showsRoute(scan.text) && !facts && !scan.acceptLabel && !scan.declineLabel) {
            if (takeoverDisplayEligible && now <= takeoverDisplayUntil && takeover != Takeover.NONE) {
                step(takeover.offer, DecisionLog.StepKind.ACCEPTED_OBSERVED,
                        "delivery screen followed the offer you took over; display only, nothing learned");
            }
            takeoverDisplayEligible = false;
            endAuthority("delivery", false);
            episode.end();
            forgetTakeover("delivery");
            readIdentity = null;
            boundedOffer = null;
            RestartSuppression.clear(this);
        }
        if (scan.accept == null && scan.decline == null) {
            // With capture on, Dasher's other screens (a shopping list, an item, a delivery) are kept too, so their
            // wording can be learned from a shared report. Nothing is decided from them.
            // Not while a decline of ours is under way: that read is for its confirmation (and the next one keeps it).
            boolean aftermath = left >= 0 && left <= AFTERMATH_CAPTURE_MS && aftermathLines < AFTERMATH_LINES;
            if (!declineState.hasPendingConfirmation(now) && captureOtherScreen(scan, now, aftermath
                    ? "after an offer left (" + Math.round(left / 1000.0) + " s)" : "other") && aftermath) {
                aftermathLines++;
            }
            // A loading/map frame cannot replenish the still-pending offer's retry budget.
            if (!declineState.hasPendingConfirmation(now)) declineState.offerGone();
            return declineState.hasPendingConfirmation(now);
        }
        // Half an offer: still ours only if it positively shows the declined offer's facts.
        declinedOfferShowing = offer.agreesWith(declinedOffer);
        diagnostic("incomplete-controls", scan, offer, null);
        // A partial wallet page can expose an amount beside one offer-shaped control. Its parsed figures are
        // not offer evidence: status is persisted too, so unknown/payment surfaces keep only this generic message.
        String summary = PersonalText.recognizedDashScreen(withParts(scan)) ? offer.summary() + "\n" : "";
        status(summary + "Both offer controls are not yet readable; no action.");
        return settings.enabled;
    }

    /** Fixed vocabulary only; never raw labels or numerical values from an unclassified screen. */
    private void noteEvidenceWithoutControls(OfferSnapshot offer) {
        String evidence = (offer.payCents != null || offer.payAtMostCents != null ? "pay " : "")
                + (offer.miles != null ? "distance " : "") + (offer.minutes != null ? "duration " : "")
                + (offer.stops != null ? "stops " : "") + (offer.itemCountApplicable ? "items" : "");
        if (!evidence.isEmpty() && !evidence.equals(evidenceWithoutControls)) {
            DiagnosticLog.log(this, "screen", "offer evidence without controls: " + evidence.trim());
        }
        evidenceWithoutControls = evidence;
    }

    /**
     * An accepted offer: learned from (while auto-decline and the adaptive minimum are both on), its route kept, and
     * its history line told how it was seen and what it taught.
     *
     * @param how how the acceptance was seen, in fixed words and numbers
     */
    private void recordAcceptance(AcceptedOfferTracker.Acceptance accepted, String how) {
        // No peek for a minute after an acceptance: the user is busy with it.
        lastAcceptAt = Peek.now();
        // Accepting after all means an earlier Decline of this offer was backed out of.
        onMain(() -> ManualDeclines.dropped(this, "you accepted it after all"));
        DecisionLog.StepKind kind;
        String taught;
        if (accepted.addOn) {
            kind = DecisionLog.StepKind.ACCEPTED_ADD_ON;
            taught = "";
            DiagnosticLog.log(this, "accept", "Accepted an add-on: the standalone minimums do not learn from it");
        } else if (accepted.acceptedOffer.payCents != null) {
            boolean learned = FilterStore.recordAccepted(this, accepted.acceptedOffer);
            kind = learned ? DecisionLog.StepKind.ACCEPTED_LEARNED : DecisionLog.StepKind.ACCEPTED_NOT_LEARNED;
            taught = learned ? "" : "; auto-decline or the adaptive minimum was off";
            if (!accepted.tapSeen) {
                DiagnosticLog.log(this, "accept", "Accepted without a seen tap: " + accepted.acceptedOffer.summary()
                        + "; " + how);
            }
            DiagnosticLog.log(this, "accept", learned
                    ? "Learned from accepted " + accepted.acceptedOffer.summary()
                    : "Accepted " + accepted.acceptedOffer.summary()
                            + " but not learned: auto-decline or Adaptive minimum was off");
        } else {
            kind = DecisionLog.StepKind.ACCEPTED_NOT_LEARNED;
            taught = "; its pay was not read";
            DiagnosticLog.log(this, "accept", "Accepted " + accepted.acceptedOffer.summary()
                    + " but not learned: its pay was not read");
        }
        step(accepted.line, kind, how + taught);
        ActiveRouteStore.save(this, accepted.routeAfter);
        status("Acceptance observed. " + (accepted.addOn
                ? "Add-on route updated; standalone baseline unchanged."
                : kind == DecisionLog.StepKind.ACCEPTED_LEARNED
                        ? "Standalone minimums learned from this offer."
                        : "Standalone minimums unchanged."));
    }

    /** A readable offer with distinct Accept and Decline targets. Only a known failure is declined, at once. */
    private boolean handleOffer(Scan scan, OfferSnapshot offer, FilterSettings settings, long now, long generation) {
        if (consumeDeclineError()) return declineError.pending();
        int readCountdown = countdown(scan.text);
        boolean newCountdown = previousCountdown >= 0 && readCountdown >= 0
                && readCountdown * 1000L > previousCountdown * 1000L - (now - previousCountdownAt) + 3_000;
        boolean isAddOn = AddOnOffer.isLikely(scan.text);
        if (boundedOffer != null && now < boundedUntil && !newCountdown && !isAddOn
                && offer.agreesWith(boundedOffer) && (offer.payCents == null || offer.payCents <= boundedOffer.payAtMostCents)) {
            offer = new OfferSnapshot(null, offer.miles, offer.minutes, offer.stops, boundedOffer.payAtMostCents,
                    offer.finalStopHotspotMiles, offer.items, offer.itemCountApplicable);
        }
        if (offer.payAtMostCents != null) {
            boundedOffer = offer;
            if (boundedUntil <= now) boundedUntil = now + (readCountdown >= 0 ? readCountdown * 1000L + 3_000 : 30_000);
        } else { boundedOffer = null; boundedUntil = 0; }
        if (newCountdown) newScreenInstancePending = true;
        if (newCountdown && ((takeover != Takeover.NONE && offer.agreesWith(takeover.offer))
                || offer.agreesWith(declinedOffer))) {
            forgetTakeover("new instance (countdown)");
            endAuthority("new instance (countdown)", true);
            episode.end();
        }
        if (takeover != Takeover.NONE && takeoverGeneration != OfferNotificationService.generation()
                && OfferNotificationService.freshBackgroundGeneration() > takeoverGeneration) {
            newScreenInstancePending = true;
            forgetTakeover("fresh notification");
            endAuthority("fresh notification", true);
            episode.end();
        }
        if (declineError.pending() || declineError.hasBackAttempt()) {
            if (offer.contradicts(declinedOffer)) {
                // Positively different figures get the normal new-offer decision, with no inherited recovery.
                endAuthority("different offer during error recovery", true);
                episode.end();
            } else if (readCountdown < 0 || previousCountdown < 0
                    || !DeclineState.offerKey(offer, scan.text).equals(declinedKey)
                    || !recoveryAuthority(errorAttempt, SystemClock.uptimeMillis())) {
                // Partial facts or changed stable labels cannot create a new key and replenish any budget.
                handBack(HandBack.DASHER_ERROR, "the original offer and countdown could not be verified after its error", now);
                return false;
            } else if (declineError.returning()) {
                episode.returnedAfterErrorBack();
                declineError.clearSignal();
                DiagnosticLog.log(this, "confirm", "same offer revalidated after error recovery; normal retry limits remain");
            }
        }
        previousCountdown = readCountdown;
        previousCountdownAt = now;
        AddOnOffer addOn = isAddOn ? AddOnOffer.parse(ActiveRouteStore.load(this), scan.text) : null;
        OfferRule.Decision decision = isAddOn
                ? OfferRule.evaluateAddOn(addOn, settings) : OfferRule.evaluate(offer, settings);
        // An add-on's own pay is its explicit "+$" increment, which is never standalone pay.
        OfferSnapshot learn = isAddOn ? addOn.incremental : offer;
        OfferSnapshot routeAfter = isAddOn ? addOn.combined : offer;
        int secondsLeft = countdown(scan.text);
        boolean routeStored = ActiveRouteStore.load(this) != null;
        acceptedTracker.observeOffer(learn, routeAfter, isAddOn, decision.basis, secondsLeft, routeStored, now);
        offerAcceptTarget = scan.accept;
        offerDeclineTarget = scan.decline;
        offerTargetsAt = now;
        offerTargetsEndedAt = NEVER;
        String phase = isAddOn ? "add-on" : "offer";
        String detail = isAddOn ? addOn.summary() : offer.summary();
        String key = DeclineState.offerKey(offer, scan.text);
        boolean declines = settings.enabled && decision.result == OfferRule.Result.DECLINE;
        // The screen's line in the log comes after a decline's tap, so nothing delays the tap.
        if (!declines) diagnostic(phase, scan, offer, decision);
        if (isTakenOver(offer, now)) {
            if (declines) diagnostic(phase, scan, offer, decision);
            declinedOfferShowing = false;
            // Its decline was ours, even though the user has it now: a seen Accept tap is still learned from.
            acceptedTracker.offerDeclinedByApp(decision.basis, isAddOn, now);
            applyNotes();
            boolean wasPeeked = peekReads(offer);
            record(scan, isAddOn, decision, settings, takeover.action, wasPeeked);
            quietTakenOver = true;
            if (wasPeeked) peekOver("left Dasher up because you took over this offer");
            status(detail + (takeover.action == DecisionLog.Action.CONFIRMATION_NOT_TAPPED
                    ? "\nThe decline is unconfirmed and left to you; no automatic action."
                    : "\nYou took over this offer; no automatic action."));
            return false;
        }
        // A clearly different offer, or a long-expired takeover, ends it, and the decline before it.
        forgetTakeover(takeover != Takeover.NONE && now - takeover.at >= TAKEOVER_MS ? "2 min" : "different offer");
        if (episode.active(now) && offer.contradicts(episode.offer())) {
            recoverLateCompletion(now, "next offer");
            endAuthority("different offer", true);
            episode.end();
        } else if (lateCompletionUntil != 0 && offer.contradicts(declinedOffer)) {
            recoverLateCompletion(now, "next offer");
        }
        declinedOfferShowing = decision.result == OfferRule.Result.DECLINE
                && (key.equals(declinedKey) || offer.agreesWith(declinedOffer));

        if (!settings.enabled || decision.result != OfferRule.Result.DECLINE) {
            endAuthority("offer does not fail active rules", true);
            // Left alone: what the user does with it, and what Dasher shows after it, may teach. The step of the
            // offer before (another offer came first) goes to the main thread ahead of this offer's line.
            acceptedTracker.offerLeftAlone(decision.basis, learn, routeAfter, isAddOn,
                    settings.enabled && decision.result == OfferRule.Result.KEEP, secondsLeft, routeStored, now);
            noteNotificationDecline();
            applyNotes();
            boolean peeked = peekReads(offer);
            DecisionLog.Entry entry = record(scan, isAddOn, decision, settings, !settings.enabled
                    ? DecisionLog.Action.PAUSED
                    : decision.result == OfferRule.Result.KEEP ? DecisionLog.Action.PASSES
                    : DecisionLog.Action.NEEDS_REVIEW, peeked);
            // Passing or unclear, the peeked offer is the user's: Dasher stays up, or while they navigate it goes back
            // to the map with its card (the user's decision).
            if (peeked) {
                peek.screen(false, Peek.now());
                peek.countdown(secondsLeft, Peek.now());
                peekLeavesOffer(decision.result, offer, decision.reason, !settings.enabled);
            }
            if (decision.result == OfferRule.Result.REVIEW
                    && !OfferRule.onlyHotspotMissing(offer, addOn, settings)) reportUnreadable(scan, offer, entry);
            status(detail + "\n" + decision.summary() + (settings.enabled ? "" : "\nAuto-decline is off."));
            return settings.enabled && decision.result == OfferRule.Result.REVIEW;
        }
        // The declined offer again, after its screen gave way to its question (or any other screen): the user went
        // back to it, or Dasher closed the question we confirmed. Never a second first Decline, but once after
        // Dasher's glitch.
        boolean again = false;
        if (episode.active(now) && episode.covers(key, offer)) {
            DeclineEpisode.Back back = episode.offerShowing(now, readShowsQuestion || questionLookIncomplete);
            if (back == DeclineEpisode.Back.HAND_BACK || back == DeclineEpisode.Back.GIVE_UP) {
                diagnostic(phase, scan, offer, decision);
                declinedOfferShowing = false;
                acceptedTracker.offerDeclinedByApp(decision.basis, isAddOn, now);
                applyNotes();
                if (back == DeclineEpisode.Back.HAND_BACK) {
                    handBack(HandBack.BACK_TO_OFFER, "it showed again, and no tap on its question's Decline was "
                            + "taken", now);
                } else {
                    handBack(HandBack.NOT_TAPPED, "Dasher closed its question twice after the app confirmed it, and "
                            + "still shows the offer", now);
                }
                return false;
            }
            if (back == DeclineEpisode.Back.HOLD) {
                diagnostic(phase, scan, offer, decision);
                if (episode.holding(now)) {
                    // Decided GLITCH_MS after it showed again, whether or not Dasher sends an event by then.
                    scanner.removeCallbacks(episodeCheck);
                    scanner.postAtTime(episodeCheck, episode.backSince() + episode.glitchMs());
                    status(detail + "\nDasher closed its question but still shows the offer; waiting for it to close.");
                }
                return true;
            }
            if (back == DeclineEpisode.Back.DECLINE_AGAIN) {
                confirmLog("Dasher glitch: its question was confirmed and closed, but the offer still shows "
                        + (now - episode.backSince()) + " ms later and nothing of the user's was seen; one more Decline");
                again = true;
            }
        }
        if (!again && !declineState.mayDecline(key, now)) {
            diagnostic(phase, scan, offer, decision);
            if (key.equals(declinedKey)) reportIfStuck(scan, now);
            return declineState.hasPendingConfirmation(now);
        }
        // A restart may only recover suppression, never tap authority. Persist before Android can take the request.
        if ((!episode.active(now) || !episode.covers(key, offer))
                && !RestartSuppression.remember(this, offer, secondsLeft)) {
            status("Could not preserve this offer's restart guard; no automatic action.");
            return false;
        }
        // Finish metadata calls under the old request identity, then consume any error they received.
        boolean readable = dasherStillReadable(scan.decline);
        if (consumeDeclineError()) return declineError.pending();
        // Publish identity before the Android call so a fast toast can be copied while the call is under way.
        ErrorAttempt attempt = !isAddOn && !routeStored && scene != DasherScene.ROUTE
                && !episode.questionWasSeen() && !episode.wasConfirmed()
                ? new ErrorAttempt(generation, userActions.get(), settings.minimumScalePercent,
                        settings.perItemCents, SystemClock.uptimeMillis(), false)
                : null;
        errorAttempt = attempt;
        // Re-check that Dasher is still on screen just before acting: the screen can change while it is being read.
        Tap tap = readable ? ownTap(scan.decline, offer, settings.minimumScalePercent, settings.perItemCents) : Tap.REFUSED;
        if (tap != Tap.TAPPED && errorAttempt == attempt) errorAttempt = null;
        if (tap == Tap.RULES_CHANGED) { staleMinimumScaleRead(); return false; }
        long tappedAt = SystemClock.uptimeMillis();
        if (stopped) return false;
        diagnostic(phase, scan, offer, decision);
        if (tap == Tap.HELD) {
            // The declined offer again while a touch in split screen is judged: read again once it is.
            DiagnosticLog.log(this, "accessibility", "Decline held while a touch in split screen is judged: " + detail);
            return true;
        }
        // After the tap, so nothing delays it; ahead of this offer's line, so the offer before has its step first.
        acceptedTracker.offerDeclinedByApp(decision.basis, isAddOn, now);
        applyNotes();
        boolean peeked = peekReads(offer);
        if (tap == Tap.TAKEN_OVER) {
            declinedOfferShowing = false;
            if (peeked) peekOver("left Dasher up because you took over this offer");
            status(detail + "\nYou took over this offer; no automatic action.");
            return false;
        }
        if (tap == Tap.TAPPED) {
            boolean firstTap = !key.equals(declinedKey) || !declineState.hasPendingConfirmation(now);
            long remaining = secondsLeft >= 0 ? Math.max(0, secondsLeft * 1000L - (ownTapAt - now)) : -1;
            declineState.readDuration(tappedAt - currentReadStartedAt);
            declineState.declineSent(key, ownTapAt, remaining);
            if (attempt != null) {
                declineError.requested(attempt, attempt.at, declineState.declineAttempts());
                attempt.accepted = true;
            }
            authorityEndReason = "";
            declineGeneration = generation;
            declineMinimumScale = settings.minimumScalePercent;
            declinePerItemCents = settings.perItemCents;
            declinedOffer = offer;
            declinedOfferShowing = true;
            if (firstTap) {
                declinedKey = key;
                declinedHadRoute = routeStored || scene == DasherScene.ROUTE;
                declinedAt = ownTapAt;
                declinedDuringRoute = routeStored || scene == DasherScene.ROUTE;
                firstTapAt = ownTapAt;
                firstTapWindows = windowChanges.get();
                confirmationFoundAt = NEVER;
                questionLooks = 0;
                flushLooks();
            }
            episode.declined(key, offer, tappedAt);
            // Watch for a takeover and silence at once, before the history line is queued for the main thread.
            syncAutomation();
            scanner.removeCallbacks(syncAutomation);
            scanner.postAtTime(syncAutomation, declineState.confirmationUntil() + RECHECK_INTERVAL_MS);
            // A peek follows its offer's decline: back once it is complete (its question confirmed, or an add-on's
            // Decline) and Dasher's own words show the offer gone.
            if (peeked) {
                peek.declined(offer, isAddOn, routeStored);
                peek.countdown(secondsLeft, Peek.now());
            }
            declinedEntry = record(scan, isAddOn, decision, settings, DecisionLog.Action.DECLINE_TAPPED, peeked);
            // Dasher's question is looked for from now on, every CONFIRM_POLL_MS, instead of re-reading everything.
            startConfirmationPoll(tappedAt);
            scanner.removeCallbacks(declineRetry);
            long retryAt = declineState.nextDeclineAt();
            if (retryAt >= 0) scanner.postAtTime(declineRetry, retryAt);
            DiagnosticLog.log(this, "accessibility", "first-step Decline REQUESTED: " + detail
                    + "; read after " + readTrigger + " (waited " + readWaitedMs + " ms)"
                    + "; sound playing: " + OfferSilencer.playing(this));
            status("Decline requested: " + detail + "\n" + decision.summary());
        } else {
            DiagnosticLog.log(this, "accessibility", "first-step Decline REFUSED: " + lastRefusal);
            record(scan, isAddOn, decision, settings, DecisionLog.Action.DECLINE_REFUSED, peeked);
            if (peeked) peekOver("left Dasher up because Android refused the Decline");
            status("Decline click was not accepted by Android. No completion claimed.");
        }
        return true;
    }

    /**
     * A declined offer, or its confirmation, still on screen {@link #STUCK_MS} after the first Decline tap means
     * Dasher is still ringing for it: report what the screen shows, once per offer.
     */
    private void reportIfStuck(Scan scan, long now) {
        if (declinedKey.isEmpty() || declinedKey.equals(reportedStuck) || now - declinedAt < STUCK_MS) return;
        reportedStuck = declinedKey;
        List<String> labels = new ArrayList<>(scan.text);
        labels.addAll(scan.metricParts);
        String stage = !episode.questionWasSeen() ? "waiting for the question"
                : episode.wasConfirmed() ? "question requested; waiting for Dasher to close it"
                : "question seen; not confirmed";
        DiagnosticLog.log(this, "accessibility", "decline still showing after " + (now - declinedAt)
                + " ms; " + stage + "; first-step requests " + declineState.declineAttempts()
                + "/" + DeclineState.MAX_ATTEMPTS);
        ReportOutbox.fileAutomatic(this, ProblemReport.Kind.DECLINE_STUCK, declinedEntry, labels, null);
    }

    /** A visible offer the rules could not judge is a reading gap worth fixing: report it once per offer. */
    private void reportUnreadable(Scan scan, OfferSnapshot offer, DecisionLog.Entry entry) {
        String key = DeclineState.offerKey(offer, scan.text);
        if (key.equals(reportedOffer) || !ReportOutbox.enabled(this)) return;
        reportedOffer = key;
        List<String> labels = new ArrayList<>(scan.text);
        labels.addAll(scan.metricParts);
        ReportOutbox.fileAutomatic(this, ProblemReport.Kind.UNREADABLE_OFFER, entry, labels, null);
    }

    /** @param peeked whether this offer was read because a peek brought Dasher up for it: its line says so */
    private DecisionLog.Entry record(Scan scan, boolean addOn, OfferRule.Decision decision, FilterSettings settings,
                                     DecisionLog.Action action, boolean peeked) {
        List<String> labels = new ArrayList<>(scan.text);
        labels.addAll(scan.metricParts);
        DecisionLog.Entry entry = DecisionLog.Entry.of(DecisionLog.Source.SCREEN, addOn, decision.basis, decision,
                action, settings.enabled, labels).peeked(peeked);
        boolean freshInstance = newScreenInstancePending;
        newScreenInstancePending = false;
        recordRead(entry, OfferEvidence.secondsLeft(scan.text), true, freshInstance);
        return entry;
    }

    /**
     * Records a screen reading. Dasher's notifications of the same offer are folded into its line, and our card for
     * each is cleared, whether or not it rang (never Dasher's own notification): the screen has the offer now.
     *
     * <p>All of it happens in one go on the main thread, where Dasher's notifications are handled, so a notification
     * is folded either before this reading (and its card cleared here) or after it (and given no card), never both
     * or neither. A new reading is stamped as the history takes it, so a notification handled before it is earlier
     * and one handled after it later; a later step of a reading already recorded keeps that reading's time.
     */
    private void recordRead(DecisionLog.Entry entry, int secondsLeft, boolean newReading) {
        recordRead(entry, secondsLeft, newReading, false);
    }

    private void recordRead(DecisionLog.Entry entry, int secondsLeft, boolean newReading, boolean freshInstance) {
        onMain(() -> {
            DecisionLog.Entry stamped = newReading ? entry.withTime(System.currentTimeMillis()) : entry;
            for (DecisionLog.Entry notice : DecisionLog.record(this, stamped, secondsLeft, freshInstance)) {
                if (notice.alertTag == null) continue;
                OfferAlerts.clear(this, notice.alertTag);
                OfferNotificationService.readOnScreen(notice.alertTag, entry.facts);
            }
        });
    }

    /**
     * Dasher's question about declining. With no decline of ours under way, only in the window just read. With one
     * under way (its confirmation authority, or its episode): in the window just read when it asks the question; else
     * in Dasher's other windows ({@link #questionElsewhere}); else in the window just read when it shows a way back
     * beside Decline.
     *
     * @param look this read's look at the windows, moments ago
     */
    private Scan confirmationScan(Scan primary, boolean lookForQuestion, Look look) {
        boolean surface = DeclineConfirmation.isSurface(primary.text);
        if (!lookForQuestion) return surface ? primary : null;
        if (surface && DeclineConfirmation.hasPrompt(primary.text)) return primary;
        Scan elsewhere = questionElsewhere(look);
        if (elsewhere != null) return elsewhere;
        return surface ? primary : null;
    }

    /**
     * Dasher's question in Dasher's windows other than the one just read: the newest (front-most) first,
     * {@link #MAX_CONFIRM_WINDOWS} at most, each read to {@link #MAX_CONFIRM_NODES} at most (a question is a few nodes),
     * never the window just read again. A window left unread (one too many, one too big, or one whose root Android
     * would not give) makes this read's look incomplete: the declined offer showing beside it is not taken for the
     * user going back to it.
     */
    private Scan questionElsewhere(Look look) {
        List<AccessibilityWindowInfo> others = new ArrayList<>();
        for (AccessibilityWindowInfo window : look.windows) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || isOwnWindow(window.getId())) continue;
            // The window just read.
            if (window == look.dasherWindow
                    || (look.dasherWindow == null && look.dasherActive && window.isActive())) {
                continue;
            }
            others.add(window);
        }
        // Front-most first; Android lists them so already, and the sort keeps that order among equals.
        Collections.sort(others, (a, b) -> Integer.compare(b.getLayer(), a.getLayer()));
        otherWindowsListed = others.size();
        for (AccessibilityWindowInfo window : others) {
            if (otherWindowsRead >= MAX_CONFIRM_WINDOWS) {
                questionLookIncomplete = true;
                break;
            }
            AccessibilityNodeInfo root = look.roots.containsKey(window) ? look.roots.get(window)
                    : windowRoot(window, true);
            if (root == null) {
                questionLookIncomplete = true;
                continue;
            }
            if (!isDasher(root)) continue;
            otherWindowsRead++;
            Scan candidate = read(root, MAX_CONFIRM_NODES, null);
            candidate.where = "another window of Dasher's (layer " + window.getLayer() + ")";
            if (candidate.truncated) {
                questionLookIncomplete = true;
                continue;
            }
            if (DeclineConfirmation.isSurface(candidate.text)) return candidate;
        }
        return null;
    }

    /**
     * One bounded read of a window, its time counted for the slow-read line.
     *
     * @param stop asked before each node is fetched; when it says so, the read stops, {@link Scan#abandoned}
     */
    private Scan read(AccessibilityNodeInfo root, int maxNodes, BooleanSupplier stop) {
        long started = System.nanoTime();
        Scan scan = Scan.of(root, maxNodes, stop);
        readTraversalNanos += System.nanoTime() - started;
        scanNodes += scan.visited;
        remoteFetches += scan.remoteFetches;
        return scan;
    }

    /** Partial item/shopping evidence may hold a read, but never authorizes a click by itself. */
    private static boolean anyFact(OfferSnapshot offer) {
        return actionFact(offer) || offer.itemCountApplicable;
    }

    /** Existing offer facts required alongside both controls before a screen may be judged for a tap. */
    private static boolean actionFact(OfferSnapshot offer) {
        return offer.payCents != null || offer.payAtMostCents != null || offer.miles != null || offer.minutes != null
                || offer.stops != null;
    }

    /**
     * What happened to one of the app's own taps. {@link #HELD}: not made yet, because a touch in split screen is
     * still being judged (Offer Filter's half, or Dasher's); it is tried again once it is.
     */
    private enum Tap { TAPPED, REFUSED, TAKEN_OVER, HELD, RULES_CHANGED }

    /**
     * The app's own tap, unless the user touched the screen since this offer's decline began: then the offer is
     * theirs, and nothing is tapped. Checked right before the tap, so a touch in the middle of a read counts too.
     * Its click event, arriving just after, is not mistaken for the user's. While a touch in split screen is being
     * judged, nothing is tapped on the offer being declined.
     */
    private Tap ownTap(AccessibilityNodeInfo node, OfferSnapshot offer, int minimumScale, int perItemCents) {
        // Before the touches are taken: a touch judged the user's is counted before it stops being held.
        boolean held = touchesHeld > 0;
        takeOverIfTouched();
        long now = SystemClock.uptimeMillis();
        if (isTakenOver(offer, now)) return Tap.TAKEN_OVER;
        if (held && declineUnderWay(now) && !offer.contradicts(declinedOffer)) return Tap.HELD;
        // The service stopped (Accessibility turned off, an update) while this read was under way: nothing is tapped.
        if (stopped) return Tap.REFUSED;
        String refusal = clickRefusal(node);
        if (refusal != null) {
            // Nothing is asked of Android, so no echo can come: no echo window opens.
            lastRefusal = refusal;
            return Tap.REFUSED;
        }
        FilterSettings current = FilterStore.load(this);
        if (current.minimumScalePercent != minimumScale || current.perItemCents != perItemCents) {
            return Tap.RULES_CHANGED;
        }
        if (!phoneReadable() || scannerFaulted) return Tap.REFUSED;
        ownTapAt = now;
        ownTapTarget = node;
        // For the touch watch and the clicks: a touch from here until OWN_ACTION_ECHO_MS after the call returns is
        // this tap's echo, and so is a click on this node. Each call has a window of its own.
        ownTaps.began(node, now);
        boolean tapped = false;
        try {
            tapped = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } finally {
            ownTaps.ended(SystemClock.uptimeMillis(), tapped);
        }
        lastRefusal = tapped ? "" : "Android refused the click";
        return tapped ? Tap.TAPPED : Tap.REFUSED;
    }

    /** Whether Dasher can still be read, as just before a tap: its window is active, or its half of a split screen. */
    private boolean dasherStillReadable(AccessibilityNodeInfo target) {
        boolean visible = knownDasherVisible(target == null ? -1 : target.getWindowId());
        if (!visible) lastRefusal = "Dasher's target window is no longer visible";
        return visible;
    }

    /** Window metadata only: never asks an app's UI thread for a root, including on the notification main thread. */
    private boolean knownDasherVisible(int targetId) {
        if (!phoneReadable() || scannerFaulted) return false;
        android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (power != null && !power.isInteractive()) return false;
        List<AccessibilityWindowInfo> windows;
        try { windows = windowSource.get(); } catch (RuntimeException unavailable) { return false; }
        if (windows == null) return false;
        AccessibilityWindowInfo activeApp = null;
        boolean split = false;
        for (AccessibilityWindowInfo window : windows) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) split = true;
            if (window.isActive() && window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) activeApp = window;
            if (window.isActive() && window.getType() == AccessibilityWindowInfo.TYPE_SYSTEM) return false;
        }
        for (AccessibilityWindowInfo window : windows) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            boolean known = dasherWindowIds.contains(window.getId())
                    || (!realWindowId(window.getId()) && window == lastReadableWindow);
            if (!known || (realWindowId(targetId) && window.getId() != targetId)) continue;
            if (window == activeApp && activeWindowUncovered(windows, window)) return true;
            if (split && activeApp != null && SplitWindows.covered(windows, window, activeApp) == null) return true;
        }
        return false;
    }

    /** Active Dasher may omit bounds. Keep foreground behavior then, but never guess around a keyboard/system layer. */
    private static boolean activeWindowUncovered(List<AccessibilityWindowInfo> windows,
                                                 AccessibilityWindowInfo active) {
        Rect bounds = new Rect();
        active.getBoundsInScreen(bounds);
        if (!bounds.isEmpty()) return SplitWindows.covered(windows, active, null) == null;
        for (AccessibilityWindowInfo window : windows) {
            int type = window.getType();
            if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD || type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                return false;
            }
        }
        return true;
    }

    /** One look at the windows: what it found, before anything is published. */
    private static final class Look {
        /** Whether a window is active: its root was read, or it is one of Offer Filter's own (not asked for). */
        final boolean activeKnown;
        /** Whether Dasher's window is the active one. */
        final boolean dasherActive;
        /** Dasher's window to read: the active one, or Dasher's half of a split screen; null when neither. */
        final AccessibilityNodeInfo dasherRoot;
        /** The window that root is in, when Android listed it. */
        final AccessibilityWindowInfo dasherWindow;
        /** Whether Android's split-screen divider is listed. */
        final boolean split;
        /** Whether the screen is split with a window of Dasher's in it, shown to us or not. */
        final boolean dasherBeside;
        /** The windows Android listed (empty when it would not say). */
        final List<AccessibilityWindowInfo> windows;
        /** The roots this look already fetched, by listed window: not asked for again. */
        final java.util.Map<AccessibilityWindowInfo, AccessibilityNodeInfo> roots;
        /** The active application window, as listed; null when none is. */
        final AccessibilityWindowInfo activeApp;
        /** Why Dasher's half beside the active app is not really in view ({@link SplitWindows#covered}), or null. */
        final String covered;

        Look(boolean activeKnown, boolean dasherActive, AccessibilityNodeInfo dasherRoot,
             AccessibilityWindowInfo dasherWindow, boolean split, boolean dasherBeside,
             List<AccessibilityWindowInfo> windows,
             java.util.Map<AccessibilityWindowInfo, AccessibilityNodeInfo> roots, AccessibilityWindowInfo activeApp,
             String covered) {
            this.activeKnown = activeKnown;
            this.dasherActive = dasherActive;
            this.dasherRoot = dasherRoot;
            this.dasherWindow = dasherWindow;
            this.split = split;
            this.dasherBeside = dasherBeside;
            this.windows = windows;
            this.roots = roots;
            this.activeApp = activeApp;
            this.covered = covered;
        }
    }

    /**
     * Where Dasher can be read, as Android's windows show it now. Dasher's window is the active one, or Dasher's half
     * of a split screen while another app's half is the active window. While the shade, recents or any other system
     * surface is in front, nothing: Dasher is left alone then, as when it is off screen. With the screen not split,
     * only the active window's root is asked for. Offer Filter's own windows are known by their IDs, so their roots are
     * not asked for again.
     *
     * <p>Publishes nothing. {@code onScanner}: called on the scanner thread, so it may count the roots it asks for
     * and remember Offer Filter's own windows; otherwise (the notification path) it changes nothing at all.
     *
     * @param stop asked before each step (each a call into Android or Dasher); when it says so, the look stops
     * @return null when {@code stop} stopped it
     */
    private Look see(boolean onScanner, BooleanSupplier stop) {
        if (!phoneReadable()) return new Look(true, false, null, null, false, false,
                Collections.emptyList(), new java.util.IdentityHashMap<>(), null, "screen off or locked");
        List<AccessibilityWindowInfo> listed;
        long started = System.nanoTime();
        try {
            listed = windowSource.get();
        } catch (RuntimeException unavailable) {
            listed = null;
        }
        if (onScanner) readWindowsNanos += System.nanoTime() - started;
        if (listed == null) listed = Collections.emptyList();
        java.util.Map<AccessibilityWindowInfo, AccessibilityNodeInfo> roots = new java.util.IdentityHashMap<>();
        boolean split = false;
        AccessibilityWindowInfo activeApp = null;
        for (AccessibilityWindowInfo window : listed) {
            if (stop != null && stop.getAsBoolean()) return null;
            if (window.getType() == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) split = true;
            if (activeApp == null && window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && window.isActive()) {
                activeApp = window;
            }
        }
        if (stop != null && stop.getAsBoolean()) return null;
        for (AccessibilityWindowInfo window : listed) {
            if (window.isActive() && window.getType() == AccessibilityWindowInfo.TYPE_SYSTEM) {
                return new Look(true, false, null, null, split, false, listed, roots, activeApp,
                        "a system surface is active");
            }
        }
        boolean activeOurs = activeApp != null && isOwnWindow(activeApp.getId());
        boolean activeOther = activeApp != null && otherWindows.contains(activeApp.getId());
        AccessibilityNodeInfo activeRoot = activeOurs || activeOther ? null
                : activeApp == null ? activeRoot(onScanner) : windowRoot(activeApp, onScanner);
        if (activeRoot == null && !activeOurs && !activeOther) activeRoot = activeRoot(onScanner);
        boolean activeKnown = activeOurs || activeOther || activeRoot != null;
        boolean dasherActive = isDasher(activeRoot);
        // The active window may have changed between the list and the root: the root is the listed window's only
        // when their IDs agree.
        boolean activeRootListed = activeApp != null && activeRoot != null && sameWindow(activeRoot, activeApp);
        if (activeRootListed) roots.put(activeApp, activeRoot);
        if (!split) {
            // Full screen: Dasher is readable only as the active window.
            return new Look(activeKnown, dasherActive, dasherActive ? activeRoot : null,
                    dasherActive && activeRootListed ? activeApp : null, false, false, listed, roots, activeApp,
                    null);
        }
        AccessibilityWindowInfo otherActive = null;
        AccessibilityWindowInfo dasher = null;
        AccessibilityNodeInfo dasherWindowRoot = null;
        for (AccessibilityWindowInfo window : listed) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            if (stop != null && stop.getAsBoolean()) return null;
            AccessibilityNodeInfo root = isOwnWindow(window.getId()) || otherWindows.contains(window.getId()) ? null
                    : window == activeApp && activeRootListed ? activeRoot : windowRoot(window, onScanner);
            if (root != null) roots.put(window, root);
            if (!isDasher(root)) {
                if (window.isActive() && otherActive == null) otherActive = window;
                continue;
            }
            // The active Dasher window first, else the one in front.
            if (dasher == null || (window.isActive() && !dasher.isActive())
                    || (window.isActive() == dasher.isActive() && window.getLayer() > dasher.getLayer())) {
                dasher = window;
                dasherWindowRoot = root;
            }
        }
        // Beside the app the user is in, Dasher's half is read only while it is really in view: that app's window is
        // the other half (not recent apps, a pop-up or a window over Dasher's), and no keyboard or system surface
        // covers it.
        String covered = dasher != null && !dasherActive && otherActive != null
                ? SplitWindows.covered(listed, dasher, otherActive) : null;
        AccessibilityWindowInfo shown = dasher != null && (dasherActive || (otherActive != null && covered == null))
                ? dasher : null;
        AccessibilityNodeInfo dasherRoot = dasherActive ? activeRoot : shown == null ? null : dasherWindowRoot;
        return new Look(activeKnown, dasherActive, dasherRoot, shown, true, dasher != null, listed, roots, activeApp,
                covered);
    }

    /**
     * Looks at the windows ({@link #see}) and publishes what it found ({@link #screen}, and a sighting of Dasher
     * beside). The area is where Dasher is on screen, for the filter tab: its window's bounds (half the screen when
     * split), or the whole screen when Android does not say; null when Dasher is not on screen. Scanner thread only.
     */
    private Look look() {
        return look(null);
    }

    /**
     * As {@link #look()}, stopping at its next step when {@code stop} says so.
     *
     * @return null when stopped: nothing published
     */
    private Look look(BooleanSupplier stop) {
        long now = SystemClock.uptimeMillis();
        lastLookAt = now;
        Look seen = see(true, stop);
        if (seen == null) return null;
        lastLookWindows = windowsSignature(seen.windows);
        scanWindows = Math.max(scanWindows, seen.windows.size());
        if (seen.dasherBeside) dasherBesideAt = screenOnTime(now);
        for (java.util.Map.Entry<AccessibilityWindowInfo, AccessibilityNodeInfo> listed : seen.roots.entrySet()) {
            if (isDasher(listed.getValue())) rememberDasher(listed.getKey().getId());
        }
        Rect area = null;
        if (seen.dasherWindow != null) {
            Rect bounds = new Rect();
            seen.dasherWindow.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) area = bounds;
        }
        // The bounds of Dasher's own window: in split screen, only then is a touch known to be on its half or not.
        boolean bounded = area != null;
        if (area == null && seen.dasherActive) area = display();
        lastReadableWindow = seen.dasherWindow;
        Screen next = new Screen(true, seen.dasherRoot != null, area, seen.split, bounded);
        if (!next.equals(screen)) screen = next;
        readWin = SplitWindows.field(seen.windows, window -> ownerOf(window, seen), display());
        noteCover(seen.covered == null ? "" : seen.covered);
        return seen;
    }

    /**
     * Once per change: why Dasher's half beside the active app is left unread (the keyboard, a system surface or
     * another window over it), and when it is in view again.
     */
    private void noteCover(String cover) {
        if (cover.equals(coverNoted)) return;
        String was = coverNoted;
        coverNoted = cover;
        String win = readWin;
        if (!cover.isEmpty()) {
            DiagnosticLog.log(this, "split", "Dasher's half not read: " + cover + " (" + win + ")");
        } else if (!was.isEmpty()) {
            DiagnosticLog.log(this, "split", "Dasher's half in view again (" + win + ")");
        }
    }

    /** The display, as this service's resources give it (widened to the windows by {@link SplitWindows#field}). */
    private Rect display() {
        android.util.DisplayMetrics display = getResources().getDisplayMetrics();
        return new Rect(0, 0, display.widthPixels, display.heightPixels);
    }

    /** One of Dasher's windows, by ID, for the log's window field: a bounded set, as for Offer Filter's own. */
    private void rememberDasher(int windowId) {
        if (!realWindowId(windowId) || dasherWindowIds.contains(windowId)) return;
        if (dasherWindowIds.size() >= MAX_OWN_WINDOWS) dasherWindowIds.clear();
        dasherWindowIds.add(windowId);
    }

    /** Whose a listed application window is, from this look's roots, else from the windows known by ID. */
    private SplitWindows.Owner ownerOf(AccessibilityWindowInfo window, Look seen) {
        if (window == seen.activeApp && seen.dasherActive) return SplitWindows.Owner.DASHER;
        AccessibilityNodeInfo root = seen.roots.get(window);
        if (root == null) return knownOwner(window);
        if (isDasher(root)) return SplitWindows.Owner.DASHER;
        return getPackageName().contentEquals(nonNull(root.getPackageName())) ? SplitWindows.Owner.OURS
                : SplitWindows.Owner.OTHER;
    }

    /** Whose a listed application window is, by its ID alone (any thread): Dasher's, Offer Filter's, or another's. */
    private SplitWindows.Owner knownOwner(AccessibilityWindowInfo window) {
        int id = window.getId();
        if (isOwnWindow(id)) return SplitWindows.Owner.OURS;
        if (realWindowId(id) && dasherWindowIds.contains(id)) return SplitWindows.Owner.DASHER;
        return SplitWindows.Owner.OTHER;
    }

    /** The active window's root. On the scanner thread, counted, and remembered if it is Offer Filter's own. */
    private AccessibilityNodeInfo activeRoot(boolean onScanner) {
        long started = System.nanoTime();
        AccessibilityNodeInfo root = null;
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try { root = getRootInActiveWindow(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID
                    | AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE); }
            catch (IllegalStateException | IllegalArgumentException unsupportedPrefetch) { /* Fall back below. */ }
        }
        if (root == null) root = getRootInActiveWindow();
        if (onScanner) {
            long elapsed = System.nanoTime() - started;
            readRootNanos += elapsed;
            if (elapsed >= 2_000_000) remoteFetches++;
            rootFetches++;
            if (root != null) rememberIfOwn(root, root.getWindowId());
        }
        return root;
    }

    /** A listed window's root. On the scanner thread, counted, and remembered if it is Offer Filter's own. */
    private AccessibilityNodeInfo windowRoot(AccessibilityWindowInfo window, boolean onScanner) {
        long started = System.nanoTime();
        AccessibilityNodeInfo root = null;
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try { root = window.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID
                    | AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE); }
            catch (IllegalStateException | IllegalArgumentException unsupportedPrefetch) { /* Fall back below. */ }
        }
        if (root == null) root = window.getRoot();
        if (onScanner) {
            long elapsed = System.nanoTime() - started;
            readRootNanos += elapsed;
            if (elapsed >= 2_000_000) remoteFetches++;
            rootFetches++;
            if (root != null) rememberIfOwn(root, window.getId());
        }
        return root;
    }

    private void rememberIfOwn(AccessibilityNodeInfo root, int windowId) {
        if (!realWindowId(windowId)) return;
        if (isDasher(root)) { rememberDasher(windowId); return; }
        Set<Integer> set = getPackageName().contentEquals(nonNull(root.getPackageName())) ? ownWindows : otherWindows;
        if (set.size() >= MAX_OWN_WINDOWS) set.clear();
        set.add(windowId);
    }

    private boolean isOwnWindow(int windowId) {
        return realWindowId(windowId) && ownWindows.contains(windowId);
    }

    /** Whether a root is in that window, as far as their IDs say (a stand-in without one is taken to be). */
    private static boolean sameWindow(AccessibilityNodeInfo root, AccessibilityWindowInfo window) {
        int id = root.getWindowId();
        return !realWindowId(id) || id == window.getId();
    }

    /** Android's IDs for real windows; tests and stand-ins have none (-1). */
    private static boolean realWindowId(int windowId) {
        return windowId >= 0 && windowId != Integer.MAX_VALUE;
    }

    private static CharSequence nonNull(CharSequence text) {
        return text == null ? "" : text;
    }

    private static boolean isDasherPackage(CharSequence packageName) {
        return packageName != null && DASHER_PACKAGE.contentEquals(packageName);
    }

    private static boolean isDasher(AccessibilityNodeInfo node) {
        return node != null && isDasherPackage(node.getPackageName());
    }

    private static boolean hasClickAction(AccessibilityNodeInfo node) {
        return node.isClickable()
                || node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
    }

    /** The nearest visible, enabled, clickable node at or above a label. */
    private static AccessibilityNodeInfo clickTarget(AccessibilityNodeInfo node) {
        for (int depth = 0; depth < MAX_CLICK_TARGET_ANCESTORS && node != null; depth++, node = node.getParent()) {
            if (node.isVisibleToUser() && node.isEnabled() && hasClickAction(node)) return node;
        }
        return null;
    }

    /** Why a node cannot be clicked at all (nothing is then asked of Android), or null when it can. */
    private static String clickRefusal(AccessibilityNodeInfo node) {
        if (node == null) return "no control";
        if (!isDasher(node)) return "not Dasher's";
        if (!node.isEnabled()) return "not enabled";
        if (!node.isVisibleToUser()) return "not visible";
        if (!hasClickAction(node)) return "not clickable";
        return null;
    }

    /** The last other screen captured (navigation aside), and when. */
    private int lastOtherScreen;
    private long lastOtherScreenAt;
    /** When the last navigation screen was captured. */
    private long lastNavigationAt = NEVER;
    /** A screen whose only change is a number (a countdown, an ETA) is kept at most this often. */
    static final long SAME_SCREEN_MS = 60_000;
    /** Turn-by-turn navigation, whatever its words and numbers, is kept at most this often. */
    static final long NAVIGATION_SCREEN_MS = 60_000;

    /**
     * With capture on, one line per distinct other Dasher screen (at most one a second): its words, as read, into
     * the screens log. Numbers alone changing (a clock ticking) does not make a new screen more than once a minute.
     * Turn-by-turn navigation ({@link DasherScene#showsNavigation}: its speed and a distance) changes its words at
     * every turn, so it is kept at most once a minute whatever it says, on a budget of its own: it never holds back
     * another screen (the pickup leg after an Accept), and in the log it never pushes one of the last 30 minutes out
     * ({@link DiagnosticLog#logNavigation}). Only while a dash is on, or within {@link #CAPTURE_AFTER_OFFER_MS} of
     * the last offer read; and a payment, account or earnings screen is never kept, not even masked (one line says
     * so, at most once a minute: {@link DiagnosticLog#NOT_KEPT}).
     */
    private boolean captureOtherScreen(Scan scan, long now, String kind) {
        if (!DiagnosticLog.isEnabled(this)) return false;
        boolean afterOffer = offerReadAt != NEVER && now - offerReadAt < CAPTURE_AFTER_OFFER_MS;
        if (!afterOffer && !Dashing.on(this)) return false;
        if (DasherScene.showsNavigation(withParts(scan))) {
            if (lastNavigationAt != NEVER && now - lastNavigationAt < NAVIGATION_SCREEN_MS) return false;
            lastNavigationAt = now;
            DiagnosticLog.logNavigation(this, labelsLine(kind, scan, true));
            return true;
        }
        int words = (scan.text.toString() + scan.metricParts).replaceAll("[0-9]", "#").hashCode();
        long since = now - lastOtherScreenAt;
        if (since < 1000 || (words == lastOtherScreen && since < SAME_SCREEN_MS)) return false;
        lastOtherScreen = words;
        lastOtherScreenAt = now;
        Supplier<String> line = labelsLine(kind, scan, false);
        String win = readWin;
        DiagnosticLog.logScreen(this, () -> {
            String labels = line.get();
            return DiagnosticLog.NOT_KEPT.equals(labels) ? labels : labels + " " + win;
        });
        return true;
    }

    /** A read's labels and the metric parts joined from its sibling nodes ("0.4", "mi" read "0.4 mi"). */
    private static List<String> withParts(Scan scan) {
        List<String> labels = new ArrayList<>(scan.text);
        labels.addAll(scan.metricParts);
        return labels;
    }

    /**
     * The steps the acceptance tracker noted: each onto its offer's history line (on the main thread, after that
     * line) and into the log; an acceptance is learned from, a decline by hand held until the dash goes on, and an
     * unrecognised screen after an offer goes to the screens log. Then the tracker's next deadline is set.
     */
    private void applyNotes() {
        for (AcceptedOfferTracker.Note note : acceptedTracker.takeNotes()) {
            if (note.accepted != null) {
                recordAcceptance(note.accepted, note.detail);
                continue;
            }
            step(note.line, note.kind, note.detail);
            DiagnosticLog.log(this, "learn", note.line.summary() + ": " + note);
            if (note.screen != null) {
                List<String> screen = new ArrayList<>(note.screen);
                DiagnosticLog.logScreen(this, () -> PersonalText.accountScreen(screen) ? DiagnosticLog.NOT_KEPT
                        : "after an offer left, neither a delivery nor the wait for offers: labels="
                                + PersonalText.kept(screen));
            }
            if (note.declined != null) {
                OfferSnapshot declined = note.declined;
                long wall = System.currentTimeMillis();
                onMain(() -> ManualDeclines.declined(this, declined, wall));
            }
        }
        scanner.removeCallbacks(aftermathTick);
        long due = acceptedTracker.nextDeadline();
        if (due >= 0 && !stopped) scanner.postAtTime(aftermathTick, Math.max(due, SystemClock.uptimeMillis()));
    }

    /**
     * A decline requested through Dasher's notification lately: the offer watched is never taken as accepted without
     * a tap (Dasher's decline question is already never taken as the user's then).
     */
    private void noteNotificationDecline() {
        if (OfferNotificationService.declineActionWithin(NOTIFICATION_DECLINE_MS)) {
            acceptedTracker.declineRequestedElsewhere();
        }
    }

    /** A learning step onto an offer's history line, on the main thread after that line. */
    private void step(OfferSnapshot line, DecisionLog.StepKind kind, String detail) {
        if (line == null) return;
        onMain(() -> DecisionLog.markStep(this, line, kind, detail, STEP_WINDOW_MS));
    }

    /**
     * Seconds left on the offer's countdown: a label that is only the countdown ("0:35"), or Accept with it ("Accept
     * 0:30"); -1 when none shows.
     */
    static int countdown(List<String> labels) {
        int seconds = OfferEvidence.secondsLeft(labels);
        if (seconds >= 0) return seconds;
        for (String label : labels) {
            if (!OfferControls.isButton(label, "accept")) continue;
            java.util.regex.Matcher clock = java.util.regex.Pattern.compile("(\\d{1,2}):([0-5]\\d)")
                    .matcher(OfferEvidence.normalize(label));
            if (clock.find()) {
                int value = Integer.parseInt(clock.group(1)) * 60 + Integer.parseInt(clock.group(2));
                if (value <= 60) return value;
            }
        }
        return -1;
    }

    private void diagnostic(String phase, Scan scan, OfferSnapshot offer, OfferRule.Decision decision) {
        if (!DiagnosticLog.isEnabled(this)) return;
        // Where Dasher was (its half, which window is active, how much of the screen it has): a change, such as the
        // user touching Dasher's half, writes a new line.
        String signature = phase
                + "|" + (offer == null ? "" : offer.fingerprint())
                + "|" + (decision == null ? "" : decision.summary())
                + "|" + (scan.accept != null)
                + "|" + (scan.decline != null)
                + " " + readWin;
        if (signature.equals(lastDiagnosticSignature)) return;
        lastDiagnosticSignature = signature;
        DiagnosticLog.log(this, "screen", labelsLine(signature, scan, false));
    }

    /**
     * "{@code head} labels=[…] metricParts=[…]" for a log, built and masked ({@link PersonalText}) on the log's own
     * thread from copies taken here, so the screen reader never waits for it. Decisions use the raw labels. A
     * payment, account or earnings screen gives {@link DiagnosticLog#NOT_KEPT} alone: none of its words are kept.
     *
     * @param navigation whether the screen is known to be turn-by-turn navigation, whose streets are masked too (else
     *     the log's thread tells from the labels)
     */
    private static Supplier<String> labelsLine(String head, Scan scan, boolean navigation) {
        List<String> text = new ArrayList<>(scan.text);
        List<String> parts = new ArrayList<>(scan.metricParts);
        return () -> {
            if (PersonalText.accountScreen(text) || PersonalText.accountScreen(parts)) return DiagnosticLog.NOT_KEPT;
            List<String> all = new ArrayList<>(text);
            all.addAll(parts);
            if (!PersonalText.recognizedDashScreen(all)) return PersonalText.UNKNOWN_NOT_KEPT;
            boolean streets = navigation || DasherScene.showsNavigation(all);
            return head + " labels=" + PersonalText.mask(text, streets) + " metricParts="
                    + PersonalText.mask(parts, streets);
        };
    }

    /** The last status, shown on the main page: set on the main thread, in order with the notification path's. */
    private void status(String message) {
        if (message.equals(lastStatus)) return;
        lastStatus = message;
        DiagnosticLog.log(this, "status", message);
        onMain(() -> FilterStore.setLastStatus(this, message));
    }

    // ---- Values handed between threads ----

    /**
     * What a click event said, taken on the main thread as it came (Android recycles the event afterwards): its text,
     * its description and its node, whose surroundings the scanner reads.
     */
    private static final class Click {
        final List<String> text;
        final String description;
        final AccessibilityNodeInfo source;
        final long at;

        private Click(List<String> text, String description, AccessibilityNodeInfo source, long at) {
            this.text = text;
            this.description = description;
            this.source = source;
            this.at = at;
        }

        /**
         * @param at when the event reached the service (uptime); the click's own time is used when Android gives it,
         *     so a busy main thread never makes Offer Filter's own tap look like a later one of the user's
         */
        static Click of(AccessibilityEvent event, long at) {
            long when = event.getEventTime();
            if (when > 0 && when <= at) at = when;
            List<String> text = new ArrayList<>();
            for (CharSequence label : event.getText()) addTapLabel(text, label);
            CharSequence description = event.getContentDescription();
            AccessibilityNodeInfo source = null;
            try {
                source = event.getSource();
            } catch (RuntimeException unavailable) {
                // The click still counts by its text.
            }
            return new Click(text, description == null ? "" : OfferEvidence.normalize(description.toString()), source,
                    at);
        }

        /**
         * What the click was on, for the log: its node's class and the label its event or node holds. Only what the
         * event and its node already hold: nothing is asked of Dasher.
         */
        String shape() {
            String label = !text.isEmpty() ? String.join(" ", text) : description;
            String kind = "";
            if (source != null) {
                try {
                    CharSequence name = source.getClassName();
                    if (name != null) kind = name.toString().substring(name.toString().lastIndexOf('.') + 1);
                    if (label.isEmpty() && source.getText() != null) {
                        label = OfferEvidence.normalize(source.getText().toString());
                    }
                    if (label.isEmpty() && source.getContentDescription() != null) {
                        label = OfferEvidence.normalize(source.getContentDescription().toString());
                    }
                } catch (RuntimeException gone) {
                    // Named by what was read before the node went away.
                }
            }
            return (kind.isEmpty() ? "a control" : kind) + (label.isEmpty() ? "" : " \"" + label + "\"")
                    + (source == null ? ", no node" : "");
        }
    }

    /** A click still to be read around (scanner thread), and how often that was tried. */
    private static final class LateClick {
        final Click click;
        int tries;

        LateClick(Click click) {
            this.click = click;
        }
    }

    /** What a look at the windows found, for any thread. */
    private static final class ErrorAttempt {
        final long generation, actions, at;
        final int scale, perItemCents;
        final boolean confirmation;
        volatile boolean accepted;
        /** The first validated toast timestamp; no message or Android event is retained. */
        final AtomicLong errorAt = new AtomicLong(-1);
        /** Scanner-owned: each latched signal is applied at most once, including while a read is under way. */
        boolean errorConsumed;
        ErrorAttempt(long generation, long actions, int scale, int perItemCents, long at, boolean confirmation) {
            this.generation = generation; this.actions = actions; this.scale = scale; this.at = at;
            this.perItemCents = perItemCents;
            this.confirmation = confirmation;
        }
    }

    private static final class Screen {
        static final Screen UNKNOWN = new Screen(false, false, null, false, false);
        /** A look that failed: as Dasher not on screen. */
        static final Screen NOT_SHOWN = new Screen(true, false, null, false, false);
        /** Whether the service has looked at all since it connected. */
        final boolean known;
        /** Whether Dasher can be read: its window is active, or its half of a split screen while ours is active. */
        final boolean dasherReadable;
        /** Where Dasher is on screen, or null when it is not. */
        final Rect area;
        /** Whether Android's split-screen divider is listed. */
        final boolean split;
        /** Whether {@link #area} is the bounds of Dasher's own window (not the whole display, for want of them). */
        final boolean bounded;

        Screen(boolean known, boolean dasherReadable, Rect area, boolean split, boolean bounded) {
            this.known = known;
            this.dasherReadable = dasherReadable;
            this.area = area == null ? null : new Rect(area);
            this.split = split;
            this.bounded = bounded && area != null;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Screen)) return false;
            Screen that = (Screen) other;
            return known == that.known && dasherReadable == that.dasherReadable && split == that.split
                    && bounded == that.bounded && (area == null ? that.area == null : area.equals(that.area));
        }

        @Override public int hashCode() {
            return (known ? 1 : 0) + (dasherReadable ? 2 : 0) + (split ? 4 : 0) + (area == null ? 0 : area.hashCode());
        }
    }

    /** The offer the user took over, and when (uptime); {@link #NONE} for none. */
    private static final class Takeover {
        static final Takeover NONE = new Takeover(OfferSnapshot.UNKNOWN, 0);
        final OfferSnapshot offer;
        final long at;
        final DecisionLog.Action action;

        Takeover(OfferSnapshot offer, long at) {
            this(offer, at, DecisionLog.Action.USER_TOOK_OVER);
        }

        Takeover(OfferSnapshot offer, long at, DecisionLog.Action action) {
            this.offer = offer;
            this.at = at;
            this.action = action;
        }

        boolean covers(OfferSnapshot facts, long now) {
            return at != 0 && now - at < TAKEOVER_MS && !facts.contradicts(offer);
        }
    }

    /** What the tab and guide are given. */
    private static final class OverlayState {
        final Rect area;
        final boolean split;
        final DasherScene scene;

        OverlayState(Rect area, boolean split, DasherScene scene) {
            this.area = area == null ? null : new Rect(area);
            this.split = split;
            this.scene = scene;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof OverlayState)) return false;
            OverlayState that = (OverlayState) other;
            return split == that.split && scene == that.scene
                    && (area == null ? that.area == null : area.equals(that.area));
        }

        @Override public int hashCode() {
            return (split ? 1 : 0) + scene.ordinal() * 2 + (area == null ? 0 : area.hashCode());
        }
    }

    /** One bounded read of a window: distinct labels, joined metric siblings, and offer control targets. */
    private static final class Scan {
        int remoteFetches;
        private AccessibilityNodeInfo child(AccessibilityNodeInfo node, int index) {
            long began = System.nanoTime();
            try {
                AccessibilityNodeInfo child = null;
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    try { child = node.getChild(index, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID); }
                    catch (IllegalStateException | IllegalArgumentException unsupportedPrefetch) { /* Fall back below. */ }
                }
                return child == null ? node.getChild(index) : child;
            } finally { if (System.nanoTime() - began >= 2_000_000) remoteFetches++; }
        }
        final List<String> text = new ArrayList<>();
        final List<String> metricParts = new ArrayList<>();
        final List<String> declineLabels = new ArrayList<>();
        final List<AccessibilityNodeInfo> declineTargets = new ArrayList<>();
        AccessibilityNodeInfo accept;
        AccessibilityNodeInfo decline;
        /** Whether an Accept or Decline label showed, with or without a button to tap yet. */
        boolean acceptLabel;
        boolean declineLabel;
        /** Any visible enabled click target blocks global Back, even an unrecognized control. */
        boolean actionable;
        boolean truncated;
        int visited;
        /** Which window this read was of, for the log ("the active window", "another window of Dasher's"). */
        String where = "";
        /** Whether the read stopped early because it was told to (it is then {@link #truncated} too). */
        boolean abandoned;
        private int maxNodes = MAX_SCAN_NODES;
        private BooleanSupplier stop;

        /**
         * @param maxNodes nodes read at most; a window with more is {@link #truncated}
         * @param stop asked before each child is fetched (each a call into Dasher); null to read on regardless
         */
        static Scan of(AccessibilityNodeInfo root, int maxNodes, BooleanSupplier stop) {
            Scan scan = new Scan();
            scan.maxNodes = maxNodes;
            scan.stop = stop;
            scan.visit(root, 0);
            return scan;
        }

        /** @return the node's own label, or its only child's, so a parent can join split metric siblings */
        private String visit(AccessibilityNodeInfo node, int depth) {
            if (node == null) return "";
            if (depth > MAX_SCAN_DEPTH || visited++ >= maxNodes) {
                truncated = true;
                return "";
            }
            String own = "";
            if (node.isVisibleToUser()) {
                if (node.isEnabled() && hasClickAction(node)) actionable = true;
                addLabel(node, node.getText());
                addLabel(node, node.getContentDescription());
                own = OfferEvidence.normalize(node.getText() == null ? null : node.getText().toString());
                if (own.isEmpty() && node.getContentDescription() != null) {
                    own = OfferEvidence.normalize(node.getContentDescription().toString());
                }
            }
            List<String> childLabels = new ArrayList<>();
            int children = node.getChildCount();
            for (int i = 0; i < children && !truncated; i++) {
                if (stop != null && stop.getAsBoolean()) {
                    abandoned = true;
                    truncated = true;
                    break;
                }
                beforeNodeFetch();
                childLabels.add(visit(child(node, i), depth + 1));
            }
            metricParts.addAll(OfferParser.joinMetricSiblings(childLabels));
            return own.isEmpty() && children == 1 && childLabels.size() == 1 ? childLabels.get(0) : own;
        }

        private void addLabel(AccessibilityNodeInfo node, CharSequence value) {
            if (value == null) return;
            String label = OfferEvidence.normalize(value.toString());
            if (label.isEmpty()) return;
            if (label.length() > OfferEvidence.MAX_LABEL_LENGTH || text.size() >= OfferEvidence.MAX_LABELS) {
                truncated = true;
                return;
            }
            if (!text.contains(label)) text.add(label);
            if (OfferControls.isButton(label, "accept")) {
                acceptLabel = true;
                if (accept == null) accept = clickTarget(node);
            }
            if (OfferControls.isButton(label, "decline")) {
                declineLabel = true;
                AccessibilityNodeInfo target = clickTarget(node);
                if (target != null) {
                    if (decline == null) decline = target;
                    declineLabels.add(label);
                    declineTargets.add(target);
                }
            }
        }
    }
}
