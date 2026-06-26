import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  AlertTriangle,
  Check,
  CheckCircle2,
  ChevronDown,
  Database,
  FileText,
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

type RoleChoice = 'broker_controller' | 'broker' | 'controller' | 'separate';
type FlowStage = 'landing' | 'details' | 'preview';
type ConfigMode = 'default' | 'custom';
type ConfigKind = 'server' | 'broker' | 'controller';
type PrereqStatus = 'IDLE' | 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED';

type ServiceAssignment = {
  host_id: string;
  role: 'broker_controller' | 'broker' | 'controller';
  node_id: number;
  configuration_mode: ConfigMode;
  properties_template: string;
  heap_size: string;
};

type NodeConfigState = {
  mode: ConfigMode;
  template: string;
  heapSize: string;
};

type PrereqResult = {
  status: PrereqStatus;
  taskId?: string;
  logOutput: string;
  errorMsg: string;
};

const ROLE_OPTIONS: Array<{ id: RoleChoice; label: string; detail: string }> = [
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
    label: 'Separate Broker and Controller',
    detail: 'Two Kafka services on the same VM using broker.properties and controller.properties.',
  },
];

const DEFAULT_CONTROLLER_PROPERTIES = `# =============================================================
# KRaft Controller Configuration
# =============================================================
process.roles=controller
node.id=101

# ---- Listeners ----
controller.listener.names=CONTROLLER
listeners=CONTROLLER://192.168.253.143:9093
listener.security.protocol.map=CONTROLLER:PLAINTEXT

# ---- KRaft Quorum ----
controller.quorum.voters=101@192.168.253.143:9093,102@192.168.253.136:9093
controller.quorum.bootstrap.servers=192.168.253.143:9093,192.168.253.136:9093

# ---- Metadata Storage ----
metadata.log.dir=/apache/kafka/controller-data/metadata

# ---- KRaft Timing ----
controller.quorum.election.timeout.ms=5000
controller.quorum.fetch.timeout.ms=5000
controller.quorum.election.backoff.max.ms=5000
controller.quorum.request.timeout.ms=10000

# ---- Metadata Retention ----
metadata.log.segment.bytes=1073741824
metadata.log.segment.ms=604800000
metadata.max.retention.bytes=-1
metadata.max.retention.ms=604800000

# ---- Network ----
num.network.threads=8
num.io.threads=16
socket.send.buffer.bytes=102400
socket.receive.buffer.bytes=102400
socket.request.max.bytes=104857600`;

const DEFAULT_BROKER_PROPERTIES = `# =============================================================
# KRaft Broker Configuration
# =============================================================
process.roles=broker
node.id=1
broker.id=1
broker.rack=rack1

# ---- Listeners ----
listeners=PLAINTEXT://192.168.253.143:9092
advertised.listeners=PLAINTEXT://192.168.253.143:9092
listener.security.protocol.map=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
inter.broker.listener.name=PLAINTEXT

# ---- KRaft Quorum ----
controller.listener.names=CONTROLLER
controller.quorum.voters=101@192.168.253.143:9093,102@192.168.253.136:9093
controller.quorum.bootstrap.servers=192.168.253.143:9093,192.168.253.136:9093

# ---- Metadata Storage ----
metadata.log.dir=/apache/kafka/broker-metadata

# ---- Data Storage ----
log.dirs=/apache/kafka/data
num.recovery.threads.per.data.dir=2

# ---- Topic Defaults ----
num.partitions=1
auto.create.topics.enable=false
default.replication.factor=3
min.insync.replicas=2

# ---- Internal Topic Replication ----
offsets.topic.replication.factor=3
offsets.topic.num.partitions=50
transaction.state.log.replication.factor=3
transaction.state.log.min.isr=2

# ---- Message Size ----
message.max.bytes=15728640
replica.fetch.max.bytes=15728640
socket.request.max.bytes=104857600
fetch.message.max.bytes=15728640

# ---- Log Retention ----
log.segment.bytes=1073741824
log.retention.hours=72
log.retention.check.interval.ms=300000

# ---- Replication ----
num.replica.fetchers=4
replica.lag.time.max.ms=30000

# ---- Network & IO ----
num.network.threads=8
num.io.threads=8
socket.send.buffer.bytes=102400
socket.receive.buffer.bytes=102400

# ---- Consumer Group ----
group.initial.rebalance.delay.ms=0`;

