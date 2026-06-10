import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { Users, LayoutList } from 'lucide-react';

interface ConsumerGroup {
  groupId: string;
  state: string;
  coordinator: string;
  members: number;
}

export function Consumers() {
  const { id } = useParams<{ id: string }>();
  const [groups, setGroups] = useState<ConsumerGroup[]>([]);
  const [loading, setLoading] = useState(true);

  const fetchGroups = async () => {
    setLoading(true);
    try {
      const res = await fetch(`/api/v1/clusters/${id}/consumer-groups`);
      if (res.ok) setGroups(await res.json());
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchGroups();
  }, [id]);

  return (
    <div className="topics-tab animate-fade-in">
      <div className="topics-header">
        <h2>Consumer Groups</h2>
      </div>

      <div className="table-card">
        {loading ? (
          <div className="empty-state">Loading consumer groups...</div>
        ) : groups.length === 0 ? (
          <div className="empty-state">
            <Users size={32} style={{ color: 'var(--text-secondary)' }} />
            <p className="empty-title">No active consumer groups</p>
            <p className="empty-subtitle">There are no consumers attached to this cluster currently.</p>
          </div>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Group ID</th>
                <th>State</th>
                <th>Members</th>
                <th>Coordinator</th>
              </tr>
            </thead>
            <tbody>
              {groups.map(g => (
                <tr key={g.groupId}>
                  <td style={{ fontWeight: 500 }}>{g.groupId}</td>
                  <td>
                    <span className="status-badge" style={g.state === 'Stable' ? {} : { backgroundColor: '#fefce8', color: '#a16207', borderColor: '#fef08a' }}>
                      <div className="status-dot" style={g.state === 'Stable' ? {} : { backgroundColor: '#eab308' }}></div>
                      {g.state}
                    </span>
                  </td>
                  <td>{g.members}</td>
                  <td style={{ fontFamily: 'monospace', fontSize: '0.875rem' }}>{g.coordinator}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
