import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Activity,
  AlertTriangle,
  ChevronDown,
  CheckCircle2,
  Copy,
  ExternalLink,
  Globe,
  Play,
  RefreshCw,
  Server,
  Terminal,
  X,
} from 'lucide-react';
import './ExternalClusters.css';

interface ExternalClusterSummary {
  id: string;
  name: string;
  clusterId?: string;
  kafkaVersion?: string;
  kafkaMode?: string;
  security?: string;
  bootstrapServers?: string;
  environment?: string;
  brokerCount?: number;
  agentCount?: number;
  managementLevel?: 'AGENT_MANAGED' | 'BOOTSTRAP_ONLY' | string;
  managementLabel?: string;
  health?: string;
  lastSeen?: string;
  installPath?: string;
  logDirs?: string;
}

interface BootstrapResult {
  success?: boolean;
  connected?: boolean;
  status?: string;
  bootstrapServers?: string;
  bootstrap_servers?: string;
  security_protocol?: string;
  mode?: string;
  clusterId?: string;
  kafka_cluster_id?: string;
  brokerCount?: number;
  brokers?: unknown[];
  topicCount?: number;
  topic_count?: number;
  topics?: unknown[];
  controllerId?: string | number;
  controller_id?: string | number;
  kafka_version?: string;
  socket_results?: unknown[];
  message?: string;
}

interface ExternalDiscovery {
  discoveryKey: string;
  name: string;
  hostname?: string;
  bootstrapServers?: string;
  kafkaVersion?: string;
  kafkaMode?: string;
  security?: string;
  brokerCount?: number;
  nodeId?: number;
  environment?: string;
  installPath?: string;
  logDirs?: string;
  running?: boolean;
  health?: string;
  lastSeen?: string;
  kafkaClusterId?: string;
}

