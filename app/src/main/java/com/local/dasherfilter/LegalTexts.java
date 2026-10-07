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

            Beta terms, effective 7 October 2026 · for {app} 0.5.1

            ## What {app} is

            {app} is independent software for Android phones. It is not made, endorsed, sponsored or supported by \
            DoorDash, Inc. DoorDash and Dasher are trademarks of DoorDash, Inc., named here only to say which app \
            {app} works with.

            {app} is beta software: it is still being tested, it changes often, and it can fail in ways no one has \
            found yet. These terms change with it (see Changes).

            By tapping I understand and accept on the app's first screen, you confirm that you understand the \
            acceptance-rate risk below, choose to use {app} at your own risk, and accept these terms. If you do not \
            accept them, do not use the app: tap Not now, turn off its screen reading and notification access, or \
            uninstall it.

            ## Acceptance-rate risk

            Automatic declines may dramatically lower your DoorDash acceptance rate. This can affect DoorDash \
            programs that depend on it. You must acknowledge this risk before filtering begins, including after an \
            update that changes the notice. Continuing is your choice, at your own risk; the app makes no promise \
            to preserve your acceptance rate. Autopilot's acceptance-rate goal is a best effort, not a promise: \
            Autopilot cannot accept offers for you, and your acceptance rate rises only when you accept offers.

            ## What it does

            - It reads the Dasher app's screen and notifications on your phone, through Android's accessibility \
            service and notification access, which you turn on yourself. While auto-decline is paused, or no rule \
            is set, it reads nothing of Dasher's screen.
            - It compares each offer with the minimums you set: pay, pay per mile and pay per hour of Dasher's time \
            estimate, plus an optional maximum number of stops. When an offer is below them, it can tap Decline, \
            and then Dasher's confirmation, for you. Autopilot is off until you turn it on. It then moves how much \
            of your minimums an offer must pay, between 50% and 150%, using your recent offers, how often they come \
            and the acceptance rate Dasher shows when you decline. Above 100% it declines offers that meet your \
            minimums. Offers it lets through below your minimums are left to you. Autopilot also raises your \
            minimums once its bar has stayed at 103% or more for 30 offers over at least 2 days: all of them by the \
            same share, at most 10% at a time, while its bar comes down so offers are asked about the same right \
            after. It never lowers them; you can. A note on the homepage says what grew, with Undo, until you tap \
            it or your minimums change another way. Raised minimums can mean more declines. Turn this off with Let \
            my minimums grow in Autopilot's details; while Autopilot is off, your minimums never grow. Auto-accept \
            starts off. If you separately enable it in Settings, it can tap Accept on a complete standalone offer \
            that meets 100% of your minimums and any higher Autopilot bar, committing you to that delivery. Add-ons \
            and unclear offers are left to you.
            - If Dasher reports an error after that first Decline and leaves a recognized empty or map-only screen, \
            it can press Back and retry the same offer, at most twice within the original attempt's time limit. It \
            checks the offer again before retrying. Your touch, a new offer, locking the phone or leaving Dasher \
            stops this recovery; an unknown loading screen alone never permits it.
            - Peek is on by default after you accept the current notice. On an unlocked, quiet phone it can briefly \
            open Dasher for a fresh background offer and apply your normal rules. It can return to the app you were \
            using after completion evidence; while navigation is detected, it also returns for passing or unclear \
            offers and leaves a card. Otherwise those offers stay in Dasher. If Dasher opens without showing the \
            offer's details, Peek may tap Dasher's own offer notification once; if they still don't show, it leaves \
            a card saying so and, while you navigate, returns to your map first. Your touch on Dasher or an app \
            switch ends that automatic return (a touch made before Dasher appeared, meant for the app you were in, \
            still lets a completed automatic decline take you back). If the phone locks during Peek, Peek waits up \
            to 60 seconds for you to unlock it; an offer that arrives while the phone is locked can be opened when \
            you unlock within 40 seconds of it, unless you open Dasher yourself first. Within a minute of an \
            offer's first notification, a re-post of it does not open Dasher again when you already had that offer \
            in Dasher or may have accepted it. After repeated withdrawn offers or failed launches, Peek pauses \
            itself until your next dash or for 15 minutes; after an error, until your next dash or until you tap \
            Resume on the homepage. Peek never turns off its Settings switch. You can turn Peek off in Settings.
            - When you tap one of its offer cards, it opens Dasher; if Dasher comes up without the offer, it may \
            tap Dasher's own offer notification once. After an offer that left you in Dasher from a navigation app \
            ends without being accepted, a Back to map button can take you back; it opens your map only when you \
            tap it.
            - During an active dash with auto-decline on and a rule set, it keeps your unlocked screen from timing \
            out, whatever app is in front, while it has seen something of the dash in the last 15 minutes or Dasher \
            shows it, unless the dash is paused in Dasher, a call is under way, battery saver is on, or the battery \
            is at 20% or less and not charging. It never wakes or unlocks the phone; the power button still turns \
            the screen off. This uses more battery.
            - While it declines an offer on screen, it can turn media and alarm sound down for a moment and then \
            put them back. During Peek it touches only the alarm stream, leaving media and navigation audio alone. \
            You can turn this off in Settings.
            - A tap is a request to the Dasher app, not a confirmation from DoorDash. Hiding a notification does \
            not decline an order.
            - It updates itself from its public signed update feed without requiring an account. An update it finds \
            by itself during a dash waits until the dash ends; if nothing of a dash is seen for 8 hours while \
            Dasher is not in front and the screen is off, it installs then, after checking the update again.
            - Feedback and offer reports are accountless and are sent only when you tap Send. Masked diagnostics go \
            only when you attach them to feedback, or after each dash once you turn on Share anonymous diagnostics \
            after each dash (off by default). The privacy text says what each sends, where it goes and how long it \
            is kept: unsent submissions wait on the phone for at most 7 days, and the feedback service keeps them \
            for 90 days. Sending feedback does not guarantee a reply, review or fix.

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
            terms do not limit the rights granted by the MIT License. Its source code is public, in the project's \
            GitHub repository: <https://github.com/Dillxn/dasher-offer-filter>. Tips are optional gifts to the \
            author and buy nothing.

            ## Governing law

            These terms are governed by the laws of the State of Ohio, United States. Where the law of the place \
            you live gives you rights that an agreement cannot take away, these terms do not take them away.

            ## Changes

            These terms may change. When they change in substance, the app shows its notice again before it reads \
            or acts on anything more.

            ## Contact

            For privacy, data-deletion and security requests only, email privacy@offerfilter.org. Send everything \
            else as anonymous feedback from the app's Settings.

            ## Stopping

            You can stop at any time: pause the filter, disable auto-accept, turn off the app's screen reading and \
            notification access in Android's settings, or uninstall it.
            """;

    private static final String PRIVACY_TEXT = """
            # {app} privacy

            Beta privacy policy, effective 7 October 2026 · for {app} 0.5.1

            {app} does not require an {app} account and has no ads or analytics. It does not sell or rent your \
            data. Offer reading and decisions happen on your phone. Update requests, feedback you send, the \
            optional diagnostics after each dash, place-name lookups and links you choose to open can send \
            information to the services described under What leaves the phone. The developer's public project \
            identity is Dillxn. {app} is beta software, and this text changes with it (see Changes).

            ## What it reads on the phone

            - Dasher's screen, through Android's accessibility service, while Dasher is on screen or in its half of \
            a split screen: each offer's pay, miles, time, stops and displayed total item count, and the other text \
            Dasher shows, which can include store names, customer names and addresses. While auto-decline is \
            paused, or no rule is set, it reads nothing of Dasher's screen; Android's list of windows alone places \
            its tab. Map views it recognizes inside Dasher's screens are skipped, not read, except while it checks \
            a failed decline or an automatic Accept, or when a map's own label shows a sign of an offer; whether \
            Dasher's maps are recognized depends on how Dasher draws them.
            - On Dasher's decline question ("Are you sure you want to decline this offer?"), whether the decline is \
            the app's or yours and whether Autopilot is on or off: the acceptance-rate percentage Dasher shows, and \
            whether Dasher says declining that offer does not lower your acceptance rate. See Autopilot and your \
            acceptance rate below.
            - Dasher's notifications, through Android's notification access. Android shows the app every \
            notification; it ignores every app but Dasher. To decide whether a card of its own is needed, it also \
            checks how Android ranks Dasher's offer notification: whether it can pop up or sound, whether it \
            sounded, and whether Do Not Disturb lets it through. It remembers which of Dasher's notification \
            channels its offers came on and whether that channel alerts by itself. Its logs note that channel's \
            sound, vibration and importance settings, whether the notification asks to fill the screen, and the \
            names (never the values) of the notification's fields.
            - Dasher's short error messages (toasts), through accessibility, only to recognize a failed decline. \
            Their wording is not stored or sent; recovery logs keep a fixed error category and attempt counts.
            - Peek, on by default after the current notice is accepted, can briefly bring Dasher forward to read a \
            fresh background offer, or one that arrived while the phone was locked, right after you unlock it \
            (within 40 seconds of its notification, while that notification is still up). To return you afterward, \
            it checks Android's window information and the app identifier of the app previously in front. It does \
            not read that app's text. To tell which app a window belongs to, it asks Android once for that window's \
            top element and uses only its app identifier. It also checks screen lock, keyboard and window state, \
            call/audio mode, and Android's available microphone/camera-in-use indicators; it does not record audio \
            or images. If Dasher opens without showing the offer's details, Peek (or, after you tap one of the \
            app's offer cards, the screen reader) may send Dasher's own offer-notification tap once for that offer, \
            an intent Dasher itself made; when you tap a card saying Dasher didn't show the offer, it opens \
            Dasher's own offer screen first, and Dasher's launcher if nothing of Dasher's comes up.
            - When you tap one of the app's offer cards, it checks Android's window information and the identifier \
            of the app you tapped it from, only to offer a Back to map button when that app is a navigation app \
            (Google Maps, Google Maps Go or Waze). After an update installed while {app}'s screen was open, it \
            checks which app is in front the same way (window information and that app's identifier, never its \
            text), to reopen {app} only over its own screen or the home screen.
            - When a finger lands on the screen, as a time only. While it declines an offer (and for a few seconds \
            after), while it checks an offer before an automatic Accept, and before and during a peek, an invisible \
            one-pixel watch notes when a finger lands, so your touch hands the offer back or keeps Peek from \
            opening Dasher; the touch still goes where you touched. It never learns a touch's position or what was \
            touched; {app}'s own screens note when they are touched, so in split screen a touch on {app}'s half is \
            not taken for one on Dasher's. Touch times stay in memory; the logs say only that a touch came, how \
            soon after the app's own tap, and whether it was on {app}'s half.
            - For the screen hold during a dash (see Your choices): whether the phone is on and unlocked, whether a \
            call is under way, the battery level, whether it is charging and whether battery saver is on. It looks \
            at no other app's windows or words for it.
            - Approximate location, only if you allow it, for the offer map. Auto theme can use the same existing, \
            fresh approximate location while the offer map is enabled, to calculate sunrise and sunset on the \
            phone. Theme selection never requests a new location fix or precise location.
            - Android's own record of how the app last stopped (Android 11 and later), to notice a crash or a stop \
            for not responding.
            - Android's own settings for {app} itself, to show the homepage's setup steps and Settings' rows: how \
            {app} was installed (Android 13 and later: from a downloaded or local file or an app store, and the \
            name of the installing app); Android's restricted-settings record for {app}, where Android lets the app \
            read it; whether Accessibility and notification access are on for {app} and connected; whether its \
            notifications are allowed; whether it may install its own updates; and whether Android limits its \
            background battery use (the Restricted battery setting, or the restricted standby bucket on Android 11 \
            and later). Apart from how Android ranks and sets up Dasher's offer notifications (above) and, for \
            reports, Dasher's version and how its launcher screen starts, it reads no other app's settings.

            Before you accept the current first-run notice, it reads none of this and sends no feedback or \
            diagnostics, with three exceptions that read no words: the channel a new notification of Dasher's came \
            on, to post one reminder that the app is paused; after an update, which app is in front, to reopen \
            {app} only over its own screen or the home screen; and whether Android lets it install its own updates, \
            since updates are checked for and installed before the notice is accepted too.

            ## What it keeps on the phone

            - Your rules (minimum pay, per mile, per hour and max stops) and when your current minimums took \
            effect, your separate auto-accept choice (off by default), and Autopilot's settings: whether it is on, \
            your acceptance goal (70%, 50% or pay first), its current bar, whether you have answered its goal \
            question and whether Let my minimums grow is on (it is unless you turn it off). What else Autopilot \
            keeps is under Autopilot and your acceptance rate below.
            - The latest 200 decisions: when, the pay, miles, minutes, stops and total items read, whether the \
            offer declared items or shopping, the result, what the app did, the score (pay as a percent of your \
            minimums), the bar it was judged at and whether Autopilot set that bar, whether Dasher said declining \
            it does not lower your acceptance rate, what was seen of it afterward (accepted, declined by hand, or \
            not counted and why), and a few masked lines read from the offer. This history has no age-based expiry: \
            entries remain until newer decisions replace them or you clear history or uninstall. Aggregate outcome \
            counts are kept separately until you clear history or uninstall.
            - Up to 200 numeric wait-estimate records in a rolling 24-hour window, pruned when used: the \
            observation time, positively observed waiting duration and any observed offer's pay, miles, minutes, \
            stops and item count/applicability, used for the wait estimate and by Autopilot to estimate how often \
            offers arrive. These separate records contain no merchant names, screen text, addresses or coordinates \
            and are never included in reports or uploaded; Autopilot's log lines carry only the offer rate (offers \
            an hour) it works out from them. Only visible, unlocked, eligible waiting is measured; rejected-offer \
            handling counts only after a later recognized wait or standalone offer confirms it continued. Hidden, \
            unknown, delivery, locked, paused and stopped-service time is excluded. A restart does not resume an \
            old waiting timer. The estimate rechecks these numeric offers against your current rules and stays \
            unavailable until enough readable observations exist; old decision timestamps alone are not treated as \
            waiting time.
            - Two rolling logs of screen text, one for offers and the app's status and one for other dash screens. \
            Other screens are captured only during a dash or within ten minutes of a readable offer, and only when \
            positively recognized as offer, confirmation, idle, delivery or navigation screens. \
            Unrecognized/partial screens retain only a generic text-not-kept note. Each holds about what one report \
            carries, with a 24-hour retention window. Older entries are pruned during app use and when the logs are \
            read; files can remain while the app is stopped or a storage operation fails. Screens recognized as \
            payment, account or earnings pages are discarded; at most one line a minute records that a screen was \
            not kept. The logs also hold lines in fixed words, counts and times: how much reading cost Dasher (once \
            a minute), changes of the screen hold, a dash's end, setup steps, Autopilot's plans and bar changes, \
            and what Peek did, including Dasher's own screens after it opens (short class names, at most six per \
            launch), what a peek cost Dasher (counts of reads, nodes and slow fetches), the shapes of money labels \
            when pay could not be read (counts only, never amounts), how much of the screen Dasher had when it was \
            resized, and what happened to Dasher's own notification tap and to an offer that arrived while the \
            phone was locked.
            - A few latest states, in fixed words, for reports: Dasher's version and how its launcher screen \
            starts, the settings of Dasher's offer notification channel and the names of its notification's fields, \
            whether the touch watch could be put up, that Dasher reported an error after a decline, and the last \
            update result. At most 12 are kept, each replaced when it changes, until Clear history; reports carry \
            those from the last day.
            - The app's last status line, with its date and time: what it last did or found, which can include the \
            last offer's figures (pay, miles, minutes, stops and item count) and the pay it required. The next \
            status replaces it; Clear history does not remove it.
            - The offer map, if it is on and location is allowed: up to 300 historical squares of about 2 km where \
            offers came in, each with its number of offers, their total and best pay, the pay and miles of those \
            whose miles were read, and when it last had one, with no age-based expiry; also the last offer's pay, \
            miles, minutes and stops and when it came, so one offer seen twice counts once, and a count of offers \
            that came with no location. The separate place-name cache holds at most 300 rounded positions, which \
            can include where you opened the app (the place the homepage names), also without age-based expiry; \
            older names are removed as others are used. Clear history or uninstalling removes all of it.
            - While you are dashing: whether a dash is on, when it started and was last seen (also on the phone's \
            since-boot clock with its boot number, so a changed clock cannot release an update held for a dash \
            early: an update the app finds by itself waits for the dash to end, or for 8 hours with nothing of a \
            dash seen, Dasher not in front and the screen off), when it ended and whether Dasher showed it paused; \
            and the current route's pay, miles, time, stops and observed item count/applicability, until Dasher \
            shows the wait for offers, the dash's end or its home screen, or you tap Forget on the homepage. A \
            route is never used more than three hours after it was observed; it is deleted the next time the app \
            looks at it after that. For the screen hold, when something of the dash was last seen is also kept in \
            memory only.
            - One temporary record that prevents automatic taps on a possibly taken-over or automatically accepted \
            offer after a restart: numeric pay, miles, minutes, stops, observed item count/applicability, \
            countdown, and device uptime/boot number. It can suppress taps for at most two minutes; the app \
            discards it when it next checks an expired record, observes the offer ended, or you clear history. It \
            never restores permission to tap. One scanner-error category, without the error message or screen text, \
            is kept until the next service connection or Clear history.
            - A separate temporary automatic-Accept request record keeps only numeric offer facts and device \
            uptime/boot number, for up to two minutes. It only marks an automatic acceptance as automatic in your \
            history; it never proves acceptance or restores tap authority. Clear history removes it.
            - The state of updates: the version last advertised; when updates were last checked; a verified update \
            still waiting (its version name) and why it waits (a dash, Android's confirmation, or an installation \
            Android blocked); the newest version an "allow updates" notice was shown for; and the downloaded update \
            itself, until it installs, a newer one replaces it or it is no longer needed.
            - Setup progress on this phone: which setup switches were opened or tried from the homepage; which \
            switch you were turning on when you left for Android's settings or App info (acted on only within ten \
            minutes); whether restricted settings were found allowed; and whether Android was already asked for \
            location all the time. Also the one-time notes already read: Peek's introduction, and the version whose \
            "What is new" note was shown. Uninstalling removes it.
            - Feedback waiting to send. A submission is saved on the phone before it is sent, so no signal or a \
            restart loses nothing: your words, its category, and any masked diagnostics or offer report you chose \
            to include. At most 5 submissions wait, each at most 7 days; one still unsent after 7 days is discarded \
            unsent. Each is deleted from the phone once the feedback service has accepted it, or refused it for \
            good.
            - The last 10 references of accepted submissions: the date, the short reference and whether it was \
            feedback, an offer report or diagnostics after a dash, so you can refer to them. Newer ones replace \
            older ones; uninstalling removes them.
            - After the app stopped unexpectedly (a crash, or Android found it not responding), a short summary of \
            each stop in the last 24 hours: the kind of stop, the minute (UTC), whether the app was in front, and \
            where in the app's code it happened (the error's type and the top of its stack, or the top of the main \
            thread for a stop for not responding). Never an error's message, a trace's other text or anything read \
            from a screen. It is kept for 24 hours; Clear history removes it.
            - With **Share anonymous diagnostics after each dash** on: counts of the current dash's problems by \
            fixed category, and the masked read lines of up to six recent problems, until that dash's summary is \
            queued. Turning the option off or Clear history removes them.
            - Your Day, Night, System or Auto theme choice. Auto is the default for new choices; it calculates the \
            local sun cycle using only an existing permitted approximate location, without a network request or a \
            separate saved location. When none is available it uses a disclosed local-clock fallback: day from 6 am \
            to 6 pm. Existing explicit day/night choices are preserved. Also where you moved the filter tab over \
            Dasher, and whether one-time hints, such as the split-screen divider hint, were shown.
            - While it has turned media or alarm sound down during a decline, the level to put back, so the sound \
            is restored even after a crash; it goes once the sound is back.
            - Your other switches (such as Peek, the offer map and the sound turn-down), which notice you accepted \
            and when, and small flags about what it has already done (one-time cleanups, the paused reminder), as \
            numbers and fixed words.
            - During Peek, the previous app's identifier and launcher component stay only in memory. They are \
            cleared when Peek ends without a return, when a return fails, or after the return check (up to 1.5 \
            seconds after returning), with one exception. When Peek leaves Dasher up, or a card's tap opens Dasher, \
            from a navigation app, that app's identifier and launcher component stay in memory for the Back to map \
            button until it has shown for 12 seconds, the offer did not end within 90 seconds, an offer is \
            accepted, a delivery starts, the phone locks or the screen goes off, another app comes in front, you \
            tap it, auto-decline is paused, or screen reading stops. A peek held for the unlock keeps them at most \
            60 seconds. An offer kept for the unlock holds no app at all: only its notification's key and post \
            time, the store name it shows and its card, in memory until the next unlock (looked at then only if its \
            notification is still up and at most 40 seconds old), or until you tap one of the app's cards, a newer \
            offer takes its place or screen reading stops. The previous app's identity is never saved or sent; Peek \
            and Back to map logs describe only the kind of previous app, such as a navigation app or the home \
            screen. Its screen text is never kept.

            Item counts on shopping offers are kept only as offer figures; no rule uses them. An unread declared \
            count stays unknown; no quantity is inferred from product names, unique-item counts, stops or orders. \
            These numeric item facts can appear in the reports and diagnostics you choose to send, like the other \
            offer figures.

            0.5.0 deletes the learned minimums and the held hand-decline record from the phone the first time it \
            runs: what older versions' adaptive minimum learned from offers you accepted or declined (their pay and \
            rates, and when learning was on or reset), the record of offers you declined by hand, and the retired \
            rules (per stop, per item, the hotspot distance, the minimums percentage and score by area), after \
            building per stop and the minimums percentage into your minimums. Until you close the one-time note \
            about this change, a summary of it (your old and new minimums, as numbers) stays with your rules. \
            Decisions an older version recorded keep the reasons and notes they were recorded with, which can name \
            an amount its adaptive minimum had learned (such as "must match best accepted $0.45/min"), until newer \
            decisions replace them or you clear history; like the rest of the history, they can go in a report you \
            share or send. Log lines it wrote about what it learned age out with the logs within 24 hours.

            Decision and confirmation lines take priority over repeated scan timings within the existing log \
            limits. Reports include numeric counts of discarded log lines, so missing evidence is visible. Atomic \
            history writes keep a recovery copy only while replacing the same bounded history.

            Screen text is masked before it is kept: customer names, your own name, street addresses, city, state \
            and ZIP lines, apartment numbers, phone numbers, email addresses, delivery instructions, card numbers \
            and their security codes/expiry/PIN, and streets in navigation become [name], [address], [phone], \
            [email], [instructions], [card] and [street]. Store names, offer figures, buttons and shopping items \
            stay. Offers are judged from what is on screen; only what is kept is masked. Click diagnostics retain \
            the control shape and action category, never its text labels.

            Masking is pattern-based and can miss unfamiliar wording. It is not a guarantee of anonymity or removal \
            of every personal detail. Review what you send with Preview first. What you type in feedback, and notes \
            you type with Report this offer, are not masked; do not include customer, payment or account details. \
            Diagnostics and offer reports waiting to send are masked again with the current rules immediately \
            before sending.

            Versions before 0.5.0 could connect to GitHub and file problem reports and diagnostics there, as issues \
            in the project's GitHub repository (<https://github.com/Dillxn/dasher-offer-filter>). That repository \
            is public, so what was filed there could be read by anyone; earlier versions of this text wrongly \
            called it private. This version sends nothing to GitHub. The first time it runs, it deletes any stored \
            GitHub connection, its history, the old report queue and its scheduled jobs, once. A one-time privacy \
            cleanup also clears older diagnostic logs if it has not already completed on this installation. Neither \
            cleanup can remove copies that were already sent.

            Android backup is turned off for this app, so none of this goes into your phone's cloud backup.

            ## Autopilot and your acceptance rate

            Autopilot is off unless you turn it on. While it is on, it moves the bar (how much of your minimums an \
            offer must pay, from 50% to 150%) between offers, only through the screen reader, using your decision \
            history, the wait-estimate records above and the acceptance rate Dasher shows. Turning it off puts the \
            bar back to exactly your minimums. While it is on and Let my minimums grow is on, it also raises your \
            minimums, at most 10% at a time, once the bars it recorded in your decision history have stayed at 103% \
            or more for 30 offers over at least 2 days; it reads nothing else for this, and nothing of what you \
            accepted or declined.

            - What is kept. Only the latest acceptance-rate reading: a whole percent, when Dasher showed it, and \
            the offer it was shown for as a numeric fingerprint (pay in cents, miles, minutes, stops and, for \
            shopping offers, the item count). It is kept whether Autopilot is on or off. A reading older than 7 \
            days is never used or sent: it is deleted the next time the app looks at it (when you open the app, \
            Autopilot plans or a report is built), and until then stays on the phone unused. A percentage counts \
            only when exactly one stands beside acceptance-rate wording on Dasher's decline question; no text of \
            the question is kept with it.
            - When Dasher says declining an offer does not lower your acceptance rate, that offer's line in the \
            decision history is marked so, and Autopilot leaves that offer out of its acceptance-rate count (unless \
            Dasher says so of most of your recent declines, which Autopilot then takes as Dasher's general \
            wording).
            - Autopilot also keeps a note of its last change (time, old and new bar, a fixed reason), when the bar \
            last rose, a pending reason for its next change (a fixed word, such as that you turned it on) and when \
            it last noted that Dasher's "does not lower" wording looked general, and, while it is on, its working \
            figures: whether it is recovering toward your goal, a correction of at most 10 points and, for that \
            correction, the acceptance rate it carried forward from Dasher's reading at its last checkpoint \
            (replaced after 25 more counted offers, and dropped when you change the goal or turn Autopilot off or \
            on). All of this is numbers and fixed words, never screen text.
            - When it raises your minimums, Autopilot keeps a note of the last growth: when, by how much, how many \
            offers on how many days it rested on, your minimums and its bar before and after, and whether its note \
            on the homepage (with Undo) is still waiting. Undo removes it; tapping OK keeps it for Autopilot's \
            details. Numbers only.
            - The 24-hour logs note each new reading, and Autopilot's plans with the acceptance rate and the offer \
            rate (offers an hour) they count with, as numbers, each growth of your minimums and its Undo (your \
            minimums and the bar before and after), and when Let my minimums grow is turned on or off; as for other \
            recognized screens, they can also hold the decline question's masked words with its percentage.
            - Where it goes. Your acceptance rate leaves the phone only in a report you share (**Share report**), \
            in masked diagnostics you attach to feedback, and in **Report this offer**, which sends Autopilot's \
            state and the acceptance rate it counts with. It is never in the summary after each dash: that summary \
            carries Autopilot's bar and why it changed (never your acceptance rate), so a reason such as \
            "acceptance rate below your goal" can show it was below your goal, never what it was. The fingerprint \
            kept with a reading never leaves the phone. The mark that declining an offer does not lower your \
            acceptance rate is part of that offer's history line and goes with it in Share report, attached \
            diagnostics and the summary after each dash; Report this offer leaves it out.
            - Clear history removes the reading, Autopilot's working figures, its last-change note, the note of its \
            last growth (and with it the Undo) and its other notes above. Your rules and Autopilot's settings stay.

            ## What leaves the phone, and when

            - Update checks. The app asks its public update server (dash-offer-filter-build.onrender.com, hosted by \
            Render) for the latest version and downloads it. No account is required. These requests carry ordinary \
            internet connection metadata such as your IP address and the app's own request information, but no \
            offer history.
            - **Send anonymous feedback**, only when you tap Send: the category you pick, what you type (at most \
            4,000 characters), and the app's version. With **Attach masked diagnostics** on (off by default, chosen \
            for each submission), it also sends the masked diagnostic report Share report builds: the Android \
            version and phone maker, the app's readiness, settings and last status line, the offer map's counts \
            (never a position), your current rules and Autopilot's state, including the latest acceptance-rate \
            reading, the decision history, the state of updates, the latest states, the bounded masked logs \
            described above and any recent stop summary. Long diagnostics go in at most 4 parts; Preview shows \
            exactly what will be sent.
            - **Report this offer**, only when you tap Send: what went wrong (Misread, Wrong decline, Wrong accept \
            or Other), your optional note, and that offer's report: its figures, decision and outcome, the bar it \
            was judged at, its masked read lines (with every word that is not offer vocabulary reduced to its \
            shape), your current rules and Autopilot's state (on or off, goal, bar, mode and last change, and the \
            acceptance rate it counts with: Dasher's latest, carried forward, or its own estimate), the app's and \
            Android's versions (not the phone's maker) and the minute it was decided. With Autopilot's state go \
            whether it is recovering toward your goal, its correction and, for Dasher's own reading, how many \
            minutes ago Dasher showed it. The dialog says what is sent; tapping Send is your choice to send it.
            - **Share anonymous diagnostics after each dash**, only if you turn it on in Settings (it starts off, \
            and no update turns it on). After each dash ends (Dasher shows the dash ended, you end it in Dasher \
            with its End dash, or nothing of the dash was seen for 30 minutes), it sends one masked summary of at \
            most 30,000 characters: the app and Android version and phone maker; how long the dash lasted (to the \
            nearest 5 minutes) and how it ended; your switches and which rules are set, and whether screen reading, \
            background offers and alerts were ready; Autopilot's bar and why it changed (never your acceptance \
            rate); counts of offers by result, action and outcome; unreadable offers; declines still showing, by \
            stage; decline-error recoveries; automatic accepts not sent, and why; the types of screen-read and \
            notification errors with the place in the app's code where they happened; how Dasher's window was laid \
            out; what Peek did, by fixed category; that dash's decisions, with times counted from the dash's start; \
            masked log lines only around those problems; and any recent stop summary. Never coordinates, place \
            names, or an install or device identifier. At most one a dash and three a day. Turning it off discards \
            summaries not yet sent.
            - After a crash or a stop for not responding: with diagnostics after each dash on, the stop's summary \
            goes with the next one. Otherwise the homepage shows "{app} stopped unexpectedly last time" with Send \
            report, which opens the feedback dialog with Bug chosen and diagnostics attached, for that report only; \
            nothing is sent unless you tap Send.
            - Where feedback goes. All of the above go only to the project's own feedback service, a Supabase Edge \
            Function reached through Cloudflare. Every submission carries a fresh random token, used only to tie \
            its parts together and store each once; it is new for each submission and not tied to your phone. The \
            service stores each part as sent, with its kind, category, app version, whether it came from the app or \
            the website, and when it arrived: no account, name, email, IP address, user agent, install or device \
            identifier. To limit how often one network can send, it counts recent submissions under a one-way value \
            made with a secret key from the network's address and the date, so the value changes every day; the \
            address itself is not stored. Counts older than two days are deleted as new submissions arrive. \
            Cloudflare and Supabase necessarily receive ordinary connection metadata, such as your IP address, \
            while handling a request and may keep infrastructure logs under their own policies. The developer can \
            see the request logs Supabase keeps for the project, which can record each request's IP address and the \
            app or browser that sent it, for as long as Supabase keeps them. So this is not a guarantee of \
            anonymity.
            - Submissions are kept on the feedback service for 90 days, then deleted. The developer reads them, and \
            may use AI systems (Anthropic's Claude or OpenAI's ChatGPT/Codex) to investigate problems with them. A \
            submission does not guarantee review or a fix.
            - Waiting and retrying. If the phone is offline, the service is busy or too many submissions came from \
            your network recently, a submission waits on the phone (see above) and Android sends it later, once a \
            network is available; the app also sends what waits when it next opens. Nothing is sent unless the \
            current notice is accepted, and an after-dash summary only while that option is still on; both are \
            checked again before every part.
            - **Share report**, only when you tap it, hands a masked report to the app you choose: the same report \
            attached diagnostics carry, with the latest acceptance-rate reading. From there, that app handles the \
            copy under its own privacy practices.
            - Place names. To name the squares on the map and the place you are in, the app asks Android's own \
            place lookup (Google's servers on most phones) about positions rounded to about half a kilometre, the \
            phone's own approximate position among them, once while a position remains cached. An evicted or \
            cleared position may be looked up again. Nothing about your offers goes with them.
            - Navigation, only when you tap it. Opening an offer area hands that historical area's center \
            coordinates to your chosen Maps or Waze app, or to Google Maps in a browser. Gas and gas-price choices \
            hand a search phrase to the map provider. A Back to map tap opens your navigation app the way its own \
            icon does. The receiving app or website handles the request under its own privacy practices; these \
            actions do not upload your offer history or establish your arrival.
            - Help, only when you tap it in Settings: your browser opens the install and setup page on \
            offerfilter.org, and that website receives the ordinary information any web request carries.
            - The Jesus Loves You signature at the end of Settings, only when you tap it: your browser opens \
            jesuslovesyou.xyz, and that website receives the ordinary information any web request carries. The app \
            sends nothing with the tap.
            - Tips. The Cash App, Venmo and PayPal links open only when you tap them; the app sends nothing for \
            them and counts nothing.
            - Web and email addresses in these texts open in your browser or email app only when you tap them.

            Render, Cloudflare, Supabase, Google, Anthropic and OpenAI, and the email services that carry a message \
            you send to the privacy contact below, handle what reaches them under their own terms and privacy \
            policies. Copies you deliberately share through another app are controlled by that app and recipient.

            ## What it never collects

            The app does not request your DoorDash password, contacts, photos, messages, precise location or \
            payment details. Accessibility can expose unrelated text when Dasher displays it; recognized payment, \
            account and earnings screens are discarded, and the masking described above is applied before \
            diagnostic text is saved.

            ## Your choices

            - Turn off **Peek at background offers** in Settings to stop automatic temporary opens of Dasher. Peek \
            requires an unlocked, quiet phone (an offer that arrived while it was locked is looked at only just \
            after you unlock, within 40 seconds of it) and skips when its checks find typing, a call, \
            microphone/camera use, split or floating windows, a pinned or unrecognized app, or another conflicting \
            action. Its checks depend on what Android exposes; they are not a safety guarantee.
            - During an active dash, with auto-decline on and a rule set, the screen is kept from timing out \
            whatever app is in front, but only while something of the dash was seen in the last 15 minutes or \
            Dasher shows it, and never with battery saver on or the battery at 20% or less and not charging. It \
            never wakes or unlocks the phone, and the power button still turns the screen off; pausing auto-decline \
            ends the hold. Peek pauses while your phone is locked.
            - Autopilot is off unless you turn it on, and turning it off puts the bar back to exactly your \
            minimums. The acceptance-rate reading is kept whether or not Autopilot is on; Clear history removes it. \
            Turn off Let my minimums grow in Autopilot's details to keep your minimums where you set them.
            - Clear history (Settings) removes the decisions, both logs and the latest states, the observed-wait \
            records, the offer map and cached place names, the temporary restart/error records above, stop \
            summaries, the current dash's problem counts and after-dash summaries not yet sent, plus Autopilot's \
            acceptance-rate reading, its working figures, its last-change note, the note of its last growth and its \
            other notes. Lookups and wait-record writes already in progress cannot restore cleared history. \
            Feedback and offer reports you sent and that still wait to go stay until they are sent or 7 days pass. \
            Your rules, Autopilot's settings and the app's last status line (which can show the last offer's \
            figures until the next status replaces it) remain.
            - Feedback and offer reports leave only after you tap Send. Leave **Attach masked diagnostics** off to \
            send only your words, the category and the app's version.
            - **Share anonymous diagnostics after each dash** is off unless you turn it on, and you can turn it off \
            at any time.
            - Uninstalling removes the app's local data, including submissions still waiting and the kept \
            references. It does not remove submissions the feedback service already received or copies you \
            deliberately shared through another app.

            ## Help and privacy contact

            Use **Send anonymous feedback** in Settings, or the feedback form on offerfilter.org, for non-sensitive \
            feedback without creating an account. Do not type customer, payment, account or other sensitive \
            details. If you need to refer to an earlier submission, keep the short reference shown after it is \
            accepted; Settings' feedback dialog lists the last ten.

            For privacy, data-deletion and security requests only, email privacy@offerfilter.org. Unlike feedback, \
            email is not anonymous: the developer sees your address and uses it only to answer you. Your message \
            passes through your own email provider and the email services that deliver mail for offerfilter.org to \
            the developer. Feedback carries no name or account, so to ask about a submission you sent, include its \
            reference; without it the developer may be unable to tell which anonymous submission is yours. Send \
            everything else as anonymous feedback.

            The project's public issue tracker is suitable only for public, non-sensitive development discussion; \
            posts there are public. Do not attach diagnostic reports, customer details, payment or account \
            information, addresses, screenshots containing personal information, or tokens.

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
