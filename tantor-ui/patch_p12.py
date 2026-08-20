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
def sub_all(c, old, new, tag=''):
    n = c.count(old)
    if n == 0:
        print(f"  MISS_ALL [{tag}]")
        return c
    print(f"  OK_ALL [{tag}] ({n}x)")
    return c.replace(old, new)

# ─── Hosts.tsx: Map<string, any> ─────────────────────────────────────────────
c = read('pages/Hosts.tsx')
c = sub_all(c, 'Map<string, any>', 'Map<string, HostInfo>', 'Hosts Map any')
write('pages/Hosts.tsx', c)

# ─── Artifacts.tsx: refreshAll() and fetchParcelState() in effect ─────────────
# L179-183: useEffect calls refreshAll() and fetchParcelState() directly
c = read('pages/Artifacts.tsx')

c = sub(c,
    '  useEffect(() => {\n'
    '    refreshAll();\n'
    '    const timer = window.setInterval(fetchParcelState, 5000);\n'
    '    return () => window.clearInterval(timer);\n'
    '  }, []);',
    '  useEffect(() => {\n'
    '    void (async () => { await refreshAll(); })();\n'
    '    const timer = window.setInterval(() => { void (async () => { await fetchParcelState(); })(); }, 5000);\n'
    '    return () => window.clearInterval(timer);\n'
    '  }, [refreshAll, fetchParcelState]);',
    'Artifacts (effect IIFE)')

# localStorage effect at L185-187: direct setState in effect
# setXxx(localStorage...) — check
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 183 <= i <= 195:
        print(f"  Art L{i}: {line.rstrip()}")

# refreshAll and fetchParcelState need useCallback
for i, line in enumerate(lines, 1):
    if ('const refreshAll' in line or 'const fetchParcelState' in line or 'const fetchVersions' in line or 'const fetchHosts' in line):
        if 130 <= i <= 180:
            print(f"  Art func L{i}: {line.rstrip()}")

write('pages/Artifacts.tsx', c)

# ─── ExternalClusters.tsx: show useEffect at L152 ────────────────────────────
c = read('pages/ExternalClusters.tsx')
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 145 <= i <= 200:
        print(f"  EC L{i}: {line.rstrip()}")
write('pages/ExternalClusters.tsx', c)

# ─── ClusterDeployment.tsx: show full violation context ───────────────────────
c = read('pages/ClusterDeployment.tsx')
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 70 <= i <= 170 and ('useEffect' in line or 'any' in line or 'fetch' in line.lower()):
        print(f"  CD L{i}: {line.rstrip()}")
write('pages/ClusterDeployment.tsx', c)

print("\nDone.")
