import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { Play } from 'lucide-react';
import './KsqlDb.css';

export function KsqlDb() {
  const { id } = useParams<{ id: string }>();
  const [activeTab, setActiveTab] = useState<'tables' | 'streams'>('tables');
  const [tables, setTables] = useState<any[]>([]);
  const [streams, setStreams] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // In a real implementation, we would fetch from the backend
    setLoading(false);
    setTables([]);
    setStreams([]);
  }, [id]);

  if (loading) {
    return <div className="state-center">Loading KSQL DB data...</div>;
  }

  return (
    <div className="ksqldb-container animate-fade-in">
      <div className="ksqldb-header">
        <div className="header-actions">
          <button className="btn-primary">
            <Play size={16} style={{ marginRight: '6px' }} />
            Execute KSQL Request
          </button>
        </div>
      </div>

      <div className="summary-cards">
        <div className="summary-card">
          <span className="card-title">Tables</span>
          <span className="card-value">{tables.length}</span>
        </div>
        <div className="summary-card">
          <span className="card-title">Streams</span>
          <span className="card-value">{streams.length}</span>
        </div>
      </div>

      <div className="kc-tabs-and-content">
        <div className="kc-tabs">
          <div
            className={`kc-tab ${activeTab === 'tables' ? 'active' : ''}`}
            onClick={() => setActiveTab('tables')}
          >
            Tables
          </div>
          <div
            className={`kc-tab ${activeTab === 'streams' ? 'active' : ''}`}
            onClick={() => setActiveTab('streams')}
          >
            Streams
          </div>
        </div>

        <div className="table-container glass-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Topic</th>
                <th>Key Format</th>
                <th>Value Format</th>
                <th>Is Windowed</th>
              </tr>
            </thead>
            <tbody>
              {activeTab === 'tables' ? (
                tables.length === 0 ? (
                  <tr>
                    <td colSpan={5} className="empty-state">No rows found</td>
                  </tr>
                ) : (
                  tables.map((t, i) => (
                    <tr key={i}>
                      <td colSpan={5}>{t.name}</td>
                    </tr>
                  ))
                )
              ) : (
                streams.length === 0 ? (
                  <tr>
                    <td colSpan={5} className="empty-state">No rows found</td>
                  </tr>
                ) : (
                  streams.map((s, i) => (
                    <tr key={i}>
                      <td colSpan={5}>{s.name}</td>
                    </tr>
                  ))
                )
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
