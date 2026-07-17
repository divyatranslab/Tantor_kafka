import { useEffect, useMemo, useState } from 'react';
import {
  CheckCircle2, FileClock,
  History, Info, Package, Search, XCircle, Database,
  ChevronLeft, ChevronRight
} from 'lucide-react';
import './AuditLogs.css';

const CustomRefreshIcon = ({ size = 20, color = "#818181", className = "" }: { size?: number, color?: string, className?: string }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="1.25" strokeLinecap="round" strokeLinejoin="round" className={className}>
    <path d="M 12 5 A 7 7 0 0 1 17 17" />
    <path d="M 18 13 L 17 17 L 21 16" />
    <path d="M 12 19 A 7 7 0 0 1 7 7" />
    <path d="M 6 11 L 7 7 L 3 8" />
  </svg>
);

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

interface CustomDropdownProps {
  value: string;
  options: { label: string; value: string }[];
  onChange: (val: string) => void;
}

function CustomDropdown({ value, options, onChange }: CustomDropdownProps) {
  const [isOpen, setIsOpen] = useState(false);
  const selectedOption = options.find(opt => opt.value === value) || options[0];

  useEffect(() => {
    if (!isOpen) return;
    const handleClose = () => setIsOpen(false);
    window.addEventListener('click', handleClose);
    return () => window.removeEventListener('click', handleClose);
  }, [isOpen]);

  return (
    <div className="custom-select-wrapper" onClick={e => e.stopPropagation()}>
      <div
        className={`custom-select-trigger ${isOpen ? 'open' : ''}`}
        onClick={() => setIsOpen(prev => !prev)}
      >
        <span>{selectedOption ? selectedOption.label : value}</span>
        <span className="custom-select-arrow">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#707070" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <polyline points="6 9 12 15 18 9"></polyline>
          </svg>
        </span>
      </div>
      {isOpen && (
        <div className="custom-select-options">
          {options.map(opt => (
            <div
              key={opt.value}
              className={`custom-select-option ${opt.value === value ? 'selected' : ''}`}
              onClick={() => {
                onChange(opt.value);
                setIsOpen(false);
              }}
            >
              {opt.label}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export function AuditLogs() {
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // Draft filter state
  const [search, setSearch] = useState('');
  const [resourceId, setResourceId] = useState('');
  const [category, setCategory] = useState('ALL');
  const [status, setStatus] = useState('ALL');
  const [actor, setActor] = useState('ALL');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');

  // Applied filter state
  const [appliedFilters, setAppliedFilters] = useState({
    search: '', resourceId: '', category: 'ALL', status: 'ALL', actor: 'ALL', from: '', to: ''
  });

  // Pagination state
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);

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
    if (appliedFilters.category !== 'ALL' && event.category !== appliedFilters.category) return false;
    if (appliedFilters.status !== 'ALL' && event.status !== appliedFilters.status) return false;
    if (appliedFilters.actor !== 'ALL' && actorOf(event) !== appliedFilters.actor) return false;
    if (appliedFilters.resourceId.trim()) {
      const needle = appliedFilters.resourceId.trim().toLowerCase();
      const resourceHaystack = [event.displayResourceId, event.kafkaClusterId, event.resourceId, event.resource, event.hostId, event.hostName, event.clusterId]
        .join(' ')
        .toLowerCase();
      if (!resourceHaystack.includes(needle)) return false;
    }
    const created = new Date(timeOf(event)).getTime();
    if (appliedFilters.from && created < new Date(appliedFilters.from).getTime()) return false;
    if (appliedFilters.to && created > new Date(appliedFilters.to).getTime()) return false;
    const haystack = [event.action, actionLabel(event), actorOf(event), event.resourceType, event.displayResourceId, event.kafkaClusterId, event.resourceId, event.resource, event.hostName, event.clusterId, detailLabel(event)].join(' ').toLowerCase();
    return !appliedFilters.search.trim() || haystack.includes(appliedFilters.search.trim().toLowerCase());
  }), [events, appliedFilters]);

  // Reset pagination when filters change
  useEffect(() => {
    setCurrentPage(1);
  }, [filtered.length]);

  const paginatedEvents = useMemo(() => {
    const start = (currentPage - 1) * pageSize;
    return filtered.slice(start, start + pageSize);
  }, [filtered, currentPage, pageSize]);

  const applyFilters = () => {
    setAppliedFilters({ search, resourceId, category, status, actor, from, to });
  };

  const resetFilters = () => {
    setSearch(''); setResourceId(''); setCategory('ALL'); setStatus('ALL'); setActor('ALL'); setFrom(''); setTo('');
    setAppliedFilters({ search: '', resourceId: '', category: 'ALL', status: 'ALL', actor: 'ALL', from: '', to: '' });
  };

  const summary = useMemo(() => ({
    total: events.length,
    success: events.filter(event => event.status === 'SUCCESS').length,
    failed: events.filter(event => event.status === 'FAILED').length,
  }), [events]);

  const totalResults = filtered.length;
  const startResult = totalResults === 0 ? 0 : (currentPage - 1) * pageSize + 1;
  const endResult = Math.min(currentPage * pageSize, totalResults);

  return <div className="audit-page animate-fade-in">
    <header className="audit-header">
      <div>
        <h1>Audit Trail</h1>
        <p>Who performed each action, on which resource, and whether it succeeded.</p>
      </div>
      <button className="btn btn-icon-only" onClick={fetchLogs} disabled={loading} title="Refresh">
        <CustomRefreshIcon size={20} color="#818181" className={loading ? 'spin' : ''} />
      </button>
    </header>

    <div className="audit-summary-wrapper">
      <section className="audit-summary-grid">
        <article className="captured">
          <div className="card-header">
            <div className="icon-wrap"><FileClock size={20} /></div>
            <span>Captured Events</span>
          </div>
          <strong>{summary.total}</strong>
        </article>
        <article className="success">
          <div className="card-header">
            <div className="icon-wrap"><CheckCircle2 size={20} /></div>
            <span>Successful</span>
          </div>
          <strong>{summary.success}</strong>
        </article>
        <article className="failed">
          <div className="card-header">
            <div className="icon-wrap"><XCircle size={20} /></div>
            <span>Failed</span>
          </div>
          <strong>{summary.failed}</strong>
        </article>
      </section>
    </div>

    <section className="audit-readonly-note">
      <Info size={16} />
      <div>
        <strong>Read-only audit history</strong>
        <span>This screen has no edit or delete controls. Every application action creates a separate record.</span>
      </div>
    </section>

    <section className="audit-filters-container">
      <h3 className="section-heading">Audit Log Filters</h3>
      <div className="audit-filters-row-1">
        <label className="audit-search">
          <Search size={14} />
          <input placeholder="Search configs..." value={search} onChange={e => setSearch(e.target.value)} />
        </label>
        <label className="audit-resource-id">
          <Database size={14} />
          <input placeholder="Resource ID" value={resourceId} onChange={e => setResourceId(e.target.value)} />
        </label>
        <div className="audit-filters-actions">
          <button type="button" className="btn-refresh" onClick={fetchLogs} disabled={loading} title="Refresh">
            <CustomRefreshIcon size={20} className={loading ? 'spin' : ''} />
          </button>
          <button className="btn-reset" onClick={resetFilters}>Reset</button>
          <button className="btn-apply" onClick={applyFilters}>Apply Filter</button>
        </div>
      </div>
      <div className="audit-filters-row-2">
        <div className="filter-group">
          <label>Event</label>
          <CustomDropdown
            value={category}
            options={[
              { label: 'All Event', value: 'ALL' },
              ...categories.map(item => ({ label: title(item), value: item }))
            ]}
            onChange={setCategory}
          />
        </div>
        <div className="filter-group">
          <label>Status</label>
          <CustomDropdown
            value={status}
            options={[
              { label: 'All', value: 'ALL' },
              { label: 'SUCCESS', value: 'SUCCESS' },
              { label: 'FAILED', value: 'FAILED' },
              { label: 'ATTEMPTED', value: 'ATTEMPTED' },
              { label: 'SCHEDULED', value: 'SCHEDULED' },
              { label: 'REQUESTED', value: 'REQUESTED' }
            ]}
            onChange={setStatus}
          />
        </div>
        <div className="filter-group">
          <label>Actors</label>
          <CustomDropdown
            value={actor}
            options={[
              { label: 'All', value: 'ALL' },
              ...actors.map(item => ({ label: item, value: item }))
            ]}
            onChange={setActor}
          />
        </div>
        <div className="filter-group">
          <label>From</label>
          <div>
            <input type="date" value={from} onChange={e => setFrom(e.target.value)} />
          </div>
        </div>
        <div className="filter-group">
          <label>To</label>
          <div>
            <input type="date" value={to} onChange={e => setTo(e.target.value)} />
          </div>
        </div>
      </div>
    </section>

    {error && <div className="audit-warning">{error}</div>}
    <section className="audit-ledger">
      <div className="audit-ledger-head">
        <div><History size={15} /><strong>Event Ledger</strong></div>
      </div>
      {loading ? <div className="audit-empty"><CustomRefreshIcon className="spin" /><p>Loading audit records...</p></div>
        : filtered.length === 0 ? <div className="audit-empty"><Info size={24} /><h3>No matching audit events</h3><p>Adjust the filters or perform an auditable operation.</p></div>
          : <div className="audit-table-wrap">
            <table className="audit-table">
              <thead><tr><th>Time</th><th>Event</th><th>Actor</th><th>Resource</th><th>Cluster / Artifact / Host ID</th><th>Details</th><th>Status</th></tr></thead>
              <tbody>{paginatedEvents.map(event => <AuditRow key={event.id} event={event} />)}</tbody>
            </table>
            <div className="audit-pagination">
              <span className="pagination-info">{startResult} to {endResult} of results</span>
              <div className="pagination-controls">
                <span>Show per page</span>
                <select value={pageSize} onChange={e => { setPageSize(Number(e.target.value)); setCurrentPage(1); }}>
                  <option value={10}>10</option>
                  <option value={20}>20</option>
                  <option value={50}>50</option>
                </select>
                <button className="page-btn" disabled={currentPage === 1} onClick={() => setCurrentPage(p => p - 1)}><ChevronLeft size={14} /></button>
                <button className="page-btn" disabled={currentPage * pageSize >= totalResults} onClick={() => setCurrentPage(p => p + 1)}><ChevronRight size={14} /></button>
              </div>
            </div>
          </div>}
    </section>
  </div>;
}

function AuditRow({ event }: { event: AuditEvent }) {
  const created = timeOf(event);
  const createdDate = created ? new Date(created) : null;
  const details = detailLabel(event);

  const formattedTime = createdDate && !Number.isNaN(createdDate.getTime())
    ? `${String(createdDate.getDate()).padStart(2, '0')}/${String(createdDate.getMonth() + 1).padStart(2, '0')}/${createdDate.getFullYear()} | ${createdDate.toLocaleTimeString('en-GB', { hour12: false })}`
    : '-';

  return <tr>
    <td><div className="audit-time"><span>{formattedTime}</span></div></td>
    <td><div className="audit-event"><span className={`category-dot ${event.category.toLowerCase()}`}>{event.category === 'PACKAGE' ? <Package size={12} /> : null}</span><div><strong>{actionLabel(event)}</strong></div></div></td>
    <td><div className="audit-actor"><span>{actorOf(event)}</span></div></td>
    <td><div className="audit-resource"><strong>{resourceTypeLabel(event)}</strong>{resourceName(event) && <small>{resourceName(event)}</small>}</div></td>
    <td><div className="audit-resource"><small title={event.clusterId ? `Internal UUID: ${event.clusterId}` : undefined}>{scopeId(event)}</small></div></td>
    <td><div className="audit-details-inline"><span title={details}>{details || '-'}</span></div></td>
    <td><span className={`audit-status ${event.status.toLowerCase()}`}>{event.status.charAt(0).toUpperCase() + event.status.slice(1).toLowerCase()}</span></td>
  </tr>
}
