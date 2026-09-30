package com.local.dasherfilter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
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
 * The app's two pages, kept quiet. The main page: the mascot in its ring with the day's three counts, which is also
 * the one button (a tap pauses or resumes), and one line saying whether auto-decline is on; then recent offers as a small skyline with one line about the chosen offer
 * (tap for its ticket); the minimums as a constellation with recent offers marked; and, only when mapping is on,
 * where offers pay best. Settings holds everything set once: the rules, sound and Android shortcuts, the offer map,
 * reports and updates. Pause and Resume take effect at once; Save keeps the on/paused state. The drawings move
 * gently and shift with the phone's tilt while the app is open, unless Android's animations are off.
 */
public final class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 13;
    private static final int LOCATION_REQUEST = 14;
    private static final int BACKGROUND_LOCATION_REQUEST = 15;
    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
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
    private Ui ui;
    private ScrollView mainPage;
    private ScenePage scene;
    private LinearLayout liveRow;
    private TextView liveText;
    private FrameLayout root;
    /** A card sliding up over the main page for the chosen offer's ticket or the map: the page itself never scrolls. */
    private FrameLayout sheet;
    private LinearLayout sheetCard;
    private TextView areaLine;
    private ScrollView settingsPage;
    private boolean showingSettings;

    // Main page: the mascot.
    private TextView stateLine;
    private TextView stateHint;
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
    private TextView chosenLine;
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
    private TextView minimumsNote;
    private LinearLayout mapping;
    private AreaMapView areaMap;
    private LinearLayout areaDetail;
    private String shownAreas = "";
    private double[] areaHere;
    private boolean pickedArea;

    // Settings page.
    private EditText flat;
    private EditText mile;
    private EditText minute;
    private EditText stop;
    private EditText maxStops;
    private Switch rising;
    private TextView baselineNote;
    private TextView rulesPreview;
    private Switch areasToggle;
    private TextView areasStatus;
    private Button areasFix;
    private Button areasForget;
    private EditText reportToken;
    private TextView reportStatus;
    private Button sendTest;
    private Button stopReports;
    private Switch diagnostics;
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

        setContentView(root);
        styleSystemBars();
        fitToSystemBars(root);
        showSettings(state != null && state.getBoolean(SHOWING_SETTINGS, false));
        Updater.schedule(this);
        refresh();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean(SHOWING_SETTINGS, showingSettings);
    }

    /** Back from Settings returns to the main page; back from the main page leaves. */
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (sheet.getVisibility() == View.VISIBLE) closeSheet();
        else if (showingSettings) showSettings(false);
        else super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        // Sound left turned down by a decline the screen reader could not finish is put back here too.
        if (!OfferFilterService.isConnected()) OfferSilencer.restore(this);
        Updater.foreground(this);
        if (GitHubConnect.configured()) GitHubConnect.resume(this);
        Tilt.start(this);
        handler.removeCallbacks(refresh);
        handler.post(refresh);
        Updater.check(this, false, null);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        Tilt.stop();
        Updater.background(this);
        super.onPause();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
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
        TextView name = ui.text(title, 22, ui.ink, true);
        if (back) name.setPadding(ui.dp(8), 0, 0, 0);
        if (Build.VERSION.SDK_INT >= 28) name.setAccessibilityHeading(true);
        header.addView(name, Ui.weighted());
        if (!back) {
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
        LinearLayout body = ui.column();
        body.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(12));
        page.addView(body, Ui.matchWidth());
        return body;
    }

    /** The drawn hills and road a page ends on. */
    private void ground(LinearLayout page, int heightDp) {
        View ground = new View(this);
        ground.setBackground(new Scenery(Scenery.Part.GROUND, ui));
        ground.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        page.addView(ground, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(heightDp)));
    }

    /** A share of the height left on one screen. */
    private static LinearLayout.LayoutParams share(float weight) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight);
    }

    private void buildSheet(FrameLayout root) {
        sheet = new FrameLayout(this);
        sheet.setBackgroundColor(0x66000000);
        sheet.setVisibility(View.GONE);
        sheet.setClickable(true);
        sheet.setOnClickListener(tapped -> closeSheet());
        sheetCard = ui.column();
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
        content.addView(mapping, Ui.matchWidth());
        scroll.addView(content, Ui.matchWidth());
        sheetCard.addView(scroll, Ui.matchWidth());
        sheet.addView(sheetCard, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        root.addView(sheet, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Slides the sheet up with {@code content} (the ticket or the map) in it. */
    private void openSheet(View content) {
        ticket.setVisibility(content == ticket ? View.VISIBLE : View.GONE);
        mapping.setVisibility(content == mapping ? View.VISIBLE : View.GONE);
        if (sheet.getVisibility() == View.VISIBLE) return;
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
        page.addView(header("Offer Filter", false));
        LinearLayout body = body(page);
        // Everything between the title and the road shares one screen.
        body.setLayoutParams(share(1));

        // The mascot is the button: a tap pauses, resumes, or with no rule yet opens the rules.
        hero = new FilterHeroView(this, ui);
        hero.setOnClickListener(tapped -> toggleAutoDecline());
        body.addView(hero, share(1));
        stateLine = ui.text("", 17, ui.ink, true);
        stateLine.setGravity(Gravity.CENTER_HORIZONTAL);
        stateLine.setPadding(0, ui.dp(4), 0, 0);
        body.addView(stateLine, Ui.matchWidth());
        stateHint = ui.text("", 14, ui.inkSecondary, false);
        stateHint.setGravity(Gravity.CENTER_HORIZONTAL);
        stateHint.setPadding(0, ui.dp(2), 0, 0);
        // The mascot already says what a tap does; this line is for the eye.
        stateHint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        body.addView(stateHint, Ui.matchWidth());
        // While dashing: the app is live, watching Dasher.
        liveRow = ui.row();
        liveRow.setGravity(Gravity.CENTER);
        liveRow.setPadding(0, ui.dp(6), 0, 0);
        liveRow.addView(new LiveDot(this, ui));
        liveText = ui.text("", 13, ui.inkSecondary, false);
        liveText.setPadding(ui.dp(4), 0, 0, 0);
        liveRow.addView(liveText);
        liveRow.setVisibility(View.GONE);
        body.addView(liveRow, Ui.matchWidth());
        LinearLayout problems = ui.column();
        LinearLayout.LayoutParams problemParams = Ui.matchWidth();
        problemParams.topMargin = ui.dp(8);
        body.addView(problems, problemParams);
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
        body.addView(routeRow);

        // One picture from top to bottom: the minimums as a constellation in the sky, the offers as a skyline on
        // the horizon, the chosen offer and the map on the ground, and the road along the bottom.
        addMinimums(body);
        addOffers(body);
        addAreas(body);
        ground(page, 78);
        // The skyline's street (12 dp above the chart's bottom) is the horizon.
        scene.setHorizon(chart, ui.dp(11), noOffers);
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
        LinearLayout.LayoutParams chartParams = share(0.7f);
        chartParams.topMargin = ui.dp(6);
        body.addView(chart, chartParams);
        chosenLine = ui.text("", 14, ui.inkSecondary, false);
        chosenLine.setGravity(Gravity.CENTER);
        chosenLine.setPadding(0, ui.dp(4), 0, ui.dp(4));
        chosenLine.setMinHeight(ui.dp(40));
        chosenLine.setOnClickListener(tapped -> setTicketOpen(!ticketOpen));
        body.addView(chosenLine, Ui.matchWidth());
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

    private void addMinimums(LinearLayout body) {
        minimums = new MinimumsStarView(this, ui);
        minimums.setOnClickListener(tapped -> showSettings(true));
        LinearLayout.LayoutParams starParams = share(1.15f);
        starParams.topMargin = ui.dp(6);
        body.addView(minimums, starParams);
        minimumsNote = ui.text("", 13, ui.inkSecondary, false);
        minimumsNote.setGravity(Gravity.CENTER_HORIZONTAL);
        minimumsNote.setPadding(0, ui.dp(2), 0, 0);
        body.addView(minimumsNote, Ui.matchWidth());
    }

    /**
     * Only while mapping is on (turned on in Settings): one line on the ground naming the chosen area, which opens
     * the map and the area's details in the sheet.
     */
    private void addAreas(LinearLayout body) {
        areaLine = ui.text("", 14, ui.ink, true);
        areaLine.setGravity(Gravity.CENTER);
        areaLine.setMinHeight(ui.dp(40));
        areaLine.setBackground(ui.pressable(12));
        areaLine.setOnClickListener(tapped -> openSheet(mapping));
        areaLine.setVisibility(View.GONE);
        body.addView(areaLine, Ui.matchWidth());
        mapping = ui.column();
        areaMap = new AreaMapView(this, ui);
        areaMap.setOnSelect(cell -> {
            pickedArea = true;
            showArea(cell);
        });
        mapping.addView(areaMap, Ui.matchWidth());
        areaDetail = ui.column();
        mapping.addView(areaDetail, Ui.matchWidth());
        mapping.setVisibility(View.GONE);
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
        stop = ui.tagField(cell(third), "Per extra stop ($)", money(saved.extraStopCents), true, Glyph.Shape.PIN);
        LinearLayout hint = cell(third);
        TextView zero = ui.text("0 turns a rule off.", 12, ui.inkSecondary, false);
        zero.setPadding(ui.dp(6), ui.dp(14), 0, 0);
        hint.addView(zero);
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
        // A small fixed tab on Dasher's left edge that pauses or resumes, shown only while Dasher is on screen.
        Switch tab = ui.toggle(body, "Filter button in Dasher", DasherOverlay.enabled(this));
        tab.setOnCheckedChangeListener((view, on) -> DasherOverlay.setEnabled(this, on));
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
        diagnostics = ui.toggle(body, "Capture full screen text (30 min)", DiagnosticLog.isEnabled(this));
        diagnostics.setOnCheckedChangeListener((view, on) -> DiagnosticLog.setEnabled(this, on));

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
        FilterSettings saved = FilterStore.load(this);
        int state = saved.enabled ? 1 : saved.hasAnyRule() ? 2 : 3;
        shownState = state;
        if (saved.enabled) {
            stateLine.setText("Auto-decline is on");
            stateHint.setText("Tap me to pause");
            hero.setAction("Pause auto-decline");
        } else if (saved.hasAnyRule()) {
            stateLine.setText("Auto-decline is paused");
            stateHint.setText("Tap me to resume");
            hero.setAction("Resume auto-decline");
        } else {
            stateLine.setText("Auto-decline is off");
            stateHint.setText("Tap me to set up rules");
            hero.setAction("Set up rules");
        }

        screenReading.update(OfferFilterService.isConnected());
        backgroundOffers.update(OfferNotificationService.isConnected());
        offerAlerts.update(OfferAlerts.canNotify(this));
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
        if (githubStatus != null) refreshGitHub();
        allowInstalls.setVisibility(getPackageManager().canRequestPackageInstalls() ? View.GONE : View.VISIBLE);
        if (diagnostics.isChecked() && !DiagnosticLog.isEnabled(this)) diagnostics.setChecked(false);
        boolean reporting = ReportOutbox.enabled(this);
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

    /** "Watching for offers · last one 3 min ago" while dashing, with the searchlights in the sky. */
    private void refreshLive() {
        boolean live = Dashing.now(this);
        scene.setWatching(live);
        liveRow.setVisibility(live ? View.VISIBLE : View.GONE);
        // One line under the state either way: what a tap does, or that the app is watching.
        stateHint.setVisibility(live ? View.GONE : View.VISIBLE);
        if (!live) return;
        String text = "Watching for offers";
        if (!recentEntries.isEmpty()) {
            long minutes = (System.currentTimeMillis() - recentEntries.get(0).at) / 60_000L;
            if (minutes >= 0 && minutes < 60) {
                text += " · last one " + (minutes < 1 ? "just now" : minutes + " min ago");
            }
        }
        if (!text.contentEquals(liveText.getText())) liveText.setText(text);
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
        chosenLine.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            ticketOpen = false;
            ticket.setVisibility(View.GONE);
            if (sheet != null && mapping.getVisibility() != View.VISIBLE) sheet.setVisibility(View.GONE);
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

    /** Redraws the minimums from the rules as typed (unsaved), with the latest fully read offer. */
    private void updateMeter() {
        if (minimums == null || rising == null) return;
        FilterSettings saved = FilterStore.load(this);
        FilterSettings typed = new FilterSettings(saved.enabled, lenientCents(flat, saved.flatCents),
                lenientCents(mile, saved.perMileCents), lenientCents(minute, saved.perMinuteCents),
                lenientCents(stop, saved.extraStopCents), lenientStops(saved.maxStops), rising.isChecked(),
                saved.lastAcceptedCents, saved.best, saved.declined);
        OfferSnapshot example = exampleOffer();
        minimums.show(typed, example, recentEntries);
        minimumsNote.setText(minimums.caption());
        rulesPreview.setText(MinimumsStarView.needs(typed, example));
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

    /** The newest standalone offer whose miles, minutes and stops were all read, else a typical one. */
    private OfferSnapshot exampleOffer() {
        for (DecisionLog.Entry entry : recentEntries) {
            OfferSnapshot facts = entry.facts;
            if (!entry.addOn && facts.miles != null && facts.minutes != null && facts.stops != null) return facts;
        }
        return new OfferSnapshot(null, 5.0, 20, 2);
    }

    /**
     * The chosen offer in one line under the skyline (time, outcome, reason), and, when unfolded, as a ticket: its
     * outcome stamped on the stub with the time, then the drawn offer (pay against needed, the route), the reason,
     * what the app did, and the exact lines read.
     */
    private void showSelection(DecisionLog.Entry entry) {
        String line = when(entry.at) + (entry.addOn ? " · add-on" : "") + " · " + Ui.resultLabel(entry.result)
                + " · " + plainReason(entry);
        chosenLine.setText(line + (ticketOpen ? "  ▴" : "  ▾"));
        chosenLine.setContentDescription(line + (ticketOpen ? ". Details shown; tap to hide."
                : ". Tap for details."));
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
        boolean stopFees = reason.endsWith(" and extra stops");
        String base = stopFees ? reason.substring(0, reason.length() - " and extra stops".length()) : reason;
        String plain;
        switch (base) {
            case "flat minimum": plain = "Below your minimum pay"; break;
            case "dollars per mile": plain = "Below your per-mile rate"; break;
            case "dollars per minute": plain = "Below your per-minute rate"; break;
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
        boolean permitted = AreaMap.hasPermission(this);
        boolean needsAllTheTime = permitted && unlocated > 0 && !AreaMap.hasBackgroundPermission(this);
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
        areaLine.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) {
            if (mapping.getVisibility() == View.VISIBLE) closeSheet();
            return;
        }

        double[] here = permitted ? AreaMap.here(this) : null;
        String shown = AreaMap.version() + "/" + (here == null ? "-" : Math.round(here[0] * 2000) + ","
                + Math.round(here[1] * 2000));
        if (shown.equals(shownAreas)) return;
        shownAreas = shown;
        areaHere = here;
        areaMap.show(cells, here);
        List<AreaMap.Cell> ranked = AreaMap.ranked(cells);
        // Until a square is picked, the best one is shown, following it as the ranking changes.
        if (!pickedArea && !ranked.isEmpty()) areaMap.select(ranked.get(0));
        if (areaMap.selected() != null) {
            showArea(areaMap.selected());
        } else {
            areaDetail.removeAllViews();
            areaLine.setText("Best areas: none yet  ›");
        }
    }

    /** The chosen square in two quiet lines, its place then its offers and pay, and a way to see it. */
    private void showArea(AreaMap.Cell cell) {
        areaDetail.removeAllViews();
        int rank = areaMap.rank(cell);
        String where = AreaMap.from(areaHere, cell);
        TextView place = ui.text((rank > 0 ? "#" + rank : "Not ranked yet") + (where.isEmpty() ? "" : " · " + where),
                16, ui.ink, true);
        place.setGravity(Gravity.CENTER_HORIZONTAL);
        place.setPadding(0, ui.dp(12), 0, 0);
        areaDetail.addView(place, Ui.matchWidth());
        areaLine.setText("Best area " + place.getText() + "  ›");
        areaLine.setContentDescription("Best area " + place.getText() + ". Opens the map.");
        TextView facts = ui.text(cell.offers + (cell.offers == 1 ? " offer" : " offers")
                + (cell.ranked() ? " · " + cell.perMile() : " · ranked after " + AreaMap.MIN_OFFERS + " with miles")
                + " · avg " + DecisionLog.money(cell.averagePayCents()) + " · last " + when(cell.lastAt), 13,
                ui.inkSecondary, false);
        facts.setGravity(Gravity.CENTER_HORIZONTAL);
        areaDetail.addView(facts, Ui.matchWidth());
        Button maps = ui.button("Open in Maps", false, () -> open(new Intent(Intent.ACTION_VIEW, Uri.parse(
                String.format(Locale.US, "geo:%.4f,%.4f?q=%.4f,%.4f(Offer area)", cell.latitude(),
                        cell.longitude(), cell.latitude(), cell.longitude())))));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = ui.dp(10);
        areaDetail.addView(maps, params);
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
                .setMessage("This removes the recorded decisions from this phone. It does not change your rules.")
                .setPositiveButton("Clear", (dialog, which) -> {
                    DecisionLog.clear(this);
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
