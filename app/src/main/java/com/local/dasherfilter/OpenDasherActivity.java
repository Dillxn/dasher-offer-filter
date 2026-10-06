package com.local.dasherfilter;

import android.app.Activity;
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
 * opened in one half without Dasher beside it; then it clears that card and is gone. Dasher's launcher resumes its
 * task, and Dasher sometimes never draws a background offer it has to fetch, or draws it without its details (the
 * 0.4.72 report): with Dasher up and none of the offer's figures {@link Peek#PRESENT_MS} later, the screen reader sends
 * Dasher's own notification tap once for that offer ({@link OfferFilterService#cardOpened}). A card that says Dasher did not show the offer when it opened sends Dasher's
 * own notification intent first, its launcher at once if that cannot be sent, or from the screen reader if nothing of
 * Dasher's came up {@link OfferFilterService#OWN_FIRST_WAIT_MS} later ({@link OfferFilterService#cardOpenedOwnFirst}).
 * Dasher's own intent is otherwise used only when Dasher has no launch intent. It runs only at the user's tap on a card.
 * The app opens Dasher by itself only for Peek ({@link Peek}), from its screen reader, the same way.
 */
public final class OpenDasherActivity extends Activity {
    static final String EXTRA_CARD = "card";
    /** Dasher's own notification intent: the fallback when Dasher's launch intent cannot be started at the tap. */
    static final String EXTRA_DASHER_INTENT = "dasher_intent";
    /** The card says Dasher did not show its offer when it opened: Dasher's own notification intent goes first. */
    static final String EXTRA_PREFER_DASHER_OWN = "prefer_dasher_own";

    /**
     * The tap for the card with {@code tag}: this screen, through an activity intent (Android blocks a service or
     * broadcast in between since Android 12). Dasher's own intent ({@code dasherOwn}, when Dasher made it) when Dasher
     * has no launch intent to start.
     */
    static PendingIntent forCard(Context context, String tag, PendingIntent dasherOwn) {
        return forCard(context, tag, dasherOwn, false);
    }

    /** As {@link #forCard(Context, String, PendingIntent)}; {@code preferDasherOwn}: Dasher's own intent first. */
    static PendingIntent forCard(Context context, String tag, PendingIntent dasherOwn, boolean preferDasherOwn) {
        if (DasherSplit.launcher(context) == null) return dasherOwn;
        Intent tap = new Intent(context, OpenDasherActivity.class)
                // One intent per card, so each tap clears its own card.
                .setData(new Uri.Builder().scheme("dashbuddy").authority("card").appendPath(tag).build())
                .putExtra(EXTRA_CARD, tag)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (dasherOwn != null) tap.putExtra(EXTRA_DASHER_INTENT, dasherOwn);
        if (preferDasherOwn && dasherOwn != null) tap.putExtra(EXTRA_PREFER_DASHER_OWN, true);
        return PendingIntent.getActivity(context, tag.hashCode(), tap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The user chose to open Dasher: a peek under way never goes back over it.
        OfferFilterService.cardTapped();
        Intent tap = getIntent();
        String card = tap == null ? null : tap.getStringExtra(EXTRA_CARD);
        boolean preferOwn = tap != null && tap.getBooleanExtra(EXTRA_PREFER_DASHER_OWN, false);
        String opened = null;
        // Dasher's launcher already brought Dasher up once without drawing this offer: its own intent first. Android
        // may block that start without a word (a send it refuses still returns), so the screen reader starts Dasher's
        // launcher once if nothing of Dasher's comes up; and no automatic own tap follows for this offer.
        if (preferOwn && sendDashersOwn(tap)) {
            opened = "its own notification's screen, first";
            if (card != null) {
                OfferNotificationService.claimOwnTap(card);
                OfferFilterService.cardOpenedOwnFirst(card);
            }
        }
        boolean launched = false;
        if (opened == null) {
            // Dasher already in one half of a split screen: its plain launch intent brings its task forward there.
            // Only when fresh metadata identifies a real pair without Dasher does it open into the other half. The old
            // UI sighting cannot choose a task to replace; uncertain placement uses a normal launcher request.
            SplitWindows.CardPlacement placement = OfferFilterService.cardSplitPlacementNow();
            boolean beside = placement == SplitWindows.CardPlacement.DASHER_PRESENT;
            boolean adjacent = placement == SplitWindows.CardPlacement.OTHER_PAIR && DasherSplit.inSplit(this);
            Intent dasher = adjacent ? DasherSplit.dasher(this) : DasherSplit.launcher(this);
            if (dasher != null) {
                try {
                    startActivity(dasher);
                    opened = beside ? "in its own half" : adjacent ? "in the other half" : "launcher";
                    launched = true;
                } catch (ActivityNotFoundException | SecurityException refused) {
                    opened = null;
                }
            }
        }
        // Dasher's launcher resumes its task: with no sign of the offer once Dasher is up, Dasher's own notification
        // tap is sent once (not again when it was just tried first).
        if (launched && card != null) OfferFilterService.cardOpened(card, preferOwn ? null : dashersOwn(tap));
        if (opened == null && !preferOwn && sendDashersOwn(tap)) opened = "its own notification's screen";
        DiagnosticLog.log(this, "alert", opened != null
                ? "card tapped → opened Dasher (" + opened + ")" : "card tapped; Dasher could not be opened");
        if (card != null) OfferAlerts.clear(this, card);
        finish();
        overridePendingTransition(0, 0);
    }

    /** Dasher's own notification intent, when the card carried one Dasher made. */
    private boolean sendDashersOwn(Intent tap) {
        try {
            PendingIntent own = dashersOwn(tap);
            if (!DasherOwnIntent.fromDasher(own)) return false;
            // This screen is in front at the user's tap: Dasher may open from it.
            own.send(this, 0, null, null, null, null, DasherOwnIntent.options(true));
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
