package com.local.dasherfilter;

import android.Manifest;
import android.app.Activity;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.accessibility.AccessibilityManager;
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

public final class MainActivity extends Activity {
    private Switch enabled;
    private Switch rising;
    private Switch diagnostics;
    private EditText flat;
    private EditText mile;
    private EditText minute;
    private EditText stop;
    private EditText maxStops;
    private TextView status;
    private TextView risingStatus;
    private TextView updateStatus;
    private boolean finishingUpdateSetup;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshStatus();
            refreshHandler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        FilterSettings settings = FilterStore.load(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(page);

        TextView title = text("Offer Filter " + Updater.version(this), 24);
        page.addView(title);
        page.addView(text("Set your offer limits. Zero disables a rule. The app only declines when a readable value fails an enabled rule.", 15));

        rising = new Switch(this);
        rising.setText("Only offers above my last accepted payout");
        rising.setChecked(settings.risingOffers);
        page.addView(rising);
        risingStatus = text("", 14);
        page.addView(risingStatus);
        page.addView(text("Tracks an Accept tap followed by the delivery screen. Until an acceptance is detected, your other rules apply. This threshold can eventually block most offers.", 13));
        Button resetRising = button("Reset last accepted payout");
        resetRising.setOnClickListener(v -> { FilterStore.recordAccepted(this, 0); refreshStatus(); });
        page.addView(resetRising);

        maxStops = field(page, "Maximum total stops (0 = off)",
                Integer.toString(settings.maxStops), InputType.TYPE_CLASS_NUMBER);
        page.addView(text("Pickup + drop-off = 2 stops. Offers above your limit are declined. An unreadable stop count cannot trigger this rule.", 13));
        flat = field(page, "Minimum payout ($)", settings.flatCents);
        mile = field(page, "Minimum dollars per mile ($/mi)", settings.perMileCents);
        minute = field(page, "Minimum dollars per minute ($/min)", settings.perMinuteCents);
        stop = field(page, "Fee per extra stop ($)", settings.extraStopCents);
        page.addView(text("Required payout = max(flat minimum, miles × rate, minutes × rate) + fee for each stop after pickup and drop-off. A known rule failure declines the offer; otherwise missing values leave it for you to review. Set all prices to zero to use only the stop limit.", 13));
        page.addView(text("Add-on offers are evaluated against the active route: the combined route must still meet your rules, and the added payout must cover the added miles/time and any newly added stop fees. The flat minimum is not charged again to the add-on by itself.", 13));

        enabled = new Switch(this);
        enabled.setText("Auto-decline filtered offers");
        enabled.setChecked(settings.enabled);
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(-1, -2);
        switchParams.topMargin = dp(18);
        page.addView(enabled, switchParams);

        Button save = button("Save rules");
        save.setOnClickListener(v -> save());
        page.addView(save);
        Button accessibility = button("Open Accessibility settings");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        page.addView(accessibility);
        Button notificationAccess = button("Allow background offer access");
        notificationAccess.setOnClickListener(v -> openNotificationAccess());
        page.addView(notificationAccess);
        page.addView(text("Notification access lets Offer Filter notice a DoorDash offer while Dasher is in the background. It uses DoorDash's own notification action to bring Dasher forward for qualifying or unreadable offers, and removes a filtered offer notification after a Decline request succeeds.", 13));
        Button sound = button("Dasher notification sound settings");
        sound.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, "com.doordash.driverapp")));
        page.addView(sound);
        page.addView(text("Offer Filter is silent. Android can play a DoorDash notification before a listener can classify and cancel it, so a filtered offer may still make a brief sound. Ringing inside Dasher is separate.", 13));

        page.addView(text("Last offer check", 18));
        status = text("", 14);
        page.addView(status);
        Button refresh = button("Refresh status");
        refresh.setOnClickListener(v -> refreshStatus());
        page.addView(refresh);

        page.addView(text("Diagnostics", 18));
        diagnostics = new Switch(this);
        diagnostics.setText("Capture notification and screen diagnostics");
        diagnostics.setChecked(DiagnosticLog.isEnabled(this));
        diagnostics.setOnCheckedChangeListener((view, checked) -> {
            DiagnosticLog.setEnabled(this, checked);
            refreshStatus();
        });
        page.addView(diagnostics);
        page.addView(text("Diagnostics stay on this phone until you share them. While enabled they can contain visible offer text, including store/customer/location text, so turn capture off when troubleshooting is done.", 13));
        Button shareDiagnostics = button("Share diagnostics");
        shareDiagnostics.setOnClickListener(v -> shareDiagnostics());
        page.addView(shareDiagnostics);
        Button clearDiagnostics = button("Clear diagnostics");
        clearDiagnostics.setOnClickListener(v -> {
            DiagnosticLog.clear(this);
            Toast.makeText(this, "Diagnostics cleared.", Toast.LENGTH_SHORT).show();
        });
        page.addView(clearDiagnostics);

        page.addView(text("Updates", 18));
        Switch updates = new Switch(this);
        updates.setText("Automatic updates");
        updates.setChecked(Updater.enabled(this));
        updates.setOnCheckedChangeListener((view, checked) -> {
            Updater.setEnabled(this, checked);
            refreshStatus();
            if (checked) Updater.check(this, false, null);
        });
        page.addView(updates);
        updateStatus = text("", 14);
        page.addView(updateStatus);
        Button installPermission = button("Allow automatic installs");
        installPermission.setOnClickListener(v -> allowUpdates());
        page.addView(installPermission);
        Button checkUpdate = button("Check / install update");
        checkUpdate.setOnClickListener(v -> Updater.check(this, true, null));
        page.addView(checkUpdate);
        page.addView(text("Checks in the background about every 15 minutes when Android permits, and also when this app or its background services reconnect. Failed checks retry quickly. Updates install when you leave Dasher. Enable Allow from this source once; Android may still ask you to confirm an installation. Update notices are silent.", 13));
        page.addView(text("Setup: enable Offer Filter in Accessibility settings and allow background offer access in Notification access. If Android blocks a sideloaded accessibility service, open this app's App info menu and allow restricted settings. Auto-decline starts only after you turn it on and save.", 13));
        setContentView(scroll);
        Updater.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Updater.foreground(this);
        if (status != null) {
            refreshHandler.removeCallbacks(refresh);
            refreshHandler.post(refresh);
        }
        if (finishingUpdateSetup) {
            finishingUpdateSetup = false;
            if (getPackageManager().canRequestPackageInstalls()) allowUpdates();
        }
        Updater.check(this, false, null);
    }

    @Override
    protected void onPause() {
        refreshHandler.removeCallbacks(refresh);
        Updater.background(this);
        super.onPause();
    }

    private void openNotificationAccess() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= 30) {
            intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            new ComponentName(this, OfferNotificationService.class));
        } else {
            intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        }
        try {
            startActivity(intent);
        } catch (Exception error) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }

    private void shareDiagnostics() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Offer Filter diagnostics");
        send.putExtra(Intent.EXTRA_TEXT, DiagnosticLog.report(this));
        startActivity(Intent.createChooser(send, "Share Offer Filter diagnostics"));
    }

    private void allowUpdates() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            finishingUpdateSetup = true;
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !Updater.prefs(this).getBoolean("notices_asked", false)) {
            Updater.prefs(this).edit().putBoolean("notices_asked", true).apply();
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 12);
            return;
        }
        Updater.check(this, true, null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 12) Updater.check(this, true, null);
    }

    private void save() {
        try {
            FilterSettings next = new FilterSettings(enabled.isChecked(), parse(flat),
                    parse(mile), parse(minute), parse(stop), parseMaxStops(),
                    rising.isChecked(), FilterStore.load(this).lastAcceptedCents);
            if (next.enabled && next.flatCents == 0 && next.perMileCents == 0 &&
                    next.perMinuteCents == 0 && next.extraStopCents == 0 && next.maxStops == 0 && !next.risingOffers) {
                Toast.makeText(this, "Set at least one nonzero rule.", Toast.LENGTH_LONG).show();
                return;
            }
            FilterStore.save(this, next);
            Toast.makeText(this, "Rules saved.", Toast.LENGTH_SHORT).show();
            refreshStatus();
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private static int parse(EditText input) {
        try {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) return 0;
            BigDecimal amount = new BigDecimal(value);
            if (amount.signum() < 0 || amount.compareTo(new BigDecimal("1000")) > 0 ||
                    amount.scale() > 2) throw new NumberFormatException();
            return amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).intValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IllegalArgumentException("Enter amounts from 0 to 1000 with up to two decimals.", error);
        }
    }

    private int parseMaxStops() {
        String value = maxStops.getText().toString().trim();
        if (value.isEmpty()) return 0;
        if (!value.matches("\\d{1,2}")) {
            throw new IllegalArgumentException("Maximum stops must be a whole number from 0 to 99.");
        }
        return Integer.parseInt(value);
    }

    private EditText field(LinearLayout page, String label, int cents) {
        return field(page, label, String.format(Locale.US, "%.2f", cents / 100.0),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    }

    private EditText field(LinearLayout page, String label, String value, int inputType) {
        TextView caption = text(label, 15);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(-1, -2);
        captionParams.topMargin = dp(16);
        page.addView(caption, captionParams);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setText(value);
        input.setSelectAllOnFocus(true);
        page.addView(input, new LinearLayout.LayoutParams(-1, -2));
        return input;
    }

    private void refreshStatus() {
        FilterSettings saved = FilterStore.load(this);
        risingStatus.setText(saved.lastAcceptedCents == 0 ? "No accepted payout recorded yet." :
                String.format(Locale.US, "Last accepted: $%.2f. Rising rule %s.",
                        saved.lastAcceptedCents / 100.0, saved.risingOffers ? "ON" : "OFF"));
        if (updateStatus != null) updateStatus.setText(Updater.status(this));
        AccessibilityManager manager = getSystemService(AccessibilityManager.class);
        boolean serviceEnabled = false;
        String serviceId = new ComponentName(this, OfferFilterService.class).flattenToString();
        if (manager != null) {
            for (AccessibilityServiceInfo service : manager.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                ComponentName component = ComponentName.unflattenFromString(service.getId());
                if (component != null && serviceId.equals(component.flattenToString())) serviceEnabled = true;
            }
        }
        String accessibility = OfferFilterService.isConnected() ? "connected" :
                serviceEnabled ? "enabled, waiting to connect" : "OFF";
        boolean notificationAllowed = OfferNotificationService.hasAccess(this);
        String notification = OfferNotificationService.isConnected() ? "connected" :
                notificationAllowed ? "allowed, waiting to connect" : "OFF";
        OfferSnapshot activeRoute = ActiveRouteStore.load(this);
        status.setText(String.format(Locale.US,
                "Accessibility: %s\nBackground notification access: %s\nSaved auto-decline: %s\nSaved minimum payout: $%.2f\nSaved maximum stops: %s\nActive route: %s\nDiagnostics: %s\n\n%s",
                accessibility, notification, saved.enabled ? "ON" : "OFF", saved.flatCents / 100.0,
                saved.maxStops == 0 ? "off" : saved.maxStops,
                activeRoute == null ? "none" : activeRoute.summary(),
                DiagnosticLog.isEnabled(this) ? "ON" : "off", FilterStore.lastStatus(this)));
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setGravity(Gravity.START);
        return view;
    }

    private Button button(String label) {
        Button view = new Button(this);
        view.setText(label);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
