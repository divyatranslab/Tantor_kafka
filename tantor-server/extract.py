import json

log_path = r'C:\Users\Jayesh\.gemini\antigravity-ide\brain\4e2bb346-d406-48f1-b29f-81e146c93c35\.system_generated\logs\transcript.jsonl'
target = 'DataServicesController.java'

last_content = None
with open(log_path, 'r', encoding='utf-8') as f:
    for line in f:
        try:
            d = json.loads(line)
            if 'multi_replace_file_content' in str(d) and target in str(d):
                if 'ReplacementChunks' in str(d):
                    last_content = d
        except:
            pass

if last_content:
    print("Found replacing call!")
    with open('last_call.json', 'w', encoding='utf-8') as out:
        json.dump(last_content, out, indent=2)
else:
    print("Not found")
