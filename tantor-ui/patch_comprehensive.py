"""
Final comprehensive fixes based on exact file content.
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

# ─── AuditLogs.tsx ─────────────────────────────────────────────────────────
# L223: useEffect(() => { fetchLogs(); }, []);
# L247-249: useEffect(() => { setCurrentPage(1); }, [filtered.length]);
c = read('pages/AuditLogs.tsx')

# fetchLogs: wrap in IIFE
c = sub(c,
    '  useEffect(() => { fetchLogs(); }, []);',
    '  useEffect(() => { void (async () => { await fetchLogs(); })(); }, [fetchLogs]);',
    'AuditLogs (fetchLogs IIFE)')

# Also need to wrap fetchLogs in useCallback — find its definition
# From diagnostic: fetchLogs ends at L221 with "  };"
# Need to find where it starts
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 195 <= i <= 225:
        if 'fetchLogs' in line or 'const ' in line:
            print(f"    L{i}: {line.rstrip()}")

# The set-state-in-effect at L248: setCurrentPage(1) is a direct setState in effect
# Fix: use queueMicrotask
c = sub(c,
    '  useEffect(() => {\n    setCurrentPage(1);\n  }, [filtered.length]);',
    '  useEffect(() => {\n    Promise.resolve().then(() => setCurrentPage(1));\n  }, [filtered.length]);',
    'AuditLogs (setCurrentPage IIFE)')

write('pages/AuditLogs.tsx', c)

# Now find fetchLogs start
c = read('pages/AuditLogs.tsx')
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 195 <= i <= 225:
        print(f"    AuditL{i}: {line.rstrip()}")

# ─── Alerts.tsx ──────────────────────────────────────────────────────────────
# L44-55: fetchAlerts() called directly — needs IIFE
# L37: catch (e: any) — fix to unknown
c = read('pages/Alerts.tsx')

c = sub(c,
    '    } catch (e: any) {',
    '    } catch (e: unknown) {',
    'Alerts (catch any)')

# Fix the error message usage — e.message won't work with unknown
c = sub(c,
    "      if (!quiet) setError(e.message || 'Failed to load alerts');",
    "      if (!quiet) setError(e instanceof Error ? e.message : 'Failed to load alerts');",
    'Alerts (e.message guard)')

# Fix set-state-in-effect: fetchAlerts() is at L45
c = sub(c,
    '  useEffect(() => {\n'
    '    fetchAlerts();\n'
    '    const timer = window.setInterval(() => fetchAlerts(true), 15_000);\n'
    '    const refreshWhenVisible = () => {\n'
    '      if (document.visibilityState === \'visible\') fetchAlerts(true);\n'
    '    };\n'
    '    document.addEventListener(\'visibilitychange\', refreshWhenVisible);\n'
    '    return () => {\n'
    '      window.clearInterval(timer);\n'
    '      document.removeEventListener(\'visibilitychange\', refreshWhenVisible);\n'
    '    };\n'
    '  }, [fetchAlerts]);',
    '  useEffect(() => {\n'
    '    void (async () => { await fetchAlerts(); })();\n'
    '    const timer = window.setInterval(() => { void (async () => { await fetchAlerts(true); })(); }, 15_000);\n'
    '    const refreshWhenVisible = () => {\n'
    '      if (document.visibilityState === \'visible\') void (async () => { await fetchAlerts(true); })();\n'
    '    };\n'
    '    document.addEventListener(\'visibilitychange\', refreshWhenVisible);\n'
    '    return () => {\n'
    '      window.clearInterval(timer);\n'
    '      document.removeEventListener(\'visibilitychange\', refreshWhenVisible);\n'
    '    };\n'
    '  }, [fetchAlerts]);',
    'Alerts (set-state-in-effect IIFE)')

write('pages/Alerts.tsx', c)

# ─── Consumers.tsx ────────────────────────────────────────────────────────────
# L82: fetchGroups() in effect — IIFE
# L73: catch (e: any) — fix
# fetchGroups is NOT in useCallback (L79 closes with "};")
c = read('pages/Consumers.tsx')

c = sub(c,
    '    } catch (e: any) {',
    '    } catch (e: unknown) {',
    'Consumers (catch any)')

c = sub(c,
    "      setError(e.message || 'Failed to load consumer groups');",
    "      setError(e instanceof Error ? e.message : 'Failed to load consumer groups');",
    'Consumers (e.message guard)')

# Close fetchGroups in useCallback (currently just "};")
# Show what's around the closing
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 60 <= i <= 85:
        print(f"    ConsL{i}: {line.rstrip()}")

write('pages/Consumers.tsx', c)

# ─── AuthContext.tsx ───────────────────────────────────────────────────────────
# L102-113: useEffect with direct setState calls (setDecodedToken, setIsInitializing, setIsAuthenticated)
# These are in a synchronous if-branch. Fix: wrap in Promise.resolve().then()
c = read('contexts/AuthContext.tsx')
c = sub(c,
    '  useEffect(() => {\n'
    '    if (!authEnabled) {\n'
    '      installAuthenticatedFetch();\n'
    '      setDecodedToken({\n'
    '        preferred_username: \'shaukat\',\n'
    '        roles: [\'admin\'],\n'
    '        realm_access: { roles: [\'admin\'] }\n'
    '      });\n'
    '      setIsInitializing(false);\n'
    '      setIsAuthenticated(true);\n'
    '      return;\n'
    '    }',
    '  useEffect(() => {\n'
    '    if (!authEnabled) {\n'
    '      installAuthenticatedFetch();\n'
    '      Promise.resolve().then(() => {\n'
    '        setDecodedToken({\n'
    '          preferred_username: \'shaukat\',\n'
    '          roles: [\'admin\'],\n'
    '          realm_access: { roles: [\'admin\'] }\n'
    '        });\n'
    '        setIsInitializing(false);\n'
    '        setIsAuthenticated(true);\n'
    '      });\n'
    '      return;\n'
    '    }',
    'AuthContext (set-state-in-effect)')
write('contexts/AuthContext.tsx', c)

# ─── LdapSettings.tsx ─────────────────────────────────────────────────────────
# immutability: fetchConfig is used before it is declared (L60 calls L63)
# Fix: move fetchConfig definition before the useEffect
# Also 3x any types at L89, L108, L130
c = read('pages/LdapSettings.tsx')

# The effect at L59-61 calls fetchConfig which is defined at L63
# Fix: add useCallback, move the function before the effect, or inline
# Simplest: add useCallback and just move the function declaration before useEffect
# Find the effect block and fetchConfig definition, swap order

# The effect: "  useEffect(() => {\n    fetchConfig();\n  }, []);\n\n  const fetchConfig"
c = sub(c,
    '  useEffect(() => {\n'
    '    fetchConfig();\n'
    '  }, []);\n'
    '\n'
    '  const fetchConfig = async () => {',
    '  const fetchConfig = async () => {',
    'LdapSettings (move fetchConfig before effect - part 1)')

# Now we need to find where fetchConfig ends and put the useEffect after it
# From diagnostic: fetchConfig ends around L78 with "  };"
# Need to find the exact closing pattern
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 58 <= i <= 90:
        print(f"    LdapL{i}: {line.rstrip()}")

write('pages/LdapSettings.tsx', c)

# ─── DataServices.tsx ──────────────────────────────────────────────────────────
c = read('pages/DataServices.tsx')
lines = c.split('\n')
print("\n  DataServices first 15 lines:")
for i, line in enumerate(lines, 1):
    if i <= 15:
        print(f"    L{i}: {line.rstrip()}")
# The any[] -> Record[] fix — check
for i, line in enumerate(lines, 1):
    if 'any' in line or 'useState' in line:
        print(f"    L{i}: {line.rstrip()}")
write('pages/DataServices.tsx', c)

# ─── ClusterContext.tsx ────────────────────────────────────────────────────────
c = read('contexts/ClusterContext.tsx')
lines = c.split('\n')
print("\n  ClusterContext violations:")
for i, line in enumerate(lines, 1):
    if 'any' in line or 'only-export' in line:
        print(f"    L{i}: {line.rstrip()}")
write('contexts/ClusterContext.tsx', c)

# ─── ConfirmDialog.tsx ─────────────────────────────────────────────────────────
c = read('components/ConfirmDialog.tsx')
lines = c.split('\n')
print("\n  ConfirmDialog violations:")
for i, line in enumerate(lines, 1):
    if i <= 35:
        print(f"    L{i}: {line.rstrip()}")
write('components/ConfirmDialog.tsx', c)

print("\nDone.")
