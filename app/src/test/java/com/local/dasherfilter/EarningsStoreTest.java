package com.local.dasherfilter;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.provider.Settings;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35, 36})
public class EarningsStoreTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final FilterSettings RULES = new FilterSettings(true, 1000, 0, 0, 0, 0);
    private static final EarningsModel.Config CONFIG = new EarningsModel.Config(true, 50.0, 90, 110);
    private Context context;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(EarningsStore.SETTINGS_PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        history().edit().clear().commit();
        EarningsStore.forgetClockCache();
        context.getSharedPreferences(Consent.PREFS, Context.MODE_PRIVATE).edit()
                .putInt(Consent.ACCEPTED_VERSION, Consent.VERSION).commit();
    }

    private SharedPreferences history() { return context.getSharedPreferences(EarningsStore.HISTORY_PREFS, Context.MODE_PRIVATE); }
    private EarningsModel.Recommendation ready() {
        EarningsModel.Recommendation result = EarningsStore.recommendation(context, EarningsModelTest.evidence(NOW), RULES, NOW);
        assertTrue(result.canAdjust());
        return result;
    }

    @Test public void absentAndUnknownSchemaMigrateSafelyToDisabledUnsetDefaults() {
        assertFalse(EarningsStore.config(context).enabled);
        assertNull(EarningsStore.config(context).vehicleCostCentsPerMile);
        for (String raw : new String[] {"oops", "{}", "{\"version\":0,\"enabled\":true}",
                "{\"version\":2,\"enabled\":true,\"costCentsPerMile\":50,\"floorPercent\":90,\"ceilingPercent\":110}"}) {
            EarningsModel.Config config = EarningsStore.decodeConfig(raw);
            assertFalse(config.enabled);
            assertNull(config.vehicleCostCentsPerMile);
        }
    }

    @Test public void costUnsetAndExplicitZeroRoundTripWithNoGuessedDefault() {
        assertTrue(EarningsStore.saveConfig(context, EarningsModel.Config.defaults()));
        assertNull(EarningsStore.config(context).vehicleCostCentsPerMile);
        EarningsModel.Config zero = new EarningsModel.Config(true, 0.0, 85, 115);
        assertTrue(EarningsStore.saveConfig(context, zero));
        assertEquals(zero.key(), EarningsStore.config(context).key());
        assertEquals(0, EarningsStore.config(context).vehicleCostCentsPerMile, 0);
        assertEquals(CONFIG.key(), EarningsStore.decodeConfig(EarningsStore.encodeConfig(CONFIG)).key());
    }

    @Test public void malformedNonfiniteFractionalAndOutOfRangeSettingsFailClosed() {
        String valid = "{\"version\":1,\"enabled\":true,\"costCentsPerMile\":COST,\"floorPercent\":90,\"ceilingPercent\":110}";
        for (String cost : new String[] {"-1", "1000.01", "\"NaN\"", "\"Infinity\"", "1e999", "\"50\"", "true"}) {
            assertFalse(EarningsStore.decodeConfig(valid.replace("COST", cost)).enabled);
        }
        assertFalse(EarningsStore.decodeConfig(valid.replace("COST", "0").replace(":90", ":0")).enabled);
        assertFalse(EarningsStore.decodeConfig(valid.replace("COST", "0").replace(":110", ":201")).enabled);
        assertFalse(EarningsStore.decodeConfig(valid.replace("COST", "0").replace(":90", ":90.5")).enabled);
        assertFalse(EarningsStore.decodeConfig(valid.replace("COST", "0").replace(":true", ":\"true\"")).enabled);
        context.getSharedPreferences(EarningsStore.SETTINGS_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(EarningsStore.CONFIG, true).commit();
        assertFalse(EarningsStore.config(context).enabled);
    }

    @Test public void manualArValidatesDatesPercentagesAndReplacesSameDate() {
        assertTrue(EarningsStore.recordAr(context, 0, NOW - 1000, NOW));
        assertTrue(EarningsStore.recordAr(context, 100, NOW, NOW));
        assertEquals(2, EarningsStore.arHistory(context, NOW).size());
        assertTrue(EarningsStore.recordAr(context, 51, NOW, NOW));
        assertEquals(2, EarningsStore.arHistory(context, NOW).size());
        assertEquals(51, EarningsStore.arHistory(context, NOW).get(1).percent);
        assertFalse(EarningsStore.recordAr(context, -1, NOW, NOW));
        assertFalse(EarningsStore.recordAr(context, 101, NOW, NOW));
        assertFalse(EarningsStore.recordAr(context, 1, NOW + 1, NOW));
        assertFalse(EarningsStore.recordAr(context, 1, NOW - EarningsModel.AR_RETAIN_MS, NOW));
        assertFalse(EarningsStore.recordAr(context, 1, -1, NOW));
    }

    @Test public void historyPrunesAtThirtyDaysAndNeverStoresMoreThanTwoHundred() {
        for (int i = 0; i < 205; i++) assertTrue(EarningsStore.recordAr(context, i % 101, NOW - 1000 + i, NOW));
        assertEquals(200, EarningsStore.arHistory(context, NOW).size());
        assertEquals(200, EarningsStore.decodeAr(history().getString(EarningsStore.AR_HISTORY, null)).size());
        assertTrue(EarningsStore.arHistory(context, NOW + EarningsModel.AR_RETAIN_MS).isEmpty());
        assertTrue(EarningsStore.decodeAr(history().getString(EarningsStore.AR_HISTORY, null)).isEmpty());
    }

    @Test public void corruptArCannotCreateGuessedReportsOrNumericCoercions() {
        String raw = "{\"version\":1,\"snapshots\":[{\"percent\":51,\"at\":1234},"
                + "{\"percent\":101,\"at\":1235},{\"percent\":50.5,\"at\":1236},"
                + "{\"percent\":\"50\",\"at\":1237},{\"percent\":50,\"at\":-1}]}";
        List<EarningsModel.Snapshot> decoded = EarningsStore.decodeAr(raw);
        assertEquals(1, decoded.size());
        assertEquals(51, decoded.get(0).percent);
        assertTrue(EarningsStore.decodeAr("not json").isEmpty());
        assertTrue(EarningsStore.decodeAr(raw.replace("\"version\":1", "\"version\":2")).isEmpty());
        String encoded = EarningsStore.encodeAr(decoded);
        assertFalse(encoded.contains("merchant"));
        assertFalse(encoded.contains("location"));
        assertFalse(encoded.contains("label"));
        assertEquals(51, EarningsStore.decodeAr(encoded).get(0).percent);
    }

    @Test public void applyPersistsOneMarkerWithExactSettingsIdentityAndBlocksReplay() {
        assertTrue(EarningsStore.saveConfig(context, CONFIG));
        EarningsModel.Recommendation recommendation = ready();
        AtomicInteger calls = new AtomicInteger();
        assertTrue(EarningsStore.applyIfCurrent(context, recommendation, NOW, () -> { calls.incrementAndGet(); return true; }));
        assertEquals(1, calls.get());
        EarningsModel.Adjustment marker = EarningsStore.decodeAdjustment(history().getString(EarningsStore.ADJUSTMENT, null));
        assertEquals(recommendation.suggestedSettingsKey, marker.settingsKey);
        assertEquals(CONFIG.key(), marker.configKey);
        assertEquals(NOW, marker.at);
        assertFalse(EarningsStore.applyIfCurrent(context, recommendation, NOW, () -> { calls.incrementAndGet(); return true; }));
        assertEquals(1, calls.get());
        assertEquals(EarningsModel.Status.COOLDOWN, EarningsStore.recommendation(context,
                EarningsModelTest.evidence(NOW), RULES, NOW + 1).status);
    }

    @Test public void failedConditionalSaveConsumesCooldownInsteadOfReusingAuthority() {
        assertTrue(EarningsStore.saveConfig(context, CONFIG));
        assertFalse(EarningsStore.applyIfCurrent(context, ready(), NOW, () -> false));
        assertTrue(history().contains(EarningsStore.ADJUSTMENT));
        assertEquals(EarningsModel.Status.COOLDOWN, EarningsStore.recommendation(context,
                EarningsModelTest.evidence(NOW), RULES, NOW + 1).status);
    }

    @Test public void failedMarkerCommitNeverRunsConditionalSave() {
        assertTrue(EarningsStore.saveConfig(context, CONFIG));
        EarningsModel.Recommendation recommendation = ready();
        SharedPreferences real = history();
        SharedPreferences rejected = (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("edit")) return method.invoke(real, args);
                    SharedPreferences.Editor editor = real.edit();
                    boolean[] adjustmentWrite = {false};
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                            (editorProxy, editorMethod, editorArgs) -> {
                                if (editorMethod.getName().equals("putString") && EarningsStore.ADJUSTMENT.equals(editorArgs[0])) {
                                    adjustmentWrite[0] = true;
                                }
                                if (editorMethod.getName().equals("commit") && adjustmentWrite[0]) return false;
                                Object result = editorMethod.invoke(editor, editorArgs);
                                return result instanceof SharedPreferences.Editor ? editorProxy : result;
                            });
                });
        Context failing = new ContextWrapper(context) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return EarningsStore.HISTORY_PREFS.equals(name) ? rejected : super.getSharedPreferences(name, mode);
            }
        };
        AtomicInteger calls = new AtomicInteger();
        assertFalse(EarningsStore.applyIfCurrent(failing, recommendation, NOW, () -> { calls.incrementAndGet(); return true; }));
        assertEquals(0, calls.get());
        assertFalse(history().contains(EarningsStore.ADJUSTMENT));
    }

    @Test public void forwardWallClockJumpCannotShortenRealCooldownAndBackwardClockFailsClosed() {
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 7);
        assertTrue(EarningsStore.saveConfig(context, CONFIG));
        assertTrue(EarningsStore.markAdjusted(context, ready(), NOW));
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMinutes(10));
        long wallJump = NOW + 2 * 60 * 60_000L;
        assertEquals(EarningsModel.Status.COOLDOWN, EarningsStore.recommendation(context,
                EarningsModelTest.evidence(wallJump), RULES, wallJump).status);
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMinutes(5));
        assertTrue(EarningsStore.recommendation(context, EarningsModelTest.evidence(wallJump), RULES, wallJump).canAdjust());
        assertEquals(EarningsModel.Status.COOLDOWN, EarningsStore.recommendation(context,
                EarningsModelTest.evidence(NOW - 1), RULES, NOW - 1).status);
    }

    @Test public void sameBootRestartUsesElapsedRecordAndUnknownOrChangedBootRequiresFreshCooldown() {
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 7);
        assertTrue(EarningsStore.saveConfig(context, CONFIG));
        assertTrue(EarningsStore.markAdjusted(context, ready(), NOW));
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMinutes(10));
        EarningsStore.forgetClockCache();
        long later = NOW + 2 * 60 * 60_000L;
        assertEquals(EarningsModel.Status.COOLDOWN, EarningsStore.recommendation(context,
                EarningsModelTest.evidence(later), RULES, later).status);
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMinutes(5));
        assertTrue(EarningsStore.recommendation(context, EarningsModelTest.evidence(later), RULES, later).canAdjust());
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 8);
        assertEquals(EarningsModel.Status.COOLDOWN, EarningsStore.recommendation(context,
                EarningsModelTest.evidence(later), RULES, later).status);
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMinutes(15));
        assertTrue(EarningsStore.recommendation(context, EarningsModelTest.evidence(later), RULES, later).canAdjust());
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        EarningsStore.forgetClockCache();
        assertEquals(EarningsModel.Status.COOLDOWN, EarningsStore.recommendation(context,
                EarningsModelTest.evidence(later), RULES, later).status);
    }

    @Test public void atomicConfigSnapshotAndReentrantMutationInvalidateApplyingGuard() {
        assertTrue(EarningsStore.saveConfig(context, CONFIG));
        EarningsStore.ConfigSnapshot before = EarningsStore.configSnapshot(context);
        EarningsModel.Recommendation recommendation = ready();
        assertEquals(before.generation, recommendation.generation);
        assertFalse(EarningsStore.applyIfCurrent(context, recommendation, NOW, () -> {
            assertTrue(EarningsStore.isApplyingCurrent(context, recommendation));
            assertTrue(EarningsStore.saveConfig(context, CONFIG));
            assertFalse(EarningsStore.isApplyingCurrent(context, recommendation));
            return false;
        }));
        assertNotEquals(before.generation, EarningsStore.configSnapshot(context).generation);
    }

    @Test public void configurationArHistoryClearAndTimeInvalidatePendingRecommendation() {
        assertTrue(EarningsStore.saveConfig(context, CONFIG));
        EarningsModel.Recommendation old = ready();
        assertTrue(EarningsStore.saveConfig(context, new EarningsModel.Config(false, 50.0, 90, 110)));
        assertFalse(EarningsStore.markAdjusted(context, old, NOW));
        assertTrue(EarningsStore.saveConfig(context, CONFIG));
        old = ready();
        assertTrue(EarningsStore.recordAr(context, 51, NOW));
        assertFalse(EarningsStore.markAdjusted(context, old, NOW));
        old = ready();
        EarningsStore.clearHistory(context);
        assertFalse(EarningsStore.markAdjusted(context, old, NOW));
        old = ready();
        assertFalse(EarningsStore.markAdjusted(context, old, NOW - 1));
        assertFalse(EarningsStore.markAdjusted(context, old, NOW + EarningsStore.RECOMMENDATION_VALID_MS + 1));
    }

    @Test public void clearHistoryKeepsConfiguredPreferencesAndRemovesArAndAdjustment() {
        assertTrue(EarningsStore.saveConfig(context, CONFIG));
        assertTrue(EarningsStore.recordAr(context, 50, NOW));
        assertTrue(EarningsStore.markAdjusted(context, ready(), NOW));
        EarningsStore.clearHistory(context);
        assertEquals(CONFIG.key(), EarningsStore.config(context).key());
        assertTrue(EarningsStore.arHistory(context, NOW).isEmpty());
        assertFalse(history().contains(EarningsStore.ADJUSTMENT));
        assertTrue(ready().canAdjust());
    }

    @Test public void corruptAdjustmentFailsClosedUntilExplicitHistoryClear() {
        assertTrue(EarningsStore.saveConfig(context, CONFIG));
        history().edit().putString(EarningsStore.ADJUSTMENT, "broken").commit();
        assertEquals(EarningsModel.Status.COOLDOWN, EarningsStore.recommendation(context,
                EarningsModelTest.evidence(NOW), RULES, NOW).status);
        EarningsStore.clearHistory(context);
        assertTrue(ready().canAdjust());
    }

    @Test public void missingConsentStoresNoUserInputsAndAppliesNoAdjustment() {
        assertTrue(EarningsStore.saveConfig(context, CONFIG));
        EarningsModel.Recommendation old = ready();
        context.getSharedPreferences(Consent.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        assertFalse(EarningsStore.saveConfig(context, EarningsModel.Config.defaults()));
        assertFalse(EarningsStore.recordAr(context, 51, NOW));
        AtomicInteger calls = new AtomicInteger();
        assertFalse(EarningsStore.applyIfCurrent(context, old, NOW, () -> { calls.incrementAndGet(); return true; }));
        assertEquals(0, calls.get());
    }

    @Test public void manualEditDisablesOnlyAutoAndInvalidatesOldCandidate() {
        assertTrue(EarningsStore.saveConfig(context, CONFIG));
        assertTrue(EarningsStore.recordAr(context, 50, NOW));
        EarningsModel.Recommendation old = ready();
        assertTrue(EarningsStore.disableForManualChange(context));
        EarningsModel.Config saved = EarningsStore.config(context);
        assertFalse(saved.enabled);
        assertEquals(CONFIG.vehicleCostCentsPerMile, saved.vehicleCostCentsPerMile);
        assertEquals(CONFIG.floorPercent, saved.floorPercent);
        assertEquals(CONFIG.ceilingPercent, saved.ceilingPercent);
        assertEquals(1, EarningsStore.arHistory(context, NOW).size());
        assertFalse(EarningsStore.markAdjusted(context, old, NOW));
    }

    @Test public void conditionalConfigSaveRejectsAbaHistoryClearAndOtherSaveButAcceptsCurrent() {
        EarningsStore.ConfigSnapshot original = EarningsStore.configSnapshot(context);
        assertTrue(EarningsStore.saveConfigIfCurrent(context, CONFIG, original));
        assertEquals(CONFIG.key(), EarningsStore.config(context).key());
        assertFalse(EarningsStore.saveConfigIfCurrent(context, EarningsModel.Config.defaults(), original));
        EarningsStore.ConfigSnapshot beforeAba = EarningsStore.configSnapshot(context);
        assertTrue(EarningsStore.saveConfig(context, EarningsModel.Config.defaults()));
        assertTrue(EarningsStore.saveConfig(context, CONFIG));
        assertEquals(beforeAba.config.key(), EarningsStore.config(context).key());
        assertFalse(EarningsStore.saveConfigIfCurrent(context, EarningsModel.Config.defaults(), beforeAba));
        EarningsStore.ConfigSnapshot beforeClear = EarningsStore.configSnapshot(context);
        EarningsStore.clearHistory(context);
        assertFalse(EarningsStore.saveConfigIfCurrent(context, EarningsModel.Config.defaults(), beforeClear));
        EarningsStore.ConfigSnapshot beforeSameValueSave = EarningsStore.configSnapshot(context);
        assertTrue(EarningsStore.saveConfig(context, CONFIG));
        assertFalse(EarningsStore.saveConfigIfCurrent(context, EarningsModel.Config.defaults(), beforeSameValueSave));
        assertTrue(EarningsStore.saveConfigIfCurrent(context, EarningsModel.Config.defaults(),
                EarningsStore.configSnapshot(context)));
        assertFalse(EarningsStore.config(context).enabled);
    }
}
