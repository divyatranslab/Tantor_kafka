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
          <svg className="search-icon" width="18" height="18" viewBox="0 0 18 18" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M16.1383 17.1923L9.8575 10.9113C9.3575 11.3241 8.7825 11.6472 8.1325 11.8805C7.4825 12.1138 6.81008 12.2305 6.11525 12.2305C4.40608 12.2305 2.95958 11.6388 1.77575 10.4553C0.591917 9.27175 0 7.82558 0 6.11675C0 4.40808 0.59175 2.96142 1.77525 1.77675C2.95875 0.59225 4.40492 0 6.11375 0C7.82242 0 9.26908 0.591916 10.4537 1.77575C11.6382 2.95958 12.2305 4.40608 12.2305 6.11525C12.2305 6.82942 12.1107 7.5115 11.871 8.1615C11.6312 8.8115 11.3112 9.37683 10.9113 9.8575L17.192 16.1383L16.1383 17.1923ZM6.11525 10.7308C7.40375 10.7308 8.49508 10.2836 9.38925 9.38925C10.2836 8.49508 10.7308 7.40375 10.7308 6.11525C10.7308 4.82675 10.2836 3.73542 9.38925 2.84125C8.49508 1.94692 7.40375 1.49975 6.11525 1.49975C4.82675 1.49975 3.73542 1.94692 2.84125 2.84125C1.94692 3.73542 1.49975 4.82675 1.49975 6.11525C1.49975 7.40375 1.94692 8.49508 2.84125 9.38925C3.73542 10.2836 4.82675 10.7308 6.11525 10.7308Z" fill="#818181"/>
          </svg>
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
