package com.local.dasherfilter;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;

/**
 * Passing offers may ring on their own channel. An offer that cannot be judged (DoorDash's background notification
 * shows no pay, or auto-decline is paused) gets a card on "Offers to check", ringing once, only while DoorDash's own
 * notification of it is gone or cannot be tapped (the owner: never a payless card beside DoorDash's own); it is never
 * judged, declined or opened for you. A card that carries what was read of the offer, or why it could not be read, and
 * need not ring (Dasher's own alert, or an earlier card, already rang for it) still pops up, with no sound of its own,
 * on "Offer details" ({@link #SHOWN_CHANNEL_ID}); with the offer's countdown read on Dasher's screen, it counts down
 * and goes when the offer does.
 */
final class OfferAlerts {
    /** New id: Android keeps a channel's first sound forever, so the passing chime needs a fresh channel. */
    static final String CHANNEL_ID = "qualifying_offers_v2";
    private static final String RETIRED_PASSING_CHANNEL_ID = "qualifying_offers";
    static final String REVIEW_CHANNEL_ID = "offers_to_check_v2";
    /** 0.4.15 and earlier posted review cards silently here; Android cannot raise a channel's importance later. */
    private static final String RETIRED_SILENT_REVIEW_CHANNEL_ID = "unclassified_offers_v1";
    /**
     * Cards that carry what was read of an offer (or why it could not be read) and need not ring: they pop up with no
     * sound or vibration of their own. High importance is what lets them pop up; Android keeps a channel's first
     * settings, so any change of its sound needs a new id.
     */
    static final String SHOWN_CHANNEL_ID = "offer_details_v1";
    static final int NOTIFICATION_ID = 8241;
    /** A card times out after this: Dasher's countdown runs 47-60 s, so a later tap would find no offer. */
    static final long CARD_MS = 60_000;
    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    private static final int MAX_BODY_CHARS = 500;

    static void ensureChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;

        NotificationChannel passing =
                new NotificationChannel(CHANNEL_ID, "Offers that pass", NotificationManager.IMPORTANCE_HIGH);
        passing.setDescription("Only offers proven to meet enabled rules. Uses the app's pass chime; Android "
                + "sound and DND settings still apply.");
        passing.enableVibration(true);
        passing.setSound(passingSound(context), new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        manager.createNotificationChannel(passing);

        NotificationChannel review = new NotificationChannel(
                REVIEW_CHANNEL_ID, "Offers to check", NotificationManager.IMPORTANCE_HIGH);
        review.setDescription("An offer arrived while Dasher was in the background, but its pay or distance was not "
                + "shown, so it could not be judged. Rings once; tap to open Dasher.");
        review.enableVibration(true);
        review.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        manager.createNotificationChannel(review);

        NotificationChannel shown = new NotificationChannel(
                SHOWN_CHANNEL_ID, "Offer details", NotificationManager.IMPORTANCE_HIGH);
        shown.setDescription("What " + AppName.NAME + " read of an offer, or why it couldn't, after Dasher's own "
                + "alert already rang for it: shown without a sound of its own.");
        shown.setSound(null, null);
        shown.enableVibration(false);
        manager.createNotificationChannel(shown);
        manager.deleteNotificationChannel(RETIRED_PASSING_CHANNEL_ID);
        manager.deleteNotificationChannel(RETIRED_SILENT_REVIEW_CHANNEL_ID);
    }

    /** A short, bundled three-note chime reserved for offers that pass. */
    static Uri passingSound(Context context) {
        return Uri.parse("android.resource://" + context.getPackageName() + "/" + R.raw.offer_pass_chime);
    }

