package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
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

/** An outcome is readable and tappable without targeting a narrow skyline or a draggable minimum knob. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36}, qualifiers = "w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class OfferCaptionUiTest extends AndroidAdapterTestBase {
    private static final FilterSettings RULES = FilterSettings.of(true, 1000, 100, 20, 3);

    @Before public void palette() { Appearance.choose(app, Appearance.Mode.NIGHT); }

    private DecisionLog.Entry entry(long at, int pay, DecisionLog.StepKind step) {
        OfferSnapshot facts = new OfferSnapshot(pay, 3.0, 15, 2).withItems(1, true);
        DecisionLog.Entry entry = new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false, facts, 1000,
                OfferRule.Result.KEEP, "meets your minimums", DecisionLog.Action.PASSES, true,
                Collections.singletonList(DecisionLog.money(pay)));
        if (step != null) entry = entry.withStep(new DecisionLog.Step(step, at + 10, "observed test outcome"));
        return entry;
    }

    @Test public void captionUsesObservedOutcomesInsteadOfCallingEveryPassingShapeAccepted() {
        FilterStore.save(app, RULES);
        long now = System.currentTimeMillis();
        // 0.5.0's outcomes (the user's acceptance, the app's automatic one), then the kinds older versions wrote.
        DecisionLog.StepKind[] steps = {null, DecisionLog.StepKind.AUTO_ACCEPT_REQUESTED,
                DecisionLog.StepKind.AUTO_ACCEPT_NOT_SENT, DecisionLog.StepKind.ACCEPTED,
                DecisionLog.StepKind.ACCEPTED_AUTOMATIC, DecisionLog.StepKind.ACCEPTED_NOT_LEARNED,
                DecisionLog.StepKind.ACCEPTED_BEST_SAVED, DecisionLog.StepKind.ACCEPTED_LEARNED};
        String[] words = {"Passed", "Accept requested, not confirmed", "Left to you", "Accepted by you",
                "Automatically accepted", "Accepted", "Accepted by you", "Accepted by you"};
        for (int i = 0; i < steps.length; i++) DecisionLog.record(app, entry(now - (steps.length - i) * 1000L,
                1200 + i * 100, steps[i]));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            DecisionChartView chart = find(content, DecisionChartView.class);
            for (int i = 0; i < steps.length; i++) {
                chart.select(i);
                View target;
                if (i == steps.length - 1) {
                    // The latest is said once, by the tile at the top (the owner, 11 October 2026), as a label and a
                    // badge, not a sentence (12 October): its outcome in the caption's words, never a guessed one.
                    assertNull(shownTextContaining(content, "Latest ·"));
                    assertNull(shownTextContaining(content, "Selected ·"));
                    target = latestTile(content, DecisionLog.money(1200 + i * 100), words[i]);
                } else {
                    String expected = "Selected · " + DecisionLog.money(1200 + i * 100) + " · " + words[i];
                    TextView caption = shownTextContaining(content, expected);
                    assertNotNull(expected, caption);
                    assertTrue(caption.getContentDescription().toString().endsWith("Open offer details"));
                    target = caption;
                }
                target.performClick();
                assertTrue(find(content, MinimumsStarView.class).openedOffer() >= 0);
                assertNotNull(shownTextContaining(content, "Read: " + DecisionLog.money(1200 + i * 100)));
                activity.get().onBackPressed();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
            }
        }
    }

    @Test public void automaticAcceptanceNeedsItsOwnConfirmationEvidenceAndCancellationAppearsFirst() {
        FilterStore.save(app, RULES);
        long now = System.currentTimeMillis();
        // 0.5.0 says it outright: the app's own request, then a delivery screen.
        DecisionLog.record(app, entry(now - 4000, 1250, DecisionLog.StepKind.AUTO_ACCEPT_REQUESTED)
                .withStep(new DecisionLog.Step(DecisionLog.StepKind.ACCEPTED_AUTOMATIC, now - 3500,
                        "automatic Accept was requested, and Dasher showed a delivery screen")));
        // Written by older versions: the request, then their acceptance step's words.
        DecisionLog.Entry automatic = entry(now - 3000, 1300, DecisionLog.StepKind.AUTO_ACCEPT_REQUESTED)
                .withStep(new DecisionLog.Step(DecisionLog.StepKind.ACCEPTED_NOT_LEARNED, now - 2500,
                        "automatic Accept was requested, and Dasher showed a delivery screen; automatic choices never raise your learned minimums"));
        DecisionLog.record(app, automatic);
        DecisionLog.record(app, entry(now - 2000, 1400, DecisionLog.StepKind.AUTO_ACCEPT_REQUESTED)
                .withStep(new DecisionLog.Step(DecisionLog.StepKind.ACCEPTED_NOT_LEARNED, now - 1500,
                        "recent automatic-acceptance provenance is uncertain, so personal minimums stay unchanged")));
        DecisionLog.record(app, entry(now - 1000, 1500, DecisionLog.StepKind.AUTO_ACCEPT_NOT_SENT));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            assertNull("the latest is not repeated under the skyline", shownTextContaining(content, "Latest ·"));
            VerdictTile tile = latestTile(content, "$15.00", "Left to you");
            assertNull("an Accept never sent is no request and no acceptance", tile.tag());
            tile.performClick();
            layOut(content);
            TextView status = shownTextContaining(content, "Automatic Accept not sent; left to you:");
            assertNotNull(status);
            int[] statusAt = new int[2], cardAt = new int[2];
            status.getLocationOnScreen(statusAt);
            find(content, OfferCardView.class).getLocationOnScreen(cardAt);
            assertTrue(statusAt[1] < cardAt[1]);
            activity.get().onBackPressed();
            DecisionChartView chart = find(content, DecisionChartView.class);
            chart.select(0);
            assertNotNull(shownTextContaining(content, "Selected · $12.50 · Automatically accepted"));
            chart.select(1);
            assertNotNull("as an older version recorded it", shownTextContaining(content,
                    "Selected · $13.00 · Automatically accepted"));
            chart.select(2);
            assertEquals("ambiguous provenance does not invent who accepted", "Selected · $14.00 · Accepted",
                    shownTextContaining(content, "Selected · $14.00").getText().toString());
        }
    }

    @Test public void reviewAndActualDeclineRemainDistinctAndUnreadPayStaysUnknown() {
        FilterStore.save(app, RULES);
        long now = System.currentTimeMillis();
        DecisionLog.record(app, new DecisionLog.Entry(now - 1000, DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(500, 3.0, 15, 2), 1000, OfferRule.Result.DECLINE, "below minimum",
                DecisionLog.Action.DECLINE_TAPPED, true, Collections.emptyList()));
        DecisionLog.record(app, new DecisionLog.Entry(now, DecisionLog.Source.SCREEN, false,
                OfferSnapshot.UNKNOWN, 1000, OfferRule.Result.REVIEW, "unread pay",
                DecisionLog.Action.NEEDS_REVIEW, true, Collections.emptyList()));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            assertNull(shownTextContaining(content, "Latest ·"));
            VerdictTile tile = verdictTile(content);
            assertEquals("Review", tile.outcome());
            String shown = tile.shownText();
            assertTrue("unread pay is drawn as unread, never guessed: " + shown, shown.startsWith("$?"));
            assertTrue("and so are the miles and the minutes: " + shown, shown.endsWith("? mi · ? min"));
            String said = String.valueOf(tile.getContentDescription());
            assertTrue("and heard as unread: " + said,
                    said.startsWith("Pay unread · Miles unread · Time unread · Review · "));
            find(content, DecisionChartView.class).select(0);
            assertNotNull(shownTextContaining(content, "Selected · $5.00 · Declined"));
            DecisionLog.clear(app);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertNull(shownTextContaining(content, "Selected ·"));
            assertNull("the skyline's caption says nothing without an offer", shownTextContaining(content, "Latest ·"));
            assertNull("the tile at the top says so", tile.outcome());
            assertEquals(VerdictTile.NO_OFFERS, String.valueOf(tile.getContentDescription()));
            assertEquals(VerdictTile.NO_OFFERS, tile.shownText());
            assertFalse("no offer to open", tile.isClickable());
        }
    }

    @Test public void olderSelectionAndItsCaptionSurviveALaterOutcomeAndNewArrivals() {
        FilterStore.save(app, RULES);
        long now = System.currentTimeMillis();
        DecisionLog.Entry older = entry(now - 5000, 1234, null);
        DecisionLog.record(app, older);
        DecisionLog.record(app, entry(now - 3000, 2000, null));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            DecisionChartView chart = find(content, DecisionChartView.class);
            chart.select(0);
            assertNotNull(shownTextContaining(content, "Selected · $12.34 · Passed"));
            assertTrue(DecisionLog.markStep(app, older.facts, DecisionLog.StepKind.ACCEPTED,
                    "you tapped Accept, then Dasher showed a delivery", 60_000));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertEquals(older.at, chart.selectedEntry().at);
            TextView caption = shownTextContaining(content, "Selected · $12.34 · Accepted by you");
            assertNotNull(caption);
            caption.performClick();
            layOut(content);
            TextView outcome = shownTextContaining(content, "Accepted: you tapped Accept");
            assertNotNull(outcome);
            OfferCardView card = find(content, OfferCardView.class);
            int[] outcomeLocation = new int[2], cardLocation = new int[2];
            outcome.getLocationOnScreen(outcomeLocation);
            card.getLocationOnScreen(cardLocation);
            assertTrue("the outcome precedes the detailed offer numbers", outcomeLocation[1] < cardLocation[1]);
            assertTrue("opening the ticket does not pause", FilterStore.load(app).enabled);
            assertEquals("the promoted outcome is not repeated in the lower step history", 1,
                    countText(content, "Accepted: you tapped Accept"));
            assertArrayEquals("an acceptance changes no minimum", RULES.minimums(), FilterStore.load(app).minimums());
            activity.get().onBackPressed();
            DecisionLog.record(app, entry(now - 1000, 2500, null));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertEquals(older.at, chart.selectedEntry().at);
            assertNotNull(shownTextContaining(content, "Selected · $12.34 · Accepted by you"));
            chart.select(2);
            assertNull("the latest has no caption of its own", shownTextContaining(content, "Selected ·"));
            assertNull(shownTextContaining(content, "Latest ·"));
            latestTile(content, "$25.00", "Passed");
        }
    }

    /** The radar's round button is the learner's (0.6.1): "Learn" over Off or On; a tap changes no minimum. */
    @Test public void theLearnButtonsWordsAreDrawnAndATapPreservesTheMinimumsAndAutopilot() {
        FilterStore.save(app, RULES);
        withAutopilotAt(97);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            List<String> drawn = drawnWords(star);
            assertTrue(drawn.toString(), drawn.contains(MinimumsStarView.LEARN_TITLE) && drawn.contains("Off"));
            assertFalse("Autopilot's words are the chip's alone", drawn.contains("Auto") || drawn.contains("97%"));
            assertTrue(act(star, MinimumsStarView.SCORE_ID, AccessibilityNodeInfo.ACTION_CLICK));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            drawn = drawnWords(star);
            assertTrue(drawn.toString(), drawn.contains(MinimumsStarView.LEARN_TITLE) && drawn.contains("On"));
            assertTrue(FilterStore.load(app).acceptanceLearning);
            assertTrue("Autopilot is untouched", FilterStore.load(app).autopilot);
            assertEquals(97, FilterStore.load(app).minimumScalePercent);
            assertArrayEquals(RULES.minimums(), FilterStore.load(app).minimums());
        }
    }

    /** Autopilot on, its bar moved to {@code bar} between offers. */
    private void withAutopilotAt(int bar) {
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertTrue(FilterStore.commitAutopilotBar(app, 100, bar));
    }

    @Test public void phoneCaptionIsAVisible48DpTarget() throws Exception { render(false, "phone"); }
    @Test @Config(qualifiers = "w360dp-h396dp-xhdpi")
    public void splitCaptionRemainsVisibleAndTappable() throws Exception { render(true, "split"); }
    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    public void narrowCaptionRemainsVisibleAndTappable() throws Exception { render(false, "narrow"); }
    @Test public void doubledFontWrapsWithoutClippingTheCaption() throws Exception {
        RuntimeEnvironment.setFontScale(2f);
        try { render(false, "large-font"); }
        finally { RuntimeEnvironment.setFontScale(1f); }
    }

    @Test @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void compactMapKeepsItsCaptionAndSkylineOnScreen() throws Exception { render(false, "compact-map"); }
    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    public void narrowDoubledFontWrapsAnUnconfirmedRequestWithoutClipping() throws Exception {
        RuntimeEnvironment.setFontScale(2f);
        // Autopilot on: at a very large font its status stands in its chip beside this long line, never a row of its
        // own, and the page stays one screen.
        try { render(false, "narrow-large-requested", DecisionLog.StepKind.AUTO_ACCEPT_REQUESTED,
                "Accept requested, not confirmed", true); }
        finally { RuntimeEnvironment.setFontScale(1f); }
    }

    private void render(boolean split, String name) throws Exception {
        render(split, name, DecisionLog.StepKind.AUTO_ACCEPT_NOT_SENT, "Left to you", true);
    }

    private void render(boolean split, String name, DecisionLog.StepKind status, String outcome, boolean autopilot)
            throws Exception {
        FilterStore.save(app, RULES);
        // Autopilot on below 100%: its chip and status line in the strip at the top, at every size.
        if (autopilot) withAutopilotAt(82);
        DecisionLog.record(app, entry(System.currentTimeMillis(), 1835, status));
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        ServiceController<OfferNotificationService> listener = Robolectric.buildService(OfferNotificationService.class).create();
        service.get().onServiceConnected();
        listener.get().onListenerConnected();
        if (split) OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        if (app.getResources().getConfiguration().screenHeightDp < 600) {
            Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        }
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            // The latest offer is said once, by the tile at the top of the page, which opens it at every size (the
            // owner, 11 October 2026: the skyline's caption repeated it; 12 October: a label and a badge, not a
            // sentence).
            assertNull(shownTextContaining(content, "Latest ·"));
            assertNull("the skyline's caption is an older offer's alone", shownTextContaining(content, "Selected ·"));
            ScenePage scene = find(content, ScenePage.class);
            VerdictTile tile = latestTile(content, "$18.35", outcome);
            assertEquals("a request is tagged as one, never drawn as an acceptance",
                    status == DecisionLog.StepKind.AUTO_ACCEPT_REQUESTED ? VerdictTile.TAG_REQUESTED : null, tile.tag());
            Ui ui = new Ui(app);
            assertTrue("a 48 dp target (" + ui.dp(48) + " px): " + tile.getWidth() + " x " + tile.getHeight() + " px",
                    tile.getWidth() >= ui.dp(48) && tile.getHeight() >= ui.dp(48));
            assertTrue("main scene fits its window: " + scene.getHeight() + " in " + ((View) scene.getParent()).getHeight(),
                    scene.getHeight() <= ((View) scene.getParent()).getHeight());
            Rect visible = new Rect();
            assertTrue(tile.getGlobalVisibleRect(visible));
            assertEquals("whole target remains on screen", tile.getHeight(), visible.height());
            assertEquals("whole target remains on screen", tile.getWidth(), visible.width());
            // Nothing it draws is clipped: its two lines fit its height, the pay is at its own size (condensed at most
            // to four fifths of its width, never drawn smaller) and the trip is whole.
            assertTrue("its two lines fit: " + tile.getHeight(),
                    tile.getHeight() >= VerdictTile.heightFor(tile.getContext()) - 1);
            String shown = tile.shownText();
            assertEquals("the pay at its own size: " + shown + " in " + tile.getWidth() + " px",
                    VerdictTile.drawn(tile.getResources().getDisplayMetrics(), 16), tile.payTextSize(), 0.5f);
            assertTrue("condensed at most to four fifths: " + tile.payCondensed(), tile.payCondensed() >= 0.8f - 1e-4f);
            assertTrue("the trip whole: " + shown, shown.contains("3 mi · 15 min") && !shown.contains("…"));
            Bitmap bitmap = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
            content.draw(new Canvas(bitmap));
            File dir = new File("build/reports/offer-caption");
            assertTrue(dir.isDirectory() || dir.mkdirs());
            try (FileOutputStream stream = new FileOutputStream(new File(dir, name + ".png"))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
            } finally { bitmap.recycle(); }
            AreaMapView map = find(content, AreaMapView.class);
            if (scene.shown(map) >= 1) {
                int[] mapAt = new int[2], rootAt = new int[2];
                map.getLocationOnScreen(mapAt);
                content.getLocationOnScreen(rootAt);
                tap((ViewGroup) content, mapAt[0] - rootAt[0] + map.getWidth() / 2f,
                        mapAt[1] - rootAt[1] + map.getHeight() / 2f);
                assertNotNull("the map wholly there is a usable target",
                        Shadows.shadowOf(activity.get()).getLastRequestedPermission());
            }
            // A finger on the tile itself (the mascot beside it, the counts after it and the chips under it keep their
            // own taps).
            int[] targetAt = new int[2], contentAt = new int[2];
            tile.getLocationOnScreen(targetAt);
            content.getLocationOnScreen(contentAt);
            tap((ViewGroup) content, targetAt[0] - contentAt[0] + tile.getWidth() / 2f,
                    targetAt[1] - contentAt[1] + tile.getHeight() / 2f);
            assertNotNull("real coordinate tap opens the displayed offer", shownTextContaining(content, "Read: $18.35"));
            assertTrue(FilterStore.load(app).enabled);
        } finally {
            listener.destroy();
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    /**
     * The latest offer's tile at the top of the page (0.6.3): it draws {@code pay} first, shows {@code outcome} (the
     * skyline caption's words for it, by its badge), and screen readers hear the facts and that outcome in full, then
     * what a tap does. Returned for its tap.
     */
    private static VerdictTile latestTile(View content, String pay, String outcome) {
        VerdictTile tile = verdictTile(content);
        assertNotNull("the latest offer's tile", tile);
        assertTrue("it is on the page", tile.isShown());
        String shown = tile.shownText();
        assertTrue("the pay comes first: " + shown, shown.startsWith(pay));
        assertEquals("its outcome, in the caption's words", outcome, tile.outcome());
        assertNotNull("drawn as a badge", tile.symbol());
        String said = String.valueOf(tile.getContentDescription());
        assertTrue("heard with its pay first: " + said, said.startsWith(pay + " · "));
        assertTrue("and its outcome in words: " + said, said.contains(" · " + outcome + " · "));
        assertTrue("then what a tap does: " + said, said.endsWith(". " + VerdictTile.ACTION));
        assertTrue("a tap opens it", tile.isClickable());
        return tile;
    }

    private static int countText(View view, String text) {
        int count = view instanceof TextView && view.isShown()
                && ((TextView) view).getText().toString().contains(text) ? 1 : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) count += countText(group.getChildAt(i), text);
        }
        return count;
    }

    private static List<String> drawnWords(View view) {
        List<String> words = new ArrayList<>();
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap) {
            @Override public void drawText(String text, float x, float y, Paint paint) {
                words.add(text);
                super.drawText(text, x, y, paint);
            }
        });
        bitmap.recycle();
        return words;
    }
}
