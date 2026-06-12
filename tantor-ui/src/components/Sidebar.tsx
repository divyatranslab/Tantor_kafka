import { NavLink } from 'react-router-dom';
import {
  LayoutDashboard,
  Server,
  Network,
  Package,
  PlayCircle,
  Activity,
  ShieldAlert,
  Bell,
  LineChart
} from 'lucide-react';
import './Sidebar.css';
import tantorLogo from '../assets/tantor-logo.png';

const navItems = [
  { icon: LayoutDashboard, label: 'Home', path: '/' },
  { icon: Network, label: 'Clusters', path: '/clusters' },
  { icon: Server, label: 'Hosts', path: '/hosts' },
  { icon: LineChart, label: 'Monitoring', path: '/monitoring' },
  { icon: Bell, label: 'Alerts', path: '/alerts' },
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
        <div className="logo-container" style={{ justifyContent: 'center', width: '100%', padding: '0.5rem 0', marginTop: '0.5rem' }}>
          <img 
            src={tantorLogo} 
            alt="Tantor Logo" 
            style={{ 
              width: '120px', 
              height: 'auto', 
              maxHeight: '40px',
              objectFit: 'contain', 
              filter: 'invert(1) opacity(0.9)'
            }} 
          />
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
