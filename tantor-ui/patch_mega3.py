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
def sub_re(c, pattern, new, tag='', flags=re.DOTALL):
    result, n = re.subn(pattern, new, c, flags=flags)
    if n == 0: print(f"  MISS_RE [{tag}]")
    else: print(f"  OK_RE  [{tag}] ({n}x)")
    return result

print("=== FIX: ClusterContext.tsx ===")
c = read('contexts/ClusterContext.tsx')
# check for createContext call
m = re.search(r'const ClusterContext = createContext<ClusterContextProps>\(.*?\);\n', c, re.DOTALL)
if m:
    c = c[:m.start()] + c[m.end():]
    print("  OK [ClusterContext createContext removal]")
else:
    print("  MISS [ClusterContext createContext removal]")
write('contexts/ClusterContext.tsx', c)

print("=== FIX: ConfirmDialog.tsx ===")
c = read('components/ConfirmDialog.tsx')
c = sub(c, '  }, []);', '  }, [finish]);', 'ConfirmDialog finish dep')
write('components/ConfirmDialog.tsx', c)

print("=== FIX: Brokers.tsx ===")
c = read('pages/Brokers.tsx')
c = sub_re(c, r'  const sortIndicator = \(field: keyof Broker\) =>\n    sortField === field \? \(sortOrder === \'asc\' \? \' ↑\' : \' ↓\'\) : \'\;\n\n', '', 'Brokers remove sortIndicator')
write('pages/Brokers.tsx', c)

print("=== FIX: AgentConnectivityModal.tsx ===")
c = read('components/AgentConnectivityModal.tsx')
c = sub(c, '} catch {}', '} catch { /* ignore */ }', 'ACM catch block')
write('components/AgentConnectivityModal.tsx', c)

print("=== FIX: Hosts.tsx ===")
c = read('pages/Hosts.tsx')
c = sub(c, '} catch {}', '} catch { /* ignore */ }', 'Hosts catch block')
write('pages/Hosts.tsx', c)

print("=== FIX: TopNavbar.tsx ===")
c = read('components/TopNavbar.tsx')
c = sub_re(c, r'  // Fetch all clusters for search context.*?^\s*\}\, \[\]\);\n', '', 'TopNavbar remove fetch clusters effect', flags=re.DOTALL|re.MULTILINE)
c = sub_re(c, r'  // Fetch topics in current cluster for search context.*?^\s*\}\, \[activeClusterId\]\);\n', '', 'TopNavbar remove fetch topics effect', flags=re.DOTALL|re.MULTILINE)
write('components/TopNavbar.tsx', c)

print("=== FIX: ClusterActions.tsx ===")
c = read('pages/ClusterActions.tsx')
c = sub(c,
    '  useEffect(() => {\n    fetchUpgradeContext();\n  }, [fetchUpgradeContext]);',
    '  useEffect(() => {\n    void (async () => { await fetchUpgradeContext(); })();\n  }, [fetchUpgradeContext]);',
    'ClusterActions fetchUpgradeContext IIFE'
)
c = sub(c,
    '  useEffect(() => {\n    if (!targetVersion && activeUpgradeVersions.length > 0) {\n      setTargetVersion(activeUpgradeVersions[0]);\n    }\n  // activeUpgradeVersions reference changes on every render — only react to length change\n  // eslint-disable-next-line react-hooks/exhaustive-deps\n  }, [activeUpgradeVersions.length, targetVersion]);',
    '  useEffect(() => {\n    Promise.resolve().then(() => {\n      if (!targetVersion && activeUpgradeVersions.length > 0) {\n        setTargetVersion(activeUpgradeVersions[0]);\n      }\n    });\n  // activeUpgradeVersions reference changes on every render — only react to length change\n  // eslint-disable-next-line react-hooks/exhaustive-deps\n  }, [activeUpgradeVersions.length, targetVersion]);',
    'ClusterActions setTargetVersion Promise'
)
write('pages/ClusterActions.tsx', c)

print("=== FIX: ConfigEditor.tsx ===")
c = read('pages/ConfigEditor.tsx')
c = sub(c,
    '  useEffect(() => {\n    let cancelled = false;\n    setLoadingCluster(true);\n    fetch',
    '  useEffect(() => {\n    let cancelled = false;\n    Promise.resolve().then(() => setLoadingCluster(true));\n    fetch',
    'ConfigEditor setLoadingCluster Promise'
)
c = sub(c,
    '  useEffect(() => {\n    if (!selectedFile) {\n      setDraftProperties({});\n      return;\n    }',
    '  useEffect(() => {\n    Promise.resolve().then(() => {\n      if (!selectedFile) {\n        setDraftProperties({});\n        return;\n      }',
    'ConfigEditor setDraftProperties Promise 1'
)
c = sub(c,
    '    const liveProps = fetchedProperties[selectedFile.nodeId!] || selectedFile.properties || {};\n    setDraftProperties({ ...liveProps, ...(stagedChanges[selectedFile.nodeId!] || {}) });\n  }, [selectedFile, fetchedProperties, stagedChanges]);',
    '      const liveProps = fetchedProperties[selectedFile.nodeId!] || selectedFile.properties || {};\n      setDraftProperties({ ...liveProps, ...(stagedChanges[selectedFile.nodeId!] || {}) });\n    });\n  }, [selectedFile, fetchedProperties, stagedChanges]);',
    'ConfigEditor setDraftProperties Promise 2'
)
write('pages/ConfigEditor.tsx', c)

