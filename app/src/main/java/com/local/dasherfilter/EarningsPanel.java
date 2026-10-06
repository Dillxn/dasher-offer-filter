package com.local.dasherfilter;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;

/** Explicit local inputs and an opt-in; opening or saving this panel never applies a recommendation. */
final class EarningsPanel {
    private final Activity activity;
    private final Ui ui;
    private final Runnable onChanged;
    private AlertDialog dialog;
    private AlertDialog confirmation;
    private AlertDialog historyDialog;
    private EarningsStore.ConfigSnapshot shownConfig;
    private long epoch;
    private TextView status;
    private EditText cost, fuel, mpg, wear, ar, floor, ceiling;

    EarningsPanel(Activity activity, Ui ui, Runnable onChanged) {
        this.activity = activity;
        this.ui = ui;
        this.onChanged = onChanged;
    }

    void show() {
        dismiss();
        if (!Consent.accepted(activity) || activity.isFinishing() || activity.isDestroyed()) return;
        LinearLayout body = ui.column();
        body.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(12));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);
        text(body, "Estimates use observed offers and visible waiting, not completed earnings. DoorDash’s dispatch formula is unknown. Your entries stay on this phone.");
        cost = input(body, "Driving cost ($ per mile; blank is unknown)", true);
        text(body, "Include the costs you want counted. Zero is used only if you explicitly enter zero. Missing return mileage and unobserved time can change the result.");
        fuel = input(body, "Optional fuel price ($ per gallon)", true);
        mpg = input(body, "Your observed miles per gallon", true);
        wear = input(body, "Your wear allowance ($ per mile)", true);
        button(body, "Calculate cost from my entries", () -> {
            Double f = number(fuel), m = number(mpg), w = number(wear);
            if (f == null || m == null || w == null || m <= 0) {
                status.setText("Enter fuel price, positive observed MPG and wear allowance. Nothing was saved."); return;
            }
            double value = f / m + w;
            if (!Double.isFinite(value) || value > EarningsModel.MAX_COST_CENTS_PER_MILE / 100) { status.setText("Check the cost entries. Nothing was saved."); return; }
            cost.setText(String.format(Locale.US, "%.6f", value));
            status.setText("Calculated in the field only. Tap Save driving cost to use it.");
        });
        button(body, "Save driving cost", () -> {
            String raw = cost.getText().toString().trim();
            Double dollars = raw.isEmpty() ? null : number(cost);
            if (!raw.isEmpty() && (dollars == null || dollars * 100 > EarningsModel.MAX_COST_CENTS_PER_MILE)) { status.setText("Enter a nonnegative cost, or leave it blank for unknown."); return; }
            EarningsModel.Config current = EarningsStore.config(activity);
            save(new EarningsModel.Config(false, dollars == null ? null : dollars * 100,
                    current.floorPercent, current.ceilingPercent), "Driving cost saved. Automatic adjustment is off.");
        });
        ar = input(body, "Reported acceptance rate now (0–100%)", false);
        button(body, "Record reported rate", () -> {
            Integer value = integer(ar);
            if (value == null || value < 0 || value > 100) { status.setText("Enter a whole reported rate from 0 to 100."); return; }
            if (!EarningsStore.recordAr(activity, value, System.currentTimeMillis())) {
                status.setText("The rate could not be saved."); return;
            }
            ar.setText("");
            loadFields();
            status.setText("Reported rate recorded locally. It is not read automatically or used as a causal dispatch multiplier.");
            changed();
        });
        button(body, "View reported rate history", this::showHistory);
        text(body, "The report date can select a supported observation period. Its percentage does not prove that acceptance rate changed dispatch or earnings.");
        floor = input(body, "Automatic lower bound (1–200%)", false);
        ceiling = input(body, "Automatic upper bound (1–200%)", false);
        button(body, "Save bounds", () -> {
            Integer low = integer(floor), high = integer(ceiling);
            if (low == null || high == null || low < 1 || high > 200 || low > high) {
                status.setText("Use whole bounds from 1 to 200, with lower no greater than upper."); return;
            }
            EarningsModel.Config current = EarningsStore.config(activity);
            save(new EarningsModel.Config(false, current.vehicleCostCentsPerMile, low, high),
                    "Bounds saved. Automatic adjustment is off until you enable it again.");
        });
        button(body, "Enable automatic adjustment…", this::confirmEnable);
        button(body, "Turn automatic adjustment off", () -> {
            EarningsModel.Config current = EarningsStore.config(activity);
            save(new EarningsModel.Config(false, current.vehicleCostCentsPerMile,
                    current.floorPercent, current.ceilingPercent), "Automatic adjustment is off.");
        });
        text(body, "When enabled: at least 20 readable arrivals, five matching offers and 30 observed waiting minutes; corroboration in both halves of the sample. At most five percentage points per 15 real minutes, only between offers during visible, unlocked waiting. Manual percentage changes turn it off. Saved and learned minimums remain.");
        status = ui.text("", 14, ui.ink, false);
        body.addView(status, Ui.matchWidth());
        dialog = new AlertDialog.Builder(activity).setTitle("Costs and estimates").setView(scroll)
                .setNegativeButton("Close", null).create();
        AlertDialog shown = dialog;
        shown.setOnDismissListener(ignored -> { if (dialog == shown) { dialog = null; closeConfirmation(); } });
        OwnWindowTouches.track(shown).show();
        loadFields();
    }

    void dismiss() {
        epoch++;
        closeConfirmation();
        AlertDialog old = dialog;
        dialog = null;
        if (old != null) old.dismiss();
    }

    private void closeConfirmation() {
        if (historyDialog != null) { historyDialog.dismiss(); historyDialog = null; }
        AlertDialog old = confirmation;
        confirmation = null;
        if (old != null) old.dismiss();
    }

    private boolean active() {
        return dialog != null && dialog.isShowing() && !activity.isFinishing() && !activity.isDestroyed()
                && Consent.accepted(activity);
    }

    private boolean current() {
        if (!active()) return false;
        EarningsStore.ConfigSnapshot current = EarningsStore.configSnapshot(activity);
        if (shownConfig == null || current.generation != shownConfig.generation
                || !current.config.key().equals(shownConfig.config.key())) {
            loadFields();
            status.setText("Settings changed. Review the current values before saving again.");
            return false;
        }
        return true;
    }

    private void save(EarningsModel.Config config, String message) {
        if (!current()) return;
        if (!EarningsStore.saveConfigIfCurrent(activity, config, shownConfig)) { status.setText("Settings could not be saved."); return; }
        loadFields();
        status.setText(message);
        changed();
    }

    private void confirmEnable() {
        if (!current()) return;
        EarningsModel.Config selected = EarningsStore.config(activity);
        if (selected.vehicleCostCentsPerMile == null) {
            status.setText("Save an explicit driving cost before enabling automatic adjustment."); return;
        }
        int currentPercent = FilterStore.load(activity).minimumScalePercent;
        if (currentPercent < selected.floorPercent || currentPercent > selected.ceilingPercent) {
            status.setText("Your current " + currentPercent + "% minimums are outside the saved bounds. Save bounds that include them before enabling.");
            return;
        }
        closeConfirmation();
        String expected = selected.key();
        EarningsStore.ConfigSnapshot expectedConfig = shownConfig;
        AlertDialog parent = dialog;
        final AlertDialog[] expectedDialog = new AlertDialog[1];
        confirmation = new AlertDialog.Builder(activity).setTitle("Automatically adjust minimums?")
                .setMessage(String.format(Locale.US, "Saved bounds: %d–%d%%. Saved cost: $%.4f per mile. Unsaved field edits are not used.\n\n",
                        selected.floorPercent, selected.ceilingPercent, selected.vehicleCostCentsPerMile / 100)
                        + "This can change which offers are declined and lower your acceptance rate or earnings. If you separately enabled Auto-accept, changed minimums can also commit you to different deliveries.\n\n"
                        + "Local estimates do not know DoorDash’s dispatch formula or actual completed earnings. Changes stay within your saved bounds and happen only between offers, at most five points per 15 minutes after enough evidence. Your touch and existing safety guards still apply.\n\nEnable only if you accept these risks.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Enable adjustment", (ignored, which) -> {
                    if (!active() || confirmation != expectedDialog[0] || !confirmation.isShowing() || dialog != parent || !EarningsStore.config(activity).key().equals(expected)) return;
                    int percent = FilterStore.load(activity).minimumScalePercent;
                    if (percent < selected.floorPercent || percent > selected.ceilingPercent) {
                        status.setText("Current minimums are now outside the saved bounds. Review them before enabling.");
                        return;
                    }
                    if (!EarningsStore.saveConfigIfCurrent(activity,
                            new EarningsModel.Config(true, selected.vehicleCostCentsPerMile,
                                    selected.floorPercent, selected.ceilingPercent), expectedConfig)) {
                        status.setText("Settings changed or could not be saved. Review them before enabling again.");
                        return;
                    }
                    loadFields();
                    status.setText("Automatic adjustment enabled. It waits for sufficient fresh evidence between offers.");
                    changed();
                }).create();
        expectedDialog[0] = confirmation;
        confirmation.setOnDismissListener(ignored -> { if (confirmation == expectedDialog[0]) confirmation = null; });
        OwnWindowTouches.track(confirmation).show();
    }

    private void loadFields() {
        EarningsModel.Recommendation advice = EarningsStore.recommendation(activity,
                QualifyingWaitStore.snapshot(activity), FilterStore.load(activity), System.currentTimeMillis());
        List<EarningsModel.Snapshot> snapshots = EarningsStore.arHistory(activity, System.currentTimeMillis());
        // Reads may prune old history and advance its generation. Capture only afterward.
        shownConfig = EarningsStore.configSnapshot(activity);
        EarningsModel.Config config = shownConfig.config;
        cost.setText(config.vehicleCostCentsPerMile == null ? "" : String.format(Locale.US, "%.4f", config.vehicleCostCentsPerMile / 100));
        floor.setText(Integer.toString(config.floorPercent));
        ceiling.setText(Integer.toString(config.ceilingPercent));
        String rate = snapshots.isEmpty() ? "No reported acceptance rate." : "Reported rate: "
                + snapshots.get(snapshots.size() - 1).percent + "% (manual).";
        status.setText("Automatic adjustment: " + (config.enabled ? "on" : "off") + ". " + rate + "\n\n"
                + advice.summary() + "\n" + advice.detail());
    }

    private void showHistory() {
        if (!active()) return;
        List<EarningsModel.Snapshot> rows = EarningsStore.arHistory(activity, System.currentTimeMillis());
        java.text.DateFormat date = java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT);
        String[] labels = new String[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            EarningsModel.Snapshot row = rows.get(rows.size() - 1 - i);
            labels[i] = row.percent + "% — " + date.format(new java.util.Date(row.at));
        }
        closeConfirmation();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("Reported rate history (local time)").setNegativeButton("Close", null);
        if (rows.isEmpty()) builder.setMessage("No manually reported rates in the last 30 days.");
        else builder.setItems(labels, null);
        historyDialog = OwnWindowTouches.show(builder);
        loadFields();
    }

    private void changed() { if (onChanged != null) onChanged.run(); }
    private void text(LinearLayout body, String value) { body.addView(ui.text(value, 14, ui.inkSecondary, false), Ui.matchWidth()); }
    private EditText input(LinearLayout body, String label, boolean decimal) {
        text(body, label);
        EditText view = new EditText(activity);
        view.setContentDescription(label);
        view.setSingleLine(true);
        view.setMinHeight(ui.dp(48));
        view.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        body.addView(view, Ui.matchWidth());
        return view;
    }
    private void button(LinearLayout body, String label, Runnable action) {
        Button button = new Button(activity);
        button.setText(label);
        button.setMinHeight(ui.dp(48));
        long createdAt = epoch;
        button.setOnClickListener(ignored -> { if (createdAt == epoch && active()) action.run(); });
        body.addView(button, Ui.matchWidth());
    }
    private static Double number(EditText field) {
        try {
            double value = Double.parseDouble(field.getText().toString().trim());
            return Double.isFinite(value) && value >= 0 && value <= 1000 ? value : null;
        } catch (NumberFormatException error) { return null; }
    }
    private static Integer integer(EditText field) {
        String value = field.getText().toString().trim();
        if (!value.matches("[0-9]{1,3}")) return null;
        try { return Integer.valueOf(value); } catch (NumberFormatException error) { return null; }
    }
}