const DEFAULT_SERVER_PROPERTIES = `#############################
# KRaft Combined Server
#############################
process.roles=broker,controller
node.id=101

#############################
# Listeners
#############################
listeners=PLAINTEXT://192.168.253.143:9092,CONTROLLER://192.168.253.143:9093
advertised.listeners=PLAINTEXT://192.168.253.143:9092
listener.security.protocol.map=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
inter.broker.listener.name=PLAINTEXT
controller.listener.names=CONTROLLER

#############################
# Controller Quorum
#############################
controller.quorum.voters=101@192.168.253.143:9093,102@192.168.253.136:9093
controller.quorum.bootstrap.servers=192.168.253.143:9093,192.168.253.136:9093

#############################
# Storage
#############################
log.dirs=/apache/kafka/data
metadata.log.dir=/apache/kafka/controller-data/metadata

#############################
# Topic Defaults
#############################
num.partitions=1
auto.create.topics.enable=false
default.replication.factor=3
min.insync.replicas=2

#############################
# Internal Topics
#############################
offsets.topic.replication.factor=3
offsets.topic.num.partitions=50
transaction.state.log.replication.factor=3
transaction.state.log.min.isr=2

#############################
# Message Size
#############################
message.max.bytes=15728640
replica.fetch.max.bytes=15728640
fetch.message.max.bytes=15728640
socket.request.max.bytes=104857600

#############################
# Log Retention
#############################
log.segment.bytes=1073741824
log.retention.hours=72
log.retention.check.interval.ms=300000

#############################
# Replication
#############################
num.replica.fetchers=4
replica.lag.time.max.ms=30000

#############################
# Network
#############################
num.network.threads=8
num.io.threads=8
socket.send.buffer.bytes=102400
socket.receive.buffer.bytes=102400

#############################
# Controller Settings
#############################
controller.quorum.election.timeout.ms=5000
controller.quorum.fetch.timeout.ms=5000
controller.quorum.election.backoff.max.ms=5000
controller.quorum.request.timeout.ms=10000
metadata.log.segment.bytes=1073741824
metadata.log.segment.ms=604800000
metadata.max.retention.bytes=-1
metadata.max.retention.ms=604800000

#############################
# Consumer Groups
#############################
group.initial.rebalance.delay.ms=0

#############################
# Rack Awareness
#############################
broker.rack=rack1`;

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
  const [environment, setEnvironment] = useState('');
  const [installDir, setInstallDir] = useState('/opt');
  const [dataDir, setDataDir] = useState('/data/kafka');
  const [logDir, setLogDir] = useState('/var/log/kafka');
  const [artifactLoadDir, setArtifactLoadDir] = useState('/srv/yawar/kafka-artifacts');
  const [listenerPort, setListenerPort] = useState(9092);
  const [controllerPort, setControllerPort] = useState(9093);
  const [numPartitions, setNumPartitions] = useState(1);

  const [nodeSearch, setNodeSearch] = useState('');
  const [nodeDropdownOpen, setNodeDropdownOpen] = useState(false);
  const [draftNodeIds, setDraftNodeIds] = useState<string[]>([]);
  const [selectedNodeIds, setSelectedNodeIds] = useState<string[]>([]);
  const [rolesByHost, setRolesByHost] = useState<Record<string, RoleChoice>>({});
  const [configsByService, setConfigsByService] = useState<Record<string, NodeConfigState>>({});
  const [configModalHostId, setConfigModalHostId] = useState<string | null>(null);
  const [prereqResults, setPrereqResults] = useState<Record<string, PrereqResult>>({});
  const [checkingPrereqs, setCheckingPrereqs] = useState(false);
  const [deploying, setDeploying] = useState(false);

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
        const cfg = cluster.config || {};
        setInstallDir(String(cfg.kafka_install_base_dir || cfg.kafka_install_dir || '/opt'));
        setDataDir(String(cfg.kafka_data_dir || '/data/kafka'));
        setLogDir(String(cfg.kafka_app_log_dir || '/var/log/kafka'));
        setArtifactLoadDir(String(cfg.artifact_load_dir || '/srv/yawar/kafka-artifacts'));
        setListenerPort(Number(cfg.listener_port || 9092));
        setControllerPort(Number(cfg.controller_port || 9093));
        setNumPartitions(Number(cfg.num_partitions || 1));
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
      if (res.ok) setHosts(await res.json());
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
  const selectedHosts = selectedNodeIds
    .map(id => hosts.find(host => host.id === id))
    .filter(Boolean) as Host[];

  const filteredHosts = hosts.filter(host => {
    const needle = `${host.hostname} ${displayIp(host)} ${host.id}`.toLowerCase();
    return needle.includes(nodeSearch.toLowerCase());
  });

  const brokerCount = selectedHosts.filter(host => {
    const role = rolesByHost[host.id] || 'broker_controller';
    return role === 'broker_controller' || role === 'broker' || role === 'separate';
  }).length;

  const controllerCount = selectedHosts.filter(host => {
    const role = rolesByHost[host.id] || 'broker_controller';
    return role === 'broker_controller' || role === 'controller' || role === 'separate';
  }).length;

  const replication = useMemo(() => {
    if (brokerCount <= 1) return { factor: 1, minIsr: 1 };
    if (brokerCount === 2) return { factor: 2, minIsr: 1 };
    return { factor: 3, minIsr: 2 };
  }, [brokerCount]);

  const warnings = useMemo(() => {
    const items: string[] = [];
    if (brokerCount === 1) items.push('Only one broker selected. Kafka will run without data replication.');
    if (controllerCount === 1) items.push('Only one controller selected. Controller failover will not be available.');
    if (controllerCount > 1 && controllerCount % 2 === 0) items.push('Even controller count selected. Odd controller count is recommended for quorum voting.');
    if (isAddNodeMode && selectedHosts.some(host => {
      const role = rolesByHost[host.id] || 'broker_controller';
      return role === 'controller' || role === 'broker_controller' || role === 'separate';
    })) {
      items.push('Adding a controller changes KRaft quorum. Existing nodes may need updated configs and restart sequencing.');
    }
    return items;
  }, [brokerCount, controllerCount, isAddNodeMode, rolesByHost, selectedHosts]);

  const pathErrors = [
    validatePath(installDir, 'Install directory'),
    validatePath(dataDir, 'Data directory'),
    validatePath(logDir, 'Log directory'),
    validatePath(artifactLoadDir, 'Artifact/load directory'),
  ].filter(Boolean);

  const canPreview = clusterName.trim()
    && kafkaVersion
    && selectedHosts.length > 0
    && brokerCount > 0
    && controllerCount > 0
    && pathErrors.length === 0;

  const configModalHost = configModalHostId
    ? selectedHosts.find(host => host.id === configModalHostId) || null
    : null;

  const prerequisiteComplete = selectedHosts.length > 0
    && selectedHosts.every(host => prereqResults[host.id]?.status === 'SUCCESS');

  const configKey = (hostId: string, kind: ConfigKind) => `${hostId}:${kind}`;

  const defaultTemplateForKind = (kind: ConfigKind) => {
    if (kind === 'server') return DEFAULT_SERVER_PROPERTIES;
    if (kind === 'broker') return DEFAULT_BROKER_PROPERTIES;
    return DEFAULT_CONTROLLER_PROPERTIES;
  };

  const defaultHeapForKind = (kind: ConfigKind) => {
    if (kind === 'controller') return '512M';
    return '1G';
  };

  const configFileName = (kind: ConfigKind) => {
    if (kind === 'server') return 'server.properties';
    if (kind === 'broker') return 'broker.properties';
    return 'controller.properties';
  };

  const configKindsForRole = (role: RoleChoice): ConfigKind[] => {
    if (role === 'broker_controller') return ['server'];
    if (role === 'broker') return ['broker'];
    if (role === 'controller') return ['controller'];
    return ['broker', 'controller'];
  };

  const serviceConfigFor = (hostId: string, kind: ConfigKind): NodeConfigState => {
    const existing = configsByService[configKey(hostId, kind)];
    return existing || { mode: 'default', template: defaultTemplateForKind(kind), heapSize: defaultHeapForKind(kind) };
  };

  const updateServiceConfig = (hostId: string, kind: ConfigKind, patch: Partial<NodeConfigState>) => {
    setConfigsByService(prev => {
      const current = prev[configKey(hostId, kind)] || { mode: 'default', template: defaultTemplateForKind(kind), heapSize: defaultHeapForKind(kind) };
      return {
        ...prev,
        [configKey(hostId, kind)]: { ...current, ...patch },
      };
    });
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
      const role = rolesByHost[host.id] || 'broker_controller';
      const configFor = (kind: ConfigKind) => serviceConfigFor(host.id, kind);
      if (role === 'broker_controller') {
        const cfg = configFor('server');
        services.push({ host_id: host.id, role: 'broker_controller', node_id: allocateNodeId(101), configuration_mode: cfg.mode, properties_template: cfg.template, heap_size: cfg.heapSize });
      } else if (role === 'separate') {
        const controllerCfg = configFor('controller');
        const brokerCfg = configFor('broker');
        services.push({ host_id: host.id, role: 'controller', node_id: allocateNodeId(101), configuration_mode: controllerCfg.mode, properties_template: controllerCfg.template, heap_size: controllerCfg.heapSize });
        services.push({ host_id: host.id, role: 'broker', node_id: allocateNodeId(1), configuration_mode: brokerCfg.mode, properties_template: brokerCfg.template, heap_size: brokerCfg.heapSize });
      } else if (role === 'controller') {
        const cfg = configFor('controller');
        services.push({ host_id: host.id, role: 'controller', node_id: allocateNodeId(101), configuration_mode: cfg.mode, properties_template: cfg.template, heap_size: cfg.heapSize });
      } else {
        const cfg = configFor('broker');
        services.push({ host_id: host.id, role: 'broker', node_id: allocateNodeId(1), configuration_mode: cfg.mode, properties_template: cfg.template, heap_size: cfg.heapSize });
      }
    });

    return services;
  };

  const confirmNodeSelection = () => {
    setSelectedNodeIds(draftNodeIds);
    setRolesByHost(prev => {
      const next: Record<string, RoleChoice> = {};
      draftNodeIds.forEach(id => { next[id] = prev[id] || 'broker_controller'; });
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

  const checkPrerequisites = async () => {
    setCheckingPrereqs(true);
    const initial: Record<string, PrereqResult> = {};
    selectedHosts.forEach(host => {
      initial[host.id] = { status: 'QUEUED', logOutput: 'Queued prerequisite check.', errorMsg: '' };
    });
    setPrereqResults(initial);

    await Promise.all(selectedHosts.map(async host => {
      try {
        const res = await fetch(`/api/v1/ui/hosts/${host.id}/check-prerequisites`, { method: 'POST' });
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
          status: activeStatus(status) ? 'RUNNING' : status === 'SUCCESS' ? 'SUCCESS' : 'FAILED',
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

  const deployCluster = async () => {
    setDeploying(true);
    try {
      const selectedArtifact = versions.find(version => version.version === kafkaVersion);
      const artifactRepoBaseUrl = import.meta.env.VITE_ARTIFACT_REPO_URL || `http://${window.location.hostname || 'localhost'}:8081`;
      const payload = {
        name: clusterName.trim(),
        kafka_version: kafkaVersion,
        mode: 'kraft',
        services: buildServices(),
        environment: environment.trim().toLowerCase(),
        artifactUrl: selectedArtifact ? `${artifactRepoBaseUrl}/api/v1/artifacts/${selectedArtifact.id}/download` : '',
        config: {
          kafka_install_dir: installDir.trim(),
          kafka_install_base_dir: installDir.trim(),
          kafka_data_dir: dataDir.trim(),
          kafka_app_log_dir: logDir.trim(),
          artifact_load_dir: artifactLoadDir.trim(),
          scala_version: selectedArtifact?.scala_version || '2.13',
          listener_port: listenerPort,
          controller_port: controllerPort,
          num_partitions: numPartitions,
          replication_factor: replication.factor,
          min_insync_replicas: replication.minIsr,
        },
      };

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
      navigate(`/clusters/${body.id}/logs`);
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
            <small>Build a new KRaft cluster on selected Tantor hosts.</small>
          </button>
          <button className="cd-choice-card" onClick={() => navigate('/external-clusters')}>
            <Database size={26} />
            <span>Existing cluster</span>
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
              ? 'Existing cluster details are loaded. Select new nodes and roles to add.'
              : 'Define the cluster, select nodes, and choose roles.'
            : 'Run prerequisites across every selected node before deployment.'}</p>
        </div>
        <div className="cd-stage-tabs" aria-label="Deployment progress">
          <span className={stage === 'details' ? 'active' : ''}>Details</span>
          <span className={stage === 'preview' ? 'active' : ''}>Preview</span>
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
            <div className="cd-grid-2">
              <label className="cd-field">
                <span>Cluster name</span>
                <input value={clusterName} onChange={e => setClusterName(e.target.value)} placeholder="production-kraft" disabled={isAddNodeMode} />
              </label>
              <label className="cd-field">
                <span>Kafka version</span>
                <select value={kafkaVersion} onChange={e => setKafkaVersion(e.target.value)} disabled={isAddNodeMode || loadingVersions || versions.length === 0}>
                  {availableVersions.map(version => (
                    <option key={version.version} value={version.version}>
                      {version.version} ({version.size_mb} MB)
                    </option>
                  ))}
                  {availableVersions.length === 0 && <option>No available Kafka artifact</option>}
                </select>
              </label>
              <label className="cd-field">
                <span>Environment</span>
                <input value={environment} onChange={e => setEnvironment(e.target.value)} placeholder="prod, qa, staging" disabled={isAddNodeMode} />
              </label>
            </div>
          </section>

          <section className="cd-panel cd-config-guidance">
            <div className="cd-panel-title">
              <FileText size={18} />
              <h2>Configuration Files</h2>
            </div>
            <p className="cd-muted">
              Configuration is selected per node after you choose roles. Each node row opens only the file that applies to that role: server.properties, broker.properties, controller.properties, or broker plus controller files for separate services.
            </p>
          </section>

          <section className="cd-panel">
            <div className="cd-panel-title">
              <Server size={18} />
              <h2>Deployment Paths</h2>
            </div>
            <div className="cd-grid-2">
              <label className="cd-field">
                <span>Install directory</span>
                <input value={installDir} onChange={e => setInstallDir(e.target.value)} disabled={isAddNodeMode} />
              </label>
              <label className="cd-field">
                <span>Data directory</span>
                <input value={dataDir} onChange={e => setDataDir(e.target.value)} disabled={isAddNodeMode} />
              </label>
              <label className="cd-field">
                <span>Log directory</span>
                <input value={logDir} onChange={e => setLogDir(e.target.value)} disabled={isAddNodeMode} />
              </label>
              <label className="cd-field">
                <span>Artifact/load directory</span>
                <input value={artifactLoadDir} onChange={e => setArtifactLoadDir(e.target.value)} disabled={isAddNodeMode} />
              </label>
              <label className="cd-field">
                <span>Broker port</span>
                <input type="number" value={listenerPort} onChange={e => setListenerPort(Number(e.target.value))} disabled={isAddNodeMode} />
              </label>
              <label className="cd-field">
                <span>Controller port</span>
                <input type="number" value={controllerPort} onChange={e => setControllerPort(Number(e.target.value))} disabled={isAddNodeMode} />
              </label>
              <label className="cd-field">
                <span>Default partitions</span>
                <input type="number" min={1} value={numPartitions} onChange={e => setNumPartitions(Number(e.target.value))} disabled={isAddNodeMode} />
              </label>
            </div>
            {pathErrors.length > 0 && (
              <div className="cd-inline-errors">
                {pathErrors.map(error => <span key={error}><AlertTriangle size={13} /> {error}</span>)}
              </div>
            )}
          </section>

          <section className="cd-panel">
            <div className="cd-panel-title">
              <Network size={18} />
              <h2>Nodes and Roles</h2>
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
                      const disabled = host.status !== 'ONLINE' || host.available === false;
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
                            <small>{displayIp(host)} {disabled ? `- ${host.available === false ? 'unavailable' : host.status}` : ''}</small>
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
                  <select
                    value={rolesByHost[host.id] || 'broker_controller'}
                    onChange={e => {
                      setRolesByHost(prev => ({ ...prev, [host.id]: e.target.value as RoleChoice }));
                      setPrereqResults({});
                    }}
                  >
                    {ROLE_OPTIONS.map(role => <option key={role.id} value={role.id}>{role.label}</option>)}
                  </select>
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

            <div className="cd-calculated">
              <span>Broker count: <strong>{brokerCount}</strong></span>
              <span>Controller count: <strong>{controllerCount}</strong></span>
              <span>Replication factor: <strong>{replication.factor}</strong></span>
              <span>Min ISR: <strong>{replication.minIsr}</strong></span>
            </div>
            {warnings.length > 0 && (
              <div className="cd-warning-list">
                {warnings.map(warning => <span key={warning}><AlertTriangle size={13} /> {warning}</span>)}
              </div>
            )}
          </section>

          <div className="cd-footer-actions">
            <button className="cd-secondary-btn" onClick={() => isAddNodeMode ? navigate('/clusters') : setStage('landing')}>Back</button>
            <button className="cd-primary-btn" disabled={!canPreview} onClick={() => setStage('preview')}>
              {isAddNodeMode ? 'Preview add node' : 'Preview'}
            </button>
          </div>
        </div>
      ) : (
        <div className="cd-layout">
          <section className="cd-panel">
            <div className="cd-panel-title">
              <Network size={18} />
              <h2>Nodes Selected for Deployment</h2>
            </div>
            <div className="cd-preview-list">
              {selectedHosts.map(host => {
                const role = ROLE_OPTIONS.find(item => item.id === (rolesByHost[host.id] || 'broker_controller'));
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
          </section>

          <section className="cd-panel">
            <div className="cd-panel-title">
              <CheckCircle2 size={18} />
              <h2>Prerequisites</h2>
              <button className="cd-primary-btn small" disabled={checkingPrereqs || selectedHosts.length === 0} onClick={checkPrerequisites}>
                {checkingPrereqs ? <Loader2 size={14} className="spin" /> : <RefreshCw size={14} />}
                Check prerequisites on all nodes
              </button>
            </div>
            {checkingPrereqs && <div className="cd-progress"><span /></div>}
            <div className="cd-prereq-grid">
              {selectedHosts.map(host => {
                const result = prereqResults[host.id] || { status: 'IDLE', logOutput: '', errorMsg: '' };
                return (
                  <details className="cd-prereq-card" key={host.id} open={result.status === 'FAILED'}>
                    <summary>
                      <span>{host.hostname}</span>
                      <StatusBadge status={result.status} />
                    </summary>
                    <pre>{result.errorMsg ? `${result.errorMsg}\n\n` : ''}{result.logOutput || 'Waiting for prerequisite run...'}</pre>
                  </details>
                );
              })}
            </div>
          </section>

          <div className="cd-footer-actions">
            <button className="cd-secondary-btn" disabled={checkingPrereqs || deploying} onClick={() => setStage('details')}>Back to details</button>
            <button className="cd-primary-btn" disabled={!prerequisiteComplete || deploying} onClick={deployCluster}>
              {deploying ? <Loader2 size={15} className="spin" /> : <Play size={15} />}
              {isAddNodeMode ? 'Add node' : 'Deploy'}
            </button>
          </div>
        </div>
      )}
      {configModalHost && (
        <div className="cd-modal-backdrop" onClick={() => setConfigModalHostId(null)}>
          <div className="cd-config-modal" onClick={e => e.stopPropagation()}>
            <div className="cd-config-modal-header">
              <div>
                <h2>Configuration</h2>
                <p>{configModalHost.hostname} - {ROLE_OPTIONS.find(role => role.id === (rolesByHost[configModalHost.id] || 'broker_controller'))?.label}</p>
              </div>
              <button className="cd-icon-btn" onClick={() => setConfigModalHostId(null)} title="Close configuration">
                <X size={16} />
              </button>
            </div>

            <div className="cd-config-modal-body">
              {configKindsForRole(rolesByHost[configModalHost.id] || 'broker_controller').map(kind => {
                const cfg = serviceConfigFor(configModalHost.id, kind);
                return (
                  <div className="cd-node-config-editor" key={kind}>
                    <div className="cd-node-config-top">
                      <div>
                        <h3>{configFileName(kind)}</h3>
                        <p>Tantor will append generated values for this node during deployment.</p>
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
                        <div className="cd-segmented">
                          <button
                            className={cfg.mode === 'default' ? 'active' : ''}
                            onClick={() => updateServiceConfig(configModalHost.id, kind, { mode: 'default', template: defaultTemplateForKind(kind) })}
                          >
                            Default
                          </button>
                          <button
                            className={cfg.mode === 'custom' ? 'active' : ''}
                            onClick={() => updateServiceConfig(configModalHost.id, kind, { mode: 'custom' })}
                          >
                            Custom
                          </button>
                        </div>
                      </div>
                    </div>
                    <ConfigEditorBox
                      label={configFileName(kind)}
                      value={cfg.template}
                      onChange={value => updateServiceConfig(configModalHost.id, kind, { mode: 'custom', template: value })}
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
    </div>
  );
}

function ConfigEditorBox({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) {
  return (
    <label className="cd-config-box">
      <span>{label}</span>
      <textarea value={value} onChange={e => onChange(e.target.value)} spellCheck={false} />
    </label>
  );
}

function StatusBadge({ status }: { status: PrereqStatus }) {
  const normalized = status || 'IDLE';
  const icon = normalized === 'SUCCESS'
    ? <CheckCircle2 size={13} />
    : normalized === 'FAILED'
      ? <XCircle size={13} />
      : normalized === 'RUNNING' || normalized === 'QUEUED'
        ? <Loader2 size={13} className="spin" />
        : null;
  return <span className={`cd-status ${normalized.toLowerCase()}`}>{icon}{normalized}</span>;
}
