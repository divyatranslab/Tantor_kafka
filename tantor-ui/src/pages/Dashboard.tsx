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
    {
      label: 'Managed hosts',
      value: stats ? stats.totalHosts : '...',
      icon: Server,
      bg: '#E6F1FB',
      color: '#185FA5',
    },
    {
      label: 'Total clusters',
      value: stats ? stats.totalClusters : '...',
      icon: Network,
      bg: '#EEEDFE',
      color: '#534AB7',
    },
    {
      label: 'Healthy clusters',
      value: stats ? stats.healthyClusters : '...',
      icon: Activity,
      bg: '#EAF3DE',
      color: '#3B6D11',
    },
    {
      label: 'Active alerts',
      value: stats ? stats.activeAlerts : '...',
      icon: AlertCircle,
      bg: '#fef2f2',
      color: '#ef4444',
    },
  ];

  return (
    <div className="dashboard animate-fade-in">

      <header className="page-header">
        <h1>Platform overview</h1>
        <p>Real-time metrics from the Tantor management plane</p>
      </header>

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

      <section className="charts-section">

        <div className="chart-card">
          <h3>Cluster topology</h3>
          <div className="placeholder-chart">
            <div className="circle-node center" />
            <div className="circle-node ring n1" />
            <div className="circle-node ring n2" />
            <div className="circle-node ring n3" />
            <div className="circle-node ring n4" />
            <div className="circle-node ring n5" />
            <div className="connection line1" />
            <div className="connection line2" />
            <div className="connection line3" />
          </div>
        </div>

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
                  <span className={`dot ${a.level === 'INFO' ? 'success' : a.level === 'WARN' ? 'warning' : 'error'}`} />
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