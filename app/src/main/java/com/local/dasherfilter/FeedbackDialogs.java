package com.local.dasherfilter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;

/**
 * The feedback and offer-report dialogs, and what a screen shows of a submission's fate. Owned by one screen: it
 * listens only while that screen is started, and its dialogs go with it, so no result reaches a screen that is gone.
 * Every submission is built and sent off the main thread ({@link Feedback}); the dialog stays open while it is sent,
 * keeps what was typed when it is refused, and shows the reference, copyable, once it is accepted.
 *
 * <p>Attach masked diagnostics is chosen for each submission: every new dialog opens with it off, unless that dialog
 * itself was opened with it on (the report offered after a stop); no earlier dialog's choice carries over.
 */
final class FeedbackDialogs implements Feedback.Listener {
    /** What the user was writing: kept across a recreated screen and a refused send, until it is accepted. */
    private static final class Draft {
        Feedback.Category category = Feedback.Category.GENERAL;
        String message = "";
    }

    /**
     * What Report this offer says it sends: all of what OfferReport carries, Autopilot's state and the acceptance rate
     * it counts with among it.
     */
    static final String OFFER_REPORT_SAYS = "Sends this offer's figures, decision and masked read lines, your current "
            + "rules and Autopilot's state (its bar, goal and your latest acceptance rate), the app's and Android's "
            + "versions and the minute it was decided, with your note. No account. Masking can miss details.";
    /** A page of the preview: a whole report in one view would stall the screen. */
    static final int PREVIEW_PAGE_CHARS = 12_000;
    private static Draft draft = new Draft();
    private static final String OPEN = "feedback_dialog_open";
    /** The open feedback dialog's own Attach choice, kept only through its recreated screen. */
    private static final String OPEN_ATTACH = "feedback_dialog_attach";

    private final Activity activity;
    private final Ui ui;
    private final Runnable changed;
    private AlertDialog dialog;
    /** The submission the open dialog waits for, or "" while it waits for none. */
    private String waitingFor = "";
    private TextView statusLine;
    private boolean feedbackOpen;
    /** The open feedback dialog's Attach masked diagnostics switch, or null. */
    private Switch attachSwitch;
    /** What the open dialog built ahead of Send (for its preview), or null; dropped once a send is refused. */
    private Feedback.Prepared[] preparedNow;
    /** The preview over the open dialog, so it goes with the screen. */
    private AlertDialog previewDialog;

    /** @param changed run on the main thread after any submission changed (Settings' line refreshes) */
    FeedbackDialogs(Activity activity, Ui ui, Runnable changed) {
        this.activity = activity;
        this.ui = ui;
        this.changed = changed;
    }

    // ---- The screen's life ----

    void start() {
        Feedback.listen(this);
        Feedback.Event unseen = Feedback.takeUnseen();
        if (unseen != null) changed(unseen);
    }

    void stop() {
        Feedback.unlisten(this);
    }

    void destroy() {
        Feedback.unlisten(this);
        if (previewDialog != null && previewDialog.isShowing()) previewDialog.dismiss();
        previewDialog = null;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        dialog = null;
    }

    /** A recreated screen (a resize, day and night) opens the feedback dialog again, as it was. */
    void save(Bundle state) {
        boolean open = feedbackOpen && dialog != null && dialog.isShowing() && waitingFor.isEmpty();
        state.putBoolean(OPEN, open);
        state.putBoolean(OPEN_ATTACH, open && attachSwitch != null && attachSwitch.isChecked());
    }

    void restore(Bundle state) {
        if (state != null && state.getBoolean(OPEN, false)) feedback(null, state.getBoolean(OPEN_ATTACH, false));
    }

    boolean showing() {
        return dialog != null && dialog.isShowing();
    }

    // ---- Send anonymous feedback ----

