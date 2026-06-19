import { NavLink } from 'react-router-dom';
import {
  LayoutDashboard,
  Server,
  Network,
  Globe,
  Package,
  PlayCircle,
  ShieldAlert,
  Bell,
  LineChart,
  Settings,
} from 'lucide-react';
import './Sidebar.css';
import tantorLogo from '../assets/tantor-logo.png';

const navSections = [
  {
    label: 'Overview',
    items: [
      { icon: LayoutDashboard, label: 'Dashboard', path: '/' },
      { icon: Server, label: 'Hosts', path: '/hosts' },
      { icon: Network, label: 'Clusters', path: '/clusters' },


      { icon: Globe, label: 'External Clusters', path: '/external-clusters' },
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
      { icon: ShieldAlert, label: 'Audits', path: '/audit' },
      { icon: PlayCircle, label: 'Commands', path: '/commands' },
      { icon: Package, label: 'Artifacts', path: '/artifacts' },
      { icon: Settings, label: 'Administration', path: '/admin' },
    ],
  },
];

export function Sidebar() {
  return (
    <aside className="sidebar">

      {/* Logo */}
      <div className="sidebar-header">
        <img
          src={tantorLogo}
          alt="Tantor"
          className="sidebar-logo"
        />
        <span className="sidebar-tagline">Stream Intelligence</span>
      </div>

      {/* Nav */}
      <nav className="sidebar-nav">
        {navSections.map((section) => (
          <div key={section.label} className="nav-section">
            <span className="nav-section-label">{section.label}</span>
            {section.items.map((item) => (
              <NavLink
                key={item.path}
                to={item.path}
                end={item.path === '/'}
                className={({ isActive }) =>
                  `nav-item${isActive ? ' active' : ''}`
                }
              >
                <item.icon size={15} className="nav-item-icon" />
                <span>{item.label}</span>
              </NavLink>
            ))}
          </div>
        ))}
      </nav>

      {/* Footer */}
      <div className="sidebar-footer">
        <span className="sidebar-version-dot" />
        v1.0.0 · Air-Gapped
      </div>

    </aside>
  );
}
