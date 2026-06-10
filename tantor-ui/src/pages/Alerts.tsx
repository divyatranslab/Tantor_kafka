import { useState, useEffect } from 'react';
import { ShieldAlert, RefreshCw, AlertTriangle, CheckCircle } from 'lucide-react';
import './AuditLogs.css'; // Reuse audit log styles for table

export function Alerts() {
  const [alerts, setAlerts] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  const fetchAlerts = () => {
    setLoading(true);
    fetch('/api/v1/ui/alerts')
      .then(res => res.json())
      .then(setAlerts)
      .catch(console.error)
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    fetchAlerts();
  }, []);

  const getIcon = (severity: string) => {
    switch (severity) {
      case 'CRITICAL': return <AlertTriangle size={18} style={{ color: 'var(--color-error)' }} />;
      case 'WARNING': return <AlertTriangle size={18} style={{ color: 'var(--color-warning)' }} />;
      default: return <ShieldAlert size={18} style={{ color: 'var(--color-info)' }} />;
    }
  };

  return (
    <div className="audit-page animate-fade-in">
      <header className="page-header flex-between">
        <div>
          <h1>Active Alerts</h1>
          <p>System health anomalies and configuration warnings</p>
        </div>
        <button className="btn" onClick={fetchAlerts}>
          <RefreshCw size={14} className={loading ? 'spin' : ''} />
          Refresh
        </button>
      </header>

      <div className="glass-panel" style={{ padding: 0, overflow: 'hidden' }}>
        {loading ? (
          <div className="state-center" style={{ padding: '3rem' }}>
            <RefreshCw className="spin" size={24} style={{ color: 'var(--accent-primary)', marginBottom: '1rem' }} />
            <p>Loading alerts...</p>
          </div>
        ) : alerts.length === 0 ? (
          <div className="state-center" style={{ padding: '3rem' }}>
            <CheckCircle size={48} style={{ color: 'var(--color-success)', marginBottom: '1rem' }} />
            <h3>All Systems Healthy</h3>
            <p style={{ color: 'var(--text-secondary)' }}>No active alerts or warnings triggered.</p>
          </div>
        ) : (
          <table className="audit-table">
            <thead>
              <tr>
                <th style={{ width: '50px' }}></th>
                <th style={{ width: '180px' }}>Triggered At</th>
                <th style={{ width: '120px' }}>Severity</th>
                <th>Title & Description</th>
                <th style={{ width: '200px' }}>Cluster ID</th>
              </tr>
            </thead>
            <tbody>
              {alerts.map((alert) => (
                <tr key={alert.id}>
                  <td style={{ textAlign: 'center' }}>{getIcon(alert.severity)}</td>
                  <td className="mono" style={{ fontSize: '0.85rem' }}>{new Date(alert.createdAt).toLocaleString()}</td>
                  <td>
                    <span className={`tag ${alert.severity.toLowerCase()}`}>{alert.severity}</span>
                  </td>
                  <td>
                    <div style={{ fontWeight: 600, color: 'var(--text-primary)' }}>{alert.title}</div>
                    <div style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginTop: '4px' }}>{alert.description}</div>
                  </td>
                  <td className="mono" style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                    {alert.clusterId || '-'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
