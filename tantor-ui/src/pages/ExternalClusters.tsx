import { useEffect, useState } from 'react';
import { Plug, RefreshCw, Plus, Trash2, X, Check, Wifi, AlertCircle, Loader2 } from 'lucide-react';

type SecurityProtocol = 'PLAINTEXT' | 'SSL' | 'SASL_PLAINTEXT' | 'SASL_SSL';
type SaslMechanism = 'PLAIN' | 'SCRAM-SHA-256' | 'SCRAM-SHA-512' | 'OAUTHBEARER' | 'GSSAPI';

interface ExternalCluster {
  id: string;
  name: string;
  bootstrap_servers?: string;
  security_protocol: SecurityProtocol;
  sasl_mechanism: SaslMechanism | null;
  ssl_verify: boolean;
  sasl_username?: string;
  sasl_password_set?: boolean;
}

interface ExternalConnectionTestResult {
  success: boolean;
  message: string;
  cluster_id?: string;
  controller_id?: number;
}

const PROTOCOLS: SecurityProtocol[] = ['PLAINTEXT', 'SSL', 'SASL_PLAINTEXT', 'SASL_SSL'];
const SASL_MECHANISMS: SaslMechanism[] = ['PLAIN', 'SCRAM-SHA-256', 'SCRAM-SHA-512', 'OAUTHBEARER', 'GSSAPI'];

interface FormState {
  id?: string;
  name: string;
  bootstrap_servers: string;
  security_protocol: SecurityProtocol;
  sasl_mechanism: SaslMechanism | null;
  ssl_verify: boolean;
  sasl_username: string;
  sasl_password: string;
  ssl_ca_pem: string;
  ssl_cert_pem: string;
  ssl_key_pem: string;
}

const blankForm = (): FormState => ({
  name: '',
  bootstrap_servers: '',
  security_protocol: 'PLAINTEXT',
  sasl_mechanism: null,
  ssl_verify: true,
  sasl_username: '',
  sasl_password: '',
  ssl_ca_pem: '',
  ssl_cert_pem: '',
  ssl_key_pem: '',
});

const validateBootstrapServers = (value: string): string | null => {
  const servers = value.split(',').map((s) => s.trim()).filter(Boolean);
  if (!servers.length) {
    return 'Bootstrap servers are required';
  }

  for (const server of servers) {
    if (!server.includes(':')) {
      return `Bootstrap server "${server}" must include a port, for example ${server}:9092`;
    }
    if (server.includes(':') && server.split(':').length > 2 && !server.startsWith('[')) {
      return `Bootstrap server "${server}" must use [ipv6]:port format for IPv6 addresses`;
    }

    const [host, port] = server.split(/:(?=[^:]*$)/);
    if (server.startsWith('[') && !host.endsWith(']')) {
      return `Bootstrap server "${server}" must use [ipv6]:port format for IPv6 addresses`;
    }
    const portNumber = Number(port);
    if (!host || !port || !/^\d+$/.test(port) || portNumber < 1 || portNumber > 65535) {
      return `Bootstrap server "${server}" must include a valid port between 1 and 65535`;
    }
  }

  return null;
};

