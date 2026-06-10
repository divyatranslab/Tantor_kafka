import { Server, Network, Activity, ShieldCheck } from 'lucide-react';
import './Dashboard.css';

const stats = [
  {
    label: 'Active hosts',
    value: '24',
    icon: Server,
    bg: '#E6F1FB',
    color: '#185FA5',
  },
  {
    label: 'Running clusters',
    value: '6',
    icon: Network,
    bg: '#EEEDFE',
    color: '#534AB7',
  },
  {
    label: 'Healthy services',
    value: '142',
    icon: Activity,
    bg: '#EAF3DE',
    color: '#3B6D11',
  },
  {
    label: 'Security score',
    value: '98%',
    icon: ShieldCheck,
    bg: '#EEEDFE',
    color: '#534AB7',
  },
];

const events = [
  { msg: 'Kafka Broker #3 recovered',              time: '2m ago',  status: 'success' },
  { msg: 'High memory on Connect Worker-02',        time: '15m ago', status: 'warning' },
  { msg: 'KRaft quorum stabilized',                 time: '1h ago',  status: 'success' },
  { msg: 'Replication lag cleared on topic orders', time: '2h ago',  status: 'success' },
];

export function Dashboard() {
  return (
    <div className="dashboard animate-fade-in">

      <header className="page-header">
        <h1>Platform overview</h1>
        <p>Real-time metrics from the Tantor management plane</p>
      </header>

      <section className="stats-grid">
        {stats.map((s) => (
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
            {events.map((e, i) => (
              <div key={i} className="feed-item">
                <span className={`dot ${e.status}`} />
                <p>{e.msg}</p>
                <span className="time">{e.time}</span>
              </div>
            ))}
          </div>
        </div>

      </section>
    </div>
  );
}