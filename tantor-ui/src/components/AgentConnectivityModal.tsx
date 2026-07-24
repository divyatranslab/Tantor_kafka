import { useState, useEffect } from 'react';
import { parseIpList } from '../lib/hosts';
import { Trash2, X } from 'lucide-react';
import '../pages/Hosts.css';
import { confirmAction, notifyAction } from './ConfirmDialog';import { apiFetch } from '../lib/apiClient.ts';


type AgentConnectivityModalProps = {
  onClose: () => void;
};

export function AgentConnectivityModal({ onClose }: AgentConnectivityModalProps) {
  const [hosts, setHosts] = useState<any[]>([]);
  const [selectedPendingIds, setSelectedPendingIds] = useState<Record<string, boolean>>({});
  const [connectingAgents, setConnectingAgents] = useState(false);

  const fetchHosts = async () => {
    try {
      const res = await apiFetch('/api/v1/ui/hosts');
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
      const res = await apiFetch(`/api/v1/ui/hosts/${id}`, { method: 'DELETE' });
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
        selectedIds.map(id => apiFetch(`/api/v1/ui/hosts/${id}/approve`, { method: 'POST' }))
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
      <div className="modal" onClick={e => e.stopPropagation()} style={{ padding: 0, overflow: 'hidden', maxWidth: '720px' }}>
        <div className="modal-header" style={{ padding: '24px 32px 16px 32px', margin: 0, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <h2 style={{ fontSize: '1.25rem', fontWeight: 500, color: '#111827', margin: 0 }}>Agent Connectivity</h2>
          <button className="modal-close" onClick={onClose} style={{ border: 'none', background: 'transparent', padding: 0, color: '#9CA3AF' }}>
            <X size={20} />
          </button>
        </div>

        {pendingHosts.length === 0 ? (
          <div className="empty-pending" style={{ margin: '0 32px 32px 32px' }}>
            No new nodes discovered. Run the agent script on a VM to discover it.
          </div>
        ) : (
          <div style={{ background: '#F8FAFC', borderRadius: '12px', margin: '0 32px 24px 32px', padding: '24px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
              <span style={{ fontSize: '14px', color: '#475569', fontWeight: 500 }}>Discovered Nodes</span>
              <label className="pending-select-all" style={{ margin: 0, padding: 0, border: 'none', background: 'transparent', gap: '8px', cursor: 'pointer' }}>
                <input 
                  type="checkbox" 
                  checked={allPendingSelected} 
                  onChange={toggleAllPendingHosts} 
                  style={{ width: '16px', height: '16px', accentColor: '#8B5CF6', cursor: 'pointer' }} 
                />
                <span style={{ fontSize: '14px', color: '#1E293B', fontWeight: 500 }}>Select all</span>
              </label>
            </div>
            
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px', maxHeight: '400px', overflowY: 'auto', paddingRight: '4px' }}>
              {pendingHosts.map(host => (
                <div 
                  key={host.id} 
                  className={`pending-node selectable ${selectedPendingIds[host.id] ? 'selected' : ''}`} 
                  onClick={() => togglePendingHost(host.id)} 
                  style={{ 
                    background: '#FFFFFF', 
                    border: selectedPendingIds[host.id] ? '1px solid #8B5CF6' : '1px solid #F1F5F9', 
                    borderRadius: '8px', 
                    padding: '16px', 
                    margin: 0, 
                    boxShadow: '0 1px 2px rgba(0,0,0,0.02)',
                    display: 'flex',
                    alignItems: 'center',
                    gap: '16px'
                  }}
                >
                  <label className="pending-node-select" onClick={event => event.stopPropagation()} style={{ margin: 0, display: 'flex' }}>
                    <input
                      type="checkbox"
                      checked={!!selectedPendingIds[host.id]}
                      onChange={() => togglePendingHost(host.id)}
                      style={{ width: '16px', height: '16px', accentColor: '#8B5CF6', cursor: 'pointer' }}
                    />
                  </label>
                  <div className="pending-node-info" style={{ flex: 1 }}>
                    <p className="name" style={{ fontSize: '14px', color: '#1E293B', fontWeight: 500, margin: '0 0 4px 0' }}>{host.agentName || host.hostname}</p>
                    <p className="ip" style={{ fontSize: '13px', color: '#94A3B8', margin: 0 }}>{displayIp(host.ipAddresses)} - {host.agentPath || 'Path unavailable'}</p>
                  </div>
                  <div className="pending-node-actions">
                    <button 
                      className="btn icon-only danger" 
                      title="Reject & remove" 
                      onClick={(event) => { event.stopPropagation(); deleteHost(host.id); }} 
                      style={{ border: 'none', background: 'transparent', color: '#9CA3AF', padding: '4px' }}
                    >
                      <Trash2 size={18} />
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        <div className="modal-footer" style={{ margin: '0', borderTop: '1px solid #F1F5F9', padding: '20px 32px', display: 'flex', justifyContent: 'flex-end', gap: '12px', background: '#FFFFFF' }}>
          <button 
            className="btn" 
            onClick={onClose} 
            style={{ background: '#FFFFFF', border: '1px solid #CBD5E1', color: '#64748B', padding: '8px 24px', borderRadius: '8px', fontWeight: 500, fontSize: '14px' }}
          >
            Cancel
          </button>
          <button 
            className="btn btn-primary-action" 
            disabled={selectedCount === 0 || connectingAgents} 
            onClick={connectSelectedAgents} 
            style={{ 
              background: '#FFFFFF', 
              border: '1px solid #8B5CF6', 
              color: '#8B5CF6', 
              padding: '8px 24px', 
              borderRadius: '8px', 
              fontWeight: 500, 
              fontSize: '14px',
              opacity: (selectedCount === 0 || connectingAgents) ? 0.5 : 1
            }}
          >
            {connectingAgents ? 'Connecting...' : 'Connect'}
          </button>
        </div>
      </div>
    </div>
  );
}
