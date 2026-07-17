import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { Users, Search, ChevronLeft, ChevronRight, X, ArrowUp, RefreshCw } from 'lucide-react';
import './Consumers.css';

interface ConsumerGroupSummaryDto {
  groupId: string;
  state: string;
  membersCount: number;
  totalLag: number;
  health: 'HEALTHY' | 'WARNING' | 'INACTIVE' | 'CRITICAL';
  lastUpdated: number;
}

interface PaginatedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}

interface PartitionLagDto {
  topic: string;
  partition: number;
  currentOffset: number;
  logEndOffset: number;
  lag: number;
}

interface ConsumerGroupMemberDto {
  memberId: string;
  clientId: string;
  host: string;
  partitions: PartitionLagDto[];
}

interface ConsumerGroupDetailDto {
  groupId: string;
  state: string;
  members: ConsumerGroupMemberDto[];
}

export function Consumers() {
  const { id } = useParams<{ id: string }>();
  const [data, setData] = useState<PaginatedResponse<ConsumerGroupSummaryDto> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [page, setPage] = useState(0);
  const [size] = useState(10);
  const [searchInput, setSearchInput] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [sortBy, setSortBy] = useState('totalLag'); // Default sort

  // Detail Modal State
  const [selectedGroupId, setSelectedGroupId] = useState<string | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailData, setDetailData] = useState<ConsumerGroupDetailDto | null>(null);

  const fetchGroups = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`/api/v1/clusters/${id}/consumer-groups?page=${page}&size=${size}&search=${encodeURIComponent(searchQuery)}&sortBy=${sortBy}`);
      if (!res.ok) {
        const errorData = await res.json().catch(() => ({}));
        throw new Error(errorData.message || `Failed to load consumer groups (HTTP ${res.status})`);
      }
      const json = await res.json();
      setData(json);
    } catch (e: any) {
      console.error(e);
      setError(e.message || 'Failed to load consumer groups');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchGroups();
  }, [id, page, searchQuery, sortBy]);

  const handleSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setPage(0);
    setSearchQuery(searchInput);
  };

  const handleSort = (column: string) => {
    setSortBy(column);
    setPage(0);
  };

  const handleRowClick = async (groupId: string) => {
    setSelectedGroupId(groupId);
    setDetailLoading(true);
    setDetailData(null);
    try {
      const res = await fetch(`/api/v1/clusters/${id}/consumer-groups/${encodeURIComponent(groupId)}`);
      if (res.ok) {
        setDetailData(await res.json());
      }
    } catch (e) {
      console.error(e);
    } finally {
      setDetailLoading(false);
    }
  };



  return (
    <div className="consumers-tab animate-fade-in" style={{ width: '100%' }}>
      <div className="consumers-header" style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', marginBottom: '24px', gap: '16px' }}>
        <h2 style={{ fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '18px', color: '#5B327F', margin: 0 }}>Consumer Groups</h2>
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
            onClick={fetchGroups} 
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
            aria-label="Refresh consumer groups"
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

      <div className="table-card">
        {loading && !data ? (
          <div className="empty-state">Loading consumer groups...</div>
        ) : data?.content.length === 0 ? (
          <div className="empty-state">
            <Users size={32} style={{ color: 'var(--text-secondary)' }} />
            <p className="empty-title">No active consumer groups</p>
            <p className="empty-subtitle">There are no consumers matching your search criteria.</p>
          </div>
        ) : (
          <>
            <div style={{ overflowX: 'auto', width: '100%' }}>
              <div className="figma-table" style={{ display: 'flex', flexDirection: 'column', width: '100%' }}>
                {/* Header Row */}
                <div className="figma-table-header" style={{ display: 'flex', flexDirection: 'row', alignItems: 'center', width: '100%', height: '54px', background: '#F9F9F9', borderBottom: '1px solid #CCCCCC', boxSizing: 'border-box' }}>
                  <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '16px', gap: '4px', width: '188.17px', flex: '1 1 188.17px', height: '54px', color: '#332849', fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '16px', cursor: 'pointer' }} onClick={() => handleSort('groupId')}>
                    <span>Group ID</span> <ArrowUp size={14} style={{ marginLeft: '4px' }} />
                  </div>
                  <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '16px', gap: '4px', width: '188.17px', flex: '1 1 188.17px', height: '54px', color: '#332849', fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '16px', cursor: 'pointer' }} onClick={() => handleSort('state')}>
                    <span>State</span>
                  </div>
                  <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '16px', gap: '4px', width: '188.17px', flex: '1 1 188.17px', height: '54px', color: '#332849', fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '16px' }}>
                    <span>Members</span>
                  </div>
                  <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '16px', gap: '4px', width: '188.17px', flex: '1 1 188.17px', height: '54px', color: '#332849', fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '16px', cursor: 'pointer' }} onClick={() => handleSort('totalLag')}>
                    <span>Total Lag</span>
                  </div>
                  <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '16px', gap: '4px', width: '188.17px', flex: '1 1 188.17px', height: '54px', color: '#332849', fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '16px' }}>
                    <span>Health</span>
                  </div>
                  <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '16px', gap: '4px', width: '188.17px', flex: '1 1 188.17px', height: '54px', color: '#332849', fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '16px' }}>
                    <span>Last Updated</span>
                  </div>
                </div>

                {/* Table Body */}
                <div className="figma-table-body" style={{ display: 'flex', flexDirection: 'column' }}>
                  {data?.content.map(g => (
                    <div key={g.groupId} className="figma-table-row table-row-hover clickable" onClick={() => handleRowClick(g.groupId)} style={{ display: 'flex', flexDirection: 'row', alignItems: 'center', width: '100%', height: '52px', background: '#FFFFFF', borderBottom: '1px solid #CCCCCC', boxSizing: 'border-box' }}>
                      <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '14px 16px', gap: '4px', width: '188.17px', flex: '1 1 188.17px', height: '52px', color: '#23252D', fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '14px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {g.groupId}
                      </div>
                      <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '14px 16px', gap: '4px', width: '188.17px', flex: '1 1 188.17px', height: '52px', color: '#23252D', fontFamily: 'Satoshi, sans-serif', fontWeight: 400, fontSize: '14px' }}>
                        {g.state}
                      </div>
                      <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '14px 16px', gap: '4px', width: '188.17px', flex: '1 1 188.17px', height: '52px', color: '#23252D', fontFamily: 'Satoshi, sans-serif', fontWeight: 400, fontSize: '14px' }}>
                        {g.membersCount}
                      </div>
                      <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '14px 16px', gap: '4px', width: '188.17px', flex: '1 1 188.17px', height: '52px', color: '#23252D', fontFamily: 'Satoshi, sans-serif', fontWeight: 400, fontSize: '14px' }}>
                        {g.totalLag}
                      </div>
                      <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '14px 16px', gap: '4px', width: '188.17px', flex: '1 1 188.17px', height: '52px', color: '#23252D', fontFamily: 'Satoshi, sans-serif', fontWeight: 400, fontSize: '14px' }}>
                        {g.health.charAt(0) + g.health.slice(1).toLowerCase()}
                      </div>
                      <div style={{ boxSizing: 'border-box', display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '14px 16px', gap: '4px', width: '188.17px', flex: '1 1 188.17px', height: '52px', color: '#818181', fontFamily: 'Satoshi, sans-serif', fontWeight: 400, fontSize: '14px' }}>
                        {new Date(g.lastUpdated).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            </div>

            {/* Pagination Controls */}
            {data && data.totalPages > 1 && (
              <div className="pagination">
                <span style={{ fontSize: '0.875rem', color: 'var(--text-secondary)' }}>
                  Showing {page * size + 1} to {Math.min((page + 1) * size, data.totalElements)} of {data.totalElements} results
                </span>
                <div className="pagination-controls">
                  <button
                    className="pagination-btn"
                    disabled={page === 0}
                    onClick={() => setPage(p => p - 1)}
                  >
                    <ChevronLeft size={16} /> Previous
                  </button>
                  <button
                    className="pagination-btn"
                    disabled={!data.hasNext}
                    onClick={() => setPage(p => p + 1)}
                  >
                    Next <ChevronRight size={16} />
                  </button>
                </div>
              </div>
            )}
          </>
        )}
      </div>

      {/* Detail Modal */}
      {selectedGroupId && (
        <div className="modal-overlay" onClick={() => setSelectedGroupId(null)}>
          <div className="modal-content" onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <h3>Consumer Group Details: {selectedGroupId}</h3>
              <button onClick={() => setSelectedGroupId(null)} style={{ background: 'none', border: 'none', cursor: 'pointer', padding: '0.25rem' }}>
                <X size={20} color="var(--text-secondary)" />
              </button>
            </div>
            <div className="modal-body">
              {detailLoading ? (
                <p>Loading details...</p>
              ) : detailData ? (
                <div>
                  <div style={{ display: 'flex', gap: '2rem', marginBottom: '1.5rem', backgroundColor: 'var(--bg-primary)', padding: '1rem', borderRadius: '0.5rem' }}>
                    <div>
                      <span style={{ display: 'block', fontSize: '0.75rem', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>State</span>
                      <span style={{ fontWeight: 500 }}>{detailData.state}</span>
                    </div>
                    <div>
                      <span style={{ display: 'block', fontSize: '0.75rem', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Total Members</span>
                      <span style={{ fontWeight: 500 }}>{detailData.members.length}</span>
                    </div>
                  </div>

                  <div className="detail-section">
                    <h4>Members & Partitions</h4>
                    {detailData.members.length === 0 ? (
                      <p style={{ color: 'var(--text-secondary)', fontSize: '0.875rem' }}>No active members or assigned partitions found for this group.</p>
                    ) : (
                      detailData.members.map((m, i) => (
                        <div key={i} style={{ marginBottom: '1.5rem', border: '1px solid var(--border-color)', borderRadius: '0.5rem', overflow: 'hidden' }}>
                          <div style={{ backgroundColor: '#f8fafc', padding: '0.75rem 1rem', borderBottom: '1px solid var(--border-color)', fontSize: '0.875rem' }}>
                            <div style={{ display: 'flex', gap: '1rem' }}>
                              <div><strong>Member ID:</strong> {m.memberId}</div>
                              <div><strong>Client ID:</strong> {m.clientId}</div>
                              <div><strong>Host:</strong> {m.host}</div>
                            </div>
                          </div>
                          <table className="data-table" style={{ border: 'none' }}>
                            <thead>
                              <tr>
                                <th style={{ backgroundColor: '#fff', borderTop: 'none' }}>Topic</th>
                                <th style={{ backgroundColor: '#fff', borderTop: 'none' }}>Partition</th>
                                <th style={{ backgroundColor: '#fff', borderTop: 'none' }}>Current Offset</th>
                                <th style={{ backgroundColor: '#fff', borderTop: 'none' }}>Log End Offset</th>
                                <th style={{ backgroundColor: '#fff', borderTop: 'none' }}>Lag</th>
                              </tr>
                            </thead>
                            <tbody>
                              {m.partitions.length === 0 ? (
                                <tr><td colSpan={5} style={{ textAlign: 'center', color: 'var(--text-secondary)' }}>No partitions assigned</td></tr>
                              ) : (
                                m.partitions.map((p) => (
                                  <tr key={`${p.topic}-${p.partition}`}>
                                    <td>{p.topic}</td>
                                    <td>{p.partition}</td>
                                    <td style={{ color: p.currentOffset === -1 ? 'var(--text-secondary)' : 'inherit' }}>
                                      {p.currentOffset === -1 ? 'Unknown' : p.currentOffset.toLocaleString()}
                                    </td>
                                    <td style={{ color: p.logEndOffset === -1 ? 'var(--text-secondary)' : 'inherit' }}>
                                      {p.logEndOffset === -1 ? 'Unknown' : p.logEndOffset.toLocaleString()}
                                    </td>
                                    <td style={{ fontWeight: 600, color: p.lag > 0 ? '#b91c1c' : 'inherit' }}>
                                      {p.lag === -1 ? 'Unknown' : p.lag.toLocaleString()}
                                    </td>
                                  </tr>
                                ))
                              )}
                            </tbody>
                          </table>
                        </div>
                      ))
                    )}
                  </div>
                </div>
              ) : (
                <p>Failed to load group details.</p>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
