package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.widget.LinearLayout;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Once after an update: "What is new in <version>", a few bundled lines ({@link BundledNotes}) and OK, which closes it
 * for good. Not after a new install (nothing is new to it), never behind the notice (the homepage then refreshes
 * nothing), and never with placeholder words: until the docs package writes them, there is nothing to show. Not after
 * an update whose notice already said what changed either ({@link #toldByNotice}): the update's news shows once.
 */
final class WhatsNewCard {
    static final String PREFS = "onboarding";
    /** The version whose notes were shown and closed (or a new install's own version: nothing new to it). */
    static final String SEEN = "whats_new_seen";

    private final Context context;
    private final Function<String, List<String>> notes;
    private final OnboardingCard card;
    /** This version's name, asked once: the page refreshes every second. */
    private String version;
    /**
     * The lines this card shows, worked out once for this page (whether this install was updated is a PackageManager
     * call, not one for every second's refresh); null until then.
     */
    private List<String> lines;
    /** Closed, or nothing to show for this version: no more asking until the page is made again. */
    private boolean done;
    private String shownFor;

    WhatsNewCard(Context context, Ui ui, LinearLayout parent) {
        this(context, ui, parent, BundledNotes::whatsNew);
    }

    /** @param notes what is new in a version, by its name (the bundled notes, or a test's) */
    WhatsNewCard(Context context, Ui ui, LinearLayout parent, Function<String, List<String>> notes) {
        this.context = context;
        this.notes = notes;
        card = new OnboardingCard(context, ui, parent);
        card.addAction(ui, "OK", this::dismiss);
    }

    static String title(String version) {
        return "What is new in " + version;
    }

    void refresh() {
        if (done) return;
        if (lines == null) {
            if (version == null) version = Updater.version(context);
            lines = pending(context, version, notes.apply(version));
        }
        boolean show = !lines.isEmpty();
        if (show && !version.equals(shownFor)) {
            shownFor = version;
            card.setWords(OnboardingCard.titled(title(version), lines));
        }
        card.show(show);
        done = !show;
    }

    boolean shown() {
        return card.shown();
    }

    /**
     * The lines to show for {@code version} now: none once shown and closed for it, none for a new install (marked
     * seen, so its first update shows that update's notes), else {@code lines}.
     */
    static List<String> pending(Context context, String version, List<String> lines) {
        SharedPreferences prefs = prefs(context);
        String seen = prefs.getString(SEEN, null);
        if (version.equals(seen)) return Collections.emptyList();
        if (seen == null && !updated(context)) {
            prefs.edit().putString(SEEN, version).apply();
            return Collections.emptyList();
        }
        return lines;
    }

    /**
     * The notice's What changed box told this update's news, and the reader accepted it: the card would only say it
     * again, so it counts as seen for this version (the owner, 7 October 2026: "we don't need to show new features
     * update twice").
     */
    static void toldByNotice(Context context) {
        prefs(context).edit().putString(SEEN, Updater.version(context)).apply();
    }

    /** Whether this install was ever updated (an older version was on the phone before). */
    static boolean updated(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.lastUpdateTime > info.firstInstallTime;
        } catch (Exception unknown) {
            return false;
        }
    }

    private void dismiss() {
        prefs(context).edit().putString(SEEN, version != null ? version : Updater.version(context)).apply();
        card.show(false);
        done = true;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
