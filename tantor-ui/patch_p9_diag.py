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

# ─── ExternalClusters.tsx ─────────────────────────────────────────────────────
c = read('pages/ExternalClusters.tsx')
# The sub missed — find actual pattern
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 28 <= i <= 36:
        print(f"  EC L{i}: {line.rstrip()}")

# Fix brokers any[]
c = sub(c, '  brokers?: any[];', '  brokers?: unknown[];', 'EC brokers[]')

# Find and fix other any types + useEffect patterns
for i, line in enumerate(lines, 1):
    if 'any' in line and i < 200:
        print(f"  EC any L{i}: {line.rstrip()}")
for i, line in enumerate(lines, 1):
    if 50 <= i <= 120 and ('useEffect' in line or 'const fetch' in line or 'fetchClusters' in line):
        print(f"  EC useEffect L{i}: {line.rstrip()}")
write('pages/ExternalClusters.tsx', c)

# ─── Hosts.tsx ────────────────────────────────────────────────────────────────
c = read('pages/Hosts.tsx')
# Fix useEffect that still calls fetchHosts directly
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
    'Hosts (effect IIFE)')

# Also find if there's a simpler useEffect calling fetchHosts
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 50 <= i <= 65:
        print(f"  Hosts L{i}: {line.rstrip()}")
write('pages/Hosts.tsx', c)

# ─── AgentConnectivityModal.tsx ───────────────────────────────────────────────
c = read('components/AgentConnectivityModal.tsx')
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 43 <= i <= 95:
        print(f"  ACM L{i}: {line.rstrip()}")
write('components/AgentConnectivityModal.tsx', c)

# ─── Artifacts.tsx ────────────────────────────────────────────────────────────
c = read('pages/Artifacts.tsx')
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 55 <= i <= 120:
        print(f"  Art L{i}: {line.rstrip()}")
write('pages/Artifacts.tsx', c)

# ─── ClusterDeployment.tsx ────────────────────────────────────────────────────
# CD had no unused icons but may have other violations
c = read('pages/ClusterDeployment.tsx')
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 55 <= i <= 120 and ('useEffect' in line or 'any' in line or 'const fetch' in line):
        print(f"  CD L{i}: {line.rstrip()}")
write('pages/ClusterDeployment.tsx', c)

print("\nDone.")
