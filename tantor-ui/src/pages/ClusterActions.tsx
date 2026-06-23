import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { Activity, Play, RefreshCw, CheckCircle2, XCircle, ArrowUpCircle } from 'lucide-react';

interface ClusterInfo {
  id: string;
  kafkaVersion: string;
  status: string;
  mode: string;
  managementLevel?: string;
}

interface HostParcel {
  hostId: string;
  serviceType: string;
  version: string;
  status: string;
  active: boolean;
}

export function ClusterActions() {
  const { id } = useParams<{ id: string }>();
  const [taskId, setTaskId] = useState<string | null>(null);
  const [status, setStatus] = useState<string>('');
  const [loading, setLoading] = useState(false);
  const [cluster, setCluster] = useState<ClusterInfo | null>(null);
  const [parcels, setParcels] = useState<HostParcel[]>([]);
  const [targetVersion, setTargetVersion] = useState('');
  const [upgradeMsg, setUpgradeMsg] = useState('');
  const [upgradeLoading, setUpgradeLoading] = useState(false);

  const fetchUpgradeContext = async () => {
    try {
      const [clusterRes, parcelsRes] = await Promise.all([
        fetch(`/api/v1/ui/clusters/${id}`),
        fetch('/api/v1/ui/parcels'),
      ]);
      if (clusterRes.ok) setCluster(await clusterRes.json());
      if (parcelsRes.ok) setParcels(await parcelsRes.json());
    } catch (e) {
      console.error(e);
    }
  };

  const triggerRollingRestart = async () => {
    if (!window.confirm("WARNING: This will begin a rolling restart of the cluster. Continue?")) return;
    
    setLoading(true);
    try {
      const res = await fetch(`/api/v1/clusters/${id}/actions/rolling-restart`, { method: 'POST' });
      if (res.ok) {
        const data = await res.json();
        setTaskId(data.taskId);
        setStatus("Initialization started...");
      } else {
        alert("Failed to trigger rolling restart.");
      }
    } catch (e) {
      alert("Error triggering rolling restart.");
    } finally {
      setLoading(false);
    }
  };

  const activeUpgradeVersions = Array.from(new Set(
    parcels
      .filter(p => p.serviceType === 'KAFKA' && p.active && p.status === 'ACTIVE' && p.version !== cluster?.kafkaVersion)
      .map(p => p.version)
  )).sort((a, b) => a.localeCompare(b, undefined, { numeric: true })).reverse();
  const isExternal = cluster?.mode === 'EXTERNAL';
  const externalCanRestart = !isExternal || cluster?.managementLevel === 'AGENT_MANAGED';

  useEffect(() => {
    fetchUpgradeContext();
  }, [id]);

  useEffect(() => {
    if (!targetVersion && activeUpgradeVersions.length > 0) {
      setTargetVersion(activeUpgradeVersions[0]);
    }
  }, [activeUpgradeVersions, targetVersion]);

  const triggerUpgrade = async () => {
    if (!targetVersion) return;
    if (!window.confirm(`Upgrade this cluster from Kafka ${cluster?.kafkaVersion || 'current'} to ${targetVersion}?`)) return;

    setUpgradeLoading(true);
    setUpgradeMsg('');
    try {
      const res = await fetch(`/api/v1/ui/clusters/${id}/upgrade`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ targetVersion }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        throw new Error(data.error || 'Failed to schedule upgrade.');
      }
      setUpgradeMsg(`Upgrade to Kafka ${targetVersion} scheduled. Watch Deployment Logs for symlink switch, validation, and automatic rollback details.`);
      await fetchUpgradeContext();
    } catch (e: any) {
      setUpgradeMsg(e.message || 'Failed to schedule upgrade.');
    } finally {
      setUpgradeLoading(false);
    }
  };

  useEffect(() => {
    if (!taskId) return;

    const interval = setInterval(async () => {
      try {
        const res = await fetch(`/api/v1/clusters/${id}/actions/tasks/${taskId}`);
        if (res.ok) {
          const data = await res.json();
          setStatus(data.status);
          if (data.status.startsWith('COMPLETED') || data.status.startsWith('FAILED')) {
            clearInterval(interval);
          }
        }
      } catch (e) {
        console.error(e);
      }
    }, 2000);

    return () => clearInterval(interval);
  }, [taskId, id]);

  return (
    <div className="topics-tab" style={{ maxWidth: '800px' }}>
      <div className="topics-header">
        <div>
          <h2>Cluster Actions</h2>
          <p style={{ fontSize: '0.875rem', color: 'var(--text-secondary)', marginTop: '0.25rem' }}>Perform disruptive day-two operations on your cluster.</p>
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr', gap: '1.5rem' }}>
        {!isExternal && (
        <div className="table-card">
          <div style={{ padding: '1.5rem', borderBottom: '1px solid var(--border-color)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', marginBottom: '1rem' }}>
              <div style={{ padding: '0.625rem', backgroundColor: '#f0fdf4', color: '#16a34a', borderRadius: '0.5rem' }}>
                <ArrowUpCircle size={24} />
              </div>
              <div>
                <h3 style={{ fontWeight: 600, color: 'var(--text-primary)', margin: 0 }}>Upgrade Kafka Version</h3>
                <p style={{ fontSize: '0.875rem', color: 'var(--text-secondary)', margin: 0 }}>
                  Apply an active parcel version to this running cluster
                </p>
              </div>
            </div>

            <p style={{ fontSize: '0.875rem', color: '#4b5563', marginBottom: '1rem', lineHeight: 1.5 }}>
              Current cluster version: <strong>Kafka {cluster?.kafkaVersion || '-'}</strong>. Choose a newer active parcel, then Tantor stages the target binaries into the versioned install directory, switches the stable Kafka symlink, validates the service, and automatically rolls back to the previous symlink target if validation fails.
            </p>

            <div style={{ display: 'grid', gridTemplateColumns: 'minmax(180px, 260px) 1fr', gap: '0.75rem', alignItems: 'center' }}>
              <select
                className="form-control"
                value={targetVersion}
                onChange={e => setTargetVersion(e.target.value)}
                disabled={upgradeLoading || activeUpgradeVersions.length === 0}
              >
                {activeUpgradeVersions.length === 0 ? (
                  <option value="">No active upgrade parcel</option>
                ) : activeUpgradeVersions.map(version => (
                  <option key={version} value={version}>Kafka {version}</option>
                ))}
              </select>
              <button
                className="btn btn-primary-action"
                style={{ justifyContent: 'center' }}
                onClick={triggerUpgrade}
                disabled={upgradeLoading || !targetVersion || cluster?.status !== 'SUCCESS'}
              >
                {upgradeLoading ? <RefreshCw size={16} className="spin" /> : <ArrowUpCircle size={16} />}
                Upgrade Kafka
              </button>
            </div>

            {upgradeMsg && (
              <p style={{ margin: '1rem 0 0', fontSize: '0.875rem', color: upgradeMsg.startsWith('Failed') ? '#b91c1c' : '#166534' }}>
                {upgradeMsg}
              </p>
            )}
          </div>
        </div>
        )}
        
        {/* Rolling Restart Card */}
        <div className="table-card">
          <div style={{ padding: '1.5rem', borderBottom: '1px solid var(--border-color)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', marginBottom: '1rem' }}>
              <div style={{ padding: '0.625rem', backgroundColor: '#eff6ff', color: '#2563eb', borderRadius: '0.5rem' }}>
                <RefreshCw size={24} />
              </div>
              <div>
                <h3 style={{ fontWeight: 600, color: 'var(--text-primary)', margin: 0 }}>Rolling Restart</h3>
                <p style={{ fontSize: '0.875rem', color: 'var(--text-secondary)', margin: 0 }}>
                  {isExternal ? 'Queue restart through the discovery agent' : 'Safely restart brokers one-by-one'}
                </p>
              </div>
            </div>
            <p style={{ fontSize: '0.875rem', color: '#4b5563', marginBottom: '1.5rem', lineHeight: 1.5 }}>
              {isExternal
                ? 'External clusters require a running discovery agent on the broker VM before Tantor can perform service control. Bootstrap-only external clusters remain read-only.'
                : 'The orchestrator will restart each broker node sequentially. It automatically waits for the node to rejoin the cluster and for all partitions to become fully replicated before proceeding to the next node.'}
            </p>
            <button 
              className="btn btn-primary-action"
              style={{ width: '100%', justifyContent: 'center' }}
              onClick={triggerRollingRestart}
              disabled={!externalCanRestart || loading || (taskId != null && !status.startsWith('COMPLETED') && !status.startsWith('FAILED'))}
              title={!externalCanRestart ? 'Attach the discovery agent to enable restart control' : 'Start rolling restart'}
            >
              <Play size={16} /> Start Rolling Restart
            </button>
          </div>

          {/* Progress Tracker */}
          {taskId && (
            <div style={{ backgroundColor: '#f9fafb', padding: '1.5rem' }}>
              <h4 style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-primary)', marginBottom: '0.75rem', display: 'flex', alignItems: 'center', gap: '0.5rem', margin: '0 0 0.75rem 0' }}>
                <Activity size={16} color="#3b82f6" /> Live Task Status
              </h4>
              <div style={{ backgroundColor: '#111827', borderRadius: '0.5rem', padding: '1rem', fontFamily: 'monospace', fontSize: '0.875rem', boxShadow: 'inset 0 2px 4px 0 rgba(0, 0, 0, 0.06)', position: 'relative', overflow: 'hidden' }}>
                {!status.startsWith('COMPLETED') && !status.startsWith('FAILED') && (
                  <div style={{ position: 'absolute', top: 0, left: 0, width: '100%', height: '4px' }}>
                    <div style={{ height: '100%', backgroundColor: '#3b82f6', width: '33%', animation: 'pulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite' }}></div>
                  </div>
                )}
                <div style={{ display: 'flex', alignItems: 'flex-start', gap: '0.75rem' }}>
                  {status.startsWith('COMPLETED') ? (
                    <CheckCircle2 size={18} color="#34d399" style={{ flexShrink: 0, marginTop: '2px' }} />
                  ) : status.startsWith('FAILED') ? (
                    <XCircle size={18} color="#f87171" style={{ flexShrink: 0, marginTop: '2px' }} />
                  ) : (
                    <RefreshCw size={18} color="#60a5fa" className="spin" style={{ flexShrink: 0, marginTop: '2px' }} />
                  )}
                  <span style={{ wordBreak: 'break-all', color: status.startsWith('FAILED') ? '#fca5a5' : '#d1d5db' }}>
                    {status}
                  </span>
                </div>
              </div>
            </div>
          )}
        </div>

      </div>
    </div>
  );
}
