import { useEffect, useMemo, useState } from 'react';
import {
  CheckCircle2, ChevronDown, ChevronUp, Clock3, FileClock, Filter,
  History, LockKeyhole, Network, Package, RefreshCw, Search, ShieldCheck,
  UserRound, XCircle,
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
  oldValue?: unknown;
  newValue?: unknown;
  approval?: unknown;
  details?: unknown;
  ipAddress?: string;
  source: string;
  requestId?: string;
  previousHash?: string;
  recordHash: string;
  createdAt: string;
}

interface AuditResponse {
  events?: AuditEvent[];
  integrity?: string;
  summary?: { total?: number; successful?: number; failed?: number; approvals?: number; integrity?: string };
}

const parseJson = (value: unknown): unknown => {
  if (typeof value !== 'string' || !value.trim()) return value;
  try { return JSON.parse(value); } catch { return value; }
};

const displayJson = (value: unknown) => {
  const parsed = parseJson(value);
  if (parsed === null || parsed === undefined || parsed === '') return 'No value captured';
  return typeof parsed === 'string' ? parsed : JSON.stringify(parsed, null, 2);
};

const shortHash = (value?: string) => value ? `${value.slice(0, 10)}…${value.slice(-8)}` : 'ledger start';
const title = (value: string) => value.replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, letter => letter.toUpperCase());

export function AuditLogs() {
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [integrity, setIntegrity] = useState('CHECKING');
  const [expanded, setExpanded] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [category, setCategory] = useState('ALL');
  const [status, setStatus] = useState('ALL');
  const [actor, setActor] = useState('ALL');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');

  const fetchLogs = async () => {
    setLoading(true);
    setError('');
    try {
      const [managementResult, artifactResult] = await Promise.allSettled([
        fetch('/api/v1/ui/audit?size=500').then(async response => {
          if (!response.ok) throw new Error(`Management audit API returned ${response.status}`);
          return response.json() as Promise<AuditResponse>;
        }),
        fetch('/api/v1/artifacts/audit?size=500').then(async response => {
          if (!response.ok) throw new Error(`Package audit API returned ${response.status}`);
          return response.json() as Promise<AuditResponse>;
        }),
      ]);

      const management = managementResult.status === 'fulfilled' ? managementResult.value : { events: [] };
      const artifacts = artifactResult.status === 'fulfilled' ? artifactResult.value : { events: [] };
      const combined = [...(management.events || []), ...(artifacts.events || [])]
        .map(event => ({ ...event, source: event.source || 'MANAGEMENT_SERVER' }))
        .sort((left, right) => new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime());
      setEvents(combined);

      const managementIntegrity = management.summary?.integrity || (management as any).integrity || 'UNAVAILABLE';
      const artifactIntegrity = artifacts.integrity || 'UNAVAILABLE';
      setIntegrity(managementIntegrity === 'BROKEN' || artifactIntegrity === 'BROKEN'
        ? 'BROKEN'
        : managementIntegrity === 'VERIFIED' && artifactIntegrity === 'VERIFIED' ? 'VERIFIED' : 'PARTIAL');

      const failures = [managementResult, artifactResult].filter(result => result.status === 'rejected');
      if (failures.length === 2) throw new Error('Audit services are unavailable.');
      if (failures.length === 1) setError('One audit source is temporarily unavailable; showing the remaining immutable events.');
    } catch (caught) {
      setEvents([]);
      setIntegrity('UNAVAILABLE');
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
    const created = new Date(event.createdAt).getTime();
    if (from && created < new Date(from).getTime()) return false;
    if (to && created > new Date(to).getTime()) return false;
    if (search.trim()) {
      const haystack = [event.action, event.actor, event.resourceType, event.resourceId, event.clusterId,
        displayJson(event.details), displayJson(event.oldValue), displayJson(event.newValue)].join(' ').toLowerCase();
      if (!haystack.includes(search.trim().toLowerCase())) return false;
    }
    return true;
  }), [events, category, status, actor, from, to, search]);

  const summary = useMemo(() => ({
    total: events.length,
    success: events.filter(event => event.status === 'SUCCESS').length,
    failed: events.filter(event => event.status === 'FAILED').length,
    approvals: events.filter(event => event.category === 'APPROVAL').length,
  }), [events]);

  return (
    <div className="audit-page animate-fade-in">
      <header className="audit-header">
        <div>
          <div className="audit-eyebrow"><LockKeyhole size={13} /> Append-only security ledger</div>
          <h1>Audit Trail</h1>
          <p>Who changed what, on which resource, from where, and whether it succeeded.</p>
        </div>
        <div className="audit-header-actions">
          <span className={`integrity-pill ${integrity.toLowerCase()}`}><ShieldCheck size={14} /> Integrity {integrity}</span>
          <button className="btn" onClick={fetchLogs} disabled={loading}><RefreshCw size={14} className={loading ? 'spin' : ''} /> Refresh</button>
        </div>
      </header>

      <section className="audit-summary-grid">
        <article><FileClock size={18} /><div><strong>{summary.total}</strong><span>Captured events</span></div></article>
        <article className="success"><CheckCircle2 size={18} /><div><strong>{summary.success}</strong><span>Successful</span></div></article>
        <article className="failed"><XCircle size={18} /><div><strong>{summary.failed}</strong><span>Failed</span></div></article>
        <article className="approval"><ShieldCheck size={18} /><div><strong>{summary.approvals}</strong><span>Approvals</span></div></article>
      </section>

      <section className="audit-readonly-note">
        <LockKeyhole size={15} />
        <div><strong>Immutable by design</strong><span>Database triggers block UPDATE and DELETE. SHA-256 chaining exposes tampering. This screen has no mutation controls.</span></div>
      </section>

      <section className="audit-filters">
        <label className="audit-search"><Search size={14} /><input placeholder="Search actor, action, resource or value" value={search} onChange={event => setSearch(event.target.value)} /></label>
        <label><Filter size={13} /><select value={category} onChange={event => setCategory(event.target.value)}><option value="ALL">All events</option>{categories.map(item => <option key={item} value={item}>{title(item)}</option>)}</select></label>
        <label><select value={status} onChange={event => setStatus(event.target.value)}><option value="ALL">All statuses</option><option>SUCCESS</option><option>FAILED</option><option>REQUESTED</option></select></label>
        <label><UserRound size={13} /><select value={actor} onChange={event => setActor(event.target.value)}><option value="ALL">All actors</option>{actors.map(item => <option key={item}>{item}</option>)}</select></label>
        <label className="date-filter"><span>From</span><input type="datetime-local" value={from} onChange={event => setFrom(event.target.value)} /></label>
        <label className="date-filter"><span>To</span><input type="datetime-local" value={to} onChange={event => setTo(event.target.value)} /></label>
      </section>

      {error && <div className="audit-warning">{error}</div>}

      <section className="audit-ledger">
        <div className="audit-ledger-head"><div><History size={15} /><strong>Event ledger</strong></div><span>{filtered.length} of {events.length} events</span></div>
        {loading ? <div className="audit-empty"><RefreshCw className="spin" /><p>Loading immutable audit records…</p></div>
          : filtered.length === 0 ? <div className="audit-empty"><LockKeyhole /><h3>No matching audit events</h3><p>Adjust the filters or perform an auditable operation.</p></div>
          : <div className="audit-table-wrap"><table className="audit-table">
            <thead><tr><th>Time</th><th>Event</th><th>Actor</th><th>Resource</th><th>Origin</th><th>Status</th><th></th></tr></thead>
            <tbody>{filtered.map(event => {
              const open = expanded === event.id;
              return <FragmentRow key={event.id} event={event} open={open} onToggle={() => setExpanded(open ? null : event.id)} />;
            })}</tbody>
          </table></div>}
      </section>
    </div>
  );
}

