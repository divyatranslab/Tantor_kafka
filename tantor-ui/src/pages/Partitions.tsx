import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { Database, Search, ChevronLeft, ChevronRight, ArrowUp, ArrowDown, RefreshCw } from 'lucide-react';

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
      if (field === 'messageCount') return <ArrowDown size={14} style={{ display: 'inline', marginLeft: 4 }} />;
      return <ArrowUp size={14} style={{ display: 'inline', marginLeft: 4 }} />;
    }
    return null;
  };

  return (
    <div className="partitions-tab animate-fade-in" style={{ width: '100%' }}>
      <div className="topics-header" style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', marginBottom: '24px', gap: '16px' }}>
        <h2 className="cluster-section-heading">Partitions Dashboard</h2>
        
        {/* -- Figma Toolbar Search Bar & Refresh Button -- */}
        <div className="tab-toolbar" style={{ display: 'flex', gap: '24px', alignItems: 'center', width: '100%', height: '40px' }}>
          <form onSubmit={handleSearchSubmit} style={{ margin: 0 }}>
            <label style={{
              boxSizing: 'border-box',
              display: 'flex',
              flexDirection: 'row',
              alignItems: 'center',
              padding: '6px 8px',
              gap: '8px',
              width: '612px',
              height: '36px',
              background: '#FFFFFF',
              border: '1px solid #CCCCCC',
              borderRadius: '8px'
            }}>
              <Search size={16} style={{ color: '#818181' }} />
              <input 
                type="text" 
                placeholder="Search key or value" 
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                style={{
                  border: 'none',
                  outline: 'none',
                  fontFamily: 'Satoshi, sans-serif',
                  fontWeight: 400,
                  fontSize: '14px',
                  color: '#23252D',
                  width: '100%',
                  background: 'transparent'
                }}
              />
            </label>
          </form>
          <button 
            type="button"
            onClick={fetchPartitions} 
            disabled={loading} 
            style={{
              boxSizing: 'border-box',
              display: 'flex',
              flexDirection: 'row',
              alignItems: 'center',
              justifyContent: 'center',
              padding: '8px',
              width: '40px',
              height: '40px',
              background: '#FFFFFF',
              border: '1px solid #CCCCCC',
              borderRadius: '8px',
              cursor: 'pointer',
              color: '#818181'
            }}
            aria-label="Refresh partitions"
          >
            <RefreshCw className={loading ? 'spin' : ''} size={15} />
          </button>
        </div>
      </div>

      {error && (
        <div className="error-alert" style={{ marginBottom: '1rem' }}>
          {error}
        </div>
      )}

      <div className="table-card" style={{ backgroundColor: '#FFFFFF', borderRadius: '12px', border: '1px solid #CCCCCC', overflow: 'hidden', width: '100%' }}>
        {loading && !data ? (
          <div className="empty-state" style={{ padding: '4rem 2rem', textAlign: 'center' }}>Loading partitions from backend...</div>
        ) : !data || data.content.length === 0 ? (
          <div className="empty-state" style={{ padding: '4rem 2rem', textAlign: 'center' }}>
            <Database size={32} style={{ color: 'var(--text-secondary)', marginBottom: '1rem' }} />
            <p style={{ fontWeight: 600, marginBottom: '0.5rem' }}>No partitions found</p>
            <p style={{ color: 'var(--text-secondary)' }}>Try adjusting your search criteria.</p>
          </div>
        ) : (
          <div style={{ overflowX: 'auto', width: '100%' }}>
            <div className="figma-table" style={{ display: 'flex', flexDirection: 'column', width: '100%' }}>
              {/* Header Row */}
              <div className="figma-table-header" style={{ display: 'flex', flexDirection: 'row', alignItems: 'center', width: '100%', height: '54px', background: '#F9F9F9', borderBottom: '1px solid #CCCCCC', boxSizing: 'border-box' }}>
                <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '16px', gap: '4px', flex: 1, height: '54px', color: '#332849', fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '16px', cursor: 'pointer' }} onClick={() => handleSort('topicName')}>
                  <span>Topic Name</span>{renderSortIcon('topicName')}
                </div>
                <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '16px', gap: '4px', width: '80px', flex: 'none', height: '54px', color: '#332849', fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '16px', cursor: 'pointer', marginLeft: '-60px' }} onClick={() => handleSort('partitionId')}>
                  <span>Partition</span>{renderSortIcon('partitionId')}
                </div>
                <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '16px', gap: '4px', width: '201px', flex: 'none', height: '54px', color: '#332849', fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '16px', cursor: 'pointer' }} onClick={() => handleSort('leaderBroker')}>
                  <span>Leader Broker</span>{renderSortIcon('leaderBroker')}
                </div>
                <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '16px', gap: '4px', width: '80px', flex: 'none', height: '54px', color: '#332849', fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '16px' }}>
                  Replicas
                </div>
                <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '16px', gap: '4px', width: '80px', flex: 'none', height: '54px', color: '#332849', fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '16px' }}>
                  ISR
                </div>
                <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '16px', gap: '4px', width: '124px', flex: 'none', height: '54px', color: '#332849', fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '16px', cursor: 'pointer' }} onClick={() => handleSort('messageCount')}>
                  <span>Offsets ( / L)</span>{renderSortIcon('messageCount')}
                </div>
                <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '16px', gap: '4px', width: '135px', flex: 'none', height: '54px', color: '#332849', fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '16px', cursor: 'pointer' }} onClick={() => handleSort('messageCount')}>
                  <span>Message Count</span>{renderSortIcon('messageCount')}
                </div>
                <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '16px', gap: '4px', width: '124px', flex: 'none', height: '54px', color: '#332849', fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '16px', cursor: 'pointer' }} onClick={() => handleSort('health')}>
                  <span>Health Status</span>{renderSortIcon('health')}
                </div>
              </div>

              {/* Table Body */}
              <div className="figma-table-body" style={{ display: 'flex', flexDirection: 'column' }}>
                {data.content.map((p, idx) => (
                  <div key={`${p.topicName}-${p.partitionId}-${idx}`} className="figma-table-row table-row-hover" style={{ display: 'flex', flexDirection: 'row', alignItems: 'center', width: '100%', height: '52px', background: '#FFFFFF', borderBottom: '1px solid #CCCCCC', boxSizing: 'border-box' }}>
                    <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '14px 16px', gap: '4px', flex: 1, height: '52px', color: '#23252D', fontFamily: 'Satoshi, sans-serif', fontWeight: 400, fontSize: '14px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {p.topicName}
                    </div>
                    <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '14px 16px', gap: '4px', width: '80px', flex: 'none', height: '52px', color: '#23252D', fontFamily: 'Satoshi, sans-serif', fontWeight: 400, fontSize: '14px', marginLeft: '-60px' }}>
                      {p.partitionId}
                    </div>
                    <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '14px 16px', gap: '4px', width: '201px', flex: 'none', height: '52px', color: '#23252D', fontFamily: 'Satoshi, sans-serif', fontWeight: 400, fontSize: '14px' }}>
                      <div style={{ display: 'flex', flexDirection: 'row', alignItems: 'center', gap: '4px' }}>
                        <span style={{ fontWeight: 500 }}>{p.leaderBroker === -1 ? 'None' : p.leaderBroker}</span>
                        <span style={{ fontSize: '12px', color: '#818181' }}>({p.leaderHostname})</span>
                      </div>
                    </div>
                    <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '14px 16px', gap: '4px', width: '80px', flex: 'none', height: '52px', color: '#23252D', fontFamily: 'Satoshi, sans-serif', fontWeight: 400, fontSize: '14px' }}>
                      [{p.replicaBrokers.join(', ')}]
                    </div>
                    <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '14px 16px', gap: '4px', width: '80px', flex: 'none', height: '52px', color: '#23252D', fontFamily: 'Satoshi, sans-serif', fontWeight: 400, fontSize: '14px' }}>
                      [{p.isrBrokers.join(', ')}]
                    </div>
                    <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '14px 16px', gap: '4px', width: '124px', flex: 'none', height: '52px', color: '#23252D', fontFamily: 'Satoshi, sans-serif', fontWeight: 400, fontSize: '14px' }}>
                      {p.earliestOffset} / {p.latestOffset}
                    </div>
                    <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '14px 16px', gap: '4px', width: '135px', flex: 'none', height: '52px', color: '#23252D', fontFamily: 'Satoshi, sans-serif', fontWeight: 400, fontSize: '14px' }}>
                      {p.messageCount.toLocaleString()}
                    </div>
                    <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '14px 16px', gap: '4px', width: '124px', flex: 'none', height: '52px', color: '#23252D', fontFamily: 'Satoshi, sans-serif', fontWeight: 400, fontSize: '14px' }}>
                      {p.health === 'CRITICAL' && (
                        <span style={{ boxSizing: 'border-box', display: 'inline-flex', justifyContent: 'center', alignItems: 'center', padding: '4px 8px', gap: '10px', width: '63px', height: '27px', backgroundColor: 'rgba(239, 77, 95, 0.25)', borderRadius: '100px', color: '#EF4D5F', fontFamily: 'Satoshi, sans-serif', fontWeight: 400, fontSize: '14px', lineHeight: '19px', textAlign: 'center' }}>
                          Offline
                        </span>
                      )}
                      {p.health === 'WARNING' && (
                        <span style={{ boxSizing: 'border-box', display: 'inline-flex', justifyContent: 'center', alignItems: 'center', padding: '4px 8px', gap: '10px', width: '63px', height: '27px', backgroundColor: 'rgba(224, 142, 64, 0.25)', borderRadius: '100px', color: '#E08E40', fontFamily: 'Satoshi, sans-serif', fontWeight: 400, fontSize: '14px', lineHeight: '19px', textAlign: 'center' }}>
                          Warning
                        </span>
                      )}
                      {(p.health === 'HEALTHY' || !p.health) && (
                        <span style={{ boxSizing: 'border-box', display: 'inline-flex', justifyContent: 'center', alignItems: 'center', padding: '4px 8px', gap: '10px', width: '63px', height: '27px', backgroundColor: 'rgba(42, 199, 146, 0.25)', borderRadius: '100px', color: '#1F845A', fontFamily: 'Satoshi, sans-serif', fontWeight: 400, fontSize: '14px', lineHeight: '19px', textAlign: 'center' }}>
                          Healthy
                        </span>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}

        {/* Pagination Controls */}
        {data && data.totalPages > 1 && (
          <div className="pagination" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '1rem', borderTop: '1px solid #CCCCCC' }}>
            <div style={{ fontSize: '0.875rem', color: '#818181' }}>
              Showing page {data.page + 1} of {data.totalPages === 0 ? 1 : data.totalPages} (Total {data.totalElements} partitions)
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
              <select 
                value={size} 
                onChange={(e) => { setSize(Number(e.target.value)); setPage(0); }}
                style={{ padding: '0.4rem', borderRadius: '6px', border: '1px solid #CCCCCC', backgroundColor: '#FFFFFF', color: '#23252D' }}
              >
                <option value={10}>10 per page</option>
                <option value={25}>25 per page</option>
                <option value={50}>50 per page</option>
                <option value={100}>100 per page</option>
                <option value={500}>500 per page</option>
              </select>
              
              <div style={{ display: 'flex', gap: '0.5rem' }}>
                <button 
                  className="pagination-btn" 
                  disabled={data.page === 0} 
                  onClick={() => setPage(p => Math.max(0, p - 1))}
                  style={{ padding: '0.4rem', display: 'flex', alignItems: 'center', background: '#FFFFFF', border: '1px solid #CCCCCC', borderRadius: '6px', cursor: data.page === 0 ? 'not-allowed' : 'pointer', opacity: data.page === 0 ? 0.5 : 1 }}
                >
                  <ChevronLeft size={16} />
                </button>
                <button 
                  className="pagination-btn" 
                  disabled={!data.hasNext} 
                  onClick={() => setPage(p => p + 1)}
                  style={{ padding: '0.4rem', display: 'flex', alignItems: 'center', background: '#FFFFFF', border: '1px solid #CCCCCC', borderRadius: '6px', cursor: !data.hasNext ? 'not-allowed' : 'pointer', opacity: !data.hasNext ? 0.5 : 1 }}
                >
                  <ChevronRight size={16} />
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
