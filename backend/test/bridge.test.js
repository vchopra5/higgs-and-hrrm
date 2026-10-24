import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { WebSocket, WebSocketServer } from 'ws';
import { createBridge } from '../src.js';
const token = 'a'.repeat(64);
const delay = ms => new Promise(r => setTimeout(r, ms));
async function until(fn) { for (let i = 0; i < 100; i++) { const value = fn(); if (value) return value; await delay(10); } throw new Error('Timed out'); }
test('authenticated session, one-use ticket, audio relay, tool batching and cleanup', async t => {
  const mock = new WebSocketServer({ port: 0, host: '127.0.0.1' }); await once(mock, 'listening');
  const received = []; let upstream;
  mock.on('connection', ws => { upstream = ws; ws.on('message', data => { const event = JSON.parse(data); received.push(event); if (event.type === 'session.update') ws.send(JSON.stringify({ type: 'session.created' })); }); });
  const bridge = createBridge({ token, apiKey: 'test-key', upstreamUrl: `ws://127.0.0.1:${mock.address().port}` });
  bridge.server.listen(0, '127.0.0.1'); await once(bridge.server, 'listening');
  t.after(async () => { await bridge.close(); for (const c of mock.clients) c.terminate(); await new Promise(r => mock.close(r)); });
  const base = `http://127.0.0.1:${bridge.server.address().port}`;
  const call = (path, method = 'GET', body) => fetch(base + path, { method, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
  assert.equal((await fetch(base + '/sessions', { method: 'POST' })).status, 401);
  const response = await call('/sessions', 'POST', { playerId: 'player', villagerId: 'villager', context: { profession: 'farmer' } });
  assert.equal(response.status, 201); const { sessionId, ticket } = await response.json();
  const client = new WebSocket(base.replace('http:', 'ws:') + '/audio?mode=ptt&ticket=' + ticket); const events = []; client.on('message', data => events.push(JSON.parse(data))); await once(client, 'open');
  await until(() => events.some(e => e.message === 'Ready'));
  const reused = new WebSocket(base.replace('http:', 'ws:') + '/audio?mode=ptt&ticket=' + ticket); const rejected = once(reused, 'error'); await rejected;
  assert.equal(received[0].session.audio.input.turn_detection, null);
  assert.equal(received[0].session.audio.input.transcription.model, 'higgs-stt-3.1');
  const audio = Buffer.alloc(4800).toString('base64');
  client.send(JSON.stringify({ type: 'audio', audio })); client.send(JSON.stringify({ type: 'commit' }));
  await until(() => received.some(e => e.type === 'input_audio_buffer.commit'));
  upstream.send(JSON.stringify({ type: 'input_audio_buffer.committed', item_id: 'turn-one' }));
  upstream.send(JSON.stringify({ type: 'conversation.item.input_audio_transcription.completed', item_id: 'turn-one', transcript: 'Hello' }));
  await until(() => [...bridge.sessions.get(sessionId).pending.values()].some(tool => tool.memoryContext));
  const memoryCall = [...bridge.sessions.get(sessionId).pending.values()].find(tool => tool.memoryContext);
  await call(`/sessions/${sessionId}/tools/${memoryCall.callId}/result`, 'POST', { result: { memories: [] } });
  await until(() => received.some(e => e.type === 'response.create'));
  upstream.send(JSON.stringify({ type: 'response.output_audio.delta', delta: audio }));
  await until(() => events.some(e => e.type === 'audio' && e.audio === audio));
  upstream.send(JSON.stringify({ type: 'response.created' }));
  for (const call_id of ['a', 'b']) upstream.send(JSON.stringify({ type: 'response.function_call_arguments.done', call_id, name: 'get_trades', arguments: '{}' }));
  upstream.send(JSON.stringify({ type: 'response.done' }));
  await until(() => bridge.sessions.get(sessionId).pending.size === 2);
  assert.equal((await (await call(`/sessions/${sessionId}/tools`)).json()).length, 2);
  const before = received.filter(e => e.type === 'response.create').length;
  await call(`/sessions/${sessionId}/tools/a/result`, 'POST', { result: { offers: [1] } }); await delay(20);
  assert.equal(received.filter(e => e.type === 'response.create').length, before);
  await call(`/sessions/${sessionId}/tools/b/result`, 'POST', { result: { offers: [2] } });
  await until(() => received.filter(e => e.type === 'response.create').length === before + 1);
  assert.equal(received.filter(e => e.type === 'conversation.item.create' && e.item?.type === 'function_call_output').length, 2);
  assert.equal((await call(`/sessions/${sessionId}/tools/b/result`, 'POST', { result: {} })).status, 404);
  upstream.send(JSON.stringify({ type: 'response.created' }));
  upstream.send(JSON.stringify({ type: 'response.function_call_arguments.done', call_id: 'cancel-me', name: 'propose_trade', arguments: '{"offerIndex":0}' }));
  await until(() => bridge.sessions.get(sessionId).pending.size === 1);
  const repliesBeforeInterrupt = received.filter(e => e.type === 'response.create').length;
  client.send(JSON.stringify({ type: 'interrupt' }));
  await until(() => bridge.sessions.get(sessionId).pending.size === 0);
  upstream.send(JSON.stringify({ type: 'response.done', response: { status: 'cancelled' } }));
  upstream.send(JSON.stringify({ type: 'response.function_call_arguments.done', call_id: 'late', name: 'get_trades', arguments: '{}' }));
  await delay(30);
  assert.equal(received.filter(e => e.type === 'response.create').length, repliesBeforeInterrupt);
  assert.equal((await (await call(`/sessions/${sessionId}/tools`)).json()).length, 0);
  assert.equal((await call(`/sessions/${sessionId}/tools/cancel-me/result`, 'POST', { result: {} })).status, 404);
  await call(`/sessions/${sessionId}`, 'DELETE'); assert.equal(bridge.sessions.size, 0);
});
test('missing secrets fail safely', async t => {
  assert.throws(() => createBridge({ token: '' }), /HIGGS_BRIDGE_TOKEN/);
  const bridge = createBridge({ token }); bridge.server.listen(0, '127.0.0.1'); await once(bridge.server, 'listening'); t.after(() => bridge.close());
  const response = await fetch(`http://127.0.0.1:${bridge.server.address().port}/sessions`, { method: 'POST', headers: { Authorization: `Bearer ${token}` } });
  assert.equal(response.status, 503);
});

test('hands-free VAD survives long silence, repeated turns, interruption and nonfatal errors', async t => {
  const mock = new WebSocketServer({ port: 0, host: '127.0.0.1' }); await once(mock, 'listening');
  const received = []; const logs = []; let upstream;
  mock.on('connection', ws => { upstream = ws; ws.on('message', data => { const event = JSON.parse(data); received.push(event); if (event.type === 'session.update') ws.send(JSON.stringify({ type: 'session.created' })); }); });
  const bridge = createBridge({ token, apiKey: 'test-key', upstreamUrl: `ws://127.0.0.1:${mock.address().port}`, log: entry => logs.push(entry) });
  bridge.server.listen(0, '127.0.0.1'); await once(bridge.server, 'listening');
  t.after(async () => { await bridge.close(); for (const c of mock.clients) c.terminate(); await new Promise(r => mock.close(r)); });
  const base = `http://127.0.0.1:${bridge.server.address().port}`;
  const response = await fetch(base + '/sessions', { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ playerId: 'p', villagerId: 'v', context: {} }) });
  const { sessionId, ticket } = await response.json();
  const client = new WebSocket(base.replace('http:', 'ws:') + '/audio?ticket=' + ticket); const events = []; client.on('message', data => events.push(JSON.parse(data))); await once(client, 'open');
  await until(() => events.some(e => e.message === 'Ready'));
  assert.deepEqual(received[0].session.audio.input.turn_detection, { type: 'server_vad', silence_duration_ms: 900 });
  // 65 seconds of PCM silence, delivered quickly in bounded chunks: never an input turn cap.
  const audio = Buffer.alloc(48_000).toString('base64');
  for (let i = 0; i < 65; i++) { client.send(JSON.stringify({ type: 'audio', audio })); await until(() => received.filter(e => e.type === 'input_audio_buffer.append').length === i + 1); }
  assert.equal(bridge.sessions.get(sessionId).inputBytes, 0);
  assert.equal(events.filter(e => e.type === 'error').length, 0);
  const emit = event => upstream.send(JSON.stringify(event));
  for (let i = 0; i < 3; i++) {
    emit({ type: 'input_audio_buffer.speech_started' });
    emit({ type: 'input_audio_buffer.speech_stopped' });
    emit({ type: 'response.created', response: { id: `r${i}` } });
    // Continuous capture must not suppress the output while Higgs is talking.
    client.send(JSON.stringify({ type: 'audio', audio }));
    emit({ type: 'response.output_audio.delta', delta: 'AAA=' });
    emit({ type: 'response.done', response: { id: `r${i}`, status: 'completed' } });
    await until(() => events.filter(e => e.type === 'audio').length === i + 1);
  }
  assert.equal(events.filter(e => e.type === 'speech' && e.active).length, 3);
  assert.equal(received.filter(e => ['response.create', 'input_audio_buffer.commit', 'response.cancel'].includes(e.type)).length, 0);
  emit({ type: 'response.created', response: { id: 'old' } });
  emit({ type: 'response.function_call_arguments.done', call_id: 'stale', name: 'propose_trade', arguments: '{"offerIndex":0}' });
  await until(() => bridge.sessions.get(sessionId).pending.size === 1);
  emit({ type: 'input_audio_buffer.speech_started' });
  emit({ type: 'response.output_audio.delta', delta: 'STALE' });
  emit({ type: 'response.done', response: { id: 'old', status: 'cancelled' } });
  emit({ type: 'input_audio_buffer.speech_stopped' });
  emit({ type: 'response.created', response: { id: 'new' } });
  // Late response.done for a cancelled response must not poison the new response.
  emit({ type: 'response.done', response: { id: 'old', status: 'cancelled' } });
  emit({ type: 'error', error: { code: 'response_cancel_not_active', message: 'PRIVATE PROVIDER DETAIL' } });
  emit({ type: 'response.output_audio.delta', delta: 'NEW=' });
  await until(() => events.some(e => e.audio === 'NEW='));
  assert.equal(events.some(e => e.audio === 'STALE'), false);
  assert.equal(bridge.sessions.get(sessionId).pending.size, 0);
  assert.equal(events.find(e => e.code === 'response_cancel_not_active').fatal, false);
  assert.equal(client.readyState, WebSocket.OPEN);
  assert.equal(JSON.stringify(logs).includes('PRIVATE PROVIDER DETAIL'), false);
  emit({ type: 'input_audio_buffer.speech_started' });
  await until(() => bridge.sessions.get(sessionId).recording === true);
  client.send(JSON.stringify({ type: 'mute', muted: true }));
  await until(() => bridge.sessions.get(sessionId).muted === true);
  assert.equal(bridge.sessions.get(sessionId).recording, false);
  assert.ok(received.some(e => e.type === 'input_audio_buffer.clear'));
  client.send(JSON.stringify({ type: 'mute', muted: false }));
  await until(() => bridge.sessions.get(sessionId).muted === false);
  emit({ type: 'input_audio_buffer.speech_started' });
  emit({ type: 'input_audio_buffer.speech_stopped' });
  emit({ type: 'response.created', response: { id: 'unmuted' } });
  emit({ type: 'response.output_audio.delta', delta: 'RESUMED=' });
  await until(() => events.some(e => e.audio === 'RESUMED='));
  // response.created can race ahead of speech_stopped; a new response must clear
  // the previous response's cancellation even while the player is still speaking.
  emit({ type: 'input_audio_buffer.speech_started' });
  emit({ type: 'response.created', response: { id: 'reordered' } });
  emit({ type: 'response.output_audio.delta', response_id: 'reordered', delta: 'AAA=' });
  emit({ type: 'input_audio_buffer.speech_stopped' });
  emit({ type: 'response.output_audio.delta', response_id: 'old', delta: 'AAA=' });
  emit({ type: 'response.output_audio.delta', response_id: 'reordered', delta: 'FINAL=' });
  await until(() => events.some(e => e.audio === 'FINAL='));
  await delay(1100);
  const diagnostic = events.filter(e => e.type === 'diagnostics').at(-1);
  assert.ok(diagnostic.inputBytes >= 65 * 48_000);
  assert.ok(diagnostic.outputBytes > 0);
  assert.ok(diagnostic.speechStarts >= 6);
  assert.ok(diagnostic.speechStops >= 5);
  assert.ok(diagnostic.responses >= 6);
  assert.equal(typeof diagnostic.firstAudioLatencyMs, 'number');
  assert.equal(diagnostic.upstreamReady, true);
  assert.equal(diagnostic.pendingTools, 0);
  assert.equal(diagnostic.lastError, 'response_cancel_not_active');
  assert.equal(diagnostic.lastEvent, 'response.output_audio.delta');
  assert.ok(diagnostic.suppressedAudio.speaking > 0);
  assert.ok(diagnostic.suppressedAudio.staleResponse > 0);
  assert.ok(logs.some(e => e.stage === 'speech_stopped'));
  assert.ok(logs.some(e => e.stage === 'first_audio' && typeof e.firstAudioLatencyMs === 'number'));
  upstream.close(1013, 'PRIVATE CLOSE DETAIL');
  await until(() => !bridge.sessions.has(sessionId));
  assert.ok(logs.some(e => e.event === 'session_closed' && e.reason === 'upstream_closed' && e.code === 1013));
  assert.equal(JSON.stringify(logs).includes('PRIVATE CLOSE DETAIL'), false);
});

