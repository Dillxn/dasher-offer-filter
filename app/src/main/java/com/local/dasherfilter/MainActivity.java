package com.local.dasherfilter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
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
import android.util.Patterns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The app's one screen, with a tab bar that swaps what fills it: Home (the filter picture, Pause/Resume, any setup
 * problem, the latest offer), Offers (chart and history), Rules, and More (setup, reports, updates). Pause and
 * Resume take effect at once; Save keeps the on/paused state.
 */
public final class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 13;
    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000");
    private static final int HOME = 0;
    private static final int OFFERS = 1;
    private static final int RULES = 2;
    private static final int MORE = 3;
    private static final String[] TAB_NAMES = {"Home", "Offers", "Rules", "More"};
    private static final Glyph.Shape[] TAB_ICONS = {
            Glyph.Shape.FUNNEL, Glyph.Shape.CHART, Glyph.Shape.SLIDERS, Glyph.Shape.DOTS};
    private static final String SHOWN_TAB = "tab";
    private static final int HISTORY_ROWS = 10;
    private static final int MORE_HISTORY_ROWS = 60;
    private static final long DAY_MS = 86_400_000L;
    private static final Pattern TOO_MANY_STOPS = Pattern.compile("(\\d+) stops exceeds maximum (\\d+)");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };
    private Ui ui;

    private TextView pageTitle;
    private final ScrollView[] pages = new ScrollView[TAB_NAMES.length];
    private final TextView[] tabs = new TextView[TAB_NAMES.length];
    private LinearLayout tabBar;
    private int tab = -1;

    private FilterHeroView hero;
    private TextView stateTitle;
    private TextView stateDetail;
    private Button masterButton;
    private int shownState;
    private LinearLayout problems;
    private Readiness screenReading;
    private Readiness backgroundOffers;
    private Readiness offerAlerts;
    private LinearLayout latestCard;
    private LinearLayout latest;
    private DecisionLog.Entry shownLatest;
    private LinearLayout routeCard;
    private TextView routeNote;

    private DecisionChartView chart;
    private View legend;
    private LinearLayout selectionPanel;
    private LinearLayout selectionDetail;
    private Button reportSelected;
    private LinearLayout emptyHistory;
    private LinearLayout historyCard;
    private LinearLayout history;
    private Button moreHistory;
    private boolean showAllHistory;
    private long shownHistoryVersion = -1;
    private List<DecisionLog.Entry> recentEntries = java.util.Collections.emptyList();
    private String shownHero = "";

    private EditText flat;
    private EditText mile;
    private EditText minute;
    private EditText stop;
    private EditText maxStops;
    private Switch rising;
    private TextView baselineNote;
    private RuleMeterView ruleMeter;
    private MinimumsStarView minimums;
    private TextView minimumsNote;
    private Mascot.Figure waitingMascot;
    private Mascot.Figure footerMascot;

    private EditText reportEmail;
    private EditText reportToken;
    private TextView reportStatus;
    private Button stopReports;
    private Switch diagnostics;
    private TextView updateStatus;
    private Button allowInstalls;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        OfferAlerts.ensureChannel(this);
        FilterSettings saved = FilterStore.load(this);

        LinearLayout root = ui.column();
        root.setBackgroundColor(ui.page);
        pageTitle = ui.text("", 24, ui.ink, true);
        pageTitle.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(10));
        pageTitle.setBackground(new Scenery(Scenery.Part.SKY, ui));
        if (Build.VERSION.SDK_INT >= 28) pageTitle.setAccessibilityHeading(true);
        root.addView(pageTitle, Ui.matchWidth());
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(new Scenery(Scenery.Part.GROUND, ui));
        root.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout home = addPage(frame, HOME);
        addStatusCard(home);
        addLatestCard(home);
        addRouteCard(home);
        addActivityCards(addPage(frame, OFFERS));
        addRulesCards(addPage(frame, RULES), saved);
        addMoreCards(addPage(frame, MORE));
        tabBar = tabBar();
        root.addView(tabBar, Ui.matchWidth());

        setContentView(root);
        styleSystemBars();
        fitToSystemBars(root);
        showTab(state == null ? HOME : state.getInt(SHOWN_TAB, HOME));
        Updater.schedule(this);
        refresh();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt(SHOWN_TAB, tab);
    }

    /** Back from another tab returns Home, as in any app with a tab bar; back from Home leaves. */
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (tab != HOME) showTab(HOME);
        else super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        // Sound left turned down by a decline the screen reader could not finish is put back here too.
        if (!OfferFilterService.isConnected()) OfferSilencer.restore(this);
        Updater.foreground(this);
        handler.removeCallbacks(refresh);
        handler.post(refresh);
        Updater.check(this, false, null);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        Updater.background(this);
        super.onPause();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refresh();
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            toast(granted ? "Alerts allowed." : "Alerts not allowed.");
        }
    }

    // ---- Layout ----

    /** One tab's scrolling page inside {@code frame}; returns the column the caller fills with cards. */
    private LinearLayout addPage(FrameLayout frame, int index) {
        ScrollView scroll = new ScrollView(this);
        scroll.setVisibility(View.GONE);
        LinearLayout page = ui.column();
        // Room at the end, so the last card scrolls clear of the drawn road and hills.
        page.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(120));
        scroll.addView(page, Ui.matchWidth());
        frame.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        pages[index] = scroll;
        return page;
    }

    /** The bottom bar: an icon and a word per tab, the shown one marked by a tinted pill behind its icon. */
    private LinearLayout tabBar() {
        LinearLayout bar = ui.column();
        bar.setBackgroundColor(ui.surface);
        View edge = new View(this);
        edge.setBackgroundColor(ui.gridline);
        bar.addView(edge, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ui.dp(1))));
        LinearLayout items = ui.row();
        for (int i = 0; i < tabs.length; i++) {
            TextView item = ui.text(TAB_NAMES[i], 12, ui.inkSecondary, false);
            item.setGravity(Gravity.CENTER);
            item.setSingleLine(true);
            item.setMinHeight(ui.dp(64));
            item.setPadding(0, ui.dp(8), 0, ui.dp(8));
            item.setCompoundDrawablePadding(ui.dp(4));
            item.setBackground(new RippleDrawable(ColorStateList.valueOf(ui.selectionWash), null,
                    new ColorDrawable(0xFFFFFFFF)));
            int index = i;
            item.setOnClickListener(tapped -> showTab(index));
            tabs[i] = item;
            items.addView(item, Ui.weighted());
        }
        bar.addView(items, Ui.matchWidth());
        return bar;
    }

    private Drawable tabIcon(Glyph.Shape shape, boolean shown) {
        Drawable pill = ui.rounded(shown ? (ui.accent & 0x00FFFFFF) | (ui.dark ? 0x4D000000 : 0x29000000) : 0, 0, 15);
        LayerDrawable icon = new LayerDrawable(new Drawable[] {
                pill, new Glyph(shape, shown ? (ui.dark ? ui.ink : ui.accent) : ui.inkSecondary, ui.dp(22))});
        icon.setLayerSize(0, ui.dp(56), ui.dp(30));
        icon.setLayerSize(1, ui.dp(22), ui.dp(22));
        icon.setLayerGravity(0, Gravity.CENTER);
        icon.setLayerGravity(1, Gravity.CENTER);
        icon.setBounds(0, 0, ui.dp(56), ui.dp(30));
        return icon;
    }

    /** Swaps the page in place, like a single-page app; each page keeps its own scroll position. */
    private void showTab(int index) {
        if (index < 0 || index >= pages.length) index = HOME;
        boolean changed = index != tab;
        tab = index;
        for (int i = 0; i < pages.length; i++) {
            boolean shown = i == index;
            pages[i].setVisibility(shown ? View.VISIBLE : View.GONE);
            tabs[i].setSelected(shown);
            tabs[i].setTextColor(shown ? ui.ink : ui.inkSecondary);
            tabs[i].setTypeface(shown ? Ui.MEDIUM : Typeface.DEFAULT);
            tabs[i].setCompoundDrawablesRelative(null, tabIcon(TAB_ICONS[i], shown), null, null);
        }
        pageTitle.setText(index == HOME ? "Offer Filter" : TAB_NAMES[index]);
        View focused = getCurrentFocus();
        if (focused != null && !focused.isShown()) focused.clearFocus();
        if (changed) {
            pages[index].setAlpha(0f);
            pages[index].animate().alpha(1f).setDuration(150);
        }
    }

    private void addStatusCard(LinearLayout page) {
        LinearLayout card = ui.card(page, null);
        hero = new FilterHeroView(this, ui);
        card.addView(hero, Ui.matchWidth());
        stateTitle = ui.text("", 20, ui.ink, true);
        stateTitle.setPadding(0, ui.dp(8), 0, 0);
        card.addView(stateTitle);
        stateDetail = ui.note("");
        card.addView(stateDetail);
        masterButton = ui.addButton(card, "", true, this::toggleAutoDecline);

        problems = ui.column();
        problems.setPadding(0, ui.dp(6), 0, 0);
        card.addView(problems);
        screenReading = new Readiness(problems, "Screen reading is off",
                () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        backgroundOffers = new Readiness(problems, "Background offers are off", this::openNotificationAccess);
        offerAlerts = new Readiness(problems, "Alerts are blocked", this::configureOfferAlerts);
    }

    /** The newest offer on Home, one line of what happened; tapping it opens Offers. */
    private void addLatestCard(LinearLayout page) {
        latestCard = ui.card(page, "Latest offer");
        latest = ui.column();
        latestCard.addView(latest, Ui.matchWidth());
        latestCard.setVisibility(View.GONE);
    }

    /** Shown only while a route is known: add-on offers are judged against it. */
    private void addRouteCard(LinearLayout page) {
        routeCard = ui.card(page, null);
        LinearLayout row = ui.row();
        routeNote = ui.text("", 13, ui.inkSecondary, false);
        routeNote.setCompoundDrawablesRelative(new Glyph(Glyph.Shape.ROAD, ui.inkSecondary, ui.dp(18)), null, null,
                null);
        routeNote.setCompoundDrawablePadding(ui.dp(8));
        row.addView(routeNote, Ui.weighted());
        row.addView(ui.button("Forget", false, () -> {
            ActiveRouteStore.clear(this);
            refresh();
        }));
        routeCard.addView(row);
        routeCard.setVisibility(View.GONE);
    }

    private void addActivityCards(LinearLayout page) {
        LinearLayout card = ui.card(page, null);

        chart = new DecisionChartView(this, ui);
        chart.setOnSelect(entry -> {
            showSelection(entry);
            selectionPanel.setVisibility(View.VISIBLE);
        });
        card.addView(chart, Ui.matchWidth());
        legend = legend();
        card.addView(legend);

        selectionPanel = ui.column();
        selectionPanel.setVisibility(View.GONE);
        selectionDetail = ui.column();
        selectionPanel.addView(selectionDetail, Ui.matchWidth());
        reportSelected = ui.addButton(selectionPanel, "Report this offer", false, () -> {
            DecisionLog.Entry selected = chart.selectedEntry();
            if (selected != null) reportOffer(selected);
        });
        LinearLayout.LayoutParams panelParams = Ui.matchWidth();
        panelParams.topMargin = ui.dp(10);
        card.addView(selectionPanel, panelParams);
        emptyHistory = ui.row();
        waitingMascot = new Mascot.Figure(this, ui, Mascot.Mood.HAPPY, 72);
        emptyHistory.addView(waitingMascot);
        TextView waiting = ui.note("No offers yet. Each one Dasher shows appears here with what the filter did.");
        waiting.setPadding(ui.dp(12), 0, 0, 0);
        emptyHistory.addView(waiting, Ui.weighted());
        card.addView(emptyHistory);

        historyCard = ui.card(page, null);
        history = ui.column();
        historyCard.addView(history);
        moreHistory = ui.addButton(historyCard, "Show more", false, () -> {
            showAllHistory = !showAllHistory;
            shownHistoryVersion = -1;
            refreshHistory();
        });
    }

    /** Symbol and word for each status, plus the requirement tick, so no meaning rests on color alone. */
    private LinearLayout legend() {
        // One line normally; two with a large font, so no entry is cut off.
        LinearLayout legend = ui.column();
        legend.setPadding(0, ui.dp(6), 0, 0);
        LinearLayout line = ui.row();
        legend.addView(line);
        for (OfferRule.Result result : new OfferRule.Result[] {
                OfferRule.Result.KEEP, OfferRule.Result.DECLINE, OfferRule.Result.REVIEW}) {
            if (result == OfferRule.Result.REVIEW && ui.largeText()) {
                line = ui.row();
                line.setPadding(0, ui.dp(4), 0, 0);
                legend.addView(line);
            }
            line.addView(ui.badge(result, 14));
            TextView word = ui.text(Ui.resultLabel(result), 12, ui.inkSecondary, false);
            word.setPadding(ui.dp(4), 0, ui.dp(12), 0);
            line.addView(word);
        }
        View tick = new View(this);
        tick.setBackgroundColor(ui.ink);
        line.addView(tick, new LinearLayout.LayoutParams(ui.dp(14), ui.dp(2)));
        TextView needed = ui.text("Needed", 12, ui.inkSecondary, false);
        needed.setPadding(ui.dp(4), 0, 0, 0);
        line.addView(needed);
        return legend;
    }

    private void addRulesCards(LinearLayout page, FilterSettings saved) {
        minimums = new MinimumsStarView(this, ui);
        LinearLayout star = ui.card(page, "Minimums");
        LinearLayout.LayoutParams starParams = Ui.matchWidth();
        starParams.topMargin = ui.dp(8);
        star.addView(minimums, starParams);
        minimumsNote = ui.note("");
        star.addView(minimumsNote);
        ruleMeter = new RuleMeterView(this, ui);
        ui.card(page, null).addView(ruleMeter, Ui.matchWidth());

        LinearLayout body = ui.card(page, null);
        LinearLayout first = fieldRow();
        flat = ui.field(cell(first), "Minimum pay ($)", money(saved.flatCents), true, Glyph.Shape.COIN);
        maxStops = ui.field(cell(first), "Max stops (1 order = 2)", Integer.toString(saved.maxStops), false,
                Glyph.Shape.STOPS);
        body.addView(first);
        LinearLayout second = fieldRow();
        mile = ui.field(cell(second), "Per mile ($)", money(saved.perMileCents), true, Glyph.Shape.ROAD);
        minute = ui.field(cell(second), "Per minute ($)", money(saved.perMinuteCents), true, Glyph.Shape.CLOCK);
        body.addView(second);
        LinearLayout third = fieldRow();
        stop = ui.field(cell(third), "Per extra stop ($)", money(saved.extraStopCents), true, Glyph.Shape.PIN);
        cell(third);
        body.addView(third);
        TextView zero = ui.text("0 turns a rule off.", 12, ui.inkSecondary, false);
        zero.setPadding(0, ui.dp(6), 0, 0);
        body.addView(zero);
        body.addView(ui.divider());

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

    /** Two fields side by side, or one above the other when a large font would crowd them. */
    private LinearLayout fieldRow() {
        return ui.largeText() ? ui.column() : ui.row();
    }

    /** A half-width column inside {@code row}, or a full-width one when the row is stacked. */
    private LinearLayout cell(LinearLayout row) {
        LinearLayout cell = ui.column();
        if (row.getOrientation() == LinearLayout.VERTICAL) {
            row.addView(cell, Ui.matchWidth());
            return cell;
        }
        LinearLayout.LayoutParams params = Ui.weighted();
        params.setMarginEnd(row.getChildCount() == 0 ? ui.dp(6) : 0);
        params.setMarginStart(row.getChildCount() == 0 ? 0 : ui.dp(6));
        row.addView(cell, params);
        return cell;
    }

    private void addMoreCards(LinearLayout page) {
        LinearLayout body = ui.card(page, "Setup");
        ui.buttonPair(body,
                ui.button("Accessibility", false, () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))),
                ui.button("Notification access", false, this::openNotificationAccess));
        ui.buttonPair(body,
                ui.button("Alert settings", false, this::configureOfferAlerts),
                ui.button("DoorDash channel", false, this::openDoorDashChannel));
        Switch mute = ui.toggle(body, "Mute Dasher's ring while declining", FilterStore.silenceWhileDeclining(this));
        mute.setOnCheckedChangeListener((view, on) -> FilterStore.setSilenceWhileDeclining(this, on));

        body = ui.card(page, "Reports");
        TextView caption = ui.text("Your email", 13, ui.inkSecondary, false);
        caption.setPadding(0, ui.dp(10), 0, ui.dp(4));
        body.addView(caption);
        reportEmail = new EditText(this);
        reportEmail.setId(View.generateViewId());
        caption.setLabelFor(reportEmail.getId());
        reportEmail.setSingleLine(true);
        reportEmail.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        reportEmail.setHint("you@example.com");
        reportEmail.setText(FilterStore.reportEmail(this));
        ui.styleField(reportEmail);
        body.addView(reportEmail, Ui.matchWidth());
        ui.addButton(body, "Email report", true, this::emailReport);
        diagnostics = ui.toggle(body, "Capture full screen text (30 min)", DiagnosticLog.isEnabled(this));
        diagnostics.setOnCheckedChangeListener((view, on) -> DiagnosticLog.setEnabled(this, on));
        ui.buttonPair(body,
                ui.button("Share report", false, this::shareReport),
                ui.button("Clear history", false, this::confirmClearHistory));
        body.addView(ui.divider());
        addAutomaticReports(body);

        body = ui.card(page, "Updates");
        Switch updates = ui.toggle(body, "Automatic updates", Updater.enabled(this));
        updates.setOnCheckedChangeListener((view, on) -> {
            Updater.setEnabled(this, on);
            if (on) Updater.check(this, false, null);
        });
        updateStatus = ui.text("", 13, ui.inkSecondary, false);
        body.addView(updateStatus);
        ui.addButton(body, "Check for update", false, () -> Updater.check(this, true, null));
        allowInstalls = ui.addButton(body, "Allow installs", false, () -> open(new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))));

        footerMascot = new Mascot.Figure(this, ui, Mascot.Mood.HAPPY, 56);
        LinearLayout.LayoutParams mascotParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mascotParams.gravity = Gravity.CENTER_HORIZONTAL;
        mascotParams.topMargin = ui.dp(8);
        page.addView(footerMascot, mascotParams);
        TextView footer = ui.text("Offer Filter v" + Updater.version(this) + " · Not a DoorDash app.", 12,
                ui.inkSecondary, false);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setPadding(0, ui.dp(4), 0, 0);
        page.addView(footer, Ui.matchWidth());
    }

    /** A GitHub token turns on reports of unreadable offers and errors, filed where the fixer picks them up. */
    private void addAutomaticReports(LinearLayout body) {
        TextView caption = ui.text("Automatic reports (GitHub token)", 13, ui.inkSecondary, false);
        caption.setPadding(0, ui.dp(8), 0, ui.dp(4));
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
        ui.buttonPair(body,
                ui.button("Save token", false, this::saveReportToken),
                ui.button("Send test", false, this::sendTestReport));
        stopReports = ui.addButton(body, "Turn off reports", false, this::confirmStopReports);
    }

    // ---- State ----

    private void refresh() {
        if (stateTitle == null) return;
        FilterSettings saved = FilterStore.load(this);
        int state = saved.enabled ? 1 : saved.hasAnyRule() ? 2 : 3;
        if (state != shownState) ui.style(masterButton, state != 1);
        shownState = state;
        if (saved.enabled) {
            stateTitle.setText("Auto-decline on");
            stateDetail.setText(saved.brief());
            masterButton.setText("Pause auto-decline");
        } else if (saved.hasAnyRule()) {
            stateTitle.setText("Auto-decline paused");
            stateDetail.setText(saved.brief());
            masterButton.setText("Resume auto-decline");
        } else {
            stateTitle.setText("Auto-decline off");
            stateDetail.setText("Set a minimum pay or rate to start.");
            masterButton.setText("Set up rules");
        }

        screenReading.update(OfferFilterService.isConnected());
        backgroundOffers.update(OfferNotificationService.isConnected());
        offerAlerts.update(OfferAlerts.canNotify(this));

        refreshHistory();
        FilterHeroView.State heroState = saved.enabled ? FilterHeroView.State.ON
                : saved.hasAnyRule() ? FilterHeroView.State.PAUSED : FilterHeroView.State.OFF;
        refreshHero(heroState);
        waitingMascot.setMood(Mascot.moodOf(heroState));
        footerMascot.setMood(Mascot.moodOf(heroState));
        baselineNote.setText(adaptiveNote(saved));
        updateStatus.setText(Updater.status(this));
        allowInstalls.setVisibility(getPackageManager().canRequestPackageInstalls() ? View.GONE : View.VISIBLE);
        OfferSnapshot route = ActiveRouteStore.load(this);
        routeCard.setVisibility(route == null ? View.GONE : View.VISIBLE);
        if (route != null) routeNote.setText("Active route: " + route.summary());
        if (diagnostics.isChecked() && !DiagnosticLog.isEnabled(this)) diagnostics.setChecked(false);
        boolean reporting = ReportOutbox.enabled(this);
        reportStatus.setText(ReportOutbox.status(this));
        reportToken.setHint(reporting ? "Token saved · paste to replace" : "github_pat_…");
        reportSelected.setVisibility(reporting ? View.VISIBLE : View.GONE);
        stopReports.setVisibility(reporting ? View.VISIBLE : View.GONE);
    }

    private void refreshHistory() {
        long version = DecisionLog.version();
        if (version == shownHistoryVersion) return;
        shownHistoryVersion = version;
        List<DecisionLog.Entry> recent = DecisionLog.recent(this, DecisionLog.MAX_ENTRIES);
        recentEntries = recent;
        chart.setEntries(recent);
        boolean empty = recent.isEmpty();
        chart.setVisibility(empty ? View.GONE : View.VISIBLE);
        legend.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyHistory.setVisibility(empty ? View.VISIBLE : View.GONE);
        historyCard.setVisibility(empty ? View.GONE : View.VISIBLE);
        DecisionLog.Entry newest = empty ? null : recent.get(0);
        latestCard.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (newest != shownLatest) {
            shownLatest = newest;
            latest.removeAllViews();
            if (newest != null) latest.addView(offerRow(newest, false));
        }
        DecisionLog.Entry selected = chart.selectedEntry();
        selectionPanel.setVisibility(selected == null ? View.GONE : View.VISIBLE);
        if (selected != null) showSelection(selected);
        history.removeAllViews();
        int rows = Math.min(showAllHistory ? MORE_HISTORY_ROWS : HISTORY_ROWS, recent.size());
        for (int i = 0; i < rows; i++) history.addView(historyRow(recent.get(i)));
        moreHistory.setVisibility(recent.size() > HISTORY_ROWS ? View.VISIBLE : View.GONE);
        moreHistory.setText(showAllHistory ? "Show less" : "Show more");
        updateMeter();
    }

    /** The filter picture with the last 24 hours' counts; redrawn only when something it shows changed. */
    private void refreshHero(FilterHeroView.State state) {
        long since = System.currentTimeMillis() - DAY_MS;
        int passed = 0;
        int filtered = 0;
        int review = 0;
        for (DecisionLog.Entry entry : recentEntries) {
            if (entry.at < since) continue;
            if (entry.result == OfferRule.Result.KEEP) passed++;
            else if (entry.result == OfferRule.Result.DECLINE && actedOn(entry.action)) filtered++;
            else review++;
        }
        String shown = state + "/" + passed + "/" + filtered + "/" + review;
        if (shown.equals(shownHero)) return;
        shownHero = shown;
        hero.set(state, passed, filtered, review);
    }

    /** A failing offer the app did something about; one paused, refused or taken over was left to the user. */
    private static boolean actedOn(DecisionLog.Action action) {
        return action == DecisionLog.Action.DECLINE_TAPPED || action == DecisionLog.Action.CONFIRMATION_TAPPED
                || action == DecisionLog.Action.NOTIFICATION_DECLINE_SENT
                || action == DecisionLog.Action.NOTIFICATION_HIDDEN;
    }

    /** "Last accepted $14.20 · best $0.59/min, $2.37/mi, $7.10/stop", or what it waits for. */
    private static String adaptiveNote(FilterSettings saved) {
        if (saved.lastAcceptedCents <= 0 && saved.best.isEmpty()) {
            return "Rises with the offers you accept. None yet.";
        }
        String last = saved.lastAcceptedCents > 0 ? "Last accepted " + DecisionLog.money(saved.lastAcceptedCents) : "";
        String best = saved.best.isEmpty() ? "" : "best " + saved.best.summary();
        return last + (!last.isEmpty() && !best.isEmpty() ? " · " : "") + best;
    }

    /** Redraws the rules picture from the rules as typed (unsaved), with the latest fully read offer. */
    private void updateMeter() {
        if (ruleMeter == null) return;
        FilterSettings saved = FilterStore.load(this);
        FilterSettings typed = new FilterSettings(saved.enabled, lenientCents(flat, saved.flatCents),
                lenientCents(mile, saved.perMileCents), lenientCents(minute, saved.perMinuteCents),
                lenientCents(stop, saved.extraStopCents), lenientStops(saved.maxStops), rising.isChecked(),
                saved.lastAcceptedCents, saved.best);
        OfferSnapshot example = exampleOffer();
        ruleMeter.show(typed, example);
        minimums.show(typed, example);
        minimumsNote.setText(minimums.caption());
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

    /** The tapped chart column, drawn as an offer card with its reason and what the app did. */
    private void showSelection(DecisionLog.Entry entry) {
        selectionDetail.removeAllViews();
        LinearLayout heading = ui.row();
        heading.addView(ui.badge(entry.result, 20));
        TextView title = ui.text(Ui.resultLabel(entry.result) + " · " + when(entry.at)
                + (entry.addOn ? " · add-on" : ""), 14, ui.ink, true);
        title.setPadding(ui.dp(8), 0, 0, 0);
        heading.addView(title, Ui.weighted());
        selectionDetail.addView(heading);
        selectionDetail.addView(offerDetail(entry, true));
    }

    /**
     * The drawn offer (pay against needed, the route), then the reason, what the app did, and the exact lines read.
     *
     * @param withReason false where the reason is already shown right above
     */
    private LinearLayout offerDetail(DecisionLog.Entry entry, boolean withReason) {
        LinearLayout detail = ui.column();
        detail.setBackground(ui.rounded(ui.fieldFill, 0, 12));
        detail.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(10));
        OfferCardView card = new OfferCardView(this, ui);
        card.show(entry);
        detail.addView(card, Ui.matchWidth());
        if (withReason) {
            TextView reason = ui.text(plainReason(entry), 15, ui.ink, true);
            reason.setPadding(0, ui.dp(8), 0, 0);
            detail.addView(reason);
        }
        TextView action = ui.text(entry.action.label
                + (entry.source == DecisionLog.Source.SCREEN ? " · on screen" : " · from notification")
                + (entry.autoDecline ? "" : " · while paused"), 13, ui.inkSecondary, false);
        action.setPadding(0, ui.dp(4), 0, 0);
        detail.addView(action);
        if (!entry.evidence.isEmpty()) {
            TextView read = ui.text("Read: " + String.join("  ·  ", entry.evidence), 12, ui.inkMuted, false);
            read.setPadding(0, ui.dp(4), 0, 0);
            read.setTextIsSelectable(true);
            detail.addView(read);
        }
        LinearLayout.LayoutParams params = Ui.matchWidth();
        params.topMargin = ui.dp(8);
        detail.setLayoutParams(params);
        return detail;
    }

    private View historyRow(DecisionLog.Entry entry) {
        return offerRow(entry, true);
    }

    /**
     * Badge, "$7.90 · needed $10.80", time, and the reason in plain words.
     *
     * @param opens true to open the drawn offer in place on a tap; false (Home) to go to Offers instead
     */
    private View offerRow(DecisionLog.Entry entry, boolean opens) {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.TOP);
        row.setPadding(0, ui.dp(8), 0, ui.dp(8));
        TextView badge = ui.badge(entry.result, 24);
        ((LinearLayout.LayoutParams) badge.getLayoutParams()).topMargin = ui.dp(2);
        row.addView(badge);
        LinearLayout texts = ui.column();
        texts.setPadding(ui.dp(12), 0, 0, 0);
        // With a large font the time moves under the amounts instead of squeezing them.
        LinearLayout top = ui.largeText() ? ui.column() : ui.row();
        top.addView(ui.text(headline(entry), 15, ui.ink, true),
                ui.largeText() ? Ui.matchWidth() : Ui.weighted());
        top.addView(ui.text(when(entry.at), 13, ui.inkSecondary, false));
        texts.addView(top);
        texts.addView(ui.text(plainReason(entry), 13, ui.inkSecondary, false));
        row.addView(texts, Ui.weighted());
        row.setClickable(true);
        row.setContentDescription(Ui.resultLabel(entry.result) + ", " + headline(entry) + ", "
                + plainReason(entry));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(ui.selectionWash), null,
                ui.rounded(0xFFFFFFFF, 0, 8)));
        if (!opens) {
            TextView chevron = ui.text("›", 22, ui.inkSecondary, false);
            chevron.setPadding(ui.dp(10), 0, 0, 0);
            chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams centered = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            centered.gravity = Gravity.CENTER_VERTICAL;
            row.addView(chevron, centered);
            row.setOnClickListener(tapped -> showTab(OFFERS));
            return row;
        }
        // The drawn offer appears on the first tap; built then, so a long history stays cheap.
        LinearLayout expansion = ui.column();
        expansion.setVisibility(View.GONE);
        texts.addView(expansion, Ui.matchWidth());
        Button report = ui.addButton(texts, "Report this offer", false, () -> reportOffer(entry));
        report.setVisibility(View.GONE);
        row.setOnClickListener(tapped -> {
            boolean expand = expansion.getVisibility() != View.VISIBLE;
            if (expand && expansion.getChildCount() == 0) expansion.addView(offerDetail(entry, false));
            expansion.setVisibility(expand ? View.VISIBLE : View.GONE);
            report.setVisibility(expand && ReportOutbox.enabled(this) ? View.VISIBLE : View.GONE);
        });
        return row;
    }

    /** "$7.90 · needed $10.80", or "Pay not read". */
    private static String headline(DecisionLog.Entry entry) {
        String pay = entry.facts.payCents == null ? "Pay unknown" : DecisionLog.money(entry.facts.payCents);
        String needed = entry.requiredCents > 0 && entry.requiredCents < Long.MAX_VALUE
                ? " · needed " + DecisionLog.money(entry.requiredCents) : "";
        return pay + needed + (entry.addOn ? " · add-on" : "");
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
        if (reason.startsWith("must beat last accepted payout ")) {
            return "Not above last accepted " + reason.substring("must beat last accepted payout ".length());
        }
        if (reason.startsWith("must match best accepted ")) {
            return "Below your best accepted " + reason.substring("must match best accepted ".length());
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

    // ---- Actions ----

    /** Pause, Resume, or with no saved rule, off to the Rules tab to add one. */
    private void toggleAutoDecline() {
        FilterSettings saved = FilterStore.load(this);
        if (saved.enabled) pause();
        else if (saved.hasAnyRule()) resume();
        else showTab(RULES);
    }

    /** Persists auto-decline off immediately, keeping every saved rule. */
    private void pause() {
        FilterStore.save(this, FilterStore.load(this).withEnabled(false));
        rulesChanged();
        toast("Paused. Nothing will be declined.");
    }

    /** Saves the rules on screen and turns auto-decline on. */
    private void resume() {
        try {
            FilterSettings next = readRules(true);
            if (!next.hasAnyRule()) {
                toast("Add a rule first.");
                showTab(RULES);
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
                    : "Rules saved. Auto-decline stays paused until you Resume it on Home.");
        } catch (IllegalArgumentException error) {
            toast(error.getMessage());
        }
    }

    private void rulesChanged() {
        OfferNotificationService.rulesChanged();
        OfferFilterService.requestCheckFromNotification();
        refresh();
    }

    /** The rules on screen with the given on/paused state. @throws IllegalArgumentException with a user message */
    private FilterSettings readRules(boolean enabled) {
        String stops = maxStops.getText().toString().trim();
        if (stops.isEmpty()) stops = "0";
        if (!stops.matches("[0-9]{1,2}")) throw new IllegalArgumentException("Maximum stops must be 0 through 99.");
        return new FilterSettings(enabled, parseCents(flat), parseCents(mile), parseCents(minute), parseCents(stop),
                Integer.parseInt(stops), rising.isChecked(), FilterStore.load(this).lastAcceptedCents,
                FilterStore.load(this).best);
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
            toast("Add a GitHub token under More first.");
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
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Opens the user's mail app addressed to themselves, with the report as the body. They press Send. */
    private void emailReport() {
        String address = reportEmail.getText().toString().trim();
        if (!Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
            toast("Enter your email first.");
            reportEmail.requestFocus();
            return;
        }
        FilterStore.setReportEmail(this, address);
        Intent send = reportIntent()
                .putExtra(Intent.EXTRA_EMAIL, new String[] {address});
        send.setSelector(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")));
        open(send);
    }

    private void shareReport() {
        open(Intent.createChooser(reportIntent(), "Share Offer Filter report"));
    }

    private Intent reportIntent() {
        return new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, DiagnosticLog.reportSubject(this))
                .putExtra(Intent.EXTRA_TEXT, DiagnosticLog.report(this));
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
            window.setNavigationBarColor(ui.surface);
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
     * Android 15 draws apps targeting API 35 edge to edge, so the screen must keep itself clear of the status bar,
     * navigation bar, cutouts, and keyboard; the tab bar's own color runs on under the navigation bar. Earlier
     * versions already lay the window out inside the system bars. While the keyboard is up (Android 11 and later
     * report it), the tab bar steps aside so the field being typed in keeps the room.
     */
    private void fitToSystemBars(View content) {
        if (Build.VERSION.SDK_INT < 30) return;
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            int keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom;
            boolean typing = keyboard > 0 || insets.isVisible(WindowInsets.Type.ime());
            tabBar.setVisibility(typing ? View.GONE : View.VISIBLE);
            if (Build.VERSION.SDK_INT >= 35) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, typing ? Math.max(keyboard, bars.bottom) : 0);
                tabBar.setPadding(0, 0, 0, bars.bottom);
            }
            return insets;
        });
    }

    /** One setup problem with a Fix button; the whole row is hidden while that part is working. */
    private final class Readiness {
        private final LinearLayout row;

        Readiness(LinearLayout parent, String problem, Runnable onFix) {
            row = ui.row();
            row.setPadding(0, ui.dp(6), 0, 0);
            row.addView(ui.badge("!", Ui.CRITICAL, 22));
            TextView text = ui.text(problem, 15, ui.ink, true);
            text.setPadding(ui.dp(10), 0, ui.dp(8), 0);
            row.addView(text, Ui.weighted());
            Button fix = ui.button("Fix", true, onFix);
            fix.setMinWidth(ui.dp(72));
            row.addView(fix);
            parent.addView(row);
        }

        void update(boolean ready) {
            row.setVisibility(ready ? View.GONE : View.VISIBLE);
        }
    }
}
