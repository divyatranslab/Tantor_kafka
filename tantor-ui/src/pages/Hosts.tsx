import { useState, useEffect } from 'react';
import { MoreVertical, RefreshCw, Trash2, X } from 'lucide-react';
import './Hosts.css';

type PrereqModalState = {
  host: any;
  taskId?: string;
  status: string;
  logOutput: string;
  errorMsg: string;
  loading: boolean;
};

export function Hosts() {
  const [hosts, setHosts] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [showEnrollModal, setShowEnrollModal] = useState(false);
  const [openMenuHostId, setOpenMenuHostId] = useState<string | null>(null);
  const [prereqModal, setPrereqModal] = useState<PrereqModalState | null>(null);

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
      if (res.ok) fetchHosts();
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
  const startPrerequisiteCheck = async (host: any) => {
    setOpenMenuHostId(null);
    setPrereqModal({
      host,
      status: 'QUEUING',
      logOutput: 'Queuing prerequisite check on the agent...',
      errorMsg: '',
      loading: true,
    });

    try {
      const res = await fetch(`/api/v1/ui/hosts/${host.id}/check-prerequisites`, { method: 'POST' });
      const body = await res.json().catch(() => ({}));
      if (!res.ok) {
        setPrereqModal({
          host,
          status: 'FAILED',
          logOutput: '',
          errorMsg: body.message || 'Failed to queue prerequisite check.',
          loading: false,
        });
        return;
      }
      setPrereqModal({
        host,
        taskId: body.taskId,
        status: 'PENDING',
        logOutput: 'Task queued. Waiting for agent to pick it up...',
        errorMsg: '',
        loading: true,
      });
    } catch (e) {
      setPrereqModal({
        host,
        status: 'FAILED',
        logOutput: '',
        errorMsg: 'Network error while queuing prerequisite check.',
        loading: false,
      });
    }
  };

  useEffect(() => {
    fetchHosts();
    const t = setInterval(fetchHosts, 5000);
    return () => clearInterval(t);
  }, []);

  useEffect(() => {
    if (!prereqModal?.taskId || !prereqModal.loading) return;
    const poll = setInterval(async () => {
      try {
        const res = await fetch(`/api/v1/ui/hosts/${prereqModal.host.id}/check-prerequisites/${prereqModal.taskId}`);
        if (!res.ok) return;
        const body = await res.json();
        setPrereqModal(prev => prev ? {
          ...prev,
          status: body.status || prev.status,
          logOutput: body.logOutput || prev.logOutput,
          errorMsg: body.errorMsg || '',
          loading: ['PENDING', 'IN_PROGRESS', 'RUNNING'].includes(String(body.status || '').toUpperCase()),
        } : prev);
      } catch (e) {
        console.error(e);
      }
    }, 1500);
    return () => clearInterval(poll);
  }, [prereqModal?.taskId, prereqModal?.host?.id, prereqModal?.loading]);

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

  const activeHosts = hosts.filter(h => h.status !== 'PENDING');
  const pendingHosts = hosts.filter(h => h.status === 'PENDING');

  return (
    <div className="hosts-page animate-fade-in" onClick={() => setOpenMenuHostId(null)}>
      <header className="page-header flex-between">
        <div>
          <h1>Infrastructure fleet</h1>
          <p>Manage and monitor physical and virtual nodes</p>
        </div>
        <div className="header-actions">
          <button className="btn" onClick={() => setShowEnrollModal(true)}>
            + Add node
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
              <th>Hostname</th>
              <th>IP address</th>
              <th>Agent version</th>
              <th>CPU</th>
              <th>Memory</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {activeHosts.length === 0 ? (
              <tr>
                <td colSpan={8}>
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
              const available = host.available !== false;

              return (
                <tr key={host.id}>
                  <td>
                    <span className={`status-badge ${(host.status ?? 'offline').toLowerCase()}`}>
                      {host.status ?? 'OFFLINE'}
                    </span>
                  </td>
                  <td>
                    <div className="availability-cell">
                      <span className={`availability-badge ${available ? 'available' : 'unavailable'}`}>
                        {available ? 'Available' : 'Unavailable'}
                      </span>
                      {!available && (
                        <div className="cluster-lock">
                          <span>{host.clusterName || 'Assigned cluster'}</span>
                          <code>{host.clusterId}</code>
                        </div>
                      )}
                    </div>
                  </td>
                  <td className="font-medium">{host.hostname}</td>
                  <td className="text-secondary">{ip}</td>
                  <td className="text-secondary">{host.agentVersion || 'N/A'}</td>
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
                          <button onClick={() => startPrerequisiteCheck(host)}>Check prerequisite</button>
                          {host.status === 'UNAVAILABLE' ? (
                            <button onClick={() => setHostAvailability(host, true)}>Mark available</button>
                          ) : (
                            <button onClick={() => setHostAvailability(host, false)}>Mark unavailable</button>
                          )}
                          <button className="danger" onClick={() => deleteHost(host.id)}>
                            <Trash2 size={13} /> Remove node
                          </button>
                        </div>
                      )}
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {prereqModal && (
        <div className="modal-overlay" onClick={() => setPrereqModal(null)}>
          <div className="modal prerequisite-modal" onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <div>
                <h2>Prerequisite check</h2>
                <p className="modal-subtitle">{prereqModal.host.hostname} - {displayIp(prereqModal.host.ipAddresses)}</p>
              </div>
              <button className="modal-close" onClick={() => setPrereqModal(null)}>
                <X size={14} />
              </button>
            </div>
            <div className={`prereq-status ${(prereqModal.status || '').toLowerCase()}`}>
              {prereqModal.loading ? 'Running' : prereqModal.status}
            </div>
            <pre className="terminal-output">
{prereqModal.errorMsg ? `${prereqModal.errorMsg}\n\n` : ''}{prereqModal.logOutput || 'Waiting for output...'}
            </pre>
            <div className="modal-footer">
              <button className="btn" onClick={() => setPrereqModal(null)}>Close</button>
            </div>
          </div>
        </div>
      )}

      {showEnrollModal && (
        <div className="modal-overlay" onClick={() => setShowEnrollModal(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <h2>Add a new node</h2>
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