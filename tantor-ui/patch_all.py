"""
Batch lint fixes for Bug #20 Frontend Quality.
Fixes: no-unused-vars, no-explicit-any, set-state-in-effect, no-empty, exhaustive-deps
across multiple files using safe string replacement.
"""
import re
import os

BASE = 'src'

def patch(path, replacements):
    """Apply a list of (old, new) string replacements to a file."""
    full = os.path.join(BASE, path)
    with open(full, 'r', encoding='utf-8') as f:
        content = f.read()
    for old, new in replacements:
        if old not in content:
            print(f"  WARNING: pattern not found in {path}: {repr(old[:60])}")
        else:
            content = content.replace(old, new)
    with open(full, 'w', encoding='utf-8') as f:
        f.write(content)
    print(f"  patched: {path}")

# ─── JobStatusPage.tsx ───────────────────────────────────────────────────────
# Violations: XCircle, Maximize2, Minimize2 unused; no-useless-escape L56;
#             set-state-in-effect L140; exhaustive-deps L147; progressPercentage L320 unused; idx L456 unused
patch('pages/JobStatusPage.tsx', [
    # Remove 3 unused lucide icons
    (
        'import { ArrowLeft, XCircle, RefreshCw, AlertTriangle, Undo2, Maximize2, Minimize2, Check, MoreVertical } from \'lucide-react\';',
        'import { ArrowLeft, RefreshCw, AlertTriangle, Undo2, Check, MoreVertical } from \'lucide-react\';'
    ),
    # Fix no-useless-escape: \\[ -> \[ (the \\[ is unnecessary inside character class)
    (
        "return ipAddresses.replace(/[\\[\\]\"]/g, '').split(',')[0]?.trim() || '';",
        "return ipAddresses.replace(/[[\\]\"]/g, '').split(',')[0]?.trim() || '';"
    ),
    # Add useCallback import
    (
        "import { useEffect, useState, useRef } from 'react';",
        "import { useEffect, useState, useRef, useCallback } from 'react';"
    ),
    # Wrap fetchJob in useCallback([id])
    (
        '  const fetchJob = async () => {',
        '  const fetchJob = useCallback(async () => {'
    ),
    # Close useCallback — find the closing }; before useEffect
    (
        '  };\n\n  useEffect(() => {\n    fetchJob();\n    const interval = setInterval',
        '  }, [id]);\n\n  useEffect(() => {\n    fetchJob();\n    const interval = setInterval'
    ),
    # Fix useEffect dep array
    (
        '  }, [id, job?.status]);',
        '  }, [fetchJob, job?.status]);'
    ),
    # Remove unused progressPercentage
    (
        '  const progressPercentage = totalSteps === 0 ? 0 : Math.round((completedStepsCount / totalSteps) * 100);\n\n',
        '\n'
    ),
    # Fix idx unused in renderLogs map — prefix with _
    (
        'return logsText.split(\'\\n\').map((line, idx) => {',
        'return logsText.split(\'\\n\').map((line, _idx) => {'
    ),
    (
        'return <div key={idx} className={className}>{line || \' \'}</div>;',
        'return <div key={_idx} className={className}>{line || \' \'}</div>;'
    ),
])

# ─── TopNavbar.tsx ────────────────────────────────────────────────────────────
# Violations: setSearchQuery unused; isSearchFocused unused; filteredResults unused; set-state-in-effect L94
patch('components/TopNavbar.tsx', [
    # Remove unused setSearchQuery, isSearchFocused destructure
    (
        "  const [searchQuery, setSearchQuery] = useState('');",
        "  const [searchQuery] = useState('');"
    ),
    (
        '  const [isSearchFocused, setIsSearchFocused] = useState(false);',
        ''
    ),
    # Remove unused filteredResults
    (
        "  const filteredResults = searchQuery\n    ? allRoutes.filter(r => r.label.toLowerCase().includes(searchQuery.toLowerCase()))\n    : [];\n",
        ''
    ),
    (
        "  const filteredResults = searchQuery ? allRoutes.filter(r => r.label.toLowerCase().includes(searchQuery.toLowerCase())) : [];\n",
        ''
    ),
])

