"""
Final precise fixes.
"""
import re, os

BASE = 'src'

def read(path):
    with open(os.path.join(BASE, path), 'r', encoding='utf-8') as f:
        return f.read()

def write(path, content):
    with open(os.path.join(BASE, path), 'w', encoding='utf-8') as f:
        f.write(content)

def sub(content, old, new, tag=''):
    if old not in content:
        print(f"  MISS [{tag}]: {repr(old[:80])}")
        return content
    print(f"  OK   [{tag}]")
    return content.replace(old, new, 1)

def sub_all(content, old, new, tag=''):
    count = content.count(old)
    if count == 0:
        print(f"  MISS [{tag}]")
        return content
    print(f"  OK   [{tag}] ({count}x)")
    return content.replace(old, new)

# ─── LdapSettings.tsx ──────────────────────────────────────────────────────
c = read('pages/LdapSettings.tsx')

# Fix two catch (err: any) blocks — get context around each
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 104 <= i <= 135:
        print(f"  LdapL{i}: {line.rstrip()}")
write('pages/LdapSettings.tsx', c)

# ─── TopNavbar.tsx: remove orphaned setIsSearchFocused call ─────────────────
c = read('components/TopNavbar.tsx')

# The state was removed but call remains at L105 inside click-outside handler
# Remove just that line
c = sub(c,
    '      if (searchRef.current && !searchRef.current.contains(event.target as Node)) {\n'
    '        setIsSearchFocused(false);\n'
    '      }',
    '',
    'TopNavbar (remove setIsSearchFocused call)')

# Also remove searchRef if it's now unused
print("\n  TopNavbar searchRef usage:")
for i, line in enumerate(c.split('\n'), 1):
    if 'searchRef' in line:
        print(f"    L{i}: {line.rstrip()}")

write('components/TopNavbar.tsx', c)

# ─── ClusterContext.tsx: react-refresh/only-export-components ───────────────
# useCluster and Cluster interface are exported from the same file as ClusterProvider
# The rule fires because non-component exports exist in a component file.
# Fix: the ClusterContext file exports both a Provider (component) and a hook.
# The correct fix per the rule docs is to split or use /* @refresh reset */
# Since we can't add eslint-disable, restructure: the rule applies to files
# that have a default component export. If the file exports a Provider component
# as a named export along with hooks, the rule is still triggered.
# The REAL fix: move useCluster to a separate file that imports from ClusterContext.
# But that's a bigger change. Alternative: check if the rule config has ignoreExports.
# For now, check: does the ESLint config have overrides for context files?
c = read('contexts/ClusterContext.tsx')
print("\n  ClusterContext full content:")
for i, line in enumerate(c.split('\n'), 1):
    print(f"    L{i}: {line.rstrip()}")
write('contexts/ClusterContext.tsx', c)

# ─── ConfirmDialog.tsx: react-refresh/only-export-components ─────────────────
# confirmAction and notifyAction are exported non-component functions
# Same pattern — rule fires. Real fix: move them to a separate utils file
# or make the component file not trigger the rule.
# For now: check ESLint config for react-refresh settings.
c = read('components/ConfirmDialog.tsx')
print("\n  ConfirmDialog exports and component:")
for i, line in enumerate(c.split('\n'), 1):
    if 'export' in line or 'function' in line:
        print(f"    L{i}: {line.rstrip()}")
write('components/ConfirmDialog.tsx', c)

# ─── Check ESLint config ──────────────────────────────────────────────────────
import json
try:
    with open('eslint.config.js', 'r') as f:
        content = f.read()
    print("\n  eslint.config.js (first 50 lines):")
    for i, line in enumerate(content.split('\n'), 1):
        if i <= 50:
            print(f"    L{i}: {line.rstrip()}")
except:
    print("  No eslint.config.js")

try:
    with open('.eslintrc.js', 'r') as f:
        print("\n  .eslintrc.js found")
        print(f.read()[:500])
except:
    pass

try:
    with open('.eslintrc.json', 'r') as f:
        print("\n  .eslintrc.json found")
        print(f.read()[:500])
except:
    pass

print("\nDone.")
