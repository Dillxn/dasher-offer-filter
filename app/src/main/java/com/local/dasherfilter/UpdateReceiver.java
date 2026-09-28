package com.local.dasherfilter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

public final class UpdateReceiver extends BroadcastReceiver {
    static final String INSTALL_RESULT = "com.local.dasherfilter.INSTALL_RESULT";

    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            Updater.clearReady(context);
            Updater.status(context, "Updated to " + Updater.version(context));
            Updater.schedule(context);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Updater.schedule(context);
        } else if (INSTALL_RESULT.equals(intent.getAction())) {
            int session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
            if (session != Updater.prefs(context).getInt("session", -2)) return;
            int result = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (result == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirmation != null) Updater.confirmation(context, confirmation);
            } else if (result == PackageInstaller.STATUS_SUCCESS) {
                Updater.clearReady(context);
                Updater.status(context, "Updated to " + Updater.version(context));
                Updater.schedule(context);
            } else {
                Updater.prefs(context).edit().remove("session").remove("confirmation_needed").apply();
                Updater.status(context, "Android did not install the update. Tap Check / install update to retry.");
            }
        }
    }
}
