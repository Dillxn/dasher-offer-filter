# Adaptive minimums audit — October 4, 2026

Read-only source audit of private app main `5fe1f90abb01b4f84e61efc493ffc8910396eac9` (0.4.63). No app source, model, authority guard or release changed. No tests were rerun because this is analysis of unchanged code.

## Finding

The user's expectation is correct for an eligible confirmed manual acceptance: every newly highest supported rate should be retained, and its learned axis should meet that rate thereafter. Area normalization can keep a learned outline visually near the 100% ring; it cannot explain an eligible, successfully learned Pay/stop remaining below that same accepted offer's Pay/stop at a 100% buffer.

Current code learns only Payout, Pay/mile, Pay/minute and Pay/stop. Pay/item is fixed-only and has no learned storage or purple point. Hotspot is also fixed-only, with no verified automatic measurement. An offer merely qualifying or arriving does not establish a personal choice; automatic accepts intentionally do not teach.

## Matching field report, supplied by the root agent's private issue audit

Issue #45 is a 0.4.62 report for dash marker `1fbfb1f839fbcc90d1d661d1d84dfb54`, generated October 3 at 20:06:29 Eastern. Its matching 19:35:57 offer was $14.70 / 6.8 miles / 33 minutes / 2 stops / 2 items, KEEP, 128% fitness, $11.50 required under area mode. Automatic acceptance was canceled before dispatch (`candidate_replaced`, then `content_changed`). No automatic click was reported for this candidate. The subsequent observations retained only fixed evidence categories, including pay/distance/duration/stops/items and later pay/items. The offer expired from the learning watch after 60 seconds without a recognized delivery or waiting outcome; no acceptance lesson was recorded.

The user's 19:36 visual screenshot contains existing recognized pickup/progress labels. The report does not preserve those labels for the corresponding accessibility read, so it does not establish whether they were exposed to accessibility at that instant.

Important distinction: `PersonalText.recognizedDashScreen` accepts explicit progress and Pick-up-by labels regardless of parsed pay/items. An unknown-screen privacy marker is therefore not, by itself, proof that a recognized pickup screen was rejected because of money. The label sets could have differed across frames or accessibility could have omitted the visible labels.

## Expected lesson if that exact offer had been confirmed manually

| Measure | Reported current minimums | Offer | Expected result |
| --- | --- | --- | --- |
| Accepted payout record | $9.40 | $14.70 | Record rises to $14.70; learned payout asks $14.71. Saved $19.50 remains stronger. |
| Pay/mile | About $4.09/mi | About $2.16/mi | Existing higher best retained. |
| Pay/minute | About $0.59/min | About $0.4455/min | Existing higher best retained. |
| Pay/stop | $4.70/stop | $7.35/stop | Learned rate rises to $7.35/stop. |
| Pay/item | Fixed-only | $7.35/item | No adaptive item lesson exists in the current model. |

The offer's 6.8 miles, 33 minutes and 2 stops satisfy every existing plausibility and rate-setting guard. The report describes rules at generation time, not a reconstructed decision-time baseline. These are counterfactual expected effects under those reported rules, not proof that the phone stored them.

## Code evidence

- `FilterStore.java:247–279`: only learning-enabled manual lessons update independent maxima and report whether a stored record or active floor increased.
- `AcceptedBest.java:67–99`: independent best accepted minute/mile/stop rates; no item field; exclusions below 5 minutes / 0.5 mile / 2 stops, and separate 10-minute / 2-mile thresholds for time/distance rate learning.
- `AreaScore.java:169–196`: accepted payout is highest accepted plus one cent; rate requirements match existing bests; no accepted item component.
- `MinimumsStarView.java:598–621`: four learned axes; hotspot/item supplied with no learned values.
- `MinimumsStarView.java:722–754, 779–824`: offer values and minima share the current area normalization. Purple is learned/current resolved floor; green is offer/current resolved floor. Historical green color is the recorded KEEP decision, not proof of acceptance.
- `AcceptedOfferTracker.java:200–213, 232–246`: new-offer and unresolved numeric facts precede route classification. Item facts alone are already exempted on recognized route screens; any pay, pay bound or stops still makes an offer-shaped read, and non-navigation miles/minutes do too.
- `OfferFilterService.java:4028–4082`: absent controls are not enough; parsed facts are passed into both tap and no-tap outcome classification.
- `OfferFilterService.java:4183–4198`: auto-accept provenance and add-ons are excluded from personal standalone learning.
- `PersonalText.java:257–268`: explicit progress and Pick-up-by labels are sufficient for privacy-safe screen recognition unless an account/payment marker rejects the screen.

## Smallest coherent next work

1. Preserve numeric/identity/countdown/foreground/touch/lock guards. Do not make any route label override arbitrary money: a new offer can appear over route UI, and the same classifier also affects Peek and decline lifecycle.
2. If another diagnostic release is useful, record bounded fixed categories for why post-offer outcome reads were withheld: complete read, controls, countdown/new-offer/confirmation, explicit progress/route/wait markers and numeric fact kinds. No raw text, amounts or new sensitive capture are needed. This distinguishes visible-but-inaccessible progress from classification conflict.
3. A future direct fix for a product price being mistaken for offer pay needs actual source evidence of node provenance. Attribute numerals to positively identified delivery-owned UI, rather than weakening the generic fact guard. Synthetic overlap tests can prove the classifier behavior but cannot prove the real phone's label tree.
4. Adaptive Pay/item would be a separate, explicit feature extension across exact ratio storage, persistence, reset/adopt, shared floors, unknown/inapplicable handling, chart and tests. Merely repainting the purple shape would misrepresent the current scoring model, and this extension would not repair the missing acceptance observation in #45.

Useful regression fixtures if implementation follows: (a) eligible manual $14.70/6.8/33/2 raises stop best $4.70→$7.35 despite lower payout than saved $19.50; (b) a known pickup screen plus unresolved pay stays withheld under current code; (c) fixed category output distinguishes explicit progress present versus absent without changing classification; (d) any future provenance exception keeps new headlines, either offer control, countdown, confirmation, partial reads, mixed waiting/progress and unknown money blocked; (e) autoaccept never trains; (f) area qualification alone never creates a lesson.
