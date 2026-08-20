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
    if old not in c:
        print(f"  MISS [{tag}]")
        return c
    print(f"  OK   [{tag}]")
    return c.replace(old, new, 1)

# ─── Artifacts.tsx: fix fetchVersions (not wrapped yet) ──────────────────────
c = read('pages/Artifacts.tsx')

# fetchVersions: starts at "const fetchVersions = useCallback(async () => {"
# and ends at "  };\n\n  const fetchHosts = async () => {"
# (the MISS was because the closing pattern expected "finally" block)
# Fix: close useCallback on fetchVersions
c = sub(c,
    '    setVersions((data.content || []).map((a: ArtifactVersion) => ({\n'
    '      id: a.id,\n'
    '      service_type: (a.serviceType || \'KAFKA\').toUpperCase(),\n'
    '      version: a.version,\n'
    '      available: a.status === \'AVAILABLE\',\n'
    '      release_date: a.createdAt ? new Date(a.createdAt).toLocaleDateString() : \'\',\n'
    '      size_mb: (a.fileSizeBytes / 1024 / 1024).toFixed(1),\n'
    '      filename: a.fileName,\n'
    '      sha256: a.sha256,\n'
    '      download_url: a.downloadUrl || `/api/v1/artifacts/${a.id}/download`,\n'
    '    })));\n'
    '  };\n'
    '\n'
    '  const fetchHosts = async () => {',
    '    setVersions((data.content || []).map((a: ArtifactVersion) => ({\n'
    '      id: a.id,\n'
    '      service_type: (a.serviceType || \'KAFKA\').toUpperCase(),\n'
    '      version: a.version,\n'
    '      available: a.status === \'AVAILABLE\',\n'
    '      release_date: a.createdAt ? new Date(a.createdAt).toLocaleDateString() : \'\',\n'
    '      size_mb: (a.fileSizeBytes / 1024 / 1024).toFixed(1),\n'
    '      filename: a.fileName,\n'
    '      sha256: a.sha256,\n'
    '      download_url: a.downloadUrl || `/api/v1/artifacts/${a.id}/download`,\n'
    '    })));\n'
    '  }, []);\n'
    '\n'
    '  const fetchHosts = useCallback(async () => {',
    'Artifacts fetchVersions close + fetchHosts start')

# fetchHosts currently: "const fetchHosts = async () => {" ... "}, []);"
# The "}, []);" is wrong (was written by previous patch to fetchParcelState)
# Fix: fetchHosts ends at "setHosts(await res.json());\n  }," — make it "}, []);"
c = sub(c,
    '  const fetchHosts = useCallback(async () => {\n'
    '    const res = await fetch(\'/api/v1/ui/hosts\');\n'
    '    if (!res.ok) return;\n'
    '    setHosts(await res.json());\n'
    '  }, []);',
    '  const fetchHosts = useCallback(async () => {\n'
    '    const res = await fetch(\'/api/v1/ui/hosts\');\n'
    '    if (!res.ok) return;\n'
    '    setHosts(await res.json());\n'
    '  }, []);',
    'Artifacts fetchHosts verify (no-op)')

# Verify the current state
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 139 <= i <= 185:
        print(f"  Art L{i}: {line.rstrip()}")

write('pages/Artifacts.tsx', c)
print("\nDone.")
