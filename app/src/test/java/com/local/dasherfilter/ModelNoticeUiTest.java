package com.local.dasherfilter;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The one-time 0.5.0 notice (finalSpec.migration, ONE-TIME NOTICE) on the real homepage: shown once, after the first-run
 * notice, until a button is tapped, again on a recreated screen; the owner's lines that apply, in his order; the pass
 * check only from 20 offers and when fewer than one in five would pass; "Use typical minimums" saving $4.00, $1.00 a
 * mile and $15 an hour from exactly the minimums (the owner's decision D1: Autopilot's bar back at 100 first), keeping
 * max stops and the switch, then asking for the goal; "Set up Autopilot" asking for it at once; and no notice at all
 * for plain strict rules or a new install.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, qualifiers = "w411dp-h914dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class ModelNoticeUiTest extends AndroidAdapterTestBase {
    /** The owner's notice (his Oct 5–6 rules): every line that applies to them, in the spec's order. */
    private static final String OWNER_LINES = "• Score by area is gone: an offer now has to meet each of your minimums."
            + "\n\n• Per stop is gone. Your $12.75 per stop now counts as a $25.50 minimum pay, so single orders are "
            + "judged the same. Use Max stops to limit stacked orders."
            + "\n\n• Your 80% buffer is now built into your minimums: $20.40 · $4.00/mi · $28.80/hr."
            + "\n\n• Pay per item is gone: shopping time is already in each offer's minutes."
            + "\n\n• Learned minimums are gone; they only ever rose. Autopilot can adjust to your offers instead."
            + "\n\n• Auto-accept is off until you turn it on again in Settings, because your rules changed.";

    @Before public void plansOnThisThread() {
        AutopilotRuntime.forgetCache();
        AutopilotRuntime.executorForTests = Runnable::run;
        ShadowAlertDialog.reset();
    }

    @After public void plansOnTheirOwnThread() {
        AutopilotRuntime.executorForTests = null;
        AutopilotRuntime.forgetCache();
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE);
    }

    /**
     * What 0.4.73 left on the owner's phone (Oct 5–6, as ModelMigrationTest): his shape in area mode, an 80% buffer,
     * per stop and per item, learned values and auto-accept on; max stops {@code maxStops}.
     */
    private void ownerShape(int maxStops) {
        prefs().edit()
                .putBoolean("enabled", true).putInt("flat", 1950).putInt("mile", 500).putInt("minute", 60)
                .putInt("per_stop", 1275).putInt("per_item", 1790).putInt("hotspot_proximity_hundredths", 0)
                .putInt("minimum_scale_percent", 80).putInt("max_stops", maxStops)
                .putBoolean("rising_offers", true).putBoolean("score_by_area", true)
                .putInt("last_accepted", 1785)
                .putInt("best_mile_pay", 1350).putLong("best_miles_bits", Double.doubleToLongBits(3.3))
                .putBoolean("auto_accept_matching_offers", true)
                .commit();
    }

    /** A 0.4.73 save of plain strict rules: every key written, the retired ones at their defaults but per stop. */
    private void plainSave(boolean enabled, int flat, int mile, int minute, int perStop, int maxStops) {
        prefs().edit()
                .putBoolean("enabled", enabled).putInt("flat", flat).putInt("mile", mile).putInt("minute", minute)
                .putInt("per_stop", perStop).putInt("per_item", 0).putInt("hotspot_proximity_hundredths", 0)
                .putInt("minimum_scale_percent", 100).putInt("max_stops", maxStops)
                .putBoolean("rising_offers", false).putBoolean("score_by_area", false)
                .commit();
    }

    /**
     * A standalone screen line {@code minutesAgo} minutes ago, as the older version wrote it before the update (no
     * rules model, bar or Autopilot in its JSON): what the pass check reads on an updated phone.
     */
    private static DecisionLog.Entry line(Integer pay, Double miles, Integer minutes, long minutesAgo) {
        DecisionLog.Entry entry = new DecisionLog.Entry(System.currentTimeMillis() - minutesAgo * 60_000L,
                DecisionLog.Source.SCREEN, false, new OfferSnapshot(pay, miles, minutes, 2), 0,
                OfferRule.Result.KEEP, "meets enabled rules", DecisionLog.Action.PASSES, true,
                Collections.<String>emptyList());
        try {
            org.json.JSONObject json = entry.toJson();
            json.remove("model");
            json.remove("bar");
            json.remove("auto");
            DecisionLog.Entry legacy = DecisionLog.Entry.fromJson(json);
            assertEquals(DecisionLog.LEGACY_MODEL, legacy.model);
            return legacy;
        } catch (org.json.JSONException unexpected) {
            throw new AssertionError(unexpected);
        }
    }

    /**
     * {@code count} distinct offers, newest first, {@code passing} of them (the newest) meeting $20.40 · $4.00/mi ·
     * $28.80/hr at exactly 100% ($25.00 for 5 mi and 20 to 39 min: at most $20.40 asked) and the rest not ($12.00).
     */
    private static List<DecisionLog.Entry> offers(int count, int passing) {
        List<DecisionLog.Entry> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) lines.add(line(i < passing ? 2500 : 1200, 5.0, 20 + i % 20, 2 + i));
        return lines;
    }

    /** Records {@code newestFirst} oldest first, as they happened. */
    private void record(List<DecisionLog.Entry> newestFirst) {
        for (int i = newestFirst.size() - 1; i >= 0; i--) DecisionLog.record(app, newestFirst.get(i));
    }

    private static View page(ActivityController<MainActivity> activity) {
        return activity.get().findViewById(android.R.id.content);
    }

    private static void refreshed() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static String title(AlertDialog dialog) {
        return String.valueOf(Shadows.shadowOf(dialog).getTitle());
    }

    private static String message(AlertDialog dialog) {
        return String.valueOf(Shadows.shadowOf(dialog).getMessage());
    }

    private static boolean shows(AlertDialog dialog, int which) {
        return dialog.getButton(which) != null && dialog.getButton(which).getVisibility() == View.VISIBLE;
    }

    /** The notice on screen now, or null. */
    private static AlertDialog notice() {
        AlertDialog latest = ShadowAlertDialog.getLatestAlertDialog();
        return latest != null && latest.isShowing() && MainActivity.NOTICE_TITLE.equals(title(latest)) ? latest : null;
    }

    @Test
    public void theOwnersNoticeComesOnceAfterTheFirstRunNoticeInHisOrderAndStaysUntilAnswered() {
        ConsentedTestApp.forget(app);
        ownerShape(0);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            refreshed();
            assertNull("never behind the first-run notice", ShadowAlertDialog.getLatestAlertDialog());
            shownButton(content, Consent.ACCEPT).performClick();
            refreshed();
            AlertDialog notice = notice();
            assertNotNull("after the first-run notice is accepted", notice);
            assertTrue("through OwnWindowTouches.show", notice.getWindow().getCallback().getClass().getName()
                    .endsWith("OwnWindowTouches$TrackedCallback"));
            assertEquals("Your rules are simpler now", title(notice));
            assertEquals(OWNER_LINES, message(notice));
            assertFalse("no pass check without 20 offers to judge", message(notice).contains("would have passed"));
            assertEquals("OK", notice.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertEquals("Set up Autopilot", notice.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
            assertFalse(shows(notice, AlertDialog.BUTTON_NEGATIVE));

            // Back does not answer it.
            notice.onBackPressed();
            idle();
            assertTrue("it stays until a button is tapped", notice.isShowing());
            assertNotNull(FilterStore.peekModelNotice(app));

            // A recreated screen (a resize, day and night) shows it again, once.
            activity.recreate();
            refreshed();
            AlertDialog again = notice();
            assertNotNull(again);
            assertNotSame(notice, again);
            assertFalse(notice.isShowing());
            assertEquals(OWNER_LINES, message(again));
            refreshed();
            assertTrue("one notice, not one a second", again == ShadowAlertDialog.getLatestAlertDialog());

            again.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            idle();
            assertNull("answered", FilterStore.peekModelNotice(app));
            assertFalse(again.isShowing());
            assertTrue("OK asks nothing more", again == ShadowAlertDialog.getLatestAlertDialog());
            FilterSettings rules = FilterStore.load(app);
            assertArrayEquals("the migrated rules stay", new int[] {2040, 400, 48, 0, 0, 0}, rules.minimums());
            assertFalse(rules.autopilot);

            ShadowAlertDialog.reset();
            activity.recreate();
            refreshed();
            assertNull("never again", notice());
        }
        ShadowAlertDialog.reset();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            refreshed();
            assertNull(notice());
        }
    }

    /**
     * A third of a split screen is the driving strip (the mascot, the latest verdict, the status: nothing else, so
     * Dasher's map keeps two thirds): the notice waits there, kept in the store, since nothing it names is on screen
     * and a dialog that only a button closes would stand in the strip a driver glances at. Dragging the divider back
     * makes the screen again with the page, and the notice comes on it.
     */
    @Test @Config(qualifiers = "w411dp-h300dp-420dpi")
    public void inTheDrivingStripTheNoticeWaitsForThePage() {
        ownerShape(0);
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            refreshed();
            DrivingStrip strip = find(page(activity), DrivingStrip.class);
            assertNotNull("a third of a split screen: the strip", strip);
            assertTrue(strip.isShown());
            assertNull("no dialog over the strip", ShadowAlertDialog.getLatestAlertDialog());
            refreshed();
            assertNull("nor at a later refresh", ShadowAlertDialog.getLatestAlertDialog());
            assertNotNull("kept for the page", FilterStore.peekModelNotice(app));
            // The strip's own controls work as ever, nothing in their way.
            find(strip, DrivingStrip.MascotButton.class).performClick();
            idle();
            assertFalse("the mascot pauses", FilterStore.load(app).enabled);
            assertNull(ShadowAlertDialog.getLatestAlertDialog());

            // The divider dragged back: the screen made again, now with the page, and the notice on it.
            RuntimeEnvironment.setQualifiers("w411dp-h914dp-420dpi");
            activity.recreate();
            refreshed();
            View content = page(activity);
            assertNull("the page, not the strip", find(content, DrivingStrip.class));
            assertTrue(find(content, MinimumsStarView.class).isShown());
            AlertDialog notice = notice();
            assertNotNull("the notice, once the page shows", notice);
            assertEquals(OWNER_LINES, message(notice));
            notice.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            idle();
            assertNull("answered", FilterStore.peekModelNotice(app));
        }
    }

    @Test
    public void readmeRulesHearOnlyThatPerStopWasBelowTheirPayAndSetUpAutopilotAsksForTheGoal() {
        plainSave(true, 1300, 385, 41, 475, 3);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            refreshed();
            AlertDialog notice = notice();
            assertNotNull(notice);
            assertEquals("• Per stop is gone. Your $4.75 per stop was below your $13.00 minimum pay, so single orders "
                    + "are judged the same. Use Max stops to limit stacked orders.", message(notice));
            notice.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            idle();
            assertNull("answered", FilterStore.peekModelNotice(app));
            AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("Set up Autopilot asks for the goal", AutopilotText.CHOOSER_TITLE, title(chooser));
            assertEquals("the top tier checked", 0, chooser.getListView().getCheckedItemPosition());
            Shadows.shadowOf(chooser).clickOnItem(0);
            idle();
            FilterSettings on = FilterStore.load(app);
            assertTrue(on.autopilot);
            assertEquals(FilterSettings.GOAL_TOP_TIER, on.autopilotGoalPercent);
            assertArrayEquals("its rules stay", new int[] {1300, 385, 41, 0, 0, 0}, on.minimums());
            assertEquals(3, on.maxStops);
        }
    }

    @Test
    public void thePassCheckCountsTheNewestTwoHundredReadStandaloneOffersAndSpeaksOnlyBelowOneInFive() {
        FilterSettings owner = FilterSettings.of(true, 2040, 400, 48, 0);
        assertNull("19 offers are too few to say", MainActivity.passCheck(owner, offers(19, 0)));
        assertArrayEquals(new int[] {3, 20}, MainActivity.passCheck(owner, offers(20, 3)));
        assertNull("4 of 20 is one in five: nothing to warn of", MainActivity.passCheck(owner, offers(20, 4)));
        assertArrayEquals(new int[] {0, 20}, MainActivity.passCheck(owner, offers(20, 0)));

        // Judged at exactly the minimums: a bar below 100 passes nothing more here.
        FilterSettings low = owner.withAutopilot(true, 70).withMinimumScalePercent(50);
        assertArrayEquals(new int[] {3, 20}, MainActivity.passCheck(low, offers(20, 3)));

        // Add-ons, replays and offers with pay, miles or minutes unread are not counted.
        List<DecisionLog.Entry> mixed = new ArrayList<>(offers(20, 3));
        mixed.add(0, new DecisionLog.Entry(System.currentTimeMillis(), DecisionLog.Source.SCREEN, true,
                new OfferSnapshot(2500, 5.0, 20, 2), 0, OfferRule.Result.KEEP, "meets enabled rules",
                DecisionLog.Action.PASSES, true, Collections.<String>emptyList()));
        mixed.add(0, line(2500, 5.0, 20, 1).withAlertTag("offer", true));
        mixed.add(0, line(null, 5.0, 20, 1));
        mixed.add(0, line(2500, null, 20, 1));
        mixed.add(0, line(2500, 5.0, null, 1));
        assertArrayEquals(new int[] {3, 20}, MainActivity.passCheck(owner, mixed));

        // Only the newest 200: older offers that would pass do not soften it.
        List<DecisionLog.Entry> many = new ArrayList<>(offers(200, 0));
        many.addAll(offers(50, 50));
        assertArrayEquals(new int[] {0, 200}, MainActivity.passCheck(owner, many));
    }

    @Test
    public void useTypicalMinimumsStartsFromExactlyTheMinimumsKeepsMaxStopsAndTheSwitchAndAsksForTheGoal() {
        ownerShape(3);
        // A phone that had 0.5.0 before (Autopilot on, its bar at 82%), went back to 0.4.73 and came forward again.
        prefs().edit().putBoolean("autopilot_on", true).putInt("autopilot_goal", 70)
                .putInt("autopilot_bar_percent", 82).putBoolean("autopilot_goal_asked", true).commit();
        record(offers(20, 3));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            refreshed();
            AlertDialog notice = notice();
            assertNotNull(notice);
            assertEquals(OWNER_LINES + "\n\n• Your minimums would have passed 3 of your last 20 offers.",
                    message(notice));
            assertEquals("Use typical minimums", notice.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertEquals("Keep mine", notice.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
            assertFalse(shows(notice, AlertDialog.BUTTON_NEUTRAL));
            assertEquals(82, FilterStore.load(app).minimumScalePercent);

            notice.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            idle();
            assertNull("answered", FilterStore.peekModelNotice(app));
            FilterSettings typical = FilterStore.load(app);
            assertArrayEquals(new int[] {400, 100, 25, 0, 0, 0}, typical.minimums());
            assertEquals("max stops stay", 3, typical.maxStops);
            assertTrue("and the switch", typical.enabled);
            assertEquals("Autopilot's bar is back at exactly the minimums", 100, typical.minimumScalePercent);
            assertTrue(typical.autopilot);
            AutopilotStore.Change change = AutopilotStore.lastChange(app);
            assertNotNull(change);
            assertEquals(82, change.from);
            assertEquals(100, change.to);
            assertEquals("RULES_CHANGED", change.why);
            String log = DiagnosticLog.read(app);
            int back = log.indexOf("[autopilot] commit 82% -> 100% (you changed your minimums)");
            int chosen = log.indexOf("[rules] typical minimums chosen from the 0.5.0 notice");
            assertTrue(log, back >= 0 && chosen > back);
            Autopilot.Plan plan = AutopilotRuntime.latest();
            assertNotNull(plan);
            assertEquals("the plan for them starts from 100", 100, plan.current);
            assertEquals(typical.rulesKey(), plan.rulesKey);
            assertEquals("Typical minimums set: $4 · $1/mi · $15/hr", ShadowToast.getTextOfLatestToast());
            AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("then the goal chooser", AutopilotText.CHOOSER_TITLE, title(chooser));
            assertEquals("its stored goal checked", 0, chooser.getListView().getCheckedItemPosition());
        }
    }

    /**
     * Minimums that are the typical ones already, after score by area: the pass check still speaks, but "Use typical
     * minimums" would change nothing, so the notice offers OK and "Set up Autopilot" instead.
     */
    @Test
    public void typicalMinimumsAlreadyHearThePassCheckButAreNotOfferedThemAgain() {
        prefs().edit()
                .putBoolean("enabled", true).putInt("flat", 400).putInt("mile", 100).putInt("minute", 25)
                .putInt("per_stop", 0).putInt("per_item", 0).putInt("hotspot_proximity_hundredths", 0)
                .putInt("minimum_scale_percent", 100).putInt("max_stops", 0)
                .putBoolean("rising_offers", false).putBoolean("score_by_area", true)
                .commit();
        // Twenty distinct offers (the same facts within two minutes would be one offer seen twice) that pay $3.00.
        List<DecisionLog.Entry> low = new ArrayList<>();
        for (int i = 0; i < 20; i++) low.add(line(300, 5.0, 20 + i, 2 + i));
        record(low);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            refreshed();
            AlertDialog notice = notice();
            assertNotNull(notice);
            assertEquals("• Score by area is gone: an offer now has to meet each of your minimums."
                    + "\n\n• Your minimums would have passed 0 of your last 20 offers.", message(notice));
            assertEquals("OK", notice.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertEquals("Set up Autopilot", notice.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
            assertFalse("no Keep mine: nothing else is offered", shows(notice, AlertDialog.BUTTON_NEGATIVE));
            notice.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            idle();
            assertNull(FilterStore.peekModelNotice(app));
            assertArrayEquals(new int[] {400, 100, 25, 0, 0, 0}, FilterStore.load(app).minimums());
            assertFalse(DiagnosticLog.read(app).contains("typical minimums chosen"));
        }
    }

    @Test
    public void keepMineKeepsTheMovedRulesAndAsksNothingMore() {
        ownerShape(0);
        record(offers(20, 3));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            refreshed();
            AlertDialog notice = notice();
            assertNotNull(notice);
            notice.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            idle();
            assertNull(FilterStore.peekModelNotice(app));
            assertTrue("nothing more opens", notice == ShadowAlertDialog.getLatestAlertDialog());
            FilterSettings kept = FilterStore.load(app);
            assertArrayEquals(new int[] {2040, 400, 48, 0, 0, 0}, kept.minimums());
            assertFalse(kept.autopilot);
            assertFalse(DiagnosticLog.read(app).contains("typical minimums chosen"));
        }
    }

    @Test
    public void plainStrictRulesAndANewInstallGetNoNotice() {
        plainSave(true, 700, 150, 30, 0, 3);
        record(offers(20, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            refreshed();
            assertNull("the Auto button is the invitation", notice());
            assertNull(FilterStore.peekModelNotice(app));
            assertArrayEquals(new int[] {700, 150, 30, 0, 0, 0}, FilterStore.load(app).minimums());
        }
    }

    @Test
    public void aNewInstallGetsNoNoticeAndNoRules() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            refreshed();
            assertNull(notice());
            assertNull(FilterStore.peekModelNotice(app));
            assertFalse("no automatic starters", FilterStore.load(app).hasAnyRule());
            assertNotNull("the start line offers them", shownTextContaining(page(activity), MainActivity.START_LINE));
        }
    }
}