test('voice confirmations carry trusted input-turn IDs, never model-supplied IDs', async t => {
  const mock = new WebSocketServer({port: 0, host: '127.0.0.1'}); await once(mock, 'listening');
  let upstream; const received=[];
  mock.on('connection', ws => { upstream = ws; ws.on('message', data => {
    received.push(JSON.parse(data));
    if (JSON.parse(data).type === 'session.update') ws.send(JSON.stringify({type:'session.created'}));
  }); });
  const bridge = createBridge({token, apiKey:'test', upstreamUrl:`ws://127.0.0.1:${mock.address().port}`, log:()=>{}});
  bridge.server.listen(0,'127.0.0.1'); await once(bridge.server,'listening');
  t.after(async()=>{await bridge.close(); for(const ws of mock.clients) ws.terminate(); await new Promise(r=>mock.close(r));});
  const base=`http://127.0.0.1:${bridge.server.address().port}`;
  const response=await fetch(base+'/sessions',{method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify({playerId:'p',villagerId:'v',context:{}})});
  const {sessionId,ticket}=await response.json();
  const client=new WebSocket(base.replace('http:','ws:')+'/audio?mode=ptt&ticket='+ticket);
  const captions=[]; client.on('message',raw=>{ const event=JSON.parse(raw); if(event.type==='transcript')captions.push(event); });
  await once(client,'open'); await until(()=>bridge.sessions.get(sessionId).ready);
  const emit=e=>upstream.send(JSON.stringify(e));
  emit({type:'conversation.item.input_audio_transcription.completed',item_id:'u1',transcript:'Bread please'});
  emit({type:'response.created',response:{id:'captions'}});
  emit({type:'response.output_audio_transcript.delta',response_id:'captions',item_id:'a1',delta:'Here '});
  emit({type:'response.output_audio_transcript.done',response_id:'captions',item_id:'a1',transcript:'Here you go.'});
  emit({type:'response.output_audio_transcript.delta',response_id:'stale',item_id:'a0',delta:'obsolete'});
  await until(()=>captions.length===3);
  assert.deepEqual(captions.map(e=>[e.speaker,e.text,e.replace]),[['you','Bread please',true],['villager','Here ',false],['villager','Here you go.',true]]);
  emit({type:'response.done',response:{id:'captions',status:'completed'}});
  const prepare = async turn => {
    emit({type:'input_audio_buffer.committed',item_id:'committed-'+turn});
    emit({type:'conversation.item.input_audio_transcription.completed',item_id:'committed-'+turn,transcript:'Yes'});
    await until(()=>[...bridge.sessions.get(sessionId).pending.values()].some(tool=>tool.memoryContext));
    const tool=[...bridge.sessions.get(sessionId).pending.values()].find(tool=>tool.memoryContext);
    await fetch(base+`/sessions/${sessionId}/tools/${tool.callId}/result`, {method:'POST',
      headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify({result:{memories:[]}})});
  };
  const proposeId='a'.repeat(8)+'-aaaa-aaaa-aaaa-'+ 'a'.repeat(12);
  for(let turn=1;turn<=2;turn++){
    if(turn>1){
      client.send(JSON.stringify({type:'interrupt'}));
      await until(()=>bridge.sessions.get(sessionId).awaitingCancellation);
      emit({type:'response.done',response:{id:'r'+(turn-1),status:'cancelled'}});
    }
    client.send(JSON.stringify({type:'audio',audio:Buffer.alloc(4800).toString('base64')}));
    client.send(JSON.stringify({type:'commit'}));
    await until(()=>bridge.sessions.get(sessionId).turnId===turn);
    await prepare(turn);
    emit({type:'response.created',response:{id:'r'+turn}});
    for(const suffix of ['a','b'])emit({type:'response.function_call_arguments.done',call_id:turn+suffix,name:'confirm_trade',arguments:JSON.stringify({proposalId:proposeId,turnId:999})});
    await until(()=>bridge.sessions.get(sessionId).pending.has(turn+'b'));
    for(const suffix of ['a','b'])assert.equal(bridge.sessions.get(sessionId).pending.get(turn+suffix).turnId,turn);
  }
  // A short yes can finish before cancellation acknowledgement arrives.
  client.send(JSON.stringify({type:'interrupt'}));
  await until(()=>received.some(e=>e.type==='response.cancel'));
  const responses=received.filter(e=>e.type==='response.create').length;
  client.send(JSON.stringify({type:'audio',audio:Buffer.alloc(9600).toString('base64')}));
  client.send(JSON.stringify({type:'commit'}));
  await until(()=>bridge.sessions.get(sessionId).turnId===3);
  await delay(30);
  assert.equal(received.filter(e=>e.type==='response.create').length,responses);
  emit({type:'response.done',response:{id:'r2',status:'cancelled'}});
  await until(()=>received.filter(e=>e.type==='input_audio_buffer.commit').length===3);
  await prepare(3);
  await until(()=>received.filter(e=>e.type==='response.create').length===responses+1);
  emit({type:'response.created',response:{id:'confirmed'}});
  emit({type:'response.function_call_arguments.done',response_id:'r2',call_id:'late-old',name:'confirm_trade',arguments:JSON.stringify({proposalId:proposeId})});
  emit({type:'response.function_call_arguments.done',response_id:'confirmed',call_id:'yes',name:'confirm_trade',arguments:JSON.stringify({proposalId:proposeId})});
  await until(()=>bridge.sessions.get(sessionId).pending.has('yes'));
  assert.equal(bridge.sessions.get(sessionId).pending.get('yes').turnId,3);
  assert.equal(bridge.sessions.get(sessionId).pending.has('late-old'),false);

});

