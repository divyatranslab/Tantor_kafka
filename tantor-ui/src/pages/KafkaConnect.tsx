import { useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Pause, Play, Plug, Plus, RefreshCw, RotateCw, Trash2, X } from 'lucide-react';
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
  connectors: ConnectorRow[];
  plugins: ConnectorPlugin[];
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
  const [summary, setSummary] = useState<ConnectSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<'clusters' | 'connectors' | 'plugins'>('clusters');
  const [showCreate, setShowCreate] = useState(false);
  const [connectorJson, setConnectorJson] = useState(connectorTemplate);
  const [customIp, setCustomIp] = useState('');
  const [customPort, setCustomPort] = useState('');
  const [protocol, setProtocol] = useState('http');
  const [certificate, setCertificate] = useState('');
  const [certType, setCertType] = useState('PEM');
  const [certFile, setCertFile] = useState<File | null>(null);
  const [certPassword, setCertPassword] = useState('');
  const [showAdvanced, setShowAdvanced] = useState(false);

  const readFileAsBase64 = (file: File): Promise<string> => {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => {
        const base64 = (reader.result as string).split(',')[1];
        resolve(base64);
      };
      reader.onerror = reject;
      reader.readAsDataURL(file);
    });
  };

  const buildHeaders = async (baseHeaders: Record<string, string> = {}) => {
    const headers = { ...baseHeaders };
    if (certType === 'PEM' && certificate.trim()) {
      headers['X-Custom-Certificate'] = btoa(certificate.trim());
      headers['X-Custom-Certificate-Type'] = 'PEM';
    } else if (certType === 'PKCS12' && certFile) {
      const base64 = await readFileAsBase64(certFile);
      headers['X-Custom-Certificate'] = base64;
      headers['X-Custom-Certificate-Type'] = 'PKCS12';
      if (certPassword) {
        headers['X-Custom-Certificate-Password'] = certPassword;
      }
    }
    return headers;
  };

  const getQueryParams = () => {
    const params = new URLSearchParams();
    if (protocol) params.append('protocol', protocol);
    if (customIp.trim()) params.append('ip', customIp.trim());
    if (customPort.trim()) params.append('port', customPort.trim());
    const qs = params.toString();
    return qs ? `?${qs}` : '';
  };

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const hdrs = await buildHeaders();
      const res = await fetch(`/api/v1/clusters/${id}/data-services/kafka-connect/summary${getQueryParams()}`, {
        headers: hdrs
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'Failed to load Kafka Connect.');
      setSummary(data);
    } catch (e: any) {
      setError(e.message || 'Failed to load Kafka Connect.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (id) {
      load();
    }
  }, [id]);

  const clusters = useMemo(() => {
    return [{
      name: 'default-connect',
      version: summary?.version || '-',
      connectors: summary?.connectorCount ?? 0,
      runningTasks: summary?.runningTasks ?? 0
    }];
  }, [summary]);

  const createConnector = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const body = JSON.parse(connectorJson);
      const hdrs = await buildHeaders({ 'Content-Type': 'application/json' });
      const res = await fetch(`/api/v1/clusters/${id}/data-services/kafka-connect/connectors${getQueryParams()}`, {
        method: 'POST',
        headers: hdrs,
        body: JSON.stringify(body)
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'Failed to create connector.');
      setShowCreate(false);
      setConnectorJson(connectorTemplate);
      await load();
    } catch (e: any) {
      setError(e.message || 'Failed to create connector.');
    } finally {
      setSaving(false);
    }
  };

  const connectorAction = async (name: string, action: 'pause' | 'resume' | 'restart' | 'delete') => {
    if (action === 'delete' && !window.confirm(`Delete connector ${name}?`)) return;
    setSaving(true);
    setError(null);
    try {
      const url = action === 'delete'
        ? `/api/v1/clusters/${id}/data-services/kafka-connect/connectors/${encodeURIComponent(name)}${getQueryParams()}`
        : `/api/v1/clusters/${id}/data-services/kafka-connect/connectors/${encodeURIComponent(name)}/${action}${getQueryParams()}`;
      const hdrs = await buildHeaders();
      const res = await fetch(url, { 
        method: action === 'delete' ? 'DELETE' : 'PUT',
        headers: hdrs
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

  return (
    <div className="data-services-page animate-fade-in">
      <div className="ds-header">
        <h2>Kafka Connect</h2>
        <div className="ds-actions">
          <select value={protocol} onChange={e => setProtocol(e.target.value)} style={{ padding: '6px', borderRadius: '4px', border: '1px solid #444', background: '#1e1e1e', color: '#fff', width: '80px' }}>
            <option value="http">http://</option>
            <option value="https">https://</option>
          </select>
          <input type="text" placeholder="Custom IP" value={customIp} onChange={e => setCustomIp(e.target.value)} style={{ padding: '6px', borderRadius: '4px', border: '1px solid #444', background: '#1e1e1e', color: '#fff', width: '120px' }} />
          <input type="number" placeholder="Port" value={customPort} onChange={e => setCustomPort(e.target.value)} style={{ padding: '6px', borderRadius: '4px', border: '1px solid #444', background: '#1e1e1e', color: '#fff', width: '80px' }} />
          <button className="ds-button" onClick={() => setShowAdvanced(!showAdvanced)} title="TLS/Security Options">
            Certificate
          </button>
          <button className="ds-button" onClick={load} disabled={loading} title="Refresh">
            <RefreshCw size={16} className={loading ? 'spin' : ''} /> Refresh
          </button>
          <button className="ds-button primary" onClick={() => setShowCreate(true)}>
            <Plus size={16} /> Create Connector
          </button>
        </div>
      </div>

      {showAdvanced && (
        <div className="ds-form" style={{ padding: '0 0 10px 0' }}>
          <div className="ds-field">
            <label>Certificate Type</label>
            <select value={certType} onChange={e => setCertType(e.target.value)}>
              <option value="PEM">PEM (Text)</option>
              <option value="PKCS12">PKCS12 / JKS (.p12, .jks)</option>
            </select>
          </div>
          {certType === 'PEM' ? (
            <div className="ds-field">
              <label>Custom CA Certificate (PEM Format)</label>
              <textarea 
                value={certificate} 
                onChange={e => setCertificate(e.target.value)} 
                placeholder="-----BEGIN CERTIFICATE-----&#10;...&#10;-----END CERTIFICATE-----"
                style={{ minHeight: '120px' }}
              />
            </div>
          ) : (
            <>
              <div className="ds-field">
                <label>Truststore File (.p12)</label>
                <input 
                  type="file" 
                  accept=".p12,.pfx,.jks"
                  onChange={e => setCertFile(e.target.files ? e.target.files[0] : null)} 
                />
              </div>
              <div className="ds-field">
                <label>Truststore Password</label>
                <input 
                  type="password" 
                  value={certPassword} 
                  onChange={e => setCertPassword(e.target.value)} 
                  placeholder="Password"
                />
              </div>
            </>
          )}
        </div>
      )}

      {error && <div className="ds-alert">{error}</div>}

      <div className="ds-metrics">
        <div className="ds-metric-card"><span>Clusters</span><strong>1</strong></div>
        <div className="ds-metric-card"><span>Connectors</span><strong>{summary?.connectorCount ?? 0}</strong></div>
        <div className="ds-metric-card"><span>Tasks</span><strong>{summary?.taskCount ?? 0}</strong></div>
        <div className="ds-metric-card"><span>Running</span><strong>{summary?.runningTasks ?? 0}</strong></div>
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
            <thead><tr><th>Name</th><th>Version</th><th>Connectors</th><th>Running Tasks</th><th>REST Endpoint</th></tr></thead>
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
            <thead><tr><th>Name</th><th>Class</th><th>Status</th><th>Tasks</th><th>Actions</th></tr></thead>
            <tbody>
              {loading && !summary ? (
                <tr><td colSpan={5} className="ds-empty">Loading connectors...</td></tr>
              ) : summary && summary.connectors.length > 0 ? (
                summary.connectors.map(connector => (
                  <tr key={connector.name}>
                    <td>{connector.name}</td>
                    <td>{connector.class || '-'}</td>
                    <td><span className={statusClass(connector.state)}>{connector.state}</span></td>
                    <td>{connector.runningTasks} / {connector.tasks}</td>
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
                  </tr>
                ))
              ) : (
                <tr><td colSpan={5} className="ds-empty">No connectors found.</td></tr>
              )}
            </tbody>
          </table>
        )}

        {activeTab === 'plugins' && (
          <table className="ds-table">
            <thead><tr><th>Class</th><th>Type</th><th>Version</th></tr></thead>
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

      {showCreate && (
        <div className="ds-modal-backdrop" role="dialog" aria-modal="true">
          <form className="ds-modal" onSubmit={createConnector}>
            <div className="ds-modal-header">
              <h3>Create Connector</h3>
              <button type="button" className="ds-button" onClick={() => setShowCreate(false)} title="Close">
                <X size={16} />
              </button>
            </div>
            <div className="ds-form">
              <div className="ds-field">
                <label>Connector JSON</label>
                <textarea value={connectorJson} onChange={e => setConnectorJson(e.target.value)} required />
              </div>
            </div>
            <div className="ds-modal-footer">
              <button type="button" className="ds-button" onClick={() => setShowCreate(false)}>Cancel</button>
              <button type="submit" className="ds-button primary" disabled={saving}>
                {saving ? <RefreshCw size={16} className="spin" /> : <Plus size={16} />} Create
              </button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
}
