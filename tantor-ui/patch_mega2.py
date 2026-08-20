import sys, os, re
sys.stdout.reconfigure(encoding='utf-8')
BASE = 'src'

def read(p):
    with open(os.path.join(BASE, p), 'r', encoding='utf-8') as f:
        return f.read()
def write(p, c):
    with open(os.path.join(BASE, p), 'w', encoding='utf-8') as f:
        f.write(c)
def create(p, c):
    write(p, c); print(f"  CREATED {p}")
def sub(c, old, new, tag=''):
    if old not in c:
        print(f"  MISS [{tag}]"); return c
    print(f"  OK   [{tag}]"); return c.replace(old, new, 1)
def sub_re(c, pattern, new, tag='', flags=re.DOTALL):
    result, n = re.subn(pattern, new, c, flags=flags)
    if n == 0: print(f"  MISS_RE [{tag}]")
    else: print(f"  OK_RE  [{tag}] ({n}x)")
    return result

# ── 1. ConfirmDialog.tsx — remove confirmAction/notifyAction (they're in confirmUtils.ts) ──
c = read('components/ConfirmDialog.tsx')
# Remove exact block L7-L30 (confirmAction + notifyAction + blank lines)
c = sub(c,
    'export const confirmAction = (\n'
    '  message: string,\n'
    '  options: { title?: string; confirmLabel?: string } = {},\n'
    '): Promise<boolean> => {\n'
    '  return new Promise<boolean>(resolve => {\n'
    '    window.dispatchEvent(new CustomEvent<ConfirmRequest>(CONFIRM_EVENT, {\n'
    '      detail: { message, resolve, ...options },\n'
    '    }));\n'
    '  });\n'
    '}\n'
    '\n'
    'export const notifyAction = (\n'
    '  message: string,\n'
    '  options: { title?: string; confirmLabel?: string } = {},\n'
    '): void => {\n'
    '  window.dispatchEvent(new CustomEvent<ConfirmRequest>(CONFIRM_EVENT, {\n'
    '    detail: {\n'
    '      message,\n'
    '      title: options.title || \'Notice\',\n'
    '      confirmLabel: options.confirmLabel || \'OK\',\n'
    '      showCancel: false,\n'
    '    },\n'
    '  }));\n'
    '}\n\n',
    '',
    'ConfirmDialog remove both exports')
# Fix finish dep warning at L44-48: useEffect for confirmButtonRef.focus()
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 42 <= i <= 56:
        print(f"  CD L{i}: {line.rstrip()}")
write('components/ConfirmDialog.tsx', c)

# ── 2. AuthContext.tsx — move useAuth to separate file ──────────────────────────
c = read('contexts/AuthContext.tsx')
# Extract useAuth function body (L184-190)
c = sub(c,
    '\nexport const useAuth = () => {\n'
    '  const context = useContext(AuthContext);\n'
    '  if (!context) {\n'
    '    throw new Error(\'useAuth must be used inside AuthProvider\');\n'
    '  }\n'
    '  return context;\n'
    '};',
    '',
    'AuthContext remove useAuth export')
write('contexts/AuthContext.tsx', c)

# Create useAuth.ts
use_auth_content = (
    "import { useContext } from 'react';\n"
    "import { AuthContext } from './AuthContext';\n"
    "\n"
    "export const useAuth = () => {\n"
    "  const context = useContext(AuthContext);\n"
    "  if (!context) {\n"
    "    throw new Error('useAuth must be used inside AuthProvider');\n"
    "  }\n"
    "  return context;\n"
    "};\n"
)
create('contexts/useAuth.ts', use_auth_content)

# AuthContext.tsx must export AuthContext for useAuth.ts to import
c = read('contexts/AuthContext.tsx')
# Check if AuthContext is exported
if 'export const AuthContext' not in c and 'export { AuthContext' not in c:
    c = sub(c,
        'const AuthContext = createContext',
        'export const AuthContext = createContext',
        'AuthContext export AuthContext')
write('contexts/AuthContext.tsx', c)

