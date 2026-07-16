import { useEffect, useMemo, useState } from 'react';
import {
  CheckCircle2, Clock3, FileClock, Filter,
  History, LockKeyhole, Package, RefreshCw, Search, UserRound, XCircle,
} from 'lucide-react';
import './AuditLogs.css';

interface AuditEvent {
  id: string;
  userName?: string;
  actor: string;
  category: string;
  action: string;
  resourceType: string;
  resourceId?: string;
  resource?: string;
  clusterId?: string;
  kafkaClusterId?: string;
  displayResourceId?: string;
  hostId?: string;
  hostIp?: string;
  hostName?: string;
  status: string;
  details?: unknown;
  createdAt?: string;
  createdTime?: string;
}

interface AuditResponse { events?: AuditEvent[] }

const parseJson = (value: unknown): unknown => {
  if (typeof value !== 'string' || !value.trim()) return value;
  try { return JSON.parse(value); } catch { return value; }
};

const title = (value: string) => value.replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, letter => letter.toUpperCase());
const actorOf = (event: AuditEvent) => event.actor || event.userName || 'system';
const timeOf = (event: AuditEvent) => event.createdAt || event.createdTime || '';

const normalized = (value?: string) => (value || '').toUpperCase();

const isArtifactEvent = (event: AuditEvent) => {
  const resourceType = normalized(event.resourceType);
  return resourceType === 'ARTIFACT' || resourceType === 'HOST_PARCEL' || normalized(event.category) === 'PACKAGE';
};

const isHostOnboardingEvent = (event: AuditEvent) => {
  const action = normalized(event.action);
  const category = normalized(event.category);
  return action.includes('ONBOARDING') || action.includes('REGISTER') || category === 'AGENT';
};

const isNoisyAuditEvent = (event: AuditEvent) => {
  const action = normalized(event.action);
  const resourceType = normalized(event.resourceType);
  return resourceType === 'JOB'
    || action === 'CLUSTER_CREATED'
    || action === 'CLUSTER_STATUS_CHANGED'
    || action === 'KAFKA_NODE_DEPLOYED'
    || action === 'KAFKA_NODE_DEPLOYMENT_FAILED'
    || action.endsWith('_REQUESTED');
};

const actionLabel = (event: AuditEvent) => {
  const action = normalized(event.action);
  if (action.includes('ONBOARDING') && normalized(event.status) === 'SUCCESS') return 'Host Registered';
  if (action.includes('ONBOARDING')) return 'Host Onboarding Requested';
  if (action === 'REGISTER' || action === 'HOST_REGISTERED') return 'Host Registered';
  return title(event.action || 'Captured');
};

const resourceTypeLabel = (event: AuditEvent) => {
  if (isHostOnboardingEvent(event)) return 'Agent';
  if (isArtifactEvent(event)) return 'Artifact';
  return title(event.resourceType || 'SYSTEM');
};

const resourceName = (event: AuditEvent) => {
  if (isHostOnboardingEvent(event) || isArtifactEvent(event)) return '';
  return event.resource || event.hostName || event.hostId || event.displayResourceId || event.kafkaClusterId || 'platform';
};

const scopeId = (event: AuditEvent) => event.displayResourceId || event.kafkaClusterId || event.resourceId || event.hostId || '-';

