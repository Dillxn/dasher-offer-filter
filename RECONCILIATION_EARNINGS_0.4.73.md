# Concurrent earnings implementation review

Reference branch: codex/earnings-optimizer-0-4-73, reviewed commits 7515bc08e26c3725742d47f278ce7a8a755314b4 and amended c36be8496d3851935b23a29d9dc25c3c1feefa3a. That branch remains intact. It is not a merge parent and is not claimed integrated.

The compatible product aims are a simple offers/pay control, explicit local observations, per-mile costs and optional automatic adjustment. This candidate implements one engine and one set of settings rather than combining two engines with different meanings.

The other implementation's accepted-outcome replay uses DecisionLog.outcome, whose decline-request categories are not always confirmed outcomes, and does not bind outcome step time to the sampled cohort. Its pass share is not observed platform AR or a causal dispatch band. Implicit zero cost would turn unknown cost into an invented profitability assumption. Its wall-clock cooldown, broad immediate retuning and missing final generation/window checks differ from this candidate's between-offer safeguards. Its AR diagnostic logging is excluded here so the newly entered data stays local.

The amended c36 commit removes immediate retuning from enabling the preference. That improvement is acknowledged; it does not resolve the remaining model/evidence and final-guard differences. Neither branch has evidence establishing proprietary DoorDash dispatch causality or a guaranteed earnings optimum.

Disposition: preserve the competing branch as a reviewed independent reference. Do not transplant its engine, diagnostics or immediate-adjustment paths. Keep our explicit unknown-cost state, bounded observed-wait model, manual dated AR history, immutable rules/config/history generations, monotonic cooldown and final current-window checks. Do not imply an artificial merge absorbed every file. Later compatible contributions can be reviewed independently; publication must recheck main and version collisions.
