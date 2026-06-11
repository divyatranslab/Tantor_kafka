import { useState, useEffect } from 'react';
import { CheckCircle2, AlertCircle, AlertTriangle, Settings, Activity, Server, Database, Box, Layers, HardDrive } from 'lucide-react';
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

  // Map real clusters to the Cloudera-style list
  // If no clusters exist, show empty state, otherwise list them
  const services = clusters.map(c => ({
    name: c.name,
    status: c.nodeCount > 0 ? 'healthy' : 'warning',
    alerts: 0,
    icon: Activity
  }));
  
  // Add some core platform services that are always present
  const platformServices = [
    { name: `${stats?.totalHosts ?? 0} Hosts`, status: 'healthy', alerts: stats?.activeAlerts || 0, icon: Settings },
    { name: 'Core Configuration', status: 'unknown', alerts: 0, icon: Settings },
    { name: 'Tantor Agent Management', status: 'healthy', alerts: 0, icon: Server },
  ];

  const allServices = [...platformServices, ...services];

  const renderStatusIcon = (status: string) => {
    switch(status) {
      case 'healthy': return <CheckCircle2 className="status-icon healthy" size={18} />;
      case 'warning': return <AlertTriangle className="status-icon warning" size={18} />;
      case 'error': return <AlertCircle className="status-icon error" size={18} />;
      default: return <div className="status-icon unknown" />;
    }
  };

  return (
    <div className="dashboard animate-fade-in">

      <header className="page-header cloudera-header">
        <div className="header-tabs">
          <span className="active-tab">Status</span>
          <span>All Health Issues <span className="badge error">{stats?.activeAlerts || 0}</span></span>
          <span>Configuration <span className="badge warning">0</span></span>
          <span>All Recent Commands</span>
        </div>
      </header>

      <section className="cloudera-grid">
        {/* ── Left Pane: Services ── */}
        <div className="services-pane">
          <div className="services-header">
            <h2 className="cluster-title">
              <CheckCircle2 className="status-icon healthy" size={20} />
              Tantor Runtime
            </h2>
            <p className="runtime-version">Platform Core (Parcels)</p>
          </div>
          <div className="services-list">
            {allServices.length === 0 ? (
              <div style={{padding: '1rem', color: '#666'}}>No services deployed yet. Click "Add Service" to begin.</div>
            ) : allServices.map((svc) => (
              <div key={svc.name} className="service-row">
                <div className="service-name-col">
                  {renderStatusIcon(svc.status)}
                  <svc.icon size={16} className="service-type-icon" />
                  <span className="service-name">{svc.name}</span>
                </div>
                <div className="service-alerts-col">
                  {svc.alerts > 0 && (
                    <span className={`alert-count ${svc.status}`}>
                      <Settings size={14} /> {svc.alerts}
                    </span>
                  )}
                </div>
                <div className="service-actions-col">
                  ⋮
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* ── Right Pane: Charts ── */}
        <div className="charts-pane">
          <div className="charts-header">
            <h2>Charts</h2>
            <div className="time-filters">
              <span className="active">30m</span>
              <span>1h</span>
              <span>2h</span>
              <span>6h</span>
              <span>12h</span>
              <span>1d</span>
              <span>7d</span>
              <span>30d</span>
            </div>
          </div>

          <div className="chart-card-c">
            <div className="chart-title-c">Cluster CPU</div>
            <div className="chart-body-c">
              <svg viewBox="0 0 400 100" className="line-chart">
                <path d="M0 80 L50 82 L100 78 L150 80 L200 79 L250 81 L300 78 L350 80 L400 79" fill="none" stroke="#2196F3" strokeWidth="2" strokeDasharray="4 4" />
                <path d="M0 85 L50 86 L100 84 L150 85 L200 84 L250 86 L300 85 L350 86 L400 85" fill="none" stroke="#64B5F6" strokeWidth="1" />
              </svg>
              <div className="chart-legend">- Translab, Host CPU Usage Across Hosts <strong>4.1%</strong></div>
            </div>
          </div>

          <div className="chart-card-c">
            <div className="chart-title-c">Cluster Disk IO</div>
            <div className="chart-body-c">
              <svg viewBox="0 0 400 100" className="area-chart">
                <path d="M0 100 L0 50 L40 45 L80 60 L120 40 L160 55 L200 30 L240 60 L280 40 L320 25 L360 45 L400 50 L400 100 Z" fill="#CDDC39" opacity="0.8" />
                <path d="M0 100 L0 90 L40 88 L80 92 L120 85 L160 90 L200 80 L240 95 L280 88 L320 82 L360 88 L400 90 L400 100 Z" fill="#2196F3" opacity="0.8" />
              </svg>
              <div className="chart-legend"><span style={{color: '#2196F3'}}>- Total Disk By... <strong>12.9K/s</strong></span> <span style={{color: '#CDDC39'}}>- Total Disk By... <strong>2.5M/s</strong></span></div>
            </div>
          </div>

          <div className="chart-card-c">
            <div className="chart-title-c">Cluster Network IO</div>
            <div className="chart-body-c">
              <svg viewBox="0 0 400 100" className="line-chart">
                <path d="M0 90 L50 80 L100 85 L150 60 L200 95 L250 70 L300 90 L350 40 L400 80" fill="none" stroke="#2196F3" strokeWidth="1.5" />
                <path d="M0 95 L50 85 L100 90 L150 75 L200 80 L250 85 L300 95 L350 60 L400 85" fill="none" stroke="#CDDC39" strokeWidth="1.5" />
              </svg>
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}