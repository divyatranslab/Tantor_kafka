import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { MoreVertical, Network, RefreshCw, Trash2, Server, HardDrive, ExternalLink, RotateCcw, ServerCog, Settings, Plus } from 'lucide-react';
import { usePermissions } from '../hooks/usePermissions';
import { confirmAction, notifyAction } from '../components/ConfirmDialog';
import { clusterStatusTone } from '../utils/clusterStatusTone';
import './Clusters.css';

interface ClusterHost {
  hostId?: string;
  hostname?: string;
  ipAddress?: string;
  status?: string;
  role?: string;
  lastHeartbeat?: string;
  diskUsedGb?: number;
  diskTotalGb?: number;
  bootstrap?: string;
}

interface ClusterInfo {
  id: string;
  name: string;
  kafkaVersion: string;
  mode: string;
  environment: string;
  createdAt: string;
  status: string;
  nodeCount: number;
  bootstrapServers?: string;
  clusterId?: string;
  kafkaClusterId?: string;
  managementLevel?: string;
  sourceLabel?: string;
  accessLabel?: string;
  hosts?: ClusterHost[];
  managedHostsCount?: number;
  totalHostsCount?: number;
  telemetry?: string;
  lastAgentHeartbeat?: string;
  kafkaHealth?: string;
  agentHealth?: string;
  monitoringHealth?: string;
  overallHealth?: string;
  runtimeHealth?: string;
  runtimeStatusLabel?: string;
  runtimeStatusReason?: string;
  kafkaHealthChecking?: boolean;
}

const EXTERNAL_HEALTH_REFRESH_MS = 15000;