# Update all files importing useAuth from AuthContext to use useAuth.ts
for root, dirs, files in os.walk(BASE):
    for fname in files:
        if not fname.endswith(('.ts', '.tsx')): continue
        if fname in ('AuthContext.tsx', 'useAuth.ts'): continue
        fpath = os.path.join(root, fname)
        with open(fpath, 'r', encoding='utf-8') as f:
            fc = f.read()
        if 'useAuth' not in fc: continue
        # Pattern: import { ..., useAuth, ... } from '...AuthContext'
        m = re.search(r"import \{([^}]+)\} from '([^']*AuthContext)';", fc)
        if m:
            imported = [x.strip() for x in m.group(1).split(',')]
            base_path = m.group(2)
            use_auth_path = base_path.replace('AuthContext', 'useAuth')
            auth_imports = [x for x in imported if x != 'useAuth']
            use_auth_imports = [x for x in imported if x == 'useAuth']
            new_imports = ''
            if auth_imports:
                new_imports += f"import {{ {', '.join(auth_imports)} }} from '{base_path}';\n"
            if use_auth_imports:
                new_imports += f"import {{ {', '.join(use_auth_imports)} }} from '{use_auth_path}';"
            fc = fc[:m.start()] + new_imports.rstrip() + fc[m.end():]
            with open(fpath, 'w', encoding='utf-8') as f:
                f.write(fc)
            print(f"  Updated useAuth import in {fname}")

# ── 3. TopicActionConfirmationModal.tsx — split types out ───────────────────────
c = read('components/TopicActionConfirmationModal.tsx')
# Create types file
types_content = (
    "export type TopicActionKind = 'clear' | 'recreate' | 'remove';\n"
    "\n"
    "export const topicActionCopy: Record<TopicActionKind, { title: string; description: string; button: string }> = {\n"
    "  clear: {\n"
    "    title: 'Clear all messages?',\n"
    "    description: 'Kafka will advance the low watermark for every partition. This cannot be undone and requires a DELETE cleanup policy.',\n"
    "    button: 'Clear messages'\n"
    "  },\n"
    "  recreate: {\n"
    "    title: 'Recreate this topic?',\n"
    "    description: 'All messages will be deleted. Partition assignments and explicit settings will be restored.',\n"
    "    button: 'Recreate topic'\n"
    "  },\n"
    "  remove: {\n"
    "    title: 'Remove this topic?',\n"
    "    description: 'The topic and all associated data will be permanently deleted.',\n"
    "    button: 'Remove topic'\n"
    "  }\n"
    "};\n"
)
create('components/topicActionTypes.ts', types_content)

# Remove TopicActionKind and topicActionCopy from the modal file
c = sub(c,
    "export type TopicActionKind = 'clear' | 'recreate' | 'remove';\n\nexport const topicActionCopy:",
    "import { type TopicActionKind, topicActionCopy } from './topicActionTypes';\n\nconst topicActionCopyLocal =",
    'TACM types split')
# Fix the reference name
c = sub(c, 'topicActionCopyLocal', 'topicActionCopy', 'TACM local ref fix')
# Actually cleaner: just remove the type/const and add import
c = read('components/TopicActionConfirmationModal.tsx')
# Remove type and const, add import at top
c = sub_re(c,
    r"export type TopicActionKind = '[^']+' \| '[^']+' \| '[^']+';\n\nexport const topicActionCopy: Record<TopicActionKind, \{ title: string; description: string; button: string \}> = \{.*?\};\n\n",
    "import { type TopicActionKind, topicActionCopy } from './topicActionTypes';\n\n",
    'TACM types import')
write('components/TopicActionConfirmationModal.tsx', c)

