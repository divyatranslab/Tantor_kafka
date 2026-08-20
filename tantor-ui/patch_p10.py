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

# ─── AgentConnectivityModal.tsx ───────────────────────────────────────────────
# Fix parseIpList(raw: any) and displayIp(raw: any) -> unknown
# Fix Map<string, any> -> Map<string, Record<string, unknown>>
c = read('components/AgentConnectivityModal.tsx')

c = sub(c,
    '  const parseIpList = (raw: any): string[] => {',
    '  const parseIpList = (raw: unknown): string[] => {',
    'ACM parseIpList any')

c = sub(c,
    '  const displayIp = (raw: any) => {',
    '  const displayIp = (raw: unknown) => {',
    'ACM displayIp any')

# Map<string, any> -> Map<string, Record<string, unknown>>
c = sub_all(c,
    'Map<string, any>',
    'Map<string, Record<string, unknown>>',
    'ACM Map any')

write('components/AgentConnectivityModal.tsx', c)

# ─── Hosts.tsx ────────────────────────────────────────────────────────────────
# Fix parseIpList(raw: any) -> unknown
c = read('pages/Hosts.tsx')

c = sub(c,
    '  const parseIpList = (raw: any): string[] => {',
    '  const parseIpList = (raw: unknown): string[] => {',
    'Hosts parseIpList any')

# If there's a displayIp or similar
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 60 <= i <= 80 and 'any' in line:
        print(f"  Hosts any L{i}: {line.rstrip()}")

write('pages/Hosts.tsx', c)

# ─── ExternalClusters.tsx ─────────────────────────────────────────────────────
# Show useEffect patterns and remaining any
c = read('pages/ExternalClusters.tsx')
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 40 <= i <= 130 and ('useEffect' in line or 'const fetch' in line or 'fetchClusters' in line or 'any' in line):
        print(f"  EC L{i}: {line.rstrip()}")
write('pages/ExternalClusters.tsx', c)

# ─── ClusterDeployment.tsx ────────────────────────────────────────────────────
# Show useEffect and any patterns in more detail
c = read('pages/ClusterDeployment.tsx')
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 55 <= i <= 160 and ('useEffect' in line or 'const fetch' in line or 'any' in line or 'fetchData' in line):
        print(f"  CD L{i}: {line.rstrip()}")
write('pages/ClusterDeployment.tsx', c)

# ─── Artifacts.tsx ────────────────────────────────────────────────────────────
# Show useEffect patterns
c = read('pages/Artifacts.tsx')
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 120 <= i <= 190 and ('useEffect' in line or 'const fetch' in line or 'any' in line):
        print(f"  Art L{i}: {line.rstrip()}")
write('pages/Artifacts.tsx', c)

print("\nDone.")