function FragmentRow({ event, open, onToggle }: { event: AuditEvent; open: boolean; onToggle: () => void }) {
  return <>
    <tr className={open ? 'expanded' : ''}>
      <td><div className="audit-time"><Clock3 size={12} /><span>{new Date(event.createdAt).toLocaleDateString()}</span><small>{new Date(event.createdAt).toLocaleTimeString()}</small></div></td>
      <td><div className="audit-event"><span className={`category-dot ${event.category.toLowerCase()}`}>{event.category === 'PACKAGE' ? <Package size={12} /> : null}</span><div><strong>{title(event.action)}</strong><small>{title(event.category)}</small></div></div></td>
      <td><div className="audit-actor"><UserRound size={13} /><span>{event.actor || 'system'}</span></div></td>
      <td><div className="audit-resource"><strong>{title(event.resourceType || 'SYSTEM')}</strong><small>{event.resourceId || event.clusterId || 'platform'}</small></div></td>
      <td><div className="audit-origin"><Network size={13} /><span>{event.ipAddress || 'internal'}</span><small>{event.source}</small></div></td>
      <td><span className={`audit-status ${event.status.toLowerCase()}`}>{event.status}</span></td>
      <td><button className="expand-button" onClick={onToggle} aria-label={`${open ? 'Collapse' : 'Expand'} ${event.action}`}>{open ? <ChevronUp size={15} /> : <ChevronDown size={15} />}</button></td>
    </tr>
    {open && <tr className="audit-detail-row"><td colSpan={7}>
      <div className="audit-detail-grid">
        <DetailPanel title="Old value" value={event.oldValue} tone="old" />
        <DetailPanel title="New value" value={event.newValue} tone="new" />
        <DetailPanel title="Approval" value={event.approval} tone="approval" />
        <DetailPanel title="Context" value={event.details} tone="context" />
      </div>
      <div className="audit-chain">
        <span><strong>Event ID</strong> {event.id}</span><span><strong>Request ID</strong> {event.requestId || 'not supplied'}</span>
        <span><strong>Previous hash</strong> <code>{shortHash(event.previousHash)}</code></span><span><strong>Record hash</strong> <code>{shortHash(event.recordHash)}</code></span>
      </div>
    </td></tr>}
  </>;
}

function DetailPanel({ title: panelTitle, value, tone }: { title: string; value: unknown; tone: string }) {
  return <article className={`audit-detail-panel ${tone}`}><h4>{panelTitle}</h4><pre>{displayJson(value)}</pre></article>;
}
