package com.local.dasherfilter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Boot and self-update events, plus PackageInstaller results for this app's own session. Not exported. */
public final class UpdateReceiver extends BroadcastReceiver {
    static final String INSTALL_RESULT = "com.local.dasherfilter.INSTALL_RESULT";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            updated(context);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            LauncherAppearance.sync(context, Appearance.resolve(context));
            OfferSilencer.restore(context);
            Updater.schedule(context);
        } else if (INSTALL_RESULT.equals(action)) {
            installResult(context, intent);
        }
    }

    private static void installResult(Context context, Intent intent) {
        if (!Updater.isCurrentSession(context, intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1))) return;
        int result = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (result == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) {
                Updater.confirmation(context, confirmation);
            } else {
                Updater.installationFailed(context, result, "missing confirmation intent");
            }
        } else if (result == PackageInstaller.STATUS_SUCCESS) {
            updated(context);
        } else {
            Updater.installationFailed(context, result, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }

    private static void updated(Context context) {
        LauncherAppearance.sync(context, Appearance.resolve(context));
        // What an older version kept of Dasher's screens is cleaned up once, as soon as the update is in.
        DiagnosticLog.cleanUpSoon(context);
        // So is what the retired GitHub connection and its report queues left (a stored token among it).
        LegacyReportingCleanup.cleanUpSoon(context);
        OfferSilencer.restore(context);
        Updater.clearReady(context);
        Updater.status(context, "Updated to " + Updater.version(context));
        Updater.schedule(context);
        // Offer Filter's screen was up as the update began: it opens again only over its own window or the home screen,
        // never over Dasher, a map or any other app. With screen reading on, the screen reader alone decides, once it
        // has asked which app is in front (now, or as it reconnects after the update: until then nothing can say).
        if (Updater.relaunchPending(context)) OfferFilterService.relaunchAfterUpdate(context);
    }
}
