# Higgs issue tracker

Updated: October 23, 2026. Tracks hackathon feedback from the Minecraft voice-trading demo. No confirmed Boson defect has been isolated yet. Suspected provider behavior and application bugs are tracked separately. Nothing here has been submitted to Boson.

## Suspected Higgs issues

### HIGGS-01 — Repeated confirmation after an explicit acceptance

- **Status:** Open; user-observed, provider attribution unconfirmed.
- **Observed:** The villager sometimes requests another confirmation after a spoken “yes,” despite single-confirmation instructions.
- **Expected:** Active quote → spoken yes → `confirm_trade` → purchase result, with no additional question.
- **Reproduction target:** Request a quote, let the villager finish, then say “Yes, accept that trade” once. Repeat while interrupting the quote to distinguish ordinary acceptance from interruption failures.
- **Confounder:** APP-01 reset the approval requirement when identical terms were re-proposed. That path has been fixed locally; the exact tool sequence from the reported incident was not retained.
- **Next evidence:** Capture the quote ID, input turn, recognized acceptance, provider response ID, tool names and results, and whether a second question is spoken. Check again with the local fix installed.
- **Boson report:** Not submitted. Suggested title: “Higgs requests repeated confirmation instead of executing an explicitly accepted trade.”

### HIGGS-02 — Possible re-proposal instead of confirmation

- **Status:** Hypothesis; not directly captured. May be the mechanism behind HIGGS-01 rather than a separate bug.
- **Suspected behavior:** Higgs calls `propose_trade` after acceptance instead of `confirm_trade` for the active quote.
- **Expected:** An unchanged accepted quote uses its existing proposal ID. Only an actual change of terms requires a new quote and fresh consent.
- **Next evidence:** Record the tool sequence after one unambiguous acceptance. Do not report this as observed until the trace shows it.
- **Boson report:** Not submitted.

### HIGGS-03 — Short acceptance during interruption appears ineffective

- **Status:** Open symptom; ownership unresolved.
- **Observed:** Interrupting the villager to say “yes” sometimes does not complete the trade.
- **Confounders:** APP-02 can stall a queued response under a simulated cancellation-error sequence; APP-03 can discard short input; APP-04 omits playback-history truncation.
- **Reproduction target:** Compare the same acceptance after playback finishes versus during playback, with measured input duration and confirmed input delivery.
- **Next evidence:** Correlate cancellation, input commit, response creation, transcription and tool events with the actual playback position.
- **Boson report:** Not submitted.

### HIGGS-04 — Tool calling sensitivity to detailed instructions

- **Status:** Investigation candidate, not reproduced in this application.
- **Evidence:** Boson’s own tutorial describes tool calls stopping after additional behavioral instructions were added. Our application also uses detailed trade rules. [Official tutorial source](https://github.com/boson-ai/Higgs-Realtime-Tutorial/blob/main/src/realtime/sessionConfig.ts).
- **Reproduction target:** Run identical trade requests with short and detailed prompts, holding tool schemas, audio and configuration constant. Measure correct tool selection and successful completion across repeated trials.
- **Next evidence:** A controlled comparison showing the failing prompt, tool definitions, response/tool trace and failure frequency.
- **Boson report:** Not submitted.

## Application issues and documented behavior

| ID | Issue | Status and evidence | Next action |
| --- | --- | --- | --- |
| APP-01 | Identical quotes reset approval | Confirmed code path; fixed locally by retaining the original proposal ID, confirmation gate and expiry. Repeated successful confirmations return the receipt without another purchase. | Verify one-yes completion in game after installation. |
| APP-02 | Cancellation error can leave a reply queued indefinitely | Reproduced with simulated `response_cancel_not_active` and no subsequent `response.done`; no equivalent live Boson sequence captured. | Add recovery and regression coverage for cancellation rejection. |
| APP-03 | Very short acceptance can be discarded | Bridge rejects input under 100 ms; releasing B can discard the pending 40 ms capture frame. Two logged turns began and ended in the same second, which is insufficient to establish their actual duration. | Measure captured/sent duration and handle the final capture frame reliably. |
| APP-04 | Unheard speech remains in model history after interruption | Client clears playback but does not send `conversation.item.truncate` with the played position. | Implement playback accounting and truncation. [Boson interruption guide](https://github.com/boson-ai/Higgs-Realtime-Tutorial/blob/main/docs/part-2.md). |
| APP-05 | Initial microphone silence | Earlier investigation identified local capture/permission problems; switching to Prism enabled microphone capture. | Keep separate from provider bug reports. |
| DOC-01 | User captions arrive after speech processing | Documented provider behavior, not evidence of a defect. | Treat as UX feedback unless behavior deviates from the documented contract. [Event reference](https://github.com/boson-ai/Higgs-Realtime-Tutorial/blob/main/src/realtime/events.ts). |

## Evidence available

- Review of five retained Prism client logs found nine Ready connections, 34 speaking turns and no logged Boson error messages. This does not establish that every response or tool decision was correct.
- Historical provider stdout is incomplete; transcripts and full provider traces were not retained by the application.
- Configured model: `higgs-realtime`. The underlying model version and Higgs 1.5 entitlement are unverified.
- Local trade logging now records tool name, trusted input-turn number and outcome, without speech content. It does not yet provide all evidence required for provider attribution.

## Capture for the next incident

- [ ] Note date/time, application commit, session configuration and reproduction steps.
- [ ] Record provider session/response IDs, quote ID and input-turn number.
- [ ] Record ordered input-commit, cancellation, response and tool events, including tool outcomes.
- [ ] Measure input duration, forwarded audio bytes and played audio position.
- [ ] For a deliberate reproduction, capture the minimal acceptance/reply text needed to distinguish recognition from tool-selection failure; current logging does not save this.
- [ ] Re-test with the relevant application fix and document the result.
- [ ] Remove credentials and unrelated personal content before sharing a report.
- [ ] Add the Boson issue link and disposition here after submission.
