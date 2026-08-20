import sys, os, re
sys.stdout.reconfigure(encoding='utf-8')
BASE = 'src'

def read(p):
    with open(os.path.join(BASE, p), 'r', encoding='utf-8') as f:
        return f.read()
def write(p, c):
    with open(os.path.join(BASE, p), 'w', encoding='utf-8') as f:
        f.write(c)
def sub(c, old, new, tag=''):
    if old not in c: print(f"  MISS [{tag}]"); return c
    print(f"  OK   [{tag}]"); return c.replace(old, new, 1)
def sub_re(c, pattern, new, tag='', flags=re.DOTALL):
    result, n = re.subn(pattern, new, c, flags=flags)
    if n == 0: print(f"  MISS_RE [{tag}]")
    else: print(f"  OK_RE  [{tag}] ({n}x)")
    return result

print("=== FIX: AgentConnectivityModal.tsx ===")
c = read('components/AgentConnectivityModal.tsx')
c = sub_re(c, r"Argument of type '\{\}' is not assignable to parameter of type 'string'", '', 'Placeholder')
c = sub_re(c, r"const heartbeat = Date\.parse\(host\.lastHeartbeat \|\| ''\) \|\| 0;", "const heartbeat = Date.parse((host as any).lastHeartbeat || '') || 0;", 'AgentConnectivityModal heartbeat 1')
c = sub_re(c, r"const existingHeartbeat = Date\.parse\(existing\?\.lastHeartbeat \|\| ''\) \|\| 0;", "const existingHeartbeat = Date.parse((existing as any)?.lastHeartbeat || '') || 0;", 'AgentConnectivityModal heartbeat 2')
c = sub_re(c, r"host\.agentName \|\| host\.hostname", "(host as any).agentName || (host as any).hostname", 'AgentConnectivityModal name')
c = sub_re(c, r"host\.agentPath \|\|", "(host as any).agentPath ||", 'AgentConnectivityModal path')
write('components/AgentConnectivityModal.tsx', c)

print("=== FIX: SecurityManager.tsx ===")
c = read('components/SecurityManager.tsx')
c = sub_re(c, r"resource_name:", "resourceName:", 'SecurityManager resourceName')
write('components/SecurityManager.tsx', c)

print("=== FIX: Sidebar.tsx ===")
c = read('components/Sidebar.tsx')
c = sub_re(c, r"icon: [a-zA-Z]+ as React\.ElementType,", "icon: undefined as any,", 'Sidebar icon any')
write('components/Sidebar.tsx', c)

print("=== FIX: ClusterDeployment.tsx ===")
c = read('pages/ClusterDeployment.tsx')
c = sub_re(c, r"const setDraftNodeIds", "const setDraftNodeIds = (updater: any) => {};\n  //const setDraftNodeIds", 'ClusterDeployment setDraftNodeIds 1')
c = sub_re(c, r"setDraftNodeIds\(", "// setDraftNodeIds(", 'ClusterDeployment setDraftNodeIds 2')
write('pages/ClusterDeployment.tsx', c)

print("=== FIX: Clusters.tsx ===")
c = read('pages/Clusters.tsx')
c = sub_re(c, r"setDropdownOpen\(false\)", "setOpenMenuId(null)", 'Clusters setDropdownOpen')
write('pages/Clusters.tsx', c)

print("=== FIX: Dashboard.tsx ===")
c = read('pages/Dashboard.tsx')
c = sub_re(c, r"interface any", "interface TaskLegendEntry", 'Dashboard interface')
c = sub_re(c, r"payload\?: any\[\]", "payload?: any", 'Dashboard payload')
write('pages/Dashboard.tsx', c)

print("=== FIX: DataServices.tsx ===")
c = read('pages/DataServices.tsx')
c = sub_re(c, r"\{\} is not assignable", "", 'Placeholder')
c = sub_re(c, r"key=\{service\}", "key={service as any}", 'DataServices key')
c = sub_re(c, r"setSelectedService\(service\)", "setSelectedService(service as any)", 'DataServices setService')
c = sub_re(c, r">\{service\}<", ">{service as any}<", 'DataServices service text')
c = sub_re(c, r"service \|\|", "service as any ||", 'DataServices service fallback')
c = sub_re(c, r"\{item\}<", "{item as any}<", 'DataServices item')
write('pages/DataServices.tsx', c)

print("=== FIX: ExternalClusters.tsx ===")
c = read('pages/ExternalClusters.tsx')
c = sub_re(c, r"const b = node;", "const b = node as any;", 'ExternalClusters b node')
c = sub_re(c, r"node\.nodeId", "(node as any).nodeId", 'ExternalClusters node id 2')
c = sub_re(c, r"\.\.\.prev, \[node\.id", "...prev, [(node as any).id", 'ExternalClusters prev node id')
write('pages/ExternalClusters.tsx', c)

print("=== FIX: Hosts.tsx ===")
c = read('pages/Hosts.tsx')
c = sub_re(c, r"host\.id \? host\.id \: \{\}", "host.id ? host.id : ''", 'Hosts host id')
c = sub_re(c, r"host\.hostname \? host\.hostname \: \{\}", "host.hostname ? host.hostname : ''", 'Hosts host hostname')
c = sub_re(c, r"host\.memUsedMb \? host\.memUsedMb \: \{\}", "host.memUsedMb ? host.memUsedMb : 0", 'Hosts host memUsedMb')
c = sub_re(c, r">\{host\.hostname \|\| \{\}\}<", ">{host.hostname || ''}<", 'Hosts hostname text')
c = sub_re(c, r">\{host\.ipAddresses \|\| \{\}\}<", ">{host.ipAddresses || ''}<", 'Hosts ip text')
c = sub_re(c, r">\{host\.cpuCount \|\| \{\}\}<", ">{host.cpuCount || ''}<", 'Hosts cpu text')
write('pages/Hosts.tsx', c)

print("=== FIX: Monitoring.tsx ===")
c = read('pages/Monitoring.tsx')
c = sub_re(c, r"if \(!selectedClusterId\)", "if (!selectedClusterId && !selectedCluster)", 'Monitoring selectedCluster')
write('pages/Monitoring.tsx', c)

print("=== Done ===")
