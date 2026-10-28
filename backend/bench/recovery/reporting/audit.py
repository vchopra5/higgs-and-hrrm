"""Materialize the assistant trace review from explicitly recorded case labels.

Labels are fixed in audit-labels.json after reading transcripts and tool results;
this script does not generate expected labels from the original judge verdicts.
"""
import json,pathlib,sys,hashlib,collections
root=pathlib.Path(sys.argv[1]); raw=(root/'results.json').read_bytes();r=json.loads(raw)
labels=json.loads((root/'audit-labels.json').read_text()); reviews=[]
for x in sorted(r['results'],key=lambda x:(x['type'],x['depth'],x['provider'])):
    if not(x['scenario']=='axe' and x['repeat']==1 and x['arm']=='interrupted'):continue
    key=f"{x['type']}/{x['depth']}/{x['provider']}"; label=labels[key]; orig=x['judge']
    review={'trialId':x['trialId'],'taskFulfillment':label[0],'recoveryQuality':label[1],
      'rationale':label[2],'originalTask':orig['taskFulfillment'],'originalRecovery':orig['recoveryQuality'],
      'taskDisagreement':label[0]!=orig['taskFulfillment'],'recoveryDisagreement':label[1]!=orig['recoveryQuality'],
      'transcript':x['transcript'],'calls':x['calls'],'rounds':x['rounds'],'actionCorrect':x['scores']['actionCorrect']}
    reviews.append(review)
assert len(reviews)==len(labels)==48
summary={'n':48,'task_disagreements':sum(x['taskDisagreement'] for x in reviews),'recovery_disagreements':sum(x['recoveryDisagreement'] for x in reviews),'either_disagreements':sum(x['taskDisagreement'] or x['recoveryDisagreement'] for x in reviews),'gatedTaskDisagreements':sum(x['actionCorrect'] and x['taskDisagreement'] for x in reviews),'gatedRecoveryDisagreements':sum(x['actionCorrect'] and x['recoveryDisagreement'] for x in reviews)}
output={'raw_sha256':hashlib.sha256(raw).hexdigest(),'selection':'Predeclared: axe scenario, repeat 1, interrupted arm, every type/depth/model.','reviewer':'Codex assistant; unblinded transcript/tool-trace review, not independent human annotation or listening audit.','policy':'Preserve frozen primary scores; document disagreements and audited-subsample sensitivity separately.','summary':summary,'results':reviews}
for m in ['higgs','openrouter']:
    subset=[x for x in reviews if x['trialId'].startswith(m+'/')]
    summary[m]={k:sum(x['actionCorrect'] and x[v] for x in subset) for k,v in [('auditedTask','taskFulfillment'),('auditedRecovery','recoveryQuality'),('originalTask','originalTask'),('originalRecovery','originalRecovery')]};summary[m]['n']=len(subset)
(root/'trace-audit.json').write_text(json.dumps(output,indent=2)+'\n');print(json.dumps(summary,indent=2))
