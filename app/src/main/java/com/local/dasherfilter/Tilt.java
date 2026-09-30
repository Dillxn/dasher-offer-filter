package com.local.dasherfilter;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Surface;
import android.view.WindowManager;

/**
 * How the phone is tilted, for the drawings' parallax: far layers (stars, sky) slide a little against near ones as
 * the phone moves. It reads the gyroscope-fused game rotation vector, else gravity, else the accelerometer; nothing
 * leaves the phone and no permission is needed. It listens only while the app is on screen with animations on, and
 * it recentres slowly, so whatever angle the phone is held at reads as level and only movement shows.
 */
final class Tilt implements SensorEventListener {
    /** How far a tilt of about 30° moves things, before the ±1 limit. */
    private static final float GAIN = 2f;
    /** Share of the way the resting angle moves toward the current one per event (~2 s to settle at 50 Hz). */
    private static final float RECENTRE = 0.01f;
    /** Share of the way the shown tilt moves toward the measured one per event, so it glides. */
    private static final float SMOOTHING = 0.15f;

    private static Tilt listening;
    private static float x;
    private static float y;

    private final SensorManager sensors;
    private final int rotation;
    private final float[] matrix = new float[9];
    private boolean rested;
    private float restX;
    private float restY;

    private Tilt(SensorManager sensors, int rotation) {
        this.sensors = sensors;
        this.rotation = rotation;
    }

    /** Sideways tilt, -1 (left) to 1 (right); 0 when not listening. */
    static float x() {
        return listening == null ? 0 : x;
    }

    /** Forward tilt, -1 (top tipped away) to 1 (top tipped toward you); 0 when not listening. */
    static float y() {
        return listening == null ? 0 : y;
    }

    static void start(Context context) {
        stop();
        if (!Motion.on()) return;
        SensorManager sensors = context.getSystemService(SensorManager.class);
        if (sensors == null) return;
        Sensor sensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (sensor == null) sensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (sensor == null) sensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (sensor == null) return;
        Tilt tilt = new Tilt(sensors, displayRotation(context));
        if (sensors.registerListener(tilt, sensor, SensorManager.SENSOR_DELAY_GAME)) listening = tilt;
    }

    static void stop() {
        if (listening != null) listening.sensors.unregisterListener(listening);
        listening = null;
        x = 0;
        y = 0;
    }

    @SuppressWarnings("deprecation")
    private static int displayRotation(Context context) {
        WindowManager windows = context.getSystemService(WindowManager.class);
        return windows == null ? Surface.ROTATION_0 : windows.getDefaultDisplay().getRotation();
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (this != listening) return;
        float upX;
        float upY;
        if (event.sensor.getType() == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            // The world's "up" seen from the phone is the rotation matrix's last row.
            SensorManager.getRotationMatrixFromVector(matrix, event.values);
            upX = matrix[6];
            upY = matrix[7];
        } else {
            float length = (float) Math.sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1]
                    + event.values[2] * event.values[2]);
            if (length < 1f) return;
            upX = event.values[0] / length;
            upY = event.values[1] / length;
        }
        // Phone axes to screen axes when the screen is turned.
        float screenX;
        float screenY;
        switch (rotation) {
            case Surface.ROTATION_90: screenX = -upY; screenY = upX; break;
            case Surface.ROTATION_180: screenX = -upX; screenY = -upY; break;
            case Surface.ROTATION_270: screenX = upY; screenY = -upX; break;
            default: screenX = upX; screenY = upY;
        }
        if (!rested) {
            restX = screenX;
            restY = screenY;
            rested = true;
        }
        restX += (screenX - restX) * RECENTRE;
        restY += (screenY - restY) * RECENTRE;
        float targetX = clamp(-(screenX - restX) * GAIN);
        float targetY = clamp((screenY - restY) * GAIN);
        x += (targetX - x) * SMOOTHING;
        y += (targetY - y) * SMOOTHING;
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private static float clamp(float value) {
        return Math.max(-1f, Math.min(1f, value));
    }
}