test('compliments in the transcript reach the game server even if the model skips report_treatment', async t => {
  const mock = new WebSocketServer({port: 0, host: '127.0.0.1'}); await once(mock, 'listening');
  let upstream; const received=[];
  mock.on('connection', ws => { upstream = ws; ws.on('message', data => {
    received.push(JSON.parse(data));
    if (JSON.parse(data).type === 'session.update') ws.send(JSON.stringify({type:'session.created'}));
  }); });
  const bridge = createBridge({token, apiKey:'test', upstreamUrl:`ws://127.0.0.1:${mock.address().port}`, log:()=>{}});
  bridge.server.listen(0,'127.0.0.1'); await once(bridge.server,'listening');
  t.after(async()=>{await bridge.close(); for(const ws of mock.clients) ws.terminate(); await new Promise(r=>mock.close(r));});
  const base=`http://127.0.0.1:${bridge.server.address().port}`;
  const auth={Authorization:`Bearer ${token}`,'Content-Type':'application/json'};
  const response=await fetch(base+'/sessions',{method:'POST',headers:auth,body:JSON.stringify({playerId:'p',villagerId:'v',context:{}})});
  const {sessionId,ticket}=await response.json();
  const client=new WebSocket(base.replace('http:','ws:')+'/audio?mode=ptt&ticket='+ticket);
  await once(client,'open'); await until(()=>bridge.sessions.get(sessionId).ready);
  const emit=e=>upstream.send(JSON.stringify(e));
  client.send(JSON.stringify({type:'audio',audio:Buffer.alloc(4800).toString('base64')}));
  client.send(JSON.stringify({type:'commit'}));
  await until(()=>bridge.sessions.get(sessionId).turnId===1);
  emit({type:'conversation.item.input_audio_transcription.completed',item_id:'u1',transcript:'Your eyes are beautiful'});
  emit({type:'conversation.item.input_audio_transcription.completed',item_id:'u1',transcript:'Your eyes are beautiful'});
  await until(()=>bridge.sessions.get(sessionId).pending.size>0);
  const tools=await (await fetch(base+`/sessions/${sessionId}/tools`,{headers:auth})).json();
  assert.deepEqual(tools.map(({name,arguments:args,internal,turnId})=>({name,args,internal,turnId})),
    [{name:'report_treatment',args:{treatment:'polite'},internal:true,turnId:1}]);
  const posted=await fetch(base+`/sessions/${sessionId}/tools/${encodeURIComponent(tools[0].callId)}/result`,
    {method:'POST',headers:auth,body:JSON.stringify({result:{status:'assessed'}})});
  assert.equal(posted.status,200);
  await delay(30);
  assert.equal(received.some(e=>e.type==='conversation.item.create'&&e.item?.type==='function_call_output'),false,
    'the model never sees the internal assessment');
});
