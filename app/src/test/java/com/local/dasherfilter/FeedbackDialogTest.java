package com.local.dasherfilter;

import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * Send anonymous feedback, as the user meets it in Settings: one row; a dialog with six categories, a counted
 * 4,000-character box, a reminder to leave out customer, payment and account details, an Attach masked diagnostics
 * switch (off) whose Preview shows exactly what goes; the reference afterwards, copyable; the last references
 * listed; the words kept when a send is refused, and kept through a recreated screen. The service is a fake.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class FeedbackDialogTest extends AndroidAdapterTestBase {
    private ActivityController<MainActivity> activity;
    private View content;
    private FakeFeedbackTransport service;

    @After
    public void close() {
        if (activity != null) activity.close();
        Feedback.flush();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** The list row whose first line is {@code title}. */
    private static Button row(View view, String title) {
        if (view instanceof Button && !(view instanceof Switch)) {
            String text = ((Button) view).getText().toString();
            if (text.equals(title) || text.startsWith(title + "\n")) return (Button) view;
        }
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                Button found = row(((ViewGroup) view).getChildAt(i), title);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends View> List<T> all(View view, Class<T> type, List<T> out) {
        if (type.isInstance(view)) out.add(type.cast(view));
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) all(((ViewGroup) view).getChildAt(i), type, out);
        }
        return out;
    }

    private static AlertDialog latest() {
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private static View decor(AlertDialog dialog) {
        return dialog.getWindow().getDecorView();
    }

    /** Settings, then Send anonymous feedback: the dialog. */
    private AlertDialog open() {
        service = FakeFeedbackTransport.installed();
        if (activity == null) {
            activity = Robolectric.buildActivity(MainActivity.class).setup();
            content = activity.get().findViewById(android.R.id.content);
            iconButton(content, "Settings").performClick();
            settle();
        }
        row(content, "Send anonymous feedback").performClick();
        settle();
        AlertDialog dialog = latest();
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private static RadioButton chip(AlertDialog dialog, String label) {
        for (RadioButton chip : all(decor(dialog), RadioButton.class, new ArrayList<>())) {
            if (chip.getText().toString().equals(label)) return chip;
        }
        return null;
    }

    private static void send(AlertDialog dialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Feedback.flush();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static String said(AlertDialog dialog) {
        TextView message = dialog.findViewById(android.R.id.message);
        return message == null ? "" : message.getText().toString();
    }

    @Test
    public void oneRowOpensACalmDialogWithSixCategoriesACountedBoxAndDiagnosticsOff() {
        AlertDialog dialog = open();
        assertEquals("No account, name or email. It is sent only when you tap Send.", said(dialog));
        List<String> labels = new ArrayList<>();
        for (RadioButton chip : all(decor(dialog), RadioButton.class, new ArrayList<>())) {
            labels.add(chip.getText().toString());
            assertTrue("a full-size touch target", chip.getMinHeight() >= new Ui(activity.get()).dp(48));
        }
        assertEquals(Arrays.asList("General", "Bug", "Idea", "Usability", "Privacy", "Update"), labels);
        assertTrue(chip(dialog, "General").isChecked());
        assertNotNull(findText(decor(dialog), "Don't include customer, payment or account details."));
        Switch attach = find(decor(dialog), Switch.class);
        assertEquals("Attach masked diagnostics", attach.getText().toString());
        assertFalse("diagnostics are a separate choice, off", attach.isChecked());
        assertNotNull(shownButton(decor(dialog), "Preview what is sent"));

        EditText words = find(decor(dialog), EditText.class);
        assertNotNull(findText(decor(dialog), "0 / 4,000"));
        words.setText("The map is too small.");
        assertNotNull(findText(decor(dialog), "21 / 4,000"));
        words.setText("a".repeat(4_100));
        assertEquals("the service's limit, enforced as typed", 4_000, words.length());
        assertNotNull(findText(decor(dialog), "4,000 / 4,000"));
        assertEquals("nothing leaves before Send", 0, service.count());
    }

    @Test
    public void sendSendsOnlyTheWordsAndShowsACopyableReferenceKeptWithTheLastTen() throws Exception {
        AlertDialog dialog = open();
        chip(dialog, "Idea").performClick();
        find(decor(dialog), EditText.class).setText("  Show the pay per mile bigger.  ");
        send(dialog);

        assertEquals(1, service.count());
        JSONObject request = service.requests().get(0);
        assertEquals("feedback", request.getString("kind"));
        assertEquals("feature", request.getString("category"));
        assertEquals("Show the pay per mile bigger.", request.getString("message"));
        assertFalse("no diagnostics unless attached", request.has("diagnostics"));
        assertFalse(request.getBoolean("diagnosticsConsented"));
        String reference = request.getString("reportToken").substring(0, 8);

        assertFalse("the feedback dialog closes once it is accepted", dialog.isShowing());
        AlertDialog sent = latest();
        assertNotSame(dialog, sent);
        TextView shown = findText(decor(sent), "Reference: " + reference);
        assertNotNull(shown);
        assertTrue("selectable", shown.isTextSelectable());
        assertNotNull(findText(decor(sent), "Keep it if you may need to refer to this submission."));
        sent.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        settle();
        ClipboardManager clipboard = app.getSystemService(ClipboardManager.class);
        assertEquals(reference, clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertEquals(AppName.NAME + " reference", clipboard.getPrimaryClip().getDescription().getLabel().toString());
        settle();

        assertTrue("Settings' one line says so", row(content, "Send anonymous feedback").getText().toString()
                .matches("Send anonymous feedback\nSent \\d{1,2} \\w{3} \\d{2}:\\d{2} · " + reference));
        AlertDialog again = open();
        assertEquals("the draft went with the accepted submission", "",
                find(decor(again), EditText.class).getText().toString());
        assertTrue(chip(again, "General").isChecked());
        assertNotNull(findText(decor(again), "Sent before"));
        TextView list = findTextContaining(decor(again), reference + " · feedback");
        assertNotNull(list);
        assertTrue(list.isTextSelectable());
    }

    @Test
    public void thePreviewIsExactlyWhatIsSent() throws Exception {
        DiagnosticLog.log(app, "accessibility", "a line of the log for the preview");
        AlertDialog dialog = open();
        chip(dialog, "Bug").performClick();
        find(decor(dialog), Switch.class).setChecked(true);
        find(decor(dialog), EditText.class).setText("It declined an offer it should have kept.");
        shownButton(decor(dialog), "Preview what is sent").performClick();
        Feedback.flush();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        AlertDialog preview = latest();
        assertNotSame(dialog, preview);
        TextView body = all(decor(preview), TextView.class, new ArrayList<>()).stream()
                .filter(text -> text.getText().toString().startsWith("Kind: feedback")).findFirst().orElse(null);
        assertNotNull(body);
        assertTrue(body.isTextSelectable());
        String previewed = body.getText().toString();
        assertTrue(previewed, previewed.contains("Kind: feedback · category: bug"));
        assertTrue(previewed, previewed.contains("Message:\nIt declined an offer it should have kept."));
        assertTrue(previewed, previewed.contains("a line of the log for the preview"));
        assertEquals("previewing sends nothing", 0, service.count());
        preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();

        send(dialog);
        assertTrue(service.count() >= 1);
        for (JSONObject request : service.requests()) {
            assertTrue(request.getBoolean("diagnosticsConsented"));
            assertTrue("the very part previewed", previewed.contains(request.getString("diagnostics")));
            assertEquals("bug", request.getString("category"));
        }
    }

    @Test
    public void withNoConnectionItIsSavedAndSentLaterWithoutAnotherTap() throws Exception {
        AlertDialog dialog = open();
        find(decor(dialog), EditText.class).setText("Offline words.");
        FakeFeedbackTransport.installed().down = true;
        send(dialog);

        assertFalse(dialog.isShowing());
        assertEquals("Saved; it will send when you're online", said(latest()));
        assertEquals(1, new File(app.getFilesDir(), FeedbackOutbox.DIR).listFiles().length);
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        assertEquals("Send anonymous feedback\n1 waiting to send · Saved; it will send when you're online",
                row(content, "Send anonymous feedback").getText().toString());

        FakeFeedbackTransport.installed().down = false;
        FeedbackOutbox.drain(app, () -> false);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        AlertDialog sent = latest();
        assertTrue("its reference comes to the screen that is up", sent.isShowing());
        assertNotNull(findTextContaining(decor(sent), "Reference: "));
        assertEquals(0, new File(app.getFilesDir(), FeedbackOutbox.DIR).listFiles().length);
    }

    @Test
    public void aRefusalKeepsTheDialogAndEveryWord() throws Exception {
        AlertDialog dialog = open();
        EditText words = find(decor(dialog), EditText.class);
        words.setText("Words the service refuses.");
        FakeFeedbackTransport.installed().answer(400, "{\"error\":\"invalid\"}");
        send(dialog);

        assertTrue("still open", dialog.isShowing());
        assertNotNull(findText(decor(dialog), "Couldn't be accepted (too long?)"));
        assertEquals("Words the service refuses.", words.getText().toString());
        assertTrue("ready to try again", dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        assertTrue(words.isEnabled());
        assertEquals("dropped from the outbox, never retried", 0,
                new File(app.getFilesDir(), FeedbackOutbox.DIR).listFiles().length);
    }

    @Test
    public void nothingIsSentWithoutWords() {
        AlertDialog dialog = open();
        find(decor(dialog), EditText.class).setText("   \n  ");
        send(dialog);
        assertTrue(dialog.isShowing());
        assertNotNull(findText(decor(dialog), "Write something first."));
        assertEquals(0, service.count());
    }

    @Test
    public void aRecreatedScreenOpensTheDialogAgainWithTheWords() {
        AlertDialog dialog = open();
        chip(dialog, "Privacy").performClick();
        find(decor(dialog), EditText.class).setText("Half-written words.");
        activity.recreate();
        settle();
        AlertDialog restored = latest();
        assertNotSame(dialog, restored);
        assertTrue(restored.isShowing());
        assertEquals("Half-written words.", find(decor(restored), EditText.class).getText().toString());
        assertTrue(chip(restored, "Privacy").isChecked());
        assertEquals(0, service.count());
    }

    @Test
    public void aResultAfterTheScreenClosedWaitsForTheNextScreen() throws Exception {
        AlertDialog dialog = open();
        find(decor(dialog), EditText.class).setText("Sent as the screen closed.");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        activity.pause().stop().destroy();
        activity = null;
        Feedback.flush();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, service.count());
        String reference = service.requests().get(0).getString("reportToken").substring(0, 8);

        activity = Robolectric.buildActivity(MainActivity.class).setup();
        settle();
        AlertDialog sent = latest();
        assertTrue(sent.isShowing());
        assertNotNull(findText(decor(sent), "Reference: " + reference));
    }

    @Test
    public void theOptInIsOneSwitchOffByDefaultAndAsksBeforeTurningOn() {
        open().dismiss();
        settle();
        Switch afterDash = (Switch) findButton(content, "Share anonymous diagnostics after each dash");
        assertNotNull(afterDash);
        assertFalse(afterDash.isChecked());
        afterDash.setChecked(true);
        settle();
        AlertDialog ask = latest();
        assertTrue(ask.isShowing());
        assertFalse("not on until confirmed", Feedback.afterDashOn(app));
        ask.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        settle();
        assertFalse(afterDash.isChecked());
        assertFalse(Feedback.afterDashOn(app));

        afterDash.setChecked(true);
        settle();
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        assertTrue(afterDash.isChecked());
        assertTrue(Feedback.afterDashOn(app));
        afterDash.setChecked(false);
        settle();
        assertFalse("off at once", Feedback.afterDashOn(app));
        assertEquals("nothing sent by the switch itself", 0, service.count());
    }
}
