package com.local.dasherfilter;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * The split pane (0.6.4): a screen of this app's own that never shows anything, started beside the homepage in a task
 * of its own, launch-adjacent, at the user's Split tap ({@link DasherSplit}). Once it is in split screen it starts
 * Dasher's launcher as its icon does, into its own half (never launch-adjacent), and is gone; with no split
 * {@link DasherSplit#VERIFY_MS} after it resumed, it is gone without starting anything, and the homepage opens recent
 * apps. Every step goes in the log as a [split] line.
 */
public final class SplitPaneActivity extends Activity {
    /** The Split tap's number, for the log. */
    static final String EXTRA_TAP = "tap";

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean done;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DiagnosticLog.log(this, "split", "split pane started (in split screen: " + (isInMultiWindowMode() ? "yes" : "no")
                + ")");
    }

    @Override protected void onResume() {
        super.onResume();
        if (isInMultiWindowMode()) {
            startDasher();
        } else if (!done) {
            main.removeCallbacksAndMessages(null);
            main.postDelayed(this::giveUp, DasherSplit.VERIFY_MS);
        }
    }

    @Override public void onMultiWindowModeChanged(boolean inMultiWindow, Configuration newConfig) {
        super.onMultiWindowModeChanged(inMultiWindow, newConfig);
        if (inMultiWindow) startDasher();
    }

    private void startDasher() {
        if (done) return;
        done = true;
        main.removeCallbacksAndMessages(null);
        DasherSplit.fromPane(this);
        finish();
        overridePendingTransition(0, 0);
    }

    private void giveUp() {
        if (done) return;
        done = true;
        DasherSplit.paneWithoutSplit(this);
        finish();
        overridePendingTransition(0, 0);
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
