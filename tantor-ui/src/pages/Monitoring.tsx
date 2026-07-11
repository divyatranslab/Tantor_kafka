import { useCallback, useEffect, useMemo, useState } from 'react';
import { Activity, AlertTriangle, Database, Gauge, HardDrive, RefreshCw, Server, ShieldCheck } from 'lucide-react';
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
  warnings?: string[];
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

function MetricCard({ icon: Icon, label, value, tone = 'neutral' }: {
  icon: typeof Activity;
  label: string;
  value: string;
  tone?: 'neutral' | 'good' | 'warn' | 'bad';
}) {
  return (
    <div className={`monitoring-metric-card ${tone}`}>
      <div className="monitoring-metric-icon"><Icon size={18} /></div>
      <div>
        <span>{label}</span>
        <strong>{value}</strong>
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
    setClusters([]);
    setSelectedClusterId('');
    if (type) {
      loadClusters(type);
    }
  }, [type, loadClusters]);

  useEffect(() => {
    if (selectedClusterId) {
      loadOverview();
    }
  }, [selectedClusterId, loadOverview]);

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
        <div className="monitoring-summary-band">
          <div>
            <span className="monitoring-eyebrow">{selectedCluster.originType}</span>
            <h2>{selectedCluster.name}</h2>
            <p>Exporter target: {overview?.kafkaExporterTarget || selectedCluster.kafkaExporterTarget || 'Not configured'}</p>
          </div>
          <div className="monitoring-status-pills">
            <span className={overview?.kafkaExporterUp && overview.kafkaExporterUp > 0 ? 'pill good' : 'pill warn'}>
              kafka_exporter {overview?.kafkaExporterUp && overview.kafkaExporterUp > 0 ? 'up' : 'pending'}
            </span>
            <span className={overview?.jmxUp && overview.jmxUp > 0 ? 'pill good' : 'pill neutral'}>
              JMX {overview?.jmxUp && overview.jmxUp > 0 ? 'up' : selectedCluster.jmxAvailable ? 'pending' : 'not attached'}
            </span>
          </div>
        </div>
      )}

      {type && selectedCluster && (
        <div className="monitoring-metrics-grid">
          <MetricCard icon={Server} label="Brokers" value={formatNumber(overview?.brokerCount)} tone={(overview?.brokerCount || 0) > 0 ? 'good' : 'warn'} />
          <MetricCard icon={Database} label="Topics" value={formatNumber(overview?.topicCount)} />
          <MetricCard icon={Database} label="Partitions" value={formatNumber(overview?.partitionCount)} />
          <MetricCard icon={ShieldCheck} label="Under Replicated" value={formatNumber(overview?.underReplicatedPartitions)} tone={(overview?.underReplicatedPartitions || 0) > 0 ? 'bad' : 'good'} />
          <MetricCard icon={Gauge} label="Consumer Lag" value={formatNumber(overview?.consumerLag)} tone={(overview?.consumerLag || 0) > 0 ? 'warn' : 'neutral'} />
          <MetricCard icon={Activity} label="Messages/sec" value={formatNumber(overview?.messagesInPerSecond, 1)} />
          <MetricCard icon={Activity} label="Bytes In/sec" value={formatBytes(overview?.bytesInPerSecond)} />
          <MetricCard icon={Activity} label="Bytes Out/sec" value={formatBytes(overview?.bytesOutPerSecond)} />
          <MetricCard icon={HardDrive} label="JVM Heap" value={overview?.jvmHeapUsedPercent == null ? 'JMX required' : `${formatNumber(overview.jvmHeapUsedPercent, 1)}%`} tone={overview?.jvmHeapUsedPercent == null ? 'neutral' : 'good'} />
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
