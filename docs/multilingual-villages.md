# Multilingual villages

Branch: `codex/multilingual-villages`, based on `dev` at `28b4029`.

## Plan and implementation

1. Remove the English transcription hint. Keep `higgs-stt-3.1`, audio formats, turn handling and the selected voice unchanged.
2. Give the main villager and companions a shared language policy: follow the player's speech, honour an explicit language request, and preserve personality while switching languages. Short confirmations and borrowed item names do not cause a switch.
3. Keep actions authoritative. Translate spoken item labels while preserving item identity, exact quantities and tool IDs. Interpret consent and refusals in context in any language; a translated keyword alone is not consent.
4. Keep new memory summaries and conversation digests in English for the existing lexical search. Restore bounded explicit `recall_memories` calls so Higgs can translate a missed query. Return the current player's journal episodes with explicit recall too. Spoken recollections use the conversation language.
5. Make the English reputation keyword fallback conservative. Other scripts, accented text and phrases without a recognised English opening defer to the model's semantic `report_treatment` call. Do not add translated keyword lists that could mistake negation or ordinary words for praise.
6. Verify configuration, Unicode transport, tool validation, trusted turn IDs, memory lookup and existing regressions; rehearse actual speech separately.

## Player experience

Speak normally in your preferred language, or ask the villager to use it. You can switch during a conversation. The same villager keeps the same configured voice and personality. English tool results and game-event messages should not switch the reply language back to English.

A fresh conversation starts with an English greeting until the player speaks. A continued conversation can use its carried-over dialogue. Language preference is not yet saved across new sessions.

Examples to rehearse:

| Step | Say | Expected result |
| --- | --- | --- |
| Spanish conversation | “Hola, ¿qué vendes?” | Spanish reply using real available offers. |
| Spanish acceptance | “Sí, de acuerdo.” after an exact quote | One confirmation tool call; at most one purchase. |
| Conditional acceptance | “Sí, pero más barato.” | Negotiate new terms; do not buy the previous quote. |
| Hindi switch | “अब हिंदी में बात करो।” | Hindi reply, same villager voice and personality. |
| Praise | “तुम बहुत दयालु हो।” | Model reports polite treatment, subject to the normal score cap. |
| Memory | Tell a Spanish story, later ask about it in English | Faithful English internal summary can be retrieved; provenance and privacy retained. |
| Translated recall | Ask in Spanish where the diamonds were found | Use supplied evidence or an explicit English lookup; never invent an answer. |
| Companion | “Rook, ¿qué piensas?” | Named companion answers in the conversation language. |
| Refusal | “不，谢谢。” after a quote | No purchase. |
| Code-switching | “Can I have dos panes, please?” | Correct item and quantity, or a short clarification if ambiguous. |

Also test interruption halfway through a translated quote, a second confirmation after missing items, a private memory, a failed recall, and an unknown item name.

## Scope and limitations

This enables multilingual player conversations; it does not translate the Minecraft UI, debug pages, dashboard or saved books. Villager-only ambient scenes and cached shouts still use their existing language. Companion name routing works with the name followed by a comma; greeting and dismissal phrase shortcuts remain English. Server-side message-errand matching is lexical, so a translated paraphrase may not satisfy an errand.

The speech model performs translation and intent recognition. There is no separate translation service, no automatic voice replacement and no new model call for each utterance. Explicit translated recall can add a tool round trip. Memory translation is prompted, not guaranteed by a language validator; old saves are not rewritten.

The English fallback is intentionally incomplete, not a general language detector. Mixed Latin-script wording can still be ambiguous. When it abstains, reputation depends on the model calling `report_treatment`. Server score limits and per-turn checks still apply. The existing spoken-price correction heuristic is English-specific; actual purchases remain checked by the game server in every language.

## Verification

Run `npm --prefix backend test` and `./scripts/build-mod.sh --offline` with the repository's Java runtime. New tests cover:

- No forced transcription language in either hands-free or push-to-talk mode.
- Stable voices, no trade tools for non-trading villagers, and tool-less companions.
- Original Unicode transcripts surviving captions and consolidation input.
- Model-selected treatment, translated memory queries and confirmation calls retaining trusted input-turn IDs.
- Invalid memory scopes, oversized queries and extra identity arguments remaining rejected.
- Conservative treatment fallback for non-English and mixed-script phrases.

Mock-provider tests do not prove that Higgs understands or speaks any particular language correctly. Run the rehearsal table with a live key and game client before demonstrating it.

Boson's [session configuration guide](https://docs.boson.ai/models/higgs-realtime/guides/connections-and-sessions) documents `language` as an optional transcription hint. Its [Realtime tutorial](https://www.boson.ai/blog/higgs-realtime-tutorial) describes multilingual conversations and code-switching.
