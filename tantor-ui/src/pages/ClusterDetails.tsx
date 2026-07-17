import { useState, useEffect, useRef } from 'react';

import { Network, Activity, Settings, RefreshCw, LayoutList, Users, Server, Database, LineChart, Terminal, Shield, FileJson, Plug, ChevronLeft, Info, ChevronDown } from 'lucide-react';
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
  kafkaHealth?: string;
  agentHealth?: string;
  monitoringHealth?: string;
  overallHealth?: string;
  runtimeHealth?: string;
  runtimeStatusLabel?: string;
  runtimeStatusReason?: string;
}

export function ClusterDetails() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const [cluster, setCluster] = useState<ClusterInfo | null>(null);
  const [isDropdownOpen, setIsDropdownOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setIsDropdownOpen(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

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
  const agentLabel = (() => {
    switch ((cluster.agentHealth || '').toUpperCase()) {
      case 'CONNECTED':
        return 'Agent connected';
      case 'PARTIAL':
        return 'Agent partial';
      case 'NOT_CONNECTED':
        return 'Agent not connected';
      default:
        return 'Agent not connected';
    }
  })();
  const agentClass = (() => {
    switch ((cluster.agentHealth || '').toUpperCase()) {
      case 'CONNECTED':
        return 'connected';
      case 'PARTIAL':
        return 'partial';
      case 'NOT_CONNECTED':
        return 'not-connected';
      default:
        return 'not-connected';
    }
  })();

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
    { to: `/clusters/${id}/security`, icon: Shield, label: 'ACLs', disabled: cluster.status !== 'SUCCESS' && cluster.mode !== 'EXTERNAL' },
    { to: `/clusters/${id}/config`, icon: Settings, label: 'Configuration', disabled: cluster.status !== 'SUCCESS' && cluster.mode !== 'EXTERNAL' },
  ];

  if (cluster.mode === 'EXTERNAL') {
    tabs.push({ to: `/clusters/${id}/actions`, icon: Activity, label: 'Actions & Restarts', disabled: false });
  } else {
    tabs.push({ to: `/clusters/${id}/actions`, icon: Activity, label: 'Actions & Restarts', disabled: cluster.status !== 'SUCCESS' });
    tabs.push({ to: `/clusters/${id}/logs`, icon: RefreshCw, label: 'Deployment Logs', disabled: false });
  }

  // The first 9 items are visible in the main navbar
  const visibleTabs = tabs.slice(0, 9);
  // The rest are in the dropdown
  const dropdownTabs = tabs.slice(9);
  const isDropdownActive = dropdownTabs.some(tab => location.pathname === tab.to);

  return (
    <div className="cluster-details-page animate-fade-in">
      <div className="cluster-details-card">
        <header className="page-header">
          <div className="breadcrumb">
            <span onClick={() => navigate('/clusters')} className="breadcrumb-link">Cluster</span>
            <span className="breadcrumb-separator">&gt;</span>
            <span className="breadcrumb-active">{cluster.name}</span>
          </div>
          <div className="cluster-health-stack">
            <div className={`status-badge ${runtimeClass}`} title={cluster.runtimeStatusReason}>
              <div className="status-dot"></div> {runtimeLabel}
            </div>
            {cluster.mode === 'EXTERNAL' && (
              <div className={`agent-status-badge ${agentClass}`}>
                {agentLabel}
              </div>
            )}
          </div>
        </div>
      </header>

          <div className="cluster-header-main">
            <div className="cluster-header-left">
              <button className="cluster-back-btn" onClick={() => navigate('/clusters')} aria-label="Go back to clusters">
                <ChevronLeft size={20} />
              </button>
              <h1 className="cluster-title">{cluster.name}</h1>
              <div className="cluster-info-tooltip-wrap">
                <Info size={16} className="cluster-info-icon" />
                <div className="cluster-info-tooltip">
                  <p className="tooltip-line">{cluster.name} · Kafka {cluster.kafkaVersion} · {cluster.nodeCount} node{cluster.nodeCount === 1 ? '' : 's'} · {cluster.originType || (cluster.mode === 'EXTERNAL' ? 'EXTERNAL' : 'INTERNAL')}</p>
                  <p className="tooltip-line">Kafka Cluster ID: {cluster.kafkaClusterId || 'Pending discovery'} · Install directory: {cluster.installDirectory || '-'}</p>
                </div>
              </div>
            </div>
            <div className={`status-badge ${runtimeClass}`} title={cluster.runtimeStatusReason}>
              <div className="status-dot"></div> {runtimeLabel}
            </div>
          </div>
        </header>

        <div className="cluster-tabs">
          <nav>
            <div className="cluster-tabs-scroll-wrapper">
              {visibleTabs.map(tab => {
                if (tab.disabled) {
                  return (
                    <div key={tab.to} className="disabled-tab" title="Requires active cluster">
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
                    {tab.label}
                  </NavLink>
                );
              })}
            </div>

            {dropdownTabs.length > 0 && (
              <div className="cluster-tabs-dropdown-container" ref={dropdownRef}>
                <button
                  type="button"
                  className={`cluster-tabs-dropdown-trigger ${isDropdownActive ? 'active' : ''} ${isDropdownOpen ? 'open' : ''}`}
                  onClick={() => setIsDropdownOpen(!isDropdownOpen)}
                  aria-label="More navigation links"
                >
                  <ChevronDown size={18} />
                </button>
                {isDropdownOpen && (
                  <div className="cluster-tabs-dropdown-menu">
                    {dropdownTabs.map(tab => {
                      if (tab.disabled) {
                        return (
                          <div key={tab.to} className="disabled-dropdown-item" title="Requires active cluster">
                            {tab.label}
                          </div>
                        );
                      }
                      return (
                        <NavLink
                          key={tab.to}
                          to={tab.to}
                          className={({ isActive }) => isActive ? 'dropdown-item active' : 'dropdown-item'}
                          onClick={() => setIsDropdownOpen(false)}
                        >
                          {tab.label}
                        </NavLink>
                      );
                    })}
                  </div>
                )}
              </div>
            )}
          </nav>
        </div>

        <div className="cluster-content">
          <Outlet />
        </div>
      </div>
    </div>
  );
}
