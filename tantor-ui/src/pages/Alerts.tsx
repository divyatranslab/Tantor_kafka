import { useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle, CheckCircle, RefreshCw,
  Shield, Activity
} from 'lucide-react';
import './Alerts.css';

const CustomRefreshIcon = ({ size = 20, color = "#818181", className = "" }: { size?: number, color?: string, className?: string }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="1.25" strokeLinecap="round" strokeLinejoin="round" className={className}>
    <path d="M 12 5 A 7 7 0 0 1 17 17" />
    <path d="M 18 13 L 17 17 L 21 16" />
    <path d="M 12 19 A 7 7 0 0 1 7 7" />
    <path d="M 6 11 L 7 7 L 3 8" />
  </svg>
);

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
    <div className="alerts-container">
      {/* Frame 1000004628 */}
      <div className="alerts-wrapper">
        
        <header className="alerts-header">
          {/* Left side info */}
          <div className="alerts-header-info">
            <h1>Alerts</h1>
            <p className="alerts-subtitle">Runtime health, failed tasks, storage pressure, and cluster availability signals.</p>
          </div>
          
          {/* Right side actions */}
          <div className="alerts-header-actions">
            <span className={`alerts-status-badge ${alerts.length ? 'needs-attention' : 'healthy'}`}>
              {alerts.length ? 'Live system needs attention' : 'Live system healthy'}
            </span>
            <button className="alerts-refresh-btn" onClick={fetchAlerts} aria-label="Refresh alerts">
              <CustomRefreshIcon size={24} color="#818181" className={`alerts-refresh-icon ${loading ? 'spin' : ''}`} />
            </button>
          </div>
        </header>

        {error && <div className="alerts-banner-error">{error}</div>}

        {/* Frame 1000005223 */}
        <div className="alerts-content-body">
          
          {/* Frame 1000005211 (KPI Gradient Container) */}
          <section className="alerts-kpi-banner">
            <div className="alerts-kpi-row">
              
              {/* Critical KPI Card */}
              <div className="alerts-kpi-card">
                <div className="alerts-kpi-header">
                  <div className="alerts-kpi-icon-container critical">
                    <Shield size={24} className="alerts-kpi-icon" />
                  </div>
                  <span className="alerts-kpi-title">Critical</span>
                </div>
                <div className="alerts-kpi-value-row">
                  <span className="alerts-kpi-value">{String(summary.critical).padStart(2, '0')}</span>
                </div>
              </div>

              {/* Warnings KPI Card */}
              <div className="alerts-kpi-card">
                <div className="alerts-kpi-header">
                  <div className="alerts-kpi-icon-container warning">
                    <AlertTriangle size={24} className="alerts-kpi-icon" />
                  </div>
                  <span className="alerts-kpi-title">Warnings</span>
                </div>
                <div className="alerts-kpi-value-row">
                  <span className="alerts-kpi-value">{String(summary.warning).padStart(2, '0')}</span>
                </div>
              </div>

              {/* Impacted Clusters KPI Card */}
              <div className="alerts-kpi-card">
                <div className="alerts-kpi-header">
                  <div className="alerts-kpi-icon-container report">
                    <Activity size={24} className="alerts-kpi-icon" />
                  </div>
                  <span className="alerts-kpi-title">Impacted Clusters</span>
                </div>
                <div className="alerts-kpi-value-row">
                  <span className="alerts-kpi-value">{String(summary.clusters).padStart(2, '0')}</span>
                </div>
              </div>

            </div>
          </section>

          {/* Frame 1000005212 (Details Panel) */}
          <section className="alerts-details-panel">
            <h2>Details Activity</h2>

            {/* Frame 1000005221 */}
            <div className="alerts-details-list">
              {loading ? (
                <div className="alerts-empty-state">
                  <RefreshCw className="spin" size={24} />
                  <strong>Loading alerts...</strong>
                </div>
              ) : alerts.length === 0 ? (
                <div className="alerts-empty-state healthy">
                  <CheckCircle size={44} />
                  <strong>No active alerts</strong>
                  <span>Hosts, clusters, parcels, and recent tasks are not reporting failures.</span>
                </div>
              ) : (
                alerts.map(alert => (
                  /* Frame 1000005219 / 1000005225 */
                  <article key={alert.id} className="alerts-detail-card">
                    {/* Frame 1000005354 */}
                    <div className="alerts-detail-card-content">
                      {/* Frame 1000005353 */}
                      <div className="alerts-detail-title-row">
                        <span className={`alerts-detail-severity-icon ${severityTone(alert.severity)}`}>
                          <AlertTriangle size={24} />
                        </span>
                        
                        {/* Frame 1000005218 */}
                        <div className="alerts-detail-info-col">
                          {/* Frame 1000005217 */}
                          <div className="alerts-detail-header-text">
                            {/* Frame 1000005352 */}
                            <div className="alerts-detail-title-line">
                              <h3>{alert.title}</h3>
                              <span className="alerts-detail-category-pill">{sourceLabel(alert.source)}</span>
                            </div>
                            
                            {/* Frame 1000005355 */}
                            {alert.description && (
                              <p className="alerts-detail-desc">{alert.description}</p>
                            )}
                            
                            {/* Frame 1000005356 (Metadata Grid) */}
                            <div className="alerts-detail-metadata">
                              {/* Cluster */}
                              <div className="alerts-meta-block">
                                <span className="meta-block-label">Cluster</span>
                                <span className="meta-block-val">{clusterLabel(alert)}</span>
                              </div>
                              <div className="alerts-meta-separator" />
                              
                              {/* Cluster ID */}
                              <div className="alerts-meta-block">
                                <span className="meta-block-label">Cluster ID</span>
                                <span className="meta-block-val mono">{alert.clusterId || '-'}</span>
                              </div>
                              <div className="alerts-meta-separator" />
                              
                              {/* Host / IP */}
                              <div className="alerts-meta-block">
                                <span className="meta-block-label">Host / IP</span>
                                <span className="meta-block-val">{hostLabel(alert)}</span>
                              </div>
                              <div className="alerts-meta-separator" />
                              
                              {/* Detected */}
                              <div className="alerts-meta-block">
                                <span className="meta-block-label">Detected</span>
                                <span className="meta-block-val">{formatDateTime(alert.createdAt) || '-'}</span>
                              </div>
                              <div className="alerts-meta-separator" />

                              {/* Status Badge */}
                              <div className="alerts-meta-status-container">
                                <span className="alerts-detail-status-pill">Active</span>
                              </div>
                            </div>

                            {/* Frame 1000005357 (Logs box) */}
                            {alert.errorLog && (
                              <div className="alerts-detail-log-box">
                                <pre>{alert.errorLog}</pre>
                              </div>
                            )}

                          </div>
                        </div>
                      </div>
                    </div>
                  </article>
                ))
              )}
            </div>
          </section>

        </div>
      </div>
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