    /** Readiness covers the three offer channels and a notice channel the user has already blocked. */
    static boolean canNotify(Context context) {
        if (!canNotify(context, CHANNEL_ID) || !canNotify(context, REVIEW_CHANNEL_ID)
                || !canNotify(context, SHOWN_CHANNEL_ID)) {
            return false;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel reminder = manager.getNotificationChannel(ConsentReminder.CHANNEL_ID);
        // This channel is created only when a notice waits. Its absence before then is not a missing permission.
        return reminder == null || reminder.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private static boolean canNotify(Context context, String channelId) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationChannel channel = manager.getNotificationChannel(channelId);
        return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    /**
     * The app's funnel for the status bar. Looked up by name because this build has no generated R class; falls
     * back to a system icon if the resource is missing.
     */
    @SuppressWarnings("DiscouragedApi")
    static int smallIcon(Context context) {
        int id = context.getResources().getIdentifier("ic_notification", "drawable", context.getPackageName());
        return id != 0 ? id : android.R.drawable.stat_notify_more;
    }

    /**
     * Posts or replaces the Offer Filter card for one DoorDash notification: ringing when {@code ring}, otherwise
     * quietly (no sound and no pop-up).
     *
     * @return true only when Android accepted the post. The caller must keep the original DoorDash notification
     *         whenever this returns false.
     */
    static boolean notifyOffer(Context context, String tag, PendingIntent doorDashIntent, OfferRule.Result result,
                               String detail, boolean ring) {
        return notifyOffer(context, tag, doorDashIntent, result, detail, ring, "");
    }

    /**
     * As {@link #notifyOffer(Context, String, PendingIntent, OfferRule.Result, String, boolean)}, titled with the
     * store Dasher's notification names ("Taco Bell offer: open Dasher to check it") when it names one.
     */
    static boolean notifyOffer(Context context, String tag, PendingIntent doorDashIntent, OfferRule.Result result,
                               String detail, boolean ring, String store) {
        return notifyOffer(context, tag, doorDashIntent, result, detail, ring, store, false);
    }

    /**
     * As {@link #notifyOffer(Context, String, PendingIntent, OfferRule.Result, String, boolean, String)}; with
     * {@code preferDasherOwn} (Dasher's launcher brought Dasher up and it never drew this offer), the card's tap tries
     * Dasher's own notification intent first, its launcher after.
     */
    static boolean notifyOffer(Context context, String tag, PendingIntent doorDashIntent, OfferRule.Result result,
                               String detail, boolean ring, String store, boolean preferDasherOwn) {
        return post(context, new Card(tag, result, detail).dasherOwn(doorDashIntent).ring(ring).store(store)
                .preferDasherOwn(preferDasherOwn));
    }

    /**
     * One card: the offer it is for (its tag), what it says, and how it shows. Not ringing, a card either pops up with
     * no sound of its own ({@link #shown}: it carries what was read of the offer, or why it could not be read), or
     * stays quiet in the shade (the card posted while a peek reads the offer).
     */
    static final class Card {
        final String tag;
        final OfferRule.Result result;
        final String detail;
        PendingIntent dasherOwn;
        boolean ring;
        boolean shown;
        String store = "";
        boolean preferDasherOwn;
        /** When the offer's own countdown runs out (wall clock), as read on Dasher's screen; 0 when not known. */
        long endsAt;

        Card(String tag, OfferRule.Result result, String detail) {
            this.tag = tag;
            this.result = result;
            this.detail = detail;
        }

        Card dasherOwn(PendingIntent intent) {
            dasherOwn = intent;
            return this;
        }

        Card ring(boolean on) {
            ring = on;
            return this;
        }

        /** Not ringing, it pops up all the same, with no sound of its own: it carries what was read. */
        Card shown(boolean on) {
            shown = on;
            return this;
        }

        Card store(String name) {
            store = name == null ? "" : name;
            return this;
        }

        Card preferDasherOwn(boolean on) {
            preferDasherOwn = on;
            return this;
        }

        Card endsAt(long wallClock) {
            endsAt = wallClock;
            return this;
        }
    }

    /**
     * Posts or replaces one card: ringing on its result's channel (the pass chime for KEEP, "Offers to check" for
     * REVIEW), popping up silently on {@link #SHOWN_CHANNEL_ID}, or quietly in the shade; never for DECLINE. With the
     * offer's countdown known from Dasher's screen, the card counts down and goes when the offer does (after
     * {@link #CARD_MS} at most); otherwise it goes after {@link #CARD_MS}.
     *
     * @return true only when Android accepted the post. The caller must keep the original DoorDash notification
     *         whenever this returns false.
     */
    static boolean post(Context context, Card card) {
        if (card.result == OfferRule.Result.DECLINE) {
            clear(context, card.tag);
            return false;
        }
        ensureChannel(context);
        String own = card.result == OfferRule.Result.KEEP ? CHANNEL_ID : REVIEW_CHANNEL_ID;
        boolean audible = card.ring;
        // A card that need not ring but carries what was read pops up silently; with that channel blocked, it goes
        // quietly on its own channel instead (still in the shade, as before).
        boolean popUp = !audible && card.shown && canNotify(context, SHOWN_CHANNEL_ID);
        String channel = popUp ? SHOWN_CHANNEL_ID : own;
        if (!canNotify(context, channel)) {
            DiagnosticLog.log(context, "alert", "replacement unavailable; original notification retained; channel="
                    + channel);
            return false;
        }

        String body = card.detail == null ? "" : card.detail;
        if (body.length() > MAX_BODY_CHARS) body = body.substring(0, MAX_BODY_CHARS) + "…";
        Notification.Builder builder = new Notification.Builder(context, channel)
                .setSmallIcon(smallIcon(context))
                .setContentTitle(title(card.result, card.store))
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setOnlyAlertOnce(!audible)
                .setCategory(Notification.CATEGORY_RECOMMENDATION);
        long left = card.endsAt > 0 ? card.endsAt - System.currentTimeMillis() : 0;
        if (left > 0) {
            // The offer's own countdown, as Dasher showed it: the card counts down with it and goes when it does.
            builder.setWhen(card.endsAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                    .setTimeoutAfter(Math.min(CARD_MS, left));
        } else {
            builder.setTimeoutAfter(CARD_MS);
        }
        if (!audible && !popUp) {
            // A group child with summary-only alerting never alerts, even on a high-importance channel.
            builder.setGroup("quiet-" + card.tag).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY);
        }
        PendingIntent open = openDasherIntent(context, card.tag, card.dasherOwn, card.preferDasherOwn);
        if (open != null) builder.setContentIntent(open);

        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            // An update of a card Android shows does not pop up again (only once): one that pops up in place of a quiet
            // card, or of one that rang on its own channel, goes up afresh, once.
            if (popUp && postedElsewhere(manager, card.tag)) manager.cancel(card.tag, NOTIFICATION_ID);
            // A decline of an earlier offer may still have the sound turned down; this offer's alert must be heard.
            if (audible) OfferSilencer.yieldToPassingAlert(context);
            manager.notify(card.tag, NOTIFICATION_ID, builder.build());
            // Where Dasher was, whether the screen was on, and what was playing (Dasher rings on the alarm stream, usage
            // 4; streams and usages only, never an app), so a report can tell whether our card rang over Dasher.
            DiagnosticLog.log(context, "alert", "posted " + card.result + " audibleRequested=" + audible
                    + (popUp ? " shown silently" : "") + (left > 0 ? " countdown=" + (left + 999) / 1000 + "s" : "")
                    + " " + OfferFilterService.windowsNow(context) + " playing=" + OfferSilencer.playing(context));
            return true;
        } catch (RuntimeException error) {
            DiagnosticLog.log(context, "alert", "post failed; original retained: " + error.getClass().getSimpleName());
            return false;
        }
    }

    /** Whether Android shows our card with this tag on a channel other than the silent pop-up one. */
    private static boolean postedElsewhere(NotificationManager manager, String tag) {
        try {
            for (StatusBarNotification posted : manager.getActiveNotifications()) {
                if (posted.getId() != NOTIFICATION_ID || !tag.equals(posted.getTag())) continue;
                Notification notification = posted.getNotification();
                return notification == null || !SHOWN_CHANNEL_ID.equals(notification.getChannelId());
            }
        } catch (RuntimeException unknown) {
            // Not known: posted as an update, as before.
        }
        return false;
    }

    /** "Taco Bell offer: open Dasher to check it", or "DoorDash offer …" when the store is not named. */
    static String title(OfferRule.Result result, String store) {
        String offer = (store == null || store.trim().isEmpty() ? "DoorDash" : store.trim()) + " offer";
        return result == OfferRule.Result.KEEP ? offer + " meets your rules" : offer + ": open Dasher to check it";
    }

    /**
     * The card's tap: {@link OpenDasherActivity}, which opens Dasher as its launcher icon does (in its own half when
     * it is already in split screen, into the other half when only ours is) and clears the card. DoorDash's own content intent only when Dasher has no launch intent, and only when
     * DoorDash created it.
     */
    private static PendingIntent openDasherIntent(Context context, String tag, PendingIntent doorDashIntent,
                                                  boolean preferDasherOwn) {
        PendingIntent dashers = doorDashIntent != null && DASHER_PACKAGE.equals(doorDashIntent.getCreatorPackage())
                ? doorDashIntent : null;
        return OpenDasherActivity.forCard(context, tag, dashers, preferDasherOwn && dashers != null);
    }

    /** Whether the card with this tag is still posted (not cleared, timed out, or tapped away). */
    static boolean showing(Context context, String tag) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return false;
        try {
            for (StatusBarNotification posted : manager.getActiveNotifications()) {
                if (posted.getId() == NOTIFICATION_ID && tag.equals(posted.getTag())) return true;
            }
        } catch (RuntimeException unknown) {
            return true;
        }
        return false;
    }

    static void clear(Context context, String tag) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(tag, NOTIFICATION_ID);
    }

    /**
     * Removes every Offer Filter card, whatever its tag. Used when the listener (re)connects: per-notification
     * state from before a process restart is gone, so its cards would otherwise linger beside replayed ones.
     */
    static void clearAll(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        try {
            for (StatusBarNotification posted : manager.getActiveNotifications()) {
                if (posted.getId() == NOTIFICATION_ID) manager.cancel(posted.getTag(), NOTIFICATION_ID);
            }
        } catch (RuntimeException error) {
            DiagnosticLog.log(context, "alert", "could not list own cards: " + error.getClass().getSimpleName());
        }
    }

    private OfferAlerts() {}
}
