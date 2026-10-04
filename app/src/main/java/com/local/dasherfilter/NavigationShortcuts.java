package com.local.dasherfilter;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** User-tapped map handoffs. No location lookup, gas data, destination storage or automatic task placement. */
final class NavigationShortcuts {
    static final String MAPS = "com.google.android.apps.maps";
    static final String WAZE = "com.waze";

    private NavigationShortcuts() {}

    static final class Destination {
        final Uri maps;
        final Uri waze;

        Destination(Uri maps, Uri waze) {
            this.maps = maps;
            this.waze = waze;
        }

        Intent web() {
            return new Intent(Intent.ACTION_VIEW, maps);
        }
    }

    /** Ranking stays the Atlas's pooled historical pay/mile, with its existing sample minimum. */
    static AreaMap.Cell best(List<AreaMap.Cell> cells) {
        List<AreaMap.Cell> ranked = AreaMap.ranked(cells);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    static Destination area(AreaMap.Cell cell) {
        String point = String.format(Locale.US, "%.4f,%.4f", cell.latitude(), cell.longitude());
        Uri maps = Uri.parse("https://www.google.com/maps/dir/").buildUpon()
                .appendQueryParameter("api", "1")
                .appendQueryParameter("destination", point)
                .appendQueryParameter("travelmode", "driving")
                .appendQueryParameter("dir_action", "navigate").build();
        Uri waze = Uri.parse("https://waze.com/ul").buildUpon()
                .appendQueryParameter("ll", point).appendQueryParameter("navigate", "yes").build();
        return new Destination(maps, waze);
    }

    /** These are free-text searches, never a claim that a particular station is nearest or cheapest. */
    static Destination gas(boolean prices) {
        String query = prices ? "gas prices near me" : "gas stations near me";
        Uri maps = Uri.parse("https://www.google.com/maps/search/").buildUpon()
                .appendQueryParameter("api", "1").appendQueryParameter("query", query).build();
        // Waze's gas search uses the user's own distance/price sorting preference; its link API has no sort key.
        Uri waze = Uri.parse("https://waze.com/ul").buildUpon()
                .appendQueryParameter("q", "gas stations").build();
        return new Destination(maps, waze);
    }

    static Intent intent(PackageManager packages, Destination destination) {
        List<Intent> available = new ArrayList<>();
        Intent maps = destination.web().setPackage(MAPS);
        Intent waze = new Intent(Intent.ACTION_VIEW, destination.waze).setPackage(WAZE);
        if (maps.resolveActivity(packages) != null) available.add(maps);
        if (waze.resolveActivity(packages) != null) available.add(waze);
        if (available.isEmpty()) return destination.web();
        if (available.size() == 1) return available.get(0);
        Intent chooser = Intent.createChooser(available.get(0), "Open with");
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Parcelable[] {available.get(1)});
        return chooser;
    }

    static boolean open(Activity activity, Destination destination) {
        try {
            activity.startActivity(intent(activity.getPackageManager(), destination));
            return true;
        } catch (RuntimeException unavailable) {
            // An app can be disabled/uninstalled between resolution and launch. The universal URL still works
            // in a browser; no package-bound fallback or task-clearing flags are carried into it.
            try {
                activity.startActivity(destination.web());
                return true;
            } catch (RuntimeException noBrowser) {
                return false;
            }
        }
    }
}
