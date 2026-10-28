import { WebSocket } from 'ws';

export async function* sseEvents(body) {
  const decoder = new TextDecoder(); let buffer = '';
  for await (const chunk of body) {
    buffer += decoder.decode(chunk, { stream: true });
    let index;
    while ((index = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, index).replace(/\r$/, ''); buffer = buffer.slice(index + 1);
      if (!line.startsWith('data:')) continue;
      const data = line.slice(5).trim();
      if (data === '[DONE]') { yield { sseDone: true }; return; }
      if (data) yield JSON.parse(data);
    }
  }
  if (buffer.trim() && buffer.trim() !== 'data: [DONE]') throw new Error('Incomplete SSE stream');
}

export async function openRouterTurn({ apiKey, model, instructions, tools, messages, audio, audioIndex, fetchImpl = fetch, timeoutMs = 60000, captureAudio = false }) {
  const input = structuredClone(messages);
  if (audio) input[audioIndex].content = [{ type: 'input_audio', input_audio: { data: audio.wav.toString('base64'), format: 'wav' } }];
  const response = await fetchImpl('https://openrouter.ai/api/v1/chat/completions', {
    method: 'POST', signal: AbortSignal.timeout(timeoutMs),
    headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ model, messages: [{ role: 'system', content: instructions }, ...input],
      tools: tools.map(({ type, ...fn }) => ({ type: 'function', function: fn })),
      tool_choice: 'auto', modalities: ['text', 'audio'], audio: { voice: 'alloy', format: 'pcm16' },
      stream: true, stream_options: { include_usage: true }, max_tokens: 800,
      provider: { allow_fallbacks: false, require_parameters: true } }),
  });
  if (!response.ok) throw new Error(`OpenRouter HTTP ${response.status}`);
  let content = '', transcript = '', audioBytes = 0, finishReason, usage, returnedModel, provider, sawDone = false, audioExpired = false;
  const calls = new Map(), audioChunks = [];
  for await (const e of sseEvents(response.body)) {
    if (e.sseDone) { sawDone = true; continue; }
    if (e.error) throw new Error(`OpenRouter stream error ${String(e.error.code ?? 'unknown').replace(/[^a-z0-9_-]/gi, '').slice(0, 40)}`);
    usage = e.usage ?? usage; returnedModel = e.model ?? returnedModel; provider = e.provider ?? provider;
    const c = e.choices?.[0]; if (!c) continue;
    finishReason = c.finish_reason ?? finishReason;
    audioExpired ||= Number.isFinite(c.delta?.audio?.expires_at);
    content += c.delta?.content ?? '';
    transcript += c.delta?.audio?.transcript ?? '';
    const chunk = Buffer.from(c.delta?.audio?.data ?? '', 'base64');
    audioBytes += chunk.length; if (captureAudio && chunk.length) audioChunks.push(chunk);
    for (const t of c.delta?.tool_calls ?? []) {
      const old = calls.get(t.index) ?? { id: '', type: 'function', function: { name: '', arguments: '' } };
      if (t.id) old.id = t.id;
      old.function.name += t.function?.name ?? '';
      old.function.arguments += t.function?.arguments ?? '';
      calls.set(t.index, old);
    }
  }
  // OpenRouter's GPT Audio currently emits null finish_reason even on complete streams.
  // Require its explicit DONE terminator, usage, and audio expiry or complete tool JSON.
  const completeTools = calls.size > 0 && [...calls.values()].every(c => { try { return Boolean(c.id && c.function.name && JSON.parse(c.function.arguments)); } catch { return false; } });
  const terminalEvidence = !finishReason && sawDone && usage && (audioExpired || completeTools);
  if (!['stop', 'tool_calls'].includes(finishReason) && !terminalEvidence) throw new Error(`OpenRouter incomplete response (${finishReason ?? 'missing finish'})`);
  return { ...(captureAudio ? { audioPcm: Buffer.concat(audioChunks) } : {}), text: transcript || content, calls: [...calls.values()], audioBytes, usage: usage ?? null,
    returnedModel, provider, finishReason: finishReason ?? null, completionEvidence: terminalEvidence ? 'DONE+usage+audio_expiry_or_complete_tool' : 'finish_reason' };
}

