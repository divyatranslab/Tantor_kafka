import sys, os
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

# ── eslint.config.js is already patched above ──────────────────────────────

# ── ConfirmDialog.tsx: export function -> export const ─────────────────────
c = read('components/ConfirmDialog.tsx')
c = sub(c,
    'export function confirmAction(\n'
    '  message: string,\n'
    '  options: { title?: string; confirmLabel?: string } = {},\n'
    ') {\n'
    '  return new Promise<boolean>(resolve => {',

    'export const confirmAction = (\n'
    '  message: string,\n'
    '  options: { title?: string; confirmLabel?: string } = {},\n'
    '): Promise<boolean> => {\n'
    '  return new Promise<boolean>(resolve => {',
    'ConfirmDialog confirmAction')

c = sub(c,
    'export function notifyAction(\n'
    '  message: string,\n'
    '  options: { title?: string; confirmLabel?: string } = {},\n'
    ') {\n'
    '  window.dispatchEvent',

    'export const notifyAction = (\n'
    '  message: string,\n'
    '  options: { title?: string; confirmLabel?: string } = {},\n'
    '): void => {\n'
    '  window.dispatchEvent',
    'ConfirmDialog notifyAction')

# Close the arrow function body - need to add closing }; after the function ends
# The notifyAction body is: window.dispatchEvent(...);\n}
# With arrow function it should be fine since body is { ... }
write('components/ConfirmDialog.tsx', c)

# ── TopNavbar.tsx: remove unused searchQuery ────────────────────────────────
c = read('components/TopNavbar.tsx')
c = sub(c,
    "  const [searchQuery] = useState('');\n",
    '',
    'TopNavbar searchQuery')

# Check if useState is still used (profileRef, alertsCount, etc.)
# If searchQuery was the only useState, need to remove import too
# But there are other useState calls - keep import
write('components/TopNavbar.tsx', c)

# ── InternalConfigEditor.tsx: unused lucide icons ──────────────────────────
c = read('pages/InternalConfigEditor.tsx')
lines = c.split('\n')
# Print first 12 lines to see import
for i, line in enumerate(lines, 1):
    if i <= 12:
        print(f"  ICE L{i}: {line.rstrip()}")
# Print any violations
for i, line in enumerate(lines, 1):
    if 'any' in line or 'set-state' in line:
        print(f"  ICE any L{i}: {line.rstrip()}")
write('pages/InternalConfigEditor.tsx', c)

# ── Show imports of high-count files ────────────────────────────────────────
for fname in ['pages/ClusterDeployment.tsx', 'pages/ExternalClusters.tsx',
              'pages/Hosts.tsx', 'components/AgentConnectivityModal.tsx',
              'pages/Artifacts.tsx']:
    c = read(fname)
    lines = c.split('\n')
    # Show first 6 lines (imports)
    print(f"\n  {fname}:")
    for i, line in enumerate(lines, 1):
        if i <= 6:
            print(f"    L{i}: {line.rstrip()}")
    write(fname, c)

print("\nDone.")
