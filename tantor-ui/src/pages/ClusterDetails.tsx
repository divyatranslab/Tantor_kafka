import { useState, useEffect } from 'react';
import { useParams, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { Network, Activity, Settings, RefreshCw, LayoutList, Users, Server, Database } from 'lucide-react';
import './ClusterDetails.css';

interface ClusterInfo {
  id: string;
  name: string;
  kafkaVersion: string;
  mode: string;
  environment: string;
  nodeCount: number;
  status: string;
  managementLevel?: string;
}

export function ClusterDetails() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [cluster, setCluster] = useState<ClusterInfo | null>(null);

  useEffect(() => {
    fetch(`/api/v1/ui/clusters/${id}`)
      .then(res => res.json())
      .then(setCluster)
      .catch(console.error);
  }, [id]);

  // Handle redirects
  useEffect(() => {
    if (!cluster) return;
    
    // Redirect to logs if actively deploying/deleting
    if (cluster.mode !== 'EXTERNAL' && cluster.status !== 'SUCCESS' && cluster.status !== 'FAILED' && cluster.status !== 'DELETED') {
        if (window.location.pathname === `/clusters/${id}` || window.location.pathname === `/clusters/${id}/topics` || window.location.pathname === `/clusters/${id}/brokers`) {
             navigate(`/clusters/${id}/logs`, { replace: true });
             return;
        }
    }
    
    // Default redirect to brokers for valid clusters
    if (window.location.pathname === `/clusters/${id}`) {
        navigate(`/clusters/${id}/brokers`, { replace: true });
    }
  }, [cluster, id, navigate]);

  if (!cluster) {
    return <div className="state-center"><RefreshCw className="spin" /> Loading cluster...</div>;
  }

  const tabs = [
    { to: `/clusters/${id}/brokers`, icon: Server, label: 'Brokers', disabled: cluster.status !== 'SUCCESS' && cluster.mode !== 'EXTERNAL' },
    { to: `/clusters/${id}/topics`, icon: LayoutList, label: 'Topics', disabled: cluster.status !== 'SUCCESS' && cluster.mode !== 'EXTERNAL' },
    { to: `/clusters/${id}/partitions`, icon: Database, label: 'Partitions', disabled: cluster.status !== 'SUCCESS' && cluster.mode !== 'EXTERNAL' },
    { to: `/clusters/${id}/consumers`, icon: Users, label: 'Consumers', disabled: cluster.status !== 'SUCCESS' && cluster.mode !== 'EXTERNAL' },
    { to: `/clusters/${id}/config`, icon: Settings, label: 'Configuration', disabled: cluster.status !== 'SUCCESS' && cluster.mode !== 'EXTERNAL' },
  ];

  if (cluster.mode === 'EXTERNAL') {
    tabs.push({ to: `/clusters/${id}/actions`, icon: Activity, label: 'Actions & Restarts', disabled: false });
  } else {
    tabs.push({ to: `/clusters/${id}/actions`, icon: Activity, label: 'Actions & Restarts', disabled: cluster.status !== 'SUCCESS' });
    tabs.push({ to: `/clusters/${id}/logs`, icon: RefreshCw, label: 'Deployment Logs', disabled: false });
  }

  return (
    <div className="cluster-details-page animate-fade-in">
      <header className="page-header">
        <div className="breadcrumb">
          <span onClick={() => navigate('/clusters')} style={{cursor: 'pointer', color: 'var(--text-secondary)'}}>Clusters</span>
          <span style={{margin: '0 8px'}}>/</span>
          <span style={{fontWeight: 600}}>{cluster.name}</span>
        </div>
        
        <div className="cluster-header-main">
          <div className="cluster-header-left">
            <div className="icon-wrap">
              <Network size={28} />
            </div>
            <div>
              <h1>{cluster.name}</h1>
              <p>Kafka {cluster.kafkaVersion} • {cluster.nodeCount} nodes • {cluster.mode}</p>
            </div>
          </div>
          <div className={`status-badge ${(cluster.status || '').toLowerCase()}`}>
             <div className="status-dot"></div> {cluster.mode === 'EXTERNAL' ? 'External' : cluster.status}
          </div>
        </div>
      </header>

      <div className="cluster-tabs">
        <nav>
          {tabs.map(tab => {
            if (tab.disabled) {
              return (
                <div key={tab.to} className="disabled-tab" title="Requires active cluster">
                  <tab.icon size={16} />
                  {tab.label}
                </div>
              );
            }
            return (
              <NavLink
                key={tab.to}
                to={tab.to}
                className={({ isActive }) => isActive ? 'active' : ''}
              >
                <tab.icon size={16} />
                {tab.label}
              </NavLink>
            );
          })}
        </nav>
      </div>

      <div className="cluster-content mt-6">
        <Outlet />
      </div>
    </div>
  );
}
