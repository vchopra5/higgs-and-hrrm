<p align="center"><img src="docs/assets/hero.png" alt="HIGGS HRRM — Give the hrrm a voice" width="100%" /></p>

# HIGGS HRRM

**Give the “hrrm” a voice.**

Talk to a Minecraft villager, discover its real offers, and confirm a trade by voice—powered by Boson Higgs Realtime.

## The story we are telling

Minecraft villagers already have jobs, stock, and a role in the world. Their conversation is mostly “hrrm.” We give that familiar character a voice connected to what it can actually do.

The demo follows one simple player goal: **find something useful and ask to trade for it.** The memorable moment is when a spoken confirmation completes a real Minecraft trade.

Our first audience is Minecraft players and creators who want more expressive roleplay and adventure worlds. A possible next audience is server owners building characters players can interact with through speech. Those are product directions, not claims of existing customers or demand.

**What judges should remember:** a character that can talk about the world and take a useful action inside it.

## Thirty-second pitch

> “You know the sound a Minecraft villager makes: hrrm.
>
> We built HIGGS HRRM to give that character a voice. You can ask a villager what it sells, hear an answer based on its actual offers, and ask for a quote and confirm by voice.
>
> Boson Higgs Realtime powers the voice conversation and tool calls. Our Minecraft integration connects those calls to live game state. You choose the trade and confirm it by saying yes.
>
> Let’s meet the villager.”

## One story, three perspectives · character extension

[Prepare Mira, Rowan and Bram](docs/villager-personalities.md). Ask each “What matters most to you around here?” and let the audience hear the contrast: warm curiosity, sceptical fascination, guarded practicality. Their replies are generated, not scripted.

Tell Mira the hidden-library story, watch the small gossip indicator, then let Rowan open the next conversation. Return after a restart to show that the character and memory persist. Allow time for each character's gossip delay; rehearse the actual voices before presenting.

**Say:** “These villagers have different motives, voices and habits. Mira shares quickly. Rowan wants stronger evidence. Bram keeps more to himself. They remember who they are when we come back.”

## Memory and gossip demo · two-minute extension

Rehearse this flow using the [memory and rumours setup](docs/memory-rumours.md). The feature is implemented and tested locally; confirm live recall and visible particles before presenting it as a successful in-game demo.

1. **Tell the farmer casually:** “I found a hidden library under the old well. There were enchanted books everywhere.” Let it react, then end the session. No memory command is needed; the model judges what is worth saving and spreading.
2. **Return if demonstrating persistence:** Start a new conversation and refer to the well. Let the villager use its saved summary naturally.
3. **Watch the village:** Keep a second awake villager within 16 blocks. A green particle burst signals a newly saved rumour transfer. End the first conversation before addressing the neighbour.
4. **Hear the reaction:** Look at the neighbour and tap **B** to switch if needed; release and let it speak first. A fresh noteworthy rumour can become the opening topic, with the source credited. A librarian gives the hidden-library story extra attention. Keep a third villager isolated as a control.

**Say:** “I didn’t ask it to remember or ask its neighbour for gossip. The villager picked out a story worth repeating, the village passed it along, and the neighbour had something to say. Each villager knows only the stories it learned.”

The default check interval is 10 seconds; the base pair cooldown is 60 seconds, scaled by character discretion; characters also wait 5–60 seconds after hearing a story before sharing it. Prepare a fresh public story for each run and leave enough time for a transfer. Explicitly private entries are excluded from gossip. Treat the discovery story as a player claim, not a verified discovery. Restart the server and ask again if you want to demonstrate persistence on disk.

## Four-minute presentation and live demo

Use one presenter and, if available, a second person operating Minecraft. The operator holds **B** for each player line and releases it to request the reply. If presenting solo, pause narration while operating the game. Let the audience hear the villager. Villagers now greet first when you start a session; allow the opening to finish before your first question.

The prompts below are suggestions. The villager’s replies are generated live; do not promise exact wording, a specific item, or a price.

### 0:00–0:25 · Start with the familiar

**On screen:** Minecraft, facing the prepared villager. No terminal or setup screen.

**Say:**

> “This is one of Minecraft’s most familiar characters. It has a profession. It has things to sell. But ask it a question and the answer is usually… hrrm.
>
> We wanted to hear what it had to say.”

