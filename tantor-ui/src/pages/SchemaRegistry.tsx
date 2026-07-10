import { useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { CheckCircle, ChevronRight, Edit3, GitCompare, MoreVertical, Plus, RefreshCw, Save, Settings, Trash2, Upload, X } from 'lucide-react';
import './DataServiceTabs.css';

interface SchemaSubject {
  subject: string;
  type: string;
  version: number;
  id: number;
  schemaType: string;
  schema: string;
}

interface SchemaVersion {
  version: number;
  id: number;
  schemaType: string;
  schema: string;
}

interface SubjectDetails {
  subject: string;
  latest: SchemaVersion;
  versions: SchemaVersion[];
  compatibility: string;
}

interface SchemaSummary {
  connection: string;
  subjects: SchemaSubject[];
  totalSubjects: number;
  keySubjects: number;
  valueSubjects: number;
}

interface SavedConnection {
  id: string;
  connectionName: string;
  protocol: string;
  host: string;
  port: number;
  status: string;
  isDefault: boolean;
  certificateConfigured: boolean;
  truststoreConfigured: boolean;
  certificateType?: string;
}

const emptySchema = `{
  "type": "record",
  "name": "Example",
  "fields": [
    { "name": "id", "type": "string" }
  ]
}`;

const compatibilityOptions = [
  'BACKWARD',
  'BACKWARD_TRANSITIVE',
  'FORWARD',
  'FORWARD_TRANSITIVE',
  'FULL',
  'FULL_TRANSITIVE'
];

type View = 'list' | 'detail' | 'edit';

export function SchemaRegistry() {
  const { id } = useParams<{ id: string }>();
  const [view, setView] = useState<View>('list');
  const [summary, setSummary] = useState<SchemaSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showConnection, setShowConnection] = useState(false);
  const [selected, setSelected] = useState<SchemaSubject | null>(null);
  const [details, setDetails] = useState<SubjectDetails | null>(null);
  const [loadingDetails, setLoadingDetails] = useState(false);
  const [expandedVersions, setExpandedVersions] = useState<Set<number>>(new Set());

  // Edit form state
  const [editSchemaType, setEditSchemaType] = useState('AVRO');
  const [editCompatibility, setEditCompatibility] = useState('BACKWARD');
  const [newSchema, setNewSchema] = useState(emptySchema);

  // Create schema modal state
  const [showCreate, setShowCreate] = useState(false);
  const [createSubject, setCreateSubject] = useState('');
  const [createSchemaType, setCreateSchemaType] = useState('AVRO');
  const [createSchema, setCreateSchema] = useState(emptySchema);

  // ── Multi-instance state ──────────────────────────────────────────────────
  const [savedConnections, setSavedConnections] = useState<SavedConnection[]>([]);
  const [selectedConnectionId, setSelectedConnectionId] = useState<string | null>(null);

  // Connection form state
  const [formConnectionName, setFormConnectionName] = useState('');
  const [customIp, setCustomIp] = useState('');
  const [customPort, setCustomPort] = useState('');
  const [protocol, setProtocol] = useState('http');
  const [certType, setCertType] = useState('PEM');
  const [certFile, setCertFile] = useState<File | null>(null);
  const [certFileName, setCertFileName] = useState('');
  const [certPassword, setCertPassword] = useState('');
  const [certPasteMode, setCertPasteMode] = useState(false);
  const [certPasteText, setCertPasteText] = useState('');
  const [formIsDefault, setFormIsDefault] = useState(false);
  /** ID of the connection being edited — set when editing an existing connection. */
  const [editingConnectionId, setEditingConnectionId] = useState<string | null>(null);
  const [globalCompatibility, setGlobalCompatibility] = useState('BACKWARD');
  const [subjectCompatibility, setSubjectCompatibility] = useState('BACKWARD');
  const [connectSaving, setConnectSaving] = useState(false);
  const [connectError, setConnectError] = useState<string | null>(null);

  // Derived: currently selected connection object
  const selectedConn = useMemo(
    () => savedConnections.find(c => c.id === selectedConnectionId) ?? null,
    [savedConnections, selectedConnectionId]
  );

  // ── Cert helpers ──────────────────────────────────────────────

  /**
   * Safely appends ?connectionId=... to any URL using URLSearchParams.
   * Works even if the base URL already contains query params (avoids unsafe string concatenation).
   */
  const withConnId = (url: string, connId: string | null = selectedConnectionId): string => {
    if (!connId) return url;
    const [base, existing] = url.split('?');
    const params = new URLSearchParams(existing || '');
    params.set('connectionId', connId);
    return base + '?' + params.toString();
  };
  const readFileAsBase64 = (file: File): Promise<string> => new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve((reader.result as string).split(',')[1]);
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });

  const readFileAsText = (file: File): Promise<string> => new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ''));
    reader.onerror = reject;
    reader.readAsText(file);
  });


  const safeBase64Encode = (str: string): string => {
    try {
      return btoa(str);
    } catch {
      // fallback for unicode
      return btoa(encodeURIComponent(str).replace(/%([0-9A-F]{2})/g, (_, p1) => String.fromCharCode(parseInt(p1, 16))));
    }
  };

  /** Build certificate data for PUT /connection body. */
  const buildCertData = async (): Promise<string | undefined> => {
    if (certType === 'PEM') {
      if (certPasteMode && certPasteText.trim()) return safeBase64Encode(certPasteText.trim());
      if (!certPasteMode && certFile) return safeBase64Encode(await readFileAsText(certFile));
    } else if (certType === 'PKCS12_JKS' && certFile) {
      return await readFileAsBase64(certFile);
    }
    return undefined;
  };

  // ── Data fetching ─────────────────────────────────────────────

  /** Load all saved SR connections for the instance switcher. */
  const loadConnections = async () => {
    try {
      const res = await fetch(`/api/v1/clusters/${id}/data-services/schema-registry/connections`);
      if (!res.ok) return;
      const data: SavedConnection[] = await res.json().catch(() => []);
      setSavedConnections(data);
      if (data.length > 0) {
        const defaultConn = data.find(c => c.isDefault) ?? data[0];
        setSelectedConnectionId(prev => prev ?? defaultConn.id);
      }
    } catch { /* non-fatal */ }
  };

  /** Open connection modal, optionally pre-filling from an existing connection. */
  const openConnectionModal = (conn?: SavedConnection) => {
    if (conn) {
      setEditingConnectionId(conn.id);
      setFormConnectionName(conn.connectionName);
      setProtocol(conn.protocol || 'http');
      setCustomIp(conn.host || '');
      setCustomPort(conn.port ? String(conn.port) : '');
      setCertType(conn.certificateType || 'PEM');
      setFormIsDefault(conn.isDefault);
    } else {
      setEditingConnectionId(null);
      setFormConnectionName('');
      setProtocol('http');
      setCustomIp('');
      setCustomPort('');
      setCertType('PEM');
      setFormIsDefault(false);
    }
    setCertFile(null); setCertFileName(''); setCertPasteText(''); setCertPasteMode(false); setCertPassword('');
    setConnectError(null);
    setShowConnection(true);
  };

  const handleSaveConnection = async () => {
    setConnectSaving(true);
    setConnectError(null);
    try {
      const certData = await buildCertData();
      const body = {
        connectionName: formConnectionName.trim() || 'Default connection',
        protocol,
        host: customIp.trim(),
        port: parseInt(customPort.trim()) || 8081,
        certificateType: certType,
        certificateData: certData,
        truststorePassword: certPassword || undefined,
        isDefault: formIsDefault
      };

      // Use PUT /connections/{id} when editing existing (prevents rename creating duplicate rows).
      // Use PUT /connection (upsert-by-name) when creating new.
      const url = editingConnectionId
        ? `/api/v1/clusters/${id}/data-services/schema-registry/connections/${editingConnectionId}`
        : `/api/v1/clusters/${id}/data-services/schema-registry/connection`;

      const res = await fetch(url, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'Failed to save connection.');

      setCertPassword('');
      setShowConnection(false);
      await loadConnections();
      if (data.id) setSelectedConnectionId(data.id);
      await load();
    } catch (e: any) {
      setConnectError(e.message || 'Failed to save connection.');
    } finally {
      setConnectSaving(false);
    }
  };

  const handleDeleteConnection = async () => {
    if (!selectedConnectionId) return;
    if (!window.confirm("Are you sure you want to delete this connection?")) return;
    
    setLoading(true);
    try {
      const res = await fetch(`/api/v1/clusters/${id}/data-services/schema-registry/connections/${selectedConnectionId}`, {
        method: 'DELETE'
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.message || 'Failed to delete connection.');
      }
      setSelectedConnectionId(null);
      await loadConnections();
      await load();
    } catch (e: any) {
      setError(e.message || 'Failed to delete connection.');
      setLoading(false);
    }
  };

  const loadGlobalCompatibility = async () => {
    try {
      const res = await fetch(withConnId(`/api/v1/clusters/${id}/data-services/schema-registry/config`));
      const data = await res.json().catch(() => ({}));
      if (res.ok) setGlobalCompatibility(data.compatibilityLevel || data.compatibility || 'BACKWARD');
    } catch { setGlobalCompatibility('BACKWARD'); }
  };

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(withConnId(`/api/v1/clusters/${id}/data-services/schema-registry/summary`));
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'Failed to load Schema Registry.');
      setSummary(data);
      await loadGlobalCompatibility();
    } catch (e: any) {
      setError(e.message || 'Failed to load Schema Registry.');
    } finally {
      setLoading(false);
    }
  };

  // Initial load
  useEffect(() => { if (id) { loadConnections(); } }, [id]);

  // Reload when selected connection changes
  useEffect(() => { if (id && selectedConnectionId !== undefined) { load(); } }, [id, selectedConnectionId]);

  const openSubject = async (item: SchemaSubject) => {
    setSelected(item);
    setView('detail');
    setDetails(null);
    setLoadingDetails(true);
    setError(null);
    setExpandedVersions(new Set());
    try {
      const res = await fetch(withConnId(`/api/v1/clusters/${id}/data-services/schema-registry/subjects/${encodeURIComponent(item.subject)}/details`));
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'Failed to load subject details.');
      setDetails(data);
      setSubjectCompatibility(data.compatibility || 'BACKWARD');
    } catch (e: any) {
      setDetails({
        subject: item.subject,
        latest: { version: item.version, id: item.id, schemaType: item.schemaType, schema: item.schema },
        versions: [{ version: item.version, id: item.id, schemaType: item.schemaType, schema: item.schema }],
        compatibility: 'BACKWARD'
      });
      setSubjectCompatibility('BACKWARD');
      setError(e.message || 'Failed to load subject details.');
    } finally {
      setLoadingDetails(false);
    }
  };

  const openEdit = () => {
    const latest = details?.latest;
    if (!selected || !latest) return;
    setEditSchemaType(latest.schemaType || 'AVRO');
    setEditCompatibility(subjectCompatibility);
    setNewSchema(latest.schema || emptySchema);
    setView('edit');
  };

  const backToList = () => {
    setView('list');
    setSelected(null);
    setDetails(null);
    setExpandedVersions(new Set());
    setError(null);
  };

  const backToDetail = () => {
    setView('detail');
    setError(null);
  };

  const toggleVersion = (version: number) => {
    setExpandedVersions(prev => {
      const next = new Set(prev);
      next.has(version) ? next.delete(version) : next.add(version);
      return next;
    });
  };

  // ── Actions ───────────────────────────────────────────────────
  const submitCreateSchema = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!createSubject.trim() || !createSchema.trim()) return;
    setSaving(true);
    setError(null);
    try {
      const res = await fetch(withConnId(`/api/v1/clusters/${id}/data-services/schema-registry/subjects/${encodeURIComponent(createSubject.trim())}/versions`), {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ schemaType: createSchemaType, schema: createSchema })
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'Failed to create schema.');
      setShowCreate(false);
      setCreateSubject('');
      setCreateSchema(emptySchema);
      setCreateSchemaType('AVRO');
      await load();
    } catch (e: any) {
      setError(e.message || 'Failed to create schema.');
    } finally {
      setSaving(false);
    }
  };

  const submitEditSchema = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selected || !newSchema.trim()) return;
    setSaving(true);
    setError(null);
    try {
      const res = await fetch(withConnId(`/api/v1/clusters/${id}/data-services/schema-registry/subjects/${encodeURIComponent(selected.subject)}/versions`), {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ schemaType: editSchemaType, schema: newSchema })
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'Failed to update schema.');
      // Also save compatibility if changed
      if (editCompatibility !== subjectCompatibility) {
        await fetch(withConnId(`/api/v1/clusters/${id}/data-services/schema-registry/subjects/${encodeURIComponent(selected.subject)}/config`), {
          method: 'PUT', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ compatibility: editCompatibility })
        });
        setSubjectCompatibility(editCompatibility);
      }
      await load();
      // Reload detail
      const refreshed = summary?.subjects.find(s => s.subject === selected.subject) || selected;
      await openSubject(refreshed);
    } catch (e: any) {
      setError(e.message || 'Failed to update schema.');
    } finally {
      setSaving(false);
    }
  };

  const deleteSubject = async (name: string) => {
    if (!window.confirm(`Delete schema subject "${name}"?`)) return;
    setSaving(true);
    setError(null);
    try {
      const res = await fetch(withConnId(`/api/v1/clusters/${id}/data-services/schema-registry/subjects/${encodeURIComponent(name)}`), {
        method: 'DELETE'
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'Failed to delete subject.');
      backToList();
      await load();
    } catch (e: any) {
      setError(e.message || 'Failed to delete subject.');
    } finally {
      setSaving(false);
    }
  };

  const saveGlobalCompatibility = async () => {
    setSaving(true); setError(null);
    try {
      const res = await fetch(withConnId(`/api/v1/clusters/${id}/data-services/schema-registry/config`), {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ compatibility: globalCompatibility })
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'Failed to update global compatibility.');
    } catch (e: any) {
      setError(e.message || 'Failed to update global compatibility.');
    } finally { setSaving(false); }
  };

  const saveSubjectCompatibility = async () => {
    if (!selected) return;
    setSaving(true); setError(null);
    try {
      const res = await fetch(withConnId(`/api/v1/clusters/${id}/data-services/schema-registry/subjects/${encodeURIComponent(selected.subject)}/config`), {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ compatibility: subjectCompatibility })
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'Failed to update subject compatibility.');
      setDetails(prev => prev ? { ...prev, compatibility: subjectCompatibility } : prev);
    } catch (e: any) {
      setError(e.message || 'Failed to update subject compatibility.');
    } finally { setSaving(false); }
  };

  const connStatusColor = (s: string) =>
    s === 'ONLINE' ? '#80e8a2' : (s === 'OFFLINE' || s === 'ERROR') ? '#e88080' : '#a8c5c0';

  // ── Render ────────────────────────────────────────────────────
  return (
    <div className="data-services-page animate-fade-in">

      {/* ── LIST VIEW ─────────────────────────────────────────── */}
      {view === 'list' && (
        <>
          <div className="ds-header">
            <h2>Schema Registry</h2>
            <div className="ds-actions">
              {/* ── Instance switcher ── */}
              {savedConnections.length > 0 && (
                <div className="ds-compat-control">
                  <span>Instance</span>
                  <select
                    value={selectedConnectionId ?? ''}
                    onChange={e => setSelectedConnectionId(e.target.value || null)}
                  >
                    {savedConnections.map(c => (
                      <option key={c.id} value={c.id}>
                        {c.connectionName}{c.isDefault ? ' (default)' : ''}
                      </option>
                    ))}
                  </select>
                  {selectedConn && (
                    <span
                      className="ds-instance-status-dot"
                      style={{
                        display: 'inline-block',
                        width: 8,
                        height: 8,
                        borderRadius: '50%',
                        background: connStatusColor(selectedConn.status),
                        flexShrink: 0
                      }}
                      title={selectedConn.status}
                    />
                  )}
                </div>
              )}
              <div className="ds-compat-control">
                <span>Global compatibility</span>
                <select value={globalCompatibility} onChange={e => setGlobalCompatibility(e.target.value)}>
                  {compatibilityOptions.map(o => <option key={o} value={o}>{o}</option>)}
                </select>
                <button className="ds-icon-button" onClick={saveGlobalCompatibility} disabled={saving} title="Save global compatibility">
                  <Save size={16} />
                </button>
              </div>
              <button className="ds-button" onClick={load} disabled={loading}>
                <RefreshCw size={16} className={loading ? 'spin' : ''} /> Refresh
              </button>
              <button className="ds-button primary" onClick={() => setShowCreate(true)}>
                <Plus size={16} /> Create Schema
              </button>
              <button className="ds-icon-button" onClick={() => openConnectionModal(selectedConn ?? undefined)} disabled={!selectedConn} title="Edit connection">
                <MoreVertical size={18} />
              </button>
              <button className="ds-icon-button" onClick={handleDeleteConnection} disabled={!selectedConn} title="Delete connection" style={{ color: 'var(--color-danger, #e88080)' }}>
                <Trash2 size={18} />
              </button>
              <button className="ds-button" onClick={() => openConnectionModal()} title="Add new SR instance">
                <Settings size={16} /> Add Connection
              </button>
            </div>
          </div>

          {error && <div className="ds-alert">{error}</div>}

          <div className="ds-metrics">
            <div className="ds-metric-card"><span>Total Subjects</span><strong>{summary?.totalSubjects ?? 0}</strong></div>
            <div className="ds-metric-card"><span>Value Subjects</span><strong>{summary?.valueSubjects ?? 0}</strong></div>
            <div className="ds-metric-card"><span>Key Subjects</span><strong>{summary?.keySubjects ?? 0}</strong></div>
            <div className="ds-metric-card"><span>REST Endpoint</span><strong style={{ fontSize: 16 }}>{summary?.connection || '-'}</strong></div>
          </div>

          <div className="ds-panel">
            <table className="ds-table">
              <thead>
                <tr><th>Subject</th><th>Type</th><th>Latest Version</th><th>Schema ID</th><th>Schema Type</th><th>Actions</th></tr>
              </thead>
              <tbody>
                {loading && !summary ? (
                  <tr><td colSpan={6} className="ds-empty">Loading schemas...</td></tr>
                ) : summary && summary.subjects.length > 0 ? (
                  summary.subjects.map(item => (
                    <tr key={item.subject} className="ds-hoverable-row" onClick={() => openSubject(item)} style={{ cursor: 'pointer' }}>
                      <td><span className="ds-link-button">{item.subject}</span></td>
                      <td><span className="ds-status">{item.type}</span></td>
                      <td>{item.version || '-'}</td>
                      <td>{item.id || '-'}</td>
                      <td>{item.schemaType}</td>
                      <td>
                        <div className="ds-inline-actions" onClick={e => e.stopPropagation()}>
                          <button className="ds-button danger" onClick={() => deleteSubject(item.subject)} disabled={saving}>
                            <Trash2 size={15} /> Delete
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))
                ) : (
                  <tr><td colSpan={6} className="ds-empty">No schemas found in this registry.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </>
      )}

      {/* ── DETAIL VIEW ───────────────────────────────────────── */}
      {view === 'detail' && selected && (
        <>
          {/* Breadcrumb bar */}
          <div className="ds-page-bar">
            <div className="ds-breadcrumb-nav">
              <button className="ds-breadcrumb-link" onClick={backToList}>Schema Registry</button>
              <ChevronRight size={14} className="ds-breadcrumb-sep" />
              <span className="ds-breadcrumb-current">{selected.subject}</span>
            </div>
            <div className="ds-inline-actions">
              <button className="ds-button" disabled={(details?.versions.length || 0) < 2} title="Compare versions">
                <GitCompare size={16} /> Compare Versions
              </button>
              <button className="ds-button" onClick={openEdit} disabled={!details?.latest}>
                <Edit3 size={16} /> Edit Schema
              </button>
              <button className="ds-icon-button" onClick={() => deleteSubject(selected.subject)} disabled={saving} title="Delete subject">
                <Trash2 size={16} />
              </button>
            </div>
          </div>

          {error && <div className="ds-alert">{error}</div>}

          {/* Actual version */}
          <div className="ds-schema-shell">
            <div className="ds-schema-code-card">
              <h4>Actual version</h4>
              <pre className="ds-schema-code">
                {loadingDetails ? 'Loading schema...' : (details?.latest?.schema || selected.schema || '{}')}
              </pre>
            </div>
            <div className="ds-schema-meta-card">
              <div><span>Latest version</span><strong>{details?.latest?.version ?? selected.version ?? '-'}</strong></div>
              <div><span>ID</span><strong>{details?.latest?.id ?? selected.id ?? '-'}</strong></div>
              <div><span>Type</span><strong>{details?.latest?.schemaType || selected.schemaType || '-'}</strong></div>
              <div><span>Subject</span><strong>{selected.subject}</strong></div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr', gap: 8 }}>
                <span>Compatibility</span>
                <div className="ds-compat-inline">
                  <select value={subjectCompatibility} onChange={e => setSubjectCompatibility(e.target.value)}>
                    {compatibilityOptions.map(o => <option key={o} value={o}>{o}</option>)}
                  </select>
                  <button className="ds-icon-button" onClick={saveSubjectCompatibility} disabled={saving} title="Save">
                    <Save size={15} />
                  </button>
                </div>
              </div>
            </div>
          </div>

          {/* Old versions */}
          <h4 className="ds-section-title">Old versions</h4>
          <div className="ds-panel">
            <table className="ds-table">
              <thead>
                <tr>
                  <th style={{ width: 40 }}></th>
                  <th>Version</th>
                  <th>ID</th>
                  <th>Type</th>
                </tr>
              </thead>
              <tbody>
                {(details?.versions || []).map(version => (
                  <>
                    <tr key={version.version} className="ds-hoverable-row" style={{ cursor: 'pointer' }} onClick={() => toggleVersion(version.version)}>
                      <td>
                        <button className="ds-mini-button ds-expand-btn">
                          {expandedVersions.has(version.version) ? '−' : '+'}
                        </button>
                      </td>
                      <td>{version.version}</td>
                      <td>{version.id}</td>
                      <td>{version.schemaType}</td>
                    </tr>
                    {expandedVersions.has(version.version) && (
                      <tr key={`${version.version}-schema`} className="ds-version-expand-row">
                        <td colSpan={4} style={{ padding: 0 }}>
                          <pre className="ds-version-schema-code">{version.schema}</pre>
                        </td>
                      </tr>
                    )}
                  </>
                ))}
                {!loadingDetails && (!details?.versions || details.versions.length === 0) && (
                  <tr><td colSpan={4} className="ds-empty">No older versions found.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </>
      )}

      {/* ── EDIT VIEW ─────────────────────────────────────────── */}
      {view === 'edit' && selected && (
        <form onSubmit={submitEditSchema}>
          {/* Breadcrumb bar */}
          <div className="ds-page-bar">
            <div className="ds-breadcrumb-nav">
              <button type="button" className="ds-breadcrumb-link" onClick={backToList}>Schema Registry</button>
              <ChevronRight size={14} className="ds-breadcrumb-sep" />
              <button type="button" className="ds-breadcrumb-link" onClick={backToDetail}>{selected.subject}</button>
              <ChevronRight size={14} className="ds-breadcrumb-sep" />
              <span className="ds-breadcrumb-current">Edit</span>
            </div>
            <button type="submit" className="ds-button primary" disabled={saving}>
              {saving ? <RefreshCw size={16} className="spin" /> : <Save size={16} />} Submit
            </button>
          </div>

          {error && <div className="ds-alert">{error}</div>}

          {/* Type + Compatibility selectors */}
          <div className="ds-edit-controls">
            <div className="ds-field">
              <label>Type</label>
              <select value={editSchemaType} onChange={e => setEditSchemaType(e.target.value)}>
                <option value="AVRO">AVRO</option>
                <option value="JSON">JSON</option>
                <option value="PROTOBUF">PROTOBUF</option>
              </select>
            </div>
            <div className="ds-field">
              <label>Compatibility level</label>
              <select value={editCompatibility} onChange={e => setEditCompatibility(e.target.value)}>
                {compatibilityOptions.map(o => <option key={o} value={o}>{o}</option>)}
              </select>
            </div>
          </div>

          {/* Side-by-side editors */}
          <div className="ds-edit-shell">
            <div className="ds-edit-pane">
              <div className="ds-edit-pane-header">Latest schema</div>
              <pre className="ds-edit-code ds-edit-readonly">
                {details?.latest?.schema || selected.schema || '{}'}
              </pre>
            </div>
            <div className="ds-edit-pane">
              <div className="ds-edit-pane-header">New schema</div>
              <textarea
                className="ds-edit-code ds-edit-editable"
                value={newSchema}
                onChange={e => setNewSchema(e.target.value)}
                spellCheck={false}
                required
              />
            </div>
          </div>
        </form>
      )}

      {/* ── Connection modal ──────────────────────────────────── */}
      {showConnection && (
        <div className="ds-modal-backdrop" role="dialog" aria-modal="true">
          <div className="ds-modal ds-connection-modal">
            <div className="ds-modal-header">
              <div>
                <h3>{editingConnectionId ? 'Edit Connection' : 'Add Schema Registry Connection'}</h3>
                <span className="ds-muted-line">{formConnectionName || 'New connection'}</span>
              </div>
              <button type="button" className="ds-icon-button" onClick={() => setShowConnection(false)}><X size={16} /></button>
            </div>
            <div className="ds-form ds-compact-form">
              {connectError && <div className="ds-alert" style={{ marginBottom: 12 }}>{connectError}</div>}
              {selectedConn?.status && editingConnectionId && (
                <div style={{ padding: '8px 12px', background: 'rgba(255,255,255,0.05)', borderRadius: 4, marginBottom: 12, fontSize: 13 }}>
                  Status: <strong style={{ color: connStatusColor(selectedConn.status) }}>{selectedConn.status}</strong>
                  {selectedConn.certificateConfigured && <span style={{ marginLeft: 16 }}>✓ Cert Configured</span>}
                  {selectedConn.truststoreConfigured && <span style={{ marginLeft: 16 }}>✓ Truststore Password Configured</span>}
                </div>
              )}
              <div className="ds-field">
                <label>Connection Name</label>
                <input
                  value={formConnectionName}
                  onChange={e => setFormConnectionName(e.target.value)}
                  placeholder="e.g. Team A Registry"
                  required
                />
              </div>
              <div className="ds-form-grid three">
                <div className="ds-field"><label>Protocol</label><select value={protocol} onChange={e => setProtocol(e.target.value)}><option value="http">http://</option><option value="https">https://</option></select></div>
                <div className="ds-field"><label>Host / IP</label><input value={customIp} onChange={e => setCustomIp(e.target.value)} placeholder="192.168.3.222" required /></div>
                <div className="ds-field"><label>Port</label><input type="number" value={customPort} onChange={e => setCustomPort(e.target.value)} placeholder="8081" required /></div>
              </div>
              <div className="ds-form-grid two">
                <div className="ds-field">
                  <label>Certificate Type</label>
                  <select value={certType} onChange={e => { setCertType(e.target.value); setCertFile(null); setCertFileName(''); setCertPasteText(''); }}>
                    <option value="PEM">PEM (.pem / .crt)</option>
                    <option value="PKCS12_JKS">PKCS12 / JKS (.p12, .jks)</option>
                  </select>
                </div>
                {certType === 'PEM' ? (
                  <div className="ds-field">
                    <label>
                      <span>Certificate</span>
                      <button type="button" className="ds-mini-button" onClick={() => { setCertPasteMode(!certPasteMode); setCertFile(null); setCertFileName(''); setCertPasteText(''); }}>
                        {certPasteMode ? '📎 Upload file' : '📋 Paste text'}
                      </button>
                    </label>
                    {certPasteMode ? (
                      <textarea value={certPasteText} onChange={e => setCertPasteText(e.target.value)}
                        placeholder="-----BEGIN CERTIFICATE-----&#10;MIIDXTCCAkWgAwIBAgIJAMEn...&#10;-----END CERTIFICATE-----"
                        rows={5} style={{ fontFamily: 'monospace', fontSize: 12 }} />
                    ) : (
                      <>
                        <label className="ds-upload-control">
                          <Upload size={16} /> {certFileName || 'Upload certificate (.pem, .crt)'}
                          <input type="file" accept=".pem,.crt,.cer" onChange={e => { const f = e.target.files?.[0] || null; setCertFile(f); setCertFileName(f ? f.name : ''); }} />
                        </label>
                        {certFileName && <span className="ds-secret-note"><CheckCircle size={14} /> File selected: {certFileName}</span>}
                      </>
                    )}
                  </div>
                ) : (
                  <div className="ds-field">
                    <label>Truststore File (.p12 / .jks)</label>
                    <label className="ds-upload-control">
                      <Upload size={16} /> {certFileName || 'Upload truststore'}
                      <input type="file" accept=".p12,.pfx,.jks" onChange={e => { const f = e.target.files?.[0] || null; setCertFile(f); setCertFileName(f ? f.name : ''); }} />
                    </label>
                    {certFileName && <span className="ds-secret-note"><CheckCircle size={14} /> File selected: {certFileName}</span>}
                  </div>
                )}
              </div>
              {certType === 'PKCS12_JKS' && <div className="ds-field"><label>Truststore Password {selectedConn?.truststoreConfigured ? '(Leave blank to keep existing)' : ''}</label><input type="password" value={certPassword} onChange={e => setCertPassword(e.target.value)} placeholder="Password" /></div>}
              <div className="ds-default-toggle-row">
                <label className="ds-toggle-switch" htmlFor="sr-is-default">
                  <input
                    type="checkbox"
                    id="sr-is-default"
                    checked={formIsDefault}
                    onChange={e => setFormIsDefault(e.target.checked)}
                  />
                  <span className="ds-toggle-track">
                    <span className="ds-toggle-thumb" />
                  </span>
                </label>
                <label htmlFor="sr-is-default" className="ds-toggle-label">
                  Set as default connection for this cluster
                </label>
              </div>
            </div>
            <div className="ds-modal-footer">
              <button className="ds-button" onClick={() => setShowConnection(false)} disabled={connectSaving}>Cancel</button>
              <button className="ds-button primary" onClick={handleSaveConnection} disabled={connectSaving || !customIp.trim() || !customPort.trim()}>
                {connectSaving ? <RefreshCw size={16} className="spin" /> : <CheckCircle size={16} />} Save & Connect
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── Create Schema modal ───────────────────────────────── */}
      {showCreate && (
        <div className="ds-modal-backdrop" role="dialog" aria-modal="true">
          <form className="ds-modal" onSubmit={submitCreateSchema}>
            <div className="ds-modal-header">
              <h3>Create Schema</h3>
              <button type="button" className="ds-icon-button" onClick={() => setShowCreate(false)}><X size={16} /></button>
            </div>
            <div className="ds-form">
              <div className="ds-field"><label>Subject</label><input value={createSubject} onChange={e => setCreateSubject(e.target.value)} placeholder="orders-value" required /></div>
              <div className="ds-field"><label>Schema Type</label><select value={createSchemaType} onChange={e => setCreateSchemaType(e.target.value)}><option value="AVRO">AVRO</option><option value="JSON">JSON</option><option value="PROTOBUF">PROTOBUF</option></select></div>
              <div className="ds-field"><label>Schema</label><textarea value={createSchema} onChange={e => setCreateSchema(e.target.value)} required /></div>
            </div>
            <div className="ds-modal-footer">
              <button type="button" className="ds-button" onClick={() => setShowCreate(false)}>Cancel</button>
              <button type="submit" className="ds-button primary" disabled={saving}>{saving ? <RefreshCw size={16} className="spin" /> : <Plus size={16} />} Save</button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
}