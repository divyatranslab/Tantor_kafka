import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Activity, AlertTriangle, Database, HardDrive, RefreshCw, Check } from 'lucide-react';
import { Area, AreaChart, CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import './Monitoring.css';

interface MonitoringCluster {
  id: string;
  name: string;
  originType: 'INTERNAL' | 'EXTERNAL' | string;
  monitoringEnabled: boolean;
  kafkaExporterTarget?: string;
  jmxAvailable?: boolean;
  warning?: string;
}

interface MonitoringOverview {
  clusterId: string;
  name: string;
  originType: string;
  prometheusUrl?: string;
  kafkaExporterTarget?: string;
  jmxAvailable?: boolean;
  kafkaExporterUp?: number | null;
  jmxUp?: number | null;
  brokerCount?: number | null;
  topicCount?: number | null;
  partitionCount?: number | null;
  underReplicatedPartitions?: number | null;
  consumerLag?: number | null;
  messagesInPerSecond?: number | null;
  bytesInPerSecond?: number | null;
  bytesOutPerSecond?: number | null;
  jvmHeapUsedPercent?: number | null;
  brokerCpuPercent?: number | null;
  systemCpuPercent?: number | null;
  warnings?: string[];
  hostMemoryUsedPercent?: number | null;
}

interface MonitoringSample {
  time: string;
  brokers: number | null;
  topics: number | null;
  partitions: number | null;
  underReplicated: number | null;
  lag: number | null;
  messagesIn: number | null;
  bytesIn: number | null;
  bytesOut: number | null;
  heap: number | null;
  hostMemory: number | null;
  brokerCpu: number | null;
  systemCpu: number | null;
}

const formatNumber = (value?: number | null, digits = 0) => {
  if (value === undefined || value === null || Number.isNaN(value)) return '0';
  return value.toLocaleString(undefined, { maximumFractionDigits: digits, minimumFractionDigits: digits });
};

const formatBytes = (value?: number | null) => {
  if (value === undefined || value === null || Number.isNaN(value)) return '0 B';
  if (value <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let next = value;
  let unit = 0;
  while (next >= 1024 && unit < units.length - 1) {
    next /= 1024;
    unit += 1;
  }
  return `${next.toFixed(unit === 0 ? 0 : 2)} ${units[unit]}`;
};

const hasValue = (value?: number | null) => value !== undefined && value !== null && !Number.isNaN(value);

const chartNumber = (value?: number | null) => hasValue(value) ? Number(value) : null;

export function Monitoring() {
  const [selectedType, setSelectedType] = useState<'INTERNAL' | 'EXTERNAL'>('INTERNAL');
  const [clusters, setClusters] = useState<MonitoringCluster[]>([]);
  const [selectedClusterId, setSelectedClusterId] = useState('');

  const [overview, setOverview] = useState<MonitoringOverview | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [refreshInterval, setRefreshInterval] = useState(10); // Default 10 seconds
  const [history, setHistory] = useState<MonitoringSample[]>([]);

  // 1. Load clusters and hosts on mount
  const loadInitialData = async () => {
    setLoading(true);
    setError(null);
    try {
      const clustersRes = await fetch(`/api/v1/monitoring/clusters?type=${selectedType}`);
      let clusterList: MonitoringCluster[] = [];
      if (clustersRes.ok) {
        clusterList = await clustersRes.json();
        setClusters(clusterList);
      }

      setSelectedClusterId(current =>
        clusterList.some(cluster => cluster.id === current)
          ? current
          : (clusterList[0]?.id || '')
      );
    } catch (err: any) {
      console.error(err);
      setError('Failed to load initial monitoring data.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    setOverview(null);
    setHistory([]);
    loadInitialData();
  }, [selectedType]);

  // Fetch overview metrics for the selected cluster
  const loadOverview = useCallback(async (silent = false) => {
    if (!selectedClusterId) return;
    if (!silent) setLoading(true);
    try {
      // 1. Fetch Prometheus Metrics
      const res = await fetch(`/api/v1/monitoring/clusters/${selectedClusterId}/overview`);
      if (res.ok) {
        const data = await res.json();
        setOverview(data);
      }

    } catch (err) {
      console.error(err);
    } finally {
      if (!silent) setLoading(false);
    }
  }, [selectedClusterId]);

  useEffect(() => {
    if (selectedClusterId) {
      setHistory([]);
      loadOverview();
    }
  }, [selectedClusterId, loadOverview]);

  // Append sample to history when overview updates
  useEffect(() => {
    if (!overview) return;
    setHistory(current => {
      const next: MonitoringSample = {
        time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }),
        brokers: chartNumber(overview.brokerCount),
        topics: chartNumber(overview.topicCount),
        partitions: chartNumber(overview.partitionCount),
        underReplicated: chartNumber(overview.underReplicatedPartitions),
        lag: chartNumber(overview.consumerLag),
        messagesIn: chartNumber(overview.messagesInPerSecond),
        bytesIn: chartNumber(overview.bytesInPerSecond),
        bytesOut: chartNumber(overview.bytesOutPerSecond),
        heap: chartNumber(overview.jvmHeapUsedPercent),
        hostMemory: chartNumber(overview.hostMemoryUsedPercent),
        brokerCpu: chartNumber(overview.brokerCpuPercent),
        systemCpu: chartNumber(overview.systemCpuPercent),
      };
      return [...current, next].slice(-15); // keep 15 samples
    });
  }, [overview]);

  // Auto refresh loop
  useEffect(() => {
    if (!autoRefresh || !selectedClusterId) return;
    const timer = window.setInterval(() => {
      if (!document.hidden) {
        loadOverview(true);
      }
    }, refreshInterval * 1000);
    return () => window.clearInterval(timer);
  }, [autoRefresh, refreshInterval, loadOverview, selectedClusterId]);

  // Selected cluster helper
  const selectedCluster = useMemo(() => clusters.find(c => c.id === selectedClusterId), [clusters, selectedClusterId]);

  // Mock initial history if empty to generate pretty graphs immediately
  const graphHistory = history;
  const clusterTitle = overview?.name || selectedCluster?.name || 'Select a cluster';
  const exporterTarget = overview?.kafkaExporterTarget || selectedCluster?.kafkaExporterTarget;
  const kafkaExporterHealthy = overview?.kafkaExporterUp === 1;
  const jmxHealthy = overview?.jmxUp === 1;
  const kafkaExporterLabel = overview
    ? (kafkaExporterHealthy ? 'KAFKA_EXPORTER UP' : 'KAFKA_EXPORTER REQUIRED')
    : 'KAFKA_EXPORTER';
  const jmxLabel = overview
    ? (jmxHealthy ? 'JMX UP' : 'JMX REQUIRED')
    : 'JMX';
  const warningMessages = [
    selectedCluster?.warning,
    ...(overview?.warnings || []),
  ].filter((message): message is string => Boolean(message && message.trim()));
  const clusterTypeLabel = overview?.originType || selectedCluster?.originType || selectedType;
  const displayCpuUsage = overview?.brokerCpuPercent ?? overview?.systemCpuPercent;
  const displayMemoryUsage = overview?.jvmHeapUsedPercent ?? overview?.hostMemoryUsedPercent;

  return (
    <div className="monitoring-container animate-fade-in">
      {/* Header Section */}
      <div className="header-section">
        <div className="title-area">
          <Activity size={32} className="title-icon" />
          <div>
            <h1>Monitoring</h1>
            <p className="subtitle">Real-time Kafka & system metrics</p>
          </div>
        </div>

        {/* Controls */}
        <div className="controls-area">
          <select
            className="tantor-select"
            value={selectedType}
            onChange={event => {
              setSelectedType(event.target.value as 'INTERNAL' | 'EXTERNAL');
              setSelectedClusterId('');
            }}
          >
            <option value="INTERNAL">Internal</option>
            <option value="EXTERNAL">External</option>
          </select>

          {/* Cluster Selection */}
          {clusters.length > 0 && (
            <select
              className="tantor-select"
              value={selectedClusterId}
              onChange={e => {
                setSelectedClusterId(e.target.value);
              }}
            >
              {clusters.map(c => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </select>
          )}

          {/* Live Indicator */}
          <div className="live-pill-container" onClick={() => setAutoRefresh(prev => !prev)}>
            <span className={`live-pill-dot ${autoRefresh ? 'active' : ''}`}></span>
            <span className="live-pill-text">Live</span>
            <div className={`custom-checkbox ${autoRefresh ? 'checked' : ''}`}>
              {autoRefresh && <Check size={12} strokeWidth={3} className="custom-check-icon" />}
            </div>
          </div>

          {/* Interval Selection */}
          <div className="interval-pill-container">
            <select
              className="interval-select-element"
              value={refreshInterval}
              onChange={e => setRefreshInterval(Number(e.target.value))}
            >
              <option value={5}>5 Sec</option>
              <option value={10}>10 Sec</option>
              <option value={30}>30 Sec</option>
              <option value={60}>1 Min</option>
            </select>
          </div>

          {/* Manual Refresh Button */}
          <button
            className="manual-refresh-button"
            onClick={() => loadOverview()}
            disabled={loading}
          >
            <RefreshCw size={18} className={loading ? 'spin' : ''} />
          </button>
        </div>
      </div>

      {error && (
        <div className="error-banner">
          <p>{error}</p>
        </div>
      )}

      {warningMessages.length > 0 && (
        <div className="monitoring-warning-list">
          {warningMessages.map(message => (
            <div className="monitoring-warning" key={message}>
              <AlertTriangle size={16} />
              <span>{message}</span>
            </div>
          ))}
        </div>
      )}

      {/* Broker Details Header Card */}
      <div className="broker-details-card">
        <div className="broker-info">
          <h2>{clusterTitle}</h2>
          <p className="broker-meta">
            Exporter target: {exporterTarget || 'Not configured'}
            <span className="separator">|</span>
            {clusterTypeLabel}
          </p>
        </div>
        <div className="monitoring-status-group">
          <span className={`monitoring-connection-pill ${kafkaExporterHealthy ? 'up' : 'warn'}`}>
            <span className="status-dot"></span>
            {kafkaExporterLabel}
          </span>
          <span className={`monitoring-connection-pill ${jmxHealthy ? 'up' : 'warn'}`}>
            <span className="status-dot"></span>
            {jmxLabel}
          </span>
        </div>
      </div>

      {/* Real-time Performance Section */}
      <div className="performance-section-card">
        <div className="section-header-row">
          <h3>Real-time Performance</h3>
          <span className="live-performance-badge">
            <span className="live-dot active"></span> Live
          </span>
        </div>

        <div className="charts-grid-row">
          {/* CPU Usage Chart */}
          <div className="chart-box-wrapper">
            <div className="chart-box-header">
              <span>CPU Usage</span>
              <span className="chart-stat-value green">
                {hasValue(displayCpuUsage) ? `${formatNumber(displayCpuUsage, 1)}%` : '-'}
              </span>
            </div>
            <div className="chart-body-container">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={graphHistory} margin={{ top: 5, right: 5, left: -25, bottom: 5 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" />
                  <XAxis dataKey="time" tick={{ fontSize: 9, fill: '#94a3b8' }} />
                  <YAxis tick={{ fontSize: 9, fill: '#94a3b8' }} domain={[0, 100]} />
                  <Tooltip contentStyle={{ fontSize: '11px', borderRadius: '6px' }} />
                  <Line type="monotone" dataKey="systemCpu" stroke="#3b82f6" strokeWidth={1.5} dot={false} activeDot={{ r: 4 }} />
                </LineChart>
              </ResponsiveContainer>
            </div>
          </div>

          {/* Memory Usage Chart */}
          <div className="chart-box-wrapper">
            <div className="chart-box-header">
              <span>Memory Usage</span>
              <span className="chart-stat-value green">
                {hasValue(displayMemoryUsage) ? `${formatNumber(displayMemoryUsage, 1)}%` : '-'}
              </span>
            </div>
            <div className="chart-body-container">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={graphHistory} margin={{ top: 5, right: 5, left: -25, bottom: 5 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" />
                  <XAxis dataKey="time" tick={{ fontSize: 9, fill: '#94a3b8' }} />
                  <YAxis tick={{ fontSize: 9, fill: '#94a3b8' }} domain={[0, 100]} />
                  <Tooltip contentStyle={{ fontSize: '11px', borderRadius: '6px' }} />
                  <Line type="monotone" dataKey="heap" stroke="#10b981" strokeWidth={1.5} dot={false} activeDot={{ r: 4 }} />
                </LineChart>
              </ResponsiveContainer>
            </div>
          </div>

          {/* Messages In Chart */}
          <div className="chart-box-wrapper">
            <div className="chart-box-header">
              <span>Messages In</span>
              <span className="chart-stat-value red">
                {hasValue(overview?.messagesInPerSecond) ? `${formatNumber(overview?.messagesInPerSecond, 1)}/s` : '-'}
              </span>
            </div>
            <div className="chart-body-container">
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={graphHistory} margin={{ top: 5, right: 5, left: -25, bottom: 5 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" />
                  <XAxis dataKey="time" tick={{ fontSize: 9, fill: '#94a3b8' }} />
                  <YAxis tick={{ fontSize: 9, fill: '#94a3b8' }} />
                  <Tooltip contentStyle={{ fontSize: '11px', borderRadius: '6px' }} />
                  <Area type="monotone" dataKey="messagesIn" stroke="#c084fc" fill="#f3e8ff" strokeWidth={1.5} />
                </AreaChart>
              </ResponsiveContainer>
            </div>
          </div>
        </div>

        <div className="monitoring-detail-grid">
          <section className="monitoring-resource-panel">
            <div className="monitoring-section-title">
              <HardDrive size={16} />
              <span>System resources</span>
            </div>
            <ResourceBar label="Broker CPU" value={overview?.brokerCpuPercent} tone="blue" />
            <ResourceBar label="System CPU" value={overview?.systemCpuPercent} detail={overview?.systemCpuPercent == null ? 'No samples' : undefined} tone="green" />
            <ResourceBar label="JVM Heap" value={overview?.jvmHeapUsedPercent} tone="purple" />
            <ResourceBar label="Host Memory" value={overview?.hostMemoryUsedPercent} detail={overview?.hostMemoryUsedPercent == null ? 'Agent metric unavailable' : 'Agent heartbeat'} tone="blue" />
          </section>

          <section className="monitoring-broker-panel">
            <div className="monitoring-section-title">
              <Database size={16} />
              <span>Kafka broker</span>
            </div>
            <div className="monitoring-broker-grid">
              <BrokerStat label="Msg in/sec" value={formatNumber(overview?.messagesInPerSecond, 2)} />
              <BrokerStat label="Bytes in/sec" value={formatBytes(overview?.bytesInPerSecond)} />
              <BrokerStat label="Bytes out/sec" value={formatBytes(overview?.bytesOutPerSecond)} />
              <BrokerStat label="Partitions" value={formatNumber(overview?.partitionCount)} />
              <BrokerStat label="Under-replicated" value={formatNumber(overview?.underReplicatedPartitions)} />
              <BrokerStat label="Consumer lag" value={formatNumber(overview?.consumerLag)} />
              <BrokerStat label="Brokers" value={formatNumber(overview?.brokerCount)} />
              <BrokerStat label="Topics" value={formatNumber(overview?.topicCount)} />
            </div>
          </section>
        </div>
      </div>
    </div>
  );
}

const boundedPercent = (value?: number | null) => {
  if (!hasValue(value)) return 0;
  return Math.max(0, Math.min(100, Number(value)));
};

function ResourceBar({ label, value, detail, tone = 'blue' }: {
  label: string;
  value?: number | null;
  detail?: string;
  tone?: 'blue' | 'green' | 'purple';
}) {
  const percent = boundedPercent(value);
  return (
    <div className="monitoring-resource-row">
      <div className="monitoring-resource-row-header">
        <span>{label}{detail ? ` (${detail})` : ''}</span>
        <strong>{hasValue(value) ? `${formatNumber(value, 1)}%` : '-'}</strong>
      </div>
      <div className="monitoring-resource-track">
        <div className={`monitoring-resource-fill ${tone}`} style={{ width: `${percent}%` }} />
      </div>
    </div>
  );
}

function BrokerStat({ label, value }: { label: string; value: string }) {
  return (
    <div className="monitoring-broker-stat">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

