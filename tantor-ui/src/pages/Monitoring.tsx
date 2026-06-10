import { useState, useEffect, useCallback } from 'react';
import { Activity, Server, Database, HardDrive, RefreshCw } from 'lucide-react';
import { XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Area, AreaChart } from 'recharts';
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

interface TimePoint {
  time: string;
  timestamp: number;
  [key: string]: string | number;
}

const MAX_HISTORY = 30; // 30 data points = 5 minutes at 10s intervals

function LiveChart({ data, dataKey, color, label, unit = '%', max, id }: {
  data: TimePoint[];
  dataKey: string;
  color: string;
  label: string;
  unit?: string;
  max?: number;
  id?: string;
}) {
  const gradId = `grad-${id || dataKey}-${dataKey}`;
  const latest = data.length > 0 ? (data[data.length - 1][dataKey] as number) ?? 0 : 0;
  const statusColor = latest > 80 ? 'text-danger' : latest > 60 ? 'text-warning' : 'text-success';

  return (
    <div className="live-chart-container">
      <div className="chart-header">
        <span className="chart-label">{label}</span>
        <span className={`chart-value ${statusColor}`}>
          {typeof latest === 'number' ? latest.toFixed(1) : latest}{unit}
        </span>
      </div>
      <div className="chart-body">
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={data} margin={{ top: 5, right: 5, left: -20, bottom: 0 }}>
            <defs>
              <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor={color} stopOpacity={0.3} />
                <stop offset="95%" stopColor={color} stopOpacity={0.05} />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
            <XAxis dataKey="time" tick={{ fontSize: 10, fill: '#6b7280' }} stroke="#e5e7eb" interval="preserveStartEnd" />
            <YAxis domain={[0, max || 'auto']} tick={{ fontSize: 10, fill: '#6b7280' }} stroke="#e5e7eb" />
            <Tooltip
              contentStyle={{ fontSize: 12, borderRadius: 8, backgroundColor: '#ffffff', border: '1px solid #e5e7eb', color: '#111827' }}
              formatter={(value: unknown) => [`${Number(value).toFixed(1)}${unit}`, label]}
              labelStyle={{ fontSize: 11, color: '#6b7280' }}
            />
            <Area
              type="monotone"
              dataKey={dataKey}
              stroke={color}
              strokeWidth={2}
              fill={`url(#${gradId})`}
              isAnimationActive={false}
              dot={false}
            />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}

export function Monitoring() {
  const [clusters, setClusters] = useState<Cluster[]>([]);
  const [selectedCluster, setSelectedCluster] = useState<string>('');
  const [metrics, setMetrics] = useState<ClusterMetrics | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [history, setHistory] = useState<Record<string, TimePoint[]>>({});
  const [autoRefresh, setAutoRefresh] = useState(true);

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

  const fetchMetrics = useCallback(async (silent = false) => {
    if (!selectedCluster) return;
    if (!silent) setLoading(true);
    setError(null);
    try {
      const res = await fetch(`/api/v1/ui/clusters/${selectedCluster}/metrics`);
      if (!res.ok) throw new Error('Failed to fetch metrics');
      const data = await res.json();
      setMetrics(data);

      const now = new Date();
      const timeLabel = now.toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
      setHistory(prev => {
        const next = { ...prev };
        for (const node of data.nodes) {
          const key = node.hostId;
          const existing = next[key] ? [...next[key]] : [];
          
          const memUsage = ((node.system.memUsedMb || 0) / (node.system.memTotalMb || 1)) * 100;
          
          existing.push({
            time: timeLabel,
            timestamp: now.getTime(),
            cpu: node.system.cpuUsagePct ?? 0,
            memory: memUsage,
            messagesIn: node.kafka.messagesInPerSec ?? 0
          });
          next[key] = existing.length > MAX_HISTORY ? existing.slice(-MAX_HISTORY) : existing;
        }
        return next;
      });
    } catch (err: any) {
      if (!silent) setError(err.message);
    } finally {
      if (!silent) setLoading(false);
    }
  }, [selectedCluster]);

  useEffect(() => {
    if (selectedCluster) {
      setHistory({});
      fetchMetrics();
    }
  }, [selectedCluster, fetchMetrics]);

  useEffect(() => {
    if (!autoRefresh || !selectedCluster) return;
    const interval = setInterval(() => {
      if (!document.hidden) fetchMetrics(true);
    }, 10000);
    return () => clearInterval(interval);
  }, [autoRefresh, selectedCluster, fetchMetrics]);

  const formatBytes = (bytes: number) => {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  return (
    <div className="monitoring-container animate-fade-in">
      <div className="header-section">
        <div className="title-area">
          <Activity size={32} className="title-icon text-accent" />
          <div>
            <h1>Live Monitoring</h1>
            <p className="subtitle">Real-time Kafka & system metrics via JMX</p>
          </div>
        </div>

        <div className="controls-area">
          {clusters.length > 1 && (
            <select 
              className="tantor-select"
              value={selectedCluster}
              onChange={e => setSelectedCluster(e.target.value)}
            >
              {clusters.map(c => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </select>
          )}
          <label className="auto-refresh-toggle">
            <input
              type="checkbox"
              checked={autoRefresh}
              onChange={e => setAutoRefresh(e.target.checked)}
            />
            <span className={autoRefresh ? 'text-green-600 font-medium' : 'text-gray-500'}>
              Live {autoRefresh ? '● 10s' : '(off)'}
            </span>
          </label>
          <button className="tantor-btn primary" onClick={() => fetchMetrics()} disabled={loading}>
            <RefreshCw size={16} className={loading ? 'spin' : ''} />
            Refresh
          </button>
        </div>
      </div>

      {error && (
        <div className="error-banner">
          <p>{error}</p>
        </div>
      )}

      <div className="metrics-grid">
        {metrics?.nodes.map(node => {
          const nodeHistory = history[node.hostId] || [];

          return (
            <div key={node.hostId} className="node-card flex-col">
              <div className="node-header">
                <div className="node-header-left">
                  <Server size={20} className="text-gray-500" />
                  <h3>{node.hostname} <span className="node-badge">Node {node.nodeId}</span></h3>
                </div>
                <span className="role-tag">{node.role}</span>
              </div>
              
              <div className="graphs-section">
                <h4 className="section-title"><Activity size={14}/> Real-time Performance</h4>
                <div className="graphs-grid">
                  <LiveChart data={nodeHistory} dataKey="cpu" color="#3b82f6" label="CPU Usage" max={100} id={node.hostId} />
                  <LiveChart data={nodeHistory} dataKey="memory" color="#10b981" label="Memory Usage" max={100} id={node.hostId} />
                  <LiveChart data={nodeHistory} dataKey="messagesIn" color="#8b5cf6" label="Messages In" unit="/s" id={node.hostId} />
                </div>
              </div>

              <div className="metrics-columns">
                <div className="system-metrics">
                  <h4 className="section-title"><HardDrive size={14}/> System Resources</h4>
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
                  <h4 className="section-title"><Database size={14}/> Kafka Broker</h4>
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

            </div>
          );
        })}

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
