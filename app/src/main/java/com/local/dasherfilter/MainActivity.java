package com.local.dasherfilter;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
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
    private EditText flat;
    private EditText mile;
    private EditText minute;
    private EditText stop;
    private TextView status;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        FilterSettings settings = FilterStore.load(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(page);

        TextView title = text("Offer Filter", 24);
        page.addView(title);
        page.addView(text("Set a minimum offer value. Zero disables a rule. The app only declines offers it can read confidently.", 15));

        flat = field(page, "Minimum payout ($)", settings.flatCents);
        mile = field(page, "Minimum dollars per mile ($/mi)", settings.perMileCents);
        minute = field(page, "Minimum dollars per minute ($/min)", settings.perMinuteCents);
        stop = field(page, "Fee per extra stop ($)", settings.extraStopCents);
        page.addView(text("Required payout = max(flat minimum, miles × rate, minutes × rate) + fee for each stop after the usual pickup and drop-off. If a needed value is missing, the offer is left for you to review.", 13));

        enabled = new Switch(this);
        enabled.setText("Auto-decline low offers");
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

        page.addView(text("Last offer check", 18));
        status = text("", 14);
        page.addView(status);
        Button refresh = button("Refresh status");
        refresh.setOnClickListener(v -> refreshStatus());
        page.addView(refresh);
        page.addView(text("Setup: enable Offer Filter in Accessibility settings. If Android blocks a sideloaded accessibility service, open this app's App info menu and allow restricted settings. Auto-decline starts only after you turn it on and save.", 13));
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null) refreshStatus();
    }

    private void save() {
        try {
            FilterSettings next = new FilterSettings(enabled.isChecked(), parse(flat),
                    parse(mile), parse(minute), parse(stop));
            if (next.enabled && next.flatCents == 0 && next.perMileCents == 0 &&
                    next.perMinuteCents == 0 && next.extraStopCents == 0) {
                Toast.makeText(this, "Set at least one nonzero rule.", Toast.LENGTH_LONG).show();
                return;
            }
            FilterStore.save(this, next);
            Toast.makeText(this, "Rules saved.", Toast.LENGTH_SHORT).show();
            refreshStatus();
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, "Enter amounts from 0 to 1000 with up to two decimals.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private static int parse(EditText input) {
        try {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) return 0;
            BigDecimal amount = new BigDecimal(value);
            if (amount.signum() < 0 || amount.compareTo(new BigDecimal("1000")) > 0 ||
                    amount.scale() > 2) throw new IllegalArgumentException();
            return amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).intValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IllegalArgumentException(error);
        }
    }

    private EditText field(LinearLayout page, String label, int cents) {
        TextView caption = text(label, 15);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(-1, -2);
        captionParams.topMargin = dp(16);
        page.addView(caption, captionParams);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(String.format(Locale.US, "%.2f", cents / 100.0));
        input.setSelectAllOnFocus(true);
        page.addView(input, new LinearLayout.LayoutParams(-1, -2));
        return input;
    }

    private void refreshStatus() {
        status.setText(FilterStore.lastStatus(this));
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
