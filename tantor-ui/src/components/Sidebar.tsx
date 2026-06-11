import { NavLink } from 'react-router-dom';
import { 
  LayoutDashboard, 
  Server, 
  Network, 
  Package, 
  PlayCircle, 
  Activity, 
  ShieldAlert 
} from 'lucide-react';
import './Sidebar.css';

const navItems = [
  { icon: LayoutDashboard, label: 'Home', path: '/' },
  { icon: Network, label: 'Clusters', path: '/clusters' },
  { icon: Server, label: 'Hosts', path: '/hosts' },
  { icon: Activity, label: 'Diagnostics', path: '/diagnostics' },
  { icon: ShieldAlert, label: 'Audits', path: '/audit' },
  { icon: PlayCircle, label: 'Running Commands', path: '/commands' },
  { icon: LayoutDashboard, label: 'Data Services', path: '/services' },
  { icon: Package, label: 'Parcels', path: '/artifacts' },
  { icon: ShieldAlert, label: 'Administration', path: '/admin' },
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
