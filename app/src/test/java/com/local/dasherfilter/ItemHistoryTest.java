package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import static org.junit.Assert.*;

/** Observed numeric item context survives history and temporary stores; legacy unknowns stay unknown. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
public final class ItemHistoryTest {
    private Application app;

    @Before public void setUp() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        DecisionLog.flush();
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        DecisionLog.flush();
        ActiveRouteStore.clear(app);
        ManualDeclines.forget(app);
        RestartSuppression.clear(app);
        Settings.Global.putInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, 7);
    }

    @After public void flush() { DecisionLog.flush(); }

    private static OfferSnapshot offer(Integer items, boolean applicable) {
        return new OfferSnapshot(1800, 4.0, 25, 2).withItems(items, applicable);
    }

    private static DecisionLog.Entry entry(long at, OfferSnapshot facts, int score) {
        return new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false, facts, 1400,
                OfferRule.Result.KEEP, "meets enabled rules", DecisionLog.Action.PASSES, true,
                Collections.emptyList()).withScore(score);
    }

    private static JSONObject reportJson(ProblemReport report) throws Exception {
        int start = report.body.indexOf("```json\n") + 8;
        return new JSONObject(report.body.substring(start, report.body.indexOf("\n```", start)));
    }

    @Test public void numericCountAndApplicabilityRoundTripWithoutChangingRecordedScore() throws Exception {
        DecisionLog.Entry original = entry(1000, offer(12, true), 137);
        JSONObject json = original.toJson();
        assertEquals(12, json.getInt("items"));
        assertTrue(json.getBoolean("itemCountApplicable"));
        HashSet<String> keys = new HashSet<>();
        for (java.util.Iterator<String> it = json.keys(); it.hasNext();) keys.add(it.next());
        assertEquals(new HashSet<>(Arrays.asList("at", "source", "addOn", "required", "result", "reason",
                "action", "autoDecline", "evidence", "pay", "miles", "minutes", "stops", "items",
                "itemCountApplicable", "score")), keys);
        DecisionLog.Entry loaded = DecisionLog.Entry.fromJson(json);
        assertEquals(Integer.valueOf(12), loaded.facts.items);
        assertTrue(loaded.facts.itemCountApplicable);
        assertEquals(137, loaded.scorePercent);
        assertEquals(original.requiredCents, loaded.requiredCents);
        assertTrue(DecisionLog.facts(loaded.facts).contains("12 items"));
    }

    @Test public void legacyHistoryDoesNotInventItemsOrRecalculateItsScore() throws Exception {
        JSONObject legacy = entry(1000, offer(null, false), 86).toJson();
        assertFalse(legacy.has("items"));
        assertFalse(legacy.has("itemCountApplicable"));
        DecisionLog.Entry loaded = DecisionLog.Entry.fromJson(legacy);
        assertNull(loaded.facts.items);
        assertFalse(loaded.facts.itemCountApplicable);
        assertEquals(86, loaded.scorePercent);
        assertFalse(DecisionLog.facts(loaded.facts).contains("item"));
        assertEquals(-1, DecisionLog.Entry.fromJson(entry(1000, offer(null, false), -1).toJson()).scorePercent);
    }

    @Test public void declaredUnreadCountStaysUnknownAndNeverBecomesOneOrZero() throws Exception {
        JSONObject json = entry(1000, offer(null, true), -1).toJson();
        assertFalse(json.has("items"));
        assertTrue(json.getBoolean("itemCountApplicable"));
        OfferSnapshot loaded = DecisionLog.Entry.fromJson(json).facts;
        assertNull(loaded.items);
        assertTrue(loaded.itemCountApplicable);
        assertTrue(DecisionLog.facts(loaded).contains("item count unknown"));
        assertTrue(DecisionLog.facts(offer(1, true)).contains("1 item"));
    }

    @Test public void malformedCountsAreUnknownRatherThanCoercedIntoObservedCounts() throws Exception {
        for (Object malformed : new Object[] {0, -1, 3.5, "12", "unread", Long.MAX_VALUE, JSONObject.NULL}) {
            JSONObject json = entry(1000, offer(null, true), 103).toJson().put("items", malformed);
            DecisionLog.Entry loaded = DecisionLog.Entry.fromJson(json);
            assertNull("count " + malformed, loaded.facts.items);
            assertTrue(loaded.facts.itemCountApplicable);
            assertEquals(103, loaded.scorePercent);
        }
        JSONObject explicitCount = entry(1000, offer(null, false), 103).toJson().put("items", 12);
        assertTrue(DecisionLog.Entry.fromJson(explicitCount).facts.itemCountApplicable);
    }

    @Test public void diskHistoryAndFoldedNotificationKeepObservedItemsAndOriginalScores() {
        DecisionLog.Entry notification = new DecisionLog.Entry(900, DecisionLog.Source.NOTIFICATION, false,
                offer(null, true), 0, OfferRule.Result.REVIEW, "items not read", DecisionLog.Action.CHECK_BELL,
                true, Collections.emptyList()).withScore(-1);
        DecisionLog.record(app, entry(1000, offer(12, true), 137).withNotification(notification));
        DecisionLog.flush();
        FilterStore.save(app, new FilterSettings(true, 0, 0, 0, 0, 0).withPerItem(9999));
        DecisionLog.forgetCache();
        DecisionLog.Entry loaded = DecisionLog.recent(app, 1).get(0);
        assertEquals(Integer.valueOf(12), loaded.facts.items);
        assertEquals(137, loaded.scorePercent);
        assertTrue(loaded.notification.facts.itemCountApplicable);
        assertNull(loaded.notification.facts.items);
        assertEquals(-1, loaded.notification.scorePercent);
        assertTrue(DecisionLog.report(app, 1).contains("12 items"));
    }

    @Test public void reportsCarryNumericItemsAndAnEmptyBestWithoutLearningFromHistory() throws Exception {
        FilterSettings rules = new FilterSettings(true, 0, 0, 0, 0, 0).withPerItem(150);
        FilterStore.save(app, rules);
        DecisionLog.Entry original = entry(1000, offer(12, true), 137);
        DecisionLog.record(app, original);
        ProblemReport report = ProblemReport.build(ProblemReport.Kind.USER_REPORT, "test", rules, original,
                Collections.emptyList(), null, null, Collections.singletonList(original));
        JSONObject json = reportJson(report);
        assertEquals(150, json.getJSONObject("rules").getInt("perItemCents"));
        assertEquals(12, json.getJSONObject("entry").getInt("items"));
        assertTrue(json.getJSONObject("entry").getBoolean("itemCountApplicable"));
        assertEquals(137, json.getJSONObject("entry").getInt("score"));
        assertEquals(12, json.getJSONArray("recent").getJSONObject(0).getInt("items"));
        assertEquals(0, json.getJSONObject("rules").getJSONObject("bestAccepted").getInt("items"));
        assertEquals(0, json.getJSONObject("rules").getJSONObject("bestAccepted").getInt("itemPay"));
        assertFalse(FilterStore.load(app).best.hasPerItem());
        assertFalse(json.getJSONObject("rules").getJSONObject("declinedByHand").has("items"));
        assertTrue(report.body.contains("12 items"));
        String shared = DiagnosticLog.fullReport(app);
        assertTrue(shared.contains("per-item cents=150"));
        assertTrue(shared.contains("Current when this report was generated"));
        assertTrue(shared.contains("12 items"));
    }

    @Test public void activeRouteInvalidatesStaleItemTotalAtProgressAndPreservesScope() {
        ActiveRouteStore.save(app, offer(12, true));
        assertEquals(Integer.valueOf(12), ActiveRouteStore.load(app).items);
        ActiveRouteStore.invalidateTravel(app);
        OfferSnapshot progressed = ActiveRouteStore.load(app);
        assertNull(progressed.items);
        assertTrue(progressed.itemCountApplicable);
        assertEquals(Integer.valueOf(1800), progressed.payCents);
        assertNull(progressed.miles);
        assertNull(progressed.minutes);
        assertNull(progressed.stops);
        ActiveRouteStore.save(app, offer(null, false));
        assertNull(ActiveRouteStore.load(app).items);
        assertFalse(ActiveRouteStore.load(app).itemCountApplicable);
    }

    @Test public void pendingManualDeclineRetainsObservedCountWithoutAStaleCountOnReplacement() {
        long now = System.currentTimeMillis();
        ManualDeclines.declined(app, offer(12, true), now);
        assertEquals(Integer.valueOf(12), ManualDeclines.pending(app, now).items);
        assertTrue(ManualDeclines.pending(app, now).itemCountApplicable);
        ManualDeclines.declined(app, offer(null, true), now + 1);
        assertNull(ManualDeclines.pending(app, now + 1).items);
        assertTrue(ManualDeclines.pending(app, now + 1).itemCountApplicable);
        ManualDeclines.declined(app, offer(null, false), now + 2);
        assertFalse(ManualDeclines.pending(app, now + 2).itemCountApplicable);
        assertFalse(app.getSharedPreferences("offer_filter_manual_declines", Context.MODE_PRIVATE).contains("items"));
    }

    @Test public void restartRecordRetainsCountsButUnreadCountStillCannotEndSuppression() throws Exception {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
        assertTrue(RestartSuppression.remember(app, offer(12, true), 35));
        RestartSuppression.Saved saved = RestartSuppression.load(app);
        assertNotNull(saved);
        assertEquals(Integer.valueOf(12), saved.offer.items);
        assertTrue(saved.offer.itemCountApplicable);
        assertNull(saved.offer.finalStopHotspotMiles);
        assertEquals(new HashSet<>(Arrays.asList("elapsed", "boot", "countdown", "pay", "miles", "minutes",
                "stops", "items", "item_count_applicable")),
                app.getSharedPreferences(RestartSuppression.PREFS, Context.MODE_PRIVATE).getAll().keySet());
        Class<?> takeoverClass = Class.forName("com.local.dasherfilter.OfferFilterService$Takeover");
        Constructor<?> constructor = takeoverClass.getDeclaredConstructor(OfferSnapshot.class, long.class);
        constructor.setAccessible(true);
        Object takeover = constructor.newInstance(saved.offer, SystemClock.uptimeMillis());
        Method covers = takeoverClass.getDeclaredMethod("covers", OfferSnapshot.class, long.class);
        covers.setAccessible(true);
        assertEquals(Boolean.TRUE, covers.invoke(takeover, offer(null, true), SystemClock.uptimeMillis() + 1));
        assertEquals(Boolean.TRUE, covers.invoke(takeover, offer(null, false), SystemClock.uptimeMillis() + 1));
        assertEquals(Boolean.FALSE, covers.invoke(takeover, offer(13, true), SystemClock.uptimeMillis() + 1));
        ShadowSystemClock.advanceBy(Duration.ofMillis(RestartSuppression.MAX_MS));
        assertNull(RestartSuppression.load(app));
    }

    @Test public void unreadItemRereadDoesNotProveANewOfferForManualDeclineLearning() {
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0, true, 0).withPerItem(100));
        long now = System.currentTimeMillis();
        ManualDeclines.declined(app, offer(12, true), now);
        ManualDeclines.offerSeen(app, offer(null, true), now + 1);
        ManualDeclines.offerSeen(app, offer(null, false), now + 2);
        assertTrue(FilterStore.load(app).declined.isEmpty());
        assertEquals(Integer.valueOf(12), ManualDeclines.pending(app, now + 2).items);
        ManualDeclines.offerSeen(app, offer(13, true), now + 3);
        assertNull(ManualDeclines.pending(app, now + 3));
        assertEquals(1800, FilterStore.load(app).declined.payCents);
        assertEquals(100, FilterStore.load(app).perItemCents);
    }

    @Test public void repeatedManualDeclineWithUnreadItemsDoesNotTeachUntilLegacyFactsChange() {
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0, true, 0).withPerItem(100));
        long now = System.currentTimeMillis();
        ManualDeclines.declined(app, offer(12, true), now);
        ManualDeclines.declined(app, offer(null, true), now + 1);
        assertTrue(FilterStore.load(app).declined.isEmpty());
        ManualDeclines.offerSeen(app, new OfferSnapshot(1800, 5.0, 25, 2).withItems(null, true), now + 2);
        assertEquals(1800, FilterStore.load(app).declined.payCents);
        assertEquals(100, FilterStore.load(app).perItemCents);
    }

    @Test public void legacyTemporaryRecordsRemainUnreadAndOutsideDeclaredItemScope() {
        ActiveRouteStore.save(app, offer(null, false));
        ManualDeclines.declined(app, offer(null, false), System.currentTimeMillis());
        assertTrue(RestartSuppression.remember(app, offer(null, false), 35));
        for (OfferSnapshot legacy : Arrays.asList(ActiveRouteStore.load(app),
                ManualDeclines.pending(app, System.currentTimeMillis()), RestartSuppression.load(app).offer)) {
            assertNull(legacy.items);
            assertFalse(legacy.itemCountApplicable);
        }
    }
}
