package com.local.dasherfilter;

import android.animation.ValueAnimator;
import android.os.SystemClock;
import android.view.View;

/**
 * The drawings' gentle motion: one clock, and a way to ask for the next frame. Nothing moves when Android's "remove
 * animations" setting is on. A view asks for the next frame only while it is being drawn, so motion stops by itself
 * off screen, behind another app, or with the screen off.
 */
final class Motion {
    private static final long START = SystemClock.uptimeMillis();

    private Motion() {}

    static boolean on() {
        return ValueAnimator.areAnimatorsEnabled();
    }

    /** Seconds since the app started, or 0 with animations off (every drawing at rest). */
    static float seconds() {
        return on() ? (SystemClock.uptimeMillis() - START) / 1000f : 0f;
    }

    /** A value that swings between -1 and 1 once every {@code period} seconds, offset by {@code phase} (0–1). */
    static float wave(float period, float phase) {
        return (float) Math.sin((seconds() / period + phase) * Math.PI * 2);
    }

    /** 0 to 1 over {@code period} seconds, then again. */
    static float loop(float period, float phase) {
        float t = seconds() / period + phase;
        return t - (float) Math.floor(t);
    }

    /** Eased 0–1 progress of something that started at {@code startedAt} (uptime ms) and lasts {@code duration} ms. */
    static float settle(long startedAt, long duration) {
        if (!on() || startedAt <= 0) return 1f;
        float t = Math.min(1f, Math.max(0f, (SystemClock.uptimeMillis() - startedAt) / (float) duration));
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    /** Asks for another frame after this one, if anything moves. */
    static void next(View view) {
        if (on()) view.postInvalidateOnAnimation();
    }
}
