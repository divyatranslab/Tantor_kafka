import json

with open('last_call.json', 'r', encoding='utf-8') as f:
    call = json.load(f)

# The content is a json object representing the model's output or step
# Let's find the tool call for multi_replace_file_content for DataServicesController.java
tool_calls = call.get('tool_calls', [])
if not tool_calls and 'content' in call:
    # maybe it's in the string content?
    try:
        content = json.loads(call['content'])
        tool_calls = content.get('tool_calls', [])
    except:
        pass

for tc in tool_calls:
    if tc.get('name') == 'default_api:multi_replace_file_content':
        args = tc.get('arguments', {})
        if 'DataServicesController.java' in args.get('TargetFile', ''):
            with open(args['TargetFile'], 'r', encoding='utf-8') as src:
                src_text = src.read()
            
            for chunk in args.get('ReplacementChunks', []):
                target = chunk['TargetContent']
                replacement = chunk['ReplacementContent']
                if target in src_text:
                    src_text = src_text.replace(target, replacement)
                else:
                    print(f"Target not found: {target[:50]}...")
                    
            with open(args['TargetFile'], 'w', encoding='utf-8') as dst:
                dst.write(src_text)
            print("Patched successfully!")
            break
else:
    print("No valid tool call found in last_call.json")
