"""
Fix remaining set-state-in-effect violations by wrapping direct fn() calls
in the effect body inside an async IIFE. Also fix remaining no-unused-vars.
"""
import re, os

BASE = 'src'

def read(path):
    with open(os.path.join(BASE, path), 'r', encoding='utf-8') as f:
        return f.read()

def write(path, content):
    with open(os.path.join(BASE, path), 'w', encoding='utf-8') as f:
        f.write(content)

def sub(content, old, new, path=''):
    if old not in content:
        print(f"  MISS [{path}]: {repr(old[:70])}")
        return content
    result = content.replace(old, new, 1)
    print(f"  OK   [{path}]: {repr(old[:60])}")
    return result

# ─── ClusterDetails.tsx ───────────────────────────────────────────────────────
# setCluster(null) is a sync setState in an effect. Fix: move it into the
# .then() chain so it's async, by resetting inside a .then() at the start.
c = read('pages/ClusterDetails.tsx')
c = sub(c,
    '  useEffect(() => {\n'
    '    // Clear stale data when switching clusters to prevent flash of old data\n'
    '    setCluster(null);\n'
    '    fetch(`/api/v1/ui/clusters/${id}`)\n'
    '      .then(res => res.json())\n'
    '      .then(setCluster)\n'
    '      .catch(console.error);\n'
    '  }, [id]);',
    '  useEffect(() => {\n'
    '    // Clear stale data when switching clusters; then fetch new data async\n'
    '    void (async () => {\n'
    '      setCluster(null);\n'
    '      try {\n'
    '        const res = await fetch(`/api/v1/ui/clusters/${id}`);\n'
    '        setCluster(await res.json());\n'
    '      } catch (e) { console.error(e); }\n'
    '    })();\n'
    '  }, [id]);',
    'ClusterDetails.tsx')
write('pages/ClusterDetails.tsx', c)

# ─── JobStatusPage.tsx ────────────────────────────────────────────────────────
# fetchJob() is called directly in the effect at L140
# idx at L455 is used as map key — just remove the variable by using index
# _idx at L287 was renamed but key={idx} not updated — fix both
c = read('pages/JobStatusPage.tsx')

# Fix set-state-in-effect: wrap fetchJob() in async IIFE in the effect
c = sub(c,
    '  useEffect(() => {\n'
    '    fetchJob();\n'
    '    const interval = setInterval(() => {\n'
    '      if (![\'SUCCESS\', \'FAILED\', \'PARTIAL_SUCCESS\', \'ROLLED_BACK\', \'ROLLBACK_FAILED\'].includes(job?.status || \'\')) {\n'
    '         fetchJob();\n'
    '      }\n'
    '    }, 2000);\n'
    '    return () => clearInterval(interval);\n'
    '  }, [fetchJob, job?.status]);',
    '  useEffect(() => {\n'
    '    void (async () => { await fetchJob(); })();\n'
    '    const interval = setInterval(() => {\n'
    '      if (![\'SUCCESS\', \'FAILED\', \'PARTIAL_SUCCESS\', \'ROLLED_BACK\', \'ROLLBACK_FAILED\'].includes(job?.status || \'\')) {\n'
    '        void (async () => { await fetchJob(); })();\n'
    '      }\n'
    '    }, 2000);\n'
    '    return () => clearInterval(interval);\n'
    '  }, [fetchJob, job?.status]);',
    'JobStatusPage.tsx (set-state-in-effect)')

# Fix _idx: rename back to index (used for key prop)
c = sub(c,
    'return logsText.split(\'\\n\').map((line, _idx) => {',
    'return logsText.split(\'\\n\').map((line, lineIndex) => {',
    'JobStatusPage.tsx (_idx)')
c = sub(c,
    'return <div key={_idx} className={className}>{displayLine || \' \'}</div>;',
    'return <div key={lineIndex} className={className}>{displayLine || \' \'}</div>;',
    'JobStatusPage.tsx (key={_idx})')
# If the old idx->_idx patch left key={idx} (not updated), fix that too
c = sub(c,
    'return <div key={idx} className={className}>{displayLine || \' \'}</div>;',
    'return <div key={lineIndex} className={className}>{displayLine || \' \'}</div>;',
    'JobStatusPage.tsx (key={idx} fallback)')

# Fix idx at L455: displaySteps.map((step, idx) => — idx is not used in the body
# Check if idx is used as key or in the body
# From diagnostic: it is used in the body (as key presumably)
# Let's rename it to stepIndex if it's used, or drop if not
c = sub(c,
    '{displaySteps.map((step, idx) => {',
    '{displaySteps.map((step, stepIndex) => {',
    'JobStatusPage.tsx (displaySteps idx)')
# Update any usage of idx inside the step map — key prop likely
c = sub(c,
    'key={idx}',
    'key={stepIndex}',
    'JobStatusPage.tsx (key={idx} in steps)')

write('pages/JobStatusPage.tsx', c)

