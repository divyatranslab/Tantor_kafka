import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Activity, AlertTriangle, Database, HardDrive, RefreshCw, Server } from 'lucide-react';
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
  if (value === undefined || value === null || Number.isNaN(value)) return '-';
  return value.toLocaleString(undefined, { maximumFractionDigits: digits, minimumFractionDigits: digits });
};

const formatBytes = (value?: number | null) => {
  if (!value || value <= 0) return '-';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let next = value;
  let unit = 0;
  while (next >= 1024 && unit < units.length - 1) {
    next /= 1024;
    unit += 1;
  }
  return `${next.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}/s`;
};

const hasValue = (value?: number | null) => value !== undefined && value !== null && !Number.isNaN(value);

const chartNumber = (value?: number | null) => hasValue(value) ? Number(value) : null;

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

function GraphPanel({ title, value, source, children, emptyText }: {
  title: string;
  value: string;
  source: string;
  children: ReactNode;
  emptyText: string;
}) {
  return (
    <div className="monitoring-graph-card">
      <div className="monitoring-graph-header">
        <div>
          <span>{source}</span>
          <h3>{title}</h3>
        </div>
        <strong>{value}</strong>
      </div>
      <div className="monitoring-chart-body">
        {children || <div className="monitoring-chart-empty">{emptyText}</div>}
      </div>
    </div>
  );
}