export function Clusters() {
  const navigate = useNavigate();
  const { canManage } = usePermissions();
  const [clusters, setClusters] = useState<ClusterInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [openMenuId, setOpenMenuId] = useState<string | null>(null);
  const [showDeploymentModal, setShowDeploymentModal] = useState(false);

  const fetchClusters = async () => {
    setLoading(true);
    try {
      const res = await fetch('/api/v1/ui/clusters');
      if (res.ok) {
        const data: ClusterInfo[] = await res.json();
        const visibleData = data.map(cluster => cluster.mode === 'EXTERNAL'
          ? { ...cluster, kafkaHealthChecking: true }
          : cluster
        );
        setClusters(visibleData);
        refreshExternalKafkaHealth(visibleData);
      }
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  const refreshExternalKafkaHealth = (items: ClusterInfo[], showChecking = true) => {
    const externalClusters = items.filter(cluster => cluster.mode === 'EXTERNAL');
    if (externalClusters.length === 0) return;

    if (showChecking) {
      const externalIds = new Set(externalClusters.map(cluster => cluster.id));
      setClusters(prev => prev.map(cluster => externalIds.has(cluster.id)
        ? { ...cluster, kafkaHealthChecking: true }
        : cluster
      ));
    }

    externalClusters
      .forEach(cluster => {
        const controller = new AbortController();
        const timeout = window.setTimeout(() => controller.abort(), 7000);
        fetch(`/api/v1/ui/clusters/${cluster.id}`, { signal: controller.signal })
          .then(res => res.ok ? res.json() : Promise.reject(new Error('Kafka health request failed')))
          .then((fresh: ClusterInfo) => {
            setClusters(prev => prev.map(current => current.id === cluster.id
              ? {
                ...current,
                kafkaHealthChecking: false,
                kafkaHealth: fresh.kafkaHealth,
                agentHealth: fresh.agentHealth,
                monitoringHealth: fresh.monitoringHealth,
                overallHealth: fresh.overallHealth,
                runtimeHealth: fresh.runtimeHealth,
                runtimeStatusLabel: fresh.runtimeStatusLabel,
                runtimeStatusReason: fresh.runtimeStatusReason,
                managementLevel: fresh.managementLevel,
                accessLabel: fresh.accessLabel,
                telemetry: fresh.telemetry,
                managedHostsCount: fresh.managedHostsCount,
                totalHostsCount: fresh.totalHostsCount,
                lastAgentHeartbeat: fresh.lastAgentHeartbeat,
                hosts: fresh.hosts,
                nodeCount: fresh.nodeCount,
                status: fresh.status || current.status,
              }
              : current
            ));
          })
          .catch(() => {
            setClusters(prev => prev.map(current => current.id === cluster.id
              ? {
                ...current,
                kafkaHealthChecking: false,
                kafkaHealth: 'OFFLINE',
                runtimeHealth: 'OFFLINE',
                overallHealth: 'OFFLINE',
                runtimeStatusLabel: 'Kafka Offline',
                runtimeStatusReason: 'Kafka live check timed out or failed.',
              }
              : current
            ));
          })
          .finally(() => window.clearTimeout(timeout));
      });
  };

  const deleteCluster = async (e: React.MouseEvent, id: string, name: string) => {
    e.stopPropagation();
    if (!canManage) return;
    if (!(await confirmAction(`Delete cluster '${name}' and clean it from assigned VM(s)?`))) return;
    try {
      const res = await fetch(`/api/v1/ui/clusters/${id}`, { method: 'DELETE' });
      if (res.ok) {
        setClusters(prev =>
          prev
            .map(c => c.id === id
              ? { ...c, status: c.mode === 'EXTERNAL' ? 'DELETED' : 'DELETING' }
              : c
            )
            .filter(c => c.status !== 'DELETED')
        );
        setTimeout(fetchClusters, 2000);
      } else {
        notifyAction('Failed to delete cluster.');
      }
    } catch {
      notifyAction('An error occurred while deleting.');
    }
  };

  const triggerRollingRestart = async (cluster: ClusterInfo) => {
    if (!canManage) return;
    const nodeCount = cluster.nodeCount || cluster.hosts?.length || 0;
    const warning = nodeCount === 1
      ? `WARNING: '${cluster.name}' has only one node. Three nodes are recommended for availability, and this restart will interrupt Kafka service. Do you want to continue?`
      : `Start rolling restart for '${cluster.name}'?`;
    if (!(await confirmAction(warning))) return;
    try {
      const res = await fetch(`/api/v1/clusters/${cluster.id}/actions/rolling-restart`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ confirmSingleNode: nodeCount === 1 }),
      });
      const data = await res.json().catch(() => ({}));
      if (res.ok) {
        navigate(data.jobId ? `/jobs/${data.jobId}` : `/clusters/${cluster.id}/actions`);
      } else {
        notifyAction(data.error || 'Failed to schedule rolling restart.');
      }
    } catch {
      notifyAction('Network error while scheduling rolling restart.');
    }
  };

  const isClickable = (c: ClusterInfo) =>
    c.status === 'SUCCESS' || c.mode === 'EXTERNAL';

  const statusLabel = (c: ClusterInfo) => {
    if (c.kafkaHealthChecking) return 'Checking Kafka...';
    if (c.runtimeStatusLabel) return c.runtimeStatusLabel;
    if (c.mode === 'EXTERNAL') {
      if (c.status === 'SUCCESS') return 'Connected';
      if (c.status === 'DEGRADED') return 'Degraded';
      return c.status;
    }
    if (c.status === 'SUCCESS') return 'Active';
    return c.status;
  };

  const statusClass = (c: ClusterInfo) => {
    if (c.kafkaHealthChecking) return 'checking';
    const runtime = (c.runtimeHealth || '').toLowerCase();
    if (runtime) return runtime;
    if (c.mode === 'EXTERNAL') return c.status === 'SUCCESS' ? 'external' : (c.status || 'external').toLowerCase();
    return (c.status || 'pending').toLowerCase();
  };

  const inProgress = (status: string) =>
    ['PENDING', 'RUNNING', 'VALIDATING', 'DELETING'].includes(status);

  const displayKafkaClusterId = (value?: string) => value && value.trim() ? value : '-';

  const formatHeartbeat = (value?: string) => {
    if (!value) return '-';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '-';
    return date.toLocaleString([], {
      month: 'short',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  const formatCreatedDate = (value?: string) => {
    if (!value) return '-';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '-';
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    const day = date.getDate();
    const month = months[date.getMonth()];
    const hours = String(date.getHours()).padStart(2, '0');
    const minutes = String(date.getMinutes()).padStart(2, '0');
    return `${day} ${month}, ${hours}:${minutes}`;
  };

  const primaryHost = (cluster: ClusterInfo) => cluster.hosts?.[0];

  const diskLabel = (host?: ClusterHost) => {
    if (!host?.diskTotalGb || host.diskTotalGb <= 0) return '-';
    const used = host.diskUsedGb ?? 0;
    return `${used}/${host.diskTotalGb} GB`;
  };

  const diskPct = (host?: ClusterHost) => {
    if (!host?.diskTotalGb || host.diskTotalGb <= 0) return 0;
    return Math.min(100, Math.round(((host.diskUsedGb ?? 0) / host.diskTotalGb) * 100));
  };

  const managementLabel = (cluster: ClusterInfo) => {
    if (cluster.accessLabel) return cluster.accessLabel;
    if (cluster.mode !== 'EXTERNAL') return 'Full access';
    if (cluster.managementLevel === 'AGENT_MANAGED') return 'Fully managed';
    return 'Metadata available';
  };

  const managementClass = (cluster: ClusterInfo) => {
    const label = `${cluster.managementLevel || ''} ${cluster.accessLabel || ''}`.toLowerCase();
    return label.includes('bootstrap') || label.includes('metadata') ? 'metadata' : 'managed';
  };

  const agentHealthLabel = (cluster: ClusterInfo) => {
    if (cluster.mode !== 'EXTERNAL') return '';
    switch ((cluster.agentHealth || '').toUpperCase()) {
      case 'CONNECTED':
        return 'Agent connected';
      case 'PARTIAL':
        return 'Agent partial';
      case 'NOT_INSTALLED':
        return 'Agent not installed';
      case 'NOT_CONNECTED':
        return 'Agent not connected';
      default:
        return 'Agent not connected';
    }
  };

  const agentHealthClass = (cluster: ClusterInfo) => {
    switch ((cluster.agentHealth || '').toUpperCase()) {
      case 'CONNECTED':
        return 'connected';
      case 'PARTIAL':
        return 'partial';
      case 'NOT_INSTALLED':
        return 'not-installed';
      case 'NOT_CONNECTED':
        return 'not-connected';
      default:
        return 'not-connected';
    }
  };

  const sourceLabel = (cluster: ClusterInfo) =>
    cluster.sourceLabel || (cluster.mode === 'EXTERNAL' ? 'External' : 'Internal managed');

  useEffect(() => { fetchClusters(); }, []);

  useEffect(() => {
    const externalClusters = clusters.filter(cluster => cluster.mode === 'EXTERNAL');
    if (externalClusters.length === 0) return;
    const timer = window.setInterval(() => {
      refreshExternalKafkaHealth(externalClusters, true);
    }, EXTERNAL_HEALTH_REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [clusters]);

  const renderHeader = () => (
    <header className="clusters-header flex-between">
      <div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <h1>Clusters</h1>
        </div>
        <p className="clusters-subtitle">Deploy and manage your Tantor Kafka environments</p>
      </div>
      <div className="header-actions">
        <button className="btn outline-icon refresh-btn" onClick={fetchClusters} title="Refresh">
          <RefreshCw size={16} className={loading ? 'spin' : ''} />
        </button>
        {canManage && (
          <button className={`btn btn-primary-action add-cluster-btn${clusters.length > 0 ? ' deploy-cluster-btn' : ''}`} onClick={() => setShowDeploymentModal(true)}>
            {clusters.length > 0 ? <><Network size={17} /> Deploy Cluster</> : <><Plus size={17} /> Add Cluster</>}
          </button>
        )}
      </div>
    </header>
  );

  return (
    <div className={`clusters-page animate-fade-in ${!loading && clusters.length === 0 ? 'is-empty' : ''}`} onClick={() => setOpenMenuId(null)}>
      <div className="clusters-surface">
        {renderHeader()}
        {(!loading && clusters.length === 0) ? (
          <div className="white-container full-empty-card">
            <div className="state-center">
              <div className="empty-state-icon" />
              <h2>No clusters yet</h2>
              <p>
                You haven't added any Kafka clusters.<br />Click above to provision your first cluster or connect an external one.
              </p>
            </div>
          </div>
        ) : (
          <>
            <div className="inventory-header">
              <div>
                <h3>Cluster Inventory</h3>
                <span>Kafka Clusters</span>
              </div>
              <div className="inventory-total-badge">
                <span>{clusters.length} Total</span>
              </div>
            </div>
            <div className="inventory-divider" />

            <section className="clusters-inventory white-card">
              {loading ? (
                <div className="state-center loading-state">
                  <RefreshCw size={24} className="spin" style={{ color: '#3E1363' }} />
                  <p>Loading clusters...</p>
                </div>
              ) : (
                <div className="clusters-table-wrap">
                  <table className="clusters-table">
                    <thead>
                      <tr>
                        <th>Cluster Name</th>
                        <th>Cluster ID</th>
                        <th>Broker</th>
                        <th>DEV</th>
                        <th>Storage</th>
                        <th>Created</th>
                        <th>Tags</th>
                        <th></th>
                      </tr>
                    </thead>
                    <tbody>
                      {clusters.map(cluster => {
                        const host = primaryHost(cluster);
                        const progress = diskPct(host);
                        const tagTone = cluster.kafkaHealthChecking
                          ? 'state-negative'
                          : clusterStatusTone(
                            cluster.runtimeStatusLabel,
                            cluster.runtimeHealth,
                            cluster.kafkaHealth,
                            cluster.status,
                            cluster.overallHealth,
                          );
                        return (
                          <tr
                            key={cluster.id}
                            className={!isClickable(cluster) ? 'disabled' : ''}
                            onClick={() => {
                              if (isClickable(cluster)) navigate(`/clusters/${cluster.id}/overview`);
                            }}
                          >
                            <td>
                              <div className="cluster-title-cell" style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                                {cluster.mode === 'EXTERNAL' ? (
                                  <ExternalLink size={15} style={{ color: '#3E1363', flexShrink: 0, marginTop: '1px' }} />
                                ) : (
                                  <Network size={16} style={{ color: '#3E1363', flexShrink: 0, marginTop: '1px' }} />
                                )}
                                <div className="cluster-title-text" style={{ display: 'flex', flexDirection: 'column', justifyContent: 'center' }}>
                                  <strong style={{ color: '#23252D', fontWeight: 600, fontSize: '13px' }}>{cluster.name}</strong>
                                  <span style={{ color: '#818181', fontSize: '12px', display: 'block', marginTop: '2px' }}>
                                    Kafka {cluster.kafkaVersion || '4.0.1'} - {cluster.mode?.toLowerCase() || 'kraft'}
                                  </span>
                                </div>
                              </div>
                            </td>

                            <td>
                              <span className="mono-muted kafka-id-value" title={cluster.kafkaClusterId || ''}>
                                {displayKafkaClusterId(cluster.kafkaClusterId)}
                              </span>
                            </td>
                            <td>
                              <div className="host-cell-v2">
                                <strong>{host?.hostname || '-'}</strong>
                                <span>{host?.ipAddress || cluster.bootstrapServers || '-'}</span>
                              </div>
                            </td>
                            <td>
                              <div className="env-cell-v2">
                                <strong>{cluster.environment?.toUpperCase() || 'DEV'}</strong>
                                <span>{cluster.nodeCount || cluster.hosts?.length || 0} node{(cluster.nodeCount || cluster.hosts?.length || 0) === 1 ? '' : 's'}</span>
                              </div>
                            </td>
                            <td>
                              <div className="disk-cell-progress">
                                <div className="disk-label-text">
                                  {cluster.mode === 'EXTERNAL' ? (
                                    <span>{cluster.managedHostsCount || 0}/{cluster.totalHostsCount || cluster.nodeCount || 0} hosts</span>
                                  ) : (
                                    <span>{diskLabel(host)}</span>
                                  )}
                                </div>
                                <div className="progress-bar-container">
                                  <div className="progress-bar-fill" style={{ width: `${progress > 0 ? progress : 0}%` }} />
                                </div>
                              </div>
                            </td>
                            <td>
                              <span className="created-text">
                                {formatCreatedDate(cluster.createdAt)}
                              </span>
                            </td>
                            <td>
                              <div className="tags-column-wrap">
                                <span className={`source-pill-v2 ${cluster.mode === 'EXTERNAL' ? 'external' : 'internal'} ${tagTone}`}>
                                  {sourceLabel(cluster)}
                                </span>
                                <span className={`access-pill-v2 ${managementClass(cluster)} ${tagTone}`}>
                                  {managementLabel(cluster)}
                                </span>
                                <span className={`status-badge-v2 ${statusClass(cluster)} ${tagTone}`}>
                                  {statusLabel(cluster)}
                                </span>
                              </div>
                            </td>
                            <td>
                              {canManage ? (
                                <div className="row-actions cluster-menu-anchor" onClick={e => e.stopPropagation()}>
                                  <button
                                    className="btn icon-only trash-btn-action"
                                    onClick={(e) => deleteCluster(e, cluster.id, cluster.name)}
                                    title="Delete cluster"
                                  >
                                    <Trash2 size={15} />
                                  </button>
                                </div>
                              ) : (
                                <span className="heartbeat-text">View only</span>
                              )}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </section>
          </>
        )}
      </div>

      {showDeploymentModal && (
        <div className="cd-modal-backdrop" onClick={() => setShowDeploymentModal(false)}>
          <div className="cd-deployment-modal" onClick={e => e.stopPropagation()}>
            <div className="cd-deployment-modal-header">
              <div className="cd-deployment-modal-header-content">
                <h2>Cluster Development</h2>
                <p>Create a managed Kafka cluster or connect an exiting external cluster.</p>
              </div>
              <button className="cd-icon-btn close-btn" onClick={() => setShowDeploymentModal(false)} title="Close">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M18 6L6 18M6 6l12 12"></path></svg>
              </button>
            </div>

            <div className="cd-deployment-cards-wrapper">
              <div className="cd-deployment-choice-grid">
                <div className="cd-deployment-card">
                  <div className="cd-deployment-card-content">
                    <svg className="cluster-choice-icon managed" width="24" height="24" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                      <circle cx="12" cy="4.5" r="3.25" />
                      <path d="M12 7.75v6M5 13.75h14M5 13.75V17M19 13.75V17" fill="none" stroke="currentColor" strokeWidth="2.5" />
                      <rect x="2" y="17" width="6" height="5" rx="0.5" />
                      <rect x="16" y="17" width="6" height="5" rx="0.5" />
                    </svg>
                    <h3>Create your Cluster</h3>
                    <p>Build a new KRaft or ZooKeeper cluster on selected Tantor host</p>
                  </div>
                  <button className="cd-deployment-btn outline" onClick={() => { setShowDeploymentModal(false); navigate('/cluster-deployment'); }}>Create</button>
                </div>

                <div className="cd-deployment-card">
                  <div className="cd-deployment-card-content">
                    <svg className="cluster-choice-icon existing" width="24" height="24" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                      <path d="M22 11V3h-7v3H9V3H2v8h7V8h2v10h4v3h7v-8h-7v3h-2V8h2v3h7v-8zM7 9H4V5h3v4zm13-4h-3V5h3v4zm0 14h-3v-4h3v4z" />
                    </svg>
                    <h3>Existing Cluster</h3>
                    <p>Connect or discover an external Kafka cluster</p>
                  </div>
                  <button className="cd-deployment-btn outline" onClick={() => { setShowDeploymentModal(false); navigate('/external-clusters'); }}>Explorer</button>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
