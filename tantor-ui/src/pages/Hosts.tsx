import { useState, useEffect } from 'react';
import { Terminal, Cpu, HardDrive, RefreshCw, Trash2 } from 'lucide-react';
import './Hosts.css';

export function Hosts() {
  const [hosts, setHosts] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [showEnrollModal, setShowEnrollModal] = useState(false);

  const fetchHosts = async () => {
    setLoading(true);
    try {
      const response = await fetch('/api/v1/ui/hosts');
      if (response.ok) {
        const data = await response.json();
        setHosts(data);
      } else {
        console.error('Failed to fetch hosts');
      }
    } catch (error) {
      console.error('Error fetching hosts:', error);
    }
    setLoading(false);
  };

  const approveHost = async (hostId: string) => {
    try {
      const response = await fetch(`/api/v1/ui/hosts/${hostId}/approve`, {
        method: 'POST',
      });
      if (response.ok) {
        fetchHosts(); // refresh list
      } else {
        console.error('Failed to approve host');
      }
    } catch (error) {
      console.error('Error approving host:', error);
    }
  };

  const deleteHost = async (hostId: string) => {
    if (!window.confirm('Are you sure you want to completely remove this node?')) return;
    try {
      const response = await fetch(`/api/v1/ui/hosts/${hostId}`, {
        method: 'DELETE',
      });
      if (response.ok) {
        fetchHosts(); // refresh list
      } else {
        console.error('Failed to delete host');
      }
    } catch (error) {
      console.error('Error deleting host:', error);
    }
  };

  useEffect(() => {
    fetchHosts();
    // Auto-refresh every 5 seconds
    const interval = setInterval(fetchHosts, 5000);
    return () => clearInterval(interval);
  }, []);

  return (
    <div className="hosts-page animate-fade-in">
      <header className="page-header flex-between">
        <div>
          <h1>Infrastructure Fleet</h1>
          <p>Manage and monitor physical/virtual nodes.</p>
        </div>
        <div style={{ display: 'flex', gap: '10px' }}>
          <button className="btn" style={{background: 'var(--accent-primary)', color: 'white'}} onClick={() => setShowEnrollModal(true)}>
            + Add Node
          </button>
          <button className="btn btn-primary" onClick={fetchHosts}>
            <RefreshCw size={16} className={loading ? "spin" : ""} /> Sync Inventory
          </button>
        </div>
      </header>

      <div className="table-container glass-panel">
        <table className="data-table">
          <thead>
            <tr>
              <th>Status</th>
              <th>Hostname</th>
              <th>IP Address</th>
              <th>Agent Version</th>
              <th>CPU</th>
              <th>Memory</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {hosts.filter(h => h.status !== 'PENDING').length === 0 ? (
              <tr>
                <td colSpan={7} style={{textAlign: 'center', padding: '2rem'}}>
                  {loading ? 'Loading connected agents...' : 'No agents connected yet.'}
                </td>
              </tr>
            ) : hosts.filter(h => h.status !== 'PENDING').map(host => {
              // Parse IPs if it's a JSON string
              let ipString = host.ipAddresses || "Unknown";
              if (typeof host.ipAddresses === 'string' && host.ipAddresses.startsWith('[')) {
                try {
                  const ips = JSON.parse(host.ipAddresses);
                  ipString = ips.join(', ');
                } catch (e) {}
              }
              
              // Calculate Memory Pct
              let memPct = 0;
              if (host.memTotalMb > 0) {
                memPct = Math.round((host.memUsedMb / host.memTotalMb) * 100);
              }
              const cpuPct = host.cpuUsagePct ? Math.round(host.cpuUsagePct) : 0;

              return (
                <tr key={host.id}>
                  <td>
                    <span className={`status-badge ${host.status ? host.status.toLowerCase() : 'offline'}`}>
                      {host.status || 'OFFLINE'}
                    </span>
                  </td>
                  <td className="font-medium">{host.hostname}</td>
                  <td className="text-secondary">{ipString}</td>
                  <td>{host.agentVersion || "N/A"}</td>
                  <td>
                    <div className="metric-bar">
                      <div className="bar-fill" style={{ width: `${cpuPct}%`, background: cpuPct > 80 ? 'var(--accent-danger)' : 'var(--accent-primary)' }}></div>
                      <span>{cpuPct}%</span>
                    </div>
                  </td>
                  <td>
                    <div className="metric-bar">
                      <div className="bar-fill" style={{ width: `${memPct}%`, background: memPct > 85 ? 'var(--accent-warning)' : 'var(--accent-secondary)' }}></div>
                      <span>{memPct}%</span>
                    </div>
                  </td>
                  <td>
                    <div className="actions">
                      <button className="btn btn-glass icon-only" title="View Metrics"><Cpu size={16} /></button>
                      <button className="btn btn-glass icon-only" title="View Storage"><HardDrive size={16} /></button>
                      <button className="btn btn-glass icon-only" title="SSH Terminal"><Terminal size={16} /></button>
                      <button className="btn btn-glass icon-only" title="Remove Node" onClick={() => deleteHost(host.id)} style={{ color: 'var(--accent-danger)' }}><Trash2 size={16} /></button>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {/* Enroll Node Modal */}
      {showEnrollModal && (
        <div className="modal-overlay" style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, background: 'rgba(0,0,0,0.7)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 }}>
          <div className="glass-panel" style={{ padding: '2rem', maxWidth: '700px', width: '100%', position: 'relative', maxHeight: '90vh', overflowY: 'auto' }}>
            <button 
              onClick={() => setShowEnrollModal(false)}
              style={{ position: 'absolute', top: '10px', right: '15px', background: 'transparent', border: 'none', color: 'white', fontSize: '1.5rem', cursor: 'pointer' }}
            >
              &times;
            </button>
            <h2 style={{ marginBottom: '1rem', color: 'white' }}>Add a New Node</h2>
            
            {/* Discovered Nodes Section */}
            <div style={{ marginBottom: '2rem' }}>
              <h3 style={{ color: 'var(--accent-primary)', marginBottom: '1rem', borderBottom: '1px solid #1e293b', paddingBottom: '0.5rem' }}>Discovered Nodes Waiting to Connect</h3>
              {hosts.filter(h => h.status === 'PENDING').length === 0 ? (
                <div style={{ background: 'rgba(255,255,255,0.05)', padding: '1rem', borderRadius: '8px', textAlign: 'center', color: 'var(--text-secondary)' }}>
                  No new nodes discovered. Run the agent script on a VM to discover it.
                </div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                  {hosts.filter(h => h.status === 'PENDING').map(host => {
                    let hostIp = host.ipAddresses;
                    if (typeof host.ipAddresses === 'string' && host.ipAddresses.startsWith('[')) {
                      try { hostIp = JSON.parse(host.ipAddresses).join(', '); } catch (e) {}
                    }
                    return (
                      <div key={host.id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', background: 'rgba(255,255,255,0.05)', padding: '1rem', borderRadius: '8px' }}>
                        <div>
                          <div style={{ fontWeight: 'bold', color: 'white' }}>{host.hostname}</div>
                          <div style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>{hostIp}</div>
                        </div>
                        <div style={{ display: 'flex', gap: '0.5rem' }}>
                          <button 
                            className="btn" 
                            style={{ background: 'var(--accent-primary)', color: 'white', padding: '0.5rem 1rem' }} 
                            onClick={() => approveHost(host.id)}
                          >
                            Connect
                          </button>
                          <button 
                            className="btn btn-glass icon-only" 
                            title="Reject & Remove" 
                            style={{ color: 'var(--accent-danger)' }}
                            onClick={() => deleteHost(host.id)}
                          >
                            <Trash2 size={16} />
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            
            <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
              <button className="btn" style={{ background: 'var(--accent-secondary)', color: 'white' }} onClick={() => setShowEnrollModal(false)}>
                Close
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
