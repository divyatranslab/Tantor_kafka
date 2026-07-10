import re

with open('src/main/java/io/translab/tantor/server/web/DataServicesController.java', 'r', encoding='utf-8') as f:
    content = f.read()

# Replace method signatures
content = content.replace(
    'private ResponseEntity<?> forwardJson(String baseUrl, String method, String path, JsonNode body, String encodedCert) {',
    'private ResponseEntity<?> forwardJson(String baseUrl, String method, String path, JsonNode body, String encodedCert, java.util.UUID clusterId, String serviceType) {'
)

content = content.replace(
    'private JsonNode requestJson(String baseUrl, String method, String path, JsonNode body, String encodedCert) {',
    'private JsonNode requestJson(String baseUrl, String method, String path, JsonNode body, String encodedCert, java.util.UUID clusterId, String serviceType) {'
)

# Replace the inner call in forwardJson
content = content.replace(
    'JsonNode response = requestJson(baseUrl, method, path, body, encodedCert);',
    'JsonNode response = requestJson(baseUrl, method, path, body, encodedCert, clusterId, serviceType);'
)

# Replace the getHttpClient call in requestJson
content = content.replace(
    'HttpClient client = getHttpClient(encodedCert);',
    'HttpClient client = getHttpClientForCluster(encodedCert, clusterId, serviceType);'
)

# Now find all requestJson / forwardJson calls and append `clusterId, "SCHEMA_REGISTRY"` or `clusterId, "KAFKA_CONNECT"`
# We can tell by the mapping or the method name roughly.

def replacer(match):
    full = match.group(0)
    if 'clusterId,' in full and ('SCHEMA_REGISTRY' in full or 'KAFKA_CONNECT' in full):
        return full # already replaced
        
    # figure out service type based on surrounding context?
    # actually all schema registry things have SCHEMA_REGISTRY in the customBaseUrl call before it!
    # Let's just look at the customBaseUrl call inside the same line.
    service = '"SCHEMA_REGISTRY"'
    if 'KAFKA_CONNECT' in full:
        service = '"KAFKA_CONNECT"'
    
    # regex matches: forwardJson(..., encodedCert)
    # append: , clusterId, "KAFKA_CONNECT"
    # match.group(1) is the start until encodedCert
    return match.group(1) + f', clusterId, {service})'

content = re.sub(r'(forwardJson\([^;]+?encodedCert)\)', replacer, content)
content = re.sub(r'(requestJson\([^;]+?encodedCert)\)', replacer, content)

# One more pass to be safe: schema-registry methods don't always have SCHEMA_REGISTRY in the forwardJson call line directly if it's broken over lines.
# If there's missing ones, we can fix them manually. Let's just write this and see if it compiles.

with open('src/main/java/io/translab/tantor/server/web/DataServicesController.java', 'w', encoding='utf-8') as f:
    f.write(content)