**Do:** Stay within eight blocks, aim at the adult villager, wait for the automatic connection to show **Ready**. The villager must be awake and available.

### 0:25–1:00 · Let the character speak

**Player says, holding B:**

> “Hello! What do you do around here?”

**Do:** Release **B** and let the reply finish. Keep the camera on the villager.

**Say after the reply:**

> “The game supplies this villager’s name, profession, and level as context. Higgs handles the voice conversation.”

**Audience takeaway:** The character can respond naturally in its role.

### 1:00–1:45 · Give the conversation a purpose

**Player says, holding B:**

> “What can I buy from you?”

**Do:** Release **B**. Listen for an available offer and remember its item name. If the answer is long, hold **B** during the reply and say, “Just tell me one available trade,” then release. This also demonstrates interruption if it worked during rehearsal.

**Say after the reply:**

> “That question gives the agent a reason to use a tool. It can look up this villager’s current offers on the Minecraft server. Stock comes from the game, and the server applies the player-specific prices.”

**Audience takeaway:** The answer has a connection to the game’s actual state.

### 1:45–2:30 · Deliver the payoff

**Player says, holding B:**

> “Open the trade for [available item the villager just mentioned].”

**Do:** Replace the bracketed text with the actual item. Release **B**. Listen to the exact quote, then hold B, say yes, and release to confirm.

**Say:**

> “That spoken confirmation completes the trade. The server checks the villager, distance, price, stock, and inventory before exchanging items.”

**Optional:** After confirming, let the villager report the actual result aloud and check the inventory.

**Audience takeaway:** Speech can lead to a useful game action, with the player in control.

### 2:30–3:15 · Explain the build

**On screen:** One architecture slide, or stay in Minecraft if switching would interrupt the flow.

```text
Player microphone → Fabric client → local bridge → Boson Higgs Realtime
                                                        ↕ tool calls
                                  Minecraft server ← local bridge
                                      ↓
                           Live offers / voice-confirmed trade

Villager speech streams back through the bridge to the client.
```

**Say:**

> “We built the client and server mod, microphone capture and playback, the local voice bridge, and tools for live offers, voice-confirmed trades, memory, and recall.
>
> Higgs powers streaming voice and generates the tool calls. Minecraft supplies the offers and performs the actual exchange. Our integration connects those systems and checks whether the requested action is allowed.”

### 3:15–4:00 · Close on the player experience

**On screen:** Return to the villager. Walk away to end the conversation.

**Say:**

> “We started with a small interaction: ask a shopkeeper what it sells, then ask to see a trade.
>
> That is the direction we want to explore: game characters whose conversations connect to the world around them. We now have saved personalities, memories and gossip. Next, we would explore more game actions, such as helping a player with a quest. Quests are a future direction.
>
> This is HIGGS HRRM. Give the hrrm a voice.”

## Minimal slide plan

Keep slides visual and use the spoken script for detail.

| Slide | Headline | Visual |
| --- | --- | --- |
| 1 | **Give the “hrrm” a voice.** | One villager screenshot, project name, Boson Higgs Realtime credit. |
| 2 | **Ask. Hear. Trade.** | Live Minecraft demo; keep this slide as a transition or backup. |
| 3 | **Voice connected to game state.** | The architecture above, with `get_trades` and `propose_trade` labeled. |
| 4 | **Characters you can talk to—and do things with.** | Inventory screenshot after a voice-confirmed trade; a small cast portrait or name row, with quests marked as a future direction. |

## Rehearsal and stage setup

