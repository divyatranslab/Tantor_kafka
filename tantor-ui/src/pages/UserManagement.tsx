import { useState, useEffect } from 'react';
import { Users, Plus, Shield, Eye, Trash2, Key, Check, X } from 'lucide-react';

interface UserResponse {
  id: string;
  username: string;
  role: string;
  is_active: boolean;
  auth_source: string;
  ldap_dn?: string;
  last_login?: string;
  created_at: string;
}

export function UserManagement() {
  const [users, setUsers] = useState<UserResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreate, setShowCreate] = useState(false);
  const [newUser, setNewUser] = useState({ username: '', password: '', role: 'monitor' });
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState('');
  const [editingPassword, setEditingPassword] = useState<string | null>(null);
  const [newPassword, setNewPassword] = useState('');

  const fetchUsers = async () => {
    try {
      const res = await fetch('/api/v1/users');
      if (res.ok) {
        const data = await res.json();
        setUsers(data);
      } else {
        throw new Error('Failed to load users');
      }
    } catch (err: any) {
      setError(err.message || 'Failed to load users');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchUsers(); }, []);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setCreating(true);
    setError('');
    try {
      const res = await fetch('/api/v1/users', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(newUser)
      });
      if (!res.ok) {
        const err = await res.json();
        throw new Error(err.detail || 'Failed to create user');
      }
      setShowCreate(false);
      setNewUser({ username: '', password: '', role: 'monitor' });
      fetchUsers();
    } catch (err: any) {
      setError(err.message || 'Failed to create user');
    } finally {
      setCreating(false);
    }
  };

  const handleToggleRole = async (user: UserResponse) => {
    const newRole = user.role === 'admin' ? 'monitor' : 'admin';
    try {
      const res = await fetch(`/api/v1/users/${user.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ role: newRole })
      });
      if (!res.ok) {
        const err = await res.json();
        throw new Error(err.detail || 'Failed to update role');
      }
      fetchUsers();
    } catch (err: any) {
      setError(err.message || 'Failed to update role');
    }
  };

  const handleToggleActive = async (user: UserResponse) => {
    try {
      const res = await fetch(`/api/v1/users/${user.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ is_active: !user.is_active })
      });
      if (!res.ok) {
        const err = await res.json();
        throw new Error(err.detail || 'Failed to update status');
      }
      fetchUsers();
    } catch (err: any) {
      setError(err.message || 'Failed to update status');
    }
  };

  const handleDelete = async (user: UserResponse) => {
    if (!confirm(`Delete user "${user.username}"? This cannot be undone.`)) return;
    try {
      const res = await fetch(`/api/v1/users/${user.id}`, { method: 'DELETE' });
      if (!res.ok) {
        const err = await res.json();
        throw new Error(err.detail || 'Failed to delete user');
      }
      fetchUsers();
    } catch (err: any) {
      setError(err.message || 'Failed to delete user');
    }
  };

  const handlePasswordChange = async (userId: string) => {
    if (!newPassword) return;
    try {
      const res = await fetch(`/api/v1/users/${userId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password: newPassword })
      });
      if (!res.ok) {
        const err = await res.json();
        throw new Error(err.detail || 'Failed to change password');
      }
      setEditingPassword(null);
      setNewPassword('');
    } catch (err: any) {
      setError(err.message || 'Failed to change password');
    }
  };

  if (loading) {
    return (
      <div className="flex-row justify-center" style={{ height: '16rem' }}>
        <div className="w-8 h-8 border-4 border-blue-500/30 border-t-blue-500 rounded-full animate-spin" style={{ width: '32px', height: '32px', border: '4px solid var(--border-default)', borderTopColor: 'var(--accent-primary)', borderRadius: '50%' }} />
      </div>
    );
  }

  return (
    <div className="migrated-page">
      <div className="page-header">
        <div>
          <h1 className="page-title">
            <Users size={24} style={{ color: 'var(--accent-primary)' }} />
            User Management
          </h1>
          <p className="page-subtitle">Manage application users and their roles</p>
        </div>
        <button
          onClick={() => setShowCreate(!showCreate)}
          className="btn btn-primary"
        >
          <Plus size={18} />
          Add User
        </button>
      </div>

      {error && (
        <div className="alert alert-error flex-row justify-between mb-4 w-full">
          <span>{error}</span>
          <button onClick={() => setError('')} className="btn-icon text-danger">
            <X size={16} />
          </button>
        </div>
      )}

      {/* Create Form */}
      {showCreate && (
        <div className="migrated-card mb-6">
          <h3 style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--text-primary)', marginBottom: '1rem' }}>Create New User</h3>
          <form onSubmit={handleCreate} className="flex-row gap-4" style={{ alignItems: 'flex-end' }}>
            <div className="form-group flex-1" style={{ marginBottom: 0 }}>
              <label className="form-label">Username</label>
              <input
                type="text"
                value={newUser.username}
                onChange={(e) => setNewUser({ ...newUser, username: e.target.value })}
                required
                className="form-input"
                placeholder="username"
              />
            </div>
            <div className="form-group flex-1" style={{ marginBottom: 0 }}>
              <label className="form-label">Password</label>
              <input
                type="password"
                value={newUser.password}
                onChange={(e) => setNewUser({ ...newUser, password: e.target.value })}
                required
                className="form-input"
                placeholder="password"
              />
            </div>
            <div className="form-group" style={{ width: '160px', marginBottom: 0 }}>
              <label className="form-label">Role</label>
              <select
                value={newUser.role}
                onChange={(e) => setNewUser({ ...newUser, role: e.target.value })}
                className="form-select"
              >
                <option value="monitor">Monitor</option>
                <option value="admin">Admin</option>
              </select>
            </div>
            <button
              type="submit"
              disabled={creating}
              className="btn btn-primary"
              style={{ padding: '0.5rem 1rem' }}
            >
              {creating ? 'Creating...' : 'Create'}
            </button>
            <button
              type="button"
              onClick={() => setShowCreate(false)}
              className="btn btn-secondary"
              style={{ padding: '0.5rem 1rem' }}
            >
              Cancel
            </button>
          </form>
        </div>
      )}

      {/* Users Table */}
      <div className="table-container">
        <table className="migrated-table">
          <thead>
            <tr>
              <th>User</th>
              <th>Source</th>
              <th>Role</th>
              <th>Status</th>
              <th>Last Login</th>
              <th>Created</th>
              <th style={{ textAlign: 'right' }}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {users.length === 0 ? (
              <tr>
                <td colSpan={7} className="text-center" style={{ fontStyle: 'italic', color: 'var(--text-tertiary)' }}>No users found</td>
              </tr>
            ) : (
            users.map((user) => (
              <tr key={user.id}>
                <td style={{ fontWeight: 500 }}>
                  {user.username}
                </td>
                <td>
                  {user.auth_source === 'ldap' ? (
                    <span
                      className="badge badge-info"
                      title={user.ldap_dn || 'Synced from directory'}
                    >
                      LDAP
                    </span>
                  ) : (
                    <span className="badge badge-neutral">
                      local
                    </span>
                  )}
                </td>
                <td>
                  <button
                    onClick={() => handleToggleRole(user)}
                    className={`badge ${user.role === 'admin' ? 'badge-warning' : 'badge-info'}`}
                    style={{ cursor: 'pointer', padding: '4px 8px' }}
                  >
                    {user.role === 'admin' ? <Shield size={12} style={{ marginRight: '4px' }} /> : <Eye size={12} style={{ marginRight: '4px' }} />}
                    {user.role}
                  </button>
                </td>
                <td>
                  <button
                    onClick={() => handleToggleActive(user)}
                    className={`badge ${user.is_active ? 'badge-success' : 'badge-error'}`}
                    style={{ cursor: 'pointer', padding: '4px 8px' }}
                  >
                    {user.is_active ? <Check size={12} style={{ marginRight: '4px' }} /> : <X size={12} style={{ marginRight: '4px' }} />}
                    {user.is_active ? 'Active' : 'Disabled'}
                  </button>
                </td>
                <td style={{ color: 'var(--text-secondary)' }}>
                  {user.last_login ? new Date(user.last_login).toLocaleString() : 'Never'}
                </td>
                <td style={{ color: 'var(--text-secondary)' }}>
                  {new Date(user.created_at).toLocaleDateString()}
                </td>
                <td>
                  <div className="flex-row gap-2" style={{ justifyContent: 'flex-end' }}>
                    {editingPassword === user.id ? (
                      <div className="flex-row gap-2">
                        <input
                          type="password"
                          value={newPassword}
                          onChange={(e) => setNewPassword(e.target.value)}
                          placeholder="New password"
                          className="form-input"
                          style={{ width: '120px', padding: '0.25rem 0.5rem' }}
                          autoFocus
                        />
                        <button
                          onClick={() => handlePasswordChange(user.id)}
                          className="btn-icon" style={{ color: 'var(--color-success)' }}
                        >
                          <Check size={16} />
                        </button>
                        <button
                          onClick={() => { setEditingPassword(null); setNewPassword(''); }}
                          className="btn-icon text-danger"
                        >
                          <X size={16} />
                        </button>
                      </div>
                    ) : (
                      user.auth_source === 'ldap' ? (
                        <span
                          className="btn-icon" style={{ opacity: 0.5, cursor: 'not-allowed' }}
                          title="Password is managed by your directory (LDAP)"
                        >
                          <Key size={16} />
                        </span>
                      ) : (
                        <button
                          onClick={() => setEditingPassword(user.id)}
                          className="btn-icon"
                          title="Change password"
                        >
                          <Key size={16} />
                        </button>
                      )
                    )}
                    <button
                      onClick={() => handleDelete(user)}
                      className="btn-icon text-danger"
                      title="Delete user"
                    >
                      <Trash2 size={16} />
                    </button>
                  </div>
                </td>
              </tr>
            ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
