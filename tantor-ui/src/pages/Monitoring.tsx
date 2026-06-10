import { useState, useEffect } from 'react';
import { Activity, Server, Database, HardDrive, RefreshCw } from 'lucide-react';
import './Monitoring.css';

interface SystemMetrics {
  cpuUsagePct: number;
  memTotalMb: number;
  memUsedMb: number;
  diskTotalGb: number;
  diskUsedGb: number;
}

interface KafkaMetrics {
  messagesInPerSec: number;
  bytesInPerSec: number;
  bytesOutPerSec: number;
  underReplicatedPartitions: number;
  partitionCount: number;
  activeControllerCount: number;
  networkProcessorAvgIdlePercent: number;
  offlineReplicaCount: number;
}

interface NodeMetrics {
  hostId: string;
  hostname: string;
  role: string;
  nodeId: number;
  system: SystemMetrics;
  kafka: KafkaMetrics;
}

interface ClusterMetrics {
  nodes: NodeMetrics[];
}

interface Cluster {
  id: string;
  name: string;
}

export function Monitoring() {
  const [clusters, setClusters] = useState<Cluster[]>([]);
  const [selectedCluster, setSelectedCluster] = useState<string>('');
  const [metrics, setMetrics] = useState<ClusterMetrics | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetch('/api/v1/ui/clusters')
      .then(res => res.json())
      .then(data => {
        setClusters(data);
        if (data.length > 0) {
          setSelectedCluster(data[0].id);
        }
      })
      .catch(err => console.error("Failed to load clusters", err));
  }, []);

  const fetchMetrics = () => {
    if (!selectedCluster) return;
    setLoading(true);
    setError(null);
    fetch(`/api/v1/ui/clusters/${selectedCluster}/metrics`)
      .then(res => {
        if (!res.ok) throw new Error('Failed to fetch metrics');
        return res.json();
      })
      .then(data => setMetrics(data))
      .catch(err => setError(err.message))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    fetchMetrics();
    const interval = setInterval(fetchMetrics, 10000);
    return () => clearInterval(interval);
  }, [selectedCluster]);

  const formatBytes = (bytes: number) => {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  return (
    <div className="monitoring-container animate-fade-in">
      <div className="header-section glass-panel">
        <div className="title-area">
          <Activity size={32} className="title-icon text-accent" />
          <div>
            <h1>Live Monitoring</h1>
            <p className="subtitle">Real-time Kafka & system metrics via JMX</p>
          </div>
        </div>

        <div className="controls-area">
          <select 
            className="tantor-select"
            value={selectedCluster}
            onChange={e => setSelectedCluster(e.target.value)}
          >
            {clusters.map(c => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
          <button className="tantor-btn primary" onClick={fetchMetrics} disabled={loading}>
            <RefreshCw size={16} className={loading ? 'spin' : ''} />
            Refresh
          </button>
        </div>
      </div>

      {error && (
        <div className="error-banner glass-panel">
          <p>{error}</p>
        </div>
      )}

      <div className="metrics-grid">
        {metrics?.nodes.map(node => (
          <div key={node.hostId} className="node-card glass-panel">
            <div className="node-header">
              <Server size={20} className="text-accent" />
              <h3>{node.hostname} <span className="node-badge">Node {node.nodeId}</span></h3>
              <span className="role-tag">{node.role}</span>
            </div>

            <div className="system-metrics">
              <div className="metric-bar-group">
                <div className="metric-label">
                  <span>CPU Usage</span>
                  <span>{node.system?.cpuUsagePct?.toFixed(1) || 0}%</span>
                </div>
                <div className="progress-bg">
                  <div className="progress-fill" style={{ width: `${node.system?.cpuUsagePct || 0}%` }}></div>
                </div>
              </div>

              <div className="metric-bar-group">
                <div className="metric-label">
                  <span>Memory ({formatBytes((node.system?.memUsedMb || 0) * 1024 * 1024)} / {formatBytes((node.system?.memTotalMb || 0) * 1024 * 1024)})</span>
                  <span>{(((node.system?.memUsedMb || 0) / (node.system?.memTotalMb || 1)) * 100).toFixed(1)}%</span>
                </div>
                <div className="progress-bg">
                  <div className="progress-fill" style={{ width: `${(((node.system?.memUsedMb || 0) / (node.system?.memTotalMb || 1)) * 100)}%` }}></div>
                </div>
              </div>

              <div className="metric-bar-group">
                <div className="metric-label">
                  <span>Disk ({formatBytes((node.system?.diskUsedGb || 0) * 1024 * 1024 * 1024)} / {formatBytes((node.system?.diskTotalGb || 0) * 1024 * 1024 * 1024)})</span>
                  <span>{(((node.system?.diskUsedGb || 0) / (node.system?.diskTotalGb || 1)) * 100).toFixed(1)}%</span>
                </div>
                <div className="progress-bg">
                  <div className="progress-fill disk" style={{ width: `${(((node.system?.diskUsedGb || 0) / (node.system?.diskTotalGb || 1)) * 100)}%` }}></div>
                </div>
              </div>
            </div>

            <div className="kafka-metrics">
              <div className="kpi-grid">
                <div className="kpi-box">
                  <span className="kpi-title">Msg In/sec</span>
                  <span className="kpi-value">{node.kafka?.messagesInPerSec?.toFixed(2) || 0}</span>
                </div>
                <div className="kpi-box">
                  <span className="kpi-title">Bytes In/sec</span>
                  <span className="kpi-value">{formatBytes(node.kafka?.bytesInPerSec || 0)}</span>
                </div>
                <div className="kpi-box">
                  <span className="kpi-title">Bytes Out/sec</span>
                  <span className="kpi-value">{formatBytes(node.kafka?.bytesOutPerSec || 0)}</span>
                </div>
                <div className="kpi-box">
                  <span className="kpi-title">Partitions</span>
                  <span className="kpi-value">{node.kafka?.partitionCount || 0}</span>
                </div>
                <div className="kpi-box">
                  <span className="kpi-title">Under-Replicated</span>
                  <span className={`kpi-value ${(node.kafka?.underReplicatedPartitions || 0) > 0 ? 'text-danger' : 'text-success'}`}>
                    {node.kafka?.underReplicatedPartitions || 0}
                  </span>
                </div>
                <div className="kpi-box">
                  <span className="kpi-title">Offline Replicas</span>
                  <span className={`kpi-value ${(node.kafka?.offlineReplicaCount || 0) > 0 ? 'text-danger' : 'text-success'}`}>
                    {node.kafka?.offlineReplicaCount || 0}
                  </span>
                </div>
                <div className="kpi-box">
                  <span className="kpi-title">Active Controller</span>
                  <span className="kpi-value">{node.kafka?.activeControllerCount || 0}</span>
                </div>
                <div className="kpi-box">
                  <span className="kpi-title">Net Idle</span>
                  <span className="kpi-value">{(node.kafka?.networkProcessorAvgIdlePercent * 100 || 0).toFixed(1)}%</span>
                </div>
              </div>
            </div>

          </div>
        ))}

        {metrics?.nodes.length === 0 && !loading && (
          <div className="empty-state glass-panel">
            <Database size={48} className="text-secondary" />
            <h3>No Metrics Available</h3>
            <p>Ensure the cluster is deployed and the agent is running.</p>
          </div>
        )}
      </div>
    </div>
  );
}