export function higgsTurn({ apiKey, model, instructions, tools, messages, audio, audioIndex, timeoutMs = 60000, captureAudio = false,
  upstreamUrl = 'wss://api.boson.ai/v1/realtime/', connect = (url, options) => new WebSocket(url, options) }) {
  return new Promise((resolve, reject) => {
    let ws, finished = false, configured = false, nextIndex = 0, audioBytes = 0, inputTranscript = '';
    const texts = new Map(), calls = new Map(), audioChunks = [];
    const timer = setTimeout(() => done(new Error('Higgs timeout')), timeoutMs);
    function done(error, value) {
      if (finished) return; finished = true; clearTimeout(timer);
      if (ws?.readyState === WebSocket.OPEN) ws.close(); else ws?.terminate();
      error ? reject(error) : resolve(value);
    }
    function send(e) { ws.send(JSON.stringify(e)); }
    function item(i) { send({ type: 'conversation.item.create', item: i }); }
    function sendHistory() {
      while (nextIndex < messages.length) {
        const i = nextIndex++, m = messages[i];
        if (audio && i === audioIndex) {
          for (let p = 0; p < audio.pcm.length; p += 24000) send({ type: 'input_audio_buffer.append', audio: audio.pcm.subarray(p, p + 24000).toString('base64') });
          send({ type: 'input_audio_buffer.commit' }); return; // Resume only after the audio item exists.
        }
        if (m.role === 'tool') item({ type: 'function_call_output', call_id: m.tool_call_id, output: m.content });
        else {
          if (m.content) item({ type: 'message', role: m.role, content: [{ type: m.role === 'assistant' ? 'text' : 'input_text', text: m.content }] });
          for (const t of m.tool_calls ?? []) item({ type: 'function_call', call_id: t.id, name: t.function.name, arguments: t.function.arguments });
        }
      }
      send({ type: 'response.create' });
    }
    function recordCall(e) { if (e.call_id) calls.set(e.call_id, { id: e.call_id, type: 'function', function: { name: e.name, arguments: e.arguments } }); }
    try { ws = connect(upstreamUrl, { headers: { Authorization: `Bearer ${apiKey}` }, handshakeTimeout: 15000 }); }
    catch { done(new Error('Higgs connection failed')); return; }
    ws.on('open', () => send({ type: 'session.update', session: { model, instructions, tools, tool_choice: 'auto', output_modalities: ['audio'],
      audio: { input: { format: { type: 'audio/pcm', rate: 24000 }, turn_detection: null,
        transcription: { model: 'higgs-stt-3.1', language: 'en' } }, output: { format: { type: 'audio/pcm', rate: 24000 }, voice: 'default' } } } }));
    ws.on('error', () => done(new Error('Higgs connection failed')));
    ws.on('close', () => { if (!finished) done(new Error('Higgs connection closed early')); });
    ws.on('message', raw => {
      try {
        const e = JSON.parse(String(raw));
        if (['session.created', 'session.updated'].includes(e.type) && !configured && e.session?.instructions === instructions) { configured = true; sendHistory(); }
        else if (e.type === 'input_audio_buffer.committed') sendHistory();
        else if (e.type === 'conversation.item.input_audio_transcription.completed') inputTranscript = e.transcript ?? '';
        else if (/^response\.(?:output_)?audio\.delta$/.test(e.type)) { const chunk = Buffer.from(e.delta ?? '', 'base64'); audioBytes += chunk.length; if (captureAudio) audioChunks.push(chunk); }
        else if (/^response\.(?:output_)?audio_transcript\.(delta|done)$/.test(e.type)) {
          const key = `${e.item_id ?? 0}:${e.content_index ?? 0}`;
          texts.set(key, e.type.endsWith('.done') ? e.transcript ?? texts.get(key) ?? '' : (texts.get(key) ?? '') + (e.delta ?? ''));
        } else if (e.type === 'response.function_call_arguments.done') recordCall(e);
        else if (e.type === 'response.done') {
          const r = e.response;
          for (const o of r?.output ?? []) if (o.type === 'function_call') recordCall(o);
          if (r?.status !== 'completed') return done(new Error(`Higgs response ${r?.status ?? 'unknown'}`));
          done(null, { ...(captureAudio ? { audioPcm: Buffer.concat(audioChunks) } : {}), responseId: r.id, responseStatus: r.status, text: [...texts.values()].join('\n'), calls: [...calls.values()], audioBytes, inputTranscript, usage: r.usage ?? null, returnedModel: model });
        } else if (e.type === 'error') done(new Error(`Higgs provider error ${String(e.error?.code ?? 'unknown').replace(/[^a-z0-9_-]/gi, '').slice(0, 40)}`));
      } catch { done(new Error('Invalid Higgs event')); }
    });
  });
}
