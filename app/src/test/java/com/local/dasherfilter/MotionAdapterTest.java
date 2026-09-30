package com.local.dasherfilter;

import android.animation.ValueAnimator;
import android.app.Application;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSensor;
import org.robolectric.shadows.ShadowSensorManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The drawings' tilt parallax and motion clock, through Android's sensor and animator adapters. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,35})
@LooperMode(LooperMode.Mode.PAUSED)
public class MotionAdapterTest {
    private final Application app = RuntimeEnvironment.getApplication();

    @After
    public void tearDown() throws Exception {
        Tilt.stop();
        setAnimatorDurationScale(1f);
    }

    @Test
    public void tiltFollowsTheGyroscopeAndSettlesBackToLevel() {
        ShadowSensorManager sensors = sensors();
        sensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_GAME_ROTATION_VECTOR));
        Tilt.start(app);
        assertEquals("listens while the app is open", 1, sensors.getListeners().size());

        // Held at some angle: that angle reads as level.
        for (int i = 0; i < 20; i++) sensors.sendSensorEventToListeners(rotation(0.3f, 0f));
        assertEquals(0f, Tilt.x(), 0.001f);
        assertEquals(0f, Tilt.y(), 0.001f);

        // Tipped sideways: the drawings shift, gliding rather than jumping.
        sensors.sendSensorEventToListeners(rotation(0.3f, 0.25f));
        float first = Math.abs(Tilt.x());
        for (int i = 0; i < 20; i++) sensors.sendSensorEventToListeners(rotation(0.3f, 0.25f));
        float moved = Math.abs(Tilt.x());
        assertTrue("moves with the tilt: " + moved, moved > 0.3f);
        assertTrue("glides there", first > 0 && first < moved);

        // Held still at the new angle, it slowly reads as level again.
        for (int i = 0; i < 600; i++) sensors.sendSensorEventToListeners(rotation(0.3f, 0.25f));
        assertTrue("recentred: " + Tilt.x(), Math.abs(Tilt.x()) < 0.05f);

        Tilt.stop();
        assertEquals("stops listening when the app leaves the screen", 0, sensors.getListeners().size());
        assertEquals(0f, Tilt.x(), 0f);
    }

    @Test
    public void withAnimationsOffNothingListensOrMoves() throws Exception {
        ShadowSensorManager sensors = sensors();
        sensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_GAME_ROTATION_VECTOR));
        setAnimatorDurationScale(0f);
        Tilt.start(app);
        assertEquals(0, sensors.getListeners().size());
        assertEquals(0f, Motion.seconds(), 0f);
        assertEquals("a finished-looking settle, so nothing waits on motion", 1f,
                Motion.settle(android.os.SystemClock.uptimeMillis(), 700), 0f);
    }

    @Test
    public void withoutAGyroscopeGravityIsUsed() {
        ShadowSensorManager sensors = sensors();
        sensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_GRAVITY));
        Tilt.start(app);
        assertEquals(1, sensors.getListeners().size());
        for (int i = 0; i < 5; i++) sensors.sendSensorEventToListeners(gravity(0f, 6.9f, 6.9f));
        for (int i = 0; i < 20; i++) sensors.sendSensorEventToListeners(gravity(-3.4f, 6.4f, 6.4f));
        assertTrue("right edge down reads as a tilt to the right: " + Tilt.x(), Tilt.x() > 0.3f);
    }

    private ShadowSensorManager sensors() {
        return Shadows.shadowOf(app.getSystemService(SensorManager.class));
    }

    /** A game rotation vector for the phone turned {@code pitch} about its x axis then {@code roll} about y. */
    private static SensorEvent rotation(float pitch, float roll) {
        // Quaternion of the combined turn; the event carries its x, y, z parts.
        double cp = Math.cos(pitch / 2);
        double sp = Math.sin(pitch / 2);
        double cr = Math.cos(roll / 2);
        double sr = Math.sin(roll / 2);
        SensorEvent event = ShadowSensorManager.createSensorEvent(4, Sensor.TYPE_GAME_ROTATION_VECTOR);
        event.values[0] = (float) (sp * cr);
        event.values[1] = (float) (cp * sr);
        event.values[2] = (float) (sp * sr);
        event.values[3] = (float) (cp * cr);
        return event;
    }

    private static SensorEvent gravity(float x, float y, float z) {
        SensorEvent event = ShadowSensorManager.createSensorEvent(3, Sensor.TYPE_GRAVITY);
        event.values[0] = x;
        event.values[1] = y;
        event.values[2] = z;
        return event;
    }

    /** Android's "animator duration scale" developer and accessibility setting; 0 is "remove animations". */
    private static void setAnimatorDurationScale(float scale) throws Exception {
        ValueAnimator.class.getMethod("setDurationScale", float.class).invoke(null, scale);
        assertEquals(scale != 0, ValueAnimator.areAnimatorsEnabled());
    }
}
