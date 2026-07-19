import { useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { CheckCircle, MoreVertical, Pause, Play, Plug, Plus, RefreshCw, RotateCw, Settings, Trash2, Upload, X, FileDown, ChevronDown, Database } from 'lucide-react';
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
    <div className="data-services-page animate-fade-in" style={{ width: '100%' }}>
      <div className="ds-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '24px', width: '100%' }}>
        <h2 className="cluster-section-heading">Kafka Connect</h2>
        <div className="ds-actions" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          {/* ── Instance switcher ── */}
          {savedConnections.length > 0 && (
            <div className="ds-compat-control" style={{ marginRight: '8px' }}>
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

          {canManage && (
            <button 
              type="button"
              onClick={() => setShowCreate(true)}
              style={{
                width: '38px',
                height: '38px',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                background: '#FFFFFF',
                border: '1px solid #CCCCCC',
                borderRadius: '8px',
                cursor: 'pointer'
              }}
              title="Upload JSON configurations"
            >
              <FileDown size={16} style={{ color: '#818181' }} />
            </button>
          )}

          {canManage && (
            <button 
              className="ds-button" 
              onClick={() => openConnectionModal()} 
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: '8px',
                height: '38px',
                padding: '0 16px',
                background: '#FFFFFF',
                border: '1px solid #CCCCCC',
                borderRadius: '8px',
                color: '#332849',
                fontFamily: 'Satoshi, sans-serif',
                fontWeight: 500,
                fontSize: '14px',
                cursor: 'pointer'
              }}
            >
              <Settings size={16} style={{ color: '#332849' }} /> Add Connection
            </button>
          )}

          {canManage && (
            <button 
              className="ds-button primary" 
              onClick={() => setShowCreate(true)}
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: '8px',
                height: '38px',
                padding: '0 16px',
                background: '#332849',
                border: 'none',
                borderRadius: '8px',
                color: '#FFFFFF',
                fontFamily: 'Satoshi, sans-serif',
                fontWeight: 500,
                fontSize: '14px',
                cursor: 'pointer'
              }}
            >
              <Plus size={16} color="#FFFFFF" /> Create Connector
            </button>
          )}

          {canManage && (
            <button
              className="ds-icon-button"
              onClick={handleDeleteConnection}
              disabled={!selectedConn}
              style={{
                width: '38px',
                height: '38px',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                background: '#FFFFFF',
                border: '1px solid #CCCCCC',
                borderRadius: '8px',
                cursor: 'pointer',
                opacity: selectedConn ? 1 : 0.5
              }}
              title="Delete connection"
            >
              <Trash2 size={16} style={{ color: '#818181' }} />
            </button>
          )}

          <button 
            className="ds-button" 
            onClick={load} 
            disabled={loading}
            style={{
              width: '38px',
              height: '38px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              background: '#FFFFFF',
              border: '1px solid #CCCCCC',
              borderRadius: '8px',
              cursor: 'pointer'
            }}
            title="Refresh"
          >
            <RefreshCw size={16} className={loading ? 'spin' : ''} style={{ color: '#818181' }} />
          </button>

          {canManage && (
            <button
              className="ds-icon-button"
              onClick={() => openConnectionModal(selectedConn ?? undefined)}
              disabled={!selectedConn}
              style={{
                width: '38px',
                height: '38px',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                background: '#FFFFFF',
                border: 'none',
                borderRadius: '8px',
                cursor: 'pointer',
                opacity: selectedConn ? 1 : 0.5
              }}
              title="Edit selected connection"
            >
              <MoreVertical size={18} style={{ color: '#818181' }} />
            </button>
          )}
        </div>
      </div>

      {error && <div className="ds-alert">{error}</div>}

      {!hasFetched ? (
        <div style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          minHeight: '350px',
          textAlign: 'center',
          background: 'transparent',
          border: 'none'
        }}>
          <div style={{ width: '185px', height: '185px', position: 'relative', marginBottom: '16px' }}>
            {/* Document Lines Container (Holds the 3 cards) */}
            <div style={{
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'flex-start',
              padding: '0px',
              gap: '3.91px',
              position: 'absolute',
              width: '109.44px',
              height: '78.17px',
              left: '37.48px',
              top: '59.81px'
            }}>
              {/* CARD 1 */}
              <div style={{
                boxSizing: 'border-box',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'flex-start',
                padding: '7.81px 11.72px',
                gap: '10.42px',
                width: '109.44px',
                height: '23.45px',
                background: '#FFFFFF',
                border: '1.30282px solid #D4DCE7',
                boxShadow: '0px 5.21127px 7.68662px rgba(0, 22, 93, 0.08)',
                borderRadius: '2.60563px'
              }}>
                {/* Document Line Group */}
                <div style={{ display: 'flex', flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', width: '85.99px', height: '7.82px' }}>
                  {/* Left Column */}
                  <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: '3.91px', width: '31.27px', height: '7.82px' }}>
                    <div style={{ width: '19.54px', height: '2.61px', background: '#B3A4D1', borderRadius: '0.651408px' }} />
                    <div style={{ display: 'flex', flexDirection: 'row', gap: '3.99px', width: '31.1px', height: '1.3px' }}>
                      <div style={{ width: '13.56px', height: '1.3px', background: '#CCCCCC', borderRadius: '0.260563px' }} />
                      <div style={{ width: '13.56px', height: '1.3px', background: '#CCCCCC', borderRadius: '0.260563px' }} />
                    </div>
                  </div>
                  {/* Right Column */}
                  <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: '3.91px', width: '31.27px', height: '7.82px' }}>
                    <div style={{ width: '19.54px', height: '2.61px', background: '#B3A4D1', borderRadius: '0.651408px' }} />
                    <div style={{ width: '31.27px', height: '1.3px', background: '#CCCCCC', borderRadius: '0.260563px' }} />
                  </div>
                </div>
              </div>

              {/* CARD 2 */}
              <div style={{
                boxSizing: 'border-box',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'flex-start',
                padding: '7.81px 11.72px',
                gap: '10.42px',
                width: '109.44px',
                height: '23.45px',
                background: '#FFFFFF',
                border: '1.30282px solid #D4DCE7',
                boxShadow: '0px 5.21127px 7.68662px rgba(0, 22, 93, 0.08)',
                borderRadius: '2.60563px'
              }}>
                {/* Document Line Group */}
                <div style={{ display: 'flex', flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', width: '85.99px', height: '7.82px' }}>
                  {/* Left Column */}
                  <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: '3.91px', width: '31.27px', height: '7.82px' }}>
                    <div style={{ width: '19.54px', height: '2.61px', background: '#B3A4D1', borderRadius: '0.651408px' }} />
                    <div style={{ width: '31.27px', height: '1.3px', background: '#CCCCCC', borderRadius: '0.260563px' }} />
                  </div>
                  {/* Right Column */}
                  <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: '3.91px', width: '31.27px', height: '7.82px' }}>
                    <div style={{ width: '19.54px', height: '2.61px', background: '#B3A4D1', borderRadius: '0.651408px' }} />
                    <div style={{ width: '31.27px', height: '1.3px', background: '#CCCCCC', borderRadius: '0.260563px' }} />
                  </div>
                </div>
              </div>

              {/* CARD 3 */}
              <div style={{
                boxSizing: 'border-box',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'flex-start',
                padding: '7.81px 11.72px',
                gap: '10.42px',
                width: '109.44px',
                height: '23.45px',
                background: '#FFFFFF',
                border: '1.30282px solid #D4DCE7',
                boxShadow: '0px 5.21127px 7.68662px rgba(0, 22, 93, 0.08)',
                borderRadius: '2.60563px'
              }}>
                {/* Document Line Group */}
                <div style={{ display: 'flex', flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', width: '85.99px', height: '7.82px' }}>
                  {/* Left Column */}
                  <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: '3.91px', width: '31.27px', height: '7.82px' }}>
                    <div style={{ width: '19.54px', height: '2.61px', background: '#B3A4D1', borderRadius: '0.651408px' }} />
                    <div style={{ width: '31.27px', height: '1.3px', background: '#CCCCCC', borderRadius: '0.260563px' }} />
                  </div>
                  {/* Right Column */}
                  <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: '3.91px', width: '31.27px', height: '7.82px' }}>
                    <div style={{ width: '19.54px', height: '2.61px', background: '#B3A4D1', borderRadius: '0.651408px' }} />
                    <div style={{ display: 'flex', flexDirection: 'row', gap: '3.99px', width: '31.1px', height: '1.3px' }}>
                      <div style={{ width: '13.56px', height: '1.3px', background: '#CCCCCC', borderRadius: '0.260563px' }} />
                      <div style={{ width: '13.56px', height: '1.3px', background: '#CCCCCC', borderRadius: '0.260563px' }} />
                    </div>
                  </div>
                </div>
              </div>
            </div>

            {/* Document Lines Container (Bottom grey lines) */}
            <div style={{
              display: 'flex',
              flexDirection: 'row',
              alignItems: 'flex-start',
              padding: '0px',
              gap: '3.91px',
              position: 'absolute',
              width: '80.77px',
              height: '1.3px',
              left: '51.81px',
              top: '147.1px'
            }}>
              <div style={{ width: '10.42px', height: '1.3px', background: '#CCCCCC', borderRadius: '0.260563px' }} />
              <div style={{ width: '31.27px', height: '1.3px', background: '#CCCCCC', borderRadius: '0.260563px' }} />
              <div style={{ width: '31.27px', height: '1.3px', background: '#CCCCCC', borderRadius: '0.260563px' }} />
            </div>
          </div>
          <h3 style={{
            fontFamily: 'Satoshi, sans-serif',
            fontWeight: 500,
            fontSize: '16px',
            color: '#332849',
            margin: '0 0 4px 0'
          }}>
            Kafka Connect data is not loaded automatically.
          </h3>
          <span 
            onClick={load}
            style={{
              fontFamily: 'Satoshi, sans-serif',
              fontWeight: 400,
              fontSize: '14px',
              color: '#818181',
              cursor: 'pointer',
              textDecoration: 'none'
            }}
          >
            Fetch Kafka Connect for this cluster
          </span>
        </div>
      ) : <>
      <div className="ds-metrics" style={{
        display: 'flex',
        flexDirection: 'row',
        alignItems: 'center',
        padding: '24px',
        gap: '16px',
        background: 'linear-gradient(273.74deg, #FAE1E8 38.03%, #DF678B 80.94%, #3E1363 99.36%)',
        borderRadius: '8px',
        marginBottom: '24px',
        boxSizing: 'border-box'
      }}>
        {/* Total Connectors */}
        <div style={{
          boxSizing: 'border-box',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'flex-start',
          padding: '16px',
          gap: '16px',
          width: '258.5px',
          height: '111px',
          background: '#FFFFFF',
          borderRadius: '8px',
          flex: '1 1 0px'
        }}>
          <div style={{ display: 'flex', flexDirection: 'row', alignItems: 'center', gap: '8px', width: '100%', height: '36px' }}>
            <div style={{ display: 'flex', flexDirection: 'row', alignItems: 'center', justifyContent: 'center', padding: '6px', gap: '8px', width: '36px', height: '36px', background: '#C5EAF0', borderRadius: '44px', boxSizing: 'border-box' }}>
              <Database size={24} style={{ color: '#16ABC2', flex: 'none' }} />
            </div>
            <span style={{ width: '123px', height: '22px', fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '16px', lineHeight: '22px', color: '#332849' }}>Total Connectors</span>
          </div>
          <div style={{ display: 'flex', flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', width: '100%', height: '27px' }}>
            <strong style={{ fontFamily: 'Satoshi, sans-serif', fontWeight: 700, fontSize: '20px', lineHeight: '27px', color: '#332849' }}>{summary?.connectorCount ?? 0}</strong>
          </div>
        </div>

        {/* Running Connectors */}
        <div style={{
          boxSizing: 'border-box',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'flex-start',
          padding: '16px',
          gap: '16px',
          width: '258.5px',
          height: '111px',
          background: '#FFFFFF',
          borderRadius: '8px',
          flex: '1 1 0px'
        }}>
          <div style={{ display: 'flex', flexDirection: 'row', alignItems: 'center', gap: '8px', width: '100%', height: '36px' }}>
            <div style={{ display: 'flex', flexDirection: 'row', alignItems: 'center', justifyContent: 'center', padding: '6px', gap: '8px', width: '36px', height: '36px', background: '#EBD4F7', borderRadius: '44px', boxSizing: 'border-box' }}>
              <Plug size={24} style={{ color: '#8E77BB', flex: 'none' }} />
            </div>
            <span style={{ width: '182.5px', height: '22px', fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '16px', lineHeight: '22px', color: '#332849' }}>Running Connectors</span>
          </div>
          <div style={{ display: 'flex', flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', width: '100%', height: '27px' }}>
            <strong style={{ fontFamily: 'Satoshi, sans-serif', fontWeight: 700, fontSize: '20px', lineHeight: '27px', color: '#332849' }}>{summary?.runningConnectors ?? 0}</strong>
          </div>
        </div>

        {/* Paused Connectors */}
        <div style={{
          boxSizing: 'border-box',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'flex-start',
          padding: '16px',
          gap: '16px',
          width: '258.5px',
          height: '111px',
          background: '#FFFFFF',
          borderRadius: '8px',
          flex: '1 1 0px'
        }}>
          <div style={{ display: 'flex', flexDirection: 'row', alignItems: 'center', gap: '8px', width: '100%', height: '36px' }}>
            <div style={{ display: 'flex', flexDirection: 'row', alignItems: 'center', justifyContent: 'center', padding: '6px', gap: '8px', width: '36px', height: '36px', background: '#DEF0D6', borderRadius: '44px', boxSizing: 'border-box' }}>
              <Pause size={24} style={{ color: '#E08E40', flex: 'none' }} />
            </div>
            <span style={{ width: '142px', height: '22px', fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '16px', lineHeight: '22px', color: '#332849' }}>Paused Connectors</span>
          </div>
          <div style={{ display: 'flex', flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', width: '100%', height: '27px' }}>
            <strong style={{ fontFamily: 'Satoshi, sans-serif', fontWeight: 700, fontSize: '20px', lineHeight: '27px', color: '#332849' }}>{summary?.pausedConnectors ?? 0}</strong>
          </div>
        </div>

        {/* Failed Connectors */}
        <div style={{
          boxSizing: 'border-box',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'flex-start',
          padding: '16px',
          gap: '16px',
          width: '258.5px',
          height: '111px',
          background: '#FFFFFF',
          borderRadius: '8px',
          flex: '1 1 0px'
        }}>
          <div style={{ display: 'flex', flexDirection: 'row', alignItems: 'center', gap: '8px', width: '100%', height: '36px' }}>
            <div style={{ display: 'flex', flexDirection: 'row', alignItems: 'center', justifyContent: 'center', padding: '6px', gap: '8px', width: '36px', height: '36px', background: '#DEF0D6', borderRadius: '44px', boxSizing: 'border-box' }}>
              <X size={24} style={{ color: '#EF4D5F', flex: 'none' }} />
            </div>
            <span style={{ width: '132px', height: '22px', fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '16px', lineHeight: '22px', color: '#332849' }}>Failed Connectors</span>
          </div>
          <div style={{ display: 'flex', flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', width: '100%', height: '27px' }}>
            <strong style={{ fontFamily: 'Satoshi, sans-serif', fontWeight: 700, fontSize: '20px', lineHeight: '27px', color: '#332849' }}>{summary?.failedConnectors ?? 0}</strong>
          </div>
        </div>
      </div>

      <div className="ds-tabs" style={{ display: 'flex', gap: '24px', borderBottom: '1px solid #CCCCCC', marginBottom: '20px' }}>
        <button 
          onClick={() => setActiveTab('clusters')}
          style={{
            background: 'none',
            border: 'none',
            borderBottom: activeTab === 'clusters' ? '2px solid #5B327F' : '2px solid transparent',
            color: activeTab === 'clusters' ? '#5B327F' : '#818181',
            fontFamily: 'Satoshi, sans-serif',
            fontWeight: activeTab === 'clusters' ? 500 : 400,
            fontSize: '14px',
            padding: '8px 12px 12px 12px',
            cursor: 'pointer',
            marginBottom: '-1px'
          }}
        >
          Clusters
        </button>
        <button 
          onClick={() => setActiveTab('connectors')}
          style={{
            background: 'none',
            border: 'none',
            borderBottom: activeTab === 'connectors' ? '2px solid #5B327F' : '2px solid transparent',
            color: activeTab === 'connectors' ? '#5B327F' : '#818181',
            fontFamily: 'Satoshi, sans-serif',
            fontWeight: activeTab === 'connectors' ? 500 : 400,
            fontSize: '14px',
            padding: '8px 12px 12px 12px',
            cursor: 'pointer',
            marginBottom: '-1px'
          }}
        >
          Connectors
        </button>
        <button 
          onClick={() => setActiveTab('plugins')}
          style={{
            background: 'none',
            border: 'none',
            borderBottom: activeTab === 'plugins' ? '2px solid #5B327F' : '2px solid transparent',
            color: activeTab === 'plugins' ? '#5B327F' : '#818181',
            fontFamily: 'Satoshi, sans-serif',
            fontWeight: activeTab === 'plugins' ? 500 : 400,
            fontSize: '14px',
            padding: '8px 12px 12px 12px',
            cursor: 'pointer',
            marginBottom: '-1px'
          }}
        >
          Plugins
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
          <div className="ds-modal ds-connection-modal" style={{ width: '680px', borderRadius: '12px', background: '#FFFFFF', padding: '24px', boxShadow: '0px 22px 60px rgba(0, 0, 0, 0.24)' }}>
            <div className="ds-modal-header" style={{ border: 'none', padding: '0 0 20px 0', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div>
                <h3 style={{ fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '18px', color: '#332849', margin: 0 }}>Add Kafka Connect Connection</h3>
                <span className="ds-muted-line" style={{ fontFamily: 'Satoshi, sans-serif', fontSize: '13px', color: '#818181', marginTop: '4px', display: 'block' }}>New connection</span>
              </div>
              <button type="button" className="ds-icon-button" onClick={() => setShowConnection(false)} title="Close" style={{ border: 'none', background: 'none', cursor: 'pointer', color: '#818181' }}>
                <X size={20} />
              </button>
            </div>
            
            <div className="ds-form ds-compact-form" style={{ display: 'flex', flexDirection: 'column', gap: '16px', background: '#F9F9F9', borderRadius: '8px', padding: '24px', marginBottom: '24px' }}>
              {connectError && <div className="ds-alert" style={{ marginBottom: 12 }}>{connectError}</div>}
              {selectedConn?.status && editingConnectionId && (
                <div style={{ padding: '8px 12px', background: 'rgba(255,255,255,0.05)', borderRadius: 4, marginBottom: 12, fontSize: 13 }}>
                  Status: <strong style={{ color: connStatusColor(selectedConn.status) }}>{selectedConn.status}</strong>
                  {selectedConn.certificateConfigured && <span style={{ marginLeft: 16 }}>✓ Cert Configured</span>}
                  {selectedConn.truststoreConfigured && <span style={{ marginLeft: 16 }}>✓ Truststore Password Configured</span>}
                </div>
              )}
              
              <div className="ds-field" style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                <label style={{ fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '13px', color: '#332849' }}>Connection Name</label>
                <input
                  value={formConnectionName}
                  onChange={e => setFormConnectionName(e.target.value)}
                  placeholder="e.g. ETL Kafka Connect"
                  required
                  style={{ width: '100%', height: '40px', background: '#FFFFFF', border: '1px solid #CCCCCC', borderRadius: '8px', padding: '0 12px', fontFamily: 'Satoshi, sans-serif', fontSize: '14px', outline: 'none' }}
                />
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '16px' }}>
                <div className="ds-field" style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                  <label style={{ fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '13px', color: '#332849' }}>Protocol</label>
                  <div style={{ position: 'relative' }}>
                    <select 
                      value={protocol} 
                      onChange={e => setProtocol(e.target.value)}
                      style={{ width: '100%', height: '40px', background: '#FFFFFF', border: '1px solid #CCCCCC', borderRadius: '8px', padding: '0 12px', fontFamily: 'Satoshi, sans-serif', fontSize: '14px', outline: 'none', appearance: 'none', cursor: 'pointer' }}
                    >
                      <option value="http">http://</option>
                      <option value="https">https://</option>
                    </select>
                    <span style={{ position: 'absolute', right: '12px', top: '50%', transform: 'translateY(-50%)', pointerEvents: 'none', display: 'flex', alignItems: 'center' }}>
                      <ChevronDown size={16} style={{ color: '#818181' }} />
                    </span>
                  </div>
                </div>
                <div className="ds-field" style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                  <label style={{ fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '13px', color: '#332849' }}>Host / IP</label>
                  <input 
                    value={customIp} 
                    onChange={e => setCustomIp(e.target.value)} 
                    placeholder="192.168.3.161" 
                    required 
                    style={{ width: '100%', height: '40px', background: '#FFFFFF', border: '1px solid #CCCCCC', borderRadius: '8px', padding: '0 12px', fontFamily: 'Satoshi, sans-serif', fontSize: '14px', outline: 'none' }}
                  />
                </div>
                <div className="ds-field" style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                  <label style={{ fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '13px', color: '#332849' }}>Port</label>
                  <input 
                    type="number" 
                    value={customPort} 
                    onChange={e => setCustomPort(e.target.value)} 
                    placeholder="8083" 
                    required 
                    style={{ width: '100%', height: '40px', background: '#FFFFFF', border: '1px solid #CCCCCC', borderRadius: '8px', padding: '0 12px', fontFamily: 'Satoshi, sans-serif', fontSize: '14px', outline: 'none' }}
                  />
                </div>
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
                <div className="ds-field" style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                  <label style={{ fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '13px', color: '#332849' }}>Certificate Type</label>
                  <div style={{ position: 'relative' }}>
                    <select 
                      value={certType} 
                      onChange={e => { setCertType(e.target.value); setCertFile(null); setCertFileName(''); }}
                      style={{ width: '100%', height: '40px', background: '#FFFFFF', border: '1px solid #CCCCCC', borderRadius: '8px', padding: '0 12px', fontFamily: 'Satoshi, sans-serif', fontSize: '14px', outline: 'none', appearance: 'none', cursor: 'pointer' }}
                    >
                      <option value="PEM">PEM</option>
                      <option value="PKCS12_JKS">PKCS12 / JKS</option>
                    </select>
                    <span style={{ position: 'absolute', right: '12px', top: '50%', transform: 'translateY(-50%)', pointerEvents: 'none', display: 'flex', alignItems: 'center' }}>
                      <ChevronDown size={16} style={{ color: '#818181' }} />
                    </span>
                  </div>
                </div>
                <div className="ds-field" style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                  <label style={{ fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '13px', color: '#332849' }}>Certificate / Truststore</label>
                  <label 
                    className="ds-upload-control"
                    style={{
                      boxSizing: 'border-box',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      gap: '8px',
                      width: '100%',
                      height: '40px',
                      background: '#FFFFFF',
                      border: '1px solid #7F56D9',
                      borderRadius: '8px',
                      cursor: 'pointer',
                      fontFamily: 'Satoshi, sans-serif',
                      fontSize: '14px',
                      color: '#7F56D9',
                      fontWeight: 500
                    }}
                  >
                    <FileDown size={16} style={{ color: '#7F56D9' }} /> {certFileName || 'Choose file'}
                    <input
                      type="file"
                      accept={certType === 'PEM' ? '.pem,.crt,.cer' : '.p12,.pfx,.jks'}
                      onChange={e => { const f = e.target.files?.[0] || null; setCertFile(f); setCertFileName(f ? f.name : ''); }}
                      style={{ display: 'none' }}
                    />
                  </label>
                  {certFileName && <span className="ds-secret-note" style={{ fontFamily: 'Satoshi, sans-serif', fontSize: '12px', color: '#36AD8F', display: 'flex', alignItems: 'center', gap: '4px', marginTop: '4px' }}><CheckCircle size={14} /> {certFileName}</span>}
                </div>
              </div>

              {certType === 'PKCS12_JKS' && (
                <div className="ds-field" style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                  <label style={{ fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '13px', color: '#332849' }}>Truststore Password {selectedConn?.truststoreConfigured && editingConnectionId ? '(Leave blank to keep existing)' : ''}</label>
                  <div style={{ position: 'relative' }}>
                    <input 
                      type="password" 
                      value={certPassword} 
                      onChange={e => setCertPassword(e.target.value)} 
                      placeholder="Password" 
                      style={{ width: '100%', height: '40px', background: '#FFFFFF', border: '1px solid #CCCCCC', borderRadius: '8px', padding: '0 12px', fontFamily: 'Satoshi, sans-serif', fontSize: '14px', outline: 'none' }}
                    />
                    <span style={{ position: 'absolute', right: '12px', top: '50%', transform: 'translateY(-50%)', pointerEvents: 'none', display: 'flex', alignItems: 'center' }}>
                      <ChevronDown size={16} style={{ color: '#818181' }} />
                    </span>
                  </div>
                </div>
              )}

              <div className="ds-default-toggle-row" style={{ display: 'flex', alignItems: 'center', gap: '12px', marginTop: '8px' }}>
                <label className="ds-toggle-switch" htmlFor="kc-is-default" style={{ position: 'relative', display: 'inline-block', width: '33px', height: '18px', cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    id="kc-is-default"
                    checked={formIsDefault}
                    onChange={e => setFormIsDefault(e.target.checked)}
                    style={{ opacity: 0, width: 0, height: 0 }}
                  />
                  <span className="ds-toggle-track" style={{
                    boxSizing: 'border-box',
                    position: 'absolute',
                    inset: 0,
                    display: 'flex',
                    flexDirection: 'row',
                    alignItems: 'center',
                    padding: '1.5px',
                    width: '33px',
                    height: '18px',
                    background: formIsDefault ? '#3E1363' : '#ADADAD',
                    border: formIsDefault ? '0.75px solid #3E1363' : '0.75px solid #ADADAD',
                    borderRadius: '9px',
                    transition: 'background-color 0.2s, border-color 0.2s'
                  }}>
                    <span className="ds-toggle-thumb" style={{
                      width: '13.5px',
                      height: '13.5px',
                      background: '#FFFFFF',
                      borderRadius: '50%',
                      position: 'absolute',
                      left: formIsDefault ? '16.5px' : '1.5px',
                      transition: 'left 0.2s',
                      transform: 'none'
                    }} />
                  </span>
                </label>
                <label htmlFor="kc-is-default" className="ds-toggle-label" style={{ fontFamily: 'Satoshi, sans-serif', fontSize: '13px', color: '#818181', cursor: 'pointer' }}>
                  Set as default connection for this cluster
                </label>
              </div>
            </div>

            <div className="ds-modal-footer" style={{ border: 'none', padding: '0', display: 'flex', justifyContent: 'flex-end', gap: '12px' }}>
              <button 
                className="ds-button" 
                onClick={() => setShowConnection(false)} 
                disabled={connectSaving}
                style={{
                  height: '38px',
                  padding: '0 20px',
                  background: '#FFFFFF',
                  border: '1px solid #CCCCCC',
                  borderRadius: '8px',
                  fontFamily: 'Satoshi, sans-serif',
                  fontSize: '14px',
                  fontWeight: 500,
                  color: '#332849',
                  cursor: 'pointer'
                }}
              >
                Cancel
              </button>
              <button
                className="ds-button primary"
                onClick={handleSaveConnection}
                disabled={connectSaving || !customIp.trim() || !customPort.trim()}
                style={{
                  height: '38px',
                  padding: '0 20px',
                  background: '#3E1363',
                  border: 'none',
                  borderRadius: '8px',
                  fontFamily: 'Satoshi, sans-serif',
                  fontSize: '14px',
                  fontWeight: 500,
                  color: '#FFFFFF',
                  cursor: 'pointer',
                  opacity: (connectSaving || !customIp.trim() || !customPort.trim()) ? 0.6 : 1
                }}
              >
                {connectSaving ? <RefreshCw size={16} className="spin" style={{ marginRight: '8px' }} /> : null} Save & Connect
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
