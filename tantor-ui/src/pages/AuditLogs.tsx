import { useState, useEffect } from 'react';
import { ShieldAlert, RefreshCw, AlertTriangle, CheckCircle, Info } from 'lucide-react';
import './AuditLogs.css';

export function AuditLogs() {
  const [logs, setLogs] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  const fetchLogs = () => {
    setLoading(true);
    fetch('/api/v1/ui/dashboard/activity')
      .then(res => res.json())
      .then(setLogs)
      .catch(console.error)
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    fetchLogs();
  }, []);

  const getIcon = (level: string) => {
    switch (level) {
      case 'ERROR':
      case 'CRITICAL': return <AlertTriangle size={18} style={{ color: 'var(--color-error)' }} />;
      case 'WARN':
      case 'WARNING': return <AlertTriangle size={18} style={{ color: 'var(--color-warning)' }} />;
      case 'SUCCESS': return <CheckCircle size={18} style={{ color: 'var(--color-success)' }} />;
      default: return <Info size={18} style={{ color: 'var(--color-info)' }} />;
    }
  };

  return (
    <div className="audit-page animate-fade-in">
      <header className="page-header flex-between">
        <div>
          <h1>Audit Logs</h1>
          <p>System activity, security events, and configuration changes</p>
        </div>
        <button className="btn" onClick={fetchLogs}>
          <RefreshCw size={14} className={loading ? 'spin' : ''} />
          Refresh
        </button>
      </header>

      <div className="glass-panel" style={{ padding: 0, overflow: 'hidden' }}>
        {loading ? (
          <div className="state-center" style={{ padding: '3rem' }}>
            <RefreshCw className="spin" size={24} style={{ color: 'var(--accent-primary)', marginBottom: '1rem' }} />
            <p>Loading audit logs...</p>
          </div>
        ) : logs.length === 0 ? (
          <div className="state-center" style={{ padding: '3rem' }}>
            <ShieldAlert size={32} style={{ color: 'var(--text-secondary)', marginBottom: '1rem' }} />
            <h3>No audit logs found</h3>
            <p style={{ color: 'var(--text-secondary)' }}>No system activity has been recorded yet.</p>
          </div>
        ) : (
          <table className="audit-table">
            <thead>
              <tr>
                <th style={{ width: '50px' }}></th>
                <th style={{ width: '180px' }}>Timestamp</th>
                <th style={{ width: '120px' }}>Level</th>
                <th>Event Message</th>
                <th style={{ width: '200px' }}>Cluster ID</th>
              </tr>
            </thead>
            <tbody>
              {logs.map((log) => (
                <tr key={log.id}>
                  <td style={{ textAlign: 'center' }}>{getIcon(log.level)}</td>
                  <td className="mono" style={{ fontSize: '0.85rem' }}>{new Date(log.createdAt).toLocaleString()}</td>
                  <td>
                    <span className={`tag ${log.level.toLowerCase()}`}>{log.level}</span>
                  </td>
                  <td style={{ fontWeight: 500, color: 'var(--text-primary)' }}>{log.message}</td>
                  <td className="mono" style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                    {log.clusterId || '-'}
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
