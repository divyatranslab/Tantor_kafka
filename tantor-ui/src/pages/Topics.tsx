import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { Database, Plus, Trash2, Settings2 } from 'lucide-react';
import './Topics.css';

interface TopicInfo {
  name: string;
  partitionCount: number;
  replicationFactor: number;
  underReplicated: number;
}

export function Topics() {
  const { id } = useParams<{ id: string }>();
  const [topics, setTopics] = useState<TopicInfo[]>([]);
  const [loading, setLoading] = useState(true);

  const fetchTopics = async () => {
    setLoading(true);
    try {
      const res = await fetch(`/api/v1/clusters/${id}/topics`);
      if (res.ok) setTopics(await res.json());
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchTopics();
  }, [id]);

  const handleDelete = async (name: string) => {
    if (!window.confirm(`Are you sure you want to delete topic ${name}?`)) return;
    try {
      await fetch(`/api/v1/clusters/${id}/topics/${name}`, { method: 'DELETE' });
      fetchTopics();
    } catch (e) {
      alert("Failed to delete topic");
    }
  };

  return (
    <div className="topics-tab animate-fade-in">
      <div className="topics-header">
        <h2>Topics</h2>
        <button className="btn btn-primary-action" onClick={() => alert("Create Topic Modal not implemented yet")}>
          <Plus size={16} /> Create Topic
        </button>
      </div>

      <div className="table-card">
        {loading ? (
          <div className="empty-state">Loading topics...</div>
        ) : topics.length === 0 ? (
          <div className="empty-state">
            <Database size={32} style={{ color: 'var(--text-secondary)' }} />
            <p className="empty-title">No topics found</p>
            <p className="empty-subtitle">This cluster does not have any topics yet.</p>
          </div>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Topic Name</th>
                <th>Partitions</th>
                <th>Replication Factor</th>
                <th>Status</th>
                <th style={{ textAlign: 'right' }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {topics.map(t => (
                <tr key={t.name}>
                  <td style={{ fontWeight: 500 }}>{t.name}</td>
                  <td>{t.partitionCount}</td>
                  <td>{t.replicationFactor}</td>
                  <td>
                    {t.underReplicated > 0 ? (
                      <span className="status-badge" style={{ backgroundColor: '#fef2f2', color: '#b91c1c', borderColor: '#fecaca' }}>
                        <div className="status-dot" style={{ backgroundColor: '#ef4444' }}></div> Under Replicated ({t.underReplicated})
                      </span>
                    ) : (
                      <span className="status-badge">
                        <div className="status-dot"></div> Healthy
                      </span>
                    )}
                  </td>
                  <td style={{ textAlign: 'right' }}>
                    <button className="btn" style={{ padding: '0.25rem' }}>
                      <Settings2 size={16} style={{ color: 'var(--text-secondary)' }} />
                    </button>
                    <button className="btn" style={{ padding: '0.25rem', marginLeft: '0.5rem' }} onClick={() => handleDelete(t.name)}>
                      <Trash2 size={16} style={{ color: 'var(--text-secondary)' }} />
                    </button>
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
