package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The first-run notice and the user's consent to it. Everyone sees it once, existing installs included, and again
 * only when {@link #VERSION} is raised. Until the current version is accepted, the screen reader reads, decides and
 * taps nothing, the notification path posts, hides and sends nothing (but for the one reminder that the app is
 * paused, {@link ConsentReminder}), no feedback, diagnostics or daily count ({@link DailyCount}) leave the phone, and
 * the homepage shows only the notice.
 * "Not now" closes the app and stores nothing. Nothing here ever turns consent off again.
 */
final class Consent {
    /**
     * Raised when the notice, the terms or the privacy text change in substance, so everyone sees them once more. 14
     * (published with 0.5.0; 13 was the one before) covers accountless feedback, Autopilot and the acceptance-rate
     * reading it keeps, the screen held on during a dash, Peek's unlock catch-up and the dated beta terms and privacy
     * policy. 15 (0.5.1) covers Autopilot raising the minimums after offers have paid above them for a while (at most
     * 10% at a time, with Undo, and the switch that stops it) and the note of the last growth it keeps.
     */
    // 16: separate prospective study scope; accepting this notice never enables that optional choice.
    // 17: separate area mode and local acceptance learning; study confirmation remains independent.
    // 18 (0.6.1): learning on by default, so a new high you accept raises the learned minimums offers must meet.
    // 19 (0.6.2): Autopilot on by default, for new installs and once for installs updating, with goal 70%.
    // 20 (0.6.3): the anonymous daily count (DailyCount), on by default with its switch in Settings, and in Privacy
    // the install page's Download count; both kept by the feedback service only as daily totals.
    // 21 (0.6.10): the store skip list, a reason the app declines (any pay), and the driver's own cost per mile, one
    // of Autopilot's inputs (off by default); both kept on the phone like the rules.
    // 22 (0.6.11): Autopilot's bar rises to the score of an offer you accept yourself, for the rest of the dash; and in
    // split screen during a delivery the divider moves, by its own controls, to give the navigating app more room.
    // 23 (0.6.12): the pay minimum is per stop (rule C, max(1, stops − 1)), so stacked offers need more;
    // learning's pay best is per stop.
    // 24 (0.6.14): pay per stop counts every stop Dasher shows, the pickup included; the saved number is unchanged, so
    // a single order asks twice what it did.
    // 25 (0.6.17): the account point says plainly that DoorDash's terms ban automated tools that read or collect
    // data from its apps, and that this is one (the owner chose "Say it plainly", 14 October 2026).
    // 26 (0.6.18): Autopilot puts pay first by default (a 70% goal moves to pay first once; the goals that protect the
    // acceptance rate are opt-in), and the best score you accepted yourself in the last 7 days holds its bar across
    // dashes (the owner chose "Earnings first", 14 October 2026).
    // 27 (0.6.19): turning Autopilot off and on keeps that hold, the figures of offers accepted by hand are kept 7 days
    // so it lasts past the latest 200 offers, and with pay first the update counts the accepts of the last 7 days again
    // (the owner's report, 14 October 2026: "it dropped the auto score below my recent high again on 6.18").
    // 28 (0.6.21): an accept holds the bar for the 24 offers after it instead of 7 days (the owner: "We may have to do
    // it by like two dozen orders"); the minimums no longer grow on their own, so the notice no longer says they do
    // ("mins should grow as we accept offers with higher mins when adaptive / learned mins is on. autopilot is the
    // buffer": learning raises them, Autopilot moves its bar); and in split screen the divider gives a map's half 70%
    // whenever one shows, not only during a delivery (all the owner's, 15 October 2026).
    // 29 (0.6.27): offers are scored by area by default, for new installs and once for installs updating (none kept
    // whether Strict was picked), Strict a pick in How offers are judged (the owner, 17 October 2026: "it should be on
    // area score mode by default"). The notice's words are unchanged: the Terms and Privacy say it.
    // 30 (0.6.29): confirmed personal acceptances set independent Autopilot working minimums up or down;
    // readable plausible short trips can teach learned highs, and green shows the calculated learned envelope.
    // 31 (0.6.31): that acceptance also keeps its total pay, and in Area mode a quarter of each Autopilot score weighs
    // an offer's total pay against it (the owner, 22 October 2026: "we must factor in overall pay").
    // 32 (0.6.32): Autopilot's bar can come down to 10% of its working references instead of stopping at 50% (the
    // owner, 22 October 2026: "i think auto needs to be able to go below 50%, like maybe even 10%?").
    // 33 (0.6.33): split screen is retired, so the app no longer moves the split-screen divider for a map (the owner,
    // 22 October 2026: "i say let's get rid of split screen mode. we haven't been able to get the auto resizing to work
    // and it's so buggy").
    // 34 (0.6.34): one switch, Share anonymous diagnostics, for the summary after each dash, masked diagnostics with
    // feedback and the offer timing study, which were three choices; it starts off for everyone, and the notice asks
    // (the owner, 22 October 2026: "we should combine all diagnostics opt in into one").
    // 35 (0.6.35): paused, the app still reads each offer on Dasher's screen and shows its score and what it would do,
    // tapping nothing, where it read nothing of Dasher's screen before (the owner's "Read nothing" of 6 October 2026,
    // reversed by his ask of 22 October: "we still show what we would recommend and it's score even if the user toggles
    // off actual decline"); turning the app off in Accessibility is the full stop. The notice's words are unchanged:
    // the Terms and Privacy say it.
    // 36 (0.6.37): an offer you decline yourself raises Autopilot's bar to just above its score for the 24 offers after
    // it, even during an accept's hold (the owner, 23 October 2026: "if i decline an offer then autopilot should raise
    // my mins to its"); to keep the notice short, its Area sentence now says only that the scores weigh total pay too.
    // 37 (0.6.40): an offer you accept yourself moves Autopilot's bar to that offer's own score as it showed, holding
    // it there for the next 24 offers (it stayed where it was, and the offer then scored the bar), so add-ons and
    // automatic accepts, which ask the saved minimums at the bar, follow it; an accept the history holds that set no
    // working minimums still holds the bar at its score; and the bar's last 40 changes are kept for the chart's bar
    // line (the owner, 23 October 2026: "the app should be declarative and therefore naturally self healing"). The
    // notice's words are unchanged: the Terms and Privacy say it.
    // 38 (0.6.44): retained confirmed personal history now repairs the current working shape and bar, including an
    // older incorrectly normalized shape, from the original score and numeric rules after the last policy reset.
    // The acceptance's 24-offer hold fixes the bar at that score unless a newer personal decline holds it higher.
    // No new collection; the current notice and normal safe point are still required. The Terms and Privacy and
    // notice's What changed box disclose this use of already retained history; the short points stay unchanged.
    static final int VERSION = 38;
    static final String PREFS = "consent";
    static final String ACCEPTED_VERSION = "accepted_version";
    static final String ACCEPTED_AT = "accepted_at";

    static final String TITLE = "Before you start";

    /**
     * The notice: a few short points, each a bold lead and a line or two after it. 0.6.20 cut it from 11 points and
     * about 1,230 words to 7 and about 400 (the owner, 14 October 2026: "that screen is way too long"): what it does,
     * what is on by default and what leaves the phone, each once, with the details left to the Terms and Privacy it
     * links to (ConsentGateTest checks they are there). The acceptance-rate and account warnings are word for word as
     * before. 0.6.20 changed words only, so VERSION stayed; 0.6.21 changed what it says (VERSION 28).
     */
    static final String[][] POINTS = {
        {"Acceptance rate.", "Automatic declines may dramatically lower your DoorDash acceptance rate. "
                + "Only continue if you understand and accept that risk."},
        {"Your Dasher account.", AppName.NAME + " is not made by, endorsed by or affiliated with DoorDash. "
                + "DoorDash's terms ban automated tools that read or collect data from its apps, "
                + "and this is one. DoorDash could limit or deactivate your account."},
        {"What it does.", "It reads Dasher's screen and notifications on this phone and declines offers that fail "
                + "your rules, or come from stores on your skip list, by tapping Decline and Dasher's usual question "
                + "(\"Are you sure you want to decline this offer?\"). Any other question it leaves to you and pauses. "
                + "It may also go Back after a Dasher error, tap Dasher's ⚙ once to bring up a missing offer, briefly "
                + "turn offer sound down and keep your unlocked screen on during a dash. Auto-accept is off unless you "
                + "turn it on in Settings. Your touch stops it."},
        {"On by default.", "Autopilot puts pay first. Its bar moves from " + FilterStore.BAR_MIN + "% to "
                + FilterStore.BAR_MAX + "% of its working references. Each offer you accept yourself sets its "
                + "readable active minimums up or down to that offer's values; the latest acceptance replaces earlier "
                + "levels. Each offer you decline yourself raises its bar just above that offer. In Area mode, its "
                + "scores also weigh total pay. This can allow offers below your saved or learned minimums, or "
                + "decline offers that meet them. Learning separately raises learned minimums when you accept an "
                + "offer that beats your best. Peek can briefly open Dasher to read a background offer. An anonymous "
                + "daily count tells the developer how many people use the app. Turn any of them off: Autopilot from "
                + "its chip, learning with Learn, Peek and the count in Settings."},
        {"At your own risk.", "It can misread, accept or decline an offer you did not want it to. It comes with no warranty."},
        {"Not while driving.", "Don't handle your phone while driving. Pull over to look at offers."},
        {"Your data.", "Your offers, rules and history stay on this phone. Feedback and reports leave only when you "
                + "tap Send, and masked diagnostics only with the switch below on: after each dash and with feedback "
                + "you send. What you type is sent as written, and masking can miss details. The feedback service "
                + "keeps what you send 90 days, and the developer may review it with AI tools (Anthropic's Claude or "
                + "OpenAI's ChatGPT/Codex). The Terms and Privacy say the rest, including what updates, the daily "
                + "count, place lookups and map links send."},
    };
    static final String ACCEPT = "I understand and accept";
    static final String AGREEMENT = "By tapping " + ACCEPT + ", you acknowledge this acceptance-rate risk, "
            + "choose to use " + AppName.NAME + " at your own risk and accept the Terms.";
    static final String NOT_NOW = "Not now";
    /**
     * The notice's last control (0.6.18; the owner chose "The install page and the notice ask pilot Dashers to turn
     * diagnostics on, and it stays opt-in", 14 October 2026): one switch showing Settings' "Share anonymous
     * diagnostics" as it is, off on a new install (0.6.20; 0.6.18 showed it unchecked every time, and the owner asked,
     * 14 October 2026: "share diagnostics was turned off by default again on the splash screen even though I had it
     * turned on last time"). From 0.6.34 it is the one switch for every diagnostic, so it shows off once for everyone:
     * no earlier, narrower choice is read as this one. Accepting the notice leaves Settings' switch as this one is left
     * ({@link Feedback#setDiagnostics} only when that changes it). The same summaries, masking and limits as that
     * switch.
     */
    static final String DIAGNOSTICS_ASK = "Share anonymous diagnostics (helps fix it for your phone)";

    /** Whether the current notice was accepted. Cheap enough for every read: Android keeps the prefs in memory. */
    static boolean accepted(Context context) {
        return prefs(context).getInt(ACCEPTED_VERSION, 0) >= VERSION;
    }

    /**
     * The user explicitly accepted the notice: kept at once, before anything that waited for it runs, and the reminder
     * that the app is paused ({@link ConsentReminder}) goes.
     */
    static void accept(Context context) {
        FilterStore.invalidateLearningEvidence(context);
        prefs(context).edit().putInt(ACCEPTED_VERSION, VERSION).putLong(ACCEPTED_AT, System.currentTimeMillis())
                .commit();
        ConsentReminder.cancel(context);
        DiagnosticLog.log(context, "consent", "notice " + VERSION + " accepted; screen reading and background offers "
                + "may act");
        // Feedback the user sent before an updated notice waited for it.
        FeedbackOutbox.consented(context);
        // So did the anonymous daily count (on unless turned off in Settings); it goes off this thread.
        DailyCount.trigger(context);
        // How the app last stopped is looked at from now on: before the notice, Android's record of it is not read.
        StopReports.install(context);
        StopReports.checkSoon(context);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private Consent() {}
}
