import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Activity, AlertTriangle, BarChart3, Bot, Clock, Database, ExternalLink,
  HardDrive, Network, Plus, RefreshCw, Server, ShieldCheck
} from 'lucide-react';
import {
  Area, AreaChart, Bar, BarChart, CartesianGrid, Cell, Legend, Pie, PieChart,
  ResponsiveContainer, Tooltip, XAxis, YAxis
} from 'recharts';
import './Dashboard.css';

interface DashboardSummary {
  totalHosts: number;
  activeHosts: number;
  offlineHosts: number;
  pendingHosts: number;
  totalClusters: number;
  activeClusters: number;
  failedClusters: number;
  externalClusters: number;
  internalClusters: number;
  activeAlerts: number;
  runningTasks: number;
  failedTasks: number;
  activeParcels: number;
  failedParcels: number;
  runningServices: number;
  failedServices: number;
  firstClusterCreatedAt?: string;
  latestClusterCreatedAt?: string;
  lastActivityAt?: string;
}

interface ChartRow {
  name: string;
  status?: string;
  value?: number;
  usedGb?: number;
  freeGb?: number;
  totalGb?: number;
  usedPct?: number;
  success?: number;
  failed?: number;
  running?: number;
  label?: string;
}

interface ServiceRow {
  name: string;
  description: string;
  status: string;
  type: string;
}

interface ClusterHealthRow {
  id: string;
  name: string;
  mode?: string;
  kafkaVersion?: string;
  source?: string;
  status: string;
  reason: string;
  hostCount?: number;
  bootstrapServers?: string;
}

interface ActivityRow {
  id: string;
  level: string;
  message: string;
  clusterId?: string;
  createdAt?: string;
}

interface TaskRow {
  id: string;
  command: string;
  status: string;
  hostId: string;
  clusterName?: string;
  createdAt?: string;
  updatedAt?: string;
  errorMsg?: string;
}

interface DashboardPayload {
  generatedAt: string;
  summary: DashboardSummary;
  hostStatus: ChartRow[];
  clusterStatus: ChartRow[];
  clusterHealth: ClusterHealthRow[];
  hostDiskUsage: ChartRow[];
  taskStatus: ChartRow[];
  taskTimeline: ChartRow[];
  runningServices: ServiceRow[];
  failedServices: ServiceRow[];
  recentActivities: ActivityRow[];
  recentTasks: TaskRow[];
}

const emptyDashboard: DashboardPayload = {
  generatedAt: '',
  summary: {
    totalHosts: 0,
    activeHosts: 0,
    offlineHosts: 0,
    pendingHosts: 0,
    totalClusters: 0,
    activeClusters: 0,
    failedClusters: 0,
    externalClusters: 0,
    internalClusters: 0,
    activeAlerts: 0,
    runningTasks: 0,
    failedTasks: 0,
    activeParcels: 0,
    failedParcels: 0,
    runningServices: 0,
    failedServices: 0,
  },
  hostStatus: [],
  clusterStatus: [],
  clusterHealth: [],
  hostDiskUsage: [],
  taskStatus: [],
  taskTimeline: [],
  runningServices: [],
  failedServices: [],
  recentActivities: [],
  recentTasks: [],
};

const STATUS_COLORS: Record<string, string> = {
  SUCCESS: '#1D9E75',
  ONLINE: '#1D9E75',
  RUNNING: '#378ADD',
  IN_PROGRESS: '#378ADD',
  PENDING: '#BA7517',
  DELETING: '#BA7517',
  OFFLINE: '#A32D2D',
  FAILED: '#A32D2D',
  UNKNOWN: '#8b8982',
};

