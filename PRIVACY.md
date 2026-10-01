# Offer Filter privacy

Draft of 1 October 2026. Not legal advice; have a lawyer review before public release.

Offer Filter has no account, no ads, no analytics and no server that collects your data. It does not sell or rent your data. What it reads stays on your phone, except in the cases listed under What leaves the phone. [Developer's name and a privacy contact: to add before public release.]

## What it reads on the phone

- Dasher's screen, through Android's accessibility service, while Dasher is on screen or in its half of a split screen: each offer's pay, miles, time and stops, and the other text Dasher shows, which can include store names, customer names and addresses.
- Dasher's notifications, through Android's notification access. Android shows the app every notification; it ignores every app but Dasher.
- Peek, on by default after the current notice is accepted, can briefly bring Dasher forward to read a fresh background offer. To return you afterward, it checks Android's window information and the app identifier of the app previously in front. It does not read that app's text. It also checks screen lock, keyboard and window state, call/audio mode, and Android's available microphone/camera-in-use indicators; it does not record audio or images.
- Approximate location, only if you allow it, for the offer map. It never asks for precise location.

Before you accept the current first-run notice, it reads none of this and sends no reports.

## What it keeps on the phone

- Your rules, and what the adaptive minimum learned from offers you accepted or declined.
- The latest 200 decisions: when, the pay, miles, minutes and stops read, the result, what the app did, and a few lines read from the offer. The decision format also supports a numeric final-stop-to-hotspot distance, without coordinates or addresses. This candidate has no automatic reader for that distance and displays it as unavailable; it does not obtain it from your location or the offer map.
- Two rolling logs of screen text, one for offers and the app's status and one for other dash screens. Other screens are captured only during a dash or within ten minutes of a readable offer, and only when positively recognized as offer, confirmation, idle, delivery or navigation screens. Unrecognized/partial screens retain only a generic text-not-kept note. Each holds about what one report carries and nothing older than 24 hours. Screens recognized as payment, account or earnings pages are discarded; at most one line a minute records that a screen was not kept.
- The offer map, if it is on and location is allowed: the square of about 2 km you were in when each offer came in, and place names for those squares.
- While you are dashing: whether a dash is on, and the current route's pay, miles, time and stops.
- A GitHub connection, if you set one up, and the state of updates.
- During Peek, the previous app's identifier and launcher component stay only in memory. They are cleared when Peek ends without a return, when a return fails, or after the return check (up to 1.5 seconds after returning). They are never saved or sent; Peek logs describe only the kind of previous app, such as a navigation app or the home screen. Its screen text is never kept.

Screen text is masked before it is kept: customer names, your own name, street addresses, city, state and ZIP lines, apartment numbers, phone numbers, email addresses, delivery instructions, card numbers and their security codes/expiry/PIN, and streets in navigation become [name], [address], [phone], [email], [instructions], [card] and [street]. Store names, offer figures, buttons and shopping items stay. Offers are judged from what is on screen; only what is kept is masked. Click diagnostics retain the control shape and action category, never its text labels.

This update clears both old diagnostic logs and all unsent reports once. If that cleanup cannot finish, diagnostic reads, writes and report sending remain paused until it succeeds. It cannot remove reports already sent to GitHub or another app.

Android backup is turned off for this app, so none of this goes into your phone's cloud backup.

## What leaves the phone, and when

- Update checks. The app asks its update server (dash-offer-filter-build.onrender.com, hosted by Render) for the latest version and downloads it; with GitHub connected, it also asks GitHub for the update files in the app's repository. These requests carry your IP address, as any internet request does, and the app's own name, and nothing about your offers. The app checks by itself, every so often and when it opens.
- GitHub connection, if you choose it. Signing in uses GitHub's device sign-in. The token stays on the phone and goes only to GitHub: its sign-in pages, the app's update files, one look-up of your GitHub account name to show in Settings, and, if you also turn on Send problem reports, the app's private issue list.
- Reports, only if you turn them on: Send problem reports in Settings (shown once GitHub is connected; they go only through that connection). Problem reports go to the developer's private GitHub repository as issues: when an offer cannot be read, when the app hits an error or a decline does not finish, and when you tap Report this offer. Every word that is not part of an offer's wording (pay, miles, buttons and the like) is masked first, and a problem report never carries the logs. A note you type with Report this offer is sent as you typed it.
- Diagnostics after each dash, only if you turn them on (off by default; they need Send problem reports on). After a dash ends, the same masked report that Share report makes is filed to that private repository, at most one issue per detected dash and six times a day. Each dash has a random receipt identifier. If GitHub's reply is lost, the app looks for that receipt in the repository before continuing; it never creates another issue while the first request remains uncertain. Reports may remain pending after a lost reply.
- Share report, only when you tap it: the masked report goes to the app you choose, and from there wherever you send it.
- A report you share and the diagnostics after each dash also name your phone's Android version and maker (for example "Android 15 (API 35), Google"); problem reports do not.
- Place names. To name the squares on the map, the app asks Android's own place lookup (Google's servers on most phones) about positions rounded to about half a kilometre, each once, and keeps the names on the phone. Nothing about your offers goes with them.
- Tips. The Cash App, Venmo and PayPal links open only when you tap them; the app sends nothing for them and counts nothing.

Reports in the repository are read by the developer and by an AI system (Anthropic's Claude) that the developer uses to find and fix problems. GitHub, Render, Google and Anthropic handle what reaches them under their own terms and privacy policies.

## What it never collects

The app does not request your DoorDash password, contacts, photos, messages, precise location or payment details. Accessibility can expose unrelated text when Dasher displays it; recognized payment, account and earnings screens are discarded, and the masking described above is applied before diagnostic text is saved.

## Your choices

- Turn off **Peek at background offers** in Settings to stop automatic temporary opens of Dasher. Peek requires an unlocked, quiet phone and skips when its checks find typing, a call, microphone/camera use, split or floating windows, a pinned or unrecognized app, or another conflicting action. Its checks depend on what Android exposes; they are not a safety guarantee.
- Clear history (Settings) removes the decisions, both logs and the offer map.
- Turning Send problem reports off, or disconnecting GitHub, stops reports and discards reports not yet sent.
- Disconnecting GitHub (Settings) removes the GitHub token.
- Uninstalling the app removes everything it kept.

## Children

Offer Filter is not for anyone under 18.

## Changes

When this text changes in substance, the app shows its notice again before it reads anything more or sends reports.
