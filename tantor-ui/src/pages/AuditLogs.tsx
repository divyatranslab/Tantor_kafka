import { useState, useEffect, useMemo } from 'react';
import { ShieldAlert, RefreshCw, AlertTriangle, CheckCircle, Info, LockKeyhole, Search } from 'lucide-react';
import './AuditLogs.css';

interface AuditLogRow {
  id: string;
  level?: string;
  message: string;
  clusterId?: string;
  eventType?: string;
  action?: string;
  actor?: string;
  resourceType?: string;
  resourceId?: string;
  oldValue?: string;
  newValue?: string;
  ipAddress?: string;
  eventStatus?: string;
  approvalStatus?: string;
  createdAt: string;
}

export function AuditLogs() {
  const [logs, setLogs] = useState<AuditLogRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [search, setSearch] = useState('');
  const [eventType, setEventType] = useState('ALL');

  const fetchLogs = async () => {
    setLoading(true);
    setError('');
    try {
      const response = await fetch('/api/v1/ui/dashboard/activity');
      if (!response.ok) throw new Error(`Audit request failed (${response.status})`);
      setLogs(await response.json());
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load audit logs');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchLogs();
  }, []);

  const eventTypes = useMemo(() => Array.from(new Set(logs.map(log => log.eventType).filter(Boolean))).sort(), [logs]);
  const filteredLogs = useMemo(() => {
    const query = search.trim().toLowerCase();
    return logs.filter(log => {
      const typeMatches = eventType === 'ALL' || (log.eventType || 'LEGACY') === eventType;
      const text = [log.message, log.actor, log.action, log.resourceType, log.resourceId, log.clusterId, log.ipAddress]
        .filter(Boolean).join(' ').toLowerCase();
      return typeMatches && (!query || text.includes(query));
    });
  }, [logs, search, eventType]);


  const getIcon = (level?: string) => {
    switch (level?.toUpperCase()) {
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
          <p>Who changed what, when, and from where.</p>
          <span className="audit-immutable"><LockKeyhole size={12} /> Append-only records</span>
        </div>


        <button className="btn" onClick={fetchLogs}>
          <RefreshCw size={14} className={loading ? 'spin' : ''} />
          Refresh
        </button>
      </header>

      {error && <div className="audit-error">{error}</div>}

      <div className="audit-toolbar">
        <label className="audit-search">
          <Search size={15} />
          <input value={search} onChange={event => setSearch(event.target.value)} placeholder="Search actor, action, resource, IP..." />
        </label>
        <select value={eventType} onChange={event => setEventType(event.target.value)} aria-label="Filter event type">
          <option value="ALL">All event types</option>
          <option value="LEGACY">Legacy events</option>
          {eventTypes.map(type => <option key={type} value={type}>{type}</option>)}
        </select>
        <span>{filteredLogs.length} of {logs.length} records</span>
      </div>







      <div className="glass-panel" style={{ padding: 0, overflow: 'hidden' }}>
        {loading ? (
          <div className="state-center" style={{ padding: '3rem' }}>
            <RefreshCw className="spin" size={24} style={{ color: 'var(--accent-primary)', marginBottom: '1rem' }} />
            <p>Loading audit logs...</p>
          </div>
        ) : filteredLogs.length === 0 ? (
          <div className="state-center" style={{ padding: '3rem' }}>
            <ShieldAlert size={32} style={{ color: 'var(--text-secondary)', marginBottom: '1rem' }} />
            <h3>{logs.length ? 'No matching audit logs' : 'No audit logs found'}</h3>
            <p style={{ color: 'var(--text-secondary)' }}>{logs.length ? 'Adjust the search or event filter.' : 'No system activity has been recorded yet.'}</p>
          </div>
        ) : (
          <table className="audit-table">
            <thead>
              <tr>
                <th>Timestamp</th>
                <th>Actor</th>
                <th>Event</th>
                <th>Details</th>
                <th>Resource</th>
                <th>Status</th>
                <th>Source IP</th>
              </tr>
            </thead>
            <tbody>
              {filteredLogs.map((log) => (
                <tr key={log.id}>
                  <td className="mono audit-time">{new Date(log.createdAt).toLocaleString()}</td>
                  <td><strong>{log.actor || 'system'}</strong></td>
                  <td>
                    <div className="audit-event">{getIcon(log.level)} <span>{log.eventType || 'LEGACY'}</span></div>
                    <small>{log.action || log.level || 'EVENT'}</small>
                  </td>
                  <td className="audit-details">
                    <strong>{log.message}</strong>
                    {(log.oldValue || log.newValue) && <small>{log.oldValue || '-'} <span>?</span> {log.newValue || '-'}</small>}
                    {log.approvalStatus && <small>Approval: {log.approvalStatus}</small>}
                  </td>
                  <td>
                    <span>{log.resourceType || (log.clusterId ? 'CLUSTER' : '-')}</span>
                    <small className="mono">{log.resourceId || log.clusterId || '-'}</small>
                  </td>
                  <td><span className={`tag ${(log.eventStatus || log.level || 'info').toLowerCase()}`}>{log.eventStatus || log.level || 'INFO'}</span></td>
                  <td className="mono">{log.ipAddress || '-'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
