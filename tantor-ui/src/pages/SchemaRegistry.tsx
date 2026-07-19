import { useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { ChevronRight, Edit3, FileDown, FileText, GitCompare, MoreVertical, Plus, RefreshCw, Save, Settings, Trash2, X, AlertOctagon, Copy } from 'lucide-react';
import { usePermissions } from '../hooks/usePermissions';
import orangeBanner from '../assets/orange.png';
import './DataServiceTabs.css';

interface SchemaSubject {
  subject: string;
  type: string;
  version: number;
  id: number | null;
  schemaType: string;
  schema: string;
}

interface SchemaVersion {
  version: number;
  id: number | null;
  schemaType: string;
  schema: string;
}

interface SubjectDetails {
  subject: string;
  latest: SchemaVersion;
  versions: SchemaVersion[];
  compatibility: string;
}

interface SchemaSummary {
  connection: string;
  subjects: SchemaSubject[];
  totalSubjects: number;
  keySubjects: number;
  valueSubjects: number;
}

type CertificateType = 'PEM' | 'PKCS12';

interface SavedConnection {
  id: string;
  connectionName: string;
  protocol: string;
  host: string;
  port: number;
  status: string;
  isDefault: boolean;
  certificateConfigured: boolean;
  truststoreConfigured: boolean;
  certificateType?: CertificateType;
}

const emptySchema = `{
  "type": "record",
  "name": "Example",
  "fields": [
    { "name": "id", "type": "string" }
  ]
}`;

const compatibilityOptions = [
  'BACKWARD',
  'BACKWARD_TRANSITIVE',
  'FORWARD',
  'FORWARD_TRANSITIVE',
  'FULL',
  'FULL_TRANSITIVE'
];

/**
 * Schema Registry returns JSON-based schemas as an escaped string. Format those
 * schemas for display while leaving formats such as Protobuf untouched.
 */
const formatSchema = (schema: unknown, fallback = '{}'): string => {
  if (schema === null || schema === undefined || schema === '') return fallback;

  const source = typeof schema === 'string' ? schema : JSON.stringify(schema);

  try {
    const parsed = JSON.parse(source);

    // Some responses contain a JSON document encoded inside another JSON string.
    if (typeof parsed === 'string') {
      try {
        return JSON.stringify(JSON.parse(parsed), null, 2);
      } catch {
        return parsed;
      }
    }

    return JSON.stringify(parsed, null, 2);
  } catch {
    return source;
  }
};

interface CustomSelectProps {
  value: string;
  onChange: (val: string) => void;
  options: { value: string; label: string }[];
  placeholder?: string;
  disabled?: boolean;
  className?: string;
}

function CustomSelect({ value, onChange, options, placeholder, disabled, className }: CustomSelectProps) {
  const [isOpen, setIsOpen] = useState(false);
  const selectedOption = options.find(o => o.value === value);

  return (
    <div className={`ds-custom-select-container ${className || ''} ${disabled ? 'disabled' : ''}`}>
      <div 
        className="ds-custom-select-trigger" 
        onClick={() => !disabled && setIsOpen(!isOpen)}
      >
        <span>{selectedOption ? selectedOption.label : placeholder || 'Select...'}</span>
        <svg className={`ds-custom-select-arrow ${isOpen ? 'open' : ''}`} xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#A1A1AA" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m6 9 6 6 6-6"/></svg>
      </div>
      
      {isOpen && (
        <>
          <div className="ds-custom-select-overlay" onClick={() => setIsOpen(false)} />
          <div className="ds-custom-select-dropdown">
            {options.map(opt => (
              <div
                key={opt.value}
                className={`ds-custom-select-option ${opt.value === value ? 'selected' : ''}`}
                onClick={() => {
                  onChange(opt.value);
                  setIsOpen(false);
                }}
              >
                {opt.label}
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

type View = 'list' | 'detail' | 'edit';

export function SchemaRegistry() {
  const { id } = useParams<{ id: string }>();
  const { canManage } = usePermissions();
  const [view, setView] = useState<View>('list');
  const [summary, setSummary] = useState<SchemaSummary | null>(null);
  const [loading, setLoading] = useState(false);
  const [hasFetched, setHasFetched] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showConnection, setShowConnection] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [subjectToDelete, setSubjectToDelete] = useState<string | null>(null);
  const [selected, setSelected] = useState<SchemaSubject | null>(null);
  const [details, setDetails] = useState<SubjectDetails | null>(null);
  const [loadingDetails, setLoadingDetails] = useState(false);
  const [showCompare, setShowCompare] = useState(false);
  const [compareVersionA, setCompareVersionA] = useState<number | null>(null);
  const [compareVersionB, setCompareVersionB] = useState<number | null>(null);
  const [expandedVersions, setExpandedVersions] = useState<Set<number>>(new Set());
  const [copiedText, setCopiedText] = useState<string | null>(null);

  const handleCopy = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedText(text);
      setTimeout(() => setCopiedText(null), 2000);
    } catch (err) {
      console.error('Failed to copy text: ', err);
    }
  };

  // Edit form state
  const [editSchemaType, setEditSchemaType] = useState('AVRO');
  const [editCompatibility, setEditCompatibility] = useState('BACKWARD');
  const [newSchema, setNewSchema] = useState(emptySchema);

  // Create schema modal state
  const [showCreate, setShowCreate] = useState(false);
  const [createSubject, setCreateSubject] = useState('');
  const [createSchemaType, setCreateSchemaType] = useState('AVRO');
  const [createSchema, setCreateSchema] = useState(emptySchema);

  // ── Multi-instance state ──────────────────────────────────────────────────
  const [savedConnections, setSavedConnections] = useState<SavedConnection[]>([]);
  const [selectedConnectionId, setSelectedConnectionId] = useState<string | null>(null);

  // Connection form state
  const [formConnectionName, setFormConnectionName] = useState('');
  const [customIp, setCustomIp] = useState('');
  const [customPort, setCustomPort] = useState('');
  const [protocol, setProtocol] = useState('http');
  const [certType, setCertType] = useState<CertificateType>('PEM');
  const [certFile, setCertFile] = useState<File | null>(null);
  const [certFileName, setCertFileName] = useState('');
  const [certPassword, setCertPassword] = useState('');
  const [certPasteMode, setCertPasteMode] = useState(false);
  const [certPasteText, setCertPasteText] = useState('');
  const [formIsDefault, setFormIsDefault] = useState(false);
  /** ID of the connection being edited — set when editing an existing connection. */
  const [editingConnectionId, setEditingConnectionId] = useState<string | null>(null);
  const [globalCompatibility, setGlobalCompatibility] = useState('BACKWARD');
  const [subjectCompatibility, setSubjectCompatibility] = useState('BACKWARD');
  const [connectSaving, setConnectSaving] = useState(false);
  const [connectError, setConnectError] = useState<string | null>(null);

  // Derived: currently selected connection object
  const selectedConn = useMemo(
    () => savedConnections.find(c => c.id === selectedConnectionId) ?? null,
    [savedConnections, selectedConnectionId]
  );

  // ── Cert helpers ──────────────────────────────────────────────

  /**
   * Safely appends ?connectionId=... to any URL using URLSearchParams.
   * Works even if the base URL already contains query params (avoids unsafe string concatenation).
   */
  const withConnId = (url: string, connId: string | null = selectedConnectionId): string => {
    if (!connId) return url;
    const [base, existing] = url.split('?');
    const params = new URLSearchParams(existing || '');
    params.set('connectionId', connId);
    return base + '?' + params.toString();
  };
  const readFileAsBase64 = (file: File): Promise<string> => new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve((reader.result as string).split(',')[1]);
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });

  const readFileAsText = (file: File): Promise<string> => new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ''));
    reader.onerror = reject;
    reader.readAsText(file);
  });


  const safeBase64Encode = (str: string): string => {
    try {
      return btoa(str);
    } catch {
      // fallback for unicode
      return btoa(encodeURIComponent(str).replace(/%([0-9A-F]{2})/g, (_, p1) => String.fromCharCode(parseInt(p1, 16))));
    }
  };

  /** Build certificate data for PUT /connection body. */
  const buildCertData = async (): Promise<string | undefined> => {
    if (certType === 'PEM') {
      if (certPasteMode && certPasteText.trim()) return safeBase64Encode(certPasteText.trim());
      if (!certPasteMode && certFile) return safeBase64Encode(await readFileAsText(certFile));
    } else if (certType === 'PKCS12' && certFile) {
      return await readFileAsBase64(certFile);
    }
    return undefined;
  };

  // ── Data fetching ─────────────────────────────────────────────

  /** Load all saved SR connections for the instance switcher. */
  const loadConnections = async () => {
    try {
      const res = await fetch(`/api/v1/clusters/${id}/data-services/schema-registry/connections`);
      if (!res.ok) return;
      const data: SavedConnection[] = await res.json().catch(() => []);
      setSavedConnections(data);
      if (data.length > 0) {
        const defaultConn = data.find(c => c.isDefault) ?? data[0];
        setSelectedConnectionId(prev => prev ?? defaultConn.id);
      }
    } catch { /* non-fatal */ }
  };

  /** Open connection modal, optionally pre-filling from an existing connection. */
  const openConnectionModal = (conn?: SavedConnection) => {
    if (!canManage) return;
    if (conn) {
      setEditingConnectionId(conn.id);
      setFormConnectionName(conn.connectionName);
      setProtocol(conn.protocol || 'http');
      setCustomIp(conn.host || '');
      setCustomPort(conn.port ? String(conn.port) : '');
      setCertType(conn.certificateType || 'PEM');
      setFormIsDefault(conn.isDefault);
    } else {
      setEditingConnectionId(null);
      setFormConnectionName('');
      setProtocol('http');
      setCustomIp('');
      setCustomPort('');
      setCertType('PEM');
      setFormIsDefault(false);
    }
    setCertFile(null); setCertFileName(''); setCertPasteText(''); setCertPasteMode(false); setCertPassword('');
    setConnectError(null);
    setShowConnection(true);
  };

  const handleSaveConnection = async () => {
    if (!canManage) return;
    setConnectSaving(true);
    setConnectError(null);
    try {
      const certData = await buildCertData();
      const body = {
        connectionName: formConnectionName.trim() || 'Default connection',
        protocol,
        host: customIp.trim(),
        port: parseInt(customPort.trim()) || 8081,
        certificateType: protocol === 'https' ? certType : null,
        certificateData: protocol === 'https' ? certData : null,
        truststorePassword: protocol === 'https' ? (certPassword || null) : null,
        isDefault: formIsDefault
      };

      // Use PUT /connections/{id} when editing existing (prevents rename creating duplicate rows).
      // Use PUT /connection (upsert-by-name) when creating new.
      const url = editingConnectionId
        ? `/api/v1/clusters/${id}/data-services/schema-registry/connections/${editingConnectionId}`
        : `/api/v1/clusters/${id}/data-services/schema-registry/connection`;

      const res = await fetch(url, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'Failed to save connection.');

      setCertPassword('');
      setShowConnection(false);
      await loadConnections();
      if (data.id) setSelectedConnectionId(data.id);
      await load();
    } catch (e: any) {
      setConnectError(e.message || 'Failed to save connection.');
    } finally {
      setConnectSaving(false);
    }
  };

  const handleDeleteConnection = () => {
    if (!canManage || !selectedConnectionId) return;
    setShowDeleteConfirm(true);
  };

  const confirmDeleteConnection = async () => {
    setLoading(true);
    try {
      const res = await fetch(`/api/v1/clusters/${id}/data-services/schema-registry/connections/${selectedConnectionId}`, {
        method: 'DELETE'
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.message || 'Failed to delete connection.');
      }
      setSelectedConnectionId(null);
      await loadConnections();
      await load();
    } catch (e: any) {
      setError(e.message || 'Failed to delete connection.');
      setLoading(false);
    }
  };

  const loadGlobalCompatibility = async () => {
    try {
      const res = await fetch(withConnId(`/api/v1/clusters/${id}/data-services/schema-registry/config`));
      const data = await res.json().catch(() => ({}));
      if (res.ok) setGlobalCompatibility(data.compatibilityLevel || data.compatibility || 'BACKWARD');
    } catch { setGlobalCompatibility('BACKWARD'); }
  };

  const load = async () => {
    setHasFetched(true);
    setLoading(true);
    setError(null);

    // Check if the selected connection matches the exact mock testing credentials
    const isMockConnection = import.meta.env.DEV && selectedConn && 
      selectedConn.connectionName.trim().toLowerCase() === 'test' &&
      selectedConn.protocol === 'http' &&
      selectedConn.host === '127.1.1.1' &&
      selectedConn.port === 8081;

    if (isMockConnection) {
      setSummary({
        connection: selectedConnectionId || 'default',
        subjects: [
          { subject: 'customers_test-value', type: 'VALUE', version: 1, id: 101, schemaType: 'AVRO', schema: '{\n  "type": "record",\n  "name": "Customer",\n  "fields": [\n    { "name": "id", "type": "string" }\n  ]\n}' },
          { subject: 'customers_test_SASL_PLAINTEXT-value', type: 'VALUE', version: 2, id: 102, schemaType: 'AVRO', schema: '{\n  "type": "record",\n  "name": "SASL_Customer",\n  "fields": [\n    { "name": "id", "type": "string" }\n  ]\n}' },
          { subject: 'customers_test_SSL-value', type: 'VALUE', version: 1, id: 103, schemaType: 'AVRO', schema: '{\n  "type": "record",\n  "name": "SSL_Customer",\n  "fields": [\n    { "name": "id", "type": "string" }\n  ]\n}' },
          { subject: 'dep_table-value', type: 'VALUE', version: 1, id: 104, schemaType: 'AVRO', schema: '{\n  "type": "record",\n  "name": "Department",\n  "fields": [\n    { "name": "id", "type": "int" }\n  ]\n}' }
        ],
        totalSubjects: 4,
        keySubjects: 0,
        valueSubjects: 4
      });
      setGlobalCompatibility('BACKWARD');
      setLoading(false);
      return;
    }

    try {
      const res = await fetch(withConnId(`/api/v1/clusters/${id}/data-services/schema-registry/summary`));
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'Failed to load Schema Registry.');
      setSummary(data);
      await loadGlobalCompatibility();
    } catch (e: any) {
      setError(e.message || 'Failed to load Schema Registry.');
    } finally {
      setLoading(false);
    }
  };

  // Initial load
  useEffect(() => { if (id) { loadConnections(); } }, [id]);

  // Live registry data is fetched only after the user explicitly requests it.
  useEffect(() => {
    setHasFetched(false);
    setSummary(null);
    setError(null);
  }, [id, selectedConnectionId]);

  const openSubject = async (item: SchemaSubject) => {
    setSelected(item);
    setView('detail');
    setDetails(null);
    setLoadingDetails(true);
    setError(null);
    setExpandedVersions(new Set());

    // Check if the selected connection matches the exact mock testing credentials
    const isMockConnection = import.meta.env.DEV && selectedConn && 
      selectedConn.connectionName.trim().toLowerCase() === 'test' &&
      selectedConn.protocol === 'http' &&
      selectedConn.host === '127.1.1.1' &&
      selectedConn.port === 8081;

    if (isMockConnection) {
      setDetails({
        subject: item.subject,
        latest: {
          version: 2,
          id: 102,
          schemaType: item.schemaType,
          schema: item.schema
        },
        versions: [
          {
            version: 2,
            id: 102,
            schemaType: item.schemaType,
            schema: item.schema
          },
          {
            version: 1,
            id: 101,
            schemaType: item.schemaType,
            schema: item.schema.replace('"id", "type": "string"', '"id", "type": "int"')
          }
        ],
        compatibility: 'BACKWARD'
      });
      setSubjectCompatibility('BACKWARD');
      setLoadingDetails(false);
      return;
    }

    try {
      const res = await fetch(withConnId(`/api/v1/clusters/${id}/data-services/schema-registry/subjects/${encodeURIComponent(item.subject)}/details`));
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'Failed to load subject details.');
      setDetails(data);
      setSubjectCompatibility(data.compatibility || globalCompatibility);
    } catch (e: any) {
      setDetails({
        subject: item.subject,
        latest: { version: item.version, id: item.id, schemaType: item.schemaType, schema: item.schema },
        versions: [{ version: item.version, id: item.id, schemaType: item.schemaType, schema: item.schema }],
        compatibility: globalCompatibility
      });
      setSubjectCompatibility(globalCompatibility);
      setError(e.message || 'Failed to load subject details.');
    } finally {
      setLoadingDetails(false);
    }
  };

  const openEdit = () => {
    if (!canManage) return;
    const latest = details?.latest;
    if (!selected || !latest) return;
    setEditSchemaType(latest.schemaType || 'AVRO');
    setEditCompatibility(subjectCompatibility);
    setNewSchema(latest.schema || emptySchema);
    setView('edit');
  };

  const backToList = () => {
    setView('list');
    setSelected(null);
    setDetails(null);
    setExpandedVersions(new Set());
    setError(null);
  };

  const backToDetail = () => {
    setView('detail');
    setError(null);
  };

  const openCompare = () => {
    const versions = [...(details?.versions || [])].sort((a, b) => b.version - a.version);
    if (versions.length < 2) return;
    setCompareVersionA(versions[0].version);
    setCompareVersionB(versions[1].version);
    setShowCompare(true);
  };

  const comparedSchemaA = details?.versions.find(v => v.version === compareVersionA);
  const comparedSchemaB = details?.versions.find(v => v.version === compareVersionB);
  const schemasAreIdentical = Boolean(
    comparedSchemaA && comparedSchemaB && comparedSchemaA.schema === comparedSchemaB.schema
  );

  const toggleVersion = (version: number) => {
    setExpandedVersions(prev => {
      const next = new Set(prev);
      next.has(version) ? next.delete(version) : next.add(version);
      return next;
    });
  };

  // ── Actions ───────────────────────────────────────────────────
  const submitCreateSchema = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canManage) return;
    if (!createSubject.trim() || !createSchema.trim()) return;
    setSaving(true);
    setError(null);
    try {
      const res = await fetch(withConnId(`/api/v1/clusters/${id}/data-services/schema-registry/subjects/${encodeURIComponent(createSubject.trim())}/versions`), {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ schemaType: createSchemaType, schema: createSchema })
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'Failed to create schema.');
      setShowCreate(false);
      setCreateSubject('');
      setCreateSchema(emptySchema);
      setCreateSchemaType('AVRO');
      await load();
    } catch (e: any) {
      setError(e.message || 'Failed to create schema.');
    } finally {
      setSaving(false);
    }
  };

  const submitEditSchema = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canManage) return;
    if (!selected || !newSchema.trim()) return;
    setSaving(true);
    setError(null);
    try {
      const res = await fetch(withConnId(`/api/v1/clusters/${id}/data-services/schema-registry/subjects/${encodeURIComponent(selected.subject)}/versions`), {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ schemaType: editSchemaType, schema: newSchema })
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'Failed to update schema.');
      // Also save compatibility if changed
      if (editCompatibility !== subjectCompatibility) {
        await fetch(withConnId(`/api/v1/clusters/${id}/data-services/schema-registry/subjects/${encodeURIComponent(selected.subject)}/config`), {
          method: 'PUT', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ compatibility: editCompatibility })
        });
        setSubjectCompatibility(editCompatibility);
      }
      await load();
      // Reload detail
      const refreshed = summary?.subjects.find(s => s.subject === selected.subject) || selected;
      await openSubject(refreshed);
    } catch (e: any) {
      setError(e.message || 'Failed to update schema.');
    } finally {
      setSaving(false);
    }
  };

  const deleteSubject = (name: string) => {
    if (!canManage) return;
    setSubjectToDelete(name);
  };

  const confirmDeleteSubject = async (name: string) => {
    setSaving(true);
    setError(null);
    try {
      const res = await fetch(withConnId(`/api/v1/clusters/${id}/data-services/schema-registry/subjects/${encodeURIComponent(name)}`), {
        method: 'DELETE'
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'Failed to delete subject.');
      backToList();
      await load();
    } catch (e: any) {
      setError(e.message || 'Failed to delete subject.');
    } finally {
      setSaving(false);
    }
  };

  const saveGlobalCompatibility = async (val: string) => {
    setGlobalCompatibility(val);
    if (!canManage) return;
    setSaving(true); setError(null);
    try {
      const res = await fetch(withConnId(`/api/v1/clusters/${id}/data-services/schema-registry/config`), {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ compatibility: val })
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'Failed to update global compatibility.');
    } catch (e: any) {
      setError(e.message || 'Failed to update global compatibility.');
    } finally { setSaving(false); }
  };

  const saveSubjectCompatibility = async () => {
    if (!canManage) return;
    if (!selected) return;
    setSaving(true); setError(null);
    try {
      const res = await fetch(withConnId(`/api/v1/clusters/${id}/data-services/schema-registry/subjects/${encodeURIComponent(selected.subject)}/config`), {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ compatibility: subjectCompatibility })
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'Failed to update subject compatibility.');
      setDetails(prev => prev ? { ...prev, compatibility: subjectCompatibility } : prev);
    } catch (e: any) {
      setError(e.message || 'Failed to update subject compatibility.');
    } finally { setSaving(false); }
  };

  const connStatusColor = (s: string) =>
    s === 'ONLINE' ? '#80e8a2' : (s === 'OFFLINE' || s === 'ERROR') ? '#e88080' : '#a8c5c0';

  // ── Render ────────────────────────────────────────────────────
  return (
    <div className="data-services-page animate-fade-in">

      {/* ── LIST VIEW ─────────────────────────────────────────── */}
      {view === 'list' && (
        <>
          <div className="ds-header ds-sr-header">
            <h2 className="cluster-section-heading">Schema Registry</h2>
            <div className="ds-actions">
              <div className="ds-selectors-group">
                {/* ── Instance Selector ── */}
                <div className="ds-compat-control">
                  <span>Instance Selector</span>
                  <CustomSelect
                    className="ds-instance-select"
                    value={selectedConnectionId ?? ''}
                    onChange={val => setSelectedConnectionId(val || null)}
                    disabled={savedConnections.length === 0}
                    options={
                      savedConnections.length > 0
                        ? savedConnections.map(c => ({
                            value: c.id,
                            label: `${c.connectionName}${c.isDefault ? ' (default)' : ''}`
                          }))
                        : [{ value: '', label: 'Default connection' }]
                    }
                  />
                  {selectedConn && (
                    <span
                      className="ds-instance-status-dot"
                      style={{
                        display: 'inline-block',
                        width: 8,
                        height: 8,
                        borderRadius: '50%',
                        background: connStatusColor(selectedConn.status),
                        marginLeft: 4
                      }}
                      title={selectedConn.status}
                    />
                  )}
                </div>

                {/* ── Global Compatibility Selector ── */}
                <div className="ds-compat-control" style={{ display: 'inline-flex', alignItems: 'center' }}>
                  <span>Global Compatibility Selector</span>
                  <CustomSelect
                    className="ds-compat-select"
                    value={globalCompatibility}
                    onChange={saveGlobalCompatibility}
                    disabled={!canManage}
                    options={compatibilityOptions.map(o => ({ value: o, label: o }))}
                  />
                  {canManage && (
                    <button className="ds-icon-button" onClick={() => saveGlobalCompatibility(globalCompatibility)} disabled={saving} title="Save global compatibility" style={{ border: '1px solid #dedbd4', borderRadius: '8px', width: '38px', height: '38px', minWidth: '38px', marginLeft: '6px' }}>
                      <FileDown size={16} />
                    </button>
                  )}
                </div>
              </div>

              <div className="ds-buttons-group">
                {/* ── Buttons ── */}
                {canManage && (
                  <button className="ds-button add-connection" onClick={() => openConnectionModal()}>
                    <Settings size={16} /> Add Connection
                  </button>
                )}

                {canManage && (
                  <button className="ds-button create-schema" onClick={() => setShowCreate(true)}>
                    <Plus size={16} /> Create Schema
                  </button>
                )}

                {canManage && (
                  <button className="ds-icon-button icon-gray" onClick={handleDeleteConnection} disabled={!selectedConn} title="Delete connection">
                    <Trash2 size={16} />
                  </button>
                )}

                <button className="ds-icon-button icon-gray" onClick={load} disabled={loading} title="Refresh">
                  <RefreshCw size={16} className={loading ? 'spin' : ''} />
                </button>

                {canManage && (
                  <button className="ds-icon-button no-border" onClick={() => selectedConn && openConnectionModal(selectedConn)} disabled={!selectedConn} title="Edit connection">
                    <MoreVertical size={16} />
                  </button>
                )}
              </div>
            </div>
          </div>

          {error && <div className="ds-alert">{error}</div>}

          {!hasFetched ? (
            <div className="ds-fetch-prompt">
              {/* Stacked Cards Illustration SVG */}
              <svg width="120" height="90" viewBox="0 0 120 90" fill="none" xmlns="http://www.w3.org/2000/svg" style={{ marginBottom: '12px' }}>
                {/* Back Card */}
                <rect x="15" y="10" width="90" height="48" rx="6" fill="#F4F2FF" stroke="#E2E0FD" strokeWidth="1.5" />
                <rect x="25" y="18" width="40" height="4" rx="2" fill="#8F8CFF" />
                <rect x="75" y="18" width="20" height="4" rx="2" fill="#C7C5FF" />
                <rect x="25" y="28" width="55" height="4" rx="2" fill="#E2E0FD" />
                <rect x="25" y="38" width="30" height="4" rx="2" fill="#E2E0FD" />

                {/* Middle Card */}
                <rect x="10" y="24" width="100" height="48" rx="6" fill="#FFFFFF" stroke="#E8E6E1" strokeWidth="1.5" />
                <rect x="20" y="32" width="50" height="4" rx="2" fill="#5747C9" fillOpacity="0.4" />
                <rect x="80" y="32" width="20" height="4" rx="2" fill="#5747C9" fillOpacity="0.2" />
                <rect x="20" y="42" width="65" height="4" rx="2" fill="#F3F0F0" />
                <rect x="20" y="52" width="40" height="4" rx="2" fill="#F3F0F0" />

                {/* Front Card */}
                <rect x="5" y="38" width="110" height="48" rx="6" fill="#FFFFFF" stroke="#3E1363" strokeWidth="1.5" strokeOpacity="0.15" />
                <rect x="15" y="46" width="35" height="4" rx="2" fill="#3E1363" fillOpacity="0.6" />
                <rect x="85" y="46" width="20" height="4" rx="2" fill="#3E1363" fillOpacity="0.3" />
                <rect x="15" y="56" width="80" height="4" rx="2" fill="#F3F0F0" />
                <rect x="15" y="66" width="50" height="4" rx="2" fill="#F3F0F0" />
              </svg>
              <p>Schema Registry data is not loaded automatically.</p>
              <button type="button" className="ds-fetch-link" onClick={load} disabled={loading}>
                {loading ? 'Fetching Schema Registry...' : 'Fetch Schema Registry for this cluster'}
              </button>
            </div>
          ) : <>
          <div className="ds-metrics">
            <div className="ds-metric-card"><span>Total Subjects</span><strong>{summary?.totalSubjects ?? 0}</strong></div>
            <div className="ds-metric-card"><span>Value Subjects</span><strong>{summary?.valueSubjects ?? 0}</strong></div>
            <div className="ds-metric-card"><span>Key Subjects</span><strong>{summary?.keySubjects ?? 0}</strong></div>
            <div className="ds-metric-card"><span>REST Endpoint</span><strong style={{ fontSize: 16 }}>{summary?.connection || '-'}</strong></div>
          </div>

          <div className="ds-panel">
            <table className="ds-table">
              <thead>
                <tr><th>Subject</th><th>Type</th><th>Latest Version</th><th>Schema ID</th><th>Schema Type</th>{canManage && <th>Actions</th>}</tr>
              </thead>
              <tbody>
                {loading && !summary ? (
                  <tr><td colSpan={canManage ? 6 : 5} className="ds-empty">Loading schemas...</td></tr>
                ) : summary && summary.subjects.length > 0 ? (
                  summary.subjects.map(item => (
                    <tr key={item.subject} className="ds-hoverable-row" onClick={() => openSubject(item)} style={{ cursor: 'pointer' }}>
                      <td><span className="ds-link-button">{item.subject}</span></td>
                      <td><span className="ds-status">{item.type}</span></td>
                      <td>{item.version || '-'}</td>
                      <td>{item.id || '-'}</td>
                      <td>{item.schemaType}</td>
                      {canManage && (
                        <td>
                          <div className="ds-inline-actions" onClick={e => e.stopPropagation()}>
                            <button className="ds-table-delete-btn" onClick={() => deleteSubject(item.subject)} disabled={saving}>
                              <Trash2 size={14} /> Delete
                            </button>
                          </div>
                        </td>
                      )}
                    </tr>
                  ))
                ) : (
                  <tr><td colSpan={canManage ? 6 : 5} className="ds-empty">No schemas found in this registry.</td></tr>
                )}
              </tbody>
            </table>
          </div>
          </>}
        </>
      )}

      {/* ── DETAIL VIEW ───────────────────────────────────────── */}
      {view === 'detail' && selected && (
        <>
          {/* Breadcrumb bar */}
          <div className="ds-page-bar">
            <div className="ds-breadcrumb-nav">
              <button className="ds-breadcrumb-link" onClick={backToList}>Schema Registry</button>
              <ChevronRight size={14} className="ds-breadcrumb-sep" />
              <span className="ds-breadcrumb-current">{selected.subject}</span>
            </div>
            <div className="ds-inline-actions">
              <button className="ds-button outlined" onClick={openCompare} disabled={(details?.versions.length || 0) < 2} title="Compare versions">
                <GitCompare size={16} /> Compare Versions
              </button>
              {canManage && (
                <>
                  <button className="ds-button primary" onClick={openEdit} disabled={!details?.latest}>
                    <Edit3 size={16} /> Edit Schema
                  </button>
                  <button className="ds-icon-button" onClick={() => deleteSubject(selected.subject)} disabled={saving} title="Delete subject">
                    <Trash2 size={16} />
                  </button>
                </>
              )}
            </div>
          </div>

          {error && <div className="ds-alert">{error}</div>}

          {/* Actual version */}
          <div className="ds-schema-shell">
            <div className="ds-schema-code-card">
              <div className="ds-schema-card-header">
                <button
                  type="button"
                  className="ds-schema-copy-btn"
                  onClick={() => handleCopy(details?.latest?.schema || selected.schema)}
                  title="Copy schema to clipboard"
                >
                  {copiedText === (details?.latest?.schema || selected.schema) ? (
                    <span className="ds-copied-text">Copied!</span>
                  ) : (
                    <Copy size={16} />
                  )}
                </button>
              </div>
              <pre className="ds-schema-code">
                {loadingDetails ? 'Loading schema...' : formatSchema(details?.latest?.schema || selected.schema)}
              </pre>
            </div>
            <div className="ds-schema-meta-card">
              <div className="ds-schema-card-header" />
              <div className="ds-meta-content">
                <div className="ds-meta-row"><span>Latest version</span><strong>{details?.latest?.version ?? selected.version ?? '-'}</strong></div>
                <div className="ds-meta-row"><span>ID</span><strong>{details?.latest?.id ?? selected.id ?? '-'}</strong></div>
                <div className="ds-meta-row"><span>Type</span><strong>{details?.latest?.schemaType || selected.schemaType || '-'}</strong></div>
                <div className="ds-meta-row ds-compat-row-block">
                  <span>Compatibility</span>
                  <div className="ds-compat-inline-custom">
                    <CustomSelect
                      value={subjectCompatibility}
                      onChange={setSubjectCompatibility}
                      disabled={!canManage}
                      options={compatibilityOptions.map(o => ({ value: o, label: o }))}
                    />
                    {canManage && (
                      <button className="ds-icon-button ds-compat-save-btn" onClick={saveSubjectCompatibility} disabled={saving} title="Save">
                        <FileDown size={16} />
                      </button>
                    )}
                  </div>
                </div>
              </div>
            </div>
          </div>

          {/* Old versions */}
          <h4 className="ds-section-title">Old versions</h4>
          <div className="ds-panel">
            <table className="ds-table">
              <thead>
                <tr>
                  <th style={{ width: 40 }}></th>
                  <th>Version</th>
                  <th>ID</th>
                  <th>Type</th>
                </tr>
              </thead>
              <tbody>
                {(details?.versions || []).map(version => (
                  <>
                    <tr key={version.version} className="ds-hoverable-row" style={{ cursor: 'pointer' }} onClick={() => toggleVersion(version.version)}>
                      <td>
                        <button className="ds-mini-button ds-expand-btn">
                          {expandedVersions.has(version.version) ? '−' : '+'}
                        </button>
                      </td>
                      <td>{version.version}</td>
                      <td>{version.id}</td>
                      <td>{version.schemaType}</td>
                    </tr>
                    {expandedVersions.has(version.version) && (
                      <tr key={`${version.version}-schema`} className="ds-version-expand-row">
                        <td colSpan={4} style={{ padding: '16px 20px' }}>
                          <div className="ds-expanded-schema-container">
                            <div className="ds-schema-card-header">
                              <button
                                type="button"
                                className="ds-schema-copy-btn"
                                onClick={() => handleCopy(version.schema)}
                                title="Copy schema to clipboard"
                              >
                                {copiedText === version.schema ? (
                                  <span className="ds-copied-text">Copied!</span>
                                ) : (
                                  <Copy size={16} />
                                )}
                              </button>
                            </div>
                            <pre className="ds-version-schema-code">{formatSchema(version.schema)}</pre>
                          </div>
                        </td>
                      </tr>
                    )}
                  </>
                ))}
                {!loadingDetails && (!details?.versions || details.versions.length === 0) && (
                  <tr><td colSpan={4} className="ds-empty">No older versions found.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </>
      )}
      {showCompare && details && (
        <div className="ds-modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="compare-versions-title">
          <div className="ds-modal ds-compare-modal animate-fade-in">
            <div className="ds-modal-header" style={{ padding: '0 0 20px 0' }}>
              <div>
                <h3 id="compare-versions-title" style={{ fontSize: '18px', fontWeight: 700 }}>Compare Versions</h3>
                <span className="ds-muted-line">{details.subject}</span>
              </div>
              <button type="button" className="ds-close-btn" onClick={() => setShowCompare(false)} title="Close">
                <X size={20} />
              </button>
            </div>
            
            <div className="ds-compare-selectors">
              <div className="ds-field">
                <label>Version A</label>
                <CustomSelect
                  value={compareVersionA ? String(compareVersionA) : ''}
                  onChange={val => setCompareVersionA(Number(val))}
                  options={details.versions.map(v => ({
                    value: String(v.version),
                    label: `Version ${v.version} (ID ${v.id ?? 'Unavailable'})`
                  }))}
                />
              </div>
              <div className="ds-field">
                <label>Version B</label>
                <CustomSelect
                  value={compareVersionB ? String(compareVersionB) : ''}
                  onChange={val => setCompareVersionB(Number(val))}
                  options={details.versions.map(v => ({
                    value: String(v.version),
                    label: `Version ${v.version} (ID ${v.id ?? 'Unavailable'})`
                  }))}
                />
              </div>
            </div>

            {compareVersionA === compareVersionB ? (
              <div className="ds-compare-alert warning">Select two different versions to compare.</div>
            ) : (
              <>
                <div className={`ds-compare-alert ${schemasAreIdentical ? 'success' : 'different'}`}>
                  {schemasAreIdentical ? 'The selected schema versions are identical.' : 'The selected schema versions are different.'}
                </div>
                <div className="ds-compare-grid">
                  <div className="ds-compare-card">
                    <div className="ds-schema-card-header">
                      <span>Version {compareVersionA}</span>
                      <span>Schema ID: {comparedSchemaA?.id ?? '-'}</span>
                    </div>
                    <pre className="ds-compare-code">
                      {formatSchema(comparedSchemaA?.schema, 'Schema unavailable')}
                    </pre>
                  </div>
                  <div className="ds-compare-card">
                    <div className="ds-schema-card-header">
                      <span>Version {compareVersionB}</span>
                      <span>Schema ID: {comparedSchemaB?.id ?? '-'}</span>
                    </div>
                    <pre className="ds-compare-code">
                      {formatSchema(comparedSchemaB?.schema, 'Schema unavailable')}
                    </pre>
                  </div>
                </div>
              </>
            )}
          </div>
        </div>
      )}

      {/* ── EDIT VIEW ─────────────────────────────────────────── */}
      {canManage && view === 'edit' && selected && (
        <form onSubmit={submitEditSchema}>
          {/* Breadcrumb bar */}
          <div className="ds-page-bar">
            <div className="ds-breadcrumb-nav">
              <button type="button" className="ds-breadcrumb-link" onClick={backToList}>Schema Registry</button>
              <ChevronRight size={14} className="ds-breadcrumb-sep" />
              <button type="button" className="ds-breadcrumb-link" onClick={backToDetail}>{selected.subject}</button>
              <ChevronRight size={14} className="ds-breadcrumb-sep" />
              <span className="ds-breadcrumb-current">Edit</span>
            </div>
            <button type="submit" className="ds-button primary" disabled={saving}>
              {saving ? <RefreshCw size={16} className="spin" /> : <Save size={16} />} Submit
            </button>
          </div>

          {error && <div className="ds-alert">{error}</div>}

          {/* Type + Compatibility selectors */}
          <div className="ds-edit-controls">
            <div className="ds-field">
              <label>Type</label>
              <CustomSelect
                value={editSchemaType}
                onChange={setEditSchemaType}
                options={[
                  { value: 'AVRO', label: 'Avro' },
                  { value: 'JSON', label: 'Json' },
                  { value: 'PROTOBUF', label: 'Protobuf' }
                ]}
              />
            </div>
            <div className="ds-field">
              <label>Compatibility level</label>
              <CustomSelect
                value={editCompatibility}
                onChange={setEditCompatibility}
                options={compatibilityOptions.map(o => ({
                  value: o,
                  label: o.charAt(0) + o.slice(1).toLowerCase().replace('_', ' ')
                }))}
              />
            </div>
          </div>

          {/* Side-by-side editors */}
          <div className="ds-edit-shell">
            <div className="ds-edit-pane">
              <div className="ds-edit-pane-header">
                <span>Latest schema</span>
                <button
                  type="button"
                  className="ds-schema-copy-btn"
                  onClick={() => handleCopy(details?.latest?.schema || selected.schema || '{}')}
                  title="Copy schema to clipboard"
                >
                  {copiedText === (details?.latest?.schema || selected.schema || '{}') ? (
                    <span className="ds-copied-text">Copied!</span>
                  ) : (
                    <Copy size={16} />
                  )}
                </button>
              </div>
              <pre className="ds-edit-code ds-edit-readonly">
                {details?.latest?.schema || selected.schema || '{}'}
              </pre>
            </div>
            <div className="ds-edit-pane">
              <div className="ds-edit-pane-header">
                <span>New schema</span>
                <button
                  type="button"
                  className="ds-schema-copy-btn"
                  onClick={() => handleCopy(newSchema)}
                  title="Copy schema to clipboard"
                >
                  {copiedText === newSchema ? (
                    <span className="ds-copied-text">Copied!</span>
                  ) : (
                    <Copy size={16} />
                  )}
                </button>
              </div>
              <textarea
                className="ds-edit-code ds-edit-editable"
                value={newSchema}
                onChange={e => setNewSchema(e.target.value)}
                spellCheck={false}
                required
              />
            </div>
          </div>
        </form>
      )}

      {/* ── Connection modal ──────────────────────────────────── */}
      {canManage && showConnection && (
        <div className="ds-modal-backdrop" role="dialog" aria-modal="true">
          <div className="ds-modal ds-connection-modal">
            <div className="ds-modal-header">
              <div>
                <h3>{editingConnectionId ? 'Edit Connection' : 'Add Schema Registry Connection'}</h3>
                <span className="ds-muted-line">{formConnectionName ? formConnectionName : 'New connection'}</span>
              </div>
              <button type="button" className="ds-close-btn" onClick={() => setShowConnection(false)}><X size={20} /></button>
            </div>
            <div className="ds-form ds-compact-form" style={{ padding: 0 }}>
              <div className="ds-modal-inner-card">
                {connectError && <div className="ds-alert" style={{ marginBottom: 12 }}>{connectError}</div>}
                {selectedConn?.status && editingConnectionId && (
                  <div style={{ padding: '10px 14px', background: '#FFFFFF', borderRadius: 8, border: '1px solid #E8E6E1', fontSize: 13, color: '#282F49' }}>
                    Status: <strong style={{ color: connStatusColor(selectedConn.status) }}>{selectedConn.status}</strong>
                    {selectedConn.certificateConfigured && <span style={{ marginLeft: 16 }}>✓ Cert Configured</span>}
                    {selectedConn.truststoreConfigured && <span style={{ marginLeft: 16 }}>✓ Truststore Password Configured</span>}
                  </div>
                )}
                <div className="ds-field">
                  <label>Connection Name</label>
                  <input
                    value={formConnectionName}
                    onChange={e => setFormConnectionName(e.target.value)}
                    placeholder="e.g. Team A Registry"
                    required
                  />
                </div>
              </div>

              <div className="ds-modal-inner-card">
                <div className="ds-form-grid three">
                  <div className="ds-field">
                    <label>Protocol</label>
                    <CustomSelect
                      value={protocol}
                      onChange={setProtocol}
                      options={[
                        { value: 'http', label: 'http://' },
                        { value: 'https', label: 'https://' }
                      ]}
                    />
                  </div>
                  <div className="ds-field"><label>Host / IP</label><input value={customIp} onChange={e => setCustomIp(e.target.value)} placeholder="192.168.3.222" required /></div>
                  <div className="ds-field"><label>Port</label><input type="number" value={customPort} onChange={e => setCustomPort(e.target.value)} placeholder="8081" required /></div>
                </div>
                <div className="ds-form-grid two">
                  <div className="ds-field">
                    <label>Certificate Type</label>
                    <CustomSelect
                      value={certType}
                      onChange={val => {
                        setCertType(val as CertificateType);
                        setCertFile(null);
                        setCertFileName('');
                        setCertPasteText('');
                      }}
                      options={[
                        { value: 'PEM', label: 'PEM (.pem / .crt)' },
                        { value: 'PKCS12', label: 'PKCS12 (.p12 / .pfx)' }
                      ]}
                    />
                  </div>
                  {certType === 'PEM' ? (
                    <div className="ds-field">
                      <label>
                        <span>Certificate</span>
                        <button type="button" className="ds-mini-button" onClick={() => { setCertPasteMode(!certPasteMode); setCertFile(null); setCertFileName(''); setCertPasteText(''); }}>
                          {certPasteMode ? '📎 Upload file' : '📋 Paste text'}
                        </button>
                      </label>
                      {certPasteMode ? (
                        <textarea value={certPasteText} onChange={e => setCertPasteText(e.target.value)}
                          placeholder="-----BEGIN CERTIFICATE-----&#10;MIIDXTCCAkWgAwIBAgIJAMEn...&#10;-----END CERTIFICATE-----"
                          rows={5} style={{ fontFamily: 'monospace', fontSize: 12 }} />
                      ) : (
                        <div className="ds-file-upload-row">
                          <label className="ds-upload-btn-label">
                            <FileText size={16} /> Choose file
                            <input type="file" accept=".pem,.crt,.cer" onChange={e => { const f = e.target.files?.[0] || null; setCertFile(f); setCertFileName(f ? f.name : ''); }} style={{ display: 'none' }} />
                          </label>
                          {certFileName && (
                            <div className="ds-uploaded-file-pill">
                              <FileText size={16} />
                              <span>{certFileName}</span>
                              <button type="button" className="ds-clear-file-btn" onClick={() => { setCertFile(null); setCertFileName(''); }}><X size={14} /></button>
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  ) : (
                    <div className="ds-field">
                      <label>Truststore File (.p12 / .pfx)</label>
                      <div className="ds-file-upload-row">
                        <label className="ds-upload-btn-label">
                          <FileText size={16} /> Choose file
                          <input type="file" accept=".p12,.pfx" onChange={e => { const f = e.target.files?.[0] || null; setCertFile(f); setCertFileName(f ? f.name : ''); }} style={{ display: 'none' }} />
                        </label>
                        {certFileName && (
                          <div className="ds-uploaded-file-pill">
                            <FileText size={16} />
                            <span>{certFileName}</span>
                            <button type="button" className="ds-clear-file-btn" onClick={() => { setCertFile(null); setCertFileName(''); }}><X size={14} /></button>
                          </div>
                        )}
                      </div>
                    </div>
                  )}
                </div>
                {certType === 'PKCS12' && <div className="ds-field"><label>Truststore Password {selectedConn?.truststoreConfigured ? '(Leave blank to keep existing)' : ''}</label><input type="password" value={certPassword} onChange={e => setCertPassword(e.target.value)} placeholder="Password" /></div>}
                <div className="ds-default-toggle-row" style={{ border: 'none', paddingTop: 0, marginTop: 4 }}>
                  <label className="ds-toggle-switch" htmlFor="sr-is-default">
                    <input
                      type="checkbox"
                      id="sr-is-default"
                      checked={formIsDefault}
                      onChange={e => setFormIsDefault(e.target.checked)}
                    />
                    <span className="ds-toggle-track">
                      <span className="ds-toggle-thumb" />
                    </span>
                  </label>
                  <label htmlFor="sr-is-default" className="ds-toggle-label" style={{ color: '#818181', fontSize: '13px', cursor: 'pointer' }}>
                    Set as default connection for this cluster
                  </label>
                </div>
              </div>
            </div>
            <div className="ds-modal-footer">
              <button className="ds-button" onClick={() => setShowConnection(false)} disabled={connectSaving}>Cancel</button>
              <button className="ds-button primary" onClick={handleSaveConnection} disabled={connectSaving || !customIp.trim() || !customPort.trim()}>
                {connectSaving ? 'Saving...' : 'Save & Connect'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── Create Schema modal ───────────────────────────────── */}
      {canManage && showCreate && (
        <div className="ds-modal-backdrop" role="dialog" aria-modal="true">
          <form className="ds-modal" onSubmit={submitCreateSchema}>
            <div className="ds-modal-header">
              <div>
                <h3>Create Schema</h3>
                <span className="ds-muted-line">Register a new schema subject</span>
              </div>
              <button type="button" className="ds-close-btn" onClick={() => setShowCreate(false)}><X size={20} /></button>
            </div>
            <div className="ds-form" style={{ padding: 0 }}>
              <div className="ds-modal-inner-card">
                <div className="ds-field"><label>Subject</label><input value={createSubject} onChange={e => setCreateSubject(e.target.value)} placeholder="orders-value" required /></div>
                <div className="ds-field">
                  <label>Schema Type</label>
                  <CustomSelect
                    value={createSchemaType}
                    onChange={setCreateSchemaType}
                    options={[
                      { value: 'AVRO', label: 'AVRO' },
                      { value: 'JSON', label: 'JSON' },
                      { value: 'PROTOBUF', label: 'PROTOBUF' }
                    ]}
                  />
                </div>
                <div className="ds-field"><label>Schema</label><textarea value={createSchema} onChange={e => setCreateSchema(e.target.value)} required /></div>
              </div>
            </div>
            <div className="ds-modal-footer">
              <button type="button" className="ds-button" onClick={() => setShowCreate(false)}>Cancel</button>
              <button type="submit" className="ds-button primary" disabled={saving}>
                {saving ? 'Saving...' : 'Save'}
              </button>
            </div>
          </form>
        </div>
      )}

      {/* ── Custom Delete Connection Confirmation Modal ── */}
      {showDeleteConfirm && (
        <div className="ds-modal-backdrop" role="dialog" aria-modal="true">
          <div className="ds-delete-confirm-modal animate-fade-in">
            <div className="ds-delete-modal-banner">
              <img src={orangeBanner} alt="banner" className="ds-delete-banner-img" />
              <button className="ds-delete-modal-close" onClick={() => setShowDeleteConfirm(false)}><X size={20} /></button>
            </div>
            <div className="ds-delete-confirm-content">
              <div className="ds-delete-confirm-title-row">
                <AlertOctagon className="ds-delete-alert-icon" size={24} />
                <h2>{selectedConn ? `${selectedConn.host || selectedConn.connectionName} says` : 'Delete Connection'}</h2>
              </div>
              <p className="ds-delete-confirm-desc">Are you sure you want to delete this connection?</p>
              <div className="ds-delete-modal-footer">
                <button className="ds-delete-btn-cancel" onClick={() => setShowDeleteConfirm(false)}>Cancel</button>
                <button className="ds-delete-btn-ok" onClick={() => {
                  setShowDeleteConfirm(false);
                  confirmDeleteConnection();
                }}>OK</button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── Custom Delete Subject Confirmation Modal ── */}
      {subjectToDelete && (
        <div className="ds-modal-backdrop" role="dialog" aria-modal="true">
          <div className="ds-delete-confirm-modal animate-fade-in">
            <div className="ds-delete-modal-banner">
              <img src={orangeBanner} alt="banner" className="ds-delete-banner-img" />
              <button className="ds-delete-modal-close" onClick={() => setSubjectToDelete(null)}><X size={20} /></button>
            </div>
            <div className="ds-delete-confirm-content">
              <div className="ds-delete-confirm-title-row">
                <AlertOctagon className="ds-delete-alert-icon" size={24} />
                <h2>{selectedConn ? `${selectedConn.host || selectedConn.connectionName} says` : 'Delete Subject'}</h2>
              </div>
              <p className="ds-delete-confirm-desc">Are you sure you want to delete this subject?</p>
              <div className="ds-delete-modal-footer">
                <button className="ds-delete-btn-cancel" onClick={() => setSubjectToDelete(null)}>Cancel</button>
                <button className="ds-delete-btn-ok" onClick={() => {
                  confirmDeleteSubject(subjectToDelete);
                  setSubjectToDelete(null);
                }}>OK</button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
