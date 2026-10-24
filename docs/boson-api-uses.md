# Boson AI uses in HIGGS HRRM

[← README](../README.md#how-we-use-boson-ai)

Reviewed October 24, 2026 against `dev` at `dbf380a`. This is a map of implemented uses, not a claim that every interaction has passed a live playtest.

Boson helps villagers understand speech, speak in character, and request actions. Minecraft validates and carries out those actions.

## What to show stakeholders

| Experience | Simple demonstration | Boson's role |
| --- | --- | --- |
| Voice and captions | Ask “What do you do around here?”, then interrupt the reply. | Understand speech, generate a spoken response, and provide transcripts. |
| Personality | Tell Mira, Rowan, and Bram the same story. | Follow each character's identity, voice, interests, mood, and delivery style. |
| Greetings | Return to a villager you know. | Greet you using supplied relationship history and relevant news. |
| Trade browsing | Ask “What can I buy?” or request a suggestion. | Request real offers and describe an optional preview. |
| Bargaining and purchases | Ask for a different quantity or price, then accept the exact quote. | Interpret your request, ask the server for terms, and request confirmation or cancellation. |
| Treatment and gifts | Thank a villager, then say “This diamond is for you.” | Classify social treatment, request a gift transfer, and react to the actual result. |
| Quests | Bargain until a quest is offered, then bring the requested items. | Request an available quest and its completion; explain the resulting deal. |
| Errands | Ask “Anything I can help with?” and deliver the message or package. | Compose a short message, interpret the delivery, and request server validation. |
| Personal memories | Share a meaningful plan, then ask about it on a later visit. | Summarise important statements and use retrieved history in conversation. |
| Relationship summaries | Have a conversation, leave, and return later. | Produce a text-only digest, relationship summary, and promise updates. |
| Friendship | Grow close, then ask “Write down our story.” | Choose an eligible nickname or request a keepsake book from saved history. |
| Rumours | Tell a consequential public story, then meet a nearby neighbour. | Assess interest and audiences; speak about supplied public memories and their sources. |
| Urgent sharing | Share major news and let the villager finish its farewell. | Request a visit to share a specific saved public story. |
| Emotional gestures | Share exciting or worrying news. | Request a context-appropriate gesture; Minecraft renders the movement and particles. |
| Three-way conversation | Speak to the main villager and a nearby companion by name. | Generate separate character responses using coordinated turns and supplied shared dialogue. |
| Overheard chatter | Watch idle neighbours within earshot. | Generate an unscripted exchange about work, moods, bonds, the player, or public news. |
| Walks | Ask “Come for a walk with me,” then “You can go home.” | Request following or departure and comment on supplied surroundings. |
| Directions | Ask “Take me to the librarian” or “Where is the nearest forest?” | Interpret the destination and describe the server's real lookup or escort result. |
| Hits, hostility, and bedtime | Hit a villager, meet one who dislikes you, or approach at night. | Generate contextual protest, refusal, or goodnight speech. |
| Danger and village events | Trigger a zombie chase, ring a bell, or launch a firework. | Generate cries for help, bell questions, or delighted reactions. |
| Music, children, and pets | Play a jukebox, wait until dusk, or bring a named pet. | Generate a short rap, call children home, or comment on the pet. |

Availability depends on game conditions, relationship, stock, and enabled settings. Follow the [playtest checklist](playtest-checklist.md) for live rehearsal.

## API usage in plain language

All current provider calls use Boson's Realtime WebSocket endpoint, `wss://api.boson.ai/v1/realtime/`.

| Path | Purpose | Source |
| --- | --- | --- |
| Main conversation | Live player dialogue, tool requests, captions, emotional delivery, and reactions. Configures `higgs-realtime-1.5-preview`, with `higgs-stt-3.1` input transcription. | [Session configuration](../backend/lib/protocol.js), [session handling](../backend/lib/session.js), [audio](../backend/lib/audio.js) |
| Companion conversation | A separate voice session for a neighbour joining the player. Configures `higgs-realtime`. | [Companion](../backend/lib/companion.js), [speaker coordination](../backend/lib/conversation-floor.js) |
| Ambient chatter | Separate character sessions generate overheard village dialogue. Configures `higgs-realtime`. | [Chatter](../backend/lib/chatter.js) |
| Short ambient clips | Generate event-specific speech using `higgs-realtime`. Some short calls are cached by voice and kind; raps and pet remarks are fresh. | [Ambient voices](../backend/lib/shout.js) |
| Conversation consolidation | A separate `higgs-realtime` session returns text containing a digest, relationship summary, and promise updates. | [Consolidation](../backend/lib/consolidate.js) |

Voice identity comes from [character configuration](../backend/lib/personalities.js); delivery instructions shape tone and expression. The current game client uses push-to-talk. The bridge also supports hands-free turn detection. Captions are supplied by the Realtime session, without a separate standalone transcription request.

## Actions Boson can request

These are application-defined function tools, not separate Boson endpoints. A trading-capable main session exposes 19 tools. Villagers without stock receive a reduced set; companions have no action tools.

| Player-facing purpose | Tools |
| --- | --- |
| Share saved news and express emotion | `share_news`, `express_emotion` |
| Save a meaningful statement, interest, privacy, and relevant audiences | `remember_conversation` |
| Browse, quote, accept, or cancel a trade | `get_trades`, `preview_trade`, `propose_trade`, `confirm_trade`, `cancel_trade` |
| Report polite, neutral, or rude treatment | `report_treatment` |
| Offer and complete a quest | `offer_quest`, `complete_quest` |
| Accept or refuse an offered gift | `receive_gift` |
| Assign and deliver a message or package | `offer_errand`, `deliver_errand` |
| Walk together, guide the player, or return home | `follow_player`, `guide_player`, `stop_following` |
| Choose a nickname or request a shared-story book | `set_nickname`, `write_our_story` |

The bridge prepares relevant memory context before speech. Although `recall_memories` still has a definition in the source, the current main session filters it out of the exposed tool set.

Definitions: [protocol.js](../backend/lib/protocol.js). Validation and results: [tools.js](../backend/lib/tools.js). Game execution: [HiggsVillagers.java](../mod/src/main/java/dev/higgs/HiggsVillagers.java).

## What the game owns

Minecraft controls prices, stock, inventory transfers, confirmation checks, quest rewards, movement, and relationship changes. The model's words alone do not complete an action. The server controls memory persistence, privacy, gossip eligibility, and forwarding limits. Game animations, particles, dancing, and child tag behavior are implemented locally.

The game supplies places and events as context. Boson does not independently observe the world or find destinations. Relationship books use the saved journal; their contents are assembled by the game.

## Scope and validation

Dedicated TTS, voice cloning, custom-voice management, built-in web search, and Avatar APIs are not integrated. Current ambient speech is generated through Realtime. Multilingual support is proposed in [PR #1](https://github.com/vchopra5/higgs-and-hrrm/pull/1) and is outside this `dev` baseline.

This review checks implementation coverage. It does not run paid API calls or prove live speech accuracy. Use [Setup](setup.md), the [voice bridge guide](../backend/README.md), and the [playtest checklist](playtest-checklist.md) to run the game demonstrations.
