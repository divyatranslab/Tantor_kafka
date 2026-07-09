import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { MoreVertical, RefreshCw, Trash2, X } from 'lucide-react';
import './Hosts.css';

export function Hosts() {
  const navigate = useNavigate();
  const [hosts, setHosts] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [showEnrollModal, setShowEnrollModal] = useState(false);
  const [openMenuHostId, setOpenMenuHostId] = useState<string | null>(null);

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

  const approveHost = async (id: string) => {
    try {
      const res = await fetch(`/api/v1/ui/hosts/${id}/approve`, { method: 'POST' });
      if (res.ok) {
        const body = await res.json().catch(() => ({}));
        if (body.jobId) navigate(`/jobs/${body.jobId}`);
        else fetchHosts();
      }
    } catch (e) { console.error(e); }
  };

  const deleteHost = async (id: string) => {
    setOpenMenuHostId(null);
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

  const activeHosts = hosts;
  const activeHostIps = new Set(hosts.filter(h => h.status !== 'PENDING').map(host => displayIp(host.ipAddresses)));
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

  return (
    <div className="hosts-page animate-fade-in" onClick={() => setOpenMenuHostId(null)}>
      <header className="page-header flex-between">
        <div>
          <h1>Infrastructure fleet</h1>
          <p>Manage and monitor physical and virtual nodes</p>
        </div>
        <div className="header-actions">
          <button className="btn" onClick={() => setShowEnrollModal(true)}>
            + Agent Connectivity
          </button>
          <button className="btn" onClick={fetchHosts}>
            <RefreshCw size={14} className={loading ? 'spin' : ''} />
            Sync inventory
          </button>
        </div>
      </header>

      <div className="table-container">
        <table className="data-table">
          <thead>
            <tr>
              <th>Status</th>
              <th>Availability</th>
              <th>Agent Name</th>
              <th>Hostname</th>
              <th>IP address</th>
              <th>Agent</th>
              <th>CPU</th>
              <th>Memory</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {activeHosts.length === 0 ? (
              <tr>
                <td colSpan={9}>
                  <div className="empty-state">
                    {loading ? 'Loading connected agents...' : 'No agents connected yet.'}
                  </div>
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
                    <span className={`status-badge ${(host.agentStatus ?? 'offline').toLowerCase()}`}>
                      {host.agentStatus ?? 'OFFLINE'}
                    </span>
                  </td>
                  <td>
                    <div className="availability-cell">
                      <span className={`availability-badge ${host.status === 'AVAILABLE' ? 'available' : host.status === 'PENDING' ? 'warning' : 'occupied'}`}>
                        {host.status === 'OCCUPIED_INTERNAL' ? 'Occupied - Internal Cluster' : 
                         host.status === 'OCCUPIED_EXTERNAL' ? 'Occupied - External Cluster' : 
                         host.status === 'PENDING' ? 'Pending Approval' : 'Available'}
                      </span>
                      {host.status !== 'AVAILABLE' && (
                        <div className="cluster-lock">
                          <span>{host.clusterName || 'Assigned cluster'}</span>
                          <code>{host.kafkaClusterId || host.clusterId}</code>
                        </div>
                      )}
                    </div>
                  </td>
                  <td className="text-secondary">{host.agentName ?? '-'}</td>
                  <td className="font-medium">{host.hostname}</td>
                  <td className="text-secondary">{ip}</td>
                  <td className="text-secondary">
                    <div className="agent-kind-cell">
                      <span>v{host.agentVersion || 'N/A'}</span>
                      <small>{host.agentPath || 'Path unavailable'} · {host.agentStatus || 'OFFLINE'}</small>
                    </div>
                  </td>
                  <td>
                    <div className="metric-bar">
                      <div className="bar-track">
                        <div className={`bar-fill ${cpu > 80 ? 'danger' : 'normal'}`} style={{ width: `${cpu}%` }} />
                      </div>
                      <span>{cpu}%</span>
                    </div>
                  </td>
                  <td>
                    <div className="metric-bar">
                      <div className="bar-track">
                        <div className={`bar-fill ${mem > 85 ? 'warning' : 'normal'}`} style={{ width: `${mem}%` }} />
                      </div>
                      <span>{mem}%</span>
                    </div>
                  </td>
                  <td>
                    {discoveryAgent ? (
                      <span className="discovery-managed-label">Managed from External Clusters</span>
                    ) : (
                    <div className="actions menu-anchor" onClick={e => e.stopPropagation()}>
                      <button
                        className="btn icon-only"
                        title="Node actions"
                        onClick={() => setOpenMenuHostId(openMenuHostId === host.id ? null : host.id)}
                      >
                        <MoreVertical size={16} />
                      </button>
                      {openMenuHostId === host.id && (
                        <div className="host-action-menu">
                          {host.status === 'PENDING' ? (
                            <>
                              <button onClick={() => approveHost(host.id)}>Approve</button>
                              <button className="text-danger" onClick={() => deleteHost(host.id)}>Reject</button>
                            </>
                          ) : host.status === 'OCCUPIED_INTERNAL' ? (
                            <button onClick={() => setHostAvailability(host, true)}>Mark available</button>
                          ) : host.status === 'AVAILABLE' ? (
                            <button onClick={() => setHostAvailability(host, false)}>Mark occupied</button>
                          ) : (
                            <div className="menu-info">Externally Managed</div>
                          )}
                        </div>
                      )}
                    </div>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {showEnrollModal && (
        <div className="modal-overlay" onClick={() => setShowEnrollModal(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <h2>Agent Connectivity</h2>
              <button className="modal-close" onClick={() => setShowEnrollModal(false)}>
                <X size={14} />
              </button>
            </div>

            <p className="modal-section-title">Discovered nodes waiting to connect</p>

            {pendingHosts.length === 0 ? (
              <div className="empty-pending">
                No new nodes discovered. Run the agent script on a VM to discover it.
              </div>
            ) : pendingHosts.map(host => (
              <div key={host.id} className="pending-node">
                <div className="pending-node-info">
                  <p className="name">{host.hostname}</p>
                  <p className="ip">{displayIp(host.ipAddresses)}</p>
                </div>
                <div className="pending-node-actions">
                  <button className="btn btn-primary-action" onClick={() => approveHost(host.id)}>
                    Connect
                  </button>
                  <button className="btn icon-only danger" title="Reject & remove" onClick={() => deleteHost(host.id)}>
                    <Trash2 size={14} />
                  </button>
                </div>
              </div>
            ))}

            <hr className="modal-divider" />
            <div className="modal-footer">
              <button className="btn" onClick={() => setShowEnrollModal(false)}>Close</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
