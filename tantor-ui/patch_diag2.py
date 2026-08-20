"""
Fix the remaining specific violations found in diagnostic output.
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

# ─── SecurityManager.tsx ─────────────────────────────────────────────────────
# Second catch at L113 still has `any`, and L90/L114 use err.response?.data?.detail
# These are axios-style responses — need proper typing
c = read('components/SecurityManager.tsx')

# First catch (L89) already changed to unknown but uses err.response/err.message — fix the body too
c = sub(c,
    '    } catch (err: unknown) {\n      setAlertMessage(err.response?.data?.detail || err.message || \'Failed to create ACL\');',
    '    } catch (err: unknown) {\n      const msg = (err as { response?: { data?: { detail?: string } }; message?: string })?.response?.data?.detail\n        || (err instanceof Error ? err.message : \'Failed to create ACL\');\n      setAlertMessage(msg);',
    'SecurityManager (fix first catch body)')

# Second catch still has any
c = sub(c,
    '    } catch (err: any) {\n      setAlertMessage(err.response?.data?.detail || err.message || \'Failed to delete ACL\');',
    '    } catch (err: unknown) {\n      const msg = (err as { response?: { data?: { detail?: string } }; message?: string })?.response?.data?.detail\n        || (err instanceof Error ? err.message : \'Failed to delete ACL\');\n      setAlertMessage(msg);',
    'SecurityManager (fix second catch body)')

write('components/SecurityManager.tsx', c)

# ─── AuditLogs.tsx ────────────────────────────────────────────────────────────
# set-state-in-effect L223: useEffect(() => { fetchLogs(); }, []);
# Add useCallback and fix effect
c = read('pages/AuditLogs.tsx')

# Add useCallback import
c = sub(c,
    "import { useEffect, useMemo, useState } from 'react';",
    "import { useEffect, useMemo, useState, useCallback } from 'react';",
    'AuditLogs (useCallback import)')

# Find fetchLogs definition — must be async and call setState
lines = c.split('\n')
print("  AuditLogs fetchLogs/useEffect context:")
for i, line in enumerate(lines, 1):
    if 215 <= i <= 260:
        print(f"    L{i}: {line.rstrip()}")
write('pages/AuditLogs.tsx', c)

# ─── Alerts.tsx ───────────────────────────────────────────────────────────────
c = read('pages/Alerts.tsx')
print("\n  Alerts useEffect area:")
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 38 <= i <= 55:
        print(f"    L{i}: {line.rstrip()}")
# any type usage
for i, line in enumerate(lines, 1):
    if 'any' in line:
        print(f"    L{i} (any): {line.rstrip()}")
write('pages/Alerts.tsx', c)

# ─── Consumers.tsx ────────────────────────────────────────────────────────────
c = read('pages/Consumers.tsx')
print("\n  Consumers useEffect area:")
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 75 <= i <= 95:
        print(f"    L{i}: {line.rstrip()}")
for i, line in enumerate(lines, 1):
    if 'any' in line:
        print(f"    L{i} (any): {line.rstrip()}")
write('pages/Consumers.tsx', c)

# ─── AuthContext.tsx ──────────────────────────────────────────────────────────
c = read('contexts/AuthContext.tsx')
print("\n  AuthContext set-state-in-effect area:")
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 100 <= i <= 115:
        print(f"    L{i}: {line.rstrip()}")
write('contexts/AuthContext.tsx', c)

# ─── LdapSettings.tsx ─────────────────────────────────────────────────────────
c = read('pages/LdapSettings.tsx')
print("\n  LdapSettings first 70 lines:")
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if i <= 70:
        print(f"    L{i}: {line.rstrip()}")
write('pages/LdapSettings.tsx', c)

print("\nDone.")
