package com.local.dasherfilter;

import android.Manifest;
import android.app.Activity;
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
import android.view.WindowInsets;
import android.widget.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private Switch enabled, rising, diagnostics;
    private EditText flat, mile, hour, stop, maxStops, maxMiles, avoidStores, maxDeclines;
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() { @Override public void run() { refreshStatus(); handler.postDelayed(this, 1000); } };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); OfferAlerts.ensureChannel(this); FilterSettings s = FilterStore.load(this);
        ScrollView scroll = new ScrollView(this); LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(18 * getResources().getDisplayMetrics().density); page.setPadding(pad, pad, pad, pad); scroll.addView(page);
        applySystemBarInsets(scroll);
        page.addView(text("Offer Filter " + Updater.version(this), 24));
        page.addView(text("Quiet background mode: this app never opens Dasher automatically. Offers without enough pay/distance data get a SILENT review card, not a passing verdict. Tap the card to open Dasher; visible offers can then be filtered.", 15));
        page.addView(text("Notification silence is not control over Dasher's in-app sound or vibration. Keep DoorDash notifications enabled so offers remain detectable. This app cannot promise silent, fully automatic filtering of a notification that contains no price or distance.", 14));
        button(page, "Pause auto-decline immediately", this::pause);
        rising = toggle(page, "Only standalone offers above last accepted payout", s.risingOffers);
        button(page, "Reset standalone payout baseline", () -> { FilterStore.recordAccepted(this, 0); refreshStatus(); });
        maxStops = field(page, "Maximum total stops (0 disables)", Integer.toString(s.maxStops), false);
        maxMiles = field(page, "Maximum miles (0 disables)", money(s.maxMilesHundredths), true);
        flat = field(page, "Minimum payout ($)", money(s.flatCents), true);
        mile = field(page, "Minimum dollars per mile", money(s.perMileCents), true);
        hour = field(page, "Minimum dollars per hour (uses only minutes Dasher shows)", money(hourlyCents(s)), true);
        stop = field(page, "Fee per stop after the first two ($)", money(s.extraStopCents), true);
        page.addView(text("Stores to avoid (one per line or comma-separated)", 15));
        avoidStores = new EditText(this); avoidStores.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        avoidStores.setMinLines(2); avoidStores.setText(String.join("\n", s.avoidStores)); page.addView(avoidStores);
        maxDeclines = field(page, "Safety limit: decline requests per hour (0 = no limit)", Integer.toString(s.maxDeclinesPerHour), false);
        page.addView(text("Required pay = max(flat, miles × $/mi, minutes × $/hr ÷ 60) + extra-stop fees. Zero disables a rule. Missing or conflicting evidence requires review unless another known rule already fails. Over max miles/stops or an avoided store is a known failure even when pay is not shown. Store rules alone never mark an offer as passing. Earn by Time offers are never auto-declined. Add-ons require explicit added values.", 14));
        enabled = toggle(page, "Auto-decline (Save to enable; switching off is immediate)", s.enabled);
        enabled.setOnCheckedChangeListener((v, on) -> { if (!on && FilterStore.load(this).enabled) pause(); });
        button(page, "Save rules", this::save);
        button(page, "Forget active route context", () -> { ActiveRouteStore.clear(this); toast("Route context cleared; ambiguous add-ons require review."); refreshStatus(); });
        page.addView(text("Permissions and alerts", 20));
        button(page, "Open Accessibility settings", () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        button(page, "Allow background notification access", this::notificationAccess);
        button(page, "Enable / configure passing-offer alerts", this::offerAlerts);
        button(page, "DoorDash offer channel settings", this::doorDashChannel);
        page.addView(text("Set only DoorDash's offer channel to Silent; do not turn notification access off. Passing-offer bells and silent unclassified-review cards use separate Offer Filter channels. If permission is denied, original DoorDash notifications are retained.", 14));
        page.addView(text("Saved state and last decision", 20)); status = text("", 14); status.setTextIsSelectable(true); page.addView(status);
        page.addView(text("Diagnostics", 20)); diagnostics = toggle(page, "Capture raw diagnostics for 30 minutes", DiagnosticLog.isEnabled(this));
        diagnostics.setOnCheckedChangeListener((v, on) -> DiagnosticLog.setEnabled(this, on));
        page.addView(text("Local only. Raw screen text can include names and addresses. Capture expires automatically. The report includes updater state even when raw capture is off.", 14));
        button(page, "Share diagnostics", () -> {
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain"); send.putExtra(Intent.EXTRA_SUBJECT, "Offer Filter diagnostics"); send.putExtra(Intent.EXTRA_TEXT, DiagnosticLog.report(this)); open(Intent.createChooser(send, "Review and share diagnostics"));
        });
        button(page, "Clear diagnostic log", () -> { DiagnosticLog.clear(this); toast("Local log cleared."); });
        page.addView(text("Updates", 20)); Switch updates = toggle(page, "Automatic update checks", Updater.enabled(this));
        updates.setOnCheckedChangeListener((v, on) -> { Updater.setEnabled(this, on); if (on) Updater.check(this, false, null); });
        button(page, "Allow automatic installs", () -> {
            if (!getPackageManager().canRequestPackageInstalls()) open(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); else Updater.check(this, true, null);
        });
        button(page, "Check / install update", () -> Updater.check(this, true, null));
        page.addView(text("Checks request a 15-minute background interval. Failed checks schedule an actual retry, subject to Android delays. Automatic installation waits while an offer or delivery is active. Android can still require installation confirmation.", 14));
        setContentView(scroll); Updater.schedule(this);
    }
    private static String money(int cents) { return String.format(Locale.US, "%.2f", cents / 100.0); }
    private void pause() {
        FilterStore.save(this, FilterStore.load(this).withEnabled(false));
        if (enabled != null && enabled.isChecked()) enabled.setChecked(false); OfferNotificationService.rulesChanged(); OfferFilterService.requestCheckFromNotification(); toast("Auto-decline paused immediately."); refreshStatus();
    }
    private void save() {
        try {
            String n = maxStops.getText().toString().trim(); if (!n.matches("[0-9]{1,2}")) throw new IllegalArgumentException("Maximum stops must be 0 through 99.");
            String limit = maxDeclines.getText().toString().trim(); if (!limit.matches("[0-9]{1,2}")) throw new IllegalArgumentException("Decline limit must be 0 through 99 per hour.");
            List<String> stores = avoidTerms(avoidStores.getText().toString());
            FilterSettings next = FilterStore.load(this).withEnabled(enabled.isChecked()).withFlatCents(parse(flat)).withPerMileCents(parse(mile))
                    .withPerMinuteCents(0).withPerHourCents(parse(hour)).withExtraStopCents(parse(stop)).withMaxStops(Integer.parseInt(n))
                    .withMaxMilesHundredths(parse(maxMiles)).withAvoidStores(stores).withMaxDeclinesPerHour(Integer.parseInt(limit)).withRisingOffers(rising.isChecked());
            if (next.enabled && !next.hasAnyRule()) throw new IllegalArgumentException("Enable at least one rule before auto-decline.");
            FilterStore.save(this, next); OfferNotificationService.rulesChanged(); OfferFilterService.requestCheckFromNotification(); toast("Rules saved."); refreshStatus();
        } catch (IllegalArgumentException error) { toast(error.getMessage()); }
    }
    /** Legacy per-minute rules display as their exact hourly equivalent; saving stores the hourly rate. */
    private static int hourlyCents(FilterSettings s) { long legacy = (long) s.perMinuteCents * 60; return (int) Math.min(Integer.MAX_VALUE, Math.max(s.perHourCents, legacy)); }
    private static List<String> avoidTerms(String text) {
        List<String> raw = new ArrayList<>();
        for (String part : text.split("[\\n\\r,;]+")) if (!part.trim().isEmpty()) raw.add(part.trim());
        if (raw.size() > StoreMatcher.MAX_TERMS) throw new IllegalArgumentException("Avoid at most " + StoreMatcher.MAX_TERMS + " stores.");
        for (String term : raw) { String error = StoreMatcher.termError(term); if (error != null) throw new IllegalArgumentException("Store \"" + term + "\": " + error); }
        return StoreMatcher.sanitize(raw);
    }
    private static int parse(EditText field) {
        try {
            String raw = field.getText().toString().trim(); if (raw.isEmpty()) return 0;
            if (raw.indexOf('.') < 0 && raw.indexOf(',') == raw.lastIndexOf(',')) raw = raw.replace(',', '.');   // "2,50" in comma-decimal locales
            BigDecimal n = new BigDecimal(raw);
            if (n.signum() < 0 || n.compareTo(new BigDecimal("1000")) > 0 || n.scale() > 2) throw new NumberFormatException(); return n.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).intValueExact();
        } catch (ArithmeticException | NumberFormatException error) { throw new IllegalArgumentException("Amounts must be 0–1000 with at most two decimals."); }
    }
    /** Settings reads the component as a flattened String; a Parcelable closes or crashes the detail page on Android 11+. */
    static Intent notificationAccessIntent(android.content.Context context) {
        if (Build.VERSION.SDK_INT < 30) return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                new ComponentName(context, OfferNotificationService.class).flattenToString());
    }
    private void notificationAccess() {
        try { startActivity(notificationAccessIntent(this)); }
        catch (RuntimeException error) { open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
    }
    private void offerAlerts() {
        OfferAlerts.ensureChannel(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            boolean asked = getSharedPreferences("offer_filter_ui", MODE_PRIVATE).getBoolean("notification_permission_asked", false);
            // After Android stops showing the prompt, send the user to the app's notification settings instead of a dead end.
            if (!asked || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                getSharedPreferences("offer_filter_ui", MODE_PRIVATE).edit().putBoolean("notification_permission_asked", true).apply();
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 13); return;
            }
            open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())); return;
        }
        android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
        if (manager != null && !manager.areNotificationsEnabled()) { open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())); return; }
        open(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID, OfferAlerts.CHANNEL_ID));
    }
    /** targetSdk 35 is edge-to-edge on Android 15: keep content clear of the status/navigation bars and cutouts. */
    private static void applySystemBarInsets(ScrollView scroll) {
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
    }
    private void doorDashChannel() {
        String channel = FilterStore.doorDashOfferChannel(this);
        Intent i = new Intent(channel.isEmpty() ? Settings.ACTION_APP_NOTIFICATION_SETTINGS : Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, "com.doordash.driverapp");
        if (!channel.isEmpty()) i.putExtra(Settings.EXTRA_CHANNEL_ID, channel); open(i);
    }
    private void open(Intent i) { try { startActivity(i); } catch (RuntimeException error) { toast("Android could not open this screen: " + error.getClass().getSimpleName()); } }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results); refreshStatus();
        if (requestCode == 13) toast(results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED ? "Alerts permitted." : "Alerts not permitted; originals are retained.");
    }
    @Override protected void onResume() { super.onResume(); Updater.foreground(this); handler.removeCallbacks(refresh); handler.post(refresh); Updater.check(this, false, null); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); Updater.background(this); super.onPause(); }
    private void refreshStatus() {
        if (status == null) return; FilterSettings s = FilterStore.load(this); OfferSnapshot route = ActiveRouteStore.load(this);
        status.setText("Accessibility connected: " + OfferFilterService.isConnected() + "\nBackground listener connected: " + OfferNotificationService.isConnected() + "\nPassing alerts permitted: " + OfferAlerts.canNotify(this) + "\nSaved auto-decline: " + s.enabled + "\nSaved minimum payout: $" + money(s.flatCents) + "\nStandalone accepted baseline: " + baseline(s) + "\nActive route: " + (route == null ? "none" : route.summary()) + "\nRaw capture active: " + DiagnosticLog.isEnabled(this) + "\n\n" + FilterStore.lastStatus(this) + "\n\nUpdater: " + Updater.status(this));
        if (diagnostics != null && diagnostics.isChecked() && !DiagnosticLog.isEnabled(this)) diagnostics.setChecked(false);
    }
    private static String baseline(FilterSettings s) {
        if (s.lastAcceptedCents <= 0) return "not set";
        if (s.lastAcceptedAt <= 0) return "$" + money(s.lastAcceptedCents) + " (undated from an older version; not applied)";
        String at = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(s.lastAcceptedAt));
        return "$" + money(s.lastAcceptedCents) + " accepted " + at + (s.baselineFresh(System.currentTimeMillis()) ? "" : " (older than 8 h; not applied)");
    }
    private TextView text(String label, int size) { TextView v = new TextView(this); v.setText(label); v.setTextSize(size); return v; }
    private Switch toggle(LinearLayout page, String label, boolean value) { Switch v = new Switch(this); v.setText(label); v.setChecked(value); page.addView(v); return v; }
    private void button(LinearLayout page, String label, Runnable action) { Button v = new Button(this); v.setText(label); v.setOnClickListener(view -> action.run()); page.addView(v); }
    private EditText field(LinearLayout page, String label, String value, boolean decimal) {
        page.addView(text(label, 15)); EditText v = new EditText(this); v.setSingleLine(true); v.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0)); v.setText(value); v.setSelectAllOnFocus(true); page.addView(v); return v;
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
