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

## Observed episodes and long-term relationships

The comparison above left "server-observed episodes as a separate evidence type" as future work. `VillagerJournal` adds it: the game server writes short first-person lines for what a villager actually saw happen with a player, never inferred from dialogue.

| Episode | Recorded when | Salience |
| --- | --- | ---: |
| `first_met` | the first conversation with that player | 5 |
| `trade` | a voice purchase completes (quest deals noted) | 3–4 |
| `gift`, `gift_refused` | a gift is given, or refused and handed back | 2–4 |
| `quest_set`, `quest_done` | a quest is set; its items are delivered | 4, 5 |
| `errand_given`, `errand_done`, `errand_received` | an errand is set; delivered (giver and recipient) | 3–4 |
| `hit` (merged per 30 s), `insult`, `kindness` (merged per 10 min) | physical hits and assessed treatment | 2–4 |
| `shunned`, `chatter_heard` | the villager refused to talk; a neighbour praised or badmouthed the player | 2 |
| `witnessed` | another villager within 8 blocks with line of sight saw a hit, gift or trade | 2 |

Episodes are kept per villager and player (48 per pair, 20,000 total), evicting the least important and oldest first. They fade after `HIGGS_MEMORY_EPISODE_DAYS` (default 30); salience 4 lasts twice as long, salience 5 four times. A per-pair relationship summary never expires (written by consolidation in a later phase). Player-claim memories now default to 30 days (`HIGGS_MEMORY_TTL_HOURS=720`).

Each conversation starts with `context.relationship`: the summary, six recent episodes (favouring the most important, in chronological order, with in-game relative times), and counts by type. The PTT recall step also searches episode text with the same lexical analysis (`TextTerms`) and adds matches to the recollection item as `episodes`, beside `memories`. The prompt distinguishes the two: episodes are things the villager saw; memories are things the player said. It asks for at most one natural callback per conversation and no invented history. Covered by `VillagerJournalTest` (merge, caps, salience eviction, expiry tiers, search, restart, schema rejection) and backend tests for episode delivery.

## Conversation consolidation

Inspired by TinyTroupe's `EpisodicConsolidator`: when a conversation with at least two player turns ends, the bridge runs one text-only provider session (`backend/lib/consolidate.js`) over the last 40 transcript lines and the villager's previous relationship summary. It returns JSON: a first-person **digest** (at most two sentences), an updated **summary** (at most 600 characters) and **promises**. The game server collects results every five seconds from the authenticated `GET /digests` (dropped after ten minutes) and writes a `conversation` episode, a `promise` episode per promise (salience 4), the new never-expiring summary, and an `overheard` episode for each companion who was present. Malformed or failed digests write nothing; the observed episodes remain. `HIGGS_CONSOLIDATION_ENABLED=false` turns it off.

The live probe (`backend/bench/text-consolidation-probe.mjs`, results in `bench/results/text-consolidation-probe.json`) found that Higgs accepts `output_modalities: ["text"]` (valid JSON in about 1.2 s) but rejects mixed `["text","audio"]`, so consolidation always uses its own session. The probe's first prompt recorded a promise as done ("Steve dealt with the wolves"). The production prompt states that a plan or promise is not an event and that secrets stay out. In two live runs on the same transcript plus a secret, both kept "promised to deal with the wolves" and omitted the secret, in about 1.6 s. This is a spot check, not a benchmark.

## Agent-to-agent behaviour (TinyTroupe-inspired)

From Microsoft TinyTroupe we borrow persona relationships, a cognitive state, the act-until-DONE loop with a repeat check, and consolidating what agents heard into memory.

- **Bonds** (`VillagerBonds`): a fixed relation per villager pair (sibling 8%, friend 24%, rival 12%, acquaintance otherwise), the same from both sides and derived from both UUIDs, so nothing is stored. Passed to chatter speakers and companions as `facts.neighbours`.
- **State of mind** (`VillagerMind`, `VillagerMemoryService.mind`): `mood` from the villager's own episodes with anyone in the last in-game day (bad experiences outweigh good ones; calm otherwise), a profession `goal` that changes each in-game day, and `onYourMind` (the latest notable episode). Sent as `context.mind` in conversations and `facts.mind` in chatter and companions.
- **Turn-taking** (`backend/lib/chatter.js`): scenes have 2–3 speakers. Whoever the last line names by call name speaks next, otherwise turns rotate. A goodbye ends the scene (`concluded`). Each cue quotes only the line being answered plus, in a three-way chat, the line this speaker missed.
- **Repeat check**: live runs showed the voice model echoing quoted lines verbatim. Lines are now held until complete; one whose wording repeats an earlier line (cosine similarity > 0.7, the existing reaction check) is never broadcast, and the speaker gets one fresh attempt; a second repeat ends the scene. A live three-villager run after this change produced six distinct lines in which mood and bonds were visible (a villager the player hit stayed suspicious; a rival disagreed).
- **Scene memory**: a scene about the player reaches the game server through `GET /digests` (`kind: scene`). Each participant records a `chatter_heard` episode quoting the other speakers.
- **Persona check**: a villager with nothing to sell that mentions wares or prices gets one correction, including in its greeting. The planned check for an errand recipient quoting the message was unnecessary: since the errand fix, the recipient never receives the message.

## Episode retrieval evaluation

`VillagerJournalBenchmark` (run on every build; report in `benchmarks/journal-retrieval-v1.json`) asks 13 answerable and 3 unanswerable player-style questions of a persisted journal with decoys for another player and another villager. The frozen baseline is the first journal search (text words only). The revised search also indexes a few plain words for each event's type (structured server data, such as `trade` → "buy sell bought sold") and normalises word endings (plurals, -ed, -ing). Queries are never rewritten.

| Metric | Baseline | Revised |
| --- | ---: | ---: |
| Top-1 evidence accuracy, 13 answerable | 46.2% | 84.6% |
| Mean reciprocal rank | 0.500 | 0.885 |
| Evidence recall at 3 | 53.8% | 92.3% |
| Correct abstention, 3 unanswerable | 100% | 100% |

Invariants passed: another player's episodes and another villager's episodes are never returned, and rankings are identical after restart. The build fails if top-1 drops below 0.8 or abstention below 100%. Two questions still miss, kept rather than tuned. "What have others said about me?" returns nothing because "said" is a shared stop word. "Who did I bring a message from?" ranks the quest ("…to bring me…") above the errand. These are small, author-written synthetic questions: a regression check, not a general score.

Gift history in conversation context (`gifts.receivedFromPlayer`) now comes from journal `gift` episodes. The gift ledger keeps only what the rules need: refused items and the reward cooldown. Older saves load unchanged.
