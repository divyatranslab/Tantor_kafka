import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { Database, Search, ChevronLeft, ChevronRight, AlertTriangle, ArrowUp, ArrowDown } from 'lucide-react';

interface PartitionSummaryDto {
  topicName: string;
  partitionId: number;
  leaderBroker: number;
  leaderHostname: string;
  replicaBrokers: number[];
  isrBrokers: number[];
  earliestOffset: number;
  latestOffset: number;
  messageCount: number;
  underReplicated: boolean;
  health: string;
}

interface PaginatedResponse {
  content: PartitionSummaryDto[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}

export function Partitions() {
  const { id } = useParams<{ id: string }>();
  const [data, setData] = useState<PaginatedResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [searchInput, setSearchInput] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [sortBy, setSortBy] = useState('topicName');

  const fetchPartitions = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`/api/v1/clusters/${id}/partitions?page=${page}&size=${size}&search=${encodeURIComponent(searchQuery)}&sortBy=${sortBy}`);
      if (!res.ok) {
        const errorData = await res.json().catch(() => ({}));
        throw new Error(errorData.message || `Partitions are not available yet (HTTP ${res.status})`);
      }
      const json = await res.json();
      setData(json);
    } catch (e: any) {
      console.error(e);
      setError(e.message || "Failed to load partitions");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchPartitions();
  }, [id, page, size, searchQuery, sortBy]);

  // Handle Search submit
  const handleSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setPage(0); // Reset to first page on new search
    setSearchQuery(searchInput);
  };

  const handleSort = (field: string) => {
    setSortBy(field);
    setPage(0);
  };

  const renderSortIcon = (field: string) => {
    if (sortBy === field) {
        if (field === 'messageCount') return <ArrowDown size={14} style={{display: 'inline', marginLeft: 4}} />;
        return <ArrowUp size={14} style={{display: 'inline', marginLeft: 4}} />;
    }
    return null;
  };

  return (
    <div className="partitions-tab animate-fade-in">
      <div className="topics-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
        <h2>Partitions Dashboard</h2>
        
        <form onSubmit={handleSearchSubmit} style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
          <div style={{ position: 'relative' }}>
            <Search size={16} style={{ position: 'absolute', left: '10px', top: '10px', color: 'var(--text-secondary)' }} />
            <input 
              type="text" 
              placeholder="Search topic name..." 
              value={searchInput}
              onChange={(e) => setSearchInput(e.target.value)}
              style={{ padding: '0.5rem 0.5rem 0.5rem 2rem', borderRadius: '6px', border: '1px solid var(--border-color)', width: '250px', backgroundColor: 'var(--bg-surface)', color: 'var(--text-primary)' }}
            />
          </div>
          <button type="submit" className="btn btn-secondary">Search</button>
        </form>
      </div>

      {error && (
        <div className="alert-box" style={{ backgroundColor: '#fef2f2', color: '#b91c1c', padding: '1rem', borderRadius: '8px', marginBottom: '1rem', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
          <AlertTriangle size={20} />
          <span>Error loading partitions: {error}</span>
        </div>
      )}

      <div className="table-card" style={{ backgroundColor: 'var(--bg-surface)', borderRadius: '12px', border: '1px solid var(--border-color)', overflow: 'hidden' }}>
        {loading && !data ? (
          <div className="empty-state" style={{ padding: '4rem 2rem', textAlign: 'center' }}>Loading partitions from backend...</div>
        ) : !data || data.content.length === 0 ? (
          <div className="empty-state" style={{ padding: '4rem 2rem', textAlign: 'center' }}>
            <Database size={48} style={{ color: 'var(--text-secondary)', marginBottom: '1rem', margin: '0 auto' }} />
            <p style={{ fontWeight: 600, fontSize: '1.1rem' }}>No partitions found</p>
            <p style={{ color: 'var(--text-secondary)' }}>Try adjusting your search criteria.</p>
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table className="data-table" style={{ width: '100%', borderCollapse: 'collapse', minWidth: '1000px' }}>
              <thead>
                <tr style={{ borderBottom: '1px solid var(--border-color)', textAlign: 'left', backgroundColor: 'var(--bg-surface-hover)' }}>
                  <th style={{ padding: '1rem', cursor: 'pointer' }} onClick={() => handleSort('topicName')}>Topic Name {renderSortIcon('topicName')}</th>
                  <th style={{ padding: '1rem', cursor: 'pointer' }} onClick={() => handleSort('partitionId')}>Partition {renderSortIcon('partitionId')}</th>
                  <th style={{ padding: '1rem', cursor: 'pointer' }} onClick={() => handleSort('leaderBroker')}>Leader Broker {renderSortIcon('leaderBroker')}</th>
                  <th style={{ padding: '1rem' }}>Replicas</th>
                  <th style={{ padding: '1rem' }}>ISR</th>
                  <th style={{ padding: '1rem', cursor: 'pointer' }} onClick={() => handleSort('messageCount')}>Offsets (E / L) {renderSortIcon('messageCount')}</th>
                  <th style={{ padding: '1rem', cursor: 'pointer' }} onClick={() => handleSort('messageCount')}>Message Count {renderSortIcon('messageCount')}</th>
                  <th style={{ padding: '1rem', cursor: 'pointer' }} onClick={() => handleSort('health')}>Health Status {renderSortIcon('health')}</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((p, idx) => (
                  <tr key={`${p.topicName}-${p.partitionId}-${idx}`} style={{ borderBottom: '1px solid var(--border-color)', transition: 'background-color 0.2s ease' }} className="table-row-hover">
                    <td style={{ padding: '1rem', fontWeight: 500 }}>{p.topicName}</td>
                    <td style={{ padding: '1rem' }}>{p.partitionId}</td>
                    <td style={{ padding: '1rem' }}>
                      <div style={{ display: 'flex', flexDirection: 'column' }}>
                        <span style={{ fontWeight: 500 }}>{p.leaderBroker === -1 ? 'None' : p.leaderBroker}</span>
                        <span style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>{p.leaderHostname}</span>
                      </div>
                    </td>
                    <td style={{ padding: '1rem', fontSize: '0.9rem' }}>[{p.replicaBrokers.join(', ')}]</td>
                    <td style={{ padding: '1rem', fontSize: '0.9rem' }}>[{p.isrBrokers.join(', ')}]</td>
                    <td style={{ padding: '1rem', fontSize: '0.9rem', color: 'var(--text-secondary)' }}>{p.earliestOffset} / {p.latestOffset}</td>
                    <td style={{ padding: '1rem', fontWeight: 600 }}>{p.messageCount.toLocaleString()}</td>
                    <td style={{ padding: '1rem' }}>
                      {p.health === 'CRITICAL' && (
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', backgroundColor: '#fef2f2', color: '#b91c1c', padding: '4px 10px', borderRadius: '12px', fontSize: '0.85rem', fontWeight: 500 }}>
                          <div style={{ width: 8, height: 8, borderRadius: '50%', backgroundColor: '#ef4444' }}></div> 
                          Offline Leader
                        </span>
                      )}
                      {p.health === 'WARNING' && (
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', backgroundColor: '#fffbeb', color: '#b45309', padding: '4px 10px', borderRadius: '12px', fontSize: '0.85rem', fontWeight: 500 }}>
                          <div style={{ width: 8, height: 8, borderRadius: '50%', backgroundColor: '#f59e0b' }}></div> 
                          Under Replicated
                        </span>
                      )}
                      {p.health === 'HEALTHY' && (
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
          </div>
        )}

        {data && data.content.length > 0 && (
          <div style={{ padding: '1rem', borderTop: '1px solid var(--border-color)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <div style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>
              Showing page {data.page + 1} of {data.totalPages === 0 ? 1 : data.totalPages} (Total {data.totalElements} partitions)
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
              <select 
                value={size} 
                onChange={(e) => { setSize(Number(e.target.value)); setPage(0); }}
                style={{ padding: '0.4rem', borderRadius: '6px', border: '1px solid var(--border-color)', backgroundColor: 'var(--bg-surface)', color: 'var(--text-primary)' }}
              >
                <option value={10}>10 per page</option>
                <option value={50}>50 per page</option>
                <option value={100}>100 per page</option>
                <option value={500}>500 per page</option>
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
        )}
      </div>
      <style>{`
        .table-row-hover:hover {
          background-color: var(--bg-surface-hover);
        }
      `}</style>
    </div>
  );
}
