import { useEffect, useMemo, useState } from 'react';
import { Activity as ActivityIcon, RefreshCw, Search, Filter } from 'lucide-react';

interface ClusterOption {
  id: string;
  name: string;
}

interface ActivityEntry {
  id: string;
  kind: string;
  cluster_id: string;
  cluster_name?: string;
  action: string;
  resource: string;
  details?: string;
  actor: string;
  occurred_at: string;
}

const PAGE_SIZE = 100;

export function Activity() {
  const [entries, setEntries] = useState<ActivityEntry[]>([]);
  const [clusters, setClusters] = useState<ClusterOption[]>([]);
  const [clusterId, setClusterId] = useState<string>('');
  const [kind, setKind] = useState<'all' | 'security' | 'config'>('all');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [totalCount, setTotalCount] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const hasNext = (page + 1) * PAGE_SIZE < totalCount;

  useEffect(() => {
    fetch('/api/v1/ui/clusters')
      .then(r => r.json())
      .then((cs) => setClusters(cs.map((c: any) => ({ id: c.id, name: c.name }))))
      .catch(() => setClusters([]));
  }, []);

  const load = useMemo(
    () => async () => {
      setLoading(true);
      setError('');
      try {
        const params = new URLSearchParams();
        if (clusterId) params.append('cluster_id', clusterId);
        if (kind !== 'all') params.append('kind', kind);
        if (query) params.append('q', query);
        params.append('limit', PAGE_SIZE.toString());
        params.append('offset', (page * PAGE_SIZE).toString());

        const res = await fetch(`/api/v1/activity?${params.toString()}`);
        if (!res.ok) {
          const err = await res.json();
          throw new Error(err.detail || 'Failed to load activity');
        }
        const resp = await res.json();
        setEntries(resp.entries || []);
        setTotalCount(resp.count || 0);
      } catch (err: any) {
        setError(err.message || 'Failed to load activity');
        setEntries([]);
        setTotalCount(0);
      } finally {
        setLoading(false);
      }
    },
    [clusterId, kind, query, page],
  );

  useEffect(() => {
    load();
  }, [load]);

  const reset = () => {
    setPage(0);
    setQuery('');
    setClusterId('');
    setKind('all');
  };

  return (
    <div className="migrated-page">
      <div className="page-header">
        <div>
          <h1 className="page-title">
            <ActivityIcon size={24} style={{ color: 'var(--accent-primary)' }} /> Activity
          </h1>
          <p className="page-subtitle">
            Cross-cluster timeline of security actions and broker config changes.
          </p>
        </div>
        <button
          onClick={load}
          disabled={loading}
          className="btn btn-secondary"
        >
          <RefreshCw size={16} className={loading ? 'animate-spin' : ''} /> Refresh
        </button>
      </div>

      {/* Filters */}
      <div className="migrated-card mb-6 flex-row gap-4 flex-wrap" style={{ alignItems: 'flex-end', backgroundColor: 'var(--color-info-light)', borderColor: 'rgba(24, 95, 165, 0.2)' }}>
        <div className="form-group flex-1" style={{ minWidth: '200px', marginBottom: 0 }}>
          <label className="form-label">Search</label>
          <div style={{ position: 'relative' }}>
            <Search size={16} style={{ position: 'absolute', left: '0.75rem', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-tertiary)' }} />
            <input
              value={query}
              onChange={(e) => {
                setPage(0);
                setQuery(e.target.value);
              }}
              placeholder="action, resource, actor, details"
              className="form-input"
              style={{ paddingLeft: '2.25rem' }}
            />
          </div>
        </div>

        <div className="form-group" style={{ minWidth: '200px', marginBottom: 0 }}>
          <label className="form-label">Cluster</label>
          <select
            value={clusterId}
            onChange={(e) => {
              setPage(0);
              setClusterId(e.target.value);
            }}
            className="form-select"
          >
            <option value="">All clusters</option>
            {clusters.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </div>

        <div className="form-group" style={{ minWidth: '120px', marginBottom: 0 }}>
          <label className="form-label">Kind</label>
          <select
            value={kind}
            onChange={(e) => {
              setPage(0);
              setKind(e.target.value as 'all' | 'security' | 'config');
            }}
            className="form-select"
          >
            <option value="all">All</option>
            <option value="security">Security</option>
            <option value="config">Config</option>
          </select>
        </div>

        <button
          onClick={reset}
          className="btn btn-secondary"
          style={{ height: '38px' }}
        >
          <Filter size={16} /> Reset
        </button>
      </div>

      {error && (
        <div className="alert alert-error mb-6 flex-row gap-2" style={{ alignItems: 'center' }}>
           <Filter size={16}/> {error}
        </div>
      )}

      {/* Table */}
      <div className="table-container">
        <table className="migrated-table">
          <thead>
            <tr>
              <th style={{ width: '180px' }}>When</th>
              <th style={{ width: '80px' }}>Kind</th>
              <th style={{ width: '160px' }}>Cluster</th>
              <th style={{ width: '180px' }}>Action</th>
              <th>Resource</th>
              <th style={{ width: '120px' }}>Actor</th>
            </tr>
          </thead>
          <tbody>
            {entries.length === 0 && !loading && (
              <tr>
                <td colSpan={6} className="empty-state" style={{ backgroundColor: 'transparent' }}>
                  <ActivityIcon size={32} style={{ opacity: 0.5, marginBottom: '0.5rem' }} />
                  No activity matches these filters.
                </td>
              </tr>
            )}
            {entries.map((e) => (
              <tr key={`${e.kind}-${e.id}`}>
                <td className="font-mono text-xs" style={{ color: 'var(--text-secondary)' }}>
                  {new Date(e.occurred_at).toLocaleString()}
                </td>
                <td>
                  <span
                    className={`badge ${e.kind === 'security' ? 'badge-warning' : 'badge-info'}`}
                    style={{ fontSize: '10px', padding: '0.125rem 0.375rem' }}
                  >
                    {e.kind}
                  </span>
                </td>
                <td style={{ fontWeight: 500 }}>{e.cluster_name || '—'}</td>
                <td className="font-mono text-xs" style={{ color: 'var(--text-secondary)' }}>{e.action}</td>
                <td>
                  <div className="font-mono text-xs" style={{ backgroundColor: 'var(--bg-raised)', padding: '0.125rem 0.375rem', borderRadius: 'var(--radius-sm)', display: 'inline-block' }}>{e.resource}</div>
                  {e.details && (
                    <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', marginTop: '0.375rem', wordBreak: 'break-all', maxWidth: '36rem' }}>{e.details}</div>
                  )}
                </td>
                <td style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>{e.actor || '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      <div className="migrated-card mt-6 flex-row justify-between" style={{ alignItems: 'center' }}>
        <div style={{ fontSize: '0.875rem', color: 'var(--text-secondary)' }}>
          Page <span style={{ fontWeight: 500, color: 'var(--text-primary)' }}>{page + 1}</span> · <span style={{ fontWeight: 500, color: 'var(--text-primary)' }}>{entries.length}</span> entries on this page · <span style={{ fontWeight: 500, color: 'var(--text-primary)' }}>{totalCount}</span> total
          {hasNext && <span style={{ color: 'var(--accent-primary)', marginLeft: '0.25rem' }}>· more available</span>}
        </div>
        <div className="flex-row gap-2">
          <button
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={page === 0 || loading}
            className="btn btn-secondary"
          >
            Previous
          </button>
          <button
            onClick={() => setPage((p) => p + 1)}
            disabled={!hasNext || loading}
            className="btn btn-secondary"
          >
            Next
          </button>
        </div>
      </div>
    </div>
  );
}
