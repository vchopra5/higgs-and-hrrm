<p><a href="../README.md"><img src="assets/logo.png" alt="HIGGS HRRM" width="64" /></a></p>

# HIGGS HRRM · One village, different minds

[← HIGGS HRRM](../README.md) · [Memory and rumours](memory-rumours.md) · [Design](villager-personalities-plan.md)

Villagers now keep a name, character and delivery style across conversations and world restarts. Their profession remains their job: two farmers can have different personalities. A name tag changes the displayed name without replacing their saved character.

| Meet | What drives them | How they sound | What they share |
| --- | --- | --- | --- |
| **Mira** | Generous and attentive; cannot bear being the last to know. | Warm and brisk · `chloe` | Public news from 55 interest, after 5 seconds; normal pair cooldown. |
| **Rowan** | Demands evidence, but longs for a great mystery. | Measured and dryly amused · `oliver` | News from 70 interest, after 30 seconds; 1.5× pair cooldown. |
| **Bram** | Wants his craft respected; hides a fondness for poetry. | Brief and understated · `marcus` | News from 65 interest, after 60 seconds; 2× pair cooldown. |
| **Rook** | Protective, impatient and quick to bristle; grudgingly respects resolve. | Firm and clipped · `marcus` | News from 65 interest, after 10 seconds; 1.25× pair cooldown, with an interest in danger and defence. |

These are authored directions, not fixed dialogue. Preset suitability and actual delivery still need a live audition. Voice configuration follows Boson's [Realtime audio guide](https://docs.boson.ai/models/higgs-realtime/guides/audio-and-voices); the names come from its [preset catalogue](https://docs.boson.ai/models/higgs-tts/voices).

## Prepare the cast

Rebuild and install the mod on both client and server. Restart the backend and game server. Keep the cast as adult villagers awake in loaded chunks. Farmer, librarian and toolsmith are useful demo jobs, but jobs do not assign personality.

Stand within four blocks of the intended villager and run its command as an operator. Move to the next villager before running the next command:

```mcfunction
/tag @e[type=minecraft:villager,sort=nearest,limit=1,distance=..4] add higgs_mira
/tag @e[type=minecraft:villager,sort=nearest,limit=1,distance=..4] add higgs_rowan
/tag @e[type=minecraft:villager,sort=nearest,limit=1,distance=..4] add higgs_bram
/tag @e[type=minecraft:villager,sort=nearest,limit=1,distance=..4] add higgs_rook
```

Use one cast tag per entity. Assignment takes effect at its next conversation; existing sessions retain their current character. To change an assignment, remove the old tag before adding the new one. Removing a tag alone retains the saved identity. Repeated cast names receive a numeric suffix. Existing custom name tags are preserved.

Villagers without a cast tag receive a stable combination of archetype, delivery style and generated name. Assignment happens on first conversation or gossip encounter, without an idle model request. Gossip waits for the identity to reach disk; a newly encountered neighbour may need an extra gossip pass.

Rook adds a little friction: a doubtful claim can earn a pointed question or dry jab, while ordinary courtesy earns grudging respect. He can back down when shown evidence. His irritation is a conversational trait; it does not grant combat actions, change prices or bypass purchase consent. He uses the existing steady voice mapping with a different delivery direction; live audition is still pending.

Catalogue edition 2 introduces Rook and the clipped style for new assignments. Edition 1 is retained unchanged, so saved characters keep their identities. To make an existing villager Rook, remove its previous cast tag and add `higgs_rook`, then reconnect.

## Present one story, three perspectives

1. Look at Mira and wait for **Ready**. Hold **B**, ask “What matters most to you around here?”, then release. Ask Rowan and Bram the same question in separate conversations. Let their attitudes emerge. For a sharper contrast, also ask Rook “I could protect this village better than you—what do you think?” and let him choose his own brief response.
2. Tell Mira casually: **“I found a hidden library under the old well. There were enchanted books everywhere.”** No remember command is required. Let the reply finish so any memory tool can complete.
3. Keep the villagers within the configured gossip radius and watch for the green particle indicator. Default scans run every 10 seconds. Mira can share after 5 seconds; Rowan must wait 30 seconds after *he* hears the story before passing it onward. Bram waits 60 seconds. Eligibility, cooldowns, stock of known stories and scan timing can add time.
4. Switch to Rowan or Bram by looking at them and tapping **B**. Let the opening finish. They can mention a fresh rumour or react to the same story in their own manner, crediting their source.
5. Return after several minutes, or restart the server and reconnect. Ask about the story. Identity and memories persist; recognition becomes warmer after confirmed visits separated by at least five minutes.

