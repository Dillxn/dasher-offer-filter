# Offer Filter privacy

Draft of 6 October 2026. Not legal advice; have a lawyer review before public release. No attorney review is claimed; a private privacy contact is not yet configured.

Offer Filter does not require an Offer Filter account and has no ads or analytics. It does not sell or rent your data. Offer reading and decisions happen on your phone. Update requests, optional reports, place-name lookups and links you choose to open can send information to the services described under What leaves the phone. The developer's public project identity is Dillxn.

## What it reads on the phone

- Dasher's screen, through Android's accessibility service, while Dasher is on screen or in its half of a split screen: each offer's pay, miles, time, stops and displayed total item count, and the other text Dasher shows, which can include store names, customer names and addresses.
- Dasher's notifications, through Android's notification access. Android shows the app every notification; it ignores every app but Dasher.
- Dasher's short error messages (toasts), through accessibility, only to recognize a failed decline. Their wording is not stored or sent; recovery logs keep a fixed error category and attempt counts.
- Peek, on by default after the current notice is accepted, can briefly bring Dasher forward to read a fresh background offer. To return you afterward, it checks Android's window information and the app identifier of the app previously in front. It does not read that app's text. It also checks screen lock, keyboard and window state, call/audio mode, and Android's available microphone/camera-in-use indicators; it does not record audio or images.
- Approximate location, only if you allow it, for the offer map. Auto theme can use the same existing, fresh approximate location while the offer map is enabled, to calculate sunrise and sunset on the phone. Theme selection never requests a new location fix or precise location.

Before you accept the current first-run notice, it reads none of this and sends no reports.

## What it keeps on the phone

- Your rules, your separate auto-accept choice (off by default), and what the adaptive minimum learned from your own accepted or declined offers. Automatic accept requests do not train it.
- The latest 200 decisions: when, the pay, miles, minutes, stops and total items read, whether the offer declared items or shopping, the result, what the app did, and a few masked lines read from the offer. This history has no age-based expiry: entries remain until newer decisions replace them or you clear history or uninstall. Aggregate outcome counts are kept separately until you clear history or uninstall. The decision format also supports a numeric final-stop-to-hotspot distance, without coordinates or addresses. This candidate has no automatic reader for that distance and displays it as unavailable; it does not obtain it from your location or the offer map.
- Up to 200 numeric wait-estimate records in a rolling 24-hour window, pruned when used: the observation time, positively observed waiting duration and any observed offer's pay, miles, minutes, stops, item count/applicability and numeric hotspot distance if ever available. These separate records contain no merchant names, screen text, addresses or coordinates and are never included in reports or uploaded. Only visible, unlocked, eligible waiting is measured; rejected-offer handling counts only after a later recognized wait or standalone offer confirms it continued. Hidden, unknown, delivery, locked, paused and stopped-service time is excluded. A restart does not resume an old waiting timer. The estimate rechecks these numeric offers against your current rules and stays unavailable until enough readable observations exist; old decision timestamps alone are not treated as waiting time.
- Two rolling logs of screen text, one for offers and the app's status and one for other dash screens. Other screens are captured only during a dash or within ten minutes of a readable offer, and only when positively recognized as offer, confirmation, idle, delivery or navigation screens. Unrecognized/partial screens retain only a generic text-not-kept note. Each holds about what one report carries, with a 24-hour retention window. Older entries are pruned during app use and when the logs are read; files can remain while the app is stopped or a storage operation fails. Screens recognized as payment, account or earnings pages are discarded; at most one line a minute records that a screen was not kept.
- The offer map, if it is on and location is allowed: up to 300 historical squares of about 2 km where offers came in, with no age-based expiry. The separate place-name cache holds at most 300 rounded positions, also without age-based expiry; older names are removed as others are used. Clear history or uninstalling removes both.
- While you are dashing: whether a dash is on, and the current route's pay, miles, time, stops and observed item count/applicability, for at most three hours since the route was observed.
- One temporary record that prevents automatic taps on a possibly taken-over or automatically accepted offer after a restart: numeric pay, miles, minutes, stops, observed item count/applicability, countdown, and device uptime/boot number. It can suppress taps for at most two minutes; the app discards it when it next checks an expired record, observes the offer ended, or you clear history. It never restores permission to tap. One scanner-error category, without the error message or screen text, is kept until the next service connection or Clear history.
- A separate temporary automatic-Accept request record keeps only numeric offer facts and device uptime/boot number, for up to two minutes. It only prevents an automatic choice from training personal minimums; it never proves acceptance or restores tap authority. Clear history removes it.
- A pending hand-decline observation can retain the same numeric offer facts, including observed item count/applicability, to reconcile its outcome, for at most one hour.
- A GitHub connection, if you set one up, and the state of updates.
- Up to 30 queued reports, if reporting is enabled. A queued report is a separate copy of the report as built, including any history, diagnostic text or note it contains. Queued reports have no age-based expiry and can remain pending after an outage, a permission failure or an uncertain send. They are removed when delivery finishes, when the app drops an invalid report, or when you turn the corresponding reporting option off, disconnect GitHub or uninstall. The logs' 24-hour window does not expire these copies.
- Your Day, Night, System or Auto theme choice. Auto is the default for new choices; it calculates the local sun cycle using only an existing permitted approximate location, without a network request or a separate saved location. When none is available it uses a disclosed local-clock fallback: day from 6 am to 6 pm. Existing explicit day/night choices are preserved.
- During Peek, the previous app's identifier and launcher component stay only in memory. They are cleared when Peek ends without a return, when a return fails, or after the return check (up to 1.5 seconds after returning). They are never saved or sent; Peek logs describe only the kind of previous app, such as a navigation app or the home screen. Its screen text is never kept.

