import json

with open('lint_final.json', 'r', encoding='utf-8-sig') as f:
    data = json.load(f)

total_errors = sum(r['errorCount'] for r in data)
total_warnings = sum(r['warningCount'] for r in data)
print(f'Total errors: {total_errors}')
print(f'Total warnings: {total_warnings}')

rule_errors = {}
rule_warnings = {}
file_errors = {}
for r in data:
    fp = r['filePath']
    idx = fp.find('src\\')
    fname = fp[idx+4:] if idx >= 0 else fp
    if r['errorCount'] > 0:
        file_errors[fname] = r['errorCount']
    for msg in r['messages']:
        rule = msg.get('ruleId', '?')
        if msg['severity'] == 2:
            rule_errors[rule] = rule_errors.get(rule, 0) + 1
        else:
            rule_warnings[rule] = rule_warnings.get(rule, 0) + 1

print('\nErrors by rule (sorted):')
for rule, count in sorted(rule_errors.items(), key=lambda x: -x[1]):
    print(f'  {count:4d}  {rule}')

print('\nWarnings by rule (sorted):')
for rule, count in sorted(rule_warnings.items(), key=lambda x: -x[1]):
    print(f'  {count:4d}  {rule}')

print('\nFiles with errors (sorted):')
for fname, count in sorted(file_errors.items(), key=lambda x: -x[1]):
    print(f'  {count:4d}  {fname}')
