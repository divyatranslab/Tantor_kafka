import { Check, CheckCircle2, ChevronDown, FileText, Loader2, MoreVertical, Play, Search, Trash2, XCircle } from 'lucide-react';
import { useClusterDeployment } from '../../hooks/useClusterDeployment';
import { AnchoredMenu } from '../AnchoredMenu';
import { displayIp } from '../../hooks/useClusterDeployment';

type NodeSelectionStepProps = {
  hook: ReturnType<typeof useClusterDeployment>;
};

const CustomRefreshIcon = ({ size = 20, color = '#818181', className = '' }: { size?: number, color?: string, className?: string }) => (
  <svg width={size} height={size} viewBox='0 0 24 24' fill='none' stroke={color} strokeWidth='1.25' strokeLinecap='round' strokeLinejoin='round' className={className}>
    <path d='M 12 5 A 7 7 0 0 1 17 17' />
    <path d='M 18 13 L 17 17 L 21 16' />
    <path d='M 12 19 A 7 7 0 0 1 7 7' />
    <path d='M 6 11 L 7 7 L 3 8' />
  </svg>
);

export function NodeSelectionStep({ hook }: NodeSelectionStepProps) {
  const {
    setShowEnrollModal,
    loadHosts,
    loadingHosts,
    dropdownRef,
    setNodeDropdownOpen,
    nodeDropdownOpen,
    selectedNodeIds,
    nodeSearch,
    setNodeSearch,
    filteredHosts,
    toggleNodeSelection,
    selectedHosts,
    checkHostPorts,
    portCheckResults,
    setHoveredPortCheckHostId,
    hoveredPortCheckHostId,
    rolesByHost,
    defaultRoleForMode,
    openRoleMenuHostId,
    setOpenRoleMenuHostId,
    roleMenuAnchor,
    setRoleMenuAnchor,
    roleOptions,
    deploymentMode,
    setRolesByHost,
    setPrereqResults,
    setConfigModalHostId,
    removeNode,
  } = hook;

  return (
    <section className="cd-panel">
      <div className="cd-panel-title">
        <h2>Nodes & Roles</h2>
        <button className="cd-add-node-btn" onClick={() => setShowEnrollModal(true)}>
          + Add Node
        </button>
        <button className="cd-refresh-icon" onClick={loadHosts} title="Refresh">
          <CustomRefreshIcon size={14} className={loadingHosts ? 'spin' : ''} />
        </button>
      </div>

      <div className="cd-node-picker-container" style={{ display: 'flex', flexDirection: 'column', gap: '8px', alignSelf: 'stretch', marginBottom: '16px' }}>
        <span style={{ fontFamily: 'Satoshi, sans-serif', fontSize: '14px', fontWeight: 500, color: '#332849' }}>Select node</span>
        <div className="cd-node-picker" ref={dropdownRef}>
          <button className="cd-node-trigger" onClick={() => {
            setNodeDropdownOpen(open => !open);
          }}>
            <span>{selectedNodeIds.length ? `${selectedNodeIds.length} node${selectedNodeIds.length > 1 ? 's' : ''} selected` : 'Select'}</span>
            <ChevronDown size={16} />
          </button>
          {nodeDropdownOpen && dropdownRef.current && (
            <AnchoredMenu
              anchor={dropdownRef.current}
              className="cd-node-menu"
              onClose={() => setNodeDropdownOpen(false)}
              align="start"
              matchAnchorWidth
            >
              <div className="cd-search">
                <Search size={15} />
                <input value={nodeSearch} onChange={e => setNodeSearch(e.target.value)} placeholder="Search hostname or IP" autoFocus />
              </div>
              <div className="cd-node-options">
                {filteredHosts.map(host => {
                  const disabled = host.status !== 'AVAILABLE' || host.available === false;
                  const checked = selectedNodeIds.includes(host.id);
                  return (
                    <button
                      key={host.id}
                      className={`cd-node-option ${checked ? 'checked' : ''}`}
                      disabled={disabled}
                      onClick={() => toggleNodeSelection(host.id)}
                    >
                      <span className="cd-checkbox">{checked && <Check size={12} strokeWidth={3} />}</span>
                      <span className="cd-node-info">
                        <strong>{host.hostname}</strong>
                        <small>{displayIp(host)} - {disabled ? (host.available === false ? 'Kafka Already Deployed' : host.status) : '/srv/tantor-agent/tantor-agent-linux'}</small>
                      </span>
                    </button>
                  );
                })}
              </div>
            </AnchoredMenu>
          )}
        </div>
      </div>

      <div className="cd-selected-node-list">
        {selectedHosts.map(host => (
          <div className="cd-selected-node" key={host.id} style={{ display: 'flex', flexDirection: 'column', gap: '10px', background: '#FFFFFF', borderRadius: '8px', padding: '10px 16px', border: 'none' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', width: '100%' }}>
              <div className="cd-node-main" style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: '4px' }}>
                <strong style={{ fontFamily: 'Satoshi, sans-serif', fontWeight: 500, fontSize: '14px', lineHeight: '19px', color: '#332849', margin: 0 }}>{host.hostname}</strong>
                <span style={{ fontFamily: 'Satoshi, sans-serif', fontWeight: 400, fontSize: '14px', lineHeight: '19px', color: '#818181', margin: 0 }}>{displayIp(host)} - /srv/tantor-agent/tantor-agent-linux</span>
              </div>
              <div className="cd-node-actions" style={{ display: 'flex', alignItems: 'center', gap: '8px', position: 'relative' }}>
                <button
                  className="cd-figma-action-btn"
                  onClick={() => checkHostPorts(host.id)}
                  disabled={portCheckResults[host.id]?.status === 'RUNNING'}
                  onMouseEnter={() => setHoveredPortCheckHostId(host.id)}
                  onMouseLeave={() => setHoveredPortCheckHostId(null)}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '6px',
                    cursor: portCheckResults[host.id]?.status === 'RUNNING' ? 'not-allowed' : 'pointer'
                  }}
                >
                  <span>
                    {portCheckResults[host.id]?.status === 'RUNNING'
                      ? 'Checking...'
                      : portCheckResults[host.id]?.status === 'SUCCESS'
                      ? 'Ports OK'
                      : portCheckResults[host.id]?.status === 'FAILED'
                      ? 'Ports Failed'
                      : 'Check Ports'}
                  </span>
                  {portCheckResults[host.id]?.status === 'RUNNING' ? (
                    <Loader2 size={12} className="spin" style={{ color: '#3E1363' }} />
                  ) : portCheckResults[host.id]?.status === 'SUCCESS' ? (
                    <CheckCircle2 size={14} style={{ color: '#069B68' }} />
                  ) : portCheckResults[host.id]?.status === 'FAILED' ? (
                    <XCircle size={14} style={{ color: '#E15252' }} />
                  ) : (
                    <Play size={10} fill="#3E1363" style={{ transform: 'none' }} />
                  )}
                </button>

                {hoveredPortCheckHostId === host.id && portCheckResults[host.id] && (
                  <div
                    style={{
                      position: 'absolute',
                      top: '40px',
                      left: '0',
                      zIndex: 1000,
                      width: '240px',
                      background: '#FAF8FF',
                      border: '1px solid #CCCCCC',
                      borderRadius: '8px',
                      padding: '12px',
                      boxShadow: '0px 4px 12px rgba(0, 0, 0, 0.08)',
                      display: 'flex',
                      flexDirection: 'column',
                      gap: '8px',
                      fontFamily: 'Satoshi, sans-serif',
                      fontSize: '13px',
                      color: '#332849',
                      pointerEvents: 'none',
                      textAlign: 'left',
                    }}
                  >
                    {(() => {
                      const result = portCheckResults[host.id];
                      const log = result.logOutput || '';
                      const lines = log.split('\n');
                      const available: number[] = [];
                      const unavailable: number[] = [];

                      lines.forEach(line => {
                        const match = line.match(/Port (\d+):\s*(Available|Unavailable)/i);
                        if (match) {
                          const port = parseInt(match[1], 10);
                          if (match[2].toLowerCase() === 'available') {
                            available.push(port);
                          } else {
                            unavailable.push(port);
                          }
                        }
                      });

                      if (result.status === 'SUCCESS') {
                        return (
                          <>
                            <div style={{ fontWeight: 600, color: '#069B68' }}>All ports available</div>
                            {available.map(p => (
                              <div key={p} style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                                <span style={{ color: '#069B68' }}>•</span>
                                <span>Port {p}</span>
                              </div>
                            ))}
                          </>
                        );
                      }

                      return (
                        <>
                          {unavailable.length > 0 && (
                            <div>
                              <div style={{ fontWeight: 600, color: '#332849', marginBottom: '4px' }}>Unavailable (in use):</div>
                              {unavailable.map(p => (
                                <div key={p} style={{ display: 'flex', alignItems: 'center', gap: '6px', paddingLeft: '4px' }}>
                                  <span style={{ color: '#E15252' }}>•</span>
                                  <span>Port {p}</span>
                                </div>
                              ))}
                            </div>
                          )}
                          {available.length > 0 && (
                            <div style={{ marginTop: '4px' }}>
                              <div style={{ fontWeight: 600, color: '#332849', marginBottom: '4px' }}>Available (free):</div>
                              {available.map(p => (
                                <div key={p} style={{ display: 'flex', alignItems: 'center', gap: '6px', paddingLeft: '4px' }}>
                                  <span style={{ color: '#069B68' }}>•</span>
                                  <span>Port {p}</span>
                                </div>
                              ))}
                            </div>
                          )}
                          {unavailable.length === 0 && available.length === 0 && (
                            <div style={{ color: '#E15252' }}>{result.errorMsg || 'Failed to check ports'}</div>
                          )}
                        </>
                      );
                    })()}
                  </div>
                )}
                <div className="cd-role-menu-wrap" style={{ position: 'relative' }}>
                  <button
                    className="cd-figma-action-btn"
                    onClick={event => {
                      const opening = openRoleMenuHostId !== host.id;
                      setOpenRoleMenuHostId(opening ? host.id : null);
                      setRoleMenuAnchor(opening ? event.currentTarget : null);
                    }}
                  >
                    <span>{(rolesByHost[host.id] || defaultRoleForMode).replace('_', ' + ').replace(/\b\w/g, l => l.toUpperCase())}</span>
                    <MoreVertical size={14} />
                  </button>
                  {openRoleMenuHostId === host.id && roleMenuAnchor && (
                    <AnchoredMenu anchor={roleMenuAnchor} className="cd-role-menu" onClose={() => { setOpenRoleMenuHostId(null); setRoleMenuAnchor(null); }}>
                      {roleOptions.filter(r => r.id !== 'separate').map(role => {
                        const currentRole = rolesByHost[host.id] || defaultRoleForMode;
                        let isActive = currentRole === role.id;

                        if (deploymentMode === 'kraft') {
                          if (role.id === 'broker') isActive = currentRole === 'broker' || currentRole === 'separate';
                          if (role.id === 'controller') isActive = currentRole === 'controller' || currentRole === 'separate';
                        }

                        return (
                          <label
                            key={role.id}
                            className={`cd-role-label ${isActive ? 'active' : ''}`}
                          >
                            <input
                              type="checkbox"
                              checked={isActive}
                              onChange={() => {
                                let nextRole = role.id;

                                if (deploymentMode === 'kraft') {
                                  if (role.id === 'broker') {
                                    if (currentRole === 'controller') nextRole = 'separate';
                                    else if (currentRole === 'separate') nextRole = 'controller';
                                  } else if (role.id === 'controller') {
                                    if (currentRole === 'broker') nextRole = 'separate';
                                    else if (currentRole === 'separate') nextRole = 'broker';
                                  }
                                }

                                setRolesByHost(prev => ({ ...prev, [host.id]: nextRole }));
                                setPrereqResults({});
                                hook.setPortCheckResults({});
                              }}
                            />
                            <span>{role.label}</span>
                          </label>
                        );
                      })}
                    </AnchoredMenu>
                  )}
                </div>
                <button className="cd-figma-action-btn" onClick={() => setConfigModalHostId(host.id)}>
                  <FileText size={14} />
                  Configuration
                </button>
                <button className="cd-figma-icon-btn" onClick={() => removeNode(host.id)} title="Remove node" style={{ width: '24px', height: '24px', padding: 0 }}>
                  <Trash2 size={16} />
                </button>
              </div>
            </div>

          </div>
        ))}
      </div>
    </section>
  );
}
