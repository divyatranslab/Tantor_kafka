"""
Final targeted fixes: LdapSettings catches, TopNavbar searchRef,
ClusterContext react-refresh, ConfirmDialog react-refresh.
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

# ─── LdapSettings.tsx ──────────────────────────────────────────────────────
c = read('pages/LdapSettings.tsx')

# Fix catch at L108 (handleSave)
c = sub(c,
    "    } catch (err: any) {\n      setError(err.message || 'Failed to save configuration');",
    "    } catch (err: unknown) {\n      setError(err instanceof Error ? err.message : 'Failed to save configuration');",
    'LdapSettings (handleSave catch any)')

# Fix catch at L130 (handleTest)
c = sub(c,
    "    } catch (err: any) {\n      setTestResult({ success: false, message: err.message || 'Test failed' });",
    "    } catch (err: unknown) {\n      setTestResult({ success: false, message: err instanceof Error ? err.message : 'Test failed' });",
    'LdapSettings (handleTest catch any)')

write('pages/LdapSettings.tsx', c)

# ─── TopNavbar.tsx ─────────────────────────────────────────────────────────
# Remove orphaned searchRef declaration (now unused since setIsSearchFocused removed)
c = read('components/TopNavbar.tsx')

c = sub(c,
    "  const searchRef = useRef<HTMLDivElement>(null);\n",
    '',
    'TopNavbar (remove searchRef)')

# Also check if setSearchQuery is still unused
# searchQuery = useState('') — we removed setSearchQuery. searchQuery itself is
# used in filteredResults (which we removed) and in the input value.
# Check if searchQuery is used anywhere in the JSX:
lines = c.split('\n')
print("  TopNavbar searchQuery usage:")
for i, line in enumerate(lines, 1):
    if 'searchQuery' in line:
        print(f"    L{i}: {line.rstrip()}")

# Also check if useRef is still used (profileRef remains)
use_ref_count = sum(1 for l in lines if 'useRef' in l or 'profileRef' in l)
print(f"  useRef still used: {use_ref_count} occurrences")

write('components/TopNavbar.tsx', c)

# ─── ClusterContext.tsx: react-refresh/only-export-components ──────────────
# The rule fires because useCluster (a hook) is exported from the same file
# as ClusterProvider (a component). Fix: the rule's vite config can be
# configured with allowConstantExport but not allowHooks by default.
# The proper fix without suppressions: move useCluster to a separate file.
# We'll create a companion file useCluster.ts that re-exports the hook.

# First, remove the export from ClusterContext.tsx
c = read('contexts/ClusterContext.tsx')
c = sub(c,
    'export const useCluster = () => useContext(ClusterContext);\n\n',
    '',
    'ClusterContext (remove useCluster export)')

write('contexts/ClusterContext.tsx', c)

# Create useCluster.ts companion file
hook_content = """import { useContext } from 'react';
import { ClusterContext } from './ClusterContext';

export const useCluster = () => useContext(ClusterContext);
"""

# But wait — ClusterContext is not exported. We need to export it.
# Re-read to add the export:
c = read('contexts/ClusterContext.tsx')
c = sub(c,
    'const ClusterContext = createContext<ClusterContextProps>({',
    'export const ClusterContext = createContext<ClusterContextProps>({',
    'ClusterContext (export ClusterContext)')
write('contexts/ClusterContext.tsx', c)

# Write the hook file
with open(os.path.join(BASE, 'contexts/useCluster.ts'), 'w', encoding='utf-8') as f:
    f.write(hook_content)
print("  created contexts/useCluster.ts")

# Find all imports of useCluster and update them
import_dirs = [
    'pages', 'components', 'contexts', 'hooks', 'layouts', 'lib', 'utils',
]
for d in import_dirs:
    dirpath = os.path.join(BASE, d)
    if not os.path.isdir(dirpath):
        continue
    for fname in os.listdir(dirpath):
        if not fname.endswith(('.ts', '.tsx')):
            continue
        fpath = os.path.join(dirpath, fname)
        with open(fpath, 'r', encoding='utf-8') as f:
            fc = f.read()
        if 'useCluster' not in fc:
            continue
        # Update import path
        old_import = "import { useCluster } from '../contexts/ClusterContext';"
        new_import = "import { useCluster } from '../contexts/useCluster';"
        old_import2 = "import { useCluster } from './ClusterContext';"
        new_import2 = "import { useCluster } from './useCluster';"
        # Also handle combined imports like: import { ClusterProvider, useCluster } from ...
        if old_import in fc:
            fc = fc.replace(old_import, new_import)
            with open(fpath, 'w', encoding='utf-8') as f:
                f.write(fc)
            print(f"  updated useCluster import in {fname}")
        elif old_import2 in fc:
            fc = fc.replace(old_import2, new_import2)
            with open(fpath, 'w', encoding='utf-8') as f:
                f.write(fc)
            print(f"  updated useCluster import in {fname}")
        else:
            # Check for combined imports
            m = re.search(r"import \{([^}]+)\} from '([^']*ClusterContext)'", fc)
            if m:
                print(f"  combined import in {fname}: {m.group(0)}")

# Check App.tsx for ClusterContext imports
for root, dirs, files in os.walk(BASE):
    for fname in files:
        if not fname.endswith(('.ts', '.tsx')):
            continue
        fpath = os.path.join(root, fname)
        with open(fpath, 'r', encoding='utf-8') as f:
            fc = f.read()
        if 'useCluster' in fc and 'ClusterContext' in fc:
            print(f"  check: {fpath} has both useCluster and ClusterContext import")

# ─── ConfirmDialog.tsx: react-refresh/only-export-components ───────────────
# confirmAction and notifyAction are utilities exported from a file that also
# exports a React component (GlobalConfirmDialog).
# Fix: move utility functions to a separate file.
c = read('components/ConfirmDialog.tsx')
lines = c.split('\n')

# Extract the confirmAction and notifyAction functions:
# Find lines 16-40 (the two utility functions)
print("\n  ConfirmDialog lines 14-42:")
for i, line in enumerate(lines, 1):
    if 14 <= i <= 42:
        print(f"    L{i}: {line.rstrip()}")

write('components/ConfirmDialog.tsx', c)

# Check App.tsx
c = read('../App.tsx') if os.path.exists('../App.tsx') else ''
if c:
    print("\n  App.tsx imports:")
    for line in c.split('\n')[:5]:
        print(f"    {line.rstrip()}")

print("\nDone.")