export function Dashboard() {
  const navigate = useNavigate();
  const [dashboard, setDashboard] = useState<DashboardPayload>(emptyDashboard);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const fetchDashboard = async () => {
    setLoading(true);
    setError('');
    try {
      const res = await fetch('/api/v1/ui/dashboard');
      if (!res.ok) throw new Error(`Dashboard request failed (${res.status})`);
      setDashboard(await res.json());
    } catch (e: any) {
      setError(e.message || 'Failed to load dashboard');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchDashboard();
  }, []);

  const summary = dashboard.summary;
  const platformState = summary.failedServices > 0 || summary.failedTasks > 0 || summary.offlineHosts > 0
    ? 'Attention'
    : 'Healthy';

  const kpis = useMemo(() => [
    {
      label: 'Active Hosts',
      value: `${summary.activeHosts}/${summary.totalHosts}`,
      detail: `${summary.offlineHosts} offline, ${summary.pendingHosts} pending`,
      icon: Server,
      tone: summary.offlineHosts > 0 ? 'warn' : 'good',
    },
    {
      label: 'Clusters',
      value: String(summary.totalClusters),
      detail: `${summary.internalClusters} internal, ${summary.externalClusters} external`,
      icon: Network,
      tone: summary.failedClusters > 0 ? 'bad' : 'blue',
    },
    {
      label: 'External Clusters',
      value: String(summary.externalClusters),
      detail: summary.externalClusters > 0 ? 'Connected inventory' : 'No external clusters',
      icon: ExternalLink,
      tone: summary.externalClusters > 0 ? 'purple' : 'muted',
    },
    {
      label: 'Failed Services',
      value: String(summary.failedServices),
      detail: `${summary.failedTasks} failed tasks, ${summary.failedParcels} parcel issues`,
      icon: AlertTriangle,
      tone: summary.failedServices > 0 ? 'bad' : 'good',
    },
    {
      label: 'Running Services',
      value: String(summary.runningServices),
      detail: `${summary.activeParcels} active parcels`,
      icon: Activity,
      tone: 'good',
    },
    {
      label: 'Last Activity',
      value: relativeTime(summary.lastActivityAt),
      detail: formatDateTime(summary.lastActivityAt) || 'Waiting for activity',
      icon: Clock,
      tone: 'blue',
    },
  ], [summary]);

  const serviceIcon = (type: string) => {
    if (type === 'agent') return Bot;
    if (type === 'kafka') return Network;
    if (type === 'external') return ExternalLink;
    if (type === 'parcel') return Database;
    if (type === 'task') return Activity;
    if (type === 'cleanup') return RefreshCw;
    if (type === 'storage') return HardDrive;
    return ShieldCheck;
  };

  return (
    <div className="db animate-fade-in">
      <header className="db-hero">
        <div>
          <div className={`db-live-pill ${platformState === 'Healthy' ? 'good' : 'warn'}`}>
            <span />
            {platformState === 'Healthy' ? 'Live system healthy' : 'Live system needs attention'}
          </div>
          {platformState !== 'Healthy' && (
            <button className="db-alert-link" onClick={() => navigate('/alerts')}>
              View alerts
            </button>
          )}
          <h1>Tantor Kafka Operations</h1>
          <p>Real-time inventory, service health, task activity, and agent heartbeat state.</p>
        </div>
        <div className="db-hero-actions">
          <span className="db-generated">Updated {relativeTime(dashboard.generatedAt)}</span>
          <button className="db-btn ghost" onClick={fetchDashboard}>
            <RefreshCw size={14} className={loading ? 'spin' : ''} />
            Refresh
          </button>
          <button className="db-btn primary" onClick={() => navigate('/cluster-deployment')}>
            <Plus size={14} />
            New cluster
          </button>
        </div>
      </header>

      {error && <div className="db-banner error">{error}</div>}

      <section className="db-kpi-grid">
        {kpis.map(kpi => (
          <article key={kpi.label} className={`db-kpi-card ${kpi.tone}`}>
            <div className="db-kpi-icon"><kpi.icon size={18} /></div>
            <div>
              <span>{kpi.label}</span>
              <strong>{kpi.value}</strong>
              <small>{kpi.detail}</small>
            </div>
          </article>
        ))}
      </section>

      <section className="db-cluster-health">
        <PanelTitle icon={Network} title="Cluster Health" detail={`${dashboard.clusterHealth.length} tracked clusters`} />
        {dashboard.clusterHealth.length ? (
          <div className="db-cluster-list">
            {dashboard.clusterHealth.map(cluster => (
              <button
                key={cluster.id}
                className={`db-cluster-card ${healthTone(cluster.status)}`}
                onClick={() => navigate(`/clusters/${cluster.id}`)}
              >
                <span className="db-cluster-dot" />
                <div>
                  <strong>{cluster.name || 'Unnamed cluster'}</strong>
                  <small>{cluster.source || 'Cluster'} - Kafka {cluster.kafkaVersion || '-'} - {cluster.hostCount || 0} node{cluster.hostCount === 1 ? '' : 's'}</small>
                  <em>{cluster.reason}</em>
                </div>
                <b>{statusLabel(cluster.status)}</b>
              </button>
            ))}
          </div>
        ) : (
          <EmptyPanel text="No cluster records yet." compact />
        )}
      </section>

      <section className="db-main-grid">
        <article className="db-panel large">
          <PanelTitle icon={HardDrive} title="Host Disk Usage" detail="From latest host heartbeat" />
          {dashboard.hostDiskUsage.length ? (
            <ResponsiveContainer width="100%" height={270}>
              <BarChart data={dashboard.hostDiskUsage} layout="vertical" margin={{ top: 8, right: 22, bottom: 8, left: 18 }}>
                <CartesianGrid stroke="#eeeae3" horizontal={false} />
                <XAxis type="number" domain={[0, 100]} tickFormatter={v => `${v}%`} stroke="#8b8982" fontSize={11} />
                <YAxis dataKey="name" type="category" width={132} stroke="#5f5e5a" fontSize={11} tickLine={false} />
                <Tooltip content={<DiskTooltip />} />
                <Bar dataKey="usedPct" radius={[0, 6, 6, 0]} fill="#378ADD" barSize={16} />
              </BarChart>
            </ResponsiveContainer>
          ) : (
            <EmptyPanel text="No disk data yet. Wait for host heartbeat metrics." />
          )}
        </article>

        <article className="db-panel">
          <PanelTitle icon={Network} title="Cluster Status" detail={`${summary.totalClusters} cluster records`} />
          <StatusDonut data={dashboard.clusterStatus} />
        </article>

        <article className="db-panel">
          <PanelTitle icon={Server} title="Host Fleet" detail={`${summary.activeHosts} active of ${summary.totalHosts}`} />
          <StatusDonut data={dashboard.hostStatus} />
        </article>
      </section>

      <section className="db-main-grid lower">
        <article className="db-panel large">
          <PanelTitle icon={BarChart3} title="Task Activity" detail="Last seven days" />
          <ResponsiveContainer width="100%" height={235}>
            <AreaChart data={dashboard.taskTimeline} margin={{ top: 8, right: 18, bottom: 8, left: 0 }}>
              <CartesianGrid stroke="#eeeae3" vertical={false} />
              <XAxis dataKey="label" stroke="#8b8982" fontSize={11} tickLine={false} />
              <YAxis allowDecimals={false} stroke="#8b8982" fontSize={11} tickLine={false} />
              <Tooltip />
              <Legend />
              <Area type="monotone" dataKey="success" stackId="1" stroke="#1D9E75" fill="#dff3e8" name="Success" />
              <Area type="monotone" dataKey="running" stackId="1" stroke="#378ADD" fill="#e4f0fb" name="Running" />
              <Area type="monotone" dataKey="failed" stackId="1" stroke="#A32D2D" fill="#f7dddd" name="Failed" />
            </AreaChart>
          </ResponsiveContainer>
        </article>

        <article className="db-panel">
          <PanelTitle icon={ShieldCheck} title="Running Services" detail={`${summary.runningServices} active units`} />
          <ServiceList rows={dashboard.runningServices} iconFor={serviceIcon} />
        </article>

        <article className="db-panel">
          <PanelTitle icon={AlertTriangle} title="Failed Services" detail={summary.failedServices > 0 ? 'Needs review' : 'No failures'} />
          {dashboard.failedServices.length ? (
            <ServiceList rows={dashboard.failedServices} iconFor={serviceIcon} />
          ) : (
            <EmptyPanel text="No failed services right now." compact />
          )}
        </article>
      </section>

      <section className="db-bottom-grid">
        <article className="db-panel">
          <PanelTitle icon={Clock} title="Recent Activities" detail="Latest platform events" />
          <div className="db-feed">
            {dashboard.recentActivities.length ? dashboard.recentActivities.map(item => (
              <div key={item.id} className="db-feed-row">
                <span className={`db-feed-level ${item.level?.toLowerCase() || 'info'}`}>{item.level || 'INFO'}</span>
                <div>
                  <strong>{item.message}</strong>
                  <small>{formatDateTime(item.createdAt)}</small>
                </div>
              </div>
            )) : <EmptyPanel text="No activity has been logged yet." compact />}
          </div>
        </article>

        <article className="db-panel">
          <PanelTitle icon={Activity} title="Recent Tasks" detail="Deploy, upgrade, parcel, and cleanup jobs" />
          <div className="db-task-list">
            {dashboard.recentTasks.length ? dashboard.recentTasks.map(task => (
              <div key={task.id} className="db-task-row">
                <span className={`db-task-status ${task.status?.toLowerCase()}`}>{task.status}</span>
                <div>
                  <strong>{prettyCommand(task.command)}</strong>
                  <small>{task.clusterName || task.hostId} - {formatDateTime(task.createdAt)}</small>
                  {task.errorMsg && <em>{task.errorMsg}</em>}
                </div>
              </div>
            )) : <EmptyPanel text="No tasks have run yet." compact />}
          </div>
        </article>
      </section>
    </div>
  );
}