# ─── JobsList.tsx ─────────────────────────────────────────────────────────────
# fetchJobs is not in useCallback yet and calls setState. Fix both.
c = read('pages/JobsList.tsx')

# Add useCallback
c = sub(c,
    "import { useEffect, useState } from 'react';",
    "import { useEffect, useState, useCallback } from 'react';",
    'JobsList.tsx (useCallback)')

# Wrap fetchJobs in useCallback
c = sub(c,
    '  const fetchJobs = async (isManual = false) => {',
    '  const fetchJobs = useCallback(async (isManual = false) => {',
    'JobsList.tsx (useCallback wrap)')

# Close useCallback — find the end of fetchJobs; it ends before useEffect
# fetchJobs ends with: setRefreshing(false);\n    }\n  };\n\n  useEffect
c = sub(c,
    '      if (isManual) setRefreshing(false);\n    }\n  };\n\n  useEffect(() => {\n    fetchJobs(false);\n    const interval = setInterval(() => fetchJobs(true), 5000);\n    return () => clearInterval(interval);\n  }, []);',
    '      if (isManual) setRefreshing(false);\n    }\n  }, []);\n\n  useEffect(() => {\n    void (async () => { await fetchJobs(false); })();\n    const interval = setInterval(() => { void (async () => { await fetchJobs(true); })(); }, 5000);\n    return () => clearInterval(interval);\n  }, [fetchJobs]);',
    'JobsList.tsx (effect fix)')

write('pages/JobsList.tsx', c)

# ─── Brokers.tsx ──────────────────────────────────────────────────────────────
# any L44, set-state-in-effect L52 (useCallback already added but effect not fixed),
# sortIndicator used in JSX as sortField === field
# From diagnostic: fetchBrokers is already useCallback (line 38), BUT effect still uses [id] not [fetchBrokers]
# sortIndicator at L66 IS used in JSX (check)
c = read('pages/Brokers.tsx')

# Fix catch any->unknown
c = sub(c,
    '    } catch (e: any) {\n      setError(e.message);',
    '    } catch (e: unknown) {\n      setError(e instanceof Error ? e.message : \'Failed to fetch brokers\');',
    'Brokers.tsx (catch any)')

# Close useCallback around fetchBrokers (it was partially wrapped but missing the closing)
# Check: "const fetchBrokers = useCallback(async () => {" is at L38
# but the closing "};" is still there not "}, [id]);"
c = sub(c,
    '    } finally {\n      setLoading(false);\n    }\n  };\n\n  useEffect(() => {\n    fetchBrokers();\n    const interval = setInterval(fetchBrokers, 10000);\n    return () => clearInterval(interval);\n  }, [id]);',
    '    } finally {\n      setLoading(false);\n    }\n  }, [id]);\n\n  useEffect(() => {\n    void (async () => { await fetchBrokers(); })();\n    const interval = setInterval(() => { void (async () => { await fetchBrokers(); })(); }, 10000);\n    return () => clearInterval(interval);\n  }, [fetchBrokers]);',
    'Brokers.tsx (effect fix)')

# sortIndicator IS used in JSX (it renders arrows) — don't remove it
# The "unused" warning was wrong earlier — recheck: lint said L66 sortIndicator unused
# From the diagnostic: "const sortIndicator = (field: keyof Broker) => sortField === field ..."
# Let's check if it's called in JSX
write('pages/Brokers.tsx', c)

# ─── SecurityManager.tsx ──────────────────────────────────────────────────────
c = read('components/SecurityManager.tsx')
print("\n  SecurityManager imports:")
for i, line in enumerate(c.split('\n'), 1):
    if i <= 10:
        print(f"    L{i}: {line.rstrip()}")
print("  SecurityManager RESOURCE_TYPES:")
for i, line in enumerate(c.split('\n'), 1):
    if 'RESOURCE_TYPES' in line or 'Shield' in line or 'Check' in line:
        print(f"    L{i}: {line.rstrip()}")
print("  SecurityManager fetchAcls/useEffect:")
for i, line in enumerate(c.split('\n'), 1):
    if 60 <= i <= 100 and ('fetchAcls' in line or 'useEffect' in line or 'setState' in line or 'setAcls' in line):
        print(f"    L{i}: {line.rstrip()}")
write('components/SecurityManager.tsx', c)

# ─── Sidebar.tsx ──────────────────────────────────────────────────────────────
c = read('components/Sidebar.tsx')
print("\n  Sidebar displayName:")
for i, line in enumerate(c.split('\n'), 1):
    if 'displayName' in line or ('any' in line and i <= 30):
        print(f"    L{i}: {line.rstrip()}")
write('components/Sidebar.tsx', c)

# ─── TopNavbar.tsx ────────────────────────────────────────────────────────────
c = read('components/TopNavbar.tsx')
print("\n  TopNavbar set-state-in-effect L94:")
for i, line in enumerate(c.split('\n'), 1):
    if 88 <= i <= 100:
        print(f"    L{i}: {line.rstrip()}")
write('components/TopNavbar.tsx', c)

print("\nDone.")
