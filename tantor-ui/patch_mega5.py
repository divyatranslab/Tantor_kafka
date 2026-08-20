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
    if old not in c: print(f"  MISS [{tag}]"); return c
    print(f"  OK   [{tag}]"); return c.replace(old, new, 1)
def sub_re(c, pattern, new, tag='', flags=re.DOTALL):
    result, n = re.subn(pattern, new, c, flags=flags)
    if n == 0: print(f"  MISS_RE [{tag}]")
    else: print(f"  OK_RE  [{tag}] ({n}x)")
    return result

print("=== FIX: Artifacts.tsx ===")
c = read('pages/Artifacts.tsx')
c = sub_re(c, r": any", ": unknown", 'Artifacts replace any')
write('pages/Artifacts.tsx', c)

print("=== FIX: ClusterDeployment.tsx ===")
c = read('pages/ClusterDeployment.tsx')
c = sub_re(c, r"import \{.*?useCallback.*?\} from 'react';\n", "import { useState, useEffect, useMemo } from 'react';\n", 'ClusterDeployment remove useCallback')
c = sub_re(c, r"import \{ Network, Server, Play, StopCircle.*?\} from 'lucide-react';\n", "import { Play, StopCircle, HardDrive, Cpu, Terminal, ArrowRight, Shield, Globe, Clock, Check, AlertCircle, X, ChevronRight, CheckCircle2, ChevronDown } from 'lucide-react';\n", 'ClusterDeployment remove Network Server')
c = sub_re(c, r": any", ": unknown", 'ClusterDeployment replace any')
c = sub_re(c, r"catch \(\) \{", "catch {", 'ClusterDeployment catch block')
c = sub_re(c, r"const draftNodeIds = .*?;\n", "", 'ClusterDeployment remove draftNodeIds')
c = sub_re(c, r"const confirmNodeSelection = .*?;\n", "", 'ClusterDeployment remove confirmNodeSelection')
c = sub_re(c, r"const getPortTooltipText = .*?;\n", "", 'ClusterDeployment remove getPortTooltipText')
c = sub_re(c, r"let text = '';\n", "", 'ClusterDeployment remove let text')
write('pages/ClusterDeployment.tsx', c)

print("=== FIX: ExternalClusters.tsx ===")
c = read('pages/ExternalClusters.tsx')
c = sub_re(c, r": any", ": unknown", 'ExternalClusters replace any')
write('pages/ExternalClusters.tsx', c)

print("=== FIX: useAuth.ts ===")
# useAuth.ts is now importing AuthContext from AuthContext instead of authContextDef
c = read('contexts/useAuth.ts')
c = sub(c, "import { AuthContext } from './AuthContext';", "import { AuthContext } from './authContextDef';", 'useAuth fix import')
write('contexts/useAuth.ts', c)

print("=== Done ===")
