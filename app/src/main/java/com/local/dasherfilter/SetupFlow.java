package com.local.dasherfilter;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.widget.Toast;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Setup (0.6.26): while Offer Filter cannot filter, its whole page is setup's, one step at a time and one button each
 * ({@link SetupPage}; the owner, 16 October 2026: "shouldn't have this limbo screen. if the app is unable to work for
 * permissions or whathaveyou then we shepherd the user directly to resolution. all ui on screen needs to be justified
 * by what is currently functional."; notes/0.6.26/setup-first.md). The steps, in order:
 * <ol>
 * <li>Screen reading (Accessibility): whenever it is off, or on and not running for {@link #GRACE_MS};</li>
 * <li>Background offers (notification access): the same;</li>
 * <li>Alerts: Android's own question, asked once;</li>
 * <li>The offer map: location, asked once with Not now, while the map is on;</li>
 * <li>Typical minimums: Start auto-decline, or Set my own.</li>
 * </ol>
 * Steps 3 to 5 are the first run's walk: once a rule is saved, or Set my own is tapped, they never come again. Steps 1
 * and 2 come back whenever their switch goes off, since nothing works without them.
 *
 * <p>Each step's words are read on Offer Filter's own page before any of Android's opens, and Android's pages open
 * only at the user's tap (the owner: "it drops the user into the settings screen"); a toast on Android's page only
 * repeats the path, in a few words (the owner: "toast instructions too long to read"). With the switch on, Offer
 * Filter comes back by itself ({@link #connected}) when setup sent the user there within {@link #RETURN_WITHIN_MS}.
 *
 * <p>Android 13 and later block both accesses for an app installed from a downloaded file until the user allows it in
 * App info, ⋮ → Allow restricted settings, which Android offers only once the switch was tried and blocked. One UI 8
 * blocks Accessibility but not notification access (the owner's Galaxy, 16 October 2026: "it gave me a toast with
 * instructions saying andrioid would block turning on notifications, it didnt"). So only step 1 says the block is
 * coming, and only for an install from the web; a try from here that comes back off there shows the App info step, and
 * back from App info, the switch again. Android's block counts as lifted only by screen reading on, or by Android's own
 * record ({@link RestrictedSettingsGuide#opMode}) saying allowed: notification access turning on proves nothing (0.6.25
 * took it as proof, and on a Galaxy its App info step vanished for good). Nothing here changes a setting: the user does
 * every step. Main thread only.
 */
final class SetupFlow {
    /** What setup's page shows. */
    enum Screen {
        READING, READING_BLOCKED, READING_NOW, READING_RESTART,
        BACKGROUND, BACKGROUND_BLOCKED, BACKGROUND_NOW, BACKGROUND_RECONNECT,
        ALERTS, LOCATION, MINIMUMS
    }

    /** What a button on setup's page does. */
    enum Action {
        OPEN_ACCESSIBILITY, OPEN_APP_INFO, OPEN_NOTIFICATION_ACCESS, RECONNECT, ALLOW_ALERTS, ALLOW_LOCATION, NOT_NOW,
        START, SET_MY_OWN
    }

    /** One step as setup's page shows it. */
    static final class Page {
        final Screen screen;
        /** Its place in the first run's walk ("2 of 5"), 0 once the walk is done. */
        final int number;
        final int of;
        final String title;
        /** Its words, a paragraph each. */
        final List<String> words;
        final String button;
        final Action action;
        /** The quiet link under the button, or null. */
        final String link;
        final Action linkAction;

        Page(Screen screen, int number, int of, String title, List<String> words, String button, Action action,
             String link, Action linkAction) {
            this.screen = screen;
            this.number = number;
            this.of = of;
            this.title = title;
            this.words = Collections.unmodifiableList(words);
            this.button = button;
            this.action = action;
            this.link = link;
            this.linkAction = linkAction;
        }

        /** "2 of 5" while the first run's walk lasts, else nothing. */
        String counter() {
            return number > 0 ? number + " of " + of : "";
        }

        /** Everything the page shows, to tell whether it changed. */
        String key() {
            return screen + "/" + counter() + "/" + title + "/" + words + "/" + button + "/" + link;
        }
    }

    static final String PREFS = "setup_steps";
    /** Android's question for alerts was asked from here (a refusal is the user's answer: asked once). */
    static final String NOTIFICATIONS_ASKED = "notifications_asked";
    /** Android's question for location was asked from setup, or Not now tapped: asked once. */
    static final String LOCATION_ASKED = "location_asked";
    /** The first run's walk is over (a rule saved, or Set my own): steps 3 to 5 never come again. */
    static final String WALK_DONE = "walk_done";
    /** Screen reading was on in this install: Android's block, if any, was lifted. */
    static final String LIFTED = "restriction_lifted";
    /** The access whose page setup opened to turn it on (or off and on), when, and why: a try or a restart. */
    static final String SENT = "sent_to";
    static final String SENT_AT = "sent_at";
    static final String SENT_FOR = "sent_for";
    /** The access App info was opened for, and when. */
    static final String APP_INFO = "app_info_for";
    static final String APP_INFO_AT = "app_info_at";
    /** Each access's walk around Android's block: blocked (App info next) or allowed (the switch next). */
    static final String PHASE_READING = "phase_reading";
    static final String PHASE_BACKGROUND = "phase_background";
    static final String BLOCKED = "blocked";
    static final String ALLOWED = "allowed";
    static final String TRY = "try";
    static final String RESTART = "restart";
    /** What 0.6.25 and older kept here, forgotten once (0.6.25's "restricted_cleared" was the Galaxy's dead end). */
    private static final String[] RETIRED = {"restricted_cleared", "restricted_tried", "restricted_missing",
            "restricted_trying", "restricted_trying_at", "restricted_return_to", "restricted_return_at",
            "restricted_record_seen", "turning_on", "turning_on_at", "accessibility_opened", "listener_opened"};

    /** How long setup's marks are acted on after leaving for one of Android's pages. */
    static final long RETURN_WITHIN_MS = RestrictedSettingsGuide.RETURN_WITHIN_MS;
    /**
     * How long a switch may be on and not running before its step shows again: Android binds both again within
     * seconds of an update or a restart of the app, and a step flashing up meanwhile would be the limbo the owner
     * asked to end.
     */
    static final long GRACE_MS = 10_000;
    /** How long after asking Android to reconnect the listener a second tap opens its page instead. */
    static final long RECONNECT_GRACE_MS = 60_000;
    /**
     * The most characters a toast on one of Android's pages may have: from Android 12 a text toast shows two lines at
     * most and cuts the rest; two lines of a 300 dp toast hold about 85 at the default font, fewer at the larger fonts
     * many drivers use (ToastWordsTest).
     */
    static final int TOAST_MOST = 72;

    // ---- The words (the study's table: notes/0.6.26/setup-first.md) ----

    static final String READING_TITLE = "Turn on screen reading";
    static final String READING_WHY = AppName.NAME + " reads Dasher's offers on your screen.";
    /** Said before the first try only on an install from the web, where Android's block is certain to come. */
    static final String BLOCK_AHEAD = "Android blocks this the first time for apps from the web. That's expected: "
            + "close its message and come back here.";
    static final String BLOCKED_TITLE = "Allow restricted settings";
    static final String READING_BLOCKED_WORDS = "Android blocks this switch the first time for apps from the web. "
            + "Allow it in App info: tap ⋮ at the top right, then Allow restricted settings.";
    static final String BACKGROUND_BLOCKED_WORDS = "Android blocks notification access the first time for apps from "
            + "the web. Allow it in App info: tap ⋮ at the top right, then Allow restricted settings.";
    static final String READING_NOW_TITLE = "Now turn on screen reading";
    static final String RESTART_TITLE = "Restart screen reading";
    static final String RESTART_WHY = AppName.NAME + "'s screen reading stopped.";
    static final String BACKGROUND_TITLE = "Turn on background offers";
    static final String BACKGROUND_WHY = AppName.NAME + " also reads Dasher's offer notifications, so it catches "
            + "offers while Dasher is in the background.";
    /** The listener's name in Android's list of apps with notification access (strings.xml listener_name). */
    static final String LISTENER_NAME = AppName.NAME + " background offers";
    static final String BACKGROUND_PATH = "In notification access, turn on " + LISTENER_NAME + ".";
    static final String BACKGROUND_NOW_TITLE = "Now turn on background offers";
    static final String RECONNECT_TITLE = "Reconnect background offers";
    static final String RECONNECT_WORDS = AppName.NAME + " stopped getting Dasher's notifications.";
    static final String ALERTS_TITLE = "Allow alerts";
    static final String ALERTS_WORDS = AppName.NAME + " shows a card when an offer passes or needs a look, and tells "
            + "you when it pauses.";
    static final String LOCATION_TITLE = "Map where offers pay best";
    static final String LOCATION_WORDS = AppName.NAME + " can pin each offer where it came in, so its map shows where "
            + "pay is best.";
    static final String MINIMUMS_TITLE = "Start with typical minimums";
    static final String MINIMUMS_WORDS = "$2.00 per stop ($4.00 for a single order), $1.00 per mile and $15 per hour "
            + "of trip time, typical of Cincinnati offers seen in October 2026.";
    static final String MINIMUMS_THEN = AppName.NAME + " declines offers below them. Change them anytime on the home "
            + "screen.";

    static final String OPEN_ACCESSIBILITY = "Open Accessibility";
    static final String OPEN_APP_INFO = "Open App info";
    static final String TRY_AGAIN = "Try the switch again";
    static final String OPEN_NOTIFICATION_ACCESS = "Open notification access";
    static final String RECONNECT = "Reconnect";
    static final String ALLOW_ALERTS = "Allow alerts";
    static final String ALLOW_LOCATION = "Allow location";
    static final String NOT_NOW = "Not now";
    static final String START = "Start auto-decline";
    static final String SET_MY_OWN = "Set my own";

    /** The toasts on Android's pages: the path alone, a few words ({@link #TOAST_MOST} at most). */
    static final String IN_APP_INFO = "⋮ → Allow restricted settings";
    static final String OFF_AND_ON_HERE = "Turn it off, then on.";
    static final String LISTENER_IN_LIST = "Turn on " + LISTENER_NAME;
    static final String LISTENER_OFF_AND_ON = "Turn " + LISTENER_NAME + " off, then on";
    static final String REBINDING = "Reconnecting…";
    /** Android's own name for the switch behind Allow updates ("Install unknown apps" for this app). */
    static final String ALLOW_SOURCE = "Turn on Allow from this source.";
    static final String ALLOW_NOTIFICATIONS = "Turn on notifications for " + AppName.NAME + ", every category.";

    /** Tests written before 0.6.26 run with setup's page away (ConsentedTestApp); SetupFlowTest turns it on. */
    static boolean pageShows = true;

    private final Activity activity;
    private final int alertsRequest;
    private final Runnable askLocation;
    private final Runnable start;
    private final Runnable setMyOwn;
    private final MainActivity.Asked<Boolean> accessibilityOn;
    private final MainActivity.Asked<Boolean> listenerOn;
    private final MainActivity.Asked<Boolean> alertsOn;
    private final MainActivity.Asked<Boolean> locationOn;
    private final MainActivity.Asked<Integer> record;
    private final MainActivity.Asked<RestrictedSettingsGuide.Source> source;
    /** When a switch on but not running was first seen (elapsed realtime), or -1. */
    private long readerMissingSince = -1;
    private long listenerMissingSince = -1;
    private long reconnectAskedAt = -1;
    /** The screen shown last, for the log (null: none). */
    private Screen shown;

    /**
     * @param alertsRequest the request code of the alerts permission, answered in the Activity
     * @param askLocation   Android's question for location, answered in the Activity
     * @param start         Start auto-decline: the typical minimums saved and auto-decline on
     * @param setMyOwn      Set my own: the home screen, its knobs beckoning
     */
    SetupFlow(Activity activity, int alertsRequest, Runnable askLocation, Runnable start, Runnable setMyOwn) {
        this.activity = activity;
        this.alertsRequest = alertsRequest;
        this.askLocation = askLocation;
        this.start = start;
        this.setMyOwn = setMyOwn;
        accessibilityOn = new MainActivity.Asked<>(() -> accessibilityEnabled(activity));
        listenerOn = new MainActivity.Asked<>(() -> OfferNotificationService.hasAccess(activity));
        alertsOn = new MainActivity.Asked<>(() -> OfferAlerts.canNotify(activity));
        locationOn = new MainActivity.Asked<>(() -> AreaMap.hasPermission(activity));
        record = new MainActivity.Asked<>(() -> RestrictedSettingsGuide.opMode(activity));
        source = new MainActivity.Asked<>(() -> RestrictedSettingsGuide.source(activity));
        SharedPreferences prefs = prefs(activity);
        SharedPreferences.Editor retired = null;
        for (String key : RETIRED) {
            if (!prefs.contains(key)) continue;
            if (retired == null) retired = prefs.edit();
            retired.remove(key);
        }
        if (retired != null) retired.apply();
    }

    /** Android is asked again, at the next look. */
    void forget() {
        accessibilityOn.forget();
        listenerOn.forget();
        alertsOn.forget();
        locationOn.forget();
        record.forget();
        source.forget();
    }

    // ---- What shows ----

    /**
     * The step to show now, or null while Offer Filter can filter and the first run's walk is done. Asked at every
     * refresh of the page (Android's answers kept for a while, as the page keeps its own).
     */
    Page page() {
        Page page = pageShows ? current() : null;
        Screen now = page == null ? null : page.screen;
        if (now != shown) {
            DiagnosticLog.log(activity, "setup", now == null ? "done: the home screen shows"
                    : "showing " + now.name().toLowerCase(java.util.Locale.US).replace('_', ' '));
            shown = now;
        }
        return page;
    }

    private Page current() {
        SharedPreferences prefs = prefs(activity);
        long now = SystemClock.elapsedRealtime();
        boolean walking = walking(prefs);
        int of = AreaMap.enabled(activity) ? 5 : 4;
        // Running proves it on, whatever Android's list says (an app may not always read it).
        boolean a11y = OfferFilterService.isConnected() || accessibilityOn.get();
        if (!a11y) {
            readerMissingSince = -1;
            return reading(prefs, walking ? 1 : 0, of);
        }
        if (!prefs.getBoolean(LIFTED, false)) prefs.edit().putBoolean(LIFTED, true).remove(PHASE_READING).apply();
        if (OfferFilterService.isConnected()) {
            readerMissingSince = -1;
        } else {
            if (readerMissingSince < 0 || now < readerMissingSince) readerMissingSince = now;
            if (now - readerMissingSince >= GRACE_MS) {
                return new Page(Screen.READING_RESTART, walking ? 1 : 0, of, RESTART_TITLE,
                        Arrays.asList(RESTART_WHY, "In Accessibility, tap " + appsList() + ", then " + AppName.NAME
                                + ", and turn it off, then on."),
                        OPEN_ACCESSIBILITY, Action.OPEN_ACCESSIBILITY, null, null);
            }
        }
        if (!OfferNotificationService.isConnected() && !listenerOn.get()) {
            listenerMissingSince = -1;
            return background(prefs, walking ? 2 : 0, of);
        }
        if (prefs.contains(PHASE_BACKGROUND)) prefs.edit().remove(PHASE_BACKGROUND).apply();
        if (OfferNotificationService.isConnected()) {
            listenerMissingSince = -1;
        } else {
            if (listenerMissingSince < 0 || now < listenerMissingSince) listenerMissingSince = now;
            if (now - listenerMissingSince >= GRACE_MS) {
                return new Page(Screen.BACKGROUND_RECONNECT, walking ? 2 : 0, of, RECONNECT_TITLE,
                        Collections.singletonList(RECONNECT_WORDS), RECONNECT, Action.RECONNECT, null, null);
            }
        }
        if (!walking) return null;
        if (!alertsOn.get() && !prefs.getBoolean(NOTIFICATIONS_ASKED, false)) {
            return new Page(Screen.ALERTS, 3, of, ALERTS_TITLE, Collections.singletonList(ALERTS_WORDS), ALLOW_ALERTS,
                    Action.ALLOW_ALERTS, null, null);
        }
        if (of == 5 && !locationOn.get() && !prefs.getBoolean(LOCATION_ASKED, false)) {
            return new Page(Screen.LOCATION, 4, of, LOCATION_TITLE, Collections.singletonList(LOCATION_WORDS),
                    ALLOW_LOCATION, Action.ALLOW_LOCATION, NOT_NOW, Action.NOT_NOW);
        }
        return new Page(Screen.MINIMUMS, of, of, MINIMUMS_TITLE, Arrays.asList(MINIMUMS_WORDS, MINIMUMS_THEN), START,
                Action.START, SET_MY_OWN, Action.SET_MY_OWN);
    }

    /** Step 1 with screen reading off: the first try, App info after a block, or the switch again after App info. */
    private Page reading(SharedPreferences prefs, int number, int of) {
        int mode = record.get();
        String phase = prefs.getString(PHASE_READING, null);
        boolean restricted = restricted(mode);
        // Allowed in App info without setup's help: the switch next.
        if (mode == AppOpsManager.MODE_ALLOWED && BLOCKED.equals(phase)) phase = ALLOWED;
        String path = "In Accessibility, tap " + appsList() + ", then " + AppName.NAME + ", and turn it on.";
        if (ALLOWED.equals(phase) && !restricted) {
            return new Page(Screen.READING_NOW, number, of, READING_NOW_TITLE, Collections.singletonList(path),
                    OPEN_ACCESSIBILITY, Action.OPEN_ACCESSIBILITY, null, null);
        }
        if (BLOCKED.equals(phase) || mode == AppOpsManager.MODE_IGNORED) {
            return new Page(Screen.READING_BLOCKED, number, of, BLOCKED_TITLE,
                    Collections.singletonList(READING_BLOCKED_WORDS), OPEN_APP_INFO, Action.OPEN_APP_INFO, TRY_AGAIN,
                    Action.OPEN_ACCESSIBILITY);
        }
        List<String> words = blockAhead(prefs, mode) ? Arrays.asList(READING_WHY, path, BLOCK_AHEAD)
                : Arrays.asList(READING_WHY, path);
        return new Page(Screen.READING, number, of, READING_TITLE, words, OPEN_ACCESSIBILITY,
                Action.OPEN_ACCESSIBILITY, null, null);
    }

    /** Step 2 with notification access off: never a warning, App info only where Android's record says blocked. */
    private Page background(SharedPreferences prefs, int number, int of) {
        int mode = record.get();
        String phase = prefs.getString(PHASE_BACKGROUND, null);
        if (mode == AppOpsManager.MODE_ALLOWED && BLOCKED.equals(phase)) phase = ALLOWED;
        if (ALLOWED.equals(phase) && !restricted(mode)) {
            return new Page(Screen.BACKGROUND_NOW, number, of, BACKGROUND_NOW_TITLE,
                    Collections.singletonList(BACKGROUND_PATH), OPEN_NOTIFICATION_ACCESS,
                    Action.OPEN_NOTIFICATION_ACCESS, null, null);
        }
        if (BLOCKED.equals(phase)) {
            return new Page(Screen.BACKGROUND_BLOCKED, number, of, BLOCKED_TITLE,
                    Collections.singletonList(BACKGROUND_BLOCKED_WORDS), OPEN_APP_INFO, Action.OPEN_APP_INFO,
                    TRY_AGAIN, Action.OPEN_NOTIFICATION_ACCESS);
        }
        return new Page(Screen.BACKGROUND, number, of, BACKGROUND_TITLE, Arrays.asList(BACKGROUND_WHY,
                BACKGROUND_PATH), OPEN_NOTIFICATION_ACCESS, Action.OPEN_NOTIFICATION_ACCESS, null, null);
    }

    /** Whether the first run's walk lasts: no rule saved yet, and Set my own not tapped. */
    private boolean walking(SharedPreferences prefs) {
        if (prefs.getBoolean(WALK_DONE, false)) return false;
        if (!FilterStore.load(activity).hasAnyRule()) return true;
        // An install in use (updated from before 0.6.26, or a rule saved since): the walk is over for good.
        prefs.edit().putBoolean(WALK_DONE, true).apply();
        return false;
    }

    private static boolean restricted(int mode) {
        return mode == AppOpsManager.MODE_ERRORED || mode == AppOpsManager.MODE_IGNORED;
    }

    /**
     * Whether step 1 says Android's block is coming: Android's record says restricted and untried, or the install came
     * from a downloaded or local file and nothing says the block was lifted. Never for notification access, which One
     * UI 8 does not block.
     */
    private boolean blockAhead(SharedPreferences prefs, int mode) {
        if (mode == AppOpsManager.MODE_ERRORED) return true;
        if (mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_IGNORED) return false;
        return !prefs.getBoolean(LIFTED, false) && source.get() == RestrictedSettingsGuide.Source.FILE;
    }

    /**
     * Whether a try from here that came back off may have met Android's block: for screen reading, Android's record
     * says so, or the install came from outside an app store and nothing says it was lifted; for notification access,
     * only Android's record (One UI 8 lets it on with no block).
     */
    private boolean mayBeBlocked(RestrictedSettingsGuide.Access access, SharedPreferences prefs, int mode) {
        if (restricted(mode)) return true;
        if (access != RestrictedSettingsGuide.Access.ACCESSIBILITY || mode == AppOpsManager.MODE_ALLOWED) return false;
        RestrictedSettingsGuide.Source from = source.get();
        return !prefs.getBoolean(LIFTED, false)
                && (from == RestrictedSettingsGuide.Source.FILE || from == RestrictedSettingsGuide.Source.OTHER);
    }

    /** Where Accessibility keeps downloaded apps: One UI's name on a Galaxy, stock Android's elsewhere. */
    static String appsList() {
        String maker = android.os.Build.MANUFACTURER == null ? ""
                : android.os.Build.MANUFACTURER.toLowerCase(java.util.Locale.US);
        return maker.contains("samsung") ? "Installed apps" : "Downloaded apps";
    }

    // ---- Coming back ----

    /**
     * Back on the page, perhaps from one of Android's pages setup opened: what it sent the user to do is looked at
     * once, within {@link #RETURN_WITHIN_MS}. A try that came back off may have met Android's block: App info next.
     * Back from App info with Android's record no longer restricted (or unreadable): the switch next. Never behind the
     * notice ({@code behindNotice}), where nothing is shown or asked.
     */
    void resumed(boolean behindNotice) {
        forget();
        if (behindNotice) return;
        SharedPreferences prefs = prefs(activity);
        if (!prefs.contains(SENT) && !prefs.contains(APP_INFO)) return;
        long now = System.currentTimeMillis();
        RestrictedSettingsGuide.Access sent = fresh(prefs, SENT, SENT_AT, now);
        String why = prefs.getString(SENT_FOR, TRY);
        RestrictedSettingsGuide.Access info = fresh(prefs, APP_INFO, APP_INFO_AT, now);
        SharedPreferences.Editor edit = prefs.edit().remove(SENT).remove(SENT_AT).remove(SENT_FOR).remove(APP_INFO)
                .remove(APP_INFO_AT);
        int mode = sent != null || info != null ? record.get() : -1;
        if (sent != null) {
            String phase = phaseKey(sent);
            if (on(sent)) {
                edit.remove(phase);
                DiagnosticLog.log(activity, "setup", name(sent) + " on, back from Android's page");
            } else if (TRY.equals(why) && mayBeBlocked(sent, prefs, mode)) {
                edit.putString(phase, BLOCKED);
                DiagnosticLog.log(activity, "setup", "a try at " + name(sent) + " came back off (record "
                        + RestrictedSettingsGuide.modeWord(mode) + "); App info next");
            } else {
                DiagnosticLog.log(activity, "setup", name(sent) + " still off, back from Android's page (record "
                        + RestrictedSettingsGuide.modeWord(mode) + ")");
            }
        }
        if (info != null) {
            String phase = phaseKey(info);
            if (on(info)) {
                edit.remove(phase);
            } else if (!restricted(mode)) {
                edit.putString(phase, ALLOWED);
                DiagnosticLog.log(activity, "setup", "back from App info (record "
                        + RestrictedSettingsGuide.modeWord(mode) + "); the switch next");
            } else {
                DiagnosticLog.log(activity, "setup", "back from App info, still restricted (record "
                        + RestrictedSettingsGuide.modeWord(mode) + ")");
            }
        }
        edit.apply();
    }

    /**
     * A switch setup sent the user to turn on has turned on ({@code access}'s service connected): Offer Filter comes
     * back to the front by itself, if setup sent them there within {@link #RETURN_WITHIN_MS} and the current notice is
     * accepted. Android lets an app with a bound Accessibility service open its screen from the background; anywhere it
     * does not, Back returns as before. The mark is read once.
     */
    static void connected(Context context, RestrictedSettingsGuide.Access access) {
        SharedPreferences prefs = prefs(context);
        if (!access.name().equals(prefs.getString(SENT, null))) return;
        RestrictedSettingsGuide.Access sent = fresh(prefs, SENT, SENT_AT, System.currentTimeMillis());
        prefs.edit().remove(SENT).remove(SENT_AT).remove(SENT_FOR).remove(phaseKey(access)).apply();
        if (sent == null || !Consent.accepted(context)) return;
        Intent back = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(back);
            // Android may still keep it behind without a word: the log says only that it was asked.
            DiagnosticLog.log(context, "setup", name(access) + " turned on; " + AppName.NAME + " asked back to the front");
        } catch (RuntimeException refused) {
            DiagnosticLog.log(context, "setup", name(access) + " turned on; Android kept " + AppName.NAME
                    + " in the background (" + refused.getClass().getSimpleName() + ")");
        }
    }

    private static RestrictedSettingsGuide.Access fresh(SharedPreferences prefs, String key, String atKey, long now) {
        String name = prefs.getString(key, null);
        long at = prefs.getLong(atKey, 0);
        if (name == null || at <= 0 || now < at || now - at > RETURN_WITHIN_MS) return null;
        try {
            return RestrictedSettingsGuide.Access.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static String phaseKey(RestrictedSettingsGuide.Access access) {
        return access == RestrictedSettingsGuide.Access.ACCESSIBILITY ? PHASE_READING : PHASE_BACKGROUND;
    }

    private static String name(RestrictedSettingsGuide.Access access) {
        return access == RestrictedSettingsGuide.Access.ACCESSIBILITY ? "screen reading" : "notification access";
    }

    private boolean on(RestrictedSettingsGuide.Access access) {
        return access == RestrictedSettingsGuide.Access.ACCESSIBILITY
                ? OfferFilterService.isConnected() || accessibilityOn.get()
                : OfferNotificationService.isConnected() || listenerOn.get();
    }

    // ---- The buttons ----

    /** {@code action}, tapped on {@code page}. The page refreshes after. */
    void act(Page page, Action action) {
        DiagnosticLog.log(activity, "setup", "tapped " + action.name().toLowerCase(java.util.Locale.US).replace('_', ' ')
                + " on " + page.screen.name().toLowerCase(java.util.Locale.US).replace('_', ' '));
        boolean background = page.screen == Screen.BACKGROUND || page.screen == Screen.BACKGROUND_BLOCKED
                || page.screen == Screen.BACKGROUND_NOW || page.screen == Screen.BACKGROUND_RECONNECT;
        switch (action) {
            case OPEN_ACCESSIBILITY:
                openAccessibility(page.screen == Screen.READING_RESTART);
                break;
            case OPEN_APP_INFO:
                openAppInfo(background ? RestrictedSettingsGuide.Access.NOTIFICATIONS
                        : RestrictedSettingsGuide.Access.ACCESSIBILITY);
                break;
            case OPEN_NOTIFICATION_ACCESS:
                openNotificationAccess(false);
                break;
            case RECONNECT:
                reconnect();
                break;
            case ALLOW_ALERTS:
                allowAlerts();
                break;
            case ALLOW_LOCATION:
                prefs(activity).edit().putBoolean(LOCATION_ASKED, true).apply();
                askLocation.run();
                break;
            case NOT_NOW:
                prefs(activity).edit().putBoolean(LOCATION_ASKED, true).apply();
                break;
            case START:
                prefs(activity).edit().putBoolean(WALK_DONE, true).apply();
                start.run();
                break;
            case SET_MY_OWN:
                prefs(activity).edit().putBoolean(WALK_DONE, true).apply();
                setMyOwn.run();
                break;
        }
    }

    /** Setup sent the user to {@code access}'s page: noted, for the return (and Offer Filter's own, by itself). */
    private void send(RestrictedSettingsGuide.Access access, String why) {
        prefs(activity).edit().putString(SENT, access.name()).putLong(SENT_AT, System.currentTimeMillis())
                .putString(SENT_FOR, why).remove(APP_INFO).remove(APP_INFO_AT).apply();
    }

    /**
     * The service's own Accessibility page where Android lets an app open it, else the top page, whose toast names the
     * path. Stock Android guards the service's page with a permission only system apps hold, so most phones show the
     * top page.
     */
    private void openAccessibility(boolean restart) {
        send(RestrictedSettingsGuide.Access.ACCESSIBILITY, restart ? RESTART : TRY);
        // AOSP's service-specific settings action is not part of the public SDK constants. OEMs may omit it.
        Intent details = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME,
                        new ComponentName(activity, OfferFilterService.class).flattenToString());
        try {
            activity.startActivity(details);
            if (restart) toast(OFF_AND_ON_HERE);
            return;
        } catch (RuntimeException refused) {
            DiagnosticLog.log(activity, "setup", "the service's own Accessibility page is not open to apps here ("
                    + refused.getClass().getSimpleName() + "); the top page instead");
        }
        if (open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) toast(inAccessibilityList(restart));
    }

    /** The toast on Accessibility's top page: where Offer Filter is in it, and for a restart, what to do there. */
    static String inAccessibilityList(boolean restart) {
        return appsList() + " → " + AppName.NAME + (restart ? ": off, then on" : "");
    }

    /** App info, where ⋮ → Allow restricted settings is once Android has blocked a try. */
    private void openAppInfo(RestrictedSettingsGuide.Access access) {
        prefs(activity).edit().putString(APP_INFO, access.name()).putLong(APP_INFO_AT, System.currentTimeMillis())
                .remove(SENT).remove(SENT_AT).remove(SENT_FOR).apply();
        if (open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.getPackageName())))) {
            toast(IN_APP_INFO);
        }
    }

    /** The listener's own page (its switch right there, no toast), else the list, where a toast names it. */
    private void openNotificationAccess(boolean restart) {
        send(RestrictedSettingsGuide.Access.NOTIFICATIONS, restart ? RESTART : TRY);
        Intent detail = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                new ComponentName(activity, OfferNotificationService.class).flattenToString());
        try {
            activity.startActivity(detail);
            if (restart) toast(OFF_AND_ON_HERE);
            return;
        } catch (RuntimeException missing) {
            DiagnosticLog.log(activity, "setup", "no page of its own for notification access here ("
                    + missing.getClass().getSimpleName() + "); the list instead");
        }
        if (open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) {
            toast(restart ? LISTENER_OFF_AND_ON : LISTENER_IN_LIST);
        }
    }

    /** Android asked to bind the listener again; asked lately (or refused), its page, to turn it off and on. */
    private void reconnect() {
        long now = SystemClock.uptimeMillis();
        boolean askedLately = reconnectAskedAt >= 0 && now >= reconnectAskedAt
                && now - reconnectAskedAt < RECONNECT_GRACE_MS;
        if (!askedLately) {
            reconnectAskedAt = now;
            try {
                NotificationListenerService.requestRebind(new ComponentName(activity, OfferNotificationService.class));
                DiagnosticLog.log(activity, "setup", "notification access allowed but not connected; rebind asked");
                ReadingStopped.afterRebind(activity);
                toast(REBINDING);
                return;
            } catch (RuntimeException refused) {
                DiagnosticLog.log(activity, "setup", "rebind refused: " + refused.getClass().getSimpleName());
            }
        }
        openNotificationAccess(true);
    }

    /**
     * Android's own question for alerts the first time (or while Android still asks), else the app's notification
     * settings: setup's step 3, and Settings' row for alerts turned off.
     */
    void allowAlerts() {
        OfferAlerts.ensureChannel(activity);
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            SharedPreferences prefs = prefs(activity);
            boolean asked = prefs.getBoolean(NOTIFICATIONS_ASKED, false);
            if (!asked || activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                prefs.edit().putBoolean(NOTIFICATIONS_ASKED, true).apply();
                activity.requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, alertsRequest);
                return;
            }
        }
        // Passing offers, review cards and the paused-until-opened reminder each have their own channel.
        if (open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,
                activity.getPackageName()))) {
            toast(ALLOW_NOTIFICATIONS);
        }
    }

    // ---- Android ----

    /** Whether Offer Filter's screen reading is switched on in Accessibility (running or not). */
    static boolean accessibilityEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName ours = new ComponentName(context, OfferFilterService.class);
        for (String component : enabled.split(":")) {
            if (ours.equals(ComponentName.unflattenFromString(component))) return true;
        }
        return false;
    }

    /** Opens one of Android's pages; whether it opened (a toast says so when it did not). */
    private boolean open(Intent intent) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (RuntimeException error) {
            DiagnosticLog.log(activity, "setup", "Android could not open " + intent.getAction() + ": "
                    + error.getClass().getSimpleName());
            toast("Android couldn't open that screen.");
            return false;
        }
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
