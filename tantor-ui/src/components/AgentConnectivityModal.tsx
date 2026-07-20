import { useState, useEffect } from 'react';
import { Trash2, X } from 'lucide-react';
import '../pages/Hosts.css';
import { confirmAction, notifyAction } from './ConfirmDialog';

type AgentConnectivityModalProps = {
  onClose: () => void;
};

export function AgentConnectivityModal({ onClose }: AgentConnectivityModalProps) {
  const [hosts, setHosts] = useState<any[]>([]);
  const [selectedPendingIds, setSelectedPendingIds] = useState<Record<string, boolean>>({});
  const [connectingAgents, setConnectingAgents] = useState(false);

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

  const deleteHost = async (id: string) => {
    if (!(await confirmAction('Disconnect this node? It will move back to discovered nodes and can be connected again.'))) return;
    try {
      const res = await fetch(`/api/v1/ui/hosts/${id}`, { method: 'DELETE' });
      if (res.ok) {
        fetchHosts();
      } else {
        const errorData = await res.json().catch(() => ({}));
        notifyAction(errorData.message || 'Failed to disconnect node.');
      }
    } catch (e) {
      console.error(e);
      notifyAction('An error occurred while disconnecting the node.');
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
  const selectedCount = pendingHosts.filter(host => selectedPendingIds[host.id]).length;
  const allPendingSelected = pendingHosts.length > 0 && selectedCount === pendingHosts.length;

  const togglePendingHost = (id: string) => {
    setSelectedPendingIds(prev => ({ ...prev, [id]: !prev[id] }));
  };

  const toggleAllPendingHosts = () => {
    if (allPendingSelected) {
      setSelectedPendingIds({});
      return;
    }
    setSelectedPendingIds(Object.fromEntries(pendingHosts.map(host => [host.id, true])));
  };

  const connectSelectedAgents = async () => {
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
        notifyAction(`${failed} agent${failed === 1 ? '' : 's'} could not be connected. Refreshing the list now.`);
      }
      setSelectedPendingIds({});
      await fetchHosts();
    } finally {
      setConnectingAgents(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose} style={{ zIndex: 9999 }}>
      <div className="modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <h2>Agent Connectivity</h2>
          <button className="modal-close" onClick={onClose}>
            <X size={14} />
          </button>
        </div>

        <p className="modal-section-title" style={{ margin: '20px 32px 10px 32px' }}>Discovered nodes waiting to connect</p>

        {pendingHosts.length === 0 ? (
          <div className="empty-pending" style={{ margin: '0 32px' }}>
            No new nodes discovered. Run the agent script on a VM to discover it.
          </div>
        ) : (
          <>
            <label className="pending-select-all" style={{ margin: '0 32px 10px 32px' }}>
              <input type="checkbox" checked={allPendingSelected} onChange={toggleAllPendingHosts} />
              <span>Select all discovered agents</span>
            </label>
            <div style={{ padding: '0 32px' }}>
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
            </div>
            <div className="pending-connect-summary" style={{ margin: '16px 32px 0 32px' }}>
              <span>{selectedCount} selected</span>
              <button className="btn btn-primary-action" disabled={selectedCount === 0 || connectingAgents} onClick={connectSelectedAgents}>
                {connectingAgents ? 'Connecting...' : 'Connect selected'}
              </button>
            </div>
          </>
        )}

        <hr className="modal-divider" />
        <div className="modal-footer" style={{ margin: '16px 32px 24px 32px' }}>
          <button className="btn" onClick={onClose}>Close</button>
        </div>
      </div>
    </div>
  );
}
