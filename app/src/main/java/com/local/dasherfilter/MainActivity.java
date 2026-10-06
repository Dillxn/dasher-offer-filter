package com.local.dasherfilter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
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
 * screen, a map of where offers pay best. Every rule lives on the constellation and saves at once: the knobs (the four
 * minimums, hollow until set), the max stops badge by the per-stop spoke, and the round buttons (score by area, the
 * adaptive minimum on or off with Reset on a long press, and adopting what it learned). Settings holds only what exists
 * nowhere else: setup still needing a fix, two switches, updates, anonymous feedback, reports and a tip, each one row. Pause and
 * Resume take effect at once. The drawings move gently and shift with the phone's tilt while the app fills the screen,
 * unless Android's animations are off; in split screen they move calmly and the tilt sensor rests.
 */
public final class MainActivity extends Activity implements Updater.Busy {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 13;
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
    private static final String SETUP_PREFS = "setup_steps";
    private static final String NOTIFICATIONS_ASKED = "notifications_asked";
    private static final String ACCESSIBILITY_OPENED = "accessibility_opened";
    /** With no rule saved, the line under the mascot: the fewest words that say how to begin. */
    static final String START_HINT = "Drag a knob to start";

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
    private final Asked<Boolean> installsAllowed = new Asked<>(() -> getPackageManager().canRequestPackageInstalls());
    private final Asked<Boolean> locationAllowed = new Asked<>(() -> AreaMap.hasPermission(this));
    private final Asked<Boolean> locationAlways = new Asked<>(() -> AreaMap.hasBackgroundPermission(this));
    private final Asked<Boolean> screenReadingEnabled = new Asked<>(this::screenReadingEnabled);
    private final Asked<double[]> here = new Asked<>(() -> AreaMap.here(this));
    /** Whether the page is resumed, so leaving split screen knows whether to start the tilt again. */
    private boolean resumed;
    private boolean started;
    private boolean restrictedSettingsHint;
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
    /** Over everything while an update installs: it says so and takes no input until the new version opens. */
    private LinearLayout updatingCover;
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
    private TextView waitEstimateLine;
    private boolean readyForOffers;
    private FilterHeroView hero;
    private int shownState;
    private String shownHero = "";
    private Readiness screenReading;
    private Readiness backgroundOffers;
    private Readiness offerAlerts;
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
    /** What the adaptive minimums had learned when the star was last drawn. */
    private String shownLearned = "";
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
    private Readiness installs;
    private Readiness location;
    private Readiness locationAllTheTime;
    private Switch areasToggle;
    private Button updatesRow;
    private Button shareReportRow;
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
        buildUpdatingCover(root);
        notice = NoticePage.build(this, ui, this::acceptNotice, this::finish, this::read);
        root.addView(notice, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        Updater.relaunched(this);

        setContentView(root);
        styleSystemBars(this, ui);
        fitToSystemBars(root);
        showSettings(state != null && state.getBoolean(SHOWING_SETTINGS, false));
        showNotice(!Consent.accepted(this));
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
    }

