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

            Draft of 1 October 2026. Not legal advice; have a lawyer review before public release.

            ## What {app} is

            {app} is independent software for Android phones. It is not made, endorsed, sponsored or supported by \
            DoorDash, Inc. DoorDash and Dasher are trademarks of DoorDash, Inc., named here only to say which app \
            {app} works with.

            By tapping I understand on the app's first screen, or by using the app, you accept these terms. If you \
            do not accept them, do not use the app: tap Not now, turn off its screen reading and notification \
            access, or uninstall it.

            ## What it does

            - It reads the Dasher app's screen and notifications on your phone, through Android's accessibility \
            service and notification access, which you turn on yourself.
            - It compares each offer with the minimums you set. When an offer is below them, it can tap Decline, \
            and then Dasher's confirmation, for you. It never taps Accept.
            - While it declines an offer on screen, it turns media and alarm sound down for a moment and then puts \
            them back. You can turn this off in Settings.
            - A tap is a request to the Dasher app, not a confirmation from DoorDash. Hiding a notification does \
            not decline an order.
            - It can update itself from its own update feed, and it sends reports only when you turn them on or \
            share one (see the privacy text).

            ## Your responsibilities

            - Your Dasher account and your agreements with DoorDash are yours. Using third-party tools may break \
            DoorDash's terms, and DoorDash could limit or deactivate your account. You decide whether to use {app}, \
            and you accept that risk.
            - Declining offers can lower your acceptance rate, which can affect DoorDash programs that depend on it.
            - Obey traffic laws. Do not handle your phone while driving; set your rules before you drive and pull \
            over to look at offers. {app} is not a safety device.
            - Check your Dasher history. {app} can misread an offer, decline an offer you wanted, or fail to \
            decline one.
            - You must be 18 or older to use {app}.

            ## No warranty

            {app} is provided "as is" and "as available", without warranties of any kind, express or implied, \
            including warranties of merchantability, fitness for a particular purpose, accuracy and \
            non-infringement, to the extent the law allows.

            ## Limitation of liability

            To the extent the law allows, the author of {app} is not liable for any loss or damage arising from \
            your use of it or your inability to use it, including lost earnings, missed, accepted or declined \
            offers, limits on or deactivation of your Dasher account, fines, or lost data. [Liability cap and \
            governing law: for a lawyer to set.]

            ## Licence

            {app} is licensed to you, not sold, under its LICENSE: all rights reserved. Tips are optional gifts to \
            the author and buy nothing.

            ## Changes

            These terms may change. When they change in substance, the app shows its notice again before it reads \
            or declines anything more.

            ## Stopping

            You can stop at any time: pause auto-decline, turn off the app's screen reading and notification access \
            in Android's settings, or uninstall it.
            """;

    private static final String PRIVACY_TEXT = """
            # {app} privacy

            Draft of 1 October 2026. Not legal advice; have a lawyer review before public release.

            {app} has no account, no ads, no analytics and no server that collects your data. It does not sell or \
            rent your data. What it reads stays on your phone, except in the cases listed under What leaves the \
            phone. [Developer's name and a privacy contact: to add before public release.]

            ## What it reads on the phone

            - Dasher's screen, through Android's accessibility service, while Dasher is on screen or in its half of \
            a split screen: each offer's pay, miles, time and stops, and the other text Dasher shows, which can \
            include store names, customer names and addresses.
            - Dasher's notifications, through Android's notification access. Android shows the app every \
            notification; it ignores every app but Dasher.
            - Approximate location, only if you allow it, for the offer map. It never asks for precise location.

            Before you accept the first-run notice, it reads none of this.

            ## What it keeps on the phone

            - Your rules, and what the adaptive minimum learned from offers you accepted or declined.
            - The latest 200 decisions: when, the pay, miles, minutes and stops read, the result, what the app did, \
            and a few lines read from the offer.
            - Two rolling logs of screen text, one for offers and the app's status and one for Dasher's other \
            screens. Each holds about what one report carries and nothing older than 24 hours.
            - The offer map, if it is on and location is allowed: the square of about 2 km you were in when each \
            offer came in, and place names for those squares.
            - While you are dashing: whether a dash is on, and the current route's pay, miles, time and stops.
            - A GitHub connection, if you set one up, and the state of updates.

            Screen text is masked before it is kept: customer names, your own name, street addresses, city, state \
            and ZIP lines, apartment numbers, phone numbers, email addresses and delivery instructions become \
            [name], [address], [phone], [email] and [instructions]. Store names, offer figures, buttons and \
            shopping items stay. Offers are judged from what is on screen; only what is kept is masked.

            Android backup is turned off for this app, so none of this goes into your phone's cloud backup.

            ## What leaves the phone, and when

            - Update checks. The app asks its update server (dash-offer-filter-build.onrender.com, hosted by \
            Render) for the latest version and downloads it; with GitHub connected, it also asks GitHub for the \
            update files in the app's repository. These requests carry your IP address, as any internet request \
            does, and the app's own name, and nothing about your offers. The app checks by itself, every so often \
            and when it opens.
            - GitHub connection, if you choose it. Signing in uses GitHub's device sign-in. The token stays on the \
            phone and goes only to GitHub: its sign-in pages, the app's update files, one look-up of your GitHub \
            account name to show in Settings, and, if you also turn on Send problem reports, the app's private \
            issue list.
            - Reports, only if you turn them on: Send problem reports in Settings (shown once GitHub is connected; \
            they go only through that connection). Problem reports go to the developer's private GitHub repository \
            as issues: when an offer cannot be read, when the app hits an error or a decline does not finish, and \
            when you tap Report this offer. Every word that is not part of an offer's wording (pay, miles, buttons \
            and the like) is masked first, and a problem report never carries the logs. A note you type with Report \
            this offer is sent as you typed it.
            - Diagnostics after each dash, only if you turn them on (off by default; they need Send problem reports \
            on). After a dash ends, the same masked report that Share report makes is filed to that private \
            repository, at most once a dash and six times a day.
            - Share report, only when you tap it: the masked report goes to the app you choose, and from there \
            wherever you send it.
            - A report you share and the diagnostics after each dash also name your phone's Android version and \
            maker (for example "Android 15 (API 35), Google"); problem reports do not.
            - Place names. To name the squares on the map, the app asks Android's own place lookup (Google's \
            servers on most phones) about positions rounded to about half a kilometre, each once, and keeps the \
            names on the phone. Nothing about your offers goes with them.
            - Tips. The Cash App, Venmo and PayPal links open only when you tap them; the app sends nothing for \
            them and counts nothing.

            Reports in the repository are read by the developer and by an AI system (Anthropic's Claude) that the \
            developer uses to find and fix problems. GitHub, Render, Google and Anthropic handle what reaches them \
            under their own terms and privacy policies.

            ## What it never collects

            Your DoorDash password or account details, your contacts, photos or messages, your precise location, or \
            payment details.

            ## Your choices

            - Clear history (Settings) removes the decisions, both logs and the offer map.
            - Turning Send problem reports off, or disconnecting GitHub, stops reports and discards reports not yet \
            sent.
            - Disconnecting GitHub (Settings) removes the GitHub token.
            - Uninstalling the app removes everything it kept.

            ## Children

            {app} is not for anyone under 18.

            ## Changes

            When this text changes in substance, the app shows its notice again before it reads anything more.
            """;

    private static final String LICENSE_TEXT = """
            {app} licence

            Draft of 1 October 2026. Not legal advice; have a lawyer review before public release.

            Copyright (c) 2026 Dillxn, the author of {app}. All rights reserved.

            No licence is granted to copy, modify, publish, distribute, sublicense or sell this software or its \
            source code, in whole or in part, except with the copyright holder's written permission. You may \
            install and use the app as the copyright holder distributes it, on your own devices, under its terms of \
            use.

            THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT \
            LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN \
            NO EVENT SHALL THE AUTHOR OR COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, \
            WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE \
            SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

            DoorDash and Dasher are trademarks of DoorDash, Inc. This software is not made by, endorsed by or \
            affiliated with DoorDash.
            """;

    private LegalTexts() {}
}
