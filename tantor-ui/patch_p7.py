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
        print(f"  MISS [{tag}]: {repr(old[:70])}")
        return c
    print(f"  OK   [{tag}]")
    return c.replace(old, new, 1)

def sub_re(c, pattern, new, tag=''):
    result, n = re.subn(pattern, new, c)
    if n == 0:
        print(f"  MISS_RE [{tag}]")
        return c
    print(f"  OK_RE [{tag}] ({n}x)")
    return result

# ── InternalConfigEditor.tsx ─────────────────────────────────────────────────
# Unused: CheckCircle2, FileText, History, RotateCcw, Server  
# need to check which are actually used in JSX
c = read('pages/InternalConfigEditor.tsx')
lines = c.split('\n')
used_icons = set()
for line in lines[8:]:  # skip import lines
    for icon in ['CheckCircle2', 'FileText', 'GitCompare', 'History', 'RotateCcw', 'Server',
                 'Loader2', 'Plus', 'RefreshCw', 'Save', 'Trash2', 'UploadCloud',
                 'FileCheck', 'Download', 'X']:
        if icon in line:
            used_icons.add(icon)
print(f"  InternalConfigEditor used icons: {sorted(used_icons)}")

# Remove unused icons from import (those NOT in used_icons from the body)
# From lint: CheckCircle2, FileText, History, RotateCcw flagged as unused
for icon in ['CheckCircle2', 'FileText', 'History', 'RotateCcw', 'Server']:
    if icon not in used_icons:
        # Try to remove from multi-line import
        c = sub_re(c, rf',?\s*{icon}\s*,?', lambda m: '' if icon in m.group() else m.group(), f'ICE remove {icon}')

# Better approach: rebuild the import line
# Current import is multi-line L3-L6
old_import = (
    'import {\n'
    '  CheckCircle2, FileText, GitCompare, History, Loader2, Plus, RefreshCw,\n'
    '  RotateCcw, Save, Server, Trash2, UploadCloud, FileCheck, Download, X,\n'
    '} from \'lucide-react\';'
)
# Build new import from used_icons
icons_to_keep = sorted(used_icons - {'CheckCircle2', 'FileText', 'History', 'RotateCcw', 'Server'})
# Always keep those that are in body
new_import = 'import { ' + ', '.join(icons_to_keep) + " } from 'lucide-react';"
c = sub(c, old_import, new_import, 'ICE lucide import rebuild')

# Add useCallback import
c = sub(c,
    "import { useEffect, useMemo, useState } from 'react';",
    "import { useEffect, useMemo, useState, useCallback } from 'react';",
    'ICE useCallback')

# Show useEffect patterns
for i, line in enumerate(lines, 1):
    if 'useEffect' in line or ('fetchConfig' in line and i < 100):
        print(f"  ICE L{i}: {line.rstrip()}")

write('pages/InternalConfigEditor.tsx', c)

# ── ClusterDeployment.tsx ─────────────────────────────────────────────────────
c = read('pages/ClusterDeployment.tsx')
lines = c.split('\n')
# Show lucide import block (L5 onwards)
print('\n  ClusterDeployment lucide import:')
in_import = False
for i, line in enumerate(lines, 1):
    if i == 5: in_import = True
    if in_import and '} from' in line and 'lucide' in line:
        print(f"    L{i}: {line.rstrip()}"); in_import = False; break
    if in_import:
        print(f"    L{i}: {line.rstrip()}")
    if i > 30: break

# Show useEffect and any type patterns
for i, line in enumerate(lines, 1):
    if 30 <= i <= 80 and ('useEffect' in line or 'any' in line or 'const fetch' in line):
        print(f"  CD L{i}: {line.rstrip()}")

write('pages/ClusterDeployment.tsx', c)

# ── ExternalClusters.tsx ──────────────────────────────────────────────────────
c = read('pages/ExternalClusters.tsx')
lines = c.split('\n')
print('\n  ExternalClusters first 20 lines:')
for i, line in enumerate(lines, 1):
    if i <= 20:
        print(f"    L{i}: {line.rstrip()}")
for i, line in enumerate(lines, 1):
    if 'useEffect' in line or 'any' in line:
        if i < 100:
            print(f"  EC L{i}: {line.rstrip()}")
write('pages/ExternalClusters.tsx', c)

# ── Hosts.tsx ─────────────────────────────────────────────────────────────────
c = read('pages/Hosts.tsx')
lines = c.split('\n')
print('\n  Hosts first 40 lines:')
for i, line in enumerate(lines, 1):
    if i <= 40:
        print(f"    L{i}: {line.rstrip()}")
write('pages/Hosts.tsx', c)

# ── AgentConnectivityModal.tsx ────────────────────────────────────────────────
c = read('components/AgentConnectivityModal.tsx')
lines = c.split('\n')
print('\n  AgentConnectivityModal first 40 lines:')
for i, line in enumerate(lines, 1):
    if i <= 40:
        print(f"    L{i}: {line.rstrip()}")
write('components/AgentConnectivityModal.tsx', c)

# ── Artifacts.tsx ─────────────────────────────────────────────────────────────
c = read('pages/Artifacts.tsx')
lines = c.split('\n')
print('\n  Artifacts first 50 lines:')
for i, line in enumerate(lines, 1):
    if i <= 50:
        print(f"    L{i}: {line.rstrip()}")
write('pages/Artifacts.tsx', c)

print("\nDone.")
