package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

/** The sixth measure uses observed total items and the same exact floor components as decisions. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, qualifiers = "w411dp-h914dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class ItemConstellationTest extends AndroidAdapterTestBase {
    private static final FilterSettings RULES = new FilterSettings(true, 1000, 200, 50, 500, 3)
            .withHotspotProximity(50).withPerItem(50).withScoreByArea(true);

    private static OfferSnapshot offer(Integer items, boolean applicable) {
        return new OfferSnapshot(1200, 5.0, 20, 2).withFinalStopHotspotMiles(2.0).withItems(items, applicable);
    }

    private static DecisionLog.Entry entry(OfferSnapshot offer, FilterSettings rules) {
        return DecisionLog.Entry.of(DecisionLog.Source.SCREEN, false, offer, OfferRule.evaluate(offer, rules),
                DecisionLog.Action.NEEDS_REVIEW, true, Collections.emptyList());
    }

    private MinimumsStarView show(android.app.Activity activity, FilterSettings rules, OfferSnapshot offer) {
        LoneSky sky = new LoneSky(activity, rules);
        sky.star.show(rules, offer, Collections.singletonList(entry(offer, rules)));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        return sky.star;
    }

    @Test public void sixSpokeAreaMatchesTheExactCurrentScore() {
        OfferSnapshot facts = offer(20, true);
        try (ActivityController<android.app.Activity> activity = Robolectric.buildActivity(android.app.Activity.class)
                .setup()) {
            MinimumsStarView star = show(activity.get(), RULES, facts);
            assertEquals(6, star.minimumShape().size());
            assertEquals(6, star.offerShape(0).size());
            double score = AreaScore.score(AreaScore.floors(RULES, facts), facts.payCents);
            assertEquals(score * score, area(star.offerShape(0)) / area(star.minimumShape()), 0.00001);
            assertEquals(1000, star.setAsks(AreaScore.ITEM), 0);
            assertTrue(Double.isNaN(star.learnedAsks(AreaScore.ITEM)));
            assertTrue(node(star, AreaScore.ITEM).getContentDescription().toString().contains("20 items"));
        }
    }

    @Test public void missingAndInapplicableCountsHaveNoInventedVertexButKeepTheControl() {
        try (ActivityController<android.app.Activity> activity = Robolectric.buildActivity(android.app.Activity.class)
                .setup()) {
            MinimumsStarView star = show(activity.get(), RULES, offer(null, true));
            assertTrue(Double.isNaN(star.setAsks(AreaScore.ITEM)));
            assertNull(star.markAt(0, AreaScore.ITEM));
            assertEquals(5, star.offerShape(0).size());
            assertEquals(5, star.minimumShape().size());
            assertNotNull(star.knobAt(AreaScore.ITEM));
            assertTrue(star.caption(), star.caption().contains("needs the item count"));
            assertTrue(node(star, AreaScore.ITEM).getContentDescription().toString().contains("unavailable"));
            OfferSnapshot withoutItems = offer(null, false);
            star.show(RULES, withoutItems, Collections.singletonList(entry(withoutItems, RULES)));
            assertTrue(node(star, AreaScore.ITEM).getContentDescription().toString().contains("not applicable"));
            assertEquals(5, star.offerShape(0).size());
        }
    }

    @Test public void selectingShoppingHistoryRestoresItsApplicableAxisAndLabelsTheReratedScore() {
        OfferSnapshot shopping = offer(20, true), ordinary = offer(null, false);
        DecisionLog.Entry past = new DecisionLog.Entry(System.currentTimeMillis() - 60_000,
                DecisionLog.Source.SCREEN, false, shopping, 1000, OfferRule.Result.KEEP, "meets enabled rules",
                DecisionLog.Action.PASSES, true, Collections.emptyList()).withScore(80);
        DecisionLog.Entry latest = entry(ordinary, RULES);
        try (ActivityController<android.app.Activity> activity = Robolectric.buildActivity(android.app.Activity.class)
                .setup()) {
            MinimumsStarView star = show(activity.get(), RULES, ordinary);
            star.show(RULES, ordinary, Arrays.asList(latest, past));
            assertTrue(Double.isNaN(star.setAsks(AreaScore.ITEM)));
            star.emphasize(past);
            assertEquals(1000, star.setAsks(AreaScore.ITEM), 0);
            assertEquals(6, star.minimumShape().size());
            assertTrue(star.currentScoreLabel(), star.currentScoreLabel().startsWith("Now "));
            assertTrue(star.getContentDescription().toString().contains("At decision it scored 80%"));
            assertEquals("history is not rewritten by re-rating", 80, past.scorePercent);
            star.emphasize(null);
            assertTrue(Double.isNaN(star.setAsks(AreaScore.ITEM)));
        }
    }

    @Test public void exactFixedMileageAndLearnedComponentsStayVisibleWithoutAdaptiveBeingOn() {
        FilterSettings saved = new FilterSettings(true, 700, 199, 30, 100, 3, true, 0);
        FilterStore.save(app, saved);
        assertTrue(FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2)));
        FilterSettings learned = FilterStore.load(app).withAdaptive(false);
        OfferSnapshot facts = new OfferSnapshot(1200, 1.001, 20, 2).withItems(20, true);
        try (ActivityController<android.app.Activity> activity = Robolectric.buildActivity(android.app.Activity.class)
                .setup()) {
            MinimumsStarView star = show(activity.get(), learned, facts);
            AreaScore.Floors floors = AreaScore.floors(learned, facts);
            assertEquals(199.199, star.setAsks(AreaScore.MILE), 0.000001);
            assertEquals(floors.acceptedCents[AreaScore.MILE].doubleValue(), star.learnedAsks(AreaScore.MILE), 0);
            assertTrue(node(star, AreaScore.MILE).getContentDescription().toString().contains("learned, not applied"));
        }
    }

    @Test public void itemMinimumIsAdjustableWithoutChangingOtherSavedOrLearnedValues() {
        FilterStore.save(app, RULES);
        DecisionLog.record(app, entry(offer(20, true), RULES));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            int[] before = FilterStore.load(app).minimums();
            assertTrue(act(star, AreaScore.ITEM, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD));
            int[] after = FilterStore.load(app).minimums();
            assertEquals(55, after[AreaScore.ITEM]);
            for (int axis = 0; axis < AreaScore.ITEM; axis++) assertEquals(before[axis], after[axis]);
            assertEquals("$0.55/item", MinimumsStarView.readout(AreaScore.ITEM, after[AreaScore.ITEM]));
        }
    }

    @Test public void cardDistinguishesCountRateUnknownAndNotApplicable() {
        OfferCardView card = new OfferCardView(app, new Ui(app));
        card.show(entry(offer(20, true), RULES));
        assertTrue(card.getContentDescription().toString().contains("20 items · $0.60/item"));
        card.show(entry(offer(null, true), RULES));
        assertTrue(card.getContentDescription().toString().contains("Item count unavailable"));
        assertFalse(card.getContentDescription().toString().contains("/item"));
        card.show(entry(offer(null, false), RULES));
        assertFalse(card.getContentDescription().toString().contains("item"));
        assertTrue(MinimumsStarView.itemsLabel(offer(7, true)).contains("≈"));
    }

    @Test public void selectedItemSpokeExplainsObservedUnavailableAndInapplicableData() throws Exception {
        try (ActivityController<android.app.Activity> activity = Robolectric.buildActivity(android.app.Activity.class)
                .setup()) {
            OfferSnapshot[] offers = {offer(20, true), offer(null, true), offer(null, false)};
            String[] states = {"20 items · $0.60/item", "Item count unavailable", "not applicable"};
            for (int i = 0; i < offers.length; i++) {
                MinimumsStarView star = show(activity.get(), RULES, offers[i]);
                star.setOfferTaps(ignored -> {});
                assertTrue(act(star, AreaScore.ITEM, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS));
                java.lang.reflect.Method readout = MinimumsStarView.class.getDeclaredMethod("focusedReadout");
                readout.setAccessible(true);
                String visible = (String) readout.invoke(star);
                assertTrue(visible, visible.contains("$0.50/item"));
                assertTrue(visible, visible.contains(states[i]));
                String spoken = node(star, MinimumsStarView.OFFER_ID).getContentDescription().toString();
                assertTrue(spoken, spoken.contains(states[i]));
                if (i > 0) assertNull("unknown/inapplicable data never creates an item vertex",
                        star.markAt(0, AreaScore.ITEM));
            }
        }
    }

    @Test public void unreadableSelectedPayoutNeverBorrowsAnOlderOffersPayForItemRate() throws Exception {
        OfferSnapshot previous = offer(null, false);
        OfferSnapshot unread = OfferSnapshot.UNKNOWN.withItems(12, true);
        DecisionLog.Entry past = new DecisionLog.Entry(System.currentTimeMillis() - 60_000,
                DecisionLog.Source.SCREEN, false, previous, 1000, OfferRule.Result.KEEP, "meets enabled rules",
                DecisionLog.Action.PASSES, true, Collections.emptyList());
        DecisionLog.Entry latest = entry(unread, RULES);
        try (ActivityController<android.app.Activity> activity = Robolectric.buildActivity(android.app.Activity.class)
                .setup()) {
            MinimumsStarView star = show(activity.get(), RULES, previous);
            star.show(RULES, previous, Arrays.asList(latest, past));
            assertTrue(act(star, AreaScore.ITEM, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS));
            java.lang.reflect.Method readout = MinimumsStarView.class.getDeclaredMethod("focusedReadout");
            readout.setAccessible(true);
            assertEquals("Min $0.50/item · 12 items", readout.invoke(star));
            String spoken = node(star, AreaScore.ITEM).getContentDescription().toString();
            assertTrue(spoken, spoken.contains("12 items"));
            assertFalse("old $12 payout must not price the new 12-item offer", spoken.contains("$1.00/item"));
        }
    }

    @Test @Config(sdk = 35) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void phoneExportsSixSpokePreviewWithClearLabelsAndControls() throws Exception {
        preview(false, "phone");
    }

    @Test @Config(sdk = 35, qualifiers = "w411dp-h410dp-420dpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void shortSplitExportsSixSpokePreviewWithClearLabelsAndControls() throws Exception {
        preview(true, "split");
    }

    @Test @Config(sdk = 35, qualifiers = "w411dp-h914dp-xxhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void largeFontExportsSixSpokePreviewWithoutClippedLabels() throws Exception {
        android.content.res.Configuration configuration = new android.content.res.Configuration(app.getResources().getConfiguration());
        configuration.fontScale = 2f;
        app.getResources().updateConfiguration(configuration, app.getResources().getDisplayMetrics());
        preview(false, "large-font");
    }

    private void preview(boolean split, String name) throws Exception {
        FilterStore.save(app, RULES);
        DecisionLog.record(app, entry(offer(20, true), RULES));
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        ServiceController<OfferNotificationService> listener = Robolectric.buildService(OfferNotificationService.class).create();
        service.get().onServiceConnected();
        listener.get().onListenerConnected();
        if (split) OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        if (split) Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(star.backdrop());
            save(content, name);
            for (int axis = 0; axis < AreaScore.AXES; axis++) {
                RectF label = star.axisLabelBox(axis);
                assertNotNull("label for " + axis, label);
                assertTrue(label.toString(), label.left >= 0 && label.top >= 0
                        && label.right <= star.getWidth() && label.bottom <= star.getHeight());
            }
            java.lang.reflect.Method iconAt = MinimumsStarView.class.getDeclaredMethod("skyIcon", int.class,
                    float.class, float.class, float.class, RectF.class);
            iconAt.setAccessible(true);
            RectF itemIcon = new RectF();
            assertTrue((Boolean) iconAt.invoke(star, AreaScore.ITEM, star.skyX(), star.skyY(), star.skyRadius(), itemIcon));
            float[] observedItem = star.markAt(0, AreaScore.ITEM);
            assertNotNull(observedItem);
            float markReach = new Ui(app).dp(8);
            assertFalse("shopping icon leaves the observed item point legible", RectF.intersects(itemIcon,
                    new RectF(observedItem[0] - markReach, observedItem[1] - markReach,
                            observedItem[0] + markReach, observedItem[1] + markReach)));
            Rect itemTarget = new Rect();
            node(star, AreaScore.ITEM).getBoundsInParent(itemTarget);
            for (RectF button : new RectF[] {star.scoreToggleBox(), star.adaptiveBox(), star.stopsBox()}) {
                assertNotNull(button);
                assertFalse("item knob remains clear of the other controls", RectF.intersects(new RectF(itemTarget), button));
            }
            openTicket(content);
            settleSky(content);
            save(content, name + "-ticket");
        } finally {
            listener.destroy();
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    private static void save(View content, String name) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
        content.draw(new Canvas(bitmap));
        File directory = new File("build/reports/item-constellation");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }

    private static double area(List<float[]> polygon) {
        double twice = 0;
        for (int i = 0; i < polygon.size(); i++) {
            float[] a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            twice += (double) a[0] * b[1] - (double) b[0] * a[1];
        }
        return Math.abs(twice) / 2;
    }
}
