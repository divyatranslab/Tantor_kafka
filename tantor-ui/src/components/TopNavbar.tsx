import { useMemo } from 'react';
import { Search, BookOpen, Bell } from 'lucide-react';
import { useAuth } from '../contexts/AuthContext';
import './TopNavbar.css';
import tantorLogo from '../assets/Tantor-pink-logo.png';

export function TopNavbar() {
  const { decodedToken } = useAuth();

  // Resolve the first letter of the username
  const userInitial = useMemo(() => {
    const rawName = decodedToken?.preferred_username || decodedToken?.name || 'User';
    return rawName.charAt(0).toUpperCase();
  }, [decodedToken]);

  return (
    <div className="top-navbar">
      <div className="navbar-left">
        <div className="logo-container">
          <img src={tantorLogo} alt="Tantor" className="header-logo" />
        </div>
      </div>
      <div className="navbar-right">
        <div className="search-container">
          <Search size={18} className="search-icon" />
          <input type="text" placeholder="Search" className="search-input" />
        </div>
        <button className="nav-action-btn" title="Documentation">
          <BookOpen size={20} />
        </button>
        <button className="nav-action-btn" title="Notifications">
          <Bell size={20} />
        </button>
        <div className="profile-badge">
          <span>{userInitial}</span>
        </div>
      </div>
    </div>
  );
}
