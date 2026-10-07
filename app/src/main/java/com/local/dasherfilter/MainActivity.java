package com.local.dasherfilter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The app's two pages, kept quiet. The main page is one picture: a sky where the minimums, as a very large
 * constellation with recent offers marked (tap an offer for its ticket), spread behind the mascot in its ring and the
 * dash's three counts (the mascot is the one button: a tap pauses or resumes), with a line only when something needs
 * the user; then recent offers as a skyline on the horizon (tap for a ticket); and, on the ground below it on a whole
 * screen, a map of where offers pay best. Every rule lives on the constellation and saves at once: the knobs (pay, per
 * mile and per hour, hollow until set), the max stops badge by the pin, and the Autopilot button (a tap turns it off,
 * or on with the goal chooser; a long press changes the goal), whose status line stands under the skyline's caption
 * (a chip beside the caption in a short window). Settings holds only what exists
 * nowhere else: setup still needing a fix, two switches, updates, anonymous feedback, reports and a tip, each one row. Pause and
 * Resume take effect at once. The drawings move gently and shift with the phone's tilt while the app fills the screen,
 * unless Android's animations are off; in split screen they move calmly and the tilt sensor rests.
 */
public final class MainActivity extends Activity implements Updater.Busy {
    /** The alerts permission's request code (asked by the setup checklist, answered here). */
    static final int NOTIFICATION_PERMISSION_REQUEST = 13;
    private static final int LOCATION_REQUEST = 14;
    private static final int BACKGROUND_LOCATION_REQUEST = 15;
    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    /** A split-screen window shorter than this gets the compact homepage; any window at all under the second. */
    static final int COMPACT_SPLIT_HEIGHT_DP = 600;
    static final int COMPACT_HEIGHT_DP = 400;
    private static final Pattern TOO_MANY_STOPS = Pattern.compile("(\\d+) stops exceeds maximum (\\d+)");
    private static final String SHOWING_SETTINGS = "settings";
    private static final String FEE_NOTICE = "fee_notice";
    private static final String SKY_CHOSEN = "sky_chosen";
    /**
     * With no rule saved, the line under the skyline's caption (never over the constellation's knobs): the fewest
     * words that say how to begin. It opens the starter.
     */
    static final String START_LINE = "Tap to start with typical minimums";
    static final String STARTER_TITLE = "Start with typical minimums?";
    static final String STARTER_TEXT = "$4.00 per offer · $1.00 per mile · $15 per hour of trip time. These are "
            + "typical of Cincinnati offers seen in October 2026. Drag any knob to change them.";
    static final String STARTER_USE = "Use these";
    static final String STARTER_OWN = "Set my own";
    /** Said with the knobs' beckoning, when the user chose to set a first minimum by hand. */
    static final String KNOBS_HINT = "Drag a knob to start. Each knob sets a minimum.";
    static final String FIRST_RULE = "Rule saved. Tap the mascot to turn on auto-decline.";
    /** The one-time 0.5.0 notice (FilterStore's model notice). */
    static final String NOTICE_TITLE = "Your rules are simpler now";
    static final String NOTICE_TYPICAL = "Use typical minimums";
    static final String NOTICE_KEEP = "Keep mine";
    static final String NOTICE_OK = "OK";
    static final String NOTICE_AUTOPILOT = "Set up Autopilot";
    /** The pass check: offers counted at most, and the least for the check to say anything. */
    static final int PASS_CHECK_LINES = 200;
    static final int PASS_CHECK_LEAST = 20;
    /** Settings' auto-accept confirmation: what it takes, and that below-minimum passes are always left to the user. */
    static final String AUTO_ACCEPT_TITLE = "Auto-accept matching offers?";
    static final String AUTO_ACCEPT_MESSAGE = "This can commit you to a delivery without another tap. It accepts only "
            + "complete standalone offers that meet 100% of your minimums (and Autopilot's bar when that is higher). "
            + "Offers Autopilot lets through below your minimums are always left to you. Add-ons and unclear offers "
            + "stay yours. Your touch stops the current attempt.\n\nThe app can misread an offer. Enable this only if "
            + "you accept that risk.";
    /**
     * Clear history's confirmation: the spec's words, with the unsent automatic diagnostics it also clears (the
     * anonymous-feedback package's addition) kept in the list.
     */
    static final String CLEAR_HISTORY = "Removes the offer decisions, waiting estimates, captured screen text, offer "
            + "areas, cached place names, unsent automatic diagnostics and Autopilot's acceptance-rate reading from this "
            + "phone. Your rules and Autopilot settings stay.";
    /** What the ground's one line shows: nothing, the start, Autopilot's status, or the wait for a matching offer. */
    private static final int SLOT_NONE = 0;
    private static final int SLOT_START = 1;
    private static final int SLOT_AUTOPILOT = 2;
    private static final int SLOT_WAIT = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ReportShare reportShare = new ReportShare();
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!started) return;
            refresh();
            if (started) handler.postDelayed(this, 1000);
        }
    };
    /**
     * Android's answers that change only when the user changes something, asked at most this often by the
     * once-a-second refresh (each is a call into Android), and again at once when the page resumes or a permission
     * answer comes back.
     */
    static final long ASK_EVERY_MS = 30_000;
    /** Where the phone is, for the map's dot, asked at most this often while the map shows. */
    static final long HERE_EVERY_MS = 5_000;
    private final Asked<Boolean> dasherInstalled = new Asked<>(() -> DasherSplit.dasher(this) != null);
    private final Asked<Boolean> alertsAllowed = new Asked<>(() -> OfferAlerts.canNotify(this));
    private final Asked<Boolean> locationAllowed = new Asked<>(() -> AreaMap.hasPermission(this));
    private final Asked<Boolean> locationAlways = new Asked<>(() -> AreaMap.hasBackgroundPermission(this));
    private final Asked<Boolean> batteryRestricted = new Asked<>(() -> BatteryLimits.restricted(this));
    private final Asked<double[]> here = new Asked<>(() -> AreaMap.here(this));
    /** Whether the page is resumed, so leaving split screen knows whether to start the tilt again. */
    private boolean resumed;
    private boolean started;
    private Ui ui;
    private ScrollView mainPage;
    private ScenePage scene;
    /** The header's Split with Dasher button (Put Dasher beside once split), while Dasher is not beside already. */
    private View splitButton;
    private FrameLayout root;
    /** A card sliding up over the main page for the chosen offer's ticket or the map: the page itself never scrolls. */
    private FrameLayout sheet;
    private DrawerCard sheetCard;
    private TextView areaLine;
    /** Over everything while an update installs; after a minute it offers "Still updating? Continue". */
    private UpdatingCover updatingCover;
    private ScrollView settingsPage;
    private boolean showingSettings;
    /**
     * The first-run notice, over everything until it is accepted: the homepage shows only it, and nothing behind it
     * is refreshed (no place lookups, no readiness checks).
     */
    private ScrollView notice;

    // Main page: the mascot.
    private TextView stateLine;
    private TextView offerCaption;
    /** The ground's one line: the start, Autopilot's status line, or the wait ({@code slot} says which). */
    private TextView waitEstimateLine;
    private int slot = SLOT_NONE;
    /** Autopilot's chip, beside the caption in a short window (no header row); the chips another strip hosts too. */
    private AutopilotChip chip;
    private final List<AutopilotChip> chips = new java.util.ArrayList<>();
    /** What Autopilot is doing, as last shown. */
    private AutopilotText.Status autopilotStatus;
    /** Told on the main thread after a plan or a commit: the page shows the new status. */
    private final AutopilotRuntime.Listener autopilotListener = () -> {
        if (started) refresh();
    };
    /** The one-time 0.5.0 notice, while it shows; whether this screen looked for it yet. */
    private AlertDialog modelNotice;
    private boolean modelNoticeAsked;
    private boolean readyForOffers;
    private FilterHeroView hero;
    private int shownState;
    private String shownHero = "";
    /** Setup still to do, in order (restricted settings, Accessibility, notification access, alerts, updates). */
    private SetupChecklist checklist;
    private LinearLayout routeRow;
    private TextView routeNote;
    /** The retired extra-stop fee's note, shown once on the homepage until tapped; null when there is none. */
    private String feeNotice;
    private boolean feeNoticeAsked;
    private LinearLayout feeRow;
    private TextView feeText;

    // Main page: offers.
    private DecisionChartView chart;
    private LinearLayout ticket;
    private Decor.Ticket ticketShape;
    private long shownHistoryVersion = -1;
    /** The newest offer the page has seen (when it was recorded), so the mascot plays out each new one once. */
    private long seenOfferAt = -1;
    private long liveAnimationOfferAt;
    private DecisionLog.Outcome shownOfferOutcome;
    /** A new offer plays out only when decided this recently, a moment after it was recorded (its line settled). */
    private static final long OFFER_FRESH_MS = 20_000;
    private static final long OFFER_SETTLE_MS = 1200;
    /** The rules (Autopilot's bar among them) when the star and the skyline were last drawn. */
    private String shownRules = "";
    private List<DecisionLog.Entry> recentEntries = Collections.emptyList();
    /** The ticket follows each new offer until an older one is picked. */
    private boolean followNewest = true;
    /** The chosen offer's ticket is folded away until its line or a building is tapped. */
    private boolean ticketOpen;
    /** A retained count result older than the visible skyline; no plotted selection is implied. */
    private DecisionLog.Entry countTicket;

    // Main page: minimums and areas.
    private MinimumsStarView minimums;
    /**
     * A short window (half a split screen): the constellation moves into the header beside the sun, drawn with its
     * icons beside the circle; the page keeps the mascot, its counts, the skyline and the map, and drops the road.
     */
    private boolean compact;
    /** The main page's header, whose left holds the constellation in a short window. */
    private LinearLayout mainHeader;
    private TextView mainTitle;
    /** The sun (or moon) button in the main page's header. */
    private AppearanceButton sunButton;
    private long appearanceCheckedAt = -1;
    private boolean changingAppearance;
    /**
     * The main page's sky: the header, the mascot with its counts and the page's few lines, with the constellation
     * drawn large behind them all whenever it is not up in the header.
     */
    private SkyStage sky;
    private LinearLayout.LayoutParams skyParams;
    /** Below the sky: the skyline, and on a whole screen the map. */
    private LinearLayout.LayoutParams groundParams;
    /** The skyline's share of the ground, which a short window with the map holds at a fixed height instead. */
    private LinearLayout.LayoutParams chartParams;
    /** The skyline's height, weighted out of the ground, in a short window beside another app and its map. */
    private static final int CHART_SHORT_DP = 56;
    /**
     * Split with Dasher: Dasher's own map is on screen in the other half, so this page shows no map of its own
     * (the pointer over Dasher points to the best area); the constellation and the skyline take its room.
     */
    private boolean besideDasher;
    /**
     * In a short window beside another app, the user tapped the header's constellation: it spreads across the sky with
     * its knobs (the map making room) until its circle is tapped again.
     */
    private boolean skyChosen;
    /** No map on the page now: beside Dasher, or a short window whose sky the constellation was chosen into. */
    private boolean noMap;
    private boolean arranged;
    /** How tall the constellation stands in the header of a short window. */
    static final int HEADER_STAR_DP = 72;
    /**
     * The sky's share of the page, on top of what must be in it, beside Dasher, in a short window with the map, and
     * on a whole screen; the ground (the skyline, and the map where there is one) takes the rest.
     */
    private static final float SKY_BESIDE_DASHER = 2.8f;
    private static final float SKY_WITH_MAP_SHORT = 1f;
    private static final float SKY_WHOLE = 1.7f;
    private AreaMapView areaMap;
    private AreaMap.Cell shownArea;
    private String shownAreas = "";
    private double[] areaHere;
    private boolean pickedArea;

    // Settings page: only what is set nowhere else, one row each.
    /** Setup that needs a fix and has no row on the homepage; each hidden while all is well. */
    private Readiness doorDashAlerts;
    /** Android's "Restricted" battery setting stops the update and feedback jobs (BETA-16). */
    private Readiness battery;
    private Readiness location;
    private Readiness locationAllTheTime;
    private Switch areasToggle;
    private Button updatesRow;
    private Button shareReportRow;
    /** Send anonymous feedback, its line saying what waits or what was last sent. */
    private Button feedbackRow;
    private Switch afterDashToggle;
    /** The feedback and offer-report dialogs, and what this screen shows of a submission's fate. */
    private FeedbackDialogs feedbackDialogs;
    /** On the homepage after the app stopped unexpectedly, while diagnostics after each dash are off. */
    private Readiness stopNotice;
    /** On the homepage: a held, verified update while no dash is on. */
    private UpdateReadyRow updateReady;
    /** One-time cards on the homepage: what Peek does (the first time filtering is on), what is new after an update. */
    private PeekIntroCard peekIntro;
    private WhatsNewCard whatsNew;
    /** This screen was made fresh (not recreated by a resize or day and night): its first resume checks at once. */
    private boolean freshScreen;

    /** Day or night as chosen with the sun and moon, for every view and dialog of this screen. */
    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        android.content.res.Configuration chosen = Appearance.override(base);
        if (chosen != null) applyOverrideConfiguration(chosen);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        freshScreen = state == null;
        ui = new Ui(this);
        // A crash notes where it happened (never what was on screen), for the next start's summary.
        StopReports.install(this);
        feedbackDialogs = new FeedbackDialogs(this, ui, this::feedbackChanged);
        OfferAlerts.ensureChannel(this);
        FilterStore.forgetRetiredEmail(this);
        // Settings has no Automatic updates switch any more: an "off" kept from an older version is cleared once.
        Updater.retireSwitch(this);
        // What an older version kept of Dasher's screens (it could hold a payment card page) goes once, off this thread.
        DiagnosticLog.cleanUpSoon(this);
        // What the retired GitHub connection and its report queues left goes once, off this thread.
        LegacyReportingCleanup.cleanUpSoon(this);
        // Retires an old extra-stop fee now, so its note is ready for the homepage.
        FilterStore.load(this);
        if (state != null) {
            feeNotice = state.getString(FEE_NOTICE);
            feeNoticeAsked = state.getBoolean(FEE_NOTICE + "_asked", false);
            skyChosen = state.getBoolean(SKY_CHOSEN, false);
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(ui.page);
        scene = new ScenePage(this, ui);
        mainPage = addPage(root, scene);
        settingsPage = addPage(root, ui.column());
        buildMain((LinearLayout) mainPage.getChildAt(0));
        buildSettings((LinearLayout) settingsPage.getChildAt(0));
        buildSheet(root);
        updatingCover = new UpdatingCover(this, ui, root);
        notice = NoticePage.build(this, ui, this::acceptNotice, this::finish, this::read);
        root.addView(notice, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        Updater.relaunched(this);

        setContentView(root);
        styleSystemBars(this, ui);
        fitToSystemBars(root);
        showSettings(state != null && state.getBoolean(SHOWING_SETTINGS, false));
        showNotice(!Consent.accepted(this));
        // How the last process ended, and a dash that went quiet meanwhile, are looked at off this thread.
        StopReports.checkSoon(this);
        DashSummary.checkSoon(this);
        // Feedback still waiting (its job lost to a crash, a reboot or a stop) goes now, off this thread.
        FeedbackOutbox.resume(this);
        if (Consent.accepted(this)) feedbackDialogs.restore(state);
        Updater.schedule(this);
        refresh();
    }

    /** In Settings, reading a ticket or the map, or the notice: an update waits rather than interrupt. */
    @Override public boolean midTask() {
        return noticeShown() || showingSettings || (sheet != null && sheet.getVisibility() == View.VISIBLE);
    }

    private boolean noticeShown() {
        return notice != null && notice.getVisibility() == View.VISIBLE;
    }

    /** The notice alone, or the pages as they were; screen readers reach only what is shown. */
    private void showNotice(boolean shown) {
        notice.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (shown) {
            mainPage.setVisibility(View.GONE);
            settingsPage.setVisibility(View.GONE);
            sheet.setVisibility(View.GONE);
            root.setBackgroundColor(ui.page);
            if (Build.VERSION.SDK_INT < 35) getWindow().setStatusBarColor(ui.page);
        } else {
            showSettings(showingSettings);
        }
    }

    /**
     * I understand: kept at once, then what waited for it begins. The screen is read now and Dasher's notifications
     * are replayed (a replay never rings, declines or hides anything).
     */
    private void acceptNotice() {
        Consent.accept(this);
        showNotice(false);
        forgetAnswers();
        OfferNotificationService.rulesChanged();
        OfferFilterService.requestCheckFromNotification();
        refresh();
    }

    /** One of the bundled texts, on its own page. */
    private void read(LegalTexts.Doc doc) {
        open(LegalActivity.intent(this, doc));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean(SHOWING_SETTINGS, showingSettings);
        // Taken once from the store: a recreated page (a resize, day and night) keeps showing it until it is tapped.
        if (feeNotice != null) state.putString(FEE_NOTICE, feeNotice);
        state.putBoolean(FEE_NOTICE + "_asked", feeNoticeAsked);
        state.putBoolean(SKY_CHOSEN, skyChosen);
        feedbackDialogs.save(state);
    }

    /** Back from Settings returns to the main page; back from the main page leaves. */
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        // While updating, nothing is to be interrupted; the new version opens by itself.
        if (updatingCover.shown()) return;
        // Back from the notice is Not now: the app closes, and the notice comes again next time.
        if (noticeShown()) super.onBackPressed();
        else if (sheet.getVisibility() == View.VISIBLE) closeSheet();
        else if (showingSettings) showSettings(false);
        else super.onBackPressed();
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        handler.removeCallbacks(refresh);
        handler.post(refresh);
        // Results of submissions reach this screen only while it is started.
        feedbackDialogs.start();
        // Autopilot's plans and commits reach this screen while it is started; a plan for display is asked for now.
        AutopilotRuntime.listen(autopilotListener);
        AutopilotRuntime.requestPlan(this, AutopilotRuntime.Trigger.RESUME);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        appearanceCheckedAt = -1;
        // Sound left turned down by a decline the screen reader could not finish is put back here too.
        if (!OfferFilterService.isConnected()) OfferSilencer.restore(this);
        Updater.foreground(this);
        // Back from Android's settings, perhaps: ask again.
        forgetAnswers();
        // Back from Android's settings: a switch tried and still off is noted; back from App info, the next one opens
        // (never behind the notice).
        checklist.resumed(noticeShown());
        followSplit(isInMultiWindowMode());
        handler.removeCallbacks(refresh);
        handler.post(refresh);
        // Opening the app checks at once; coming back to it checks at most every five minutes.
        Updater.check(this, freshScreen ? UpdateCadence.Trigger.OPENED : UpdateCadence.Trigger.RESUMED, null);
        freshScreen = false;
        DasherSplit.windowMode(this);
        DasherSplit.resumed(this);
        // Whether Dasher is beside now, not at Dasher's next event: the page is laid out for it at once.
        OfferFilterService.lookSoon(windowsLooked);
    }

    @Override public void onMultiWindowModeChanged(boolean inMultiWindow, Configuration configuration) {
        super.onMultiWindowModeChanged(inMultiWindow, configuration);
        followSplit(inMultiWindow);
        DasherSplit.windowMode(this);
        DasherSplit.resumed(this);
        OfferFilterService.lookSoon(windowsLooked);
        refresh();
    }

    /** The screen reader looked at the windows for this page: laid out for what it saw (Dasher beside or not). */
    private final Runnable windowsLooked = () -> {
        if (started) refresh();
    };

    /**
     * A finger landing on this page is not on Dasher: in split screen, a touch here during an automatic decline does
     * not hand the offer back (the user's decision). Only its time is passed on, before anything else sees it.
     */
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        OwnWindowTouches.onTouch(event);
        return super.dispatchTouchEvent(event);
    }

    /**
     * Beside another app the page stays on screen for a whole dash: no tilt sensor then, and the drawings' steady
     * motion is calm (about ten frames a second). A whole screen has both back.
     */
    private void followSplit(boolean inMultiWindow) {
        Motion.setCalm(inMultiWindow);
        if (inMultiWindow || !resumed) Tilt.stop();
        else Tilt.start(this);
    }

    private void forgetAnswers() {
        dasherInstalled.forget();
        alertsAllowed.forget();
        locationAllowed.forget();
        locationAlways.forget();
        batteryRestricted.forget();
        if (checklist != null) checklist.forget();
        here.forget();
    }

    /** One of Android's answers, kept for a while (see {@link #ASK_EVERY_MS}). Main thread only. */
    static final class Asked<T> {
        private final java.util.function.Supplier<T> ask;
        private T answer;
        private long askedAt;
        private boolean known;

        Asked(java.util.function.Supplier<T> ask) {
            this.ask = ask;
        }

        T get(long keepMs) {
            long now = android.os.SystemClock.uptimeMillis();
            if (!known || now - askedAt >= keepMs || now < askedAt) {
                answer = ask.get();
                askedAt = now;
                known = true;
            }
            return answer;
        }

        T get() {
            return get(ASK_EVERY_MS);
        }

        void forget() {
            known = false;
        }
    }

    /** Offer Filter above, Dasher below, at the user's tap; already split, Dasher into the other half. */
    private void splitWithDasher() {
        String said = DasherSplit.start(this, this::toast);
        if (said != null) toast(said);
    }

    @Override protected void onPause() {
        resumed = false;
        cancelReportShare();
        refreshScreenAwake();
        DasherSplit.paused(this);
        Tilt.stop();
        Updater.background(this);
        super.onPause();
    }

    @Override protected void onStop() {
        started = false;
        handler.removeCallbacks(refresh);
        feedbackDialogs.stop();
        AutopilotRuntime.unlisten(autopilotListener);
        super.onStop();
    }

    @Override protected void onDestroy() {
        cancelReportShare();
        feedbackDialogs.destroy();
        // The notice is kept until a button is tapped: a recreated screen shows it again.
        if (modelNotice != null && modelNotice.isShowing()) modelNotice.dismiss();
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        forgetAnswers();
        refresh();
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            toast(granted ? "Alerts allowed." : "Alerts not allowed.");
        } else if (requestCode == LOCATION_REQUEST && !granted) {
            toast("Location not allowed, so no areas are kept. You can allow it in Android's app settings.");
        } else if (requestCode == BACKGROUND_LOCATION_REQUEST) {
            LocationRationale.answered(this, granted);
        }
    }

    // ---- Pages ----

    private ScrollView addPage(FrameLayout root, LinearLayout page) {
        ScrollView scroll = new ScrollView(this);
        // The main page fills exactly one screen; it scrolls only if a very large font leaves no other way.
        scroll.setFillViewport(true);
        scroll.setVisibility(View.GONE);
        scroll.addView(page, Ui.matchWidth());
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return scroll;
    }

    /** Swaps the pages in place; each keeps its own scroll position. */
    private void showSettings(boolean settings) {
        View shown = settings ? settingsPage : mainPage;
        boolean changed = shown.getVisibility() != View.VISIBLE;
        showingSettings = settings;
        mainPage.setVisibility(settings ? View.GONE : View.VISIBLE);
        settingsPage.setVisibility(settings ? View.VISIBLE : View.GONE);
        if (!settings) cancelReportShare();
        else if (!noticeShown()) refreshSettings();
        // Behind the status bar: the top of the main page's sky, or the Settings page.
        root.setBackgroundColor(settings ? ui.page : ScenePage.skyTop(ui));
        if (Build.VERSION.SDK_INT < 35) getWindow().setStatusBarColor(settings ? ui.page : ScenePage.skyTop(ui));
        View focused = getCurrentFocus();
        if (focused != null && !focused.isShown()) focused.clearFocus();
        if (changed) {
            shown.setAlpha(0f);
            shown.animate().alpha(1f).setDuration(160);
        }
    }

    /** A sky strip across the top of a page: the title and one round button. */
    private View header(String title, boolean back) {
        LinearLayout header = ui.row();
        // The main page's sky is the whole scene behind it; Settings has its own strip of sky.
        if (back) header.setBackground(new Scenery(Scenery.Part.SKY, ui));
        header.setPadding(ui.dp(back ? 8 : 20), ui.dp(back ? 18 : 10), ui.dp(12), ui.dp(back ? 12 : 4));
        header.setMinimumHeight(ui.dp(back ? 84 : 62));
        if (back) header.addView(iconButton(Glyph.Shape.BACK, "Back", () -> showSettings(false)));
        // The main page's picture needs no title; screen readers still hear it.
        TextView name = ui.text(back ? title : "", 22, ui.ink, true);
        if (!back) name.setContentDescription(title);
        if (back) name.setPadding(ui.dp(8), 0, 0, 0);
        if (Build.VERSION.SDK_INT >= 28) name.setAccessibilityHeading(true);
        header.addView(name, Ui.weighted());
        if (!back) {
            mainHeader = header;
            mainTitle = name;
            header.addView(iconButton(Glyph.Shape.PIN, "Navigate", this::chooseNavigation));
            splitButton = iconButton(Glyph.Shape.SPLIT, DasherSplit.SPLIT_LABEL, this::splitWithDasher);
            LinearLayout.LayoutParams splitParams = new LinearLayout.LayoutParams(ui.dp(52), ui.dp(52));
            splitParams.setMarginEnd(ui.dp(4));
            header.addView(splitButton, splitParams);
            // The existing sun/moon cycles modes directly; no chooser or duplicate Settings row.
            AppearanceButton sun = new AppearanceButton(this, ui);
            sun.show(Appearance.resolve(this));
            sun.setOnClickListener(tapped -> cycleAppearance());
            LinearLayout.LayoutParams sunParams = new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56));
            sunParams.setMarginEnd(ui.dp(10));
            header.addView(sun, sunParams);
            scene.setSunAnchor(sun);
            sunButton = sun;
            header.addView(iconButton(Glyph.Shape.SLIDERS, "Settings", () -> showSettings(true)));
        }
        return header;
    }

    /** A round button carrying a drawn icon; its description is what screen readers say. */
    private ImageButton iconButton(Glyph.Shape icon, String description, Runnable action) {
        ImageButton button = new ImageButton(this);
        button.setImageDrawable(new Glyph(icon, ui.ink, ui.dp(24)));
        button.setContentDescription(description);
        button.setBackground(ui.rounded(ui.surface, ui.dark ? 0x40FFFFFF : 0x330B0B0B, 26));
        button.setOnClickListener(clicked -> action.run());
        button.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)));
        return button;
    }

    /** The padded column a page's content goes in, between its sky and its ground. */
    private LinearLayout body(LinearLayout page) {
        return body(page, ui.column());
    }

    private LinearLayout body(LinearLayout page, LinearLayout body) {
        body.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(12));
        page.addView(body, Ui.matchWidth());
        return body;
    }

    /** The drawn hills and road a page ends on. */
    private View ground(LinearLayout page, int heightDp) {
        View ground = new View(this);
        ground.setBackground(new Scenery(Scenery.Part.GROUND, ui));
        ground.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        page.addView(ground, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(heightDp)));
        return ground;
    }

    /** A share of the height left on one screen. */
    private static LinearLayout.LayoutParams share(float weight) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight);
    }

    private void buildSheet(FrameLayout root) {
        sheet = new FrameLayout(this);
        sheet.setBackground(new android.graphics.drawable.ColorDrawable(0x66000000));
        sheet.setVisibility(View.GONE);
        sheet.setClickable(true);
        sheet.setOnClickListener(tapped -> closeSheet());
        // A drawer: a downward drag closes it, as do a tap on the dimmed page and Back.
        sheetCard = new DrawerCard(this, ui);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(ui.page);
        float corner = ui.dp(24);
        shape.setCornerRadii(new float[] {corner, corner, corner, corner, 0, 0, 0, 0});
        sheetCard.setBackground(shape);
        sheetCard.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(16));
        // Taps on the card stay on it; only the dimmed page around it closes the sheet.
        sheetCard.setClickable(true);
        View handle = new View(this);
        handle.setBackground(ui.rounded(ui.baseline, 0, 2));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = ui.dp(10);
        sheetCard.addView(handle, handleParams);
        ScrollView scroll = new ScrollView(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int most = Math.round(root.getHeight() * 0.8f);
                super.onMeasure(widthSpec, most > 0
                        ? MeasureSpec.makeMeasureSpec(most, MeasureSpec.AT_MOST) : heightSpec);
            }
        };
        LinearLayout content = ui.column();
        content.addView(ticket, Ui.matchWidth());
        scroll.addView(content, Ui.matchWidth());
        sheetCard.addView(scroll, Ui.matchWidth());
        sheetCard.setUp(scroll, sheet, this::closeSheet);
        sheet.addView(sheetCard, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        root.addView(sheet, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Slides the sheet up with {@code content} (the ticket or the map) in it. */
    private void openSheet(View content) {
        ticket.setVisibility(content == ticket ? View.VISIBLE : View.GONE);
        if (sheet.getVisibility() == View.VISIBLE) return;
        sheetCard.settle();
        sheet.setVisibility(View.VISIBLE);
        sheet.setAlpha(0f);
        sheet.animate().alpha(1f).setDuration(160);
        sheetCard.setTranslationY(ui.dp(120));
        sheetCard.animate().translationY(0).setDuration(220);
    }

    private void closeSheet() {
        if (sheet.getVisibility() != View.VISIBLE) return;
        sheet.setVisibility(View.GONE);
        if (ticketOpen) setTicketOpen(false);
    }

    // ---- Main page ----

    private void buildMain(LinearLayout page) {
        int heightDp = getResources().getConfiguration().screenHeightDp;
        compact = heightDp < COMPACT_HEIGHT_DP || (isInMultiWindowMode() && heightDp < COMPACT_SPLIT_HEIGHT_DP);
        View header = header(AppName.NAME, false);

        // The mascot is the button: a tap pauses, resumes, or with no rule yet offers typical minimums.
        hero = new FilterHeroView(this, ui);
        hero.setOnMascotClickListener(tapped -> toggleAutoDecline());
        hero.setOnCountClickListener(this::openLatestCountedOffer);
        // Words only when something needs the user: paused. On, the picture says it all; with no rule yet, the start
        // line stands under the skyline's caption, clear of the constellation's hollow knobs.
        LinearLayout lines = ui.column();
        lines.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        stateLine = ui.text("", 16, ui.ink, true);
        stateLine.setGravity(Gravity.CENTER_HORIZONTAL);
        // Only as wide as its words (and the veil the sky fades under them), so the constellation's knobs beside the
        // words take their own touches rather than this line.
        stateLine.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4));
        stateLine.setOnClickListener(tapped -> toggleAutoDecline());
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        stateParams.gravity = Gravity.CENTER_HORIZONTAL;
        lines.addView(stateLine, stateParams);
        offerCaption = ui.text("", 13, ui.ink, true);
        offerCaption.setGravity(Gravity.CENTER);
        offerCaption.setMinHeight(ui.dp(48));
        offerCaption.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        offerCaption.setOnClickListener(tapped -> {
            if (chart.selectedEntry() != null) setTicketOpen(true);
        });
        // One line under the caption, 13 sp and at least 48 dp: with no rule, the start; with Autopilot on (a whole
        // page), its status, a tap opening its details; else the wait for a matching offer, as before. Never a live
        // region: Autopilot moving its bar by itself is never announced.
        waitEstimateLine = ui.text("", 13, ui.inkSecondary, false);
        waitEstimateLine.setMinHeight(ui.dp(48));
        waitEstimateLine.setGravity(Gravity.CENTER);
        waitEstimateLine.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        waitEstimateLine.setOnClickListener(tapped -> {
            if (slot == SLOT_START) {
                showStarter();
            } else if (slot == SLOT_AUTOPILOT) {
                showAutopilotDetails();
            } else {
                OwnWindowTouches.show(new AlertDialog.Builder(this)
                        .setTitle("Time until a matching offer")
                        .setMessage(QualifyingWaitStore.estimate(this, FilterStore.load(this)).detail())
                        .setPositiveButton("OK", null));
            }
        });
        LinearLayout.LayoutParams waitParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        waitParams.gravity = Gravity.CENTER_HORIZONTAL;
        LinearLayout problems = ui.column();
        lines.addView(problems, Ui.matchWidth());
        checklist = new SetupChecklist(this, ui, problems, NOTIFICATION_PERMISSION_REQUEST);
        // A verified update held back while no dash is on: "Update ready · Install now" (the user's own check).
        updateReady = new UpdateReadyRow(this, ui, problems);
        // After a stop, with diagnostics after each dash off: one line offering a report the user still sends.
        // Offered once: the line goes as its dialog opens; the stop stays in diagnostics for a day.
        stopNotice = new Readiness(problems, AppName.NAME + " stopped unexpectedly last time", "Send report", () -> {
            StopReports.acknowledge(this);
            feedbackDialogs.feedback(Feedback.Category.BUG, true);
            refresh();
        });
        peekIntro = new PeekIntroCard(this, ui, problems, () -> showSettings(true));
        whatsNew = new WhatsNewCard(this, ui, problems);
        addFeeNotice(problems);
        routeRow = ui.row();
        routeRow.setPadding(0, ui.dp(10), 0, 0);
        routeNote = ui.text("", 13, ui.inkSecondary, false);
        routeNote.setCompoundDrawablesRelative(new Glyph(Glyph.Shape.ROAD, ui.inkSecondary, ui.dp(18)), null, null,
                null);
        routeNote.setCompoundDrawablePadding(ui.dp(8));
        routeRow.addView(routeNote, Ui.weighted());
        routeRow.addView(ui.button("Forget", false, () -> {
            ActiveRouteStore.clear(this);
            refresh();
        }));
        lines.addView(routeRow);

        // One picture from top to bottom: the sky, where the minimums' constellation spreads behind the mascot and
        // its counts; the offers as a skyline on the horizon; the map on the ground; and the road along the bottom.
        addMinimums();
        sky = new SkyStage(this, ui, minimums, hero, header, mainTitle, lines, sunButton);
        // What must be in the sky (the header, the counts and the lines), then its share of the rest.
        skyParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        page.addView(sky, skyParams);
        // The skyline and the map share the ground, each keeping the least it reads well at.
        LinearLayout body = body(page, new LeastColumn(this));
        groundParams = share(1);
        body.setLayoutParams(groundParams);
        // A stable target identifies the displayed shape and opens its ticket, clear of the minimum knobs. In a short
        // window Autopilot's chip stands at its start, under the mascot (above it when both do not fit), so the header
        // gains no row.
        chip = newAutopilotChip();
        chip.setVisibility(compact ? View.VISIBLE : View.GONE);
        body.addView(new AutopilotChip.Row(this, ui, chip, offerCaption), Ui.matchWidth());
        body.addView(waitEstimateLine, waitParams);
        addOffers(body);
        addAreas(body);
        // The road needs a whole screen; in a short window (beside Dasher or not) the page ends at the skyline or
        // the map, and a window changing size builds the page again.
        View road = ground(page, 78);
        road.setVisibility(compact || getResources().getConfiguration().fontScale >= 1.5f
                ? View.GONE : View.VISIBLE);
        // The empty title takes the header's spare room, so screen readers reach it, and hear it first.
        mainTitle.setId(View.generateViewId());
        minimums.setAccessibilityTraversalAfter(mainTitle.getId());
        if (compact) {
            // Half a screen holds the whole picture only if each part settles for a little less.
            chart.setLeastDp(52);
            chartParams.topMargin = 0;
            areaMap.setLeastDp(84);
            areaLine.setMinHeight(ui.dp(32));
            body.setPadding(body.getPaddingLeft(), 0, body.getPaddingRight(), ui.dp(4));
            // Keep every shortcut reachable even with the constellation beside another app's short pane.
            mainHeader.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), 0);
            for (int i = 0; i < mainHeader.getChildCount(); i++) {
                View child = mainHeader.getChildAt(i);
                if (child == mainTitle || child == sunButton) continue;
                child.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
            }
            sunButton.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)));
        }
        arrangeForSplit();
        // The skyline's street (12 dp above the chart's bottom) is the horizon.
        scene.setHorizon(chart, ui.dp(11), offerCaption);
        // The scene's stars, clouds and signpost keep out from under the sky's words and icons.
        scene.setOver(sky, sky);
    }

    /** Split screen with Dasher in the other half (as the screen reader last saw it). */
    private boolean besideDasherNow() {
        return isInMultiWindowMode() && OfferFilterService.dasherBeside();
    }

    /**
     * Beside Dasher, no map of our own (Dasher's is right there): the sky takes nearly all the room, the
     * constellation spread across it, over a skyline. In any other short window the map stays, the skyline keeps a
     * fixed height above it, and the constellation moves into the header, its icons beside the circle; a tap on it
     * there spreads it across the sky with its knobs, as beside Dasher (the map making room), and a tap on its circle
     * puts it back. A whole screen shows everything, the sky above the map. (The road is set once, when the page is
     * built.)
     */
    private void arrangeForSplit() {
        boolean beside = besideDasherNow();
        boolean mapless = beside || (compact && skyChosen);
        if (arranged && beside == besideDasher && mapless == noMap) return;
        arranged = true;
        besideDasher = beside;
        noMap = mapless;
        areaMap.setVisibility(mapless ? View.GONE : View.VISIBLE);
        if (mapless || shownArea == null) areaLine.setVisibility(View.GONE);
        else areaLine.setVisibility(View.VISIBLE);
        boolean inHeader = compact && !mapless;
        placeConstellation(inHeader);
        // In a short window the constellation's tap moves it between the header and the sky; elsewhere it has none.
        boolean movable = compact && !beside;
        minimums.setOnClickListener(movable ? tapped -> chooseSky(!skyChosen) : null);
        minimums.setClickable(movable);
        minimums.setClickLabel(!movable ? null : inHeader ? "set the minimums" : "show the map");
        // Beside another app the map needs the room, so the skyline stands at a fixed height rather than a share.
        chartParams.height = inHeader ? ui.dp(CHART_SHORT_DP) : 0;
        chartParams.weight = inHeader ? 0 : 0.7f;
        chart.setLayoutParams(chartParams);
        // The sky's share against the ground's (the skyline, and the map where there is one).
        skyParams.weight = mapless ? SKY_BESIDE_DASHER : inHeader ? SKY_WITH_MAP_SHORT : SKY_WHOLE;
        groundParams.weight = mapless ? 0.7f : inHeader ? 3.2f : 1.7f;
        sky.requestLayout();
    }

    /** In a short window, the constellation spread across the sky with its knobs ({@code on}), or in the header. */
    private void chooseSky(boolean on) {
        if (!compact || besideDasher || skyChosen == on) return;
        skyChosen = on;
        arrangeForSplit();
        // The start line follows the knobs (see refresh).
        refresh();
        if (on) minimums.beckon();
    }

    /** The constellation is the sky, with its knobs (a whole screen, beside Dasher, or chosen into a short window's sky). */
    private boolean knobsInSky() {
        return !compact || noMap;
    }

    /** The constellation in the header's left (drawn with its icons beside the circle), or spread across the sky. */
    private void placeConstellation(boolean inHeader) {
        ViewGroup now = (ViewGroup) minimums.getParent();
        if (now != null && inHeader == (now == mainHeader)) return;
        if (now != null) now.removeView(minimums);
        minimums.setBeside(inHeader);
        if (inHeader) {
            // Clear of the split screen's handle at the middle of the top edge.
            int size = getResources().getConfiguration().screenWidthDp < 344 ? 56 : HEADER_STAR_DP;
            mainHeader.addView(minimums, 0, new LinearLayout.LayoutParams(
                    ui.dp(MinimumsStarView.besideWidthDp(size)), ui.dp(size)));
        } else {
            sky.holdStar();
        }
    }

    private void addOffers(LinearLayout body) {
        chart = new DecisionChartView(this, ui);
        chart.setOnSelect(entry -> {
            countTicket = null;
            followNewest = !recentEntries.isEmpty() && entry == recentEntries.get(0);
            // By area, the constellation picks out the chosen offer's polygon and score.
            if (minimums != null) minimums.emphasize(followNewest ? null : entry);
            showSelection(entry);
        });
        // A tapped building opens its ticket; the screen's own choice of the newest does not.
        chart.setOnClickListener(tapped -> setTicketOpen(true));
        chartParams = share(0.7f);
        chartParams.topMargin = ui.dp(6);
        body.addView(chart, chartParams);
        // The ticket unfolds in the sheet over the page.
        ticket = ui.column();
        ticketShape = new Decor.Ticket(ui);
        ticket.setBackground(ticketShape);
        ticket.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(14));
        ticket.setVisibility(View.GONE);
    }

    /** Unfolds or folds the chosen offer's ticket. */
    private void setTicketOpen(boolean open) {
        countTicket = null;
        ticketOpen = open;
        if (chart.selectedEntry() != null) showSelection(chart.selectedEntry());
        else if (minimums != null) minimums.showTicket(null);
        if (open) openSheet(ticket);
        else if (sheet != null && ticket.getVisibility() != View.VISIBLE) sheet.setVisibility(View.GONE);
    }

    /**
     * An offer picked on the constellation (its mark or its shape) opens exactly as a tap on its building does: chosen
     * in the skyline (and so on the constellation), its ticket unfolded.
     */
    private void openOffer(DecisionLog.Entry entry) {
        if (!chart.choose(entry)) return;
        setTicketOpen(true);
    }

    /**
     * The minimums' constellation, where every rule is set. The sky or the header holds it, as the window allows. In
     * the sky its knobs set the minimums and its badge the max stops, each saved at once through {@link FilterStore},
     * and its Autopilot button turns Autopilot off (or on, through the goal chooser) and, held, changes the goal; a tap
     * on a marked offer opens its ticket, as its building in the skyline does, and a tap off every offer while an older
     * one is chosen chooses the newest again.
     */
    private void addMinimums() {
        minimums = new MinimumsStarView(this, ui);
        minimums.setOfferTaps(new MinimumsStarView.OfferTaps() {
            @Override public void open(DecisionLog.Entry entry) {
                openOffer(entry);
            }

            @Override public void chooseNewest() {
                if (!recentEntries.isEmpty()) chart.choose(recentEntries.get(0));
            }
        });
        minimums.setChanges(new MinimumsStarView.Changes() {
            @Override public void setMinimum(int axis, int cents) {
                MainActivity.this.setMinimum(axis, cents);
            }

            @Override public void setMaxStops(int stops) {
                setStops(stops);
            }

            @Override public void toggleAutopilot() {
                MainActivity.this.toggleAutopilot();
            }

            @Override public void chooseAutopilotGoal() {
                MainActivity.this.chooseAutopilotGoal();
            }

            @Override public void showAutopilotDetails() {
                MainActivity.this.showAutopilotDetails();
            }
        });
    }

    /**
     * A minimum set on the constellation (a knob let go or adjusted by a screen reader): pay, per mile or per minute of
     * trip time, in cents (0: off), saved at once through {@link FilterStore} and applied to any offer on screen.
     *
     * @return whether the rules were saved
     */
    private boolean setMinimum(int axis, int cents) {
        FilterSettings saved = FilterStore.load(this);
        int value = Math.max(0, Math.min(FilterSettings.MOST_CENTS, cents));
        int flat = axis == AreaScore.PAY ? value : saved.flatCents;
        int mile = axis == AreaScore.MILE ? value : saved.perMileCents;
        int minute = axis == AreaScore.MINUTE ? value : saved.perMinuteCents;
        if (flat == saved.flatCents && mile == saved.perMileCents && minute == saved.perMinuteCents) return false;
        return saveRules(saved, saved.withMinimums(flat, mile, minute));
    }

    /** Max stops set on the constellation's badge (0: no limit), saved at once. @return whether it changed */
    private boolean setStops(int stops) {
        FilterSettings saved = FilterStore.load(this);
        int next = Math.max(0, Math.min(99, stops));
        if (next == saved.maxStops) return false;
        return saveRules(saved, saved.withMaxStops(next));
    }

    /**
     * Saves rules changed on the constellation and applies them to any offer on screen. The on or paused state stays as
     * saved, except that no rule left pauses; a first rule saved while paused says auto-decline stays paused (nothing
     * here ever turns auto-decline on).
     */
    private boolean saveRules(FilterSettings saved, FilterSettings rules) {
        FilterSettings stored = storeRules(saved, rules);
        boolean firstRule = !saved.hasAnyRule() && stored.hasAnyRule() && !stored.enabled;
        if (rules.enabled && !stored.enabled) toast("No rules left, so auto-decline is paused.");
        else if (firstRule) toast(FIRST_RULE);
        return true;
    }

    /**
     * Saves {@code rules} (no rule left pauses auto-decline), tells Autopilot when a minimum or max stops changed (never
     * for pausing or resuming), and applies them to any offer on screen. @return the rules as saved
     */
    private FilterSettings storeRules(FilterSettings saved, FilterSettings rules) {
        if (rules.enabled && !rules.hasAnyRule()) rules = rules.withEnabled(false);
        FilterStore.save(this, rules);
        if (rules.flatCents != saved.flatCents || rules.perMileCents != saved.perMileCents
                || rules.perMinuteCents != saved.perMinuteCents || rules.maxStops != saved.maxStops) {
            AutopilotRuntime.rulesChanged(this);
        }
        rulesChanged();
        updateMeter();
        return rules;
    }

    // ---- Autopilot ----

    /**
     * Autopilot's chip, wired: a tap opens the details, a long press the goal chooser. For any strip on this screen
     * that hosts one: while it is attached it follows Autopilot's status with the page's own.
     */
    AutopilotChip newAutopilotChip() {
        AutopilotChip made = new AutopilotChip(this, ui);
        made.setOnClickListener(tapped -> showAutopilotDetails());
        made.setOnLongClickListener(held -> {
            chooseAutopilotGoal();
            return true;
        });
        if (autopilotStatus != null) made.show(autopilotStatus);
        made.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View attached) {
                if (!chips.contains(made)) chips.add(made);
                if (autopilotStatus != null) made.show(autopilotStatus);
            }

            @Override public void onViewDetachedFromWindow(View detached) {
                chips.remove(made);
            }
        });
        return made;
    }

    /** What Autopilot is doing now (finalSpec explainability), as the button, the chip and the status line say it. */
    private AutopilotText.Status autopilotStatus() {
        return AutopilotRuntime.status(this, System.currentTimeMillis(), OfferFilterService.isConnected());
    }

    /** The Autopilot button's tap: off while on (exactly the minimums again); while off, the goal chooser. */
    private void toggleAutopilot() {
        FilterSettings saved = FilterStore.load(this);
        if (saved.autopilot) {
            AutopilotRuntime.setAutopilot(this, false, saved.autopilotGoalPercent);
            toast(AutopilotText.TOAST_OFF);
            refresh();
        } else if (!saved.hasMonetaryRule()) {
            toast(AutopilotText.TOAST_NEEDS_MINIMUM);
        } else {
            chooseAutopilotGoal();
        }
    }

    /**
     * "What matters more?", a single-choice list with the stored goal checked (70% at first): one tap applies it and
     * closes (Autopilot on with it, or its goal changed while on); "About acceptance rate" explains, leaving the choice
     * open; "Not now" (or Back) leaves Autopilot as it was. Any answer counts as asked. With Autopilot off and no money
     * minimum, it cannot be on: the toast says what is needed.
     */
    private void chooseAutopilotGoal() {
        FilterSettings saved = FilterStore.load(this);
        if (!saved.autopilot && !saved.hasMonetaryRule()) {
            toast(AutopilotText.TOAST_NEEDS_MINIMUM);
            return;
        }
        String[] items = AutopilotText.chooserItems().toArray(new String[0]);
        AlertDialog chooser = OwnWindowTouches.show(new AlertDialog.Builder(this)
                .setTitle(AutopilotText.CHOOSER_TITLE)
                .setSingleChoiceItems(items, AutopilotText.chooserIndex(saved.autopilotGoalPercent), (dialog, which) -> {
                    dialog.dismiss();
                    chooseGoal(Autopilot.GOALS.get(which));
                })
                .setNeutralButton(AutopilotText.CHOOSER_ABOUT, null)
                .setNegativeButton(AutopilotText.CHOOSER_NOT_NOW, (dialog, which) -> FilterStore.setGoalAsked(this, true))
                .setOnCancelListener(dialog -> FilterStore.setGoalAsked(this, true)));
        // About explains without closing the choice.
        android.widget.Button about = chooser.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (about != null) about.setOnClickListener(tapped -> OwnWindowTouches.show(new AlertDialog.Builder(this)
                .setTitle(AutopilotText.CHOOSER_ABOUT)
                .setMessage(AutopilotText.ABOUT_ACCEPTANCE_RATE)
                .setPositiveButton("OK", null)));
    }

    /** A goal chosen: Autopilot on with it (or the goal changed while on), said in a toast. */
    private void chooseGoal(int goal) {
        boolean wasOn = FilterStore.load(this).autopilot;
        AutopilotRuntime.setAutopilot(this, true, goal);
        toast(wasOn ? AutopilotText.toastGoalChanged(goal) : AutopilotText.toastTurnedOn(goal));
        refresh();
    }

    /**
     * Autopilot's details ({@link AutopilotText#detailsLines}): each line only when its facts are known; "Change goal"
     * ("Use typical minimums" while even the lowest bar passes too few), "Turn off" or "Turn on", and "Close".
     */
    private void showAutopilotDetails() {
        AutopilotText.Status status = autopilotStatus();
        List<String> buttons = AutopilotText.detailsButtons(status);
        OwnWindowTouches.show(new AlertDialog.Builder(this)
                .setTitle(AutopilotText.DETAILS_TITLE)
                .setMessage(String.join("\n\n", AutopilotText.detailsLines(status)))
                .setNeutralButton(buttons.get(0), (dialog, which) -> {
                    if (status.pinned()) {
                        useTypicalMinimums("Autopilot's details");
                        toast(typicalSet());
                    } else {
                        chooseAutopilotGoal();
                    }
                })
                .setNegativeButton(buttons.get(1), (dialog, which) -> toggleAutopilot())
                .setPositiveButton(buttons.get(2), null));
    }

    /**
     * With no rule yet: "Start with typical minimums?" "Use these" saves $4.00, $1.00 a mile and $15 an hour (auto-decline
     * stays paused; the mascot turns it on) and asks for Autopilot's goal; "Set my own" makes the hollow knobs beckon.
     */
    private void showStarter() {
        OwnWindowTouches.show(new AlertDialog.Builder(this)
                .setTitle(STARTER_TITLE)
                .setMessage(STARTER_TEXT)
                .setPositiveButton(STARTER_USE, (dialog, which) -> {
                    useTypicalMinimums("the start line");
                    chooseAutopilotGoal();
                    toast(FIRST_RULE);
                })
                .setNegativeButton(STARTER_OWN, (dialog, which) -> beckonKnobs()));
    }

    /**
     * Typical minimums ($4.00, $1.00 a mile, $15 an hour), max stops and the switch as they are, logged as chosen from
     * {@code where}. With Autopilot on its bar goes back to exactly 100% first, so its plan for them starts there.
     */
    private void useTypicalMinimums(String where) {
        FilterSettings saved = FilterStore.load(this);
        AutopilotRuntime.barBackToMinimums(this);
        storeRules(saved, saved.withMinimums(Autopilot.STARTER_FLAT_CENTS, Autopilot.STARTER_PER_MILE_CENTS,
                Autopilot.STARTER_PER_MINUTE_CENTS));
        DiagnosticLog.log(this, "rules", "typical minimums chosen from " + where);
    }

    /** "Typical minimums set: $4 · $1/mi · $15/hr". */
    private static String typicalSet() {
        return "Typical minimums set: " + DecisionLog.shortMoney(Autopilot.STARTER_FLAT_CENTS) + " · "
                + DecisionLog.shortMoney(Autopilot.STARTER_PER_MILE_CENTS) + "/mi · "
                + DecisionLog.shortMoney(Autopilot.STARTER_PER_MINUTE_CENTS * 60L) + "/hr";
    }

    /**
     * The one-time 0.5.0 notice ({@link FilterStore#peekModelNotice}): what changed, in the owner's order, and, when the
     * minimums would have passed fewer than one in five of the last offers, the pass check and "Use typical minimums".
     * It stays until a button is tapped (a recreated screen shows it again).
     */
    private void showModelNotice(String json) {
        org.json.JSONObject facts;
        try {
            facts = new org.json.JSONObject(json);
        } catch (org.json.JSONException unreadable) {
            FilterStore.dismissModelNotice(this);
            return;
        }
        List<String> lines = modelNoticeLines(facts);
        int[] check = passCheck(FilterStore.load(this), DecisionLog.recent(this, DecisionLog.MAX_ENTRIES));
        if (check != null) {
            lines.add("Your minimums would have passed " + check[0] + " of your last " + check[1] + " offers.");
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(NOTICE_TITLE)
                .setMessage("• " + String.join("\n\n• ", lines))
                .setCancelable(false);
        if (check != null) {
            builder.setPositiveButton(NOTICE_TYPICAL, (dialog, which) -> {
                noticeAnswered();
                useTypicalMinimums("the 0.5.0 notice");
                toast(typicalSet());
                chooseAutopilotGoal();
            }).setNegativeButton(NOTICE_KEEP, (dialog, which) -> noticeAnswered());
        } else {
            builder.setPositiveButton(NOTICE_OK, (dialog, which) -> noticeAnswered())
                    .setNeutralButton(NOTICE_AUTOPILOT, (dialog, which) -> {
                        noticeAnswered();
                        chooseAutopilotGoal();
                    });
        }
        modelNotice = OwnWindowTouches.show(builder);
    }

    private void noticeAnswered() {
        FilterStore.dismissModelNotice(this);
        modelNotice = null;
    }

    /** The notice's lines that apply, in order, from the migration's facts (money in cents). */
    static List<String> modelNoticeLines(org.json.JSONObject facts) {
        List<String> lines = new java.util.ArrayList<>();
        if (facts.optBoolean("area")) {
            lines.add("Score by area is gone: an offer now has to meet each of your minimums.");
        }
        int perStop = facts.optInt("perStop");
        if (perStop > 0) {
            int folded = facts.optInt("foldedFlat");
            int before = facts.optInt("flatBefore");
            lines.add(folded > before
                    ? "Per stop is gone. Your " + DecisionLog.money(perStop) + " per stop now counts as a "
                            + DecisionLog.money(folded) + " minimum pay, so single orders are judged the same. Use Max "
                            + "stops to limit stacked orders."
                    : "Per stop is gone. Your " + DecisionLog.money(perStop) + " per stop was below your "
                            + DecisionLog.money(before) + " minimum pay, so single orders are judged the same. Use Max "
                            + "stops to limit stacked orders.");
        }
        int buffer = facts.optInt("buffer", FilterSettings.BAR_AT_MINIMUMS);
        if (buffer != FilterSettings.BAR_AT_MINIMUMS) {
            List<String> now = new java.util.ArrayList<>();
            if (facts.optInt("newFlat") > 0) now.add(DecisionLog.money(facts.optInt("newFlat")));
            if (facts.optInt("newMile") > 0) now.add(DecisionLog.money(facts.optInt("newMile")) + "/mi");
            if (facts.optInt("newMinute") > 0) now.add(DecisionLog.money(facts.optInt("newMinute") * 60L) + "/hr");
            lines.add("Your " + buffer + "% buffer is now built into your minimums"
                    + (now.isEmpty() ? "." : ": " + String.join(" · ", now) + "."));
        }
        if (facts.optInt("perItem") > 0) {
            lines.add("Pay per item is gone: shopping time is already in each offer's minutes.");
        }
        if (facts.optBoolean("adaptive")) {
            lines.add("Learned minimums are gone; they only ever rose. Autopilot can adjust to your offers instead.");
        }
        if (facts.optInt("hotspot") > 0) lines.add("The hotspot rule is gone: its distance could never be read.");
        if (facts.optBoolean("autoAcceptOff")) {
            lines.add("Auto-accept is off until you turn it on again in Settings, because your rules changed.");
        }
        if (facts.optBoolean("paused")) lines.add("No rule was left, so auto-decline is paused.");
        return lines;
    }

    /**
     * The notice's pass check: of the newest {@link #PASS_CHECK_LINES} standalone, non-replay lines with pay, miles
     * and minutes read, how many {@code rules} pass at exactly 100%: {k, n}, only when {@code n ≥ 20} and
     * {@code k × 5 < n}; else null.
     */
    static int[] passCheck(FilterSettings rules, List<DecisionLog.Entry> newestFirst) {
        FilterSettings exact = rules.withMinimumScalePercent(FilterSettings.BAR_AT_MINIMUMS);
        int counted = 0;
        int passed = 0;
        for (DecisionLog.Entry entry : newestFirst) {
            if (counted >= PASS_CHECK_LINES) break;
            OfferSnapshot facts = entry.facts;
            if (entry.addOn || entry.replay || entry.action == DecisionLog.Action.REPLAY || facts.payCents == null
                    || facts.miles == null || facts.minutes == null) continue;
            counted++;
            if (OfferRule.evaluate(facts, exact).result == OfferRule.Result.KEEP) passed++;
        }
        return counted >= PASS_CHECK_LEAST && passed * 5 < counted ? new int[] {passed, counted} : null;
    }

    /**
     * The ground: the offer areas as a map, always on the page, and one line under it naming the chosen area, which
     * opens it in Maps. Before location is allowed (or with mapping turned off), a tap on the ground asks for it.
     */
    private void addAreas(LinearLayout body) {
        areaMap = new AreaMapView(this, ui);
        areaMap.setOnSelect(cell -> {
            pickedArea = true;
            showArea(cell);
        });
        areaMap.setOnClickListener(tapped -> {
            if (!AreaMap.enabled(this)) {
                AreaMap.setEnabled(this, true);
                refresh();
            }
            if (!AreaMap.hasPermission(this)) askForLocation();
        });
        // In a short window the map is what the page is for, beside Dasher's own.
        LinearLayout.LayoutParams mapParams = share(compact ? 2f : 1f);
        mapParams.topMargin = ui.dp(2);
        body.addView(areaMap, mapParams);
        areaLine = ui.text("", 13, ui.ink, true);
        // A rate and its sample count can wrap at large font sizes; keep the two caption lines compact.
        areaLine.setLineSpacing(0, 1f);
        areaLine.setGravity(Gravity.CENTER);
        areaLine.setMinHeight(ui.dp(36));
        areaLine.setBackground(ui.pressable(12));
        areaLine.setOnClickListener(tapped -> openArea());
        areaLine.setVisibility(View.GONE);
        body.addView(areaLine, Ui.matchWidth());
    }

    // ---- Settings page ----

    /**
     * Only what exists nowhere else, one row each and no paragraphs: setup that still needs a fix (and has no row on
     * the homepage), behavior switches, updates, anonymous feedback, reports and a tip, then the version and the
     * bundled texts.
     * The rules are all on the homepage's constellation.
     */
    private void buildSettings(LinearLayout page) {
        page.addView(header("Settings", true));
        LinearLayout body = body(page);
        LinearLayout setup = ui.column();
        body.addView(setup, Ui.matchWidth());
        doorDashAlerts = new Readiness(setup, "DoorDash's offer alert also sounded", this::openDoorDashChannel);
        battery = new Readiness(setup, BatteryLimits.PROBLEM, () -> BatteryLimits.open(this, this::open));
        location = new Readiness(setup, "The offer map needs location", this::askForLocation);
        locationAllTheTime = new Readiness(setup, "The offer map needs location all the time", this::askForLocation);

        LinearLayout switches = ui.column();
        body.addView(switches, Ui.matchWidth());
        Switch mute = ui.toggle(switches, "Quiet Dasher while declining",
                FilterStore.silenceWhileDeclining(this));
        mute.setOnCheckedChangeListener((view, on) -> FilterStore.setSilenceWhileDeclining(this, on));
        // Peek (on unless turned off): Dasher is brought up for a moment to read a background offer.
        Switch peek = ui.toggle(switches, "Peek at background offers", FilterStore.peek(this));
        peek.setOnCheckedChangeListener((view, on) -> {
            if (on == FilterStore.peek(this)) return;
            FilterStore.setPeek(this, on);
            DiagnosticLog.log(this, "peek", "turned " + (on ? "on" : "off") + " in Settings");
        });
        Switch autoAccept = ui.toggle(switches, "Auto-accept matching offers", FilterStore.autoAcceptEnabled(this));
        autoAccept.setOnCheckedChangeListener((view, on) -> {
            if (on == FilterStore.autoAcceptEnabled(this)) return;
            if (!on) {
                FilterStore.setAutoAcceptEnabled(this, false);
                DiagnosticLog.log(this, "auto-accept", "turned off in Settings");
                return;
            }
            // The switch stays off until the separate commitment is explicitly confirmed.
            autoAccept.setChecked(false);
            OwnWindowTouches.show(new AlertDialog.Builder(this)
                    .setTitle(AUTO_ACCEPT_TITLE)
                    .setMessage(AUTO_ACCEPT_MESSAGE)
                    .setNegativeButton("Not now", null)
                    .setPositiveButton("Enable auto-accept", (dialog, which) -> {
                        FilterStore.setAutoAcceptEnabled(this, true);
                        autoAccept.setChecked(true);
                        DiagnosticLog.log(this, "auto-accept", "explicitly enabled in Settings");
                    }));
        });
        areasToggle = ui.toggle(switches, "Offer map", AreaMap.enabled(this));
        areasToggle.setOnCheckedChangeListener((view, on) -> {
            if (on == AreaMap.enabled(this)) return;
            AreaMap.setEnabled(this, on);
            if (on && !AreaMap.hasPermission(this)) askForLocation();
            refresh();
        });

        LinearLayout connections = group(body);
        // Checks are always automatic; a tap checks now. Public signed updates need no user account.
        // A verified update that waits only for updates to be allowed: the tap opens that switch instead.
        updatesRow = ui.listRow(connections, "Updates", () -> {
            if (Updater.waitsForPermission(this)) open(UpdateNotices.allowUpdates(this));
            else Updater.check(this, true, null);
        });
        // The website's install and setup help (BETA-22).
        ui.setRow(ui.listRow(connections, BetaProgram.HELP, () -> open(BetaProgram.help())), BetaProgram.HELP,
                BetaProgram.HELP_DETAIL);

        LinearLayout reports = group(body);
        feedbackRow = ui.listRow(reports, "Send anonymous feedback", () -> feedbackDialogs.feedback(null, null));
        // Off unless the user turns it on (no update does), and only after its own confirmation.
        afterDashToggle = ui.toggle(reports, "Share anonymous diagnostics after each dash", Feedback.afterDashOn(this));
        afterDashToggle.setOnCheckedChangeListener((view, on) -> {
            if (on == Feedback.afterDashOn(this)) return;
            if (!on) {
                Feedback.setAfterDash(this, false);
                refresh();
                return;
            }
            afterDashToggle.setChecked(false);
            OwnWindowTouches.show(new AlertDialog.Builder(this)
                    .setTitle("Share diagnostics after each dash?")
                    .setMessage("After each dash, sends one masked summary to the developer, with no account: the "
                            + "app and Android versions and phone maker, your switches, counts of offers and problems, "
                            + "that dash's decisions timed from its start, and masked log lines around problems. No "
                            + "location, place names or device ID. At most 3 a day; kept 90 days.")
                    .setNegativeButton("Not now", null)
                    .setPositiveButton("Turn on", (dialog, which) -> {
                        Feedback.setAfterDash(this, true);
                        afterDashToggle.setChecked(true);
                        refresh();
                    }));
        });
        shareReportRow = ui.listRow(reports, "Share report", this::shareReport);
        ui.listRow(reports, "Clear history", this::confirmClearHistory);

        if (!Support.methods().isEmpty()) ui.listRow(group(body), "Tip", this::chooseTip);

        TextView footer = ui.text("", 12, ui.inkSecondary, false);
        // The version with a small Beta label beside it (BETA-22).
        footer.setText(BetaProgram.footer(ui, Updater.version(this)));
        footer.setContentDescription(BetaProgram.footerSaid(Updater.version(this)));
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setPadding(0, ui.dp(28), 0, 0);
        body.addView(footer, Ui.matchWidth());
        // The terms, the privacy text and the licence, bundled: they open with no connection.
        LinearLayout texts = ui.row();
        texts.setGravity(Gravity.CENTER_HORIZONTAL);
        for (LegalTexts.Doc doc : LegalTexts.Doc.values()) texts.addView(ui.link(doc.title, () -> read(doc)));
        body.addView(texts, Ui.matchWidth());
        ImageView closing = new ImageView(this);
        closing.setImageDrawable(new SettingsSignatureDrawable(getResources()));
        closing.setContentDescription("Jesus Loves You. We love each other because He loves us first. 1 John 4:19.");
        closing.setAdjustViewBounds(true);
        closing.setScaleType(ImageView.ScaleType.FIT_CENTER);
        closing.setMaxWidth(ui.dp(121)); // 55% of the previous 220 dp signature.
        closing.setMaxHeight(ui.dp(113)); // Scaled height plus breathing room before the passage.
        closing.setColorFilter(ui.dark ? android.graphics.Color.WHITE : 0xFF776550);
        closing.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        LinearLayout.LayoutParams signature = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        signature.gravity = Gravity.CENTER_HORIZONTAL;
        signature.topMargin = ui.dp(16);
        signature.bottomMargin = ui.dp(12);
        body.addView(closing, signature);
        ground(page, 170);
    }

    /** A group of rows, set a little apart from the one above. */
    private LinearLayout group(LinearLayout body) {
        LinearLayout group = ui.column();
        LinearLayout.LayoutParams params = Ui.matchWidth();
        params.topMargin = ui.dp(18);
        body.addView(group, params);
        return group;
    }

    /** The configured ways to tip, to choose from; each opens only at the user's tap. */
    private void chooseTip() {
        List<Support.Method> methods = Support.methods();
        if (methods.isEmpty()) return;
        String[] labels = new String[methods.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = methods.get(i).label;
        OwnWindowTouches.show(new AlertDialog.Builder(this)
                .setTitle("Tip with")
                .setItems(labels, (dialog, which) ->
                        open(new Intent(Intent.ACTION_VIEW, Uri.parse(Support.link(methods.get(which))))))
                .setNegativeButton("Cancel", null));
    }

    // ---- State ----

    private void cycleAppearance() {
        if (changingAppearance) return;
        if (!Appearance.choose(this, Appearance.mode(this).next())) {
            toast("Theme could not be saved. Please try again.");
            return;
        }
        appearanceCheckedAt = -1;
        refreshAppearance();
        Appearance.State state = Appearance.resolve(this);
        toast(state.mode.label + (state.mode == Appearance.Mode.AUTO && state.clockFallback
                ? " · 6 am–6 pm local-clock fallback" : ""));
    }

    /** A visible page follows sunrise/sunset within a minute; no timer or location read while stopped. */
    private boolean refreshAppearance() {
        if (changingAppearance) return true;
        long now = android.os.SystemClock.uptimeMillis();
        if (appearanceCheckedAt >= 0 && now >= appearanceCheckedAt && now - appearanceCheckedAt < 60_000) return false;
        appearanceCheckedAt = now;
        Appearance.State appearance = Appearance.resolve(this);
        if (sunButton != null) sunButton.show(appearance);
        if (appearance.night == ui.dark) return false;
        changingAppearance = true;
        recreate();
        return true;
    }

    private void refreshScreenAwake() {
        int flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        if (resumed && OfferFilterService.isConnected() && !Dashing.isPaused(this)
                && ScreenAwake.wanted(this)) getWindow().addFlags(flag);
        else getWindow().clearFlags(flag);
    }

    private void refresh() {
        refreshScreenAwake();
        if (stateLine == null || noticeShown()) return;
        if (refreshAppearance()) return;
        arrangeForSplit();
        FilterSettings saved = FilterStore.load(this);
        int state = saved.enabled ? 1 : saved.hasAnyRule() ? 2 : 3;
        shownState = state;
        // With no rule yet, the start line: on the ground, under the latest offer's line, while the constellation's
        // knobs are in the sky, so it never covers one; in the sky under the mascot while a short window's header holds
        // the constellation (no knob in the sky to cover, and its ground has no row to spare).
        boolean startOnGround = knobsInSky();
        if (saved.enabled) {
            stateLine.setText("");
            hero.setAction("Pause auto-decline");
        } else if (saved.hasAnyRule()) {
            stateLine.setText("Paused");
            hero.setAction("Resume auto-decline");
        } else {
            stateLine.setText(startOnGround ? "" : START_LINE);
            hero.setAction("Set up rules");
        }
        stateLine.setVisibility(saved.enabled || (!saved.hasAnyRule() && startOnGround) ? View.GONE : View.VISIBLE);
        if (splitButton != null) {
            splitButton.setVisibility(DasherSplit.offered(this, dasherInstalled.get()) ? View.VISIBLE : View.GONE);
            String label = DasherSplit.label(this);
            if (!label.equals(String.valueOf(splitButton.getContentDescription()))) {
                splitButton.setContentDescription(label);
            }
        }

        boolean readerConnected = OfferFilterService.isConnected();
        // Setup prompts take precedence over optional timing, especially at large font sizes in a short window.
        readyForOffers = saved.enabled && saved.hasAnyRule() && readerConnected
                && OfferNotificationService.isConnected() && alertsAllowed.get();
        QualifyingWait.Estimate estimate = QualifyingWaitStore.estimate(this, saved);
        boolean showWait = readyForOffers && estimate.status == QualifyingWait.Status.READY
                && QualifyingWaitStore.observingWaiting(this);
        AutopilotText.Status status = autopilotStatus();
        refreshAutopilot(status);
        // The ground's one line: the start with no rule (while the knobs are in the sky); Autopilot's status while it
        // is on (the chip says it in a short window); else the wait, as before.
        if (!saved.hasAnyRule()) {
            if (startOnGround) showSlot(SLOT_START, START_LINE, null, ui.link);
            else showSlot(SLOT_NONE, null, null, ui.inkSecondary);
        } else if (saved.autopilot && !compact) {
            showSlot(SLOT_AUTOPILOT, AutopilotText.statusLine(status), AutopilotText.chipDescription(status),
                    ui.inkSecondary);
        } else if (showWait) {
            showSlot(SLOT_WAIT, estimate.label(), null, ui.inkSecondary);
        } else {
            showSlot(SLOT_NONE, null, null, ui.inkSecondary);
        }
        checklist.refresh(readerConnected, alertsAllowed.get());
        updateReady.refresh(checklist.installsAllowed());
        stopNotice.update(Feedback.afterDashOn(this) || !StopReports.unacknowledged(this));
        peekIntro.refresh(saved.enabled);
        whatsNew.refresh();
        refreshFeeNotice();
        OfferSnapshot route = ActiveRouteStore.load(this);
        routeRow.setVisibility(route == null ? View.GONE : View.VISIBLE);
        if (route != null) routeNote.setText("On a route: " + route.summary());

        refreshHistory();
        refreshOfferCaption(chart.selectedEntry());
        refreshLive();
        // Rules saved elsewhere, and Autopilot moving its bar or another goal chosen without a new offer in the
        // history: the star and the skyline's rails follow them as well.
        String rules = saved.describe() + "/" + saved.autopilot + "/" + saved.autopilotGoalPercent;
        if (!rules.equals(shownRules)) {
            shownRules = rules;
            updateMeter();
        }
        refreshAreas();
        refreshHero(saved.enabled ? FilterHeroView.State.ON
                : saved.hasAnyRule() ? FilterHeroView.State.PAUSED : FilterHeroView.State.OFF);
        updatingCover.refresh();
        refreshSettings();
        // The one-time 0.5.0 notice, once the screen is up (never behind the first-run notice).
        if (started && !modelNoticeAsked) {
            modelNoticeAsked = true;
            String notice = FilterStore.peekModelNotice(this);
            if (notice != null) showModelNotice(notice);
        }
    }

    /**
     * Autopilot as everything on the page shows it: the constellation's button (and its dashed shape), and the chips.
     * Quiet: nothing is announced when Autopilot moves the bar by itself.
     */
    private void refreshAutopilot(AutopilotText.Status status) {
        autopilotStatus = status;
        minimums.setAutopilot(status.on, status.bar, status.belowGoal(), status.pinned());
        for (AutopilotChip each : chips) each.show(status);
    }

    /** The ground's one line, as {@code which} says ({@link #SLOT_NONE} hides it). */
    private void showSlot(int which, String words, String said, int color) {
        slot = which;
        waitEstimateLine.setVisibility(which == SLOT_NONE ? View.GONE : View.VISIBLE);
        if (which == SLOT_NONE) return;
        if (!words.contentEquals(waitEstimateLine.getText())) waitEstimateLine.setText(words);
        if (!java.util.Objects.equals(said, waitEstimateLine.getContentDescription() == null ? null
                : waitEstimateLine.getContentDescription().toString())) {
            waitEstimateLine.setContentDescription(said);
        }
        if (waitEstimateLine.getCurrentTextColor() != color) waitEstimateLine.setTextColor(color);
        waitEstimateLine.setTypeface(which == SLOT_START ? Ui.MEDIUM : android.graphics.Typeface.DEFAULT);
    }

    /** Settings' rows: what needs a fix and where the accountless updater stands. */
    private void refreshSettings() {
        doorDashAlerts.update(!FilterStore.doorDashChannelAlerts(this));
        battery.update(!batteryRestricted.get());
        ui.setRow(updatesRow, "Updates", Updater.status(this));
        // What waits to send is read only while Settings shows, and as it opens.
        if (showingSettings) ui.setRow(feedbackRow, "Send anonymous feedback", Feedback.status(this));
        boolean afterDash = Feedback.afterDashOn(this);
        if (afterDashToggle.isChecked() != afterDash) afterDashToggle.setChecked(afterDash);
    }

    /** A submission changed (sent, waiting, refused): Settings' line follows, if the page is up. */
    private void feedbackChanged() {
        if (!noticeShown()) refreshSettings();
    }

    /**
     * The homepage's one-time note that an older version's extra-stop fee was retired: taken from the store the first
     * time the homepage shows (never behind the first-run notice), kept through a recreation, gone once tapped.
     */
    private void addFeeNotice(LinearLayout parent) {
        feeRow = ui.row();
        feeRow.setBackground(ui.pressable(16));
        feeRow.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6));
        feeRow.setMinimumHeight(ui.dp(48));
        feeRow.setClickable(true);
        feeRow.setFocusable(true);
        feeRow.setOnClickListener(tapped -> {
            feeNotice = null;
            refreshFeeNotice();
        });
        View sign = new View(this);
        sign.setBackground(new Glyph(Glyph.Shape.PIN, ui.accent, ui.dp(20)));
        sign.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        feeRow.addView(sign, new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
        feeText = ui.text("", 14, ui.ink, false);
        feeText.setPadding(ui.dp(10), 0, ui.dp(8), 0);
        feeRow.addView(feeText, Ui.weighted());
        feeRow.addView(ui.text("OK", 15, ui.accent, true));
        feeRow.setVisibility(View.GONE);
        parent.addView(feeRow, Ui.matchWidth());
    }

    private void refreshFeeNotice() {
        if (!feeNoticeAsked) {
            feeNoticeAsked = true;
            feeNotice = FilterStore.takeStopFeeNotice(this);
        }
        boolean shown = feeNotice != null;
        if (shown && !feeNotice.contentEquals(feeText.getText())) {
            feeText.setText(feeNotice);
            feeRow.setContentDescription(feeNotice + " OK.");
        }
        if (shown != (feeRow.getVisibility() == View.VISIBLE)) feeRow.setVisibility(shown ? View.VISIBLE : View.GONE);
    }

    /** While dashing, the searchlights sweep the sky; screen readers hear it from the mascot. */
    private void refreshLive() {
        boolean live = Dashing.now(this);
        scene.setWatching(live);
        hero.setWatching(live);
    }

    private void refreshHistory() {
        long version = DecisionLog.version();
        if (version == shownHistoryVersion) return;
        shownHistoryVersion = version;
        List<DecisionLog.Entry> recent = DecisionLog.recent(this, DecisionLog.MAX_ENTRIES);
        recentEntries = recent;
        noteNewOffer(recent);
        chart.setEntries(recent);
        boolean empty = recent.isEmpty();
        chart.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            countTicket = null;
            ticketOpen = false;
            ticket.setVisibility(View.GONE);
            if (sheet != null) sheet.setVisibility(View.GONE);
            if (minimums != null) minimums.showTicket(null);
        }
        if (!empty) {
            if (countTicket != null) {
                long selectedAt = countTicket.at;
                countTicket = null;
                for (DecisionLog.Entry entry : recent) if (entry.at == selectedAt) countTicket = entry;
            }
            DecisionLog.Entry selected = chart.selectedEntry();
            if (countTicket != null) {
                if (followNewest || selected == null) {
                    // Keep the skyline current without replacing the older count ticket being inspected.
                    DecisionLog.Entry inspected = countTicket;
                    chart.select(Math.min(DecisionChartView.SLOTS, recent.size()) - 1);
                    countTicket = inspected;
                }
                showSelection(countTicket);
            } else if (followNewest || selected == null) {
                chart.select(Math.min(DecisionChartView.SLOTS, recent.size()) - 1);
            } else {
                showSelection(selected);
            }
        }
        updateMeter();
    }

    /** The header counts inspect history; they never change auto-decline. */
    private void openLatestCountedOffer(DecisionLog.Tally tally) {
        refreshHistory();
        List<DecisionLog.Entry> recent = DecisionLog.recent(this, DecisionLog.MAX_ENTRIES);
        for (int i = 0; i < recent.size(); i++) {
            DecisionLog.Entry entry = recent.get(i);
            if (DecisionLog.tally(entry) != tally) continue;
            if (i < DecisionChartView.SLOTS) {
                openOffer(entry);
            } else {
                // Retained history outlives the visible skyline. Its ticket can still explain this count.
                countTicket = entry;
                ticketOpen = true;
                showSelection(entry);
                openSheet(ticket);
            }
            return;
        }
        String kind = tally == DecisionLog.Tally.PASSED ? "passed offers"
                : tally == DecisionLog.Tally.FILTERED ? "filtered offers" : "offers left to review";
        toast("No recent " + kind + " in history.");
    }

    /**
     * A new offer at the top of the history, decided moments ago, is played out by the mascot a little later, once its
     * line has settled (its confirmation tapped, say, or taken over), as what became of it by then. Offers already in
     * the history when the page opened, and older ones, are not.
     */
    private void noteNewOffer(List<DecisionLog.Entry> recent) {
        long newest = recent.isEmpty() ? 0 : recent.get(0).at;
        boolean opening = seenOfferAt < 0;
        if (!opening && newest == seenOfferAt) {
            if (newest == liveAnimationOfferAt && shownOfferOutcome != null
                    && System.currentTimeMillis() - newest <= OFFER_FRESH_MS) {
                DecisionLog.Outcome outcome = DecisionLog.outcome(recent.get(0));
                if (outcome != shownOfferOutcome) {
                    shownOfferOutcome = outcome;
                    hero.showOffer(outcome);
                }
            }
            return;
        }
        if (!opening && newest < seenOfferAt) return;
        seenOfferAt = newest;
        liveAnimationOfferAt = 0;
        shownOfferOutcome = null;
        if (opening || System.currentTimeMillis() - newest > OFFER_FRESH_MS) return;
        liveAnimationOfferAt = newest;
        handler.postDelayed(() -> {
            if (!started || isFinishing() || isDestroyed() || liveAnimationOfferAt != newest
                    || System.currentTimeMillis() - newest > OFFER_FRESH_MS) return;
            List<DecisionLog.Entry> now = DecisionLog.recent(this, 1);
            if (!now.isEmpty() && now.get(0).at == newest) {
                shownOfferOutcome = DecisionLog.outcome(now.get(0));
                hero.showOffer(shownOfferOutcome);
            }
        }, OFFER_SETTLE_MS);
    }

    /** The filter picture with this dash's counts and all-time totals; redrawn only when something it shows changed. */
    private void refreshHero(FilterHeroView.State state) {
        long[] dash = Dashing.lastDash(this);
        int[] counts = new int[DecisionLog.Tally.values().length];
        if (dash != null) {
            for (DecisionLog.Entry entry : recentEntries) {
                if (entry.at >= dash[0] && entry.at <= dash[1]) counts[DecisionLog.tally(entry).ordinal()]++;
            }
        }
        int[] totals = DecisionLog.totals(this);
        String label = dash == null ? "No dash yet" : dash[1] == Long.MAX_VALUE ? "This dash" : "Last dash";
        String shown = state + "/" + java.util.Arrays.toString(counts) + java.util.Arrays.toString(totals) + label;
        if (shown.equals(shownHero)) return;
        shownHero = shown;
        hero.set(state, counts, totals, label);
    }

    /** Redraws current rule references and minimums, with the latest fully read offer as the example. */
    private void updateMeter() {
        if (minimums == null) return;
        FilterSettings rules = FilterStore.load(this);
        if (chart != null) chart.setRules(rules);
        minimums.show(rules, exampleOffer(), recentEntries);
    }

    /**
     * The newest standalone offer whose miles, minutes and stops were all read and none looks misread ("1 stop"),
     * else a typical one.
     */
    private OfferSnapshot exampleOffer() {
        for (DecisionLog.Entry entry : recentEntries) {
            OfferSnapshot facts = entry.facts;
            if (!entry.addOn && facts.miles != null && facts.minutes != null && facts.stops != null
                    && !OfferSanity.looksMisread(facts)) {
                return facts;
            }
        }
        return new OfferSnapshot(null, 5.0, 20, 2);
    }

    private void refreshOfferCaption(DecisionLog.Entry entry) {
        if (entry == null) {
            String empty = readyForOffers ? "Waiting for offers" : "No offers yet.";
            if (!empty.contentEquals(offerCaption.getText())) offerCaption.setText(empty);
            offerCaption.setContentDescription(null);
            offerCaption.setClickable(false);
            return;
        }
        boolean latest = !recentEntries.isEmpty() && recentEntries.get(0).at == entry.at;
        String label = (latest ? "Latest" : "Selected") + " · "
                + (entry.facts.payCents == null ? "Pay unread" : DecisionLog.money(entry.facts.payCents))
                + " · " + captionOutcome(entry);
        if (!label.contentEquals(offerCaption.getText())) offerCaption.setText(label);
        String description = label + ". " + when(entry.at) + ". Open offer details";
        if (!description.contentEquals(String.valueOf(offerCaption.getContentDescription()))) {
            offerCaption.setContentDescription(description);
        }
        offerCaption.setClickable(true);
    }

    /**
     * Qualify acceptance only when the stored observation distinguishes its source: "Automatically accepted" after the
     * app's own Accept request, "Accepted by you" after the user's; older lines by their old steps.
     */
    private static String captionOutcome(DecisionLog.Entry entry) {
        if (DecisionLog.outcome(entry) != DecisionLog.Outcome.ACCEPTED) return DecisionLog.outcome(entry).said;
        for (int i = entry.steps.size() - 1; i >= 0; i--) {
            DecisionLog.Step step = entry.steps.get(i);
            if (step.kind == DecisionLog.StepKind.ACCEPTED_AUTOMATIC) return "Automatically accepted";
            if (step.kind == DecisionLog.StepKind.ACCEPTED || step.kind == DecisionLog.StepKind.ACCEPTED_ADD_ON) {
                return "Accepted by you";
            }
            // Written before 0.5.0.
            if (step.kind == DecisionLog.StepKind.ACCEPTED_NOT_LEARNED) {
                return step.detail.startsWith("automatic Accept was requested, and Dasher showed a delivery screen")
                        ? "Automatically accepted" : DecisionLog.Outcome.ACCEPTED.said;
            }
            if (step.kind == DecisionLog.StepKind.ACCEPTED_OBSERVED) return DecisionLog.Outcome.ACCEPTED.said;
            if (step.kind == DecisionLog.StepKind.ACCEPTED_LEARNED
                    || step.kind == DecisionLog.StepKind.ACCEPTED_BEST_SAVED
                    || step.kind == DecisionLog.StepKind.ACCEPTED_MINIMUMS_UNCHANGED) return "Accepted by you";
        }
        return DecisionLog.Outcome.ACCEPTED.said;
    }

    /**
     * The chosen offer (a tapped building in the skyline), unfolded as a ticket: its outcome stamped on the stub with
     * the time ({@link DecisionLog#outcome}: what became of it, not only what the rules said), then the drawn offer (pay
     * against needed, the route), the rules' reason (with their verdict where the stamp says otherwise), what the app
     * did, and the exact lines read.
     */
    private void showSelection(DecisionLog.Entry entry) {
        refreshOfferCaption(countTicket == null ? entry : chart.selectedEntry());
        // The constellation picks out the offer whose ticket is open.
        if (minimums != null) minimums.showTicket(ticketOpen && countTicket == null ? entry : null);
        ticket.removeAllViews();
        ticket.setVisibility(ticketOpen ? View.VISIBLE : View.GONE);
        if (!ticketOpen) return;
        LinearLayout stub = ui.row();
        stub.addView(new Decor.Stamp(this, ui, DecisionLog.outcome(entry)));
        TextView time = ui.text(when(entry.at) + (entry.addOn ? " · add-on" : ""), 13, ui.inkSecondary, false);
        time.setGravity(Gravity.END);
        stub.addView(time, Ui.weighted());
        // The tear line runs under the stub however tall a large font makes it.
        stub.setMinimumHeight(ui.dp(Decor.Ticket.STUB_DP));
        stub.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                ticketShape.setStub(bottom));
        ticket.addView(stub, Ui.matchWidth());

        DecisionLog.Step outcomeStep = latestOutcomeStep(entry);
        if (outcomeStep != null) {
            TextView outcome = ui.text(outcomeStep.text(), 14, ui.ink, true);
            outcome.setPadding(0, ui.dp(12), 0, 0);
            ticket.addView(outcome);
        }
        OfferCardView card = new OfferCardView(this, ui);
        card.show(entry);
        LinearLayout.LayoutParams cardParams = Ui.matchWidth();
        cardParams.topMargin = ui.dp(12);
        ticket.addView(card, cardParams);
        // The score as decided (a line decided under the retired rules says so); a reason by area score already says
        // it.
        String scoreLine = entry.model < DecisionLog.MODEL && entry.reason.startsWith("score ") ? null
                : AutopilotText.ticketScoreLine(entry.scorePercent, entry.barPercent, entry.model);
        if (scoreLine != null) {
            TextView score = ui.text(scoreLine, 14, ui.inkSecondary, true);
            score.setPadding(0, ui.dp(8), 0, 0);
            ticket.addView(score);
        }
        // Passed only because Autopilot lowered the bar: left to the user, never auto-accepted.
        if (passedBelowMinimums(entry)) {
            TextView below = ui.text(AutopilotText.ticketBelowMinimums(entry.barPercent), 14, ui.ink, true);
            below.setPadding(0, ui.dp(6), 0, 0);
            ticket.addView(below);
        }
        boolean exempt = DecisionLog.hasStep(entry, DecisionLog.StepKind.AR_EXEMPT);
        if (exempt) {
            TextView free = ui.text(AutopilotText.TICKET_EXEMPT, 13, ui.inkSecondary, false);
            free.setPadding(0, ui.dp(6), 0, 0);
            ticket.addView(free);
        }
        TextView reason = ui.text(reasonLine(entry), 16, ui.ink, true);
        reason.setPadding(0, ui.dp(10), 0, 0);
        ticket.addView(reason);
        TextView action = ui.text(entry.action.label
                + (entry.source == DecisionLog.Source.SCREEN
                        ? " · on screen" + (entry.peeked ? " (peeked)" : "") : " · from notification")
                + (entry.autoDecline ? "" : " · while paused"), 13, ui.inkSecondary, false);
        action.setPadding(0, ui.dp(4), 0, 0);
        ticket.addView(action);
        if (!entry.steps.isEmpty()) {
            List<String> steps = new java.util.ArrayList<>();
            for (DecisionLog.Step step : entry.steps) {
                if (step != outcomeStep && step.kind != DecisionLog.StepKind.AR_EXEMPT) steps.add(step.text());
            }
            if (!steps.isEmpty()) {
                TextView followed = ui.text(String.join("\n", steps), 13, ui.inkSecondary, false);
                followed.setPadding(0, ui.dp(6), 0, 0);
                ticket.addView(followed);
            }
        }
        TextView minimumDetails = ui.text(MinimumsDetails.describe(FilterStore.load(this), entry), 13,
                ui.inkSecondary, false);
        minimumDetails.setPadding(0, ui.dp(6), 0, 0);
        minimumDetails.setVisibility(View.GONE);
        Button minimumKey = ui.addButton(ticket, AutopilotText.TICKET_KEY, false, () -> {});
        minimumKey.setContentDescription("Show how this offer was judged against your minimums");
        minimumKey.setOnClickListener(clicked -> {
            boolean expanded = minimumDetails.getVisibility() != View.VISIBLE;
            minimumDetails.setVisibility(expanded ? View.VISIBLE : View.GONE);
            minimumKey.setContentDescription((expanded ? "Hide" : "Show")
                    + " how this offer was judged against your minimums");
        });
        ticket.addView(minimumDetails);
        if (entry.notification != null) {
            // Dasher's notification of this same offer, folded into its line.
            TextView notice = ui.text("Dasher's notification " + DecisionLog.noticeWhen(entry) + ": "
                    + plainReason(entry.notification) + " · " + entry.notification.action.label, 13,
                    ui.inkSecondary, false);
            notice.setPadding(0, ui.dp(4), 0, 0);
            ticket.addView(notice);
        }
        if (!entry.evidence.isEmpty()) {
            TextView read = ui.text("Read: " + String.join("  ·  ", entry.evidence), 12, ui.inkMuted, false);
            read.setPadding(0, ui.dp(4), 0, 0);
            read.setTextIsSelectable(true);
            ticket.addView(read);
        }
        ui.addButton(ticket, "Report this offer", false, () -> reportOffer(entry));
    }

    /**
     * A standalone offer that passed only because Autopilot's bar was below 100% (its score under 100, or its reason
     * saying so): left to the user, never auto-accepted. Only a line this version decided.
     */
    static boolean passedBelowMinimums(DecisionLog.Entry entry) {
        if (entry.result != OfferRule.Result.KEEP || entry.model < DecisionLog.MODEL
                || entry.barPercent >= FilterSettings.BAR_AT_MINIMUMS) return false;
        return (entry.scorePercent >= 0 && entry.scorePercent < FilterSettings.BAR_AT_MINIMUMS)
                || entry.reason.contains("below your minimums");
    }

    /** Keep the most recent observed outcome prominent, without inventing one from a passed rule. */
    private static DecisionLog.Step latestOutcomeStep(DecisionLog.Entry entry) {
        for (int i = entry.steps.size() - 1; i >= 0; i--) {
            DecisionLog.Step step = entry.steps.get(i);
            switch (step.kind) {
                case ACCEPTED:
                case ACCEPTED_AUTOMATIC:
                case ACCEPTED_OBSERVED:
                case ACCEPTED_LEARNED:
                case ACCEPTED_NOT_LEARNED:
                case ACCEPTED_BEST_SAVED:
                case ACCEPTED_MINIMUMS_UNCHANGED:
                case ACCEPTED_ADD_ON:
                case NOT_LEARNED:
                case DECLINE_TAUGHT:
                case DECLINE_NOT_TAUGHT:
                    return step;
                default: break;
            }
        }
        // A canceled or still-unconfirmed automatic request must be easy to find too.
        for (int i = entry.steps.size() - 1; i >= 0; i--) {
            DecisionLog.Step step = entry.steps.get(i);
            if (step.kind == DecisionLog.StepKind.AUTO_ACCEPT_NOT_SENT
                    || step.kind == DecisionLog.StepKind.AUTO_ACCEPT_UNCONFIRMED
                    || step.kind == DecisionLog.StepKind.AUTO_ACCEPT_REQUESTED
                    || step.kind == DecisionLog.StepKind.ACCEPT_UNCONFIRMED) return step;
        }
        return null;
    }

    /**
     * The ticket's reason line: the rules' reason in everyday words; where the stamp says something other than the
     * rules did (an offer left to you, or accepted), their verdict first, so it stays in view: "Rules: decline — too
     * many stops (4, max 3)".
     */
    static String reasonLine(DecisionLog.Entry entry) {
        String plain = plainReason(entry);
        if (DecisionLog.outcome(entry).isVerdict(entry.result)) return plain;
        String verdict = entry.result == OfferRule.Result.KEEP ? "pass"
                : entry.result == OfferRule.Result.DECLINE ? "decline" : "review";
        return "Rules: " + verdict + (plain.isEmpty() ? "" : " — " + lowerFirst(plain));
    }

    /** "Too many stops" as "too many stops"; a word in capitals ("ETA") stays as it is. */
    private static String lowerFirst(String text) {
        if (text.length() > 1 && Character.isUpperCase(text.charAt(1))) return text;
        return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    static String plainReason(DecisionLog.Entry entry) {
        if (entry.reason.equals("pay not found")) {
            return entry.source == DecisionLog.Source.NOTIFICATION ? "Notification showed no pay" : "Pay not readable";
        }
        return plainReason(entry.reason);
    }

    /** "82% bar: dollars per mile": what Autopilot's bar asked (0.5.0). */
    private static final Pattern BAR_REASON = Pattern.compile("(\\d+)% bar: (.+)");
    /** "97% of baseline: dollars per mile": the retired minimums scale. */
    private static final Pattern BASELINE_REASON = Pattern.compile("(\\d+)% of baseline: (.+)");
    private static final Pattern MEETS_BAR = Pattern.compile("meets the (\\d+)% bar");
    private static final Pattern BELOW_PASSES = Pattern.compile("below your minimums; passes the (\\d+)% bar");
    private static final Pattern ADD_ON_MEETS_BAR = Pattern.compile("combined route and add-on meet the (\\d+)% bar");
    private static final Pattern ADD_ON_BELOW_PASSES =
            Pattern.compile("combined route and add-on below your minimums; pass the (\\d+)% bar");
    /** The retired minimums scale's pass and the retired area score, on older lines. */
    private static final Pattern MEETS_SCALE = Pattern.compile("meets enabled rules at (\\d+)% minimum scale");
    private static final Pattern AREA_SCORE = Pattern.compile("score (\\d+)% \\(needs (\\d+)%\\)");

    /** Rule reasons in everyday words; the report keeps the exact wording. Older lines keep their own words. */
    static String plainReason(String reason) {
        if (reason == null || reason.isEmpty()) return "";
        if (reason.startsWith("combined route fails: ")) {
            return "Whole route: " + plainReason(reason.substring("combined route fails: ".length()));
        }
        // "pay at most $8.35 with its +$ amount; flat minimum": even the most it can pay fails that rule.
        int bound = reason.indexOf(" with its +$ amount; ");
        if (reason.startsWith("pay at most ") && bound > 0) {
            return "Even at " + reason.substring("pay at most ".length(), bound) + " with its +$: "
                    + lowerFirst(plainReason(reason.substring(bound + " with its +$ amount; ".length())));
        }
        Matcher bar = BAR_REASON.matcher(reason);
        if (bar.matches()) return plainReason(bar.group(2)) + " (Autopilot " + bar.group(1) + "%)";
        Matcher baseline = BASELINE_REASON.matcher(reason);
        if (baseline.matches()) return plainReason(baseline.group(2)) + " (buffer " + baseline.group(1) + "%)";
        Matcher meets = MEETS_BAR.matcher(reason);
        if (meets.matches()) return "Meets Autopilot's " + meets.group(1) + "% bar";
        Matcher below = BELOW_PASSES.matcher(reason);
        if (below.matches()) return "Below your minimums · passed by Autopilot's " + below.group(1) + "% bar";
        Matcher addOnMeets = ADD_ON_MEETS_BAR.matcher(reason);
        if (addOnMeets.matches()) return "Add-on meets Autopilot's " + addOnMeets.group(1) + "% bar";
        Matcher addOnBelow = ADD_ON_BELOW_PASSES.matcher(reason);
        if (addOnBelow.matches()) {
            return "Add-on below your minimums · passed by Autopilot's " + addOnBelow.group(1) + "% bar";
        }
        Matcher scale = MEETS_SCALE.matcher(reason);
        if (scale.matches()) return "Meets your rules (buffer " + scale.group(1) + "%)";
        Matcher area = AREA_SCORE.matcher(reason);
        if (area.matches()) return "Area score " + area.group(1) + "% (needed " + area.group(2) + "%)";
        if (reason.startsWith("must beat highest accepted payout ")) {
            return "Not above your highest accepted " + reason.substring("must beat highest accepted payout ".length());
        }
        // Recorded before 0.4.22, when the pay minimum followed the last accepted offer.
        if (reason.startsWith("must beat last accepted payout ")) {
            return "Not above last accepted " + reason.substring("must beat last accepted payout ".length());
        }
        if (reason.startsWith("must match best accepted ")) {
            return "Below your best accepted " + reason.substring("must match best accepted ".length());
        }
        if (reason.startsWith("must beat declined payout ")) {
            return "Not above an offer you declined, " + reason.substring("must beat declined payout ".length());
        }
        if (reason.startsWith("must beat declined ")) {
            return "Not above an offer you declined, " + reason.substring("must beat declined ".length());
        }
        Matcher stops = TOO_MANY_STOPS.matcher(reason);
        if (stops.matches()) return "Too many stops (" + stops.group(1) + ", max " + stops.group(2) + ")";
        // Recorded while per stop was a fee added on top of the other minimums, before it became a minimum itself.
        boolean stopFees = reason.endsWith(" and extra stops");
        String base = stopFees ? reason.substring(0, reason.length() - " and extra stops".length()) : reason;
        String plain;
        switch (base) {
            case "flat minimum": plain = "Below your minimum pay"; break;
            case "dollars per mile": plain = "Below your per-mile minimum"; break;
            case "dollars per hour": plain = "Below your hourly minimum"; break;
            case "meets your minimums": plain = "Meets your minimums"; break;
            case "combined route and add-on meet your minimums": plain = "Add-on meets your minimums"; break;
            case "pay not found": plain = "Pay not readable"; break;
            case "an enabled value was not found": plain = "Miles, time or stops not readable"; break;
            case "add-on marginal economics": plain = "Add-on pays too little for what it adds"; break;
            case "add-on has missing or ambiguous incremental/route evidence": plain = "Add-on details unclear"; break;
            case "auto-decline is off; inspect this offer manually": plain = "Auto-decline paused"; break;
            // Recorded before 0.5.0, under the retired rules.
            case "dollars per minute": plain = "Below your per-minute rate"; break;
            case "dollars per stop": plain = "Below your per-stop rate"; break;
            case "dollars per item": plain = "Below your per-item rate"; break;
            case "meets enabled rules": plain = "Meets your rules"; break;
            case "combined route and add-on meet enabled rules": plain = "Add-on meets your rules"; break;
            default: plain = Character.toUpperCase(base.charAt(0)) + base.substring(1);
        }
        return stopFees ? plain + " (with stop fees)" : plain;
    }

    /** "9:41 PM" today, otherwise "Sep 28, 9:41 PM". */
    private String when(long at) {
        if (DateUtils.isToday(at)) return DateFormat.getTimeFormat(this).format(new Date(at));
        return DateUtils.formatDateTime(this, at, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME
                | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_NO_YEAR);
    }

    // ---- Offer areas ----

    /** Approximate location first; "all the time" only once offers have come in without a location. */
    private void askForLocation() {
        if (!AreaMap.hasPermission(this)) {
            requestPermissions(new String[] {Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
        } else if (!AreaMap.hasBackgroundPermission(this)) {
            // One line of why first; Android's request only after Continue (App info once Android won't ask).
            LocationRationale.ask(this, () -> requestPermissions(
                    new String[] {Manifest.permission.ACCESS_BACKGROUND_LOCATION}, BACKGROUND_LOCATION_REQUEST),
                    this::open);
        }
    }

    /**
     * Settings' offer map switch and its fixes (location, or "all the time" once offers came in without one, since
     * Android may share it only while the app is open), then the treasure map when anything it shows changed.
     */
    private void refreshAreas() {
        boolean on = AreaMap.enabled(this);
        if (areasToggle.isChecked() != on) areasToggle.setChecked(on);
        List<AreaMap.Cell> cells = AreaMap.cells(this);
        int unlocated = AreaMap.unlocated(this);
        boolean permitted = locationAllowed.get();
        boolean needsAllTheTime = permitted && unlocated > 0 && !locationAlways.get();
        location.update(!on || permitted);
        locationAllTheTime.update(!on || !needsAllTheTime);
        if (!on) {
            areaMap.setEmptyMessage("Tap to map where offers pay best");
        } else if (!permitted) {
            areaMap.setEmptyMessage("Tap to allow location");
        } else {
            areaMap.setEmptyMessage("Offers will pin here");
        }
        List<AreaMap.Cell> shownCells = on ? cells : java.util.Collections.<AreaMap.Cell>emptyList();
        // With the map hidden (beside Dasher, say) only the signpost's place name uses where the phone is.
        double[] here = on && permitted ? this.here.get(noMap ? ASK_EVERY_MS : HERE_EVERY_MS) : null;
        String shown = on + "/" + AreaMap.version() + "/" + Places.version() + "/" + (here == null ? "-"
                : Math.round(here[0] * 2000) + "," + Math.round(here[1] * 2000));
        if (shown.equals(shownAreas)) return;
        shownAreas = shown;
        areaHere = here;
        areaMap.show(shownCells, here);
        List<AreaMap.Cell> ranked = AreaMap.ranked(shownCells);
        // Place names from the phone's lookup: where you are, and near the best three.
        java.util.Map<String, String> names = new java.util.HashMap<>();
        for (int i = 0; i < Math.min(3, ranked.size()); i++) {
            String name = Places.name(this, ranked.get(i).latitude(), ranked.get(i).longitude());
            if (name != null) names.put(AreaMapView.key(ranked.get(i)), name);
        }
        String hereName = here == null ? null : Places.name(this, here[0], here[1]);
        areaMap.setNames(names, hereName);
        // The signpost stands on the hills beside the skyline.
        scene.setPlace(hereName);
        // Until a square is picked, the best one is shown, following it as the ranking changes.
        if (!pickedArea && !ranked.isEmpty()) areaMap.select(ranked.get(0));
        if (areaMap.selected() != null) {
            showArea(areaMap.selected());
        } else {
            shownArea = null;
            areaLine.setVisibility(View.GONE);
        }
    }

    /** The chosen square in one line under the map: its rank and place, its pay per mile, and a way to see it. */
    private void showArea(AreaMap.Cell cell) {
        shownArea = cell;
        int rank = areaMap.rank(cell);
        String where = AreaMap.from(areaHere, cell);
        String name = Places.name(this, cell.latitude(), cell.longitude());
        String samples = cell.mileOffers + (cell.mileOffers == 1 ? " offer" : " offers");
        String rate = cell.ranked() ? cell.perMile() + " · " + samples : "Unranked · " + samples + " with miles";
        // Its rank is on its coin; "of you" and Maps go without saying on the page, not to screen readers.
        List<String> parts = new java.util.ArrayList<>();
        if (name != null) parts.add(name);
        else if (!where.isEmpty()) parts.add(where.replace(" of you", ""));
        parts.add(rate);
        areaLine.setText(String.join(" · ", parts) + "  ›");
        String spoken = (rank > 0 ? "#" + rank : "Not ranked yet") + (name == null ? "" : " · " + name)
                + (where.isEmpty() ? "" : " · " + where) + " · " + rate;
        areaLine.setContentDescription(spoken + ". Average " + DecisionLog.money(cell.averagePayCents()) + ", last "
                + when(cell.lastAt) + ". Opens it in Maps.");
        areaLine.setVisibility(noMap ? View.GONE : View.VISIBLE);
    }

    private void openArea() {
        AreaMap.Cell cell = shownArea;
        if (cell == null) return;
        open(new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US,
                "geo:%.4f,%.4f?q=%.4f,%.4f(Offer area)", cell.latitude(), cell.longitude(), cell.latitude(),
                cell.longitude()))));
    }

    /** Always available in the header, including when the Atlas is hidden beside Dasher. */
    private void chooseNavigation() {
        boolean areasOn = AreaMap.enabled(this);
        AreaMap.Cell best = areasOn ? NavigationShortcuts.best(AreaMap.cells(this)) : null;
        String bestDetail = !areasOn ? "Offer map is off" : best == null
                ? "Needs 3 offers with readable miles in an area"
                : best.perMile() + " · " + best.mileOffers
                        + " offers\nApproximate historical area, not a live hotspot";
        String[] choices = {"Best offer area\n" + bestDetail,
                "Nearby gas\nChoose a station in your map app",
                "Compare gas prices\nChoose from prices listed in your map app"};
        ArrayAdapter<String> rows = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, choices) {
            @Override public boolean areAllItemsEnabled() { return best != null; }
            @Override public boolean isEnabled(int position) { return position != 0 || best != null; }
            @Override public View getView(int position, View reused, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, reused, parent);
                row.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                row.setMinHeight(ui.dp(48));
                row.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(10));
                row.setTextSize(16);
                row.setTextColor(isEnabled(position) ? ui.ink : ui.inkMuted);
                android.text.SpannableString words = new android.text.SpannableString(choices[position]);
                words.setSpan(new android.text.style.RelativeSizeSpan(13f / 16f),
                        choices[position].indexOf('\n') + 1, words.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                row.setText(words);
                return row;
            }
        };
        OwnWindowTouches.show(new AlertDialog.Builder(this)
                .setTitle("Navigate")
                .setAdapter(rows, (dialog, which) -> {
                    if (which == 0 && best == null) return;
                    NavigationShortcuts.Destination destination = which == 0
                            ? NavigationShortcuts.area(best) : NavigationShortcuts.gas(which == 2);
                    if (!NavigationShortcuts.open(this, destination)) {
                        toast("No map app or browser could open this search.");
                    }
                })
                .setNegativeButton("Cancel", null));
    }

    // ---- Actions ----

    /** Pause, Resume, or with no saved rule, the starter (typical minimums, or a pointer to the knobs). */
    private void toggleAutoDecline() {
        FilterSettings saved = FilterStore.load(this);
        if (saved.enabled) pause();
        else if (saved.hasAnyRule()) resume();
        else showStarter();
    }

    /**
     * "Set my own": the constellation's hollow knobs beckon (in a short window it first leaves the header for the
     * sky, where they are), and screen readers hear how to begin.
     */
    private void beckonKnobs() {
        if (compact && !besideDasher && !skyChosen) chooseSky(true);
        else minimums.beckon();
        hero.announceForAccessibility(KNOBS_HINT);
    }

    /** Persists auto-decline off immediately, keeping every saved rule. */
    private void pause() {
        FilterStore.save(this, FilterStore.load(this).withEnabled(false));
        rulesChanged();
        toast("Paused. Nothing will be declined.");
    }

    /**
     * Turns auto-decline on with the saved rules. The first time it does with a money minimum, and Autopilot's goal
     * was never asked, the goal chooser opens once.
     */
    private void resume() {
        FilterSettings saved = FilterStore.load(this);
        if (!saved.hasAnyRule()) {
            showStarter();
            return;
        }
        FilterStore.save(this, saved.withEnabled(true));
        rulesChanged();
        toast("Auto-decline is on.");
        if (saved.hasMonetaryRule() && !saved.autopilot && !FilterStore.goalAsked(this)) chooseAutopilotGoal();
    }

    private void rulesChanged() {
        OfferNotificationService.rulesChanged();
        OfferFilterService.requestCheckFromNotification();
        refresh();
    }

    /** Accountless report of one selected offer (OfferReport: masked twice, no learning steps). */
    private void reportOffer(DecisionLog.Entry entry) {
        feedbackDialogs.reportOffer(entry);
    }

    /**
     * One confirm for decisions, waiting estimates, captured text, offer areas, cached place names, unsent automatic
     * diagnostics and Autopilot's acceptance-rate reading. Rules and Autopilot's settings stay.
     */
    private void confirmClearHistory() {
        OwnWindowTouches.show(new AlertDialog.Builder(this)
                .setTitle("Clear history?")
                .setMessage(CLEAR_HISTORY)
                .setPositiveButton("Clear", (dialog, which) -> {
                    cancelReportShare();
                    DecisionLog.clear(this);
                    QualifyingWaitStore.clear(this);
                    // After the history and the watched waiting it plans from: its reading and change note go too.
                    AutopilotRuntime.cleared(this);
                    DiagnosticLog.clear(this);
                    AreaMap.forget(this);
                    Places.forget(this);
                    RestartSuppression.clear(this);
                    AutoAcceptMemory.clear(this);
                    ScannerFailure.clear(this);
                    // Automatic diagnostics not yet sent, what this dash counted for them, and stop summaries (their
                    // files go off this thread; a summary being built is never queued).
                    Feedback.clearAutomatic(this);
                    StopReports.clearSoon(this);
                    pickedArea = false;
                    areaMap.select(null);
                    followNewest = true;
                    ticketOpen = false;
                    refresh();
                })
                .setNegativeButton("Cancel", null));
    }

    /** Offers any app for the diagnostic report; mail keeps its "Offer Filter diagnostics" subject. */
    private void shareReport() {
        if (!resumed || noticeShown() || isFinishing() || isDestroyed()) return;
        if (reportShare.start(this, report -> {
            shareReportRow.setEnabled(true);
            if (!resumed || noticeShown() || isFinishing() || isDestroyed()) return;
            if (report == null) {
                toast("Could not prepare the report. Try again.");
                return;
            }
            open(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, report.subject)
                    .putExtra(Intent.EXTRA_TEXT, report.body), "Share " + AppName.NAME + " report"));
        })) shareReportRow.setEnabled(false);
    }

    private void cancelReportShare() {
        reportShare.cancel();
        if (shareReportRow != null) shareReportRow.setEnabled(true);
    }

    private void openDoorDashChannel() {
        String channel = FilterStore.doorDashOfferChannel(this);
        Intent intent = new Intent(channel.isEmpty()
                ? Settings.ACTION_APP_NOTIFICATION_SETTINGS : Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, DASHER_PACKAGE);
        if (!channel.isEmpty()) intent.putExtra(Settings.EXTRA_CHANNEL_ID, channel);
        open(intent);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (RuntimeException error) {
            // Plain words for the user; the detail goes to the log (BETA-20).
            DiagnosticLog.log(this, "ui", "Android could not open " + intent.getAction() + ": "
                    + error.getClass().getSimpleName());
            toast("Android couldn't open that screen.");
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // ---- Window ----

    /** Page-colored system bars with icons that contrast with the page in light and dark themes. */
    @SuppressWarnings("deprecation")
    static void styleSystemBars(Activity activity, Ui ui) {
        Window window = activity.getWindow();
        if (Build.VERSION.SDK_INT < 35) {
            window.setStatusBarColor(ui.page);
            window.setNavigationBarColor(ui.page);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(ui.dark ? 0 : light, light);
            }
        } else if (!ui.dark) {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    /**
     * Android 15 draws apps targeting API 35 edge to edge, so the pages must keep themselves clear of the status bar,
     * navigation bar, cutouts, and keyboard. Earlier versions already lay the window out inside the system bars.
     */
    static void fitToSystemBars(View content) {
        if (Build.VERSION.SDK_INT < 35) return;
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    /**
     * One setup problem as a quiet row, a small warning sign, what is off, and "Fix", that is itself the button;
     * the row is hidden while that part works.
     */
    private final class Readiness {
        private final LinearLayout row;
        private final TextView text;
        private final String action;

        Readiness(LinearLayout parent, String problem, Runnable onFix) {
            this(parent, problem, "Fix", onFix);
        }

        /** @param action the word at the row's end, that a tap does ("Fix", "Send report") */
        Readiness(LinearLayout parent, String problem, String action, Runnable onFix) {
            this.action = action;
            row = ui.row();
            row.setBackground(ui.pressable(16));
            row.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6));
            row.setMinimumHeight(ui.dp(48));
            row.setClickable(true);
            row.setFocusable(true);
            row.setContentDescription(problem + ". " + action + ".");
            row.setOnClickListener(tapped -> onFix.run());
            View sign = new View(MainActivity.this);
            sign.setBackground(new Glyph(Glyph.Shape.SIGN, Ui.CRITICAL, ui.dp(20)));
            sign.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(sign, new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
            text = ui.text(problem, 15, ui.ink, false);
            text.setPadding(ui.dp(10), 0, ui.dp(8), 0);
            row.addView(text, Ui.weighted());
            // Link ink: the accent itself read at 3.6:1 on the night sky (BETA-21).
            row.addView(ui.text(action, 15, ui.link, true));
            parent.addView(row, Ui.matchWidth());
        }

        void update(boolean ready) {
            row.setVisibility(ready ? View.GONE : View.VISIBLE);
        }

        void problem(String problem) {
            if (problem.contentEquals(text.getText())) return;
            text.setText(problem);
            row.setContentDescription(problem + ". " + action + ".");
        }
    }
}