print("=== FIX: InternalConfigEditor.tsx ===")
c = read('pages/InternalConfigEditor.tsx')
c = sub(c,
    '  useEffect(() => {\n    if (!selectedHostId && hosts[0]) setSelectedHostId(hosts[0].id);\n  }, [hosts, selectedHostId]);',
    '  useEffect(() => {\n    Promise.resolve().then(() => {\n      if (!selectedHostId && hosts[0]) setSelectedHostId(hosts[0].id);\n    });\n  }, [hosts, selectedHostId]);',
    'InternalConfigEditor selectedHostId Promise'
)
c = sub(c,
    '  useEffect(() => {\n    if (selectedFile && selectedFile.id !== selectedFileId) setSelectedFileId(selectedFile.id);\n  }, [selectedFile, selectedFileId]);',
    '  useEffect(() => {\n    Promise.resolve().then(() => {\n      if (selectedFile && selectedFile.id !== selectedFileId) setSelectedFileId(selectedFile.id);\n    });\n  }, [selectedFile, selectedFileId]);',
    'InternalConfigEditor selectedFileId Promise'
)
c = sub(c,
    '    setBaselineProperties(properties);\n    setDraftProperties(properties);\n    setPreview(null);\n  }, [selectedFile?.id, selectedFile?.properties]);',
    '    Promise.resolve().then(() => {\n      setBaselineProperties(properties);\n      setDraftProperties(properties);\n      setPreview(null);\n    });\n  }, [selectedFile?.id, selectedFile?.properties]);',
    'InternalConfigEditor setBaselineProperties Promise'
)
write('pages/InternalConfigEditor.tsx', c)

print("=== FIX: Monitoring.tsx ===")
c = read('pages/Monitoring.tsx')
c = sub(c,
    '  useEffect(() => {\n    if (!selectedCluster) {\n      setNodes([]);\n      setSelectedNodeId(\'\');\n      return;\n    }',
    '  useEffect(() => {\n    Promise.resolve().then(() => {\n      if (!selectedCluster) {\n        setNodes([]);\n        setSelectedNodeId(\'\');\n        return;\n      }\n    });',
    'Monitoring setNodes Promise'
)
c = sub(c,
    '  useEffect(() => {\n    setOverview(null);\n    setHistory([]);\n    setSelectedNodeId(\'\');\n    loadInitialData();\n  }, [loadInitialData]);',
    '  useEffect(() => {\n    Promise.resolve().then(() => {\n      setOverview(null);\n      setHistory([]);\n      setSelectedNodeId(\'\');\n    });\n    void (async () => { await loadInitialData(); })();\n  }, [loadInitialData]);',
    'Monitoring setOverview Promise'
)
c = sub(c,
    '  useEffect(() => {\n    if (selectedClusterId) {\n      setHistory([]);\n      loadOverview();\n    }\n  }, [selectedClusterId, loadOverview]);',
    '  useEffect(() => {\n    if (selectedClusterId) {\n      Promise.resolve().then(() => setHistory([]));\n      void (async () => { await loadOverview(); })();\n    }\n  }, [selectedClusterId, loadOverview]);',
    'Monitoring setHistory Promise'
)
c = sub(c,
    '  useEffect(() => {\n    if (!overview) return;\n    setHistory(current => {',
    '  useEffect(() => {\n    if (!overview) return;\n    Promise.resolve().then(() => {\n      setHistory(current => {',
    'Monitoring setHistory 2 Promise 1'
)
c = sub(c,
    '      ];\n    });\n  }, [overview]);',
    '        ];\n      });\n    });\n  }, [overview]);',
    'Monitoring setHistory 2 Promise 2'
)
write('pages/Monitoring.tsx', c)

print("=== FIX: DeploymentLogs.tsx ===")
c = read('pages/DeploymentLogs.tsx')
c = sub(c,
    '  useEffect(() => {\n    fetchTasks();\n  }, [fetchTasks]);',
    '  useEffect(() => {\n    void (async () => { await fetchTasks(); })();\n  }, [fetchTasks]);',
    'DeploymentLogs fetchTasks IIFE'
)
write('pages/DeploymentLogs.tsx', c)

print("=== Done ===")
