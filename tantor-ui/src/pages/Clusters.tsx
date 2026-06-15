import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { PlusCircle, Network, RefreshCw, Trash2 } from 'lucide-react';
import './Clusters.css';

interface ClusterInfo {
  id:           string;
  name:         string;
  kafkaVersion: string;
  mode:         string;
  environment:  string;
  createdAt:    string;
  status:       string;
  nodeCount:    number;
  bootstrapServers?: string;
}

export function Clusters() {
  const navigate = useNavigate();
  const [clusters, setClusters] = useState<ClusterInfo[]>([]);
  const [loading, setLoading]   = useState(true);

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
    if (!window.confirm(`Remove cluster '${name}' from Tantor?`)) return;
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

  const isClickable = (c: ClusterInfo) =>
    c.status === 'SUCCESS' || c.mode === 'EXTERNAL';

  const statusLabel = (c: ClusterInfo) => {
    if (c.mode === 'EXTERNAL') return 'External';
    if (c.status === 'SUCCESS') return 'Active';
    return c.status;
  };

  const statusClass = (c: ClusterInfo) => {
    if (c.mode === 'EXTERNAL') return 'external';
    return (c.status || 'pending').toLowerCase();
  };

  const inProgress = (status: string) =>
    ['PENDING', 'RUNNING', 'VALIDATING', 'DELETING'].includes(status);

  useEffect(() => { fetchClusters(); }, []);

  return (
    <div className="clusters-page animate-fade-in">

      {/* ── Header ── */}
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
          <button
            className="btn btn-primary-action"
            onClick={() => navigate('/clusters/new')}
          >
            <PlusCircle size={13} />
            Add cluster
          </button>
        </div>
      </header>

      {/* ── Loading ── */}
      {loading ? (
        <div className="state-center">
          <RefreshCw size={24} className="spin" style={{ color: '#378ADD' }} />
          <p>Loading clusters…</p>
        </div>

      /* ── Empty ── */
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
            onClick={() => navigate('/clusters/new')}
          >
            <PlusCircle size={13} /> Add first cluster
          </button>
        </div>

      /* ── Grid ── */
      ) : (
        <div className="clusters-grid">
          {clusters.map(cluster => (
            <div
              key={cluster.id}
              className={`cluster-card${!isClickable(cluster) ? ' disabled' : ''}`}
              style={{ cursor: isClickable(cluster) ? 'pointer' : 'default' }}
              onClick={() => {
                if (isClickable(cluster))
                  navigate(`/clusters/${cluster.id}/topics`);
              }}
            >
              {/* Card header */}
              <div className="cluster-card-header">
                <div className={`cluster-icon-wrap${cluster.mode === 'EXTERNAL' ? ' external' : ''}`}>
                  <Network size={18} />
                </div>

                <div>
                  <p className="cluster-name">{cluster.name}</p>
                  <span className="cluster-version">Kafka {cluster.kafkaVersion}</span>
                </div>

                <div style={{ marginLeft: 'auto', display: 'flex', gap: 6, alignItems: 'center' }}>
                  <span className={`cluster-status-badge ${statusClass(cluster)}`}>
                    {inProgress(cluster.status) && cluster.mode !== 'EXTERNAL' && (
                      <RefreshCw size={11} className="spin" />
                    )}
                    {statusLabel(cluster)}
                  </span>

                  <button
                    className="btn btn-icon-danger"
                    onClick={e => deleteCluster(e, cluster.id, cluster.name)}
                    title="Remove cluster"
                  >
                    <Trash2 size={15} />
                  </button>
                </div>
              </div>

              {/* Card body */}
              <div className="cluster-card-body">
                <div className="cluster-meta-row">
                  <span className="cluster-meta-label">Mode</span>
                  <span className="cluster-meta-value tag">
                    {cluster.mode?.toUpperCase() || 'KRAFT'}
                  </span>
                </div>

                {cluster.environment && (
                  <div className="cluster-meta-row">
                    <span className="cluster-meta-label">Environment</span>
                    <span className="cluster-meta-value tag">{cluster.environment}</span>
                  </div>
                )}

                {cluster.mode === 'EXTERNAL' ? (
                  <div className="cluster-meta-row">
                    <span className="cluster-meta-label">Bootstrap</span>
                    <span
                      className="cluster-meta-value"
                      style={{
                        fontSize: 11,
                        maxWidth: 140,
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                        fontFamily: 'SF Mono, Fira Code, monospace',
                        color: '#5f5e5a',
                      }}
                      title={cluster.bootstrapServers}
                    >
                      {cluster.bootstrapServers || 'N/A'}
                    </span>
                  </div>
                ) : (
                  <div className="cluster-meta-row">
                    <span className="cluster-meta-label">Nodes</span>
                    <span className="cluster-meta-value">{cluster.nodeCount}</span>
                  </div>
                )}

                <div className="cluster-meta-row">
                  <span className="cluster-meta-label">Created</span>
                  <span className="cluster-meta-value">
                    {cluster.createdAt
                      ? new Date(cluster.createdAt).toLocaleDateString()
                      : 'N/A'}
                  </span>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

    </div>
  );
}