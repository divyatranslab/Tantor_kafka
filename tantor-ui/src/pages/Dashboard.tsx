import { Activity, Server, Network, ShieldCheck } from 'lucide-react';
import './Dashboard.css';

export function Dashboard() {
  const stats = [
    { label: 'Active Hosts', value: '24', icon: Server, color: 'var(--accent-primary)' },
    { label: 'Running Clusters', value: '6', icon: Network, color: 'var(--accent-secondary)' },
    { label: 'Healthy Services', value: '142', icon: Activity, color: 'var(--accent-success)' },
    { label: 'Security Score', value: '98%', icon: ShieldCheck, color: 'var(--accent-warning)' },
  ];

  return (
    <div className="dashboard animate-fade-in">
      <header className="page-header">
        <h1>Platform Overview</h1>
        <p>Real-time metrics from the Tantor management plane.</p>
      </header>

      <section className="stats-grid">
        {stats.map((stat) => (
          <div key={stat.label} className="stat-card glass-panel">
            <div className="stat-icon" style={{ backgroundColor: `${stat.color}20`, color: stat.color }}>
              <stat.icon size={24} />
            </div>
            <div className="stat-info">
              <h3>{stat.value}</h3>
              <p>{stat.label}</p>
            </div>
          </div>
        ))}
      </section>

      <section className="charts-section">
        <div className="chart-card glass-panel">
          <h3>Cluster Topology</h3>
          <div className="placeholder-chart">
            {/* We will embed an actual chart library later, using a CSS placeholder for aesthetics */}
            <div className="circle-node center"></div>
            <div className="circle-node ring n1"></div>
            <div className="circle-node ring n2"></div>
            <div className="circle-node ring n3"></div>
            <div className="connection line1"></div>
            <div className="connection line2"></div>
            <div className="connection line3"></div>
          </div>
        </div>
        
        <div className="chart-card glass-panel">
          <h3>System Health Events</h3>
          <div className="activity-feed">
            <div className="feed-item">
              <span className="dot success"></span>
              <p>Kafka Broker #3 recovered</p>
              <span className="time">2m ago</span>
            </div>
            <div className="feed-item">
              <span className="dot warning"></span>
              <p>High memory usage on Connect Worker-02</p>
              <span className="time">15m ago</span>
            </div>
            <div className="feed-item">
              <span className="dot success"></span>
              <p>KRaft quorum stabilized</p>
              <span className="time">1h ago</span>
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}