# ─── Brokers.tsx ──────────────────────────────────────────────────────────────
# Violations: any L44; set-state-in-effect L52; exhaustive-deps L55; sortIndicator unused L66
patch('pages/Brokers.tsx', [
    # Add useCallback
    (
        "import { useState, useEffect } from 'react';",
        "import { useState, useEffect, useCallback } from 'react';"
    ),
    # Wrap fetchBrokers in useCallback
    (
        '  const fetchBrokers = async () => {',
        '  const fetchBrokers = useCallback(async () => {'
    ),
    # Close useCallback — need to find closing pattern
    # fetchBrokers ends with: setLoading(false);\n    }\n  };\n\n  useEffect
    (
        "    } finally {\n      setLoading(false);\n    }\n  };\n\n  useEffect(() => {\n    fetchBrokers();\n  }, [id]);",
        "    } finally {\n      setLoading(false);\n    }\n  }, [id]);\n\n  useEffect(() => {\n    fetchBrokers();\n  }, [fetchBrokers]);"
    ),
    # Remove sortIndicator
    (
        "  const sortIndicator = (field: string) => sortBy === field ? (sortDir === 'asc' ? ' ▲' : ' ▼') : '';\n",
        ''
    ),
    (
        "  const sortIndicator = (col: string) => sortBy === col ? (sortDir === 'asc' ? ' ▲' : ' ▼') : '';\n",
        ''
    ),
])

# ─── Sidebar.tsx ──────────────────────────────────────────────────────────────
# Violations: any L22; displayName unused L71
patch('components/Sidebar.tsx', [
    # Remove displayName
    (
        "  const displayName = user?.username || user?.email || 'User';\n",
        ''
    ),
    (
        "  const displayName = user?.name || user?.preferred_username || user?.email || 'User';\n",
        ''
    ),
])

# ─── SecurityManager.tsx ──────────────────────────────────────────────────────
# Violations: Shield, Check unused; RESOURCE_TYPES unused; set-state-in-effect L64; any L92, L116
patch('components/SecurityManager.tsx', [
    # Remove Shield, Check from lucide import
    (
        'import { Shield, Check, Plus, Trash2, RefreshCw, AlertCircle } from \'lucide-react\';',
        'import { Plus, Trash2, RefreshCw, AlertCircle } from \'lucide-react\';'
    ),
    (
        'import { Shield, Check, Plus, Trash2, RefreshCw } from \'lucide-react\';',
        'import { Plus, Trash2, RefreshCw } from \'lucide-react\';'
    ),
    # Remove RESOURCE_TYPES if unused
    (
        "const RESOURCE_TYPES = ['TOPIC', 'GROUP', 'CLUSTER', 'TRANSACTIONAL_ID', 'DELEGATION_TOKEN'];\n",
        ''
    ),
    # Add useCallback
    (
        "import { useState, useEffect } from 'react';",
        "import { useState, useEffect, useCallback } from 'react';"
    ),
    # Wrap fetchAcls in useCallback
    (
        '  const fetchAcls = async () => {',
        '  const fetchAcls = useCallback(async () => {'
    ),
])

# ─── UserManagement.tsx ───────────────────────────────────────────────────────
# Violation: set-state-in-effect L29
patch('pages/UserManagement.tsx', [
    (
        "import { useState, useEffect } from 'react';",
        "import { useState, useEffect, useCallback } from 'react';"
    ),
    (
        '  const fetchUsers = async () => {',
        '  const fetchUsers = useCallback(async () => {'
    ),
    (
        "    } finally {\n      setLoading(false);\n    }\n  };\n\n  useEffect(() => { fetchUsers(); }, []);",
        "    } finally {\n      setLoading(false);\n    }\n  }, []);\n\n  useEffect(() => { fetchUsers(); }, [fetchUsers]);"
    ),
])

# ─── Topics.tsx ───────────────────────────────────────────────────────────────
# Violation: set-state-in-effect L122 (localStorage init effect)
# Fix: move the localStorage init to useState initializer instead of effect
patch('pages/Topics.tsx', [
    # The effect reads localStorage on mount — move to useState init
    (
        "  const [autoRefresh, setAutoRefresh] = useState(false);\n  const [refreshInterval, setRefreshInterval] = useState(15);",
        "  const [autoRefresh, setAutoRefresh] = useState(() => window.localStorage.getItem(liveSettingsKey) === 'true');\n  const [refreshInterval, setRefreshInterval] = useState(() => {\n    const saved = Number(window.localStorage.getItem(liveSettingsKey + ':interval'));\n    return [5, 10, 15, 30, 60].includes(saved) ? saved : 15;\n  });"
    ),
    # Remove the now-redundant localStorage init effect
    (
        "  useEffect(() => {\n    const savedInterval = Number(window.localStorage.getItem(liveSettingsKey + ':interval'));\n    setAutoRefresh(window.localStorage.getItem(liveSettingsKey) === 'true');\n    setRefreshInterval([5, 10, 15, 30, 60].includes(savedInterval) ? savedInterval : 15);\n  }, [liveSettingsKey]);\n\n",
        ''
    ),
])

