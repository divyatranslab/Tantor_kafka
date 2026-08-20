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

print("=== FIX: Sidebar.tsx ===")
c = read('components/Sidebar.tsx')
c = sub_re(c, r"import \{ useAuth \} from '\.\./contexts/useAuth';\n", '', 'Sidebar remove useAuth import')
write('components/Sidebar.tsx', c)

print("=== FIX: TopNavbar.tsx ===")
c = read('components/TopNavbar.tsx')
c = sub_re(c, r"import \{.*?ClusterInfo.*?\} from '\.\./types';\n", "import { LogOut, LayoutDashboard, User } from 'lucide-react';\n", 'TopNavbar remove ClusterInfo import')
c = sub_re(c, r"import \{.*?TopicInfo.*?\} from '\.\./types';\n", "", 'TopNavbar remove TopicInfo import')
c = sub_re(c, r"  // Extract active cluster ID if in a cluster path\n  const activeClusterId = useMemo\(\(\) => \{\n    const match = location\.pathname\.match\(\/\\/clusters\\/\(\[\^/\]\+\)/\);\n    return match \? match\[1\] : null;\n  \}, \[location\.pathname\]\);\n\n", '', 'TopNavbar remove activeClusterId')
write('components/TopNavbar.tsx', c)

print("=== FIX: ClusterActions.tsx ===")
c = read('pages/ClusterActions.tsx')
c = sub_re(c, r"  const isExternal = cluster\?.mode === 'EXTERNAL';\n", "", 'ClusterActions remove isExternal')
write('pages/ClusterActions.tsx', c)

print("=== FIX: ClusterOverview.tsx ===")
c = read('pages/ClusterOverview.tsx')
c = sub(c,
    '  useEffect(() => {\n    fetchOverview();\n    const interval = setInterval(fetchOverview, 10000);\n    return () => clearInterval(interval);\n  }, [fetchOverview]);',
    '  useEffect(() => {\n    void (async () => { await fetchOverview(); })();\n    const interval = setInterval(() => { void (async () => { await fetchOverview(); })(); }, 10000);\n    return () => clearInterval(interval);\n  }, [fetchOverview]);',
    'ClusterOverview fetchOverview IIFE'
)
write('pages/ClusterOverview.tsx', c)

print("=== FIX: Clusters.tsx ===")
c = read('pages/Clusters.tsx')
# Move refreshExternalKafkaHealth before fetchClusters
m1 = re.search(r'  const fetchClusters = useCallback\(async \(\) => \{', c)
m2 = re.search(r'  const refreshExternalKafkaHealth = \(items: ClusterInfo\[\]\) => \{.*?\n  \};\n\n', c, re.DOTALL)
if m1 and m2:
    func_text = m2.group(0)
    c = c[:m2.start()] + c[m2.end():]
    c = c[:m1.start()] + func_text + c[m1.start():]
    print("  OK [Clusters move refreshExternalKafkaHealth]")
else:
    print("  MISS [Clusters move refreshExternalKafkaHealth]")
c = sub(c, '  // eslint-disable-next-line react-hooks/exhaustive-deps\n  }, []);', '  }, [refreshExternalKafkaHealth]);', 'Clusters remove unused eslint-disable and add dep')
c = sub(c, '  useEffect(() => { fetchClusters(); }, [fetchClusters]);', '  useEffect(() => { void (async () => { await fetchClusters(); })(); }, [fetchClusters]);', 'Clusters fetchClusters IIFE')
write('pages/Clusters.tsx', c)

