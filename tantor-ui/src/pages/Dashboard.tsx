import { useState, useEffect } from 'react';
import {
  Settings, Activity, Server, ChevronRight,
  Cpu, Database, Wifi, RefreshCw, Plus, Bot
} from 'lucide-react';
import './Dashboard.css';

interface DashboardStats {
  totalClusters: number;
  totalHosts: number;
  activeAlerts: number;
  healthyClusters: number;
}

export function Dashboard() {
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [clusters, setClusters] = useState<any[]>([]);
  const [activeTab, setActiveTab] = useState('status');
  const [activeTime, setActiveTime] = useState('30m');

  useEffect(() => {
    fetch('/api/v1/ui/dashboard/stats')
      .then(res => res.json())
      .then(data => setStats(data))
      .catch(err => console.error(err));

    fetch('/api/v1/ui/clusters')
      .then(res => res.json())
      .then(data => setClusters(data))
      .catch(err => console.error(err));
  }, []);

  const platformServices = [
    {
      name: `${stats?.totalHosts ?? 0} Hosts`,
      sub: 'Registered · reachable',
      status: 'healthy',
      icon: Server,
      iconVariant: 'blue',
    },
    {
      name: 'Core Configuration',
      sub: 'Pending review',
      status: 'unknown',
      icon: Settings,
      iconVariant: 'amber',
    },
    {
      name: 'Tantor Agent Management',
      sub: `Agent v1.0.0 running`,
      status: 'healthy',
      icon: Bot,
      iconVariant: 'green',
    },
  ];

  const clusterServices = clusters.map(c => ({
    name: c.name,
    sub: `${c.nodeCount ?? 0} brokers · managed`,
    status: c.nodeCount > 0 ? 'healthy' : 'warning',
    icon: Activity,
    iconVariant: 'purple',
  }));

  const renderStatusDot = (status: string) => {
    const cls = status === 'healthy' ? 'dot-green'
              : status === 'warning' ? 'dot-amber'
              : status === 'error'   ? 'dot-red'
              : 'dot-gray';
    return <span className={`status-dot ${cls}`} />;
  };

  const renderStatusLabel = (status: string) => {
    if (status === 'healthy') return <span className="status-label green">Online</span>;
    if (status === 'warning') return <span className="status-label amber">Warning</span>;
    if (status === 'error')   return <span className="status-label red">Error</span>;
    return <span className="status-label gray">Idle</span>;
  };

  const tabs = [
    { id: 'status', label: 'Status' },
    { id: 'health', label: 'Health Issues', count: stats?.activeAlerts || 0, countType: 'danger' },
    { id: 'config', label: 'Configuration', count: 0, countType: 'warning' },
    { id: 'commands', label: 'Recent Commands' },
  ];

  const times = ['30m', '1h', '2h', '6h', '12h', '1d', '7d', '30d'];

  const kpis = [
    { label: 'CPU', value: '4.1', unit: '%', sub: 'All hosts', trend: 'stable', icon: Cpu },
    { label: 'Disk IO', value: '12.9', unit: 'K/s', sub: 'Read', trend: 'up', icon: Database },
    { label: 'Throughput', value: '2.4', unit: 'M/s', sub: 'Messages', trend: 'down', icon: Activity },
    { label: 'Lag', value: '142', unit: 'ms', sub: 'Consumer', trend: 'stable', icon: Wifi },
  ];

  return (
    <div className="db animate-fade-in">

      {/* ── Top header ── */}
      <div className="db-header">
        <div className="db-header-left">
          <div className="db-cluster-pill">
            <span className="db-cluster-dot" />
            <span className="db-cluster-name">Tantor Runtime</span>
            <span className="db-cluster-env">Air-Gapped · v1.0.0</span>
          </div>
          <div className="db-header-sep" />
          <nav className="db-tabs">
            {tabs.map(t => (
              <button
                key={t.id}
                className={`db-tab${activeTab === t.id ? ' active' : ''}`}
                onClick={() => setActiveTab(t.id)}
              >
                {t.label}
                {t.count !== undefined && (
                  <span className={`db-tab-badge ${t.countType}`}>{t.count}</span>
                )}
              </button>
            ))}
          </nav>
        </div>
        <div className="db-header-right">
          <button className="db-btn ghost">
            <RefreshCw size={12} /> Refresh
          </button>
          <button className="db-btn primary">
            <Plus size={12} /> New cluster
          </button>
        </div>
      </div>

      {/* ── Body ── */}
      <div className="db-body">

        {/* ── Left pane ── */}
        <aside className="db-left">

          {/* Cluster meta */}
          <div className="db-left-head">
            <div className="db-left-title-row">
              <span className="db-left-title">Platform Core</span>
              <span className="db-healthy-badge">
                <span className="db-healthy-dot" /> Healthy
              </span>
            </div>
            <span className="db-left-sub">Parcels · {clusters.length} cluster{clusters.length !== 1 ? 's' : ''}</span>
          </div>

          {/* KPI strip */}
          <div className="db-kpi-strip">
            <div className="db-kpi">
              <span className="db-kpi-label">CPU</span>
              <span className="db-kpi-val">4.1<span className="db-kpi-unit">%</span></span>
            </div>
            <div className="db-kpi-divider" />
            <div className="db-kpi">
              <span className="db-kpi-label">Hosts</span>
              <span className="db-kpi-val">{stats?.totalHosts ?? 0}</span>
            </div>
            <div className="db-kpi-divider" />
            <div className="db-kpi">
              <span className="db-kpi-label">Uptime</span>
              <span className="db-kpi-val">99<span className="db-kpi-unit">%</span></span>
            </div>
          </div>

          {/* Services */}
          <div className="db-svc-list">

            <div className="db-svc-section">Infrastructure</div>
            {platformServices.map(svc => (
              <div key={svc.name} className="db-svc-row">
                <div className={`db-svc-icon ${svc.iconVariant}`}>
                  <svc.icon size={13} />
                </div>
                <div className="db-svc-body">
                  <span className="db-svc-name">{svc.name}</span>
                  <span className="db-svc-sub">{svc.sub}</span>
                </div>
                <div className="db-svc-right">
                  <div className="db-svc-status">
                    {renderStatusDot(svc.status)}
                    {renderStatusLabel(svc.status)}
                  </div>
                  <ChevronRight size={12} className="db-svc-arrow" />
                </div>
              </div>
            ))}

            {clusterServices.length > 0 && (
              <>
                <div className="db-svc-section">Kafka Clusters</div>
                {clusterServices.map(svc => (
                  <div key={svc.name} className="db-svc-row">
                    <div className={`db-svc-icon ${svc.iconVariant}`}>
                      <svc.icon size={13} />
                    </div>
                    <div className="db-svc-body">
                      <span className="db-svc-name">{svc.name}</span>
                      <span className="db-svc-sub">{svc.sub}</span>
                    </div>
                    <div className="db-svc-right">
                      <div className="db-svc-status">
                        {renderStatusDot(svc.status)}
                        {renderStatusLabel(svc.status)}
                      </div>
                      <ChevronRight size={12} className="db-svc-arrow" />
                    </div>
                  </div>
                ))}
              </>
            )}

            {clusterServices.length === 0 && (
              <div className="db-empty">
                No clusters yet. Click <strong>New cluster</strong> to begin.
              </div>
            )}
          </div>
        </aside>

        {/* ── Right pane ── */}
        <div className="db-right">

          {/* Summary cards */}
          <div className="db-summary-row">
            {kpis.map(k => (
              <div key={k.label} className="db-summary-card">
                <div className="db-sc-label">
                  <k.icon size={12} className="db-sc-icon" />
                  {k.label}
                </div>
                <div className="db-sc-val">
                  {k.value}<span className="db-sc-unit">{k.unit}</span>
                </div>
                <div className={`db-sc-trend ${k.trend}`}>
                  {k.trend === 'stable' ? '→ Stable' : k.trend === 'up' ? '↑ Rising' : '↓ Dropping'}
                </div>
              </div>
            ))}
          </div>

          {/* Charts */}
          <div className="db-charts">

            {/* Time selector */}
            <div className="db-time-row">
              {times.map(t => (
                <button
                  key={t}
                  className={`db-time-btn${activeTime === t ? ' active' : ''}`}
                  onClick={() => setActiveTime(t)}
                >
                  {t}
                </button>
              ))}
            </div>

            {/* CPU chart */}
            <div className="db-chart-card">
              <div className="db-chart-head">
                <div className="db-chart-title">
                  <Cpu size={13} className="db-chart-icon" />
                  CPU usage across hosts
                </div>
                <div className="db-chart-meta">
                  <span className="db-chart-val">4.1%</span>
                  <span className="db-chart-delta stable">→ stable</span>
                </div>
              </div>
              <svg className="db-chart-svg" viewBox="0 0 500 80" preserveAspectRatio="none">
                <defs>
                  <linearGradient id="cpu-g" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#378ADD" stopOpacity="0.13" />
                    <stop offset="100%" stopColor="#378ADD" stopOpacity="0" />
                  </linearGradient>
                </defs>
                <line x1="0" y1="20" x2="500" y2="20" stroke="#f0eeea" strokeWidth="0.5" />
                <line x1="0" y1="50" x2="500" y2="50" stroke="#f0eeea" strokeWidth="0.5" />
                <path d="M0 58 C30 56,55 52,90 54 S130 50,165 51 S205 47,240 45 S275 48,310 46 S350 42,385 43 S450 44,500 43 L500 80 L0 80Z" fill="url(#cpu-g)" />
                <path d="M0 58 C30 56,55 52,90 54 S130 50,165 51 S205 47,240 45 S275 48,310 46 S350 42,385 43 S450 44,500 43" fill="none" stroke="#378ADD" strokeWidth="1.5" strokeLinecap="round" />
                <text x="4" y="17" className="db-chart-label">8%</text>
                <text x="4" y="47" className="db-chart-label">4%</text>
                <text x="4" y="77" className="db-chart-label">0%</text>
              </svg>
            </div>

            {/* Disk IO chart */}
            <div className="db-chart-card">
              <div className="db-chart-head">
                <div className="db-chart-title">
                  <Database size={13} className="db-chart-icon" />
                  Disk IO — read vs write
                </div>
                <div className="db-chart-meta">
                  <div className="db-chart-legend">
                    <span className="db-legend-dot green" /> Read
                    <span className="db-legend-dot amber" style={{ marginLeft: 10 }} /> Write
                  </div>
                  <span className="db-chart-val">12.9 K/s</span>
                  <span className="db-chart-delta up">↑ write heavy</span>
                </div>
              </div>
              <svg className="db-chart-svg" viewBox="0 0 500 80" preserveAspectRatio="none">
                <defs>
                  <linearGradient id="read-g" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#1D9E75" stopOpacity="0.2" />
                    <stop offset="100%" stopColor="#1D9E75" stopOpacity="0" />
                  </linearGradient>
                  <linearGradient id="write-g" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#BA7517" stopOpacity="0.18" />
                    <stop offset="100%" stopColor="#BA7517" stopOpacity="0" />
                  </linearGradient>
                </defs>
                <line x1="0" y1="20" x2="500" y2="20" stroke="#f0eeea" strokeWidth="0.5" />
                <line x1="0" y1="50" x2="500" y2="50" stroke="#f0eeea" strokeWidth="0.5" />
                <path d="M0 52 C40 38,90 48,140 32 S210 44,260 28 S320 38,370 22 S440 32,500 26 L500 80 L0 80Z" fill="url(#read-g)" />
                <path d="M0 52 C40 38,90 48,140 32 S210 44,260 28 S320 38,370 22 S440 32,500 26" fill="none" stroke="#1D9E75" strokeWidth="1.5" strokeLinecap="round" />
                <path d="M0 66 C40 58,90 62,140 54 S210 60,260 48 S320 56,370 44 S440 50,500 46 L500 80 L0 80Z" fill="url(#write-g)" />
                <path d="M0 66 C40 58,90 62,140 54 S210 60,260 48 S320 56,370 44 S440 50,500 46" fill="none" stroke="#BA7517" strokeWidth="1.5" strokeLinecap="round" />
              </svg>
            </div>

            {/* Network IO chart */}
            <div className="db-chart-card">
              <div className="db-chart-head">
                <div className="db-chart-title">
                  <Wifi size={13} className="db-chart-icon" />
                  Network IO
                </div>
                <div className="db-chart-meta">
                  <span className="db-chart-val">—</span>
                </div>
              </div>
              <svg className="db-chart-svg" viewBox="0 0 500 80" preserveAspectRatio="none">
                <defs>
                  <linearGradient id="net-g" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#534AB7" stopOpacity="0.13" />
                    <stop offset="100%" stopColor="#534AB7" stopOpacity="0" />
                  </linearGradient>
                </defs>
                <line x1="0" y1="20" x2="500" y2="20" stroke="#f0eeea" strokeWidth="0.5" />
                <line x1="0" y1="50" x2="500" y2="50" stroke="#f0eeea" strokeWidth="0.5" />
                <path d="M0 62 C50 50,80 58,130 42 S190 68,240 52 S300 38,350 55 S420 32,500 48 L500 80 L0 80Z" fill="url(#net-g)" />
                <path d="M0 62 C50 50,80 58,130 42 S190 68,240 52 S300 38,350 55 S420 32,500 48" fill="none" stroke="#534AB7" strokeWidth="1.5" strokeLinecap="round" />
              </svg>
            </div>

          </div>
        </div>
      </div>
    </div>
  );
}
