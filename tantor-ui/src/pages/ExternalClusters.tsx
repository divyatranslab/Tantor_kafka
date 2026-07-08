import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  AlertTriangle,
  ChevronDown,
  CheckCircle2,
  Copy,
  ExternalLink,
  Globe,
  RefreshCw,
  Server,
  Terminal,
} from 'lucide-react';
import './ExternalClusters.css';

interface BootstrapResult {
  name?: string;
  success?: boolean;
  connected?: boolean;
  status?: string;
  bootstrapServers?: string;
  bootstrap_servers?: string;
  security_protocol?: string;
  mode?: string;
  kafkaMode?: string;
  clusterId?: string;
  cluster_id?: string;
  kafka_cluster_id?: string;
  brokerCount?: number;
  brokers?: unknown[];
  topicCount?: number;
  topic_count?: number;
  topics?: unknown[];
  controllerId?: string | number;
  controller_id?: string | number;
  kafka_version?: string;
  kafkaVersion?: string;
  hostname?: string;
  security?: string;
  environment?: string;
  socket_results?: unknown[];
  message?: string;
}

export function ExternalClusters() {
  const navigate = useNavigate();
  const [banner, setBanner] = useState('');
  const [error, setError] = useState('');
  const [testing, setTesting] = useState(false);
  const [registering, setRegistering] = useState(false);
  const [openPanel, setOpenPanel] = useState<'bootstrap' | 'agent'>('bootstrap');

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
        body: JSON.stringify({ bootstrapServers: form.bootstrapServers }),
      });
      const data = await res.json();
      setBootstrapResult(data);
      if (!res.ok || data.connected !== true) {
        throw new Error(data.message || 'Bootstrap connection failed');
      }
      setBanner('Kafka details fetched successfully.');
    } catch (e: any) {
      setError(e.message || 'Failed to inspect the Kafka cluster');
    } finally {
      setTesting(false);
    }
  };

  const registerBootstrap = async () => {
    if (!form.bootstrapServers.trim() || bootstrapResult?.connected !== true) return;
    setRegistering(true);
    setError('');
    setBanner('');
    try {
      const payload = {
        ...form,
        clusterId: bootstrapResult?.cluster_id || bootstrapResult?.kafka_cluster_id || bootstrapResult?.clusterId,
        brokerCount: bootstrapResult?.brokerCount ?? bootstrapResult?.brokers?.length ?? 0,
        agentFound: bootstrapResult?.agentFound ?? false,
        discoveryKey: bootstrapResult?.discoveryKey || null,
        security: bootstrapResult?.security || bootstrapResult?.security_protocol || 'PLAINTEXT',
        brokers: bootstrapResult?.brokers || [],
        controllerId: bootstrapResult?.controllerId || bootstrapResult?.controller_id || null,
        kafkaVersion: bootstrapResult?.kafkaVersion || bootstrapResult?.kafka_version || 'Unknown',
        kafkaMode: bootstrapResult?.kafkaMode || bootstrapResult?.mode || 'KRaft'
      };

      const res = await fetch('/api/v1/ui/external-clusters/bootstrap/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || data.error || 'Failed to register external cluster');
      setBanner(`External cluster ${data.name || form.name || 'connected'} connected.`);
      setForm(prev => ({ ...prev, name: '', bootstrapServers: '', kafkaVersion: '' }));
      setBootstrapResult(null);
      navigate(`/clusters`);
    } catch (e: any) {
      setError(e.message || 'Failed to connect external cluster');
    } finally {
      setRegistering(false);
    }
  };

  const copyAgentConfig = async () => {
    await navigator.clipboard.writeText(agentConfig);
    setBanner('Discovery agent config copied.');
  };

  return (
    <div className="external-page animate-fade-in">
      <header className="external-header">
        <div>
          <h1>External Kafka Clusters</h1>
          <p>Connect an external Kafka cluster using its bootstrap URL.</p>
        </div>
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
          <span><Globe size={17} /> Connect external cluster</span>
          <ChevronDown size={17} className={openPanel === 'bootstrap' ? 'rotate' : ''} />
        </button>
        <button
          className={`connect-dropdown ${openPanel === 'agent' ? 'active' : ''}`}
          onClick={() => setOpenPanel(openPanel === 'agent' ? 'bootstrap' : 'agent')}
        >
          <span><Terminal size={17} /> Discovery Agent setup</span>
          <ChevronDown size={17} className={openPanel === 'agent' ? 'rotate' : ''} />
        </button>
      </section>

      <section className="external-connect-grid">
        {openPanel === 'bootstrap' && (
        <div className="external-panel">
          <div className="panel-title-row">
            <Globe size={18} />
            <div>
              <h2>Bootstrap server</h2>
              <p>Provide the bootstrap URL to connect the external cluster.</p>
            </div>
          </div>

          <div className="form-grid">
            <label>
              Cluster Name (Optional)
              <input 
                type="text" 
                placeholder="e.g. prod-external-01" 
                value={form.name}
                onChange={e => setForm(prev => ({ ...prev, name: e.target.value }))}
              />
            </label>
            <label>
              Environment
              <select 
                value={form.environment} 
                onChange={e => setForm(prev => ({ ...prev, environment: e.target.value }))}
              >
                <option value="dev">Development</option>
                <option value="test">Test</option>
                <option value="staging">Staging</option>
                <option value="prod">Production</option>
              </select>
            </label>
            <label className="span-2">
              Bootstrap URL
              <div className="discovery-select-row">
                <input 
                  type="text" 
                  placeholder="e.g. 192.168.1.100:9092" 
                  value={form.bootstrapServers}
                  onChange={e => {
                    setForm(prev => ({ ...prev, bootstrapServers: e.target.value }));
                    setBootstrapResult(null);
                  }}
                  onKeyDown={e => {
                    if (e.key === 'Enter') testBootstrap();
                  }}
                />
                <button 
                  className="btn icon-only" 
                  onClick={testBootstrap} 
                  title="Test Connection"
                  disabled={!form.bootstrapServers.trim() || testing}
                >
                  <RefreshCw size={14} className={testing ? 'spin' : ''} />
                </button>
              </div>
            </label>
          </div>

          {testing && (
            <div className="inspection-loading">
              <RefreshCw size={15} className="spin" />
              Testing direct connection via Kafka Admin API...
            </div>
          )}

          {bootstrapResult && (
            <div className={`inspection-result ${(bootstrapResult.success ?? bootstrapResult.connected) ? 'ok' : 'error'}`}>
              <div className="inspection-result-header">
                {(bootstrapResult.success ?? bootstrapResult.connected) ? <CheckCircle2 size={17} /> : <AlertTriangle size={17} />}
                <div>
                  <strong>{form.name || 'External Kafka cluster'}</strong>
                  <span>{(bootstrapResult.success ?? bootstrapResult.connected) ? 'Bootstrap connection verified' : 'Bootstrap connection failed'}</span>
                </div>
              </div>
              <div className="inspection-facts">
                <div><span>Cluster ID</span><strong title={bootstrapResult.cluster_id || bootstrapResult.kafka_cluster_id || bootstrapResult.clusterId}>{bootstrapResult.cluster_id || bootstrapResult.kafka_cluster_id || bootstrapResult.clusterId || '-'}</strong></div>
                <div><span>Mode</span><strong>{bootstrapResult.kafkaMode || bootstrapResult.mode || 'Unknown'}</strong></div>
                <div><span>Kafka version</span><strong>{bootstrapResult.kafkaVersion || bootstrapResult.kafka_version || 'Unknown'}</strong></div>
                <div><span>Brokers</span><strong>{bootstrapResult.brokerCount ?? bootstrapResult.brokers?.length ?? 0}</strong></div>
                <div><span>Controller</span><strong>{bootstrapResult.controller_id ?? bootstrapResult.controllerId ?? '-'}</strong></div>
                <div><span>Topics</span><strong>{bootstrapResult.topic_count ?? bootstrapResult.topicCount ?? 0}</strong></div>
                <div><span>Security</span><strong>{bootstrapResult.security || bootstrapResult.security_protocol || 'PLAINTEXT'}</strong></div>
              </div>
              
              {bootstrapResult.brokers && bootstrapResult.brokers.length > 0 && (
                <div className="inspection-brokers" style={{ marginTop: '16px', background: '#f8fafc', padding: '12px', borderRadius: '6px', border: '1px solid #e2e8f0' }}>
                  <h4 style={{ marginBottom: '8px', fontSize: '12px', fontWeight: '600', color: '#64748b', textTransform: 'uppercase' }}>Discovered Nodes</h4>
                  <table style={{ width: '100%', fontSize: '13px', borderCollapse: 'collapse' }}>
                    <thead>
                      <tr style={{ borderBottom: '1px solid #cbd5e1', textAlign: 'left', color: '#64748b' }}>
                        <th style={{ padding: '6px' }}>Node ID</th>
                        <th style={{ padding: '6px' }}>Host</th>
                        <th style={{ padding: '6px' }}>Port</th>
                        <th style={{ padding: '6px' }}>Role</th>
                      </tr>
                    </thead>
                    <tbody>
                      {bootstrapResult.brokers.map((broker: any) => (
                        <tr key={broker.node_id || broker.broker_id || broker.id} style={{ borderBottom: '1px solid #e2e8f0' }}>
                          <td style={{ padding: '6px' }}><strong>{broker.node_id || broker.broker_id || broker.id}</strong></td>
                          <td style={{ padding: '6px' }}>{broker.host}</td>
                          <td style={{ padding: '6px' }}>{broker.port}</td>
                          <td style={{ padding: '6px' }}>
                            {(broker.node_id || broker.broker_id || broker.id) == (bootstrapResult.controller_id ?? bootstrapResult.controllerId) ? <span style={{ color: '#059669', fontWeight: 500 }}>Controller + Broker</span> : <span style={{ color: '#3b82f6', fontWeight: 500 }}>Broker</span>}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

              {bootstrapResult.message && !(bootstrapResult.success ?? bootstrapResult.connected) && (
                <p className="inspection-error-message">{bootstrapResult.message}</p>
              )}
            </div>
          )}

          <div className="panel-actions">
            <button
              className="btn btn-primary-action"
              onClick={registerBootstrap}
              disabled={registering || bootstrapResult?.connected !== true}
            >
              {registering ? <RefreshCw size={14} className="spin" /> : <ExternalLink size={14} />}
              Connect cluster
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
            <div><span>3</span> Run it with `nohup`; discovered clusters will report metrics and handle restart tasks.</div>
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
        </div>
        )}
      </section>
    </div>
  );
}
