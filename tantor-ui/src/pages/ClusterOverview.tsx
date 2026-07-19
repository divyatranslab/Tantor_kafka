import { useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { AlertTriangle, Download } from 'lucide-react';
import './ClusterOverview.css';

interface OverviewSummary {
  brokerCount: number;
  activeController: number | null;
  version: string;
  controllerType: string;
}

interface PartitionSummary {
  online: number;
  total: number;
  underReplicated: number;
  inSyncReplicas: number;
  totalReplicas: number;
  outOfSyncReplicas: number;
}

interface BrokerRow {
  brokerId: number;
  host: string;
  port: number;
  controller: boolean;
  diskUsageBytes: number;
  logReplicaCount: number;
  inSyncReplicas: number;
  replicas: number;
  replicaSkewPct: number | null;
  leaders: number;
  leaderSkewPct: number | null;
}

interface ControllerRow {
  nodeId: number;
  host: string;
  port: number | null;
}

interface NodePathRow {
  nodeId: number;
  host: string;
  role: string;
  installDir: string;
  config: string;
  dataDir: string;
  logDir: string;
  hasTelemetry: boolean;
}

interface ClusterOverviewResponse {
  name: string;
  kafkaClusterId: string;
  originType: string;
  installDirectory: string;
  configDirectory: string;
  dataDirectory: string;
  logDirectory: string;
  generatedAt: string;
  warnings: string[];
  uptime: OverviewSummary;
  partitions: PartitionSummary;
  brokers: BrokerRow[];
  controllers: ControllerRow[];
  nodePaths: NodePathRow[];
}

export function ClusterOverview() {
  const { id } = useParams<{ id: string }>();
  const [overview, setOverview] = useState<ClusterOverviewResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchOverview = async () => {
    try {
      const res = await fetch(`/api/v1/clusters/${id}/overview`);
      if (!res.ok) {
        throw new Error('Failed to fetch cluster overview');
      }
      setOverview(await res.json());
      setError(null);
    } catch (e: any) {
      setError(e.message || 'Failed to fetch cluster overview');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchOverview();
    const interval = setInterval(fetchOverview, 10000);
    return () => clearInterval(interval);
  }, [id]);

  const csv = useMemo(() => {
    if (!overview) return '';
    const rows = [
      ['Broker ID', 'Disk Usage Bytes', 'Log Replicas', 'In Sync Replicas', 'Replicas', 'Replica Skew', 'Leaders', 'Leader Skew', 'Port', 'Host'],
      ...overview.brokers.map(broker => [
        broker.brokerId,
        broker.diskUsageBytes,
        broker.logReplicaCount,
        broker.inSyncReplicas,
        broker.replicas,
        formatSkew(broker.replicaSkewPct),
        broker.leaders,
        formatSkew(broker.leaderSkewPct),
        broker.port,
        broker.host,
      ]),
    ];
    return rows.map(row => row.map(value => `"${String(value).replaceAll('"', '""')}"`).join(',')).join('\n');
  }, [overview]);

  const exportCsv = () => {
    if (!overview) return;
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${overview.name || 'cluster'}-brokers-overview.csv`;
    link.click();
    URL.revokeObjectURL(url);
  };

  if (loading && !overview) {
    return <div className="state-center">Loading cluster overview...</div>;
  }

  if (!overview) {
    return (
      <div className="overview-dashboard">
        <div className="overview-alert error">
          <AlertTriangle size={17} />
          <span>{error || 'Cluster overview is unavailable'}</span>
        </div>
      </div>
    );
  }

  const { uptime, partitions } = overview;

  return (
    <div className="overview-dashboard animate-fade-in">
      {error && <Notice kind="error" text={error} />}
      {overview.warnings?.map(warning => <Notice key={warning} kind="warning" text={warning} />)}

      <section className="overview-section">
        <div className="overview-card">
          <div className="section-header-row">
            <h2>Cluster Identity</h2>
            <button className="overview-export" type="button" onClick={exportCsv}>
              <Download size={16} />
              Export CSV
            </button>
          </div>
          <div className="overview-grid identity-grid">
            <div className="overview-item">
              <div className="overview-label">Kafka cluster ID</div>
              <div className="overview-value">{overview.kafkaClusterId || '-'}</div>
            </div>
            <div className="overview-item">
              <div className="overview-label">Cluster type</div>
              <div className="overview-value">{overview.originType || '-'}</div>
            </div>
            {overview.originType !== 'EXTERNAL' && (
              <>
                <div className="overview-item">
                  <div className="overview-label">Install directory</div>
                  <div className="overview-value">{overview.installDirectory || '-'}</div>
                </div>
                <div className="overview-item">
                  <div className="overview-label">Config directory</div>
                  <div className="overview-value">{overview.configDirectory || '-'}</div>
                </div>
                <div className="overview-item">
                  <div className="overview-label">Data directory</div>
                  <div className="overview-value">{overview.dataDirectory || '-'}</div>
                </div>
                <div className="overview-item">
                  <div className="overview-label">Log directory</div>
                  <div className="overview-value">{overview.logDirectory || '-'}</div>
                </div>
              </>
            )}
          </div>
        </div>
      </section>

      <section className="overview-section">
        <div className="overview-card">
          <div className="section-header-row">
            <h2>Uptime</h2>
          </div>
          <div className="overview-grid uptime-grid">
            <div className="overview-item">
              <div className="overview-label">Broker Count</div>
              <div className="overview-value">{(uptime.brokerCount || 0).toString().padStart(2, '0')}</div>
            </div>
            <div className="overview-item">
              <div className="overview-label">Active Controller</div>
              <div className="overview-value">{uptime.activeController ?? '-'}</div>
            </div>
            <div className="overview-item">
              <div className="overview-label">Version</div>
              <div className="overview-value">{uptime.version || '-'}</div>
            </div>
          </div>
        </div>
      </section>

      <section className="overview-section">
        <div className="overview-card">
          <div className="section-header-row">
            <h2>Partitions</h2>
          </div>
          <div className="overview-grid partitions-grid">
            <div className="overview-item">
              <div className="overview-label">Online <span className="status-dot green"></span></div>
              <div className="overview-value">{partitions.online} of {partitions.total}</div>
            </div>
            <div className="overview-item">
              <div className="overview-label">URP <span className="status-dot green"></span></div>
              <div className="overview-value">{partitions.underReplicated}</div>
            </div>
            <div className="overview-item">
              <div className="overview-label">In Sync Replicas <span className="status-dot green"></span></div>
              <div className="overview-value">{partitions.inSyncReplicas} of {partitions.totalReplicas}</div>
            </div>
            <div className="overview-item">
              <div className="overview-label">Out Of Sync Replicas <span className="status-dot green"></span></div>
              <div className="overview-value">{partitions.outOfSyncReplicas}</div>
            </div>
            <div className="overview-item">
              <div className="overview-label">Controller Type</div>
              <div className="overview-value">{uptime.controllerType || '-'}</div>
            </div>
          </div>
          <div className="overview-table-wrap overview-brokers-table">
            <table className="overview-table">
            <thead>
              <tr>
                <th>Broker ID</th>
                <th>Disk usage</th>
                <th>In sync replicas</th>
                <th>Replicas</th>
                <th>Replicas skew</th>
                <th>Leaders</th>
                <th>Leaders skew</th>
                <th>Port</th>
                <th>Host</th>
              </tr>
            </thead>
            <tbody>
              {overview.brokers.map(broker => (
                <tr key={broker.brokerId}>
                  <td>{broker.brokerId}</td>
                  <td>{formatBytes(broker.diskUsageBytes)}, {broker.logReplicaCount} replica(s)</td>
                  <td>{broker.inSyncReplicas}</td>
                  <td>{broker.replicas}</td>
                  <td>{formatSkew(broker.replicaSkewPct)}</td>
                  <td>{broker.leaders}</td>
                  <td>{formatSkew(broker.leaderSkewPct)}</td>
                  <td>{broker.port ? broker.port : '-'}</td>
                  <td>{broker.host}</td>
                </tr>
              ))}
            </tbody>
            </table>
          </div>
        </div>
      </section>

      {overview.controllers && overview.controllers.length > 0 && (
        <section className="overview-section">
          <div className="overview-band">
            <h2>Controller Voters</h2>
            <div className="overview-table-wrap">
              <table className="overview-table">
                <thead>
                  <tr>
                    <th>Node ID</th>
                    <th>Host</th>
                    <th>Port</th>
                  </tr>
                </thead>
                <tbody>
                  {overview.controllers.map(c => (
                    <tr key={c.nodeId}>
                      <td>{c.nodeId}</td>
                      <td className="font-mono">{c.host}</td>
                      <td>{c.port ? c.port : '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </section>
      )}

      {overview.originType === 'EXTERNAL' && overview.nodePaths && (
        <section className="overview-section">
          <div className="overview-band">
            <h2>Paths &amp; Directories</h2>
            <div className="overview-table-wrap">
              <table className="overview-table">
                <thead>
                  <tr>
                    <th>Node ID</th>
                    <th>Host</th>
                    <th>Role</th>
                    <th>Install Dir</th>
                    <th>Config</th>
                    <th>Data Dir</th>
                    <th>Log Dir</th>
                    <th>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {overview.nodePaths.map(p => (
                    <tr key={p.nodeId}>
                      <td>{p.nodeId}</td>
                      <td className="font-mono">{p.host}</td>
                      <td>{p.role}</td>
                      <td>{p.installDir || <span className="text-muted">Not reported</span>}</td>
                      <td>{p.config || <span className="text-muted">Not reported</span>}</td>
                      <td>{p.dataDir || <span className="text-muted">Not reported</span>}</td>
                      <td>{p.logDir || <span className="text-muted">Not reported</span>}</td>
                      <td>
                        {p.hasTelemetry ? (
                          <span className="text-green text-sm">Managed</span>
                        ) : (
                          <span className="text-muted text-sm">Bootstrap metadata</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </section>
      )}
    </div>
  );
}

function Notice({ kind, text }: { kind: 'error' | 'warning'; text: string }) {
  return (
    <div className={`overview-alert ${kind}`}>
      <AlertTriangle size={17} />
      <span>{text}</span>
    </div>
  );
}

function formatSkew(value: number | null) {
  if (value === null || value === undefined) return '-';
  if (value === 0) return '0%';
  return `${value > 0 ? '+' : ''}${value}%`;
}

function formatBytes(bytes: number) {
  if (!bytes) return '0 B';
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), sizes.length - 1);
  return `${(bytes / Math.pow(1024, i)).toFixed(i === 0 ? 0 : 2)} ${sizes[i]}`;
}
