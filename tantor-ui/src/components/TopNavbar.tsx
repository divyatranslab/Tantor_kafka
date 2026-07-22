import { useMemo, useState, useEffect, useRef } from 'react';
import { Bell, LogOut } from 'lucide-react';
import { useAuth } from '../contexts/AuthContext';
import { usePermissions } from '../hooks/usePermissions';
import { useNavigate, useLocation } from 'react-router-dom';
import './TopNavbar.css';
import tantorLogo from '../assets/Tantor-pink-logo.png';

interface ClusterInfo {
  id: string;
  name: string;
  mode: string;
}

interface TopicInfo {
  name: string;
}

export function TopNavbar() {
  const { decodedToken, logout } = useAuth();
  const { isAdmin } = usePermissions();
  const navigate = useNavigate();
  const location = useLocation();

  // State
  const [isProfileOpen, setIsProfileOpen] = useState(false);
  const [alertsCount, setAlertsCount] = useState(0);
  const [searchQuery, setSearchQuery] = useState('');
  const [isSearchFocused, setIsSearchFocused] = useState(false);
  const [allClusters, setAllClusters] = useState<ClusterInfo[]>([]);
  const [clusterTopics, setClusterTopics] = useState<TopicInfo[]>([]);

  const profileRef = useRef<HTMLDivElement>(null);
  const searchRef = useRef<HTMLDivElement>(null);

  // Extract active cluster ID if in a cluster path
  const activeClusterId = useMemo(() => {
    const match = location.pathname.match(/\/clusters\/([^/]+)/);
    return match ? match[1] : null;
  }, [location.pathname]);

  // Resolve the first letter of the username
  const userInitial = useMemo(() => {
    const rawName = decodedToken?.preferred_username || decodedToken?.name || 'User';
    return rawName.charAt(0).toUpperCase();
  }, [decodedToken]);

  const applicationRole = isAdmin ? 'Admin' : 'Monitoring';
  // Fetch alerts count
  useEffect(() => {
    const fetchAlerts = async () => {
      try {
        const res = await fetch('/api/v1/ui/alerts');
        if (res.ok) {
          const data = await res.json();
          setAlertsCount(Array.isArray(data) ? data.length : 0);
        }
      } catch (e) {
        console.error('Failed to fetch alerts count', e);
      }
    };
    fetchAlerts();
    const interval = setInterval(fetchAlerts, 10000);
    return () => clearInterval(interval);
  }, []);

  // Fetch all clusters for search context
  useEffect(() => {
    fetch('/api/v1/ui/clusters')
      .then(res => res.ok ? res.json() : [])
      .then(setAllClusters)
      .catch(console.error);
  }, []);

  // Fetch topics in current cluster for search context
  useEffect(() => {
    if (activeClusterId) {
      fetch(`/api/v1/clusters/${activeClusterId}/topics`)
        .then(res => res.ok ? res.json() : null)
        .then(data => {
          if (data && Array.isArray(data.content)) {
            setClusterTopics(data.content);
          } else {
            setClusterTopics([]);
          }
        })
        .catch(() => {
          setClusterTopics([]);
        });
    } else {
      setClusterTopics([]);
    }
  }, [activeClusterId]);

  // Click outside handlers
  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (profileRef.current && !profileRef.current.contains(event.target as Node)) {
        setIsProfileOpen(false);
      }
      if (searchRef.current && !searchRef.current.contains(event.target as Node)) {
        setIsSearchFocused(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  // Search filter matches
  const filteredResults = useMemo(() => {
    if (!searchQuery.trim()) return { clusters: [], topics: [] };
    const query = searchQuery.toLowerCase();
    const clusters = allClusters.filter(c => c.name.toLowerCase().includes(query));
    const topics = clusterTopics.filter(t => t.name.toLowerCase().includes(query));
    return { clusters, topics };
  }, [searchQuery, allClusters, clusterTopics]);

  const handleSignOut = () => {
    // keycloak.logout() triggers a full-page redirect to the Keycloak
    // end-session endpoint, which then 302-redirects back to the app.
    // Do NOT set window.location.href here — it races with the Keycloak
    // redirect and can prevent proper session termination.
    logout().catch(e => console.error('Logout failed', e));
  };

  return (
    <div className="top-navbar" style={{ position: 'relative' }}>
      <div className="navbar-left">
        <div className="logo-container" style={{ cursor: 'pointer' }} onClick={() => navigate('/dashboard')}>
          <img src={tantorLogo} alt="Tantor" className="header-logo" />
        </div>
      </div>
      
      <div className="navbar-right">


        {/* Notifications Bell */}
        <button className="nav-action-btn" title="Notifications" onClick={() => navigate('/alerts')} style={{ position: 'relative' }}>
          <Bell size={20} />
          {alertsCount > 0 && (
            <span style={{
              position: 'absolute',
              top: '4px',
              right: '4px',
              background: '#EF4444',
              color: '#fff',
              borderRadius: '50%',
              width: '8px',
              height: '8px',
              display: 'block'
            }} />
          )}
        </button>

        {/* Profile Dropdown Container */}
        <div ref={profileRef} style={{ position: 'relative' }}>
          <div className="profile-badge" onClick={() => setIsProfileOpen(!isProfileOpen)} style={{ cursor: 'pointer' }}>
            <span>{userInitial}</span>
          </div>

          {/* Profile Dropdown Card */}
          {isProfileOpen && (
            <div style={{
              position: 'absolute',
              top: '48px',
              right: 0,
              width: '360px',
              background: '#fff',
              border: '1px solid #ECECF1',
              borderRadius: '16px',
              boxShadow: '0 10px 40px rgba(0,0,0,0.1)',
              padding: '24px',
              zIndex: 1000,
              fontFamily: 'Satoshi, Inter, sans-serif'
            }}>
              {/* Profile Header */}
              <div style={{ display: 'flex', gap: '16px', marginBottom: '16px' }}>
                <div style={{
                  width: '48px',
                  height: '48px',
                  borderRadius: '50%',
                  background: '#A78BFA',
                  color: '#fff',
                  display: 'flex',
                  justifyContent: 'center',
                  alignItems: 'center',
                  fontWeight: 600,
                  fontSize: '18px',
                  textTransform: 'uppercase'
                }}>
                  {userInitial}
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
                  <span style={{ fontSize: '16px', fontWeight: 600, color: '#282F49' }}>
                    {decodedToken?.preferred_username || decodedToken?.name || 'User'}
                  </span>
                  {decodedToken?.email && (
                    <span style={{ fontSize: '13px', color: '#64748B' }}>
                      {decodedToken.email}
                    </span>
                  )}
                </div>
              </div>

              {/* Application role: the UI exposes only Admin and Monitoring. */}
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '20px' }}>
                <span style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  background: '#EFF6FF',
                  color: '#3B82F6',
                  border: '1px solid #DBEAFE',
                  fontSize: '12px',
                  fontWeight: 500,
                  padding: '4px 10px',
                  borderRadius: '8px'
                }}>
                  {applicationRole}
                </span>
              </div>

              {/* Divider */}
              <hr style={{ border: 'none', borderTop: '1px solid #f1f5f9', margin: '0 -24px 16px -24px' }} />


              {/* Sign Out Row */}
              <div 
                onClick={handleSignOut} 
                style={{ 
                  display: 'flex', 
                  alignItems: 'center', 
                  gap: '8px', 
                  color: '#EF4444', 
                  fontSize: '14px', 
                  fontWeight: 600, 
                  cursor: 'pointer',
                  padding: '4px 0'
                }}
              >
                <LogOut size={16} />
                Sign Out
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
