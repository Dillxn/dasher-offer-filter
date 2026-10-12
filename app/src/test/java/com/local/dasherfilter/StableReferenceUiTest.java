package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import java.util.Collections;
import java.io.File;
import java.io.FileOutputStream;
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

/** Reconstructed UI requirements: a stable reference and readable facts even after the chart has faded away. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36}, qualifiers = "w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class StableReferenceUiTest extends AndroidAdapterTestBase {
    private static final FilterSettings RULES = FilterSettings.of(true, 400, 100, 25, 3);
    private static final OfferSnapshot COMPARISON = new OfferSnapshot(null, 5.0, 20, 2);

    @After public void restoreFont() { RuntimeEnvironment.setFontScale(1f); }

    private static DecisionLog.Entry entry(OfferSnapshot facts) {
        OfferRule.Decision decision = OfferRule.evaluate(facts, RULES);
        return new DecisionLog.Entry(System.currentTimeMillis(), DecisionLog.Source.SCREEN, false,
                facts, decision.requiredCents, decision.result, decision.reason,
                decision.result == OfferRule.Result.DECLINE ? DecisionLog.Action.DECLINE_TAPPED
                        : decision.result == OfferRule.Result.KEEP ? DecisionLog.Action.PASSES
                        : DecisionLog.Action.NEEDS_REVIEW, true, Collections.emptyList());
    }

    @Test public void sixLatestRoutesKeepTheSameReferenceCoordinatesAtFixedRulesAndViewport() {
        FilterStore.save(app, RULES);
        OfferSnapshot[] offers = {new OfferSnapshot(900, 5.0, 20, 2), new OfferSnapshot(400, 9.0, 40, 2),
                new OfferSnapshot(400, 1.0, 40, 2), new OfferSnapshot(400, 9.0, 5, 2),
                new OfferSnapshot(400, 1.0, 5, 2), new OfferSnapshot(900, 5.0, null, 2)};
        float[][] coordinates = null;
        for (OfferSnapshot facts : offers) {
            DecisionLog.clear(app);
            DecisionLog.record(app, entry(facts));
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                View content = activity.get().findViewById(android.R.id.content);
                settleSky(content);
                MinimumsStarView star = find(content, MinimumsStarView.class);
                // Hold the chart's viewport exactly fixed, independent of a different verdict's wrapped height.
                star.compose(360, 300, 220);
                settleSky(content);
                star.compose(360, 300, 220);
                assertEquals(400, star.setAsks(AreaScore.PAY), 0);
                assertEquals("a fixed five-mile comparison", 500, star.setAsks(AreaScore.MILE), 0);
                assertEquals("a fixed twenty-minute comparison", 500, star.setAsks(AreaScore.MINUTE), 0);
                float[][] now = {star.knobAt(AreaScore.PAY), star.knobAt(AreaScore.MILE),
                        star.knobAt(AreaScore.MINUTE)};
                if (coordinates == null) coordinates = now;
                else for (int axis = 0; axis < now.length; axis++) {
                    assertArrayEquals("latest route cannot move reference axis " + axis, coordinates[axis], now[axis], .01f);
                }
                assertArrayEquals("showing an offer never changes rules", RULES.minimums(), FilterStore.load(app).minimums());
                assertEquals("score still uses the actual offer", AreaScore.scorePercent(RULES, facts),
                        star.emphasizedScore());
            }
        }
    }

    @Test public void offersEnteringAndLeavingHistoryCannotStretchTheRingScale() {
        MinimumsStarView star = new MinimumsStarView(app, new Ui(app));
        star.show(RULES, COMPARISON, Collections.emptyList());
        long ring = star.ringCents();
        for (int pay : new int[] {400, 900, 100000}) {
            star.show(RULES, COMPARISON, Collections.singletonList(entry(new OfferSnapshot(pay, 5.0, 20, 2))));
            assertEquals("pay " + pay + " changes only the offer marks", ring, star.ringCents());
        }
        star.show(RULES, COMPARISON, Collections.emptyList());
        assertEquals("expiry and Clear history also leave the reference still", ring, star.ringCents());
        FilterSettings off = FilterSettings.of(false, 0, 0, 0, 0);
        star.show(off, COMPARISON, Collections.emptyList());
        long emptyRing = star.ringCents();
        assertEquals("with no minimum, keep the existing five-dollar comparison rings", 500, emptyRing);
        star.show(off, COMPARISON, Collections.singletonList(entry(new OfferSnapshot(100000, 5.0, 20, 2))));
        assertEquals(emptyRing, star.ringCents());
        assertEquals("the display comparison never invents a minimum or a score", -1, star.emphasizedScore());
    }

    @Test public void minimumsAndTheBarStillSetTheScaleAndOutliersKeepTheirRealScore() {
        MinimumsStarView star = new MinimumsStarView(app, new Ui(app));
        OfferSnapshot outlier = new OfferSnapshot(100000, 5.0, 20, 2);
        star.show(RULES, COMPARISON, Collections.singletonList(entry(outlier)));
        star.compose(360, 300, 220);
        assertEquals("rules alone ask for two-dollar rings", 200, star.ringCents());
        assertEquals(20000, star.emphasizedScore());
        for (int axis : MinimumsStarView.SPOKES) {
            float[] mark = star.markAt(0, axis);
            assertNotNull(mark);
            // Marks retain their small perpendicular separation; the cap is their reach along the spoke.
            double reach = along(new float[] {star.skyX(), star.skyY()}, axis, mark);
            assertEquals("an outlier stays at the spoke's capped edge", star.skyRadius() * 1.02, reach, .01);
        }
        star.setAutopilot(true, 150, false);
        assertEquals("the bar may expand its own reference", 300, star.ringCents());
        assertEquals(750, star.barAsks(AreaScore.MILE), 0);
        star.show(FilterSettings.of(true, 2000, 100, 25, 3), COMPARISON, Collections.emptyList());
        assertEquals("a changed minimum may expand the reference", 700, star.ringCents());
    }

    @Test @Config(qualifiers = "w360dp-h240dp-xhdpi")
    public void shortWindowShowsPayMilesAndTripTimeBeforeTheOutcome() {
        assertOverview(new OfferSnapshot(790, 7.2, 21, 2), "$7.90 · 7.2 mi · 21 min",
                new String[] {"$7.90", "7.2 mi", "21 min"}, 1f);
    }

    @Test @Config(qualifiers = "w360dp-h300dp-xhdpi")
    public void largeFontShortWindowSaysWhenTripTimeWasUnread() {
        assertOverview(new OfferSnapshot(790, 7.2, null, 2), "$7.90 · 7.2 mi · Time unread",
                new String[] {"$7.90", "7.2 mi", "? min"}, 2f);
    }

    @Test @Config(qualifiers = "w360dp-h300dp-xhdpi")
    public void missingFactsStayUnreadInTheOverviewAndItsAccessibleDescription() {
        assertOverview(OfferSnapshot.UNKNOWN, "Pay unread · Miles unread · Time unread",
                new String[] {"$?", "? mi", "? min"}, 1f);
    }

    /**
     * The latest offer at the top of a short window, as its tile (0.6.3: labels and a badge, not a sentence): it draws
     * {@code drawn}, the pay first and the trip whole, an unread figure as unread ("$?", "? mi", "? min"), never left
     * out or guessed; screen readers hear those facts in words ({@code said}) before the outcome, then the reason and
     * what a tap does.
     */
    private void assertOverview(OfferSnapshot facts, String said, String[] drawn, float fontScale) {
        RuntimeEnvironment.setFontScale(fontScale);
        FilterStore.save(app, RULES);
        DecisionLog.Entry offer = entry(facts);
        DecisionLog.record(app, offer);
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            VerdictTile tile = verdictTile(content);
            assertTrue("the tile is on the page", tile.isShown());
            String visible = tile.shownText();
            assertTrue("the pay comes first: " + visible, visible.startsWith(drawn[0] + " · "));
            assertTrue("then the miles and the trip time, whole: " + visible,
                    visible.endsWith(" · " + drawn[1] + " · " + drawn[2]));
            assertFalse("facts are never cut: " + visible, visible.contains("…"));
            assertEquals("the pay at its own size, never drawn smaller: " + visible,
                    VerdictTile.drawn(tile.getResources().getDisplayMetrics(), 16), tile.payTextSize(), 0.5f);
            String outcome = MainActivity.captionOutcome(offer);
            assertEquals("outcome stays visible, as the tile's badge", outcome, tile.outcome());
            assertNotNull(tile.symbol());
            String heard = String.valueOf(tile.getContentDescription());
            assertTrue("facts come first, in words: " + heard, heard.startsWith(said + " · " + outcome + " · "));
            assertTrue("reason stays accessible", heard.toLowerCase(java.util.Locale.US)
                    .contains(MainActivity.plainReason(offer.reason).toLowerCase(java.util.Locale.US)));
            assertTrue(heard.endsWith(VerdictTile.ACTION));
            Rect visibleBounds = new Rect();
            assertTrue(tile.getGlobalVisibleRect(visibleBounds));
            assertEquals("the whole tile remains in the window", tile.getHeight(), visibleBounds.height());
            assertEquals("the whole tile remains in the window", tile.getWidth(), visibleBounds.width());
            assertTrue("its two lines fit its height", tile.getHeight() >= VerdictTile.heightFor(tile.getContext()) - 1);
            assertTrue(tile.getHeight() >= new Ui(app).dp(48));
            assertTrue(tile.getWidth() >= new Ui(app).dp(48));
            render(content, facts.payCents == null ? "unread-facts" : facts.minutes == null ? "unread-time-font2"
                    : "complete-facts-short");
            tile.performClick();
            assertNotNull("the overview opens the offer's ticket", find(content, OfferCardView.class));
            assertEquals("this offer's", DecisionLog.outcome(offer).said,
                    find(content, Decor.Stamp.class).getContentDescription().toString());
            assertArrayEquals(RULES.minimums(), FilterStore.load(app).minimums());
        }
    }

    private static void render(View content, String name) {
        Bitmap bitmap = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
        content.draw(new Canvas(bitmap));
        File directory = new File("build/reports/stable-reference");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (java.io.IOException failure) {
            throw new AssertionError("could not retain the simulated UI render", failure);
        } finally { bitmap.recycle(); }
    }
}
