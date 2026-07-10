import { useEffect, useMemo, useState } from 'react';
import {
  CheckCircle2, Clock3, FileClock, Filter,
  History, LockKeyhole, Package, RefreshCw, Search, UserRound, XCircle,
} from 'lucide-react';
import './AuditLogs.css';

interface AuditEvent {
  id: string;
  actor: string;
  category: string;
  action: string;
  resourceType: string;
  resourceId?: string;
  clusterId?: string;
  status: string;
  details?: unknown;
  createdTime: string;
}

interface AuditResponse { events?: AuditEvent[] }

const parseJson = (value: unknown): unknown => {
  if (typeof value !== 'string' || !value.trim()) return value;
  try { return JSON.parse(value); } catch { return value; }
};

const displayJson = (value: unknown) => {
  const parsed = parseJson(value);
  if (parsed === null || parsed === undefined || parsed === '') return 'No details captured';
  return typeof parsed === 'string' ? parsed : JSON.stringify(parsed, null, 2);
};

const displayInline = (value: unknown) => displayJson(value).replace(/\s+/g, ' ').trim();

const title = (value: string) => value.replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, letter => letter.toUpperCase());

export function AuditLogs() {
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [search, setSearch] = useState('');
  const [resourceId, setResourceId] = useState('');
  const [category, setCategory] = useState('ALL');
  const [status, setStatus] = useState('ALL');
  const [actor, setActor] = useState('ALL');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');

  const fetchLogs = async () => {
    setLoading(true);
    setError('');
    try {
      const results = await Promise.allSettled([
        fetch('/api/v1/ui/audit?size=500').then(async response => {
          if (!response.ok) throw new Error(`Management audit API returned ${response.status}`);
          return response.json() as Promise<AuditResponse>;
        }),
      ]);
      const combined = results.flatMap(result => result.status === 'fulfilled' ? result.value.events || [] : [])
        .sort((left, right) => new Date(right.createdTime).getTime() - new Date(left.createdTime).getTime());
      setEvents(combined);
      const failures = results.filter(result => result.status === 'rejected').length;
      if (failures === results.length) throw new Error('Audit services are unavailable.');
      if (failures) setError('One audit source is temporarily unavailable; showing the remaining events.');
    } catch (caught) {
      setEvents([]);
      setError(caught instanceof Error ? caught.message : 'Unable to load audit trail.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchLogs(); }, []);

  const categories = useMemo(() => Array.from(new Set(events.map(event => event.category))).sort(), [events]);
  const actors = useMemo(() => Array.from(new Set(events.map(event => event.actor).filter(Boolean))).sort(), [events]);
  const filtered = useMemo(() => events.filter(event => {
    if (category !== 'ALL' && event.category !== category) return false;
    if (status !== 'ALL' && event.status !== status) return false;
    if (actor !== 'ALL' && event.actor !== actor) return false;
    if (resourceId.trim() && !(event.resourceId || '').toLowerCase().includes(resourceId.trim().toLowerCase())) return false;
    const created = new Date(event.createdTime).getTime();
    if (from && created < new Date(from).getTime()) return false;
    if (to && created > new Date(to).getTime()) return false;
    const haystack = [event.action, event.actor, event.resourceType, event.resourceId, event.clusterId, displayJson(event.details)].join(' ').toLowerCase();
    return !search.trim() || haystack.includes(search.trim().toLowerCase());
  }), [events, category, status, actor, resourceId, from, to, search]);

  const summary = useMemo(() => ({
    total: events.length,
    success: events.filter(event => event.status === 'SUCCESS').length,
    failed: events.filter(event => event.status === 'FAILED').length,
  }), [events]);

  return <div className="audit-page animate-fade-in">
    <header className="audit-header"><div>
      <div className="audit-eyebrow"><LockKeyhole size={13} /> Append-only event ledger</div>
      <h1>Audit Trail</h1><p>Who performed each action, on which resource, and whether it succeeded.</p>
    </div><button className="btn" onClick={fetchLogs} disabled={loading}><RefreshCw size={14} className={loading ? 'spin' : ''} /> Refresh</button></header>

    <section className="audit-summary-grid">
      <article><FileClock size={18} /><div><strong>{summary.total}</strong><span>Captured events</span></div></article>
      <article className="success"><CheckCircle2 size={18} /><div><strong>{summary.success}</strong><span>Successful</span></div></article>
      <article className="failed"><XCircle size={18} /><div><strong>{summary.failed}</strong><span>Failed</span></div></article>
    </section>

    <section className="audit-readonly-note"><LockKeyhole size={15} /><div><strong>Read-only audit history</strong><span>This screen has no edit or delete controls. Every application action creates a separate record.</span></div></section>

    <section className="audit-filters">
      <label className="audit-search"><Search size={14} /><input placeholder="Search actor, action, resource or details" value={search} onChange={event => setSearch(event.target.value)} /></label>
      <label><input placeholder="Resource ID" value={resourceId} onChange={event => setResourceId(event.target.value)} /></label>
      <label><Filter size={13} /><select value={category} onChange={event => setCategory(event.target.value)}><option value="ALL">All events</option>{categories.map(item => <option key={item} value={item}>{title(item)}</option>)}</select></label>
      <label><select value={status} onChange={event => setStatus(event.target.value)}><option value="ALL">All statuses</option><option>SUCCESS</option><option>FAILED</option><option>REQUESTED</option></select></label>
      <label><UserRound size={13} /><select value={actor} onChange={event => setActor(event.target.value)}><option value="ALL">All actors</option>{actors.map(item => <option key={item}>{item}</option>)}</select></label>
      <label className="date-filter"><span>From</span><input type="datetime-local" value={from} onChange={event => setFrom(event.target.value)} /></label>
      <label className="date-filter"><span>To</span><input type="datetime-local" value={to} onChange={event => setTo(event.target.value)} /></label>
    </section>

    {error && <div className="audit-warning">{error}</div>}
    <section className="audit-ledger">
      <div className="audit-ledger-head"><div><History size={15} /><strong>Event ledger</strong></div><span>{filtered.length} of {events.length} events</span></div>
      {loading ? <div className="audit-empty"><RefreshCw className="spin" /><p>Loading audit records...</p></div>
        : filtered.length === 0 ? <div className="audit-empty"><LockKeyhole /><h3>No matching audit events</h3><p>Adjust the filters or perform an auditable operation.</p></div>
        : <div className="audit-table-wrap"><table className="audit-table"><thead><tr><th>Time</th><th>Event</th><th>Actor</th><th>Resource</th><th>Cluster ID</th><th>Details</th><th>Status</th></tr></thead>
          <tbody>{filtered.map(event => <AuditRow key={event.id} event={event} />)}</tbody>
        </table></div>}
    </section>
  </div>;
}

function AuditRow({ event }: { event: AuditEvent }) {
  const details = displayInline(event.details);
  return <tr>
      <td><div className="audit-time"><Clock3 size={12} /><span>{new Date(event.createdTime).toLocaleDateString()}</span><small>{new Date(event.createdTime).toLocaleTimeString()}</small></div></td>
      <td><div className="audit-event"><span className={`category-dot ${event.category.toLowerCase()}`}>{event.category === 'PACKAGE' ? <Package size={12} /> : null}</span><div><strong>{title(event.action)}</strong><small>{title(event.category)}</small></div></div></td>
      <td><div className="audit-actor"><UserRound size={13} /><span>{event.actor || 'system'}</span></div></td>
      <td><div className="audit-resource"><strong>{title(event.resourceType || 'SYSTEM')}</strong><small>{event.resourceId || event.clusterId || 'platform'}</small></div></td>
      <td><div className="audit-resource"><small>{event.clusterId || '-'}</small></div></td>
      <td><div className="audit-details-inline"><span title={details}>{details}</span><small>Audit ID: {event.id}</small></div></td>
      <td><span className={`audit-status ${event.status.toLowerCase()}`}>{event.status}</span></td>
    </tr>
}
