import { useState, useEffect, useRef, useCallback } from 'react';
import {
  Link2, Play, Square, Trash2, Rocket, RefreshCw, Loader2,
  XCircle, ChevronDown, ChevronUp, Plus, Settings,
  Activity, AlertTriangle, BarChart3,
} from 'lucide-react';

interface Cluster {
  id: string;
  name: string;
  state: string;
  kind?: string;
}

interface ClusterLinkInfo {
  id: string;
  name: string;
  source_cluster_id: string;
  source_cluster_name: string;
  destination_cluster_id: string;
  destination_cluster_name: string;
  topics_pattern: string;
  sync_consumer_offsets: boolean;
  sync_topic_configs: boolean;
  state: string;
  mm2_port: number;
  deploy_host_id: string | null;
  created_at: string | null;
  updated_at: string | null;
}

interface LinkMetrics {
  link_id: string;
  link_name: string;
  state: string;
  connectors: string[];
  replication_lag: number | null;
  error: string | null;
  warnings?: string[];
  mm2_consumer_groups?: string[];
  connector_statuses?: Array<Record<string, unknown>>;
}

const STATE_COLORS: Record<string, string> = {
  created: 'badge-neutral',
  running: 'badge-success',
  stopped: 'badge-warning',
  error: 'badge-error',
};

