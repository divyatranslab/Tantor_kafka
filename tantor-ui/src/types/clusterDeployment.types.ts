export type Host = {
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

export type ClusterHost = {
  hostId?: string;
  role?: string;
  nodeId?: number;
};

export type ExistingCluster = {
  id: string;
  name: string;
  kafkaVersion: string;
  mode: string;
  environment?: string;
  kafkaClusterId?: string;
  config?: Record<string, unknown>;
  hosts?: ClusterHost[];
};

export type KafkaVersionInfo = {
  version: string;
  available: boolean;
  scala_version: string;
  release_date: string;
  size_mb: number;
  filename: string;
  id?: string;
};

export type DeploymentMode = 'kraft' | 'zookeeper';
export type RoleChoice = 'broker_controller' | 'broker' | 'controller' | 'separate' | 'broker_zookeeper' | 'zookeeper';
export type FlowStage = 'details' | 'preview';
export type ConfigMode = 'default' | 'custom';
export type ConfigKind = 'server' | 'broker' | 'controller' | 'zookeeper';
export type PrereqStatus = 'IDLE' | 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'REBOOT_REQUIRED';

export type ServiceAssignment = {
  host_id: string;
  role: RoleChoice;
  node_id: number;
  configuration_mode: ConfigMode;
  properties_template: string;
  heap_size: string;
  listener_port?: number;
  controller_port?: number;
  zookeeper_peer_port?: number;
  zookeeper_election_port?: number;
};

export type PropertyRow = {
  key: string;
  value: string;
  required?: boolean;
  locked?: boolean;
};

export type NodeConfigState = {
  mode: ConfigMode;
  rows: PropertyRow[];
  heapSize: string;
};

export type PrereqResult = {
  status: PrereqStatus;
  taskId?: string;
  logOutput: string;
  errorMsg: string;
};

export type KraftValidationNode = {
  hostId: string;
  address: string;
  nodeId: number;
  role: string;
};

export type KraftValidationReport = {
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
