#!/usr/bin/env python3
"""Read-only rumour snapshot audit. Filter by a unique phrase used in your test story."""
import argparse
import json
from collections import Counter
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--save', type=Path, default=Path('server-cardboard/world/data/higgs_hrrm_memories.json'))
parser.add_argument('--query', required=True, help='Distinct phrase from the test story, e.g. violet lantern')
args = parser.parse_args()
if not args.save.exists():
    raise SystemExit('No memory save yet. Tell a villager a story and wait for it to be saved.')
data = json.loads(args.save.read_text())
entries = data.get('memories', [])
roots = {m['rootId'] for m in entries if args.query.casefold() in m['summary'].casefold()}
matches = [m for m in entries if m['rootId'] in roots]
if not matches:
    raise SystemExit('No matching saved story. Check the spoken transcript and memory tool result; a summary may omit your query phrase.')
counts = Counter((m['rootId'], m['ownerId']) for m in matches)
failed = False
for m in sorted(matches, key=lambda item: (item['rootId'], item['hops'], item['ownerId'])):
    problems = []
    if counts[(m['rootId'], m['ownerId'])] > 1: problems.append('DUPLICATE owner/root')
    if m['privateMemory'] and m['hops'] > 0: problems.append('PRIVATE STORY SPREAD')
    failed |= bool(problems)
    print(json.dumps({key: m.get(key) for key in ['rootId', 'ownerId', 'originName', 'sourceName', 'hops', 'privateMemory', 'interest', 'createdAt', 'expiresAt']} | {'problems': problems}))
print(f'{len(roots)} distinct root story/stories; {len(matches)} stored copies. No summaries printed.')
raise SystemExit(1 if failed else 0)
