import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronRight, ChevronLeft, Check, Server, Loader2, AlertTriangle, FolderOpen, Info } from 'lucide-react';
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
  { id: 'ksqldb', label: 'ksqlDB', description: 'Stream processing SQL engine' },
  { id: 'kafka_connect', label: 'Kafka Connect', description: 'Data integration framework' },
  { id: 'zookeeper', label: 'ZooKeeper', description: 'Legacy consensus (Kafka < 4.0 only)' },
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
  const [mode, setMode] = useState<'kraft' | 'zookeeper'>('kraft');
  const [environment, setEnvironment] = useState('');

  // Step 2
  const [assignments, setAssignments] = useState<Record<string, string[]>>({});

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
    if (kafkaVersion && getMajorVersion(kafkaVersion) >= 4) setMode('kraft');
  }, [kafkaVersion]);

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
        alert('Deployment initialized successfully! The agent will start setting up Kafka on the selected nodes.');
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

  const steps = [
    // ─── Step 1: Cluster Basics ────────────────────────────────────
    {
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
                {selectedVersion?.features && selectedVersion.features.length > 0 && (
                  <div className="wz-version-features"><strong>Features:</strong> {selectedVersion.features.slice(0, 3).join(', ')}</div>
                )}
              </>
            )}
          </div>

          <div>
            <label className="wz-label">Environment <span className="wz-label-optional">(optional)</span></label>
            <div className="wz-env-row">
              {['dev', 'qa', 'staging', 'prod'].map(e => (
                <button key={e} type="button" onClick={() => setEnvironment(e === environment ? '' : e)} className={`wz-env-btn ${environment === e ? 'selected' : ''}`}>{e}</button>
              ))}
              <input value={environment} onChange={e => setEnvironment(e.target.value)} placeholder="custom tag (e.g. us-east-1)" className="wz-env-input" />
            </div>
            <p className="wz-hint">Used for filtering on the Clusters list. Lowercased.</p>
          </div>

          <div>
            <label className="wz-label">Consensus Mode</label>
            <div className="wz-mode-grid">
              <button onClick={() => setMode('kraft')} className={`wz-mode-card ${mode === 'kraft' ? 'active' : ''}`}>
                <div className="wz-mode-title">KRaft</div>
                <div className="wz-mode-desc">Recommended. Built-in Raft consensus, no ZooKeeper needed.</div>
              </button>
              <button onClick={() => !isKafka4Plus && setMode('zookeeper')} disabled={isKafka4Plus} className={`wz-mode-card ${mode === 'zookeeper' ? 'active' : ''}`}>
                <div className="wz-mode-title">ZooKeeper</div>
                <div className="wz-mode-desc">{isKafka4Plus ? 'Not available for Kafka 4.x+.' : 'Legacy mode. Requires separate ZooKeeper ensemble.'}</div>
              </button>
            </div>
          </div>
        </div>
      ),
      valid: name.trim().length > 0 && kafkaVersion.length > 0,
    },

    // ─── Step 2: Assign Roles ──────────────────────────────────────
    {
      title: 'Assign Roles',
      content: (
        <div>
          {hosts.length === 0 ? (
            <div className="wz-empty-text">No hosts available. <a href="/hosts" className="wz-no-versions-link">Add hosts first</a>.</div>
          ) : (
            <div className="wz-space-y">
              <p className="wz-role-info-text">
                Assign one or more roles to each host. A single host can run multiple services.
                {mode === 'kraft' && <span className="wz-role-info-sub">Node IDs: Brokers start at 1, standalone Controllers at 101.</span>}
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

    // ─── Step 3: Configuration ─────────────────────────────────────
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
            <div>
              <label className="wz-label">Broker Port</label>
              <input type="number" value={config.listener_port} onChange={e => setConfig({ ...config, listener_port: Number(e.target.value) })} className={`wz-input ${portError ? 'error' : ''}`} />
              {portError && <p className="wz-warn-text"><AlertTriangle size={12} /> {portError}</p>}
            </div>
            <div>
              <label className="wz-label">{mode === 'zookeeper' ? 'ZooKeeper Port' : 'Controller Port'}</label>
              <input type="number" value={config.controller_port} onChange={e => setConfig({ ...config, controller_port: Number(e.target.value) })} className="wz-input" />
              <p className="wz-hint">{mode === 'zookeeper' ? 'Default: 2181' : 'Default: 9093'}</p>
            </div>
            <div>
              <label className="wz-label">Heap Size</label>
              <select value={config.heap_size} onChange={e => setConfig({ ...config, heap_size: e.target.value })} className="wz-select">
                <option value="512M">512 MB</option>
                <option value="1G">1 GB</option>
                <option value="2G">2 GB</option>
                <option value="4G">4 GB</option>
                <option value="6G">6 GB</option>
              </select>
            </div>
          </div>

          {/* Deploy Paths */}
          <div className="wz-section-panel">
            <div className="wz-section-header">
              <FolderOpen size={15} className="wz-section-header-icon" />
              <span className="wz-section-title">Deploy Paths</span>
              <span className="wz-section-subtitle">(optional — leave blank for auto)</span>
            </div>
            <div className="wz-section-body">
              <div className="wz-info-banner">
                <Info size={13} className="wz-banner-icon" />
                <span>
                  By default Tantor installs to <code>/opt/tantor/kafka</code>. Override when your infrastructure requires specific mount points
                  (e.g. a dedicated disk at <code>/data/kafka</code>). Must be an absolute path.
                </span>
              </div>
              <div>
                <label className="wz-label">Log Directory</label>
                <input type="text" value={config.log_dirs} onChange={e => setConfig({ ...config, log_dirs: e.target.value })} className="wz-input mono" />
                <p className="wz-hint">Kafka log.dirs — where message log segments are stored.</p>
              </div>
              <div className="wz-grid-2">
                <div>
                  <label className="wz-label">Install Directory</label>
                  <input type="text" value={config.kafka_install_dir || ''} onChange={e => setConfig({ ...config, kafka_install_dir: e.target.value })} placeholder="/opt/tantor/kafka" className={`wz-input mono ${installDirError ? 'error' : ''}`} />
                  {installDirError ? <p className="wz-error-text"><AlertTriangle size={11} /> {installDirError}</p> : <p className="wz-hint">Where Kafka binaries are extracted on broker hosts.</p>}
                </div>
                <div>
                  <label className="wz-label">Data Directory</label>
                  <input type="text" value={config.kafka_data_dir || ''} onChange={e => setConfig({ ...config, kafka_data_dir: e.target.value })} placeholder="/var/lib/kafka/data" className={`wz-input mono ${dataDirError ? 'error' : ''}`} />
                  {dataDirError ? <p className="wz-error-text"><AlertTriangle size={11} /> {dataDirError}</p> : <p className="wz-hint">Where Kafka log segments and KRaft metadata are persisted.</p>}
                </div>
              </div>
            </div>
          </div>
        </div>
      ),
      valid: step3Valid,
    },

    // ─── Step 4: Review & Create ───────────────────────────────────
    {
      title: 'Review & Create',
      content: (
        <div className="wz-space-y">
          {(rfExceedsBrokers) && (
            <div className="wz-danger-banner">
              <AlertTriangle size={16} className="wz-banner-icon" />
              <span>Replication factor ({config.replication_factor}) exceeds broker count ({brokerCount}). Deployment will fail.</span>
            </div>
          )}

          <div className="wz-review-panel">
            <h3 className="wz-review-title">Cluster Summary</h3>
            <div className="wz-review-grid">
              <span className="wz-review-label">Name</span>
              <span className="wz-review-value">{name}</span>
              <span className="wz-review-label">Kafka Version</span>
              <span className="wz-review-value">{kafkaVersion}</span>
              <span className="wz-review-label">Mode</span>
              <span className="wz-review-value">{mode.toUpperCase()}</span>
              {environment && <><span className="wz-review-label">Environment</span><span className="wz-review-value">{environment}</span></>}
              <span className="wz-review-label">Replication Factor</span>
              <span className="wz-review-value">{config.replication_factor}</span>
              <span className="wz-review-label">Default Partitions</span>
              <span className="wz-review-value">{config.num_partitions}</span>
              <span className="wz-review-label">Broker Port</span>
              <span className="wz-review-value mono">{config.listener_port}</span>
              <span className="wz-review-label">Controller Port</span>
              <span className="wz-review-value mono">{config.controller_port}</span>
              <span className="wz-review-label">Heap Size</span>
              <span className="wz-review-value">{config.heap_size}</span>
            </div>
          </div>

          <div className="wz-review-panel">
            <h3 className="wz-review-title"><FolderOpen size={14} /> Deploy Paths</h3>
            <div className="wz-review-grid">
              <span className="wz-review-label">Install Directory</span>
              <span className={`wz-review-value ${config.kafka_install_dir?.trim() ? 'mono' : 'auto'}`}>
                {config.kafka_install_dir?.trim() || 'auto — /opt/tantor/kafka'}
              </span>
              <span className="wz-review-label">Data Directory</span>
              <span className={`wz-review-value ${config.kafka_data_dir?.trim() ? 'mono' : 'auto'}`}>
                {config.kafka_data_dir?.trim() || 'auto — /opt/tantor/kafka/data'}
              </span>
              <span className="wz-review-label">Log Directory</span>
              <span className="wz-review-value mono">{config.log_dirs}</span>
            </div>
          </div>

          <div className="wz-review-panel">
            <h3 className="wz-review-title">Service Assignments</h3>
            <div className="wz-svc-list">
              {buildServices().map((svc, i) => {
                const host = hosts.find(h => h.id === svc.host_id);
                const roleInfo = ROLES.find(r => r.id === svc.role);
                const isOffline = host && host.status !== 'ONLINE' && host.status !== 'online';
                return (
                  <div key={i} className="wz-svc-row">
                    <span className={`wz-svc-badge ${svc.role}`}>{roleInfo?.label}</span>
                    <span className="wz-svc-node-id">ID: {svc.node_id}</span>
                    <span className="wz-svc-host">{host?.hostname}</span>
                    <span className="wz-svc-ip">({host?.ip_address})</span>
                    {isOffline && <span className="wz-svc-offline">OFFLINE</span>}
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      ),
      valid: !rfExceedsBrokers,
    },
  ];

  return (
    <div className="wizard-container animate-fade-in">
      {/* Step indicators */}
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

      {/* Current step content */}
      <div className="wizard-content-card">
        {steps[step].content}
      </div>

      {/* Navigation */}
      <div className="wizard-nav">
        <button onClick={() => setStep(s => s - 1)} disabled={step === 0} className="wizard-btn-back">
          <ChevronLeft size={16} /> Back
        </button>
        {step < steps.length - 1 ? (
          <div className="wizard-nav-right">
            {!steps[step].valid && step === 2 && (
              <span className="wizard-validation-msg">
                {rfExceedsBrokers ? `RF (${config.replication_factor}) exceeds broker count (${brokerCount}).` : installDirError || dataDirError || 'Fix errors above'}
              </span>
            )}
            <button onClick={() => setStep(s => s + 1)} disabled={!steps[step].valid} className="wizard-btn-next">
              Next <ChevronRight size={16} />
            </button>
          </div>
        ) : (
          <button onClick={handleCreate} disabled={loading || rfExceedsBrokers} className="wizard-btn-create">
            {loading ? <><Loader2 size={14} className="wz-spin" /> Creating...</> : 'Create Cluster'}
          </button>
        )}
      </div>
    </div>
  );
}
