import { useState, useEffect } from 'react';
import { KeyRound, Save, TestTube, RefreshCw, X, Check, Users } from 'lucide-react';

interface LdapConfig {
  id?: string;
  enabled: boolean;
  server_url: string;
  use_ssl: boolean;
  tls_validate_cert: boolean;
  tls_ca_cert_present?: boolean;
  bind_dn: string;
  user_search_base: string;
  user_search_filter: string;
  group_search_base: string;
  admin_group_dn: string;
  monitor_group_dn: string;
  default_role: string;
  connection_timeout: number;
}

interface LdapUser {
  dn: string;
  username: string;
  display_name: string;
}

const FILTER_PRESETS = [
  { label: 'Active Directory (sAMAccountName)', value: '(sAMAccountName={username})' },
  { label: 'Active Directory (userPrincipalName)', value: '(userPrincipalName={username}@DOMAIN)' },
  { label: 'OpenLDAP (uid)', value: '(uid={username})' },
  { label: 'OpenLDAP (cn)', value: '(cn={username})' },
];

const DEFAULT_CONFIG: LdapConfig = {
  enabled: false,
  server_url: '',
  use_ssl: false,
  tls_validate_cert: true,
  tls_ca_cert_present: false,
  bind_dn: '',
  user_search_base: '',
  user_search_filter: '(sAMAccountName={username})',
  group_search_base: '',
  admin_group_dn: '',
  monitor_group_dn: '',
  default_role: 'monitor',
  connection_timeout: 10,
};