const detailLabel = (event: AuditEvent) => {
  const parsed = parseJson(event.details);
  if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
    const record = parsed as Record<string, unknown>;
    if (isArtifactEvent(event)) {
      const fileName = record.fileName || record.binaryFileName || record.name;
      const version = record.version;
      const validation = record.validationStatus || record.status || event.status;
      return [fileName, version, validation].filter(Boolean).map(String).join(' / ');
    }
    if (normalized(event.resourceType) === 'CLUSTER') {
      const version = record.kafkaVersion || record.version;
      const mode = record.kafkaMode || record.mode;
      const bootstrap = record.bootstrapServers || record.bootstrap;
      const listeners = record.listeners || record.advertisedListeners || record.processRoles;
      const status = record.status || event.status;
      return [version, mode, bootstrap, listeners, status].filter(Boolean).map(String).join(' / ');
    }
    const availability = record.availability || record.available;
    if (typeof availability === 'string') return title(availability);
    if (typeof availability === 'boolean') return availability ? 'Available' : 'Unavailable';
    const status = record.status || record.result || record.validationStatus;
    if (typeof status === 'string') {
      if (['AVAILABLE', 'ONLINE', 'SUCCESS'].includes(status.toUpperCase())) return 'Available';
      if (['UNAVAILABLE', 'OCCUPIED', 'PENDING', 'OFFLINE', 'FAILED', 'REMOVED', 'DELETED'].includes(status.toUpperCase())) return 'Unavailable';
    }
  }
  if (['AVAILABLE', 'ONLINE', 'SUCCESS'].includes(event.status.toUpperCase())) return 'Available';
  if (['UNAVAILABLE', 'OCCUPIED', 'PENDING', 'OFFLINE', 'FAILED', 'REMOVED', 'DELETED'].includes(event.status.toUpperCase())) return 'Unavailable';
  return title(event.status || 'Captured');
};

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
        .filter(event => !isNoisyAuditEvent(event))
        .sort((left, right) => new Date(timeOf(right)).getTime() - new Date(timeOf(left)).getTime());
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
  const actors = useMemo(() => Array.from(new Set(events.map(actorOf).filter(Boolean))).sort(), [events]);
  const filtered = useMemo(() => events.filter(event => {
    if (category !== 'ALL' && event.category !== category) return false;
    if (status !== 'ALL' && event.status !== status) return false;
    if (actor !== 'ALL' && actorOf(event) !== actor) return false;
    if (resourceId.trim()) {
      const needle = resourceId.trim().toLowerCase();
    const resourceHaystack = [event.displayResourceId, event.kafkaClusterId, event.resourceId, event.resource, event.hostId, event.hostName, event.clusterId]
        .join(' ')
        .toLowerCase();
      if (!resourceHaystack.includes(needle)) return false;
    }
    const created = new Date(timeOf(event)).getTime();
    if (from && created < new Date(from).getTime()) return false;
    if (to && created > new Date(to).getTime()) return false;
    const haystack = [event.action, actionLabel(event), actorOf(event), event.resourceType, event.displayResourceId, event.kafkaClusterId, event.resourceId, event.resource, event.hostName, event.clusterId, detailLabel(event)].join(' ').toLowerCase();
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
      <label className="audit-search"><Search size={14} /><input placeholder="Search user, action, resource or details" value={search} onChange={event => setSearch(event.target.value)} /></label>
      <label><input placeholder="Resource" value={resourceId} onChange={event => setResourceId(event.target.value)} /></label>
      <label><Filter size={13} /><select value={category} onChange={event => setCategory(event.target.value)}><option value="ALL">All events</option>{categories.map(item => <option key={item} value={item}>{title(item)}</option>)}</select></label>
      <label><select value={status} onChange={event => setStatus(event.target.value)}><option value="ALL">All statuses</option><option>SUCCESS</option><option>FAILED</option><option>REQUESTED</option></select></label>
      <label><UserRound size={13} /><select value={actor} onChange={event => setActor(event.target.value)}><option value="ALL">All users</option>{actors.map(item => <option key={item}>{item}</option>)}</select></label>
      <label className="date-filter"><span>From</span><input type="datetime-local" value={from} onChange={event => setFrom(event.target.value)} /></label>
      <label className="date-filter"><span>To</span><input type="datetime-local" value={to} onChange={event => setTo(event.target.value)} /></label>
    </section>

    {error && <div className="audit-warning">{error}</div>}
    <section className="audit-ledger">
      <div className="audit-ledger-head"><div><History size={15} /><strong>Event ledger</strong></div><span>{filtered.length} of {events.length} events</span></div>
      {loading ? <div className="audit-empty"><RefreshCw className="spin" /><p>Loading audit records...</p></div>
        : filtered.length === 0 ? <div className="audit-empty"><LockKeyhole /><h3>No matching audit events</h3><p>Adjust the filters or perform an auditable operation.</p></div>
        : <div className="audit-table-wrap"><table className="audit-table"><thead><tr><th>Time</th><th>Event</th><th>User</th><th>Resource</th><th>Cluster / Artifact / Host ID</th><th>Details</th><th>Status</th></tr></thead>
          <tbody>{filtered.map(event => <AuditRow key={event.id} event={event} />)}</tbody>
        </table></div>}
    </section>
  </div>;
}

function AuditRow({ event }: { event: AuditEvent }) {
  const created = timeOf(event);
  const createdDate = created ? new Date(created) : null;
  const details = detailLabel(event);
  return <tr>
      <td><div className="audit-time"><Clock3 size={12} /><span>{createdDate && !Number.isNaN(createdDate.getTime()) ? createdDate.toLocaleDateString() : '-'}</span><small>{createdDate && !Number.isNaN(createdDate.getTime()) ? createdDate.toLocaleTimeString() : ''}</small></div></td>
      <td><div className="audit-event"><span className={`category-dot ${event.category.toLowerCase()}`}>{event.category === 'PACKAGE' ? <Package size={12} /> : null}</span><div><strong>{actionLabel(event)}</strong><small>{title(event.category)}</small></div></div></td>
      <td><div className="audit-actor"><UserRound size={13} /><span>{actorOf(event)}</span></div></td>
      <td><div className="audit-resource"><strong>{resourceTypeLabel(event)}</strong>{resourceName(event) && <small>{resourceName(event)}</small>}</div></td>
      <td><div className="audit-resource"><small title={event.clusterId ? `Internal UUID: ${event.clusterId}` : undefined}>{scopeId(event)}</small></div></td>
      <td><div className="audit-details-inline"><span title={details}>{details || '-'}</span></div></td>
      <td><span className={`audit-status ${event.status.toLowerCase()}`}>{event.status}</span></td>
    </tr>
}