    /**
     * The feedback dialog: a category, what to say (a counter and its limit), a reminder to leave out customer,
     * payment and account details, an Attach masked diagnostics switch with a Preview of exactly what goes, and the
     * references of the last submissions.
     *
     * @param category chosen in advance (Bug, for a report after a stop), or null for the draft's
     * @param attach   attach diagnostics in advance, for this dialog only (a report after a stop); null or false: off
     */
    void feedback(Feedback.Category category, Boolean attach) {
        if (showing()) return;
        if (category != null) draft.category = category;
        LinearLayout frame = frame();
        RadioGroup chips = chips(frame);
        for (Feedback.Category one : Feedback.Category.values()) {
            RadioButton chip = chip(chips, one.label);
            chip.setTag(one);
            if (one == draft.category) chip.setChecked(true);
        }
        chips.setOnCheckedChangeListener((group, id) -> {
            View checked = group.findViewById(id);
            if (checked != null && checked.getTag() instanceof Feedback.Category) {
                draft.category = (Feedback.Category) checked.getTag();
            }
        });
        EditText message = editor(frame, "What should we know?", draft.message, typed -> draft.message = typed);
        frame.addView(small("Don't include customer, payment or account details."), Ui.matchWidth());
        // Off for every new submission; on only when this dialog itself was opened so.
        Switch diagnostics = ui.toggle(frame, "Attach masked diagnostics", attach != null && attach);
        attachSwitch = diagnostics;
        Feedback.Prepared[] prepared = new Feedback.Prepared[1];
        preparedNow = prepared;
        boolean[] preparedWith = new boolean[1];
        Button preview = ui.link("Preview what is sent", () -> {
            status("Preparing…");
            boolean with = diagnostics.isChecked();
            Feedback.prepareFeedback(activity, with, ready -> {
                if (!showing()) return;
                status("");
                if (ready == null) {
                    status("Couldn't prepare the diagnostics; try again.");
                    return;
                }
                prepared[0] = ready;
                preparedWith[0] = with;
                preview(ready.preview(draft.category.wire, message.getText().toString(), Updater.version(activity),
                        Feedback.versionCode(activity)));
            });
        });
        preview.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        frame.addView(preview, Ui.matchWidth());
        statusLine = status(frame);
        references(frame);
        feedbackOpen = true;
        dialog = OwnWindowTouches.show(new AlertDialog.Builder(activity)
                .setTitle("Send anonymous feedback")
                .setMessage("No account, name or email. It is sent only when you tap Send.")
                .setView(scroll(frame))
                .setPositiveButton("Send", null)
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(closedDialog -> closed()));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(tapped -> {
            String typed = message.getText().toString();
            if (Feedback.typed(typed).isEmpty()) {
                status("Write something first.");
                return;
            }
            boolean with = diagnostics.isChecked();
            Feedback.Prepared reuse = prepared[0] != null && preparedWith[0] == with ? prepared[0] : null;
            busy(true, message, diagnostics, preview);
            status(with && reuse == null ? "Preparing and sending…" : "Sending…");
            waitingFor = Feedback.sendFeedback(activity, draft.category, typed, with, reuse);
        });
    }

    // ---- Report this offer ----

    /**
     * One offer's report: what went wrong, an optional note, and a Preview; the dialog says what it sends, and tapping
     * Send is the user's consent to it.
     */
    void reportOffer(DecisionLog.Entry entry) {
        if (showing()) return;
        LinearLayout frame = frame();
        RadioGroup chips = chips(frame);
        OfferReport.Problem[] chosen = {OfferReport.Problem.MISREAD};
        for (OfferReport.Problem problem : OfferReport.Problem.values()) {
            RadioButton chip = chip(chips, problem.label);
            chip.setTag(problem);
            if (problem == chosen[0]) chip.setChecked(true);
        }
        chips.setOnCheckedChangeListener((group, id) -> {
            View checked = group.findViewById(id);
            if (checked != null && checked.getTag() instanceof OfferReport.Problem) {
                chosen[0] = (OfferReport.Problem) checked.getTag();
            }
        });
        EditText note = editor(frame, "What went wrong? (optional)", "", typed -> { });
        frame.addView(small("Don't include customer, payment or account details."), Ui.matchWidth());
        Feedback.Prepared[] prepared = new Feedback.Prepared[1];
        preparedNow = prepared;
        attachSwitch = null;
        OfferReport.Problem[] preparedFor = new OfferReport.Problem[1];
        Button preview = ui.link("Preview what is sent", () -> {
            status("Preparing…");
            OfferReport.Problem problem = chosen[0];
            Feedback.prepareOfferReport(activity, entry, problem, ready -> {
                if (!showing()) return;
                status("");
                if (ready == null) {
                    status("Couldn't prepare the report; try again.");
                    return;
                }
                prepared[0] = ready;
                preparedFor[0] = problem;
                preview(ready.preview(problem.category(), note.getText().toString(), Updater.version(activity),
                        Feedback.versionCode(activity)));
            });
        });
        preview.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        frame.addView(preview, Ui.matchWidth());
        statusLine = status(frame);
        feedbackOpen = false;
        dialog = OwnWindowTouches.show(new AlertDialog.Builder(activity)
                .setTitle("Report this offer")
                .setMessage(OFFER_REPORT_SAYS)
                .setView(scroll(frame))
                .setPositiveButton("Send", null)
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(closedDialog -> closed()));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(tapped -> {
            OfferReport.Problem problem = chosen[0];
            Feedback.Prepared reuse = prepared[0] != null && preparedFor[0] == problem ? prepared[0] : null;
            busy(true, note, preview);
            status("Sending…");
            waitingFor = Feedback.sendOfferReport(activity, entry, problem, note.getText().toString(), reuse);
        });
    }

    // ---- Results ----

