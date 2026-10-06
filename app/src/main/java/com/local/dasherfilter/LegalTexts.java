package com.local.dasherfilter;

/**
 * The terms of use, the privacy text and the licence, bundled so the app shows them with no connection: TERMS.md,
 * PRIVACY.md and LICENSE word for word (LegalTextsTest holds them to the files), with the app's name filled in from
 * {@link AppName}. Written by tools/legal_texts.py from the files: edit the files and run it, never this.
 */
final class LegalTexts {
    /** One of the texts: its short title (the page's header and the link to it) and the file it comes from. */
    enum Doc {
        TERMS("Terms of use", "TERMS.md", TERMS_TEXT),
        PRIVACY("Privacy", "PRIVACY.md", PRIVACY_TEXT),
        LICENSE("License", "LICENSE", LICENSE_TEXT);

        final String title;
        final String file;
        private final String text;

        Doc(String title, String file, String text) {
            this.title = title;
            this.file = file;
            this.text = text;
        }

        /** The text as the file has it, the app's name filled in. */
        String text() {
            return text.replace("{app}", AppName.NAME);
        }

        /** The text an intent names, or the terms for a name that is none of them. */
        static Doc named(String name) {
            for (Doc doc : values()) if (doc.name().equals(name)) return doc;
            return TERMS;
        }
    }

    private static final String TERMS_TEXT = """
            # {app} terms of use

            Draft of 6 October 2026. Not legal advice; have a lawyer review before public release. No attorney \
            review is claimed.

            ## What {app} is

            {app} is independent software for Android phones. It is not made, endorsed, sponsored or supported by \
            DoorDash, Inc. DoorDash and Dasher are trademarks of DoorDash, Inc., named here only to say which app \
            {app} works with.

            By tapping I understand and accept on the app's first screen, you confirm that you understand the \
            acceptance-rate risk below, choose to use {app} at your own risk, and accept these terms. If you do not \
            accept them, do not use the app: tap Not now, turn off its screen reading and notification access, or \
            uninstall it.

            ## Acceptance-rate risk

            Automatic declines may dramatically lower your DoorDash acceptance rate. This can affect DoorDash \
            programs that depend on it. You must acknowledge this risk before filtering begins, including after an \
            update that changes the notice. Continuing is your choice, at your own risk; the app makes no promise \
            to preserve your acceptance rate.

            ## What it does

            - It reads the Dasher app's screen and notifications on your phone, through Android's accessibility \
            service and notification access, which you turn on yourself.
            - It compares each offer with the minimums you set. When an offer is below them, it can tap Decline, \
            and then Dasher's confirmation, for you. Auto-accept starts off. If you separately enable it in \
            Settings, it can tap Accept on a complete standalone offer that passes your current rules, committing \
            you to that delivery. It respects your selected strict or compensating area mode and minimums \
            percentage. Add-ons and unclear offers are left to you.
            - If Dasher reports an error after that first Decline and leaves a recognized empty or map-only screen, \
            it can press Back and retry the same offer, at most twice within the original attempt's time limit. It \
            checks the offer again before retrying. Your touch, a new offer, locking the phone or leaving Dasher \
            stops this recovery; an unknown loading screen alone never permits it.
            - Peek is on by default after you accept the current notice. On an unlocked, quiet phone it can briefly \
            open Dasher for a fresh background offer and apply your normal rules. It can return to the app you were \
            using after completion evidence; while navigation is detected, it also returns for passing or unclear \
            offers and leaves a card. Otherwise those offers stay in Dasher. Your touch or app switch ends that \
            automatic return. You can turn Peek off in Settings.
            - While it declines an offer on screen, it can turn media and alarm sound down for a moment and then \
            put them back. During Peek it touches only the alarm stream, leaving media and navigation audio alone. \
            You can turn this off in Settings.
            - A tap is a request to the Dasher app, not a confirmation from DoorDash. Hiding a notification does \
            not decline an order.
            - It updates itself from its public signed update feed without requiring an account. Feedback is \
            accountless and is sent only when you tap Send; masked diagnostics are attached only when you \
            explicitly choose them (see the privacy text).

            ## Your responsibilities

            - Your Dasher account and your agreements with DoorDash are yours. Using third-party tools may break \
            DoorDash's terms, and DoorDash could limit or deactivate your account. You decide whether to use {app}, \
            and you accept that risk.
            - Obey traffic laws. Do not handle your phone while driving; set your rules before you drive and pull \
            over to look at offers. {app} is not a safety device.
            - Check your Dasher history. {app} can misread an offer, decline an offer you wanted, accept an offer \
            you did not want, or fail to act. An automatic Accept request does not prove DoorDash accepted it; \
            check the actual delivery state.
            - You must be 18 or older to use {app}.

            ## No warranty

            {app} is provided "as is" and "as available", without warranties of any kind, express or implied, \
            including warranties of merchantability, fitness for a particular purpose, accuracy and \
            non-infringement, to the extent the law allows.

            ## Limitation of liability

            To the extent the law allows, the author of {app} is not liable for any loss or damage arising from \
            your use of it or your inability to use it, including lost earnings, missed, accepted or declined \
            offers, limits on or deactivation of your Dasher account, fines, or lost data.

            ## Licence

            {app} is free and open-source software under the MIT License in LICENSE. That license grants the rights \
            to use, copy, modify and distribute the software, subject to its copyright and permission notice. These \
            terms do not limit the rights granted by the MIT License. Tips are optional gifts to the author and buy \
            nothing.

            ## Changes

            These terms may change. When they change in substance, the app shows its notice again before it reads \
            or acts on anything more.

            ## Stopping

            You can stop at any time: pause the filter, disable auto-accept, turn off the app's screen reading and \
            notification access in Android's settings, or uninstall it.
            """;

