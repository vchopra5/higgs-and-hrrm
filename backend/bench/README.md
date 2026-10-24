# Memory dialogue experiment

This is an original, frozen 12-case Minecraft dialogue experiment inspired by [LongMemEval](https://github.com/xiaowu0162/LongMemEval) and [LoCoMo](https://github.com/snap-research/locomo). It does not report either benchmark's official score.

`baseline-protocol.js` preserves the pre-change protocol captured on October 24, 2026, including existing user edits. Its only dependency-path change points the personality import at the current shared module. The prepared arm imports the current production `sessionConfig` and invokes the actual `BridgeSession.createConversationResponse` method to deliver a labeled recollection item before a bare `response.create`. It retains the prepared-memory object's identity across tool continuations so that evidence is inserted once.

Both arms receive identical initial evidence, with no more than eight memories per case, and use the same model, voice, personality, audio modality, and provider-default temperature. Baseline lookup calls return that same evidence pool. The prepared arm delivers that evidence again through the production method. This isolates dialogue architecture from retrieval quality. The harness alternates pair order, records every speech transcript including speech before tool calls, and never filters or retries output based on its wording.

Preview the fixture manifest without making network calls:

```sh
node backend/bench/memory-dialogue.mjs
node --test backend/bench/memory-dialogue.test.mjs
```

Explicitly run the limited live A/B experiment using `BOSON_API_KEY` and optional `HIGGS_REALTIME_URL` from the root `.env`:

```sh
node backend/bench/memory-dialogue.mjs --live --out=backend/bench/results/memory-dialogue.json
```

For a smoke check, use `--case=personal-building,explicit-lookup-request`. Optional `--repeats=2` or `--repeats=3` repeats each case, and `--arms=baseline` or `--arms=prepared` limits the arms. The prepared arm refuses to run if production PTT still exposes `recall_memories`. Credentials and audio bytes are never included in the report.

The JSON report contains fixture, protocol, production-session, memory-coordinator, and harness hashes, per-case questions, raw transcripts, tool calls and simulated results, usage reported by the provider, latency, errors, and descriptive totals. Ordinary queries and the explicit lookup request are summarized separately. Lexical factual rubrics and process-narration indicators are evaluation aids: review the transcripts before making semantic claims. They are not production dialogue rules.

This uses synthetic text input to an audio-output model and exercises the production evidence-delivery method. It does not validate microphone recognition, the Java retrieval store, PTT transcription/recall coordination, persistence, or in-game playback. Errors remain separate from completed cases. A small successful run is evidence about these examples, not a guarantee about future speech.

`runDialogueCase`, `runBenchmark`, `buildContext`, and `evaluateTranscript` are exported for additional runners, including a future production PTT integration experiment.

## Recorded run, October 24, 2026

`results/memory-dialogue.json` contains all 24 successful generations using the final production delivery method. Both arms passed 11/12 frozen lexical rubrics. Transcript inspection found that both correctly abstained on the remaining case using wording absent from the rubric. All twelve requested answers were supported in each arm. A strict review found minor additions in one case per arm: both assume Nessa's gender, and the prepared arm also states current presence at a known workplace. No substantive invented memory event was found in this run.

`results/memory-dialogue-review.json` records the separate, unblinded assistant review of every transcript, independently checked by a second agent. The baseline narrated retrieval in five cases (four ordinary questions and the explicit lookup request); the prepared arm did so in none. The frozen process regex missed all five phrasings, so its original zero counts are retained in the raw report and are not the basis of that conclusion. The prepared arm still referred to its memory and records when abstaining. This is a small descriptive result about lookup narration, not a statistical guarantee of natural language or improved dialogue quality.

`results/initial-architecture.json` and `results/initial-architecture-review.json` preserve the earlier 24-generation run using prepared initial context alone. That run reduced observed retrieval narration from three cases to zero but included one unsupported prepared-arm embellishment about Mara seeing things beneath the well. It is not pooled into the final comparison and should not be discarded when assessing the system's limitations.

`results/setup-failure.json` retains the initial invalid run that waited only for `session.updated`. Boson's first configuration acknowledgment is `session.created`; the harness now accepts either only when it echoes the requested instructions. `dialogue-smoke.json` retains a sandbox connection failure, and `dialogue-smoke-network.json` is the successful paired smoke test. None of these setup/smoke runs are pooled into the final 24-generation comparison.
