"""Verify completed-study provenance, paired audio and stored waveform integrity."""
import json, pathlib, sys, hashlib, wave, collections
root=pathlib.Path(sys.argv[1]); raw=(root/'results.json').read_bytes();r=json.loads(raw)
sha=lambda b:hashlib.sha256(b).hexdigest()
assert r['complete'] and len(r['results'])==r['selectedTrials']==576
assert len(set(r['schedule']))==576
assert set(r['schedule'])=={x['trialId'] for x in r['results']}
lookup={x['trialId']:x for x in r['results']}
for i in range(0,576,2):
    pair=r['schedule'][i:i+2];assert {x.split('/')[0] for x in pair}=={'higgs','openrouter'}
    a,b=[lookup[x] for x in pair]
    assert (a['fixtureId'],a['repeat'])==(b['fixtureId'],b['repeat'])
journal=[json.loads(line) for line in (root/'results.jsonl').read_text().splitlines()]
assert journal==r['results']
for name,digest in r['sourceHashes'].items():
    snapshot=root/'sources'/(name.replace('../','').replace('/','__')+'.txt')
    assert sha(snapshot.read_bytes())==digest,name
inputs={sha(p.read_bytes()):p for p in (root/'audio').glob('input-*.wav')}
artifacts={}; duration=collections.Counter(); audio_matches=collections.defaultdict(set)
for t in r['results']:
    assert t['audioHash'] in inputs
    audio_matches[t['type'],t['scenario']].add(t['audioHash'])
    if t['status']=='completed': assert any(x['audioBytes']>0 for x in t['rounds'])
    if t.get('judge'):
        assert t['judge']['recoveryQuality']==all(c['pass'] for c in t['judge']['criteria'])
        assert t['judge']['judgeVersion']==r['judgeVersion']
    for attempt in t['attempts']:
        for turn in attempt['rounds']:
            if not turn['audioBytes']: continue
            a=turn['audioArtifact'];p=root/'audio'/a['path'];b=p.read_bytes();assert sha(b)==a['sha256']
            with wave.open(str(p)) as w:
                assert (w.getnchannels(),w.getsampwidth(),w.getframerate())==(1,2,24000)
                assert w.getnframes()*2==turn['audioBytes']
                duration[t['provider']]+=w.getnframes()/24000
            artifacts[str(p)]=len(b)
assert all(len(v)==1 for v in audio_matches.values())
summary={'status':'passed','raw_sha256':sha(raw),'trials':576,'unique_schedule_ids':576,'journal_matches_snapshot':True,'source_snapshots_verified':len(r['sourceHashes']),'matched_input_waveforms':len(inputs),'unique_output_waveforms_verified':len(artifacts),'output_audio_seconds_all_attempts':dict(duration),'output_audio_bytes':sum(artifacts.values()),'note':'File/PCM integrity verified; this is not a listening or independent transcription audit.'}
(root/'integrity.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary,indent=2))
