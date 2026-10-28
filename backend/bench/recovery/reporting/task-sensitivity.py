"""Post-hoc, explicitly labeled task-axis consistency sensitivity.

For normal and topic_switch only, the first frozen recovery criterion is exactly
that type's task requirement. Project it to the task axis, retaining action gates.
No other labels are changed; original outputs and primary analysis are immutable.
This is not a new independent judge or confirmation of the criterion's accuracy.
"""
import json,pathlib,sys,hashlib
root=pathlib.Path(sys.argv[1]);raw=(root/'results.json').read_bytes();r=json.loads(raw);assert r['complete']
rows=[];changes=[]
for x in r['results']:
    if x['scores'] is None: continue
    original=x['scores']['taskSuccess']
    projected=x['scores']['actionCorrect'] and x['judge']['criteria'][0]['pass'] if x['type'] in ['normal','topic_switch'] else original
    row={k:x[k] for k in ['trialId','provider','depth','arm','type']};row.update(originalTask=original,projectedTask=projected);rows.append(row)
    if original!=projected:changes.append({**row,'transcript':x['transcript'],'firstCriterion':x['judge']['criteria'][0],'explanation':x['judge']['explanation']})
summary=[]
for m in ['higgs','openrouter']:
 for arm in ['control','interrupted']:
  for d in [2,6,12,20]:
   sub=[x for x in rows if x['provider']==m and x['arm']==arm and x['depth']==d]
   summary.append(dict(model=m,arm=arm,depth=d,n=len(sub),originalTask=sum(x['originalTask'] for x in sub),projectedTask=sum(x['projectedTask'] for x in sub)))
out={'version':'posthoc-task-axis-projection-v1','raw_sha256':hashlib.sha256(raw).hexdigest(),'status':'Supplementary sensitivity, not the frozen primary score','rule':__doc__,'changes':changes,'by_depth':summary}
(root/'task-axis-sensitivity.json').write_text(json.dumps(out,indent=2)+'\n');print(json.dumps({'changes':len(changes),'by_depth':summary},indent=2))
