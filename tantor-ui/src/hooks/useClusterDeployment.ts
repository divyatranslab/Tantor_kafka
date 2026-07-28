import { useEffect, useMemo, useState, useRef } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { confirmAction, notifyAction } from '../components/ConfirmDialog';
import { apiFetch } from '../lib/apiClient';
import type {
  Host, ExistingCluster, KafkaVersionInfo, DeploymentMode, RoleChoice, FlowStage,
  ConfigMode, ConfigKind, PrereqResult, KraftValidationReport, NodeConfigState, PropertyRow, ServiceAssignment
} from '../types/clusterDeployment.types';

export const UI_ONLY_PROPERTY_KEYS = new Set(['node.host', 'advertised.host', 'controller.host', 'zookeeper.host']);

export const KRAFT_ROLE_OPTIONS: Array<{ id: RoleChoice; label: string; detail: string }> = [
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

export const ZOOKEEPER_ROLE_OPTIONS: Array<{ id: RoleChoice; label: string; detail: string }> = [
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

export const KRAFT_COMMON_CONFIG_KINDS: ConfigKind[] = ['server', 'broker', 'controller'];
export const ZOOKEEPER_COMMON_CONFIG_KINDS: ConfigKind[] = ['server', 'zookeeper'];
export const SYNCED_BROKER_PROPERTY_KEYS = new Set([
  'num.partitions',
  'default.replication.factor',
  'min.insync.replicas',
  'offsets.topic.replication.factor',
  'transaction.state.log.replication.factor',
  'transaction.state.log.min.isr',
]);

export function defaultCommonRows(kind: ConfigKind, mode: DeploymentMode): PropertyRow[] {
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

export function commonConfigKindsForMode(mode: DeploymentMode): ConfigKind[] {
  return mode === 'zookeeper' ? ZOOKEEPER_COMMON_CONFIG_KINDS : KRAFT_COMMON_CONFIG_KINDS;
}

export function createCommonConfigs(mode: DeploymentMode): Record<ConfigKind, PropertyRow[]> {
  return {
    server: defaultCommonRows('server', mode),
    broker: defaultCommonRows('broker', mode),
    controller: defaultCommonRows('controller', mode),
    zookeeper: defaultCommonRows('zookeeper', mode),
  };
}

export function kafkaMajorVersion(version: string): number {
  return Number.parseInt(String(version).split('.')[0] || '0', 10);
}

export function setRowsValue(rows: PropertyRow[], key: string, value: string): PropertyRow[] {
  return rows.map(row => row.key === key ? { ...row, value } : row);
}

export function syncCommonRows(rows: PropertyRow[], config: Record<string, unknown>): PropertyRow[] {
  return rows.map(row => {
    if (row.key === 'default.replication.factor' && config.replication_factor) return { ...row, value: String(config.replication_factor) };
    if (row.key === 'min.insync.replicas' && config.min_insync_replicas) return { ...row, value: String(config.min_insync_replicas) };
    if (row.key === 'num.partitions' && config.num_partitions) return { ...row, value: String(config.num_partitions) };
    return row;
  });
}

export function parseIpList(raw: unknown): string[] {
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

export function displayIp(host: Host): string {
  const ips = parseIpList(host.ip_address || host.ipAddress || host.ipAddresses);
  return ips.find(ip => ip.startsWith('192.168.'))
    || ips.find(ip => !ip.startsWith('127.') && !ip.startsWith('172.'))
    || ips[0]
    || 'Unknown';
}

export function validatePath(value: string, label: string): string {
  if (!value.trim()) return `${label} is required.`;
  if (!value.trim().startsWith('/')) return `${label} must be an absolute Linux path.`;
  if (value.split('/').includes('..')) return `${label} cannot contain "..".`;
  if (!/^\/[A-Za-z0-9/_\-.]{1,510}$/.test(value.trim())) return `${label} contains unsupported characters.`;
  return '';
}

export function serializeProperties(rows: PropertyRow[]): string {
  return rows
    .filter(row => row.key.trim() && !UI_ONLY_PROPERTY_KEYS.has(row.key.trim()) && String(row.value).trim())
    .map(row => `${row.key.trim()}=${row.value}`)
    .join('\n');
}

export function activeStatus(status: string): boolean {
  return ['PENDING', 'IN_PROGRESS', 'RUNNING', 'QUEUED'].includes(String(status || '').toUpperCase());
}

export function useClusterDeployment(onClose?: () => void) {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const addClusterId = searchParams.get('mode') === 'add' ? searchParams.get('clusterId') : null;
  const isAddNodeMode = Boolean(addClusterId);
  const [stage, setStage] = useState<FlowStage>('details');
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
  const [hostPorts, setHostPorts] = useState<Record<string, { listenerPort: number, controllerPort: number, zookeeperPeerPort: number, zookeeperElectionPort: number }>>({});
  const [portCheckResults, setPortCheckResults] = useState<Record<string, PrereqResult>>({});
  const [hoveredPortCheckHostId, setHoveredPortCheckHostId] = useState<string | null>(null);
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
  
  const dropdownRef = useRef<HTMLDivElement>(null);

  const [kraftRiskAcknowledged, setKraftRiskAcknowledged] = useState(false);
  const [showEnrollModal, setShowEnrollModal] = useState(false);
  const [openRoleMenuHostId, setOpenRoleMenuHostId] = useState<string | null>(null);
  const [roleMenuAnchor, setRoleMenuAnchor] = useState<HTMLElement | null>(null);

  useEffect(() => {
    loadHosts();
    loadVersions();
  }, []);

  useEffect(() => {
    if (!addClusterId) return;
    setStage('details');
    setLoadingCluster(true);
    apiFetch(`/api/v1/ui/clusters/${addClusterId}`)
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
        notifyAction('Failed to load cluster details for add-node mode.');
        navigate('/clusters');
      })
      .finally(() => setLoadingCluster(false));
  }, [addClusterId, navigate]);

  const loadHosts = async () => {
    setLoadingHosts(true);
    try {
      const res = await apiFetch('/api/v1/ui/hosts');
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
      const res = await apiFetch('/api/v1/artifacts?serviceType=KAFKA');
      const data = await res.json();
      const mapped = (data.content || []).map((a: Record<string, any>) => ({
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

  const canPreview = Boolean(clusterName.trim()
    && kafkaVersion
    && selectedHosts.length > 0
    && brokerCount > 0
    && (isAddNodeMode || (deploymentMode === 'kraft' ? controllerCount > 0 : zookeeperCount > 0)));

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

    const getHp = (hostId: string) => hostPorts[hostId] || { listenerPort, controllerPort, zookeeperPeerPort, zookeeperElectionPort };

    selectedHosts.forEach(host => {
      const role = rolesByHost[host.id] || defaultRoleForMode;
      const hp = getHp(host.id);
      const configFor = (kind: ConfigKind) => serviceConfigFor(host.id, kind);
      if (role === 'broker_controller') {
        const cfg = configFor('server');
        services.push({ host_id: host.id, role: 'broker_controller', node_id: allocateNodeId(1), configuration_mode: cfg.mode, properties_template: serviceTemplate('server', cfg), heap_size: cfg.heapSize, listener_port: hp.listenerPort, controller_port: hp.controllerPort });
      } else if (role === 'broker_zookeeper') {
        const brokerCfg = configFor('server');
        const zookeeperCfg = configFor('zookeeper');
        services.push({ host_id: host.id, role: 'broker', node_id: allocateNodeId(1), configuration_mode: brokerCfg.mode, properties_template: serviceTemplate('server', brokerCfg), heap_size: brokerCfg.heapSize, listener_port: hp.listenerPort });
        services.push({ host_id: host.id, role: 'zookeeper', node_id: allocateNodeId(1001), configuration_mode: zookeeperCfg.mode, properties_template: serviceTemplate('zookeeper', zookeeperCfg), heap_size: zookeeperCfg.heapSize, controller_port: hp.controllerPort, zookeeper_peer_port: hp.zookeeperPeerPort, zookeeper_election_port: hp.zookeeperElectionPort });
      } else if (role === 'separate') {
        const brokerCfg = configFor('broker');
        const controllerCfg = configFor('controller');
        services.push({ host_id: host.id, role: 'broker', node_id: allocateNodeId(1), configuration_mode: brokerCfg.mode, properties_template: serviceTemplate('broker', brokerCfg), heap_size: brokerCfg.heapSize, listener_port: hp.listenerPort });
        services.push({ host_id: host.id, role: 'controller', node_id: allocateNodeId(101), configuration_mode: controllerCfg.mode, properties_template: serviceTemplate('controller', controllerCfg), heap_size: controllerCfg.heapSize, controller_port: hp.controllerPort });
      } else if (role === 'controller') {
        const cfg = configFor('controller');
        services.push({ host_id: host.id, role: 'controller', node_id: allocateNodeId(101), configuration_mode: cfg.mode, properties_template: serviceTemplate('controller', cfg), heap_size: cfg.heapSize, controller_port: hp.controllerPort });
      } else if (role === 'zookeeper') {
        const cfg = configFor('zookeeper');
        services.push({ host_id: host.id, role: 'zookeeper', node_id: allocateNodeId(1001), configuration_mode: cfg.mode, properties_template: serviceTemplate('zookeeper', cfg), heap_size: cfg.heapSize, controller_port: hp.controllerPort, zookeeper_peer_port: hp.zookeeperPeerPort, zookeeper_election_port: hp.zookeeperElectionPort });
      } else {
        const kind: ConfigKind = deploymentMode === 'zookeeper' ? 'server' : 'broker';
        const cfg = configFor(kind);
        services.push({ host_id: host.id, role: 'broker', node_id: allocateNodeId(1), configuration_mode: cfg.mode, properties_template: serviceTemplate(kind, cfg), heap_size: cfg.heapSize, listener_port: hp.listenerPort });
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
      const res = await apiFetch('/api/v1/ui/clusters/validate-kraft', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(buildDeploymentPayload(false)),
      });
      const body = await res.json().catch(() => ({}));
      if (!res.ok) {
        notifyAction(body.error || body.message || 'KRaft topology validation failed.');
        return;
      }
      const report = body as KraftValidationReport;
      setKraftValidation(report);
      setKraftGeneratedConfig(report.generatedConfig || {});
      setStage('preview');
    } catch (error) {
      console.error(error);
      notifyAction('Network error while validating the KRaft topology.');
    } finally {
      setValidatingKraft(false);
    }
  };

  const confirmNodeSelection = () => {
    setPrereqResults({});
    setNodeDropdownOpen(false);
  };

  const toggleNodeSelection = (hostId: string) => {
    setSelectedNodeIds(prev => {
      const isSelected = prev.includes(hostId);
      let nextSelected;
      if (isSelected) {
        nextSelected = prev.filter(id => id !== hostId);
      } else {
        nextSelected = [...prev, hostId];
      }
      
      setRolesByHost(rolesPrev => {
        const nextRoles = { ...rolesPrev };
        if (!isSelected) {
          nextRoles[hostId] = defaultRoleForMode;
        } else {
          delete nextRoles[hostId];
        }
        return nextRoles;
      });
      
      return nextSelected;
    });
    setPrereqResults({});
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

  const getHostPorts = (hostId: string) => hostPorts[hostId] || { listenerPort, controllerPort, zookeeperPeerPort, zookeeperElectionPort };

  const updateHostPort = (hostId: string, key: string, value: number) => {
    setHostPorts(prev => ({
      ...prev,
      [hostId]: { ...getHostPorts(hostId), [key]: value }
    }));
  };

  const prerequisitePortsForHost = (hostId: string): number[] => {
    const role = rolesByHost[hostId] || defaultRoleForMode;
    const ports = new Set<number>();
    const hp = getHostPorts(hostId);
    const hasBroker = ['broker', 'broker_controller', 'separate', 'broker_zookeeper'].includes(role);
    if (hasBroker) {
      ports.add(hp.listenerPort);
      ports.add(7071);
    }
    if (deploymentMode === 'kraft' && ['controller', 'broker_controller', 'separate'].includes(role)) {
      ports.add(hp.controllerPort);
    }
    if (deploymentMode === 'zookeeper' && ['zookeeper', 'broker_zookeeper'].includes(role)) {
      ports.add(hp.controllerPort);
      ports.add(hp.zookeeperPeerPort);
      ports.add(hp.zookeeperElectionPort);
    }
    return Array.from(ports);
  };

  const pollPortCheck = async (hostId: string, taskId: string) => {
    for (let i = 0; i < 90; i++) {
      await new Promise(resolve => setTimeout(resolve, 1500));
      const res = await apiFetch(`/api/v1/ui/hosts/${hostId}/check-prerequisites/${taskId}`);
      if (!res.ok) continue;

      const body = await res.json();
      const status = String(body.status || 'RUNNING').toUpperCase();
      setPortCheckResults(prev => ({
        ...prev,
        [hostId]: {
          status: activeStatus(status) ? 'RUNNING' : status === 'SUCCESS' ? 'SUCCESS' : 'FAILED',
          taskId,
          logOutput: body.logOutput || prev[hostId]?.logOutput || '',
          errorMsg: body.errorMsg || '',
        },
      }));
      if (!activeStatus(status)) return;
    }

    setPortCheckResults(prev => ({
      ...prev,
      [hostId]: {
        status: 'FAILED',
        taskId,
        logOutput: prev[hostId]?.logOutput || '',
        errorMsg: 'Port check timed out while waiting for the host agent.',
      },
    }));
  };

  const checkHostPorts = async (hostId: string) => {
    const requiredPorts = prerequisitePortsForHost(hostId);
    if (requiredPorts.length === 0) {
      setPortCheckResults(prev => ({
        ...prev,
        [hostId]: { status: 'FAILED', logOutput: '', errorMsg: 'No required ports are configured for this host.' },
      }));
      return;
    }

    setPortCheckResults(prev => ({
      ...prev,
      [hostId]: { status: 'RUNNING', logOutput: 'Queuing port availability check...', errorMsg: '' },
    }));

    try {
      const res = await apiFetch(`/api/v1/ui/hosts/${hostId}/check-ports`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ required_ports: requiredPorts.join(',') }),
      });
      const body = await res.json().catch(() => ({}));
      if (!res.ok || !body.taskId) {
        throw new Error(body.message || 'Failed to queue port availability check.');
      }

      setPortCheckResults(prev => ({
        ...prev,
        [hostId]: {
          status: 'RUNNING',
          taskId: body.taskId,
          logOutput: 'Port check queued. Waiting for the host agent...',
          errorMsg: '',
        },
      }));
      await pollPortCheck(hostId, body.taskId);
    } catch (error) {
      setPortCheckResults(prev => ({
        ...prev,
        [hostId]: {
          status: 'FAILED',
          logOutput: '',
          errorMsg: error instanceof Error ? error.message : 'Failed to check ports.',
        },
      }));
    }
  };

  const getPortTooltipText = (hostId: string) => {
    const result = portCheckResults[hostId];
    if (!result) return 'Click to check ports';
    if (result.status === 'RUNNING') return 'Checking ports...';

    const log = result.logOutput || '';
    const lines = log.split('\n');
    const available: number[] = [];
    const unavailable: number[] = [];

    lines.forEach(line => {
      const match = line.match(/Port (\d+):\s*(Available|Unavailable)/i);
      if (match) {
        const port = parseInt(match[1], 10);
        if (match[2].toLowerCase() === 'available') {
          available.push(port);
        } else {
          unavailable.push(port);
        }
      }
    });

    if (result.status === 'SUCCESS') {
      if (available.length > 0) {
        return `Available ports:\n${available.map(p => `• Port ${p}`).join('\n')}`;
      }
      return 'All required ports are available';
    }

    if (unavailable.length > 0 || available.length > 0) {
      const parts: string[] = [];
      if (unavailable.length > 0) {
        parts.push(`Unavailable (in use):\n${unavailable.map(p => `• Port ${p}`).join('\n')}`);
      }
      if (available.length > 0) {
        parts.push(`Available (free):\n${available.map(p => `• Port ${p}`).join('\n')}`);
      }
      return parts.join('\n\n');
    }

    return result.errorMsg || 'Failed to check ports';
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
        const res = await apiFetch(`/api/v1/ui/hosts/${host.id}/check-prerequisites`, {
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
      const res = await apiFetch(`/api/v1/ui/hosts/${hostId}/check-prerequisites/${taskId}`);
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
    const confirmed = await confirmAction(
      `Apply privileged operating-system changes on ${failedHosts.length} host(s)? This may update limits, sysctl, THP, SELinux, time synchronization, and may require a reboot.`,
    );
    if (!confirmed) return;

    setCheckingPrereqs(true);
    await Promise.all(failedHosts.map(async host => {
      try {
        const res = await apiFetch(`/api/v1/ui/hosts/${host.id}/fix-prerequisites`, { method: 'POST' });
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
    if (!(await confirmAction(`Reboot ${host.hostname}? The host and agent will be temporarily offline.`))) return;
    setCheckingPrereqs(true);
    try {
      const res = await apiFetch(`/api/v1/ui/hosts/${host.id}/reboot`, {
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
      const res = await apiFetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      const body = await res.json().catch(() => ({}));
      if (!res.ok) {
        notifyAction(body.error || body.message || 'Deployment failed to start.');
        return;
      }
      if (onClose) {
        onClose();
      }
      if (body.jobId) {
        navigate(`/jobs/${body.jobId}`);
      } else {
        navigate(`/clusters/${body.id}/logs`);
      }
    } catch (e) {
      console.error(e);
      notifyAction('Network error while starting deployment.');
    } finally {
      setDeploying(false);
    }
  };

  return {
    navigate,
    onClose,
    addClusterId,
    isAddNodeMode,
    stage,
    setStage,
    hosts,
    versions,
    existingCluster,
    loadingHosts,
    loadingVersions,
    loadingCluster,
    clusterName,
    setClusterName,
    kafkaVersion,
    setKafkaVersion,
    environment,
    setEnvironment,
    clusterConfigMode,
    selectClusterConfigMode,
    customImportSummary,
    importCustomCsv,
    downloadCustomTemplate,
    deploymentMode,
    changeDeploymentMode,
    changeKafkaVersion,
    installDir,
    setInstallDir,
    dataDir,
    setDataDir,
    logDir,
    setLogDir,
    artifactLoadDir,
    setArtifactLoadDir,
    listenerPort,
    setListenerPort,
    controllerPort,
    setControllerPort,
    zookeeperPeerPort,
    setZookeeperPeerPort,
    zookeeperElectionPort,
    setZookeeperElectionPort,
    hostPorts,
    updateHostPort,
    portCheckResults,
    hoveredPortCheckHostId,
    setHoveredPortCheckHostId,
    checkHostPorts,
    getPortTooltipText,
    numPartitions,
    nodeSearch,
    setNodeSearch,
    nodeDropdownOpen,
    setNodeDropdownOpen,
    dropdownRef,
    draftNodeIds,
    setDraftNodeIds,
    selectedNodeIds,
    toggleNodeSelection,
    confirmNodeSelection,
    removeNode,
    rolesByHost,
    setRolesByHost,
    allRoleOptions,
    roleOptions,
    defaultRoleForMode,
    configsByService,
    updateServiceConfig,
    updatePropertyValue,
    serviceConfigFor,
    configFileName,
    configKindsForRole,
    commonConfigs,
    commonConfigKinds,
    commonConfigKind,
    setCommonConfigKind,
    updateCommonConfigValue,
    configModalHostId,
    setConfigModalHostId,
    commonConfigOpen,
    setCommonConfigOpen,
    prereqResults,
    checkPrerequisites,
    checkingPrereqs,
    fixPrerequisites,
    rebootHost,
    deploying,
    deployCluster,
    validatingKraft,
    kraftValidation,
    kraftGeneratedConfig,
    openPreview,
    kraftRiskAcknowledged,
    setKraftRiskAcknowledged,
    showEnrollModal,
    setShowEnrollModal,
    loadHosts,
    openRoleMenuHostId,
    setOpenRoleMenuHostId,
    roleMenuAnchor,
    setRoleMenuAnchor,
    zookeeperSupported,
    availableVersions,
    filteredHosts,
    selectedHosts,
    warnings,
    replicationWarnings,
    pathErrors,
    configBlockingIssues,
    canPreview,
    configModalHost,
    prerequisiteComplete,
    kraftDeploymentBlocked,
    activeStatus,
    ipRowKeyForKind,
    defaultHeapForKind,
    setPrereqResults,
    setPortCheckResults
  };
}