Pay per item uses only an explicit, unambiguous total count on an offer declaring items or shopping. An unread declared count stays unknown; no quantity is inferred from product names, unique-item counts, stops or orders. These numeric item facts may appear in the same opt-in/shared reports as the other offer figures.

Decision and confirmation lines take priority over repeated scan timings within the existing log limits. Reports include numeric counts of discarded log lines or queued reports, so missing evidence is visible. Atomic history writes keep a recovery copy only while replacing the same bounded history.

Screen text is masked before it is kept: customer names, your own name, street addresses, city, state and ZIP lines, apartment numbers, phone numbers, email addresses, delivery instructions, card numbers and their security codes/expiry/PIN, and streets in navigation become [name], [address], [phone], [email], [instructions], [card] and [street]. Store names, offer figures, buttons and shopping items stay. Offers are judged from what is on screen; only what is kept is masked. Click diagnostics retain the control shape and action category, never its text labels.

Masking is pattern-based and can miss unfamiliar wording. It is not a guarantee of anonymity or removal of every personal detail. Review a report before sharing it. Notes you type with Report this offer are not masked; do not include customer, payment or account details. Queued diagnostic bodies and comments are masked again with the current rules immediately before sending.

A one-time privacy cleanup clears older diagnostic logs and unsent reports if it has not already completed on this installation. It does not run again merely because this notice changes. If that cleanup cannot finish, diagnostic reads, writes and report sending remain paused until it succeeds. It cannot remove reports already sent to GitHub or another app.

Android backup is turned off for this app, so none of this goes into your phone's cloud backup.

## Local cost estimates and optional adjustment

You may enter a driving cost per mile, calculate it from your own fuel price, observed fuel economy and wear allowance, and manually record a reported acceptance rate. Blank cost means unknown; zero is used only when you explicitly enter it. The app does not automatically read your acceptance rate or obtain fuel prices. Cost settings and bounded automatic-adjustment preferences stay on the phone until changed or the app is uninstalled. Up to 200 manually entered acceptance-rate snapshots are kept for 30 days, pruned during use. A reported rate is an observation, not proof of how DoorDash dispatches offers.

Estimated net rates reuse the separate bounded numeric offer/wait history above. They subtract entered per-mile costs and use displayed route times plus observed eligible waiting; they do not measure completed earnings, unpaid return trips or all working time. The AR snapshot date may select a sufficiently supported observation cohort; its numeric value is not a causal dispatch multiplier. One last-adjustment record stores the adjustment time, latest sampled arrival time, encoded rule/configuration values (including percentage, selected bounds and entered cost), and boot/elapsed cooldown markers until replaced, cleared or uninstalled; it cannot restore tap authority. These settings, AR snapshots, estimates and adjustment records are not added to diagnostic logs, reports or uploads.

Automatic adjustment starts off and requires a separate confirmation. It changes only the shared minimums percentage, within your selected bounds, by at most five percentage points and no more often than once per fifteen real minutes after enough fresh evidence. It acts only between offers during positively observed visible, unlocked waiting. Saved and learned floors are preserved. A manual percentage change turns it off. Clear history removes its AR and adjustment records as well as observed-wait history, while cost and preference settings remain.

## What leaves the phone, and when