    /** A submission changed: the open dialog follows it, and a finished one of the user's says how it went. */
    @Override public void changed(Feedback.Event event) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        changed.run();
        boolean mine = showing() && !waitingFor.isEmpty() && waitingFor.equals(event.token);
        if (mine) {
            if (event.state == Feedback.State.SENT || event.state.waiting()) {
                waitingFor = "";
                if (event.kind == Feedback.Kind.FEEDBACK) draft = new Draft();
                AlertDialog done = dialog;
                done.dismiss();
                if (event.state == Feedback.State.SENT) sent(event);
                else said(event.state.said);
            } else if (event.state == Feedback.State.REJECTED || event.state == Feedback.State.NOT_QUEUED) {
                waitingFor = "";
                // A refused submission's token is never used again (the service may have kept a part of it): the
                // next Send builds afresh, under a new one.
                if (preparedNow != null) preparedNow[0] = null;
                status(event.said);
                busy(false);
            }
            return;
        }
        if (!event.user || showing()) return;
        if (event.state == Feedback.State.SENT) {
            sent(event);
        } else if (event.state == Feedback.State.REJECTED) {
            // Refused after it left the dialog: what was typed comes back as the draft.
            if (event.kind == Feedback.Kind.FEEDBACK && !event.typed.isEmpty()) draft.message = event.typed;
            said(event.said + (event.kind == Feedback.Kind.FEEDBACK && !event.typed.isEmpty()
                    ? " Your words are kept: tap Send anonymous feedback to edit them." : ""));
        }
    }

    /** The reference, selectable and copyable, once a submission is accepted. */
    private void sent(Feedback.Event event) {
        String what = event.kind == Feedback.Kind.PROBLEM ? "Report sent" : "Feedback sent";
        LinearLayout frame = frame();
        TextView reference = ui.text(event.reference.isEmpty() ? "No reference was given."
                : "Reference: " + event.reference, 18, ui.ink, true);
        reference.setTextIsSelectable(true);
        frame.addView(reference, Ui.matchWidth());
        frame.addView(small("Keep it if you may need to refer to this submission."), Ui.matchWidth());
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(what).setView(frame)
                .setPositiveButton("OK", null);
        if (!event.reference.isEmpty()) {
            builder.setNeutralButton("Copy", (shown, which) -> {
                ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText(AppName.NAME + " reference", event.reference));
                }
            });
        }
        dialog = OwnWindowTouches.show(builder);
        feedbackOpen = false;
    }

    private void said(String words) {
        dialog = OwnWindowTouches.show(new AlertDialog.Builder(activity).setMessage(words)
                .setPositiveButton("OK", null));
        feedbackOpen = false;
    }

    private void closed() {
        feedbackOpen = false;
    }

    // ---- Pieces ----

    private LinearLayout frame() {
        LinearLayout frame = ui.column();
        frame.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(4));
        return frame;
    }

    private ScrollView scroll(View content) {
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        return scroll;
    }

    /** A row of choices, one at a time, scrolling sideways on a narrow screen. */
    private RadioGroup chips(LinearLayout frame) {
        HorizontalScrollView row = new HorizontalScrollView(activity);
        row.setHorizontalScrollBarEnabled(false);
        RadioGroup group = new RadioGroup(activity);
        group.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(group);
        LinearLayout.LayoutParams params = Ui.matchWidth();
        params.bottomMargin = ui.dp(6);
        frame.addView(row, params);
        return group;
    }

    /** A chip: a native radio button (screen readers hear it and its state) drawn as a rounded label. */
    private RadioButton chip(RadioGroup group, String label) {
        RadioButton chip = new RadioButton(activity);
        chip.setId(View.generateViewId());
        chip.setText(label);
        chip.setButtonDrawable(null);
        chip.setGravity(Gravity.CENTER);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        chip.setTypeface(Ui.MEDIUM);
        chip.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}},
                new int[] {ui.onAccent, ui.ink}));
        StateListDrawable face = new StateListDrawable();
        face.addState(new int[] {android.R.attr.state_checked}, ui.rounded(ui.accent, 0, 18));
        face.addState(new int[] {}, ui.rounded(0x00000000, ui.dark ? 0x40FFFFFF : 0x330B0B0B, 18));
        chip.setBackground(face);
        chip.setMinHeight(ui.dp(48));
        chip.setMinimumHeight(ui.dp(48));
        chip.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(RadioGroup.LayoutParams.WRAP_CONTENT,
                RadioGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(ui.dp(8));
        group.addView(chip, params);
        return chip;
    }

    private interface Typed {
        void changed(String text);
    }

    /** A text box of at most {@value Feedback#MAX_MESSAGE_CHARS} characters, with a counter under it. */
    private EditText editor(LinearLayout frame, String hint, String text, Typed typed) {
        EditText editor = new EditText(activity);
        editor.setHint(hint);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setMinLines(3);
        editor.setMaxLines(8);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setFilters(new InputFilter[] {new InputFilter.LengthFilter(Feedback.MAX_MESSAGE_CHARS)});
        editor.setText(text);
        frame.addView(editor, Ui.matchWidth());
        TextView counter = ui.text("", 12, ui.inkSecondary, false);
        counter.setGravity(Gravity.END);
        counter.setText(count(text.length()));
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override public void afterTextChanged(Editable s) {
                counter.setText(count(s.length()));
                typed.changed(s.toString());
            }
        });
        frame.addView(counter, Ui.matchWidth());
        return editor;
    }

    /** "120 / 4,000". */
    static String count(int length) {
        return String.format(Locale.US, "%,d / %,d", length, Feedback.MAX_MESSAGE_CHARS);
    }

    private TextView small(String words) {
        TextView text = ui.text(words, 13, ui.inkSecondary, false);
        text.setPadding(0, ui.dp(4), 0, ui.dp(4));
        return text;
    }

    private TextView status(LinearLayout frame) {
        TextView line = ui.text("", 14, ui.ink, true);
        line.setPadding(0, ui.dp(6), 0, ui.dp(2));
        line.setVisibility(View.GONE);
        line.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        frame.addView(line, Ui.matchWidth());
        return line;
    }

    private void status(String words) {
        if (statusLine == null) return;
        statusLine.setText(words);
        statusLine.setVisibility(words.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** The last submissions' dates and references, selectable, when there are any. */
    private void references(LinearLayout frame) {
        List<Feedback.Sent> sent = Feedback.references(activity);
        if (sent.isEmpty()) return;
        TextView heading = ui.text("Sent before", 13, ui.inkSecondary, true);
        heading.setPadding(0, ui.dp(10), 0, ui.dp(2));
        frame.addView(heading, Ui.matchWidth());
        StringBuilder lines = new StringBuilder();
        for (Feedback.Sent one : sent) lines.append(lines.length() == 0 ? "" : "\n").append(one.line());
        TextView list = ui.text(lines.toString(), 13, ui.ink, false);
        list.setTextIsSelectable(true);
        frame.addView(list, Ui.matchWidth());
    }

    /**
     * Exactly what is sent, a page of at most {@value #PREVIEW_PAGE_CHARS} characters at a time (cut at line ends:
     * the pages, in order, are the whole), so a long report never stalls the screen. It goes with the screen.
     */
    private void preview(String text) {
        if (previewDialog != null && previewDialog.isShowing()) previewDialog.dismiss();
        List<String> pages = Feedback.chunks(text, PREVIEW_PAGE_CHARS, Integer.MAX_VALUE);
        if (pages.isEmpty()) pages = java.util.Collections.singletonList("");
        TextView body = ui.text(pages.get(0), 11, ui.ink, false);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));
        ScrollView scroll = scroll(body);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(pageTitle(0, pages.size()))
                .setView(scroll);
        if (pages.size() == 1) {
            builder.setPositiveButton("OK", null);
        } else {
            builder.setPositiveButton("Next", null).setNeutralButton("Previous", null).setNegativeButton("Close", null);
        }
        AlertDialog shown = OwnWindowTouches.show(builder.setOnDismissListener(gone -> {
            if (previewDialog == gone) previewDialog = null;
        }));
        previewDialog = shown;
        if (pages.size() == 1) return;
        List<String> all = pages;
        int[] page = {0};
        Runnable turn = () -> {
            body.setText(all.get(page[0]));
            scroll.scrollTo(0, 0);
            shown.setTitle(pageTitle(page[0], all.size()));
            shown.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(page[0] > 0);
            shown.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(page[0] < all.size() - 1);
        };
        shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(tapped -> {
            if (page[0] < all.size() - 1) page[0]++;
            turn.run();
        });
        shown.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(tapped -> {
            if (page[0] > 0) page[0]--;
            turn.run();
        });
        turn.run();
    }

    /** "What is sent · page 2 of 5". */
    static String pageTitle(int page, int pages) {
        return pages <= 1 ? "What is sent" : "What is sent · page " + (page + 1) + " of " + pages;
    }

    /** While a submission is on its way the dialog takes no new one, and nothing in it changes. */
    private void busy(boolean on, View... inputs) {
        if (dialog != null) {
            Button send = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (send != null) send.setEnabled(!on);
        }
        for (View input : inputs) input.setEnabled(!on);
        busyInputs = on ? inputs : busyInputs;
        if (!on) {
            for (View input : busyInputs) input.setEnabled(true);
            busyInputs = new View[0];
        }
    }

    private View[] busyInputs = new View[0];

    /** For tests: as a new process would, forget the draft. */
    static void forgetDraft() {
        draft = new Draft();
    }
}
