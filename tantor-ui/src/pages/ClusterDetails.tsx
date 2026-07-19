import { useState, useEffect, useRef } from 'react';

import { Network, Activity, Settings, RefreshCw, LayoutList, Users, Server, Database, LineChart, Terminal, Shield, FileJson, Plug, ChevronLeft, ChevronRight, Info, ChevronDown } from 'lucide-react';
import { useParams, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useCluster } from '../contexts/ClusterContext';
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

  const { setActiveClusterId } = useCluster();

  // Sync the global active cluster context whenever this page loads
  useEffect(() => {
    if (id) setActiveClusterId(id);
  }, [id, setActiveClusterId]);

  useEffect(() => {
    // Clear stale data when switching clusters to prevent flash of old data
    setCluster(null);
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
        <header className="cd-details-header" style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', padding: '0px', gap: '5px', width: '1130px', height: '142px' }}>
          {/* Breadcrumbs (Frame 1000005411) */}
          <div style={{ display: 'flex', flexDirection: 'row', alignItems: 'flex-start', padding: '0px', gap: '5px', width: '140px', height: '20px' }}>
            <div style={{ display: 'flex', flexDirection: 'row', justifyContent: 'center', alignItems: 'center', padding: '0px', gap: '8px', height: '20px' }}>
              <span 
                onClick={() => navigate('/clusters')} 
                style={{ 
                  cursor: 'pointer', 
                  fontFamily: 'Satoshi', 
                  fontWeight: 500, 
                  fontSize: '14px', 
                  lineHeight: '19px', 
                  color: '#818181' 
                }}
              >
                Cluster
              </span>
            </div>
            <span style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', width: '20px', height: '20px', color: '#818181' }}>
              <ChevronRight size={14} />
            </span>
            <div style={{ display: 'flex', flexDirection: 'row', justifyContent: 'center', alignItems: 'center', padding: '0px', gap: '8px', height: '19px' }}>
              <span 
                style={{ 
                  fontFamily: 'Satoshi', 
                  fontWeight: 500, 
                  fontSize: '14px', 
                  lineHeight: '19px', 
                  color: '#3E1363' 
                }}
              >
                {cluster.name}
              </span>
            </div>
          </div>
          
          {/* Title Row (Frame 1000005262) */}
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', padding: '0px', width: '1129px', height: '51px' }}>
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', padding: '0px', gap: '2px', width: '1129px', height: '32px' }}>
              <div style={{ display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '0px', width: '1129px', height: '32px' }}>
                <div style={{ display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '0px', gap: '8px', width: '465px', height: '32px' }}>
                  <h1 style={{ fontFamily: 'Satoshi', fontWeight: 700, fontSize: '24px', lineHeight: '32px', color: '#282F49', margin: 0 }}>
                    Deployment Logs
                  </h1>
                  <div style={{
                    boxSizing: 'border-box',
                    display: 'flex',
                    flexDirection: 'row',
                    justifyContent: 'center',
                    alignItems: 'center',
                    padding: '4px 8px',
                    gap: '10px',
                    width: '59px',
                    height: '24px',
                    background: 'rgba(42, 199, 146, 0.25)',
                    borderRadius: '100px'
                  }}>
                    <span style={{
                      fontFamily: 'Satoshi',
                      fontWeight: 400,
                      fontSize: '12px',
                      lineHeight: '16px',
                      textAlign: 'center',
                      color: '#069B68'
                    }}>
                      Success
                    </span>
                  </div>
                </div>
              </div>
            </div>
            
            {/* Subtitle */}
            <p style={{
              fontFamily: 'Satoshi',
              fontWeight: 400,
              fontSize: '14px',
              lineHeight: '19px',
              color: '#818181',
              margin: '0px',
              width: '162px',
              height: '19px'
            }}>
              {`${cluster.name}Kafka ${cluster.kafkaVersion}${cluster.mode ? cluster.mode.toLowerCase() : ''}`}
            </p>
          </div>

          {/* Back button (Frame 1000005471) */}
          <div style={{ display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '0px', margin: '0 auto', width: '1130px', height: '24px', marginTop: '8px' }}>
            <div style={{ display: 'flex', flexDirection: 'row', alignItems: 'center', padding: '0px', gap: '8px', width: '142px', height: '24px' }}>
              <span 
                onClick={() => navigate(`/clusters/${id}/overview`)} 
                style={{ 
                  cursor: 'pointer', 
                  fontFamily: 'Satoshi', 
                  fontWeight: 500, 
                  fontSize: '16px', 
                  lineHeight: '22px',
                  color: '#5B327F', 
                  display: 'flex', 
                  alignItems: 'center', 
                  gap: '8px' 
                }}
              >
                <ChevronLeft size={24} style={{ color: '#818181' }} /> Logs (Selected)
              </span>
            </div>
          </div>
        </header>

        <div className="cluster-content cluster-logs-content" style={{ marginTop: '16px' }}>
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
    { to: `/clusters/${id}/consumers`, icon: Users, label: 'Consumers', disabled: false },
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
        <header className="cd-details-header">
          {/* Breadcrumbs */}
          <div className="cd-breadcrumbs">
            <span onClick={() => navigate('/clusters')} className="cd-breadcrumb-link">Cluster</span>
            <span className="cd-breadcrumb-separator"><ChevronRight size={14} /></span>
            <span className="cd-breadcrumb-current">{cluster.name}</span>
          </div>
          
          {/* Title Row */}
          <div className="cd-details-title-row">
            <div className="cd-details-title-left">
              <h1>{cluster.name}</h1>
              <div className={`cd-status-badge ${runtimeClass}`} title={cluster.runtimeStatusReason}>
                {runtimeLabel ? runtimeLabel.charAt(0).toUpperCase() + runtimeLabel.slice(1).toLowerCase() : ''}
              </div>
              {cluster.mode === 'EXTERNAL' && (
                <div className={`cd-agent-status-badge ${agentClass}`}>
                  {agentLabel}
                </div>
              )}
            </div>
            <div className="cd-details-actions">
              <button 
                className="cd-details-refresh-btn" 
                onClick={() => {
                  fetch(`/api/v1/ui/clusters/${id}`)
                    .then(res => res.ok ? res.json() : Promise.reject())
                    .then(setCluster)
                    .catch(() => {});
                }}
                title="Refresh Health"
              >
                <RefreshCw size={24} />
              </button>
            </div>
          </div>
          
          {/* Subtitle */}
          <p className="cd-details-subtitle">
            {`Kafka ${cluster.kafkaVersion} • ${cluster.nodeCount || 0} nodes • ${cluster.mode || 'INTERNAL'}`}
          </p>
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