export function ExternalClusters() {
  const admin = true;
  const [list, setList] = useState<ExternalCluster[]>([]);
  const [loading, setLoading] = useState(false);
  const [editing, setEditing] = useState<FormState | null>(null);
  const [error, setError] = useState('');
  const [info, setInfo] = useState('');
  const [topicsByCluster, setTopicsByCluster] = useState<Record<string, string[]>>({});
  const [testingCluster, setTestingCluster] = useState<string | null>(null);
  const [listingCluster, setListingCluster] = useState<string | null>(null);

  const reload = async () => {
    setLoading(true);
    setError('');
    try {
      const res = await fetch('/api/v1/external-clusters');
      if (!res.ok) throw new Error('Failed to load external clusters');
      const data = await res.json();
      setList(data);
    } catch (e: any) {
      setError(e.message || 'Failed to load');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { reload(); }, []);

  const startNew = () => setEditing(blankForm());

  const startEdit = (c: ExternalCluster) => {
    setEditing({
      id: c.id,
      name: c.name,
      bootstrap_servers: c.bootstrap_servers ?? '',
      security_protocol: c.security_protocol,
      sasl_mechanism: c.sasl_mechanism,
      ssl_verify: c.ssl_verify,
      sasl_username: c.sasl_username ?? '',
      sasl_password: '',
      ssl_ca_pem: '',
      ssl_cert_pem: '',
      ssl_key_pem: '',
    });
  };

  const onSave = async () => {
    if (!editing) return;
    if (!editing.name.trim()) {
      setError('Display name is required');
      return;
    }
    const bootstrapError = validateBootstrapServers(editing.bootstrap_servers);
    if (bootstrapError) {
      setError(bootstrapError);
      return;
    }
    setError('');
    try {
      const secrets: Record<string, string> = {};
      if (editing.sasl_username) secrets.sasl_username = editing.sasl_username;
      if (editing.sasl_password) secrets.sasl_password = editing.sasl_password;
      if (editing.ssl_ca_pem) secrets.ssl_ca_pem = editing.ssl_ca_pem;
      if (editing.ssl_cert_pem) secrets.ssl_cert_pem = editing.ssl_cert_pem;
      if (editing.ssl_key_pem) secrets.ssl_key_pem = editing.ssl_key_pem;
      const body = {
        name: editing.name.trim(),
        bootstrap_servers: editing.bootstrap_servers.trim(),
        security_protocol: editing.security_protocol,
        sasl_mechanism: editing.sasl_mechanism,
        ssl_verify: editing.ssl_verify,
        secrets,
      };
      
      const method = editing.id ? 'PUT' : 'POST';
      const url = editing.id ? `/api/v1/external-clusters/${editing.id}` : '/api/v1/external-clusters';

      const res = await fetch(url, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
      if (!res.ok) {
        const err = await res.json();
        throw new Error(err.detail || 'Save failed');
      }
      setEditing(null);
      await reload();
    } catch (e: any) {
      setError(e.message || 'Save failed');
    }
  };

  const onTestUnsaved = async () => {
    if (!editing) return;
    setInfo('');
    setError('');
    try {
      const secrets: Record<string, string> = {};
      if (editing.sasl_username) secrets.sasl_username = editing.sasl_username;
      if (editing.sasl_password) secrets.sasl_password = editing.sasl_password;
      if (editing.ssl_ca_pem) secrets.ssl_ca_pem = editing.ssl_ca_pem;
      if (editing.ssl_cert_pem) secrets.ssl_cert_pem = editing.ssl_cert_pem;
      if (editing.ssl_key_pem) secrets.ssl_key_pem = editing.ssl_key_pem;
      
      const body = {
        bootstrap_servers: editing.bootstrap_servers.trim(),
        security_protocol: editing.security_protocol,
        sasl_mechanism: editing.sasl_mechanism,
        ssl_verify: editing.ssl_verify,
        secrets,
      };

      const res = await fetch('/api/v1/external-clusters/test-unsaved', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
      if (!res.ok) {
        const err = await res.json();
        throw new Error(err.detail || 'Test failed');
      }
      const r: ExternalConnectionTestResult = await res.json();
      setInfo(r.success
        ? `✓ ${r.message}${r.cluster_id ? ` · cluster_id=${r.cluster_id}` : ''}${r.controller_id != null ? ` · controller=${r.controller_id}` : ''}`
        : `✗ ${r.message}`);
    } catch (e: any) {
      setError(e.message || 'Test failed');
    }
  };

  const onTestSaved = async (c: ExternalCluster) => {
    setTestingCluster(c.id);
    setInfo('');
    try {
      const res = await fetch(`/api/v1/external-clusters/${c.id}/test`, { method: 'POST' });
      if (!res.ok) {
        const err = await res.json();
        throw new Error(err.detail || 'failed');
      }
      const r = await res.json();
      setInfo(r.success ? `✓ ${c.name}: ${r.message}` : `✗ ${c.name}: ${r.message}`);
    } catch (e: any) {
      setInfo(`✗ ${c.name}: ${e.message || 'failed'}`);
    } finally {
      setTestingCluster(null);
    }
  };

  const onListTopics = async (c: ExternalCluster) => {
    setListingCluster(c.id);
    try {
      const res = await fetch(`/api/v1/external-clusters/${c.id}/topics`);
      if (!res.ok) {
        const err = await res.json();
        throw new Error(err.detail || 'failed');
      }
      const tps = await res.json();
      setTopicsByCluster({ ...topicsByCluster, [c.id]: tps.map((t: any) => t.name) });
      setInfo(`✓ ${c.name}: ${tps.length} topic(s) loaded`);
    } catch (e: any) {
      setInfo(`✗ ${c.name} list_topics: ${e.message || 'failed'}`);
    } finally {
      setListingCluster(null);
    }
  };

  const onDelete = async (c: ExternalCluster) => {
    if (!confirm(`Remove external cluster "${c.name}"?`)) return;
    try {
      const res = await fetch(`/api/v1/external-clusters/${c.id}`, { method: 'DELETE' });
      if (!res.ok) {
        const err = await res.json();
        throw new Error(err.detail || 'Delete failed');
      }
      await reload();
    } catch (e: any) {
      setError(e.message || 'Delete failed');
    }
  };

  return (
    <div className="migrated-page">
      <div className="page-header">
        <div>
          <h1 className="page-title">
            <Plug size={24} /> External Clusters
          </h1>
          <p className="page-subtitle">
            Connect to existing Kafka clusters Tantor didn't deploy. Supports PLAINTEXT, SSL, SASL_PLAINTEXT, SASL_SSL.
          </p>
        </div>
        <div className="header-actions">
          {admin && (
            <button onClick={startNew} className="btn btn-primary">
              <Plus size={16} /> Connect cluster
            </button>
          )}
          <button onClick={reload} disabled={loading} className="btn btn-secondary">
            <RefreshCw size={14} className={loading ? 'animate-spin' : ''} /> Refresh
          </button>
        </div>
      </div>

      {error && <div className="alert alert-error mb-4">{error}</div>}
      {info && <div className="alert alert-info mb-4" style={{ wordBreak: 'break-all' }}>{info}</div>}

      <div className="table-container">
        <table className="migrated-table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Bootstrap servers</th>
              <th style={{ width: '140px' }}>Protocol</th>
              <th style={{ width: '140px' }}>SASL</th>
              <th style={{ width: '80px' }}>Verify</th>
              {admin && <th style={{ width: '260px', textAlign: 'right' }}>Actions</th>}
            </tr>
          </thead>
          <tbody>
            {!list.length && (
              <tr><td colSpan={admin ? 6 : 5} className="text-center">No external clusters connected.</td></tr>
            )}
            {list.map((c) => (
              <tr key={c.id}>
                <td style={{ fontWeight: 500 }}>{c.name}</td>
                <td className="font-mono text-xs" style={{ wordBreak: 'break-all' }}>{c.bootstrap_servers}</td>
                <td>{c.security_protocol}</td>
                <td>
                  {c.sasl_mechanism ? `${c.sasl_mechanism} as ${c.sasl_username ?? '?'} ${c.sasl_password_set ? '🔐' : ''}` : '—'}
                </td>
                <td>{c.ssl_verify ? 'on' : 'off'}</td>
                {admin && (
                  <td>
                    <div className="flex-row gap-2" style={{ justifyContent: 'flex-end' }}>
                      <button
                        onClick={() => onTestSaved(c)}
                        disabled={testingCluster === c.id}
                        className="btn btn-secondary text-xs"
                        style={{ padding: '0.25rem 0.5rem' }}
                      >
                        {testingCluster === c.id ? <Loader2 size={12} className="animate-spin" /> : <Wifi size={12} />}
                        {testingCluster === c.id ? 'Testing…' : 'Test'}
                      </button>
                      <button
                        onClick={() => onListTopics(c)}
                        disabled={listingCluster === c.id}
                        className="btn btn-secondary text-xs"
                        style={{ padding: '0.25rem 0.5rem' }}
                      >
                        {listingCluster === c.id && <Loader2 size={12} className="animate-spin" />}
                        {listingCluster === c.id ? 'Loading…' : 'List topics'}
                      </button>
                      <button onClick={() => startEdit(c)} className="btn btn-secondary text-xs" style={{ padding: '0.25rem 0.5rem' }}>
                        Edit
                      </button>
                      <button onClick={() => onDelete(c)} className="btn btn-icon text-danger">
                        <Trash2 size={14} />
                      </button>
                    </div>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {Object.keys(topicsByCluster).length > 0 && (
        <div className="migrated-card mt-4">
          <h2 style={{ fontSize: '1rem', marginBottom: '0.5rem' }}>Recent topic listings</h2>
          {Object.entries(topicsByCluster).map(([cid, tps]) => {
            const c = list.find((x) => x.id === cid);
            return (
              <div key={cid} className="mb-4">
                <div className="page-subtitle mb-2">{c?.name ?? cid} · {tps.length} topic(s)</div>
                <div className="flex-row flex-wrap gap-2">
                  {tps.length === 0 && <span className="text-sm text-gray-500">No topics</span>}
                  {tps.map((t) => (
                    <code key={t} className="font-mono text-xs" style={{ padding: '0.125rem 0.375rem', background: 'var(--bg-raised)', border: '1px solid var(--border-default)', borderRadius: '4px' }}>{t}</code>
                  ))}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {editing && (
        <ConnectModal
          form={editing}
          error={error}
          onChange={(p) => { setError(''); setEditing({ ...editing, ...p }); }}
          onClose={() => { setEditing(null); setError(''); setInfo(''); }}
          onSave={onSave}
          onTest={onTestUnsaved}
        />
      )}
    </div>
  );
}

function ConnectModal({
  form, error, onChange, onClose, onSave, onTest,
}: {
  form: FormState;
  error: string;
  onChange: (p: Partial<FormState>) => void;
  onClose: () => void;
  onSave: () => void;
  onTest: () => void;
}) {
  const isSasl = form.security_protocol.startsWith('SASL_');
  const isSsl = form.security_protocol.endsWith('SSL');
  return (
    <div style={{ position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.4)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '1rem', zIndex: 50 }}>
      <div className="migrated-card flex-col" style={{ maxWidth: '42rem', width: '100%', maxHeight: '90vh', overflowY: 'auto', padding: 0 }}>
        <div className="flex-row justify-between" style={{ padding: '1rem 1.5rem', borderBottom: '1px solid var(--border-default)' }}>
          <h3 style={{ fontSize: '1.125rem', margin: 0 }}>{form.id ? 'Edit external cluster' : 'Connect external cluster'}</h3>
          <button onClick={onClose} className="btn-icon"><X size={18} /></button>
        </div>
        <div className="flex-col gap-4" style={{ padding: '1.5rem' }}>
          {error && (
            <div className="alert alert-error">
              {error}
            </div>
          )}
          <div className="form-group">
            <label className="form-label">Display name</label>
            <input value={form.name} onChange={(e) => onChange({ name: e.target.value })}
              className="form-input" placeholder="Production prod-east" />
          </div>
          <div className="form-group">
            <label className="form-label">Bootstrap servers</label>
            <input value={form.bootstrap_servers} onChange={(e) => onChange({ bootstrap_servers: e.target.value })}
              className="form-input font-mono"
              placeholder="broker-1.example.com:9092,broker-2.example.com:9092" />
            <p className="form-help">Each server must include a port, for example broker.example.com:9092.</p>
          </div>
          <div className="flex-row gap-4">
            <div className="form-group flex-1">
              <label className="form-label">Security protocol</label>
              <select value={form.security_protocol}
                onChange={(e) => onChange({ security_protocol: e.target.value as SecurityProtocol })}
                className="form-select">
                {PROTOCOLS.map((p) => <option key={p} value={p}>{p}</option>)}
              </select>
            </div>
            {isSasl && (
              <div className="form-group flex-1">
                <label className="form-label">SASL mechanism</label>
                <select value={form.sasl_mechanism ?? 'PLAIN'}
                  onChange={(e) => onChange({ sasl_mechanism: e.target.value as SaslMechanism })}
                  className="form-select">
                  {SASL_MECHANISMS.map((m) => <option key={m} value={m}>{m}</option>)}
                </select>
              </div>
            )}
          </div>

          {isSasl && (
            <div style={{ borderLeft: '4px solid var(--color-info)', paddingLeft: '1rem', paddingBottom: '0.5rem', paddingTop: '0.5rem' }}>
              <div className="flex-row gap-4">
                <div className="form-group flex-1">
                  <label className="form-label">SASL username</label>
                  <input value={form.sasl_username} onChange={(e) => onChange({ sasl_username: e.target.value })}
                    className="form-input" />
                </div>
                <div className="form-group flex-1">
                  <label className="form-label">
                    SASL password {form.id && <span style={{ color: 'var(--text-tertiary)' }}>(leave blank to keep)</span>}
                  </label>
                  <input type="password" value={form.sasl_password} onChange={(e) => onChange({ sasl_password: e.target.value })}
                    className="form-input" />
                </div>
              </div>
            </div>
          )}

          {isSsl && (
            <div className="flex-col gap-4" style={{ borderLeft: '4px solid var(--color-success)', paddingLeft: '1rem', paddingBottom: '0.5rem', paddingTop: '0.5rem' }}>
              <label className="flex-row gap-2" style={{ cursor: 'pointer', fontSize: '0.875rem' }}>
                <input type="checkbox" checked={form.ssl_verify}
                  onChange={(e) => onChange({ ssl_verify: e.target.checked })} />
                Verify server certificate
              </label>
              {!form.ssl_verify && (
                <div className="alert alert-error">
                  <AlertCircle size={16} /> SSL verification OFF — vulnerable to MITM. Use only for dev clusters.
                </div>
              )}
              <PemField
                label={`CA certificate (server) ${form.id ? '(leave blank to keep)' : '(optional)'}`}
                value={form.ssl_ca_pem}
                onChange={(v) => onChange({ ssl_ca_pem: v })}
                accept=".pem,.crt,.cer"
              />
              <PemField
                label={`Client certificate ${form.id ? '(leave blank to keep)' : '(mTLS only)'}`}
                value={form.ssl_cert_pem}
                onChange={(v) => onChange({ ssl_cert_pem: v })}
                accept=".pem,.crt,.cer"
              />
              <PemField
                label={`Client private key ${form.id ? '(leave blank to keep)' : '(mTLS only)'}`}
                value={form.ssl_key_pem}
                onChange={(v) => onChange({ ssl_key_pem: v })}
                accept=".pem,.key"
              />
              <div className="form-help" style={{ fontStyle: 'italic' }}>
                Tip: paste PEM content into the textarea, or click <strong>Upload</strong> to load a .pem / .crt / .key file from disk.
                JKS keystores aren't supported directly — convert with <code style={{ background: 'var(--bg-raised)', padding: '0 4px', borderRadius: '4px' }}>keytool -importkeystore ... -deststoretype PKCS12</code> then <code style={{ background: 'var(--bg-raised)', padding: '0 4px', borderRadius: '4px' }}>openssl pkcs12 -in ... -out ...pem</code>.
              </div>
            </div>
          )}
        </div>
        <div className="flex-row justify-between" style={{ padding: '1rem 1.5rem', backgroundColor: 'var(--bg-raised)', borderTop: '1px solid var(--border-default)', borderBottomLeftRadius: 'var(--radius-lg)', borderBottomRightRadius: 'var(--radius-lg)' }}>
          <button onClick={onTest} className="btn btn-secondary">
            <Check size={16} /> Test connection
          </button>
          <div className="flex-row gap-2">
            <button onClick={onClose} className="btn btn-secondary">Cancel</button>
            <button onClick={onSave} className="btn btn-primary">Save</button>
          </div>
        </div>
      </div>
    </div>
  );
}

function PemField({
  label, value, onChange, accept,
}: {
  label: string; value: string; onChange: (v: string) => void; accept: string;
}) {
  const fileInputId = `pem-file-${label.replace(/[^a-z0-9]/gi, '-').toLowerCase()}`;
  const onFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (!f) return;
    const reader = new FileReader();
    reader.onload = () => {
      const text = String(reader.result || '');
      onChange(text);
    };
    reader.readAsText(f);
    e.target.value = '';
  };
  const isPem = value.includes('-----BEGIN');
  return (
    <div className="form-group">
      <div className="flex-row justify-between mb-2">
        <label className="form-label" style={{ margin: 0 }}>{label}</label>
        <label htmlFor={fileInputId} style={{ fontSize: '0.75rem', color: 'var(--accent-primary)', cursor: 'pointer', fontWeight: 500 }}>
          Upload {accept.split(',')[0]} file
        </label>
        <input id={fileInputId} type="file" accept={accept} style={{ display: 'none' }} onChange={onFile} />
      </div>
      <textarea rows={3} value={value} onChange={(e) => onChange(e.target.value)}
        placeholder={`-----BEGIN CERTIFICATE-----\n…\n-----END CERTIFICATE-----`}
        className="form-textarea font-mono" />
      {value && (
        <div className="form-help flex-row gap-2" style={{ marginTop: '0.5rem' }}>
          <span style={{ color: isPem ? 'var(--color-success)' : 'var(--color-warning)', fontWeight: 500 }}>
            {isPem ? '✓ looks like PEM' : '⚠ no -----BEGIN header detected'}
          </span>
          <span>{value.length} chars</span>
        </div>
      )}
    </div>
  );
}
