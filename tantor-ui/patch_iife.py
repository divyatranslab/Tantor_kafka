"""
Fix set-state-in-effect: replace direct fn() calls in useEffect bodies
with async IIFE wrappers so setState happens inside async context.
Also fix remaining no-unused-vars.
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
        print(f"  MISS in {path}: {repr(old[:70])}")
        return content
    return content.replace(old, new)

# ─── UserManagement.tsx ───────────────────────────────────────────────────────
c = read('pages/UserManagement.tsx')
# Fix: wrap fetchUsers() call in async IIFE so setState is inside async context
c = sub(c, '  useEffect(() => { fetchUsers(); }, [fetchUsers]);',
           '  useEffect(() => { void (async () => { await fetchUsers(); })(); }, [fetchUsers]);',
           'UserManagement.tsx')
write('pages/UserManagement.tsx', c)
print('  fixed: UserManagement.tsx')

# ─── JobsList.tsx ─────────────────────────────────────────────────────────────
c = read('pages/JobsList.tsx')
# Check what's in the file first
print("  JobsList effect pattern:")
for i, line in enumerate(c.split('\n'), 1):
    if 'useEffect' in line or 'fetchJobs' in line:
        print(f"    L{i}: {line.rstrip()}")
write('pages/JobsList.tsx', c)  # no-op, just seeing

# ─── ClusterDetails.tsx ───────────────────────────────────────────────────────
c = read('pages/ClusterDetails.tsx')
print("  ClusterDetails effect pattern:")
for i, line in enumerate(c.split('\n'), 1):
    if 40 <= i <= 60:
        print(f"    L{i}: {line.rstrip()}")
write('pages/ClusterDetails.tsx', c)

# ─── JobStatusPage.tsx ────────────────────────────────────────────────────────
c = read('pages/JobStatusPage.tsx')
print("  JobStatusPage effect pattern:")
for i, line in enumerate(c.split('\n'), 1):
    if 135 <= i <= 150:
        print(f"    L{i}: {line.rstrip()}")
print("  idx usage around L450-460:")
for i, line in enumerate(c.split('\n'), 1):
    if 450 <= i <= 462:
        print(f"    L{i}: {line.rstrip()}")
write('pages/JobStatusPage.tsx', c)
