package com.local.dasherfilter;

import android.app.Activity;
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
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowInsets;
import android.view.WindowMetrics;
import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Split screen with Dasher, at the user's tap only: Offer Filter above, Dasher below, so Dasher stays on screen with
 * its offers in view (and its offers are still read and declined at once) while the map here is looked at. Android
 * gives apps no way to enter split screen themselves, so Offer Filter's screen reading asks for it, the same as
 * Android's own Split screen accessibility shortcut, and Dasher is then opened in the other half. Android saying it
 * took that request means only that it passed it on: many newer phones (Pixels from Android 13) then do nothing,
 * since split screen lives in the launcher's recent apps. So {@link #VERIFY_MS} after a request Android took, the
 * screen is looked at again, and when it did not split (or Android refused the request), recent apps are opened
 * instead with words for that phone, and once the user splits it there, Dasher opens in the other half (on a Pixel
 * the user picks Dasher there too). Already split without Dasher in the other half, the same button reads "Put Dasher
 * beside" and opens Dasher's own launch intent into the other half. Each step goes in the log as a [split] line.
 */
final class DasherSplit {
    static final String DASHER_PACKAGE = "com.doordash.driverapp";
    /** How long after the tap the screen may take to split before Dasher is no longer opened into it. */
    static final long PENDING_MS = 8_000;
    /** The same when the user splits it themselves from recent apps, which takes a few taps. */
    static final long BY_HAND_MS = 60_000;
    /** How long after a request Android took the screen is looked at again, to see whether it split. */
    static final long VERIFY_MS = 1_500;
    /** The button's words, as screen readers hear them: before a split, and split without Dasher beside. */
    static final String SPLIT_LABEL = "Split screen with Dasher";
    static final String BESIDE_LABEL = "Put Dasher beside";
    /**
     * Split without Dasher beside during a dash (Dasher's directions opened a map in its half, say): Dasher takes
     * Offer Filter's own half, so the map stays (F18).
     */
    static final String SWAP_LABEL = "Swap in Dasher";
    /** The header's one-tap Dasher, full screen (F22). */
    static final String OPEN_LABEL = "Open Dasher";

    /** Asks Android to split the screen, then to open recent apps; replaced only by tests. */
    static java.util.function.BooleanSupplier split = OfferFilterService::splitScreen;
    static java.util.function.BooleanSupplier recents = OfferFilterService::openRecents;

    /** When the user last tapped Split (uptime), 0 when nothing is waiting. */
    private static volatile long requestedAt;
    /** How long that tap waits for the split. */
    private static volatile long waitMs = PENDING_MS;
    /**
     * Offer Filter's screen was left since that tap (recent apps over it): back on it not split, no split was made
     * there, and the tap is over ({@link #resumed}), so Peek may bring Dasher up again at once.
     */
    private static volatile boolean away;
    /** The look {@link #VERIFY_MS} after a request Android took, while it is due. Main thread only. */
    private static Handler verifier;
    private static Runnable verify;
    /** Whether the screen was split when last logged, so entering and leaving split screen are logged once. */
    private static Boolean loggedSplit;

    /** Dasher's own launch intent, set to open beside this app in split screen; null when Dasher is not installed. */
    static Intent dasher(Context context) {
        Intent launch = launcher(context);
        if (launch == null) return null;
        return launch.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
    }

    /**
     * Dasher's own launch intent as its launcher icon starts it: its task comes to the front showing whatever it
     * was showing, such as an offer. Nothing in it is cleared or reset. Null when Dasher is not installed.
     */
    static Intent launcher(Context context) {
        return launcher(context, DASHER_PACKAGE);
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

    /** An enabled alias can override android:enabled="false" without changing its manifest ActivityInfo. */
    private static boolean launcherEnabled(Context context, ActivityInfo info) {
        int state = context.getPackageManager().getComponentEnabledSetting(new ComponentName(info.packageName, info.name));
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && info.enabled);
    }

    /**
     * Whether the button shows: Dasher is installed and not already in the other half of a split screen (as the
     * screen reader last saw it). Before a split it splits the screen; split, it puts Dasher beside.
     *
     * @param dasherInstalled whether {@link #dasher} found Dasher, as the page last asked Android
     */
    static boolean offered(Activity activity, boolean dasherInstalled) {
        return dasherInstalled && !(inSplit(activity) && OfferFilterService.dasherBeside());
    }

    /** Whether the user's tap on Split is still waiting for the screen to split (any thread): no peek meanwhile. */
    static boolean pending() {
        long at = requestedAt;
        long age = SystemClock.uptimeMillis() - at;
        return at != 0 && age >= 0 && age < waitMs;
    }

    /**
     * What the button says to screen readers: {@link #SPLIT_LABEL}; once split, {@link #BESIDE_LABEL}, or during a
     * dash {@link #SWAP_LABEL} (Dasher takes this half, so what is in the other half stays).
     */
    static String label(Activity activity) {
        if (!inSplit(activity)) return SPLIT_LABEL;
        return swaps(activity) ? SWAP_LABEL : BESIDE_LABEL;
    }

