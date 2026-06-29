import { useLocation } from 'react-router-dom';
import { Server } from 'lucide-react';
import { useCluster } from '../contexts/ClusterContext';
import './TopNavbar.css';

export function TopNavbar() {
  const { clusters, activeClusterId, setActiveClusterId, loading } = useCluster();
  const location = useLocation();
  
  if (location.pathname !== '/schema-registry' && location.pathname !== '/kafka-connect') {
    return null;
  }

  if (loading) {
    return (
      <div className="top-navbar">
        <div className="navbar-right">
          <span className="loading-text">Loading clusters...</span>
        </div>
      </div>
    );
  }

  if (clusters.length === 0) {
    return (
      <div className="top-navbar">
        <div className="navbar-right">
          <span className="no-cluster-text">No active clusters</span>
        </div>
      </div>
    );
  }

  return (
    <div className="top-navbar">
      <div className="navbar-right">
        <div className="cluster-selector">
          <Server size={16} className="cluster-icon" />
          <select
            className="cluster-dropdown"
            value={activeClusterId || ''}
            onChange={(e) => setActiveClusterId(e.target.value)}
          >
            {clusters.map((cluster) => (
              <option key={cluster.id} value={cluster.id}>
                {cluster.name}
              </option>
            ))}
          </select>
        </div>
      </div>
    </div>
  );
}