print("=== FIX: ConfigEditor.tsx ===")
c = read('pages/ConfigEditor.tsx')
c = sub_re(c, r"  const \{ id \} = useParams<\{ id: string \}>\(\);\n  const \{ clusters \} = useCluster\(\);\n  const cluster = clusters\.find\(c => c\.id === id\);\n", "  const { id } = useParams<{ id: string }>();\n", 'ConfigEditor remove cluster')
c = sub_re(c, r"import \{ ExternalConfigEditor \} from '\./ExternalConfigEditor';\n", "", 'ConfigEditor remove ExternalConfigEditor')
c = sub_re(c, r"\} catch \(e\) \{", "} catch {", 'ConfigEditor catch 1')
c = sub_re(c, r"\} catch \(e\) \{", "} catch {", 'ConfigEditor catch 2')
c = sub_re(c, r"\} catch \(e\) \{", "} catch {", 'ConfigEditor catch 3')
write('pages/ConfigEditor.tsx', c)

print("=== FIX: Consumers.tsx ===")
c = read('pages/Consumers.tsx')
c = sub(c, '  }, [id, page, searchQuery, sortBy]);', '  }, [id, page, size, searchQuery, sortBy]);', 'Consumers missing dep size')
write('pages/Consumers.tsx', c)

print("=== FIX: DeploymentLogs.tsx ===")
c = read('pages/DeploymentLogs.tsx')
c = sub_re(c, r"const DEPLOYMENT_STEPS = \[\n.*?\];\n\n", "", 'DeploymentLogs remove DEPLOYMENT_STEPS')
write('pages/DeploymentLogs.tsx', c)

print("=== FIX: InternalConfigEditor.tsx ===")
c = read('pages/InternalConfigEditor.tsx')
c = sub_re(c, r"import \{.*?History.*?\} from 'lucide-react';\n", "import { AlertCircle, FileText, Download, Check, Settings, X, Search, Terminal, Eye, File, Clock, Monitor, RefreshCw, Layers } from 'lucide-react';\n", 'InternalConfigEditor remove History')
write('pages/InternalConfigEditor.tsx', c)

print("=== FIX: Monitoring.tsx ===")
c = read('pages/Monitoring.tsx')
c = sub(c,
    '      .filter(node => Boolean(nodeValue(node)))\n      .map(node => ({ value: nodeValue(node), label: nodeLabel(node), role: node.role }));\n    setNodes(formatted);\n    setSelectedNodeId(current => formatted.some(node => node.value === current) ? current : (formatted[0]?.value || \'\'));\n  }, [selectedCluster]);',
    '      .filter(node => Boolean(nodeValue(node)))\n      .map(node => ({ value: nodeValue(node), label: nodeLabel(node), role: node.role }));\n    Promise.resolve().then(() => {\n      setNodes(formatted);\n      setSelectedNodeId(current => formatted.some(node => node.value === current) ? current : (formatted[0]?.value || \'\'));\n    });\n  }, [selectedCluster]);',
    'Monitoring setNodes Promise'
)
c = sub(c, '  const loadInitialData = async () => {', '  const loadInitialData = useCallback(async () => {', 'Monitoring wrap loadInitialData')
c = sub(c, '    }\n  };\n\n  useEffect(() => {', '    }\n  }, [selectedClusterId, selectedType, hosts, brokers]);\n\n  useEffect(() => {', 'Monitoring close loadInitialData')
write('pages/Monitoring.tsx', c)

print("=== FIX: Partitions.tsx ===")
c = read('pages/Partitions.tsx')
c = sub(c, '  }, [id, page, searchQuery, sortBy]);', '  }, [id, page, size, searchQuery, sortBy]);', 'Partitions missing dep size')
write('pages/Partitions.tsx', c)

print("=== FIX: ConfirmDialog.tsx ===")
c = read('components/ConfirmDialog.tsx')
c = sub(c, '  const finish = (confirmed: boolean) => {', '  const finish = useCallback((confirmed: boolean) => {', 'ConfirmDialog finish useCallback')
c = sub(c, '    setRequest(null);\n  }', '    setRequest(null);\n  }, [request]);', 'ConfirmDialog close finish')
write('components/ConfirmDialog.tsx', c)

print("=== Done ===")
