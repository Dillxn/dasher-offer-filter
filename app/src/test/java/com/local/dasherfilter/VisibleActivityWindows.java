package com.local.dasherfilter;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * Models an initially visible application window in these interactive legacy-graphics fixtures.
 * Robolectric 4.16.1 WindowSessionDelegate.addToDisplay otherwise omits ADD_FLAG_APP_VISIBLE;
 * native graphics sets that flag automatically. Real ViewRoot visibility transitions still apply.
 * Scope is one test, with the previous process property restored even when an assertion fails.
 */
final class VisibleActivityWindows implements TestRule {
    private static final String PROPERTY = "robolectric.areWindowsMarkedVisible";

    @Override public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override public void evaluate() throws Throwable {
                String previous = System.getProperty(PROPERTY);
                System.setProperty(PROPERTY, "true");
                try {
                    base.evaluate();
                } finally {
                    if (previous == null) System.clearProperty(PROPERTY);
                    else System.setProperty(PROPERTY, previous);
                }
            }
        };
    }
}