    /** Back from Settings returns to the main page; back from the main page leaves. */
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        // While updating, nothing is to be interrupted; the new version opens by itself.
        if (updatingCover.getVisibility() == View.VISIBLE) return;
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
        restrictedSettingsHint = Build.VERSION.SDK_INT >= 33
                && getSharedPreferences(SETUP_PREFS, MODE_PRIVATE).getBoolean(ACCESSIBILITY_OPENED, false)
                && !screenReadingEnabled.get();
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
        installsAllowed.forget();
        locationAllowed.forget();
        locationAlways.forget();
        screenReadingEnabled.forget();
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
        super.onStop();
    }

    @Override protected void onDestroy() {
        cancelReportShare();
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

    private void buildUpdatingCover(FrameLayout root) {
        updatingCover = ui.column();
        updatingCover.setGravity(Gravity.CENTER);
        updatingCover.setBackgroundColor(ui.page);
        updatingCover.setVisibility(View.GONE);
        // Clickable: every touch lands here and goes no further.
        updatingCover.setClickable(true);
        updatingCover.setFocusable(true);
        updatingCover.addView(new UpdatingView(this, ui), Ui.matchWidth());
        TextView title = ui.text("Updating " + AppName.NAME + "…", 20, ui.ink, true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, ui.dp(12), 0, 0);
        updatingCover.addView(title, Ui.matchWidth());
        TextView note = ui.text("It opens again by itself in a moment.", 15, ui.inkSecondary, false);
        note.setGravity(Gravity.CENTER_HORIZONTAL);
        note.setPadding(ui.dp(24), ui.dp(6), ui.dp(24), 0);
        updatingCover.addView(note, Ui.matchWidth());
        updatingCover.setContentDescription("Updating " + AppName.NAME + ". It opens again by itself in a moment.");
        root.addView(updatingCover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
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

        // The mascot is the button: a tap pauses, resumes, or with no rule yet points to the knobs.
        hero = new FilterHeroView(this, ui);
        hero.setOnMascotClickListener(tapped -> toggleAutoDecline());
        hero.setOnCountClickListener(this::openLatestCountedOffer);
        // Words only when something needs the user: paused, or no rules yet. On, the picture says it all.
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
        waitEstimateLine = ui.text("", 13, ui.inkSecondary, false);
        waitEstimateLine.setMinHeight(ui.dp(48));
        waitEstimateLine.setGravity(Gravity.CENTER_HORIZONTAL);
        waitEstimateLine.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        waitEstimateLine.setOnClickListener(tapped -> OwnWindowTouches.show(new AlertDialog.Builder(this)
                .setTitle("Time until a matching offer")
                .setMessage(QualifyingWaitStore.estimate(this, FilterStore.load(this)).detail())
                .setPositiveButton("OK", null)));
        LinearLayout.LayoutParams waitParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        waitParams.gravity = Gravity.CENTER_HORIZONTAL;
        LinearLayout problems = ui.column();
        lines.addView(problems, Ui.matchWidth());
        screenReading = new Readiness(problems, "Screen reading is off", this::fixScreenReading);
        backgroundOffers = new Readiness(problems, "Background offers are off", this::openNotificationAccess);
        offerAlerts = new Readiness(problems, "Alerts are blocked", this::configureOfferAlerts);
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
        // A stable target identifies the displayed shape and opens its ticket, clear of the minimum knobs.
        body.addView(offerCaption, Ui.matchWidth());
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
        if (on) minimums.beckon();
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
     * the sky its knobs set the minimums, its badge the max stops, and its round buttons score by area, the adaptive
     * minimum (Reset on a long press) and adopting what that learned (or undoing it), each saved at once through
     * {@link FilterStore}; a tap on a marked offer opens its ticket, as its building in the skyline does, and a tap
     * off every offer while an older one is chosen chooses the newest again.
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
            @Override public void explainHotspotUnavailable() {
                explainHotspotRule();
            }

            @Override public void setMinimum(int axis, int cents) {
                int[] one = new int[AreaScore.AXES];
                java.util.Arrays.fill(one, -1);
                one[axis] = cents;
                setMinimums(one);
            }

            @Override public int[] adoptLearned() {
                // Only the minimums the adoption raises are saved, and Undo puts back exactly the ones it replaced.
                FilterSettings saved = FilterStore.load(MainActivity.this);
                int[] before = saved.minimums();
                int[] raised = saved.adoptAdaptive().minimums();
                int[] only = new int[AreaScore.AXES];
                int[] undo = new int[AreaScore.AXES];
                java.util.Arrays.fill(only, -1);
                java.util.Arrays.fill(undo, -1);
                boolean any = false;
                for (int i = 0; i < before.length; i++) {
                    if (raised[i] == before[i]) continue;
                    only[i] = raised[i];
                    undo[i] = before[i];
                    any = true;
                }
                if (!any || !setMinimums(only)) return null;
                return undo;
            }

            @Override public void restore(int[] cents) {
                setMinimums(cents);
            }

            @Override public void setScoreByArea(boolean on) {
                setScoreMode(on);
            }

            @Override public void setMinimumScalePercent(int percent) {
                FilterSettings saved = FilterStore.load(MainActivity.this);
                FilterSettings next = saved.withMinimumScalePercent(percent);
                if (next.minimumScalePercent != saved.minimumScalePercent) saveRules(saved, next);
            }

            @Override public void setMaxStops(int stops) {
                setStops(stops);
            }

            @Override public void setAdaptive(boolean on) {
                setAdaptiveMinimum(on);
            }

            @Override public void resetLearned() {
                confirmResetLearned();
            }
        });
    }

    /**
     * Score by area on or off, from the toggle by the constellation: saved at once, through the same save as the
     * knobs, and applied to any offer on screen; nothing else changes. @return whether it changed
     */
    private boolean setScoreMode(boolean on) {
        FilterSettings saved = FilterStore.load(this);
        if (saved.scoreByArea == on) return false;
        FilterStore.save(this, saved.withScoreByArea(on));
        DiagnosticLog.log(this, "rules", on ? "score by area on: a standalone offer passes at a "
                + saved.minimumScalePercent + "% area score; max stops stays a hard limit, add-ons stay strict"
                : "score by area off: every minimum must be met at " + saved.minimumScalePercent + "% scale");
        rulesChanged();
        updateMeter();
        return true;
    }

    private void explainHotspotRule() {
        FilterSettings saved = FilterStore.load(this);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("Hotspot distance is unavailable")
                .setMessage("The app cannot yet reliably read the last stop's distance to a current Dasher hotspot. "
                        + "The Atlas shows your recorded offer areas, not Dasher's live hotspots. "
                        + (saved.hotspotProximityHundredths > 0
                        ? "Your saved hotspot rule is still on. An offer needing this missing distance stays for "
                                + "you to review; it cannot qualify for auto-accept. You can turn this rule off."
                        : "This spoke stays off until the distance can be measured."))
                .setPositiveButton("OK", null);
        if (saved.hotspotProximityHundredths > 0) {
            dialog.setNeutralButton("Turn hotspot rule off", (d, which) -> {
                int[] changes = new int[AreaScore.AXES];
                java.util.Arrays.fill(changes, -1);
                changes[AreaScore.HOTSPOT] = 0;
                setMinimums(changes);
            });
        }
        OwnWindowTouches.show(dialog);
    }

    /**
     * Minimums set on the constellation (a knob let go or adjusted by a screen reader, the learned ones adopted, or
     * that undone): saved at once through {@link FilterStore} and applied to any offer on screen. Only these minimums
     * change (-1 leaves one as saved). Monetary axes use cents; hotspot proximity uses hundredths per mile.
     *
     * @return whether the rules were saved
     */
    private boolean setMinimums(int[] cents) {
        FilterSettings saved = FilterStore.load(this);
        int[] next = saved.minimums();
        for (int i = 0; i < next.length && i < cents.length; i++) {
            if (cents[i] >= 0) next[i] = Math.min(FilterSettings.MOST_CENTS, cents[i]);
        }
        if (java.util.Arrays.equals(next, saved.minimums())) return false;
        return saveRules(saved, saved.withMinimums(next));
    }

    /** Max stops set on the constellation's badge (0: no limit), saved at once. @return whether it changed */
    private boolean setStops(int stops) {
        FilterSettings saved = FilterStore.load(this);
        int next = Math.max(0, Math.min(99, stops));
        if (next == saved.maxStops) return false;
        return saveRules(saved, saved.withMaxStops(next));
    }

    /**
     * The adaptive minimum on or off, from its toggle by the constellation: saved at once; what it learned is kept
     * either way (only Reset forgets it). @return whether it changed
     */
    private boolean setAdaptiveMinimum(boolean on) {
        FilterSettings saved = FilterStore.load(this);
        if (saved.risingOffers == on) return false;
        DiagnosticLog.log(this, "rules", on ? "adaptive minimum on" : "adaptive minimum off; what it learned is kept");
        return saveRules(saved, saved.withAdaptive(on));
    }

    /**
     * Saves rules changed on the constellation and applies them to any offer on screen. The on or paused state stays as
     * saved, except that no rule left pauses; a first rule saved while paused says auto-decline stays paused (nothing
     * here ever turns auto-decline on).
     */
    private boolean saveRules(FilterSettings saved, FilterSettings rules) {
        boolean pausedForLackOfRules = rules.enabled && !rules.hasAnyRule();
        if (pausedForLackOfRules) rules = rules.withEnabled(false);
        boolean firstRule = !saved.hasAnyRule() && rules.hasAnyRule() && !rules.enabled;
        FilterStore.save(this, rules);
        rulesChanged();
        updateMeter();
        if (pausedForLackOfRules) toast("No rules left, so auto-decline is paused.");
        else if (saved.hotspotProximityHundredths == 0 && rules.hotspotProximityHundredths > 0) {
            toast("Hotspot distance is not readable yet. Offers needing it will be left for review."
                    + (firstRule ? " Auto-decline stays paused." : ""));
        }
        else if (firstRule) toast("Rule saved. Tap the mascot to turn on auto-decline.");
        return true;
    }

    /**
     * A long press on the adaptive minimum's toggle (or a screen reader's Reset): asks first, then forgets the highest
     * accepted pay, every best rate and what declines taught. The set minimums and the switch stay as they are.
     */
    private void confirmResetLearned() {
        OwnWindowTouches.show(new AlertDialog.Builder(this)
                .setTitle("Reset learned minimums?")
                .setMessage("Forgets the highest pay you accepted, the best rates and what your own declines taught. "
                        + "Your set minimums stay.")
                .setPositiveButton("Reset", (dialog, which) -> {
                    FilterStore.resetAccepted(this);
                    DiagnosticLog.log(this, "rules", "adaptive minimum reset: what it learned was forgotten");
                    rulesChanged();
                    updateMeter();
                    toast("Learned minimums reset.");
                })
                .setNegativeButton("Cancel", null));
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
        installs = new Readiness(setup, "Updates can't install", () -> open(new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))));
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
                    .setTitle("Auto-accept matching offers?")
                    .setMessage("This can commit you to a delivery without another tap. It uses your current saved "
                            + "and adaptive minimums, your minimums percentage, and your chosen strict or area-score "
                            + "rule. Area scoring can compensate for a weaker metric.\n\n"
                            + "Only complete, matching standalone offers are eligible while the filter is on and "
                            + "your phone is unlocked. Add-ons and unclear offers stay yours. Your touch stops the "
                            + "current attempt. Automatic accepts do not train your adaptive minimums.\n\n"
                            + "The app can misread an offer. Enable this only if you accept that risk.")
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
        updatesRow = ui.listRow(connections, "Updates", () -> Updater.check(this, true, null));

        LinearLayout reports = group(body);
        ui.listRow(reports, "Send anonymous feedback", this::sendAnonymousFeedback);
        shareReportRow = ui.listRow(reports, "Share report", this::shareReport);
        ui.listRow(reports, "Clear history", this::confirmClearHistory);

        if (!Support.methods().isEmpty()) ui.listRow(group(body), "Tip", this::chooseTip);

        TextView footer = ui.text(AppName.NAME + " v" + Updater.version(this) + " · Not a DoorDash app.", 12,
                ui.inkSecondary, false);
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

    /** Accountless feedback. Diagnostics are attached only when explicitly chosen for this submission. */
    private void sendAnonymousFeedback() {
        EditText message = new EditText(this);
        message.setHint("What should we know?");
        message.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        message.setMaxLines(6);
        Switch diagnostics = new Switch(this);
        diagnostics.setText("Attach masked diagnostics");
        LinearLayout frame = ui.column();
        frame.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0);
        frame.addView(message, Ui.matchWidth());
        frame.addView(diagnostics, Ui.matchWidth());
        OwnWindowTouches.show(new AlertDialog.Builder(this)
                .setTitle("Send anonymous feedback?")
                .setMessage("No account, name or email is required. The feedback record stores only what you type, "
                        + "the app version, and masked diagnostics if you explicitly attach them. Network providers still "
                        + "see ordinary connection metadata such as an IP address; Offer Filter does not store the raw IP "
                        + "with your feedback. Masking can miss details, so review diagnostics before attaching them.")
                .setView(frame)
                .setPositiveButton("Send", (dialog, which) -> {
                    String note = message.getText().toString().trim();
                    if (note.isEmpty()) {
                        toast("Write something first.");
                        return;
                    }
                    toast("Sending feedback…");
                    AnonymousFeedback.send(this, "feedback", "general", note, diagnostics.isChecked(), result ->
                            toast(result.ok
                                    ? "Feedback sent" + (result.reference.isEmpty() ? "." : " · " + result.reference)
                                    : "Feedback not sent: " + result.message));
                })
                .setNegativeButton("Cancel", null));
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
        LauncherAppearance.sync(this, appearance);
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
        if (saved.enabled) {
            stateLine.setText("");
            hero.setAction("Pause auto-decline");
        } else if (saved.hasAnyRule()) {
            stateLine.setText("Paused");
            hero.setAction("Resume auto-decline");
        } else {
            stateLine.setText(START_HINT);
            hero.setAction("Set up rules");
        }
        stateLine.setVisibility(saved.enabled ? View.GONE : View.VISIBLE);
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
        waitEstimateLine.setVisibility(showWait ? View.VISIBLE : View.GONE);
        if (showWait && !estimate.label().contentEquals(waitEstimateLine.getText())) {
            waitEstimateLine.setText(estimate.label());
        }
        screenReading.problem(screenReadingEnabled.get() ? "Screen reading stopped"
                : restrictedSettingsHint ? "Screen reading is off · switch greyed out?" : "Screen reading is off");
        screenReading.update(readerConnected);
        backgroundOffers.update(OfferNotificationService.isConnected());
        offerAlerts.update(alertsAllowed.get());
        refreshFeeNotice();
        OfferSnapshot route = ActiveRouteStore.load(this);
        routeRow.setVisibility(route == null ? View.GONE : View.VISIBLE);
        if (route != null) routeNote.setText("On a route: " + route.summary());

        refreshHistory();
        refreshOfferCaption(chart.selectedEntry());
        refreshLive();
        // Rules saved elsewhere, and an accepted order (or a declined one that taught) changing the adaptive minimums
        // without a new offer in the history: the star follows them as well.
        String rules = saved.describe();
        if (!rules.equals(shownLearned)) {
            shownLearned = rules;
            updateMeter();
        }
        refreshAreas();
        refreshHero(saved.enabled ? FilterHeroView.State.ON
                : saved.hasAnyRule() ? FilterHeroView.State.PAUSED : FilterHeroView.State.OFF);
        boolean updating = Updater.installing(this);
        if (updating != (updatingCover.getVisibility() == View.VISIBLE)) {
            updatingCover.setVisibility(updating ? View.VISIBLE : View.GONE);
            if (updating) {
                updatingCover.setAlpha(0f);
                updatingCover.animate().alpha(1f).setDuration(250);
                updatingCover.announceForAccessibility("Updating " + AppName.NAME);
            }
        }
        refreshSettings();
    }

    /** Settings' rows: what needs a fix and where the accountless updater stands. */
    private void refreshSettings() {
        doorDashAlerts.update(!FilterStore.doorDashChannelAlerts(this));
        installs.update(installsAllowed.get());
        ui.setRow(updatesRow, "Updates", Updater.status(this));
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
                    && !AcceptedBest.looksMisread(facts)) {
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

    /** Qualify acceptance only when the stored observation distinguishes its source. */
    private static String captionOutcome(DecisionLog.Entry entry) {
        if (DecisionLog.outcome(entry) != DecisionLog.Outcome.ACCEPTED) return DecisionLog.outcome(entry).said;
        for (int i = entry.steps.size() - 1; i >= 0; i--) {
            DecisionLog.Step step = entry.steps.get(i);
            if (step.kind == DecisionLog.StepKind.ACCEPTED_NOT_LEARNED) {
                return step.detail.startsWith("automatic Accept was requested, and Dasher showed a delivery screen")
                        ? "Automatically accepted" : DecisionLog.Outcome.ACCEPTED.said;
            }
            if (step.kind == DecisionLog.StepKind.ACCEPTED_OBSERVED) return DecisionLog.Outcome.ACCEPTED.said;
            if (step.kind == DecisionLog.StepKind.ACCEPTED_LEARNED
                    || step.kind == DecisionLog.StepKind.ACCEPTED_BEST_SAVED
                    || step.kind == DecisionLog.StepKind.ACCEPTED_MINIMUMS_UNCHANGED
                    || step.kind == DecisionLog.StepKind.ACCEPTED_ADD_ON) return "Accepted by you";
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

        DecisionLog.Step learningStatus = latestLearningStatus(entry);
        if (learningStatus != null) {
            TextView learned = ui.text(learningStatus.text(), 14, ui.ink, true);
            learned.setPadding(0, ui.dp(12), 0, 0);
            ticket.addView(learned);
        }
        OfferCardView card = new OfferCardView(this, ui);
        card.show(entry);
        LinearLayout.LayoutParams cardParams = Ui.matchWidth();
        cardParams.topMargin = ui.dp(12);
        ticket.addView(card, cardParams);
        // The offer's area score as decided, in either mode; a reason by score already says it.
        if (entry.scorePercent >= 0 && !entry.reason.startsWith(OfferRule.SCORE_REASON)) {
            int currentScore = AreaScore.percent(FilterStore.load(this), entry.facts);
            TextView score = ui.text("Score reference · " + AreaScore.label(entry.scorePercent)
                    .replaceFirst("^Score ", "")
                    + (currentScore >= 0 && currentScore != entry.scorePercent ? " at decision" : ""),
                    14, ui.inkSecondary, true);
            score.setPadding(0, ui.dp(8), 0, 0);
            ticket.addView(score);
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
            List<String> learning = new java.util.ArrayList<>();
            for (DecisionLog.Step step : entry.steps) {
                if (step != learningStatus) learning.add(step.text());
            }
            if (!learning.isEmpty()) {
                TextView learned = ui.text(String.join("\n", learning), 13, ui.inkSecondary, false);
                learned.setPadding(0, ui.dp(6), 0, 0);
                ticket.addView(learned);
            }
        }
        TextView minimumDetails = ui.text(entry.addOn
                ? "Add-ons use their fixed route and incremental rules. Learned standalone minimums do not apply."
                : MinimumsDetails.describe(FilterStore.load(this), entry.facts), 13, ui.inkSecondary, false);
        minimumDetails.setPadding(0, ui.dp(6), 0, 0);
        minimumDetails.setVisibility(View.GONE);
        Button minimumKey = ui.addButton(ticket, "Minimums · blue saved / purple learned", false, () -> {});
        minimumKey.setContentDescription("Show current saved, learned and used minimums for this offer");
        minimumKey.setOnClickListener(clicked -> {
            boolean expanded = minimumDetails.getVisibility() != View.VISIBLE;
            minimumDetails.setVisibility(expanded ? View.VISIBLE : View.GONE);
            minimumKey.setContentDescription((expanded ? "Hide" : "Show")
                    + " current saved, learned and used minimums for this offer");
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

    /** Keep the most recent observed learning result prominent, without inventing a lesson from a passed rule. */
    private static DecisionLog.Step latestLearningStatus(DecisionLog.Entry entry) {
        for (int i = entry.steps.size() - 1; i >= 0; i--) {
            DecisionLog.Step step = entry.steps.get(i);
            switch (step.kind) {
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

    /** Rule reasons in everyday words; the report keeps the exact wording. */
    static String plainReason(String reason) {
        if (reason == null || reason.isEmpty()) return "";
        if (reason.startsWith("combined route fails: ")) {
            return "Whole route: " + plainReason(reason.substring("combined route fails: ".length()));
        }
        // "pay at most $8.35 with its +$ amount; flat minimum": even the most it can pay fails that rule.
        int bound = reason.indexOf(" with its +$ amount; ");
        if (reason.startsWith("pay at most ") && bound > 0) {
            return "Even at " + reason.substring("pay at most ".length(), bound) + " with its +$: "
                    + plainReason(reason.substring(bound + " with its +$ amount; ".length())).toLowerCase(Locale.US);
        }
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
            case "dollars per mile": plain = "Below your per-mile rate"; break;
            case "dollars per minute": plain = "Below your per-minute rate"; break;
            case "dollars per stop": plain = "Below your per-stop rate"; break;
            case "meets enabled rules": plain = "Meets your rules"; break;
            case "pay not found": plain = "Pay not readable"; break;
            case "an enabled value was not found": plain = "Miles, time or stops not readable"; break;
            case "add-on marginal economics": plain = "Add-on pays too little for what it adds"; break;
            case "add-on has missing or ambiguous incremental/route evidence": plain = "Add-on details unclear"; break;
            case "combined route and add-on meet enabled rules": plain = "Add-on meets your rules"; break;
            case "auto-decline is off; inspect this offer manually": plain = "Auto-decline paused"; break;
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
            requestPermissions(new String[] {Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                    BACKGROUND_LOCATION_REQUEST);
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

    /** Pause, Resume, or with no saved rule, a pointer to the knobs. */
    private void toggleAutoDecline() {
        FilterSettings saved = FilterStore.load(this);
        if (saved.enabled) pause();
        else if (saved.hasAnyRule()) resume();
        else showStart();
    }

    /**
     * With no rule yet: the constellation's hollow knobs beckon (in a short window it first leaves the header for the
     * sky, where they are), and screen readers hear how to begin.
     */
    private void showStart() {
        if (compact && !besideDasher && !skyChosen) chooseSky(true);
        else minimums.beckon();
        hero.announceForAccessibility(START_HINT + ". Each knob sets a minimum.");
    }

    /** Persists auto-decline off immediately, keeping every saved rule. */
    private void pause() {
        FilterStore.save(this, FilterStore.load(this).withEnabled(false));
        rulesChanged();
        toast("Paused. Nothing will be declined.");
    }

    /** Turns auto-decline on with the saved rules. */
    private void resume() {
        FilterSettings saved = FilterStore.load(this);
        if (!saved.hasAnyRule()) {
            showStart();
            return;
        }
        FilterStore.save(this, saved.withEnabled(true));
        rulesChanged();
        toast("Auto-decline is on.");
    }

    private void rulesChanged() {
        OfferNotificationService.rulesChanged();
        OfferFilterService.requestCheckFromNotification();
        refresh();
    }

    /** Accountless report for one selected offer; its stored evidence is already masked on the phone. */
    private void reportOffer(DecisionLog.Entry entry) {
        EditText note = new EditText(this);
        note.setHint("What went wrong? (optional)");
        note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        note.setMaxLines(4);
        LinearLayout frame = ui.column();
        frame.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0);
        frame.addView(note, Ui.matchWidth());
        OwnWindowTouches.show(new AlertDialog.Builder(this)
                .setTitle("Report this offer?")
                .setMessage("Sends this offer's masked evidence and your optional note without requiring an account. "
                        + "Do not put customer, payment or account details in your note. Masking can miss details.")
                .setView(frame)
                .setPositiveButton("Send", (dialog, which) -> {
                    toast("Sending report…");
                    AnonymousFeedback.sendOffer(this, entry, note.getText().toString(), result ->
                            toast(result.ok
                                    ? "Report sent" + (result.reference.isEmpty() ? "." : " · " + result.reference)
                                    : "Report not sent: " + result.message));
                })
                .setNegativeButton("Cancel", null));
    }

    /** One confirm for decisions, captured text, offer areas and cached place names. */
    private void confirmClearHistory() {
        OwnWindowTouches.show(new AlertDialog.Builder(this)
                .setTitle("Clear history?")
                .setMessage("Removes the offer decisions, waiting estimates, captured screen text, offer areas and cached place names "
                        + "from this phone. Your rules stay.")
                .setPositiveButton("Clear", (dialog, which) -> {
                    cancelReportShare();
                    DecisionLog.clear(this);
                    QualifyingWaitStore.clear(this);
                    DiagnosticLog.clear(this);
                    AreaMap.forget(this);
                    Places.forget(this);
                    RestartSuppression.clear(this);
                    AutoAcceptMemory.clear(this);
                    ScannerFailure.clear(this);
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

    private void openNotificationAccess() {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        if (Build.VERSION.SDK_INT >= 30) {
            intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    new ComponentName(this, OfferNotificationService.class).flattenToString());
        }
        open(intent);
    }

    private boolean screenReadingEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName ours = new ComponentName(this, OfferFilterService.class);
        for (String component : enabled.split(":")) {
            if (ours.equals(ComponentName.unflattenFromString(component))) return true;
        }
        return false;
    }

    private void fixScreenReading() {
        if (restrictedSettingsHint && !screenReadingEnabled.get()) {
            OwnWindowTouches.show(new AlertDialog.Builder(this)
                    .setTitle("Switch greyed out?")
                    .setMessage("If Android blocks the switch, open App info, then its three-dot menu and "
                            + "Allow restricted settings. Then return to Accessibility to enable screen reading.")
                    .setPositiveButton("App info", (dialog, which) -> open(new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Accessibility", (dialog, which) -> openScreenReadingSettings())
                    .setNeutralButton("Cancel", null));
        } else {
            openScreenReadingSettings();
        }
    }

    private void openScreenReadingSettings() {
        getSharedPreferences(SETUP_PREFS, MODE_PRIVATE).edit().putBoolean(ACCESSIBILITY_OPENED, true).apply();
        if (Build.VERSION.SDK_INT >= 31) {
            // AOSP's service-specific settings action is not part of the public SDK constants. OEMs may omit it.
            Intent details = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME,
                            new ComponentName(this, OfferFilterService.class).flattenToString());
            try {
                startActivity(details);
                return;
            } catch (RuntimeException unsupported) {
                // The public accessibility list remains available on phones without a direct service screen.
            }
        }
        open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void configureOfferAlerts() {
        OfferAlerts.ensureChannel(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            boolean asked = getSharedPreferences(SETUP_PREFS, MODE_PRIVATE).getBoolean(NOTIFICATIONS_ASKED, false);
            if (asked && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                openAppNotifications();
                return;
            }
            getSharedPreferences(SETUP_PREFS, MODE_PRIVATE).edit().putBoolean(NOTIFICATIONS_ASKED, true).apply();
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
            return;
        }
        openAppNotifications();
    }

    private void openAppNotifications() {
        // Passing offers, review cards and the paused-until-opened reminder each have their own channel.
        open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,
                getPackageName()));
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
            toast("Android could not open this screen: " + error.getClass().getSimpleName());
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

        Readiness(LinearLayout parent, String problem, Runnable onFix) {
            row = ui.row();
            row.setBackground(ui.pressable(16));
            row.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6));
            row.setMinimumHeight(ui.dp(48));
            row.setClickable(true);
            row.setFocusable(true);
            row.setContentDescription(problem + ". Fix.");
            row.setOnClickListener(tapped -> onFix.run());
            View sign = new View(MainActivity.this);
            sign.setBackground(new Glyph(Glyph.Shape.SIGN, Ui.CRITICAL, ui.dp(20)));
            sign.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(sign, new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
            text = ui.text(problem, 15, ui.ink, false);
            text.setPadding(ui.dp(10), 0, ui.dp(8), 0);
            row.addView(text, Ui.weighted());
            row.addView(ui.text("Fix", 15, ui.accent, true));
            parent.addView(row, Ui.matchWidth());
        }

        void update(boolean ready) {
            row.setVisibility(ready ? View.GONE : View.VISIBLE);
        }

        void problem(String problem) {
            if (problem.contentEquals(text.getText())) return;
            text.setText(problem);
            row.setContentDescription(problem + ". Fix.");
        }
    }
}
