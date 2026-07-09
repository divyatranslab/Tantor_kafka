import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  AlertTriangle,
  Check,
  CheckCircle2,
  ChevronDown,
  Database,
  FileText,
  Upload,
  Loader2,
  Network,
  Play,
  RefreshCw,
  Search,
  Server,
  Settings2,
  X,
  XCircle,
} from 'lucide-react';
import { AgentConnectivityModal } from '../components/AgentConnectivityModal';
import './ClusterDeployment.css';

type Host = {
  id: string;
  hostname: string;
  status: string;
  available?: boolean;
  availabilityReason?: string;
  clusterId?: string;
  clusterName?: string;
  ipAddresses?: string;
  ipAddress?: string;
  ip_address?: string;
  agentType?: 'HOST' | 'KAFKA_DISCOVERY' | string;
  deployable?: boolean;
};

type ClusterHost = {
  hostId?: string;
  role?: string;
  nodeId?: number;
};

type ExistingCluster = {
  id: string;
  name: string;
  kafkaVersion: string;
  mode: string;
  environment?: string;
  kafkaClusterId?: string;
  config?: Record<string, any>;
  hosts?: ClusterHost[];
};

type KafkaVersionInfo = {
  version: string;
  available: boolean;
  scala_version: string;
  release_date: string;
  size_mb: number;
  filename: string;
  id?: string;
};

type DeploymentMode = 'kraft' | 'zookeeper';
type RoleChoice = 'broker_controller' | 'broker' | 'controller' | 'separate' | 'broker_zookeeper' | 'zookeeper';
type FlowStage = 'landing' | 'details' | 'preview';
type ConfigMode = 'default' | 'custom';
type ConfigKind = 'server' | 'broker' | 'controller' | 'zookeeper';
type PrereqStatus = 'IDLE' | 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'REBOOT_REQUIRED';

type ServiceAssignment = {
  host_id: string;
  role: RoleChoice;
  node_id: number;
  configuration_mode: ConfigMode;
  properties_template: string;
  heap_size: string;
};

type PropertyRow = {
  key: string;
  value: string;
  required?: boolean;
  locked?: boolean;
};

type NodeConfigState = {
  mode: ConfigMode;
  rows: PropertyRow[];
  heapSize: string;
};

type PrereqResult = {
  status: PrereqStatus;
  taskId?: string;
  logOutput: string;
  errorMsg: string;
};

function PrerequisiteLog({ result }: { result: PrereqResult }) {
  const lines = [result.errorMsg, result.logOutput].filter(Boolean).join('\n\n').split('\n');
  return <div className="cd-prereq-log">
    {lines.map((line, index) => {
      const tone = line.startsWith('[PASS]') ? 'pass'
        : line.startsWith('[FAIL]') || line.toLowerCase().includes('gate failed') ? 'fail'
          : line.startsWith('[WARN]') ? 'warn' : 'neutral';
      return <span className={tone} key={`${index}-${line}`}>{line || '\u00a0'}</span>;
    })}
  </div>;
}

type KraftValidationNode = {
  hostId: string;
  address: string;
  nodeId: number;
  role: string;
};

type KraftValidationReport = {
  valid: boolean;
  errors: string[];
  warnings: string[];
  acknowledgementRequired: boolean;
  clusterId: string;
  quorumMode: 'static' | 'dynamic';
  controllerCount: number;
  brokerCount: number;
  failureTolerance: number;
  controllerQuorum: string;
  nodes: KraftValidationNode[];
  generatedConfig: Record<string, string>;
};

const UI_ONLY_PROPERTY_KEYS = new Set(['node.host', 'advertised.host', 'controller.host', 'zookeeper.host']);

const KRAFT_ROLE_OPTIONS: Array<{ id: RoleChoice; label: string; detail: string }> = [
  {
    id: 'broker_controller',
    label: 'Broker + Controller',
    detail: 'One combined Kafka process using server.properties.',
  },
  {
    id: 'broker',
    label: 'Broker',
    detail: 'Broker process only using broker.properties.',
  },
  {
    id: 'controller',
    label: 'Controller',
    detail: 'Controller process only using controller.properties.',
  },
  {
    id: 'separate',
    label: 'Broker and Controller',
    detail: 'Two JVMs on the same VM using broker.properties and controller.properties.',
  },
];

const ZOOKEEPER_ROLE_OPTIONS: Array<{ id: RoleChoice; label: string; detail: string }> = [
  {
    id: 'broker_zookeeper',
    label: 'Broker + ZooKeeper',
    detail: 'Two JVM services on one VM using server.properties and zookeeper.properties.',
  },
  {
    id: 'broker',
    label: 'Broker',
    detail: 'Broker process only using server.properties.',
  },
  {
    id: 'zookeeper',
    label: 'ZooKeeper',
    detail: 'ZooKeeper process only using zookeeper.properties.',
  },
];

const KRAFT_COMMON_CONFIG_KINDS: ConfigKind[] = ['server', 'broker', 'controller'];
const ZOOKEEPER_COMMON_CONFIG_KINDS: ConfigKind[] = ['server', 'zookeeper'];
const SYNCED_BROKER_PROPERTY_KEYS = new Set([
  'num.partitions',
  'default.replication.factor',
  'min.insync.replicas',
  'offsets.topic.replication.factor',
  'transaction.state.log.replication.factor',
  'transaction.state.log.min.isr',
]);

function defaultCommonRows(kind: ConfigKind, mode: DeploymentMode): PropertyRow[] {
  if (mode === 'zookeeper' && kind === 'zookeeper') {
    return [
      { key: 'tickTime', value: '2000' },
      { key: 'initLimit', value: '5' },
      { key: 'syncLimit', value: '2' },
      { key: 'maxClientCnxns', value: '0' },
      { key: 'admin.enableServer', value: 'false' },
      { key: 'autopurge.purgeInterval', value: '1' },
      { key: 'autopurge.snapRetainCount', value: '10' },
      { key: '4lw.commands.whitelist', value: '*' },
    ];
  }

  if (mode === 'zookeeper' && kind !== 'server') return [];

  if (kind === 'controller') {
    return [
      { key: 'controller.quorum.election.timeout.ms', value: '5000' },
      { key: 'controller.quorum.fetch.timeout.ms', value: '5000' },
      { key: 'controller.quorum.election.backoff.max.ms', value: '5000' },
      { key: 'controller.quorum.request.timeout.ms', value: '10000' },
      { key: 'metadata.log.segment.bytes', value: '1073741824' },
      { key: 'metadata.log.segment.ms', value: '604800000' },
      { key: 'metadata.max.retention.bytes', value: '-1' },
      { key: 'metadata.max.retention.ms', value: '604800000' },
      { key: 'num.network.threads', value: '8' },
      { key: 'num.io.threads', value: '16' },
      { key: 'socket.send.buffer.bytes', value: '102400' },
      { key: 'socket.receive.buffer.bytes', value: '102400' },
      { key: 'socket.request.max.bytes', value: '104857600' },
    ];
  }

  const brokerRows: PropertyRow[] = [
    { key: 'num.partitions', value: '1' },
    { key: 'auto.create.topics.enable', value: 'false' },
    { key: 'default.replication.factor', value: '', required: true },
    { key: 'min.insync.replicas', value: '', required: true },
    { key: 'offsets.topic.replication.factor', value: '3' },
    { key: 'offsets.topic.num.partitions', value: '50' },
    { key: 'transaction.state.log.replication.factor', value: '3' },
    { key: 'transaction.state.log.min.isr', value: '2' },
    { key: 'message.max.bytes', value: '15728640' },
    { key: 'replica.fetch.max.bytes', value: '15728640' },
    { key: 'fetch.message.max.bytes', value: '15728640' },
    { key: 'socket.request.max.bytes', value: '104857600' },
    { key: 'log.segment.bytes', value: '1073741824' },
    { key: 'log.retention.hours', value: '72' },
    { key: 'log.retention.check.interval.ms', value: '300000' },
    { key: 'num.replica.fetchers', value: '4' },
    { key: 'replica.lag.time.max.ms', value: '30000' },
    { key: 'num.network.threads', value: '8' },
    { key: 'num.io.threads', value: '8' },
    { key: 'socket.send.buffer.bytes', value: '102400' },
    { key: 'socket.receive.buffer.bytes', value: '102400' },
    { key: 'group.initial.rebalance.delay.ms', value: '0' },
    { key: 'broker.rack', value: 'rack1' },
  ];

  if (kind === 'broker') return brokerRows;

  if (mode === 'zookeeper') {
    return [
      ...brokerRows,
      { key: 'zookeeper.connection.timeout.ms', value: '40000' },
    ];
  }

  return [
    ...brokerRows,
    { key: 'controller.quorum.election.timeout.ms', value: '5000' },
    { key: 'controller.quorum.fetch.timeout.ms', value: '5000' },
    { key: 'controller.quorum.election.backoff.max.ms', value: '5000' },
    { key: 'controller.quorum.request.timeout.ms', value: '10000' },
    { key: 'metadata.log.segment.bytes', value: '1073741824' },
    { key: 'metadata.log.segment.ms', value: '604800000' },
    { key: 'metadata.max.retention.bytes', value: '-1' },
    { key: 'metadata.max.retention.ms', value: '604800000' },
  ];
}

