import { useState, useEffect } from 'react';
import { PlusCircle, Activity, Server, RefreshCw } from 'lucide-react';
import './Clusters.css';

export function Clusters() {
  const [showDeployModal, setShowDeployModal] = useState(false);
  const [hosts, setHosts] = useState<any[]>([]);
  const [selectedHosts, setSelectedHosts] = useState<string[]>([]);
  const [version, setVersion] = useState('3.7.0');
  const [artifactUrl, setArtifactUrl] = useState('http://localhost:8081/kafka_2.13-3.7.0.tgz');
  const [isDeploying, setIsDeploying] = useState(false);

  const fetchHosts = async () => {
    try {
      const response = await fetch('/api/v1/ui/hosts');
      if (response.ok) {
        const data = await response.json();
        // Only show ONLINE hosts for deployment
        setHosts(data.filter((h: any) => h.status === 'ONLINE'));
      }
    } catch (error) {
      console.error('Error fetching hosts:', error);
    }
  };

  useEffect(() => {
    if (showDeployModal) {
      fetchHosts();
    }
  }, [showDeployModal]);

  const toggleHostSelection = (hostId: string) => {
    setSelectedHosts(prev => 
      prev.includes(hostId) ? prev.filter(id => id !== hostId) : [...prev, hostId]
    );
  };

  const handleDeploy = async () => {
    if (selectedHosts.length === 0) {
      alert("Please select at least one host to deploy the cluster to.");
      return;
    }

    setIsDeploying(true);
    const selectedHostObjects = hosts.filter(h => selectedHosts.includes(h.id));
    
    try {
      const response = await fetch('/api/v1/ui/clusters/deploy', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          version,
          artifactUrl,
          hosts: selectedHostObjects.map(h => ({ id: h.id, hostname: h.hostname }))
        })
      });

      if (response.ok) {
        alert("Deployment initialized successfully! The agent will start setting up Kafka on the selected nodes.");
        setShowDeployModal(false);
        setSelectedHosts([]);
      } else {
        alert("Failed to initialize deployment.");
      }
    } catch (error) {
      console.error('Error deploying cluster:', error);
      alert("An error occurred while deploying the cluster.");
    } finally {
      setIsDeploying(false);
    }
  };

  return (
    <div className="clusters-page animate-fade-in">
      <header className="page-header flex-between">
        <div>
          <h1>Kafka Clusters</h1>
          <p>Deploy and manage your Tantor Kafka environments.</p>
        </div>
        <div style={{ display: 'flex', gap: '10px' }}>
          <button className="btn btn-primary" onClick={() => setShowDeployModal(true)}>
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
        <button className="btn btn-primary" onClick={() => setShowDeployModal(true)} style={{ fontSize: '1.1rem', padding: '0.75rem 2rem' }}>
          Deploy First Cluster
        </button>
      </div>

      {showDeployModal && (
        <div className="modal-overlay">
          <div className="glass-panel modal-content animate-fade-in" style={{ padding: '2rem', maxWidth: '600px', width: '100%', position: 'relative' }}>
            <button 
              onClick={() => setShowDeployModal(false)}
              className="modal-close-btn"
            >
              &times;
            </button>
            <h2 style={{ marginBottom: '1rem', color: 'white' }}>Deploy Kafka Cluster</h2>
            <p style={{ color: 'var(--text-secondary)', marginBottom: '2rem' }}>Configure the cluster topology and select the target nodes for agent-based deployment.</p>
            
            <div className="form-group">
              <label>Kafka Version</label>
              <select className="form-control" value={version} onChange={(e) => setVersion(e.target.value)}>
                <option value="3.7.0">Apache Kafka 3.7.0 (KRaft)</option>
                <option value="3.6.1">Apache Kafka 3.6.1 (KRaft)</option>
              </select>
            </div>

            <div className="form-group" style={{ marginTop: '1rem' }}>
              <label>Artifact URL (Internal Binary Repository)</label>
              <input 
                type="text" 
                className="form-control" 
                value={artifactUrl} 
                onChange={(e) => setArtifactUrl(e.target.value)}
                placeholder="http://tantor-artifact-repository:8081/kafka.tgz"
              />
            </div>

            <div className="form-group" style={{ marginTop: '2rem' }}>
              <label>Select Target Hosts ({selectedHosts.length} selected)</label>
              <div className="host-selection-list">
                {hosts.length === 0 ? (
                  <div className="no-hosts-warning">
                    No ONLINE hosts found. Please enroll and connect nodes first in the Hosts tab.
                  </div>
                ) : (
                  hosts.map(host => (
                    <div 
                      key={host.id} 
                      className={`host-selectable-card ${selectedHosts.includes(host.id) ? 'selected' : ''}`}
                      onClick={() => toggleHostSelection(host.id)}
                    >
                      <div className="host-info">
                        <strong>{host.hostname}</strong>
                        <span className="text-secondary">{host.agentVersion}</span>
                      </div>
                      <div className="checkbox-indicator">
                        {selectedHosts.includes(host.id) && <div className="checked-dot"></div>}
                      </div>
                    </div>
                  ))
                )}
              </div>
            </div>

            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '1rem', marginTop: '2rem' }}>
              <button className="btn" style={{ background: 'transparent', border: '1px solid var(--text-secondary)', color: 'white' }} onClick={() => setShowDeployModal(false)}>
                Cancel
              </button>
              <button 
                className="btn btn-primary" 
                onClick={handleDeploy} 
                disabled={isDeploying || selectedHosts.length === 0}
              >
                {isDeploying ? <RefreshCw size={16} className="spin" /> : <Activity size={16} />}
                {isDeploying ? 'Deploying...' : 'Deploy Cluster'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
