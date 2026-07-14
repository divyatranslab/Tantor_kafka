import { useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { CheckCircle, MoreVertical, Pause, Play, Plug, Plus, RefreshCw, RotateCw, Settings, Trash2, Upload, X } from 'lucide-react';
import { usePermissions } from '../hooks/usePermissions';
import './DataServiceTabs.css';

interface ConnectorRow {
  name: string;
  class: string;
  state: string;
  tasks: number;
  runningTasks: number;
  config: Record<string, string>;
}

interface ConnectorPlugin {
  class: string;
  type: string;
  version: string;
}

interface ConnectSummary {
  connection: string;
  version: string;
  connectorCount: number;
  taskCount: number;
  runningTasks: number;
  runningConnectors: number;
  pausedConnectors: number;
  failedConnectors: number;
  connectors: ConnectorRow[];
  plugins: ConnectorPlugin[];
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

const connectorTemplate = `{
  "name": "file-source",
  "config": {
    "connector.class": "FileStreamSource",
    "tasks.max": "1",
    "file": "/tmp/input.txt",
    "topic": "file-source-topic"
  }
}`;

export function KafkaConnect() {
  const { id } = useParams<{ id: string }>();
  const { canManage } = usePermissions();
  const [summary, setSummary] = useState<ConnectSummary | null>(null);
  const [loading, setLoading] = useState(false);
  const [hasFetched, setHasFetched] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<'clusters' | 'connectors' | 'plugins'>('clusters');
  const [showCreate, setShowCreate] = useState(false);
  const [showConnection, setShowConnection] = useState(false);
  const [connectorJson, setConnectorJson] = useState(connectorTemplate);
  const [createError, setCreateError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  // ── Multi-instance state ──────────────────────────────────────
  const [savedConnections, setSavedConnections] = useState<SavedConnection[]>([]);
  const [selectedConnectionId, setSelectedConnectionId] = useState<string | null>(null);

  // ── Connection form state ─────────────────────────────────────
  const [formConnectionName, setFormConnectionName] = useState('');
  const [customIp, setCustomIp] = useState('');
  const [customPort, setCustomPort] = useState('');
  const [protocol, setProtocol] = useState('http');
  const [certType, setCertType] = useState('PEM');
  const [certFile, setCertFile] = useState<File | null>(null);
  const [certFileName, setCertFileName] = useState('');
  const [certPassword, setCertPassword] = useState('');
  const [formIsDefault, setFormIsDefault] = useState(false);
  /** ID of the connection being edited — set when editing an existing connection. */
  const [editingConnectionId, setEditingConnectionId] = useState<string | null>(null);

  const [connectSaving, setConnectSaving] = useState(false);
  const [connectError, setConnectError] = useState<string | null>(null);

  // ── Derived: currently selected connection ────────────────────
  const selectedConn = useMemo(
    () => savedConnections.find(c => c.id === selectedConnectionId) ?? null,
    [savedConnections, selectedConnectionId]
  );

  /**
   * Safely appends ?connectionId=... to any URL using URLSearchParams.
   * Works even when the base URL already contains query params.
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

  const buildCertData = async (): Promise<string | undefined> => {
    if (certType === 'PEM' && certFile) {
      const text = await new Promise<string>((res, rej) => {
        const r = new FileReader(); r.onload = () => res(String(r.result || '')); r.onerror = rej; r.readAsText(certFile);
      });
      return btoa(text.trim());
    } else if (certType === 'PKCS12_JKS' && certFile) {
      return await readFileAsBase64(certFile);
    }
    return undefined;
  };

  // ── Load all connections (for instance switcher) ──────────────
  const loadConnections = async () => {
    try {
      const res = await fetch(`/api/v1/clusters/${id}/data-services/kafka-connect/connections`);
      if (!res.ok) return;
      const data: SavedConnection[] = await res.json().catch(() => []);
      setSavedConnections(data);
      if (data.length > 0) {
        const defaultConn = data.find(c => c.isDefault) ?? data[0];
        setSelectedConnectionId(prev => prev ?? defaultConn.id);
      }
    } catch { /* non-fatal */ }
  };

  /**
   * Open connection modal.
   * If `conn` is provided, pre-fill for editing (uses PUT /connections/{id}).
   * If omitted, blank form for a new connection.
   */
  const openConnectionModal = (conn?: SavedConnection) => {
    if (!canManage) return;
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
    setCertFile(null);
    setCertFileName('');
    setCertPassword('');
    setConnectError(null);
    setShowConnection(true);
  };

  const handleSaveConnection = async () => {
    if (!canManage) return;
    setConnectSaving(true);
    setConnectError(null);
    try {
      const certData = await buildCertData();
      const body = {
        connectionName: formConnectionName.trim() || 'Default connection',
        protocol,
        host: customIp.trim(),
        port: parseInt(customPort.trim()) || 8083,
        certificateType: certType,
        certificateData: certData,
        truststorePassword: certPassword || undefined,
        isDefault: formIsDefault
      };

      // Use PUT /connections/{id} when editing existing, else PUT /connection (upsert-by-name)
      const url = editingConnectionId
        ? `/api/v1/clusters/${id}/data-services/kafka-connect/connections/${editingConnectionId}`
        : `/api/v1/clusters/${id}/data-services/kafka-connect/connection`;

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
    if (!canManage) return;
    if (!selectedConnectionId) return;
    if (!window.confirm("Are you sure you want to delete this connection?")) return;
    
    setLoading(true);
    try {
      const res = await fetch(`/api/v1/clusters/${id}/data-services/kafka-connect/connections/${selectedConnectionId}`, {
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

  const load = async () => {
    setHasFetched(true);
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(withConnId(`/api/v1/clusters/${id}/data-services/kafka-connect/summary`));
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'Failed to load Kafka Connect.');
      setSummary(data);
    } catch (e: any) {
      setError(e.message || 'Failed to load Kafka Connect.');
    } finally {
      setLoading(false);
    }
  };

  // Initial load
  useEffect(() => {
    if (id) { loadConnections(); }
  }, [id]);

  // Live Connect data is fetched only after the user explicitly requests it.
  useEffect(() => {
    setHasFetched(false);
    setSummary(null);
    setError(null);
  }, [id, selectedConnectionId]);

  const clusters = useMemo(() => [{
    name: selectedConn?.connectionName || 'default-connect',
    version: summary?.version || '-',
    connectors: summary?.connectorCount ?? 0,
    runningTasks: summary?.runningTasks ?? 0
  }], [summary, selectedConn]);

  const connectorPayloads = (): Record<string, unknown>[] => {
    const parsed = JSON.parse(connectorJson);
    const payloads = Array.isArray(parsed) ? parsed : (parsed && Array.isArray(parsed.connectors) ? parsed.connectors : [parsed]);
    if (!payloads.length) throw new Error('No connector definitions found.');
    return payloads;
  };

  const handleConnectorFiles = async (files: FileList | null) => {
    if (!canManage) return;
    if (!files?.length) return;
    setCreateError(null);
    try {
      const payloads: Record<string, unknown>[] = [];
      for (const file of Array.from(files)) {
        const parsed = JSON.parse(await file.text());
        payloads.push(...(Array.isArray(parsed) ? parsed : (parsed && Array.isArray(parsed.connectors) ? parsed.connectors : [parsed])));
      }
      if (!payloads.length) throw new Error('No connector definitions found.');
      setConnectorJson(JSON.stringify(payloads.length === 1 ? payloads[0] : payloads, null, 2));
    } catch (e: any) { setCreateError(e.message || 'Unable to read connector JSON.'); }
  };

  const createConnector = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canManage) return;
    setSaving(true); setError(null); setCreateError(null);
    try {
      const payloads = connectorPayloads();
      let deployed = 0;
      for (const body of payloads) {
        const res = await fetch(withConnId('/api/v1/clusters/' + id + '/data-services/kafka-connect/connectors'), {
          method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
        });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(data.message || ('Failed to deploy connector. ' + deployed + ' of ' + payloads.length + ' deployed.'));
        deployed++;
      }
      setShowCreate(false); setConnectorJson(connectorTemplate); await load();
      setSuccessMessage(deployed === 1 ? 'Connector deployed successfully.' : (deployed + ' connectors deployed successfully.'));
    } catch (e: any) { setCreateError(e.message || 'Failed to deploy connector.'); }
    finally { setSaving(false); }
  };
  const connectorAction = async (name: string, action: 'pause' | 'resume' | 'restart' | 'delete') => {
    if (!canManage) return;
    if (action === 'delete' && !window.confirm(`Delete connector ${name}?`)) return;
    setSaving(true);
    setError(null);
    try {
      const baseUrl = action === 'delete'
        ? `/api/v1/clusters/${id}/data-services/kafka-connect/connectors/${encodeURIComponent(name)}`
        : `/api/v1/clusters/${id}/data-services/kafka-connect/connectors/${encodeURIComponent(name)}/${action}`;
      const res = await fetch(withConnId(baseUrl), {
        method: action === 'delete' ? 'DELETE' : 'PUT'
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || `Failed to ${action} connector.`);
      await load();
    } catch (e: any) {
      setError(e.message || `Failed to ${action} connector.`);
    } finally {
      setSaving(false);
    }
  };

  const statusClass = (state: string) => {
    if (state === 'RUNNING') return 'ds-status';
    if (state === 'PAUSED') return 'ds-status warn';
    return 'ds-status error';
  };

  const connStatusColor = (s: string) =>
    s === 'ONLINE' ? '#80e8a2' : (s === 'OFFLINE' || s === 'ERROR') ? '#e88080' : '#a8c5c0';

  return (
    <div className="data-services-page animate-fade-in">
      <div className="ds-header">
        <h2>Kafka Connect</h2>
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

          <button className="ds-button" onClick={load} disabled={loading} title="Refresh">
            <RefreshCw size={16} className={loading ? 'spin' : ''} /> Refresh
          </button>
          {canManage && (
            <>
              <button className="ds-button primary" onClick={() => setShowCreate(true)}>
                <Plus size={16} /> Create Connector
              </button>
              {/* Edit selected connection */}
              <button
                className="ds-icon-button"
                onClick={() => openConnectionModal(selectedConn ?? undefined)}
                disabled={!selectedConn}
                title="Edit selected connection"
              >
                <MoreVertical size={18} />
              </button>
              {/* Delete selected connection */}
              <button
                className="ds-icon-button"
                onClick={handleDeleteConnection}
                disabled={!selectedConn}
                title="Delete connection"
                style={{ color: 'var(--color-danger, #e88080)' }}
              >
                <Trash2 size={18} />
              </button>
              {/* Add new connection */}
              <button className="ds-button" onClick={() => openConnectionModal()} title="Add new KC instance">
                <Settings size={16} /> Add Connection
              </button>
            </>
          )}
        </div>
      </div>

      {error && <div className="ds-alert">{error}</div>}

      {!hasFetched ? (
        <div className="ds-panel ds-fetch-prompt">
          <p>Kafka Connect data is not loaded automatically.</p>
          <button className="ds-button primary" onClick={load} disabled={loading}>
            <RefreshCw size={16} /> Fetch Kafka Connect for this cluster
          </button>
        </div>
      ) : <>
      <div className="ds-metrics">
        <div className="ds-metric-card total"><span>Total Connectors</span><strong>{summary?.connectorCount ?? 0}</strong></div>
        <div className="ds-metric-card running"><span>Running Connectors</span><strong>{summary?.runningConnectors ?? 0}</strong></div>
        <div className="ds-metric-card paused"><span>Paused Connectors</span><strong>{summary?.pausedConnectors ?? 0}</strong></div>
        <div className="ds-metric-card failed"><span>Failed Connectors</span><strong>{summary?.failedConnectors ?? 0}</strong></div>
      </div>

      <div className="ds-tabs">
        <button className={`ds-tab ${activeTab === 'clusters' ? 'active' : ''}`} onClick={() => setActiveTab('clusters')}>
          <Plug size={16} /> Clusters
        </button>
        <button className={`ds-tab ${activeTab === 'connectors' ? 'active' : ''}`} onClick={() => setActiveTab('connectors')}>
          <Plug size={16} /> Connectors
        </button>
        <button className={`ds-tab ${activeTab === 'plugins' ? 'active' : ''}`} onClick={() => setActiveTab('plugins')}>
          <Plug size={16} /> Plugins
        </button>
      </div>

      <div className="ds-panel">
        {activeTab === 'clusters' && (
          <table className="ds-table">
            <thead>
              <tr><th>Name</th><th>Version</th><th>Connectors</th><th>Running Tasks</th><th>REST Endpoint</th></tr>
            </thead>
            <tbody>
              {clusters.map(cluster => (
                <tr key={cluster.name}>
                  <td>{cluster.name}</td>
                  <td>{cluster.version}</td>
                  <td>{cluster.connectors}</td>
                  <td>{cluster.runningTasks}</td>
                  <td>{summary?.connection || '-'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {activeTab === 'connectors' && (
          <table className="ds-table">
            <thead>
              <tr><th>Name</th><th>Class</th><th>Status</th><th>Tasks</th>{canManage && <th>Actions</th>}</tr>
            </thead>
            <tbody>
              {loading && !summary ? (
                <tr><td colSpan={canManage ? 5 : 4} className="ds-empty">Loading connectors...</td></tr>
              ) : summary && summary.connectors.length > 0 ? (
                summary.connectors.map(connector => (
                  <tr key={connector.name}>
                    <td>{connector.name}</td>
                    <td>{connector.class || '-'}</td>
                    <td><span className={statusClass(connector.state)}>{connector.state}</span></td>
                    <td>{connector.runningTasks} / {connector.tasks}</td>
                    {canManage && (
                      <td>
                        <div className="ds-inline-actions">
                          <button className="ds-button" onClick={() => connectorAction(connector.name, 'pause')} disabled={saving} title="Pause">
                            <Pause size={15} />
                          </button>
                          <button className="ds-button" onClick={() => connectorAction(connector.name, 'resume')} disabled={saving} title="Resume">
                            <Play size={15} />
                          </button>
                          <button className="ds-button" onClick={() => connectorAction(connector.name, 'restart')} disabled={saving} title="Restart">
                            <RotateCw size={15} />
                          </button>
                          <button className="ds-button danger" onClick={() => connectorAction(connector.name, 'delete')} disabled={saving} title="Delete">
                            <Trash2 size={15} />
                          </button>
                        </div>
                      </td>
                    )}
                  </tr>
                ))
              ) : (
                <tr><td colSpan={canManage ? 5 : 4} className="ds-empty">No connectors found.</td></tr>
              )}
            </tbody>
          </table>
        )}

        {activeTab === 'plugins' && (
          <table className="ds-table">
            <thead>
              <tr><th>Class</th><th>Type</th><th>Version</th></tr>
            </thead>
            <tbody>
              {summary && summary.plugins.length > 0 ? (
                summary.plugins.map(plugin => (
                  <tr key={`${plugin.class}-${plugin.type}`}>
                    <td>{plugin.class}</td>
                    <td>{plugin.type}</td>
                    <td>{plugin.version}</td>
                  </tr>
                ))
              ) : (
                <tr><td colSpan={3} className="ds-empty">No connector plugins found.</td></tr>
              )}
            </tbody>
          </table>
        )}
      </div>
      </>}

      {/* ── Connection modal ── */}
      {canManage && showConnection && (
        <div className="ds-modal-backdrop" role="dialog" aria-modal="true">
          <div className="ds-modal ds-connection-modal">
            <div className="ds-modal-header">
              <div>
                <h3>{editingConnectionId ? 'Edit Connection' : 'Add Kafka Connect Connection'}</h3>
                <span className="ds-muted-line">{formConnectionName || 'New connection'}</span>
              </div>
              <button type="button" className="ds-icon-button" onClick={() => setShowConnection(false)} title="Close">
                <X size={16} />
              </button>
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
                  placeholder="e.g. ETL Kafka Connect"
                  required
                />
              </div>
              <div className="ds-form-grid three">
                <div className="ds-field">
                  <label>Protocol</label>
                  <select value={protocol} onChange={e => setProtocol(e.target.value)}>
                    <option value="http">http://</option>
                    <option value="https">https://</option>
                  </select>
                </div>
                <div className="ds-field">
                  <label>Host / IP</label>
                  <input value={customIp} onChange={e => setCustomIp(e.target.value)} placeholder="192.168.3.161" required />
                </div>
                <div className="ds-field">
                  <label>Port</label>
                  <input type="number" value={customPort} onChange={e => setCustomPort(e.target.value)} placeholder="8083" required />
                </div>
              </div>
              <div className="ds-form-grid two">
                <div className="ds-field">
                  <label>Certificate Type</label>
                  <select value={certType} onChange={e => { setCertType(e.target.value); setCertFile(null); setCertFileName(''); }}>
                    <option value="PEM">PEM</option>
                    <option value="PKCS12_JKS">PKCS12 / JKS</option>
                  </select>
                </div>
                <div className="ds-field">
                  <label>Certificate / Truststore</label>
                  <label className="ds-upload-control">
                    <Upload size={16} /> {certFileName || 'Upload file'}
                    <input
                      type="file"
                      accept={certType === 'PEM' ? '.pem,.crt,.cer' : '.p12,.pfx,.jks'}
                      onChange={e => { const f = e.target.files?.[0] || null; setCertFile(f); setCertFileName(f ? f.name : ''); }}
                    />
                  </label>
                  {certFileName && <span className="ds-secret-note"><CheckCircle size={14} /> {certFileName}</span>}
                </div>
              </div>
              {certType === 'PKCS12_JKS' && (
                <div className="ds-field">
                  <label>Truststore Password {selectedConn?.truststoreConfigured && editingConnectionId ? '(Leave blank to keep existing)' : ''}</label>
                  <input type="password" value={certPassword} onChange={e => setCertPassword(e.target.value)} placeholder="Password" />
                </div>
              )}
              <div className="ds-default-toggle-row">
                <label className="ds-toggle-switch" htmlFor="kc-is-default">
                  <input
                    type="checkbox"
                    id="kc-is-default"
                    checked={formIsDefault}
                    onChange={e => setFormIsDefault(e.target.checked)}
                  />
                  <span className="ds-toggle-track">
                    <span className="ds-toggle-thumb" />
                  </span>
                </label>
                <label htmlFor="kc-is-default" className="ds-toggle-label">
                  Set as default connection for this cluster
                </label>
              </div>
            </div>
            <div className="ds-modal-footer">
              <button className="ds-button" onClick={() => setShowConnection(false)} disabled={connectSaving}>Cancel</button>
              <button
                className="ds-button primary"
                onClick={handleSaveConnection}
                disabled={connectSaving || !customIp.trim() || !customPort.trim()}
              >
                {connectSaving ? <RefreshCw size={16} className="spin" /> : <CheckCircle size={16} />} Save & Connect
              </button>
            </div>
          </div>
        </div>
      )}

      {canManage && showCreate && (
        <div className="ds-modal-backdrop" role="dialog" aria-modal="true">
          <form className="ds-modal" onSubmit={createConnector}>
            <div className="ds-modal-header">
              <h3>Create Connector</h3>
              <button type="button" className="ds-icon-button" onClick={() => setShowCreate(false)} title="Close"><X size={16} /></button>
            </div>
            <div className="ds-form">
              {createError && <div className="ds-alert">{createError}</div>}
              <div className="ds-upload-row">
                <label className="ds-button" htmlFor="connector-json-upload"><Upload size={16} /> Upload JSON files</label>
                <input id="connector-json-upload" type="file" accept="application/json,.json" multiple hidden onChange={e => { void handleConnectorFiles(e.target.files); e.target.value = ''; }} />
                <span>Choose multiple files, or paste a JSON array for bulk deployment.</span>
              </div>
              <div className="ds-field">
                <label>Connector JSON</label>
                <textarea value={connectorJson} onChange={e => setConnectorJson(e.target.value)} required />
              </div>
            </div>
            <div className="ds-modal-footer">
              <button type="button" className="ds-button" onClick={() => setShowCreate(false)}>Cancel</button>
              <button type="submit" className="ds-button primary" disabled={saving}>
                {saving ? <RefreshCw size={16} className="spin" /> : <Plus size={16} />} Deploy
              </button>
            </div>
          </form>
        </div>
      )}      {successMessage && (
        <div className="ds-modal-backdrop" role="dialog" aria-modal="true">
          <div className="ds-modal ds-success-modal">
            <CheckCircle size={48} /><h3>Deployment successful</h3><p>{successMessage}</p>
            <button className="ds-button primary" onClick={() => setSuccessMessage(null)}>Done</button>
          </div>
        </div>
      )}

    </div>
  );
}
