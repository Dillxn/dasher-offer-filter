package com.local.dasherfilter;

import static org.junit.Assert.*;
import android.app.Application;
import android.os.Looper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class DispatchStudyTransportTest {
    private Application app;
    private FakeFeedbackTransport service;
    @Before public void setup() {
        app = RuntimeEnvironment.getApplication(); service = FakeFeedbackTransport.installed();
        DispatchStudyStore.resetProcessForTest(); DispatchStudyStore.confirm(app, true); settle();
    }
    @After public void cleanup() {
        service.down = false; service.during = null;
        DispatchStudyStore.confirm(app, false); settle(); FakeFeedbackTransport.assertHonored();
        FeedbackOutbox.clock = System::currentTimeMillis;
    }
    private void settle() {
        DispatchStudyStore.flush(); Feedback.flush(); FeedbackOutbox.flush();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    static DispatchStudy.Row row() {
        DispatchStudy m = new DispatchStudy(); long wall = System.currentTimeMillis();
        m.screen(0, wall, 1, true, DasherScene.WAITING, null, false, false, DispatchStudyTest.rules(1000), false);
        m.screen(5000, wall + 5000, 1, true, DasherScene.OFFER, DispatchStudyTest.offer(1200), false, true,
                DispatchStudyTest.rules(1000), false);
        return m.rows.get(0);
    }
    private String section() { return DispatchStudyPayload.section(Collections.singletonList(row()), DispatchStudyStore.epoch(app), 0); }
    private JSONObject submission(String... parts) {
        return FeedbackOutbox.item(Feedback.newToken(), Feedback.Kind.FEEDBACK, "general", false,
                FeedbackOutbox.TEXT, "synthetic test note", true, Arrays.asList(parts));
    }
    @Test public void existingProtocolCarriesTheStrictSectionWithoutNewWireKeys() throws Exception {
        String section = section(); FeedbackOutbox.submit(app, submission("ordinary diagnostics\n" + section)); settle();
        assertEquals(1, service.requests().size()); JSONObject sent = service.requests().get(0);
        assertTrue(sent.getBoolean("diagnosticsConsented"));
        assertTrue(sent.getString("diagnostics").contains(DispatchStudyPayload.PREFIX));
        assertFalse(sent.has("study")); assertFalse(sent.has("deviceId"));
        for (String line : sent.getString("diagnostics").split("\n")) if (line.startsWith(DispatchStudyPayload.PREFIX))
            assertEquals(5000, DispatchStudy.Row.parse(new JSONObject(line.substring(DispatchStudyPayload.PREFIX.length()))).observedMs);
    }
    @Test public void automaticSummaryPathStillRequiresItsOwnSeparateOptIn() {
        assertFalse(Feedback.afterDashOn(app));
        assertFalse(FeedbackOutbox.submitAutomatic(app, FeedbackOutbox.automatic(Feedback.newToken(),
                Collections.singletonList(section()), Feedback.automaticEpoch(app))));
        settle(); assertTrue(service.requests().isEmpty());
    }
    @Test public void withdrawalRemovesQueuedStudyButPreservesUserMessageAndOtherDiagnostics() throws Exception {
        service.down = true; FeedbackOutbox.submit(app, submission("other diagnostics\n" + section())); settle();
        DispatchStudyStore.confirm(app, false); settle();
        File[] files = new File(app.getFilesDir(), FeedbackOutbox.DIR).listFiles(); assertNotNull(files);
        assertEquals(1, files.length); String queued = new String(Files.readAllBytes(files[0].toPath()), StandardCharsets.UTF_8);
        assertFalse(queued.contains(DispatchStudyPayload.PREFIX));
        assertTrue(queued.contains("synthetic test note")); assertTrue(queued.contains("other diagnostics"));
        DispatchStudyStore.confirm(app, true); settle();
        assertFalse(DispatchStudyPayload.current(app, sectionWithEpoch(1)).contains(DispatchStudyPayload.PREFIX));
    }
    private String sectionWithEpoch(long epoch) { return DispatchStudyPayload.section(Collections.singletonList(row()), epoch, 0); }
    @Test public void stalePreparedPreviewCannotResurrectDataAfterOffOnOrClear() {
        for (boolean clear : new boolean[] {false, true}) {
            String prepared = section();
            if (clear) DispatchStudyStore.clear(app);
            else { DispatchStudyStore.confirm(app, false); DispatchStudyStore.confirm(app, true); }
            FeedbackOutbox.submit(app, submission("ordinary\n" + prepared)); settle();
            JSONObject sent = service.requests().get(service.requests().size() - 1);
            assertFalse(sent.optString("diagnostics").contains(DispatchStudyPayload.PREFIX));
        }
    }
    @Test public void withdrawalDuringOnePartPreventsStudyInEveryLaterPart() {
        String data = section(); service.during = () -> { service.during = null; DispatchStudyStore.confirm(app, false); };
        FeedbackOutbox.submit(app, submission("first\n" + data, "second\n" + data)); settle();
        assertEquals(2, service.requests().size());
        assertTrue(service.requests().get(0).optString("diagnostics").contains(DispatchStudyPayload.PREFIX));
        assertFalse(service.requests().get(1).optString("diagnostics").contains(DispatchStudyPayload.PREFIX));
    }
    @Test public void withdrawalBeforeSendKeepsAnEmptyStudyTailAndTheOriginalMultipartIdentity() throws Exception {
        service.down = true;
        JSONObject queued = submission("ordinary diagnostics", section());
        FeedbackOutbox.submit(app, queued); settle();
        DispatchStudyStore.confirm(app, false); settle();
        JSONObject disk = queuedItem();
        assertEquals(queued.getString("token"), disk.getString("token"));
        assertEquals(2, disk.getJSONArray("parts").length());
        assertFalse(disk.getJSONArray("parts").getString(1).trim().isEmpty());
        assertFalse(disk.toString().contains(DispatchStudyPayload.PREFIX));
        retry();
        assertComplete(queued.getString("token"), 2);
        assertEquals("synthetic test note", service.stored(queued.getString("token"), 0).getString("message"));
        assertEquals("ordinary diagnostics", Feedback.unframed(service.stored(queued.getString("token"), 0).getString("diagnostics")));
    }
    @Test public void withdrawalInFlightCompletesTheStudyOnlyTailWithoutRecallingTheSentPart() throws Exception {
        JSONObject queued = submission("ordinary\n" + section(), section());
        service.during = () -> { service.during = null; DispatchStudyStore.confirm(app, false); };
        FeedbackOutbox.submit(app, queued); settle();
        assertComplete(queued.getString("token"), 2);
        assertTrue(service.stored(queued.getString("token"), 0).getString("diagnostics").contains(DispatchStudyPayload.PREFIX));
        assertFalse(service.stored(queued.getString("token"), 1).getString("diagnostics").contains(DispatchStudyPayload.PREFIX));
    }
    @Test public void acceptedFirstPartIsNotResentAfterWithdrawalRestartAndReconsent() throws Exception {
        service.answer(201, "{\"reference\":\"abc12345\"}").answer(503, "");
        JSONObject queued = submission("ordinary", section());
        FeedbackOutbox.submit(app, queued); settle();
        assertNotNull(service.stored(queued.getString("token"), 0));
        assertNull(service.stored(queued.getString("token"), 1));
        DispatchStudyStore.confirm(app, false); settle();
        assertEquals(1, queuedItem().getInt("sent"));
        FeedbackOutbox.forgetCache(); DispatchStudyStore.resetProcessForTest();
        DispatchStudyStore.confirm(app, true); settle();
        int before = service.requests().size();
        retry();
        assertEquals(before + 1, service.requests().size());
        assertEquals(1, service.requests().get(before).getInt("partIndex"));
        assertComplete(queued.getString("token"), 2);
        assertFalse(service.stored(queued.getString("token"), 1).getString("diagnostics").contains(DispatchStudyPayload.PREFIX));
    }
    @Test public void staleStudyOnlyPreparedFeedbackStillSendsTheUsersMessageAfterClear() throws Exception {
        JSONObject prepared = submission(section());
        DispatchStudyStore.clear(app); settle();
        FeedbackOutbox.submit(app, prepared); settle();
        assertComplete(prepared.getString("token"), 1);
        assertEquals("synthetic test note", service.stored(prepared.getString("token"), 0).getString("message"));
        assertFalse(service.stored(prepared.getString("token"), 0).getString("diagnostics").contains(DispatchStudyPayload.PREFIX));
        assertTrue(DispatchStudyStore.enabled(app));
    }
    @Test public void automaticReportKeepsItsSeparateConsentAndMultipartIdentityOnStudyWithdrawal() throws Exception {
        Feedback.setAfterDash(app, true); settle();
        JSONObject prepared = FeedbackOutbox.automatic(Feedback.newToken(),
                Arrays.asList("ordinary summary", section()), Feedback.automaticEpoch(app));
        DispatchStudyStore.confirm(app, false); settle();
        assertTrue(FeedbackOutbox.submitAutomatic(app, prepared)); settle();
        assertComplete(prepared.getString("token"), 2);
        assertTrue(Feedback.afterDashOn(app));
        for (int i = 0; i < 2; i++) {
            JSONObject part = service.stored(prepared.getString("token"), i);
            assertEquals("diagnostics", part.getString("kind"));
            assertTrue(part.getBoolean("diagnosticsConsented"));
            assertFalse(part.getString("diagnostics").contains(DispatchStudyPayload.PREFIX));
        }
    }
    private JSONObject queuedItem() throws Exception {
        File[] files = new File(app.getFilesDir(), FeedbackOutbox.DIR).listFiles((d, name) -> name.endsWith(".json"));
        assertNotNull(files); assertEquals(1, files.length);
        return new JSONObject(new String(Files.readAllBytes(files[0].toPath()), StandardCharsets.UTF_8));
    }
    private void retry() {
        service.down = false;
        long later = System.currentTimeMillis() + 24 * 60 * 60_000L;
        FeedbackOutbox.clock = () -> later;
        FeedbackOutbox.drain(app, () -> false); settle();
    }
    private void assertComplete(String token, int count) throws Exception {
        assertEquals(0, FeedbackOutbox.pending(app));
        for (int i = 0; i < count; i++) {
            JSONObject part = service.stored(token, i);
            assertNotNull(part);
            assertEquals(token, part.getString("reportToken"));
            assertEquals(i, part.getInt("partIndex")); assertEquals(count, part.getInt("partCount"));
            assertFalse(Feedback.unframed(part.getString("diagnostics")).trim().isEmpty());
        }
    }
    @Test public void malformedFieldsAndPrivateTextNeverSurviveCanonicalRemasking() throws Exception {
        for (String field : Arrays.asList("AR", "merchant", "address", "flatCents", "plan", "account")) {
            JSONObject bad = row().json(DispatchStudyStore.epoch(app)).put(field, "PRIVATE");
            String remasked = FeedbackOutbox.remask(FeedbackOutbox.TEXT, true, "base\n" + DispatchStudyPayload.PREFIX + bad + "\n");
            assertFalse(remasked.contains("PRIVATE")); assertFalse(remasked.contains(DispatchStudyPayload.PREFIX));
        }
    }
    @Test public void canonicalStudyPreviewAndRepeatedRemaskingPreserveExactlyTheSameNumericBytes() {
        String original = "ordinary report\n" + section();
        String once = FeedbackOutbox.remask(FeedbackOutbox.TEXT, true, original);
        assertEquals(original, once);
        assertEquals(once, FeedbackOutbox.remask(FeedbackOutbox.TEXT, true, once));
        assertEquals(once, DispatchStudyPayload.current(app, once));
        Feedback.Prepared preview = new Feedback.Prepared(Feedback.newToken(), Feedback.Kind.FEEDBACK,
                Collections.singletonList(once), FeedbackOutbox.TEXT);
        assertTrue(preview.preview("general", "synthetic", "0.5.8", 88).contains(section()));
    }
    @Test public void boundedSectionChunksRoundTripThroughExistingPayloadAndRedactionLimits() throws Exception {
        List<DispatchStudy.Row> rows = new ArrayList<>(); for (int i = 0; i < 32; i++) rows.add(row());
        String section = DispatchStudyPayload.section(rows, DispatchStudyStore.epoch(app), 3);
        assertTrue(section.length() <= DispatchStudyPayload.MAX_CHARS); assertTrue(section.contains("\"omittedRows\":25"));
        String base = "safe diagnostics line\n".repeat(2400);
        List<String> parts = Feedback.chunks(base + section, Feedback.MAX_PART_CHARS, Feedback.MAX_PART_BYTES);
        assertTrue(parts.size() <= Feedback.MAX_PARTS);
        int count = 0;
        for (int i = 0; i < parts.size(); i++) {
            String safe = FeedbackOutbox.remask(FeedbackOutbox.TEXT, true, parts.get(i));
            for (String line : safe.split("\n")) if (line.startsWith(DispatchStudyPayload.PREFIX)) {
                DispatchStudy.Row.parse(new JSONObject(line.substring(DispatchStudyPayload.PREFIX.length()))); count++;
            }
            byte[] bytes = Feedback.bytes(Feedback.request(Feedback.Kind.DIAGNOSTICS, "general", "", true,
                    Feedback.newToken(), i, parts.size(), safe, "0.5.8", 88));
            assertTrue(bytes.length < Feedback.MAX_BODY_BYTES);
        }
        assertEquals(10, count);
    }
}
