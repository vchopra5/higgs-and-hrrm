"""Descriptive paired analysis; no iid-binomial uncertainty claims on templated repeats."""
import json, csv, sys, pathlib, hashlib, collections
root = pathlib.Path(sys.argv[1])
raw = (root / 'results.json').read_bytes()
report = json.loads(raw)
rows = report['results']
assert len({r['trialId'] for r in rows}) == len(rows)
if '--partial' not in sys.argv:
    assert len(rows) == 576 and report.get('complete'), 'Full run must finish before final analysis'
models = ['higgs', 'openrouter']; depths = [2,6,12,20]
types = ['normal','impatient','correction','topic_switch','filler','pushback']
def summarize(group):
    judged = [r for r in group if r.get('scores') is not None]
    return dict(attempted=len(group), completed=sum(r['status']=='completed' for r in group), judged=len(judged),
        failed_or_missing=len(group)-len(judged), retries=sum(len(r.get('attempts',[]))-1 for r in group),
        task=sum(r.get('scores',{}).get('taskSuccess',False) for r in judged),
        recovery=sum(r.get('scores',{}).get('recoverySuccess',False) for r in judged),
        action=sum(r.get('scores',{}).get('actionCorrect',False) for r in judged))
def group_rows(model,arm=None,depth=None,type=None,repeat=None):
    return [r for r in rows if r['provider']==model and (arm is None or r['arm']==arm)
        and (depth is None or r['depth']==depth) and (type is None or r['type']==type) and (repeat is None or r['repeat']==repeat)]
cells = [dict(model=m,arm=a,depth=d,type=t,**summarize(group_rows(m,a,d,t)))
    for m in models for a in ['control','interrupted'] for d in depths for t in types]
by_depth = [dict(model=m,arm=a,depth=d,**summarize(group_rows(m,a,d))) for m in models for a in ['control','interrupted'] for d in depths]
by_type = [dict(model=m,arm=a,type=t,**summarize(group_rows(m,a,type=t))) for m in models for a in ['control','interrupted'] for t in types]
by_repeat = [dict(model=m,arm=a,depth=d,repeat=i,**summarize(group_rows(m,a,d,repeat=i))) for m in models for a in ['control','interrupted'] for d in depths for i in [1,2]]
lookup = {(r['provider'],r['arm'],r['type'],r['scenario'],r['repeat'],r['depth']):r for r in rows}
paired = []
for m in models:
    for a in ['control','interrupted']:
        for t in types:
            pairs = []
            for scenario in ['axe','sword','pickaxe']:
                for repeat in [1,2]:
                    lo=lookup.get((m,a,t,scenario,repeat,2)); hi=lookup.get((m,a,t,scenario,repeat,20))
                    if lo and hi and lo.get('scores') is not None and hi.get('scores') is not None:
                        pairs.append((lo,hi))
            paired.append(dict(model=m,arm=a,type=t,pairs=len(pairs),
                task_drop_pp=100*sum(int(lo['scores']['taskSuccess'])-int(hi['scores']['taskSuccess']) for lo,hi in pairs)/len(pairs) if pairs else None,
                recovery_drop_pp=100*sum(int(lo['scores']['recoverySuccess'])-int(hi['scores']['recoverySuccess']) for lo,hi in pairs)/len(pairs) if pairs else None))
penalties=[]
for m in models:
    for d in depths:
        ps=[]
        for t in types:
            for s in ['axe','sword','pickaxe']:
                for i in [1,2]:
                    c=lookup.get((m,'control',t,s,i,d)); b=lookup.get((m,'interrupted',t,s,i,d))
                    if c and b and c.get('scores') is not None and b.get('scores') is not None: ps.append((c,b))
        penalties.append(dict(model=m,depth=d,pairs=len(ps),
            task_penalty_pp=100*sum(int(c['scores']['taskSuccess'])-int(b['scores']['taskSuccess']) for c,b in ps)/len(ps) if ps else None,
            recovery_penalty_pp=100*sum(int(c['scores']['recoverySuccess'])-int(b['scores']['recoverySuccess']) for c,b in ps)/len(ps) if ps else None))
audio=collections.defaultdict(set)
for r in rows: audio[(r['type'],r['scenario'])].add(r['audioHash'])
assert all(len(v)==1 for v in audio.values()), 'Mismatched input audio'
if len(rows)==576: assert all(c['attempted']==6 for c in cells), 'Unbalanced cell coverage'
outputs = dict(raw_sha256=hashlib.sha256(raw).hexdigest(),n=len(rows),models=report['models'],
    overall=[dict(model=m,**summarize(group_rows(m))) for m in models],by_depth=by_depth,by_type=by_type,cells=cells,
    by_repeat=by_repeat,paired_depth_changes=paired,interruption_penalties=penalties,
    reported_openrouter_cost=sum(sum(round.get('usage',{}).get('cost',0) if round.get('usage') else 0 for round in attempt['rounds'])
        for r in rows for attempt in r.get('attempts',[r]))+sum((r.get('judge') or {}).get('usage',{}).get('cost',0) for r in rows),
    audio_matching=True,statistical_scope='Descriptive matched templated cases; no population confidence intervals or causal depth claim.')
(root/'analysis.json').write_text(json.dumps(outputs,indent=2)+'\n')
for name, data in [('cells',cells),('depth-rates',by_depth),('paired-depth-changes',paired),('interruption-penalties',penalties)]:
    with (root/(name+'.csv')).open('w') as f:
        w=csv.DictWriter(f,fieldnames=list(data[0]));w.writeheader();w.writerows(data)
with (root/'trial-results.csv').open('w') as f:
    keys=['trialId','provider','model','type','scenario','depth','arm','repeat','status','attempts','taskSuccess','recoverySuccess','actionCorrect','transcript']
    w=csv.DictWriter(f,fieldnames=keys);w.writeheader()
    for r in rows:
        w.writerow({k:(len(r.get('attempts',[])) if k=='attempts' else (r.get('scores') or {}).get(k,r.get(k))) for k in keys})
audit=[{k:r.get(k) for k in ['trialId','provider','type','depth','arm','transcript','calls','rounds','judge','scores']} for r in rows if r['scenario']=='axe' and r['repeat']==1 and r['arm']=='interrupted']
(root/'audit-sample.json').write_text(json.dumps(audit,indent=2)+'\n')
print(json.dumps(dict(n=outputs['n'],overall=outputs['overall'],cost=outputs['reported_openrouter_cost']),indent=2))
