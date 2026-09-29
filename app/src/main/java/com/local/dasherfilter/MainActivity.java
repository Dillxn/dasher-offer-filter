package com.local.dasherfilter;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
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
import java.util.Locale;

/** Rules, permissions, diagnostics, and update controls. Pausing auto-decline takes effect without Save. */
public final class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 13;
    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshStatus();
            handler.postDelayed(this, 1000);
        }
    };
    private Switch enabled;
    private Switch rising;
    private Switch diagnostics;
    private EditText flat;
    private EditText mile;
    private EditText minute;
    private EditText stop;
    private EditText maxStops;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        OfferAlerts.ensureChannel(this);
        FilterSettings saved = FilterStore.load(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(18 * getResources().getDisplayMetrics().density);
        page.setPadding(pad, pad, pad, pad);
        scroll.addView(page);

        page.addView(text("Offer Filter " + Updater.version(this), 24));
        page.addView(text("Quiet background mode: this app never opens Dasher automatically. Offers without enough "
                + "pay/distance data get a SILENT review card, not a passing verdict. Tap the card to open Dasher; "
                + "visible offers can then be filtered.", 15));
        page.addView(text("Notification silence is not control over Dasher's in-app sound or vibration. Keep "
                + "DoorDash notifications enabled so offers remain detectable. This app cannot promise silent, fully "
                + "automatic filtering of a notification that contains no price or distance.", 14));

        addRulesSection(page, saved);
        addPermissionsSection(page);
        page.addView(text("Saved state and last decision", 20));
        status = text("", 14);
        status.setTextIsSelectable(true);
        page.addView(status);
        addDiagnosticsSection(page);
        addUpdatesSection(page);

        setContentView(scroll);
        fitToSystemBars(scroll);
        Updater.schedule(this);
    }

    private void addRulesSection(LinearLayout page, FilterSettings saved) {
        button(page, "Pause auto-decline immediately", this::pause);
        rising = toggle(page, "Only standalone offers above last accepted payout", saved.risingOffers);
        button(page, "Reset standalone payout baseline", () -> {
            FilterStore.recordAccepted(this, 0);
            refreshStatus();
        });
        maxStops = field(page, "Maximum total stops (0 disables)", Integer.toString(saved.maxStops), false);
        flat = field(page, "Minimum payout ($)", money(saved.flatCents), true);
        mile = field(page, "Minimum dollars per mile", money(saved.perMileCents), true);
        minute = field(page, "Minimum dollars per minute", money(saved.perMinuteCents), true);
        stop = field(page, "Fee per stop after the first two ($)", money(saved.extraStopCents), true);
        page.addView(text("Required pay = max(flat, miles × rate, minutes × rate) + extra-stop fees. Zero disables a "
                + "rule. Missing or conflicting evidence requires review unless another known rule already fails. "
                + "Add-ons are judged on the combined route and need explicit added pay for rate, extra-stop and "
                + "rising rules; an unlabeled figure is never assumed to be an increment.", 14));
        enabled = toggle(page, "Auto-decline (Save to enable; switching off is immediate)", saved.enabled);
        enabled.setOnCheckedChangeListener((view, on) -> {
            if (!on && FilterStore.load(this).enabled) pause();
        });
        button(page, "Save rules", this::save);
        button(page, "Forget active route context", () -> {
            ActiveRouteStore.clear(this);
            toast("Route context cleared; ambiguous add-ons require review.");
            refreshStatus();
        });
    }

    private void addPermissionsSection(LinearLayout page) {
        page.addView(text("Permissions and alerts", 20));
        button(page, "Open Accessibility settings", () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        button(page, "Allow background notification access", this::openNotificationAccess);
        button(page, "Enable / configure passing-offer alerts", this::configureOfferAlerts);
        button(page, "DoorDash offer channel settings", this::openDoorDashChannel);
        page.addView(text("Set only DoorDash's offer channel to Silent; do not turn notification access off. "
                + "Passing-offer bells and silent unclassified-review cards use separate Offer Filter channels. If "
                + "permission is denied, original DoorDash notifications are retained.", 14));
    }

    private void addDiagnosticsSection(LinearLayout page) {
        page.addView(text("Diagnostics", 20));
        diagnostics = toggle(page, "Capture raw diagnostics for 30 minutes", DiagnosticLog.isEnabled(this));
        diagnostics.setOnCheckedChangeListener((view, on) -> DiagnosticLog.setEnabled(this, on));
        page.addView(text("Local only. Raw screen text can include names and addresses. Capture expires "
                + "automatically. The report includes updater state even when raw capture is off.", 14));
        button(page, "Share diagnostics", () -> {
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "Offer Filter diagnostics");
            send.putExtra(Intent.EXTRA_TEXT, DiagnosticLog.report(this));
            open(Intent.createChooser(send, "Review and share diagnostics"));
        });
        button(page, "Clear diagnostic log", () -> {
            DiagnosticLog.clear(this);
            toast("Local log cleared.");
        });
    }

    private void addUpdatesSection(LinearLayout page) {
        page.addView(text("Updates", 20));
        Switch updates = toggle(page, "Automatic update checks", Updater.enabled(this));
        updates.setOnCheckedChangeListener((view, on) -> {
            Updater.setEnabled(this, on);
            if (on) Updater.check(this, false, null);
        });
        button(page, "Allow automatic installs", () -> {
            if (getPackageManager().canRequestPackageInstalls()) {
                Updater.check(this, true, null);
            } else {
                open(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            }
        });
        button(page, "Check / install update", () -> Updater.check(this, true, null));
        page.addView(text("Checks request a 15-minute background interval. Failed checks schedule an actual retry, "
                + "subject to Android delays. Automatic installation waits while an offer or delivery is active. "
                + "Android can still require installation confirmation.", 14));
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
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(light, light);
        }
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
        refreshStatus();
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            toast(granted ? "Alerts permitted." : "Alerts not permitted; originals are retained.");
        }
    }

    /** Persists auto-decline off immediately, keeping every other saved rule. */
    private void pause() {
        FilterStore.save(this, FilterStore.load(this).withEnabled(false));
        if (enabled != null && enabled.isChecked()) enabled.setChecked(false);
        OfferNotificationService.rulesChanged();
        OfferFilterService.requestCheckFromNotification();
        toast("Auto-decline paused immediately.");
        refreshStatus();
    }

    private void save() {
        try {
            String stops = maxStops.getText().toString().trim();
            if (!stops.matches("[0-9]{1,2}")) throw new IllegalArgumentException("Maximum stops must be 0 through 99.");
            FilterSettings next = new FilterSettings(enabled.isChecked(), parseCents(flat), parseCents(mile),
                    parseCents(minute), parseCents(stop), Integer.parseInt(stops), rising.isChecked(),
                    FilterStore.load(this).lastAcceptedCents);
            if (next.enabled && !next.hasAnyRule()) {
                throw new IllegalArgumentException("Enable at least one rule before auto-decline.");
            }
            FilterStore.save(this, next);
            OfferNotificationService.rulesChanged();
            OfferFilterService.requestCheckFromNotification();
            toast("Rules saved.");
            refreshStatus();
        } catch (IllegalArgumentException error) {
            toast(error.getMessage());
        }
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

    private void openNotificationAccess() {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        if (Build.VERSION.SDK_INT >= 30) {
            intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    new ComponentName(this, OfferNotificationService.class));
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

    private void refreshStatus() {
        if (status == null) return;
        FilterSettings saved = FilterStore.load(this);
        OfferSnapshot route = ActiveRouteStore.load(this);
        status.setText("Accessibility connected: " + OfferFilterService.isConnected()
                + "\nBackground listener connected: " + OfferNotificationService.isConnected()
                + "\nPassing alerts permitted: " + OfferAlerts.canNotify(this)
                + "\nSaved auto-decline: " + saved.enabled
                + "\nSaved minimum payout: $" + money(saved.flatCents)
                + "\nStandalone accepted baseline: $" + money(saved.lastAcceptedCents)
                + "\nActive route: " + (route == null ? "none" : route.summary())
                + "\nRaw capture active: " + DiagnosticLog.isEnabled(this)
                + "\n\n" + FilterStore.lastStatus(this)
                + "\n\nUpdater: " + Updater.status(this));
        if (diagnostics != null && diagnostics.isChecked() && !DiagnosticLog.isEnabled(this)) {
            diagnostics.setChecked(false);
        }
    }

    private TextView text(String label, int size) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        return view;
    }

    private Switch toggle(LinearLayout page, String label, boolean value) {
        Switch view = new Switch(this);
        view.setText(label);
        view.setChecked(value);
        page.addView(view);
        return view;
    }

    private void button(LinearLayout page, String label, Runnable action) {
        Button view = new Button(this);
        view.setText(label);
        view.setOnClickListener(clicked -> action.run());
        page.addView(view);
    }

    private EditText field(LinearLayout page, String label, String value, boolean decimal) {
        page.addView(text(label, 15));
        EditText view = new EditText(this);
        view.setSingleLine(true);
        view.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        view.setText(value);
        view.setSelectAllOnFocus(true);
        page.addView(view);
        return view;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