    private static final String PRIVACY_TEXT = """
            # {app} privacy

            Draft of 6 October 2026. Not legal advice; have a lawyer review before public release. No attorney \
            review is claimed; a private privacy contact is not yet configured.

            {app} does not require an {app} account and has no ads or analytics. It does not sell or rent your \
            data. Offer reading and decisions happen on your phone. Update requests, optional reports, place-name \
            lookups and links you choose to open can send information to the services described under What leaves \
            the phone. The developer's public project identity is Dillxn.

            ## What it reads on the phone

            - Dasher's screen, through Android's accessibility service, while Dasher is on screen or in its half of \
            a split screen: each offer's pay, miles, time, stops and displayed total item count, and the other text \
            Dasher shows, which can include store names, customer names and addresses.
            - Dasher's notifications, through Android's notification access. Android shows the app every \
            notification; it ignores every app but Dasher.
            - Dasher's short error messages (toasts), through accessibility, only to recognize a failed decline. \
            Their wording is not stored or sent; recovery logs keep a fixed error category and attempt counts.
            - Peek, on by default after the current notice is accepted, can briefly bring Dasher forward to read a \
            fresh background offer. To return you afterward, it checks Android's window information and the app \
            identifier of the app previously in front. It does not read that app's text. It also checks screen \
            lock, keyboard and window state, call/audio mode, and Android's available microphone/camera-in-use \
            indicators; it does not record audio or images.
            - Approximate location, only if you allow it, for the offer map. Auto theme can use the same existing, \
            fresh approximate location while the offer map is enabled, to calculate sunrise and sunset on the \
            phone. Theme selection never requests a new location fix or precise location.

            Before you accept the current first-run notice, it reads none of this and sends no reports.

            ## What it keeps on the phone

            - Your rules, your separate auto-accept choice (off by default), and what the adaptive minimum learned \
            from your own accepted or declined offers. Automatic accept requests do not train it.
            - The latest 200 decisions: when, the pay, miles, minutes, stops and total items read, whether the \
            offer declared items or shopping, the result, what the app did, and a few masked lines read from the \
            offer. This history has no age-based expiry: entries remain until newer decisions replace them or you \
            clear history or uninstall. Aggregate outcome counts are kept separately until you clear history or \
            uninstall. The decision format also supports a numeric final-stop-to-hotspot distance, without \
            coordinates or addresses. This candidate has no automatic reader for that distance and displays it as \
            unavailable; it does not obtain it from your location or the offer map.
            - Up to 200 numeric wait-estimate records in a rolling 24-hour window, pruned when used: the \
            observation time, positively observed waiting duration and any observed offer's pay, miles, minutes, \
            stops, item count/applicability and numeric hotspot distance if ever available. These separate records \
            contain no merchant names, screen text, addresses or coordinates and are never included in reports or \
            uploaded. Only visible, unlocked, eligible waiting is measured; rejected-offer handling counts only \
            after a later recognized wait or standalone offer confirms it continued. Hidden, unknown, delivery, \
            locked, paused and stopped-service time is excluded. A restart does not resume an old waiting timer. \
            The estimate rechecks these numeric offers against your current rules and stays unavailable until \
            enough readable observations exist; old decision timestamps alone are not treated as waiting time.
            - Two rolling logs of screen text, one for offers and the app's status and one for other dash screens. \
            Other screens are captured only during a dash or within ten minutes of a readable offer, and only when \
            positively recognized as offer, confirmation, idle, delivery or navigation screens. \
            Unrecognized/partial screens retain only a generic text-not-kept note. Each holds about what one report \
            carries, with a 24-hour retention window. Older entries are pruned during app use and when the logs are \
            read; files can remain while the app is stopped or a storage operation fails. Screens recognized as \
            payment, account or earnings pages are discarded; at most one line a minute records that a screen was \
            not kept.
            - The offer map, if it is on and location is allowed: up to 300 historical squares of about 2 km where \
            offers came in, with no age-based expiry. The separate place-name cache holds at most 300 rounded \
            positions, also without age-based expiry; older names are removed as others are used. Clear history or \
            uninstalling removes both.
            - While you are dashing: whether a dash is on, and the current route's pay, miles, time, stops and \
            observed item count/applicability, for at most three hours since the route was observed.
            - One temporary record that prevents automatic taps on a possibly taken-over or automatically accepted \
            offer after a restart: numeric pay, miles, minutes, stops, observed item count/applicability, \
            countdown, and device uptime/boot number. It can suppress taps for at most two minutes; the app \
            discards it when it next checks an expired record, observes the offer ended, or you clear history. It \
            never restores permission to tap. One scanner-error category, without the error message or screen text, \
            is kept until the next service connection or Clear history.
            - A separate temporary automatic-Accept request record keeps only numeric offer facts and device \
            uptime/boot number, for up to two minutes. It only prevents an automatic choice from training personal \
            minimums; it never proves acceptance or restores tap authority. Clear history removes it.
            - A pending hand-decline observation can retain the same numeric offer facts, including observed item \
            count/applicability, to reconcile its outcome, for at most one hour.
            - The state of updates. End-user GitHub sign-in is retired in 0.4.73; an old stored GitHub connection \
            and unsent GitHub report queue are removed when the updated app first opens.
            - Feedback is sent directly when you tap Send and is not kept in a persistent on-phone outbox. If you \
            explicitly attach diagnostics, the app builds the same masked diagnostic report for that one \
            submission.
            - Your Day, Night, System or Auto theme choice. Auto is the default for new choices; it calculates the \
            local sun cycle using only an existing permitted approximate location, without a network request or a \
            separate saved location. When none is available it uses a disclosed local-clock fallback: day from 6 am \
            to 6 pm. Existing explicit day/night choices are preserved.
            - During Peek, the previous app's identifier and launcher component stay only in memory. They are \
            cleared when Peek ends without a return, when a return fails, or after the return check (up to 1.5 \
            seconds after returning). They are never saved or sent; Peek logs describe only the kind of previous \
            app, such as a navigation app or the home screen. Its screen text is never kept.

            Pay per item uses only an explicit, unambiguous total count on an offer declaring items or shopping. An \
            unread declared count stays unknown; no quantity is inferred from product names, unique-item counts, \
            stops or orders. These numeric item facts may appear in the same opt-in/shared reports as the other \
            offer figures.

            Decision and confirmation lines take priority over repeated scan timings within the existing log \
            limits. Reports include numeric counts of discarded log lines or queued reports, so missing evidence is \
            visible. Atomic history writes keep a recovery copy only while replacing the same bounded history.

            Screen text is masked before it is kept: customer names, your own name, street addresses, city, state \
            and ZIP lines, apartment numbers, phone numbers, email addresses, delivery instructions, card numbers \
            and their security codes/expiry/PIN, and streets in navigation become [name], [address], [phone], \
            [email], [instructions], [card] and [street]. Store names, offer figures, buttons and shopping items \
            stay. Offers are judged from what is on screen; only what is kept is masked. Click diagnostics retain \
            the control shape and action category, never its text labels.

            Masking is pattern-based and can miss unfamiliar wording. It is not a guarantee of anonymity or removal \
            of every personal detail. Review a report before sharing it. Notes you type with Report this offer are \
            not masked; do not include customer, payment or account details. Diagnostics are masked again with the \
            current rules immediately before an explicitly requested feedback submission.

            A one-time privacy cleanup clears older diagnostic logs and legacy unsent reports if it has not already \
            completed on this installation. Separately, 0.4.73 retires any stored end-user GitHub connection and \
            discards legacy unsent GitHub reports when the app opens. Neither cleanup can remove copies that were \
            already sent.

            Android backup is turned off for this app, so none of this goes into your phone's cloud backup.

            ## What leaves the phone, and when

            - Update checks. The app asks its public update server (dash-offer-filter-build.onrender.com, hosted by \
            Render) for the latest version and downloads it. No GitHub account is required. These requests carry \
            ordinary internet connection metadata such as your IP address and the app's own request information, \
            but no offer history.
            - Anonymous feedback, only when you tap Send. The app posts what you type to a dedicated Supabase Edge \
            Function. No {app} account, GitHub account, name or email is required or included by the app. The \
            feedback record stores the feedback category, message, app version and whether masked diagnostics were \
            explicitly attached. The service derives a one-way, rotating rate-limit value from connection metadata; \
            the raw IP address is not stored in the feedback table. Supabase and intervening network providers \
            necessarily receive ordinary connection metadata while handling the request and may keep infrastructure \
            logs under their own policies, so this is not a mathematical guarantee of anonymity.
            - Masked diagnostics are included with feedback only when you explicitly turn on **Attach masked \
            diagnostics** for that submission. They can include the Android version and phone maker, current rules, \
            decision history and the bounded masked logs described above. Review them before sending because \
            pattern-based masking can miss details.
            - Feedback records expire after 90 days. They may be reviewed by the developer and by AI systems \
            (Anthropic's Claude and OpenAI's ChatGPT/Codex) used to investigate problems. A submission does not \
            guarantee review or a fix.
            - **Share report**, only when you tap it, still hands a masked report to the app you choose. From \
            there, that app handles the copy under its own privacy practices.
            - Place names. To name the squares on the map, the app asks Android's own place lookup (Google's \
            servers on most phones) about positions rounded to about half a kilometre, once while a position \
            remains cached. An evicted or cleared position may be looked up again. Nothing about your offers goes \
            with them.
            - Navigation, only when you tap it. Opening an offer area hands that historical area's center \
            coordinates to your chosen Maps or Waze app, or to Google Maps in a browser. Gas and gas-price choices \
            hand a search phrase to the map provider. The receiving app or website handles the request under its \
            own privacy practices; these actions do not upload your offer history or establish your arrival.
            - Tips. The Cash App, Venmo and PayPal links open only when you tap them; the app sends nothing for \
            them and counts nothing.

            Render, Supabase, Google, Anthropic and OpenAI handle what reaches them under their own terms and \
            privacy policies. Copies you deliberately share through another app are controlled by that app and \
            recipient.

            ## What it never collects

            The app does not request your DoorDash password, contacts, photos, messages, precise location or \
            payment details. Accessibility can expose unrelated text when Dasher displays it; recognized payment, \
            account and earnings screens are discarded, and the masking described above is applied before \
            diagnostic text is saved.

            ## Your choices

            - Turn off **Peek at background offers** in Settings to stop automatic temporary opens of Dasher. Peek \
            requires an unlocked, quiet phone and skips when its checks find typing, a call, microphone/camera use, \
            split or floating windows, a pinned or unrecognized app, or another conflicting action. Its checks \
            depend on what Android exposes; they are not a safety guarantee.
            - Clear history (Settings) removes the decisions, both logs, the observed-wait records, the offer map \
            and cached place names, plus the temporary restart/error records above. Lookups and wait-record writes \
            already in progress cannot restore cleared history. Your rules and learned minimums remain; Reset \
            clears the learned minimums separately.
            - Feedback leaves only after you tap Send. Leave **Attach masked diagnostics** off to send only your \
            typed message and basic app-version metadata.
            - Uninstalling removes the app's local data. It does not remove feedback already submitted or copies \
            you deliberately shared through another app.

            ## Help and privacy contact

            Use **Send anonymous feedback** in Settings, or the feedback form on offerfilter.org, for non-sensitive \
            feedback without creating an account. Do not type customer, payment, account or other sensitive \
            details. If you need to refer to an earlier submission, keep the short reference shown after it is \
            accepted.

            The project issue tracker remains public and is suitable only for public, non-sensitive development \
            discussion. Do not attach diagnostic reports, customer details, payment or account information, \
            addresses, screenshots containing personal information, or tokens.

            A separate private identity-verification channel for privacy/deletion/security requests is not yet \
            configured. Because ordinary feedback does not require an identity, the developer may be unable to \
            prove which anonymous submission belongs to a requester without its reference.

            ## Children

            {app} is not for anyone under 18.

            ## Changes

            When this text changes in substance, the app shows its notice again before it reads anything more or \
            sends feedback.
            """;

    private static final String LICENSE_TEXT = """
            MIT License

            Copyright (c) 2026 Dillxn

            Permission is hereby granted, free of charge, to any person obtaining a copy
            of this software and associated documentation files (the "Software"), to deal
            in the Software without restriction, including without limitation the rights
            to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
            copies of the Software, and to permit persons to whom the Software is
            furnished to do so, subject to the following conditions:

            The above copyright notice and this permission notice shall be included in
            all copies or substantial portions of the Software.

            THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
            IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
            FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
            AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
            LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
            OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
            THE SOFTWARE.
            """;

    private LegalTexts() {}
}
