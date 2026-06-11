import { useEffect, useMemo, useState } from 'react';
import { Database, RefreshCw, Plus, Trash2, X, Check } from 'lucide-react';
import { useParams } from 'react-router-dom';

type CompatibilityLevel = 'BACKWARD' | 'BACKWARD_TRANSITIVE' | 'FORWARD' | 'FORWARD_TRANSITIVE' | 'FULL' | 'FULL_TRANSITIVE' | 'NONE';
type SchemaType = 'AVRO' | 'JSON' | 'PROTOBUF';

interface RegistryHealth {
  reachable: boolean;
  url: string | null;
  subject_count: number | null;
}

interface SchemaVersion {
  id: number;
  version: number;
  schema_text: string;
  schema_type: SchemaType | null;
}

const COMPAT_LEVELS: CompatibilityLevel[] = [
  'BACKWARD', 'BACKWARD_TRANSITIVE', 'FORWARD', 'FORWARD_TRANSITIVE',
  'FULL', 'FULL_TRANSITIVE', 'NONE',
];

export function SchemaRegistry() {
  const admin = true; // Hardcoded for now
  const { id: clusterId } = useParams<{ id: string }>();
  const [health, setHealth] = useState<RegistryHealth | null>(null);
  const [subjects, setSubjects] = useState<string[]>([]);
  const [selectedSubject, setSelectedSubject] = useState<string | null>(null);
  const [versions, setVersions] = useState<number[]>([]);
  const [activeVersion, setActiveVersion] = useState<SchemaVersion | null>(null);
  const [compat, setCompat] = useState<CompatibilityLevel>('BACKWARD');
  const [showRegister, setShowRegister] = useState(false);
  const [error, setError] = useState('');
  const [info, setInfo] = useState('');
  const [loading, setLoading] = useState(false);

  const reload = useMemo(() => async () => {
    if (!clusterId) return;
    setLoading(true);
    setError('');
    try {
      const hRes = await fetch(`/api/v1/schema-registry/${clusterId}/health`);
      if (!hRes.ok) throw new Error('Failed to load registry health');
      const h = await hRes.json();
      setHealth(h);

      if (h.reachable) {
        const subsRes = await fetch(`/api/v1/schema-registry/${clusterId}/subjects`);
        const subs = await subsRes.json();
        setSubjects(subs);

        const compatRes = await fetch(`/api/v1/schema-registry/${clusterId}/compatibility`);
        if (compatRes.ok) {
          const c = await compatRes.json();
          setCompat(c.compatibility);
        }

        if (subs.length && !selectedSubject) setSelectedSubject(subs[0]);
      } else {
        setSubjects([]);
      }
    } catch (err: any) {
      setError(err.message || 'Failed to load registry');
    } finally {
      setLoading(false);
    }
  }, [clusterId, selectedSubject]);

  useEffect(() => { reload(); }, [reload]);

  useEffect(() => {
    if (!clusterId || !selectedSubject) {
      setVersions([]); setActiveVersion(null); return;
    }
    const fetchVersions = async () => {
      try {
        const vsRes = await fetch(`/api/v1/schema-registry/${clusterId}/subjects/${selectedSubject}/versions`);
        const vs = await vsRes.json();
        setVersions(vs);
        if (vs.length) {
          const latest = vs[vs.length - 1];
          const vRes = await fetch(`/api/v1/schema-registry/${clusterId}/subjects/${selectedSubject}/versions/${latest}`);
          setActiveVersion(await vRes.json());
        } else {
          setActiveVersion(null);
        }
      } catch {
        setVersions([]);
      }
    };
    fetchVersions();
  }, [clusterId, selectedSubject]);

  const onSelectVersion = async (v: number) => {
    if (!clusterId || !selectedSubject) return;
    try {
      const vRes = await fetch(`/api/v1/schema-registry/${clusterId}/subjects/${selectedSubject}/versions/${v}`);
      setActiveVersion(await vRes.json());
    } catch {
      // ignore
    }
  };

  const onDeleteSubject = async (subject: string) => {
    if (!clusterId) return;
    if (!confirm(`Delete subject "${subject}" and all its versions?`)) return;
    try {
      const res = await fetch(`/api/v1/schema-registry/${clusterId}/subjects/${subject}`, { method: 'DELETE' });
      if (!res.ok) throw new Error('Delete failed');
      setSelectedSubject(null);
      await reload();
    } catch (err: any) {
      setError(err.message || 'Delete failed');
    }
  };

  const onUpdateCompat = async (level: CompatibilityLevel) => {
    if (!clusterId) return;
    try {
      const res = await fetch(`/api/v1/schema-registry/${clusterId}/compatibility`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ compatibility: level })
      });
      if (!res.ok) throw new Error('Failed to update compatibility');
      const r = await res.json();
      setCompat(r.compatibility);
      setInfo(`Compatibility set to ${r.compatibility}`);
      setTimeout(() => setInfo(''), 3000);
    } catch (err: any) {
      setError(err.message || 'Failed to update compatibility');
    }
  };

  return (
    <div className="migrated-page">
      <div className="page-header">
        <div>
          <h1 className="page-title">
            <Database size={24} style={{ color: 'var(--color-info)' }} /> Schema Registry
          </h1>
          <p className="page-subtitle">
            Apicurio Registry, ccompat-v7 endpoint — wire-compatible with Confluent Schema Registry.
          </p>
        </div>
        <div className="header-actions">
          <button onClick={reload} disabled={loading} className="btn btn-secondary">
            <RefreshCw size={14} className={loading ? 'animate-spin' : ''} /> Refresh
          </button>
        </div>
      </div>

      {!clusterId ? (
        <div className="empty-state migrated-card">
          Deploy a managed Kafka cluster with the Schema Registry role first.
        </div>
      ) : (
        <>
          <div className="mb-4">
            {health?.reachable ? (
              <span className="flex-row gap-2 text-sm" style={{ color: 'var(--color-success)' }}>
                <Check size={14} /> Registry connected
                {health.url && <span className="font-mono text-xs" style={{ color: 'var(--text-tertiary)' }}>({health.url})</span>}
                {health.subject_count !== null && <span style={{ color: 'var(--text-secondary)' }}>· {health.subject_count} subject(s)</span>}
              </span>
            ) : (
              <span className="text-sm" style={{ color: 'var(--color-warning)' }}>
                Schema Registry not reachable on this cluster — add the schema_registry role and redeploy.
              </span>
            )}
          </div>

          {error && <div className="alert alert-error mb-4">{error}</div>}
          {info && <div className="alert alert-info mb-4">{info}</div>}

          <div className="flex-row gap-4 mb-4 flex-wrap" style={{ alignItems: 'center' }}>
            {admin && (
              <button onClick={() => setShowRegister(true)} disabled={!health?.reachable}
                className="btn btn-primary">
                <Plus size={14} /> Register schema
              </button>
            )}
            <span className="text-sm" style={{ color: 'var(--text-secondary)' }}>Global compatibility:</span>
            <select value={compat} onChange={(e) => onUpdateCompat(e.target.value as CompatibilityLevel)}
              disabled={!admin || !health?.reachable} className="form-select" style={{ width: 'auto' }}>
              {COMPAT_LEVELS.map((l) => <option key={l} value={l}>{l}</option>)}
            </select>
          </div>

          <div className="flex-row gap-4 align-top">
            <div className="migrated-card flex-1" style={{ padding: 0, overflow: 'hidden', alignSelf: 'flex-start' }}>
              <div style={{ padding: '0.75rem 1rem', borderBottom: '1px solid var(--border-default)', fontSize: '0.75rem', textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--text-secondary)', fontWeight: 600 }}>
                Subjects ({subjects.length})
              </div>
              <ul style={{ listStyle: 'none', margin: 0, padding: 0, maxHeight: '600px', overflowY: 'auto' }}>
                {!subjects.length && <li style={{ padding: '1.5rem', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '0.875rem' }}>No subjects yet.</li>}
                {subjects.map((s) => (
                  <li key={s}
                    onClick={() => setSelectedSubject(s)}
                    className="flex-row justify-between"
                    style={{
                      padding: '0.75rem 1rem',
                      cursor: 'pointer',
                      fontSize: '0.875rem',
                      borderBottom: '1px solid var(--border-default)',
                      backgroundColor: selectedSubject === s ? 'var(--color-info-light)' : 'transparent',
                    }}>
                    <span className="font-mono">{s}</span>
                    {admin && (
                      <button onClick={(e) => { e.stopPropagation(); onDeleteSubject(s); }}
                        className="btn-icon text-danger">
                        <Trash2 size={14} />
                      </button>
                    )}
                  </li>
                ))}
              </ul>
            </div>

            <div className="migrated-card flex-[2]" style={{ padding: 0, alignSelf: 'flex-start' }}>
              <div className="flex-row justify-between" style={{ padding: '0.75rem 1rem', borderBottom: '1px solid var(--border-default)' }}>
                <div style={{ fontSize: '0.75rem', textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--text-secondary)', fontWeight: 600 }}>
                  {selectedSubject ?? 'Select a subject'}
                </div>
                {selectedSubject && versions.length > 0 && (
                  <select onChange={(e) => onSelectVersion(parseInt(e.target.value, 10))}
                    value={activeVersion?.version ?? ''} className="form-select" style={{ width: 'auto', padding: '0.25rem 2rem 0.25rem 0.5rem', fontSize: '0.75rem', minHeight: 'auto' }}>
                    {versions.map((v) => <option key={v} value={v}>v{v}</option>)}
                  </select>
                )}
              </div>
              <div style={{ padding: '1rem' }}>
                {!activeVersion ? (
                  <p style={{ color: 'var(--text-tertiary)', fontSize: '0.875rem' }}>No schema selected.</p>
                ) : (
                  <>
                    <div className="flex-row gap-4 mb-4" style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
                      <span>id <code className="font-mono" style={{ background: 'var(--bg-raised)', padding: '2px 4px', borderRadius: '4px' }}>{activeVersion.id}</code></span>
                      <span>version <code className="font-mono" style={{ background: 'var(--bg-raised)', padding: '2px 4px', borderRadius: '4px' }}>{activeVersion.version}</code></span>
                      <span>type <code className="font-mono" style={{ background: 'var(--bg-raised)', padding: '2px 4px', borderRadius: '4px' }}>{activeVersion.schema_type ?? 'AVRO'}</code></span>
                    </div>
                    <pre className="font-mono" style={{ backgroundColor: '#1C1C1A', color: '#EAF3DE', padding: '1rem', borderRadius: 'var(--radius-md)', fontSize: '0.75rem', overflowX: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-all', margin: 0 }}>
                      {(() => {
                        try { return JSON.stringify(JSON.parse(activeVersion.schema_text), null, 2); }
                        catch { return activeVersion.schema_text; }
                      })()}
                    </pre>
                  </>
                )}
              </div>
            </div>
          </div>

          {showRegister && clusterId && (
            <RegisterModal
              clusterId={clusterId}
              onClose={() => setShowRegister(false)}
              onSaved={async (subject) => {
                setShowRegister(false);
                await reload();
                setSelectedSubject(subject);
              }}
            />
          )}
        </>
      )}
    </div>
  );
}

function RegisterModal({
  clusterId, onClose, onSaved,
}: { clusterId: string; onClose: () => void; onSaved: (subject: string) => void }) {
  const [subject, setSubject] = useState('');
  const [schemaText, setSchemaText] = useState(
    '{\n  "type": "record",\n  "name": "User",\n  "fields": [\n    {"name": "id", "type": "long"},\n    {"name": "name", "type": "string"}\n  ]\n}'
  );
  const [schemaType, setSchemaType] = useState<SchemaType>('AVRO');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  const submit = async () => {
    if (!subject.trim() || !schemaText.trim()) {
      setError('Subject and schema text are required');
      return;
    }
    setSaving(true);
    try {
      const res = await fetch(`/api/v1/schema-registry/${clusterId}/subjects/${subject.trim()}/versions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ schema: schemaText, schemaType })
      });
      if (!res.ok) throw new Error('Register failed');
      onSaved(subject.trim());
    } catch (err: any) {
      setError(err.message || 'Register failed');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div style={{ position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.4)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '1rem', zIndex: 50 }}>
      <div className="migrated-card flex-col" style={{ maxWidth: '42rem', width: '100%', padding: 0 }}>
        <div className="flex-row justify-between" style={{ padding: '1rem 1.5rem', borderBottom: '1px solid var(--border-default)' }}>
          <h3 style={{ fontSize: '1.125rem', fontWeight: 600, margin: 0 }}>Register schema</h3>
          <button onClick={onClose} className="btn-icon"><X size={18} /></button>
        </div>
        <div className="flex-col gap-4" style={{ padding: '1.5rem' }}>
          {error && <div className="alert alert-error">{error}</div>}
          <div className="flex-row gap-4">
            <div className="form-group flex-[2]" style={{ marginBottom: 0 }}>
              <label className="form-label">Subject</label>
              <input value={subject} onChange={(e) => setSubject(e.target.value)}
                placeholder="orders-value" className="form-input" />
              <p className="form-help">Convention: <code>&lt;topic&gt;-key</code> or <code>&lt;topic&gt;-value</code>.</p>
            </div>
            <div className="form-group flex-1" style={{ marginBottom: 0 }}>
              <label className="form-label">Type</label>
              <select value={schemaType} onChange={(e) => setSchemaType(e.target.value as SchemaType)}
                className="form-select">
                <option>AVRO</option>
                <option>JSON</option>
                <option>PROTOBUF</option>
              </select>
            </div>
          </div>
          <div className="form-group" style={{ marginBottom: 0 }}>
            <label className="form-label">Schema</label>
            <textarea value={schemaText} onChange={(e) => setSchemaText(e.target.value)}
              rows={14} className="form-textarea font-mono text-xs" />
          </div>
        </div>
        <div className="flex-row justify-end gap-2" style={{ padding: '1rem 1.5rem', backgroundColor: 'var(--bg-raised)', borderTop: '1px solid var(--border-default)', borderBottomLeftRadius: 'var(--radius-lg)', borderBottomRightRadius: 'var(--radius-lg)' }}>
          <button onClick={onClose} className="btn btn-secondary">Cancel</button>
          <button onClick={submit} disabled={saving}
            className="btn btn-primary">
            {saving ? 'Registering…' : 'Register'}
          </button>
        </div>
      </div>
    </div>
  );
}
