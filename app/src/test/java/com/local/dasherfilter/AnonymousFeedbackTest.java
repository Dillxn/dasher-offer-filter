package com.local.dasherfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class AnonymousFeedbackTest {
    @Test public void endpointIsExactHttpsFunction() {
        assertEquals("https://zlnfvqyyjsltmkmmpgzp.supabase.co/functions/v1/offer-filter-feedback",
                AnonymousFeedback.ENDPOINT);
    }

    @Test public void diagnosticsRequireExplicitPayloadFlag() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        JSONObject plain = AnonymousFeedback.payload(app, "feedback", "general", "hello", null, false);
        assertFalse(plain.has("diagnostics"));
        assertFalse(plain.getBoolean("diagnosticsConsented"));

        JSONObject attached = AnonymousFeedback.payload(app, "feedback", "bug", "hello", "masked", true);
        assertEquals("masked", attached.getString("diagnostics"));
        assertTrue(attached.getBoolean("diagnosticsConsented"));
    }
}