function PanelTitle({ icon: Icon, title, detail }: { icon: any; title: string; detail: string }) {
  return (
    <div className="db-panel-title">
      <div>
        <Icon size={16} />
        <h2>{title}</h2>
      </div>
      <span>{detail}</span>
    </div>
  );
}

function EmptyPanel({ text, compact = false }: { text: string; compact?: boolean }) {
  return <div className={`db-empty-panel ${compact ? 'compact' : ''}`}>{text}</div>;
}

function StatusDonut({ data }: { data: ChartRow[] }) {
  const clean = data.filter(row => (row.value || 0) > 0);
  if (!clean.length) return <EmptyPanel text="No status data yet." compact />;

  return (
    <div className="db-donut-wrap">
      <ResponsiveContainer width="100%" height={190}>
        <PieChart>
          <Pie data={clean} dataKey="value" nameKey="name" innerRadius={54} outerRadius={78} paddingAngle={3}>
            {clean.map(row => <Cell key={row.status || row.name} fill={STATUS_COLORS[row.status || 'UNKNOWN'] || STATUS_COLORS.UNKNOWN} />)}
          </Pie>
          <Tooltip />
        </PieChart>
      </ResponsiveContainer>
      <div className="db-donut-legend">
        {clean.map(row => (
          <span key={row.status || row.name}>
            <i style={{ background: STATUS_COLORS[row.status || 'UNKNOWN'] || STATUS_COLORS.UNKNOWN }} />
            {row.name}: {row.value}
          </span>
        ))}
      </div>
    </div>
  );
}