function commonConfigKindsForMode(mode: DeploymentMode): ConfigKind[] {
  return mode === 'zookeeper' ? ZOOKEEPER_COMMON_CONFIG_KINDS : KRAFT_COMMON_CONFIG_KINDS;
}

function createCommonConfigs(mode: DeploymentMode): Record<ConfigKind, PropertyRow[]> {
  return {
    server: defaultCommonRows('server', mode),
    broker: defaultCommonRows('broker', mode),
    controller: defaultCommonRows('controller', mode),
    zookeeper: defaultCommonRows('zookeeper', mode),
  };
}

function kafkaMajorVersion(version: string): number {
  return Number.parseInt(String(version).split('.')[0] || '0', 10);
}

function setRowsValue(rows: PropertyRow[], key: string, value: string): PropertyRow[] {
  return rows.map(row => row.key === key ? { ...row, value } : row);
}

function syncCommonRows(rows: PropertyRow[], config: Record<string, any>): PropertyRow[] {
  return rows.map(row => {
    if (row.key === 'default.replication.factor' && config.replication_factor) return { ...row, value: String(config.replication_factor) };
    if (row.key === 'min.insync.replicas' && config.min_insync_replicas) return { ...row, value: String(config.min_insync_replicas) };
    if (row.key === 'num.partitions' && config.num_partitions) return { ...row, value: String(config.num_partitions) };
    return row;
  });
}

function parseIpList(raw: any): string[] {
  if (Array.isArray(raw)) return raw.map(String).map(ip => ip.trim()).filter(Boolean);
  if (typeof raw === 'string' && raw.startsWith('[')) {
    try {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) return parsed.map(String).map(ip => ip.trim()).filter(Boolean);
    } catch {}
  }
  if (typeof raw === 'string') return raw.split(',').map(ip => ip.trim()).filter(Boolean);
  return [];
}

function displayIp(host: Host): string {
  const ips = parseIpList(host.ip_address || host.ipAddress || host.ipAddresses);
  return ips.find(ip => ip.startsWith('192.168.'))
    || ips.find(ip => !ip.startsWith('127.') && !ip.startsWith('172.'))
    || ips[0]
    || 'Unknown';
}

function validatePath(value: string, label: string): string {
  if (!value.trim()) return `${label} is required.`;
  if (!value.trim().startsWith('/')) return `${label} must be an absolute Linux path.`;
  if (value.split('/').includes('..')) return `${label} cannot contain "..".`;
  if (!/^\/[A-Za-z0-9/_\-.]{1,510}$/.test(value.trim())) return `${label} contains unsupported characters.`;
  return '';
}

function serializeProperties(rows: PropertyRow[]): string {
  return rows
    .filter(row => row.key.trim() && !UI_ONLY_PROPERTY_KEYS.has(row.key.trim()) && String(row.value).trim())
    .map(row => `${row.key.trim()}=${row.value}`)
    .join('\n');
}

function activeStatus(status: string): boolean {
  return ['PENDING', 'IN_PROGRESS', 'RUNNING', 'QUEUED'].includes(String(status || '').toUpperCase());
}

