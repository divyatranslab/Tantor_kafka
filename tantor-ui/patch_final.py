"""
Final targeted fixes based on exact file content inspection.
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

# ─── JobStatusPage.tsx ─────────────────────────────────────────────────────
# stepIndex is defined but key uses step.id — remove the unused parameter
c = read('pages/JobStatusPage.tsx')
c = sub(c,
    '{displaySteps.map((step, stepIndex) => {',
    '{displaySteps.map((step) => {',
    'JobStatusPage (drop stepIndex param)')
write('pages/JobStatusPage.tsx', c)

# ─── JobsList.tsx ─────────────────────────────────────────────────────────
# fetchJobs is now useCallback but effect still calls it directly
# Close useCallback properly (L39 is "  };") and fix effect
c = read('pages/JobsList.tsx')
# The fetchJobs ends: setRefreshing(false);\n    }\n  };\n\n  useEffect
c = sub(c,
    '      setRefreshing(false);\n    }\n  };\n\n  useEffect(() => {\n    fetchJobs(false);\n    const interval = setInterval(() => fetchJobs(true), 5000);\n    return () => clearInterval(interval);\n  }, []);',
    '      setRefreshing(false);\n    }\n  }, []);\n\n  useEffect(() => {\n    void (async () => { await fetchJobs(false); })();\n    const interval = setInterval(() => { void (async () => { await fetchJobs(true); })(); }, 5000);\n    return () => clearInterval(interval);\n  }, [fetchJobs]);',
    'JobsList (close useCallback + IIFE effect)')
write('pages/JobsList.tsx', c)

# ─── TopNavbar.tsx ─────────────────────────────────────────────────────────
# filteredResults from useMemo — check if used
c = read('components/TopNavbar.tsx')
# Show JSX around return to find filteredResults usage
lines = c.split('\n')
used_in_jsx = any('filteredResults' in l for i, l in enumerate(lines, 1) if i > 130)
print(f"  TopNavbar filteredResults used in JSX (after L130): {used_in_jsx}")
if not used_in_jsx:
    # filteredResults is defined but never consumed in JSX — remove the useMemo
    # Find and remove the block
    c = re.sub(
        r'\n  // Search filter matches\n  const filteredResults = useMemo\(\(\) => \{.*?\}, \[searchQuery, allClusters, clusterTopics\]\);',
        '',
        c,
        flags=re.DOTALL
    )
    print("  removed filteredResults useMemo block")
    # Also remove searchQuery, allClusters, clusterTopics state vars if now unused
    # (allClusters and clusterTopics are still used in filteredResults — but if we remove filteredResults,
    #  they may become unused. Check usage in JSX.)
    for var in ['allClusters', 'clusterTopics', 'searchQuery']:
        used = sum(1 for l in lines if var in l and 'useState' not in l and 'setCluster' not in l and 'filteredResults' not in l)
        print(f"  {var} used elsewhere: {used} times")

# setIsSearchFocused is called at L105 — check if isSearchFocused has a state declaration
print("  TopNavbar isSearchFocused state line:")
for i, line in enumerate(lines, 1):
    if 'isSearchFocused' in line:
        print(f"    L{i}: {line.rstrip()}")
write('components/TopNavbar.tsx', c)

# ─── SecurityManager.tsx ─────────────────────────────────────────────────
# Fix any catches at L89 and L113
c = read('components/SecurityManager.tsx')
c = sub(c,
    '    } catch (err: any) {',
    '    } catch (err: unknown) {',
    'SecurityManager (first catch)')
# Now fix usage of err — need to guard with instanceof Error
# Show context around those catches
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 'catch' in line or ('err' in line and 85 <= i <= 120):
        print(f"    SM L{i}: {line.rstrip()}")
write('components/SecurityManager.tsx', c)

# ─── Sidebar.tsx ─────────────────────────────────────────────────────────
# icon?: any -> icon?: React.ReactNode
c = read('components/Sidebar.tsx')
# Need React import for ReactNode
c = sub(c,
    "  icon?: any;",
    "  icon?: React.ReactNode;",
    'Sidebar (icon any)')
# Ensure React is imported
if "import React" not in c and "import { " not in c.split('\n')[0]:
    c = "import React from 'react';\n" + c
    print("  added React import to Sidebar")
write('components/Sidebar.tsx', c)

# ─── AuthContext.tsx, AuditLogs.tsx, Alerts.tsx ───────────────────────────
# These had "all miss" — check actual imports
for fname in ['contexts/AuthContext.tsx', 'pages/AuditLogs.tsx', 'pages/Alerts.tsx', 'pages/Consumers.tsx']:
    c = read(fname)
    lines_list = c.split('\n')
    print(f"\n  {fname} first line: {lines_list[0].rstrip()}")
    for i, line in enumerate(lines_list, 1):
        if 'useEffect' in line or 'useCallback' in line:
            print(f"    L{i}: {line.rstrip()}")
            if i > 50:
                break
    write(fname, c)

print("\nDone.")
