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

# ─── ClusterDeployment.tsx ────────────────────────────────────────────────────
c = read('pages/ClusterDeployment.tsx')

# Fix parseIpList (raw: any) -> unknown
c = sub(c,
    'function parseIpList(raw: any): string[] {',
    'function parseIpList(raw: unknown): string[] {',
    'CD parseIpList any')

# Fix .map((a: any) => at L492
c = sub(c,
    '.map((a: any) => ({',
    '.map((a: KafkaVersionInfo) => ({',
    'CD versions map any')

# Add useCallback to react import
c = sub(c,
    "import { useEffect, useMemo, useState, useRef } from 'react';",
    "import { useEffect, useMemo, useState, useRef, useCallback } from 'react';",
    'CD useCallback import')

# L423-426: useEffect calls loadHosts() and loadVersions() directly
# Need to wrap both in useCallback and use IIFE
# First view lines around 460-570 to find function definitions
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 455 <= i <= 580 and ('const load' in line or 'const fetch' in line or 'async () =>' in line):
        print(f"  CD L{i}: {line.rstrip()}")
write('pages/ClusterDeployment.tsx', c)

# ─── ExternalClusters.tsx ─────────────────────────────────────────────────────
c = read('pages/ExternalClusters.tsx')
lines = c.split('\n')
# Find loadAgents function definition
for i, line in enumerate(lines, 1):
    if 100 <= i <= 155 and ('const load' in line or 'const fetch' in line or 'async' in line):
        print(f"  EC L{i}: {line.rstrip()}")
write('pages/ExternalClusters.tsx', c)

print("\nDone.")
