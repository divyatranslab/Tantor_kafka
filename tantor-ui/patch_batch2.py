"""
Fix remaining violations: AuditLogs fetchLogs useCallback, Consumers fetchGroups,
LdapSettings fetchConfig+useEffect+any, ClusterContext any, ConfirmDialog.
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
# fetchLogs is not useCallback — wrap it and update deps
c = read('pages/AuditLogs.tsx')

c = sub(c,
    "  const fetchLogs = async () => {",
    "  const fetchLogs = useCallback(async () => {",
    'AuditLogs (useCallback wrap)')

# Close useCallback — fetchLogs ends at "};" before useEffect
c = sub(c,
    '    } finally {\n'
    '      setLoading(false);\n'
    '    }\n'
    '  };\n'
    '\n'
    '  useEffect(() => { void (async () => { await fetchLogs(); })(); }, [fetchLogs]);',
    '    } finally {\n'
    '      setLoading(false);\n'
    '    }\n'
    '  }, []);\n'
    '\n'
    '  useEffect(() => { void (async () => { await fetchLogs(); })(); }, [fetchLogs]);',
    'AuditLogs (close useCallback)')

write('pages/AuditLogs.tsx', c)

# ─── Consumers.tsx ─────────────────────────────────────────────────────────
# fetchGroups at L62 is now useCallback but closes with "};" not "}, [...];"
c = read('pages/Consumers.tsx')

c = sub(c,
    '    } finally {\n'
    '      setLoading(false);\n'
    '    }\n'
    '  };\n'
    '\n'
    '  useEffect(() => {\n'
    '    fetchGroups();\n'
    '  }, [id, page, searchQuery, sortBy]);',
    '    } finally {\n'
    '      setLoading(false);\n'
    '    }\n'
    '  }, [id, page, searchQuery, sortBy]);\n'
    '\n'
    '  useEffect(() => {\n'
    '    void (async () => { await fetchGroups(); })();\n'
    '  }, [fetchGroups]);',
    'Consumers (close useCallback + IIFE effect)')

write('pages/Consumers.tsx', c)

# ─── LdapSettings.tsx ──────────────────────────────────────────────────────
# fetchConfig is now defined before useEffect (good for immutability rule)
# But we need to add useEffect back after fetchConfig definition
# Also fix any at L85 (payload: any)
c = read('pages/LdapSettings.tsx')

# fetchConfig ends at "  };" before "  const handleSave"
# Add the useEffect back
c = sub(c,
    '  };\n'
    '\n'
    '  const handleSave = async (e: React.FormEvent) => {',
    '  };\n'
    '\n'
    '  useEffect(() => {\n'
    '    void (async () => { await fetchConfig(); })();\n'
    '  }, [fetchConfig]);\n'
    '\n'
    '  const handleSave = async (e: React.FormEvent) => {',
    'LdapSettings (add useEffect back)')

# fetchConfig needs to be useCallback too for dep to be stable
c = sub(c,
    '  const fetchConfig = async () => {',
    '  const fetchConfig = useCallback(async () => {',
    'LdapSettings (useCallback wrap)')

# Add useCallback import
c = sub(c,
    "import { useState, useEffect } from 'react';",
    "import { useState, useEffect, useCallback } from 'react';",
    'LdapSettings (useCallback import)')

# Close useCallback
c = sub(c,
    '    } catch {\n'
    '      // No config yet\n'
    '    } finally {\n'
    '      setLoading(false);\n'
    '    }\n'
    '  };\n'
    '\n'
    '  useEffect(() => {\n'
    '    void (async () => { await fetchConfig(); })();\n'
    '  }, [fetchConfig]);',
    '    } catch {\n'
    '      // No config yet\n'
    '    } finally {\n'
    '      setLoading(false);\n'
    '    }\n'
    '  }, []);\n'
    '\n'
    '  useEffect(() => {\n'
    '    void (async () => { await fetchConfig(); })();\n'
    '  }, [fetchConfig]);',
    'LdapSettings (close useCallback)')

# Fix any at payload definition
c = sub(c,
    '      const payload: any = {\n'
    '        ...config,\n'
    '        bindPassword: bindPassword || undefined,\n'
    '      };',
    '      const payload: LdapConfig & { bindPassword?: string } = {\n'
    '        ...config,\n'
    '        bindPassword: bindPassword || undefined,\n'
    '      };',
    'LdapSettings (payload any)')

# Find and fix remaining any types
print("  LdapSettings remaining any:")
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 'any' in line:
        print(f"    L{i}: {line.rstrip()}")

write('pages/LdapSettings.tsx', c)

# ─── ClusterContext.tsx ───────────────────────────────────────────────────────
# [key: string]: any -> unknown
c = read('contexts/ClusterContext.tsx')
c = sub(c,
    '  [key: string]: any;',
    '  [key: string]: unknown;',
    'ClusterContext (any index sig)')

# react-refresh/only-export-components: useClusterContext is exported from component file
# This needs the useClusterContext hook to be in a separate file or the rule satisfied
# Check what's exported
lines = c.split('\n')
print("\n  ClusterContext exports:")
for i, line in enumerate(lines, 1):
    if 'export' in line or 'useCluster' in line:
        print(f"    L{i}: {line.rstrip()}")
write('contexts/ClusterContext.tsx', c)

# ─── App.tsx ─────────────────────────────────────────────────────────────────
# The set-state-in-effect fix we applied earlier — verify it worked
c = read('App.tsx')
lines = c.split('\n')
print("\n  App.tsx set-state-in-effect area:")
for i, line in enumerate(lines, 1):
    if 40 <= i <= 55:
        print(f"    L{i}: {line.rstrip()}")
write('App.tsx', c)

# ─── TopNavbar.tsx: setIsSearchFocused ────────────────────────────────────────
# setIsSearchFocused is called at L105 but isSearchFocused state doesn't exist
# This means the component was partially cleaned — isSearchFocused was removed from useState
# but the setIsSearchFocused call remains
c = read('components/TopNavbar.tsx')
lines = c.split('\n')
print("\n  TopNavbar all setIsSearchFocused lines:")
for i, line in enumerate(lines, 1):
    if 'isSearchFocused' in line or 'setIsSearchFocused' in line:
        print(f"    L{i}: {line.rstrip()}")
# Also check setSearchQuery
for i, line in enumerate(lines, 1):
    if 'setSearchQuery' in line or 'searchQuery' in line:
        print(f"    L{i}: {line.rstrip()}")
write('components/TopNavbar.tsx', c)

print("\nDone.")
