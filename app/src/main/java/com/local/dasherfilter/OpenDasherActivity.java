package com.local.dasherfilter;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;

/**
 * Where a tap on one of Offer Filter's offer cards lands: a screen that never shows. It opens Dasher the way Dasher's
 * launcher icon does, so Dasher's task comes to the front with the offer it is showing (nothing in it is cleared or
 * reset), in its own half when it is already in one half of a split screen, or into the other half when this screen
 * opened in one half without Dasher beside it; then it clears that card and is gone. Dasher's own notification
 * intent opened a screen that could not find the offer on some phones, while Dasher's launcher always shows it.
 * It runs only at the user's tap on a card. The app opens Dasher by itself only for Peek ({@link Peek}), from its screen
 * reader, the same way: Dasher's own launch intent, nothing cleared or reset.
 */
public final class OpenDasherActivity extends Activity {
    static final String EXTRA_CARD = "card";
    /** Dasher's own notification intent: used only if Dasher's launch intent cannot be started at the tap. */
    static final String EXTRA_DASHER_INTENT = "dasher_intent";

    /**
     * The tap for the card with {@code tag}: this screen, through an activity intent (Android blocks a service or
     * broadcast in between since Android 12). Dasher's own intent ({@code dasherOwn}, when Dasher made it) when Dasher
     * has no launch intent to start.
     */
    static PendingIntent forCard(Context context, String tag, PendingIntent dasherOwn) {
        if (DasherSplit.launcher(context) == null) return dasherOwn;
        Intent tap = new Intent(context, OpenDasherActivity.class)
                // One intent per card, so each tap clears its own card.
                .setData(new Uri.Builder().scheme("dashbuddy").authority("card").appendPath(tag).build())
                .putExtra(EXTRA_CARD, tag)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (dasherOwn != null) tap.putExtra(EXTRA_DASHER_INTENT, dasherOwn);
        return PendingIntent.getActivity(context, tag.hashCode(), tap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The user chose to open Dasher: a peek under way never goes back over it.
        OfferFilterService.cardTapped();
        Intent tap = getIntent();
        // Dasher already in one half of a split screen: its plain launch intent brings its task forward there. Only
        // when fresh metadata identifies a real pair without Dasher does it open into the other half. The old UI
        // sighting cannot choose a task to replace; uncertain placement uses a normal launcher request.
        SplitWindows.CardPlacement placement = OfferFilterService.cardSplitPlacementNow();
        boolean beside = placement == SplitWindows.CardPlacement.DASHER_PRESENT;
        boolean adjacent = placement == SplitWindows.CardPlacement.OTHER_PAIR && DasherSplit.inSplit(this);
        Intent dasher = adjacent ? DasherSplit.dasher(this) : DasherSplit.launcher(this);
        String opened = null;
        if (dasher != null) {
            try {
                startActivity(dasher);
                opened = beside ? "in its own half" : adjacent ? "in the other half" : "launcher";
            } catch (ActivityNotFoundException | SecurityException refused) {
                opened = null;
            }
        }
        if (opened == null && sendDashersOwn(tap)) opened = "its own notification's screen";
        DiagnosticLog.log(this, "alert", opened != null
                ? "card tapped → opened Dasher (" + opened + ")" : "card tapped; Dasher could not be opened");
        String card = tap == null ? null : tap.getStringExtra(EXTRA_CARD);
        if (card != null) OfferAlerts.clear(this, card);
        finish();
        overridePendingTransition(0, 0);
    }

    /** Dasher's own notification intent, when the card carried one. */
    private boolean sendDashersOwn(Intent tap) {
        try {
            PendingIntent own = dashersOwn(tap);
            if (own == null) return false;
            Bundle options = null;
            if (Build.VERSION.SDK_INT >= 34) {
                // This screen is in front at the user's tap: Dasher may open from it.
                options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();
            }
            own.send(this, 0, null, null, null, null, options);
            return true;
        } catch (PendingIntent.CanceledException | RuntimeException refused) {
            return false;
        }
    }

    /**
     * The card's copy of Dasher's own intent, or null. The typed lookup is used only from Android 14: on Android 13
     * it is known to fail where the untyped one works.
     */
    @SuppressWarnings("deprecation")
    private static PendingIntent dashersOwn(Intent tap) {
        if (tap == null) return null;
        if (Build.VERSION.SDK_INT >= 34) return tap.getParcelableExtra(EXTRA_DASHER_INTENT, PendingIntent.class);
        Parcelable own = tap.getParcelableExtra(EXTRA_DASHER_INTENT);
        return own instanceof PendingIntent ? (PendingIntent) own : null;
    }
}
