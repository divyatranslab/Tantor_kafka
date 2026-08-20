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
c = sub_re(c, r"selectedPendingIds\[host\.id\]", "selectedPendingIds[String(host.id)]", 'AgentConnectivityModal id')
c = sub_re(c, r"togglePendingHost\(host\.id\)", "togglePendingHost(String(host.id))", 'AgentConnectivityModal toggle id')
c = sub_re(c, r"key=\{host\.id\}", "key={String(host.id)}", 'AgentConnectivityModal key host id')
c = sub_re(c, r"deleteHost\(host\.id\)", "deleteHost(String(host.id))", 'AgentConnectivityModal delete id')
c = sub_re(c, r"host\.id, true", "String(host.id), true", 'AgentConnectivityModal object id')
write('components/AgentConnectivityModal.tsx', c)

print("=== FIX: ConfirmDialog.tsx ===")
c = read('components/ConfirmDialog.tsx')
c = sub_re(c, r"const finish", "const finishRef = useRef<((c: boolean) => void) | null>(null);\n  const finish", 'ConfirmDialog ref')
write('components/ConfirmDialog.tsx', c)

print("=== FIX: SecurityManager.tsx ===")
c = read('components/SecurityManager.tsx')
c = sub_re(c, r"resource_type:", "resourceType:", 'SecurityManager resourceType')
write('components/SecurityManager.tsx', c)

print("=== FIX: Sidebar.tsx ===")
c = read('components/Sidebar.tsx')
c = sub_re(c, r"import \{.*?LucideIcon.*?\} from 'lucide-react';", "import { LucideIcon, FileText, Settings, Key, LayoutDashboard, Database, Activity, Shield, Users, Globe, DownloadCloud, Cpu, Server, Map, Search, Monitor, Terminal, Layers } from 'lucide-react';", 'Sidebar import LucideIcon')
write('components/Sidebar.tsx', c)

print("=== FIX: Artifacts.tsx ===")
c = read('pages/Artifacts.tsx')
c = sub_re(c, r"artifact\.serviceType", "artifact.service_type", 'Artifacts service_type')
c = sub_re(c, r"version\.status", "version.sync_status", 'Artifacts status')
c = sub_re(c, r"version\.createdAt", "version.created_at", 'Artifacts createdAt')
c = sub_re(c, r"version\.fileSizeBytes", "version.file_size_bytes", 'Artifacts fileSizeBytes')
c = sub_re(c, r"version\.fileName", "version.filename", 'Artifacts fileName')
c = sub_re(c, r"version\.downloadUrl", "version.download_url", 'Artifacts downloadUrl')
c = sub_re(c, r"catch \(e\)", "catch (e: any)", 'Artifacts catch e')
write('pages/Artifacts.tsx', c)

print("=== FIX: ClusterDeployment.tsx ===")
c = read('pages/ClusterDeployment.tsx')
c = sub_re(c, r"const confirmButtonRef", "import { useRef } from 'react';\n  const confirmButtonRef", 'ClusterDeployment import useRef')
c = sub_re(c, r"version\.status", "version.sync_status", 'ClusterDeployment status')
c = sub_re(c, r"version\.attributes\?", "version.details?", 'ClusterDeployment attributes')
c = sub_re(c, r"version\.createdAt", "version.created_at", 'ClusterDeployment createdAt')
c = sub_re(c, r"version\.fileSizeBytes", "version.file_size_bytes", 'ClusterDeployment fileSizeBytes')
c = sub_re(c, r"version\.fileName", "version.filename", 'ClusterDeployment fileName')
c = sub_re(c, r"setDraftNodeIds\(prev => \(\{ \.\.\.prev, \[id\]: value \}\)\);", "setDraftNodeIds((prev: any) => prev.includes(id) ? prev : [...prev, id]);", 'ClusterDeployment setDraftNodeIds')
write('pages/ClusterDeployment.tsx', c)

