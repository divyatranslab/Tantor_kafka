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
    if old not in c:
        print(f"  MISS [{tag}]")
        return c
    print(f"  OK   [{tag}]")
    return c.replace(old, new, 1)

# ─── InternalConfigEditor.tsx ─────────────────────────────────────────────────
# Show current state of import after regex edits
c = read('pages/InternalConfigEditor.tsx')
lines = c.split('\n')
print("  ICE current import lines 3-8:")
for i, line in enumerate(lines, 1):
    if 3 <= i <= 10:
        print(f"    L{i}: {line.rstrip()}")

# Rebuild lucide import cleanly (remove CheckCircle2, FileText, RotateCcw which were removed by regex)
# Current state after regex: the lines might have orphaned commas
# Best: find the current import block and replace it
old_import_block = re.search(
    r"import \{[^}]+\} from 'lucide-react';",
    c,
    re.DOTALL
)
if old_import_block:
    print(f"  ICE found import block: {repr(old_import_block.group()[:120])}")
    # Replace with clean import
    new_import = ("import {\n"
                  "  GitCompare, History, Loader2, Plus, RefreshCw,\n"
                  "  Save, Server, Trash2, UploadCloud, FileCheck, Download, X,\n"
                  "} from 'lucide-react';")
    c = c[:old_import_block.start()] + new_import + c[old_import_block.end():]
    print("  ICE rebuilt import")

# useCallback already added to react import - verify
print(f"  ICE has useCallback: {'useCallback' in c}")

# fetchConfigs L96: wrap in useCallback
c = sub(c,
    '  const fetchConfigs = async () => {',
    '  const fetchConfigs = useCallback(async () => {',
    'ICE fetchConfigs useCallback')

# Close fetchConfigs useCallback - find its ending "};" before useEffect
# fetchConfigs ends before "  useEffect(() => { fetchConfigs(); }, [id]);"
c = sub(c,
    '  };\n\n  useEffect(() => { fetchConfigs(); }, [id]);',
    '  }, [id]);\n\n  useEffect(() => { void (async () => { await fetchConfigs(); })(); }, [fetchConfigs]);',
    'ICE fetchConfigs close+effect')

# fetchVersions: also needs useCallback
c = sub(c,
    '  useEffect(() => { fetchVersions(selectedFile?.serviceId); }, [id, selectedFile?.serviceId]);',
    '  useEffect(() => { void (async () => { await fetchVersions(selectedFile?.serviceId); })(); }, [id, selectedFile?.serviceId]);',
    'ICE fetchVersions effect IIFE')

write('pages/InternalConfigEditor.tsx', c)

# ─── ClusterDeployment.tsx ────────────────────────────────────────────────────
c = read('pages/ClusterDeployment.tsx')
lines = c.split('\n')
# Find which icons are actually used after the import block
icon_names = ['AlertTriangle','Check','CheckCircle2','ChevronDown','ChevronLeft',
              'FileText','Upload','Download','Loader2','MoreVertical','Network',
              'Play','RefreshCw','Search','Server','Settings2','Trash2','X','XCircle']
used = set()
for line in lines[26:]:  # skip imports
    for ic in icon_names:
        if ic in line:
            used.add(ic)
print(f"  CD used icons: {sorted(used)}")
unused = set(icon_names) - used
print(f"  CD UNUSED icons: {sorted(unused)}")

# Fix config?: Record<string, any> -> Record<string, unknown>
c = sub(c,
    '  config?: Record<string, any>;',
    '  config?: Record<string, unknown>;',
    'CD config any')

# Fix useCallback/IIFE for fetch effects - show context first
for i, line in enumerate(lines, 1):
    if 50 <= i <= 100 and ('fetchData' in line or 'useEffect' in line or 'const fetch' in line):
        print(f"  CD L{i}: {line.rstrip()}")
write('pages/ClusterDeployment.tsx', c)

# ─── ExternalClusters.tsx ─────────────────────────────────────────────────────
c = read('pages/ExternalClusters.tsx')
lines = c.split('\n')

# Fix brokers?: any[] -> unknown[]
c = sub(c,
    '  brokers?: any[];',
    '  brokers?: unknown[];',
    'EC brokers any')

# Check for more any types and useEffect patterns
for i, line in enumerate(lines, 1):
    if 'any' in line and i <= 150:
        print(f"  EC any L{i}: {line.rstrip()}")
for i, line in enumerate(lines, 1):
    if 50 <= i <= 90 and ('useEffect' in line or 'const fetch' in line or 'fetchClusters' in line):
        print(f"  EC L{i}: {line.rstrip()}")
write('pages/ExternalClusters.tsx', c)

# ─── Hosts.tsx ────────────────────────────────────────────────────────────────
c = read('pages/Hosts.tsx')

# Define Host type and replace any[]
HOST_TYPE = (
    '\ninterface HostInfo {\n'
    '  id: string;\n'
    '  hostname: string;\n'
    '  status: string;\n'
    '  agentStatus?: string;\n'
    '  ipAddresses?: string;\n'
    '  [key: string]: unknown;\n'
    '}\n'
)
c = sub(c,
    '\nexport function Hosts() {',
    HOST_TYPE + '\nexport function Hosts() {',
    'Hosts (HostInfo type)')