- Follow the [installation and controls](docs/setup.md#villager-voice-prototype). Start the backend and game server before presenting; join with the matching Fabric client and mod.
- Prepare one adult, awake villager in a quiet, well-lit enclosure. Check its offers manually. Choose an available offer and carry the required payment and any secondary item. Put away any villager spawn egg.
- Stay within eight blocks throughout the demo. Use a safe setting so combat or death cannot interrupt the session. Avoid reputation or Hero discounts for the clearest price comparison.
- Verify the full path on the presentation machine: **Look at villager → Ready → hold B → release → audible reply → request a quote → say yes → inventory updates**. Rehearse this more than once.
- Test microphone permission and the actual audience audio output. On macOS, the README recommends Prism Launcher for microphone access. Speech uses the default system audio device; Minecraft’s volume sliders do not control it.
- Use **F8** to inspect diagnostics during rehearsal. Once verified, hide them for the main presentation. Keep the microphone HUD visible so the audience can see when you speak.
- Start a fresh conversation before presenting; sessions have a ten-minute limit. Keep the game focused while holding **B**. Release **B** before switching windows.
- Record a successful 30–45 second run with audible player and villager speech and the voice-confirmed exchange. Have the recording ready locally. A recording is a backup to prepare, not an artifact included with this file.

## If the live demo stalls

Give recovery no more than about fifteen seconds. Check **Ready** and the microphone meter; if needed, walk away and return to reconnect, then repeat one short prompt. Avoid debugging in front of the judges.

**If a successful recording is ready, say:**

> “The live voice connection isn’t responding as expected. Here is a recorded run of the same flow.”

Play it, clearly label it as recorded, then continue with the build explanation.

**If no recording exists, say:**

> “We haven’t completed the live voice flow in this run. I’ll show the game interaction the tool is designed to open, then explain the integration.”

Open trading manually and identify that action as manual. Treat the voice-to-action flow as unverified in this presentation.

## Judge Q&A

| Question | Suggested answer |
| --- | --- |
| What makes this an agent? | It can request live offers with `get_trades` and request a game action with `propose_trade`. Our server integration executes and validates those calls. |
| What did you build? | The Fabric client/server integration, audio capture and playback, authenticated local bridge, session lifecycle, and trade-tool handlers. Higgs supplies the voice model and tool-call generation. |
| Why use Higgs? | The interaction needs streaming speech and tool calls in the same conversation. We integrate those capabilities through Higgs Realtime. |
| Can it negotiate or buy for me? | The tools look up offers, quote native prices, and buy once after an explicit spoken yes. They can negotiate bounded prices and proportional quantities; every changed quote needs a fresh yes. |
| How do you keep trade information accurate? | Offer lookup reads server state, and the server rechecks availability before purchasing. The prompt instructs the model to use those tools and avoid invented offers. Final prices are validated server-side; model mistakes remain possible. |
| Does each villager remember me? | The server saves bounded conversation summaries per villager and player across visits and restarts. Nearby villagers can learn public stories through gossip. Distinct saved personalities remain future work. |
| Is the audio spatial? | Currently it plays through the default system device for the speaking player. Spatial sound and nearby-player playback are future work. |
| Who would use it? | We would start with Minecraft roleplay players and creators, then validate interest from adventure-server owners. We have not established market demand. |
| How fast is it? | Measure release-to-first-audio during rehearsal. Existing synthetic bridge-test notes report about 822 ms in one successful probe; that is not an in-game benchmark or a latency guarantee. |
| What has been verified? | Separate code and mock-provider checks from a successful live run. The repository documents successful synthetic voice probes, while full microphone, playback, and in-game trade validation still need confirmation on the demo setup. |

## Presenter accuracy notes

The script describes the intended live flow implemented in this repository. **Rehearsal must establish that it works on the actual presentation setup.** This document does not certify a successful in-game demo.

Use **push-to-talk** for the Minecraft demo: hold **B**, release to send. Looking at a nearby villager starts the session automatically; the villager can greet first. Claim interruption and live memory/gossip behaviour only after rehearsal. Persistent summaries, local gossip, bounded negotiation, and voice-confirmed purchases are implemented. Unique voices for every villager and purchases without explicit consent are not.

For implementation details and current evidence, see the [project README](README.md), [architecture](docs/architecture.md), and [bridge documentation](backend/README.md).

## Sixty-second version

> “Minecraft villagers have a lot to sell and very little to say. We built HIGGS HRRM to give them a voice.”

1. Look at a villager, wait for **Ready**, then hold **B** and ask, “What can I buy?” Release and let the villager answer.
2. Hold **B**, request an available item, then release. Hear the quote, then hold B and say yes.
3. Close with: “Higgs powers the voice and tool calls. Our mod connects them to real game state. You choose and confirm the trade by voice. Give the hrrm a voice.”
