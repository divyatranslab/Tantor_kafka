import { useState } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import {
  Activity,
  Bell,
  ChevronDown,
  ChevronRight,
  LayoutDashboard,
  LineChart,
  LogOut,
  Network,
  Package,
  PlayCircle,
  Server,
  Settings,
  ShieldAlert,
  Users,
} from 'lucide-react';
import './Sidebar.css';
import tantorLogo from '../assets/tantor-logo.png';
import { useAuth } from '../contexts/AuthContext';

type NavItem = {
  icon?: any;
  label: string;
  path?: string;
  subItems?: NavItem[];
};

type NavSection = {
  label: string;
  items: NavItem[];
};

const navSections: NavSection[] = [
  {
    label: 'Overview',
    items: [
      { icon: LayoutDashboard, label: 'Dashboard', path: '/dashboard' },
      {
        icon: Network,
        label: 'Clusters',
        subItems: [
          { label: 'All Clusters', path: '/clusters' },
        ],
      },
      { icon: Server, label: 'Hosts', path: '/hosts' },
    ],
  },
  {
    label: 'Observability',
    items: [
      { icon: LineChart, label: 'Monitoring', path: '/monitoring' },
      { icon: Bell, label: 'Alerts', path: '/alerts' },
    ],
  },
  {
    label: 'Management',
    items: [
      { icon: Users, label: 'User Management', path: '/user-management' },
      { icon: ShieldAlert, label: 'Audits', path: '/audit' },
      { icon: Activity, label: 'Jobs', path: '/jobs' },
      { icon: PlayCircle, label: 'Commands', path: '/commands' },
      { icon: Package, label: 'Artifacts', path: '/artifacts' },
      { icon: Settings, label: 'LDAP Settings', path: '/ldap-settings' },
      { icon: Settings, label: 'Administration', path: '/admin' },
    ],
  },
];

const hiddenNavPaths = new Set(['/user-management', '/commands', '/ldap-settings', '/admin']);

export function Sidebar() {
  const { decodedToken, logout } = useAuth();
  const [expandedItems, setExpandedItems] = useState<Record<string, boolean>>({
    Clusters: true,
  });
  const location = useLocation();
  const displayName = decodedToken?.preferred_username || decodedToken?.name || 'Authenticated';

  const toggleExpand = (label: string) => {
    setExpandedItems(prev => ({ ...prev, [label]: !prev[label] }));
  };

  const renderItem = (item: NavItem, depth = 0) => {
    if (item.subItems) {
      const isExpanded = expandedItems[item.label];
      const isActive = item.subItems.some(sub => location.pathname === sub.path || (sub.path && sub.path !== '/' && location.pathname.startsWith(sub.path + '/')));

      return (
        <div key={item.label} className="nav-item-group">
          <div
            className={`nav-item ${isActive ? 'active-parent' : ''}`}
            style={{ paddingLeft: `${18 + depth * 12}px` }}
            onClick={() => toggleExpand(item.label)}
          >
            {item.icon && <item.icon size={15} className="nav-item-icon" />}
            <span style={{ flex: 1 }}>{item.label}</span>
            {isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
          </div>
          {isExpanded && (
            <div className="nav-subitems">
              {item.subItems.map(sub => renderItem(sub, depth + 1))}
            </div>
          )}
        </div>
      );
    }

    return (
      <NavLink
        key={item.label}
        to={item.path!}
        end={item.path === '/dashboard'}
        className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}
        style={{ paddingLeft: `${18 + depth * 22}px` }}
      >
        {item.icon && <item.icon size={15} className="nav-item-icon" />}
        {!item.icon && <span className="nav-item-dot" />}
        <span>{item.label}</span>
      </NavLink>
    );
  };

  return (
    <aside className="sidebar">
      <div className="sidebar-header">
        <img
          src={tantorLogo}
          alt="Tantor"
          className="sidebar-logo"
        />
        <span className="sidebar-tagline">Stream Intelligence</span>
      </div>

      <nav className="sidebar-nav">
        {navSections.map(section => (
          <div key={section.label} className="nav-section">
            <span className="nav-section-label">{section.label}</span>
            {section.items
              .filter(item => !item.path || !hiddenNavPaths.has(item.path))
              .map(item => renderItem(item))}
          </div>
        ))}
      </nav>

      <div className="sidebar-footer">
        <div className="sidebar-user">
          <span className="sidebar-version-dot" />
          <span className="sidebar-user-name" title={displayName}>{displayName}</span>
        </div>
        <button className="sidebar-logout" type="button" onClick={() => void logout()} title="Logout">
          <LogOut size={15} />
        </button>
      </div>
    </aside>
  );
}
