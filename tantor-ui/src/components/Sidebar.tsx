import { NavLink } from 'react-router-dom';
import { 
  LayoutDashboard, 
  Server, 
  Network, 
  Package, 
  PlayCircle, 
  Activity, 
  ShieldAlert,
  Link2,
  KeyRound,
  Users,
  Shield,
  Plug,
  Globe2,
  ListRestart
} from 'lucide-react';
import './Sidebar.css';

const navItems = [
  { icon: LayoutDashboard, label: 'Dashboard', path: '/' },
  { icon: Server, label: 'Hosts', path: '/hosts' },
  { icon: Network, label: 'Clusters', path: '/clusters' },
  { icon: Plug, label: 'External Clusters', path: '/external-clusters' },
  { icon: Globe2, label: 'Federation', path: '/federation' },
  { icon: Package, label: 'Artifacts', path: '/artifacts' },
  { icon: PlayCircle, label: 'Deployments', path: '/deployments' },
  { icon: Activity, label: 'Monitoring', path: '/monitoring' },
  { icon: ShieldAlert, label: 'Alerts', path: '/alerts' },
  { icon: Link2, label: 'Cluster Linking', path: '/cluster-linking' },
  { icon: Shield, label: 'Security Scan', path: '/security-scan' },
  { icon: ListRestart, label: 'Activity Logs', path: '/activity' },
  { icon: Users, label: 'User Management', path: '/users' },
  { icon: KeyRound, label: 'LDAP / AD', path: '/ldap-settings' },
  { icon: ShieldAlert, label: 'Audit Logs', path: '/audit' },
];

export function Sidebar() {
  return (
    <aside className="sidebar glass-panel">
      <div className="sidebar-header">
        <div className="logo-container">
          <div className="logo-icon">🐘</div>
          <h1 className="logo-text">Tantor</h1>
        </div>
      </div>
      
      <nav className="sidebar-nav">
        {navItems.map((item) => (
          <NavLink 
            key={item.path} 
            to={item.path}
            className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
          >
            <item.icon size={20} />
            <span>{item.label}</span>
          </NavLink>
        ))}
      </nav>
      
      <div className="sidebar-footer">
        <div className="version-tag">v1.0.0-AirGapped</div>
      </div>
    </aside>
  );
}
