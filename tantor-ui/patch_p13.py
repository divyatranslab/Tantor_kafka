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

# ─── Artifacts.tsx: wrap fetch functions in useCallback ──────────────────────
c = read('pages/Artifacts.tsx')

# Add useCallback import
c = sub(c,
    "import { useState, useEffect, useRef } from 'react';",
    "import { useState, useEffect, useRef, useCallback } from 'react';",
    'Artifacts useCallback import')

# Wrap fetchVersions
c = sub(c,
    '  const fetchVersions = async () => {',
    '  const fetchVersions = useCallback(async () => {',
    'Artifacts fetchVersions useCallback')

# Close fetchVersions useCallback - it ends before fetchHosts
# fetchVersions ends with: setLoading(false);\n    }\n  };\n\n  const fetchHosts
c = sub(c,
    '    } finally {\n'
    '      setLoading(false);\n'
    '    }\n'
    '  };\n'
    '\n'
    '  const fetchHosts = async () => {',
    '    } finally {\n'
    '      setLoading(false);\n'
    '    }\n'
    '  }, []);\n'
    '\n'
    '  const fetchHosts = useCallback(async () => {',
    'Artifacts fetchVersions close + fetchHosts useCallback')

# Close fetchHosts - ends before fetchParcelState
c = sub(c,
    '  };\n'
    '\n'
    '  const fetchParcelState = async () => {',
    '  }, []);\n'
    '\n'
    '  const fetchParcelState = useCallback(async () => {',
    'Artifacts fetchHosts close + fetchParcelState useCallback')

# Close fetchParcelState - ends before refreshAll
c = sub(c,
    '  };\n'
    '\n'
    '  const refreshAll = async () => {',
    '  }, []);\n'
    '\n'
    '  const refreshAll = useCallback(async () => {',
    'Artifacts fetchParcelState close + refreshAll useCallback')

# Close refreshAll - ends before useEffect
c = sub(c,
    '  };\n'
    '\n'
    '  useEffect(() => {\n'
    '    void (async () => { await refreshAll(); })();',
    '  }, [fetchVersions, fetchHosts, fetchParcelState]);\n'
    '\n'
    '  useEffect(() => {\n'
    '    void (async () => { await refreshAll(); })();',
    'Artifacts refreshAll close')

# Verify output
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 137 <= i <= 190:
        print(f"  Art L{i}: {line.rstrip()}")

write('pages/Artifacts.tsx', c)

# ─── ExternalClusters.tsx: wrap loadAgents in useCallback + IIFE effect ───────
c = read('pages/ExternalClusters.tsx')

# Add useCallback to react import
c = sub(c,
    "import { useEffect, useMemo, useRef, useState } from 'react';",
    "import { useEffect, useMemo, useRef, useState, useCallback } from 'react';",
    'EC useCallback import')

# Wrap loadAgents in useCallback
c = sub(c,
    '  const loadAgents = async () => {',
    '  const loadAgents = useCallback(async () => {',
    'EC loadAgents useCallback')

# Close loadAgents and fix effect
c = sub(c,
    '    setAgentsLoading(false);\n'
    '    }\n'
    '  };\n'
    '\n'
    '  useEffect(() => {\n'
    '    loadAgents();\n'
    '    const timer = window.setInterval(loadAgents, 10000);\n'
    '    return () => window.clearInterval(timer);\n'
    '  }, []);',
    '    setAgentsLoading(false);\n'
    '    }\n'
    '  }, []);\n'
    '\n'
    '  useEffect(() => {\n'
    '    void (async () => { await loadAgents(); })();\n'
    '    const timer = window.setInterval(() => { void (async () => { await loadAgents(); })(); }, 10000);\n'
    '    return () => window.clearInterval(timer);\n'
    '  }, [loadAgents]);',
    'EC loadAgents close + effect IIFE')

# Check for remaining any in EC
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 'any' in line and i < 200:
        print(f"  EC any L{i}: {line.rstrip()}")
write('pages/ExternalClusters.tsx', c)

# ─── ClusterDeployment.tsx: check fetch patterns ─────────────────────────────
c = read('pages/ClusterDeployment.tsx')
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 80 <= i <= 180 and ('useEffect' in line or 'const fetch' in line or 'fetchData' in line):
        print(f"  CD L{i}: {line.rstrip()}")
write('pages/ClusterDeployment.tsx', c)

print("\nDone.")
