package com.local.dasherfilter;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

/** Native controls, honest scoring explanations, and preserved short-window facts in the fresh restoration. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36}, qualifiers = "w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class AreaLearningUiTest extends AndroidAdapterTestBase {
    private static final FilterSettings RULES = FilterSettings.of(true, 250, 100, 25, 3);
    private static final OfferSnapshot OFFER = new OfferSnapshot(800, 10.0, 20, 2);

    @After public void restoreFont() { RuntimeEnvironment.setFontScale(1f); }

    private void learn() {
        FilterStore.save(app, RULES);
        FilterStore.setAcceptanceLearning(app, true);
        OfferSnapshot accepted = new OfferSnapshot(800, 4.0, 20, 2);
        assertEquals(FilterStore.AcceptedLesson.RAISED, FilterStore.recordConfirmedManualAcceptance(app,
                FilterStore.captureLearning(app, accepted), accepted));
    }

    private static DecisionLog.Entry decided(FilterSettings rules, OfferSnapshot facts) {
        return DecisionLog.Entry.of(DecisionLog.Source.SCREEN, false, facts, OfferRule.evaluate(facts, rules),
                DecisionLog.Action.PASSES, true, Collections.emptyList());
    }

    /**
     * The radar's pay-per-stop spoke divides an offer's pay by its stops, the pickup included, as the requirement does:
     * $7.50 over 2 stops stands exactly on a $3.75-a-stop knob (0.6.13 drew it at $7.50 a stop), and over 3 stops it is
     * $2.50 a stop, drawn for the example's 2 stops at $5.00.
     */
    @Test public void theRadarReadsSevenFiftyOverTwoStopsAsThreeSeventyFiveAStop() {
        FilterSettings rules = FilterSettings.of(true, 375, 0, 0, 0);
        MinimumsStarView star = new MinimumsStarView(app, new Ui(app));
        OfferSnapshot example = new OfferSnapshot(null, 5.0, 20, 2);
        star.show(rules, example, java.util.Arrays.asList(decided(rules, new OfferSnapshot(750, 3.0, 15, 2)),
                decided(rules, new OfferSnapshot(750, 3.0, 15, 3))));
        star.compose(360, 300, 220);
        assertEquals(750, star.setAsks(AreaScore.PAY), 0);
        assertEquals("on the $3.75-a-stop knob", star.setAsks(AreaScore.PAY), star.markCents(0, AreaScore.PAY), 1e-9);
        assertEquals("$2.50 a stop, for the example's 2 stops", 500, star.markCents(1, AreaScore.PAY), 1e-9);
    }

    @Test public void modeAndLearningAreDiscoverableIndependentChoicesAndNeverEnableAutopilot() {
        FilterStore.save(app, RULES.withEnabled(false));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            // Learning is the radar's round button on the homepage (0.6.1); its long press shows what it learned.
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue("the learner's button is on the homepage", star.learnShown());
            AccessibilityNodeInfo learner = node(star, MinimumsStarView.SCORE_ID);
            assertEquals(MinimumsStarView.learnSaid(false), learner.getContentDescription().toString());
            boolean named = false;
            for (AccessibilityNodeInfo.AccessibilityAction action : learner.getActionList()) {
                named |= action.getId() == AccessibilityNodeInfo.ACTION_LONG_CLICK
                        && MinimumsStarView.LEARN_DETAILS.contentEquals(action.getLabel());
            }
            assertTrue("its long press says what it shows", named);
            assertTrue(act(star, MinimumsStarView.SCORE_ID, AccessibilityNodeInfo.ACTION_LONG_CLICK));
            AlertDialog details = ShadowAlertDialog.getLatestAlertDialog();
            Switch toggle = find(details.getWindow().getDecorView(), Switch.class);
            assertNotNull(toggle);
            assertFalse("the switch matches the button", toggle.isChecked());
            toggle.performClick();
            FilterSettings after = FilterStore.load(app);
            assertTrue(after.acceptanceLearning);
            assertFalse("changing rules never starts filtering", after.enabled);
            assertFalse("Auto is still its own off switch", after.autopilot);
            assertEquals(100, after.minimumScalePercent);
            assertArrayEquals(RULES.minimums(), after.minimums());
            details.dismiss();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            // A tap on the button itself turns learning off again at once, said in a toast.
            assertTrue(act(star, MinimumsStarView.SCORE_ID, AccessibilityNodeInfo.ACTION_CLICK));
            assertFalse(FilterStore.load(app).acceptanceLearning);
            assertEquals(MainActivity.LEARN_OFF_TOAST, ShadowToast.getTextOfLatestToast());

            // The scoring mode is the radar's middle word (0.6.6; the strip's own chip from 0.6.3), its own choice;
            // neither it nor learning is in Settings.
            assertNull("no mode chip in the strip", find(content, ModeChip.class));
            assertEquals(ModeChip.STRICT, star.modeShownWord());
            assertEquals(ModeChip.STRICT_SAID, node(star, MinimumsStarView.MODE_ID).getContentDescription().toString());
            assertTrue(act(star, MinimumsStarView.MODE_ID, AccessibilityNodeInfo.ACTION_CLICK));
            ShadowAlertDialog.getLatestAlertDialog().cancel();
            assertFalse(FilterStore.load(app).scoreByArea);
            assertTrue(act(star, MinimumsStarView.MODE_ID, AccessibilityNodeInfo.ACTION_CLICK));
            Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(1);
            after = FilterStore.load(app);
            assertTrue(after.scoreByArea);
            assertFalse("the mode leaves learning as it was", after.acceptanceLearning);
            assertFalse(after.enabled);
            assertFalse(after.autopilot);
            assertArrayEquals(RULES.minimums(), after.minimums());
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals("the radar says so", ModeChip.AREA, star.modeShownWord());
            assertEquals(ModeChip.AREA_SAID, node(star, MinimumsStarView.MODE_ID).getContentDescription().toString());
            assertNotNull(find(content, AutopilotChip.class));
            openSettings(content);
            assertNull("no Mode entry in Settings", shownButton(content, "Mode · Area"));
            for (String learned : new String[] {"Learned · Off", "Learned · On"}) {
                assertNull("no learning entry in Settings", shownButton(content, learned));
            }
        }
    }

    @Test public void nativeAdoptUndoAndConfirmedResetPreserveSavedRulesAndRetainedOffBests() {
        learn();
        // The new high raised the learned minimums, never the saved ones (the owner, 12 October 2026): Adopt makes
        // them the user's own.
        assertEquals(250, FilterStore.load(app).flatCents);
        assertNull(FilterStore.pendingAdoption(app));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            TextView note = shownTextContaining(content, LearnedRaiseCard.TITLE);
            assertNotNull("the homepage notes the raise", note);
            shownButton((View) note.getParent(), LearnedRaiseCard.OK).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            layOut(content);
            AlertDialog details = learnedMinimums(star);
            find(details.getWindow().getDecorView(), Switch.class).performClick();
            assertNotNull("open dialog follows its switch", shownTextContaining(details.getWindow().getDecorView(),
                    "Stored bests are off and do not affect offers"));
            assertNull(shownTextContaining(details.getWindow().getDecorView(), "Learning is on."));
            assertEquals(800, FilterStore.load(app).accepted.highestPayCents);
            shownButton(details.getWindow().getDecorView(), "Adopt").performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            // $8.00 for 2 stops, the pickup included (0.6.14): ⌈($8.00 + 1¢) ÷ 2⌉ a stop.
            assertEquals(401, FilterStore.load(app).flatCents);
            assertNotNull(FilterStore.pendingAdoption(app));
            assertNull("adopted: no learned raise is left to note", FilterStore.learnedRaise(app));
            details = learnedMinimums(star);
            Button undo = shownButton(details.getWindow().getDecorView(), "Undo adoption");
            assertNotNull(undo);
            undo.performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertArrayEquals(RULES.minimums(), FilterStore.load(app).minimums());
            assertFalse("the Adopt button's Undo keeps the bests", FilterStore.load(app).accepted.isEmpty());
            shownButton(learnedMinimums(star).getWindow().getDecorView(), "Reset").performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertFalse("Cancel retains bests", FilterStore.load(app).accepted.isEmpty());
            shownButton(learnedMinimums(star).getWindow().getDecorView(), "Reset").performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(FilterStore.load(app).accepted.isEmpty());
            assertFalse(FilterStore.load(app).acceptanceLearning);
            assertArrayEquals(RULES.minimums(), FilterStore.load(app).minimums());
        }
    }

    /**
     * A new high the user accepts raises the learned minimums, and the homepage says so in labels, the learned asks
     * beside the user's own, with Adopt and OK (0.6.1; the owner, 12 October 2026: the knobs never move by themselves,
     * "we have the adopt mins feature for that"). Adopt makes them the user's own, with Undo in Learned minimums.
     */
    @Test public void aNewHighsRaiseIsNotedOnTheHomepageAndAdoptMakesTheLearnedMinimumsYours() {
        learn();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            TextView note = shownTextContaining(content, LearnedRaiseCard.TITLE);
            assertNotNull("the note is on the homepage", note);
            String words = note.getText().toString();
            assertTrue(words, words.contains("$4.01/stop · $2.00/mi · $24/hr · yours $2.50/stop · $1.00/mi · $15/hr"));
            assertEquals("Learned minimums rose. A new high you accepted raised them: pay per stop $4.01 (yours $2.50), "
                    + "per mile $2.00 (yours $1.00), per hour $24 (yours $15). Your own minimums stay as you set them; "
                    + "Adopt makes these yours.", String.valueOf(note.getContentDescription()));
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertEquals("the knobs stay as set", 500, star.setAsks(AreaScore.PAY), 0);
            shownButton((View) note.getParent(), LearnedRaiseCard.ADOPT).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            layOut(content);
            FilterSettings after = FilterStore.load(app);
            assertEquals(401, after.flatCents); assertEquals(200, after.perMileCents);
            assertEquals(40, after.perMinuteCents); assertEquals(RULES.maxStops, after.maxStops);
            assertEquals(MainActivity.ADOPTED_TOAST, ShadowToast.getTextOfLatestToast());
            assertFalse("the bests stay", after.accepted.isEmpty());
            assertTrue("learning stays on", after.acceptanceLearning);
            assertNull("the note goes", shownTextContaining(content, LearnedRaiseCard.TITLE));
            // The example's 2 stops ask the adopted $4.01 twice.
            assertEquals(802, star.setAsks(AreaScore.PAY), 0);
            Button undo = shownButton(learnedMinimums(star).getWindow().getDecorView(), "Undo adoption");
            assertNotNull("its Undo is in Learned minimums", undo);
            undo.performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertArrayEquals(RULES.minimums(), FilterStore.load(app).minimums());
            assertFalse(FilterStore.load(app).accepted.isEmpty());
        }
    }

    /** OK closes the note for good; the knobs stay, and the learned minimums go on applying. */
    @Test public void okClosesTheRaisesNoteAndLeavesTheKnobsAndTheLearnedMinimums() {
        learn();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            TextView note = shownTextContaining(content, LearnedRaiseCard.TITLE);
            assertNotNull(note);
            shownButton((View) note.getParent(), LearnedRaiseCard.OK).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            layOut(content);
            assertNull("OK closes it", shownTextContaining(content, LearnedRaiseCard.TITLE));
            assertArrayEquals("the knobs stay", RULES.minimums(), FilterStore.load(app).minimums());
            assertEquals("the learned minimums stay", 800, FilterStore.load(app).accepted.highestPayCents);
            assertNull(FilterStore.learnedRaise(app));
            assertNull("nothing adopted, nothing to undo", shownButton(
                    learnedMinimums(find(content, MinimumsStarView.class)).getWindow().getDecorView(), "Undo adoption"));
        }
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            assertNull("never again", shownTextContaining(content, LearnedRaiseCard.TITLE));
        }
    }

    @Test public void chartSeparatesSavedLearnedAndResolvedBarWithoutMovingKnobsOrFollowingOffers() {
        // $6.00 a stop and a best of $8.00 for 2 stops: the comparison's 2 stops ask $12.00 and $8.01 (0.6.14).
        FilterSettings rules = new FilterSettings(true, 600, 0, 25, 3, true, 70, 120)
                .withAcceptanceLearning(true).withAccepted(new AcceptedFloors(800, 2, 800, 4.0, 800, 20));
        MinimumsStarView star = new MinimumsStarView(app, new Ui(app));
        OfferSnapshot comparison = new OfferSnapshot(null, 5.0, 20, 2);
        star.show(rules, comparison, Collections.emptyList());
        star.compose(360, 300, 220);
        assertEquals(1200, star.setAsks(AreaScore.PAY), 0);
        assertTrue("learned-only axis leaves saved knob unset", Double.isNaN(star.setAsks(AreaScore.MILE)));
        assertEquals("calculated learning includes the higher saved pay minimum", 1200,
                star.learnedAsks(AreaScore.PAY), 0);
        assertEquals(1000, star.learnedAsks(AreaScore.MILE), 0);
        assertEquals("saved pay still dominates lower learned pay", 1440, star.barAsks(AreaScore.PAY), 0);
        assertEquals("bar uses learned-only active axis", 1200, star.barAsks(AreaScore.MILE), 0);
        long ring = star.ringCents();
        star.show(rules, comparison, Collections.singletonList(decided(rules, new OfferSnapshot(300, 40.0, 90, 2))));
        assertEquals(ring, star.ringCents());
        String said = star.getContentDescription().toString();
        for (String fact : new String[] {"blue is saved", "dashed green", "higher saved or learned", "purple",
                "do not move saved knobs"}) {
            assertTrue(fact + ": " + said, said.contains(fact));
        }
        star.show(rules.withAcceptanceLearning(false), comparison, Collections.emptyList());
        assertEquals("off keeps the raw observed best, not the calculated envelope", 801,
                star.learnedAsks(AreaScore.PAY), 0);
        assertTrue(Double.isNaN(star.barAsks(AreaScore.MILE)));
        assertTrue(star.getContentDescription().toString().contains("stored but off"));
    }

    @Test public void recordedAreaExplanationSurvivesCurrentRuleChangesAndSeparatesAutomaticAcceptance() {
        DecisionLog.Entry entry = decided(RULES.withScoreByArea(true), OFFER);
        assertEquals(OfferRule.Result.KEEP, entry.result);
        assertEquals(708, entry.requiredCents);
        String text = MinimumsDetails.describe(FilterSettings.of(true, 1000, 300, 50, 2), entry);
        assertTrue(text, text.contains("Recorded requirements · Area"));
        assertTrue(text, text.contains("Required $7.08 under the recorded area model at 100%"));
        assertTrue(text, text.contains("Permitted by the filter does not mean accepted"));
        assertTrue(text, text.contains("strict saved and enabled learned minimums"));
        assertTrue(text, text.contains("Add-ons use strict saved minimums"));
        assertEquals("Area score 113% · bar 100%", AutopilotText.ticketScoreLine(entry));
        DecisionLog.Entry strict = decided(RULES, OFFER);
        String strictText = MinimumsDetails.describe(RULES.withScoreByArea(true).withMinimums(1000, 300, 50), strict);
        assertTrue(strictText, strictText.contains("Recorded requirements · Strict"));
        assertTrue(strictText, strictText.contains("Required $10.00 under the recorded strict model"));
        try {
            org.json.JSONObject json = strict.toJson().put("model", 2);
            json.remove("rules"); json.remove("scoreKind");
            DecisionLog.Entry old = DecisionLog.Entry.fromJson(json);
            MinimumsStarView star = new MinimumsStarView(app, new Ui(app));
            star.show(RULES.withScoreByArea(true), new OfferSnapshot(null, 5.0, 20, 2), Collections.singletonList(old));
            assertTrue(star.getContentDescription().toString(), star.getContentDescription().toString()
                    .contains("At decision it scored 80%."));
            FilterStore.save(app, RULES.withScoreByArea(true));
            DecisionLog.record(app, old);
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                View content = activity.get().findViewById(android.R.id.content);
                settleSky(content);
                String said = find(content, MinimumsStarView.class).getAccessibilityNodeProvider()
                        .createAccessibilityNodeInfo(MinimumsStarView.OFFER_ID).getContentDescription().toString();
                assertTrue(said, said.contains("scores 113% area fitness now"));
                assertTrue(said, said.contains("scored 80% when decided"));
                assertFalse(said, said.contains("of your minimums now"));
            }
        } catch (org.json.JSONException failure) { throw new AssertionError(failure); }
    }

    @Test public void payCeilingAndKnownZeroAreaHaveDistinctAccurateExplanations() {
        FilterSettings area = new FilterSettings(true, 250, 100, 25, 3, true, 70, 120).withScoreByArea(true);
        OfferSnapshot ceiling = new OfferSnapshot(null, 10.0, 20, 2, 700);
        DecisionLog.Entry entry = decided(area, ceiling);
        assertEquals(OfferRule.Result.DECLINE, entry.result);
        assertEquals(708, entry.requiredCents);
        String text = MinimumsDetails.describe(area, entry);
        assertTrue(text, text.contains("Judged at 100%, never above 100%"));
        assertTrue(text, text.contains("Required $7.08"));
        assertFalse(text, text.contains("$8.49"));
        OfferSnapshot zero = new OfferSnapshot(800, 0.0, 20, 2);
        String zeroText = MinimumsDetails.describe(area, decided(area, zero));
        assertTrue(zeroText, zeroText.contains("zero route amount"));
        assertFalse(zeroText, zeroText.contains("until every active measure is read"));
    }

    @Test @Config(qualifiers = "w360dp-h240dp-xhdpi")
    public void shortWindowKeepsFactsAndIndependentControlsReachable() { shortWindow(1f); }

    @Test @Config(qualifiers = "w360dp-h300dp-xhdpi")
    public void twiceFontKeepsFactsAndIndependentControlsReachable() { shortWindow(2f); }

    private void shortWindow(float font) {
        RuntimeEnvironment.setFontScale(font);
        FilterStore.save(app, RULES);
        // A held update and Peek paused: two lines under the header, where setup's two steps stood before 0.6.26.
        raiseLines(2);
        if (font > 1f) {
            learn();
            // Adopt on the homepage's note makes the new high's learned minimums the user's own, with Undo in Learned
            // minimums (the knobs never move by themselves; the owner, 12 October 2026).
            assertTrue(FilterStore.adoptAccepted(app));
            FilterStore.setAcceptanceLearning(app, false);
            assertNotNull("adopted, with Undo", FilterStore.pendingAdoption(app));
        }
        DecisionLog.record(app, decided(RULES, new OfferSnapshot(790, 7.2, null, 2)));
        String shortQualifiers = RuntimeEnvironment.getQualifiers();
        ServiceController<OfferFilterService> service = null;
        ServiceController<OfferNotificationService> listener = null;
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            // The latest offer's facts at the top, as its tile's labels (0.6.3): the pay and the miles, and the trip
            // time drawn as unread, never left out; whole, nothing cut. Screen readers hear them in words.
            VerdictTile facts = verdictTile(content);
            String shown = facts.shownText();
            assertTrue(shown, shown.startsWith("$7.90 · "));
            assertTrue(shown, (shown + " · ").contains(" · 7.2 mi · ? min · "));
            assertFalse(shown, shown.contains("…"));
            assertEquals("the pay at its own size: " + shown,
                    VerdictTile.drawn(facts.getResources().getDisplayMetrics(), VerdictTile.PAY_SP), facts.payTextSize(), 0.5f);
            String said = String.valueOf(facts.getContentDescription());
            assertTrue(said, said.startsWith("$7.90 · 7.2 mi · Time unread · "));
            Rect visible = new Rect();
            assertTrue(facts.getGlobalVisibleRect(visible));
            assertEquals(facts.getHeight(), visible.height());
            assertEquals(facts.getWidth(), visible.width());
            assertNotNull(find(content, AutopilotChip.class));
            View settings = shownIcon(content, "Settings");
            assertNotNull(settings);
            assertTrue(settings.getHeight() >= new Ui(app).dp(48));
            assertTrue(settings.getWidth() >= new Ui(app).dp(48));
            assertTrue(settings.getGlobalVisibleRect(visible));
            assertEquals(settings.getHeight(), visible.height());
            // The scoring mode's word in the radar's middle (0.6.6): a 48 dp target where the radar has room for it,
            // and always a node screen readers reach while it is shown.
            MinimumsStarView radar = find(content, MinimumsStarView.class);
            if (radar.modeShownWord() != null) {
                Rect target = new Rect();
                node(radar, MinimumsStarView.MODE_ID).getBoundsInParent(target);
                assertTrue("Mode a whole target: " + target, target.height() >= new Ui(app).dp(48) - 1
                        && target.width() >= new Ui(app).dp(48) - 1);
            }
            render(content, "main-font" + (int) font);
            openSettings(content);
            assertNull("no Mode entry in Settings", shownButton(content, "Mode · Strict"));
            assertNull("learning is the radar's button, not a Settings entry", shownButton(content, "Learned · Off"));
            render(content, "controls-font" + (int) font);
            activity.get().onBackPressed();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            // The radar, its Learn button with it, waits for room as its knobs do. Set up (Offer Filter's service
            // and background offers on) and with the divider dragged for room, a long press on Learn opens Learned
            // minimums; the window made short again keeps every control of theirs in reach.
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertFalse("no room for the radar here", star.learnShown());
            service = Robolectric.buildService(OfferFilterService.class).create();
            service.get().onServiceConnected();
            listener = Robolectric.buildService(OfferNotificationService.class).create();
            listener.get().onListenerConnected();
            Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
            if (font > 1f) {
                assertNull("adopted: no note of the raise is left", shownTextContaining(content, LearnedRaiseCard.TITLE));
            }
            resizeWindow(activity, "+h914dp");
            settleSky(content);
            assertTrue("with room, the Learn button", star.learnShown());
            AlertDialog dialog = learnedMinimums(star);
            resizeWindow(activity, shortQualifiers);
            View decor = dialog.getWindow().getDecorView();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            layOut(decor);
            render(decor, "learning-dialog-font" + (int) font);
            Button close = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            assertEquals("Close", close.getText().toString());
            assertTrue(close.getGlobalVisibleRect(visible));
            assertEquals("Close stays outside the scrolling text", close.getHeight(), visible.height());
            assertTrue(close.getHeight() >= new Ui(app).dp(48));
            for (String label : new String[] {font > 1f ? "Undo adoption" : "Adopt", "Reset"}) {
                Button button = shownButton(decor, label);
                assertNotNull(label, button);
                assertTrue(button.getHeight() >= new Ui(app).dp(48));
                assertTrue(button.getWidth() >= new Ui(app).dp(48));
                button.requestRectangleOnScreen(new Rect(0, 0, button.getWidth(), button.getHeight()), true);
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                assertTrue(label + " reachable by scrolling", button.getGlobalVisibleRect(visible));
                assertEquals(label + " whole touch target", button.getHeight(), visible.height());
                if (label.equals("Undo adoption")) {
                    assertTrue("long action uses at most two readable lines", button.getLineCount() <= 2);
                    for (int line = 0; line < button.getLineCount(); line++) {
                        String words = button.getText().subSequence(button.getLayout().getLineStart(line),
                                button.getLayout().getLineEnd(line)).toString().trim();
                        assertTrue("action words stay intact: " + words,
                                words.equals("Undo") || words.equals("adoption") || words.equals("Undo adoption"));
                        assertEquals("action never ellipsizes", 0, button.getLayout().getEllipsisCount(line));
                    }
                }
            }
            render(decor, "learning-dialog-font" + (int) font);
        } finally {
            if (listener != null) listener.destroy();
            if (service != null) service.destroy();
        }
    }

    /** Learned minimums, as the radar's Learn button's long press opens them. */
    private static AlertDialog learnedMinimums(MinimumsStarView star) {
        assertTrue("the Learn button is there", act(star, MinimumsStarView.SCORE_ID,
                AccessibilityNodeInfo.ACTION_LONG_CLICK));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("Learned minimums", Shadows.shadowOf(dialog).getTitle().toString());
        return dialog;
    }

    private static void openSettings(View content) {
        shownIcon(content, "Settings").performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        layOut(content);
    }

    private static void render(View content, String name) {
        Bitmap image = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
        content.draw(new Canvas(image));
        File directory = new File("build/reports/area-learning-ui");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        finally { image.recycle(); }
    }
}
