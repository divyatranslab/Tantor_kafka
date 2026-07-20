import { useEffect, useMemo, useRef, useState } from 'react';
import { Search, BookOpen, Bell, LogOut, ShieldCheck } from 'lucide-react';
import { useAuth } from '../contexts/AuthContext';
import { usePermissions } from '../hooks/usePermissions';
import './TopNavbar.css';
import tantorLogo from '../assets/Tantor-pink-logo.png';


const normalizedDisplayName = (value?: string) => {
  const parts = (value || '').trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '';

  const adjacentDeduped = parts.filter((part, index) =>
    index === 0 || part.toLocaleLowerCase() !== parts[index - 1].toLocaleLowerCase());
  if (adjacentDeduped.length % 2 === 0) {
    const midpoint = adjacentDeduped.length / 2;
    const firstHalf = adjacentDeduped.slice(0, midpoint).join(' ').toLocaleLowerCase();
    const secondHalf = adjacentDeduped.slice(midpoint).join(' ').toLocaleLowerCase();
    if (firstHalf === secondHalf) return adjacentDeduped.slice(0, midpoint).join(' ');
  }
  return adjacentDeduped.join(' ');
};
export function TopNavbar() {
  const { decodedToken, logout } = useAuth();
  const { effectiveRole } = usePermissions();
  const [profileOpen, setProfileOpen] = useState(false);
  const profileRef = useRef<HTMLDivElement>(null);

  const profile = useMemo(() => {
    const username = decodedToken?.preferred_username || normalizedDisplayName(decodedToken?.name) || 'User';
    const displayName = normalizedDisplayName(decodedToken?.name)
      || normalizedDisplayName([decodedToken?.given_name, decodedToken?.family_name].filter(Boolean).join(' '))
      || username;
    const issuedAt = decodedToken?.auth_time || decodedToken?.iat;
    const memberSince = typeof issuedAt === 'number'
      ? new Date(issuedAt * 1000).toLocaleDateString(undefined, { month: 'long', year: 'numeric' })
      : null;
    return {
      displayName,
      username,
      email: decodedToken?.email,
      role: effectiveRole
        ? effectiveRole.replace(/[_-]+/g, ' ').replace(/\b\w/g, letter => letter.toUpperCase())
        : undefined,
      memberSince,
      initial: displayName.charAt(0).toUpperCase(),
    };
  }, [decodedToken, effectiveRole]);

  useEffect(() => {
    if (!profileOpen) return;
    const closeOnOutsideClick = (event: MouseEvent) => {
      if (!profileRef.current?.contains(event.target as Node)) setProfileOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setProfileOpen(false);
    };
    document.addEventListener('mousedown', closeOnOutsideClick);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('mousedown', closeOnOutsideClick);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [profileOpen]);

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
        <button className="nav-action-btn" title="Documentation" type="button">
          <BookOpen size={20} />
        </button>
        <button className="nav-action-btn" title="Notifications" type="button">
          <Bell size={20} />
        </button>
        <div className="profile-menu-wrap" ref={profileRef}>
          <button
            className="profile-badge"
            type="button"
            aria-label="Open user profile"
            aria-expanded={profileOpen}
            onClick={() => setProfileOpen(open => !open)}
          >
            <span>{profile.initial}</span>
          </button>
          {profileOpen && (
            <div className="profile-popover" role="dialog" aria-label="User profile">
              <div className="profile-popover-main">
                <div className="profile-avatar">{profile.initial}</div>
                <div className="profile-identity">
                  <strong>{profile.displayName}</strong>
                  {profile.email && <span>{profile.email}</span>}
                  {!profile.email && profile.username !== profile.displayName && <span>{profile.username}</span>}
                </div>
                {profile.role && (
                  <div className="profile-role-list">
                    <span className="profile-role">
                      <ShieldCheck size={13} /> {profile.role}
                    </span>
                  </div>
                )}
              </div>
              <div className="profile-popover-footer">
                {profile.memberSince && <span className="profile-member-since">Member since {profile.memberSince}</span>}
                <button type="button" className="profile-signout" onClick={() => void logout()}>
                  <LogOut size={18} /> Sign Out
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}