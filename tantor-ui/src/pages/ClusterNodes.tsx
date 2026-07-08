import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Loader2, RefreshCw, Server } from 'lucide-react';
import './ClusterNodes.css';

interface ClusterNode {
  hostId: string;
  hostname: string;
  ipAddress: string;
  status: string;
  role: string;
  nodeId?: number;
  lastHeartbeat?: string;
  agentAvailable?: boolean;
  availableAgentId?: string;
}

interface ClusterResponse {
  id: string;
  hosts?: ClusterNode[];
}

export function ClusterNodes() {
  const { id } = useParams<{ id: string }>();
  const [nodes, setNodes] = useState<ClusterNode[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedAgents, setSelectedAgents] = useState<{ [host: string]: string }>({});
  const [binding, setBinding] = useState(false);

  const fetchNodes = async () => {
    setLoading(true);
    try {
      const response = await fetch(`/api/v1/ui/clusters/${id}`);
      if (response.ok) {
        const cluster: ClusterResponse = await response.json();
        setNodes(cluster.hosts || []);
      }
    } finally {
      setLoading(false);
    }
  };

  const bindAgents = async () => {
    if (Object.keys(selectedAgents).length === 0) return;
    setBinding(true);
    try {
      for (const [host, agentId] of Object.entries(selectedAgents)) {
        await fetch(`/api/v1/ui/clusters/${id}/bind-agent`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ host, agentId }),
        });
      }
      setSelectedAgents({});
      await fetchNodes();
    } catch (e) {
      alert('Failed to bind agents');
    } finally {
      setBinding(false);
    }
  };

  useEffect(() => { fetchNodes(); }, [id]);

  if (loading && nodes.length === 0) return <div className="state-center"><Loader2 className="spin" /> Loading nodes...</div>;

  const isExternalCluster = nodes.some(n => 
    n.status?.includes('Managed') || n.status?.includes('Unmanaged') || n.agentAvailable
  );

  return (
    <div className="cluster-nodes-page animate-fade-in">
      <header>
        <div>
          <h2>Cluster Nodes</h2>
          <p>Every broker, controller, and ZooKeeper service assigned to this cluster.</p>
        </div>
        <div style={{ display: 'flex', gap: '12px' }}>
          {Object.keys(selectedAgents).length > 0 && (
            <button className="btn primary" onClick={bindAgents} disabled={binding}>
              {binding ? <RefreshCw size={14} className="spin" /> : 'Connect Agent'}
            </button>
          )}
          <button onClick={fetchNodes}><RefreshCw size={14} className={loading ? 'spin' : ''} /> Refresh</button>
        </div>
      </header>
      <div className="cluster-nodes-table-wrap">
        <table className="cluster-nodes-table">
          <thead><tr>
            {isExternalCluster && <th style={{ width: '40px', textAlign: 'center' }}></th>}
            <th>Node ID</th>
            <th>Host</th>
            <th>IP address</th>
            <th>Role</th>
            <th>Status</th>
            <th>Last heartbeat</th>
          </tr></thead>
          <tbody>
            {nodes.map((node, index) => {
              const hostKey = node.hostname || node.hostId || node.ipAddress;
              return (
              <tr key={`${node.hostId}-${node.role}-${node.nodeId ?? index}`}>
                {isExternalCluster && (
                  <td style={{ textAlign: 'center' }}>
                    <input 
                      type="checkbox"
                      checked={node.status === 'Managed' || !!selectedAgents[hostKey]}
                      disabled={!node.agentAvailable}
                      style={{ cursor: !node.agentAvailable ? 'not-allowed' : 'pointer' }}
                      onChange={(e) => {
                        if (!node.agentAvailable) return;
                        const newSelection = { ...selectedAgents };
                        if (e.target.checked) {
                          newSelection[hostKey] = node.availableAgentId!;
                        } else {
                          delete newSelection[hostKey];
                        }
                        setSelectedAgents(newSelection);
                      }}
                    />
                  </td>
                )}
                <td><code>{node.nodeId ?? '-'}</code></td>
                <td><span className="cluster-node-host"><Server size={14} /> {node.hostname || node.hostId}</span></td>
                <td><code>{node.ipAddress || '-'}</code></td>
                <td><span className="cluster-node-role">{String(node.role || 'unknown').replaceAll('_', ' ')}</span></td>
                <td>
                  <span className={`cluster-node-status ${(node.status || '').toLowerCase()}`}>
                    {node.status === 'Unmanaged / No telemetry' && node.agentAvailable ? 'Agent available' : node.status || 'UNKNOWN'}
                  </span>
                </td>
                <td>{node.lastHeartbeat ? new Date(node.lastHeartbeat).toLocaleString() : '-'}</td>
              </tr>
              );
            })}
          </tbody>
        </table>
        {nodes.length === 0 && <div className="empty-state">No service assignments are recorded for this cluster.</div>}
      </div>
    </div>
  );
}