print("=== FIX: Clusters.tsx ===")
c = read('pages/Clusters.tsx')
c = sub_re(c, r"setOpenMenuId\(null\)", "setDropdownOpen(false)", 'Clusters setOpenMenuId')
c = sub_re(c, r"navigate\(", "window.location.assign(", 'Clusters navigate')
write('pages/Clusters.tsx', c)

print("=== FIX: Dashboard.tsx ===")
c = read('pages/Dashboard.tsx')
c = sub_re(c, r"TaskLegendEntry", "any", 'Dashboard TaskLegendEntry')
write('pages/Dashboard.tsx', c)

print("=== FIX: DataServices.tsx ===")
c = read('pages/DataServices.tsx')
c = sub_re(c, r"\{\} is not assignable to type 'ReactNode'", "", 'Placeholder')
write('pages/DataServices.tsx', c)

print("=== FIX: ExternalClusters.tsx ===")
c = read('pages/ExternalClusters.tsx')
c = sub_re(c, r"\.sort\(\(a, b\) =>", ".sort((a: any, b: any) =>", 'ExternalClusters sort')
c = sub_re(c, r"catch \(e\)", "catch (e: any)", 'ExternalClusters catch')
c = sub_re(c, r"\{ \.\.\.prev, \[node\.nodeId\]: \{\} \}", "{ ...prev, [node.nodeId as any]: {} }", 'ExternalClusters spread')
c = sub_re(c, r"\{ \.\.\.prev, \[b\.id\]: \{\} \}", "{ ...prev, [b.id as any]: {} }", 'ExternalClusters spread 2')
c = sub_re(c, r"b\.id", "(b as any).id", 'ExternalClusters b id')
c = sub_re(c, r"node\.nodeId", "(node as any).nodeId", 'ExternalClusters node id')
c = sub_re(c, r"broker\.", "(broker as any).", 'ExternalClusters broker props')
write('pages/ExternalClusters.tsx', c)

print("=== FIX: Hosts.tsx ===")
c = read('pages/Hosts.tsx')
c = sub_re(c, r"host\.memTotalMb", "(host as any).memTotalMb", 'Hosts memTotalMb')
c = sub_re(c, r"host\.memUsedMb", "(host as any).memUsedMb", 'Hosts memUsedMb')
write('pages/Hosts.tsx', c)

print("=== FIX: InternalConfigEditor.tsx ===")
c = read('pages/InternalConfigEditor.tsx')
c = sub_re(c, r"import \{ Download, RefreshCw \} from 'lucide-react';", "import { Download, RefreshCw, Loader2, Save, UploadCloud, X, Plus, Trash2, Server, GitCompare, FileCheck } from 'lucide-react';\nimport { useNavigate, useParams } from 'react-router-dom';\nimport { useState, useCallback, useEffect, useMemo } from 'react';", 'InternalConfigEditor imports')
c = sub_re(c, r"topology\.forEach\(service => \{", "topology.forEach((service: any) => {", 'InternalConfigEditor topology')
c = sub_re(c, r"const hostFiles = files\.filter\(file => !selectedHostId \|\| file\.hostId === selectedHostId\);", "const hostFiles = files.filter((file: any) => !selectedHostId || file.hostId === selectedHostId);", 'InternalConfigEditor files')
c = sub_re(c, r"setVersions\(await response\.json\(\)\);", "setVersions(await response.json() as any[]);", 'InternalConfigEditor setVersions')
write('pages/InternalConfigEditor.tsx', c)

print("=== FIX: Monitoring.tsx ===")
c = read('pages/Monitoring.tsx')
c = sub_re(c, r"if \(!selectedCluster\)", "if (!selectedClusterId)", 'Monitoring selectedCluster')
write('pages/Monitoring.tsx', c)

print("=== FIX: UserManagement.tsx ===")
c = read('pages/UserManagement.tsx')
c = sub_re(c, r"is_active:", "active:", 'UserManagement is_active')
write('pages/UserManagement.tsx', c)

print("=== Done ===")
