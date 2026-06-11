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
  nodeCount:    number;
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
    e.stopPropagation(); // prevent navigating to cluster topics
    if (!window.confirm(`Are you sure you want to remove the cluster '${name}' from Tantor?`)) return;
    try {
      const res = await fetch(`/api/v1/ui/clusters/${id}`, { method: 'DELETE' });
      if (res.ok) {
        setClusters(prev => prev.filter(c => c.id !== id));
      } else {
        alert('Failed to delete cluster.');
      }
    } catch (err) {
      console.error(err);
      alert('An error occurred while deleting.');
    }
  };

  useEffect(() => { fetchClusters(); }, []);

  return (
    <div className="clusters-page animate-fade-in">

      <header className="page-header flex-between">
        <div>
          <h1>Kafka clusters</h1>
          <p>Deploy and manage your Tantor Kafka environments</p>
        </div>
        <div className="header-actions">
          <button className="btn" onClick={fetchClusters}>
            <RefreshCw size={14} className={loading ? 'spin' : ''} />
            Refresh
          </button>
          <button className="btn btn-primary-action" onClick={() => navigate('/clusters/new')}>
            <PlusCircle size={14} />
            Add Cluster
          </button>
        </div>
      </header>

      {loading ? (
        <div className="state-center">
          <RefreshCw size={28} className="spin" style={{ color: 'var(--accent-primary)' }} />
          <p>Loading clusters…</p>
        </div>
      ) : clusters.length === 0 ? (
        <div className="state-center">
          <Network size={36} style={{ color: 'var(--accent-primary)' }} />
          <h2>No clusters yet</h2>
          <p>
            You haven't added any Kafka clusters yet. Click the button
            below to provision your first cluster or connect an external one.
          </p>
          <button
            className="btn btn-primary-action"
            onClick={() => navigate('/clusters/new')}
          >
            <PlusCircle size={14} /> Add first cluster
          </button>
        </div>
      ) : (
        <div className="clusters-grid">
          {clusters.map(cluster => (
            <div 
              key={cluster.id} 
              className="cluster-card"
              style={{cursor: 'pointer', position: 'relative'}}
              onClick={() => navigate(`/clusters/${cluster.id}/topics`)}
            >

              <div className="cluster-card-header">
                <div className="cluster-icon-wrap" style={{ background: cluster.mode === 'EXTERNAL' ? '#f3f4f6' : undefined, color: cluster.mode === 'EXTERNAL' ? '#4b5563' : undefined }}>
                  <Network size={20} />
                </div>
                <div>
                  <p className="cluster-name">{cluster.name}</p>
                  <span className="cluster-version">Kafka {cluster.kafkaVersion}</span>
                </div>
                <div style={{ marginLeft: 'auto', display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                  <span className={`cluster-status-badge ${cluster.mode === 'EXTERNAL' ? 'external' : ''}`}>
                    {cluster.mode === 'EXTERNAL' ? 'External' : 'Active'}
                  </span>
                  <button 
                    className="btn" 
                    style={{ padding: '0.4rem', color: 'var(--color-danger)', border: 'none', background: 'transparent' }}
                    onClick={(e) => deleteCluster(e, cluster.id, cluster.name)}
                    title="Remove cluster"
                  >
                    <Trash2 size={16} />
                  </button>
                </div>
              </div>

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
                    <span className="cluster-meta-label">Brokers</span>
                    <span className="cluster-meta-value" style={{ fontSize: '0.75rem', maxWidth: '120px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={(cluster as any).bootstrapServers}>
                      {(cluster as any).bootstrapServers || 'N/A'}
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