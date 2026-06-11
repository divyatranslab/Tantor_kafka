import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Globe2, Search, RefreshCw, Loader2, AlertTriangle, ChevronRight } from 'lucide-react';

type Cluster = {
  id: string;
  name: string;
  kind: 'managed' | 'external';
  state: string;
  environment: string;
  kafka_version: string;
  mode: string;
  broker_count: number | null;
  topic_count: number | null;
  bootstrap_servers: string | null;
};

type Match = {
  cluster_id: string;
  cluster_name: string;
  cluster_kind: 'managed' | 'external';
  environment: string;
  topic: string;
  partitions: number;
  replication_factor: number;
};

export function Federation() {
  const [overview, setOverview] = useState<{
    clusters: Cluster[]; total: number; managed: number; external: number;
  } | null>(null);
  const [loading, setLoading] = useState(true);

  const [q, setQ] = useState('');
  const [searching, setSearching] = useState(false);
  const [searchResult, setSearchResult] = useState<{
    matches: Match[]; match_count: number; skipped: Array<{ name: string; reason: string }>;
  } | null>(null);

  const fetchOverview = async () => {
    setLoading(true);
    try {
      const res = await fetch('/api/v1/federation/overview');
      if (res.ok) {
        const data = await res.json();
        setOverview(data);
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchOverview(); }, []);

  const onSearch = async () => {
    if (!q.trim()) return;
    setSearching(true);
    try {
      const res = await fetch(`/api/v1/federation/search?q=${encodeURIComponent(q.trim())}`);
      if (res.ok) {
        const data = await res.json();
        setSearchResult(data);
      }
    } finally {
      setSearching(false);
    }
  };

  const getEnvBadge = (env: string) => {
    switch (env.toLowerCase()) {
      case 'prod': return 'badge badge-error';
      case 'staging': return 'badge badge-warning';
      case 'qa': return 'badge badge-info';
      case 'dev': return 'badge badge-success';
      default: return 'badge badge-neutral';
    }
  };

  return (
    <div className="migrated-page">
      <div className="page-header">
        <div>
          <h1 className="page-title">
            <Globe2 size={24} style={{ color: 'var(--color-info)' }} /> Data Federation
          </h1>
          <p className="page-subtitle">
            Single pane of glass across every cluster Tantor manages — managed and external.
          </p>
        </div>
        <button onClick={fetchOverview} className="btn btn-secondary">
          <RefreshCw size={16} /> Refresh
        </button>
      </div>

      {loading ? (
        <div className="flex-row justify-center" style={{ height: '8rem', color: 'var(--text-secondary)' }}>
          <Loader2 size={24} className="animate-spin" style={{ color: 'var(--accent-primary)' }} />
        </div>
      ) : !overview ? (
        <div className="alert alert-error">Failed to load federation overview.</div>
      ) : (
        <>
          <div className="flex-row gap-4 mb-6">
            <Stat label="Total clusters" value={overview.total.toString()} />
            <Stat label="Managed" value={overview.managed.toString()} />
            <Stat label="External" value={overview.external.toString()} />
          </div>

          <div className="table-container mb-6">
            <table className="migrated-table">
              <thead>
                <tr>
                  <th>Cluster</th>
                  <th>Kind</th>
                  <th>State</th>
                  <th>Env</th>
                  <th>Brokers</th>
                  <th>Topics</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {overview.clusters.length === 0 ? (
                  <tr><td colSpan={7} className="text-center" style={{ fontStyle: 'italic' }}>No clusters yet</td></tr>
                ) : overview.clusters.map(c => (
                  <tr key={c.id}>
                    <td style={{ fontWeight: 500 }}>{c.name}</td>
                    <td>
                      <span className={`badge ${c.kind === 'managed' ? 'badge-info' : 'badge-neutral'}`}>
                        {c.kind}
                      </span>
                    </td>
                    <td>
                      <span className={`badge ${
                        c.state === 'running' || c.state === 'connected' ? 'badge-success' :
                        c.state === 'error' ? 'badge-error' :
                        'badge-neutral'
                      }`}>{c.state}</span>
                    </td>
                    <td>
                      {c.environment ? (
                        <span className={getEnvBadge(c.environment)}>
                          {c.environment}
                        </span>
                      ) : <span className="text-gray-500 text-xs">—</span>}
                    </td>
                    <td>{c.broker_count ?? '—'}</td>
                    <td>{c.topic_count ?? <span className="text-gray-500" style={{ fontStyle: 'italic' }}>unreachable</span>}</td>
                    <td style={{ textAlign: 'right' }}>
                      <Link to={`/clusters/${c.id}`} className="btn-icon" style={{ textDecoration: 'none', color: 'var(--accent-primary)', fontSize: '0.875rem' }}>
                        Open <ChevronRight size={16} />
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="migrated-card">
            <h3 style={{ fontSize: '1.125rem', marginBottom: '1rem', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <Search size={20} style={{ color: 'var(--text-secondary)' }} /> Find a topic across all clusters
            </h3>
            <div className="flex-row gap-2 mb-6">
              <input
                value={q}
                onChange={e => setQ(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && onSearch()}
                placeholder="topic name (substring match)"
                className="form-input flex-1"
              />
              <button
                onClick={onSearch}
                disabled={searching || !q.trim()}
                className="btn btn-primary"
              >
                {searching && <Loader2 size={18} className="animate-spin" />}
                Search
              </button>
            </div>
            {searchResult && (
              <div className="animate-fade-in">
                <div className="flex-row justify-between mb-4">
                  <span className="text-sm text-gray-500">
                    <strong style={{ color: 'var(--text-primary)' }}>{searchResult.match_count}</strong> match(es) found
                  </span>
                  {searchResult.skipped.length > 0 && (
                    <span className="badge badge-warning flex-row gap-2" style={{ padding: '0.25rem 0.5rem' }}>
                      <AlertTriangle size={14} />
                      Skipped {searchResult.skipped.length} unreachable cluster(s)
                    </span>
                  )}
                </div>
                {searchResult.matches.length === 0 ? (
                  <div className="empty-state">
                    <Search size={32} />
                    No topics matched your search query.
                  </div>
                ) : (
                  <div className="table-container">
                    <table className="migrated-table">
                      <thead>
                        <tr>
                          <th>Topic</th>
                          <th>Cluster</th>
                          <th>Env</th>
                          <th>Partitions</th>
                          <th>RF</th>
                        </tr>
                      </thead>
                      <tbody>
                        {searchResult.matches.map((m, i) => (
                          <tr key={i}>
                            <td className="font-mono text-xs">{m.topic}</td>
                            <td>
                              <Link to={`/clusters/${m.cluster_id}`} style={{ color: 'var(--accent-primary)', textDecoration: 'none', fontWeight: 500 }}>
                                {m.cluster_name}
                              </Link>
                              <span className="badge badge-neutral" style={{ marginLeft: '0.5rem', fontSize: '10px' }}>{m.cluster_kind}</span>
                            </td>
                            <td>
                               {m.environment ? (
                                <span className={getEnvBadge(m.environment)}>
                                  {m.environment}
                                </span>
                              ) : <span className="text-gray-500 text-xs">—</span>}
                            </td>
                            <td>{m.partitions}</td>
                            <td>{m.replication_factor}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="stat-card flex-1">
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
    </div>
  );
}
