import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronRight, ChevronLeft, Check, Server, Loader2, AlertTriangle, FolderOpen, Info, Network, Wifi, CheckCircle, XCircle } from 'lucide-react';
import './ClusterWizard.css';

export interface Host {
  id: string;
  hostname: string;
  ip_address: string;
  status: string;
}

export interface KafkaVersionInfo {
  version: string;
  available: boolean;
  scala_version: string;
  release_date: string;
  size_mb: number;
  filename: string;
  features?: string[];
  id?: string;
}

export interface ServiceAssignment {
  host_id: string;
  role: string;
  node_id: number;
}

export interface ClusterConfig {
  replication_factor: number;
  num_partitions: number;
  log_dirs: string;
  listener_port: number;
  controller_port: number;
  heap_size: string;
  kafka_install_dir?: string;
  kafka_data_dir?: string;
}

const ROLES = [
  { id: 'broker_controller', label: 'Broker + Controller', description: 'Combined KRaft broker and controller' },
  { id: 'broker', label: 'Broker', description: 'Kafka broker only (data plane)' },
  { id: 'controller', label: 'Controller', description: 'KRaft controller only (metadata)' },
];

const EXCLUSIVE_GROUPS: Record<string, string[]> = {
  broker_controller: ['broker', 'controller'],
  broker: ['broker_controller'],
  controller: ['broker_controller'],
};

function getMajorVersion(version: string): number {
  return parseInt(version.split('.')[0], 10) || 0;
}

function validateDeployPath(value: string, label: string): string {
  if (!value.trim()) return '';
  const path = value.trim();
  if (!path.startsWith('/')) return `${label} must be an absolute path (start with /).`;
  if (path.split('/').includes('..')) return `${label} must not contain ".." path traversal.`;
  if (!/^\/[A-Za-z0-9/_\-.]{1,510}$/.test(path))
    return `${label} contains invalid characters. Use only letters, numbers, /, -, _, .`;
  return '';
}

