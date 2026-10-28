# Frozen full-comparison protocol

Prepared before full-study inference on October 28, 2026 (America/Toronto). The report records UTC execution timestamps, exact source hashes, and source snapshots. This protocol supersedes pilot scoring; pilot outputs are not pooled into the full study.

## Objective and sample

Measure recorded-prefix interruption recovery and its change with prior user-turn count in a Minecraft villager workflow. Compare `higgs-realtime` with `openai/gpt-audio` via OpenRouter. Use six interruption types, three templated quest variants, depths 2/6/12/20, complete-speech controls and interrupted-prefix conditions, and two repeated samples: 576 planned trials (288 per model, 144 interrupted per model). Each model/type/depth/arm cell has six trials. This is a controlled domain-specific application benchmark, not an official IHBench result, live barge-in measurement, or model-only causal comparison independent of APIs.

Use the same production villager instructions/tool schemas, matched current game state, fixed histories, and identical synthesized user audio across matched conditions. Only the last user turn is audio; historical turns are text. Retain the heard prefix only in interrupted histories. Extra depth adds benign small talk; the historical stash fact becomes more distant. The experiment does not independently identify token length, fact distance, turn count, or added content effects. The three quest variants share a template, not three independent real-world domains.

Freeze sources and source hashes before inference. Randomize the 288 matched two-provider blocks by SHA-256(seed:fixture/repetition), then randomize provider order within each block using a second hash, with seed 260628 and eight concurrent workers. This guarantees balanced dispatch throughout. A prior partial run was invalidated for a biased modulo-based shuffle; its outcomes are excluded and preserved separately. The runner may retry a transport/API error twice, logging every attempt; never retry a completed behavioral failure, tool-limit outcome, or empty-output outcome for a better score. A judge request/validation failure may be retried once. Preserve unfinished/error cases and report their denominators. Stop dispatch on explicit authentication, credit, or invalid-request errors. Resume only with matching frozen source/configuration.

## Scoring fixed before the run

Primary task success requires both a semantic task-fulfillment pass and the exact action checks. Primary recovery success requires both an all-criteria semantic recovery pass and the same action checks. Exact checks reject purchase attempts without consent, new quest issuance, unrequested quest completion, missing required cancellation, wrong corrected-item proposal, or unsuccessful librarian lookup. Lexical exact-match checks are descriptive aids, not gates on semantic scoring.

Judge: `anthropic/claude-sonnet-4.6`, temperature 0, JSON verdict with criterion evidence. Strip candidate/provider identity and canonicalize historical/generated tool IDs. Give the judge explicit chronological speech/tool/result events, initial/final game state, task requirements, and type-specific recovery criteria. For filler, concatenate delivered prefix with new speech and accept grammatical/paraphrased continuations; require the remaining item and price. Treat asking for consent separately from executing a purchase. A refused quote and a preserved earned entitlement are distinct. Use no full-study result to alter the judge.

Calibration before the run: 24 assistant-authored synthetic reference cases (12 development and 12 holdout, positive/negative pairs for all six types), with all expected two-axis labels matched; additionally rerun four real disputed pilot outputs. Neither set is independent human annotation. Pilot exposure influenced the repairs; disclose that. No claim that calibration proves general judge validity.

## Analysis planned before outcomes

Report exact success/failure/error counts, per-model overall and interrupted-only scores, and each type by depth. Show observed depth curves on shared 0–100% axes. Calculate paired 2-to-20-turn change, control-minus-interrupted penalty at each depth, and change in that penalty. Report per-repetition curves as a sampling-sensitivity check. Distinguish operational failures from completed behavioral failures; present intended-denominator rates as well as completed/judged-denominator rates where they differ.

Do not present these repeated templated cases as 576 independent conversations. Do not use naive binomial confidence intervals, unqualified population significance claims, or interpret a fitted slope as causal. The primary evidence is descriptive matched counts. Plot all six types, including flat or improving curves. Report judge errors and calibration limits.

After inference, audit a fixed stratified sample (one interrupted example per model/type/depth = 48 records, first repetition and axe variant) against traces and audio where needed. Preserve the original scores and record any disputed verdicts separately; do not silently tune or rescore the entire run after observing a preferred ranking. If audit disagreement is material, qualify semantic conclusions and foreground exact-action evidence. Any further judge revision requires a separate labeled analysis version.

## Deliverables

Raw outputs/tool traces/judgments and all attempts; hashed input/output WAV assets; source snapshots and reproducible commands; CSV results; depth plots; concise findings and limitations; a short application-ready description of the engineering work and observed findings. No claims of official certification, independent human validation, or in-game end-to-end testing.
