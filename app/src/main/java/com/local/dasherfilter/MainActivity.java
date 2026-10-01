package com.local.dasherfilter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The app's two pages, kept quiet. The main page is one picture: a sky where the minimums, as a very large
 * constellation with recent offers marked (tap for the rules), spread behind the mascot in its ring and the dash's
 * three counts (the mascot is the one button: a tap pauses or resumes), with a line only when something needs the
 * user; then recent offers as a skyline on the horizon (tap for a ticket); and, on the ground below it on a whole
 * screen, a map of where offers pay best. Settings holds everything set once: the rules, sound and Android shortcuts,
 * the offer map, reports and updates. Pause and Resume take effect at once; Save keeps the on/paused state. The
 * drawings move gently and shift with the phone's tilt while the app fills the screen, unless Android's animations
 * are off; in split screen they move calmly and the tilt sensor rests.
 */
public final class MainActivity extends Activity implements Updater.Busy {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 13;
    private static final int LOCATION_REQUEST = 14;
    private static final int BACKGROUND_LOCATION_REQUEST = 15;
    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    /** A split-screen window shorter than this gets the compact homepage; any window at all under the second. */
    static final int COMPACT_SPLIT_HEIGHT_DP = 600;
    static final int COMPACT_HEIGHT_DP = 400;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000");
    private static final Pattern TOO_MANY_STOPS = Pattern.compile("(\\d+) stops exceeds maximum (\\d+)");
    private static final String SHOWING_SETTINGS = "settings";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1000);
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
    private final Asked<double[]> here = new Asked<>(() -> AreaMap.here(this));
    /** Whether the page is resumed, so leaving split screen knows whether to start the tilt again. */
    private boolean resumed;
    private Ui ui;
    private ScrollView mainPage;
    private ScenePage scene;
    /** The header's Split with Dasher button, shown while Dasher is installed and the screen is not split. */
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

    // Main page: the mascot.
    private TextView stateLine;
    private FilterHeroView hero;
    private int shownState;
    private String shownHero = "";
    private Readiness screenReading;
    private Readiness backgroundOffers;
    private Readiness offerAlerts;
    private LinearLayout routeRow;
    private TextView routeNote;

    // Main page: offers.
    private DecisionChartView chart;
    private LinearLayout ticket;
    private Decor.Ticket ticketShape;
    private Button reportSelected;
    private TextView noOffers;
    private long shownHistoryVersion = -1;
    /** What the adaptive minimums had learned when the star was last drawn. */
    private String shownLearned = "";
    private List<DecisionLog.Entry> recentEntries = Collections.emptyList();
    /** The ticket follows each new offer until an older one is picked. */
    private boolean followNewest = true;
    /** The chosen offer's ticket is folded away until its line or a building is tapped. */
    private boolean ticketOpen;

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
    private View sunButton;
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

    // Settings page.
    private EditText flat;
    private EditText mile;
    private EditText minute;
    private EditText stop;
    /** "0 turns a rule off.", beside Per stop, and in its place once: the note that an extra-stop fee was retired. */
    private TextView zeroHint;
    private TextView stopFeeNotice;
    private EditText maxStops;
    private Switch rising;
    private TextView baselineNote;
    private TextView rulesPreview;
    private Switch areasToggle;
    private TextView areasStatus;
    private Button areasFix;
    private Button areasForget;
    private EditText reportToken;
    private Switch reportViaGitHub;
    private TextView reportStatus;
    private Button sendTest;
    private Button stopReports;
    private TextView updateStatus;
    private Button allowInstalls;
    private TextView githubStatus;
    private TextView githubCode;
    private Button githubConnect;
    private Button githubDisconnect;
    private GitHubConnect.State shownGitHub;
    private boolean askingGitHub;

    /** Day or night as chosen with the sun and moon, for every view and dialog of this screen. */
    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        android.content.res.Configuration chosen = Appearance.override(base);
        if (chosen != null) applyOverrideConfiguration(chosen);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        OfferAlerts.ensureChannel(this);
        FilterStore.forgetRetiredEmail(this);
        FilterSettings saved = FilterStore.load(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(ui.page);
        scene = new ScenePage(this, ui);
        mainPage = addPage(root, scene);
        settingsPage = addPage(root, ui.column());
        buildMain((LinearLayout) mainPage.getChildAt(0));
        buildSettings((LinearLayout) settingsPage.getChildAt(0), saved);
        buildSheet(root);
        buildUpdatingCover(root);
        Updater.relaunched(this);

        setContentView(root);
        styleSystemBars();
        fitToSystemBars(root);
        showSettings(state != null && state.getBoolean(SHOWING_SETTINGS, false));
        Updater.schedule(this);
        refresh();
    }

    /** In Settings, or reading a ticket or the map: an update waits rather than interrupt. */
    @Override public boolean midTask() {
        return showingSettings || (sheet != null && sheet.getVisibility() == View.VISIBLE);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean(SHOWING_SETTINGS, showingSettings);
    }

    /** Back from Settings returns to the main page; back from the main page leaves. */
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        // While updating, nothing is to be interrupted; the new version opens by itself.
        if (updatingCover.getVisibility() == View.VISIBLE) return;
        if (sheet.getVisibility() == View.VISIBLE) closeSheet();
        else if (showingSettings) showSettings(false);
        else super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        // Sound left turned down by a decline the screen reader could not finish is put back here too.
        if (!OfferFilterService.isConnected()) OfferSilencer.restore(this);
        Updater.foreground(this);
        if (GitHubConnect.configured()) GitHubConnect.resume(this);
        // Back from Android's settings, perhaps: ask again.
        forgetAnswers();
        followSplit(isInMultiWindowMode());
        handler.removeCallbacks(refresh);
        handler.post(refresh);
        Updater.check(this, false, null);
        ReportOutbox.retryRefused(this);
        DasherSplit.resumed(this);
    }

    @Override public void onMultiWindowModeChanged(boolean inMultiWindow, Configuration configuration) {
        super.onMultiWindowModeChanged(inMultiWindow, configuration);
        followSplit(inMultiWindow);
        DasherSplit.resumed(this);
        refresh();
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

    /** Offer Filter above, Dasher below, at the user's tap. */
    private void splitWithDasher() {
        String said = DasherSplit.start(this);
        if (said != null) toast(said);
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(refresh);
        Tilt.stop();
        Updater.background(this);
        super.onPause();
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
        // Behind the status bar: the top of the main page's sky, or the Settings page.
        root.setBackgroundColor(settings ? ui.page : ScenePage.skyTop(ui));
        if (Build.VERSION.SDK_INT < 35) getWindow().setStatusBarColor(settings ? ui.page : ScenePage.skyTop(ui));
        View focused = getCurrentFocus();
        if (focused != null && !focused.isShown()) focused.clearFocus();
        showStopFeeNotice(settings);
        if (changed) {
            shown.setAlpha(0f);
            shown.animate().alpha(1f).setDuration(160);
        }
    }

    /**
     * The first time Settings opens after an update retired an extra-stop fee, the note saying so stands beside Per
     * stop until Settings is left; it is not shown again.
     */
    private void showStopFeeNotice(boolean settings) {
        if (stopFeeNotice == null) return;
        String notice = settings ? FilterStore.takeStopFeeNotice(this) : null;
        if (notice != null) {
            stopFeeNotice.setText(notice);
            stopFeeNotice.setVisibility(View.VISIBLE);
            zeroHint.setVisibility(View.GONE);
        } else if (!settings) {
            stopFeeNotice.setVisibility(View.GONE);
            zeroHint.setVisibility(View.VISIBLE);
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
            splitButton = iconButton(Glyph.Shape.SPLIT, "Split screen with Dasher", this::splitWithDasher);
            LinearLayout.LayoutParams splitParams = new LinearLayout.LayoutParams(ui.dp(52), ui.dp(52));
            splitParams.setMarginEnd(ui.dp(4));
            header.addView(splitButton, splitParams);
            // The sun (or moon) the scene draws here is a button: a tap turns day into night and back.
            View sun = new View(this);
            sun.setContentDescription(ui.dark ? "Switch to day" : "Switch to night");
            sun.setBackground(ui.pressable(28));
            sun.setOnClickListener(tapped -> {
                Appearance.choose(this, !ui.dark);
                recreate();
            });
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
        TextView title = ui.text("Updating Offer Filter…", 20, ui.ink, true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, ui.dp(12), 0, 0);
        updatingCover.addView(title, Ui.matchWidth());
        TextView note = ui.text("It opens again by itself in a moment.", 15, ui.inkSecondary, false);
        note.setGravity(Gravity.CENTER_HORIZONTAL);
        note.setPadding(ui.dp(24), ui.dp(6), ui.dp(24), 0);
        updatingCover.addView(note, Ui.matchWidth());
        updatingCover.setContentDescription("Updating Offer Filter. It opens again by itself in a moment.");
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
        View header = header("Offer Filter", false);

        // The mascot is the button: a tap pauses, resumes, or with no rule yet opens the rules.
        hero = new FilterHeroView(this, ui);
        hero.setOnClickListener(tapped -> toggleAutoDecline());
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
        LinearLayout problems = ui.column();
        lines.addView(problems, Ui.matchWidth());
        screenReading = new Readiness(problems, "Screen reading is off",
                () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        backgroundOffers = new Readiness(problems, "Background offers are off", this::openNotificationAccess);
        offerAlerts = new Readiness(problems, "Alerts are blocked", this::configureOfferAlerts);
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
        addOffers(body);
        addAreas(body);
        // The road needs a whole screen; in a short window (beside Dasher or not) the page ends at the skyline or
        // the map, and a window changing size builds the page again.
        View road = ground(page, 78);
        road.setVisibility(compact ? View.GONE : View.VISIBLE);
        // The empty title takes the header's spare room, so screen readers reach it, and hear it first.
        mainTitle.setId(View.generateViewId());
        minimums.setAccessibilityTraversalAfter(mainTitle.getId());
        if (compact) {
            // Half a screen holds the whole picture only if each part settles for a little less.
            chart.setLeastDp(52);
            areaMap.setLeastDp(84);
            areaLine.setMinHeight(ui.dp(32));
            body.setPadding(body.getPaddingLeft(), 0, body.getPaddingRight(), ui.dp(4));
            mainHeader.setPadding(mainHeader.getPaddingLeft(), ui.dp(4), mainHeader.getPaddingRight(), 0);
        }
        besideDasher = !besideDasherNow();
        arrangeForSplit();
        // The skyline's street (12 dp above the chart's bottom) is the horizon.
        scene.setHorizon(chart, ui.dp(11), noOffers);
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
     * fixed height above it, and the constellation moves into the header, its icons beside the circle. A whole screen
     * shows everything, the sky above the map. (The road is set once, when the page is built.)
     */
    private void arrangeForSplit() {
        boolean beside = besideDasherNow();
        if (beside == besideDasher) return;
        besideDasher = beside;
        areaMap.setVisibility(beside ? View.GONE : View.VISIBLE);
        if (beside || shownArea == null) areaLine.setVisibility(View.GONE);
        else areaLine.setVisibility(View.VISIBLE);
        boolean inHeader = compact && !beside;
        placeConstellation(inHeader);
        // Beside another app the map needs the room, so the skyline stands at a fixed height rather than a share.
        chartParams.height = inHeader ? ui.dp(CHART_SHORT_DP) : 0;
        chartParams.weight = inHeader ? 0 : 0.7f;
        chart.setLayoutParams(chartParams);
        // The sky's share against the ground's (the skyline, and the map where there is one).
        skyParams.weight = beside ? SKY_BESIDE_DASHER : inHeader ? SKY_WITH_MAP_SHORT : SKY_WHOLE;
        groundParams.weight = beside ? 0.7f : inHeader ? 3.2f : 1.7f;
        sky.requestLayout();
    }

    /** The constellation in the header's left (drawn with its icons beside the circle), or spread across the sky. */
    private void placeConstellation(boolean inHeader) {
        ViewGroup now = (ViewGroup) minimums.getParent();
        if (now != null && inHeader == (now == mainHeader)) return;
        if (now != null) now.removeView(minimums);
        minimums.setBeside(inHeader);
        if (inHeader) {
            // Clear of the split screen's handle at the middle of the top edge.
            mainHeader.addView(minimums, 0, new LinearLayout.LayoutParams(
                    ui.dp(MinimumsStarView.besideWidthDp(HEADER_STAR_DP)), ui.dp(HEADER_STAR_DP)));
        } else {
            sky.holdStar();
        }
    }

    private void addOffers(LinearLayout body) {
        noOffers = ui.note("No offers yet.");
        noOffers.setGravity(Gravity.CENTER_HORIZONTAL);
        body.addView(noOffers);
        chart = new DecisionChartView(this, ui);
        chart.setOnSelect(entry -> {
            followNewest = !recentEntries.isEmpty() && entry == recentEntries.get(0);
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
        ticketOpen = open;
        if (chart.selectedEntry() != null) showSelection(chart.selectedEntry());
        if (open) openSheet(ticket);
        else if (sheet != null && ticket.getVisibility() != View.VISIBLE) sheet.setVisibility(View.GONE);
    }

    /**
     * The minimums' constellation; a tap opens them. The sky or the header holds it, as the window allows. In the sky
     * its knobs set the minimums, and its button makes the learned ones the set ones (or undoes that), each saved as
     * Settings saves them.
     */
    private void addMinimums() {
        minimums = new MinimumsStarView(this, ui);
        minimums.setOnClickListener(tapped -> showSettings(true));
        minimums.setChanges(new MinimumsStarView.Changes() {
            @Override public void setMinimum(int axis, int cents) {
                int[] one = {-1, -1, -1, -1};
                one[axis] = cents;
                setMinimums(one);
            }

            @Override public int[] adoptLearned() {
                // From the saved rules, never from what is typed but unsaved in Settings: only the minimums the
                // adoption raises are written and saved, and Undo puts back exactly the saved ones it replaced.
                FilterSettings saved = FilterStore.load(MainActivity.this);
                int[] before = saved.minimums();
                int[] raised = saved.adoptAdaptive().minimums();
                int[] only = {-1, -1, -1, -1};
                int[] undo = {-1, -1, -1, -1};
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
        });
    }

    /** The minimums' fields in the constellation's spoke order: pay, per mile, per minute, per stop. */
    private EditText[] minimumFields() {
        return new EditText[] {flat, mile, minute, stop};
    }

    /**
     * Minimums set on the constellation (a knob let go, the learned ones adopted, or that undone), as if typed into
     * their fields and saved with Save rules: each field shows its new value, the same checks apply, and the rules are
     * saved and applied to any offer on screen at once. Only these minimums change (-1 leaves one as saved); max stops,
     * the adaptive minimum and the on or paused state stay as saved, except that no rule left pauses, as Save rules
     * does; a first rule saved while paused says auto-decline stays paused, as Save rules does (nothing here ever
     * turns auto-decline on). Nothing is saved when nothing changed. @return whether the rules were saved
     */
    private boolean setMinimums(int[] cents) {
        FilterSettings saved = FilterStore.load(this);
        EditText[] fields = minimumFields();
        int[] next = saved.minimums();
        for (int i = 0; i < fields.length; i++) {
            if (cents[i] < 0) continue;
            String typed = money(cents[i]);
            if (!typed.contentEquals(fields[i].getText())) fields[i].setText(typed);
            try {
                next[i] = parseCents(fields[i]);
            } catch (IllegalArgumentException error) {
                toast(error.getMessage());
                return false;
            }
        }
        if (java.util.Arrays.equals(next, saved.minimums())) return false;
        FilterSettings rules = saved.withMinimums(next);
        boolean pausedForLackOfRules = rules.enabled && !rules.hasAnyRule();
        if (pausedForLackOfRules) rules = rules.withEnabled(false);
        boolean firstRule = !saved.hasAnyRule() && rules.hasAnyRule() && !rules.enabled;
        FilterStore.save(this, rules);
        rulesChanged();
        // The fields may already have shown these values, so the constellation is told of the save itself.
        updateMeter();
        if (pausedForLackOfRules) toast("No rules left, so auto-decline is paused.");
        else if (firstRule) toast("Rule saved. Auto-decline stays paused until you Resume it.");
        return true;
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
        areaLine.setGravity(Gravity.CENTER);
        areaLine.setMinHeight(ui.dp(36));
        areaLine.setBackground(ui.pressable(12));
        areaLine.setOnClickListener(tapped -> openArea());
        areaLine.setVisibility(View.GONE);
        body.addView(areaLine, Ui.matchWidth());
    }

    // ---- Settings page ----

    private void buildSettings(LinearLayout page, FilterSettings saved) {
        page.addView(header("Settings", true));
        LinearLayout body = body(page);
        addRules(body, saved);
        addSoundAndSetup(body);
        addOfferMap(body);
        addReports(body);
        addUpdates(body);
        addSupport(body);
        TextView footer = ui.text("Offer Filter v" + Updater.version(this) + " · Not a DoorDash app.", 12,
                ui.inkSecondary, false);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setPadding(0, ui.dp(28), 0, 0);
        body.addView(footer, Ui.matchWidth());
        ground(page, 170);
    }

    private void addRules(LinearLayout body, FilterSettings saved) {
        ui.heading(body, "Minimums");
        LinearLayout first = fieldRow(body);
        flat = ui.tagField(cell(first), "Minimum pay ($)", money(saved.flatCents), true, Glyph.Shape.COIN);
        maxStops = ui.tagField(cell(first), "Max stops (1 order = 2)", Integer.toString(saved.maxStops), false,
                Glyph.Shape.STOPS);
        LinearLayout second = fieldRow(body);
        mile = ui.tagField(cell(second), "Per mile ($)", money(saved.perMileCents), true, Glyph.Shape.ROAD);
        minute = ui.tagField(cell(second), "Per minute ($)", money(saved.perMinuteCents), true, Glyph.Shape.CLOCK);
        LinearLayout third = fieldRow(body);
        stop = ui.tagField(cell(third), "Per stop ($)", money(saved.perStopCents), true, Glyph.Shape.PIN);
        LinearLayout hint = cell(third);
        zeroHint = ui.text("0 turns a rule off.", 12, ui.inkSecondary, false);
        zeroHint.setPadding(ui.dp(6), ui.dp(14), 0, 0);
        hint.addView(zeroHint);
        stopFeeNotice = ui.text("", 12, ui.ink, true);
        stopFeeNotice.setPadding(ui.dp(6), ui.dp(4), 0, 0);
        stopFeeNotice.setVisibility(View.GONE);
        hint.addView(stopFeeNotice);
        rulesPreview = ui.text("", 14, ui.ink, true);
        rulesPreview.setPadding(0, ui.dp(12), 0, 0);
        body.addView(rulesPreview);

        rising = ui.toggle(body, "Adaptive minimum", saved.risingOffers);
        LinearLayout baseline = ui.row();
        baselineNote = ui.text("", 13, ui.inkSecondary, false);
        baselineNote.setCompoundDrawablesRelative(new Glyph(Glyph.Shape.TREND, ui.accent, ui.dp(18)), null, null,
                null);
        baselineNote.setCompoundDrawablePadding(ui.dp(8));
        baseline.addView(baselineNote, Ui.weighted());
        baseline.addView(ui.button("Reset", false, () -> {
            FilterStore.resetAccepted(this);
            refresh();
            updateMeter();
        }));
        body.addView(baseline);
        ui.addButton(body, "Save rules", true, this::save);

        TextWatcher preview = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) {
                updateMeter();
            }
        };
        for (EditText field : new EditText[] {flat, maxStops, mile, minute, stop}) {
            field.addTextChangedListener(preview);
        }
        rising.setOnCheckedChangeListener((view, on) -> updateMeter());
    }

    /** Two tags side by side, or one above the other when a large font would crowd them. */
    private LinearLayout fieldRow(LinearLayout body) {
        LinearLayout row = ui.largeText() ? ui.column() : ui.row();
        row.setGravity(Gravity.TOP);
        body.addView(row, Ui.matchWidth());
        return row;
    }

    /** A half-width column inside {@code row}, or a full-width one when the row is stacked. */
    private LinearLayout cell(LinearLayout row) {
        LinearLayout cell = ui.column();
        if (row.getOrientation() == LinearLayout.VERTICAL) {
            row.addView(cell, Ui.matchWidth());
            return cell;
        }
        // Full height, so tags side by side match even when one label wraps.
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        params.setMarginEnd(row.getChildCount() == 0 ? ui.dp(6) : 0);
        params.setMarginStart(row.getChildCount() == 0 ? 0 : ui.dp(6));
        row.addView(cell, params);
        return cell;
    }

    private void addSoundAndSetup(LinearLayout body) {
        ui.heading(body, "Sound & setup");
        Switch mute = ui.toggle(body, "Mute Dasher's ring while declining", FilterStore.silenceWhileDeclining(this));
        mute.setOnCheckedChangeListener((view, on) -> FilterStore.setSilenceWhileDeclining(this, on));
        ui.listRow(body, "Accessibility", () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        ui.listRow(body, "Notification access", this::openNotificationAccess);
        ui.listRow(body, "Alert settings", this::configureOfferAlerts);
        ui.listRow(body, "DoorDash channel", this::openDoorDashChannel);
    }

    private void addOfferMap(LinearLayout body) {
        ui.heading(body, "Offer map");
        areasToggle = ui.toggle(body, "Remember where offers come in", AreaMap.enabled(this));
        areasToggle.setOnCheckedChangeListener((view, on) -> {
            if (on == AreaMap.enabled(this)) return;
            AreaMap.setEnabled(this, on);
            if (on && !AreaMap.hasPermission(this)) askForLocation();
            refresh();
        });
        body.addView(ui.note("Shows where offers pay best per mile on the main page. Uses approximate location "
                + "when each offer appears, in squares of about 2 km, and stays on this phone."));
        areasStatus = ui.text("", 13, ui.inkSecondary, false);
        areasStatus.setPadding(0, ui.dp(8), 0, 0);
        body.addView(areasStatus);
        areasFix = ui.addButton(body, "Allow location", true, this::askForLocation);
        areasForget = ui.addButton(body, "Forget areas", false, this::confirmForgetAreas);
    }

    private void addReports(LinearLayout body) {
        ui.heading(body, "Reports");
        ui.listRow(body, "Share report", this::shareReport);
        ui.listRow(body, "Clear history", this::confirmClearHistory);
        TextView kept = ui.text("The most recent screen text (never older than a day) stays on this phone for reports. "
                + "It leaves only in a report you share.", 13, ui.inkSecondary, false);
        kept.setPadding(0, ui.dp(6), 0, 0);
        body.addView(kept);

        // Once GitHub is connected for updates, reports can go through the same connection: no token to paste.
        reportViaGitHub = ui.toggle(body, "Send reports through my GitHub connection",
                ReportOutbox.useGitHubChosen(this));
        reportViaGitHub.setOnCheckedChangeListener((view, on) -> {
            if (on == ReportOutbox.useGitHubChosen(this)) return;
            ReportOutbox.useGitHub(this, on);
            refresh();
        });
        TextView caption = ui.text("Automatic reports (GitHub token)", 13, ui.inkSecondary, false);
        caption.setPadding(0, ui.dp(14), 0, ui.dp(4));
        body.addView(caption);
        reportToken = new EditText(this);
        reportToken.setId(View.generateViewId());
        caption.setLabelFor(reportToken.getId());
        reportToken.setSingleLine(true);
        reportToken.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        // Password input switches to monospace; keep the field in the page's font like the others.
        reportToken.setTypeface(Typeface.DEFAULT);
        ui.styleField(reportToken);
        body.addView(reportToken, Ui.matchWidth());
        reportStatus = ui.text("", 13, ui.inkSecondary, false);
        reportStatus.setPadding(0, ui.dp(4), 0, 0);
        body.addView(reportStatus);
        sendTest = ui.button("Send test", false, this::sendTestReport);
        ui.buttonPair(body, ui.button("Save token", false, this::saveReportToken), sendTest);
        stopReports = ui.addButton(body, "Turn off reports", false, this::confirmStopReports);
    }

    private void addUpdates(LinearLayout body) {
        ui.heading(body, "Updates");
        Switch updates = ui.toggle(body, "Automatic updates", Updater.enabled(this));
        updates.setOnCheckedChangeListener((view, on) -> {
            Updater.setEnabled(this, on);
            if (on) Updater.check(this, false, null);
        });
        updateStatus = ui.text("", 13, ui.inkSecondary, false);
        body.addView(updateStatus);
        ui.listRow(body, "Check for update", () -> Updater.check(this, true, null));
        allowInstalls = ui.listRow(body, "Allow installs", () -> open(new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))));
        if (GitHubConnect.configured()) addGitHub(body);
    }

    /** Signing in to GitHub so updates also come from the app's private repository. */
    private void addGitHub(LinearLayout body) {
        githubStatus = ui.text("", 13, ui.inkSecondary, false);
        githubStatus.setPadding(0, ui.dp(14), 0, 0);
        body.addView(githubStatus);
        githubCode = ui.text("", 30, ui.ink, true);
        githubCode.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        githubCode.setLetterSpacing(0.08f);
        githubCode.setTextIsSelectable(true);
        githubCode.setGravity(Gravity.CENTER_HORIZONTAL);
        githubCode.setPadding(0, ui.dp(8), 0, 0);
        body.addView(githubCode, Ui.matchWidth());
        githubConnect = ui.addButton(body, "Connect GitHub", false, this::connectGitHub);
        githubDisconnect = ui.listRow(body, "Disconnect GitHub", () -> {
            GitHubConnect.disconnect(this);
            refresh();
        });
    }

    /** First tap asks GitHub for a code and opens GitHub with it copied; later taps open GitHub again. */
    private void connectGitHub() {
        if (GitHubConnect.userCode(this) != null) {
            openGitHub();
            return;
        }
        askingGitHub = true;
        refresh();
        GitHubConnect.connect(this, () -> {
            askingGitHub = false;
            if (isFinishing() || isDestroyed()) return;
            refresh();
            if (GitHubConnect.userCode(this) != null) openGitHub();
        });
    }

    private void openGitHub() {
        String code = GitHubConnect.userCode(this);
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (code != null && clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("GitHub code", code));
            toast("Code " + code + " copied. Paste it on GitHub, then come back.");
        }
        open(new Intent(Intent.ACTION_VIEW, Uri.parse(GitHubConnect.VERIFICATION_URL)));
    }

    /** One-tap tips, shown once the author's names are filled in. */
    private void addSupport(LinearLayout body) {
        List<Support.Method> methods = Support.methods();
        if (methods.isEmpty()) return;
        ui.heading(body, "Support");
        body.addView(ui.note("Offer Filter is free. If it makes your dash better, a tip keeps it going."));
        for (Support.Method method : methods) {
            ui.listRow(body, "Tip with " + method.label,
                    () -> open(new Intent(Intent.ACTION_VIEW, Uri.parse(Support.link(method)))));
        }
    }

    // ---- State ----

    private void refresh() {
        if (stateLine == null) return;
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
            stateLine.setText("Tap to set up rules");
            hero.setAction("Set up rules");
        }
        stateLine.setVisibility(saved.enabled ? View.GONE : View.VISIBLE);
        if (splitButton != null) {
            splitButton.setVisibility(DasherSplit.offered(this, dasherInstalled.get()) ? View.VISIBLE : View.GONE);
        }

        screenReading.update(OfferFilterService.isConnected());
        backgroundOffers.update(OfferNotificationService.isConnected());
        offerAlerts.update(alertsAllowed.get());
        OfferSnapshot route = ActiveRouteStore.load(this);
        routeRow.setVisibility(route == null ? View.GONE : View.VISIBLE);
        if (route != null) routeNote.setText("On a route: " + route.summary());

        refreshHistory();
        refreshLive();
        // An accepted order (or a declined one that taught) changes the adaptive minimums without a new offer in
        // the history, so the star follows what was learned as well.
        String learned = saved.lastAcceptedCents + "|" + saved.best.summary() + "|" + saved.declined.summary();
        if (!learned.equals(shownLearned)) {
            shownLearned = learned;
            updateMeter();
        }
        refreshAreas();
        refreshHero(saved.enabled ? FilterHeroView.State.ON
                : saved.hasAnyRule() ? FilterHeroView.State.PAUSED : FilterHeroView.State.OFF);
        baselineNote.setText(adaptiveNote(saved));
        updateStatus.setText(Updater.status(this));
        boolean updating = Updater.installing(this);
        if (updating != (updatingCover.getVisibility() == View.VISIBLE)) {
            updatingCover.setVisibility(updating ? View.VISIBLE : View.GONE);
            if (updating) {
                updatingCover.setAlpha(0f);
                updatingCover.animate().alpha(1f).setDuration(250);
                updatingCover.announceForAccessibility("Updating Offer Filter");
            }
        }
        if (githubStatus != null) refreshGitHub();
        allowInstalls.setVisibility(installsAllowed.get() ? View.GONE : View.VISIBLE);
        boolean reporting = ReportOutbox.enabled(this);
        boolean connected = GitHubConnect.configured() && GitHubConnect.state(this) == GitHubConnect.State.CONNECTED;
        reportViaGitHub.setVisibility(connected ? View.VISIBLE : View.GONE);
        if (reportViaGitHub.isChecked() != ReportOutbox.useGitHubChosen(this)) {
            reportViaGitHub.setChecked(ReportOutbox.useGitHubChosen(this));
        }
        reportStatus.setText(ReportOutbox.status(this));
        reportToken.setHint(reporting ? "Token saved · paste to replace" : "github_pat_…");
        if (reportSelected != null) reportSelected.setVisibility(reporting ? View.VISIBLE : View.GONE);
        sendTest.setVisibility(reporting ? View.VISIBLE : View.INVISIBLE);
        stopReports.setVisibility(reporting ? View.VISIBLE : View.GONE);
    }

    private void refreshGitHub() {
        GitHubConnect.State state = GitHubConnect.state(this);
        String code = GitHubConnect.userCode(this);
        githubStatus.setText(askingGitHub ? "Asking GitHub for a code…" : GitHubConnect.status(this));
        githubCode.setText(code == null ? "" : code);
        githubCode.setVisibility(code == null ? View.GONE : View.VISIBLE);
        githubConnect.setText(state == GitHubConnect.State.WAITING ? "Copy code and open GitHub" : "Connect GitHub");
        githubConnect.setEnabled(!askingGitHub);
        githubConnect.setVisibility(state == GitHubConnect.State.CONNECTED ? View.GONE : View.VISIBLE);
        githubDisconnect.setText(state == GitHubConnect.State.WAITING ? "Cancel" : "Disconnect GitHub");
        githubDisconnect.setVisibility(state == GitHubConnect.State.OFF ? View.GONE : View.VISIBLE);
        // Just connected: look for an update from the repository right away rather than at the next check.
        if (state == GitHubConnect.State.CONNECTED && shownGitHub == GitHubConnect.State.WAITING) {
            Updater.check(this, true, null);
        }
        shownGitHub = state;
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
        chart.setEntries(recent);
        boolean empty = recent.isEmpty();
        noOffers.setVisibility(empty ? View.VISIBLE : View.GONE);
        chart.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            ticketOpen = false;
            ticket.setVisibility(View.GONE);
            if (sheet != null) sheet.setVisibility(View.GONE);
        }
        if (!empty) {
            DecisionLog.Entry selected = chart.selectedEntry();
            if (followNewest || selected == null) {
                chart.select(Math.min(DecisionChartView.SLOTS, recent.size()) - 1);
            } else {
                showSelection(selected);
            }
        }
        updateMeter();
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

    /** "Highest accepted $14.20 · best $0.59/min, $2.37/mi · beat declined $25.00", or what it waits for. */
    private static String adaptiveNote(FilterSettings saved) {
        if (saved.lastAcceptedCents <= 0 && saved.best.isEmpty() && saved.declined.isEmpty()) {
            return "Rises with the offers you accept, and ones you decline by hand, and stays until you reset it. None yet.";
        }
        List<String> parts = new java.util.ArrayList<>();
        if (saved.lastAcceptedCents > 0) parts.add("Highest accepted " + DecisionLog.money(saved.lastAcceptedCents));
        if (!saved.best.isEmpty()) parts.add("best " + saved.best.summary());
        if (!saved.declined.isEmpty()) parts.add("beat declined " + saved.declined.summary());
        return String.join(" · ", parts);
    }

    /**
     * Redraws the minimums from the rules as typed (unsaved), with the latest fully read offer; whether the learned
     * minimums can be adopted follows the saved rules, which adopting changes.
     */
    private void updateMeter() {
        if (minimums == null || rising == null) return;
        FilterSettings typed = typedRules();
        OfferSnapshot example = exampleOffer();
        minimums.show(typed, FilterStore.load(this), example, recentEntries);
        rulesPreview.setText(MinimumsStarView.needs(typed, example));
    }

    /** The rules as typed in Settings (saved or not), with what the adaptive minimums learned: what the star shows. */
    private FilterSettings typedRules() {
        FilterSettings saved = FilterStore.load(this);
        return new FilterSettings(saved.enabled, lenientCents(flat, saved.flatCents),
                lenientCents(mile, saved.perMileCents), lenientCents(minute, saved.perMinuteCents),
                lenientCents(stop, saved.perStopCents), lenientStops(saved.maxStops), rising.isChecked(),
                saved.lastAcceptedCents, saved.best, saved.declined);
    }

    private static int lenientCents(EditText field, int fallback) {
        try {
            return parseCents(field);
        } catch (IllegalArgumentException invalid) {
            return fallback;
        }
    }

    private int lenientStops(int fallback) {
        String raw = maxStops.getText().toString().trim();
        if (raw.isEmpty()) return 0;
        return raw.matches("[0-9]{1,2}") ? Integer.parseInt(raw) : fallback;
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

    /**
     * The chosen offer (a tapped building in the skyline), unfolded as a ticket: its outcome stamped on the stub with
     * the time, then the drawn offer (pay against needed, the route), the reason, what the app did, and the exact
     * lines read.
     */
    private void showSelection(DecisionLog.Entry entry) {
        ticket.removeAllViews();
        ticket.setVisibility(ticketOpen ? View.VISIBLE : View.GONE);
        if (!ticketOpen) {
            reportSelected = null;
            return;
        }
        LinearLayout stub = ui.row();
        stub.addView(new Decor.Stamp(this, ui, entry.result));
        TextView time = ui.text(when(entry.at) + (entry.addOn ? " · add-on" : ""), 13, ui.inkSecondary, false);
        time.setGravity(Gravity.END);
        stub.addView(time, Ui.weighted());
        // The tear line runs under the stub however tall a large font makes it.
        stub.setMinimumHeight(ui.dp(Decor.Ticket.STUB_DP));
        stub.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                ticketShape.setStub(bottom));
        ticket.addView(stub, Ui.matchWidth());

        OfferCardView card = new OfferCardView(this, ui);
        card.show(entry);
        LinearLayout.LayoutParams cardParams = Ui.matchWidth();
        cardParams.topMargin = ui.dp(12);
        ticket.addView(card, cardParams);
        TextView reason = ui.text(plainReason(entry), 16, ui.ink, true);
        reason.setPadding(0, ui.dp(10), 0, 0);
        ticket.addView(reason);
        TextView action = ui.text(entry.action.label
                + (entry.source == DecisionLog.Source.SCREEN ? " · on screen" : " · from notification")
                + (entry.autoDecline ? "" : " · while paused"), 13, ui.inkSecondary, false);
        action.setPadding(0, ui.dp(4), 0, 0);
        ticket.addView(action);
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
        reportSelected = ui.addButton(ticket, "Report this offer", false, () -> reportOffer(entry));
        reportSelected.setVisibility(ReportOutbox.enabled(this) ? View.VISIBLE : View.GONE);
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

    private void confirmForgetAreas() {
        new AlertDialog.Builder(this)
                .setTitle("Forget offer areas?")
                .setMessage("This removes every area from this phone. It does not change your rules.")
                .setPositiveButton("Forget", (dialog, which) -> {
                    AreaMap.forget(this);
                    pickedArea = false;
                    areaMap.select(null);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** The status line and Fix button in Settings, then the treasure map when anything it shows changed. */
    private void refreshAreas() {
        boolean on = AreaMap.enabled(this);
        if (areasToggle.isChecked() != on) areasToggle.setChecked(on);
        List<AreaMap.Cell> cells = AreaMap.cells(this);
        int unlocated = AreaMap.unlocated(this);
        boolean permitted = locationAllowed.get();
        boolean needsAllTheTime = permitted && unlocated > 0 && !locationAlways.get();
        if (!on) {
            areasStatus.setText("Off. Nothing about where you are is kept.");
        } else if (!permitted) {
            areasStatus.setText("Needs location permission. Approximate is enough.");
        } else if (needsAllTheTime) {
            areasStatus.setText(unlocated + (unlocated == 1 ? " offer" : " offers") + " came in without a "
                    + "location. Android may share it only while Offer Filter is open, so choose Allow all the "
                    + "time.");
        } else {
            areasStatus.setText("On · " + AreaMap.totalOffers(cells) + " offers in " + cells.size()
                    + (cells.size() == 1 ? " area" : " areas")
                    + (unlocated > 0 ? " · " + unlocated + " without a location" : ""));
        }
        areasFix.setVisibility(on && (!permitted || needsAllTheTime) ? View.VISIBLE : View.GONE);
        areasFix.setText(permitted ? "Allow all the time" : "Allow location");
        areasForget.setVisibility(cells.isEmpty() && unlocated == 0 ? View.GONE : View.VISIBLE);
        if (!on) {
            areaMap.setEmptyMessage("Tap to map where offers pay best");
        } else if (!permitted) {
            areaMap.setEmptyMessage("Tap to allow location");
        } else {
            areaMap.setEmptyMessage("Offers will pin here");
        }
        List<AreaMap.Cell> shownCells = on ? cells : java.util.Collections.<AreaMap.Cell>emptyList();
        // Beside Dasher the map is hidden: only the signpost's place name uses where the phone is.
        double[] here = on && permitted ? this.here.get(besideDasher ? ASK_EVERY_MS : HERE_EVERY_MS) : null;
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
        String rate = cell.ranked() ? cell.perMile() : cell.offers + (cell.offers == 1 ? " offer" : " offers");
        // Its rank is on its coin; "of you" and Maps go without saying on the page, not to screen readers.
        List<String> parts = new java.util.ArrayList<>();
        if (name != null) parts.add(name);
        if (!where.isEmpty()) parts.add(where.replace(" of you", ""));
        parts.add(rate);
        areaLine.setText(String.join(" · ", parts) + "  ›");
        String spoken = (rank > 0 ? "#" + rank : "Not ranked yet") + (name == null ? "" : " · " + name)
                + (where.isEmpty() ? "" : " · " + where) + " · " + rate;
        areaLine.setContentDescription(spoken + ". Average " + DecisionLog.money(cell.averagePayCents()) + ", last "
                + when(cell.lastAt) + ". Opens it in Maps.");
        areaLine.setVisibility(besideDasher ? View.GONE : View.VISIBLE);
    }

    private void openArea() {
        AreaMap.Cell cell = shownArea;
        if (cell == null) return;
        open(new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US,
                "geo:%.4f,%.4f?q=%.4f,%.4f(Offer area)", cell.latitude(), cell.longitude(), cell.latitude(),
                cell.longitude()))));
    }

    // ---- Actions ----

    /** Pause, Resume, or with no saved rule, off to the rules to add one. */
    private void toggleAutoDecline() {
        FilterSettings saved = FilterStore.load(this);
        if (saved.enabled) pause();
        else if (saved.hasAnyRule()) resume();
        else showSettings(true);
    }

    /** Persists auto-decline off immediately, keeping every saved rule. */
    private void pause() {
        FilterStore.save(this, FilterStore.load(this).withEnabled(false));
        rulesChanged();
        toast("Paused. Nothing will be declined.");
    }

    /** Saves the rules as typed and turns auto-decline on. */
    private void resume() {
        try {
            FilterSettings next = readRules(true);
            if (!next.hasAnyRule()) {
                toast("Add a rule first.");
                showSettings(true);
                return;
            }
            FilterStore.save(this, next);
            rulesChanged();
            toast("Auto-decline is on.");
        } catch (IllegalArgumentException error) {
            toast(error.getMessage());
        }
    }

    private void save() {
        try {
            FilterSettings next = readRules(FilterStore.load(this).enabled);
            boolean pausedForLackOfRules = next.enabled && !next.hasAnyRule();
            if (pausedForLackOfRules) next = next.withEnabled(false);
            FilterStore.save(this, next);
            rulesChanged();
            toast(pausedForLackOfRules ? "No rules left, so auto-decline is paused."
                    : next.enabled || !next.hasAnyRule() ? "Rules saved."
                    : "Rules saved. Auto-decline stays paused until you Resume it.");
        } catch (IllegalArgumentException error) {
            toast(error.getMessage());
        }
    }

    private void rulesChanged() {
        OfferNotificationService.rulesChanged();
        OfferFilterService.requestCheckFromNotification();
        refresh();
    }

    /** The rules as typed with the given on/paused state. @throws IllegalArgumentException with a user message */
    private FilterSettings readRules(boolean enabled) {
        String stops = maxStops.getText().toString().trim();
        if (stops.isEmpty()) stops = "0";
        if (!stops.matches("[0-9]{1,2}")) throw new IllegalArgumentException("Maximum stops must be 0 through 99.");
        FilterSettings saved = FilterStore.load(this);
        return new FilterSettings(enabled, parseCents(flat), parseCents(mile), parseCents(minute), parseCents(stop),
                Integer.parseInt(stops), rising.isChecked(), saved.lastAcceptedCents, saved.best, saved.declined);
    }

    /** Parses a dollar amount from 0 to 1000 with at most two decimals. Blank means zero (rule disabled). */
    private static int parseCents(EditText field) {
        try {
            String raw = field.getText().toString().trim();
            if (raw.isEmpty()) return 0;
            BigDecimal amount = new BigDecimal(raw);
            if (amount.signum() < 0 || amount.compareTo(MAX_AMOUNT) > 0 || amount.scale() > 2) {
                throw new NumberFormatException();
            }
            return amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).intValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IllegalArgumentException("Amounts must be 0–1000 with at most two decimals.");
        }
    }

    private static String money(int cents) {
        return String.format(Locale.US, "%.2f", cents / 100.0);
    }

    /** Asks what went wrong (optional) and files the offer, with what was read, for the fixer. */
    private void reportOffer(DecisionLog.Entry entry) {
        if (!ReportOutbox.enabled(this)) {
            toast("Add a GitHub token in Settings first.");
            return;
        }
        EditText note = new EditText(this);
        note.setHint("What went wrong? (optional)");
        note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        note.setMaxLines(4);
        LinearLayout frame = ui.column();
        frame.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0);
        frame.addView(note, Ui.matchWidth());
        new AlertDialog.Builder(this)
                .setTitle("Report this offer?")
                .setMessage("Sends what the app read (other words masked, so names and streets stay here), your "
                        + "rules and recent decisions to your private repository, where Claude diagnoses it.")
                .setView(frame)
                .setPositiveButton("Send", (dialog, which) -> {
                    boolean queued = ReportOutbox.fileByUser(this, entry, note.getText().toString());
                    toast(queued ? "Report queued. It sends when you're online." : "Daily report limit reached.");
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveReportToken() {
        String token = reportToken.getText().toString().trim();
        if (token.isEmpty()) {
            toast("Paste a GitHub token first.");
            return;
        }
        if (!token.matches("[A-Za-z0-9_]{20,255}")) {
            toast("That doesn't look like a GitHub token.");
            return;
        }
        ReportOutbox.setToken(this, token);
        reportToken.setText("");
        toast("Automatic reports are on.");
        refresh();
    }

    private void confirmStopReports() {
        new AlertDialog.Builder(this)
                .setTitle("Turn off automatic reports?")
                .setMessage("Removes the token from this phone and discards reports not yet sent.")
                .setPositiveButton("Turn off", (dialog, which) -> {
                    ReportOutbox.setToken(this, "");
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendTestReport() {
        if (!ReportOutbox.enabled(this)) {
            toast("Paste a GitHub token first.");
            return;
        }
        toast(ReportOutbox.fileTest(this) ? "Test report queued." : "Daily report limit reached.");
        refresh();
    }

    private void confirmClearHistory() {
        new AlertDialog.Builder(this)
                .setTitle("Clear offer history?")
                .setMessage("This removes the recorded decisions and the captured screen text from this phone. It "
                        + "does not change your rules.")
                .setPositiveButton("Clear", (dialog, which) -> {
                    DecisionLog.clear(this);
                    DiagnosticLog.clear(this);
                    followNewest = true;
                    ticketOpen = false;
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Offers any app for the diagnostic report; mail keeps its "Offer Filter diagnostics" subject. */
    private void shareReport() {
        open(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, DiagnosticLog.reportSubject(this))
                .putExtra(Intent.EXTRA_TEXT, DiagnosticLog.report(this)), "Share Offer Filter report"));
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

    private void configureOfferAlerts() {
        OfferAlerts.ensureChannel(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
            return;
        }
        open(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, OfferAlerts.CHANNEL_ID));
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
    private void styleSystemBars() {
        Window window = getWindow();
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
    private void fitToSystemBars(View content) {
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
            TextView text = ui.text(problem, 15, ui.ink, false);
            text.setPadding(ui.dp(10), 0, ui.dp(8), 0);
            row.addView(text, Ui.weighted());
            row.addView(ui.text("Fix", 15, ui.accent, true));
            parent.addView(row, Ui.matchWidth());
        }

        void update(boolean ready) {
            row.setVisibility(ready ? View.GONE : View.VISIBLE);
        }
    }
}
