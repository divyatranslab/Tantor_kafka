import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { Database, Search, ChevronLeft, ChevronRight, AlertTriangle, Plus, X } from 'lucide-react';
import './Topics.css';

interface TopicSummaryDto {
  name: string;
  partitionCount: number;
  replicationFactor: number;
  underReplicated: number;
}

interface PaginatedResponse {
  content: TopicSummaryDto[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}

export function Topics() {
  const { id } = useParams<{ id: string }>();
  const [data, setData] = useState<PaginatedResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [searchInput, setSearchInput] = useState('');
  const [searchQuery, setSearchQuery] = useState('');

  // Create Topic Modal State
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [newTopicName, setNewTopicName] = useState('');
  const [newTopicPartitions, setNewTopicPartitions] = useState(1);
  const [newTopicReplication, setNewTopicReplication] = useState(1);
  const [creatingTopic, setCreatingTopic] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  const fetchTopics = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`/api/v1/clusters/${id}/topics?page=${page}&size=${size}&search=${encodeURIComponent(searchQuery)}`);
      if (!res.ok) {
        const errorData = await res.json().catch(() => ({}));
        throw new Error(errorData.message || `Topics are not available yet (HTTP ${res.status})`);
      }
      const json = await res.json();
      setData(json);
    } catch (e: any) {
      console.error(e);
      setError(e.message || "Failed to load topics");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchTopics();
  }, [id, page, size, searchQuery]);

  // Handle Search submit
  const handleSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setPage(0); // Reset to first page on new search
    setSearchQuery(searchInput);
  };

  const handleCreateTopic = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newTopicName.trim()) return;

    setCreatingTopic(true);
    setCreateError(null);

    try {
      const res = await fetch(`/api/v1/clusters/${id}/topics`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: newTopicName.trim(),
          partitions: newTopicPartitions,
          replicationFactor: newTopicReplication,
          configs: {}
        })
      });

      if (!res.ok) {
        const errData = await res.json().catch(() => null);
        throw new Error(errData?.message || `Failed to create topic (HTTP ${res.status})`);
      }

      // Success
      setShowCreateModal(false);
      setNewTopicName('');
      setNewTopicPartitions(1);
      setNewTopicReplication(1);
      
      // Refresh the list to show the new topic
      fetchTopics();
    } catch (e: any) {
      setCreateError(e.message);
    } finally {
      setCreatingTopic(false);
    }
  };

  return (
    <div className="topics-tab animate-fade-in" style={{ position: 'relative' }}>
      <div className="topics-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
        <h2>Topics Dashboard</h2>
        
        <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
          <form onSubmit={handleSearchSubmit} style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <div style={{ position: 'relative' }}>
              <Search size={16} style={{ position: 'absolute', left: '10px', top: '10px', color: 'var(--text-secondary)' }} />
              <input 
                type="text" 
                placeholder="Search topics..." 
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                style={{ padding: '0.5rem 0.5rem 0.5rem 2rem', borderRadius: '6px', border: '1px solid var(--border-color)', width: '250px' }}
              />
            </div>
            <button type="submit" className="btn btn-secondary">Search</button>
          </form>

          <button 
            className="btn btn-primary-action" 
            onClick={() => setShowCreateModal(true)}
            style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}
          >
            <Plus size={16} /> Create Topic
          </button>
        </div>
      </div>

      {error && (
        <div className="alert-box" style={{ backgroundColor: '#fef2f2', color: '#b91c1c', padding: '1rem', borderRadius: '8px', marginBottom: '1rem', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
          <AlertTriangle size={20} />
          <span>Error loading topics: {error}</span>
        </div>
      )}

      <div className="table-card" style={{ backgroundColor: 'var(--bg-surface)', borderRadius: '12px', border: '1px solid var(--border-color)', overflow: 'hidden' }}>
        {loading && !data ? (
          <div className="empty-state" style={{ padding: '4rem 2rem', textAlign: 'center' }}>Loading topics from backend...</div>
        ) : !data || data.content.length === 0 ? (
          <div className="empty-state" style={{ padding: '4rem 2rem', textAlign: 'center' }}>
            <Database size={48} style={{ color: 'var(--text-secondary)', marginBottom: '1rem' }} />
            <p style={{ fontWeight: 600, fontSize: '1.1rem' }}>No topics found</p>
            <p style={{ color: 'var(--text-secondary)' }}>Try adjusting your search criteria or cluster.</p>
            <button 
              className="btn btn-primary-action" 
              onClick={() => setShowCreateModal(true)}
              style={{ marginTop: '1rem' }}
            >
              Create your first topic
            </button>
          </div>
        ) : (
          <>
            <table className="data-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ borderBottom: '1px solid var(--border-color)', textAlign: 'left', backgroundColor: 'var(--bg-surface-hover)' }}>
                  <th style={{ padding: '1rem' }}>Topic Name</th>
                  <th style={{ padding: '1rem' }}>Partitions</th>
                  <th style={{ padding: '1rem' }}>Replication Factor</th>
                  <th style={{ padding: '1rem' }}>Health Status</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map(t => (
                  <tr key={t.name} style={{ borderBottom: '1px solid var(--border-color)' }}>
                    <td style={{ padding: '1rem', fontWeight: 500 }}>{t.name}</td>
                    <td style={{ padding: '1rem' }}>{t.partitionCount}</td>
                    <td style={{ padding: '1rem' }}>{t.replicationFactor}</td>
                    <td style={{ padding: '1rem' }}>
                      {t.underReplicated > 0 ? (
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', backgroundColor: '#fef2f2', color: '#b91c1c', padding: '4px 10px', borderRadius: '12px', fontSize: '0.85rem', fontWeight: 500 }}>
                          <div style={{ width: 8, height: 8, borderRadius: '50%', backgroundColor: '#ef4444' }}></div> 
                          Under Replicated ({t.underReplicated})
                        </span>
                      ) : (
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', backgroundColor: '#f0fdf4', color: '#15803d', padding: '4px 10px', borderRadius: '12px', fontSize: '0.85rem', fontWeight: 500 }}>
                          <div style={{ width: 8, height: 8, borderRadius: '50%', backgroundColor: '#22c55e' }}></div> 
                          Healthy
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            <div style={{ padding: '1rem', borderTop: '1px solid var(--border-color)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>
                Showing page {data.page + 1} of {data.totalPages === 0 ? 1 : data.totalPages} (Total {data.totalElements} topics)
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
                <select 
                  value={size} 
                  onChange={(e) => { setSize(Number(e.target.value)); setPage(0); }}
                  style={{ padding: '0.4rem', borderRadius: '6px', border: '1px solid var(--border-color)' }}
                >
                  <option value={5}>5 per page</option>
                  <option value={10}>10 per page</option>
                  <option value={50}>50 per page</option>
                  <option value={100}>100 per page</option>
                </select>
                
                <div style={{ display: 'flex', gap: '0.5rem' }}>
                  <button 
                    className="btn btn-secondary" 
                    disabled={data.page === 0} 
                    onClick={() => setPage(p => Math.max(0, p - 1))}
                    style={{ padding: '0.4rem', display: 'flex', alignItems: 'center' }}
                  >
                    <ChevronLeft size={16} />
                  </button>
                  <button 
                    className="btn btn-secondary" 
                    disabled={!data.hasNext} 
                    onClick={() => setPage(p => p + 1)}
                    style={{ padding: '0.4rem', display: 'flex', alignItems: 'center' }}
                  >
                    <ChevronRight size={16} />
                  </button>
                </div>
              </div>
            </div>
          </>
        )}
      </div>

      {/* Create Topic Modal */}
      {showCreateModal && (
        <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000, animation: 'fadeIn 0.2s ease-out' }}>
          <div style={{ backgroundColor: 'white', borderRadius: '12px', width: '450px', maxWidth: '90%', boxShadow: '0 20px 25px -5px rgba(0, 0, 0, 0.1)', overflow: 'hidden' }}>
            <div style={{ padding: '1.5rem', borderBottom: '1px solid var(--border-color)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <h3 style={{ margin: 0, fontSize: '1.25rem', fontWeight: 600 }}>Create New Topic</h3>
              <button onClick={() => setShowCreateModal(false)} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-secondary)' }}>
                <X size={20} />
              </button>
            </div>
            
            <form onSubmit={handleCreateTopic} style={{ padding: '1.5rem' }}>
              {createError && (
                <div style={{ marginBottom: '1rem', padding: '0.75rem', backgroundColor: '#fef2f2', color: '#b91c1c', borderRadius: '6px', fontSize: '0.875rem' }}>
                  {createError}
                </div>
              )}
              
              <div style={{ marginBottom: '1rem' }}>
                <label style={{ display: 'block', fontSize: '0.875rem', fontWeight: 500, marginBottom: '0.5rem' }}>Topic Name</label>
                <input 
                  type="text" 
                  value={newTopicName}
                  onChange={e => setNewTopicName(e.target.value)}
                  placeholder="e.g. user-events"
                  required
                  style={{ width: '100%', padding: '0.75rem', border: '1px solid var(--border-color)', borderRadius: '6px' }}
                />
              </div>
              
              <div style={{ display: 'flex', gap: '1rem', marginBottom: '1.5rem' }}>
                <div style={{ flex: 1 }}>
                  <label style={{ display: 'block', fontSize: '0.875rem', fontWeight: 500, marginBottom: '0.5rem' }}>Partitions</label>
                  <input 
                    type="number" 
                    min="1"
                    value={newTopicPartitions}
                    onChange={e => setNewTopicPartitions(Number(e.target.value))}
                    required
                    style={{ width: '100%', padding: '0.75rem', border: '1px solid var(--border-color)', borderRadius: '6px' }}
                  />
                </div>
                <div style={{ flex: 1 }}>
                  <label style={{ display: 'block', fontSize: '0.875rem', fontWeight: 500, marginBottom: '0.5rem' }}>Replication Factor</label>
                  <input 
                    type="number" 
                    min="1"
                    value={newTopicReplication}
                    onChange={e => setNewTopicReplication(Number(e.target.value))}
                    required
                    style={{ width: '100%', padding: '0.75rem', border: '1px solid var(--border-color)', borderRadius: '6px' }}
                  />
                </div>
              </div>
              
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '1rem' }}>
                <button 
                  type="button" 
                  className="btn btn-secondary" 
                  onClick={() => setShowCreateModal(false)}
                >
                  Cancel
                </button>
                <button 
                  type="submit" 
                  className="btn btn-primary-action" 
                  disabled={creatingTopic || !newTopicName.trim()}
                >
                  {creatingTopic ? 'Creating...' : 'Create Topic'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

    </div>
  );
}
