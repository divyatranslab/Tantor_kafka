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
    securityProtocol: 'PLAINTEXT',
    saslMechanism: 'PLAIN',
    saslUsername: '',
    saslPassword: '',
    truststoreType: 'JKS',
    truststorePassword: '',
    truststoreBase64: '',
    truststoreFilename: '',
    keystoreType: 'JKS',
    keystorePassword: '',
    keyPassword: '',
    keystoreBase64: '',
    keystoreFilename: '',
    disableHostnameVerification: false,
  });
  const [bootstrapResult, setBootstrapResult] = useState<BootstrapResult | null>(null);
  const [selectedAgents, setSelectedAgents] = useState<Record<string, string>>({});

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
        body: JSON.stringify(form),
      });
      const data = await res.json();
      setBootstrapResult(data);
      
      if (data.brokers) {
        const initialSelection: Record<string, string> = {};
        data.brokers.forEach((b: any) => {
          if (b.hasActiveAgent && b.agentDiscoveryKey) {
            initialSelection[b.host] = b.agentDiscoveryKey;
          }
        });
        setSelectedAgents(initialSelection);
      }

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

  const handleFileUpload = (e: React.ChangeEvent<HTMLInputElement>, fieldBase64: string, fieldFilename: string) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (evt) => {
      const result = evt.target?.result;
      if (typeof result === 'string') {
        const base64Content = result.split(',')[1] || result;
        setForm(prev => ({
          ...prev,
          [fieldBase64]: base64Content,
          [fieldFilename]: file.name
        }));
      }
    };
    reader.readAsDataURL(file);
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
        kafkaMode: bootstrapResult?.kafkaMode || bootstrapResult?.mode || 'KRaft',
        selectedAgents: selectedAgents
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
                  className="btn" 
                  onClick={testBootstrap} 
                  title="Test Connection"
                  disabled={!form.bootstrapServers.trim() || testing}
                >
                  <RefreshCw size={14} className={testing ? 'spin' : ''} />
                  Test Connection
                </button>
              </div>
            </label>
            
            {/* Security Settings Section */}
            <div className="span-2" style={{ marginTop: '16px', borderTop: '1px solid #e2e8f0', paddingTop: '16px' }}>
              <h3 style={{ fontSize: '14px', fontWeight: 600, color: '#334155', marginBottom: '12px' }}>Security Configuration</h3>
            </div>
            
            <label>
              Security Protocol
              <select 
                value={form.securityProtocol} 
                onChange={e => {
                  setForm(prev => ({ ...prev, securityProtocol: e.target.value }));
                  setBootstrapResult(null);
                }}
              >
                <option value="PLAINTEXT">PLAINTEXT</option>
                <option value="SSL">SSL</option>
                <option value="SASL_PLAINTEXT">SASL_PLAINTEXT</option>
                <option value="SASL_SSL">SASL_SSL</option>
              </select>
            </label>

            {(form.securityProtocol === 'SASL_PLAINTEXT' || form.securityProtocol === 'SASL_SSL') && (
              <>
                <label>
                  SASL Mechanism
                  <select 
                    value={form.saslMechanism} 
                    onChange={e => {
                      setForm(prev => ({ ...prev, saslMechanism: e.target.value }));
                      setBootstrapResult(null);
                    }}
                  >
                    <option value="PLAIN">PLAIN</option>
                    <option value="SCRAM-SHA-256">SCRAM-SHA-256</option>
                    <option value="SCRAM-SHA-512">SCRAM-SHA-512</option>
                  </select>
                </label>
                <label>
                  SASL Username
                  <input 
                    type="text" 
                    placeholder="Username" 
                    value={form.saslUsername}
                    onChange={e => {
                      setForm(prev => ({ ...prev, saslUsername: e.target.value }));
                      setBootstrapResult(null);
                    }}
                  />
                </label>
                <label>
                  SASL Password
                  <input 
                    type="password" 
                    placeholder="Password" 
                    value={form.saslPassword}
                    onChange={e => {
                      setForm(prev => ({ ...prev, saslPassword: e.target.value }));
                      setBootstrapResult(null);
                    }}
                  />
                </label>
              </>
            )}

            {(form.securityProtocol === 'SSL' || form.securityProtocol === 'SASL_SSL') && (
              <>
                <label>
                  Disable Hostname Verification
                  <div style={{ display: 'flex', alignItems: 'center', marginTop: '8px', gap: '8px' }}>
                    <input 
                      type="checkbox" 
                      style={{ width: 'auto' }}
                      checked={form.disableHostnameVerification}
                      onChange={e => {
                        setForm(prev => ({ ...prev, disableHostnameVerification: e.target.checked }));
                        setBootstrapResult(null);
                      }}
                    />
                    <span style={{ fontSize: '13px', color: '#64748b' }}>Skip checking hostname in certificate</span>
                  </div>
                </label>
                <label>
                  Truststore Type
                  <select 
                    value={form.truststoreType} 
                    onChange={e => {
                      setForm(prev => ({ ...prev, truststoreType: e.target.value }));
                      setBootstrapResult(null);
                    }}
                  >
                    <option value="JKS">JKS</option>
                    <option value="PKCS12">PKCS12</option>
                    <option value="PEM">PEM / X.509</option>
                  </select>
                </label>
                <label className="span-2">
                  Truststore File (CA Certificate)
                  <div style={{ display: 'flex', gap: '12px', alignItems: 'center', marginTop: '4px' }}>
                    <input 
                      type="file" 
                      onChange={e => {
                        handleFileUpload(e, 'truststoreBase64', 'truststoreFilename');
                        setBootstrapResult(null);
                      }}
                      style={{ border: 'none', padding: 0 }}
                    />
                    {form.truststoreFilename && <span style={{ fontSize: '12px', color: '#10b981' }}><CheckCircle2 size={12} style={{ verticalAlign: 'middle', marginRight: '4px' }} />{form.truststoreFilename} attached</span>}
                  </div>
                </label>
                {form.truststoreType !== 'PEM' && (
                  <label>
                    Truststore Password
                    <input 
                      type="password" 
                      placeholder="Password" 
                      value={form.truststorePassword}
                      onChange={e => {
                        setForm(prev => ({ ...prev, truststorePassword: e.target.value }));
                        setBootstrapResult(null);
                      }}
                    />
                  </label>
                )}
              </>
            )}
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
                  {!(bootstrapResult.success ?? bootstrapResult.connected) && bootstrapResult.message && (
                    <div style={{ fontSize: '12px', marginTop: '4px', color: '#ef4444' }}>
                      {bootstrapResult.message}
                    </div>
                  )}
                </div>
              </div>
              <div className="inspection-facts">
                <div><span>Cluster ID</span><strong title={bootstrapResult.cluster_id || bootstrapResult.kafka_cluster_id || bootstrapResult.clusterId}>{bootstrapResult.cluster_id || bootstrapResult.kafka_cluster_id || bootstrapResult.clusterId || '-'}</strong></div>
                <div><span>Mode</span><strong>{bootstrapResult.kafkaMode || bootstrapResult.mode || 'Unknown'}</strong></div>
                <div><span>Kafka version</span><strong>{bootstrapResult.kafkaVersion || bootstrapResult.kafka_version || 'Unknown'}</strong></div>
                <div><span>Brokers</span><strong>{bootstrapResult.brokerCount ?? bootstrapResult.brokers?.length ?? 0}</strong></div>
                <div><span>Controller</span><strong>{bootstrapResult.activeControllerId ?? bootstrapResult.controller_id ?? bootstrapResult.controllerId ?? '-'}</strong></div>
                <div><span>Topics</span><strong>{bootstrapResult.topic_count ?? bootstrapResult.topicCount ?? 0}</strong></div>
                <div><span>Security</span><strong>{bootstrapResult.security || bootstrapResult.security_protocol || 'PLAINTEXT'}</strong></div>
              </div>
              
              {bootstrapResult.brokers && bootstrapResult.brokers.length > 0 && (
                <div className="inspection-brokers" style={{ marginTop: '16px', background: '#f8fafc', padding: '12px', borderRadius: '6px', border: '1px solid #e2e8f0' }}>
                  <h4 style={{ marginBottom: '8px', fontSize: '12px', fontWeight: '600', color: '#64748b', textTransform: 'uppercase' }}>Discovered Nodes</h4>
                  <table style={{ width: '100%', fontSize: '13px', borderCollapse: 'collapse' }}>
                    <thead>
                      <tr style={{ borderBottom: '1px solid #cbd5e1', textAlign: 'left', color: '#64748b' }}>
                        <th style={{ padding: '6px', width: '40px' }}></th>
                        <th style={{ padding: '6px' }}>Node ID</th>
                        <th style={{ padding: '6px' }}>Host</th>
                        <th style={{ padding: '6px' }}>Port</th>
                        <th style={{ padding: '6px' }}>Role</th>
                      </tr>
                    </thead>
                    <tbody>
                      {bootstrapResult.brokers.map((broker: any) => {
                        const isSelected = !!selectedAgents[broker.host];
                        const isDisabled = !broker.hasActiveAgent;
                        return (
                        <tr key={broker.node_id || broker.broker_id || broker.id} style={{ borderBottom: '1px solid #e2e8f0', background: isSelected ? '#f0fdf4' : 'transparent' }}>
                          <td style={{ padding: '6px', textAlign: 'center' }}>
                            <input
                              type="checkbox"
                              checked={isSelected}
                              disabled={isDisabled}
                              onChange={(e) => {
                                setSelectedAgents(prev => {
                                  const next = { ...prev };
                                  if (e.target.checked) {
                                    next[broker.host] = broker.agentDiscoveryKey;
                                  } else {
                                    delete next[broker.host];
                                  }
                                  return next;
                                });
                              }}
                              style={{ cursor: isDisabled ? 'not-allowed' : 'pointer' }}
                            />
                          </td>
                          <td style={{ padding: '6px' }}><strong>{broker.node_id || broker.broker_id || broker.id}</strong></td>
                          <td style={{ padding: '6px' }}>
                            {broker.host}
                            {isDisabled && <span style={{ display: 'block', fontSize: '11px', color: '#94a3b8' }}>No telemetry / unmanaged</span>}
                          </td>
                          <td style={{ padding: '6px' }}>{broker.port}</td>
                          <td style={{ padding: '6px' }}>
                            {broker.isController && broker.isBroker ? (
                              <span style={{ color: '#059669', fontWeight: 500 }}>Controller + Broker</span>
                            ) : broker.isController ? (
                              <span style={{ color: '#7c3aed', fontWeight: 500 }}>Controller</span>
                            ) : (
                              <span style={{ color: '#3b82f6', fontWeight: 500 }}>Broker</span>
                            )}
                          </td>
                        </tr>
                      )})}
                    </tbody>
                  </table>
                </div>
              )}

              {bootstrapResult.message && !(bootstrapResult.success ?? bootstrapResult.connected) && (
                <p className="inspection-error-message">{bootstrapResult.message}</p>
              )}
            </div>
          )}

          <div style={{ marginTop: '24px', display: 'flex', justifyContent: 'flex-end', gap: '12px' }}>
            <button className="btn" onClick={() => setBootstrapResult(null)}>Cancel</button>
            <button 
              className="btn primary" 
              onClick={registerBootstrap} 
              disabled={registering || bootstrapResult?.connected !== true || Object.keys(selectedAgents).length === 0}
            >
              {registering ? <RefreshCw size={15} className="spin" /> : <Globe size={15} />}
              Connect Cluster
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
