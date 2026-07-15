import { useState, useEffect } from 'react';

import { Network, Activity, Settings, RefreshCw, LayoutList, Users, Server, Database, LineChart, Terminal, Shield, FileJson, Plug } from 'lucide-react';
import { useParams, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
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
  kafkaClusterId?: string;
  originType?: string;
  installDirectory?: string;
  runtimeHealth?: string;
  runtimeStatusLabel?: string;
  runtimeStatusReason?: string;
}

export function ClusterDetails() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const location = useLocation();
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

    const currentPath = location.pathname;

    // Redirect to logs if actively deploying/deleting
    if (
      cluster.mode !== 'EXTERNAL' &&
      cluster.status !== 'SUCCESS' &&
      cluster.status !== 'FAILED' &&
      cluster.status !== 'DELETED'
    ) {
      if (
        currentPath === `/clusters/${id}` ||
        currentPath === `/clusters/${id}/nodes` ||
        currentPath === `/clusters/${id}/topics` ||
        currentPath === `/clusters/${id}/brokers`
      ) {
        navigate(`/clusters/${id}/logs`, { replace: true });
        return;
      }
    }

    // Default redirect
    if (currentPath === `/clusters/${id}`) {
      navigate(`/clusters/${id}/overview`, { replace: true });
    }
  }, [cluster, id, navigate, location.pathname]);


  if (!cluster) {
    return <div className="state-center"><RefreshCw className="spin" /> Loading cluster...</div>;
  }

  const isLogsView = location.pathname === `/clusters/${id}/logs`;
  const runtimeLabel = cluster.runtimeStatusLabel || (cluster.mode === 'EXTERNAL' ? 'External' : cluster.status);
  const runtimeClass = (cluster.runtimeHealth || cluster.status || '').toLowerCase();

  if (isLogsView) {
    return (
      <div className="cluster-details-page cluster-logs-page animate-fade-in">
        <header className="cluster-logs-header">
          <div className="breadcrumb">
            <span onClick={() => navigate('/clusters')}>Clusters</span>
            <span>/</span>
            <strong>{cluster.name}</strong>
          </div>
          <div className="cluster-logs-title">
            <div className="icon-wrap"><Terminal size={20} /></div>
            <div>
              <h1>Deployment Logs</h1>
              <p>{cluster.name} · Kafka {cluster.kafkaVersion} · {cluster.mode}</p>
            </div>
          <div className={`status-badge ${(cluster.status || '').toLowerCase()}`}>
            <div className="status-dot" /> {cluster.status}
          </div>
          </div>
        </header>
        <div className="cluster-tabs cluster-logs-tabs">
          <nav><span className="active"><Terminal size={16} /> Logs</span></nav>
        </div>
        <div className="cluster-content cluster-logs-content">
          <Outlet />
        </div>
      </div>
    );
  }

  const tabs = [
    { to: `/clusters/${id}/overview`, icon: LineChart, label: 'Overview', disabled: cluster.status !== 'SUCCESS' && cluster.mode !== 'EXTERNAL' },
    { to: `/clusters/${id}/nodes`, icon: Network, label: 'Nodes', disabled: false },
    { to: `/clusters/${id}/brokers`, icon: Server, label: 'Brokers', disabled: cluster.status !== 'SUCCESS' && cluster.mode !== 'EXTERNAL' },
    { to: `/clusters/${id}/topics`, icon: LayoutList, label: 'Topics', disabled: cluster.status !== 'SUCCESS' && cluster.mode !== 'EXTERNAL' },
    { to: `/clusters/${id}/partitions`, icon: Database, label: 'Partitions', disabled: cluster.status !== 'SUCCESS' && cluster.mode !== 'EXTERNAL' },
    { to: `/clusters/${id}/consumers`, icon: Users, label: 'Consumers', disabled: cluster.status !== 'SUCCESS' && cluster.mode !== 'EXTERNAL' },
    { to: `/clusters/${id}/schema-registry`, icon: FileJson, label: 'Schema Registry', disabled: cluster.status !== 'SUCCESS' && cluster.mode !== 'EXTERNAL' },
    { to: `/clusters/${id}/kafka-connect`, icon: Plug, label: 'Kafka Connect', disabled: cluster.status !== 'SUCCESS' && cluster.mode !== 'EXTERNAL' },
    { to: `/clusters/${id}/security`, icon: Shield, label: 'Security', disabled: cluster.status !== 'SUCCESS' && cluster.mode !== 'EXTERNAL' },
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
          <span onClick={() => navigate('/clusters')} style={{ cursor: 'pointer', color: 'var(--text-secondary)' }}>Clusters</span>
          <span style={{ margin: '0 8px' }}>/</span>
          <span style={{ fontWeight: 600 }}>{cluster.name}</span>
        </div>

        <div className="cluster-header-main">
          <div className="cluster-header-left">
            <div className="icon-wrap">
              <Network size={28} />
            </div>
            <div>
              <h1>{cluster.name}</h1>
              <p>Kafka {cluster.kafkaVersion} · {cluster.nodeCount} nodes · {cluster.originType || (cluster.mode === 'EXTERNAL' ? 'EXTERNAL' : 'INTERNAL')}</p>
              <p className="cluster-identity-line">Kafka Cluster ID: <code>{cluster.kafkaClusterId || 'Pending discovery'}</code> · Install directory: <code>{cluster.installDirectory || '-'}</code></p>
            </div>
          </div>
          <div className={`status-badge ${runtimeClass}`} title={cluster.runtimeStatusReason}>
            <div className="status-dot"></div> {runtimeLabel}
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
