import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { PlusCircle, Server } from 'lucide-react';
import './Clusters.css';

export function Clusters() {
  const navigate = useNavigate();

  return (
    <div className="clusters-page animate-fade-in">
      <header className="page-header flex-between">
        <div>
          <h1>Kafka Clusters</h1>
          <p>Deploy and manage your Tantor Kafka environments.</p>
        </div>
        <div style={{ display: 'flex', gap: '10px' }}>
          <button className="btn btn-primary" onClick={() => navigate('/clusters/new')}>
            <PlusCircle size={16} /> Deploy New Cluster
          </button>
        </div>
      </header>

      <div className="empty-state glass-panel" style={{ padding: '4rem 2rem', textAlign: 'center' }}>
        <Server size={48} style={{ color: 'var(--accent-primary)', marginBottom: '1rem', opacity: 0.8 }} />
        <h2>No Clusters Found</h2>
        <p style={{ color: 'var(--text-secondary)', marginBottom: '2rem', maxWidth: '400px', margin: '0 auto 2rem auto' }}>
          You haven't deployed any Kafka clusters yet. Click the button below to provision your first cluster using the agent deployment system.
        </p>
        <button className="btn btn-primary" onClick={() => navigate('/clusters/new')} style={{ fontSize: '1.1rem', padding: '0.75rem 2rem' }}>
          Deploy First Cluster
        </button>
      </div>
    </div>
  );
}
