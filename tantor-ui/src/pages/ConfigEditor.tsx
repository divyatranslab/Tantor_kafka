import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { Save, AlertTriangle, RefreshCw, Server } from 'lucide-react';

interface BrokerConfigs {
  [brokerId: string]: {
    [key: string]: string;
  };
}

export function ConfigEditor() {
  const { id } = useParams<{ id: string }>();
  const [brokerConfigs, setBrokerConfigs] = useState<BrokerConfigs>({});
  const [loading, setLoading] = useState(true);
  
  // Bulk update state
  const [configKey, setConfigKey] = useState('');
  const [configValue, setConfigValue] = useState('');
  const [saving, setSaving] = useState(false);
  const [applyToAgents, setApplyToAgents] = useState(false);
  const [restart, setRestart] = useState(false);

  const fetchConfigs = async () => {
    setLoading(true);
    try {
      const res = await fetch(`/api/v1/clusters/${id}/config`);
      if (res.ok) setBrokerConfigs(await res.json());
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchConfigs();
  }, [id]);

  const handleBulkSave = async () => {
    if (!configKey.trim() || !configValue.trim()) {
      alert("Please enter both a configuration key and value.");
      return;
    }

    setSaving(true);
    try {
      const res = await fetch(`/api/v1/clusters/${id}/config/bulk`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          configKey: configKey.trim(),
          configValue: configValue.trim(),
          applyToAgents,
          restart
        })
      });
      if (res.ok) {
        alert(`Successfully applied config ${configKey} to all brokers.`);
        setConfigKey('');
        setConfigValue('');
        fetchConfigs();
      } else {
        alert("Failed to apply configuration.");
      }
    } catch (e) {
      alert("Error applying configuration.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="topics-tab" style={{ maxWidth: '1000px' }}>
      <div className="topics-header">
        <div>
          <h2>Broker Configuration</h2>
          <p style={{ fontSize: '0.875rem', color: 'var(--text-secondary)', marginTop: '0.25rem' }}>View live configurations and apply bulk changes dynamically.</p>
        </div>
        <button className="btn" onClick={fetchConfigs} disabled={loading}>
          <RefreshCw size={16} className={loading ? 'spin' : ''} /> Refresh
        </button>
      </div>

      <div className="table-card" style={{ marginBottom: '1.5rem', padding: '1.5rem' }}>
        <h3 style={{ fontWeight: 600, color: 'var(--text-primary)', marginBottom: '1rem', margin: '0 0 1rem 0' }}>Bulk Update Configuration</h3>
        
        <div style={{ backgroundColor: '#eff6ff', borderLeft: '4px solid #3b82f6', padding: '1rem', marginBottom: '1.5rem', fontSize: '0.875rem', color: '#1e40af' }}>
          By default, changes are applied <b>dynamically</b> to the live broker memory (zero downtime). Check the boxes below if you want to also persist the change to disk and restart the brokers.
        </div>

        <div style={{ display: 'flex', gap: '1rem', marginBottom: '1.5rem' }}>
          <div style={{ flex: 1 }}>
            <label style={{ display: 'block', fontSize: '0.875rem', fontWeight: 500, color: '#374151', marginBottom: '0.5rem' }}>Configuration Key</label>
            <input 
              type="text" 
              value={configKey} 
              onChange={e => setConfigKey(e.target.value)} 
              placeholder="e.g. log.retention.hours"
              style={{ width: '100%', padding: '0.5rem', border: '1px solid #d1d5db', borderRadius: '0.375rem', fontFamily: 'monospace' }}
            />
          </div>
          <div style={{ flex: 1 }}>
            <label style={{ display: 'block', fontSize: '0.875rem', fontWeight: 500, color: '#374151', marginBottom: '0.5rem' }}>Value</label>
            <input 
              type="text" 
              value={configValue} 
              onChange={e => setConfigValue(e.target.value)} 
              placeholder="e.g. 168"
              style={{ width: '100%', padding: '0.5rem', border: '1px solid #d1d5db', borderRadius: '0.375rem', fontFamily: 'monospace' }}
            />
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <div style={{ display: 'flex', gap: '1.5rem' }}>
            <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer', fontSize: '0.875rem' }}>
              <input type="checkbox" checked={applyToAgents} onChange={e => setApplyToAgents(e.target.checked)} />
              Persist to server.properties
            </label>
            <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer', fontSize: '0.875rem', opacity: applyToAgents ? 1 : 0.5 }}>
              <input type="checkbox" checked={restart} onChange={e => setRestart(e.target.checked)} disabled={!applyToAgents} />
              Restart service immediately
            </label>
          </div>
          <button className="btn btn-primary-action" onClick={handleBulkSave} disabled={saving}>
            <Save size={16} /> {saving ? 'Applying...' : 'Apply to all brokers'}
          </button>
        </div>
      </div>

      <div className="table-card">
        <div style={{ padding: '1rem', borderBottom: '1px solid var(--border-color)', backgroundColor: 'var(--bg-primary)' }}>
          <h3 style={{ margin: 0, fontSize: '1rem', fontWeight: 500 }}>Live Active Configurations</h3>
        </div>
        {loading ? (
          <div className="empty-state">Fetching active configs from cluster...</div>
        ) : Object.keys(brokerConfigs).length === 0 ? (
          <div className="empty-state">No configuration data available. Ensure brokers are online.</div>
        ) : (
          <div style={{ padding: '1rem', display: 'flex', gap: '1rem', overflowX: 'auto' }}>
            {Object.entries(brokerConfigs).map(([brokerId, configs]) => (
              <div key={brokerId} style={{ minWidth: '400px', flex: 1, border: '1px solid var(--border-color)', borderRadius: '0.5rem' }}>
                <div style={{ padding: '0.75rem', backgroundColor: '#f9fafb', borderBottom: '1px solid var(--border-color)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                  <Server size={18} color="#4b5563" />
                  <span style={{ fontWeight: 500 }}>Broker {brokerId}</span>
                </div>
                <div style={{ maxHeight: '400px', overflowY: 'auto' }}>
                  <table className="data-table" style={{ fontSize: '0.8125rem' }}>
                    <tbody>
                      {Object.entries(configs)
                        .filter(([k, _]) => !k.startsWith('ssl.') && !k.startsWith('sasl.')) // hide secrets
                        .sort(([a], [b]) => a.localeCompare(b))
                        .map(([key, value]) => (
                        <tr key={key}>
                          <td style={{ fontFamily: 'monospace', color: '#374151', padding: '0.5rem 0.75rem' }}>{key}</td>
                          <td style={{ fontFamily: 'monospace', color: '#2563eb', padding: '0.5rem 0.75rem', wordBreak: 'break-all' }}>{value}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
