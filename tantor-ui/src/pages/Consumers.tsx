import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { Users, Search, ChevronLeft, ChevronRight, X, CheckCircle, AlertTriangle, Clock, XCircle, ArrowUpDown } from 'lucide-react';
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
      if (res.ok) {
        setData(await res.json());
      } else {
        const errorData = await res.json().catch(() => ({}));
        setError(errorData.message || `Consumer groups are not available yet (HTTP ${res.status})`);
      }
    } catch (e: any) {
      console.error(e);
      setError(e.message || 'Failed to load consumer groups');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchGroups();
    const interval = setInterval(fetchGroups, 10000); // Auto-refresh every 10s
    return () => clearInterval(interval);
  }, [id, page, size, searchQuery, sortBy]);

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

  const HealthIcon = ({ health }: { health: string }) => {
    switch (health) {
      case 'HEALTHY': return <CheckCircle size={14} />;
      case 'WARNING': return <AlertTriangle size={14} />;
      case 'CRITICAL': return <XCircle size={14} />;
      default: return <Clock size={14} />;
    }
  };

  return (
    <div className="consumers-tab animate-fade-in">
      <div className="consumers-header">
        <h2>Consumer Groups</h2>
        <form onSubmit={handleSearchSubmit} className="search-bar">
          <Search size={18} color="var(--text-secondary)" />
          <input
            type="text"
            placeholder="Search by Group ID..."
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
          />
        </form>
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
            <table className="data-table">
              <thead>
                <tr>
                  <th onClick={() => handleSort('groupId')}>Group ID <ArrowUpDown size={12} style={{display:'inline', marginLeft:'4px', verticalAlign:'middle'}}/></th>
                  <th onClick={() => handleSort('state')}>State <ArrowUpDown size={12} style={{display:'inline', marginLeft:'4px', verticalAlign:'middle'}}/></th>
                  <th>Members</th>
                  <th onClick={() => handleSort('totalLag')}>Total Lag <ArrowUpDown size={12} style={{display:'inline', marginLeft:'4px', verticalAlign:'middle'}}/></th>
                  <th>Health</th>
                  <th>Last Updated</th>
                </tr>
              </thead>
              <tbody>
                {data?.content.map(g => (
                  <tr key={g.groupId} className="clickable" onClick={() => handleRowClick(g.groupId)}>
                    <td style={{ fontWeight: 500 }}>{g.groupId}</td>
                    <td>
                      <span className="status-badge" style={g.state === 'Stable' ? {} : { backgroundColor: '#fefce8', color: '#a16207', borderColor: '#fef08a' }}>
                        <div className="status-dot" style={g.state === 'Stable' ? {} : { backgroundColor: '#eab308' }}></div>
                        {g.state}
                      </span>
                    </td>
                    <td>{g.membersCount}</td>
                    <td style={{ fontWeight: 600, color: g.totalLag > 0 ? '#b91c1c' : 'inherit' }}>
                      {g.totalLag.toLocaleString()}
                    </td>
                    <td>
                      <span className={`health-badge health-${g.health}`}>
                        <HealthIcon health={g.health} />
                        {g.health}
                      </span>
                    </td>
                    <td style={{ color: 'var(--text-secondary)' }}>
                      {new Date(g.lastUpdated).toLocaleTimeString()}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

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
