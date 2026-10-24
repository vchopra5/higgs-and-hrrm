# Memory architecture comparison and evaluation

Implemented October 24, 2026. This compares our code with **documented behavior** of [Sapience: Living Villagers](https://modrinth.com/mod/sapience). Sapience is closed source and All Rights Reserved; this is an independent implementation, not a port or a source-code audit.

## What transfers from Sapience

Sapience describes local Java logic that reads world state and remembered facts before selecting behavior, records outcomes, maintains individual player relationships, and spreads witnessed events locally. Its released dialogue does not use an LLM. These are the author's descriptions, not independently verified implementation details.

| Pattern | Our previous implementation | Decision |
| --- | --- | --- |
| Knowledge informs behavior before action | Voice model could choose a recall tool during its speaking response | Prepare evidence before PTT speech; remove recall from the speaker's tools |
| Individual knowledge and local information spread | Per-villager store, player UUID scope, privacy, original/source villager and hop count | Preserve these boundaries and test them |
| Memory of events with consequences | Short attributed player claims; no claim timestamps in voice context | Expose recorded/learned timestamps; keep claims distinct from verified observations |
| Bounded memory and processing | Bounded store, eight opening memories dominated by interest | Balance recent personal details and salient local knowledge in a bounded working context |
| Actual events shape relationships | Reputation and recent hits live separately from conversational claims | Future work: add server-observed episodes as a separate evidence type; do not manufacture them from dialogue |

We did not infer embeddings, a graph database, a retrieval formula, or a consolidation algorithm from Sapience's public description. Its simulation, fixed local decision logic, family system, and needs model are not required to solve our voice-memory problem.

## Why lookup narration happened

The previous speaker was instructed to call `recall_memories` proactively and then continue speaking. It already had instructions to hide tools and storage details. This put the lookup itself inside the model's spoken action sequence.

The Minecraft PTT path now performs:

1. Commit audio after any interrupted response finishes cancelling.
2. Match the committed item ID to its completed transcript.
3. Send a private `_recall_turn` read through the authenticated game-server queue.
4. Add the returned evidence to the existing conversation as a clearly labeled context item.
5. Request speech with the existing persona, conversation, and trade history intact.

`recall_memories` is no longer advertised to the speaker. There is **no runtime phrase detection, output rewriting, forced dialogue line, or retry based on wording**. The model still chooses what to say. Semantic memory writes remain model-driven through `remember_conversation`; this work does not introduce a background consolidation model.

The context item is explicitly data, not player speech, a new claim, or purchase consent. Only the latest such item is retained; tool continuations do not insert it twice. Initial working context and real conversation history remain available, including when lexical retrieval returns no additional matches. Stale results cannot update a later turn.

Transcription has a 3.5-second deadline and the game read a 2.5-second deadline. After an acknowledged audio commit, failure retains the initial context with an unavailable status. An unacknowledged/rejected commit ends the session safely, because assigning a later transcript to an uncertain earlier commit could corrupt turn identity. No save is acknowledged before persistence succeeds.

Higgs's hands-free VAD mode generates responses automatically; `create_response:false` is documented as ineffective. VAD retains the bounded session-start working context and has **no per-turn retrieval guarantee**. The actual Minecraft client uses PTT. See [Boson's compatibility notes](https://docs.boson.ai/models/higgs-realtime/migrate-an-existing-integration).

Live probes also found that response-level tool/modality overrides were ignored and that putting evidence in `response.instructions` caused repeated greetings instead of answers. The final path uses documented conversation text items and bare `response.create`, avoiding configuration changes during dialogue. Failed experiments are retained with the successful reports, rather than counted as successful validation. See [Boson's client-event API](https://docs.boson.ai/api-reference/realtime/client-events).

## Retrieval evaluation

The new retriever uses BM25 with ordinary Unicode normalization, conversational stop words, and existing attribution names. It calculates corpus statistics **after** authorization filtering. Retrieval relevance takes precedence over gossip interest. No domain-specific synonym dictionary, canned answer, or benchmark-specific query rule was added.

The initial working context includes up to 16 recent personal memories and fills up to 32 with salient authorized local knowledge, subject to a serialized byte budget. Existing memory files require no schema migration.

The original synthetic suite is inspired by the extraction, cross-session, temporal, update, and abstention tasks in [LongMemEval](https://arxiv.org/abs/2410.10813) and evidence-linked questions in [LoCoMo](https://arxiv.org/abs/2402.17753). These are **not official benchmark scores**. The benchmark uses the real store and its persistence/retrieval path; the baseline reproduces the previous token-overlap scorer. Both arms have the same records, scope, query, clock, and eight-result limit.

| Metric | Previous | New |
| --- | ---: | ---: |
| Top-1 evidence accuracy, 35 answerable queries | 65.7% | 88.6% |
| Mean reciprocal rank | 0.717 | 0.910 |
| Evidence recall at 3 | 77.1% | 94.3% |
| Correct abstention, 9 unanswerable queries | 55.6% | 100% |

All 44 cases, including failures, are in [the retrieval report](benchmarks/memory-retrieval-v1.json). All 16 privacy, player/villager isolation, attribution, persisted-only reads, restart, expiry, and context-budget invariants passed. The suite is a development regression benchmark, not a held-out estimate of general performance.

Four diagnostic cases remain unsuccessful: unseen semantic paraphrases and questions requiring historical-state reasoning. BM25 is lexical retrieval. It does not infer synonyms or reconcile contradictory claims. Timestamps make such reasoning possible for the reader but do not solve it in the index.

## Live dialogue and audio evaluation

The dialogue experiment uses 12 frozen synthetic cases and the same Higgs model, voice, initial evidence, and provider-default sampling in both arms. Pair order alternates. The baseline preserves the pre-change prompt including existing workspace edits. Raw speech, including pre-tool narration, is retained. Factual and narration indicators are evaluation code only and never participate in production response generation.

See [the experiment instructions](../backend/bench/README.md), [raw dialogue results](../backend/bench/results/memory-dialogue.json), and [separate transcript review](../backend/bench/results/memory-dialogue-review.json). Automatic lexical scores and unblinded transcript review are reported separately; neither is presented as an independent blinded evaluation.

The final run completed all 24 generations without provider errors and used the production context-delivery method. Two agent reviewers examined all transcripts:

| Transcript-reviewed measure | Previous | New |
| --- | ---: | ---: |
| Spoken lookup narration, all cases | 5/12 | 0/12 |
| Spoken lookup narration, ordinary questions | 4/11 | 0/11 |
| Supported requested answer or appropriate abstention | 12/12 | 12/12 |
| Any unsupported factual addition, strict reading | 1/12 | 1/12 |

The strict addition in both arms assumes a character's gender; the new arm also turns an employment location into current physical presence. The requested answers are correct. Some new-arm answers still refer to “records” or empty recollections; the measured reduction is lookup narration, not a guarantee of perfectly natural speech. An earlier exploratory run also produced one unsupported hearsay elaboration, retained in `initial-architecture-review.json`.

The frozen automatic narration detector missed the baseline's phrasing. Its unchanged raw score is therefore **not** evidence for this result; the separate review contains case-level judgments and quoted excerpts. Automatic factual rubrics passed 11/12 in both arms, missing one valid abstention phrasing. Small stochastic samples and unblinded agent review warrant caution; there is no claim of statistical significance or universal suppression of lookup narration.

The production PTT probe uses actual synthetic audio, the real bridge and Higgs STT/audio output, and an authenticated simulated game-server read. The requested fact is absent from both the initial prompt and spoken question. This checks that fresh retrieval is actually used, rather than merely appearing in a payload. It does not test the Minecraft microphone, real world store over HTTP, or audible game playback.

Successful probes covered greeting and no-greeting sessions. A second audio turn received an empty retrieval result and correctly answered “It was blue” from the retained conversation after the previous evidence item was deleted. All 18 checks in [the two-turn report](../backend/bench/results/production-memory-ptt-context-continuity.json) passed. Across successful probes, preparation took 288–359 ms and first audio arrived 710–902 ms after commit; these few synthetic measurements are not a latency SLA.

Validation: all 68 backend tests passed; the full offline mod build passed, including the retrieval benchmark and Java regression suites. The code and built JAR are ready for installation; running game/server processes were not restarted.

## Reproduce

```sh
npm --prefix backend test
./scripts/build-mod.sh --offline --no-daemon
node backend/bench/memory-dialogue.mjs --live --out=backend/bench/results/memory-dialogue.json
node backend/bench/production-memory-ptt.mjs --live --greet --follow-up
```

Live commands use the configured Boson API key and incur ordinary provider usage; only synthetic fixture content is sent. The default dialogue command without `--live` prints the fixture manifest without network calls. Build checks run `benchmarkMemory` and write `mod/build/reports/memory-benchmark.json`.
