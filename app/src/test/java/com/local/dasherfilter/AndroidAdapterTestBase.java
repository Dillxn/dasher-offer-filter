package com.local.dasherfilter;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.graphics.Insets;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.time.Duration;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowNotificationManager;

import static org.junit.Assert.assertTrue;

/**
 * Setup and helpers shared by the AndroidAdapter*Test classes, which exercise notifications, jobs, the main page,
 * the settings screen and diagnostics through real Android adapters. The tests are split by topic so that Gradle's
 * parallel test JVMs share them out; tools/finalize_release.py counts the classes together.
 */
abstract class AndroidAdapterTestBase {
    /** Notification id used for offer alert cards. */
    static final int ALERT_NOTIFICATION_ID = 8241;
    /** Job id of the one-shot update retry job. */
    static final int UPDATE_RETRY_JOB_ID = 7243;
    /** Notification id of the updater's install-confirmation notice. */
    static final int UPDATE_NOTICE_ID = 7242;

    Application app;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        // The first-run notice accepted, as on a phone in use (ConsentedTestApp does so for every test already).
        ConsentedTestApp.accept(app);
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        Updater.setEnabled(app, false);
        OfferAlerts.ensureChannel(app);
        DecisionLog.forgetCache();
        AreaMap.forgetCache();
        OfferSilencer.forgetCache();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
    }

    @After
    public void tearDown() {
        OfferFilterService.scanLooperForTests = null;
        // Whatever these tests sent, the real feedback service would have taken.
        FakeFeedbackTransport.assertHonored();
    }

    static boolean isDescendant(View ancestor, View view) {
        for (View at = view; at != null; at = at.getParent() instanceof View ? (View) at.getParent() : null) {
            if (at == ancestor) return true;
        }
        return false;
    }

    static void layOut(View content) {
        int width = content.getResources().getDisplayMetrics().widthPixels;
        int height = content.getResources().getDisplayMetrics().heightPixels;
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        content.layout(0, 0, width, height);
    }

    /** A finger on the card's handle, pulled down {@code share} of its height over {@code ms}. */
    static void drag(View card, float share, long ms) {
        long start = android.os.SystemClock.uptimeMillis();
        float x = card.getWidth() / 2f;
        float y = 4;
        float to = card.getHeight() * share;
        card.dispatchTouchEvent(MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, x, y, 0));
        for (int step = 1; step <= 8; step++) {
            card.dispatchTouchEvent(MotionEvent.obtain(start, start + ms * step / 8, MotionEvent.ACTION_MOVE, x,
                    y + to * step / 8, 0));
        }
        card.dispatchTouchEvent(MotionEvent.obtain(start, start + ms + 50, MotionEvent.ACTION_UP, x, y + to, 0));
    }

    /**
     * The constellation alone as a page's sky, in a column tall enough to scroll, saving into {@link #saves} and
     * shown again from them, as the main page does.
     */
    static final class LoneSky {
        final Ui ui;
        final MinimumsStarView star;
        final android.widget.ScrollView scroll;
        final List<String> saves = new ArrayList<>();
        final int slop;
        int clicks;
        private FilterSettings rules;

        LoneSky(android.app.Activity activity, FilterSettings rules) {
            this.rules = rules;
            ui = new Ui(activity);
            slop = android.view.ViewConfiguration.get(activity).getScaledTouchSlop();
            star = new MinimumsStarView(activity, ui);
            star.setChanges(new MinimumsStarView.Changes() {
                @Override public void setMinimum(int axis, int cents) {
                    saves.add(axis + "=" + cents);
                    int[] rates = LoneSky.this.rules.minimums();
                    rates[axis] = cents;
                    LoneSky.this.rules = LoneSky.this.rules.withMinimums(rates);
                    show();
                }

                @Override public int[] adoptLearned() {
                    return null;
                }

                @Override public void restore(int[] cents) {}
            });
            star.setOnClickListener(tapped -> clicks++);
            scroll = new android.widget.ScrollView(activity);
            android.widget.LinearLayout column = ui.column();
            android.widget.FrameLayout sky = new android.widget.FrameLayout(activity);
            sky.addView(star, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            column.addView(sky, new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ui.dp(480)));
            column.addView(new View(activity), new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(900)));
            scroll.addView(column);
            activity.setContentView(scroll);
            layOut();
            show();
            star.compose(scroll.getWidth() / 2f, ui.dp(240), ui.dp(180), Collections.emptyList());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        }

        private void show() {
            star.show(rules, new OfferSnapshot(null, 5.0, 20, 2), Collections.emptyList());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        }

        void layOut() {
            View root = scroll.getRootView();
            int width = root.getResources().getDisplayMetrics().widthPixels;
            int height = root.getResources().getDisplayMetrics().heightPixels;
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);
        }

        /** A finger from {@code from} to {@code to} (in the star's pixels, the page unscrolled), then lifted. */
        void swipe(float[] from, float[] to) {
            drag(new float[][] {from, to});
        }

        /** A finger down at the first point, moved through the others in small steps, lifted at the last. */
        void drag(float[][] points) {
            int[] starAt = new int[2];
            int[] scrollAt = new int[2];
            star.getLocationInWindow(starAt);
            scroll.getLocationInWindow(scrollAt);
            float dx = starAt[0] - scrollAt[0];
            float dy = starAt[1] - scrollAt[1];
            long now = android.os.SystemClock.uptimeMillis();
            long at = now;
            scroll.dispatchTouchEvent(MotionEvent.obtain(now, at, MotionEvent.ACTION_DOWN, points[0][0] + dx,
                    points[0][1] + dy, 0));
            for (int leg = 1; leg < points.length; leg++) {
                for (int step = 1; step <= 12; step++) {
                    at += 16;
                    float x = points[leg - 1][0] + (points[leg][0] - points[leg - 1][0]) * step / 12;
                    float y = points[leg - 1][1] + (points[leg][1] - points[leg - 1][1]) * step / 12;
                    scroll.dispatchTouchEvent(MotionEvent.obtain(now, at, MotionEvent.ACTION_MOVE, x + dx, y + dy, 0));
                }
            }
            float[] last = points[points.length - 1];
            scroll.dispatchTouchEvent(MotionEvent.obtain(now, at + 50, MotionEvent.ACTION_UP, last[0] + dx,
                    last[1] + dy, 0));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        }
    }

    /** A finger down at the first point (in the star's pixels), moved through the others in steps, then lifted. */
    static void dragThrough(View content, float[][] points, Runnable whileHeld) {
        MinimumsStarView star = find(content, MinimumsStarView.class);
        int[] starAt = new int[2];
        int[] contentAt = new int[2];
        star.getLocationInWindow(starAt);
        content.getLocationInWindow(contentAt);
        float dx = starAt[0] - contentAt[0];
        float dy = starAt[1] - contentAt[1];
        long now = android.os.SystemClock.uptimeMillis();
        long at = now;
        content.dispatchTouchEvent(MotionEvent.obtain(now, at, MotionEvent.ACTION_DOWN, points[0][0] + dx,
                points[0][1] + dy, 0));
        for (int leg = 1; leg < points.length; leg++) {
            for (int step = 1; step <= 12; step++) {
                at += 16;
                float x = points[leg - 1][0] + (points[leg][0] - points[leg - 1][0]) * step / 12;
                float y = points[leg - 1][1] + (points[leg][1] - points[leg - 1][1]) * step / 12;
                content.dispatchTouchEvent(MotionEvent.obtain(now, at, MotionEvent.ACTION_MOVE, x + dx, y + dy, 0));
            }
        }
        if (whileHeld != null) whileHeld.run();
        float[] last = points[points.length - 1];
        content.dispatchTouchEvent(MotionEvent.obtain(now, at + 50, MotionEvent.ACTION_UP, last[0] + dx,
                last[1] + dy, 0));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Lays the page out and lets the constellation's points glide to where they are heading. */
    static void settleSky(View content) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
        layOut(content);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800));
    }

    static double spokeAngle(int axis) {
        float spread = MinimumsStarView.SPREAD;
        return Math.toRadians(new float[] {spread - 180, -spread, spread, 180 - spread}[axis]);
    }

    /** {@code from} moved {@code by} out along spoke {@code axis}, and {@code aside} square to it. */
    static float[] alongSpoke(float[] from, int axis, float by, float aside) {
        double angle = spokeAngle(axis);
        return new float[] {(float) (from[0] + Math.cos(angle) * by - Math.sin(angle) * aside),
                (float) (from[1] + Math.sin(angle) * by + Math.cos(angle) * aside)};
    }

    /** How far out along spoke {@code axis} from {@code middle} the point {@code at} stands. */
    static double along(float[] middle, int axis, float[] at) {
        double angle = spokeAngle(axis);
        return (at[0] - middle[0]) * Math.cos(angle) + (at[1] - middle[1]) * Math.sin(angle);
    }

    /**
     * A finger down on a knob at {@code from} and moved to {@code to} in steps, then lifted, all delivered from the
     * top of the window as the screen delivers it; {@code whileHeld} runs before the finger lifts.
     */
    static void dragKnob(View content, MinimumsStarView star, float[] from, float[] to, Runnable whileHeld) {
        int[] starAt = new int[2];
        int[] contentAt = new int[2];
        star.getLocationInWindow(starAt);
        content.getLocationInWindow(contentAt);
        float dx = starAt[0] - contentAt[0];
        float dy = starAt[1] - contentAt[1];
        long now = android.os.SystemClock.uptimeMillis();
        content.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, from[0] + dx, from[1] + dy,
                0));
        for (int step = 1; step <= 12; step++) {
            float x = from[0] + (to[0] - from[0]) * step / 12;
            float y = from[1] + (to[1] - from[1]) * step / 12;
            content.dispatchTouchEvent(MotionEvent.obtain(now, now + step * 16L, MotionEvent.ACTION_MOVE, x + dx,
                    y + dy, 0));
        }
        if (whileHeld != null) whileHeld.run();
        content.dispatchTouchEvent(MotionEvent.obtain(now, now + 300, MotionEvent.ACTION_UP, to[0] + dx, to[1] + dy,
                0));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    static boolean containsPoint(List<android.graphics.RectF> boxes, float x, float y) {
        for (android.graphics.RectF box : boxes) if (box.contains(x, y)) return true;
        return false;
    }

    /** A finger down and up at ({@code x}, {@code y}) in {@code parent}, as the screen delivers it. */
    static void tap(ViewGroup parent, float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0);
        parent.dispatchTouchEvent(down);
        parent.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Settings is the page shown (its Share report row is on screen). */
    static boolean settingsShown(View content) {
        return shownButton(content, "Share report") != null;
    }

    /** The constellation's accessibility node {@code id} (a knob, a button, the badge), or null when not there. */
    static android.view.accessibility.AccessibilityNodeInfo node(MinimumsStarView star, int id) {
        return star.getAccessibilityNodeProvider().createAccessibilityNodeInfo(id);
    }

    /** A screen reader's action on the constellation's node {@code id}. */
    static boolean act(MinimumsStarView star, int id, int action) {
        return star.getAccessibilityNodeProvider().performAction(id, action, null);
    }

    /** Dasher installed on the simulated phone, with its launcher activity. */
    void dasherInstalled() {
        android.content.ComponentName dasher =
                new android.content.ComponentName("com.doordash.driverapp", "com.doordash.driverapp.Home");
        org.robolectric.shadows.ShadowPackageManager packages = Shadows.shadowOf(app.getPackageManager());
        android.content.pm.ActivityInfo entry = packages.addActivityIfNotPresent(dasher);
        entry.enabled = true;
        entry.exported = true;
        android.content.IntentFilter launcher = new android.content.IntentFilter(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        packages.addIntentFilterForActivity(dasher, launcher);
    }

    /** A shown view with this description, or null. */
    static View shownIcon(View content, String description) {
        View found = iconDescribed(content, description);
        return found != null && found.isShown() ? found : null;
    }

    static View iconDescribed(View view, String description) {
        if (view.getContentDescription() != null && description.contentEquals(view.getContentDescription())) {
            return view;
        }
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                View found = iconDescribed(((ViewGroup) view).getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    static DecisionLog.Entry declinedEntry() {
        return new DecisionLog.Entry(System.currentTimeMillis(), DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(790, 7.2, 21, 2), 1080, OfferRule.Result.DECLINE, "dollars per mile",
                DecisionLog.Action.DECLINE_TAPPED, true, Arrays.asList("$7.90", "2 stops (7.2 mi) • 21 min"));
    }

    static void collectButtons(View view, List<Button> out) {
        if (view instanceof Button) out.add((Button) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectButtons(group.getChildAt(i), out);
        }
    }

    /** The first view of {@code type} in the tree, or null. */
    static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = find(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The first button labeled {@code text} that is actually on screen (itself and every ancestor visible). */
    static Button shownButton(View root, String text) {
        List<Button> buttons = new ArrayList<>();
        collectButtons(root, buttons);
        for (Button button : buttons) {
            if (button.isShown() && text.contentEquals(button.getText())) return button;
        }
        return null;
    }

    static EditText findEditText(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findEditText(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    static TextView findText(View view, String text) {
        if (view instanceof TextView && !(view instanceof Button) && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    static TextView findTextContaining(View view, String text) {
        if (view instanceof TextView && view.getVisibility() == View.VISIBLE
                && ((TextView) view).getText().toString().contains(text)) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findTextContaining(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Like {@link #findTextContaining}, but only text actually on screen (itself and every ancestor visible). */
    static TextView shownTextContaining(View view, String text) {
        if (view instanceof TextView && view.isShown() && ((TextView) view).getText().toString().contains(text)) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = shownTextContaining(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The round icon button whose description is {@code description}, or null. */
    static android.widget.ImageButton iconButton(View view, String description) {
        if (view instanceof android.widget.ImageButton && view.getContentDescription() != null
                && description.contentEquals(view.getContentDescription())) {
            return (android.widget.ImageButton) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.ImageButton found = iconButton(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The phone's last known position, fresh, at approximate accuracy. */
    void setLocation(double latitude, double longitude) {
        android.location.Location fix = new android.location.Location(android.location.LocationManager.NETWORK_PROVIDER);
        fix.setLatitude(latitude);
        fix.setLongitude(longitude);
        fix.setAccuracy(1500);
        fix.setTime(System.currentTimeMillis());
        fix.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
        Shadows.shadowOf(app.getSystemService(android.location.LocationManager.class))
                .setLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER, fix);
    }

    int notedOffers;

    /** A distinct standalone offer noted while the phone is at the given position. */
    void noteOfferAt(double latitude, double longitude, int payCents, double miles) {
        setLocation(latitude, longitude);
        notedOffers++;
        AreaMap.note(app, new DecisionLog.Entry(System.currentTimeMillis(), DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(payCents, miles, 20 + notedOffers, 2), 1000, OfferRule.Result.KEEP,
                "meets enabled rules", DecisionLog.Action.PASSES, true, Collections.emptyList()));
    }

    /** The line under the map as screen readers hear it: its rank is on the map's coin, not in its words. */
    static String areaLineSaid(View content) {
        TextView line = shownTextContaining(content, "  ›");
        return line == null || line.getContentDescription() == null ? "" : line.getContentDescription().toString();
    }

    /** Any TextView (a switch's label too) whose text contains {@code part}, shown or not. */
    static TextView findTextView(View view, String part) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(part)) return (TextView) view;
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                TextView found = findTextView(((ViewGroup) view).getChildAt(i), part);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Taps the skyline, unfolding the chosen offer's ticket. */
    static void openTicket(View content) {
        DecisionChartView chart = findChart(content);
        assertTrue("the skyline", chart != null && chart.isShown());
        chart.performClick();
    }

    static void settle() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));
    }

    static DecisionChartView findChart(View view) {
        if (view instanceof DecisionChartView) return (DecisionChartView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                DecisionChartView found = findChart(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** A DoorDash offer notification in the shape observed on a real device, posted now. */
    StatusBarNotification doorDashOffer(String text) {
        Notification payload = new Notification.Builder(app, "source")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("New Delivery!")
                .setContentText(text)
                .build();
        return new StatusBarNotification("com.doordash.driverapp", "com.doordash.driverapp", 3, "NEW_ORDER", 10001, 0,
                0, payload, android.os.Process.myUserHandle(), System.currentTimeMillis());
    }

    static Intent installResult(int session, int status) {
        return new Intent(UpdateReceiver.INSTALL_RESULT)
                .putExtra(PackageInstaller.EXTRA_SESSION_ID, session)
                .putExtra(PackageInstaller.EXTRA_STATUS, status);
    }

    /** Status bar 50 px, navigation bar 80 px, and a keyboard of {@code keyboard} px (0: none). Requires API 30+. */
    static void dispatchEdgeToEdgeInsets(View view, int keyboard) {
        view.dispatchApplyWindowInsets(new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 50, 0, 0))
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 80))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, keyboard))
                .build());
    }

    /** The Robolectric shadow of the app's notification manager, looked up fresh on each call. */
    ShadowNotificationManager notifications() {
        return Shadows.shadowOf(app.getSystemService(NotificationManager.class));
    }

    /** Depth-first search for a {@link Button} whose text is exactly {@code text}; null if none. */
    View findButton(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button) view).getText())) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findButton(group.getChildAt(i), text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
