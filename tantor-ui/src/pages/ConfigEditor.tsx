import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { Save, RefreshCw, Server, FileText, ChevronDown } from 'lucide-react';

interface BrokerConfigs {
  [brokerId: string]: {
    [key: string]: { value: string; isReadOnly: boolean };
  };
}

interface ConfigPayload {
  dynamicConfigs: BrokerConfigs;
  staticConfigs: {
    filePath: string;
    properties: Record<string, any>;
  };
}

type ViewType = 'BROKER' | 'CONTROLLER' | 'SERVER';

export function ConfigEditor() {
  const { id } = useParams<{ id: string }>();
  const [payload, setPayload] = useState<ConfigPayload | null>(null);
  const [loading, setLoading] = useState(true);
  
  const [viewType, setViewType] = useState<ViewType>('BROKER');
  
  // Bulk update state
  const [configKey, setConfigKey] = useState('');
  const [configValue, setConfigValue] = useState('');
  const [saving, setSaving] = useState(false);
  const [applyToAgents, setApplyToAgents] = useState(false);
  const [restart, setRestart] = useState(false);
  const [isCurrentlyStatic, setIsCurrentlyStatic] = useState(false);

  const fetchConfigs = async () => {
    setLoading(true);
    try {
      const res = await fetch(`/api/v1/clusters/${id}/config`);
      if (res.ok) setPayload(await res.json());
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

    if (isCurrentlyStatic && (!applyToAgents || !restart)) {
      if (!confirm("Warning: You are updating a STATIC (read-only) configuration. It will NOT take effect unless you persist to server.properties and restart. Proceed anyway?")) {
        return;
      }
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
        setIsCurrentlyStatic(false);
        fetchConfigs();
      } else {
        try {
          const errData = await res.json();
          alert(`Failed to apply configuration: ${errData.message || res.statusText}`);
        } catch {
          alert(`Failed to apply configuration: ${res.statusText}`);
        }
      }
    } catch (e) {
      alert("Error applying configuration. Please try again.");
    } finally {
      setSaving(false);
    }
  };

  const renderConfigurationTable = () => {
    if (!payload) return null;

    if (viewType === 'SERVER') {
      const staticProps = payload.staticConfigs.properties;
      if (!staticProps || Object.keys(staticProps).length === 0) {
        return <div className="empty-state">No static properties found in database.</div>;
      }
      return (
        <div style={{ padding: '1rem' }}>
          <table className="data-table" style={{ fontSize: '0.8125rem', border: '1px solid var(--border-color)', borderRadius: '0.5rem' }}>
            <thead>
              <tr>
                <th style={{ textAlign: 'left', padding: '0.75rem', backgroundColor: '#f9fafb' }}>Property</th>
                <th style={{ textAlign: 'left', padding: '0.75rem', backgroundColor: '#f9fafb' }}>Configured Value</th>
              </tr>
            </thead>
            <tbody>
              {Object.entries(staticProps).sort(([a], [b]) => a.localeCompare(b)).map(([key, value]) => (
                <tr key={key} style={{ borderTop: '1px solid var(--border-color)' }}>
                  <td style={{ fontFamily: 'monospace', color: '#374151', padding: '0.5rem 0.75rem' }}>{key}</td>
                  <td style={{ fontFamily: 'monospace', color: '#2563eb', padding: '0.5rem 0.75rem' }}>{String(value)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
    }

    const dynamicConfigs = payload.dynamicConfigs;
    if (!dynamicConfigs || Object.keys(dynamicConfigs).length === 0) {
      return <div className="empty-state">No configuration data available. Ensure brokers are online.</div>;
    }

    return (
      <div style={{ padding: '1rem', display: 'flex', gap: '1rem', overflowX: 'auto' }}>
        {Object.entries(dynamicConfigs).map(([nodeId, configs]) => (
          <div key={nodeId} style={{ minWidth: '450px', flex: 1, border: '1px solid var(--border-color)', borderRadius: '0.5rem' }}>
            <div style={{ padding: '0.75rem', backgroundColor: '#f9fafb', borderBottom: '1px solid var(--border-color)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <Server size={18} color="#4b5563" />
              <span style={{ fontWeight: 500 }}>
                {viewType === 'CONTROLLER' ? `Controller ${nodeId}` : `Broker ${nodeId}`}
              </span>
            </div>
            <div style={{ maxHeight: '400px', overflowY: 'auto' }}>
              <table className="data-table" style={{ fontSize: '0.8125rem' }}>
                <tbody>
                  {Object.entries(configs)
                    .filter(([k, _]) => !k.startsWith('ssl.') && !k.startsWith('sasl.')) // hide secrets
                    .sort(([a], [b]) => a.localeCompare(b))
                    .map(([key, configObj]) => (
                    <tr 
                      key={key} 
                      onClick={() => {
                        setConfigKey(key);
                        setConfigValue(configObj.value);
                        setIsCurrentlyStatic(configObj.isReadOnly);
                        if (configObj.isReadOnly) {
                          setApplyToAgents(true);
                          setRestart(true);
                        } else {
                          setApplyToAgents(false);
                          setRestart(false);
                        }
                        window.scrollTo({ top: 0, behavior: 'smooth' });
                      }}
                      style={{ cursor: 'pointer', transition: 'background-color 0.15s ease' }}
                      onMouseOver={(e) => e.currentTarget.style.backgroundColor = 'rgba(59, 130, 246, 0.05)'}
                      onMouseOut={(e) => e.currentTarget.style.backgroundColor = 'transparent'}
                    >
                      <td style={{ fontFamily: 'monospace', color: '#374151', padding: '0.5rem 0.75rem' }}>
                        {key}
                        <span style={{ 
                            marginLeft: '8px', 
                            fontSize: '0.65rem', 
                            padding: '2px 6px', 
                            borderRadius: '4px', 
                            background: configObj.isReadOnly ? '#fef3c7' : '#dcfce7', 
                            color: configObj.isReadOnly ? '#92400e' : '#166534', 
                            border: `1px solid ${configObj.isReadOnly ? '#fde68a' : '#bbf7d0'}`,
                            verticalAlign: 'middle',
                            float: 'right'
                          }}>
                          {configObj.isReadOnly ? 'STATIC' : 'DYNAMIC'}
                        </span>
                      </td>
                      <td style={{ fontFamily: 'monospace', color: '#2563eb', padding: '0.5rem 0.75rem', wordBreak: 'break-all', maxWidth: '200px' }}>{configObj.value}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        ))}
      </div>
    );
  };

  return (
    <div className="topics-tab" style={{ maxWidth: '1000px' }}>
      <div className="topics-header">
        <div>
          <h2>Cluster Configuration</h2>
          <p style={{ fontSize: '0.875rem', color: 'var(--text-secondary)', marginTop: '0.25rem' }}>View properties and apply bulk changes dynamically.</p>
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
            <label style={{ display: 'flex', alignItems: 'center', fontSize: '0.875rem', fontWeight: 500, color: '#374151', marginBottom: '0.5rem' }}>
              Configuration Key
              {isCurrentlyStatic ? (
                <span style={{ marginLeft: '8px', fontSize: '0.7rem', padding: '2px 6px', borderRadius: '4px', background: '#fef3c7', color: '#92400e', border: '1px solid #fde68a' }}>Requires Restart</span>
              ) : configKey ? (
                <span style={{ marginLeft: '8px', fontSize: '0.7rem', padding: '2px 6px', borderRadius: '4px', background: '#dcfce7', color: '#166534', border: '1px solid #bbf7d0' }}>Live Updatable</span>
              ) : null}
            </label>
            <input 
              type="text" 
              value={configKey} 
              onChange={e => {
                setConfigKey(e.target.value);
                setIsCurrentlyStatic(false);
              }} 
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
            <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer', fontSize: '0.875rem', color: isCurrentlyStatic ? '#92400e' : 'inherit', fontWeight: isCurrentlyStatic ? 600 : 400 }}>
              <input type="checkbox" checked={applyToAgents} onChange={e => { setApplyToAgents(e.target.checked); if (!e.target.checked) setRestart(false); }} />
              Persist to disk
            </label>
            <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer', fontSize: '0.875rem', opacity: applyToAgents ? 1 : 0.5, color: isCurrentlyStatic ? '#92400e' : 'inherit', fontWeight: isCurrentlyStatic ? 600 : 400 }}>
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
        <div style={{ padding: '1rem', borderBottom: '1px solid var(--border-color)', backgroundColor: 'var(--bg-primary)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <h3 style={{ margin: 0, fontSize: '1rem', fontWeight: 500 }}>Active Configurations</h3>
          <div style={{ position: 'relative' }}>
            <select
              value={viewType}
              onChange={(e) => setViewType(e.target.value as ViewType)}
              style={{
                appearance: 'none',
                padding: '0.5rem 2.5rem 0.5rem 1rem',
                border: '1px solid #d1d5db',
                borderRadius: '0.375rem',
                backgroundColor: 'white',
                fontSize: '0.875rem',
                fontWeight: 500,
                color: '#374151',
                cursor: 'pointer',
                outline: 'none',
                boxShadow: '0 1px 2px 0 rgba(0, 0, 0, 0.05)'
              }}
            >
              <option value="BROKER">Broker Properties</option>
              <option value="CONTROLLER">Controller Properties</option>
              <option value="SERVER">Kafka Server Properties</option>
            </select>
            <ChevronDown size={16} color="#6b7280" style={{ position: 'absolute', right: '0.75rem', top: '50%', transform: 'translateY(-50%)', pointerEvents: 'none' }} />
          </div>
        </div>

        {payload && (
          <div style={{ padding: '0.75rem 1rem', backgroundColor: '#f3f4f6', borderBottom: '1px solid var(--border-color)', display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.8125rem', color: '#4b5563' }}>
            <FileText size={14} />
            <span style={{ fontWeight: 500 }}>Properties File Path:</span>
            <code style={{ backgroundColor: '#e5e7eb', padding: '0.1rem 0.4rem', borderRadius: '0.25rem', color: '#1f2937' }}>
              {(() => {
                const basePath = payload.staticConfigs.filePath;
                // Replace server.properties with the appropriate filename based on view type
                if (viewType === 'BROKER') return basePath.replace('server.properties', 'broker.properties');
                if (viewType === 'CONTROLLER') return basePath.replace('server.properties', 'controller.properties');
                return basePath;
              })()}
            </code>
            <span style={{ 
              marginLeft: 'auto', 
              fontSize: '0.7rem', 
              padding: '2px 8px', 
              borderRadius: '12px', 
              background: viewType === 'SERVER' ? '#f3e8ff' : '#dbeafe', 
              color: viewType === 'SERVER' ? '#7e22ce' : '#1d4ed8', 
              fontWeight: 600,
              border: `1px solid ${viewType === 'SERVER' ? '#d8b4fe' : '#bfdbfe'}`
            }}>
              {viewType === 'SERVER' ? 'STATIC FILE' : 'LIVE MEMORY (AdminClient)'}
            </span>
          </div>
        )}

        {loading ? (
          <div className="empty-state">Fetching active configs from cluster...</div>
        ) : (
          renderConfigurationTable()
        )}
      </div>
    </div>
  );
}