export function Monitoring() {
  const [type, setType] = useState<'INTERNAL' | 'EXTERNAL' | ''>('');
  const [clusters, setClusters] = useState<MonitoringCluster[]>([]);
  const [selectedClusterId, setSelectedClusterId] = useState('');
  const [overview, setOverview] = useState<MonitoringOverview | null>(null);
  const [loadingClusters, setLoadingClusters] = useState(false);
  const [loadingOverview, setLoadingOverview] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [history, setHistory] = useState<MonitoringSample[]>([]);

  const selectedCluster = useMemo(
    () => clusters.find(cluster => cluster.id === selectedClusterId),
    [clusters, selectedClusterId],
  );

  const loadClusters = useCallback(async (nextType = type) => {
    if (!nextType) return;
    setLoadingClusters(true);
    setError(null);
    try {
      const res = await fetch(`/api/v1/monitoring/clusters?type=${nextType}`);
      if (!res.ok) throw new Error('Failed to load monitoring clusters');
      const data: MonitoringCluster[] = await res.json();
      setClusters(data);
      setSelectedClusterId(current => {
        if (current && data.some(cluster => cluster.id === current)) return current;
        return data[0]?.id || '';
      });
    } catch (err: any) {
      setError(err.message || 'Failed to load monitoring clusters');
      setClusters([]);
      setSelectedClusterId('');
    } finally {
      setLoadingClusters(false);
    }
  }, [type]);

  const loadOverview = useCallback(async (silent = false) => {
    if (!selectedClusterId) return;
    if (!silent) setLoadingOverview(true);
    setError(null);
    try {
      const res = await fetch(`/api/v1/monitoring/clusters/${selectedClusterId}/overview`);
      if (!res.ok) throw new Error('Failed to load Prometheus metrics');
      setOverview(await res.json());
    } catch (err: any) {
      if (!silent) setError(err.message || 'Failed to load Prometheus metrics');
      setOverview(null);
    } finally {
      if (!silent) setLoadingOverview(false);
    }
  }, [selectedClusterId]);

  useEffect(() => {
    setOverview(null);
    setHistory([]);
    setClusters([]);
    setSelectedClusterId('');
    if (type) {
      loadClusters(type);
    }
  }, [type, loadClusters]);

  useEffect(() => {
    if (selectedClusterId) {
      setHistory([]);
      loadOverview();
    }
  }, [selectedClusterId, loadOverview]);

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
      return [...current, next].slice(-24);
    });
  }, [overview]);

  useEffect(() => {
    if (!autoRefresh || !selectedClusterId) return;
    const timer = window.setInterval(() => {
      if (!document.hidden) {
        loadOverview(true);
      }
    }, 15000);
    return () => window.clearInterval(timer);
  }, [autoRefresh, loadOverview, selectedClusterId]);

  const warnings = [
    ...(selectedCluster?.warning ? [selectedCluster.warning] : []),
    ...(overview?.warnings || []),
  ].filter((warning, index, all) => warning && all.indexOf(warning) === index);

  const kafkaExporterReady = Boolean(overview?.kafkaExporterUp && overview.kafkaExporterUp > 0);
  const jmxReady = Boolean(overview?.jmxUp && overview.jmxUp > 0);
  const hasTrafficSeries = history.some(sample => hasValue(sample.messagesIn) || hasValue(sample.lag));
  const hasCpuSeries = history.some(sample => hasValue(sample.brokerCpu) || hasValue(sample.systemCpu));
  const hasHeapSeries = history.some(sample => hasValue(sample.heap) || hasValue(sample.hostMemory));

  return (
    <div className="monitoring-container animate-fade-in">
      <div className="header-section">
        <div className="title-area">
          <Activity size={32} className="title-icon" />
          <div>
            <h1>Live Monitoring</h1>
            <p className="subtitle">Prometheus-backed Kafka metrics from kafka_exporter and JMX exporter.</p>
          </div>
        </div>

        <div className="controls-area">
          <select className="tantor-select" value={type} onChange={event => setType(event.target.value as 'INTERNAL' | 'EXTERNAL' | '')}>
            <option value="">Select source</option>
            <option value="INTERNAL">Internal</option>
            <option value="EXTERNAL">External</option>
          </select>

          {type && (
            <select className="tantor-select" value={selectedClusterId} onChange={event => setSelectedClusterId(event.target.value)}>
              {clusters.length === 0 && <option value="">No clusters</option>}
              {clusters.map(cluster => (
                <option key={cluster.id} value={cluster.id}>{cluster.name}</option>
              ))}
            </select>
          )}

          {type && (
            <label className="auto-refresh-toggle">
              <input type="checkbox" checked={autoRefresh} onChange={event => setAutoRefresh(event.target.checked)} />
              <span>Live {autoRefresh ? '15s' : 'off'}</span>
            </label>
          )}

          {type && (
            <button className="tantor-btn primary" onClick={() => selectedClusterId ? loadOverview() : loadClusters()} disabled={loadingClusters || loadingOverview}>
              <RefreshCw size={16} className={loadingClusters || loadingOverview ? 'spin' : ''} />
              Refresh
            </button>
          )}
        </div>
      </div>

      {!type && (
        <div className="monitoring-choice-empty">
          <Activity size={42} />
          <h3>Select a monitoring source</h3>
          <p>Choose Internal or External. Monitoring stays separate from the agent heartbeat and deployment flow.</p>
        </div>
      )}

      {error && (
        <div className="error-banner">
          <p>{error}</p>
        </div>
      )}

      {type && warnings.length > 0 && (
        <div className="monitoring-warning-list">
          {warnings.map(warning => (
            <div className="monitoring-warning" key={warning}>
              <AlertTriangle size={16} />
              <span>{warning}</span>
            </div>
          ))}
        </div>
      )}

      {type && selectedCluster && (
        <div className="monitoring-node-card">
          <div className="monitoring-node-header">
            <div className="monitoring-node-title">
              <Server size={22} />
              <div>
                <h2>{selectedCluster.name}</h2>
                <p>Exporter target: {overview?.kafkaExporterTarget || selectedCluster.kafkaExporterTarget || 'Not configured'}</p>
              </div>
              <span className="monitoring-node-pill">{selectedCluster.originType}</span>
            </div>
            <div className="monitoring-status-pills">
              <span className={kafkaExporterReady ? 'pill good' : 'pill warn'}>
                kafka_exporter {kafkaExporterReady ? 'up' : 'required'}
              </span>
              <span className={jmxReady ? 'pill good' : 'pill warn'}>
                JMX {jmxReady ? 'up' : 'required'}
              </span>
            </div>
          </div>

          <div className="monitoring-node-body">
            <div className="monitoring-section-title">
              <Activity size={16} />
              <span>Real-time performance</span>
            </div>

            <div className="monitoring-performance-grid">
              <GraphPanel title="CPU Usage" value={overview?.brokerCpuPercent == null ? '-' : `${formatNumber(overview.brokerCpuPercent, 1)}%`} source="JMX exporter" emptyText="JMX exporter required">
                {hasCpuSeries ? (
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart data={history} margin={{ top: 8, right: 8, left: -18, bottom: 0 }}>
                      <CartesianGrid strokeDasharray="3 3" stroke="#edf1f7" />
                      <XAxis dataKey="time" tick={{ fontSize: 11 }} minTickGap={24} />
                      <YAxis tick={{ fontSize: 11 }} domain={[0, 100]} />
                      <Tooltip />
                      <Line type="monotone" dataKey="brokerCpu" name="Broker CPU %" stroke="#3b82f6" strokeWidth={2} dot={{ r: 3 }} connectNulls />
                      <Line type="monotone" dataKey="systemCpu" name="System CPU %" stroke="#8b5cf6" strokeWidth={2} dot={{ r: 3 }} connectNulls />
                    </LineChart>
                  </ResponsiveContainer>
                ) : null}
              </GraphPanel>

              <GraphPanel title="Memory Usage" value={overview?.jvmHeapUsedPercent == null ? '-' : `${formatNumber(overview.jvmHeapUsedPercent, 1)}%`} source="JMX exporter" emptyText="JMX exporter required">
                {hasHeapSeries ? (
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart data={history} margin={{ top: 8, right: 8, left: -18, bottom: 0 }}>
                      <CartesianGrid strokeDasharray="3 3" stroke="#edf1f7" />
                      <XAxis dataKey="time" tick={{ fontSize: 11 }} minTickGap={24} />
                      <YAxis tick={{ fontSize: 11 }} domain={[0, 100]} />
                      <Tooltip />
                      <Line type="monotone" dataKey="heap" name="JVM heap %" stroke="#10b981" strokeWidth={2} dot={{ r: 3 }} connectNulls />
                      <Line type="monotone" dataKey="hostMemory" name="Host RAM %" stroke="#f59e0b" strokeWidth={2} dot={{ r: 3 }} connectNulls />
                    </LineChart>
                  </ResponsiveContainer>
                ) : null}
              </GraphPanel>

              <GraphPanel title="Messages In" value={formatNumber(overview?.messagesInPerSecond, 1)} source="kafka_exporter" emptyText={kafkaExporterReady ? "No traffic data available" : "kafka_exporter required"}>
                {hasTrafficSeries ? (
                  <ResponsiveContainer width="100%" height="100%">
                    <AreaChart data={history} margin={{ top: 8, right: 8, left: -18, bottom: 0 }}>
                      <CartesianGrid strokeDasharray="3 3" stroke="#edf1f7" />
                      <XAxis dataKey="time" tick={{ fontSize: 11 }} minTickGap={24} />
                      <YAxis tick={{ fontSize: 11 }} />
                      <Tooltip />
                      <Area type="monotone" dataKey="messagesIn" name="Messages/sec" stroke="#ef4444" fill="#fee2e2" strokeWidth={2} connectNulls />
                    </AreaChart>
                  </ResponsiveContainer>
                ) : null}
              </GraphPanel>
            </div>

            <div className="monitoring-detail-grid">
              <section className="monitoring-resource-panel">
                <div className="monitoring-section-title">
                  <HardDrive size={16} />
                  <span>System resources</span>
                </div>
                <ResourceBar label="Broker CPU" value={overview?.brokerCpuPercent} tone="blue" />
                <ResourceBar label="System CPU" value={overview?.systemCpuPercent} tone="green" />
                <ResourceBar label="JVM Heap" value={overview?.jvmHeapUsedPercent} tone="purple" />
                <ResourceBar label="Host Memory" value={overview?.hostMemoryUsedPercent} detail="Agent heartbeat" tone="blue" />
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
      )}

      {type && !loadingClusters && clusters.length === 0 && (
        <div className="monitoring-choice-empty">
          <Database size={42} />
          <h3>No {type.toLowerCase()} clusters found</h3>
          <p>Register or deploy a cluster first, then enable kafka_exporter for Prometheus discovery.</p>
        </div>
      )}
    </div>
  );
}