c = sub(c,
    '  const [hosts, setHosts] = useState<any[]>([]);',
    '  const [hosts, setHosts] = useState<HostInfo[]>([]);',
    'Hosts (any[] -> HostInfo[])')

# Add useCallback
c = sub(c,
    "import { useState, useEffect } from 'react';",
    "import { useState, useEffect, useCallback } from 'react';",
    'Hosts (useCallback import)')

# Wrap fetchHosts in useCallback
c = sub(c,
    '  const fetchHosts = async () => {\n'
    '    setLoading(true);\n'
    '    try {\n'
    '      const res = await fetch(\'/api/v1/ui/hosts\');\n'
    '      if (res.ok) setHosts(await res.json());\n'
    '    } catch (e) {\n'
    '      console.error(e);\n'
    '    }\n'
    '    setLoading(false);\n'
    '    setHasLoaded(true);\n'
    '  };',
    '  const fetchHosts = useCallback(async () => {\n'
    '    setLoading(true);\n'
    '    try {\n'
    '      const res = await fetch(\'/api/v1/ui/hosts\');\n'
    '      if (res.ok) setHosts(await res.json());\n'
    '    } catch (e) {\n'
    '      console.error(e);\n'
    '    }\n'
    '    setLoading(false);\n'
    '    setHasLoaded(true);\n'
    '  }, []);',
    'Hosts (fetchHosts useCallback)')

# Find and fix useEffect that calls fetchHosts
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 'useEffect' in line or ('fetchHosts' in line and 'useCallback' not in line and i < 80):
        print(f"  Hosts L{i}: {line.rstrip()}")
write('pages/Hosts.tsx', c)

# ─── AgentConnectivityModal.tsx ───────────────────────────────────────────────
c = read('components/AgentConnectivityModal.tsx')

# Define host type inline and fix any[]
c = sub(c,
    '  const [hosts, setHosts] = useState<any[]>([]);',
    '  const [hosts, setHosts] = useState<Record<string, unknown>[]>([]);',
    'ACM (any[] hosts)')

# Add useCallback
c = sub(c,
    "import { useState, useEffect } from 'react';",
    "import { useState, useEffect, useCallback } from 'react';",
    'ACM (useCallback import)')

# Wrap fetchHosts in useCallback
c = sub(c,
    '  const fetchHosts = async () => {\n'
    '    try {\n'
    '      const res = await fetch(\'/api/v1/ui/hosts\');\n'
    '      if (res.ok) setHosts(await res.json());\n'
    '    } catch (e) {\n'
    '      console.error(e);\n'
    '    }\n'
    '  };',
    '  const fetchHosts = useCallback(async () => {\n'
    '    try {\n'
    '      const res = await fetch(\'/api/v1/ui/hosts\');\n'
    '      if (res.ok) setHosts(await res.json());\n'
    '    } catch (e) {\n'
    '      console.error(e);\n'
    '    }\n'
    '  }, []);',
    'ACM (fetchHosts useCallback)')

# Fix the useEffect
c = sub(c,
    '  useEffect(() => {\n'
    '    fetchHosts();\n'
    '    const t = setInterval(fetchHosts, 5000);\n'
    '    return () => clearInterval(t);\n'
    '  }, []);',
    '  useEffect(() => {\n'
    '    void (async () => { await fetchHosts(); })();\n'
    '    const t = setInterval(() => { void (async () => { await fetchHosts(); })(); }, 5000);\n'
    '    return () => clearInterval(t);\n'
    '  }, [fetchHosts]);',
    'ACM (effect IIFE)')

# Fix remaining any usages
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 'any' in line:
        print(f"  ACM any L{i}: {line.rstrip()}")
write('components/AgentConnectivityModal.tsx', c)

# ─── Artifacts.tsx ────────────────────────────────────────────────────────────
c = read('pages/Artifacts.tsx')
lines = c.split('\n')

# Find unused icons - check body
icon_names_art = ['Upload','XCircle','ChevronDown','ChevronUp','Loader2','X','RefreshCw',
                  'Server','DownloadCloud','Power','PowerOff','Trash2','AlertTriangle','MoreVertical','FileText']
used_art = set()
for line in lines[8:]:
    for ic in icon_names_art:
        if ic in line:
            used_art.add(ic)
unused_art = set(icon_names_art) - used_art
print(f"\n  Artifacts UNUSED icons: {sorted(unused_art)}")

# Find any types
for i, line in enumerate(lines, 1):
    if 'any' in line and i <= 120:
        print(f"  Art any L{i}: {line.rstrip()}")

# Find useEffect patterns
for i, line in enumerate(lines, 1):
    if 55 <= i <= 120 and ('useEffect' in line or 'const fetch' in line):
        print(f"  Art L{i}: {line.rstrip()}")
write('pages/Artifacts.tsx', c)

print("\nDone.")
