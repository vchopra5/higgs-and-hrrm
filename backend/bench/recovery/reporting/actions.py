"""Exact tool-action checks on every completed candidate, independent of judge availability."""
import json,pathlib,sys,collections,hashlib
root=pathlib.Path(sys.argv[1]);raw=(root/'results.json').read_bytes();r=json.loads(raw);assert r['complete']
keys=['noPurchaseAttempt','noNewQuest','noFalseDeliveryAction','correctAction']
rows=[]
for x in r['results']:
    checks=x['evaluation']['checks']
    row={k:x[k] for k in ['trialId','provider','depth','arm','type']};row.update(passed=all(checks[k] for k in keys),failures=[k for k in keys if not checks[k]],tools=[c['name'] for c in x['calls']]);rows.append(row)
by_depth=[];cells=[]
for m in ['higgs','openrouter']:
 for arm in ['control','interrupted']:
  for d in [2,6,12,20]:
   sub=[x for x in rows if x['provider']==m and x['arm']==arm and x['depth']==d]
   by_depth.append(dict(model=m,arm=arm,depth=d,n=len(sub),passed=sum(x['passed'] for x in sub)))
   for t in ['normal','impatient','correction','topic_switch','filler','pushback']:
    ts=[x for x in sub if x['type']==t];cells.append(dict(model=m,arm=arm,depth=d,type=t,n=len(ts),passed=sum(x['passed'] for x in ts)))
out={'raw_sha256':hashlib.sha256(raw).hexdigest(),'scope':'All 576 completed model outputs, including the one ungraded semantic trial. The frozen analysis.json action counts cover only judged rows.','by_depth':by_depth,'cells':cells,'failures':[x for x in rows if not x['passed']]}
(root/'action-diagnostics.json').write_text(json.dumps(out,indent=2)+'\n');print(json.dumps({'failures':len(out['failures']),'by_depth':by_depth},indent=2))
