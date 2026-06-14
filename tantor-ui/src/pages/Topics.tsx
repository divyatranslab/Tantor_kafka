import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { Database, Search, ChevronLeft, ChevronRight, AlertTriangle } from 'lucide-react';
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

  const fetchTopics = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`/api/v1/clusters/${id}/topics?page=${page}&size=${size}&search=${encodeURIComponent(searchQuery)}`);
      if (!res.ok) throw new Error(`HTTP error! status: ${res.status}`);
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

  return (
    <div className="topics-tab animate-fade-in">
      <div className="topics-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
        <h2>Topics Dashboard</h2>
        
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
    </div>
  );
}