For a controlled chain, keep Mira and Rowan within range, Rowan and Bram within range, and Mira and Bram outside range. Keep an isolated fourth villager as a control. If everyone is nearby, Bram can hear directly from Mira; Bram's own delay controls when he shares, not when he listens.

The voice model judges newsworthiness semantically. Personal interests add a small ranking bonus between otherwise similar candidates; they do not create stories. Explicit recall still searches saved memories, including lower-interest personal information. Privacy, source attribution, hop limits and trade validation apply to every character.

## Voice configuration

Optional backend settings in the root `.env` select approved presets or account-owned custom IDs:

```dotenv
HIGGS_VOICE_WARM=chloe
HIGGS_VOICE_THOUGHTFUL=oliver
HIGGS_VOICE_STEADY=marcus
```

For a separate jukebox rap voice, set `HIGGS_VOICE_RAP` to a preset or an account-owned custom `voice_<id>`. Leave it blank to use the villager's normal voice. This override applies only to raps; conversations and other reactions retain their personality voices.

Restart the backend after changes. The bridge validates mappings and rejects arbitrary voice names or prompt text. For character sessions, **Ready** requires the provider to acknowledge both the compiled instructions and selected voice. It retries once with `default` only for a structured `invalid_request_error` whose `param` is `audio.output.voice` or `session.audio.output.voice`. Authentication, quota, transport and other setup failures retain their existing errors.

Fallback keeps the character's directions and appears in status, backend logs and **F8** voice diagnostics. The error parameter adapter is covered with a fake provider; the actual rejection shape and audible presets still require verification with the target account. A provider error without the expected structured field fails setup rather than guessing from its message. See Boson's [server event schema](https://docs.boson.ai/api-reference/realtime/server-events).

## Persistence and boundaries

Profiles live in **`<world>/data/higgs_hrrm_personalities.json`**, alongside the separate memory file. Back up both with the world. Profiles are keyed by villager UUID inside a saved world identity. Moving an entity to another world without its profile file creates a new assignment.

The shared source is [`shared/personality-catalogue.json`](../shared/personality-catalogue.json). The backend validates it and the build bundles the same file into the mod. Saved profiles reference an immutable edition, archetype and style. Add a new edition and advance `currentVersion` for new characters; retain published editions so existing villagers keep their traits. Keep the matching catalogue with a deployed backend and mod.

Profiles are capped at 4096; identities are never silently evicted to make room. Familiarity is capped at 20 per pair, with at most 8192 pairs and oldest-pair eviction. Only a provider-confirmed session creates a visit. The bridge sends an internal readiness notification through the authenticated game queue; it is not an LLM tool or a provider function output. Interrupted audio cannot forge or cancel that notification. Failed familiarity writes do not enter future conversation context until successfully persisted.

Ordered background writes use atomic replacement when supported. New profiles wait for persistence before conversation or gossip. An unreadable profile file is preserved; the server logs the problem and uses ordinary voice sessions for that run. Stop the server before restoring it. A failed write blocks use of the unsaved identity and reports the problem.

Memory schema 3 adds a `learnedAt` per holder. Schema 1 and 2 migrate conservatively using load time for the new field; root IDs, original timestamps, privacy, interest and provenance are retained. Subsequent restarts preserve the learned time. Character delays use time since this villager heard the story, never the age of the original claim.

## Verification

```sh
npm --prefix backend test
./scripts/build-mod.sh --offline
```

Automated checks cover stable identity, name collisions, explicit assignment, retained catalogue editions, bounded familiarity, reconnect cooldowns, failed writes, corrupt saves, gossip delays and interest policies, memory migration, trusted prompt compilation, voice acknowledgements, narrow fallback, hit-reaction character continuity and the existing trading and audio regressions.

Live microphone playback, artistic voice separation, name-tag/job changes in a running world and the full three-character demo still need rehearsal with a Boson key. No live personality audition was performed for this implementation.

## Expressive vocal delivery

Realtime delivery is directed through `instructions`, as documented in [Boson's audio and voices guide](https://docs.boson.ai/models/higgs-realtime/guides/audio-and-voices).
The main conversation, companion, ambient chatter, and hit reactions now explicitly ask
for audible variation in pace, pitch, emphasis, pauses, and energy based on actual context.
Personality is the baseline; it no longer imposes uniformly subtle delivery.

A successful `express_emotion` result adds a bounded `vocalDelivery` instruction for the
next reply. The model chooses the emotion and dialogue. A physical gesture cooldown
still permits expressive speech. This does not change voices, reset the session, or
replace response instructions (which previously harmed dialogue continuity in probes).
Existing whisper styling remains in place. No TTS-only emotion parameters were added.
Regression tests verify the instructions and tool-result path; audible quality still
requires a live listening comparison.
