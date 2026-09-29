package com.local.dasherfilter;

import android.app.Application;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** D5: the on-device history store (Robolectric, API 26 and 35). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class OfferHistoryStoreTest {
    private Application app;
    @Before public void setup() { app = RuntimeEnvironment.getApplication(); }
    private static OfferRecord record(long id, long at, int actions) {
        return new OfferRecord(id, at, OfferRecord.Source.SCREEN, 790, 7.2, 21, 2, false, OfferRule.Result.DECLINE, 2000, OfferRule.Code.FLOOR, "flat minimum", actions, null);
    }
    private File file() { return new File(app.getFilesDir(), OfferHistoryStore.FILE); }
    private String fileText() throws Exception { return new String(Files.readAllBytes(file().toPath()), StandardCharsets.UTF_8); }

    @Test public void recordsPersistAcrossProcessesAndUpdatesReplaceInPlace() throws Exception {
        OfferHistoryStore store = OfferHistoryStore.get(app);
        long now = System.currentTimeMillis(), id = store.nextId(now);
        store.upsert(record(id, now, 0));
        store.addActions(id, OfferRecord.DECLINE_REQUESTED);
        store.flush();
        int lines = fileText().split("\n").length;
        long version = store.version();
        store.addActions(id, OfferRecord.DECLINE_REQUESTED);   // identical: not written again
        store.upsert(store.get(id));
        store.flush();
        assertEquals("an identical record is never written again", lines, fileText().split("\n").length);
        assertEquals(version, store.version());
        OfferHistory replayed = OfferHistory.parse(fileText(), now);
        assertEquals(1, replayed.size()); assertTrue(replayed.get(id).has(OfferRecord.DECLINE_REQUESTED));
        assertTrue(store.version() > 0);
    }

    @Test public void loadsTheFileInTheBackgroundAndSeedsTheDeclineBudget() throws Exception {
        long now = System.currentTimeMillis();
        StringBuilder text = new StringBuilder();
        long ahead = now + 1000;   // ids from a previous process may run slightly ahead of the wall clock
        text.append(record(ahead, now - 10 * 60_000L, OfferRecord.DECLINE_REQUESTED).serialize()).append('\n');
        text.append(record(ahead + 1, now - 5 * 60_000L, OfferRecord.NOTIF_HIDDEN).serialize()).append('\n');
        text.append(record(ahead + 2, now - 2 * 60 * 60_000L, OfferRecord.DECLINE_REQUESTED).serialize()).append('\n');   // outside the hour
        text.append("garbage line\n");
        try (FileOutputStream out = new FileOutputStream(file())) { out.write(text.toString().getBytes(StandardCharsets.UTF_8)); }
        OfferHistoryStore store = OfferHistoryStore.get(app); store.flush();
        assertTrue(store.loaded()); assertEquals(3, store.size());
        assertEquals("decline requests and hides in the trailing hour survive a restart", 2, store.budget().count(now));
        assertTrue("new ids never collide with loaded ones", store.nextId(now) > ahead + 2);
        assertFalse("compacted on load: the malformed line is gone", fileText().contains("garbage"));
    }

    @Test public void boundedToOneThousandRecordsAndClearDeletesEverything() throws Exception {
        OfferHistoryStore store = OfferHistoryStore.get(app);
        long now = System.currentTimeMillis();
        for (int i = 0; i < 1100; i++) store.upsert(record(store.nextId(now), now - 1100 + i, i % 2 == 0 ? OfferRecord.DECLINE_REQUESTED : 0));
        store.flush();
        assertEquals(OfferHistory.MAX_RECORDS, store.size());
        int budgetBefore = store.budget().count(now);
        store.clear(); store.flush();
        assertEquals(0, store.size()); assertTrue(store.newestFirst().isEmpty());
        assertTrue("history file removed", !file().exists() || fileText().isEmpty());
        assertEquals("clearing history never loosens the decline limit", budgetBefore, store.budget().count(now));
        assertEquals("time,source,pay,miles,minutes,stops,add_on,verdict,required,reason_code,reason,store,outcome\n", store.csv());
    }

    @Test public void oneStorePerApplicationAndStatsAreHonest() {
        OfferHistoryStore store = OfferHistoryStore.get(app);
        assertSame(store, OfferHistoryStore.get(app.getApplicationContext()));
        long now = System.currentTimeMillis();
        store.upsert(record(store.nextId(now), now, OfferRecord.DECLINE_REQUESTED));
        HistoryStats today = store.today();
        assertEquals(1, today.declineRequests); assertEquals(1, today.screenOffers);
        assertTrue(store.csv().contains("Decline requested"));
    }
}
