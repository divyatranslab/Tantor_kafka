import { useState, useEffect } from 'react';
import { useParams, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { Network, Activity, Settings, RefreshCw, LayoutList, Users, Link, Database, FileJson } from 'lucide-react';
import './ClusterDetails.css';

interface ClusterInfo {
  id: string;
  name: string;
  kafkaVersion: string;
  mode: string;
  environment: string;
  nodeCount: number;
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

  if (!cluster) {
    return <div className="state-center"><RefreshCw className="spin" /> Loading cluster...</div>;
  }

  const tabs = [
    { to: `/clusters/${id}/topics`, icon: LayoutList, label: 'Topics' },
    { to: `/clusters/${id}/consumers`, icon: Users, label: 'Consumers' },
    { to: `/clusters/${id}/config`, icon: Settings, label: 'Configuration' },
    { to: `/clusters/${id}/schema-registry`, icon: FileJson, label: 'Schema Registry' },
    { to: `/clusters/${id}/kafka-connect`, icon: Link, label: 'Kafka Connect' },
    { to: `/clusters/${id}/ksqldb`, icon: Database, label: 'KSQL DB' },
  ];

  if (cluster.mode !== 'EXTERNAL') {
    tabs.push({ to: `/clusters/${id}/actions`, icon: Activity, label: 'Actions & Restarts' });
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
          <div className="status-badge">
             <div className="status-dot"></div> Active
          </div>
        </div>
      </header>

      <div className="cluster-tabs">
        <nav>
          {tabs.map(tab => (
            <NavLink
              key={tab.to}
              to={tab.to}
              className={({ isActive }) => isActive ? 'active' : ''}
            >
              <tab.icon size={16} />
              {tab.label}
            </NavLink>
          ))}
        </nav>
      </div>

      <div className="cluster-content mt-6">
        <Outlet />
      </div>
    </div>
  );
}
