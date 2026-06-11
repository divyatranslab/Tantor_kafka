import { useState, useEffect } from 'react';
import { Server, Network, Activity, AlertCircle } from 'lucide-react';
import './Dashboard.css';

interface DashboardStats {
  totalClusters: number;
  totalHosts: number;
  activeAlerts: number;
  healthyClusters: number;
}

export function Dashboard() {
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [activities, setActivities] = useState<any[]>([]);

  useEffect(() => {
    fetch('/api/v1/ui/dashboard/stats')
      .then(res => res.json())
      .then(data => setStats(data))
      .catch(err => console.error(err));

    fetch('/api/v1/ui/dashboard/activity')
      .then(res => res.json())
      .then(data => setActivities(data))
      .catch(err => console.error(err));
  }, []);

  const statsList = [
    { label: 'Managed hosts',    value: stats?.totalHosts      ?? '…', icon: Server,      bg: '#E6F1FB', color: '#185FA5' },
    { label: 'Total clusters',   value: stats?.totalClusters   ?? '…', icon: Network,     bg: '#EEEDFE', color: '#534AB7' },
    { label: 'Healthy clusters', value: stats?.healthyClusters ?? '…', icon: Activity,    bg: '#EAF3DE', color: '#3B6D11' },
    { label: 'Active alerts',    value: stats?.activeAlerts    ?? '…', icon: AlertCircle, bg: '#FEF2F2', color: '#EF4444' },
  ];

  const VMS = [
    { cx: 320, cy: 58,  label: 'vm-01' },
    { cx: 418, cy: 102, label: 'vm-02' },
    { cx: 412, cy: 228, label: 'vm-03' },
    { cx: 228, cy: 228, label: 'vm-04' },
    { cx: 222, cy: 102, label: 'vm-05' },
  ];

  return (
    <div className="dashboard animate-fade-in">

      <header className="page-header">
        <h1>Platform overview</h1>
        <p>Real-time metrics from the Tantor management plane</p>
      </header>

      {/* ── Stat cards ── */}
      <section className="stats-grid">
        {statsList.map((s) => (
          <div key={s.label} className="stat-card">
            <div className="stat-icon" style={{ background: s.bg, color: s.color }}>
              <s.icon size={16} strokeWidth={2} />
            </div>
            <div className="stat-info">
              <h3>{s.value}</h3>
              <p>{s.label}</p>
            </div>
          </div>
        ))}
      </section>

      {/* ── Charts ── */}
      <section className="charts-section">

        {/* Topology */}
        <div className="chart-card">
          <h3>Cluster topology</h3>
          <div className="placeholder-chart">
            <svg
              className="topology-svg"
              viewBox="0 0 560 310"
              role="img"
              aria-label="Cluster topology — radial hub-and-spoke with animated heartbeats"
            >
              <defs>
                <marker id="topo-arr" viewBox="0 0 10 10" refX="8" refY="5"
                  markerWidth="5" markerHeight="5" orient="auto-start-reverse">
                  <path d="M2 1L8 5L2 9" fill="none" stroke="#888780"
                    strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
                </marker>
                <marker id="topo-arr-green" viewBox="0 0 10 10" refX="8" refY="5"
                  markerWidth="5" markerHeight="5" orient="auto-start-reverse">
                  <path d="M2 1L8 5L2 9" fill="none" stroke="#1D9E75"
                    strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
                </marker>
              </defs>

              {/* ── Provisioning sidebar ── */}
              <rect x="16" y="108" width="98" height="44" rx="8" className="topo-node-master" />
              <text x="65" y="124" textAnchor="middle" className="topo-label-bold">Master VM</text>
              <text x="65" y="140" textAnchor="middle" className="topo-label-sub">192.168.1.10</text>

              <line x1="65" y1="152" x2="65" y2="172" className="topo-arrow-line" markerEnd="url(#topo-arr)" />

              <rect x="16" y="172" width="98" height="44" rx="8" className="topo-node-ansible" />
              <text x="65" y="188" textAnchor="middle" className="topo-label-bold topo-ansible-text">Ansible</text>
              <text x="65" y="204" textAnchor="middle" className="topo-label-sub topo-ansible-sub">deploy runner</text>

              {/* Animated deploy arrow */}
              <path d="M114 194 Q175 194 204 194" fill="none"
                className="topo-deploy-dash" markerEnd="url(#topo-arr-green)" />
              <text x="159" y="188" textAnchor="middle" className="topo-label-tiny topo-ansible-sub">deploys</text>

              {/* ── Pulse rings ── */}
              <circle cx="320" cy="163" r="34" fill="none" className="topo-pulse topo-pulse-1" />
              <circle cx="320" cy="163" r="34" fill="none" className="topo-pulse topo-pulse-2" />
              <circle cx="320" cy="163" r="34" fill="none" className="topo-pulse topo-pulse-3" />

              {/* ── Spokes + heartbeat dots ── */}
              {/* top */}
              <line x1="320" y1="131" x2="320" y2="80" className="topo-spoke" />
              <circle cx="320" cy="119" r="4" className="topo-hb topo-hb-1" />
              <circle cx="320" cy="106" r="4" className="topo-hb topo-hb-2" />
              <circle cx="320" cy="93"  r="4" className="topo-hb topo-hb-3" />

              {/* upper-right */}
              <line x1="344" y1="143" x2="397" y2="114" className="topo-spoke" />
              <circle cx="358" cy="137" r="4" className="topo-hb topo-hb-2" />
              <circle cx="372" cy="129" r="4" className="topo-hb topo-hb-3" />
              <circle cx="386" cy="122" r="4" className="topo-hb topo-hb-4" />

              {/* lower-right */}
              <line x1="344" y1="183" x2="396" y2="214" className="topo-spoke" />
              <circle cx="358" cy="190" r="4" className="topo-hb topo-hb-3" />
              <circle cx="372" cy="197" r="4" className="topo-hb topo-hb-4" />
              <circle cx="386" cy="205" r="4" className="topo-hb topo-hb-5" />

              {/* lower-left */}
              <line x1="296" y1="183" x2="244" y2="214" className="topo-spoke" />
              <circle cx="282" cy="190" r="4" className="topo-hb topo-hb-4" />
              <circle cx="268" cy="197" r="4" className="topo-hb topo-hb-5" />
              <circle cx="254" cy="205" r="4" className="topo-hb topo-hb-1" />

              {/* upper-left */}
              <line x1="296" y1="143" x2="244" y2="114" className="topo-spoke" />
              <circle cx="282" cy="137" r="4" className="topo-hb topo-hb-5" />
              <circle cx="268" cy="129" r="4" className="topo-hb topo-hb-1" />
              <circle cx="254" cy="122" r="4" className="topo-hb topo-hb-2" />

              {/* ── CMB Backend centre node ── */}
              <circle cx="320" cy="163" r="36" className="topo-node-cmb" />
              <text x="320" y="157" textAnchor="middle" className="topo-label-bold topo-cmb-text">CMB</text>
              <text x="320" y="173" textAnchor="middle" className="topo-label-sub topo-cmb-sub">backend</text>

              {/* ── Agent VM ring ── */}
              {VMS.map(({ cx, cy, label }) => (
                <g key={label}>
                  <circle cx={cx} cy={cy} r="24" className="topo-node-vm" />
                  <text x={cx} y={cy - 3} textAnchor="middle" className="topo-label-bold">{label}</text>
                  <text x={cx} y={cy + 12} textAnchor="middle" className="topo-label-tiny">agent</text>
                  <circle cx={cx + 14} cy={cy - 14} r="5" className="topo-status-ring" />
                  <circle cx={cx + 14} cy={cy - 14} r="2.5" className="topo-status-dot" />
                </g>
              ))}

              {/* ── Legend ── */}
              <circle cx="360" cy="292" r="4" fill="#3B6D11" />
              <text x="370" y="296" className="topo-label-tiny">healthy</text>
              <circle cx="420" cy="292" r="5" fill="none" stroke="#E09B1A" strokeWidth="1" />
              <circle cx="420" cy="292" r="2.5" fill="#D97706" opacity="0.7" />
              <text x="430" y="296" className="topo-label-tiny">heartbeat</text>
            </svg>
          </div>
        </div>

        {/* Activity feed */}
        <div className="chart-card">
          <h3>System health events</h3>
          <div className="activity-feed">
            {activities.length === 0 ? (
              <div className="feed-item" style={{ color: 'var(--text-secondary)' }}>
                No recent activity.
              </div>
            ) : (
              activities.map((a: any) => (
                <div key={a.id} className="feed-item">
                  <span className={`dot ${a.level === 'INFO' ? 'success' : a.level === 'WARN' ? 'warning' : 'danger'}`} />
                  <p>{a.message}</p>
                  <span className="time">{new Date(a.createdAt).toLocaleString()}</span>
                </div>
              ))
            )}
          </div>
        </div>

      </section>
    </div>
  );
}