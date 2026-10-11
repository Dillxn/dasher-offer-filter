package com.local.dasherfilter;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
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
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;

/** Native controls, honest scoring explanations, and preserved short-window facts in the fresh restoration. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, qualifiers = "w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class AreaLearningUiTest extends AndroidAdapterTestBase {
    private static final FilterSettings RULES = FilterSettings.of(true, 500, 100, 25, 3);
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

    @Test public void modeAndLearningAreDiscoverableIndependentChoicesAndNeverEnableAutopilot() {
        FilterStore.save(app, RULES.withEnabled(false));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            openSettings(content);
            Button mode = shownButton(content, "Mode · Strict");
            Button learned = shownButton(content, "Learned · Off");
            assertNotNull(mode); assertNotNull(learned);
            assertTrue(mode.getContentDescription().toString().contains("Autopilot is separate"));
            assertTrue(learned.getContentDescription().toString().contains("adoption, Undo and Reset"));
            mode.performClick();
            ShadowAlertDialog.getLatestAlertDialog().cancel();
            assertFalse(FilterStore.load(app).scoreByArea);
            mode.performClick();
            Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(1);
            assertTrue(FilterStore.load(app).scoreByArea);
            assertFalse(FilterStore.load(app).acceptanceLearning);
            learned.performClick();
            AlertDialog details = ShadowAlertDialog.getLatestAlertDialog();
            Switch toggle = find(details.getWindow().getDecorView(), Switch.class);
            assertNotNull(toggle); toggle.performClick();
            FilterSettings after = FilterStore.load(app);
            assertTrue(after.acceptanceLearning); assertTrue(after.scoreByArea);
            assertFalse("changing rules never starts filtering", after.enabled);
            assertFalse("Auto is still its own off switch", after.autopilot);
            assertEquals(100, after.minimumScalePercent);
            assertArrayEquals(RULES.minimums(), after.minimums());
            assertNotNull(find(content, AutopilotChip.class));
        }
    }

    @Test public void nativeAdoptUndoAndConfirmedResetPreserveSavedRulesAndRetainedOffBests() {
        learn();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            openSettings(content);
            shownButton(content, "Learned · On").performClick();
            AlertDialog details = ShadowAlertDialog.getLatestAlertDialog();
            find(details.getWindow().getDecorView(), Switch.class).performClick();
            assertNotNull("open dialog follows its switch", shownTextContaining(details.getWindow().getDecorView(),
                    "Stored bests are off and do not affect offers"));
            assertNull(shownTextContaining(details.getWindow().getDecorView(), "Learning is on."));
            assertEquals(800, FilterStore.load(app).accepted.highestPayCents);
            shownButton(details.getWindow().getDecorView(), "Adopt").performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(801, FilterStore.load(app).flatCents);
            assertNotNull(FilterStore.pendingAdoption(app));
            shownButton(content, "Learned · Off").performClick();
            details = ShadowAlertDialog.getLatestAlertDialog();
            Button undo = shownButton(details.getWindow().getDecorView(), "Undo adoption");
            assertNotNull(undo);
            undo.performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertArrayEquals(RULES.minimums(), FilterStore.load(app).minimums());
            shownButton(content, "Learned · Off").performClick();
            shownButton(ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView(), "Reset").performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertFalse("Cancel retains bests", FilterStore.load(app).accepted.isEmpty());
            shownButton(content, "Learned · Off").performClick();
            shownButton(ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView(), "Reset").performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(FilterStore.load(app).accepted.isEmpty());
            assertFalse(FilterStore.load(app).acceptanceLearning);
            assertArrayEquals(RULES.minimums(), FilterStore.load(app).minimums());
        }
    }

    @Test public void chartSeparatesSavedLearnedAndResolvedBarWithoutMovingKnobsOrFollowingOffers() {
        FilterSettings rules = new FilterSettings(true, 1200, 0, 25, 3, true, 70, 120)
                .withAcceptanceLearning(true).withAccepted(new AcceptedFloors(800, 800, 4.0, 800, 20));
        MinimumsStarView star = new MinimumsStarView(app, new Ui(app));
        OfferSnapshot comparison = new OfferSnapshot(null, 5.0, 20, 2);
        star.show(rules, comparison, Collections.emptyList());
        star.compose(360, 300, 220);
        assertEquals(1200, star.setAsks(AreaScore.PAY), 0);
        assertTrue("learned-only axis leaves saved knob unset", Double.isNaN(star.setAsks(AreaScore.MILE)));
        assertEquals(801, star.learnedAsks(AreaScore.PAY), 0);
        assertEquals(1000, star.learnedAsks(AreaScore.MILE), 0);
        assertEquals("saved pay still dominates lower learned pay", 1440, star.barAsks(AreaScore.PAY), 0);
        assertEquals("bar uses learned-only active axis", 1200, star.barAsks(AreaScore.MILE), 0);
        long ring = star.ringCents();
        star.show(rules, comparison, Collections.singletonList(decided(rules, new OfferSnapshot(300, 40.0, 90, 2))));
        assertEquals(ring, star.ringCents());
        String said = star.getContentDescription().toString();
        for (String fact : new String[] {"blue is saved", "teal is learned", "purple", "do not move saved knobs"}) {
            assertTrue(fact + ": " + said, said.contains(fact));
        }
        star.show(rules.withAcceptanceLearning(false), comparison, Collections.emptyList());
        assertTrue(Double.isNaN(star.barAsks(AreaScore.MILE)));
        assertTrue(star.getContentDescription().toString().contains("stored but off"));
    }

    @Test public void recordedAreaExplanationSurvivesCurrentRuleChangesAndSeparatesAutomaticAcceptance() {
        DecisionLog.Entry entry = decided(RULES.withScoreByArea(true), OFFER);
        assertEquals(OfferRule.Result.KEEP, entry.result);
        assertEquals(708, entry.requiredCents);
        String text = MinimumsDetails.describe(FilterSettings.of(true, 2000, 300, 50, 2), entry);
        assertTrue(text, text.contains("Recorded requirements · Area"));
        assertTrue(text, text.contains("Required $7.08 under the recorded area model at 100%"));
        assertTrue(text, text.contains("Permitted by the filter does not mean accepted"));
        assertTrue(text, text.contains("strict saved and enabled learned minimums"));
        assertTrue(text, text.contains("Add-ons use strict saved minimums"));
        assertEquals("Area score 113% · bar 100%", AutopilotText.ticketScoreLine(entry));
        DecisionLog.Entry strict = decided(RULES, OFFER);
        String strictText = MinimumsDetails.describe(RULES.withScoreByArea(true).withMinimums(2000, 300, 50), strict);
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
        FilterSettings area = new FilterSettings(true, 500, 100, 25, 3, true, 70, 120).withScoreByArea(true);
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

    @Test public void growthDetailsNameTheLearnedFloorHoldOnlyWhileGrowthIsEnabled() {
        learn();
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            find(content, AutopilotChip.class).performClick();
            View details = ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView();
            TextView words = details.findViewById(MainActivity.DETAILS_WORDS_ID);
            assertTrue(words.getText().toString(), words.getText().toString().contains("Minimum growth waits"));
            find(details, Switch.class).performClick();
            assertFalse(words.getText().toString().contains("Minimum growth waits"));
            assertFalse(FilterStore.minimumsGrow(app));
            assertTrue(FilterStore.load(app).acceptanceLearning);
            assertFalse(FilterStore.load(app).accepted.isEmpty());
        }
    }

    @Test @Config(qualifiers = "w360dp-h240dp-xhdpi")
    public void shortWindowKeepsFactsAndIndependentControlsReachable() { shortWindow(1f); }

    @Test @Config(qualifiers = "w360dp-h300dp-xhdpi")
    public void twiceFontKeepsFactsAndIndependentControlsReachable() { shortWindow(2f); }

    private void shortWindow(float font) {
        RuntimeEnvironment.setFontScale(font);
        FilterStore.save(app, RULES);
        if (font > 1f) {
            learn();
            FilterStore.setAcceptanceLearning(app, false);
            assertTrue(FilterStore.adoptAccepted(app));
        }
        DecisionLog.record(app, decided(RULES, new OfferSnapshot(790, 7.2, null, 2)));
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            TextView facts = verdictLine(content);
            assertTrue(facts.getText().toString(), facts.getText().toString().startsWith("$7.90 · 7.2 mi · Time unread"));
            Rect visible = new Rect();
            assertTrue(facts.getGlobalVisibleRect(visible));
            assertEquals(facts.getHeight(), visible.height());
            for (int line = 0; line < facts.getLineCount(); line++) assertEquals(0, facts.getLayout().getEllipsisCount(line));
            assertNotNull(find(content, AutopilotChip.class));
            View settings = shownIcon(content, "Settings");
            assertNotNull(settings);
            assertTrue(settings.getHeight() >= new Ui(app).dp(48));
            assertTrue(settings.getWidth() >= new Ui(app).dp(48));
            assertTrue(settings.getGlobalVisibleRect(visible));
            assertEquals(settings.getHeight(), visible.height());
            render(content, "main-font" + (int) font);
            openSettings(content);
            for (String label : new String[] {"Mode · Strict", "Learned · Off"}) {
                Button button = shownButton(content, label);
                assertNotNull(label, button);
                assertTrue(button.getHeight() >= new Ui(app).dp(48));
                assertTrue(button.getWidth() >= new Ui(app).dp(48));
                button.requestRectangleOnScreen(new Rect(0, 0, button.getWidth(), button.getHeight()), true);
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                assertTrue(label + " reachable by scrolling", button.getGlobalVisibleRect(visible));
                assertEquals(label + " whole touch target", button.getHeight(), visible.height());
            }
            render(content, "controls-font" + (int) font);
            shownButton(content, "Learned · Off").performClick();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
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
        }
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