export default function ClusterWizard() {
  const navigate = useNavigate();
  const [step, setStep] = useState(0);
  const [hosts, setHosts] = useState<Host[]>([]);
  const [versions, setVersions] = useState<KafkaVersionInfo[]>([]);
  const [versionsLoading, setVersionsLoading] = useState(true);
  const [loading, setLoading] = useState(false);

  // Step 1
  const [name, setName] = useState('');
  const [kafkaVersion, setKafkaVersion] = useState('');
  const [mode, setMode] = useState<'kraft' | 'zookeeper' | 'EXTERNAL'>('kraft');
  const [environment, setEnvironment] = useState('');

  // Step 2
  const [assignments, setAssignments] = useState<Record<string, string[]>>({});
  
  // External Cluster
  const [bootstrapServers, setBootstrapServers] = useState('');

  // Step 3
  const [config, setConfig] = useState<ClusterConfig>({
    replication_factor: 3,
    num_partitions: 3,
    log_dirs: '/var/lib/kafka/data',
    listener_port: 9092,
    controller_port: 9093,
    heap_size: '1G',
    kafka_install_dir: '',
    kafka_data_dir: '',
  });

  const [portError, setPortError] = useState('');
  const [installDirError, setInstallDirError] = useState('');
  const [dataDirError, setDataDirError] = useState('');

  // Port check state
  const [portCheckLoading, setPortCheckLoading] = useState(false);
  const [portCheckResults, setPortCheckResults] = useState<{host: string; port: number; free: boolean; message: string}[]>([]);
  const [portCheckDone, setPortCheckDone] = useState(false);

  useEffect(() => {
    fetch('/api/v1/ui/hosts')
      .then(res => res.json())
      .then(data => setHosts(data.map((h: any) => ({ ...h, ip_address: h.ipAddress || h.ipAddresses || '127.0.0.1' }))))
      .catch(() => setHosts([]));

    setVersionsLoading(true);
    fetch('/api/v1/artifacts?serviceType=KAFKA')
      .then(res => res.json())
      .then(data => {
        const mapped = (data.content || []).map((a: any) => ({
          version: a.version,
          available: a.status === 'AVAILABLE',
          scala_version: a.attributes?.scala_version || '2.13',
          release_date: a.attributes?.release_date || new Date(a.createdAt).toLocaleDateString(),
          size_mb: parseFloat((a.fileSizeBytes / 1024 / 1024).toFixed(1)),
          filename: a.fileName,
          features: a.attributes?.features || [],
          id: a.id,
        }));
        setVersions(mapped);
        const avail = mapped.filter((v: any) => v.available);
        if (avail.length > 0) setKafkaVersion(v => v || avail[0].version);
        else if (mapped.length > 0) setKafkaVersion(v => v || mapped[0].version);
      })
      .catch(() => setVersions([]))
      .finally(() => setVersionsLoading(false));
  }, []);

  useEffect(() => {
    if (kafkaVersion && getMajorVersion(kafkaVersion) >= 4 && mode === 'zookeeper') setMode('kraft');
  }, [kafkaVersion, mode]);

  useEffect(() => {
    if (config.listener_port < 1024) setPortError('Ports below 1024 require root access');
    else if (config.listener_port > 65535) setPortError('Port must be between 1024 and 65535');
    else setPortError('');
  }, [config.listener_port]);

  useEffect(() => { setInstallDirError(validateDeployPath(config.kafka_install_dir || '', 'Install Directory')); }, [config.kafka_install_dir]);
  useEffect(() => { setDataDirError(validateDeployPath(config.kafka_data_dir || '', 'Data Directory')); }, [config.kafka_data_dir]);

  const handleAssign = (hostId: string, role: string) => {
    setAssignments(prev => {
      const current = prev[hostId] || [];
      if (current.includes(role)) {
        const next = current.filter(r => r !== role);
        if (next.length === 0) { const copy = { ...prev }; delete copy[hostId]; return copy; }
        return { ...prev, [hostId]: next };
      } else {
        const exclusions = EXCLUSIVE_GROUPS[role] || [];
        const filtered = current.filter(r => !exclusions.includes(r));
        return { ...prev, [hostId]: [...filtered, role] };
      }
    });
  };

  const buildServices = (): ServiceAssignment[] => {
    let brokerId = 1, controllerId = 101, otherId = 201;
    const svcs: ServiceAssignment[] = [];
    for (const [hostId, roles] of Object.entries(assignments)) {
      for (const role of roles) {
        let nid: number;
        if (role === 'controller') nid = controllerId++;
        else if (role === 'broker_controller' || role === 'broker') nid = brokerId++;
        else nid = otherId++;
        svcs.push({ host_id: hostId, role, node_id: nid });
      }
    }
    return svcs;
  };

  const handleCreate = async () => {
    setLoading(true);
    try {
      if (mode === 'EXTERNAL') {
        const payload = {
          name,
          kafkaVersion: kafkaVersion || 'Unknown',
          environment: environment.trim().toLowerCase(),
          bootstrapServers: bootstrapServers.trim(),
        };
        const response = await fetch('/api/v1/ui/clusters/external', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload),
        });
        if (response.ok) {
          alert('External cluster connected successfully!');
          navigate('/clusters');
        } else alert('Failed to connect external cluster.');
        return;
      }

      const selectedArtifact = versions.find(v => v.version === kafkaVersion);
      const payload = {
        name,
        kafka_version: kafkaVersion,
        mode,
        services: buildServices(),
        config: {
          ...config,
          kafka_install_dir: config.kafka_install_dir?.trim() || undefined,
          kafka_data_dir: config.kafka_data_dir?.trim() || undefined,
        },
        environment: environment.trim().toLowerCase(),
        artifactUrl: selectedArtifact ? `http://${window.location.hostname}:8081/api/v1/artifacts/${selectedArtifact.id}/download` : '',
      };

      const response = await fetch('/api/v1/ui/clusters/deploy', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });

      if (response.ok) {
        alert('Deployment initialized successfully!');
        navigate('/clusters');
      } else {
        alert('Deployment failed.');
      }
    } catch (e) {
      console.error(e);
      alert('Deployment error.');
    } finally {
      setLoading(false);
    }
  };

  const assignedRoles = Object.values(assignments).flat();
  const hasBroker = assignedRoles.some(r => r === 'broker' || r === 'broker_controller');
  const availableVersions = versions.filter(v => v.available);
  const selectedVersion = versions.find(v => v.version === kafkaVersion);
  const isKafka4Plus = kafkaVersion ? getMajorVersion(kafkaVersion) >= 4 : false;
  const brokerCount = assignedRoles.filter(r => r === 'broker' || r === 'broker_controller').length;
  const rfExceedsBrokers = config.replication_factor > brokerCount && brokerCount > 0;
  const pathsValid = !installDirError && !dataDirError;
  const step3Valid = !rfExceedsBrokers && pathsValid;

  const availableRoles = ROLES.filter(r => {
    if (mode === 'kraft') return r.id !== 'zookeeper';
    return r.id !== 'controller' && r.id !== 'broker_controller';
  });

  const getSteps = () => {
    const s1 = {
      title: 'Cluster Basics',
      content: (
        <div className="wz-space-y">
          <div>
            <label className="wz-label">Cluster Name</label>
            <input type="text" value={name} onChange={e => setName(e.target.value)} placeholder="my-kafka-cluster" className="wz-input" />
          </div>

          <div>
            <label className="wz-label">Kafka Version</label>
            {versionsLoading ? (
              <div className="wz-loading"><Loader2 size={14} className="wz-spin" /> Loading available versions...</div>
            ) : versions.length === 0 ? (
              <div className="wz-error-text">No versions found. Upload a Kafka binary on the <a href="/artifacts" className="wz-no-versions-link">Artifacts</a> page.</div>
            ) : (
              <>
                <select value={kafkaVersion} onChange={e => setKafkaVersion(e.target.value)} className="wz-select">
                  {availableVersions.length > 0 && (
                    <optgroup label="Available (downloaded)">
                      {availableVersions.map(v => (
                        <option key={v.version} value={v.version}>{v.version} ({v.size_mb} MB){v.release_date ? ` - Released ${v.release_date}` : ''}</option>
                      ))}
                    </optgroup>
                  )}
                  {versions.filter(v => !v.available).length > 0 && (
                    <optgroup label="Not Downloaded (upload required)">
                      {versions.filter(v => !v.available).map(v => (
                        <option key={v.version} value={v.version} disabled>{v.version} - Not available</option>
                      ))}
                    </optgroup>
                  )}
                </select>
              </>
            )}
          </div>

          <div>
            <label className="wz-label">Environment <span className="wz-label-optional">(optional)</span></label>
            <div className="wz-env-row">
              {['dev', 'qa', 'staging', 'prod'].map(e => (
                <button key={e} type="button" onClick={() => setEnvironment(e === environment ? '' : e)} className={`wz-env-btn ${environment === e ? 'selected' : ''}`}>{e}</button>
              ))}
              <input value={environment} onChange={e => setEnvironment(e.target.value)} placeholder="custom tag" className="wz-env-input" />
            </div>
          </div>

          <div>
            <label className="wz-label">Cluster Mode</label>
            <div className="wz-mode-grid">
              <button onClick={() => setMode('kraft')} className={`wz-mode-card ${mode === 'kraft' ? 'active' : ''}`}>
                <div className="wz-mode-title">KRaft Deployment</div>
                <div className="wz-mode-desc">Recommended. We will provision and install Kafka binaries to your managed hosts.</div>
              </button>
              <button onClick={() => setMode('EXTERNAL')} className={`wz-mode-card ${mode === 'EXTERNAL' ? 'active' : ''}`}>
                <div className="wz-mode-title">External Cluster</div>
                <div className="wz-mode-desc">No deployment. Simply provide connection details for an existing cluster to monitor and manage it.</div>
              </button>
            </div>
          </div>
        </div>
      ),
      valid: name.trim().length > 0 && kafkaVersion.length > 0,
    };

    if (mode === 'EXTERNAL') {
      return [s1, {
        title: 'Connection',
        content: (
          <div className="wz-space-y">
            <div>
              <label className="wz-label">Bootstrap Servers</label>
              <input type="text" value={bootstrapServers} onChange={e => setBootstrapServers(e.target.value)} placeholder="broker1.example.com:9092,broker2.example.com:9092" className="wz-input mono" />
              <p className="wz-hint">Comma separated list of broker addresses.</p>
            </div>
          </div>
        ),
        valid: bootstrapServers.trim().length > 0
      }];
    }

    return [s1,
      {
        title: 'Assign Roles',
        content: (
          <div>
            {hosts.length === 0 ? (
              <div className="wz-empty-text">No hosts available. <a href="/hosts" className="wz-no-versions-link">Add hosts first</a>.</div>
            ) : (
              <div className="wz-space-y">
                <p className="wz-role-info-text">
                  Assign one or more roles to each host.
                </p>
                <div className="wz-host-list">
                  {hosts.map(host => {
                    const hostRoles = assignments[host.id] || [];
                    const isOffline = host.status !== 'ONLINE' && host.status !== 'online';
                    return (
                      <div key={host.id} className={`wz-host-card ${isOffline ? 'offline' : ''}`}>
                        <div className="wz-host-header">
                          <Server size={16} className="wz-host-icon" />
                          <span className="wz-host-name">{host.hostname}</span>
                          <span className="wz-host-ip">{host.ip_address}</span>
                          {hostRoles.length > 0 && <span className="wz-host-role-count">{hostRoles.length} role{hostRoles.length > 1 ? 's' : ''}</span>}
                          <span className={`wz-host-status ${isOffline ? 'offline' : 'online'}`}>{host.status}</span>
                        </div>
                        <div className="wz-roles-row">
                          {availableRoles.map(role => (
                            <button
                              key={role.id}
                              onClick={() => handleAssign(host.id, role.id)}
                              title={role.description}
                              className={`wz-role-btn ${hostRoles.includes(role.id) ? `assigned ${role.id}` : ''}`}
                            >
                              {role.label}
                            </button>
                          ))}
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            )}
          </div>
        ),
        valid: hasBroker,
      },
      {
        title: 'Configuration',
        content: (
          <div className="wz-space-y">
            <div className="wz-grid-2">
              <div>
                <label className="wz-label">Replication Factor</label>
                <input type="number" min={1} max={10} value={config.replication_factor} onChange={e => setConfig({ ...config, replication_factor: Number(e.target.value) })} className={`wz-input ${rfExceedsBrokers ? 'error' : ''}`} />
                {rfExceedsBrokers && <p className="wz-error-text"><AlertTriangle size={12} /> RF ({config.replication_factor}) exceeds broker count ({brokerCount}).</p>}
              </div>
              <div>
                <label className="wz-label">Default Partitions</label>
                <input type="number" min={1} max={100} value={config.num_partitions} onChange={e => setConfig({ ...config, num_partitions: Number(e.target.value) })} className="wz-input" />
              </div>
            </div>
            
            <div className="wz-grid-2">
              <div>
                <label className="wz-label">JVM Heap Size</label>
                <input type="text" value={config.heap_size} onChange={e => setConfig({ ...config, heap_size: e.target.value })} placeholder="e.g. 1G, 512M" className="wz-input" />
                <p className="wz-hint">Memory allocated to the Kafka JVM.</p>
              </div>
              <div>
                <label className="wz-label">Log Directory</label>
                <input type="text" value={config.log_dirs} onChange={e => setConfig({ ...config, log_dirs: e.target.value })} placeholder="/var/lib/kafka/data" className="wz-input mono" />
                <p className="wz-hint">Primary directory for topic partition data.</p>
              </div>
            </div>

            <div className="wz-grid-2">
              <div>
                <label className="wz-label">Listener Port (Client/Broker)</label>
                <input type="number" value={config.listener_port} onChange={e => setConfig({ ...config, listener_port: Number(e.target.value) })} className={`wz-input ${portError ? 'error' : ''}`} />
                {portError && <p className="wz-error-text"><AlertTriangle size={12} /> {portError}</p>}
              </div>
              {mode === 'kraft' && (
                <div>
                  <label className="wz-label">Controller Port (Quorum)</label>
                  <input type="number" value={config.controller_port} onChange={e => setConfig({ ...config, controller_port: Number(e.target.value) })} className="wz-input" />
                </div>
              )}
            </div>

            <div className="wz-section-divider"></div>
            <h4 className="wz-section-title"><Wifi size={16} /> Port Availability Check</h4>
            
            <div className="wz-port-check">
              <div className="wz-port-check-header">
                <div>
                  <div className="wz-port-check-title">Check if ports are free on assigned hosts</div>
                  <div className="wz-port-check-desc">Tests listener port ({config.listener_port}){mode === 'kraft' ? ` and controller port (${config.controller_port})` : ''} on each host.</div>
                </div>
                <button
                  type="button"
                  className="wz-port-check-btn"
                  disabled={portCheckLoading || Object.keys(assignments).length === 0}
                  onClick={async () => {
                    setPortCheckLoading(true);
                    setPortCheckResults([]);
                    setPortCheckDone(false);
                    const results: {host: string; port: number; free: boolean; message: string}[] = [];
                    const hostIds = Object.keys(assignments);
                    const ports = [config.listener_port];
                    if (mode === 'kraft') ports.push(config.controller_port);
                    for (const hostId of hostIds) {
                      for (const p of ports) {
                        try {
                          const res = await fetch(`/api/v1/ui/hosts/${hostId}/check-port/${p}`);
                          if (res.ok) {
                            const data = await res.json();
                            results.push({ host: data.host, port: data.port, free: data.free, message: data.message });
                          } else {
                            const h = hosts.find(h => h.id === hostId);
                            results.push({ host: h?.hostname || hostId, port: p, free: false, message: `Failed to reach host` });
                          }
                        } catch {
                          const h = hosts.find(h => h.id === hostId);
                          results.push({ host: h?.hostname || hostId, port: p, free: false, message: `Connection error` });
                        }
                      }
                    }
                    setPortCheckResults(results);
                    setPortCheckDone(true);
                    setPortCheckLoading(false);
                  }}
                >
                  {portCheckLoading ? <><Loader2 size={14} className="wz-spin" /> Checking...</> : <><Wifi size={14} /> Check Ports</>}
                </button>
              </div>

              {portCheckDone && portCheckResults.length > 0 && (
                <div style={{ marginTop: '0.75rem', display: 'flex', flexDirection: 'column', gap: '0.4rem' }}>
                  {portCheckResults.map((r, i) => (
                    <div key={i} className={r.free ? 'wz-port-ok' : 'wz-error-text'} style={{ fontSize: '0.8rem' }}>
                      {r.free ? <CheckCircle size={14} /> : <XCircle size={14} />}
                      <span><strong>{r.host}</strong>:{r.port} — {r.message}</span>
                    </div>
                  ))}
                </div>
              )}

              {Object.keys(assignments).length === 0 && (
                <p className="wz-hint" style={{ marginTop: '0.5rem' }}>Assign hosts in the previous step first.</p>
              )}
            </div>

            <div className="wz-section-divider"></div>
            <h4 className="wz-section-title"><FolderOpen size={16} /> Deployment Paths <span className="wz-label-optional">(optional overrides)</span></h4>
            
            <div className="wz-grid-2">
              <div>
                <label className="wz-label">Install Directory</label>
                <input type="text" value={config.kafka_install_dir} onChange={e => setConfig({ ...config, kafka_install_dir: e.target.value })} placeholder="Default: /opt/tantor/kafka" className={`wz-input mono ${installDirError ? 'error' : ''}`} />
                {installDirError && <p className="wz-error-text"><AlertTriangle size={12} /> {installDirError}</p>}
              </div>
              <div>
                <label className="wz-label">Data Directory Override</label>
                <input type="text" value={config.kafka_data_dir} onChange={e => setConfig({ ...config, kafka_data_dir: e.target.value })} placeholder="Default: /var/lib/kafka" className={`wz-input mono ${dataDirError ? 'error' : ''}`} />
                {dataDirError && <p className="wz-error-text"><AlertTriangle size={12} /> {dataDirError}</p>}
              </div>
            </div>
          </div>
        ),
        valid: step3Valid,
      },
      {
        title: 'Review',
        content: (
          <div className="wz-space-y">
            <div className="wz-review-panel">
              <h3 className="wz-review-title">Cluster Summary</h3>
              <div className="wz-review-grid">
                <span className="wz-review-label">Name</span>
                <span className="wz-review-value">{name}</span>
                <span className="wz-review-label">Mode</span>
                <span className="wz-review-value">{mode.toUpperCase()}</span>
                <span className="wz-review-label">Kafka Version</span>
                <span className="wz-review-value">{kafkaVersion}</span>
                {environment && <><span className="wz-review-label">Environment</span><span className="wz-review-value">{environment}</span></>}
              </div>

              <div className="wz-section-divider"></div>
              <h4 className="wz-section-title"><Server size={16} /> Topology</h4>
              <div className="wz-review-grid">
                <span className="wz-review-label">Brokers</span>
                <span className="wz-review-value">{brokerCount} assigned</span>
                {mode === 'kraft' && (
                  <>
                    <span className="wz-review-label">Controllers</span>
                    <span className="wz-review-value">{assignedRoles.filter(r => r === 'controller' || r === 'broker_controller').length} assigned</span>
                  </>
                )}
              </div>

              <div className="wz-section-divider"></div>
              <h4 className="wz-section-title"><Network size={16} /> Configuration & Paths</h4>
              <div className="wz-review-grid">
                <span className="wz-review-label">Replication Factor</span>
                <span className="wz-review-value">{config.replication_factor}</span>
                <span className="wz-review-label">Default Partitions</span>
                <span className="wz-review-value">{config.num_partitions}</span>
                <span className="wz-review-label">JVM Heap</span>
                <span className="wz-review-value">{config.heap_size}</span>
                <span className="wz-review-label">Log Directory</span>
                <span className="wz-review-value">{config.log_dirs}</span>
                <span className="wz-review-label">Listener Port</span>
                <span className="wz-review-value">{config.listener_port}</span>
                {mode === 'kraft' && (
                  <>
                    <span className="wz-review-label">Controller Port</span>
                    <span className="wz-review-value">{config.controller_port}</span>
                  </>
                )}
                {config.kafka_install_dir && (
                  <>
                    <span className="wz-review-label">Install Dir Override</span>
                    <span className="wz-review-value">{config.kafka_install_dir}</span>
                  </>
                )}
                {config.kafka_data_dir && (
                  <>
                    <span className="wz-review-label">Data Dir Override</span>
                    <span className="wz-review-value">{config.kafka_data_dir}</span>
                  </>
                )}
              </div>
            </div>
          </div>
        ),
        valid: true,
      }
    ];
  };

  const steps = getSteps();

  return (
    <div className="wizard-container animate-fade-in">
      <div className="wizard-steps">
        {steps.map((s, i) => (
          <div key={i} className="wizard-step-group">
            <button
              onClick={() => i < step && setStep(i)}
              className={`wizard-step-btn ${i === step ? 'active' : i < step ? 'completed' : 'upcoming'}`}
            >
              {i < step ? <Check size={14} /> : <span style={{ width: 20, textAlign: 'center', display: 'inline-block' }}>{i + 1}</span>}
              {s.title}
            </button>
            {i < steps.length - 1 && <ChevronRight size={16} className="wizard-step-chevron" />}
          </div>
        ))}
      </div>

      <div className="wizard-content-card">
        {steps[step].content}
      </div>

      <div className="wizard-nav">
        <button onClick={() => setStep(s => s - 1)} disabled={step === 0} className="wizard-btn-back">
          <ChevronLeft size={16} /> Back
        </button>
        {step < steps.length - 1 ? (
          <div className="wizard-nav-right">
            <button onClick={() => setStep(s => s + 1)} disabled={!steps[step].valid} className="wizard-btn-next">
              Next <ChevronRight size={16} />
            </button>
          </div>
        ) : (
          <button onClick={handleCreate} disabled={loading || !steps[step].valid} className="wizard-btn-create">
            {loading ? <><Loader2 size={14} className="wz-spin" /> {mode === 'EXTERNAL' ? 'Connecting...' : 'Creating...'}</> : (mode === 'EXTERNAL' ? 'Connect Cluster' : 'Create Cluster')}
          </button>
        )}
      </div>
    </div>
  );
}
