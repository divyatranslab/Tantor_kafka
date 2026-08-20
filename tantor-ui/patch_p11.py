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

# ─── Hosts.tsx ─────────────────────────────────────────────────────────────────
c = read('pages/Hosts.tsx')
c = sub(c,
    '  const displayIp = (raw: any) => {',
    '  const displayIp = (raw: unknown) => {',
    'Hosts displayIp any')
# Show any remaining
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 'any' in line and i < 200:
        print(f"  Hosts any L{i}: {line.rstrip()}")
write('pages/Hosts.tsx', c)

# ─── Artifacts.tsx ─────────────────────────────────────────────────────────────
c = read('pages/Artifacts.tsx')

# Fix the .map((a: any) -> typed
c = sub(c,
    ".map((a: any) => ({",
    ".map((a: ArtifactVersion) => ({",
    'Artifacts map any')

# Show useEffect + fetch pattern context
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 175 <= i <= 220:
        print(f"  Art L{i}: {line.rstrip()}")
write('pages/Artifacts.tsx', c)

# ─── ClusterDeployment.tsx ─────────────────────────────────────────────────────
c = read('pages/ClusterDeployment.tsx')
lines = c.split('\n')
# Show any remaining violations
for i, line in enumerate(lines, 1):
    if 55 <= i <= 200 and ('useEffect' in line or 'any' in line or 'fetchData' in line or 'const fetch' in line):
        print(f"  CD L{i}: {line.rstrip()}")
write('pages/ClusterDeployment.tsx', c)

# ─── ExternalClusters.tsx ─────────────────────────────────────────────────────
c = read('pages/ExternalClusters.tsx')
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 40 <= i <= 160 and ('useEffect' in line or 'any' in line or 'const fetch' in line or 'fetchClusters' in line):
        print(f"  EC L{i}: {line.rstrip()}")
write('pages/ExternalClusters.tsx', c)

print("\nDone.")