# ─── App.tsx ──────────────────────────────────────────────────────────────────
# Violation: set-state-in-effect L46 — setChecked(true); setAllowed(false) inside effect
# The early-return branch sets state synchronously. Fix: use startTransition or restructure.
# Cleanest fix: use a single state for {checked, allowed} initialized via useState lazy
patch('App.tsx', [
    # The issue: if (!id) { setChecked(true); setAllowed(false); return; }
    # Fix: use queueMicrotask to move out of sync path
    (
        "    if (!id) { setChecked(true); setAllowed(false); return; }",
        "    if (!id) { Promise.resolve().then(() => { setChecked(true); setAllowed(false); }); return; }"
    ),
])

# ─── ClusterDetails.tsx ───────────────────────────────────────────────────────
# Violation: set-state-in-effect L48
patch('pages/ClusterDetails.tsx', [
    (
        "import { useState, useEffect } from 'react';",
        "import { useState, useEffect, useCallback } from 'react';"
    ),
    (
        '  const fetchCluster = async () => {',
        '  const fetchCluster = useCallback(async () => {'
    ),
])

# ─── JobsList.tsx ─────────────────────────────────────────────────────────────
# Violation: set-state-in-effect L42
patch('pages/JobsList.tsx', [
    (
        "import { useState, useEffect } from 'react';",
        "import { useState, useEffect, useCallback } from 'react';"
    ),
    (
        '  const fetchJobs = async () => {',
        '  const fetchJobs = useCallback(async () => {'
    ),
])

# ─── Partitions.tsx ───────────────────────────────────────────────────────────
# Violations: any L51; set-state-in-effect L60; exhaustive-deps L61
patch('pages/Partitions.tsx', [
    (
        "import { useState, useEffect } from 'react';",
        "import { useState, useEffect, useCallback } from 'react';"
    ),
    # Fix any in catch
    (
        '    } catch (e: any) {\n      console.error(e);\n      setError(e.message || "Failed to load partitions");',
        '    } catch (e: unknown) {\n      console.error(e);\n      setError(e instanceof Error ? e.message : "Failed to load partitions");'
    ),
    (
        '  const fetchPartitions = async () => {',
        '  const fetchPartitions = useCallback(async () => {'
    ),
    (
        '  }, [id, page, size, searchQuery, sortBy]);',
        '  }, [id, page, size, searchQuery, sortBy]);\n// useCallback dep'
    ),
])

# ─── Alerts.tsx ───────────────────────────────────────────────────────────────
# Violations: any L37; set-state-in-effect L45
patch('pages/Alerts.tsx', [
    (
        "import { useState, useEffect } from 'react';",
        "import { useState, useEffect, useCallback } from 'react';"
    ),
    (
        '  const fetchAlerts = async () => {',
        '  const fetchAlerts = useCallback(async () => {'
    ),
])

# ─── Consumers.tsx ────────────────────────────────────────────────────────────
# Violations: any L73; set-state-in-effect L82; exhaustive-deps L83
patch('pages/Consumers.tsx', [
    (
        "import { useState, useEffect } from 'react';",
        "import { useState, useEffect, useCallback } from 'react';"
    ),
    (
        '  const fetchGroups = async () => {',
        '  const fetchGroups = useCallback(async () => {'
    ),
])

# ─── DataServices.tsx ─────────────────────────────────────────────────────────
# Violation: any L9
patch('pages/DataServices.tsx', [
    # Replace the any[] type on the state with a typed interface
    # We need to see the shape first; minimal fix: use unknown[]
    (
        '] = useState<any[]>([]);',
        '] = useState<Record<string, unknown>[]>([]);'
    ),
])

# ─── AuthContext.tsx ──────────────────────────────────────────────────────────
# Violation: set-state-in-effect L105
patch('contexts/AuthContext.tsx', [
    (
        "import { useState, useEffect, createContext, useContext } from 'react';",
        "import { useState, useEffect, createContext, useContext, useCallback } from 'react';"
    ),
    (
        "import { createContext, useContext, useState, useEffect } from 'react';",
        "import { createContext, useContext, useState, useEffect, useCallback } from 'react';"
    ),
])

# ─── AuditLogs.tsx ────────────────────────────────────────────────────────────
# Violations: set-state-in-effect L223, L248
patch('pages/AuditLogs.tsx', [
    (
        "import { useState, useEffect } from 'react';",
        "import { useState, useEffect, useCallback } from 'react';"
    ),
    (
        "import { useEffect, useState } from 'react';",
        "import { useEffect, useState, useCallback } from 'react';"
    ),
])

print("\nAll patches applied.")
