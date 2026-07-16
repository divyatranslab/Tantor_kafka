import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { MoreVertical, Network, Database, RefreshCw, Trash2, Server, HardDrive, ExternalLink, RotateCcw, ServerCog, Settings, Plus } from 'lucide-react';
import { usePermissions } from '../hooks/usePermissions';
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
  runtimeHealth?: string;
  runtimeStatusLabel?: string;
  runtimeStatusReason?: string;
}

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
      if (res.ok) setClusters(await res.json());
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  const deleteCluster = async (e: React.MouseEvent, id: string, name: string) => {
    e.stopPropagation();
    if (!canManage) return;
    if (!window.confirm(`Delete cluster '${name}' and clean it from assigned VM(s)?`)) return;
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
        alert('Failed to delete cluster.');
      }
    } catch {
      alert('An error occurred while deleting.');
    }
  };

  const triggerRollingRestart = async (cluster: ClusterInfo) => {
    if (!canManage) return;
    const nodeCount = cluster.nodeCount || cluster.hosts?.length || 0;
    const warning = nodeCount === 1
      ? `WARNING: '${cluster.name}' has only one node. Three nodes are recommended for availability, and this restart will interrupt Kafka service. Do you want to continue?`
      : `Start rolling restart for '${cluster.name}'?`;
    if (!window.confirm(warning)) return;
    try {
      const res = await fetch(`/api/v1/clusters/${cluster.id}/actions/rolling-restart`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ confirmSingleNode: nodeCount === 1 }),
      });
      const data = await res.json().catch(() => ({}));
      if (res.ok) {
        navigate(data.jobId ? `/jobs/${data.jobId}` : `/clusters/${cluster.id}/actions`);
      } else {
        alert(data.error || 'Failed to schedule rolling restart.');
      }
    } catch {
      alert('Network error while scheduling rolling restart.');
    }
  };

  const isClickable = (c: ClusterInfo) =>
    c.status === 'SUCCESS' || c.mode === 'EXTERNAL';

  const statusLabel = (c: ClusterInfo) => {
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

  const sourceLabel = (cluster: ClusterInfo) =>
    cluster.sourceLabel || (cluster.mode === 'EXTERNAL' ? 'External' : 'Internal managed');

  useEffect(() => { fetchClusters(); }, []);

  const renderHeader = () => (
    <header className="page-header flex-between">
      <div>
        <h1>Cluster</h1>
        <p>Deploy and manage your Tantor Kafka environments</p>
      </div>
      <div className="header-actions">
        <button className="btn icon-only outline refresh-btn" onClick={fetchClusters} title="Refresh">
          <RefreshCw size={20} className={loading ? 'spin' : ''} />
        </button>
        {canManage && (
          <button className="btn btn-primary-action add-cluster-btn" onClick={() => setShowDeploymentModal(true)}>
            <Plus size={20} /> Add Cluster
          </button>
        )}
      </div>
    </header>
  );

  return (
    <div className={`clusters-page animate-fade-in ${!loading && clusters.length === 0 ? 'is-empty' : ''}`} onClick={() => setOpenMenuId(null)}>
      {(!loading && clusters.length === 0) ? (
        <div className="white-container full-empty-card">
          {renderHeader()}
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
          {renderHeader()}
          {loading ? (
            <div className="state-center">
              <RefreshCw size={24} className="spin" style={{ color: '#378ADD' }} />
              <p>Loading clusters...</p>
            </div>
          ) : (
        <section className="clusters-inventory">
          <div className="clusters-inventory-header">
            <div>
              <span className="section-eyebrow">Cluster inventory</span>
              <h2>Kafka clusters</h2>
            </div>
            <span className="inventory-count">{clusters.length} total</span>
          </div>

          <div className="clusters-table-wrap">
            <table className="clusters-table">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Kafka Cluster ID</th>
                  <th>Host / IP</th>
                  <th>Environment</th>
                  <th>Disk</th>
                  <th>Last heartbeat</th>
                  <th>Source / Access</th>
                  <th>Action</th>
                </tr>
              </thead>
              <tbody>
                {clusters.map(cluster => {
                  const host = primaryHost(cluster);
                  const progress = diskPct(host);
                  return (
                    <tr
                      key={cluster.id}
                      className={!isClickable(cluster) ? 'disabled' : ''}
                      onClick={() => {
                        if (isClickable(cluster)) navigate(`/clusters/${cluster.id}/overview`);
                      }}
                    >
                      <td>
                        <div className="cluster-title-cell">
                          <div className={`cluster-icon-wrap${cluster.mode === 'EXTERNAL' ? ' external' : ''}`}>
                            {cluster.mode === 'EXTERNAL' ? <ExternalLink size={16} /> : <Network size={17} />}
                          </div>
                          <div className="cluster-title-text">
                            <strong>{cluster.name}</strong>
                            <span>Kafka {cluster.kafkaVersion || 'Unknown'} - {cluster.mode || 'kraft'}</span>
                          </div>
                        </div>
                      </td>

                      <td>
                        <span className="mono-muted kafka-id-value" title={cluster.kafkaClusterId || ''}>
                          {displayKafkaClusterId(cluster.kafkaClusterId)}
                        </span>
                      </td>
                      <td>
                        <div className="host-cell">
                          <Server size={14} />
                          <div>
                            <strong>{host?.hostname || '-'}</strong>
                            <span>{host?.ipAddress || cluster.bootstrapServers || '-'}</span>
                          </div>
                        </div>
                      </td>
                      <td>
                        <div className="env-cell">
                          <span className="cluster-meta-value tag">{cluster.environment || 'unknown'}</span>
                          <small>{cluster.nodeCount || cluster.hosts?.length || 0} node{(cluster.nodeCount || cluster.hosts?.length || 0) === 1 ? '' : 's'}</small>
                        </div>
                      </td>
                      <td>
                        {cluster.mode === 'EXTERNAL' ? (
                          <div className="env-cell">
                            <div>
                              <span>Management: {managementLabel(cluster)}</span>
                            </div>
                            <small>{cluster.managedHostsCount || 0} / {cluster.totalHostsCount || cluster.nodeCount || 0} hosts</small>
                          </div>
                        ) : (
                          <div className="disk-cell">
                            <div>
                              <HardDrive size={13} />
                              <span>{diskLabel(host)}</span>
                            </div>
                            {progress > 0 && <span className="disk-meter"><i style={{ width: `${progress}%` }} /></span>}
                          </div>
                        )}
                      </td>
                      <td>
                        <span className="heartbeat-text">
                          {cluster.mode === 'EXTERNAL' 
                            ? formatHeartbeat(cluster.lastAgentHeartbeat) 
                            : formatHeartbeat(host?.lastHeartbeat)}
                        </span>
                      </td>
                      <td>
                        <div className="source-cell">
                          <span className={`source-pill ${cluster.mode === 'EXTERNAL' ? 'external' : 'internal'}`}>
                            {sourceLabel(cluster)}
                          </span>
                          <span className={`access-pill ${cluster.managementLevel === 'BOOTSTRAP_ONLY' ? 'metadata' : 'managed'}`}>
                            {managementLabel(cluster)}
                          </span>
                          <span 
                            className={`cluster-status-badge ${statusClass(cluster)}`}
                            title={cluster.runtimeStatusReason || (cluster.status === 'DEGRADED' ? 'Kafka is reachable, but Discovery Agent process verification failed.' : undefined)}
                          >
                            {inProgress(cluster.status) && cluster.mode !== 'EXTERNAL' && (
                              <RefreshCw size={11} className="spin" />
                            )}
                            {statusLabel(cluster)}
                          </span>
                        </div>
                      </td>
                      <td>
                        {canManage ? (
                          <div className="row-actions cluster-menu-anchor" onClick={e => e.stopPropagation()}>
                            <button
                              className="btn icon-only"
                              onClick={() => setOpenMenuId(openMenuId === cluster.id ? null : cluster.id)}
                              title="Cluster actions"
                            >
                              <MoreVertical size={16} />
                            </button>
                            {openMenuId === cluster.id && (
                              <div className="cluster-action-menu">
                                <button onClick={() => triggerRollingRestart(cluster)} disabled={!isClickable(cluster)}>
                                  <RotateCcw size={14} />
                                  Rolling restart
                                </button>
                                <button onClick={() => navigate(`/clusters/${cluster.id}/config`)} disabled={!isClickable(cluster)}>
                                  <Settings size={14} />
                                  Configuration change
                                </button>
                                <button onClick={() => navigate(`/cluster-deployment?mode=add&clusterId=${cluster.id}`)} disabled={!isClickable(cluster)}>
                                  <ServerCog size={14} />
                                  Add node
                                </button>
                                <button className="danger" onClick={e => deleteCluster(e, cluster.id, cluster.name)}>
                                  <Trash2 size={14} />
                                  Delete
                                </button>
                              </div>
                            )}
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
        </section>
          )}
        </>
      )}

      {showDeploymentModal && (
        <div className="cd-modal-backdrop" onClick={() => setShowDeploymentModal(false)}>
          <div className="cd-deployment-modal" onClick={e => e.stopPropagation()}>
            <div className="cd-deployment-modal-header">
              <div>
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
                    <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
                      <path d="M17 16l-4-4V8.82C14.16 8.4 15 7.3 15 6c0-1.66-1.34-3-3-3S9 4.34 9 6c0 1.3.84 2.4 2 2.82V12l-4 4H3v5h5v-3.05l4-4.2 4 4.2V21h5v-5h-4zM12 5c.55 0 1 .45 1 1s-.45 1-1 1-1-.45-1-1 .45-1 1-1zm-7 14v-1h1.79l4-4.2 4 4.2H17v1H5z"/>
                    </svg>
                    <h3>Create your Cluster</h3>
                    <p>Build a new KRaft or ZooKeeper cluster on selected Tantor host</p>
                  </div>
                  <button className="cd-deployment-btn outline" onClick={() => navigate('/cluster-deployment')}>Create</button>
                </div>
                
                <div className="cd-deployment-card active">
                  <div className="cd-deployment-card-content">
                    <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
                      <path d="M22 11V3h-7v3H9V3H2v8h7V8h2v10h4v3h7v-8h-7v3h-2V8h2v3h7v-8zM7 9H4V5h3v4zm13-4h-3V5h3v4zm0 14h-3v-4h3v4z"/>
                    </svg>
                    <h3>Existing Cluster</h3>
                    <p>Connect or discover an external Kafka cluster</p>
                  </div>
                  <button className="cd-deployment-btn primary" onClick={() => navigate('/external-clusters')}>Explorer</button>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