    /**
     * Whether a tap in split screen swaps Dasher into Offer Filter's own half instead of opening it in the other one: a
     * dash is under way, so the other half is most likely the map Dasher's directions opened, which should stay.
     */
    static boolean swaps(Context context) {
        return Dashing.now(context);
    }

    /**
     * Whether the header's Open Dasher shows (F22): Dasher installed, and this screen filling the screen (in split
     * screen the split button puts Dasher on screen; a short window has no room in its header).
     */
    static boolean opens(Activity activity, boolean dasherInstalled, boolean shortWindow) {
        return dasherInstalled && !shortWindow && !activity.isInMultiWindowMode();
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
        return open(activity, dasher);
    }

    /** Android's multi-window flag includes floating windows and PiP. Only reject shapes we can distinguish. */
    static boolean inSplit(Activity activity) {
        return activity.isInMultiWindowMode() && !floating(activity);
    }

    private static boolean floating(Activity activity) {
        if (activity.isInPictureInPictureMode()) return true;
        if (!activity.isInMultiWindowMode() || Build.VERSION.SDK_INT < 30) return false;
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

    private static String floatingHint() {
        return FLOATING_HINT;
    }

    /** In a floating window: Android splits only a full-screen app. */
    static final String FLOATING_HINT = "Open " + AppName.NAME + " in full screen, then choose Split screen with Dasher.";
    /** Screen reading off: Android's split action is asked through it. */
    static final String SCREEN_READING_FIRST = "Turn on screen reading first: the split goes through it.";
    /** Android refused to open recent apps too: the user opens them. */
    static final String RECENTS_REFUSED = "Open recent apps, then " + AppName.NAME + "'s icon → split screen";

    /**
     * The tap. Already split, Dasher opens in the other half; otherwise the screen is split first and Dasher opens
     * when it is (see {@link #resumed}).
     *
     * @param later what to tell the user should the screen not split after all (on the main thread)
     * @return what to tell the user now (what to do in recent apps, or why it cannot be done), else null
     */
    static String start(Activity activity, Consumer<String> later) {
        cancelVerify();
        Intent dasher = dasher(activity);
        if (dasher == null) {
            log(activity, "tap: Dasher is not installed");
            return "Dasher is not installed.";
        }
        if (floating(activity)) {
            requestedAt = 0;
            log(activity, "tap in a floating window: no adjacent launch requested");
            return floatingHint();
        }
        if (activity.isInMultiWindowMode()) {
            requestedAt = 0;
            if (OfferFilterService.dasherBesideNow()) {
                log(activity, "tap in split screen: Dasher already visible beside; no launch requested");
                return null;
            }
            if (swaps(activity)) return swapIn(activity);
            log(activity, "tap in split screen: Dasher's launch intent into the other half");
            return open(activity, dasher);
        }
        if (!OfferFilterService.isConnected()) {
            log(activity, "tap: screen reading is off, nothing asked");
            return SCREEN_READING_FIRST;
        }
        requestedAt = SystemClock.uptimeMillis();
        waitMs = PENDING_MS;
        away = false;
        if (split.getAsBoolean()) {
            log(activity, "tap: Android took the split request; looking again in " + VERIFY_MS + " ms");
            verifyLater(activity, later);
            return null;
        }
        // From Android 15 the split action is SystemUI's to offer, and many phones offer none: say so when Android can.
        log(activity, "tap: Android refused the split request"
                + (Boolean.FALSE.equals(OfferFilterService.splitActionListed())
                        ? " (this phone lists no split action for apps)" : ""));
        return byHand(activity);
    }

    /**
     * Split beside another app during a dash, at the user's tap (the readiness line's Swap, or the header's button):
     * Dasher's own launch intent from this screen's half, without asking for the other half, so Dasher takes Offer
     * Filter's own half and the app beside it (the map) stays (F18; which half Android uses is phone-specific).
     *
     * @return what to tell the user when Dasher could not be opened, else null
     */
    static String swapIn(Activity activity) {
        Intent dasher = launcher(activity);
        if (dasher == null) {
            log(activity, "swap: Dasher is not installed");
            return "Dasher is not installed.";
        }
        if (OfferFilterService.dasherBesideNow()) {
            log(activity, "swap: Dasher already visible beside; no launch requested");
            return null;
        }
        log(activity, "tap in split screen during a dash: Dasher's launch intent into this half (the other half stays)");
        return open(activity, dasher);
    }

    /**
     * The screen did not split for the request: recent apps are opened, where the user splits it, and Dasher opens in
     * the other half once it is split within {@link #BY_HAND_MS}.
     *
     * @return what to tell the user
     */
    private static String byHand(Activity activity) {
        requestedAt = SystemClock.uptimeMillis();
        waitMs = BY_HAND_MS;
        String phone = phone();
        if (recents.getAsBoolean()) {
            log(activity, "recent apps opened, with the " + phone + " hint; Dasher opens beside a split made within "
                    + BY_HAND_MS / 1000 + " s");
            return hint(phone);
        }
        requestedAt = 0;
        log(activity, "Android refused to open recent apps");
        return RECENTS_REFUSED;
    }

    /**
     * Another app came up full screen in front while a tap waited (any thread, as a peek looks): recent apps were
     * left for it without a split, and the tap is over.
     *
     * @param front what that app is, in words that never name it ("another app", "a navigation app")
     */
    static void abandon(Context context, String front) {
        if (!pending()) return;
        requestedAt = 0;
        away = false;
        log(context, "left recent apps for " + front + " without a split: the tap is over");
    }

    /** "pixel", "samsung" or "other": the phone's maker, for words that match what its recent apps show. */
    static String phone() {
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.US);
        if (maker.contains("google")) return "pixel";
        if (maker.contains("samsung")) return "samsung";
        return "other";
    }

