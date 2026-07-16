import { useState, useEffect } from 'react';
import { RefreshCw, Trash2, X } from 'lucide-react';
import { usePermissions } from '../hooks/usePermissions';
import './Hosts.css';

export function Hosts() {
  const { canManage } = usePermissions();
  const [hosts, setHosts] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [showEnrollModal, setShowEnrollModal] = useState(false);
  const [openMenuHostId, setOpenMenuHostId] = useState<string | null>(null);
  const [selectedPendingIds, setSelectedPendingIds] = useState<Record<string, boolean>>({});
  const [connectingAgents, setConnectingAgents] = useState(false);

  const fetchHosts = async () => {
    setLoading(true);
    try {
      const res = await fetch('/api/v1/ui/hosts');
      if (res.ok) setHosts(await res.json());
    } catch (e) {
      console.error(e);
    }
    setLoading(false);
  };

  const deleteHost = async (id: string) => {
    setOpenMenuHostId(null);
    if (!canManage) return;
    if (!window.confirm('Disconnect this node? It will move back to discovered nodes and can be connected again.')) return;
    try {
      const res = await fetch(`/api/v1/ui/hosts/${id}`, { method: 'DELETE' });
      if (res.ok) {
        fetchHosts();
      } else {
        const errorData = await res.json().catch(() => ({}));
        alert(errorData.message || 'Failed to disconnect node.');
      }
    } catch (e) {
      console.error(e);
      alert('An error occurred while disconnecting the node.');
    }
  };

  const setHostAvailability = async (host: any, available: boolean) => {
    setOpenMenuHostId(null);
    if (!canManage) return;
    try {
      const action = available ? 'mark-available' : 'mark-unavailable';
      const res = await fetch(`/api/v1/ui/hosts/${host.id}/${action}`, { method: 'POST' });
      if (res.ok) {
        fetchHosts();
      } else {
        const body = await res.json().catch(() => ({}));
        alert(body.message || 'Failed to update host availability.');
      }
    } catch (e) {
      console.error(e);
      alert('Network error while updating host availability.');
    }
  };
  useEffect(() => {
    fetchHosts();
    const t = setInterval(fetchHosts, 5000);
    return () => clearInterval(t);
  }, []);

  const parseIpList = (raw: any): string[] => {
    if (Array.isArray(raw)) return raw.map(String).map(ip => ip.trim()).filter(Boolean);
    if (typeof raw === 'string' && raw.startsWith('[')) {
      try {
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed)) return parsed.map(String).map(ip => ip.trim()).filter(Boolean);
      } catch {}
    }
    if (typeof raw === 'string') return raw.split(',').map(ip => ip.trim()).filter(Boolean);
    return [];
  };

  const displayIp = (raw: any) => {
    const ips = parseIpList(raw);
    return ips.find(ip => ip.startsWith('192.168.'))
      || ips.find(ip => !ip.startsWith('127.') && !ip.startsWith('172.'))
      || ips[0]
      || 'Unknown';
  };

  const activeHosts = hosts.filter(h => h.status !== 'PENDING' && h.status !== 'REMOVED');
  const activeHostIps = new Set(activeHosts.map(host => displayIp(host.ipAddresses)));
  const pendingHosts = Array.from(
    hosts
      .filter(h => h.status === 'PENDING' && !activeHostIps.has(displayIp(h.ipAddresses)))
      .reduce<Map<string, any>>((byIp, host) => {
        const ip = displayIp(host.ipAddresses);
        const existing = byIp.get(ip);
        const heartbeat = Date.parse(host.lastHeartbeat || '') || 0;
        const existingHeartbeat = Date.parse(existing?.lastHeartbeat || '') || 0;
        const expectedSuffix = `-${ip.split('.').pop()}`;
        const isCanonicalId = String(host.id || '').endsWith(expectedSuffix);
        const existingIsCanonicalId = String(existing?.id || '').endsWith(expectedSuffix);

        if (
          !existing
          || (isCanonicalId && !existingIsCanonicalId)
          || (isCanonicalId === existingIsCanonicalId && heartbeat > existingHeartbeat)
        ) {
          byIp.set(ip, host);
        }
        return byIp;
      }, new Map<string, any>())
      .values(),
  );
  const selectedCount = pendingHosts.filter(host => selectedPendingIds[host.id]).length;
  const allPendingSelected = pendingHosts.length > 0 && selectedCount === pendingHosts.length;

  const togglePendingHost = (id: string) => {
    if (!canManage) return;
    setSelectedPendingIds(prev => ({ ...prev, [id]: !prev[id] }));
  };

  const toggleAllPendingHosts = () => {
    if (!canManage) return;
    if (allPendingSelected) {
      setSelectedPendingIds({});
      return;
    }
    setSelectedPendingIds(Object.fromEntries(pendingHosts.map(host => [host.id, true])));
  };

  const connectSelectedAgents = async () => {
    if (!canManage) return;
    const selectedIds = pendingHosts.filter(host => selectedPendingIds[host.id]).map(host => host.id);
    if (selectedIds.length === 0) return;
    setConnectingAgents(true);
    try {
      const results = await Promise.allSettled(
        selectedIds.map(id => fetch(`/api/v1/ui/hosts/${id}/approve`, { method: 'POST' }))
      );
      const failed = results.filter(result => result.status === 'rejected'
        || (result.status === 'fulfilled' && !result.value.ok)).length;
      if (failed > 0) {
        alert(`${failed} agent${failed === 1 ? '' : 's'} could not be connected. Refreshing the list now.`);
      }
      setSelectedPendingIds({});
      await fetchHosts();
    } finally {
      setConnectingAgents(false);
    }
  };

  return (
    <div className="hosts-page animate-fade-in" onClick={() => setOpenMenuHostId(null)}>
      <header className="page-header flex-between">
        <div>
          <h1>Hosts</h1>
          <p>Manage and monitor physical and virtual nodes</p>
        </div>
        <div className="header-actions">
          <button className="btn icon-only round" onClick={fetchHosts} title="Sync inventory">
            <RefreshCw size={16} className={loading ? 'spin' : ''} />
          </button>
          {canManage && (
            <button className="btn btn-primary-action" style={{ background: '#3E1363', borderColor: '#3E1363' }} onClick={() => setShowEnrollModal(true)}>
              + Agent Connectivity
            </button>
          )}
        </div>
      </header>

      {activeHosts.length === 0 && !loading ? (
        <div className="hosts-empty-state">
          <div className="empty-illustration">
            <svg width="102" height="74" viewBox="0 0 102 74" fill="none" xmlns="http://www.w3.org/2000/svg">
              <rect x="0.5" y="0.5" width="101" height="21" rx="2.5" fill="white" stroke="#E5E7EB"/>
              <rect x="10" y="8" width="14" height="2" rx="1" fill="#8B5CF6"/>
              <rect x="10" y="13" width="28" height="2" rx="1" fill="#D1D5DB"/>
              <rect x="60" y="8" width="14" height="2" rx="1" fill="#8B5CF6"/>
              <rect x="60" y="13" width="28" height="2" rx="1" fill="#D1D5DB"/>
              <rect x="0.5" y="26.5" width="101" height="21" rx="2.5" fill="white" stroke="#E5E7EB"/>
              <rect x="10" y="34" width="14" height="2" rx="1" fill="#8B5CF6"/>
              <rect x="10" y="39" width="28" height="2" rx="1" fill="#D1D5DB"/>
              <rect x="60" y="34" width="14" height="2" rx="1" fill="#D1D5DB"/>
              <rect x="60" y="39" width="28" height="2" rx="1" fill="#D1D5DB"/>
              <rect x="0.5" y="52.5" width="101" height="21" rx="2.5" fill="white" stroke="#E5E7EB"/>
              <rect x="10" y="60" width="14" height="2" rx="1" fill="#8B5CF6"/>
              <rect x="10" y="65" width="28" height="2" rx="1" fill="#D1D5DB"/>
              <rect x="60" y="60" width="14" height="2" rx="1" fill="#8B5CF6"/>
              <rect x="60" y="65" width="28" height="2" rx="1" fill="#D1D5DB"/>
            </svg>
          </div>
          <h3>No hosts at this moment</h3>
          <p>Run the agent script on a node to discover and connect it here.</p>
        </div>
      ) : (
      <div className="table-container">
        <table className="data-table">
          <thead>
            <tr>
              <th style={{ width: '100px' }}>Status</th>
              <th style={{ width: '110px' }}>Availability</th>
              <th style={{ width: '160px' }}>Agent name</th>
              <th style={{ width: '160px' }}>Host name</th>
              <th style={{ width: '120px' }}>IP address</th>
              <th style={{ width: '160px' }}>Agent</th>
              <th style={{ width: '140px' }}>CPU</th>
              <th style={{ width: '140px' }}>Memory</th>
              <th></th>
              <th style={{ width: '50px', textAlign: 'center', position: 'sticky', right: 0, background: '#F9F9F9', zIndex: 2 }}></th>
            </tr>
          </thead>
          <tbody>
            {loading && activeHosts.length === 0 ? (
              <tr>
                <td colSpan={9}>
                  <div className="empty-state">Loading connected agents...</div>
                </td>
              </tr>
            ) : activeHosts.map(host => {
              const ip = displayIp(host.ipAddresses);
              const cpu = host.cpuUsagePct ? Math.round(host.cpuUsagePct) : 0;
              const mem = host.memTotalMb > 0
                ? Math.round((host.memUsedMb / host.memTotalMb) * 100)
                : 0;
              const discoveryAgent = host.agentType === 'KAFKA_DISCOVERY';

              return (
                <tr key={host.id}>
                  <td>
                    <span className={`host-status-badge ${(host.agentStatus ?? 'offline').toLowerCase()}`}>
                      {host.agentStatus ? host.agentStatus.charAt(0) + host.agentStatus.slice(1).toLowerCase() : 'Offline'}
                    </span>
                  </td>
                  <td>
                    <div className="availability-cell">
                      <span className={`availability-badge ${host.status === 'OFFLINE' ? 'unavailable' : 'available'}`}>
                        {host.status === 'OCCUPIED_INTERNAL' ? 'Occupied' :
                         host.status === 'OCCUPIED_EXTERNAL' ? 'Occupied' :
                         host.status === 'OFFLINE' ? 'Unavailable' :
                         host.status === 'REMOVED' ? 'Removed' : 'Available'}
                      </span>
                    </div>
                  </td>
                  <td>{host.agentName ?? '-'}</td>
                  <td>{host.hostname}</td>
                  <td>{ip}</td>
                  <td>
                    <div className="agent-kind-cell">
                      <span>V{host.agentVersion || 'N/A'}</span>
                      <small>{host.agentPath || 'Path unavailable'}</small>
                    </div>
                  </td>
                  <td>
                    <div className="metric-bar-stacked">
                      <span>{cpu}%</span>
                      <div className="bar-track">
                        <div className="bar-fill normal" style={{ width: `${cpu}%` }} />
                      </div>
                    </div>
                  </td>
                  <td>
                    <div className="metric-bar-stacked">
                      <span>{mem}%</span>
                      <div className="bar-track">
                        <div className="bar-fill normal" style={{ width: `${mem}%` }} />
                      </div>
                    </div>
                  </td>
                  <td></td>
                  <td style={{ textAlign: 'center', position: 'sticky', right: 0, background: '#FFFFFF', zIndex: 1 }}>
                    {discoveryAgent ? (
                      <span className="discovery-managed-label">Managed from External Clusters</span>
                    ) : canManage ? (
                    <div className="actions menu-anchor" onClick={e => e.stopPropagation()} style={{ display: 'inline-block' }}>
                      <button
                        style={{ border: 'none', background: 'transparent', padding: '8px', cursor: 'pointer', color: '#818181', display: 'inline-flex', alignItems: 'center', justifyContent: 'center' }}
                        title="Node actions"
                        onClick={() => setOpenMenuHostId(openMenuHostId === host.id ? null : host.id)}
                      >
                        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                          <circle cx="12" cy="6" r="1.5" fill="#818181"/>
                          <circle cx="12" cy="12" r="1.5" fill="#818181"/>
                          <circle cx="12" cy="18" r="1.5" fill="#818181"/>
                        </svg>
                      </button>
                      {openMenuHostId === host.id && (
                        <div className="host-action-menu">
                          {host.status === 'OCCUPIED_INTERNAL' ? (
                            <button onClick={() => setHostAvailability(host, true)}>Mark available</button>
                          ) : host.status === 'AVAILABLE' ? (
                            <button onClick={() => setHostAvailability(host, false)}>Mark occupied</button>
                          ) : (
                            <div className="menu-info">Externally Managed</div>
                          )}
                        </div>
                      )}
                    </div>
                    ) : (
                      <span className="discovery-managed-label">View only</span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      )}

      {canManage && showEnrollModal && (
        <div className="modal-overlay" onClick={() => setShowEnrollModal(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <h2>Agent Connectivity</h2>
              <button className="modal-close" onClick={() => setShowEnrollModal(false)}>
                <X size={14} />
              </button>
            </div>

            {pendingHosts.length === 0 ? (
              <>
                <div className="empty-pending">
                  <h3>No new nodes discovered</h3>
                  <p>Run the agent script on a VM to discover it.</p>
                </div>
                <hr className="modal-divider" />
                <div className="modal-footer right">
                  <button className="btn btn-outline" onClick={() => setShowEnrollModal(false)}>Cancel</button>
                </div>
              </>
            ) : (
              <>
                <div className="modal-section-header">
                  <p className="modal-section-title">Discovered Nodes</p>
                  <label className="pending-select-all">
                    <input type="checkbox" checked={allPendingSelected} onChange={toggleAllPendingHosts} />
                    <span>Select all</span>
                  </label>
                </div>
                {pendingHosts.map(host => (
                  <div key={host.id} className={`pending-node selectable ${selectedPendingIds[host.id] ? 'selected' : ''}`} onClick={() => togglePendingHost(host.id)}>
                    <label className="pending-node-select" onClick={event => event.stopPropagation()}>
                      <input
                        type="checkbox"
                        checked={!!selectedPendingIds[host.id]}
                        onChange={() => togglePendingHost(host.id)}
                      />
                    </label>
                    <div className="pending-node-info">
                      <p className="name">{host.agentName || host.hostname}</p>
                      <p className="ip">{displayIp(host.ipAddresses)} - {host.agentPath || 'Path unavailable'}</p>
                    </div>
                    <div className="pending-node-actions">
                      <button className="btn icon-only danger" title="Reject & remove" onClick={(event) => { event.stopPropagation(); deleteHost(host.id); }}>
                        <Trash2 size={14} />
                      </button>
                    </div>
                  </div>
                ))}
                <hr className="modal-divider" />
                <div className="modal-footer right">
                  <button className="btn btn-outline" onClick={() => setShowEnrollModal(false)}>Cancel</button>
                  <button className="btn btn-primary-action" disabled={selectedCount === 0 || connectingAgents} onClick={connectSelectedAgents}>
                    {connectingAgents ? 'Connecting...' : 'Connect'}
                  </button>
                </div>
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
