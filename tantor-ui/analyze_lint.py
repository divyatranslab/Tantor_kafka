import json

with open('lint_fresh.json', 'r', encoding='utf-8-sig') as f:
    data = json.load(f)

for r in data:
    if r['errorCount'] == 0 and r['warningCount'] == 0:
        continue
    fp = r['filePath']
    idx = fp.find('src\\')
    fname = fp[idx+4:] if idx >= 0 else fp
    print(f"\n=== {fname} ({r['errorCount']} errors, {r['warningCount']} warnings) ===")
    for msg in r['messages']:
        sev = 'ERR' if msg['severity'] == 2 else 'WARN'
        rule = msg.get('ruleId', '?')
        text = msg['message'][:120]
        print(f"  [{sev}] L{msg['line']}: {rule} - {text}")
