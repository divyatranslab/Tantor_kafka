import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Trash2, X } from 'lucide-react';
import '../pages/Hosts.css';

type AgentConnectivityModalProps = {
  onClose: () => void;
};

export function AgentConnectivityModal({ onClose }: AgentConnectivityModalProps) {
  const navigate = useNavigate();
  const [hosts, setHosts] = useState<any[]>([]);

  const fetchHosts = async () => {
    try {
      const res = await fetch('/api/v1/ui/hosts');
      if (res.ok) setHosts(await res.json());
    } catch (e) {
      console.error(e);
    }
  };

  useEffect(() => {
    fetchHosts();
    const t = setInterval(fetchHosts, 5000);
    return () => clearInterval(t);
  }, []);

  const approveHost = async (id: string) => {
    try {
      const res = await fetch(`/api/v1/ui/hosts/${id}/approve`, { method: 'POST' });
      if (res.ok) {
        const body = await res.json().catch(() => ({}));
        if (body.jobId) {
          navigate(`/jobs/${body.jobId}`);
          onClose();
        } else {
          fetchHosts();
        }
      }
    } catch (e) { console.error(e); }
  };

  const deleteHost = async (id: string) => {
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

  return (
    <div className="modal-overlay" onClick={onClose} style={{ zIndex: 9999 }}>
      <div className="modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <h2>Agent Connectivity</h2>
          <button className="modal-close" onClick={onClose}>
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
          <button className="btn" onClick={onClose}>Close</button>
        </div>
      </div>
    </div>
  );
}