export function ExternalClusters() {
  const navigate = useNavigate();
  const [clusters, setClusters] = useState<ExternalClusterSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [banner, setBanner] = useState('');
  const [error, setError] = useState('');
  const [testing, setTesting] = useState(false);
  const [registering, setRegistering] = useState(false);
  const [taskStatus, setTaskStatus] = useState<Record<string, string>>({});
  const [openPanel, setOpenPanel] = useState<'bootstrap' | 'agent'>('bootstrap');
  const [discoveries, setDiscoveries] = useState<ExternalDiscovery[]>([]);
  const [discoveriesLoading, setDiscoveriesLoading] = useState(false);
  const [showDiscoveryModal, setShowDiscoveryModal] = useState(false);
  const [connectingKey, setConnectingKey] = useState('');
  const [testingDiscoveryKey, setTestingDiscoveryKey] = useState('');
  const [discoveryTestResults, setDiscoveryTestResults] = useState<Record<string, BootstrapResult>>({});

  const [form, setForm] = useState({
    name: '',
    environment: 'prod',
    bootstrapServers: '',
    kafkaVersion: '',
    security: 'PLAINTEXT',
  });
  const [bootstrapResult, setBootstrapResult] = useState<BootstrapResult | null>(null);

  const serverHint = useMemo(() => {
    const host = window.location.hostname || '<tantor-server-ip>';
    return `http://${host}:8443`;
  }, []);

  const agentConfig = useMemo(() => (
`discovery:
  server_url: "${serverHint}"
  scan_paths:
    - "/srv/apps"
    - "/data/apps"
    - "/opt"
  interval: "15s"
  node_name: ""
  restart_command: "systemctl restart kafka"`
  ), [serverHint]);

  const fetchClusters = async () => {
    setLoading(true);
    setError('');
    try {
      const res = await fetch('/api/v1/ui/external-clusters');
      if (!res.ok) throw new Error(`Failed to load external clusters (${res.status})`);
      setClusters(await res.json());
      fetchDiscoveries();
    } catch (e: any) {
      setError(e.message || 'Failed to load external clusters');
    } finally {
      setLoading(false);
    }
  };

  const fetchDiscoveries = async () => {
    setDiscoveriesLoading(true);
    try {
      const res = await fetch('/api/v1/ui/external-clusters/discoveries');
      if (res.ok) {
        setDiscoveries(await res.json());
      }
    } catch (e) {
      console.error(e);
    } finally {
      setDiscoveriesLoading(false);
    }
  };

  useEffect(() => {
    fetchClusters();
  }, []);

  const testBootstrap = async () => {
    if (!form.bootstrapServers.trim()) return;
    setTesting(true);
    setError('');
    setBanner('');
    setBootstrapResult(null);
    try {
      const res = await fetch('/api/v1/ui/external-clusters/bootstrap/test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ bootstrapServers: form.bootstrapServers.trim() }),
      });
      const data = await res.json();
      setBootstrapResult(data);
      if (!res.ok || data.connected === false) {
        throw new Error(data.message || 'Bootstrap connection failed');
      }
      setBanner('Bootstrap connection verified.');
    } catch (e: any) {
      setError(e.message || 'Bootstrap test failed');
    } finally {
      setTesting(false);
    }
  };

  const registerBootstrap = async () => {
    if (!form.bootstrapServers.trim()) return;
    setRegistering(true);
    setError('');
    setBanner('');
    try {
      const res = await fetch('/api/v1/ui/external-clusters/bootstrap/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: form.name.trim(),
          environment: form.environment.trim(),
          bootstrapServers: form.bootstrapServers.trim(),
          kafkaVersion: form.kafkaVersion.trim(),
          security: form.security,
        }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || data.error || 'Failed to register external cluster');
      setBanner(`External cluster ${data.name || form.name || 'registered'} added.`);
      setForm(prev => ({ ...prev, name: '', bootstrapServers: '', kafkaVersion: '' }));
      setBootstrapResult(null);
      fetchClusters();
    } catch (e: any) {
      setError(e.message || 'Failed to register external cluster');
    } finally {
      setRegistering(false);
    }
  };

  const copyAgentConfig = async () => {
    await navigator.clipboard.writeText(agentConfig);
    setBanner('Discovery agent config copied.');
  };

  const openDiscoveryList = () => {
    setShowDiscoveryModal(true);
    fetchDiscoveries();
  };

  const connectDiscovery = async (discovery: ExternalDiscovery) => {
    setConnectingKey(discovery.discoveryKey);
    setError('');
    setBanner('');
    try {
      const res = await fetch(`/api/v1/ui/external-clusters/discoveries/${encodeURIComponent(discovery.discoveryKey)}/connect`, {
        method: 'POST',
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || data.error || 'Failed to connect discovery');
      setBanner(`${data.name || discovery.name} connected as an external cluster.`);
      await Promise.all([fetchClusters(), fetchDiscoveries()]);
    } catch (e: any) {
      setError(e.message || 'Failed to connect discovery');
    } finally {
      setConnectingKey('');
    }
  };

  const testDiscovery = async (discovery: ExternalDiscovery) => {
    if (!discovery.bootstrapServers) return;
    setTestingDiscoveryKey(discovery.discoveryKey);
    setDiscoveryTestResults(prev => {
      const next = { ...prev };
      delete next[discovery.discoveryKey];
      return next;
    });
    try {
      const res = await fetch('/api/v1/ui/external-clusters/bootstrap/test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ bootstrapServers: discovery.bootstrapServers }),
      });
      const data = await res.json();
      setDiscoveryTestResults(prev => ({ ...prev, [discovery.discoveryKey]: data }));
    } catch (e: any) {
      setDiscoveryTestResults(prev => ({
        ...prev,
        [discovery.discoveryKey]: {
          success: false,
          connected: false,
          status: 'FAILED',
          bootstrap_servers: discovery.bootstrapServers,
          message: e.message || 'Bootstrap connection failed',
        },
      }));
    } finally {
      setTestingDiscoveryKey('');
    }
  };

  const restartExternal = async (cluster: ExternalClusterSummary) => {
    if (cluster.managementLevel !== 'AGENT_MANAGED') return;
    if (!window.confirm(`Queue restart for ${cluster.name}?`)) return;

    setTaskStatus(prev => ({ ...prev, [cluster.id]: 'Queuing restart...' }));
    try {
      const res = await fetch(`/api/v1/ui/external-clusters/${cluster.id}/restart`, { method: 'POST' });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || data.message || 'Failed to queue restart');

      const taskId = data.taskId;
      setTaskStatus(prev => ({ ...prev, [cluster.id]: 'Restart queued. Waiting for discovery agent...' }));
      const interval = window.setInterval(async () => {
        const statusRes = await fetch(`/api/v1/ui/external-clusters/tasks/${taskId}`);
        const statusData = await statusRes.json();
        setTaskStatus(prev => ({ ...prev, [cluster.id]: statusData.status || 'Waiting...' }));
        if ((statusData.status || '').startsWith('COMPLETED') || (statusData.status || '').startsWith('FAILED')) {
          window.clearInterval(interval);
          fetchClusters();
        }
      }, 2000);
    } catch (e: any) {
      setTaskStatus(prev => ({ ...prev, [cluster.id]: e.message || 'Restart failed' }));
    }
  };

  const healthClass = (health?: string) => {
    const text = (health || '').toLowerCase();
    if (text.includes('online') || text.includes('connected')) return 'ok';
    if (text.includes('stale')) return 'warn';
    return 'neutral';
  };

  const bootstrapReport = (result: BootstrapResult) => {
    const connected = result.success ?? result.connected ?? false;
    const report = {
      success: connected,
      status: result.status || (connected ? 'CONNECTED' : 'FAILED'),
      bootstrap_servers: result.bootstrap_servers || result.bootstrapServers || form.bootstrapServers.trim(),
      security_protocol: result.security_protocol || 'PLAINTEXT',
      mode: result.mode || 'auto-detected by Kafka client',
      kafka_cluster_id: result.kafka_cluster_id || result.clusterId || '',
      brokers: result.brokers || [],
      controller_id: result.controller_id ?? result.controllerId ?? null,
      topic_count: result.topic_count ?? result.topicCount ?? 0,
      topics: result.topics || [],
      kafka_version: result.kafka_version || 'auto-detected by Kafka client',
      socket_results: result.socket_results || [],
    };
    return JSON.stringify(report, null, 2);
  };

  return (
    <div className="external-page animate-fade-in">
      <header className="external-header">
        <div>
          <h1>External Clusters</h1>
          <p>Connect existing Kafka clusters by bootstrap metadata or by a Tantor discovery agent.</p>
        </div>
        <button className="btn" onClick={fetchClusters}>
          <RefreshCw size={14} className={loading ? 'spin' : ''} />
          Refresh
        </button>
      </header>

      {(banner || error) && (
        <div className={`external-banner ${error ? 'error' : 'success'}`}>
          {error ? <AlertTriangle size={16} /> : <CheckCircle2 size={16} />}
          <span>{error || banner}</span>
        </div>
      )}

      <section className="connect-dropdowns">
        <button
          className={`connect-dropdown ${openPanel === 'bootstrap' ? 'active' : ''}`}
          onClick={() => setOpenPanel(openPanel === 'bootstrap' ? 'agent' : 'bootstrap')}
        >
          <span><Globe size={17} /> Bootstrap dropdown</span>
          <ChevronDown size={17} className={openPanel === 'bootstrap' ? 'rotate' : ''} />
        </button>
        <button
          className={`connect-dropdown ${openPanel === 'agent' ? 'active' : ''}`}
          onClick={() => setOpenPanel(openPanel === 'agent' ? 'bootstrap' : 'agent')}
        >
          <span><Terminal size={17} /> Discovery Agent dropdown</span>
          <ChevronDown size={17} className={openPanel === 'agent' ? 'rotate' : ''} />
        </button>
      </section>

      <section className="external-connect-grid">
        {openPanel === 'bootstrap' && (
        <div className="external-panel">
          <div className="panel-title-row">
            <Globe size={18} />
            <div>
              <h2>Bootstrap Metadata</h2>
              <p>Read-only connection for inventory, topics, brokers, and metadata.</p>
            </div>
          </div>

          <div className="warning-strip">
            <AlertTriangle size={15} />
            <span>No service restart or config file persistence until a discovery agent is attached.</span>
          </div>

          <div className="form-grid">
            <label>
              Cluster name
              <input
                value={form.name}
                onChange={e => setForm(prev => ({ ...prev, name: e.target.value }))}
                placeholder="external-prod-kafka"
              />
            </label>
            <label>
              Environment
              <input
                value={form.environment}
                onChange={e => setForm(prev => ({ ...prev, environment: e.target.value }))}
                placeholder="prod"
              />
            </label>
            <label className="span-2">
              Bootstrap servers
              <input
                value={form.bootstrapServers}
                onChange={e => setForm(prev => ({ ...prev, bootstrapServers: e.target.value }))}
                placeholder="192.168.3.196:9092"
              />
            </label>
            <label>
              Kafka version
              <input
                value={form.kafkaVersion}
                onChange={e => setForm(prev => ({ ...prev, kafkaVersion: e.target.value }))}
                placeholder="Auto if known"
              />
            </label>
          </div>

          {bootstrapResult && (
            <div className={`bootstrap-result-terminal ${(bootstrapResult.success ?? bootstrapResult.connected) ? 'ok' : 'error'}`}>
              <div className="terminal-status">
                {(bootstrapResult.success ?? bootstrapResult.connected)
                  ? 'Bootstrap connection successful.'
                  : 'Bootstrap connection failed.'}
              </div>
              <pre>{bootstrapReport(bootstrapResult)}</pre>
            </div>
          )}

          <div className="panel-actions">
            <button className="btn" onClick={testBootstrap} disabled={testing || !form.bootstrapServers.trim()}>
              {testing ? <RefreshCw size={14} className="spin" /> : <Activity size={14} />}
              Test
            </button>
            <button
              className="btn btn-primary-action"
              onClick={registerBootstrap}
              disabled={registering || !form.bootstrapServers.trim()}
            >
              {registering ? <RefreshCw size={14} className="spin" /> : <ExternalLink size={14} />}
              Register
            </button>
          </div>
        </div>
        )}

        {openPanel === 'agent' && (
        <div className="external-panel agent-panel">
          <div className="panel-title-row">
            <Terminal size={18} />
            <div>
              <h2>Discovery Agent</h2>
              <p>Full management path for restart, host metrics, and config persistence.</p>
            </div>
          </div>

          <div className="agent-flow">
            <div><span>1</span> Build or copy `tantor-discovery-agent-linux` to the Kafka VM.</div>
            <div><span>2</span> Set `server_url` to this Tantor backend.</div>
            <div><span>3</span> Run it with `nohup`; discovered clusters wait for Connect approval.</div>
          </div>

          <div className="code-block">
            <pre>{agentConfig}</pre>
            <button className="icon-button" onClick={copyAgentConfig} title="Copy config">
              <Copy size={15} />
            </button>
          </div>

          <div className="agent-note">
            <Server size={15} />
            <span>The agent auto-detects KRaft vs ZooKeeper from Kafka properties. Users do not select the mode manually.</span>
          </div>

          <div className="panel-actions">
            <button className="btn" onClick={fetchDiscoveries}>
              <RefreshCw size={14} className={discoveriesLoading ? 'spin' : ''} />
              Sync discoveries
            </button>
            <button className="btn btn-primary-action" onClick={openDiscoveryList}>
              <Server size={14} />
              View discovered clusters ({discoveries.length})
            </button>
          </div>
        </div>
        )}
      </section>

      <section className="external-list-section">
        <div className="section-heading">
          <h2>External Cluster List</h2>
          <span>{clusters.length} connected</span>
        </div>

        <div className="external-table-wrap">
          <table className="external-table">
            <thead>
              <tr>
                <th>Cluster</th>
                <th>Bootstrap</th>
                <th>Mode</th>
                <th>Brokers</th>
                <th>Management</th>
                <th>Health</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr><td colSpan={7} className="empty-cell">Loading external clusters...</td></tr>
              ) : clusters.length === 0 ? (
                <tr><td colSpan={7} className="empty-cell">No external clusters connected yet.</td></tr>
              ) : clusters.map(cluster => (
                <tr key={cluster.id}>
                  <td>
                    <div className="cluster-stack">
                      <strong>{cluster.name}</strong>
                      <span>Kafka {cluster.kafkaVersion || 'Unknown'} - {cluster.clusterId || 'no cluster id'}</span>
                    </div>
                  </td>
                  <td className="mono-cell" title={cluster.bootstrapServers}>{cluster.bootstrapServers || '-'}</td>
                  <td><span className="soft-tag">{cluster.kafkaMode || 'Auto'}</span></td>
                  <td>{cluster.brokerCount ?? 0}</td>
                  <td>
                    <span className={`management-pill ${cluster.managementLevel === 'AGENT_MANAGED' ? 'managed' : 'readonly'}`}>
                      {cluster.managementLabel || 'Bootstrap only'}
                    </span>
                  </td>
                  <td>
                    <span className={`health-pill ${healthClass(cluster.health)}`}>
                      {cluster.health || 'Unknown'}
                    </span>
                  </td>
                  <td>
                    <div className="row-actions">
                      <button className="btn btn-small" onClick={() => navigate(`/clusters/${cluster.id}/brokers`)}>
                        <ExternalLink size={13} />
                        Open
                      </button>
                      <button
                        className="btn btn-small"
                        onClick={() => restartExternal(cluster)}
                        disabled={cluster.managementLevel !== 'AGENT_MANAGED'}
                        title={cluster.managementLevel === 'AGENT_MANAGED' ? 'Queue agent restart' : 'Requires discovery agent'}
                      >
                        <Play size={13} />
                        Restart
                      </button>
                    </div>
                    {taskStatus[cluster.id] && <div className="task-line">{taskStatus[cluster.id]}</div>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      {showDiscoveryModal && (
        <div className="discovery-modal-backdrop" role="dialog" aria-modal="true">
          <div className="discovery-modal">
            <div className="modal-header">
              <div>
                <h2>Discovery Agent Clusters</h2>
                <p>Review Kafka clusters reported by discovery agents, then connect the ones you want to manage.</p>
              </div>
              <button className="modal-close" onClick={() => setShowDiscoveryModal(false)} title="Close">
                <X size={18} />
              </button>
            </div>

            <div className="discovery-list">
              {discoveriesLoading ? (
                <div className="empty-cell">Loading discovered clusters...</div>
              ) : discoveries.length === 0 ? (
                <div className="empty-cell">No pending discoveries. Start the discovery agent on a Kafka VM, then refresh.</div>
              ) : discoveries.map(discovery => {
                const testResult = discoveryTestResults[discovery.discoveryKey];
                const testPassed = Boolean(testResult && (testResult.success ?? testResult.connected));
                return (
                <div className="discovery-row" key={discovery.discoveryKey}>
                  <div className="discovery-main">
                    <div className="cluster-stack">
                      <strong>{discovery.name}</strong>
                      <span>{discovery.hostname || 'unknown host'} - Kafka {discovery.kafkaVersion || 'Unknown'}</span>
                    </div>
                    <div className="discovery-meta">
                      <span className="mono-cell" title={discovery.bootstrapServers}>{discovery.bootstrapServers || '-'}</span>
                      <span className="soft-tag">{discovery.kafkaMode || 'Auto'}</span>
                      <span className={`health-pill ${discovery.running ? 'ok' : 'warn'}`}>{discovery.health || 'Discovered'}</span>
                    </div>
                    <div className="discovery-paths">
                      <span title={discovery.installPath}>Install: {discovery.installPath || '-'}</span>
                      <span title={discovery.logDirs}>Logs: {discovery.logDirs || '-'}</span>
                    </div>
                    {testResult && (
                      <div className={`bootstrap-result-terminal compact ${(testResult.success ?? testResult.connected) ? 'ok' : 'error'}`}>
                        <div className="terminal-status">
                          {(testResult.success ?? testResult.connected)
                            ? 'Bootstrap connection successful.'
                            : 'Bootstrap connection failed.'}
                        </div>
                        <pre>{bootstrapReport(testResult)}</pre>
                      </div>
                    )}
                  </div>
                  <div className="discovery-actions">
                    <button
                      className="btn"
                      onClick={() => testDiscovery(discovery)}
                      disabled={testingDiscoveryKey === discovery.discoveryKey || !discovery.bootstrapServers}
                    >
                      {testingDiscoveryKey === discovery.discoveryKey ? <RefreshCw size={14} className="spin" /> : <Activity size={14} />}
                      Test
                    </button>
                    <button
                      className="btn btn-primary-action"
                      onClick={() => connectDiscovery(discovery)}
                      disabled={!testPassed || connectingKey === discovery.discoveryKey}
                      title={testPassed ? 'Connect this discovered cluster' : 'Test connection before connecting'}
                    >
                      {connectingKey === discovery.discoveryKey ? <RefreshCw size={14} className="spin" /> : <ExternalLink size={14} />}
                      Connect
                    </button>
                  </div>
                </div>
                );
              })}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
