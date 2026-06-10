import { useState, useEffect } from 'react';
import { Terminal, Cpu, HardDrive, RefreshCw, Trash2, X } from 'lucide-react';
import './Hosts.css';

export function Hosts() {
  const [hosts, setHosts] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [showEnrollModal, setShowEnrollModal] = useState(false);

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
    if (!window.confirm('Remove this node?')) return;
    try {
      const res = await fetch(`/api/v1/ui/hosts/${id}`, { method: 'DELETE' });
      if (res.ok) fetchHosts();
    } catch (e) { console.error(e); }
  };

  useEffect(() => {
    fetchHosts();
    const t = setInterval(fetchHosts, 5000);
    return () => clearInterval(t);
  }, []);

  const parseIps = (raw: any) => {
    if (typeof raw === 'string' && raw.startsWith('[')) {
      try { return JSON.parse(raw).join(', '); } catch {}
    }
    return raw || 'Unknown';
  };

  const activeHosts = hosts.filter(h => h.status !== 'PENDING');
  const pendingHosts = hosts.filter(h => h.status === 'PENDING');

  return (
    <div className="hosts-page animate-fade-in">

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
                <td colSpan={7}>
                  <div className="empty-state">
                    {loading ? 'Loading connected agents…' : 'No agents connected yet.'}
                  </div>
                </td>
              </tr>
            ) : activeHosts.map(host => {
              const ip = parseIps(host.ipAddresses);
              const cpu = host.cpuUsagePct ? Math.round(host.cpuUsagePct) : 0;
              const mem = host.memTotalMb > 0
                ? Math.round((host.memUsedMb / host.memTotalMb) * 100)
                : 0;

              return (
                <tr key={host.id}>
                  <td>
                    <span className={`status-badge ${(host.status ?? 'offline').toLowerCase()}`}>
                      {host.status ?? 'OFFLINE'}
                    </span>
                  </td>
                  <td className="font-medium">{host.hostname}</td>
                  <td className="text-secondary">{ip}</td>
                  <td className="text-secondary">{host.agentVersion || 'N/A'}</td>
                  <td>
                    <div className="metric-bar">
                      <div className="bar-track">
                        <div
                          className={`bar-fill ${cpu > 80 ? 'danger' : 'normal'}`}
                          style={{ width: `${cpu}%` }}
                        />
                      </div>
                      <span>{cpu}%</span>
                    </div>
                  </td>
                  <td>
                    <div className="metric-bar">
                      <div className="bar-track">
                        <div
                          className={`bar-fill ${mem > 85 ? 'warning' : 'normal'}`}
                          style={{ width: `${mem}%` }}
                        />
                      </div>
                      <span>{mem}%</span>
                    </div>
                  </td>
                  <td>
                    <div className="actions">
                      <button className="btn icon-only" title="View metrics"><Cpu size={14} /></button>
                      <button className="btn icon-only" title="View storage"><HardDrive size={14} /></button>
                      <button className="btn icon-only" title="SSH terminal"><Terminal size={14} /></button>
                      <button
                        className="btn icon-only danger"
                        title="Remove node"
                        onClick={() => deleteHost(host.id)}
                      >
                        <Trash2 size={14} />
                      </button>
                    </div>
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
                  <p className="ip">{parseIps(host.ipAddresses)}</p>
                </div>
                <div className="pending-node-actions">
                  <button
                    className="btn btn-primary-action"
                    onClick={() => approveHost(host.id)}
                  >
                    Connect
                  </button>
                  <button
                    className="btn icon-only danger"
                    title="Reject & remove"
                    onClick={() => deleteHost(host.id)}
                  >
                    <Trash2 size={14} />
                  </button>
                </div>
              </div>
            ))}

            <hr className="modal-divider" />
            <div className="modal-footer">
              <button className="btn" onClick={() => setShowEnrollModal(false)}>
                Close
              </button>
            </div>

          </div>
        </div>
      )}
    </div>
  );
}