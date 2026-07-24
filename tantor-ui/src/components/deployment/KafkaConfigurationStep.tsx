import { MoreVertical, X } from 'lucide-react';
import { useClusterDeployment } from '../../hooks/useClusterDeployment';
import type { PropertyRow } from '../../types/clusterDeployment.types';
import { displayIp } from '../../hooks/useClusterDeployment';

type KafkaConfigurationStepProps = {
  hook: ReturnType<typeof useClusterDeployment>;
};

function PropertyTable({
  rows,
  hostIp,
  onUseHostIp,
  onChange,
}: {
  rows: PropertyRow[];
  hostIp: string;
  onUseHostIp: () => void;
  onChange: (key: string, value: string) => void;
}) {
  return (
    <div className="cd-property-table-wrap">
      <table className="cd-property-table">
        <thead>
          <tr>
            <th style={{ width: hostIp ? '33.33%' : '64%' }}>Key</th>
            <th style={{ width: hostIp ? '33.33%' : '36%' }}>Value</th>
            {hostIp && <th style={{ width: '33.33%' }}>Action</th>}
          </tr>
        </thead>
        <tbody>
          {rows.map(row => (
            <tr key={row.key} className={row.required && !row.value.trim() ? 'required-missing' : ''}>
              <td>
                <span className="cd-prop-key">{row.key}</span>
                {row.required && <small><b>*</b> Required</small>}
              </td>
              <td>
                <div style={{ display: 'flex', alignItems: 'center', gap: '24px' }}>
                  <input
                    value={row.value}
                    onChange={e => onChange(row.key, e.target.value)}
                    placeholder={row.required ? 'Required before preview' : ''}
                    style={{ flex: 1 }}
                  />
                  {!hostIp && (
                    <button type="button" className="cd-icon-btn" style={{ padding: '0', background: 'transparent', border: 'none', display: 'flex', alignItems: 'center' }} onClick={() => {
                      const next = window.prompt(`Edit ${row.key}`, row.value);
                      if (next !== null) onChange(row.key, next);
                    }}>
                      <MoreVertical size={24} color="#818181" />
                    </button>
                  )}
                </div>
              </td>
              {hostIp && (
                <td>
                  <button type="button" onClick={onUseHostIp} style={{ display: 'inline-flex', alignItems: 'center', justifyContent: 'center', height: '40px', boxSizing: 'border-box', background: '#FFFFFF', border: '1px solid #CCCCCC', borderRadius: '8px', padding: '10px 16px', color: '#332849', fontSize: '14px', fontFamily: 'Satoshi, sans-serif', fontWeight: 400, whiteSpace: 'nowrap' }}>
                    Use {hostIp}
                  </button>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function KafkaConfigurationStep({ hook }: KafkaConfigurationStepProps) {
  const {
    configModalHost,
    setConfigModalHostId,
    allRoleOptions,
    rolesByHost,
    defaultRoleForMode,
    configKindsForRole,
    serviceConfigFor,
    configFileName,
    updateServiceConfig,
    updatePropertyValue,
    ipRowKeyForKind,
    defaultHeapForKind,
    commonConfigOpen,
    setCommonConfigOpen,
    deploymentMode,
    commonConfigKinds,
    commonConfigKind,
    setCommonConfigKind,
    commonConfigs,
    updateCommonConfigValue,
  } = hook;

  return (
    <>
      {configModalHost && (
        <div className="cd-modal-backdrop" onClick={() => setConfigModalHostId(null)}>
          <div className="cd-config-modal" onClick={e => e.stopPropagation()}>
            <div className="cd-config-modal-header">
              <div>
                <h2>Configuration</h2>
                <p>{configModalHost.hostname} - {allRoleOptions.find(role => role.id === (rolesByHost[configModalHost.id] || defaultRoleForMode))?.label}</p>
              </div>
              <button className="cd-icon-btn" onClick={() => setConfigModalHostId(null)} title="Close configuration">
                <X size={16} />
              </button>
            </div>

            <div className="cd-config-modal-body">
              {configKindsForRole(rolesByHost[configModalHost.id] || defaultRoleForMode).map(kind => {
                const cfg = serviceConfigFor(configModalHost.id, kind);
                return (
                  <div className="cd-node-config-editor" key={kind}>
                    <div className="cd-node-config-top">
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '2px', margin: 0 }}>
                        <h3 style={{ fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '14px', color: '#332849', margin: 0 }}>{configFileName(kind)}</h3>
                        <p style={{ fontFamily: 'Satoshi, sans-serif', fontWeight: 400, fontSize: '14px', color: '#332849', margin: 0 }}>Fill the node-specific values for this service.</p>
                      </div>
                      <div className="cd-config-controls">
                        <label className="cd-heap-field">
                          <span>Heap</span>
                          <input
                            value={cfg.heapSize}
                            onChange={e => updateServiceConfig(configModalHost.id, kind, { heapSize: e.target.value })}
                            placeholder={defaultHeapForKind(kind)}
                          />
                        </label>
                      </div>
                    </div>
                    <PropertyTable
                      rows={cfg.rows}
                      hostIp={displayIp(configModalHost)}
                      onUseHostIp={() => updatePropertyValue(configModalHost.id, kind, ipRowKeyForKind(kind), displayIp(configModalHost))}
                      onChange={(key, value) => updatePropertyValue(configModalHost.id, kind, key, value)}
                    />
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      )}
      {commonConfigOpen && (
        <div className="cd-modal-backdrop" onClick={() => setCommonConfigOpen(false)}>
          <div className="cd-config-modal common" onClick={e => e.stopPropagation()}>
            <div className="cd-config-modal-header">
              <div>
                <h2>Common Configuration</h2>
                <p>{deploymentMode === 'kraft' ? 'KRaft' : 'ZooKeeper'} properties shared across selected nodes.</p>
              </div>
              <button className="cd-icon-btn" onClick={() => setCommonConfigOpen(false)} title="Close common configuration">
                <X size={16} />
              </button>
            </div>
            <div className="cd-config-modal-body">
              <div className="cd-config-tabs">
                {commonConfigKinds.map(kind => (
                  <button
                    key={kind}
                    className={commonConfigKind === kind ? 'active' : ''}
                    onClick={() => setCommonConfigKind(kind)}
                  >
                    {configFileName(kind)}
                  </button>
                ))}
              </div>
              <PropertyTable
                rows={commonConfigs[commonConfigKind]}
                hostIp=""
                onUseHostIp={() => {}}
                onChange={(key, value) => updateCommonConfigValue(commonConfigKind, key, value)}
              />
            </div>
            <div className="cd-config-modal-footer" style={{ display: 'flex', justifyContent: 'flex-end', gap: '16px', borderTop: 'none', padding: '16px 24px', boxShadow: '0px -4px 9px rgba(0, 0, 0, 0.1)' }}>
              <button className="cd-secondary-btn" onClick={() => setCommonConfigOpen(false)} style={{ border: '1px solid #8E77BB', color: '#8E77BB', background: '#FFFFFF', borderRadius: '8px', padding: '10px 16px', fontSize: '14px', fontWeight: 500 }}>Cancel</button>
              <button className="cd-primary-btn" onClick={() => setCommonConfigOpen(false)} style={{ background: '#CBC0E0', color: '#FFFFFF', border: 'none', borderRadius: '8px', padding: '10px 16px', fontSize: '14px', fontWeight: 500 }}>Save</button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
