package com.local.dasherfilter;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.robolectric.shadows.ShadowChoreographer;

import java.time.Duration;

/**
 * Models an initially visible application window in these interactive legacy-graphics fixtures.
 * Robolectric 4.16.1 WindowSessionDelegate.addToDisplay otherwise omits ADD_FLAG_APP_VISIBLE;
 * native graphics sets that flag automatically. Real ViewRoot visibility transitions still apply.
 * A paused Choreographer delivers frames when the fixture advances time, rather than recursively
 * advancing its own clock while an animated visible window keeps requesting another frame.
 * Scope is one test; the prior visibility property and frame settings are always restored.
 */
final class VisibleActivityWindows implements TestRule {
    private static final String PROPERTY = "robolectric.areWindowsMarkedVisible";

    @Override public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override public void evaluate() throws Throwable {
                String previous = System.getProperty(PROPERTY);
                boolean wasPaused = ShadowChoreographer.isPaused();
                Duration previousDelay = ShadowChoreographer.getFrameDelay();
                System.setProperty(PROPERTY, "true");
                try {
                    ShadowChoreographer.setPaused(true);
                    ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
                    base.evaluate();
                } finally {
                    ShadowChoreographer.setFrameDelay(previousDelay);
                    ShadowChoreographer.setPaused(wasPaused);
                    if (previous == null) System.clearProperty(PROPERTY);
                    else System.setProperty(PROPERTY, previous);
                }
            }
        };
    }
}
