import { useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { AlertTriangle, CheckCircle2, Database, Download, Server, ShieldCheck } from 'lucide-react';
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
}

export function ClusterOverview() {
  const { id } = useParams<{ id: string }>();
  const [overview, setOverview] = useState<ClusterOverviewResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchOverview = async () => {
    try {
      const res = await fetch(`/api/v1/ui/clusters/${id}/overview`);
      if (!res.ok) {
        const errorData = await res.json().catch(() => ({}));
        throw new Error(errorData.message || 'Failed to fetch cluster overview');
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
      <div className="overview-toolbar">
        <button className="overview-export" type="button" onClick={exportCsv}>
          <Download size={16} />
          Export CSV
        </button>
      </div>

      {error && <Notice kind="error" text={error} />}
      {overview.warnings?.map(warning => <Notice key={warning} kind="warning" text={warning} />)}

      <section className="overview-band">
        <h2>Cluster identity and paths</h2>
        <div className="overview-grid uptime">
          <OverviewTile label="Kafka Cluster ID" value={overview.kafkaClusterId || '-'} />
          <OverviewTile label="Cluster type" value={overview.originType || '-'} />
          <OverviewTile label="Install directory" value={overview.installDirectory || '-'} />
          <OverviewTile label="Config directory" value={overview.configDirectory || '-'} />
          <OverviewTile label="Data directory" value={overview.dataDirectory || '-'} />
          <OverviewTile label="Log directory" value={overview.logDirectory || '-'} />
        </div>
      </section>

      <section className="overview-band">
        <h2>Uptime</h2>
        <div className="overview-grid uptime">
          <OverviewTile icon={<Server size={18} />} label="Broker Count" value={uptime.brokerCount} />
          <OverviewTile icon={<ShieldCheck size={18} />} label="Active Controller" value={uptime.activeController ?? '-'} />
          <OverviewTile icon={<Database size={18} />} label="Version" value={uptime.version || '-'} />
        </div>
      </section>

      <section className="overview-band">
        <h2>Partitions</h2>
        <div className="overview-grid partitions">
          <OverviewTile healthy={partitions.online === partitions.total} label="Online" value={`${partitions.online} of ${partitions.total}`} />
          <OverviewTile healthy={partitions.underReplicated === 0} label="URP" value={partitions.underReplicated} />
          <OverviewTile healthy={partitions.inSyncReplicas === partitions.totalReplicas} label="In Sync Replicas" value={`${partitions.inSyncReplicas} of ${partitions.totalReplicas}`} />
          <OverviewTile healthy={partitions.outOfSyncReplicas === 0} label="Out Of Sync Replicas" value={partitions.outOfSyncReplicas} />
          <OverviewTile label="Controller Type" value={uptime.controllerType || '-'} />
        </div>
      </section>

      <div className="overview-table-wrap">
        <table className="data-table overview-table">
          <thead>
            <tr>
              <th>Broker ID</th>
              <th>Disk usage</th>
              <th>In Sync Replicas</th>
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
                <td>
                  <div className="overview-broker-id">
                    <CheckCircle2 size={15} />
                    <span>{broker.brokerId}</span>
                    {broker.controller && <span className="controller-badge" title="Controller">C</span>}
                  </div>
                </td>
                <td>{formatBytes(broker.diskUsageBytes)}, {broker.logReplicaCount} replica(s)</td>
                <td>{broker.inSyncReplicas}</td>
                <td>{broker.replicas}</td>
                <td>{formatSkew(broker.replicaSkewPct)}</td>
                <td>{broker.leaders}</td>
                <td>{formatSkew(broker.leaderSkewPct)}</td>
                <td>{broker.port > 0 ? broker.port : '-'}</td>
                <td className="font-mono">{broker.host}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
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

function OverviewTile({ icon, label, value, healthy }: { icon?: React.ReactNode; label: string; value: React.ReactNode; healthy?: boolean }) {
  return (
    <div className="overview-tile">
      <div className="overview-tile-label">
        {label}
        {healthy !== undefined && <span className={healthy ? 'status-dot ok' : 'status-dot warn'} />}
      </div>
      <div className="overview-tile-main">
        {icon && <span className="overview-tile-icon">{icon}</span>}
        <span>{value}</span>
      </div>
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
