import { useEffect, useState } from 'react';
import { useCluster } from '../contexts/ClusterContext';
import { Eye, Plus, RefreshCw, Trash2, X } from 'lucide-react';
import './DataServiceTabs.css';

interface SchemaSubject {
  subject: string;
  type: string;
  version: number;
  id: number;
  schemaType: string;
  schema: string;
}

interface SchemaSummary {
  connection: string;
  subjects: SchemaSubject[];
  totalSubjects: number;
  keySubjects: number;
  valueSubjects: number;
}

const emptySchema = `{
  "type": "record",
  "name": "Example",
  "fields": [
    { "name": "id", "type": "string" }
  ]
}`;

export function SchemaRegistry() {
  const { activeClusterId: id } = useCluster();
  const [summary, setSummary] = useState<SchemaSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [selected, setSelected] = useState<SchemaSubject | null>(null);
  const [subject, setSubject] = useState('');
  const [schemaType, setSchemaType] = useState('AVRO');
  const [schema, setSchema] = useState(emptySchema);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`/api/v1/clusters/${id}/data-services/schema-registry/summary`);
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'Failed to load Schema Registry.');
      setSummary(data);
    } catch (e: any) {
      setError(e.message || 'Failed to load Schema Registry.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (id) {
      load();
    }
  }, [id]);

  const createSchema = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!subject.trim() || !schema.trim()) return;
    setSaving(true);
    setError(null);
    try {
      const res = await fetch(`/api/v1/clusters/${id}/data-services/schema-registry/subjects/${encodeURIComponent(subject.trim())}/versions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ schemaType, schema })
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'Failed to create schema.');
      setShowCreate(false);
      setSubject('');
      setSchema(emptySchema);
      setSchemaType('AVRO');
      await load();
    } catch (e: any) {
      setError(e.message || 'Failed to create schema.');
    } finally {
      setSaving(false);
    }
  };

  const deleteSubject = async (name: string) => {
    if (!window.confirm(`Delete schema subject ${name}?`)) return;
    setSaving(true);
    setError(null);
    try {
      const res = await fetch(`/api/v1/clusters/${id}/data-services/schema-registry/subjects/${encodeURIComponent(name)}`, {
        method: 'DELETE'
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'Failed to delete subject.');
      await load();
    } catch (e: any) {
      setError(e.message || 'Failed to delete subject.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="data-services-page animate-fade-in">
      <div className="ds-header">
        <h2>Schema Registry</h2>
        <div className="ds-actions">
          <button className="ds-button" onClick={load} disabled={loading} title="Refresh">
            <RefreshCw size={16} className={loading ? 'spin' : ''} /> Refresh
          </button>
          <button className="ds-button primary" onClick={() => setShowCreate(true)}>
            <Plus size={16} /> Create Schema
          </button>
        </div>
      </div>

      {error && <div className="ds-alert">{error}</div>}

      <div className="ds-metrics">
        <div className="ds-metric-card"><span>Total Subjects</span><strong>{summary?.totalSubjects ?? 0}</strong></div>
        <div className="ds-metric-card"><span>Value Subjects</span><strong>{summary?.valueSubjects ?? 0}</strong></div>
        <div className="ds-metric-card"><span>Key Subjects</span><strong>{summary?.keySubjects ?? 0}</strong></div>
        <div className="ds-metric-card"><span>REST Endpoint</span><strong style={{ fontSize: 16 }}>{summary?.connection || '-'}</strong></div>
      </div>

      <div className="ds-panel">
        <table className="ds-table">
          <thead>
            <tr>
              <th>Subject</th>
              <th>Type</th>
              <th>Latest Version</th>
              <th>Schema ID</th>
              <th>Schema Type</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {loading && !summary ? (
              <tr><td colSpan={6} className="ds-empty">Loading schemas...</td></tr>
            ) : summary && summary.subjects.length > 0 ? (
              summary.subjects.map(item => (
                <tr key={item.subject}>
                  <td>{item.subject}</td>
                  <td><span className="ds-status">{item.type}</span></td>
                  <td>{item.version || '-'}</td>
                  <td>{item.id || '-'}</td>
                  <td>{item.schemaType}</td>
                  <td>
                    <div className="ds-inline-actions">
                      <button className="ds-button" onClick={() => setSelected(item)} title="View schema">
                        <Eye size={15} /> View
                      </button>
                      <button className="ds-button danger" onClick={() => deleteSubject(item.subject)} disabled={saving} title="Delete subject">
                        <Trash2 size={15} /> Delete
                      </button>
                    </div>
                  </td>
                </tr>
              ))
            ) : (
              <tr><td colSpan={6} className="ds-empty">No schemas found in this registry.</td></tr>
            )}
          </tbody>
        </table>
      </div>

      {showCreate && (
        <div className="ds-modal-backdrop" role="dialog" aria-modal="true">
          <form className="ds-modal" onSubmit={createSchema}>
            <div className="ds-modal-header">
              <h3>Create Schema</h3>
              <button type="button" className="ds-button" onClick={() => setShowCreate(false)} title="Close">
                <X size={16} />
              </button>
            </div>
            <div className="ds-form">
              <div className="ds-field">
                <label>Subject</label>
                <input value={subject} onChange={e => setSubject(e.target.value)} placeholder="orders-value" required />
              </div>
              <div className="ds-field">
                <label>Schema Type</label>
                <select value={schemaType} onChange={e => setSchemaType(e.target.value)}>
                  <option value="AVRO">AVRO</option>
                  <option value="JSON">JSON</option>
                  <option value="PROTOBUF">PROTOBUF</option>
                </select>
              </div>
              <div className="ds-field">
                <label>Schema</label>
                <textarea value={schema} onChange={e => setSchema(e.target.value)} required />
              </div>
            </div>
            <div className="ds-modal-footer">
              <button type="button" className="ds-button" onClick={() => setShowCreate(false)}>Cancel</button>
              <button type="submit" className="ds-button primary" disabled={saving}>
                {saving ? <RefreshCw size={16} className="spin" /> : <Plus size={16} />} Create
              </button>
            </div>
          </form>
        </div>
      )}

      {selected && (
        <div className="ds-modal-backdrop" role="dialog" aria-modal="true">
          <div className="ds-modal">
            <div className="ds-modal-header">
              <h3>{selected.subject}</h3>
              <button type="button" className="ds-button" onClick={() => setSelected(null)} title="Close">
                <X size={16} />
              </button>
            </div>
            <textarea className="ds-code" value={selected.schema} readOnly />
          </div>
        </div>
      )}
    </div>
  );
}
