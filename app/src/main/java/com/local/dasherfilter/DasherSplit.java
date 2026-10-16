package com.local.dasherfilter;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.List;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.WindowInsets;
import android.view.WindowMetrics;
import android.widget.Toast;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Split screen with Dasher, at the user's tap only, so Dasher stays on screen with its offers in view (and its offers
 * are still read and declined at once) while the map here is looked at.
 *
 * <p>A tap from full screen starts Dasher's launcher from the homepage, as its icon starts it, with
 * NEW_TASK | LAUNCH_ADJACENT, which the phone answers by putting Dasher beside this screen (0.6.7: 0.6.2's start,
 * restored at the owner's word, "it's still worse than before we tried to fix split", after 0.6.3's "Dasher first" and
 * 0.6.4's split pane). It is the one launch-adjacent start of Dasher: every other start of Dasher stays plain. The
 * owner's "dd is stuck again" reports of 10–12 October followed launch-adjacent starts of Dasher's launcher; why
 * Dasher's launcher sometimes stayed on its launch screen is not established, and the launch watch
 * ({@link OfferFilterService#launchWatch}) and its homepage line ({@link #LAUNCH_SCREEN_LINE}) stay for it. With no
 * split {@link #VERIFY_MS} later, recent apps open with the phone's words. During a delivery nothing of Dasher's is
 * started: recent apps open at once (the owner's 0.6.1 rule); so too while auto-decline is paused. A split the user
 * makes from recent apps opens nothing. Already split without Dasher beside, the button reads "Swap in Dasher" and
 * starts Dasher's launcher from this half, so the app beside stays. Each step goes in the log as a [split] line.
 */
final class DasherSplit {
    static final String DASHER_PACKAGE = "com.doordash.driverapp";
    /**
     * How long after the tap Dasher has to come up past its launch screen, with nothing of an offer up, before this
     * app's own task is started beside it; after that nothing more is started.
     */
    static final long PENDING_MS = 8_000;
    /** How long a tap that opened recent apps waits for the user to split the screen there (it opens nothing then). */
    static final long BY_HAND_MS = 60_000;
    /** How long after Dasher's launch-adjacent start the screen is looked at again, to see whether it split. */
    static final long VERIFY_MS = 1_500;
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    /** The button's words, as screen readers hear them: before a split, and split without Dasher beside. */
    static final String SPLIT_LABEL = "Split screen with Dasher";
    /**
     * Split without Dasher beside: Dasher takes Offer Filter's own half, so whatever is beside it (a map) stays (F18).
     */
    static final String SWAP_LABEL = "Swap in Dasher";
    /** The header's one-tap Dasher, full screen (F22). */
    static final String OPEN_LABEL = "Open Dasher";

    /** Asks Android to open recent apps; replaced only by tests. */
    static java.util.function.BooleanSupplier recents = OfferFilterService::openRecents;
    /** Whether Dasher is on a delivery ({@link OfferFilterService#dasherOnDelivery}). Replaced only by tests. */
    static java.util.function.Predicate<Context> onDelivery = OfferFilterService::dasherOnDelivery;
    /** What a tap does during a delivery instead of starting Dasher's launcher, for the log. */
    static final String DELIVERY_RECENTS = "recent apps instead of Dasher's launcher, which can leave Dasher on its "
            + "launch screen";
    /**
     * What a tap does while auto-decline is paused (off, or no rule set) instead of Dasher first, for the log: nothing
     * of Dasher's is read then, so no read can say when Dasher shows no offer, and nothing is split beside it.
     */
    static final String PAUSED_RECENTS = "recent apps instead of Dasher first: nothing of Dasher's is read while "
            + "paused, so no read can say no offer is up";

    /** The homepage's line while Dasher was seen only on its launch screen after a start of this app's. */
    static final String LAUNCH_SCREEN_LINE = "Dasher stuck on its launch screen?";
    /** That line's action, and what screen readers hear of the line. */
    static final String LAUNCH_SCREEN_ACTION = "Recent apps";
    static final String LAUNCH_SCREEN_SPOKEN = "Dasher may be stuck on its launch screen. " + LAUNCH_SCREEN_ACTION;
    /** Said over recent apps once that line's button opened them. */
    static final String PICK_DASHER_CARD = "Pick the Dasher card with your dash";
    /** Said when Android would not open recent apps for that line's button. */
    static final String PICK_DASHER_CARD_REFUSED = "Open recent apps, then pick the Dasher card with your dash";

    /** When the user last tapped Split (uptime), 0 when nothing is waiting. */
    private static volatile long requestedAt;
    /** How long that tap waits. */
    private static volatile long waitMs = PENDING_MS;
    /**
     * Offer Filter's screen was left since that tap (Dasher or recent apps over it): back on it not split, the tap is
     * over ({@link #resumed}), so Peek may bring Dasher up again at once.
     */
    private static volatile boolean away;
    /**
     * The number of the Split tap whose "Dasher first" start is under way (Dasher started, this app's own start beside
     * it not yet looked at), 0 for none. Written under the class lock, from the main and the scanner thread.
     */
    private static int pairing;
    /** Split taps counted, so a newer one is told from an older one. */
    private static int taps;
    /** This app's own launch-adjacent start for {@link #pairing} was asked for: the split it makes opens nothing. */
    private static boolean ownStarted;
    /** What to tell the user, on the main thread, should recent apps open after this app's own start; else a toast. */
    private static Consumer<String> tell;
    /** The tap opened recent apps: the user picks Dasher's own card there, and a split made there opens nothing. */
    private static volatile boolean byHandTap;
    /** Whether the screen was split when last logged, so entering and leaving split screen are logged once. */
    private static Boolean loggedSplit;
    /** The class of Dasher's launcher activity, as last resolved (any thread): the launch watch tells it apart. */
    private static volatile String launcherClass;

    /**
     * Dasher's own launch intent as its launcher icon starts it: its task comes to the front showing whatever it
     * was showing, such as an offer or its turn-by-turn navigation. Never launch-adjacent. Null when Dasher is not
     * installed.
     */
    static Intent launcher(Context context) {
        Intent launch = launcher(context, DASHER_PACKAGE);
        return launch == null ? null : asItsIconOpensIt(context, launch);
    }

    /**
     * {@code launch} with the flag Android's home screen adds to every app it opens (Launcher3: NEW_TASK |
     * RESET_TASK_IF_NEEDED). Without it, when Dasher's task began at another of its screens (Dasher opened from its
     * notification, as during a dash), Android starts a new launcher screen on top of that task: Dasher came up
     * showing "Searching for offers" while its navigation went on talking underneath (the owner, 8 October 2026, after
     * the Split button on 0.5.3; "this has happened before"). With it, an existing task comes to the front as it was
     * and nothing new is started on it. Never for an activity that clears its task when launched that way
     * (clearTaskOnLaunch; Dasher's launcher reports it does not), nor when that cannot be told: then as before.
     */
    static Intent asItsIconOpensIt(Context context, Intent launch) {
        ComponentName component = launch.getComponent();
        if (component == null) return launch;
        try {
            ActivityInfo info = context.getPackageManager().getActivityInfo(component, 0);
            if ((info.flags & ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH) == 0) {
                launch.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException unknown) {
            // Not known: launched as before, with nothing that could reset it.
        }
        return launch;
    }

    /** Resolve the actual launcher entry, not an INFO front door, and preserve the existing task. */
    static Intent launcher(Context context, String pkg) {
        try {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
            List<ResolveInfo> choices = context.getPackageManager().queryIntentActivities(query, 0);
            if (choices != null) for (ResolveInfo choice : choices) {
                ActivityInfo info = choice.activityInfo;
                if (info != null && info.exported && pkg.equals(info.packageName)
                        && launcherEnabled(context, info)) {
                    if (DASHER_PACKAGE.equals(pkg)) launcherClass = info.name;
                    // A package-bearing launch can create another start screen on older Android. Keep only the
                    // real launcher component/category; no task reset/clear, and no invented category on INFO.
                    return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(new ComponentName(info.packageName, info.name))
                            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
            }
        } catch (RuntimeException unavailable) {
            // Refuse rather than guess a different activity or reset its task.
        }
        return null;
    }

    /**
     * The class of Dasher's launcher activity as last resolved ({@link #launcher(Context, String)}), or null before
     * Dasher's launcher was looked up in this process. Any thread.
     */
    static String launcherClass() {
        return launcherClass;
    }

    /** An enabled alias can override android:enabled="false" without changing its manifest ActivityInfo. */
    private static boolean launcherEnabled(Context context, ActivityInfo info) {
        int state = context.getPackageManager().getComponentEnabledSetting(new ComponentName(info.packageName, info.name));
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && info.enabled);
    }

    /**
     * Whether the button shows: Dasher is installed and not already in the other half of a split screen (as the
     * screen reader last saw it). Before a split it splits the screen; split, it swaps Dasher into this half.
     *
     * @param dasherInstalled whether {@link #launcher(Context)} found Dasher, as the page last asked Android
     */
    static boolean offered(Activity activity, boolean dasherInstalled) {
        return dasherInstalled && !(inSplit(activity) && OfferFilterService.dasherBeside());
    }

    /**
     * Whether the user's tap on Split is still under way (any thread): Dasher started and this app not yet beside it,
     * this app's own start beside it not yet looked at, or recent apps opened for the user to split it there. No peek
     * meanwhile.
     */
    static boolean pending() {
        long at = requestedAt;
        long age = SystemClock.uptimeMillis() - at;
        return at != 0 && age >= 0 && age < waitMs;
    }

    /** What the button says to screen readers: {@link #SPLIT_LABEL}; once split, {@link #SWAP_LABEL}. */
    static String label(Activity activity) {
        return inSplit(activity) ? SWAP_LABEL : SPLIT_LABEL;
    }

    /**
     * Whether the header's Open Dasher shows (F22): Dasher installed, and this screen filling the screen (in split
     * screen the split button puts Dasher on screen).
     */
    static boolean opens(Activity activity, boolean dasherInstalled) {
        return dasherInstalled && !activity.isInMultiWindowMode();
    }

    /**
     * The user's tap on Open Dasher: Dasher's own launch intent, as its launcher icon opens it (full screen, its task as
     * it was; nothing cleared or reset). Never by itself.
     *
     * @return what to tell the user when Dasher could not be opened, else null
     */
    static String openDasher(Activity activity) {
        Intent dasher = launcher(activity);
        if (dasher == null) {
            log(activity, "Open Dasher: Dasher is not installed");
            return "Dasher is not installed.";
        }
        log(activity, "Open Dasher tapped: Dasher's launch intent");
        String failed = open(activity, dasher);
        if (failed == null) OfferFilterService.launchWatch(OfferFilterService.LAUNCH_OPEN_DASHER);
        return failed;
    }

    /** Android's multi-window flag includes floating windows and PiP. Only reject shapes we can distinguish. */
    static boolean inSplit(Activity activity) {
        return activity.isInMultiWindowMode() && !floating(activity);
    }

    private static boolean floating(Activity activity) {
        if (activity.isInPictureInPictureMode()) return true;
        if (!activity.isInMultiWindowMode()) return false;
        try {
            WindowMetrics current = activity.getWindowManager().getCurrentWindowMetrics();
            WindowMetrics maximum = activity.getWindowManager().getMaximumWindowMetrics();
            Rect available = new Rect(maximum.getBounds());
            Insets bars = maximum.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            available.set(available.left + bars.left, available.top + bars.top,
                    available.right - bars.right, available.bottom - bars.bottom);
            return floatingBounds(current.getBounds(), available,
                    Math.round(8 * activity.getResources().getDisplayMetrics().density));
        } catch (RuntimeException unavailable) {
            // Older/OEM APIs may not distinguish the modes. Do not invent a window type from unknown metrics.
            return false;
        }
    }

    /** An ordinary split pane spans one usable dimension; a small window inset on both axes does not. */
    static boolean floatingBounds(Rect current, Rect available, int tolerance) {
        return current != null && available != null && !current.isEmpty() && !available.isEmpty()
                && current.width() + tolerance < available.width()
                && current.height() + tolerance < available.height();
    }

    /** In a floating window: Android splits only a full-screen app. */
    static final String FLOATING_HINT = "Open " + AppName.NAME + " in full screen, then choose Split screen with Dasher.";
    /** Screen reading off: this app's own start beside Dasher is made by it. */
    static final String SCREEN_READING_FIRST = "Turn on screen reading first: the split goes through it.";
    /** Android refused to open recent apps too: the user opens them. */
    static final String RECENTS_REFUSED = "Open recent apps, then " + AppName.NAME + "'s icon → split screen, then "
            + "Dasher";

    /**
     * The tap. From full screen: during a delivery, recent apps; while auto-decline is paused (nothing of Dasher's read,
     * so no read can say no offer is up), recent apps too; otherwise Dasher's launcher beside this screen, launch
     * adjacent ({@link #adjacentFirst}). Already split: {@link #swapIn}.
     *
     * @param later what to tell the user should recent apps open after all (on the main thread, at most
     *     {@link #PENDING_MS} plus {@link #VERIFY_MS} later)
     * @return what to tell the user now (what to do in recent apps, or why it cannot be done), else null
     */
    static String start(Activity activity, Consumer<String> later) {
        // An earlier tap's start still under way ends here: this tap decides now.
        int earlier = pairing();
        if (earlier != 0 && endPairing(earlier)) log(activity, "not paired: a newer tap; nothing more opened");
        Intent dasher = launcher(activity);
        if (dasher == null) {
            log(activity, "tap: Dasher is not installed");
            return "Dasher is not installed.";
        }
        if (floating(activity)) {
            forgetTap();
            log(activity, "tap in a floating window: nothing started");
            return FLOATING_HINT;
        }
        if (activity.isInMultiWindowMode()) {
            forgetTap();
            return swapIn(activity, "tap in split screen");
        }
        if (!OfferFilterService.isConnected()) {
            log(activity, "tap: screen reading is off, nothing started");
            return SCREEN_READING_FIRST;
        }
        forgetTap();
        requestedAt = SystemClock.uptimeMillis();
        waitMs = PENDING_MS;
        if (onDelivery.test(activity)) {
            // Dasher's own launcher started beside this app during a delivery can leave Dasher's half on its launch
            // screen (the owner, 11 October 2026: Split tapped during Dasher's turn-by-turn navigation). Recent apps
            // move Dasher's existing screen, navigation and all, into the split without starting anything in it.
            log(activity, "tap during a delivery: " + DELIVERY_RECENTS);
            return byHand(activity);
        }
        if (OfferFilterService.readingPaused(activity)) {
            // Paused, nothing of Dasher's is read (the owner's "Read nothing"): no read could say Dasher shows no offer,
            // so nothing is split beside it by itself. Recent apps, where the user splits it with Dasher's own card.
            log(activity, "tap while paused: " + PAUSED_RECENTS);
            return byHand(activity);
        }
        return adjacentFirst(activity, dasher, later);
    }

    /**
     * Dasher's launcher started from this screen, in front full screen, with NEW_TASK | LAUNCH_ADJACENT (0.6.7, back
     * to 0.6.2's start: the owner, 13 October 2026, on 0.6.4's split pane: "it's still worse than before we tried to
     * fix split"). In 0.6.2 that start split his phone's screen in 125–270 ms at every tap of 12 October with Dasher's
     * offers read in its half seconds later; 0.6.4's pane split at once but Dasher took seconds to reach its half, and
     * its later look opened recent apps over a split that worked. The phone answers by putting Dasher beside this
     * screen; with no split {@link #VERIFY_MS} later, recent apps open with the phone's words. Main thread.
     */
    private static String adjacentFirst(Activity activity, Intent dasher, Consumer<String> later) {
        int gen;
        synchronized (DasherSplit.class) {
            gen = ++taps;
            pairing = gen;
            ownStarted = true;
            tell = later;
        }
        requestedAt = SystemClock.uptimeMillis();
        waitMs = VERIFY_MS + 1_000;
        Intent beside = new Intent(dasher).addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        try {
            activity.startActivity(beside);
        } catch (RuntimeException refused) {
            recentAppsAfterOwnStart(activity, gen, "Dasher's launch-adjacent start was refused: opening recent apps ("
                    + refused.getClass().getSimpleName() + ")");
            return null;
        }
        log(activity, "tap: Dasher's launch intent beside this screen (launch adjacent); looking again in "
                + VERIFY_MS + " ms");
        OfferFilterService.launchWatch(OfferFilterService.LAUNCH_SPLIT);
        Context app = activity.getApplicationContext();
        MAIN.postDelayed(() -> adjacentCheck(app, gen), VERIFY_MS);
        return null;
    }

    /**
     * {@link #VERIFY_MS} after Dasher's launch-adjacent start (main thread): split, nothing more is done; else recent
     * apps open with the phone's words (unless an offer is up or the app's own work is under way).
     */
    private static void adjacentCheck(Context context, int gen) {
        if (!ownStartCurrent(gen)) return;
        if (Boolean.TRUE.equals(loggedSplit)) {
            if (ownStartSplit(gen)) OfferFilterService.splitMadeByOwnStart(context);
            return;
        }
        OfferFilterService.beforeSplitRecents(context, "no split after Dasher's launch-adjacent start", true,
                () -> recentAppsAfterOwnStart(context, gen, "no split " + VERIFY_MS + " ms after Dasher's "
                        + "launch-adjacent start: opening recent apps"));
    }

    /**
     * Split beside another app, at the user's tap (the readiness line's Swap, or the header's button): Dasher's own
     * launch intent from this screen's half, without asking for the other half, so Dasher takes Offer Filter's own half
     * and the app beside it (the map) stays (F18; which half Android uses is phone-specific). During a delivery, recent
     * apps instead.
     *
     * @return what to tell the user when Dasher could not be opened, else null
     */
    static String swapIn(Activity activity) {
        return swapIn(activity, "swap");
    }

    private static String swapIn(Activity activity, String what) {
        Intent dasher = launcher(activity);
        if (dasher == null) {
            log(activity, what + ": Dasher is not installed");
            return "Dasher is not installed.";
        }
        if (OfferFilterService.dasherBesideNow()) {
            log(activity, what + ": Dasher already visible beside; nothing started");
            return null;
        }
        if (onDelivery.test(activity)) {
            log(activity, what + " during a delivery: " + DELIVERY_RECENTS);
            return byHand(activity);
        }
        log(activity, what + ": Dasher's launch intent into this half (the other half stays)");
        String failed = open(activity, dasher);
        if (failed == null) OfferFilterService.launchWatch(OfferFilterService.LAUNCH_SWAP);
        return failed;
    }

    /**
     * Recent apps, where the user splits the screen with Offer Filter's card and picks Dasher's own card for the other
     * half: nothing is started into it, and a split made there within {@link #BY_HAND_MS} opens nothing. Main thread.
     *
     * @return what to tell the user
     */
    static String byHand(Context context) {
        synchronized (DasherSplit.class) {
            pairing = 0;
            ownStarted = false;
        }
        requestedAt = SystemClock.uptimeMillis();
        waitMs = BY_HAND_MS;
        byHandTap = true;
        String phone = phone();
        if (recents.getAsBoolean()) {
            log(context, "recent apps opened, with the " + phone + " hint to pick Dasher's card");
            return hintWithDasher(phone);
        }
        byHandTap = false;
        requestedAt = 0;
        log(context, "Android refused to open recent apps");
        return RECENTS_REFUSED;
    }

    /**
     * Another app came up full screen in front while a tap waited (any thread, as a peek looks): the tap is over, and
     * nothing more of it opens, before or after this app's own start beside Dasher (its look {@link #VERIFY_MS} later
     * then finds it over and opens no recent apps).
     *
     * @param front what that app is, in words that never name it ("another app", "a navigation app")
     */
    static void abandon(Context context, String front) {
        if (!pending()) return;
        boolean pairingEnded;
        boolean afterOwnStart;
        synchronized (DasherSplit.class) {
            pairingEnded = pairing != 0;
            afterOwnStart = pairingEnded && ownStarted;
            if (pairingEnded) {
                pairing = 0;
                ownStarted = false;
                tell = null;
            }
        }
        requestedAt = 0;
        byHandTap = false;
        away = false;
        log(context, !pairingEnded ? "left recent apps for " + front + " without a split: the tap is over"
                : afterOwnStart ? "not paired: another app came to the front (no split after this app's own start); "
                        + "nothing more opened"
                : "not paired: another app came to the front; nothing more opened");
    }

    /** "pixel", "samsung" or "other": the phone's maker, for words that match what its recent apps show. */
    static String phone() {
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.US);
        if (maker.contains("google")) return "pixel";
        if (maker.contains("samsung")) return "samsung";
        return "other";
    }

    /**
     * What to do in recent apps, which are open already: split the screen from Offer Filter's card, then pick Dasher's
     * own card for the other half (nothing is started into it). Short enough for a toast's two lines
     * ({@link SetupFlow#TOAST_MOST}): Samsung's own words for the choice are long, so its hint names the icon
     * alone (One UI draws it above the card).
     */
    static String hintWithDasher(String phone) {
        switch (phone) {
            case "pixel":
                return "Tap " + AppName.NAME + "'s icon above its card → Split screen, then Dasher";
            case "samsung":
                return "Tap " + AppName.NAME + "'s icon → Open in split screen view, then Dasher";
            default:
                return "Tap " + AppName.NAME + "'s icon above its card → split screen, then Dasher";
        }
    }

    /** Whether the tap with this number is still waiting for this app's own start beside Dasher (any thread). */
    static synchronized boolean pairingCurrent(int gen) {
        return gen != 0 && pairing == gen && !ownStarted;
    }

    /** The number of the tap whose Dasher-first start is under way, 0 for none (any thread). */
    static synchronized int pairing() {
        return pairing;
    }

    /**
     * The screen reader is about to start this app's own task beside Dasher for that tap (scanner thread): from now on
     * the tap waits only for the look {@link #VERIFY_MS} later.
     *
     * @return false when that tap is over (nothing is started then)
     */
    static synchronized boolean ownStarting(int gen) {
        if (gen == 0 || pairing != gen || ownStarted) return false;
        ownStarted = true;
        requestedAt = SystemClock.uptimeMillis();
        waitMs = VERIFY_MS + 1_000;
        return true;
    }

    /** Whether this app's own start beside Dasher was made for that tap and not yet looked at (any thread). */
    static synchronized boolean ownStartCurrent(int gen) {
        return gen != 0 && pairing == gen && ownStarted;
    }

    /**
     * That tap's Dasher-first start is over without a split made by it (any thread): nothing more is started.
     *
     * @return whether it was still under way (whoever ends it says why, once)
     */
    static boolean endPairing(int gen) {
        synchronized (DasherSplit.class) {
            if (gen == 0 || pairing != gen) return false;
            pairing = 0;
            ownStarted = false;
            tell = null;
        }
        requestedAt = 0;
        away = false;
        return true;
    }

    /**
     * The screen split after this app's own start beside Dasher (any thread; from the page as it resumes split, or from
     * the screen reader's look {@link #VERIFY_MS} later, whichever comes first): nothing more opens.
     *
     * @return whether it was still waiting (the line is logged once)
     */
    static boolean ownStartSplit(int gen) {
        synchronized (DasherSplit.class) {
            if (gen == 0 || pairing != gen || !ownStarted) return false;
            pairing = 0;
            ownStarted = false;
            tell = null;
        }
        requestedAt = 0;
        away = false;
        return true;
    }

    /**
     * {@link #VERIFY_MS} after this app's own start beside Dasher found no split, or Android refused that start (main
     * thread, from the screen reader): recent apps open with the phone's words, where the user splits it by hand. The
     * page split meanwhile is the split made: nothing then.
     *
     * @param why what is logged first, in fixed words
     */
    static void recentAppsAfterOwnStart(Context context, int gen, String why) {
        Consumer<String> told;
        synchronized (DasherSplit.class) {
            if (gen == 0 || pairing != gen || !ownStarted) return;
            told = tell;
            tell = null;
        }
        if (Boolean.TRUE.equals(loggedSplit)) {
            if (ownStartSplit(gen)) OfferFilterService.splitMadeByOwnStart(context);
            return;
        }
        log(context, why);
        String said = byHand(context);
        if (said == null) return;
        if (told != null) {
            told.accept(said);
        } else {
            Toast.makeText(context.getApplicationContext(), said, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Offer Filter's screen paused (main thread): Dasher, or recent apps, came over it. Back on it full screen, the tap
     * is over ({@link #resumed}).
     */
    static void paused(Activity activity) {
        if (requestedAt != 0 || pairing() != 0) away = true;
    }

    /**
     * Offer Filter's screen resumed, or went into or out of split screen (main thread). A split this app's own start
     * made opens nothing more; nor does a split the user made after a tap that opened recent apps, or one before this
     * app's own start (the user picks Dasher's card), or a late or unasked one. Back on this screen full screen after
     * leaving it, the tap is over, unless this app's own start brought it back (its look {@link #VERIFY_MS} later says).
     */
    static void resumed(Activity activity) {
        int gen = pairing();
        if (requestedAt == 0 && gen == 0) return;
        if (!activity.isInMultiWindowMode()) {
            // Back on this screen full screen after leaving it (Dasher, or recent apps, came over it): no split was made.
            // The tap is over, so Peek may bring Dasher up again at once (the owner's report of 7 October 2026: an offer
            // 37 s after a refused tap got no peek, the user long back on this screen).
            if (!away) return;
            if (ownStartCurrent(gen)) return;
            boolean waiting = pending();
            boolean wasPairing = endPairing(gen);
            requestedAt = 0;
            byHandTap = false;
            away = false;
            if (wasPairing) {
                log(activity, "not paired: this app came back without a split; nothing more opened");
            } else if (waiting) {
                log(activity, "back on " + AppName.NAME + " without a split: the tap is over");
            }
            return;
        }
        away = false;
        if (floating(activity)) {
            endPairing(gen);
            forgetTap();
            log(activity, "floating window after the tap: nothing opened");
            return;
        }
        if (ownStartSplit(gen)) {
            OfferFilterService.splitMadeByOwnStart(activity);
            return;
        }
        long after = SystemClock.uptimeMillis() - requestedAt;
        boolean fresh = requestedAt != 0 && after < waitMs;
        boolean pickedByHand = byHandTap;
        if (endPairing(gen)) {
            // Split before this app's own start beside Dasher: the user (or the phone) made it; nothing is started.
            log(activity, "split " + after + " ms after the tap, before this app's own start: Dasher beside "
                    + (OfferFilterService.dasherBesideNow() ? "yes" : "no") + "; nothing opened");
            forgetTap();
            return;
        }
        forgetTap();
        if (!fresh) {
            log(activity, "split " + after / 1000 + " s after the tap: too late, nothing opened");
            return;
        }
        log(activity, "split " + after + " ms after the tap: Dasher beside "
                + (OfferFilterService.dasherBesideNow() ? "yes" : "no") + "; nothing opened"
                + (pickedByHand ? " (recent apps path)" : ""));
    }

    /** The tap is over, given up for what would meet recent apps: nothing waits. */
    static void forgetPending() {
        forgetTap();
    }

    /** The tap is over: nothing waits. */
    private static void forgetTap() {
        requestedAt = 0;
        byHandTap = false;
        away = false;
    }

    /** Offer Filter's own tasks, as Android lists them ({@link ActivityManager#getAppTasks}); -1 when it will not say. */
    static int ownTasks(Context context) {
        try {
            ActivityManager activities = context.getSystemService(ActivityManager.class);
            return activities == null ? -1 : activities.getAppTasks().size();
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }

    /** Offer Filter's screen entered or left split screen (or came back in it): logged once per change. */
    static void windowMode(Activity activity) {
        boolean inSplit = inSplit(activity);
        // A confirmed exit overrides the 20-second sighting grace; a later split with Maps is not beside Dasher.
        if (!inSplit) OfferFilterService.sawDasherBeside(0);
        if (loggedSplit != null && loggedSplit == inSplit) return;
        boolean first = loggedSplit == null;
        loggedSplit = inSplit;
        if (first && !inSplit) return;
        log(activity, inSplit ? "entered split screen" : "left split screen");
    }

    /**
     * The homepage's "Recent apps" on {@link #LAUNCH_SCREEN_LINE}, at the user's tap (main thread): recent apps, where
     * the user picks Dasher's card with the dash. Nothing else is done to Dasher.
     *
     * @return what to tell the user
     */
    static String recentAppsForLaunchScreen(Context context) {
        boolean opened = recents.getAsBoolean();
        log(context, "launch-screen line: Recent apps tapped" + (opened ? "" : "; Android refused to open them"));
        if (opened) OfferFilterService.launchScreenAnswered();
        return opened ? PICK_DASHER_CARD : PICK_DASHER_CARD_REFUSED;
    }

    private static String open(Activity activity, Intent dasher) {
        try {
            activity.startActivity(dasher);
            return null;
        } catch (ActivityNotFoundException | SecurityException refused) {
            return "Dasher could not be opened.";
        }
    }

    private static void log(Context context, String step) {
        DiagnosticLog.log(context, "split", step);
    }

    /** For tests: forget a pending split, Dasher's launcher class and the launch-screen line, and ask Android again. */
    static void forget() {
        synchronized (DasherSplit.class) {
            pairing = 0;
            ownStarted = false;
            tell = null;
        }
        requestedAt = 0;
        waitMs = PENDING_MS;
        away = false;
        byHandTap = false;
        loggedSplit = null;
        launcherClass = null;
        onDelivery = OfferFilterService::dasherOnDelivery;
        recents = OfferFilterService::openRecents;
        OfferFilterService.forgetLaunchScreen();
    }

    private DasherSplit() {}
}
