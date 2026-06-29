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
}

interface ClusterResponse {
  id: string;
  hosts?: ClusterNode[];
}

export function ClusterNodes() {
  const { id } = useParams<{ id: string }>();
  const [nodes, setNodes] = useState<ClusterNode[]>([]);
  const [loading, setLoading] = useState(true);

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

  useEffect(() => { fetchNodes(); }, [id]);

  if (loading && nodes.length === 0) return <div className="state-center"><Loader2 className="spin" /> Loading nodes...</div>;

  return (
    <div className="cluster-nodes-page">
      <header><div><h2>Cluster Nodes</h2><p>Every broker, controller, and ZooKeeper service assigned to this cluster.</p></div><button onClick={fetchNodes}><RefreshCw size={14} className={loading ? 'spin' : ''} /> Refresh</button></header>
      <div className="cluster-nodes-table-wrap">
        <table className="cluster-nodes-table">
          <thead><tr><th>Node ID</th><th>Host</th><th>IP address</th><th>Role</th><th>Status</th><th>Last heartbeat</th></tr></thead>
          <tbody>
            {nodes.map((node, index) => (
              <tr key={`${node.hostId}-${node.role}-${node.nodeId ?? index}`}>
                <td><code>{node.nodeId ?? '-'}</code></td>
                <td><span className="cluster-node-host"><Server size={14} /> {node.hostname || node.hostId}</span></td>
                <td><code>{node.ipAddress || '-'}</code></td>
                <td><span className="cluster-node-role">{String(node.role || 'unknown').replaceAll('_', ' ')}</span></td>
                <td><span className={`cluster-node-status ${(node.status || '').toLowerCase()}`}>{node.status || 'UNKNOWN'}</span></td>
                <td>{node.lastHeartbeat ? new Date(node.lastHeartbeat).toLocaleString() : '-'}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {nodes.length === 0 && <div className="empty-state">No service assignments are recorded for this cluster.</div>}
      </div>
    </div>
  );
}