export function ClusterLinking() {
  const [links, setLinks] = useState<ClusterLinkInfo[]>([]);
  const [clusters, setClusters] = useState<Cluster[]>([]);
  const [loading, setLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [showCreate, setShowCreate] = useState(false);
  const [expandedLink, setExpandedLink] = useState<string | null>(null);
  const [metrics, setMetrics] = useState<Record<string, LinkMetrics>>({});
  const [deployingLink, setDeployingLink] = useState<string | null>(null);
  const [failedDeployLink, setFailedDeployLink] = useState<string | null>(null);
  const [deployTaskId, setDeployTaskId] = useState<string | null>(null);
  const [deployLogs, setDeployLogs] = useState<string[]>([]);
  const [deployErrorByLink, setDeployErrorByLink] = useState<Record<string, string>>({});
  const [actionLoading, setActionLoading] = useState<string | null>(null);

  const [error, setError] = useState('');
  const admin = true;

  // Create form
  const [formName, setFormName] = useState('');
  const [formSource, setFormSource] = useState('');
  const [formDest, setFormDest] = useState('');
  const [formTopics, setFormTopics] = useState('.*');
  const [formSyncOffsets, setFormSyncOffsets] = useState(true);
  const [formSyncConfigs, setFormSyncConfigs] = useState(true);
  const [creating, setCreating] = useState(false);

  const logRef = useRef<HTMLPreElement>(null);

  const fetchLinks = async () => {
    try {
      const res = await fetch('/api/v1/cluster-linking/links');
      if (res.ok) {
        const data = await res.json();
        setLinks(data);
      }
    } catch {
      // ignore
    }
  };

  const fetchClusters = async () => {
    try {
      const res = await fetch('/api/v1/ui/clusters');
      if (res.ok) {
        const data = await res.json();
        setClusters(data.filter((c: Cluster) =>
          c.state === 'running' ||
          c.state === 'connected' ||
          c.state === 'ok' ||
          c.kind === 'external'
        ));
      }
    } catch {
      // ignore
    }
  };

  useEffect(() => {
    Promise.all([fetchLinks(), fetchClusters()]).finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    const interval = setInterval(() => {
      fetchLinks();
      fetchClusters();
    }, 10000);
    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    if (!deployTaskId) return;
    const interval = setInterval(async () => {
      try {
        const res = await fetch(`/api/v1/cluster-linking/tasks/${deployTaskId}`);
        if (!res.ok) throw new Error();
        const data = await res.json();
        setDeployLogs(data.logs || []);
        if (data.status !== 'running') {
          clearInterval(interval);
          if (data.status === 'error' && deployingLink) {
            const logs = data.logs || [];
            const message = data.error_message || logs[logs.length - 1] || 'Deployment failed';
            setFailedDeployLink(deployingLink);
            setDeployErrorByLink(prev => ({ ...prev, [deployingLink]: message }));
          } else {
            setFailedDeployLink(null);
          }
          setDeployingLink(null);
          setDeployTaskId(null);
          fetchLinks();
        }
      } catch {
        clearInterval(interval);
        if (deployingLink) {
          setFailedDeployLink(deployingLink);
          setDeployErrorByLink(prev => ({ ...prev, [deployingLink]: 'Lost connection while polling deploy task' }));
        }
        setDeployingLink(null);
      }
    }, 2000);
    return () => clearInterval(interval);
  }, [deployTaskId, deployingLink]);

  useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight;
  }, [deployLogs]);

  const handleCreate = async () => {
    if (!formName || !formSource || !formDest) return;
    setCreating(true);
    setError('');
    try {
      const res = await fetch('/api/v1/cluster-linking/links', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: formName,
          source_cluster_id: formSource,
          destination_cluster_id: formDest,
          topics_pattern: formTopics,
          sync_consumer_offsets: formSyncOffsets,
          sync_topic_configs: formSyncConfigs,
        })
      });
      if (!res.ok) {
        const err = await res.json();
        throw new Error(err.detail || 'Failed to create link');
      }
      setShowCreate(false);
      setFormName('');
      setFormTopics('.*');
      fetchLinks();
    } catch (err: any) {
      setError(err.message || 'Failed to create link');
    } finally {
      setCreating(false);
    }
  };

  const handleDeploy = async (linkId: string) => {
    setDeployingLink(linkId);
    setFailedDeployLink(null);
    setDeployLogs([]);
    setDeployErrorByLink(prev => {
      const next = { ...prev };
      delete next[linkId];
      return next;
    });
    try {
      const res = await fetch(`/api/v1/cluster-linking/links/${linkId}/deploy`, { method: 'POST' });
      if (!res.ok) {
        const err = await res.json();
        throw new Error(err.detail || 'Deploy failed');
      }
      const data = await res.json();
      setDeployTaskId(data.task_id);
    } catch (err: any) {
      setError(err.message || 'Deploy failed');
      setDeployingLink(null);
    }
  };

  const handleAction = async (linkId: string, action: 'start' | 'stop' | 'delete') => {
    setActionLoading(`${linkId}-${action}`);
    setError('');
    try {
      const res = await fetch(`/api/v1/cluster-linking/links/${linkId}${action !== 'delete' ? `/${action}` : ''}`, {
        method: action === 'delete' ? 'DELETE' : 'POST'
      });
      if (!res.ok) {
        const err = await res.json();
        throw new Error(err.detail || `${action} failed`);
      }
      fetchLinks();
    } catch (err: any) {
      setError(err.message || `${action} failed`);
    } finally {
      setActionLoading(null);
    }
  };

  const fetchMetrics = useCallback(async (linkId: string) => {
    try {
      const res = await fetch(`/api/v1/cluster-linking/links/${linkId}/metrics`);
      if (!res.ok) {
        const err = await res.json();
        throw new Error(err.detail || 'Failed to refresh metrics');
      }
      const data = await res.json();
      setMetrics(prev => ({ ...prev, [linkId]: data }));
    } catch (err: any) {
      setMetrics(prev => ({
        ...prev,
        [linkId]: {
          link_id: linkId,
          link_name: '',
          state: 'unknown',
          connectors: [],
          replication_lag: null,
          error: err.message,
        },
      }));
    }
  }, []);

  const toggleExpand = (linkId: string) => {
    if (expandedLink === linkId) {
      setExpandedLink(null);
    } else {
      setExpandedLink(linkId);
      fetchMetrics(linkId);
    }
  };

  if (loading) {
    return (
      <div className="flex-row justify-center" style={{ height: '16rem' }}>
        <Loader2 size={32} className="animate-spin text-blue-500" />
      </div>
    );
  }

  return (
    <div className="migrated-page">
      <div className="page-header">
        <div>
          <h1 className="page-title">
            <Link2 size={24} />
            Cluster Linking
          </h1>
          <p className="page-subtitle">MirrorMaker 2 cross-cluster replication</p>
        </div>
        <div className="header-actions">
          <button
            onClick={async () => {
              setIsRefreshing(true);
              await Promise.all([fetchLinks(), fetchClusters()]);
              setIsRefreshing(false);
            }}
            disabled={isRefreshing}
            className="btn btn-secondary"
          >
            <RefreshCw size={16} className={isRefreshing ? 'animate-spin' : ''} /> Refresh
          </button>
          {admin && (
            <button
              onClick={() => setShowCreate(!showCreate)}
              className="btn btn-primary"
            >
              <Plus size={16} /> New Link
            </button>
          )}
        </div>
      </div>

      {error && (
        <div className="alert alert-error mb-4 flex-row justify-between w-full">
          <span>{error}</span>
          <button onClick={() => setError('')} className="btn-icon text-danger"><XCircle size={14} /></button>
        </div>
      )}

      {/* Create form */}
      {showCreate && (
        <div className="migrated-card mb-6" style={{ backgroundColor: 'var(--color-info-light)', borderColor: 'rgba(24, 95, 165, 0.2)' }}>
          <h3 style={{ fontSize: '1.125rem', fontWeight: 600, color: 'var(--color-info)', margin: 0, marginBottom: '1rem' }}>Create Cluster Link</h3>
          <div className="flex-row gap-4 mb-4">
            <div className="form-group flex-1" style={{ marginBottom: 0 }}>
              <label className="form-label">Link Name</label>
              <input
                value={formName} onChange={e => setFormName(e.target.value)}
                placeholder="e.g., prod-to-dr"
                className="form-input"
              />
            </div>
            <div className="form-group flex-1" style={{ marginBottom: 0 }}>
              <label className="form-label">Topics Pattern</label>
              <input
                value={formTopics} onChange={e => setFormTopics(e.target.value)}
                placeholder=".*"
                className="form-input font-mono"
              />
            </div>
          </div>
          <div className="flex-row gap-4 mb-4">
            <div className="form-group flex-1" style={{ marginBottom: 0 }}>
              <label className="form-label">Source Cluster</label>
              <select value={formSource} onChange={e => setFormSource(e.target.value)}
                className="form-select">
                <option value="">Select source...</option>
                {clusters.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </div>
            <div className="form-group flex-1" style={{ marginBottom: 0 }}>
              <label className="form-label">Destination Cluster</label>
              <select value={formDest} onChange={e => setFormDest(e.target.value)}
                className="form-select">
                <option value="">Select destination...</option>
                {clusters.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </div>
          </div>
          <div className="flex-row gap-6 mb-6">
            <label className="flex-row gap-2 text-sm" style={{ cursor: 'pointer' }}>
              <input type="checkbox" checked={formSyncOffsets} onChange={e => setFormSyncOffsets(e.target.checked)} />
              Sync Consumer Offsets
            </label>
            <label className="flex-row gap-2 text-sm" style={{ cursor: 'pointer' }}>
              <input type="checkbox" checked={formSyncConfigs} onChange={e => setFormSyncConfigs(e.target.checked)} />
              Sync Topic Configs
            </label>
          </div>
          <div className="flex-row gap-2">
            <button onClick={handleCreate} disabled={creating || !formName || !formSource || !formDest}
              className="btn btn-primary">
              {creating ? <Loader2 size={16} className="animate-spin" /> : <Plus size={16} />}
              Create Link
            </button>
            <button onClick={() => setShowCreate(false)}
              className="btn btn-secondary">Cancel</button>
          </div>
        </div>
      )}

      {/* Links list */}
      {links.length === 0 ? (
        <div className="empty-state migrated-card">
          <Link2 size={40} style={{ opacity: 0.5 }} />
          <h3 style={{ fontSize: '1.125rem', fontWeight: 600, margin: 0, color: 'var(--text-primary)' }}>No Cluster Links</h3>
          <p>
            Create a cluster link to set up cross-cluster replication using MirrorMaker 2.
          </p>
        </div>
      ) : (
        <div className="flex-col gap-4">
          {links.map(link => (
            <div key={link.id} className="migrated-card" style={{ padding: 0, overflow: 'hidden' }}>
              {/* Link header */}
              <div style={{ padding: '1.25rem' }}>
                <div className="flex-row justify-between mb-4">
                  <div className="flex-row gap-3">
                    <Link2 size={20} style={{ color: 'var(--accent-primary)' }} />
                    <div className="flex-col">
                      <h3 style={{ fontSize: '1rem', fontWeight: 600, margin: 0, color: 'var(--text-primary)' }}>{link.name}</h3>
                      <p className="text-sm text-gray-500 mt-1" style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                        {link.source_cluster_name}
                        <span style={{ color: 'var(--border-strong)' }}>→</span>
                        {link.destination_cluster_name}
                      </p>
                    </div>
                  </div>
                  <div className="flex-row gap-2">
                    <span className={`badge ${STATE_COLORS[link.state] || 'badge-neutral'}`}>
                      {link.state}
                    </span>
                    {admin && link.state === 'created' && (
                      <button onClick={() => handleDeploy(link.id)}
                        disabled={deployingLink === link.id}
                        className="btn text-xs" style={{ padding: '0.25rem 0.5rem', backgroundColor: 'var(--color-success)', color: 'white' }}>
                        {deployingLink === link.id ? <Loader2 size={12} className="animate-spin" /> : <Rocket size={12} />}
                        Deploy
                      </button>
                    )}
                    {admin && link.state === 'running' && (
                      <button onClick={() => handleAction(link.id, 'stop')}
                        disabled={actionLoading === `${link.id}-stop`}
                        className="btn text-xs" style={{ padding: '0.25rem 0.5rem', backgroundColor: 'var(--color-warning)', color: 'white' }}>
                        {actionLoading === `${link.id}-stop` ? <Loader2 size={12} className="animate-spin" /> : <Square size={12} />}
                        Stop
                      </button>
                    )}
                    {admin && link.state === 'stopped' && (
                      <button onClick={() => handleAction(link.id, 'start')}
                        disabled={actionLoading === `${link.id}-start`}
                        className="btn text-xs" style={{ padding: '0.25rem 0.5rem', backgroundColor: 'var(--color-success)', color: 'white' }}>
                        {actionLoading === `${link.id}-start` ? <Loader2 size={12} className="animate-spin" /> : <Play size={12} />}
                        Start
                      </button>
                    )}
                    {admin && (
                      <button onClick={() => {
                        if (confirm(`Delete link "${link.name}"? This will stop MirrorMaker 2 and remove all configuration.`)) {
                          handleAction(link.id, 'delete');
                        }
                      }}
                        disabled={actionLoading === `${link.id}-delete`}
                        className="btn-icon text-danger">
                        {actionLoading === `${link.id}-delete` ? <Loader2 size={14} className="animate-spin" /> : <Trash2 size={14} />}
                      </button>
                    )}
                    <button onClick={() => toggleExpand(link.id)}
                      className="btn-icon">
                      {expandedLink === link.id ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
                    </button>
                  </div>
                </div>

                {/* Info row */}
                <div className="flex-row flex-wrap gap-4 text-xs text-gray-500">
                  <span className="flex-row gap-1">Topics: <code className="font-mono" style={{ background: 'var(--bg-raised)', padding: '2px 4px', borderRadius: '4px' }}>{link.topics_pattern}</code></span>
                  <span>Offsets: {link.sync_consumer_offsets ? '✓' : '✗'}</span>
                  <span>Configs: {link.sync_topic_configs ? '✓' : '✗'}</span>
                  <span>Port: {link.mm2_port}</span>
                  {link.created_at && <span>Created: {new Date(link.created_at).toLocaleDateString()}</span>}
                </div>
              </div>

              {deployErrorByLink[link.id] && (
                <div className="alert alert-error" style={{ borderRadius: 0, borderTop: '1px solid var(--border-default)', borderBottom: 'none', borderLeft: 'none', borderRight: 'none' }}>
                  {deployErrorByLink[link.id]}
                </div>
              )}

              {/* Deploy logs */}
              {(deployingLink === link.id || failedDeployLink === link.id) && deployLogs.length > 0 && (
                <div style={{ backgroundColor: '#1C1C1A', borderTop: '1px solid var(--border-default)', padding: '1rem', maxHeight: '12rem', overflowY: 'auto' }}>
                  <pre ref={logRef} className="font-mono text-xs" style={{ color: '#EAF3DE', whiteSpace: 'pre-wrap', margin: 0 }}>
                    {deployLogs.join('\n')}
                  </pre>
                </div>
              )}

              {/* Expanded metrics */}
              {expandedLink === link.id && (
                <div style={{ borderTop: '1px solid var(--border-default)', padding: '1rem', backgroundColor: 'var(--bg-raised)' }}>
                  {metrics[link.id] ? (
                    <div className="flex-col gap-4">
                      <div className="flex-row gap-4">
                        <div className="stat-card flex-1" style={{ backgroundColor: 'var(--bg-surface)' }}>
                          <Activity size={20} style={{ color: 'var(--color-info)', margin: '0 auto 0.5rem auto' }} />
                          <div className="stat-value" style={{ fontSize: '1.5rem' }}>
                            {metrics[link.id].connectors.length}
                          </div>
                          <div className="stat-label" style={{ fontSize: '0.75rem' }}>Connectors</div>
                        </div>
                        <div className="stat-card flex-1" style={{ backgroundColor: 'var(--bg-surface)' }}>
                          <BarChart3 size={20} style={{ color: 'var(--color-warning)', margin: '0 auto 0.5rem auto' }} />
                          <div className="stat-value" style={{ fontSize: '1.5rem' }}>
                            {metrics[link.id].replication_lag !== null ? metrics[link.id].replication_lag : '—'}
                          </div>
                          <div className="stat-label" style={{ fontSize: '0.75rem' }}>Replication Lag</div>
                        </div>
                        <div className="stat-card flex-1" style={{ backgroundColor: 'var(--bg-surface)' }}>
                          <Settings size={20} style={{ color: 'var(--accent-primary)', margin: '0 auto 0.5rem auto' }} />
                          <div className="stat-value" style={{ fontSize: '1.5rem' }}>
                            {metrics[link.id].mm2_consumer_groups?.length || 0}
                          </div>
                          <div className="stat-label" style={{ fontSize: '0.75rem' }}>MM2 Consumer Groups</div>
                        </div>
                      </div>
                      {metrics[link.id].error && (
                        <div className="alert alert-error text-xs" style={{ padding: '0.5rem 0.75rem' }}>
                          <AlertTriangle size={14} />
                          {metrics[link.id].error}
                        </div>
                      )}
                      {metrics[link.id].warnings && metrics[link.id].warnings!.length > 0 && (
                        <div className="flex-col gap-2">
                          {metrics[link.id].warnings!.map((warning, idx) => (
                            <div key={idx} className="alert text-xs" style={{ backgroundColor: 'var(--color-warning-light)', color: 'var(--color-warning)', padding: '0.5rem 0.75rem' }}>
                               <AlertTriangle size={14} />
                               {warning}
                            </div>
                          ))}
                        </div>
                      )}
                      <button onClick={() => fetchMetrics(link.id)}
                        className="btn-icon text-xs" style={{ color: 'var(--color-info)', padding: '0.25rem', width: 'fit-content' }}>
                        <RefreshCw size={12} style={{ marginRight: '4px' }} /> Refresh Metrics
                      </button>
                    </div>
                  ) : (
                    <div className="flex-row justify-center p-4">
                      <Loader2 size={20} className="animate-spin" style={{ color: 'var(--text-tertiary)' }} />
                      <span className="text-sm text-gray-500 ml-2">Loading metrics...</span>
                    </div>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
