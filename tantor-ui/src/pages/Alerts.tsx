import { useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle, CheckCircle, Clock, Database, HardDrive, RefreshCw, Server,
  ShieldAlert
} from 'lucide-react';
import './Alerts.css';

interface AlertRow {
  id: string;
  severity: string;
  title: string;
  description?: string;
  clusterId?: string;
  clusterName?: string;
  hostId?: string;
  hostIp?: string;
  status?: string;
  createdAt?: string;
  errorLog?: string;
  source?: string;
}

export function Alerts() {
  const [alerts, setAlerts] = useState<AlertRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const fetchAlerts = async () => {
    setLoading(true);
    setError('');
    try {
      const res = await fetch('/api/v1/ui/alerts');
      if (!res.ok) throw new Error(`Alerts request failed (${res.status})`);
      setAlerts(await res.json());
    } catch (e: any) {
      setError(e.message || 'Failed to load alerts');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchAlerts();
  }, []);

  const summary = useMemo(() => {
    const critical = alerts.filter(alert => alert.severity?.toUpperCase() === 'CRITICAL').length;
    const warning = alerts.filter(alert => alert.severity?.toUpperCase() === 'WARNING').length;
    const clusters = new Set(alerts.map(alert => alert.clusterId).filter(Boolean)).size;
    return { critical, warning, clusters };
  }, [alerts]);

  return (
    <div className="alerts-page animate-fade-in">
      <header className="alerts-hero">
        <div>
          <span className={`alerts-state ${alerts.length ? 'needs-attention' : 'healthy'}`}>
            {alerts.length ? <AlertTriangle size={14} /> : <CheckCircle size={14} />}
            {alerts.length ? 'Live system needs attention' : 'All systems healthy'}
          </span>
          <h1>Active Alerts</h1>
          <p>Runtime health, failed tasks, storage pressure, and cluster availability signals.</p>
        </div>
        <button className="alerts-refresh" onClick={fetchAlerts}>
          <RefreshCw size={14} className={loading ? 'spin' : ''} />
          Refresh
        </button>
      </header>

      {error && <div className="alerts-banner">{error}</div>}

      <section className="alerts-summary">
        <SummaryCard label="Critical" value={summary.critical} tone={summary.critical ? 'bad' : 'good'} icon={ShieldAlert} />
        <SummaryCard label="Warnings" value={summary.warning} tone={summary.warning ? 'warn' : 'good'} icon={AlertTriangle} />
        <SummaryCard label="Impacted Clusters" value={summary.clusters} tone={summary.clusters ? 'bad' : 'good'} icon={Database} />
      </section>

      <section className="alerts-panel">
        {loading ? (
          <div className="alerts-empty">
            <RefreshCw className="spin" size={24} />
            <strong>Loading alerts...</strong>
            <span>Checking cluster tasks, hosts, disk usage, and runtime state.</span>
          </div>
        ) : alerts.length === 0 ? (
          <div className="alerts-empty healthy">
            <CheckCircle size={44} />
            <strong>No active alerts</strong>
            <span>Hosts, clusters, parcels, and recent tasks are not reporting failures.</span>
          </div>
        ) : (
          <div className="alerts-list">
            {alerts.map(alert => (
              <article key={alert.id} className={`alert-card ${severityTone(alert.severity)}`}>
                <div className="alert-card-icon">
                  {alert.source === 'host' ? <Server size={18} /> : alert.source === 'task' ? <Clock size={18} /> : <HardDrive size={18} />}
                </div>
                <div className="alert-card-body">
                  <div className="alert-card-top">
                    <div>
                      <span className="alert-source">{sourceLabel(alert.source)}</span>
                      <h2>{alert.title}</h2>
                    </div>
                    <span className={`alert-severity ${severityTone(alert.severity)}`}>{alert.severity || 'INFO'}</span>
                  </div>

                  {alert.description && <p>{alert.description}</p>}

                  <div className="alert-meta">
                    <Meta label="Cluster" value={clusterLabel(alert)} />
                    <Meta label="Cluster ID" value={alert.clusterId || '-'} mono />
                    <Meta label="Host / IP" value={hostLabel(alert)} />
                    <Meta label="Status" value={alert.status || '-'} />
                    <Meta label="Detected" value={formatDateTime(alert.createdAt) || '-'} />
                  </div>

                  {alert.errorLog && (
                    <pre className="alert-log">{alert.errorLog}</pre>
                  )}
                </div>
              </article>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

function SummaryCard({ label, value, tone, icon: Icon }: { label: string; value: number; tone: string; icon: any }) {
  return (
    <article className={`alerts-summary-card ${tone}`}>
      <div><Icon size={17} /></div>
      <span>{label}</span>
      <strong>{value}</strong>
    </article>
  );
}

function Meta({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="alert-meta-item">
      <span>{label}</span>
      <b className={mono ? 'mono' : ''}>{value}</b>
    </div>
  );
}

function severityTone(severity?: string) {
  const normalized = severity?.toUpperCase();
  if (normalized === 'CRITICAL') return 'bad';
  if (normalized === 'WARNING') return 'warn';
  return 'info';
}

function sourceLabel(source?: string) {
  if (!source) return 'Runtime';
  return source.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
}

function clusterLabel(alert: AlertRow) {
  if (alert.clusterName && alert.clusterName !== '-') return alert.clusterName;
  return alert.clusterId || '-';
}

function hostLabel(alert: AlertRow) {
  const host = alert.hostId && alert.hostId !== '-' ? alert.hostId : '';
  const ip = alert.hostIp && alert.hostIp !== '-' ? alert.hostIp : '';
  if (host && ip) return `${host} / ${ip}`;
  return host || ip || '-';
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
