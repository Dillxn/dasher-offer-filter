package com.local.dasherfilter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
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
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One feedback composer, with an optional selected offer, and what a screen shows of a submission's fate. Owned by one screen: it
 * listens only while that screen is started, and its dialogs go with it, so no result reaches a screen that is gone.
 * Every submission is built and sent off the main thread ({@link Feedback}); the dialog stays open while it is sent,
 * keeps what was typed when it is refused, and shows the reference, copyable, once it is accepted.
 *
 * <p>Masked diagnostics go with feedback only while Share anonymous diagnostics is on (0.6.34; before, each submission
 * chose with its own Attach switch), and with the report offered after a stop, for that report only. Turning the switch
 * on is its own dialog here ({@link #diagnostics}), never consent read from a switch.
 */
final class FeedbackDialogs implements Feedback.Listener {
    /** What the user was writing: kept across a recreated screen and a refused send, until it is accepted. */
    private static final class Draft {
        Feedback.Category category = Feedback.Category.GENERAL;
        OfferReport.Problem problem = OfferReport.Problem.MISREAD;
        String message = "";
        DecisionLog.Entry offer;
        boolean afterStop;
    }

    static final String TITLE = "Send feedback";
    static final String SETTINGS = "Feedback";

    /**
     * What Report this offer says it sends, and Send is the consent to it: all of what OfferReport carries
     * ({@link OfferReport#rulesJson}), Autopilot's whole state among it: its switch, goal, bar, mode (recovering and
     * its stall correction with it) and last change, and the acceptance rate it counts with, which is Dasher's latest
     * as shown (with how long ago), carried forward over the offers since, or the app's own estimate.
     */
    static final String OFFER_REPORT_SAYS = "Sends this offer's figures, decision and masked read lines, your current "
            + "rules and Autopilot's state (on or off, goal, bar, mode and last change, and the acceptance rate it "
            + "counts with: Dasher's latest, carried forward, or its own estimate), the app's and Android's versions "
            + "and the minute it was decided, with your note. No account. Masking can miss details.";
    /** A page of the preview: a whole report in one view would stall the screen. */
    static final int PREVIEW_PAGE_CHARS = 12_000;
    private static Draft draft = new Draft();
    private static final String OPEN = "feedback_dialog_open";
    /** Whether the open feedback dialog is the report offered after a stop, kept only through its recreated screen. */
    private static final String OPEN_ATTACH = "feedback_dialog_attach";

    private final Activity activity;
    private final Ui ui;
    private final Runnable changed;
    private AlertDialog dialog;
    /** The submission the open dialog waits for, or "" while it waits for none. */
    private String waitingFor = "";
    private TextView statusLine;
    private boolean feedbackOpen;
    /** Whether the open feedback dialog is the report offered after a stop, which sends diagnostics for it only. */
    private boolean openReport;
    /** What the open dialog built ahead of Send (for its preview), or null; dropped once a send is refused. */
    private Feedback.Prepared[] preparedNow;
    /** The preview over the open dialog, so it goes with the screen. */
    private AlertDialog previewDialog;
    private AlertDialog contextDialog;
    private final ReportShare reportShare = new ReportShare();
    private Button exportButton;
    private boolean exporting;
    /** A preview prepared for a dismissed or changed composer must never open over its replacement. */
    private long formGeneration;
    private boolean started;

    /** @param changed run on the main thread after any submission changed (Settings' line refreshes) */
    FeedbackDialogs(Activity activity, Ui ui, Runnable changed) {
        this.activity = activity;
        this.ui = ui;
        this.changed = changed;
    }

    // ---- The screen's life ----

    void start() {
        started = true;
        Feedback.listen(this);
        Feedback.Event unseen = Feedback.takeUnseen();
        if (unseen != null) changed(unseen);
    }

    void stop() {
        started = false;
        cancelExport();
        if (statusLine != null && "Preparing…".contentEquals(statusLine.getText())) status("");
        Feedback.unlisten(this);
    }

    void destroy() {
        cancelExport();
        Feedback.unlisten(this);
        if (contextDialog != null && contextDialog.isShowing()) contextDialog.dismiss();
        contextDialog = null;
        if (previewDialog != null && previewDialog.isShowing()) previewDialog.dismiss();
        previewDialog = null;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        dialog = null;
    }

    /** A recreated screen (a resize, day and night) opens the feedback dialog again, as it was. */
    void save(Bundle state) {
        boolean open = feedbackOpen && dialog != null && dialog.isShowing() && waitingFor.isEmpty();
        state.putBoolean(OPEN, open);
        state.putBoolean(OPEN_ATTACH, open && openReport);
    }

    void restore(Bundle state) {
        if (state != null && state.getBoolean(OPEN, false)) feedback(null, state.getBoolean(OPEN_ATTACH, false));
    }

    boolean showing() {
        return dialog != null && dialog.isShowing();
    }

    // ---- Share anonymous diagnostics ----

    static final String DIAGNOSTICS_TITLE = "Share anonymous diagnostics?";
    /** What turning it on sends, all of it: the summary after each dash, feedback's diagnostics, the timing study. */
    static final String DIAGNOSTICS_SAYS = "Sends masked diagnostics to the developer, with no account. After each "
            + "dash, one summary: the app and Android versions and phone maker, your switches, counts of offers and "
            + "problems, that dash's decisions timed from its start, masked log lines around problems, and how long "
            + "you waited for offers against the app's estimate, with their figures. With feedback you send, the "
            + "masked diagnostic report. No location, place names or device ID. At most 3 summaries a day; "
            + "kept 90 days. Turn it off any time: what is not sent yet is discarded.";

    /**
     * Turning Share anonymous diagnostics on (Settings' switch): Turn on is the only way in. Not now, Back, a dismissal
     * or the screen going leave it as it was, and turning it off needs no dialog.
     */
    void diagnostics() {
        if (showing() || !Consent.accepted(activity) || Feedback.diagnosticsOn(activity)) return;
        feedbackOpen = false;
        dialog = OwnWindowTouches.show(new AlertDialog.Builder(activity).setTitle(DIAGNOSTICS_TITLE)
                .setMessage(DIAGNOSTICS_SAYS)
                .setNegativeButton("Not now", null)
                .setPositiveButton("Turn on", (d, which) -> {
                    Feedback.setDiagnostics(activity, true);
                    changed.run();
                })
                .setOnDismissListener(d -> { if (dialog == d) closed(); }));
    }

    // ---- One feedback composer ----

    /** With Share anonymous diagnostics on: what goes with feedback. */
    static final String WITH_DIAGNOSTICS = "Masked diagnostics go with it, since Share anonymous diagnostics is on.";
    /** With it off. */
    static final String WORDS_ONLY = "Only your words, the category and the app's version go. Share anonymous "
            + "diagnostics in Settings adds masked diagnostics.";
    /** The report offered after a stop, whatever the switch says. */
    static final String REPORT_SENDS = "This report sends masked diagnostics with it, the stop's summary among them, "
            + "for this report only.";

    /**
     * The feedback dialog: a category, what to say (a counter and its limit), a reminder to leave out customer,
     * payment and account details, a line saying whether masked diagnostics go with it, a Preview of exactly what goes,
     * and the references of the last submissions.
     *
     * @param category chosen in advance (Bug after a stop, Usability from setup), or null to keep the draft
     * @param attach   true for the report offered after a stop, which sends diagnostics for this dialog only; null or
     *                 false: they go only while Share anonymous diagnostics is on
     */
    void feedback(Feedback.Category category, Boolean attach) {
        if (showing()) return;
        if (category != null) {
            draft.category = category;
            draft.offer = null;
            draft.afterStop = attach != null && attach;
        } else if (attach != null) {
            draft.afterStop = attach;
        }
        // A cancelled offer draft follows the retained history; Clear history must not resurrect its old evidence.
        if (draft.offer != null) {
            DecisionLog.Entry found = null;
            for (DecisionLog.Entry entry : DecisionLog.recent(activity, DecisionLog.MAX_ENTRIES)) {
                if (sameOffer(entry, draft.offer)) { found = entry; break; }
            }
            draft.offer = found;
        }
        compose();
    }

    /** The ticket is a shortcut into the same composer, with that offer already chosen. */
    void reportOffer(DecisionLog.Entry entry) {
        if (showing()) return;
        draft.offer = entry;
        draft.afterStop = false;
        draft.problem = OfferReport.Problem.MISREAD;
        compose();
    }

    private void compose() {
        cancelExport();
        final long generation = ++formGeneration;
        waitingFor = "";
        final DecisionLog.Entry offer = draft.offer;
        final boolean report = offer == null && draft.afterStop;
        openReport = report;
        LinearLayout frame = frame();
        frame.addView(small("No account, name or email. Tap Send to submit feedback to the developer."), Ui.matchWidth());
        frame.addView(small("About"), Ui.matchWidth());
        Button about = ui.link(offer != null ? offerLabel(offer)
                : report ? "The app stopped unexpectedly" : "General feedback · choose an offer if relevant",
                this::chooseContext);
        about.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        frame.addView(about, Ui.matchWidth());
        RadioGroup chips = chips(frame);
        if (offer == null) {
            for (Feedback.Category one : Feedback.Category.values()) {
                RadioButton chip = chip(chips, one.label);
                chip.setTag(one);
                if (one == draft.category) chip.setChecked(true);
            }
        } else {
            for (OfferReport.Problem problem : OfferReport.Problem.values()) {
                RadioButton chip = chip(chips, problem.label);
                chip.setTag(problem);
                if (problem == draft.problem) chip.setChecked(true);
            }
        }
        chips.setOnCheckedChangeListener((group, id) -> {
            View checked = group.findViewById(id);
            if (checked != null && checked.getTag() instanceof Feedback.Category) {
                draft.category = (Feedback.Category) checked.getTag();
            } else if (checked != null && checked.getTag() instanceof OfferReport.Problem) {
                draft.problem = (OfferReport.Problem) checked.getTag();
            }
        });
        EditText message = editor(frame, offer == null ? "What should we know?" : "What went wrong? (optional)",
                draft.message, typed -> draft.message = typed);
        frame.addView(small("Don't include customer, payment or account details."), Ui.matchWidth());
        // Selecting an offer consents to that narrow report on Send, as its ticket always did. It does not add the
        // whole diagnostic history. General feedback still follows the one switch, read again at Preview and Send.
        frame.addView(small(offer != null ? OFFER_REPORT_SAYS : report ? REPORT_SENDS
                : Feedback.diagnosticsOn(activity) ? WITH_DIAGNOSTICS : WORDS_ONLY), Ui.matchWidth());
        Feedback.Prepared[] prepared = new Feedback.Prepared[1];
        preparedNow = prepared;
        boolean[] preparedWith = new boolean[1];
        OfferReport.Problem[] preparedFor = new OfferReport.Problem[1];
        Button preview = ui.link("Preview what is sent", () -> {
            cancelExport();
            status("Preparing…");
            boolean with = report || Feedback.diagnosticsOn(activity);
            OfferReport.Problem problem = draft.problem;
            java.util.function.Consumer<Feedback.Prepared> done = ready -> {
                if (!started || !currentForm(generation) || !waitingFor.isEmpty()) return;
                status("");
                if (ready == null) {
                    status("Couldn't prepare the feedback; try again.");
                    return;
                }
                prepared[0] = ready;
                preparedWith[0] = with;
                preparedFor[0] = problem;
                preview(ready.preview(offer == null ? draft.category.wire : problem.category(),
                        message.getText().toString(), Updater.version(activity),
                        Feedback.versionCode(activity)));
            };
            if (offer == null) Feedback.prepareFeedback(activity, with, done);
            else Feedback.prepareOfferReport(activity, offer, problem, done);
        });
        preview.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        frame.addView(preview, Ui.matchWidth());
        statusLine = status(frame);
        references(frame);
        exportButton = ui.link("Export diagnostics…", this::exportDiagnostics);
        exportButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        frame.addView(exportButton, Ui.matchWidth());
        frame.addView(small("Opens a masked diagnostic report of recent history and logs in an app you choose. It does not submit "
                + "the feedback draft above."), Ui.matchWidth());
        feedbackOpen = true;
        dialog = OwnWindowTouches.show(new AlertDialog.Builder(activity)
                .setTitle(TITLE)
                .setView(scroll(frame))
                .setPositiveButton("Send", null)
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(closedDialog -> { if (dialog == closedDialog) closed(); }));
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(tapped -> {
            String typed = message.getText().toString();
            if (offer == null && Feedback.typed(typed).isEmpty()) {
                status("Write something first.");
                return;
            }
            boolean with = report || Feedback.diagnosticsOn(activity);
            Feedback.Prepared reuse = prepared[0] != null && (offer == null ? preparedWith[0] == with
                    : preparedFor[0] == draft.problem) ? prepared[0] : null;
            cancelExport();
            busy(true, message, preview, about, chips, exportButton);
            status((offer != null || with) && reuse == null ? "Preparing and sending…" : "Sending…");
            waitingFor = offer == null ? Feedback.sendFeedback(activity, draft.category, typed, with, reuse)
                    : Feedback.sendOfferReport(activity, offer, draft.problem, typed, reuse);
        });
    }

    /** All retained recent offers, newest first, reachable from Settings without another reporting route. */
    private void chooseContext() {
        if (!feedbackOpen || !showing() || !waitingFor.isEmpty()) return;
        List<DecisionLog.Entry> recent = DecisionLog.recent(activity, DecisionLog.MAX_ENTRIES);
        List<String> labels = new ArrayList<>();
        labels.add("General feedback");
        for (DecisionLog.Entry entry : recent) labels.add(offerLabel(entry));
        contextDialog = OwnWindowTouches.show(new AlertDialog.Builder(activity).setTitle("What is this about?")
                .setItems(labels.toArray(new String[0]), (shown, index) -> {
                    draft.offer = index == 0 ? null : recent.get(index - 1);
                    draft.afterStop = false;
                    dialog.dismiss();
                    compose();
                })
                .setNegativeButton("Cancel", null));
    }

    private String offerLabel(DecisionLog.Entry entry) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(entry.at))
                + " · " + (entry.addOn ? "Add-on · " : "") + OfferReport.read(entry);
    }

    private static boolean sameOffer(DecisionLog.Entry a, DecisionLog.Entry b) {
        return a.at == b.at && a.source == b.source && a.addOn == b.addOn
                && a.facts.fingerprint().equals(b.facts.fingerprint());
    }

    private boolean currentForm(long generation) {
        return formGeneration == generation && feedbackOpen && showing();
    }

    /** The optional full diagnostic export lives here, never alongside Feedback as another support route. */
    private void exportDiagnostics() {
        if (!feedbackOpen || !showing() || !started || !Consent.accepted(activity)) return;
        final long generation = formGeneration;
        if (reportShare.start(activity, report -> {
            exporting = false;
            if (!currentForm(generation) || !started || activity.isFinishing() || activity.isDestroyed()) return;
            exportButton.setEnabled(true);
            if (report == null) {
                status("Could not prepare the diagnostics. Try again.");
                return;
            }
            status("");
            try {
                activity.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, report.subject)
                        .putExtra(Intent.EXTRA_TEXT, report.body), "Export " + AppName.NAME + " diagnostics"));
            } catch (RuntimeException error) {
                status("Android couldn't open an app to share the diagnostics.");
            }
        })) {
            exporting = true;
            exportButton.setEnabled(false);
            status("Preparing diagnostics…");
        }
    }

    /** Leaving the app or its feedback composer cannot open an export chooser later. */
    void cancelExport() {
        reportShare.cancel();
        if (exporting && statusLine != null && "Preparing diagnostics…".contentEquals(statusLine.getText())) status("");
        exporting = false;
        if (exportButton != null) exportButton.setEnabled(waitingFor.isEmpty());
    }

    /** Clear history removes a cancelled draft's retained offer and stop context too; typed words remain a draft. */
    static void forgetContext() {
        draft.offer = null;
        draft.afterStop = false;
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
                draft = new Draft();
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
                    ? " Your words are kept: open Feedback to edit them." : ""));
        }
    }

    /** The reference, selectable and copyable, once a submission is accepted. */
    private void sent(Feedback.Event event) {
        String what = "Feedback sent";
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
        formGeneration++;
        feedbackOpen = false;
        cancelExport();
        if (previewDialog != null && previewDialog.isShowing()) previewDialog.dismiss();
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
        TextView heading = ui.text("Recent submissions", 13, ui.inkSecondary, true);
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
        for (View input : inputs) enabled(input, !on);
        busyInputs = on ? inputs : busyInputs;
        if (!on) {
            for (View input : busyInputs) enabled(input, true);
            busyInputs = new View[0];
        }
    }

    private View[] busyInputs = new View[0];

    private void enabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) enabled(group.getChildAt(i), enabled);
        }
    }

    /** For tests: as a new process would, forget the draft. */
    static void forgetDraft() {
        draft = new Draft();
    }
}