- Update checks. The app asks its update server (dash-offer-filter-build.onrender.com, hosted by Render) for the latest version and downloads it; with GitHub connected, it also asks GitHub for the update files in the app's repository. These requests carry your IP address, as any internet request does, and the app's own name, and nothing about your offers. The app checks by itself, every so often and when it opens.
- GitHub connection, if you choose it. Signing in uses GitHub's device sign-in. The token stays on the phone and goes only to GitHub: its sign-in pages, the app's update files, one look-up of your GitHub account name to show in Settings, and, if you also turn on Send problem reports, the app's private issue list.
- Reports, only if you turn them on: Send problem reports in Settings (shown once GitHub is connected; they go only through that connection). Problem reports go to the developer's private GitHub repository as issues: when an offer cannot be read, when the app hits an error or a decline does not finish, and when you tap Report this offer. Every word that is not part of an offer's wording (pay, miles, buttons and the like) is masked first, and a problem report never carries the logs. A note you type with Report this offer is sent as you typed it. Issue and comment parts carry opaque receipt identifiers; uncertain sends are reconciled against those receipts before proceeding, and can remain pending rather than being sent twice.
- Diagnostics after each dash, only if you turn them on (off by default; they need Send problem reports on). After a dash ends, the same masked report that Share report makes is filed to that private repository, at most one issue per detected dash and six times a day. Each dash has a random receipt identifier. If GitHub's reply is lost, the app looks for that receipt in the repository before continuing; it never creates another issue while the first request remains uncertain. Reports may remain pending after a lost reply.
- Share report, only when you tap it: the masked report goes to the app you choose, and from there wherever you send it.
- A report you share and the diagnostics after each dash also name your phone's Android version and maker (for example "Android 15 (API 35), Google"); problem reports do not.
- Place names. To name the squares on the map, the app asks Android's own place lookup (Google's servers on most phones) about positions rounded to about half a kilometre, once while a position remains cached. An evicted or cleared position may be looked up again. Nothing about your offers goes with them.
- Navigation, only when you tap it. Opening an offer area hands that historical area's center coordinates to your chosen Maps or Waze app, or to Google Maps in a browser. Gas and gas-price choices hand a search phrase to the map provider. The receiving app or website handles the request under its own privacy practices; these actions do not upload your offer history or establish your arrival.
- Tips. The Cash App, Venmo and PayPal links open only when you tap them; the app sends nothing for them and counts nothing.

Reports in the repository may be read by the developer and by AI systems (Anthropic's Claude and OpenAI's ChatGPT/Codex) that the developer uses to investigate problems. A report does not guarantee review or a fix. GitHub, Render, Google, Anthropic and OpenAI handle what reaches them under their own terms and privacy policies. Copies already sent to GitHub, an AI service or another app are not governed by the phone's 24-hour log window and are not automatically erased by Offer Filter. A developer-managed retention and deletion schedule for those copies has not yet been confirmed.

## What it never collects

The app does not request your DoorDash password, contacts, photos, messages, precise location or payment details. Accessibility can expose unrelated text when Dasher displays it; recognized payment, account and earnings screens are discarded, and the masking described above is applied before diagnostic text is saved.

## Your choices

- Turn off **Peek at background offers** in Settings to stop automatic temporary opens of Dasher. Peek requires an unlocked, quiet phone and skips when its checks find typing, a call, microphone/camera use, split or floating windows, a pinned or unrecognized app, or another conflicting action. Its checks depend on what Android exposes; they are not a safety guarantee.
- Clear history (Settings) removes the decisions, both logs, the observed-wait records, the offer map and cached place names, plus the temporary restart/error records above. Lookups and wait-record writes already in progress cannot restore cleared history. It does not remove queued reports or copies already sent. Your rules and learned minimums remain; Reset clears the learned minimums separately.
- Turning Send problem reports off, or disconnecting GitHub, stops further reports and discards reports not yet sent. Turning Share diagnostics after each dash off discards unsent diagnostics, but not other queued problem reports. A request already in flight may still arrive; these choices do not delete reports already delivered.
- Disconnecting GitHub (Settings) removes the GitHub token.
- Uninstalling removes the app's local data, including queued reports. It does not remove reports or other copies already shared with GitHub, AI services or another app.

## Help and privacy contact

Non-sensitive questions and bug descriptions can be posted to the project's public issue tracker: https://github.com/Dillxn/offer-filter-site/issues. Posts there are public. Do not attach diagnostic reports, customer details, payment or account information, addresses, screenshots containing personal information, or tokens.

A private contact for privacy, deletion and security requests is not yet configured and needs the developer's confirmation before public launch. The public issue tracker is not a private reporting channel. The app's optional GitHub reporting uses the developer's private repository and requires access to that repository; it is not a general public support inbox. Public update downloads do not require a GitHub connection.

## Children

Offer Filter is not for anyone under 18.

## Changes

When this text changes in substance, the app shows its notice again before it reads anything more or sends reports.