export function ClusterDeployment() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const addClusterId = searchParams.get('mode') === 'add' ? searchParams.get('clusterId') : null;
  const isAddNodeMode = Boolean(addClusterId);
  const [stage, setStage] = useState<FlowStage>(isAddNodeMode ? 'details' : 'landing');
  const [hosts, setHosts] = useState<Host[]>([]);
  const [versions, setVersions] = useState<KafkaVersionInfo[]>([]);
  const [existingCluster, setExistingCluster] = useState<ExistingCluster | null>(null);
  const [loadingHosts, setLoadingHosts] = useState(true);
  const [loadingVersions, setLoadingVersions] = useState(true);
  const [loadingCluster, setLoadingCluster] = useState(false);

  const [clusterName, setClusterName] = useState('');
  const [kafkaVersion, setKafkaVersion] = useState('');
  const [environment, setEnvironment] = useState('DEV');
  const [clusterConfigMode, setClusterConfigMode] = useState<ConfigMode>('default');
  const [customImportSummary, setCustomImportSummary] = useState('');
  const [deploymentMode, setDeploymentMode] = useState<DeploymentMode>('kraft');
  const [installDir, setInstallDir] = useState('/opt');
  const [dataDir, setDataDir] = useState('/data/kafka');
  const [logDir, setLogDir] = useState('/var/log/kafka');
  const [artifactLoadDir, setArtifactLoadDir] = useState('/srv/tantor-agent/artifacts');
  const [listenerPort, setListenerPort] = useState(9092);
  const [controllerPort, setControllerPort] = useState(9093);
  const [zookeeperPeerPort, setZookeeperPeerPort] = useState(2888);
  const [zookeeperElectionPort, setZookeeperElectionPort] = useState(3888);
  const [numPartitions, setNumPartitions] = useState(1);

  const [nodeSearch, setNodeSearch] = useState('');
  const [nodeDropdownOpen, setNodeDropdownOpen] = useState(false);
  const [draftNodeIds, setDraftNodeIds] = useState<string[]>([]);
  const [selectedNodeIds, setSelectedNodeIds] = useState<string[]>([]);
  const [rolesByHost, setRolesByHost] = useState<Record<string, RoleChoice>>({});
  const [configsByService, setConfigsByService] = useState<Record<string, NodeConfigState>>({});
  const [commonConfigs, setCommonConfigs] = useState<Record<ConfigKind, PropertyRow[]>>(() => createCommonConfigs('kraft'));
  const [commonConfigKind, setCommonConfigKind] = useState<ConfigKind>('server');
  const [configModalHostId, setConfigModalHostId] = useState<string | null>(null);
  const [commonConfigOpen, setCommonConfigOpen] = useState(false);
  const [prereqResults, setPrereqResults] = useState<Record<string, PrereqResult>>({});
  const [checkingPrereqs, setCheckingPrereqs] = useState(false);
  const [deploying, setDeploying] = useState(false);
  const [validatingKraft, setValidatingKraft] = useState(false);
  const [kraftValidation, setKraftValidation] = useState<KraftValidationReport | null>(null);
  const [kraftGeneratedConfig, setKraftGeneratedConfig] = useState<Record<string, string>>({});
  const [kraftRiskAcknowledged, setKraftRiskAcknowledged] = useState(false);
  const [showEnrollModal, setShowEnrollModal] = useState(false);

  useEffect(() => {
    loadHosts();
    loadVersions();
  }, []);

  useEffect(() => {
    if (!addClusterId) return;
    setStage('details');
    setLoadingCluster(true);
    fetch(`/api/v1/ui/clusters/${addClusterId}`)
      .then(res => {
        if (!res.ok) throw new Error('Cluster not found');
        return res.json();
      })
      .then((cluster: ExistingCluster) => {
        setExistingCluster(cluster);
        setClusterName(cluster.name || '');
        setKafkaVersion(cluster.kafkaVersion || '');
        setEnvironment(cluster.environment || '');
        const loadedMode: DeploymentMode = cluster.mode === 'zookeeper' ? 'zookeeper' : 'kraft';
        setDeploymentMode(loadedMode);
        const cfg = cluster.config || {};
        setClusterConfigMode(String(cfg.configuration_mode || 'default') === 'custom' ? 'custom' : 'default');
        setInstallDir('');
        setDataDir('');
        setLogDir('');
        setArtifactLoadDir('');
        setListenerPort(Number(cfg.listener_port || 9092));
        setControllerPort(Number(cfg.controller_port || 9093));
        setZookeeperPeerPort(Number(cfg.zookeeper_peer_port || 2888));
        setZookeeperElectionPort(Number(cfg.zookeeper_election_port || 3888));
        setNumPartitions(Number(cfg.num_partitions || 1));
        const loadedConfigs = createCommonConfigs(loadedMode);
        setCommonConfigs({
          server: syncCommonRows(loadedConfigs.server, cfg),
          broker: syncCommonRows(loadedConfigs.broker, cfg),
          controller: syncCommonRows(loadedConfigs.controller, cfg),
          zookeeper: loadedConfigs.zookeeper,
        });
      })
      .catch(error => {
        console.error(error);
        alert('Failed to load cluster details for add-node mode.');
        navigate('/clusters');
      })
      .finally(() => setLoadingCluster(false));
  }, [addClusterId, navigate]);

  const loadHosts = async () => {
    setLoadingHosts(true);
    try {
      const res = await fetch('/api/v1/ui/hosts');
      if (res.ok) {
        const inventory: Host[] = await res.json();
        setHosts(inventory.filter(host => host.deployable !== false && host.agentType !== 'KAFKA_DISCOVERY'));
      }
    } catch (e) {
      console.error(e);
      setHosts([]);
    } finally {
      setLoadingHosts(false);
    }
  };

  const loadVersions = async () => {
    setLoadingVersions(true);
    try {
      const res = await fetch('/api/v1/artifacts?serviceType=KAFKA');
      const data = await res.json();
      const mapped = (data.content || []).map((a: any) => ({
        version: a.version,
        available: a.status === 'AVAILABLE',
        scala_version: a.attributes?.scala_version || '2.13',
        release_date: a.attributes?.release_date || new Date(a.createdAt).toLocaleDateString(),
        size_mb: parseFloat((a.fileSizeBytes / 1024 / 1024).toFixed(1)),
        filename: a.fileName,
        id: a.id,
      }));
      setVersions(mapped);
      const firstAvailable = mapped.find((v: KafkaVersionInfo) => v.available) || mapped[0];
      if (firstAvailable) setKafkaVersion(current => current || firstAvailable.version);
    } catch (e) {
      console.error(e);
      setVersions([]);
    } finally {
      setLoadingVersions(false);
    }
  };

  const availableVersions = versions.filter(version => version.available);
  const zookeeperSupported = kafkaMajorVersion(kafkaVersion) > 0 && kafkaMajorVersion(kafkaVersion) < 4;
  const commonConfigKinds = commonConfigKindsForMode(deploymentMode);
  const selectedHosts = selectedNodeIds
    .map(id => hosts.find(host => host.id === id))
    .filter(Boolean) as Host[];

  const filteredHosts = hosts.filter(host => {
    const needle = `${host.hostname} ${displayIp(host)} ${host.id}`.toLowerCase();
    return needle.includes(nodeSearch.toLowerCase());
  });

  const roleOptions = isAddNodeMode
    ? (deploymentMode === 'zookeeper' ? ZOOKEEPER_ROLE_OPTIONS : KRAFT_ROLE_OPTIONS).filter(role => role.id === 'broker')
    : deploymentMode === 'zookeeper' ? ZOOKEEPER_ROLE_OPTIONS : KRAFT_ROLE_OPTIONS;
  const allRoleOptions = [...KRAFT_ROLE_OPTIONS, ...ZOOKEEPER_ROLE_OPTIONS];
  const defaultRoleForMode: RoleChoice = isAddNodeMode ? 'broker' : deploymentMode === 'zookeeper' ? 'broker_zookeeper' : 'broker_controller';

  const brokerCount = selectedHosts.filter(host => {
    const role = rolesByHost[host.id] || defaultRoleForMode;
    return role === 'broker_controller' || role === 'broker' || role === 'separate' || role === 'broker_zookeeper';
  }).length;

  const controllerCount = selectedHosts.filter(host => {
    const role = rolesByHost[host.id] || defaultRoleForMode;
    return role === 'broker_controller' || role === 'controller' || role === 'separate';
  }).length;

  const zookeeperCount = selectedHosts.filter(host => {
    const role = rolesByHost[host.id] || defaultRoleForMode;
    return role === 'broker_zookeeper' || role === 'zookeeper';
  }).length;

  const existingBrokerCount = isAddNodeMode
    ? (existingCluster?.hosts || []).filter(host => ['broker', 'broker_controller', 'broker_zookeeper'].includes(String(host.role || ''))).length
    : 0;
  const effectiveBrokerCount = brokerCount + existingBrokerCount;

  const replication = useMemo(() => {
    if (brokerCount <= 1) return { factor: 1, minIsr: 1 };
    if (brokerCount === 2) return { factor: 2, minIsr: 1 };
    return { factor: 3, minIsr: 2 };
  }, [brokerCount]);

  useEffect(() => {
    if (isAddNodeMode) return;
    setCommonConfigs(prev => {
      const syncDefaults = (rows: PropertyRow[]) => rows.map(row => {
        if (row.key === 'default.replication.factor' && (clusterConfigMode === 'default' || !row.value.trim())) {
          return { ...row, value: String(replication.factor) };
        }
        if (row.key === 'min.insync.replicas' && (clusterConfigMode === 'default' || !row.value.trim())) {
          return { ...row, value: String(replication.minIsr) };
        }
        if (row.key === 'offsets.topic.replication.factor' && (clusterConfigMode === 'default' || (row.value === '3' && replication.factor < 3))) {
          return { ...row, value: String(replication.factor) };
        }
        if (row.key === 'transaction.state.log.replication.factor' && (clusterConfigMode === 'default' || (row.value === '3' && replication.factor < 3))) {
          return { ...row, value: String(replication.factor) };
        }
        if (row.key === 'transaction.state.log.min.isr' && (clusterConfigMode === 'default' || (row.value === '2' && replication.minIsr < 2))) {
          return { ...row, value: String(replication.minIsr) };
        }
        return row;
      });
      return {
        ...prev,
        server: syncDefaults(prev.server),
        broker: syncDefaults(prev.broker),
      };
    });
  }, [clusterConfigMode, deploymentMode, isAddNodeMode, replication.factor, replication.minIsr]);

  const warnings = useMemo(() => {
    const items: string[] = [];
    if (effectiveBrokerCount === 1) items.push('Only one broker will be present. Kafka will run without data replication.');
    if (deploymentMode === 'zookeeper' && zookeeperCount === 1) items.push('Only one ZooKeeper selected. ZooKeeper failover will not be available.');
    if (deploymentMode === 'zookeeper' && zookeeperCount > 1 && zookeeperCount % 2 === 0) items.push('Even ZooKeeper count selected. Odd ZooKeeper count is recommended for quorum voting.');
    if (isAddNodeMode && selectedHosts.some(host => {
      const role = rolesByHost[host.id] || defaultRoleForMode;
      return role === 'controller' || role === 'broker_controller' || role === 'separate' || role === 'broker_zookeeper' || role === 'zookeeper';
    })) {
      items.push('Adding quorum nodes changes cluster membership. Existing nodes may need updated configs and restart sequencing.');
    }
    return items;
  }, [defaultRoleForMode, deploymentMode, effectiveBrokerCount, isAddNodeMode, rolesByHost, selectedHosts, zookeeperCount]);

  const pathErrors = [
    validatePath(installDir, 'Install directory'),
    validatePath(dataDir, 'Data directory'),
    validatePath(logDir, 'Log directory'),
    validatePath(artifactLoadDir, 'Artifact/load directory'),
  ].filter(Boolean);

  const configModalHost = configModalHostId
    ? selectedHosts.find(host => host.id === configModalHostId) || null
    : null;

  const prerequisiteComplete = selectedHosts.length > 0
    && selectedHosts.every(host => prereqResults[host.id]?.status === 'SUCCESS');
  const kraftDeploymentBlocked = deploymentMode === 'kraft'
    && !isAddNodeMode
    && (!kraftValidation
      || kraftValidation.errors.length > 0
      || (kraftValidation.acknowledgementRequired && !kraftRiskAcknowledged));

  const configKey = (hostId: string, kind: ConfigKind) => `${hostId}:${kind}`;

  const ipRowKeyForKind = (kind: ConfigKind) => {
    if (kind === 'broker') return 'advertised.host';
    if (kind === 'controller') return 'controller.host';
    if (kind === 'zookeeper') return 'zookeeper.host';
    return 'node.host';
  };

  const defaultRowsForKind = (kind: ConfigKind): PropertyRow[] => {
    return [
      { key: ipRowKeyForKind(kind), value: '', required: true, locked: true },
    ];
  };

  const defaultHeapForKind = (kind: ConfigKind) => {
    if (kind === 'controller' || kind === 'zookeeper') return '512M';
    return '1G';
  };

  const configFileName = (kind: ConfigKind) => {
    if (kind === 'server') return 'server.properties';
    if (kind === 'broker') return 'broker.properties';
    if (kind === 'zookeeper') return 'zookeeper.properties';
    return 'controller.properties';
  };

  const configKindsForRole = (role: RoleChoice): ConfigKind[] => {
    if (deploymentMode === 'zookeeper') {
      if (role === 'broker_zookeeper') return ['server', 'zookeeper'];
      if (role === 'broker') return ['server'];
      return ['zookeeper'];
    }
    if (role === 'broker_controller') return ['server'];
    if (role === 'broker') return ['broker'];
    if (role === 'controller') return ['controller'];
    if (role === 'separate') return ['broker', 'controller'];
    return ['zookeeper'];
  };

  const serviceConfigFor = (hostId: string, kind: ConfigKind): NodeConfigState => {
    const existing = configsByService[configKey(hostId, kind)];
    return existing || { mode: 'default', rows: defaultRowsForKind(kind), heapSize: defaultHeapForKind(kind) };
  };

  const updateServiceConfig = (hostId: string, kind: ConfigKind, patch: Partial<NodeConfigState>) => {
    setConfigsByService(prev => {
      const current = prev[configKey(hostId, kind)] || { mode: 'default', rows: defaultRowsForKind(kind), heapSize: defaultHeapForKind(kind) };
      return {
        ...prev,
        [configKey(hostId, kind)]: { ...current, ...patch },
      };
    });
  };

  const updatePropertyValue = (hostId: string, kind: ConfigKind, key: string, value: string) => {
    const cfg = serviceConfigFor(hostId, kind);
    updateServiceConfig(hostId, kind, {
      mode: 'custom',
      rows: cfg.rows.map(row => row.key === key ? { ...row, value } : row),
    });
  };

  const commonConfigValue = (key: string) => {
    const row = commonConfigKinds
      .flatMap(kind => commonConfigs[kind])
      .find(item => item.key === key);
    return row?.value.trim() || '';
  };

  const updateCommonConfigValue = (kind: ConfigKind, key: string, value: string) => {
    setCommonConfigs(prev => {
      if (!SYNCED_BROKER_PROPERTY_KEYS.has(key)) {
        return { ...prev, [kind]: setRowsValue(prev[kind], key, value) };
      }
      return {
        ...prev,
        server: setRowsValue(prev.server, key, value),
        broker: setRowsValue(prev.broker, key, value),
      };
    });
    if (key === 'num.partitions') {
      const numeric = Number.parseInt(value || '0', 10);
      setNumPartitions(Number.isFinite(numeric) ? numeric : 0);
    }
    setClusterConfigMode('custom');
  };

  const selectClusterConfigMode = (mode: ConfigMode) => {
    setClusterConfigMode(mode);
    if (mode === 'default') {
      setCommonConfigs(createCommonConfigs(deploymentMode));
      setCommonConfigKind('server');
    }
  };

  const parseCsvLine = (line: string) => {
    const values: string[] = [];
    let value = '';
    let quoted = false;
    for (let index = 0; index < line.length; index++) {
      const character = line[index];
      if (character === '"' && line[index + 1] === '"' && quoted) { value += '"'; index++; }
      else if (character === '"') quoted = !quoted;
      else if (character === ',' && !quoted) { values.push(value.trim()); value = ''; }
      else value += character;
    }
    values.push(value.trim());
    return values;
  };

  const importCustomCsv = async (file: File) => {
    if (!file.name.toLowerCase().endsWith('.csv')) {
      setCustomImportSummary('Only the documented CSV template is supported.');
      return;
    }
    const rows = (await file.text()).split(/\r?\n/).filter(line => line.trim()).map(parseCsvLine);
    const header = rows.shift()?.map(value => value.toLowerCase()) || [];
    const scopeIndex = header.indexOf('scope');
    const keyIndex = header.indexOf('key');
    const valueIndex = header.indexOf('value');
    if (scopeIndex < 0 || keyIndex < 0 || valueIndex < 0) {
      setCustomImportSummary('CSV must contain scope,key,value columns.');
      return;
    }
    const imported: Partial<Record<ConfigKind, PropertyRow[]>> = {};
    let propertyCount = 0;
    rows.forEach(row => {
      const scope = String(row[scopeIndex] || '').toLowerCase();
      const key = String(row[keyIndex] || '').trim();
      const value = String(row[valueIndex] || '').trim();
      if (scope === 'cluster') {
        if (key === 'cluster_name') setClusterName(value);
        else if (key === 'environment') setEnvironment(value.toUpperCase());
        else if (key === 'install_directory') setInstallDir(value);
        else if (key === 'data_directory') setDataDir(value);
        else if (key === 'log_directory') setLogDir(value);
        else if (key === 'artifact_directory') setArtifactLoadDir(value);
        return;
      }
      if (!['server', 'broker', 'controller'].includes(scope) || !key) return;
      const kind = scope as ConfigKind;
      imported[kind] = [...(imported[kind] || []), { key, value }];
      propertyCount++;
    });
    setCommonConfigs(current => {
      const next = { ...current };
      (['server', 'broker', 'controller'] as ConfigKind[]).forEach(kind => {
        const additions = imported[kind] || [];
        if (!additions.length) return;
        const keys = new Set(additions.map(row => row.key));
        next[kind] = [...current[kind].filter(row => !keys.has(row.key)), ...additions];
      });
      return next;
    });
    setClusterConfigMode('custom');
    setCustomImportSummary(`Imported ${propertyCount} properties and cluster-level deployment details from ${file.name}.`);
  };

  const downloadCustomTemplate = () => {
    const csv = [
      'scope,key,value',
      'cluster,cluster_name,production-kafka',
      'cluster,environment,DEV',
      'cluster,install_directory,/opt',
      'cluster,data_directory,/data/kafka',
      'cluster,log_directory,/var/log/kafka',
      'server,num.partitions,3',
      'broker,default.replication.factor,3',
      'controller,controller.listener.names,CONTROLLER',
    ].join('\n');
    const link = document.createElement('a');
    link.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
    link.download = 'tantor-cluster-config-template.csv';
    link.click();
    URL.revokeObjectURL(link.href);
  };

  const configuredReplicationFactor = Number.parseInt(commonConfigValue('default.replication.factor') || String(replication.factor), 10);
  const configuredMinIsr = Number.parseInt(commonConfigValue('min.insync.replicas') || String(replication.minIsr), 10);
  const replicationWarnings = configuredReplicationFactor > 1 && configuredMinIsr === configuredReplicationFactor
    ? [`Minimum ISR equals replication factor (${configuredReplicationFactor}). Writes will stop if any replica becomes unavailable.`]
    : [];

  const missingRequiredConfigs = selectedHosts.flatMap(host => {
    const role = rolesByHost[host.id] || defaultRoleForMode;
    return configKindsForRole(role).flatMap(kind => {
      const cfg = serviceConfigFor(host.id, kind);
      return cfg.rows
        .filter(row => row.required && !row.value.trim())
        .map(row => `${host.hostname}: ${configFileName(kind)} requires ${row.key}`);
    });
  });

  const configValidationErrors = [
    ...commonConfigKinds.flatMap(kind => commonConfigs[kind]
      .filter(row => row.required && !String(row.value).trim())
      .map(row => `${configFileName(kind)} requires ${row.key}.`)),
    commonConfigKinds.flatMap(kind => commonConfigs[kind]).some(row => row.required && String(row.value).trim() && (!/^\d+$/.test(String(row.value).trim()) || Number(row.value) < 1))
      ? 'Common numeric properties must be positive numbers.'
      : '',
    deploymentMode === 'zookeeper' && kafkaMajorVersion(kafkaVersion) >= 4 ? `Kafka ${kafkaVersion} cannot use ZooKeeper mode. Select a Kafka 3.x artifact or KRaft.` : '',
    commonConfigValue('default.replication.factor') && Number(commonConfigValue('default.replication.factor')) > effectiveBrokerCount ? `default.replication.factor=${commonConfigValue('default.replication.factor')} is invalid because the cluster will have ${effectiveBrokerCount} broker${effectiveBrokerCount === 1 ? '' : 's'}.` : '',
    commonConfigValue('min.insync.replicas') && commonConfigValue('default.replication.factor') && Number(commonConfigValue('min.insync.replicas')) > Number(commonConfigValue('default.replication.factor')) ? 'min.insync.replicas cannot be greater than default.replication.factor.' : '',
    commonConfigValue('offsets.topic.replication.factor') && Number(commonConfigValue('offsets.topic.replication.factor')) > effectiveBrokerCount ? `offsets.topic.replication.factor=${commonConfigValue('offsets.topic.replication.factor')} is invalid because the cluster will have ${effectiveBrokerCount} brokers.` : '',
    commonConfigValue('transaction.state.log.replication.factor') && Number(commonConfigValue('transaction.state.log.replication.factor')) > effectiveBrokerCount ? `transaction.state.log.replication.factor=${commonConfigValue('transaction.state.log.replication.factor')} is invalid because the cluster will have ${effectiveBrokerCount} brokers.` : '',
  ].filter(Boolean);

  const configBlockingIssues = [...missingRequiredConfigs, ...configValidationErrors];

  const canPreview = clusterName.trim()
    && kafkaVersion
    && selectedHosts.length > 0
    && brokerCount > 0
    && (isAddNodeMode || (deploymentMode === 'kraft' ? controllerCount > 0 : zookeeperCount > 0));

  const serviceTemplate = (kind: ConfigKind, cfg: NodeConfigState) => {
    const commonRows = commonConfigs[kind] || [];
    return serializeProperties([...commonRows, ...cfg.rows]);
  };

  const buildServices = (): ServiceAssignment[] => {
    const usedNodeIds = new Set((existingCluster?.hosts || [])
      .map(host => Number(host.nodeId || 0))
      .filter(id => id > 0));
    const allocateNodeId = (start: number) => {
      let next = start;
      while (usedNodeIds.has(next)) next++;
      usedNodeIds.add(next);
      return next;
    };
    const services: ServiceAssignment[] = [];

    selectedHosts.forEach(host => {
      const role = rolesByHost[host.id] || defaultRoleForMode;
      const configFor = (kind: ConfigKind) => serviceConfigFor(host.id, kind);
      if (role === 'broker_controller') {
        const cfg = configFor('server');
        services.push({ host_id: host.id, role: 'broker_controller', node_id: allocateNodeId(101), configuration_mode: cfg.mode, properties_template: serviceTemplate('server', cfg), heap_size: cfg.heapSize });
      } else if (role === 'broker_zookeeper') {
        const brokerCfg = configFor('server');
        const zookeeperCfg = configFor('zookeeper');
        services.push({ host_id: host.id, role: 'broker', node_id: allocateNodeId(1), configuration_mode: brokerCfg.mode, properties_template: serviceTemplate('server', brokerCfg), heap_size: brokerCfg.heapSize });
        services.push({ host_id: host.id, role: 'zookeeper', node_id: allocateNodeId(1001), configuration_mode: zookeeperCfg.mode, properties_template: serviceTemplate('zookeeper', zookeeperCfg), heap_size: zookeeperCfg.heapSize });
      } else if (role === 'separate') {
        const brokerCfg = configFor('broker');
        const controllerCfg = configFor('controller');
        services.push({ host_id: host.id, role: 'broker', node_id: allocateNodeId(1), configuration_mode: brokerCfg.mode, properties_template: serviceTemplate('broker', brokerCfg), heap_size: brokerCfg.heapSize });
        services.push({ host_id: host.id, role: 'controller', node_id: allocateNodeId(101), configuration_mode: controllerCfg.mode, properties_template: serviceTemplate('controller', controllerCfg), heap_size: controllerCfg.heapSize });
      } else if (role === 'controller') {
        const cfg = configFor('controller');
        services.push({ host_id: host.id, role: 'controller', node_id: allocateNodeId(101), configuration_mode: cfg.mode, properties_template: serviceTemplate('controller', cfg), heap_size: cfg.heapSize });
      } else if (role === 'zookeeper') {
        const cfg = configFor('zookeeper');
        services.push({ host_id: host.id, role: 'zookeeper', node_id: allocateNodeId(1001), configuration_mode: cfg.mode, properties_template: serviceTemplate('zookeeper', cfg), heap_size: cfg.heapSize });
      } else {
        const kind: ConfigKind = deploymentMode === 'zookeeper' ? 'server' : 'broker';
        const cfg = configFor(kind);
        services.push({ host_id: host.id, role: 'broker', node_id: allocateNodeId(1), configuration_mode: cfg.mode, properties_template: serviceTemplate(kind, cfg), heap_size: cfg.heapSize });
      }
    });

    return services;
  };

  const buildDeploymentPayload = (includeGeneratedKraftConfig = true) => {
    const selectedArtifact = versions.find(version => version.version === kafkaVersion);
    const artifactRepoBaseUrl = import.meta.env.VITE_ARTIFACT_REPO_URL || `http://${window.location.hostname || 'localhost'}:8081`;
    return {
      name: clusterName.trim(),
      kafka_version: kafkaVersion,
      mode: deploymentMode,
      services: buildServices(),
      environment: environment.trim(),
      acknowledge_kraft_risk: kraftRiskAcknowledged,
      artifactUrl: selectedArtifact ? `${artifactRepoBaseUrl}/api/v1/artifacts/${selectedArtifact.id}/download` : '',
      config: {
        configuration_mode: clusterConfigMode,
        kafka_install_dir: installDir.trim(),
        kafka_install_base_dir: installDir.trim(),
        kafka_data_dir: dataDir.trim(),
        kafka_app_log_dir: logDir.trim(),
        artifact_load_dir: artifactLoadDir.trim(),
        scala_version: selectedArtifact?.scala_version || '2.13',
        listener_port: listenerPort,
        controller_port: controllerPort,
        zookeeper_port: deploymentMode === 'zookeeper' ? controllerPort : undefined,
        zookeeper_peer_port: zookeeperPeerPort,
        zookeeper_election_port: zookeeperElectionPort,
        num_partitions: Number(commonConfigValue('num.partitions') || numPartitions),
        replication_factor: configuredReplicationFactor,
        min_insync_replicas: configuredMinIsr,
        ...(includeGeneratedKraftConfig && deploymentMode === 'kraft' ? kraftGeneratedConfig : {}),
      },
    };
  };

  const openPreview = async () => {
    setPrereqResults({});
    setKraftRiskAcknowledged(false);
    if (deploymentMode !== 'kraft' || isAddNodeMode) {
      setKraftValidation(null);
      setKraftGeneratedConfig({});
      setStage('preview');
      return;
    }

    setValidatingKraft(true);
    try {
      const res = await fetch('/api/v1/ui/clusters/validate-kraft', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(buildDeploymentPayload(false)),
      });
      const body = await res.json().catch(() => ({}));
      if (!res.ok) {
        alert(body.error || body.message || 'KRaft topology validation failed.');
        return;
      }
      const report = body as KraftValidationReport;
      setKraftValidation(report);
      setKraftGeneratedConfig(report.generatedConfig || {});
      setStage('preview');
    } catch (error) {
      console.error(error);
      alert('Network error while validating the KRaft topology.');
    } finally {
      setValidatingKraft(false);
    }
  };

  const confirmNodeSelection = () => {
    setSelectedNodeIds(draftNodeIds);
    setRolesByHost(prev => {
      const next: Record<string, RoleChoice> = {};
      draftNodeIds.forEach(id => {
        next[id] = roleOptions.some(role => role.id === prev[id]) ? prev[id] : defaultRoleForMode;
      });
      return next;
    });
    setPrereqResults({});
    setNodeDropdownOpen(false);
  };

  const removeNode = (hostId: string) => {
    setSelectedNodeIds(prev => prev.filter(id => id !== hostId));
    setDraftNodeIds(prev => prev.filter(id => id !== hostId));
    setRolesByHost(prev => {
      const next = { ...prev };
      delete next[hostId];
      return next;
    });
    setPrereqResults(prev => {
      const next = { ...prev };
      delete next[hostId];
      return next;
    });
  };

  const changeDeploymentMode = (mode: DeploymentMode) => {
    setDeploymentMode(mode);
    const nextDefaultRole: RoleChoice = isAddNodeMode ? 'broker' : mode === 'zookeeper' ? 'broker_zookeeper' : 'broker_controller';
    setRolesByHost(() => {
      const next: Record<string, RoleChoice> = {};
      selectedNodeIds.forEach(id => { next[id] = nextDefaultRole; });
      return next;
    });
    setConfigsByService({});
    setCommonConfigs(createCommonConfigs(mode));
    setCommonConfigKind('server');
    setPrereqResults({});
    if (mode === 'kraft') {
      setControllerPort(9093);
    } else {
      setControllerPort(2181);
    }
  };

  const changeKafkaVersion = (version: string) => {
    setKafkaVersion(version);
    if (kafkaMajorVersion(version) >= 4 && deploymentMode === 'zookeeper') {
      changeDeploymentMode('kraft');
    }
  };

  const prerequisitePortsForHost = (hostId: string): number[] => {
    const role = rolesByHost[hostId] || defaultRoleForMode;
    const ports = new Set<number>();
    const hasBroker = ['broker', 'broker_controller', 'separate', 'broker_zookeeper'].includes(role);
    if (hasBroker) {
      ports.add(listenerPort);
      ports.add(7071);
    }
    if (deploymentMode === 'kraft' && ['controller', 'broker_controller', 'separate'].includes(role)) {
      ports.add(controllerPort);
    }
    if (deploymentMode === 'zookeeper' && ['zookeeper', 'broker_zookeeper'].includes(role)) {
      ports.add(controllerPort);
      ports.add(zookeeperPeerPort);
      ports.add(zookeeperElectionPort);
    }
    return Array.from(ports);
  };

  const checkPrerequisites = async () => {
    setCheckingPrereqs(true);
    const initial: Record<string, PrereqResult> = {};
    selectedHosts.forEach(host => {
      initial[host.id] = { status: 'QUEUED', logOutput: 'Queued prerequisite check.', errorMsg: '' };
    });
    setPrereqResults(initial);

    await Promise.all(selectedHosts.map(async host => {
      try {
        const res = await fetch(`/api/v1/ui/hosts/${host.id}/check-prerequisites`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            mode: deploymentMode,
            required_ports: prerequisitePortsForHost(host.id).join(','),
          }),
        });
        const body = await res.json().catch(() => ({}));
        if (!res.ok) {
          setPrereqResults(prev => ({
            ...prev,
            [host.id]: {
              status: 'FAILED',
              logOutput: '',
              errorMsg: body.message || 'Failed to queue prerequisite check.',
            },
          }));
          return;
        }

        setPrereqResults(prev => ({
          ...prev,
          [host.id]: {
            status: 'RUNNING',
            taskId: body.taskId,
            logOutput: 'Task queued. Waiting for agent to report progress...',
            errorMsg: '',
          },
        }));

        await pollPrerequisite(host.id, body.taskId);
      } catch (e) {
        console.error(e);
        setPrereqResults(prev => ({
          ...prev,
          [host.id]: {
            status: 'FAILED',
            logOutput: '',
            errorMsg: 'Network error while queuing prerequisite check.',
          },
        }));
      }
    }));

    setCheckingPrereqs(false);
  };

  const pollPrerequisite = async (hostId: string, taskId: string) => {
    for (let i = 0; i < 90; i++) {
      await new Promise(resolve => setTimeout(resolve, 1500));
      const res = await fetch(`/api/v1/ui/hosts/${hostId}/check-prerequisites/${taskId}`);
      if (!res.ok) continue;
      const body = await res.json();
      const status = String(body.status || 'RUNNING').toUpperCase();
      setPrereqResults(prev => ({
        ...prev,
        [hostId]: {
          status: activeStatus(status)
            ? 'RUNNING'
            : status === 'SUCCESS'
              ? 'SUCCESS'
              : status === 'REBOOT_REQUIRED'
                ? 'REBOOT_REQUIRED'
                : 'FAILED',
          taskId,
          logOutput: body.logOutput || prev[hostId]?.logOutput || '',
          errorMsg: body.errorMsg || '',
        },
      }));
      if (!activeStatus(status)) return;
    }
    setPrereqResults(prev => ({
      ...prev,
      [hostId]: {
        status: 'FAILED',
        taskId,
        logOutput: prev[hostId]?.logOutput || '',
        errorMsg: 'Timed out waiting for prerequisite result.',
      },
    }));
  };

  const fixPrerequisites = async () => {
    const failedHosts = selectedHosts.filter(host => prereqResults[host.id]?.status === 'FAILED');
    if (failedHosts.length === 0) return;
    const confirmed = window.confirm(
      `Apply privileged operating-system changes on ${failedHosts.length} host(s)? This may update limits, sysctl, THP, SELinux, time synchronization, and may require a reboot.`,
    );
    if (!confirmed) return;

    setCheckingPrereqs(true);
    await Promise.all(failedHosts.map(async host => {
      try {
        const res = await fetch(`/api/v1/ui/hosts/${host.id}/fix-prerequisites`, { method: 'POST' });
        const body = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(body.message || 'Failed to queue prerequisite remediation.');
        setPrereqResults(prev => ({
          ...prev,
          [host.id]: { status: 'RUNNING', taskId: body.taskId, logOutput: 'Applying prerequisite fixes...', errorMsg: '' },
        }));
        await pollPrerequisite(host.id, body.taskId);
      } catch (error) {
        setPrereqResults(prev => ({
          ...prev,
          [host.id]: { status: 'FAILED', logOutput: prev[host.id]?.logOutput || '', errorMsg: error instanceof Error ? error.message : 'Failed to fix prerequisites.' },
        }));
      }
    }));
    setCheckingPrereqs(false);
  };

  const rebootHost = async (host: Host) => {
    if (!window.confirm(`Reboot ${host.hostname}? The host and agent will be temporarily offline.`)) return;
    setCheckingPrereqs(true);
    try {
      const res = await fetch(`/api/v1/ui/hosts/${host.id}/reboot`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ confirmed: true }),
      });
      const body = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(body.message || 'Failed to schedule reboot.');
      setPrereqResults(prev => ({
        ...prev,
        [host.id]: { status: 'RUNNING', taskId: body.taskId, logOutput: 'Scheduling host reboot...', errorMsg: '' },
      }));
      await pollPrerequisite(host.id, body.taskId);
    } catch (error) {
      setPrereqResults(prev => ({
        ...prev,
        [host.id]: { status: 'REBOOT_REQUIRED', logOutput: prev[host.id]?.logOutput || '', errorMsg: error instanceof Error ? error.message : 'Failed to schedule reboot.' },
      }));
    } finally {
      setCheckingPrereqs(false);
    }
  };

  const deployCluster = async () => {
    setDeploying(true);
    try {
      const payload = buildDeploymentPayload();

      const url = isAddNodeMode && addClusterId
        ? `/api/v1/ui/clusters/${addClusterId}/nodes`
        : '/api/v1/ui/clusters/deploy';
      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      const body = await res.json().catch(() => ({}));
      if (!res.ok) {
        alert(body.error || body.message || 'Deployment failed to start.');
        return;
      }
      if (body.jobId) {
        navigate(`/jobs/${body.jobId}`);
      } else {
        navigate(`/clusters/${body.id}/logs`);
      }
    } catch (e) {
      console.error(e);
      alert('Network error while starting deployment.');
    } finally {
      setDeploying(false);
    }
  };

  if (stage === 'landing' && !isAddNodeMode) {
    return (
      <div className="cluster-deploy-page animate-fade-in">
        <header className="cd-header">
          <div>
            <h1>Cluster Deployment</h1>
            <p>Create a managed Kafka cluster or connect an existing external cluster.</p>
          </div>
        </header>

        <div className="cd-choice-grid">
          <button className="cd-choice-card primary" onClick={() => setStage('details')}>
            <Network size={26} />
            <span>Create your cluster</span>
            <small>Build a new KRaft or ZooKeeper cluster on selected Tantor hosts.</small>
          </button>
          <button className="cd-choice-card" onClick={() => navigate('/external-clusters')}>
            <Database size={26} />
            <span>External Cluster</span>
            <small>Connect or discover an external Kafka cluster.</small>
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="cluster-deploy-page animate-fade-in">
      <header className="cd-header">
        <div>
          <h1>{stage === 'details' ? (isAddNodeMode ? 'Add Node to Cluster' : 'Create Kafka Cluster') : (isAddNodeMode ? 'Preview Node Addition' : 'Preview Deployment')}</h1>
          <p>{stage === 'details'
            ? isAddNodeMode
              ? 'External cluster details are loaded. Select new nodes and roles to add.'
              : 'Define the cluster, select nodes, and choose roles.'
            : 'Run prerequisites across every selected node before deployment.'}</p>
        </div>
        <div className="cd-header-side">
          <div className="cd-stage-tabs" aria-label="Deployment progress">
            <span className={stage === 'details' ? 'active' : ''}>Details</span>
            <span className={stage === 'preview' ? 'active' : ''}>Preview</span>
          </div>
        </div>
      </header>

      {stage === 'details' ? (
        <div className="cd-layout">
          {loadingCluster && (
            <section className="cd-panel">
              <div className="cd-template-summary">
                <Loader2 size={16} className="spin" />
                <span>Loading existing cluster details...</span>
              </div>
            </section>
          )}
          <section className="cd-panel">
            <div className="cd-panel-title">
              <Settings2 size={18} />
              <h2>Cluster Details</h2>
            </div>
            <div className="cd-detail-controls">
              <div className="cd-control-group">
                <span>Configuration</span>
                <div className="cd-choice-toggle">
                  <button className={clusterConfigMode === 'default' ? 'active' : ''} onClick={() => selectClusterConfigMode('default')} disabled={isAddNodeMode}>Default</button>
                  <button className={clusterConfigMode === 'custom' ? 'active' : ''} onClick={() => selectClusterConfigMode('custom')} disabled={isAddNodeMode}>Custom</button>
                </div>
              </div>
            </div>
            {clusterConfigMode === 'custom' && !isAddNodeMode && (
              <div className="cd-custom-import">
                <div>
                  <strong>Import custom cluster configuration</strong>
                  <p>Use the CSV template for cluster paths and server.properties, broker.properties, and controller.properties. Host details are not imported.</p>
                </div>
                <label className="cd-secondary-btn compact">
                  <Upload size={14} /> Upload CSV
                  <input type="file" accept=".csv,text/csv" hidden onChange={event => {
                    const selected = event.target.files?.[0];
                    if (selected) void importCustomCsv(selected);
                    event.target.value = '';
                  }} />
                </label>
                <button type="button" className="cd-secondary-btn compact" onClick={downloadCustomTemplate}>Download example</button>
                {customImportSummary && <span className="cd-import-summary">{customImportSummary}</span>}
              </div>
            )}
            <div className="cd-grid-2">
              <label className="cd-field">
                <span>Cluster name</span>
                <input value={clusterName} onChange={e => setClusterName(e.target.value)} placeholder="production-kraft" disabled={isAddNodeMode} />
              </label>
              {isAddNodeMode && (
                <label className="cd-field">
                  <span>Kafka Cluster ID</span>
                  <input value={existingCluster?.kafkaClusterId || addClusterId || ''} disabled />
                </label>
              )}
              <label className="cd-field">
                <span>Kafka version</span>
                <select value={kafkaVersion} onChange={e => changeKafkaVersion(e.target.value)} disabled={isAddNodeMode || loadingVersions || versions.length === 0}>
                  {availableVersions.map(version => (
                    <option key={version.version} value={version.version}>
                      {version.version} ({version.size_mb} MB)
                    </option>
                  ))}
                  {availableVersions.length === 0 && <option>No available Kafka artifact</option>}
                </select>
              </label>
              <div className="cd-field">
                <span>Environment</span>
                <div className="cd-env-buttons">
                  {['SIT', 'UAT', 'DEV'].map(env => (
                    <button
                      key={env}
                      className={environment.toUpperCase() === env ? 'active' : ''}
                      onClick={() => setEnvironment(env)}
                      disabled={isAddNodeMode}
                    >
                      {env === 'DEV' ? 'Dev' : env}
                    </button>
                  ))}
                </div>
              </div>
              <div className="cd-field">
                <span>Metadata mode</span>
                <div className="cd-choice-toggle cd-mode-toggle">
                  <button className={deploymentMode === 'kraft' ? 'active' : ''} onClick={() => changeDeploymentMode('kraft')} disabled={isAddNodeMode}>KRaft</button>
                  {zookeeperSupported && (
                    <button className={deploymentMode === 'zookeeper' ? 'active' : ''} onClick={() => changeDeploymentMode('zookeeper')} disabled={isAddNodeMode}>ZooKeeper</button>
                  )}
                </div>
              </div>
            </div>
          </section>

          <section className="cd-panel">
            <div className="cd-panel-title">
              <Server size={18} />
              <h2>Deployment Paths</h2>
              <button className="cd-secondary-btn compact" onClick={() => setCommonConfigOpen(true)}>
                <FileText size={14} />
                Config
              </button>
            </div>
            <div className="cd-grid-2">
              <label className="cd-field">
                <span>Install directory</span>
                <input value={installDir} onChange={e => setInstallDir(e.target.value)} placeholder="/opt" />
              </label>
              <label className="cd-field">
                <span>Data directory</span>
                <input value={dataDir} onChange={e => setDataDir(e.target.value)} placeholder="/data/kafka" />
              </label>
              <label className="cd-field">
                <span>Log directory</span>
                <input value={logDir} onChange={e => setLogDir(e.target.value)} placeholder="/var/log/kafka" />
              </label>
              <label className="cd-field">
                <span>Artifact/load directory</span>
                <input value={artifactLoadDir} onChange={e => setArtifactLoadDir(e.target.value)} placeholder="/srv/tantor-agent/artifacts" />
              </label>
            </div>
          </section>

          <section className="cd-panel">
            <div className="cd-panel-title">
              <Network size={18} />
              <h2>Nodes and Roles</h2>
              <button className="cd-ghost-btn" onClick={() => setShowEnrollModal(true)}>
                Add node
              </button>
              <button className="cd-ghost-btn" onClick={loadHosts}>
                <RefreshCw size={14} className={loadingHosts ? 'spin' : ''} />
                Refresh
              </button>
            </div>

            <div className="cd-node-picker">
              <button className="cd-node-trigger" onClick={() => {
                setDraftNodeIds(selectedNodeIds);
                setNodeDropdownOpen(open => !open);
              }}>
                <span>{selectedNodeIds.length ? `${selectedNodeIds.length} node${selectedNodeIds.length > 1 ? 's' : ''} selected` : 'Select nodes'}</span>
                <ChevronDown size={16} />
              </button>
              {nodeDropdownOpen && (
                <div className="cd-node-menu">
                  <div className="cd-search">
                    <Search size={15} />
                    <input value={nodeSearch} onChange={e => setNodeSearch(e.target.value)} placeholder="Search hostname or IP" autoFocus />
                  </div>
                  <div className="cd-node-options">
                    {filteredHosts.map(host => {
                      const disabled = host.status !== 'AVAILABLE' || host.available === false;
                      const checked = draftNodeIds.includes(host.id);
                      return (
                        <button
                          key={host.id}
                          className={`cd-node-option ${checked ? 'checked' : ''}`}
                          disabled={disabled}
                          onClick={() => setDraftNodeIds(prev => checked ? prev.filter(id => id !== host.id) : [...prev, host.id])}
                        >
                          <span className="cd-checkbox">{checked && <Check size={12} />}</span>
                          <span>
                            <strong>{host.hostname}</strong>
                            <small>{displayIp(host)} {disabled ? `- ${host.available === false ? 'Kafka Already Deployed' : host.status}` : ''}</small>
                          </span>
                        </button>
                      );
                    })}
                  </div>
                  <div className="cd-node-menu-footer">
                    <button onClick={() => setNodeDropdownOpen(false)}>Cancel</button>
                    <button className="primary" onClick={confirmNodeSelection}>OK</button>
                  </div>
                </div>
              )}
            </div>

            <div className="cd-selected-node-list">
              {selectedHosts.length === 0 ? (
                <div className="cd-empty">No nodes selected yet.</div>
              ) : selectedHosts.map(host => (
                <div className="cd-selected-node" key={host.id}>
                  <div className="cd-node-main">
                    <Server size={16} />
                    <div>
                      <strong>{host.hostname}</strong>
                      <span>{displayIp(host)}</span>
                    </div>
                  </div>
                  <div className="cd-role-buttons">
                    {roleOptions.filter(r => r.id !== 'separate').map(role => {
                      const currentRole = rolesByHost[host.id] || defaultRoleForMode;
                      let isActive = currentRole === role.id;

                      if (deploymentMode === 'kraft') {
                        if (role.id === 'broker') isActive = currentRole === 'broker' || currentRole === 'separate';
                        if (role.id === 'controller') isActive = currentRole === 'controller' || currentRole === 'separate';
                      }

                      return (
                        <label
                          key={role.id}
                          className={`cd-role-label ${isActive ? 'active' : ''}`}
                        >
                          <input 
                            type="checkbox" 
                            checked={isActive}
                            onChange={() => {
                              let nextRole = role.id;
                              
                              if (deploymentMode === 'kraft') {
                                if (role.id === 'broker') {
                                  if (currentRole === 'controller') nextRole = 'separate';
                                  else if (currentRole === 'separate') nextRole = 'controller';
                                } else if (role.id === 'controller') {
                                  if (currentRole === 'broker') nextRole = 'separate';
                                  else if (currentRole === 'separate') nextRole = 'broker';
                                }
                              }
                              
                              setRolesByHost(prev => ({ ...prev, [host.id]: nextRole }));
                              setPrereqResults({});
                            }}
                          />
                          <span>{role.label}</span>
                        </label>
                      );
                    })}
                  </div>
                  <button className="cd-secondary-btn compact" onClick={() => setConfigModalHostId(host.id)}>
                    <FileText size={14} />
                    Configuration
                  </button>
                  <button className="cd-icon-btn" onClick={() => removeNode(host.id)} title="Remove node">
                    <X size={15} />
                  </button>
                </div>
              ))}
            </div>

          </section>

          <div className="cd-footer-actions">
            <button className="cd-secondary-btn" onClick={() => isAddNodeMode ? navigate('/clusters') : setStage('landing')}>Back</button>
            <button className="cd-primary-btn" disabled={!canPreview || validatingKraft} onClick={openPreview}>
              {validatingKraft && <Loader2 size={15} className="spin" />}
              {isAddNodeMode ? 'Preview add node' : validatingKraft ? 'Validating topology' : 'Preview'}
            </button>
          </div>
        </div>
      ) : (
        <div className="cd-layout">
          {deploymentMode === 'kraft' && !isAddNodeMode && kraftValidation && (
            <section className="cd-panel cd-kraft-validation">
              <div className="cd-panel-title">
                <Network size={18} />
                <h2>KRaft Topology Validation</h2>
                <span className={`cd-validation-state ${kraftValidation.valid ? 'valid' : 'invalid'}`}>
                  {kraftValidation.valid ? <CheckCircle2 size={14} /> : <XCircle size={14} />}
                  {kraftValidation.valid ? 'Topology valid' : 'Changes required'}
                </span>
              </div>

              <div className="cd-kraft-facts">
                <div><span>Kafka cluster ID</span><strong>{kraftValidation.clusterId}</strong></div>
                <div><span>Quorum mode</span><strong>{kraftValidation.quorumMode}</strong></div>
                <div><span>Controllers</span><strong>{kraftValidation.controllerCount}</strong></div>
                <div><span>Brokers</span><strong>{kraftValidation.brokerCount}</strong></div>
                <div><span>Failure tolerance</span><strong>{kraftValidation.failureTolerance} controller{kraftValidation.failureTolerance === 1 ? '' : 's'}</strong></div>
              </div>

              <div className="cd-quorum-value">
                <span>{kraftValidation.quorumMode === 'static' ? 'controller.quorum.voters' : 'controller.quorum.bootstrap.servers'}</span>
                <code>{kraftValidation.controllerQuorum}</code>
              </div>

              <div className="cd-kraft-table-wrap">
                <table className="cd-kraft-table">
                  <thead><tr><th>Host</th><th>Address</th><th>Node ID</th><th>Role</th></tr></thead>
                  <tbody>
                    {kraftValidation.nodes.map(node => (
                      <tr key={`${node.hostId}-${node.nodeId}`}>
                        <td>{hosts.find(host => host.id === node.hostId)?.hostname || node.hostId}</td>
                        <td>{node.address}</td>
                        <td>{node.nodeId}</td>
                        <td>{node.role.replace('_', ' + ')}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {kraftValidation.errors.length > 0 && (
                <div className="cd-inline-errors">
                  {kraftValidation.errors.map(error => <span key={error}><XCircle size={13} /> {error}</span>)}
                </div>
              )}
              {kraftValidation.warnings.length > 0 && (
                <div className="cd-warning-list">
                  {kraftValidation.warnings.map(warning => <span key={warning}><AlertTriangle size={13} /> {warning}</span>)}
                </div>
              )}
              {kraftValidation.acknowledgementRequired && (
                <label className="cd-risk-ack">
                  <input
                    type="checkbox"
                    checked={kraftRiskAcknowledged}
                    onChange={event => setKraftRiskAcknowledged(event.target.checked)}
                  />
                  <span>I understand this controller topology has reduced availability and want to continue.</span>
                </label>
              )}
            </section>
          )}
          <section className="cd-panel">
            <div className="cd-panel-title">
              <Network size={18} />
              <h2>Nodes Selected for Deployment</h2>
            </div>
            <div className="cd-preview-list">
              {selectedHosts.map(host => {
                const role = allRoleOptions.find(item => item.id === (rolesByHost[host.id] || defaultRoleForMode));
                const result = prereqResults[host.id];
                return (
                  <div className="cd-preview-row" key={host.id}>
                    <div className="cd-node-main">
                      <Server size={16} />
                      <div>
                        <strong>{host.hostname}</strong>
                        <span>{displayIp(host)}</span>
                      </div>
                    </div>
                    <div className="cd-role-copy">
                      <strong>{role?.label}</strong>
                      <span>{role?.detail}</span>
                    </div>
                    <StatusBadge status={result?.status || 'IDLE'} />
                  </div>
                );
              })}
            </div>
            {[...warnings, ...replicationWarnings].length > 0 && (
              <div className="cd-warning-list">
                {[...warnings, ...replicationWarnings].map(warning => <span key={warning}><AlertTriangle size={13} /> {warning}</span>)}
              </div>
            )}
            {[...pathErrors, ...configBlockingIssues].length > 0 && (
              <div className="cd-inline-errors">
                {[...pathErrors, ...configBlockingIssues].map(item => <span key={item}><AlertTriangle size={13} /> {item}</span>)}
              </div>
            )}
          </section>

          <section className="cd-panel">
            <div className="cd-panel-title">
              <CheckCircle2 size={18} />
              <h2>Prerequisites</h2>
              <button className="cd-primary-btn small" disabled={checkingPrereqs || selectedHosts.length === 0 || pathErrors.length > 0 || configBlockingIssues.length > 0 || (kraftValidation?.errors.length || 0) > 0} onClick={checkPrerequisites}>
                {checkingPrereqs ? <Loader2 size={14} className="spin" /> : <RefreshCw size={14} />}
                Check prerequisites on all nodes
              </button>
              {selectedHosts.some(host => prereqResults[host.id]?.status === 'FAILED') && (
                <button className="cd-secondary-btn compact" disabled={checkingPrereqs} onClick={fixPrerequisites}>
                  <Settings2 size={14} /> Fix failed prerequisites
                </button>
              )}
            </div>
            {checkingPrereqs && <div className="cd-progress"><span /></div>}
            <div className="cd-prereq-grid">
              {selectedHosts.map(host => {
                const result = prereqResults[host.id] || { status: 'IDLE', logOutput: '', errorMsg: '' };
                return (
                  <details className="cd-prereq-card" key={host.id} open={result.status === 'FAILED' || result.status === 'REBOOT_REQUIRED'}>
                    <summary>
                      <span>{host.hostname}</span>
                      <StatusBadge status={result.status} />
                    </summary>
                    {result.errorMsg || result.logOutput
                      ? <PrerequisiteLog result={result} />
                      : <div className="cd-prereq-log"><span className="neutral">Waiting for prerequisite run...</span></div>}
                    {result.status === 'REBOOT_REQUIRED' && (
                      <div className="cd-prereq-remediation-actions">
                        <span><AlertTriangle size={14} /> Persistent settings were applied, but this host must reboot.</span>
                        <button className="cd-secondary-btn compact" disabled={checkingPrereqs} onClick={() => rebootHost(host)}>Reboot host</button>
                      </div>
                    )}
                  </details>
                );
              })}
            </div>
          </section>

          <div className="cd-footer-actions">
            <button className="cd-secondary-btn" disabled={checkingPrereqs || deploying} onClick={() => setStage('details')}>Back to details</button>
            <button className="cd-primary-btn" disabled={!prerequisiteComplete || deploying || pathErrors.length > 0 || configBlockingIssues.length > 0 || kraftDeploymentBlocked} onClick={deployCluster}>
              {deploying ? <Loader2 size={15} className="spin" /> : <Play size={15} />}
              {isAddNodeMode ? 'Add node' : 'Deploy'}
            </button>
          </div>
        </div>
      )}
      {showEnrollModal && (
        <AgentConnectivityModal onClose={() => {
          setShowEnrollModal(false);
          loadHosts();
        }} />
      )}
      {configModalHost && (
        <div className="cd-modal-backdrop" onClick={() => setConfigModalHostId(null)}>
          <div className="cd-config-modal" onClick={e => e.stopPropagation()}>
            <div className="cd-config-modal-header">
              <div>
                <h2>Configuration</h2>
                <p>{configModalHost.hostname} - {allRoleOptions.find(role => role.id === (rolesByHost[configModalHost.id] || defaultRoleForMode))?.label}</p>
              </div>
              <button className="cd-icon-btn" onClick={() => setConfigModalHostId(null)} title="Close configuration">
                <X size={16} />
              </button>
            </div>

            <div className="cd-config-modal-body">
              {configKindsForRole(rolesByHost[configModalHost.id] || defaultRoleForMode).map(kind => {
                const cfg = serviceConfigFor(configModalHost.id, kind);
                return (
                  <div className="cd-node-config-editor" key={kind}>
                    <div className="cd-node-config-top">
                      <div>
                        <h3>{configFileName(kind)}</h3>
                        <p>Fill the node-specific values for this service.</p>
                      </div>
                      <div className="cd-config-controls">
                        <label className="cd-heap-field">
                          <span>Heap</span>
                          <input
                            value={cfg.heapSize}
                            onChange={e => updateServiceConfig(configModalHost.id, kind, { heapSize: e.target.value })}
                            placeholder={defaultHeapForKind(kind)}
                          />
                        </label>
                      </div>
                    </div>
                    <PropertyTable
                      rows={cfg.rows}
                      hostIp={displayIp(configModalHost)}
                      onUseHostIp={() => updatePropertyValue(configModalHost.id, kind, ipRowKeyForKind(kind), displayIp(configModalHost))}
                      onChange={(key, value) => updatePropertyValue(configModalHost.id, kind, key, value)}
                    />
                  </div>
                );
              })}
            </div>

            <div className="cd-config-modal-footer">
              <button className="cd-secondary-btn" onClick={() => setConfigModalHostId(null)}>Done</button>
            </div>
          </div>
        </div>
      )}
      {commonConfigOpen && (
        <div className="cd-modal-backdrop" onClick={() => setCommonConfigOpen(false)}>
          <div className="cd-config-modal common" onClick={e => e.stopPropagation()}>
            <div className="cd-config-modal-header">
              <div>
                <h2>Common Configuration</h2>
                <p>{deploymentMode === 'kraft' ? 'KRaft' : 'ZooKeeper'} properties shared across selected nodes.</p>
              </div>
              <button className="cd-icon-btn" onClick={() => setCommonConfigOpen(false)} title="Close common configuration">
                <X size={16} />
              </button>
            </div>
            <div className="cd-config-modal-body">
              <div className="cd-config-tabs">
                {commonConfigKinds.map(kind => (
                  <button
                    key={kind}
                    className={commonConfigKind === kind ? 'active' : ''}
                    onClick={() => setCommonConfigKind(kind)}
                  >
                    {configFileName(kind)}
                  </button>
                ))}
              </div>
              <PropertyTable
                rows={commonConfigs[commonConfigKind]}
                hostIp=""
                onUseHostIp={() => {}}
                onChange={(key, value) => updateCommonConfigValue(commonConfigKind, key, value)}
              />
            </div>
            <div className="cd-config-modal-footer">
              <button className="cd-secondary-btn" onClick={() => setCommonConfigOpen(false)}>Done</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function PropertyTable({
  rows,
  hostIp,
  onUseHostIp,
  onChange,
}: {
  rows: PropertyRow[];
  hostIp: string;
  onUseHostIp: () => void;
  onChange: (key: string, value: string) => void;
}) {
  return (
    <div className="cd-property-table-wrap">
      <table className="cd-property-table">
        <thead>
          <tr>
            <th>Key</th>
            <th>Value</th>
            <th>Action</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(row => (
            <tr key={row.key} className={row.required && !row.value.trim() ? 'required-missing' : ''}>
              <td>
                <span className="cd-prop-key">{row.key}</span>
                {row.required && <small><b>*</b> Required</small>}
              </td>
              <td>
                <input
                  value={row.value}
                  onChange={e => onChange(row.key, e.target.value)}
                  placeholder={row.required ? 'Required before preview' : ''}
                />
              </td>
              <td>
                {hostIp && row.key.includes('host') ? (
                  <button type="button" onClick={onUseHostIp}>Use {hostIp}</button>
                ) : (
                  <button type="button" onClick={() => {
                    const next = window.prompt(`Edit ${row.key}`, row.value);
                    if (next !== null) onChange(row.key, next);
                  }}>
                    Edit
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function StatusBadge({ status }: { status: PrereqStatus }) {
  const normalized = status || 'IDLE';
  const icon = normalized === 'SUCCESS'
    ? <CheckCircle2 size={13} />
    : normalized === 'FAILED'
      ? <XCircle size={13} />
      : normalized === 'REBOOT_REQUIRED'
        ? <AlertTriangle size={13} />
      : normalized === 'RUNNING' || normalized === 'QUEUED'
        ? <Loader2 size={13} className="spin" />
        : null;
  return <span className={`cd-status ${normalized.toLowerCase()}`}>{icon}{normalized}</span>;
}