export function LdapSettings() {
  const [config, setConfig] = useState<LdapConfig>(DEFAULT_CONFIG);
  const [bindPassword, setBindPassword] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [showTestModal, setShowTestModal] = useState(false);
  const [testUsername, setTestUsername] = useState('');
  const [testPassword, setTestPassword] = useState('');
  const [testResult, setTestResult] = useState<{ success: boolean; message: string; user_dn?: string; groups?: string[] } | null>(null);
  const [ldapUsers, setLdapUsers] = useState<LdapUser[]>([]);
  const [showUsers, setShowUsers] = useState(false);
  const [caCertInput, setCaCertInput] = useState('');
  const [clearCaCert, setClearCaCert] = useState(false);

  useEffect(() => {
    fetchConfig();
  }, []);

  const fetchConfig = async () => {
    try {
      const res = await fetch('/api/v1/ldap/config');
      if (res.ok) {
        const data = await res.json();
        setConfig(data);
      }
    } catch {
      // No config yet, use defaults
    } finally {
      setLoading(false);
    }
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!bindPassword && !config.id) {
      setError('Bind password is required for initial configuration');
      return;
    }
    setSaving(true);
    setError('');
    setSuccess('');
    try {
      const payload: any = {
        ...config,
        bind_password: bindPassword || 'UNCHANGED',
      };
      delete payload.id;
      delete payload.tls_ca_cert_present;
      
      if (clearCaCert) {
        payload.tls_ca_cert = '';
      } else if (caCertInput.trim()) {
        payload.tls_ca_cert = caCertInput;
      } else {
        delete payload.tls_ca_cert;
      }

      const res = await fetch('/api/v1/ldap/config', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      if (!res.ok) {
        const err = await res.json();
        throw new Error(err.detail || 'Failed to save configuration');
      }

      const result = await res.json();
      setConfig(result);
      setBindPassword('');
      setCaCertInput('');
      setClearCaCert(false);
      setSuccess('LDAP configuration saved successfully');
      setTimeout(() => setSuccess(''), 5000);
    } catch (err: any) {
      setError(err.message || 'Failed to save configuration');
    } finally {
      setSaving(false);
    }
  };

  const handleTest = async () => {
    if (!testUsername || !testPassword) return;
    setTesting(true);
    setTestResult(null);
    try {
      const res = await fetch('/api/v1/ldap/test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: testUsername, password: testPassword })
      });
      if (!res.ok) {
        const err = await res.json();
        throw new Error(err.detail || 'Test failed');
      }
      const result = await res.json();
      setTestResult(result);
    } catch (err: any) {
      setTestResult({ success: false, message: err.message || 'Test failed' });
    } finally {
      setTesting(false);
    }
  };

  const handleSyncUsers = async () => {
    setSyncing(true);
    setError('');
    try {
      const res = await fetch('/api/v1/ldap/sync', { method: 'POST' });
      if (!res.ok) {
        const err = await res.json();
        throw new Error(err.detail || 'Failed to sync users');
      }
      const result = await res.json();
      setLdapUsers(result.users || []);
      setShowUsers(true);
    } catch (err: any) {
      setError(err.message || 'Failed to sync users');
    } finally {
      setSyncing(false);
    }
  };

  if (loading) {
    return (
      <div className="flex-row justify-center" style={{ height: '16rem' }}>
        <RefreshCw size={32} className="animate-spin" style={{ color: 'var(--accent-primary)' }} />
      </div>
    );
  }

  return (
    <div className="migrated-page">
      <div className="page-header">
        <div>
          <h1 className="page-title">
            <KeyRound size={24} style={{ color: 'var(--color-warning)' }} />
            LDAP / Active Directory
          </h1>
          <p className="page-subtitle">Configure LDAP or Active Directory authentication</p>
        </div>
        <div className="header-actions">
          <button
            onClick={() => setShowTestModal(true)}
            disabled={!config.id}
            className="btn btn-secondary" style={{ borderColor: 'var(--color-warning)', color: 'var(--color-warning)' }}
          >
            <TestTube size={18} />
            Test Connection
          </button>
          <button
            onClick={handleSyncUsers}
            disabled={!config.id || syncing}
            className="btn btn-primary"
          >
            <Users size={18} />
            {syncing ? 'Syncing...' : 'Sync Users'}
          </button>
        </div>
      </div>

      {error && (
        <div className="alert alert-error flex-row justify-between mb-4 w-full">
          <span>{error}</span>
          <button onClick={() => setError('')} className="btn-icon text-danger"><X size={16} /></button>
        </div>
      )}

      {success && (
        <div className="alert alert-success mb-4 flex-row gap-2" style={{ alignItems: 'center' }}>
          <Check size={16} />
          {success}
        </div>
      )}

      <form onSubmit={handleSave} className="flex-col gap-6">
        {/* Enable/Disable Toggle */}
        <div className="migrated-card">
          <div className="flex-row justify-between" style={{ alignItems: 'center' }}>
            <div>
              <h3 style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--text-primary)', margin: 0 }}>LDAP Authentication</h3>
              <p className="page-subtitle" style={{ marginTop: '0.25rem' }}>
                {config.enabled
                  ? 'LDAP authentication is enabled. Users can log in with their directory credentials.'
                  : 'LDAP authentication is disabled. Only local users can log in.'}
              </p>
            </div>
            <button
              type="button"
              onClick={() => setConfig({ ...config, enabled: !config.enabled })}
              style={{
                position: 'relative',
                display: 'inline-flex',
                height: '24px',
                width: '44px',
                alignItems: 'center',
                borderRadius: '9999px',
                transition: 'background-color 0.2s',
                backgroundColor: config.enabled ? 'var(--color-success)' : 'var(--border-strong)',
                border: 'none',
                cursor: 'pointer'
              }}
            >
              <span
                style={{
                  display: 'inline-block',
                  height: '16px',
                  width: '16px',
                  transform: config.enabled ? 'translateX(24px)' : 'translateX(4px)',
                  borderRadius: '9999px',
                  backgroundColor: 'white',
                  transition: 'transform 0.2s'
                }}
              />
            </button>
          </div>
        </div>

        {/* Server Connection */}
        <div className="migrated-card flex-col gap-4">
          <h3 style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--text-primary)', margin: 0 }}>Server Connection</h3>

          <div className="flex-row gap-4">
            <div className="form-group flex-[2]" style={{ marginBottom: 0 }}>
              <label className="form-label">Server URL</label>
              <input
                type="text"
                value={config.server_url}
                onChange={(e) => setConfig({ ...config, server_url: e.target.value })}
                placeholder="ldap://ad.company.com:389"
                className="form-input"
                required
              />
              <p className="form-help">e.g. ldap://server:389 or ldaps://server:636</p>
            </div>
            <div className="form-group flex-1" style={{ marginBottom: 0 }}>
              <label className="form-label">Connection Timeout (seconds)</label>
              <input
                type="number"
                value={config.connection_timeout}
                onChange={(e) => setConfig({ ...config, connection_timeout: parseInt(e.target.value) || 10 })}
                min={1}
                max={60}
                className="form-input"
              />
            </div>
          </div>

          <div className="flex-row gap-3">
            <label className="flex-row gap-2 text-sm" style={{ cursor: 'pointer', color: 'var(--text-secondary)' }}>
              <input
                type="checkbox"
                checked={config.use_ssl}
                onChange={(e) => setConfig({ ...config, use_ssl: e.target.checked })}
              />
              Use SSL/TLS (LDAPS)
            </label>
          </div>

          {config.use_ssl && (
            <div className="flex-col gap-3" style={{ borderLeft: '4px solid var(--color-info)', paddingLeft: '1rem', paddingBottom: '0.5rem', paddingTop: '0.5rem' }}>
              <label className="flex-row gap-2 text-sm" style={{ cursor: 'pointer', color: 'var(--text-secondary)' }}>
                <input
                  type="checkbox"
                  checked={config.tls_validate_cert}
                  onChange={(e) => setConfig({ ...config, tls_validate_cert: e.target.checked })}
                />
                Validate server certificate
                <span style={{ color: 'var(--text-tertiary)' }}>(recommended)</span>
              </label>

              {!config.tls_validate_cert && (
                <div className="alert alert-warning text-xs">
                  Server-cert validation is OFF. The connection is encrypted but vulnerable to MITM —
                  use only against trusted dev directories.
                </div>
              )}

              {config.tls_validate_cert && (
                <div className="form-group" style={{ marginBottom: 0 }}>
                  <label className="form-label">
                    CA Certificate (PEM){' '}
                    <span style={{ color: 'var(--text-tertiary)', fontWeight: 'normal' }}>
                      — optional, needed for private/internal CAs
                    </span>
                  </label>
                  <textarea
                    value={caCertInput}
                    onChange={(e) => {
                      setCaCertInput(e.target.value);
                      if (e.target.value) setClearCaCert(false);
                    }}
                    rows={6}
                    placeholder={
                      config.tls_ca_cert_present
                        ? '— stored — paste new PEM to replace —'
                        : '-----BEGIN CERTIFICATE-----\nMIID...\n-----END CERTIFICATE-----'
                    }
                    className="form-textarea font-mono text-xs"
                  />
                  <div className="flex-row justify-between mt-2">
                    <p className="form-help" style={{ margin: 0 }}>
                      {config.tls_ca_cert_present
                        ? 'A CA certificate is currently stored. Paste a new one to replace it.'
                        : 'Leave blank to use the system trust store (works for public CAs).'}
                    </p>
                    {config.tls_ca_cert_present && (
                      <label className="flex-row gap-2 text-xs" style={{ cursor: 'pointer', color: 'var(--color-error)' }}>
                        <input
                          type="checkbox"
                          checked={clearCaCert}
                          onChange={(e) => {
                            setClearCaCert(e.target.checked);
                            if (e.target.checked) setCaCertInput('');
                          }}
                        />
                        Clear stored CA on save
                      </label>
                    )}
                  </div>
                </div>
              )}
            </div>
          )}

          <div className="flex-row gap-4">
            <div className="form-group flex-[2]" style={{ marginBottom: 0 }}>
              <label className="form-label">Bind DN</label>
              <input
                type="text"
                value={config.bind_dn}
                onChange={(e) => setConfig({ ...config, bind_dn: e.target.value })}
                placeholder="cn=admin,dc=example,dc=com"
                className="form-input"
                required
              />
              <p className="form-help">Service account DN used to search for users</p>
            </div>
            <div className="form-group flex-1" style={{ marginBottom: 0 }}>
              <label className="form-label">
                Bind Password {config.id && <span style={{ color: 'var(--text-tertiary)' }}>(leave blank to keep current)</span>}
              </label>
              <input
                type="password"
                value={bindPassword}
                onChange={(e) => setBindPassword(e.target.value)}
                placeholder={config.id ? '********' : 'Enter password'}
                className="form-input"
                required={!config.id}
              />
            </div>
          </div>
        </div>

        {/* User Search */}
        <div className="migrated-card flex-col gap-4">
          <h3 style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--text-primary)', margin: 0 }}>User Search</h3>

          <div className="form-group" style={{ marginBottom: 0 }}>
            <label className="form-label">User Search Base</label>
            <input
              type="text"
              value={config.user_search_base}
              onChange={(e) => setConfig({ ...config, user_search_base: e.target.value })}
              placeholder="ou=users,dc=example,dc=com"
              className="form-input"
              required
            />
          </div>

          <div className="form-group" style={{ marginBottom: 0 }}>
            <label className="form-label">User Search Filter</label>
            <div className="flex-row gap-2">
              <input
                type="text"
                value={config.user_search_filter}
                onChange={(e) => setConfig({ ...config, user_search_filter: e.target.value })}
                placeholder="(sAMAccountName={username})"
                className="form-input flex-[2]"
                required
              />
              <select
                value=""
                onChange={(e) => {
                  if (e.target.value) setConfig({ ...config, user_search_filter: e.target.value });
                }}
                className="form-select flex-1"
              >
                <option value="">Presets...</option>
                {FILTER_PRESETS.map((p) => (
                  <option key={p.value} value={p.value}>{p.label}</option>
                ))}
              </select>
            </div>
            <p className="form-help">Use {'{username}'} as placeholder for the login username</p>
          </div>
        </div>

        {/* Group Mapping */}
        <div className="migrated-card flex-col gap-4">
          <h3 style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--text-primary)', margin: 0 }}>Group Mapping</h3>
          <p className="page-subtitle" style={{ marginTop: 0 }}>Map LDAP groups to Tantor roles. Leave blank to assign the default role to all LDAP users.</p>

          <div className="form-group" style={{ marginBottom: 0 }}>
            <label className="form-label">Group Search Base</label>
            <input
              type="text"
              value={config.group_search_base || ''}
              onChange={(e) => setConfig({ ...config, group_search_base: e.target.value })}
              placeholder="ou=groups,dc=example,dc=com"
              className="form-input"
            />
            <p className="form-help">Required for OpenLDAP group lookups. AD uses memberOf attribute automatically.</p>
          </div>

          <div className="flex-row gap-4">
            <div className="form-group flex-1" style={{ marginBottom: 0 }}>
              <label className="form-label">Admin Group DN</label>
              <input
                type="text"
                value={config.admin_group_dn || ''}
                onChange={(e) => setConfig({ ...config, admin_group_dn: e.target.value })}
                placeholder="cn=tantor-admins,ou=groups,dc=example,dc=com"
                className="form-input"
              />
              <p className="form-help">Members get admin role</p>
            </div>
            <div className="form-group flex-1" style={{ marginBottom: 0 }}>
              <label className="form-label">Monitor Group DN</label>
              <input
                type="text"
                value={config.monitor_group_dn || ''}
                onChange={(e) => setConfig({ ...config, monitor_group_dn: e.target.value })}
                placeholder="cn=tantor-monitors,ou=groups,dc=example,dc=com"
                className="form-input"
              />
              <p className="form-help">Members get monitor role</p>
            </div>
          </div>

          <div className="form-group" style={{ width: '200px', marginBottom: 0 }}>
            <label className="form-label">Default Role</label>
            <select
              value={config.default_role}
              onChange={(e) => setConfig({ ...config, default_role: e.target.value })}
              className="form-select"
            >
              <option value="monitor">Monitor</option>
              <option value="admin">Admin</option>
            </select>
            <p className="form-help">Role when user matches no group</p>
          </div>
        </div>

        {/* Save Button */}
        <div className="flex-row justify-end">
          <button
            type="submit"
            disabled={saving}
            className="btn btn-primary"
            style={{ padding: '0.75rem 1.5rem', fontSize: '1rem' }}
          >
            <Save size={20} />
            {saving ? 'Saving...' : 'Save Configuration'}
          </button>
        </div>
      </form>

      {/* Test Connection Modal */}
      {showTestModal && (
        <div style={{ position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 50 }}>
          <div className="migrated-card flex-col gap-4" style={{ width: '100%', maxWidth: '28rem', padding: '1.5rem' }}>
            <div className="flex-row justify-between" style={{ alignItems: 'center' }}>
              <h3 style={{ fontSize: '1.125rem', fontWeight: 600, color: 'var(--text-primary)', margin: 0 }}>Test LDAP Connection</h3>
              <button onClick={() => { setShowTestModal(false); setTestResult(null); }} className="btn-icon">
                <X size={20} />
              </button>
            </div>
            <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>Enter credentials of an LDAP user to test authentication.</p>

            <div className="flex-col gap-3">
              <div className="form-group" style={{ marginBottom: 0 }}>
                <label className="form-label">Username</label>
                <input
                  type="text"
                  value={testUsername}
                  onChange={(e) => setTestUsername(e.target.value)}
                  placeholder="jdoe"
                  className="form-input"
                />
              </div>
              <div className="form-group" style={{ marginBottom: 0 }}>
                <label className="form-label">Password</label>
                <input
                  type="password"
                  value={testPassword}
                  onChange={(e) => setTestPassword(e.target.value)}
                  placeholder="password"
                  className="form-input"
                />
              </div>
            </div>

            {testResult && (
              <div className={`alert ${testResult.success ? 'alert-success' : 'alert-error'}`}>
                <p style={{ fontWeight: 600, margin: '0 0 0.25rem 0' }}>{testResult.success ? 'Success' : 'Failed'}</p>
                <p style={{ margin: '0 0 0.25rem 0' }}>{testResult.message}</p>
                {testResult.user_dn && <p className="font-mono text-xs" style={{ margin: '0 0 0.25rem 0' }}>DN: {testResult.user_dn}</p>}
                {testResult.groups && testResult.groups.length > 0 && (
                  <div style={{ marginTop: '0.5rem' }}>
                    <p style={{ fontSize: '0.75rem', fontWeight: 500, margin: '0 0 0.25rem 0' }}>Groups ({testResult.groups.length}):</p>
                    <ul className="font-mono text-xs" style={{ listStyle: 'none', margin: 0, padding: 0, maxHeight: '8rem', overflowY: 'auto' }}>
                      {testResult.groups.map((g, i) => <li key={i} style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', marginBottom: '2px' }}>{g}</li>)}
                    </ul>
                  </div>
                )}
              </div>
            )}

            <div className="flex-row justify-end gap-2 mt-2">
              <button
                onClick={() => { setShowTestModal(false); setTestResult(null); }}
                className="btn btn-secondary"
              >
                Close
              </button>
              <button
                onClick={handleTest}
                disabled={testing || !testUsername || !testPassword}
                className="btn btn-primary" style={{ backgroundColor: 'var(--color-warning)', color: 'white', borderColor: 'var(--color-warning)' }}
              >
                <RefreshCw size={16} className={testing ? 'animate-spin' : ''} />
                {testing ? 'Testing...' : 'Test'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Synced Users Panel */}
      {showUsers && (
        <div className="migrated-card mt-6" style={{ padding: 0, overflow: 'hidden' }}>
          <div className="flex-row justify-between" style={{ padding: '1rem 1.5rem', borderBottom: '1px solid var(--border-default)' }}>
            <h3 style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--text-primary)', margin: 0 }}>Discovered LDAP Users ({ldapUsers.length})</h3>
            <button onClick={() => setShowUsers(false)} className="btn-icon">
              <X size={18} />
            </button>
          </div>
          {ldapUsers.length === 0 ? (
            <div style={{ padding: '2rem 1.5rem', textAlign: 'center', color: 'var(--text-tertiary)' }}>
              No users found. Check your search base and filter settings.
            </div>
          ) : (
            <div className="table-container" style={{ border: 'none', borderRadius: 0 }}>
              <table className="migrated-table">
                <thead>
                  <tr>
                    <th>Username</th>
                    <th>Display Name</th>
                    <th>DN</th>
                  </tr>
                </thead>
                <tbody>
                  {ldapUsers.map((user, i) => (
                    <tr key={i}>
                      <td style={{ fontWeight: 500 }}>{user.username}</td>
                      <td>{user.display_name}</td>
                      <td className="font-mono text-xs" style={{ color: 'var(--text-tertiary)', maxWidth: '20rem', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{user.dn}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