# Update import sites for TopicActionKind and topicActionCopy
for root, dirs, files in os.walk(BASE):
    for fname in files:
        if not fname.endswith(('.ts', '.tsx')): continue
        if fname in ('TopicActionConfirmationModal.tsx', 'topicActionTypes.ts'): continue
        fpath = os.path.join(root, fname)
        with open(fpath, 'r', encoding='utf-8') as f:
            fc = f.read()
        if 'TopicActionKind' not in fc and 'topicActionCopy' not in fc: continue
        m = re.search(r"import \{([^}]+)\} from '([^']*TopicActionConfirmationModal)';", fc)
        if m:
            imported = [x.strip() for x in m.group(1).split(',')]
            base_path = m.group(2)
            types_path = base_path.replace('TopicActionConfirmationModal', 'topicActionTypes')
            type_names = {'TopicActionKind', 'topicActionCopy'}
            component_imports = [x for x in imported if x.replace('type ', '') not in type_names]
            type_imports = [x for x in imported if x.replace('type ', '') in type_names]
            new_imports = ''
            if component_imports:
                new_imports += f"import {{ {', '.join(component_imports)} }} from '{base_path}';\n"
            if type_imports:
                new_imports += f"import {{ {', '.join(type_imports)} }} from '{types_path}';"
            fc = fc[:m.start()] + new_imports.rstrip() + fc[m.end():]
            with open(fpath, 'w', encoding='utf-8') as f:
                f.write(fc)
            print(f"  Updated TACM import in {fname}")

# ── 4. ClusterContext.tsx — create clusterContextDef.ts for context object ─────
cluster_def = (
    "import { createContext } from 'react';\n"
    "\n"
    "export interface Cluster {\n"
    "  id: string;\n"
    "  name: string;\n"
    "  status: string;\n"
    "  kafkaVersion: string;\n"
    "  mode: string;\n"
    "  [key: string]: unknown;\n"
    "}\n"
    "\n"
    "export interface ClusterContextProps {\n"
    "  clusters: Cluster[];\n"
    "  activeClusterId: string | null;\n"
    "  setActiveClusterId: (id: string | null) => void;\n"
    "  activeClusterMode: string | null;\n"
    "  isExternalCluster: boolean;\n"
    "  loading: boolean;\n"
    "}\n"
    "\n"
    "export const ClusterContext = createContext<ClusterContextProps>({\n"
    "  clusters: [],\n"
    "  activeClusterId: null,\n"
    "  setActiveClusterId: () => {},\n"
    "  activeClusterMode: null,\n"
    "  isExternalCluster: false,\n"
    "  loading: true,\n"
    "});\n"
)
create('contexts/clusterContextDef.ts', cluster_def)

# Update ClusterContext.tsx to import from clusterContextDef
c = read('contexts/ClusterContext.tsx')
# Replace the Cluster interface, ClusterContextProps interface, ClusterContext createContext call
# with import from clusterContextDef
# Current file starts with: import React, { createContext, useState, useEffect, useMemo } from 'react';
c = sub(c,
    "import React, { createContext, useState, useEffect, useMemo } from 'react';",
    "import React, { useState, useEffect, useMemo } from 'react';\nimport { type Cluster, ClusterContext } from './clusterContextDef';",
    'ClusterContext.tsx update import')
# Remove the Cluster interface (now in clusterContextDef)
c = sub_re(c,
    r'export interface Cluster \{[^}]+\}\n\n',
    '',
    'ClusterContext remove Cluster interface')
# Remove ClusterContextProps interface
c = sub_re(c,
    r'interface ClusterContextProps \{[^}]+\}\n\n',
    '',
    'ClusterContext remove ClusterContextProps interface')
# Remove const ClusterContext = createContext block (now imported)
c = sub_re(c,
    r'const ClusterContext = createContext<ClusterContextProps>\(\{[^}]+\}\);\n\n',
    '',
    'ClusterContext remove createContext call')
write('contexts/ClusterContext.tsx', c)

# Update useCluster.ts to import from clusterContextDef
use_cluster_content = (
    "import { useContext } from 'react';\n"
    "import { ClusterContext } from './clusterContextDef';\n"
    "\n"
    "export const useCluster = () => useContext(ClusterContext);\n"
)
create('contexts/useCluster.ts', use_cluster_content)