function ServiceList({ rows, iconFor }: { rows: ServiceRow[]; iconFor: (type: string) => any }) {
  return (
    <div className="db-service-list">
      {rows.map(row => {
        const Icon = iconFor(row.type);
        return (
          <div key={`${row.name}-${row.status}`} className="db-service-row">
            <div className={`db-service-icon ${row.status.toLowerCase()}`}><Icon size={15} /></div>
            <div>
              <strong>{row.name}</strong>
              <small>{row.description}</small>
            </div>
            <span className={`db-service-state ${row.status.toLowerCase()}`}>{row.status}</span>
          </div>
        );
      })}
    </div>
  );
}

function healthTone(status: string) {
  const normalized = status?.toUpperCase();
  if (normalized === 'HEALTHY' || normalized === 'SUCCESS') return 'good';
  if (normalized === 'WARNING' || normalized === 'DELETING' || normalized === 'PENDING' || normalized === 'RUNNING') return 'warn';
  return 'bad';
}

function statusLabel(status: string) {
  if (!status) return 'Unknown';
  if (status.toUpperCase() === 'HEALTHY') return 'Healthy';
  return status.toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
}

function DiskTooltip({ active, payload }: any) {
  if (!active || !payload?.length) return null;
  const row = payload[0].payload;
  return (
    <div className="db-tooltip">
      <strong>{row.name}</strong>
      <span>{row.usedGb} GB used of {row.totalGb} GB</span>
      <span>{row.freeGb} GB free</span>
    </div>
  );
}

function relativeTime(value?: string) {
  if (!value) return '-';
  const time = new Date(value).getTime();
  if (Number.isNaN(time)) return '-';
  const diff = Date.now() - time;
  if (diff < 60_000) return 'just now';
  const minutes = Math.round(diff / 60_000);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.round(hours / 24)}d ago`;
}

function formatDateTime(value?: string) {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleString([], {
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function prettyCommand(command?: string) {
  if (!command) return 'Task';
  return command.toLowerCase().split('_').map(part => part.charAt(0).toUpperCase() + part.slice(1)).join(' ');
}