    /**
     * What to do in recent apps. On a Pixel the user picks Split screen on Offer Filter's card and then Dasher's card;
     * on a Samsung "Open in split screen view" puts Offer Filter in one half at once and Dasher then opens in the
     * other by itself; elsewhere, the general words.
     */
    static String hint(String phone) {
        // Shown over recent apps, which are open already: what to tap, short enough for a toast's two lines.
        switch (phone) {
            case "pixel":
                return "Tap " + AppName.NAME + "'s icon above its card → Split screen, then Dasher";
            case "samsung":
                return "Tap " + AppName.NAME + "'s icon above its card → Open in split screen view";
            default:
                return "Tap " + AppName.NAME + "'s icon above its card → split screen";
        }
    }

    /** {@link #VERIFY_MS} from now: if the screen has not split, recent apps instead (main thread). */
    private static void verifyLater(Activity activity, Consumer<String> later) {
        WeakReference<Activity> page = new WeakReference<>(activity);
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable check = new Runnable() {
            @Override public void run() {
                if (verify != this) return;
                verify = null;
                verifier = null;
                Activity shown = page.get();
                if (requestedAt == 0 || shown == null || shown.isFinishing()) return;
                if (floating(shown)) {
                    requestedAt = 0;
                    log(shown, "floating window after split request: nothing opened");
                    if (later != null) later.accept(floatingHint());
                    return;
                }
                if (inSplit(shown)) return;
                log(shown, "no split " + VERIFY_MS + " ms after Android took the request: opening recent apps");
                String said = byHand(shown);
                if (said != null && later != null) later.accept(said);
            }
        };
        verifier = handler;
        verify = check;
        handler.postDelayed(check, VERIFY_MS);
    }

    private static void cancelVerify() {
        if (verifier != null && verify != null) verifier.removeCallbacks(verify);
        verifier = null;
        verify = null;
    }

    /**
     * Offer Filter's screen paused (main thread): the look at whether the screen split is not made. A request Android
     * took may have opened recent apps itself, so the tap then waits as long as one made from recent apps.
     */
    static void paused(Activity activity) {
        if (requestedAt != 0) away = true;
        if (verify == null) return;
        cancelVerify();
        if (requestedAt != 0) {
            waitMs = BY_HAND_MS;
            log(activity, "left " + AppName.NAME + " before the screen was looked at again (recent apps?); Dasher "
                    + "opens beside a split made within " + BY_HAND_MS / 1000 + " s of the tap");
        }
    }

    /** Once the screen is split after a tap, Dasher opens in the other half; a late or unasked split opens nothing. */
    static void resumed(Activity activity) {
        if (requestedAt == 0) return;
        if (!activity.isInMultiWindowMode()) {
            // Back on this screen full screen after leaving it (recent apps): no split was made there. The tap is
            // over, so Peek may bring Dasher up again at once; a split made by hand later opens nothing (the button,
            // "Put Dasher beside" by then, does). The owner's report of 7 October 2026: an offer 37 s after a refused
            // tap got no peek, the user long back on this screen.
            if (!away) return;
            boolean waiting = pending();
            requestedAt = 0;
            away = false;
            if (waiting) log(activity, "back on " + AppName.NAME + " without a split: the tap is over");
            return;
        }
        away = false;
        cancelVerify();
        if (floating(activity)) {
            requestedAt = 0;
            log(activity, "floating window after split request: nothing opened");
            return;
        }
        long after = SystemClock.uptimeMillis() - requestedAt;
        boolean fresh = after < waitMs;
        requestedAt = 0;
        Intent dasher = dasher(activity);
        if (!fresh) {
            log(activity, "split " + after / 1000 + " s after the tap: too late, nothing opened");
            return;
        }
        if (dasher == null) return;
        if (OfferFilterService.dasherBesideNow()) {
            log(activity, "split " + after + " ms after the tap: Dasher already visible beside; no launch requested");
            return;
        }
        log(activity, "split " + after + " ms after the tap: Dasher's launch intent into the other half");
        String failed = open(activity, dasher);
        if (failed != null) log(activity, "Dasher could not be opened");
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

    /** For tests: forget a pending split, and ask Android for real again. */
    static void forget() {
        cancelVerify();
        requestedAt = 0;
        away = false;
        loggedSplit = null;
        split = OfferFilterService::splitScreen;
        recents = OfferFilterService::openRecents;
    }

    private DasherSplit() {}
}
