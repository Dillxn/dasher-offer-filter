package com.local.dasherfilter;

import android.animation.ValueAnimator;
import android.os.SystemClock;
import android.view.View;

/**
 * The drawings' gentle motion: one clock, and a way to ask for the next frame. Nothing moves when Android's "remove
 * animations" setting is on. A view asks for the next frame only while it is being drawn, so motion stops by itself
 * off screen, behind another app, or with the screen off. In split screen the app stays on screen beside Dasher for
 * a whole dash, so the steady motion is calm then: about ten frames a second instead of every frame. A short
 * movement the user just caused (a press, a glide after a drag) still draws every frame.
 */
final class Motion {
    private static final long START = SystemClock.uptimeMillis();
    /** Between frames of steady motion while calm. */
    static final long CALM_FRAME_MS = 100;
    private static volatile boolean calm;

    private Motion() {}

    /** Calm in split screen (set by the main page as it resumes and enters or leaves split screen). */
    static void setCalm(boolean on) {
        calm = on;
    }

    /** How long steady motion waits for its next frame, in milliseconds: 0 for the very next frame. */
    static long frameDelay() {
        return calm ? CALM_FRAME_MS : 0;
    }

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

    /** Asks for another frame of steady motion after this one, if anything moves; later when calm. */
    static void next(View view) {
        if (!on()) return;
        long delay = frameDelay();
        if (delay > 0) view.postInvalidateDelayed(delay);
        else view.postInvalidateOnAnimation();
    }

    /** Asks for the very next frame of a short movement the user just caused, calm or not. */
    static void settling(View view) {
        if (on()) view.postInvalidateOnAnimation();
    }
}
