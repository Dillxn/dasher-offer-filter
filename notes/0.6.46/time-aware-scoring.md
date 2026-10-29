# Optional time-aware scoring

Candidate for 0.6.46 / code 138, based on 0.6.45 (`21a26188660f20fec84126e3f1d6797e19c9fcfc`).

The new **Time-aware scoring** switch is in **How offers are judged** and starts off. It adjusts standalone scores in Strict or Area mode, with or without Autopilot, using offers typically seen near the current local time. A weak hour can lift an offer's score; a strong hour can reduce it. A passing offer still belongs to the user unless it independently qualifies for the existing automatic-acceptance checks.

The model reads the existing last 200 decisions, limited to the preceding 28 days. It uses numeric offer facts, including declines, rescored against the same current saved and enabled learned minimums. Stored historical scores and Autopilot's working shape do not train the time comparison. Paused records, add-ons, skipped stores, apparent misreads, unread scores, future/current-bucket records and duplicates within two minutes do not count.

The nearby window is within 90 minutes of the reference local minute, wrapping around midnight. Reference time advances in 15-minute buckets. At least 30 eligible offers overall and 12 nearby across three local dates are required. Otherwise the multiplier is exactly one. With enough evidence, weighted medians use a seven-day recency half-life:

`factor = clamp(1 + nearbyCount / (nearbyCount + 20) * (overallMedian / nearbyMedian - 1), 0.75, 1.25)`

The factor is quantized to one ten-thousandth and divides exact working requirements before the existing final cent ceiling. Total pay receives the same factor once. Saved and learned baselines stay unchanged. Add-ons, ambiguous-pay ceilings and the separate strict automatic-acceptance checks strip the factor. Max stops remains hard. Unknown facts retain the existing review/known-failure behavior.

Calculation uses Autopilot's existing background worker. A changed factor commits only at the scanner's existing safe point, between offers. It works with Autopilot off; paused/off-dash ticks can refresh the time context without running Autopilot's market planner. Pending personal acceptances retain their original normalization when the clock context changes. Estimates live only in memory; Clear history invalidates pending work and removes the evidence.

Each time-aware decision keeps its numeric factor, sample counts, date count and reference minute. Its details show the multiplier and score before adjustment. Personal acceptance normalization incorporates the factor shown on the offer, before any learner update; retained-history repair uses that same snapshot. Invalid factor data cannot masquerade as an unadjusted reference. The device's current time zone interprets retained timestamps; no historical zone or market location is invented.

Notice 39, the bundled release note, Terms and Privacy describe the feature and retained numeric provenance. Only existing ConsentGateTest and LegalTextsTest version expectations change. No tests were added or restored.

## Local validation

`testDebugUnitTest -PallSdks` and `lintDebug` run locally with JDK 21, Android compile SDK 36 and Gradle 8.13. The retained suite has 320 tests in 13 XML reports, with zero failures, errors or skips. The repository's `sdk_evidence` checker confirms every required Android 15/16 pair. Lint reports zero errors and 38 warnings. A fresh environment first needed a compiler, the platform CA trust store and JDK 21 for Robolectric's Android 16 support; those setup failures are not application test failures.

Disposable worked examples against the compiled production classes cover sparse/stale history, duplicate suppression, the midnight window, opposite adjustments for weak/strong time windows, exact cent cutoffs in Strict and Area, the total-pay reference, personal-acceptance score reconstruction, unchanged add-on requirements, unknown pay, max stops and exclusion of below-minimum offers from automatic acceptance. For example, an $8 offer against a $10 ordinary requirement scores 80%; at a 1.25 time multiplier it scores 100% and remains below the saved minimum for automatic acceptance. In Area mode, a three-axis example preserves its 129% accepted score exactly when its working references are rebuilt.

This is source and local verification for review. No signed release, update-feed publication, deployment, phone installation or real DoorDash behavior is established by these checks.
