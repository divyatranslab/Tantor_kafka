import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ArrowUp, Download, Filter, Loader2, PauseCircle, PlayCircle, RefreshCw, Search, Terminal } from 'lucide-react';
import './LogManagement.css';

type CentralLogEntry = {
  timestamp: string;
  source: string;
  component: string;
  level: string;
  message: string;
  hostId?: string;
  clusterId?: string;
  jobId?: string;
  taskId?: string;
  status?: string;
  correlationId?: string;
};

type CentralLogResponse = {
  entries: CentralLogEntry[];
  total: number;
  limit: number;
  retentionDays: number;
};

const SOURCES = ['ALL', 'JOB', 'JOB_STEP', 'AGENT'];
const RETENTION_OPTIONS = [
  { label: '7 days', value: '7' },
  { label: '30 days', value: '30' },
  { label: '90 days', value: '90' },
  { label: 'All retained', value: '0' },
];

export function LogManagement() {
  const [logs, setLogs] = useState<CentralLogEntry[]>([]);
  const [query, setQuery] = useState('');
  const [source, setSource] = useState('ALL');
  const [component, setComponent] = useState('ALL');
  const [hostId, setHostId] = useState('');
  const [jobId, setJobId] = useState('');
  const [clusterId, setClusterId] = useState('');
  const [retentionDays, setRetentionDays] = useState('30');
  const [liveTail, setLiveTail] = useState(true);
  const [followingLatest, setFollowingLatest] = useState(true);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const consoleRef = useRef<HTMLDivElement>(null);

  const params = useMemo(() => {
    const next = new URLSearchParams();
    if (query.trim()) next.set('query', query.trim());
    if (source !== 'ALL') next.set('source', source);
    if (component !== 'ALL') next.set('component', component);
    if (hostId.trim()) next.set('hostId', hostId.trim());
    if (jobId.trim()) next.set('jobId', jobId.trim());
    if (clusterId.trim()) next.set('clusterId', clusterId.trim());
    next.set('retentionDays', retentionDays);
    next.set('limit', liveTail ? '500' : '1000');
    return next;
  }, [query, source, component, hostId, jobId, clusterId, retentionDays, liveTail]);

  const fetchLogs = useCallback(async () => {
    setError(null);
    try {
      const response = await fetch(`/api/v1/ui/logs?${params.toString()}`);
      if (!response.ok) throw new Error('Failed to load logs');
      const data: CentralLogResponse = await response.json();
      setLogs(data.entries || []);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load logs');
    } finally {
      setLoading(false);
    }
  }, [params]);

  useEffect(() => {
    setLoading(true);
    fetchLogs();
  }, [fetchLogs]);

  useEffect(() => {
    if (!liveTail) return;
    const interval = window.setInterval(fetchLogs, 3000);
    return () => window.clearInterval(interval);
  }, [fetchLogs, liveTail]);

  useEffect(() => {
    if (liveTail && followingLatest && consoleRef.current) {
      consoleRef.current.scrollTop = 0;
    }
  }, [logs, liveTail, followingLatest]);

  const handleConsoleScroll = useCallback(() => {
    if (!consoleRef.current) return;
    setFollowingLatest(consoleRef.current.scrollTop <= 24);
  }, []);

  const showLatestLogs = useCallback(() => {
    setFollowingLatest(true);
    consoleRef.current?.scrollTo({ top: 0, behavior: 'smooth' });
  }, []);

  const toggleLiveTail = useCallback(() => {
    setLiveTail(current => {
      if (!current) {
        setFollowingLatest(true);
        window.requestAnimationFrame(() => consoleRef.current?.scrollTo({ top: 0 }));
      }
      return !current;
    });
  }, []);

  const components = useMemo(() => {
    const values = Array.from(new Set(logs.map(log => log.component).filter(Boolean))).sort();
    return ['ALL', ...values];
  }, [logs]);

  const logRows = useMemo(() => {
    const occurrences = new Map<string, number>();
    return logs.map(log => {
      const baseKey = `${log.timestamp}|${log.source}|${log.component}|${log.taskId || log.jobId || log.correlationId || ''}|${log.message}`;
      const occurrence = occurrences.get(baseKey) || 0;
      occurrences.set(baseKey, occurrence + 1);
      return { log, key: `${baseKey}|${occurrence}` };
    });
  }, [logs]);

  const downloadLogs = () => {
    const downloadParams = new URLSearchParams(params);
    downloadParams.set('limit', '2000');
    window.location.href = `/api/v1/ui/logs/download?${downloadParams.toString()}`;
  };

  const clearFilters = () => {
    setQuery('');
    setSource('ALL');
    setComponent('ALL');
    setHostId('');
    setJobId('');
    setClusterId('');
    setRetentionDays('30');
  };

  return (
    <section className="log-management-page animate-fade-in">
      <div className="log-management-hero glass-panel">
        <div>
          <span className="eyebrow">Observability</span>
          <h1><Terminal size={28} /> Log Management</h1>
          <p>Centralized view for control-plane, job, and agent logs. Broker/service collectors can plug into the same filters later.</p>
        </div>
        <div className="log-management-actions">
          <button onClick={toggleLiveTail} className={liveTail ? 'active' : ''}>
            {liveTail ? <PauseCircle size={16} /> : <PlayCircle size={16} />}
            {liveTail ? 'Pause live tail' : 'Resume live tail'}
          </button>
          <button onClick={fetchLogs} disabled={loading}><RefreshCw size={16} className={loading ? 'spin' : ''} /> Refresh</button>
          <button onClick={downloadLogs}><Download size={16} /> Download</button>
        </div>
      </div>

      <div className="log-filter-panel glass-panel">
        <div className="log-search-box">
          <Search size={16} />
          <input value={query} onChange={event => setQuery(event.target.value)} placeholder="Search logs, status, component, correlation ID..." />
        </div>
        <label>Source<select value={source} onChange={event => setSource(event.target.value)}>{SOURCES.map(item => <option key={item}>{item}</option>)}</select></label>
        <label>Component<select value={component} onChange={event => setComponent(event.target.value)}>{components.map(item => <option key={item}>{item}</option>)}</select></label>
        <label>Retention<select value={retentionDays} onChange={event => setRetentionDays(event.target.value)}>{RETENTION_OPTIONS.map(item => <option key={item.value} value={item.value}>{item.label}</option>)}</select></label>
        <label>Host<input value={hostId} onChange={event => setHostId(event.target.value)} placeholder="host-id" /></label>
        <label>Job ID<input value={jobId} onChange={event => setJobId(event.target.value)} placeholder="uuid" /></label>
        <label>Cluster ID<input value={clusterId} onChange={event => setClusterId(event.target.value)} placeholder="uuid" /></label>
        <button className="clear-filters" onClick={clearFilters}><Filter size={15} /> Reset</button>
      </div>

      <div className="log-summary-strip">
        <div><span>Records</span><strong>{logs.length}</strong></div>
        <div><span>Live tail</span><strong>{liveTail ? (followingLatest ? 'Following latest' : 'Browsing history') : 'Paused'}</strong></div>
        <div><span>Retention</span><strong>{retentionDays === '0' ? 'All retained' : `${retentionDays} days`}</strong></div>
      </div>

      {error && <div className="log-error">{error}</div>}

      <div className="central-log-shell">
        {liveTail && !followingLatest && (
          <button className="latest-logs-button" onClick={showLatestLogs}>
            <ArrowUp size={15} /> Latest logs
          </button>
        )}
        <div className="central-log-console" ref={consoleRef} onScroll={handleConsoleScroll}>
        {logs.length > 0 && (
          <div className="central-log-columns" aria-hidden="true">
            <span>Time</span>
            <span>Level</span>
            <span>Source</span>
            <span>Component</span>
            <span>Message</span>
          </div>
        )}
        {loading && logs.length === 0 ? (
          <div className="log-loading"><Loader2 className="spin" /> Loading logs...</div>
        ) : logs.length === 0 ? (
          <div className="log-loading">No logs matched the selected filters.</div>
        ) : logRows.map(({ log, key }) => (
          <div key={key} className={`central-log-line level-${log.level?.toLowerCase() || 'info'}`}>
            <span className="log-time">{new Date(log.timestamp).toLocaleString()}</span>
            <span className="log-level">{log.level || 'INFO'}</span>
            <span className="log-source">{log.source}</span>
            <span className="log-component">{log.component}</span>
            <span className="log-message">{log.message}</span>
            <span className="log-meta">{log.hostId ? `host=${log.hostId}` : ''}{log.jobId ? ` job=${log.jobId}` : ''}{log.taskId ? ` task=${log.taskId}` : ''}</span>
          </div>
        ))}
        </div>
      </div>
    </section>
  );
}

