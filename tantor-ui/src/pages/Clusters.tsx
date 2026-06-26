import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { MoreVertical, Network, RefreshCw, Trash2, Server, HardDrive, ExternalLink, RotateCw, RotateCcw, ServerCog } from 'lucide-react';
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
}

export function Clusters() {
  const navigate = useNavigate();
  const [clusters, setClusters] = useState<ClusterInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [openMenuId, setOpenMenuId] = useState<string | null>(null);

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
    if (!window.confirm(`Start rolling restart for '${cluster.name}'?`)) return;
    try {
      const res = await fetch(`/api/v1/clusters/${cluster.id}/actions/rolling-restart`, { method: 'POST' });
      if (res.ok) {
        alert('Rolling restart scheduled. Watch cluster actions/logs for progress.');
      } else {
        alert('Failed to schedule rolling restart.');
      }
    } catch {
      alert('Network error while scheduling rolling restart.');
    }
  };

  const triggerNormalRestart = async (cluster: ClusterInfo) => {
    if (!window.confirm(`Restart all Kafka services for '${cluster.name}' at once?`)) return;
    try {
      const res = await fetch(`/api/v1/clusters/${cluster.id}/actions/normal-restart`, { method: 'POST' });
      const data = await res.json().catch(() => ({}));
      if (res.ok) {
        alert(`Normal restart scheduled for ${data.tasks || 0} service(s).`);
      } else {
        alert(data.error || 'Failed to schedule normal restart.');
      }
    } catch {
      alert('Network error while scheduling normal restart.');
    }
  };

  const isClickable = (c: ClusterInfo) =>
    c.status === 'SUCCESS' || c.mode === 'EXTERNAL';

  const statusLabel = (c: ClusterInfo) => {
    if (c.mode === 'EXTERNAL') return c.status === 'SUCCESS' ? 'Connected' : c.status;
    if (c.status === 'SUCCESS') return 'Active';
    return c.status;
  };

  const statusClass = (c: ClusterInfo) => {
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

  return (
    <div className="clusters-page animate-fade-in" onClick={() => setOpenMenuId(null)}>
      <header className="page-header flex-between">
        <div>
          <h1>Kafka clusters</h1>
          <p>Deploy and manage your Tantor Kafka environments</p>
        </div>
        <div className="header-actions">
          <button className="btn" onClick={fetchClusters}>
            <RefreshCw size={13} className={loading ? 'spin' : ''} />
            Refresh
          </button>
        </div>
      </header>

      {loading ? (
        <div className="state-center">
          <RefreshCw size={24} className="spin" style={{ color: '#378ADD' }} />
          <p>Loading clusters...</p>
        </div>
      ) : clusters.length === 0 ? (
        <div className="state-center">
          <Network size={32} style={{ color: '#c0beb8' }} />
          <h2>No clusters yet</h2>
          <p>
            You haven't added any Kafka clusters. Click below to provision
            your first cluster or connect an external one.
          </p>
          <button
            className="btn btn-primary-action"
            onClick={() => navigate('/cluster-deployment')}
          >
            <Network size={13} /> Cluster Deployment
          </button>
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
                        if (isClickable(cluster)) navigate(`/clusters/${cluster.id}/topics`);
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
                        <div className="disk-cell">
                          <div>
                            <HardDrive size={13} />
                            <span>{diskLabel(host)}</span>
                          </div>
                          {progress > 0 && <span className="disk-meter"><i style={{ width: `${progress}%` }} /></span>}
                        </div>
                      </td>
                      <td>
                        <span className="heartbeat-text">{formatHeartbeat(host?.lastHeartbeat)}</span>
                      </td>
                      <td>
                        <div className="source-cell">
                          <span className={`source-pill ${cluster.mode === 'EXTERNAL' ? 'external' : 'internal'}`}>
                            {sourceLabel(cluster)}
                          </span>
                          <span className={`access-pill ${cluster.managementLevel === 'BOOTSTRAP_ONLY' ? 'metadata' : 'managed'}`}>
                            {managementLabel(cluster)}
                          </span>
                          <span className={`cluster-status-badge ${statusClass(cluster)}`}>
                            {inProgress(cluster.status) && cluster.mode !== 'EXTERNAL' && (
                              <RefreshCw size={11} className="spin" />
                            )}
                            {statusLabel(cluster)}
                          </span>
                        </div>
                      </td>
                      <td>
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
                              <button onClick={() => navigate(`/clusters/${cluster.id}/topics`)} disabled={!isClickable(cluster)}>
                                <ExternalLink size={14} />
                                Open
                              </button>
                              <button onClick={() => triggerRollingRestart(cluster)} disabled={!isClickable(cluster) || cluster.mode === 'EXTERNAL'}>
                                <RotateCcw size={14} />
                                Rolling restart
                              </button>
                              <button onClick={() => triggerNormalRestart(cluster)} disabled={!isClickable(cluster) || cluster.mode === 'EXTERNAL'}>
                                <RotateCw size={14} />
                                Normal restart
                              </button>
                              <button onClick={() => navigate(`/cluster-deployment?mode=add&clusterId=${cluster.id}`)} disabled={!isClickable(cluster) || cluster.mode === 'EXTERNAL'}>
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
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  );
}
