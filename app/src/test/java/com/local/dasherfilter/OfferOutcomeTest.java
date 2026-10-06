package com.local.dasherfilter;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Looper;
import android.view.View;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The user's 0.4.45 report: a ticket stamped "DECLINED" for an offer they accepted ("this is incorrect. i accepted
 * this one"). The rules declined it for its 4 stops (max 3), the app tapped Decline, the user touched the screen and
 * took it over, and accepted it. The stamp, the skyline's flag and what screen readers hear follow what became of the
 * offer: "YOURS" for one left to the user, "ACCEPTED" for one later counted or seen accepted, and "DECLINED" only
 * when the app's decline went through; the rules' verdict stays on the ticket as a line of its own.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class OfferOutcomeTest extends AndroidAdapterTestBase {
    /** The offer on the user's ticket: $26.30 for 29.2 mi, 71 min and 4 stops, at most 3 stops allowed. */
    private static final OfferSnapshot REPORTED = new OfferSnapshot(2630, 29.2, 71, 4);
    private static final String TOO_MANY = "4 stops exceeds maximum 3";

    /** The reported offer's line as the screen recorded it, with {@code action}. */
    private static DecisionLog.Entry reported(long at, DecisionLog.Action action, boolean autoDecline) {
        return new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false, REPORTED, 0, OfferRule.Result.DECLINE,
                TOO_MANY, action, autoDecline, Arrays.asList("$26.30", "4 stops (29.2 mi) • 71 min")).withScore(124);
    }

    private static DecisionLog.Entry line(OfferRule.Result result, String reason, DecisionLog.Action action) {
        return new DecisionLog.Entry(System.currentTimeMillis(), DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(1500, 6.0, 25, 2), 1300, result, reason, action, true, Collections.emptyList());
    }

    private static DecisionLog.Entry step(DecisionLog.Entry entry, DecisionLog.StepKind kind, String detail) {
        return entry.withStep(new DecisionLog.Step(kind, entry.at + 5_000, detail));
    }

    /** The user's own case: declined by the rules, Decline tapped, then taken over. */
    private void recordTakenOver() {
        long at = System.currentTimeMillis();
        // Dasher's notification of it came 13 s before the screen read it, and is folded into its line.
        DecisionLog.Entry notice = new DecisionLog.Entry(at - 13_000, DecisionLog.Source.NOTIFICATION, false,
                REPORTED, 0, OfferRule.Result.DECLINE, TOO_MANY, DecisionLog.Action.SEEN_ON_SCREEN, true,
                Collections.emptyList());
        DecisionLog.record(app, reported(at, DecisionLog.Action.DECLINE_TAPPED, true).withNotification(notice));
        DecisionLog.record(app, reported(at, DecisionLog.Action.USER_TOOK_OVER, true));
    }

    @Test
    public void anOfferTheUserTookOverIsStampedYoursAndKeepsTheRulesVerdictAsALine() {
        // This fixture checks the day palette; Auto legitimately changes after 6 pm on the test machine.
        assertTrue(Appearance.choose(app, Appearance.Mode.DAY));
        FilterStore.save(app, new FilterSettings(true, 1300, 385, 41, 475, 3));
        recordTakenOver();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            openTicket(content);
            Decor.Stamp stamp = find(content, Decor.Stamp.class);
            // Before, "DECLINED" in red: the rules' verdict, not what became of the offer.
            assertEquals("YOURS", stamp.word());
            assertEquals("Left to you", stamp.getContentDescription().toString());
            assertFalse("the Activity uses the selected day palette", new Ui(activity.get()).dark);
            assertEquals("a neutral ink", new Ui(activity.get()).inkSecondary, stamp.color());
            assertNotNull("the rules' verdict stays in view",
                    findText(content, "Rules: decline — too many stops (4, max 3)"));
            assertNotNull(shownTextContaining(content,
                    "You touched the screen and took over; nothing more tapped · on screen"));
            assertNotNull("the original score is distinguished from today's rule preview",
                    findText(content, "Score reference · 124% at decision"));
            assertNotNull(shownTextContaining(content, "Dasher's notification 13 s earlier"));
            assertTrue(findChart(content).getContentDescription().toString().startsWith("Chart of the last 1 offers: 0 passed, 0 declined, 1 left to you, 0 need review."));
        }
    }

    @Test
    public void anOfferAcceptedLaterIsStampedAcceptedInGreen() {
        assertTrue(Appearance.choose(app, Appearance.Mode.DAY));
        FilterStore.save(app, new FilterSettings(true, 1300, 385, 41, 475, 3));
        recordTakenOver();
        // The user's Accept tap was seen and Dasher showed a delivery screen: the acceptance tracker's step.
        assertTrue(DecisionLog.markStep(app, REPORTED, DecisionLog.StepKind.ACCEPTED_NOT_LEARNED,
                "you tapped Accept, and Dasher showed a delivery screen", 60_000));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            openTicket(content);
            Decor.Stamp stamp = find(content, Decor.Stamp.class);
            assertEquals("ACCEPTED", stamp.word());
            assertEquals("Accepted", stamp.getContentDescription().toString());
            assertFalse("the Activity uses the selected day palette", new Ui(activity.get()).dark);
            assertEquals("green", 0xFF0E8A0E, stamp.color());
            assertNotNull(findText(content, "Rules: decline — too many stops (4, max 3)"));
            assertTrue(findChart(content).getContentDescription().toString().startsWith("Chart of the last 1 offers: 0 passed, 1 accepted, 0 declined, 0 need review."));
            settleSky(content);
            String said = find(content, MinimumsStarView.class).getAccessibilityNodeProvider()
                    .createAccessibilityNodeInfo(MinimumsStarView.OFFER_ID).getContentDescription().toString();
            assertTrue(said, said.startsWith("Offer $26.30, 29.2 mi, 71 min, 4 stops, accepted, rules said decline"));
        }

        DecisionLog.Entry tookOver = reported(System.currentTimeMillis(), DecisionLog.Action.USER_TOOK_OVER, true);
        for (DecisionLog.StepKind kind : new DecisionLog.StepKind[] {DecisionLog.StepKind.ACCEPTED_LEARNED,
                DecisionLog.StepKind.ACCEPTED_NOT_LEARNED, DecisionLog.StepKind.ACCEPTED_ADD_ON}) {
            assertEquals(kind.name(), DecisionLog.Outcome.ACCEPTED, DecisionLog.outcome(step(tookOver, kind, "")));
        }
        // A seen Accept tap, then a delivery screen that taught nothing (a delivery was already under way).
        DecisionLog.Entry tapped = step(tookOver, DecisionLog.StepKind.ACCEPT_TAPPED, "waiting for a delivery screen");
        assertEquals(DecisionLog.Outcome.ACCEPTED, DecisionLog.outcome(step(tapped, DecisionLog.StepKind.NOT_LEARNED,
                AcceptedOfferTracker.DELIVERY_UNDER_WAY)));
        assertEquals(DecisionLog.Outcome.ACCEPTED, DecisionLog.outcome(step(tapped, DecisionLog.StepKind.NOT_LEARNED,
                "it may have run out: its countdown showed 0:04, 2 s before a read found it gone")));
        // An Accept tap alone, or followed by anything but a delivery screen, or a Decline after it, is no acceptance.
        assertEquals(DecisionLog.Outcome.YOURS, DecisionLog.outcome(tapped));
        assertEquals(DecisionLog.Outcome.YOURS, DecisionLog.outcome(step(tapped, DecisionLog.StepKind.NOT_LEARNED,
                "another offer came first")));
        assertEquals(DecisionLog.Outcome.YOURS, DecisionLog.outcome(step(tapped,
                DecisionLog.StepKind.ACCEPT_UNCONFIRMED, "")));
        assertEquals(DecisionLog.Outcome.YOURS, DecisionLog.outcome(step(step(tapped,
                DecisionLog.StepKind.DECLINE_QUESTION, ""), DecisionLog.StepKind.NOT_LEARNED,
                AcceptedOfferTracker.BEGAN_TO_DECLINE)));
        // A delivery screen without a seen Accept tap is the tracker's to judge: it said not learned, so not accepted.
        assertEquals(DecisionLog.Outcome.YOURS, DecisionLog.outcome(step(tookOver, DecisionLog.StepKind.NOT_LEARNED,
                AcceptedOfferTracker.DELIVERY_UNDER_WAY)));
        // An offer that passed and was accepted is stamped accepted too, with the rules' pass beside it.
        DecisionLog.Entry passed = step(line(OfferRule.Result.KEEP, "meets enabled rules",
                DecisionLog.Action.PASSES), DecisionLog.StepKind.ACCEPTED_LEARNED, "");
        assertEquals(DecisionLog.Outcome.ACCEPTED, DecisionLog.outcome(passed));
        assertEquals("Rules: pass — meets your rules", MainActivity.reasonLine(passed));
        // The tally follows the observed outcome, while the original rule verdict remains on its ticket.
        assertEquals(DecisionLog.Tally.PASSED, DecisionLog.tally(step(tookOver,
                DecisionLog.StepKind.ACCEPTED_LEARNED, "")));
    }

    @Test
    public void onlyADeclineThatWentThroughIsStampedDeclined() {
        assertTrue(Appearance.choose(app, Appearance.Mode.DAY));
        FilterStore.save(app, new FilterSettings(true, 1300, 385, 41, 475, 3));
        DecisionLog.record(app, reported(System.currentTimeMillis(), DecisionLog.Action.DECLINE_TAPPED, true));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            openTicket(content);
            Decor.Stamp stamp = find(content, Decor.Stamp.class);
            assertEquals("DECLINED", stamp.word());
            assertEquals("Declined", stamp.getContentDescription().toString());
            assertFalse("the Activity uses the selected day palette", new Ui(activity.get()).dark);
            assertEquals("red", 0xFFC62828, stamp.color());
            assertNotNull("the stamp says the verdict, so the reason stands alone",
                    findText(content, "Too many stops (4, max 3)"));
            assertNull(shownTextContaining(content, "Rules:"));
        }

        long now = System.currentTimeMillis();
        for (DecisionLog.Action action : new DecisionLog.Action[] {DecisionLog.Action.DECLINE_TAPPED,
                DecisionLog.Action.CONFIRMATION_TAPPED, DecisionLog.Action.NOTIFICATION_DECLINE_SENT}) {
            DecisionLog.Entry entry = reported(now, action, true);
            assertEquals(action.name(), DecisionLog.Outcome.DECLINED, DecisionLog.outcome(entry));
            assertEquals(action.name(), DecisionLog.Tally.FILTERED, DecisionLog.tally(entry));
        }
        // Left to the user: taken over, paused, refused by Android, its question not confirmed; and a hidden
        // notification, which declines nothing.
        for (DecisionLog.Action action : new DecisionLog.Action[] {DecisionLog.Action.USER_TOOK_OVER,
                DecisionLog.Action.PAUSED, DecisionLog.Action.DECLINE_REFUSED,
                DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.Action.NOTIFICATION_HIDDEN,
                DecisionLog.Action.SEEN_ON_SCREEN}) {
            DecisionLog.Entry entry = reported(now, action, action != DecisionLog.Action.PAUSED);
            assertEquals(action.name(), DecisionLog.Outcome.YOURS, DecisionLog.outcome(entry));
            assertEquals(action.name(), "Rules: decline — too many stops (4, max 3)", MainActivity.reasonLine(entry));
        }
        assertEquals(DecisionLog.Outcome.YOURS, DecisionLog.outcome(
                reported(now, DecisionLog.Action.DECLINE_TAPPED, false)));
        assertEquals(DecisionLog.Outcome.PASSED, DecisionLog.outcome(line(OfferRule.Result.KEEP,
                "meets enabled rules", DecisionLog.Action.PASSES)));
        assertEquals(DecisionLog.Outcome.REVIEW, DecisionLog.outcome(line(OfferRule.Result.REVIEW, "pay not found",
                DecisionLog.Action.NEEDS_REVIEW)));
        assertEquals("Pay not readable", MainActivity.reasonLine(line(OfferRule.Result.REVIEW, "pay not found",
                DecisionLog.Action.NEEDS_REVIEW)));
    }

    @Test
    public void nightTicketsKeepTheObservedOutcomeWithReadableNightInks() {
        assertTrue(Appearance.choose(app, Appearance.Mode.NIGHT));
        FilterStore.save(app, new FilterSettings(true, 1300, 385, 41, 475, 3));
        long now = System.currentTimeMillis();
        DecisionLog.Entry takenOver = reported(now, DecisionLog.Action.USER_TOOK_OVER, true);
        DecisionLog.Entry[] entries = {
            takenOver,
            step(takenOver, DecisionLog.StepKind.ACCEPTED_NOT_LEARNED,
                    "you tapped Accept, and Dasher showed a delivery screen"),
            reported(now, DecisionLog.Action.CONFIRMATION_TAPPED, true)
        };
        String[] words = {"YOURS", "ACCEPTED", "DECLINED"};
        String[] descriptions = {"Left to you", "Accepted", "Declined"};
        int[] colors = {0xFFC3C2B7, 0xFF53C953, 0xFFFF6B6B};
        for (int i = 0; i < entries.length; i++) {
            DecisionLog.clear(app);
            DecisionLog.record(app, entries[i]);
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                View content = activity.get().findViewById(android.R.id.content);
                openTicket(content);
                Decor.Stamp stamp = find(content, Decor.Stamp.class);
                assertTrue("the Activity uses the selected night palette", new Ui(activity.get()).dark);
                assertTrue("the outcome stamp is visible", stamp.isShown());
                assertEquals(words[i], stamp.word());
                assertEquals(descriptions[i], stamp.getContentDescription().toString());
                assertEquals(words[i] + " night ink", colors[i], stamp.color());
                if (i < 2) {
                    assertNotNull("the rules do not overwrite a takeover or later acceptance",
                            shownTextContaining(content, "Rules: decline — too many stops (4, max 3)"));
                } else {
                    assertNotNull(shownTextContaining(content, "Too many stops (4, max 3)"));
                    assertNull(shownTextContaining(content, "Rules:"));
                }
            }
        }
    }

    @Test
    public void aFailingOfferLeftToTheUserCountsAsReviewNotFiltered() {
        long now = System.currentTimeMillis();
        DecisionLog.record(app, reported(now - 300_000, DecisionLog.Action.DECLINE_TAPPED, true));
        DecisionLog.record(app, reported(now - 300_000, DecisionLog.Action.USER_TOOK_OVER, true));
        DecisionLog.Entry second = new DecisionLog.Entry(now - 100_000, DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(790, 7.2, 21, 2), 1080, OfferRule.Result.DECLINE, "dollars per mile",
                DecisionLog.Action.DECLINE_TAPPED, true, Collections.emptyList());
        DecisionLog.record(app, second);
        int[] totals = DecisionLog.totals(app);
        assertEquals(1, totals[DecisionLog.Tally.FILTERED.ordinal()]);
        assertEquals(1, totals[DecisionLog.Tally.REVIEW.ordinal()]);
        // Its question was never confirmed: the app gave up, so it moves from filtered to review.
        DecisionLog.record(app, new DecisionLog.Entry(second.at, second.source, false, second.facts,
                second.requiredCents, second.result, second.reason, DecisionLog.Action.CONFIRMATION_NOT_TAPPED, true,
                Collections.emptyList()));
        totals = DecisionLog.totals(app);
        assertEquals(0, totals[DecisionLog.Tally.FILTERED.ordinal()]);
        assertEquals(2, totals[DecisionLog.Tally.REVIEW.ordinal()]);
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
        // Kept across a restart.
        DecisionLog.flush();
        DecisionLog.forgetCache();
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-420dpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void theSkylineFlagFollowsTheOutcome() {
        Ui ui = new Ui(app);
        long now = System.currentTimeMillis();
        DecisionLog.Entry tookOver = reported(now - 120_000, DecisionLog.Action.USER_TOOK_OVER, true);
        DecisionLog.Entry accepted = step(reported(now - 60_000, DecisionLog.Action.USER_TOOK_OVER, true),
                DecisionLog.StepKind.ACCEPTED_LEARNED, "");
        DecisionLog.Entry declined = reported(now, DecisionLog.Action.CONFIRMATION_TAPPED, true);
        DecisionChartView chart = new DecisionChartView(app, ui);
        List<DecisionLog.Entry> newestFirst = new ArrayList<>(Arrays.asList(declined, accepted, tookOver));
        chart.setEntries(newestFirst);
        int width = ui.dp(411);
        int height = ui.dp(160);
        chart.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        chart.layout(0, 0, width, height);
        // The buildings rise, then rest.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        // Oldest first: taken over (neutral), accepted (green), declined (red). The full badge belongs to selection.
        int[] expected = {Ui.NEUTRAL, Ui.GOOD, Ui.CRITICAL};
        for (int i = 0; i < expected.length; i++) {
            chart.select(i);
            bitmap.eraseColor(Color.TRANSPARENT);
            chart.draw(new Canvas(bitmap));
            float[] flag = chart.flagAt(i);
            assertNotNull(flag);
            // The badge's fill, beside its symbol.
            int pixel = bitmap.getPixel(Math.round(flag[0] - ui.dp(5)), Math.round(flag[1]));
            assertColor("selected flag " + i, expected[i], pixel);
            for (int j = 0; j < expected.length; j++) if (j != i) {
                float[] quiet = chart.flagAt(j);
                assertEquals("unselected flag has no filled badge", 0, Color.alpha(bitmap.getPixel(
                        Math.round(quiet[0] - ui.dp(5)), Math.round(quiet[1]))));
                assertTrue("unselected outcome symbol remains visible", hasInk(bitmap, quiet, ui));
            }
        }
        assertTrue(chart.getContentDescription().toString().startsWith("Chart of the last 3 offers: 0 passed, 1 accepted, 1 declined, 1 left to you, 0 need review."));
        // The building keeps the muted rules' color: all three were declined by the rules as written.
        for (int i = 0; i < expected.length; i++) {
            float[] flag = chart.flagAt(i);
            int wall = bitmap.getPixel(Math.round(flag[0] - ui.dp(9)), Math.round(flag[1] + ui.dp(30)));
            assertColor("building " + i, ui.dark ? 0xFF87645E : 0xFFAA8C81, wall);
        }
        assertFalse(DecisionLog.Outcome.YOURS.isVerdict(OfferRule.Result.DECLINE));
        bitmap.recycle();
    }

    private static boolean hasInk(Bitmap bitmap, float[] flag, Ui ui) {
        int cx = Math.round(flag[0]), cy = Math.round(flag[1]), reach = ui.dp(4);
        for (int y = cy - reach; y <= cy + reach; y++) {
            for (int x = cx - reach; x <= cx + reach; x++) {
                if (bitmap.getPixel(x, y) == ui.inkSecondary) return true;
            }
        }
        return false;
    }

    private static void assertColor(String what, int expected, int actual) {
        boolean near = Math.abs(Color.red(expected) - Color.red(actual)) <= 3
                && Math.abs(Color.green(expected) - Color.green(actual)) <= 3
                && Math.abs(Color.blue(expected) - Color.blue(actual)) <= 3 && Color.alpha(actual) == 0xFF;
        assertTrue(what + ": expected " + Integer.toHexString(expected) + ", drawn " + Integer.toHexString(actual),
                near);
    }
}
