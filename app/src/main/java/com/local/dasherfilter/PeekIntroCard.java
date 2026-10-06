package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.LinearLayout;

/**
 * Once, the first time the homepage sees filtering on with Peek on: what Peek does, in one sentence, and where to turn
 * it off (BETA-29). A stranger whose phone briefly shows Dasher over another app should know why. "Peek settings"
 * opens Settings, where its switch is; either link closes the card for good. Peek itself is unchanged.
 */
final class PeekIntroCard {
    static final String PREFS = WhatsNewCard.PREFS;
    static final String SEEN = "peek_intro_seen";
    static final String LEAD = "Peek is on:";
    static final String TEXT = "when an offer arrives while Dasher is in the background, " + AppName.NAME
            + " briefly opens Dasher to read it. Turn off in Settings.";
    static final String SETTINGS = "Peek settings";

    private final Context context;
    private final OnboardingCard card;

    /** @param openSettings shows the Settings page, where Peek's switch is */
    PeekIntroCard(Context context, Ui ui, LinearLayout parent, Runnable openSettings) {
        this.context = context;
        card = new OnboardingCard(context, ui, parent);
        card.setWords(OnboardingCard.lead(LEAD, TEXT));
        card.addAction(ui, SETTINGS, () -> {
            seen();
            openSettings.run();
        });
        card.addAction(ui, "OK", this::seen);
    }

    /** Shown while filtering is on with Peek on, until either link was tapped once. */
    void refresh(boolean filteringOn) {
        card.show(filteringOn && FilterStore.peek(context) && !prefs(context).getBoolean(SEEN, false));
    }

    boolean shown() {
        return card.shown();
    }

    private void seen() {
        prefs(context).edit().putBoolean(SEEN, true).apply();
        DiagnosticLog.log(context, "peek", "introduction read");
        card.show(false);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
