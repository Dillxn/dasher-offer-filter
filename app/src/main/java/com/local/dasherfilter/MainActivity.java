package com.local.dasherfilter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Patterns;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
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
 * The app's one screen, kept short: status with Pause/Resume and any setup problem, then recent offers. Rules and
 * setup/help fold away until tapped. Pause and Resume take effect at once; Save keeps the on/paused state.
 */
public final class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 13;
    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000");
    private static final int HISTORY_ROWS = 5;
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

    private TextView stateBadge;
    private TextView stateTitle;
    private TextView stateDetail;
    private Button masterButton;
    private int shownState;
    private LinearLayout problems;
    private Readiness screenReading;
    private Readiness backgroundOffers;
    private Readiness offerAlerts;

    private TextView activitySummary;
    private DecisionChartView chart;
    private View legend;
    private TextView selectionDetail;
    private LinearLayout history;
    private Button moreHistory;
    private boolean showAllHistory;
    private long shownHistoryVersion = -1;

    private EditText flat;
    private EditText mile;
    private EditText minute;
    private EditText stop;
    private EditText maxStops;
    private Switch rising;
    private TextView baselineNote;

    private EditText reportEmail;
    private Switch diagnostics;
    private TextView updateStatus;
    private Button allowInstalls;
    private LinearLayout routeRow;
    private TextView routeNote;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        OfferAlerts.ensureChannel(this);
        FilterSettings saved = FilterStore.load(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ui.page);
        scroll.setClipToPadding(false);
        LinearLayout page = ui.column();
        page.setPadding(ui.dp(16), ui.dp(20), ui.dp(16), ui.dp(24));
        scroll.addView(page, Ui.matchWidth());

        LinearLayout header = ui.row();
        header.setPadding(0, 0, 0, ui.dp(14));
        header.addView(ui.text("Offer Filter", 24, ui.ink, true), Ui.weighted());
        header.addView(ui.text("v" + Updater.version(this), 13, ui.inkSecondary, false));
        page.addView(header);
        addStatusCard(page);
        addActivityCard(page);
        addRulesCard(page, saved);
        addSetupCard(page);
        TextView footer = ui.text("Not a DoorDash app.", 12, ui.inkSecondary, false);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        page.addView(footer, Ui.matchWidth());

        setContentView(scroll);
        styleSystemBars();
        fitToSystemBars(scroll);
        Updater.schedule(this);
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
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

    private void addStatusCard(LinearLayout page) {
        LinearLayout card = ui.card(page, null);
        LinearLayout heading = ui.row();
        stateBadge = ui.badge("✓", Ui.GOOD, 22);
        heading.addView(stateBadge);
        stateTitle = ui.text("", 20, ui.ink, true);
        stateTitle.setPadding(ui.dp(10), 0, 0, 0);
        heading.addView(stateTitle, Ui.weighted());
        card.addView(heading);
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

    private void addActivityCard(LinearLayout page) {
        LinearLayout card = ui.card(page, "Recent offers");
        activitySummary = ui.note("");
        card.addView(activitySummary);

        chart = new DecisionChartView(this, ui);
        chart.setOnSelect(entry -> {
            selectionDetail.setText(describe(entry, true));
            selectionDetail.setVisibility(View.VISIBLE);
        });
        LinearLayout.LayoutParams chartParams = Ui.matchWidth();
        chartParams.topMargin = ui.dp(8);
        card.addView(chart, chartParams);
        legend = legend();
        card.addView(legend);

        selectionDetail = ui.text("", 13, ui.inkSecondary, false);
        selectionDetail.setVisibility(View.GONE);
        selectionDetail.setBackground(ui.rounded(ui.fieldFill, 0, 10));
        selectionDetail.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        selectionDetail.setTextIsSelectable(true);
        LinearLayout.LayoutParams detailParams = Ui.matchWidth();
        detailParams.topMargin = ui.dp(10);
        card.addView(selectionDetail, detailParams);

        history = ui.column();
        history.setPadding(0, ui.dp(6), 0, 0);
        card.addView(history);
        moreHistory = ui.addButton(card, "Show more", false, () -> {
            showAllHistory = !showAllHistory;
            shownHistoryVersion = -1;
            refreshHistory();
        });
    }

    /** Symbol and word for each status, plus the requirement tick, so no meaning rests on color alone. */
    private LinearLayout legend() {
        LinearLayout legend = ui.row();
        legend.setPadding(0, ui.dp(6), 0, 0);
        for (OfferRule.Result result : new OfferRule.Result[] {
                OfferRule.Result.KEEP, OfferRule.Result.DECLINE, OfferRule.Result.REVIEW}) {
            legend.addView(ui.badge(result, 14));
            TextView word = ui.text(Ui.resultLabel(result), 12, ui.inkSecondary, false);
            word.setPadding(ui.dp(4), 0, ui.dp(12), 0);
            legend.addView(word);
        }
        View tick = new View(this);
        tick.setBackgroundColor(ui.ink);
        legend.addView(tick, new LinearLayout.LayoutParams(ui.dp(14), ui.dp(2)));
        TextView needed = ui.text("Needed", 12, ui.inkSecondary, false);
        needed.setPadding(ui.dp(4), 0, 0, 0);
        legend.addView(needed);
        return legend;
    }

    private void addRulesCard(LinearLayout page, FilterSettings saved) {
        LinearLayout body = ui.foldingCard(page, "Rules");
        LinearLayout first = ui.row();
        flat = ui.field(cell(first), "Minimum pay ($)", money(saved.flatCents), true);
        maxStops = ui.field(cell(first), "Max stops", Integer.toString(saved.maxStops), false);
        body.addView(first);
        LinearLayout second = ui.row();
        mile = ui.field(cell(second), "Per mile ($)", money(saved.perMileCents), true);
        minute = ui.field(cell(second), "Per minute ($)", money(saved.perMinuteCents), true);
        body.addView(second);
        LinearLayout third = ui.row();
        stop = ui.field(cell(third), "Per extra stop ($)", money(saved.extraStopCents), true);
        cell(third);
        body.addView(third);
        TextView zero = ui.text("0 turns a rule off.", 12, ui.inkSecondary, false);
        zero.setPadding(0, ui.dp(6), 0, 0);
        body.addView(zero);

        rising = ui.toggle(body, "Must beat last accepted pay", saved.risingOffers);
        LinearLayout baseline = ui.row();
        baselineNote = ui.text("", 13, ui.inkSecondary, false);
        baseline.addView(baselineNote, Ui.weighted());
        baseline.addView(ui.button("Reset", false, () -> {
            FilterStore.recordAccepted(this, 0);
            refresh();
        }));
        body.addView(baseline);
        ui.addButton(body, "Save rules", true, this::save);
    }

    /** A half-width column inside {@code row}. */
    private LinearLayout cell(LinearLayout row) {
        LinearLayout cell = ui.column();
        LinearLayout.LayoutParams params = Ui.weighted();
        params.setMarginEnd(row.getChildCount() == 0 ? ui.dp(6) : 0);
        params.setMarginStart(row.getChildCount() == 0 ? 0 : ui.dp(6));
        row.addView(cell, params);
        return cell;
    }

    private void addSetupCard(LinearLayout page) {
        LinearLayout body = ui.foldingCard(page, "Setup & help");
        ui.buttonPair(body,
                ui.button("Accessibility", false, () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))),
                ui.button("Notification access", false, this::openNotificationAccess));
        ui.buttonPair(body,
                ui.button("Alert settings", false, this::configureOfferAlerts),
                ui.button("DoorDash channel", false, this::openDoorDashChannel));
        body.addView(ui.divider());

        TextView caption = ui.text("Your email", 13, ui.inkSecondary, false);
        caption.setPadding(0, ui.dp(8), 0, ui.dp(4));
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

        routeRow = ui.row();
        routeRow.setPadding(0, ui.dp(10), 0, 0);
        routeNote = ui.text("", 13, ui.inkSecondary, false);
        routeRow.addView(routeNote, Ui.weighted());
        routeRow.addView(ui.button("Forget", false, () -> {
            ActiveRouteStore.clear(this);
            refresh();
        }));
        body.addView(routeRow);
    }

    // ---- State ----

    private void refresh() {
        if (stateTitle == null) return;
        FilterSettings saved = FilterStore.load(this);
        int state = saved.enabled ? 1 : saved.hasAnyRule() ? 2 : 3;
        if (state != shownState) ui.style(masterButton, state != 1);
        shownState = state;
        if (saved.enabled) {
            ui.recolorBadge(stateBadge, "✓", Ui.GOOD);
            stateTitle.setText("Auto-decline on");
            stateDetail.setText(saved.brief());
            masterButton.setText("Pause auto-decline");
        } else if (saved.hasAnyRule()) {
            ui.recolorBadge(stateBadge, "‖", Ui.WARNING);
            stateTitle.setText("Auto-decline paused");
            stateDetail.setText(saved.brief());
            masterButton.setText("Resume auto-decline");
        } else {
            ui.recolorBadge(stateBadge, "–", ui.inkMuted);
            stateTitle.setText("Auto-decline off");
            stateDetail.setText("Add a rule under Rules.");
            masterButton.setText("Resume auto-decline");
        }

        screenReading.update(OfferFilterService.isConnected());
        backgroundOffers.update(OfferNotificationService.isConnected());
        offerAlerts.update(OfferAlerts.canNotify(this));

        refreshHistory();
        baselineNote.setText(saved.lastAcceptedCents > 0
                ? "Last accepted: " + DecisionLog.money(saved.lastAcceptedCents) : "Last accepted: none yet");
        updateStatus.setText(Updater.status(this));
        allowInstalls.setVisibility(getPackageManager().canRequestPackageInstalls() ? View.GONE : View.VISIBLE);
        OfferSnapshot route = ActiveRouteStore.load(this);
        routeRow.setVisibility(route == null ? View.GONE : View.VISIBLE);
        if (route != null) routeNote.setText("Active route: " + route.summary());
        if (diagnostics.isChecked() && !DiagnosticLog.isEnabled(this)) diagnostics.setChecked(false);
    }

    private void refreshHistory() {
        long version = DecisionLog.version();
        if (version == shownHistoryVersion) return;
        shownHistoryVersion = version;
        List<DecisionLog.Entry> recent = DecisionLog.recent(this, DecisionLog.MAX_ENTRIES);
        activitySummary.setText(summarize(recent));
        chart.setEntries(recent);
        boolean empty = recent.isEmpty();
        chart.setVisibility(empty ? View.GONE : View.VISIBLE);
        legend.setVisibility(empty ? View.GONE : View.VISIBLE);
        DecisionLog.Entry selected = chart.selectedEntry();
        selectionDetail.setVisibility(selected == null ? View.GONE : View.VISIBLE);
        if (selected != null) selectionDetail.setText(describe(selected, true));
        history.removeAllViews();
        int rows = Math.min(showAllHistory ? MORE_HISTORY_ROWS : HISTORY_ROWS, recent.size());
        for (int i = 0; i < rows; i++) history.addView(historyRow(recent.get(i)));
        moreHistory.setVisibility(recent.size() > HISTORY_ROWS ? View.VISIBLE : View.GONE);
        moreHistory.setText(showAllHistory ? "Show less" : "Show more");
    }

    private String summarize(List<DecisionLog.Entry> recent) {
        long since = System.currentTimeMillis() - DAY_MS;
        int total = 0;
        int passed = 0;
        int declined = 0;
        for (DecisionLog.Entry entry : recent) {
            if (entry.at < since) continue;
            total++;
            if (entry.result == OfferRule.Result.KEEP) passed++;
            else if (entry.result == OfferRule.Result.DECLINE) declined++;
        }
        if (recent.isEmpty()) return "No offers yet.";
        if (total == 0) return "None in the last 24 hours.";
        return String.format(Locale.US, "Last 24 h: %d passed · %d declined · %d review",
                passed, declined, total - passed - declined);
    }

    /** Badge, "$7.90 · needed $10.80", time, and the reason in plain words; tap for everything else. */
    private View historyRow(DecisionLog.Entry entry) {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.TOP);
        row.setPadding(0, ui.dp(8), 0, ui.dp(8));
        TextView badge = ui.badge(entry.result, 24);
        ((LinearLayout.LayoutParams) badge.getLayoutParams()).topMargin = ui.dp(2);
        row.addView(badge);
        LinearLayout texts = ui.column();
        texts.setPadding(ui.dp(12), 0, 0, 0);
        LinearLayout top = ui.row();
        top.addView(ui.text(headline(entry), 15, ui.ink, true), Ui.weighted());
        top.addView(ui.text(when(entry.at), 13, ui.inkSecondary, false));
        texts.addView(top);
        TextView details = ui.text(plainReason(entry.reason), 13, ui.inkSecondary, false);
        texts.addView(details);
        row.addView(texts, Ui.weighted());
        row.setClickable(true);
        row.setContentDescription(Ui.resultLabel(entry.result) + ", " + headline(entry) + ", "
                + plainReason(entry.reason));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(ui.selectionWash), null,
                ui.rounded(0xFFFFFFFF, 0, 8)));
        boolean[] expanded = {false};
        row.setOnClickListener(tapped -> {
            expanded[0] = !expanded[0];
            details.setText(expanded[0] ? describe(entry, false) : plainReason(entry.reason));
        });
        return row;
    }

    /** "$7.90 · needed $10.80", or "Pay not read". */
    private static String headline(DecisionLog.Entry entry) {
        String pay = entry.facts.payCents == null ? "Pay not read" : DecisionLog.money(entry.facts.payCents);
        String needed = entry.requiredCents > 0 && entry.requiredCents < Long.MAX_VALUE
                ? " · needed " + DecisionLog.money(entry.requiredCents) : "";
        return pay + needed + (entry.addOn ? " · add-on" : "");
    }

    /** Reason, facts, action and, with evidence, the headline, time and exact lines read. */
    private String describe(DecisionLog.Entry entry, boolean withHeadline) {
        StringBuilder text = new StringBuilder();
        if (withHeadline) {
            text.append(Ui.resultLabel(entry.result)).append(" · ").append(headline(entry))
                    .append(" · ").append(when(entry.at)).append('\n');
        }
        text.append(plainReason(entry.reason)).append('\n')
                .append(DecisionLog.facts(entry.facts)).append('\n')
                .append(entry.action.label)
                .append(entry.source == DecisionLog.Source.SCREEN ? " · on screen" : " · from notification")
                .append(entry.autoDecline ? "" : " · while paused").append('\n')
                .append(entry.evidence.isEmpty() ? "Nothing numeric read." : "Read: "
                        + String.join("  ·  ", entry.evidence));
        return text.toString();
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

    private void toggleAutoDecline() {
        if (FilterStore.load(this).enabled) pause();
        else resume();
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
                toast("Add a rule under Rules first.");
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
            toast(pausedForLackOfRules ? "No rules left, so auto-decline is paused." : "Rules saved.");
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
                Integer.parseInt(stops), rising.isChecked(), FilterStore.load(this).lastAcceptedCents);
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
     * Android 15 draws apps targeting API 35 edge to edge, so the page must keep itself clear of the status bar,
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