# Update App.tsx — it imports ClusterProvider from ClusterContext (unchanged, that's fine)
# Update all files importing Cluster type from ClusterContext to use clusterContextDef
for root, dirs, files in os.walk(BASE):
    for fname in files:
        if not fname.endswith(('.ts', '.tsx')): continue
        if fname in ('ClusterContext.tsx', 'clusterContextDef.ts', 'useCluster.ts'): continue
        fpath = os.path.join(root, fname)
        with open(fpath, 'r', encoding='utf-8') as f:
            fc = f.read()
        # Check if imports Cluster type from ClusterContext
        m = re.search(r"import \{([^}]+)\} from '([^']*ClusterContext)';", fc)
        if not m: continue
        imported = [x.strip() for x in m.group(1).split(',')]
        base_path = m.group(2)
        cluster_types = {'Cluster', 'type Cluster', 'ClusterContext', 'ClusterContextProps'}
        type_imports = [x for x in imported if x.replace('type ', '') in cluster_types]
        other_imports = [x for x in imported if x.replace('type ', '') not in cluster_types]
        if not type_imports: continue
        def_path = base_path.replace('ClusterContext', 'clusterContextDef')
        new_imports = ''
        if other_imports:
            new_imports += f"import {{ {', '.join(other_imports)} }} from '{base_path}';\n"
        if type_imports:
            new_imports += f"import {{ {', '.join(type_imports)} }} from '{def_path}';"
        fc = fc[:m.start()] + new_imports.rstrip() + fc[m.end():]
        with open(fpath, 'w', encoding='utf-8') as f:
            f.write(fc)
        print(f"  Updated Cluster import in {fname}")

# ── 5. Sidebar.tsx — fix decodedToken unused ────────────────────────────────────
c = read('components/Sidebar.tsx')
# Check what uses decodedToken
for i, line in enumerate(c.split('\n'), 1):
    if 'decodedToken' in line:
        print(f"  Sidebar L{i}: {line.rstrip()}")
# Remove decodedToken destructuring if it's the only usage
c = sub(c,
    '  const { decodedToken } = useAuth();\n',
    '',
    'Sidebar remove decodedToken')
write('components/Sidebar.tsx', c)

# ── 6. TopNavbar.tsx — remove unused allClusters, clusterTopics state and their effects ──
c = read('components/TopNavbar.tsx')
# Remove useState declarations
c = sub(c,
    "  const [allClusters, setAllClusters] = useState<ClusterInfo[]>([]);\n"
    "  const [clusterTopics, setClusterTopics] = useState<TopicInfo[]>([]);\n\n",
    '',
    'TopNavbar remove cluster state')
# Remove the effects that set these (find them in the file)
for i, line in enumerate(c.split('\n'), 1):
    if 60 <= i <= 110 and ('allClusters' in line or 'clusterTopics' in line or 'Fetch all' in line or 'Fetch topics' in line):
        print(f"  TopNav L{i}: {line.rstrip()}")
write('components/TopNavbar.tsx', c)

# ── 7. Brokers.tsx — remove unused sortIndicator ────────────────────────────────
c = read('pages/Brokers.tsx')
lines = c.split('\n')
# Find sortIndicator definition
for i, line in enumerate(lines, 1):
    if 63 <= i <= 72:
        print(f"  Brokers L{i}: {line.rstrip()}")
write('pages/Brokers.tsx', c)

# ── 8. AgentConnectivityModal.tsx — fix empty block at L52 ──────────────────────
c = read('components/AgentConnectivityModal.tsx')
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 48 <= i <= 56:
        print(f"  ACM L{i}: {line.rstrip()}")
write('components/AgentConnectivityModal.tsx', c)

# ── 9. Hosts.tsx — fix empty block at L66 ───────────────────────────────────────
c = read('pages/Hosts.tsx')
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 62 <= i <= 70:
        print(f"  Hosts L{i}: {line.rstrip()}")
write('pages/Hosts.tsx', c)

print("\nDone.")
