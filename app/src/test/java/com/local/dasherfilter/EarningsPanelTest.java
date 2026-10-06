package com.local.dasherfilter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import java.lang.reflect.Field;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class EarningsPanelTest extends AndroidAdapterTestBase {
    private ActivityController<PanelActivity> controller;
    private PanelActivity activity;
    private EarningsPanel panel;
    private int changes;

    @Before public void open() {
        controller = Robolectric.buildActivity(PanelActivity.class).setup();
        activity = controller.get();
        activity.getSharedPreferences(Consent.PREFS, Context.MODE_PRIVATE).edit()
                .putInt(Consent.ACCEPTED_VERSION, Consent.VERSION).commit();
        panel = new EarningsPanel(activity, new Ui(activity), () -> changes++);
        panel.show();
    }
    @After public void close() {
        panel.dismiss();
        controller.pause().stop().destroy();
    }
    @Test public void openingDoesNotInventCostOrEnableAdjustment() {
        assertNull(EarningsStore.config(activity).vehicleCostCentsPerMile);
        assertFalse(EarningsStore.config(activity).enabled);
        assertEquals(0, changes);
    }
    @Test public void blankIsUnknownAndExplicitZeroIsKnown() throws Exception {
        field("cost").setText("0"); click("Save driving cost");
        assertEquals(0, EarningsStore.config(activity).vehicleCostCentsPerMile, 0);
        field("cost").setText(""); click("Save driving cost");
        assertNull(EarningsStore.config(activity).vehicleCostCentsPerMile);
    }
    @Test public void invalidAndExcessiveCostsDoNotOverwrite() throws Exception {
        field("cost").setText("0.25"); click("Save driving cost");
        for (String value : new String[]{"NaN", "Infinity", "-1", "10.01", "wrong"}) {
            field("cost").setText(value); click("Save driving cost");
            assertEquals(25, EarningsStore.config(activity).vehicleCostCentsPerMile, 0);
        }
    }
    @Test public void calculatorRequiresAllInputsAndDoesNotSave() throws Exception {
        field("fuel").setText("4"); field("mpg").setText("20");
        click("Calculate cost from my entries");
        assertEquals("", field("cost").getText().toString());
        field("wear").setText("0.10"); click("Calculate cost from my entries");
        assertEquals(.30, Double.parseDouble(field("cost").getText().toString()), .000001);
        assertNull(EarningsStore.config(activity).vehicleCostCentsPerMile);
        click("Save driving cost");
        assertEquals(30, EarningsStore.config(activity).vehicleCostCentsPerMile, .000001);
    }
    @Test public void rateIsExplicitAndBounded() throws Exception {
        field("ar").setText("101"); click("Record reported rate");
        assertTrue(EarningsStore.arHistory(activity, System.currentTimeMillis()).isEmpty());
        field("ar").setText("8"); click("Record reported rate");
        assertEquals(8, EarningsStore.arHistory(activity, System.currentTimeMillis()).get(0).percent);
    }
    @Test public void enablingRequiresKnownCostAndSeparateConfirmation() throws Exception {
        click("Enable automatic adjustment…");
        assertNull(object("confirmation"));
        field("cost").setText("0.50"); click("Save driving cost");
        click("Enable automatic adjustment…");
        assertFalse(EarningsStore.config(activity).enabled);
        AlertDialog confirmation = (AlertDialog) object("confirmation");
        assertNotNull(confirmation);
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(EarningsStore.config(activity).enabled);
    }
    @Test public void canceledConfirmationAndOldPanelButtonsCannotEnable() throws Exception {
        field("cost").setText("0.50"); click("Save driving cost");
        click("Enable automatic adjustment…");
        AlertDialog confirmation = (AlertDialog) object("confirmation");
        Button oldConfirm = confirmation.getButton(AlertDialog.BUTTON_POSITIVE);
        confirmation.dismiss();
        oldConfirm.performClick();
        assertFalse(EarningsStore.config(activity).enabled);
        Button oldSave = button(dialog().getWindow().getDecorView(), "Save driving cost");
        panel.dismiss(); panel.show(); field("cost").setText("0"); oldSave.performClick();
        assertEquals(50, EarningsStore.config(activity).vehicleCostCentsPerMile, 0);
    }
    @Test public void changedAwayAndBackInvalidatesOpenConfirmation() throws Exception {
        field("cost").setText("0.50"); click("Save driving cost");
        click("Enable automatic adjustment…");
        AlertDialog confirmation = (AlertDialog) object("confirmation");
        EarningsModel.Config original = EarningsStore.config(activity);
        EarningsStore.saveConfig(activity, new EarningsModel.Config(false, 25.0, 90, 110));
        EarningsStore.saveConfig(activity, original);
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(EarningsStore.config(activity).enabled);
    }
    @Test public void boundsSaveDoesNotApplyPercentageOrEnable() throws Exception {
        int scale = FilterStore.load(activity).minimumScalePercent;
        field("floor").setText("80"); field("ceiling").setText("120"); click("Save bounds");
        assertEquals(80, EarningsStore.config(activity).floorPercent);
        assertEquals(120, EarningsStore.config(activity).ceilingPercent);
        assertFalse(EarningsStore.config(activity).enabled);
        assertEquals(scale, FilterStore.load(activity).minimumScalePercent);
        panel.dismiss(); panel.show();
        assertEquals("80", field("floor").getText().toString());
    }
    @Test public void consentLossPreventsSaving() throws Exception {
        field("cost").setText("0");
        activity.getSharedPreferences(Consent.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        click("Save driving cost");
        assertNull(EarningsStore.config(activity).vehicleCostCentsPerMile);
    }
    @Test public void ownRateSaveDoesNotInvalidateNextSettingsSave() throws Exception {
        field("ar").setText("8"); click("Record reported rate");
        field("cost").setText("0.45"); click("Save driving cost");
        assertEquals(45, EarningsStore.config(activity).vehicleCostCentsPerMile, .000001);
        click("View reported rate history");
        AlertDialog history = (AlertDialog) object("historyDialog");
        assertNotNull(history);
        assertTrue(history.getListView().getAdapter().getItem(0).toString().startsWith("8%"));
        assertTrue(history.getListView().getAdapter().getItem(0).toString().length() > 8);
    }
    @Test public void savedBoundsMustIncludeCurrentPercentageBeforeEnabling() throws Exception {
        field("cost").setText("0.45"); click("Save driving cost");
        field("floor").setText("110"); field("ceiling").setText("120"); click("Save bounds");
        click("Enable automatic adjustment…");
        assertNull(object("confirmation"));
        assertFalse(EarningsStore.config(activity).enabled);
    }
    @Test public void failedWritesLeavePanelOpenAndDoNotChangeData() throws Exception {
        activity.failWrites = true;
        field("cost").setText("0.45"); click("Save driving cost");
        assertNull(EarningsStore.config(activity).vehicleCostCentsPerMile);
        assertTrue(dialog().isShowing());
        field("floor").setText("80"); field("ceiling").setText("120"); click("Save bounds");
        assertEquals(90, EarningsStore.config(activity).floorPercent);
        field("ar").setText("8"); click("Record reported rate");
        assertTrue(EarningsStore.arHistory(activity, System.currentTimeMillis()).isEmpty());
        assertTrue(((android.widget.TextView) object("status")).getText().toString().contains("could not"));
        activity.failWrites = false;
    }
    @Test public void failedOffAndEnableDoNotFalselyReportSuccess() throws Exception {
        field("cost").setText("0.45"); click("Save driving cost");
        click("Enable automatic adjustment…");
        AlertDialog confirmation = (AlertDialog) object("confirmation");
        activity.failWrites = true;
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(EarningsStore.config(activity).enabled);
        assertTrue(dialog().isShowing());
        activity.failWrites = false;
        panel.dismiss(); panel.show(); click("Enable automatic adjustment…");
        ((AlertDialog) object("confirmation")).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(EarningsStore.config(activity).enabled);
        activity.failWrites = true; click("Turn automatic adjustment off");
        assertTrue(EarningsStore.config(activity).enabled);
        assertTrue(((android.widget.TextView) object("status")).getText().toString().contains("could not"));
        activity.failWrites = false;
    }
    @Test public void oldConfirmationCannotUseRefreshedPanelGeneration() throws Exception {
        field("cost").setText("0.45"); click("Save driving cost");
        click("Enable automatic adjustment…");
        AlertDialog confirmation = (AlertDialog) object("confirmation");
        EarningsModel.Config same = EarningsStore.config(activity);
        EarningsStore.saveConfig(activity, same);
        java.lang.reflect.Method refresh = EarningsPanel.class.getDeclaredMethod("loadFields");
        refresh.setAccessible(true); refresh.invoke(panel);
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(EarningsStore.config(activity).enabled);
    }
    public static class PanelActivity extends Activity {
        boolean failWrites;
        @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
            android.content.SharedPreferences delegate = super.getSharedPreferences(name, mode);
            if (!name.equals(EarningsStore.SETTINGS_PREFS) && !name.equals(EarningsStore.HISTORY_PREFS)) return delegate;
            return (android.content.SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
                    getClassLoader(), new Class<?>[]{android.content.SharedPreferences.class}, (proxy, method, args) -> {
                        Object result = method.invoke(delegate, args);
                        if (!method.getName().equals("edit")) return result;
                        android.content.SharedPreferences.Editor editor = (android.content.SharedPreferences.Editor) result;
                        return java.lang.reflect.Proxy.newProxyInstance(getClassLoader(),
                                new Class<?>[]{android.content.SharedPreferences.Editor.class}, (ep, em, ea) -> {
                                    if (failWrites && em.getName().equals("commit")) return false;
                                    if (failWrites && em.getName().equals("apply")) return null;
                                    Object out = em.invoke(editor, ea);
                                    return out instanceof android.content.SharedPreferences.Editor ? ep : out;
                                });
                    });
        }
    }
    private Object object(String name) throws Exception {
        Field f = EarningsPanel.class.getDeclaredField(name); f.setAccessible(true); return f.get(panel);
    }
    private EditText field(String name) throws Exception { return (EditText) object(name); }
    private AlertDialog dialog() throws Exception { return (AlertDialog) object("dialog"); }
    private void click(String label) throws Exception {
        Button b = button(dialog().getWindow().getDecorView(), label); assertNotNull(label, b); b.performClick();
    }
    private static Button button(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = button(((ViewGroup) view).getChildAt(i), label); if (found != null) return found;
        }
        return null;
    }
}
