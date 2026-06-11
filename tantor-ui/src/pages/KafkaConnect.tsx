import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { Download, Plus } from 'lucide-react';

export function KafkaConnect() {
  const { id } = useParams<{ id: string }>();
  const [activeTab, setActiveTab] = useState<'clusters' | 'connectors'>('clusters');
  const [clusters, setClusters] = useState<any[]>([]);
  const [connectors, setConnectors] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // In a real implementation, we would fetch from the backend
    // fetch(`/api/v1/ui/clusters/${id}/kafka-connect`)
    setLoading(false);
    // Mock data based on screenshot structure
    setClusters([
      { name: 'connect-149', version: '2.8.0', connectors: 0, runningTasks: 0 }
    ]);
    setConnectors([]);
  }, [id]);

  if (loading) {
    return (
      <div className="flex-row justify-center" style={{ height: '16rem' }}>
        <div className="w-8 h-8 border-4 border-blue-500/30 border-t-blue-500 rounded-full animate-spin" style={{ width: '32px', height: '32px', border: '4px solid var(--border-default)', borderTopColor: 'var(--accent-primary)', borderRadius: '50%' }} />
      </div>
    );
  }

  return (
    <div className="migrated-page">
      <div className="page-header" style={{ marginBottom: 0 }}>
        <div>
          <h1 className="page-title">Kafka Connect</h1>
          <p className="page-subtitle">Manage Kafka Connect clusters and connectors</p>
        </div>
        <div className="header-actions">
          <button className="btn btn-primary">
            <Plus size={16} />
            Create Connector
          </button>
          <button className="btn btn-secondary">
            <Download size={16} />
            Export CSV
          </button>
        </div>
      </div>

      <div className="flex-row gap-4 mb-6" style={{ borderBottom: '1px solid var(--border-default)' }}>
        <button
          className={`btn ${activeTab === 'clusters' ? 'btn-primary' : 'btn-secondary'}`}
          style={{
            borderBottomLeftRadius: 0,
            borderBottomRightRadius: 0,
            borderBottom: activeTab === 'clusters' ? '2px solid var(--accent-primary)' : '2px solid transparent',
            backgroundColor: 'transparent',
            color: activeTab === 'clusters' ? 'var(--accent-primary)' : 'var(--text-secondary)',
            padding: '0.75rem 1.5rem'
          }}
          onClick={() => setActiveTab('clusters')}
        >
          Clusters
        </button>
        <button
          className={`btn ${activeTab === 'connectors' ? 'btn-primary' : 'btn-secondary'}`}
          style={{
            borderBottomLeftRadius: 0,
            borderBottomRightRadius: 0,
            borderBottom: activeTab === 'connectors' ? '2px solid var(--accent-primary)' : '2px solid transparent',
            backgroundColor: 'transparent',
            color: activeTab === 'connectors' ? 'var(--accent-primary)' : 'var(--text-secondary)',
            padding: '0.75rem 1.5rem'
          }}
          onClick={() => setActiveTab('connectors')}
        >
          Connectors
        </button>
      </div>

      <div className="flex-row gap-4 mb-6">
        <div className="stat-card flex-1">
          <span className="stat-label">Clusters</span>
          <span className="stat-value">{clusters.length}</span>
        </div>
        <div className="stat-card flex-1">
          <span className="stat-label">Connectors</span>
          <span className="stat-value">{connectors.length}</span>
        </div>
        <div className="stat-card flex-1">
          <span className="stat-label">Tasks</span>
          <span className="stat-value">0</span>
        </div>
      </div>

      <div className="table-container">
        {activeTab === 'clusters' ? (
          <table className="migrated-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Version</th>
                <th>Connectors</th>
                <th>Running tasks</th>
              </tr>
            </thead>
            <tbody>
              {clusters.length === 0 ? (
                <tr>
                  <td colSpan={4} className="empty-state" style={{ backgroundColor: 'transparent' }}>No rows found</td>
                </tr>
              ) : (
                clusters.map((c, i) => (
                  <tr key={i}>
                    <td style={{ fontWeight: 500 }}>{c.name}</td>
                    <td>{c.version}</td>
                    <td>{c.connectors}</td>
                    <td>{c.runningTasks}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        ) : (
          <table className="migrated-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Class</th>
                <th>Type</th>
                <th>State</th>
                <th>Tasks</th>
              </tr>
            </thead>
            <tbody>
              {connectors.length === 0 ? (
                <tr>
                  <td colSpan={5} className="empty-state" style={{ backgroundColor: 'transparent' }}>No rows found</td>
                </tr>
              ) : (
                connectors.map((c, i) => (
                  <tr key={i}>
                    <td colSpan={5}>{c.name}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
