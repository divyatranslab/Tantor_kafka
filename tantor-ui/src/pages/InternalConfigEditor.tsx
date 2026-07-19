import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  CheckCircle2, FileText, GitCompare, History, Loader2, Plus, RefreshCw,
  RotateCcw, Save, Server, Trash2, UploadCloud, FileCheck, Download,
} from 'lucide-react';
import { usePermissions } from '../hooks/usePermissions';
import './ConfigEditor.css';
import './ConfigVersioning.css';

interface StaticConfigFile {
  id: string;
  serviceId?: string;
  hostId?: string;
  nodeId?: number;
  label: string;
  description?: string;
  path: string;
  role?: string;
  properties: Record<string, unknown>;
}

interface ServiceTopologyItem {
  hostId: string;
  hostAddress: string;
  role: string;
  nodeId: number;
  systemdUnit: string;
  configPath: string;
}

interface ConfigPayload {
  serviceTopology?: ServiceTopologyItem[];
  staticConfigs: { configFiles?: StaticConfigFile[] };
}

interface ConfigDiff {
  key: string;
  type: 'ADDED' | 'REMOVED' | 'MODIFIED';
  oldValue: string;
  newValue: string;
}

interface PreviewResult {
  valid: boolean;
  errors: string[];
  warnings: string[];
  diff: ConfigDiff[];
}

interface ConfigVersion {
  id: string;
  configVersion: number;
  component: string;
  configFileName: string;
  status: string;
  approvalRequired: boolean;
  createdBy: string;
  approvedBy?: string;
  createdAt: string;
  appliedAt?: string;
  rollbackVersion?: number;
  jobId?: string;
}

const editableVersionStatuses = new Set(['VALIDATED', 'APPROVED', 'FAILED']);

export function InternalConfigEditor() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { canManage } = usePermissions();
  const [payload, setPayload] = useState<ConfigPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [working, setWorking] = useState('');
  const [selectedHostId, setSelectedHostId] = useState('');
  const [selectedFileId, setSelectedFileId] = useState('');
  const [baselineProperties, setBaselineProperties] = useState<Record<string, string>>({});
  const [draftProperties, setDraftProperties] = useState<Record<string, string>>({});
  const [newKey, setNewKey] = useState('');
  const [newValue, setNewValue] = useState('');
  const [restart, setRestart] = useState(true);
  const approvalRequired = false;
  const [preview, setPreview] = useState<PreviewResult | null>(null);
  const [versions, setVersions] = useState<ConfigVersion[]>([]);

  const fetchConfigs = async () => {
    setLoading(true);
    try {
      const response = await fetch(`/api/v1/clusters/${id}/config`);
      if (response.ok) setPayload(await response.json());
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchConfigs(); }, [id]);

  const files = payload?.staticConfigs.configFiles || [];
  const topology = payload?.serviceTopology || [];
  const hosts = useMemo(() => {
    const unique = new Map<string, { id: string; address: string; services: number }>();
    topology.forEach(service => {
      const current = unique.get(service.hostId);
      unique.set(service.hostId, {
        id: service.hostId,
        address: service.hostAddress,
        services: (current?.services || 0) + 1,
      });
    });
    return Array.from(unique.values());
  }, [topology]);

  useEffect(() => {
    if (!selectedHostId && hosts[0]) setSelectedHostId(hosts[0].id);
  }, [hosts, selectedHostId]);

  const hostFiles = files.filter(file => !selectedHostId || file.hostId === selectedHostId);
  const selectedFile = hostFiles.find(file => file.id === selectedFileId) || hostFiles[0];

  useEffect(() => {
    if (selectedFile && selectedFile.id !== selectedFileId) setSelectedFileId(selectedFile.id);
  }, [selectedFile, selectedFileId]);

  useEffect(() => {
    if (!selectedFile) return;
    const properties = Object.fromEntries(
      Object.entries(selectedFile.properties || {}).map(([key, value]) => [key, String(value ?? '')])
    );
    setBaselineProperties(properties);
    setDraftProperties(properties);
    setPreview(null);
  }, [selectedFile?.id, selectedFile?.properties]);

  const fetchVersions = async (serviceId?: string) => {
    if (!serviceId) { setVersions([]); return; }
    const response = await fetch(`/api/v1/clusters/${id}/config/versions?serviceId=${serviceId}`);
    if (response.ok) setVersions(await response.json());
  };

  useEffect(() => { fetchVersions(selectedFile?.serviceId); }, [id, selectedFile?.serviceId]);

  const selectHost = (hostId: string) => {
    setSelectedHostId(hostId);
    setSelectedFileId(files.find(file => file.hostId === hostId)?.id || '');
  };

  const mutateDraft = (updater: (current: Record<string, string>) => Record<string, string>) => {
    if (!canManage) return;
    setDraftProperties(current => updater(current));
    setPreview(null);
  };

  const addProperty = () => {
    if (!canManage) return;
    const key = newKey.trim();
    if (!key || !/^[A-Za-z0-9._-]+$/.test(key)) {
      alert('Enter a valid Kafka property key.');
      return;
    }
    mutateDraft(current => ({ ...current, [key]: newValue }));
    setNewKey('');
    setNewValue('');
  };

  const versionRequest = {
    currentProperties: baselineProperties,
    properties: draftProperties,
    configFileName: selectedFile?.path,
    approvalRequired,
    restart,
  };

  const reviewChange = async () => {
    if (!canManage) return;
    if (!selectedFile?.serviceId) return;
    setWorking('preview');
    try {
      const response = await fetch(`/api/v1/clusters/${id}/config/services/${selectedFile.serviceId}/versions/preview`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(versionRequest),
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) { alert(data.message || 'Unable to validate configuration.'); return; }
      setPreview(data);
    } finally {
      setWorking('');
    }
  };

  const saveVersion = async () => {
    if (!canManage) return;
    if (!selectedFile?.serviceId || !preview?.valid) return;
    setWorking('save');
    try {
      const response = await fetch(`/api/v1/clusters/${id}/config/services/${selectedFile.serviceId}/versions`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(versionRequest),
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) { alert(data.message || 'Unable to save configuration version.'); return; }
      await fetchVersions(selectedFile.serviceId);
      alert(`Version v${data.configVersion} saved. The active production file has not been changed.`);
    } finally {
      setWorking('');
    }
  };

  const versionAction = async (version: ConfigVersion, action: 'apply' | 'rollback') => {
    if (!canManage) return;
    if (action === 'apply') {
      const message = restart
        ? `Apply configuration v${version.configVersion} and perform a controlled rolling service restart? Kafka on the affected node will be restarted and verified by the job.`
        : `Apply configuration v${version.configVersion} without restarting Kafka? Static properties will not become active until a later restart.`;
      if (!window.confirm(message)) return;
    }
    setWorking(`${action}-${version.id}`);
    try {
      const response = await fetch(`/api/v1/clusters/${id}/config/versions/${version.id}/${action}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: action === 'apply' ? JSON.stringify({ restart }) : undefined,
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) { alert(data.message || `${action} failed.`); return; }
      if (action === 'apply' && data.jobId) {
        navigate(`/jobs/${data.jobId}`);
        return;
      }
      await fetchVersions(selectedFile?.serviceId);
      if (action === 'rollback') {
        alert(`Rollback saved as new version v${data.configVersion}. Approve it if required, then apply it.`);
      }
    } finally {
      setWorking('');
    }
  };

  const latestApplyableVersion = useMemo(() => {
    return [...versions]
      .sort((a, b) => b.configVersion - a.configVersion)
      .find(v => editableVersionStatuses.has(v.status));
  }, [versions]);

  if (loading && !payload) {
    return <div className="state-center"><Loader2 className="spin" /> Loading node configurations...</div>;
  }

  const activeVersion = versions.find(version => version.status === 'APPLIED');

  const flowButtonStyle = (disabled: boolean) => ({
    display: 'inline-flex',
    alignItems: 'center',
    gap: '6px',
    padding: '6px 12px',
    border: '1px solid #7C3AED',
    borderRadius: '6px',
    background: '#fff',
    color: '#7C3AED',
    fontWeight: 650,
    fontSize: '12px',
    cursor: disabled ? 'not-allowed' : 'pointer',
    opacity: disabled ? 0.5 : 1,
    transition: 'all 0.2s',
  });

  return (
    <div className="node-config-page versioned-config-page">
      <header className="node-config-header">
        <div><h2>Versioned Configuration Change</h2><p>Review, validate and save an immutable version before anything reaches a node.</p></div>
        <button onClick={fetchConfigs} disabled={loading}><RefreshCw size={14} className={loading ? 'spin' : ''} /> Refresh</button>
      </header>

      <div className="config-flow-strip" style={{
        display: 'flex',
        alignItems: 'center',
        gap: '12px',
        background: 'none',
        border: 'none',
        padding: '0px',
        marginBottom: '1.25rem'
      }}>
        <button 
          onClick={reviewChange} 
          disabled={!!working || !selectedFile?.serviceId} 
          style={flowButtonStyle(!!working || !selectedFile?.serviceId)}
        >
          <GitCompare size={14} /> Diff
        </button>
        <button 
          onClick={reviewChange} 
          disabled={!!working || !selectedFile?.serviceId} 
          style={flowButtonStyle(!!working || !selectedFile?.serviceId)}
        >
          <FileCheck size={14} /> Validate
        </button>
        <button 
          onClick={saveVersion} 
          disabled={!preview?.valid || !!working} 
          style={flowButtonStyle(!preview?.valid || !!working)}
        >
          <Download size={14} /> Save version
        </button>
        <button 
          onClick={() => latestApplyableVersion && versionAction(latestApplyableVersion, 'apply')} 
          disabled={!latestApplyableVersion || !!working} 
          style={flowButtonStyle(!latestApplyableVersion || !!working)}
        >
          <UploadCloud size={14} /> Backup &amp; apply
        </button>
      </div>

      <section className="node-config-section">
        <div className="node-config-section-title">
          <span style={{ border: '1px solid #7C3AED', background: '#fff', color: '#7C3AED' }}>1</span>
          <div><h3>Select node</h3><p>Each VM may contain one or more Kafka services.</p></div>
        </div>
        <div className="node-config-hosts">
          {hosts.map(host => (
            <button 
              key={host.id} 
              className={selectedHostId === host.id ? 'active' : ''} 
              onClick={() => selectHost(host.id)}
              style={selectedHostId === host.id ? {
                background: '#FAF5FF',
                borderColor: '#D8B4FE',
                color: '#6B21A8'
              } : {}}
            >
              <Server size={16} /><span><strong>{host.address}</strong><small>{host.id} - {host.services} service{host.services === 1 ? '' : 's'}</small></span>
            </button>
          ))}
        </div>
      </section>

      <section className="node-config-section">
        <div className="node-config-section-title">
          <span style={{ border: '1px solid #7C3AED', background: '#fff', color: '#7C3AED' }}>2</span>
          <div><h3>Select Configuration File</h3><p>Only files belonging to the selected node are shown.</p></div>
        </div>
        <div className="node-config-files">
          {hostFiles.map(file => (
            <button 
              key={file.id} 
              className={selectedFile?.id === file.id ? 'active' : ''} 
              onClick={() => setSelectedFileId(file.id)}
              style={selectedFile?.id === file.id ? {
                background: '#FAF5FF',
                borderColor: '#D8B4FE',
                color: '#6B21A8'
              } : {}}
            >
              <FileText size={15} /><span><strong>{file.label}</strong><small>{file.path}</small></span>
            </button>
          ))}
        </div>
      </section>

      {selectedFile ? <>
        <section className="node-config-section editor">
          <div className="node-config-editor-head">
            <div><h3>{selectedFile.label}</h3><p>{selectedFile.path}</p></div>
            <div className="config-options">
              {canManage && <label><input type="checkbox" checked={restart} onChange={event => { setRestart(event.target.checked); setPreview(null); }} /> Restart after apply</label>}
            </div>
          </div>

          <div className="node-config-table-wrap">
            <table className="node-config-table">
              <thead><tr><th>Property</th><th>Value</th><th>Action</th></tr></thead>
              <tbody>
                {Object.entries(draftProperties).sort(([a], [b]) => a.localeCompare(b)).map(([key, value]) => {
                  return <tr key={key}>
                    <td><code>{key}</code></td>
                    <td><input value={value} disabled={!canManage} onChange={event => mutateDraft(current => ({ ...current, [key]: event.target.value }))} style={{ border: '1px solid #e2e8f0', borderRadius: '6px' }} /></td>
                    <td>{canManage && <button title={`Remove ${key}`} onClick={() => mutateDraft(current => { const next = { ...current }; delete next[key]; return next; })} style={{ border: '1px solid #fee2e2', color: '#ef4444', background: '#fff' }}><Trash2 size={14} /></button>}</td>
                  </tr>;
                })}
              </tbody>
            </table>
          </div>

          {canManage && (
            <div className="node-config-add" style={{ display: 'grid', gridTemplateColumns: 'minmax(180px, 0.8fr) minmax(220px, 1.2fr) auto', gap: '8px', marginTop: '1rem' }}>
              <input 
                placeholder="property.key" 
                value={newKey} 
                onChange={event => setNewKey(event.target.value)} 
                style={{ border: '1px solid #e2e8f0', borderRadius: '8px', padding: '8px 12px' }}
              />
              <input 
                placeholder="value" 
                value={newValue} 
                onChange={event => setNewValue(event.target.value)} 
                style={{ border: '1px solid #e2e8f0', borderRadius: '8px', padding: '8px 12px' }}
              />
              <button 
                onClick={addProperty}
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: '6px',
                  padding: '8px 16px',
                  borderRadius: '8px',
                  border: '1px solid #3E1363',
                  background: '#fff',
                  color: '#3E1363',
                  fontWeight: 500,
                  fontSize: '13px',
                  cursor: 'pointer'
                }}
              >
                <Plus size={14} /> Add property
              </button>
            </div>
          )}

          <div className="node-config-footer" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: '1.25rem' }}>
            <span style={{ fontSize: '12px', color: '#64748b' }}>
              Target: {selectedFile.hostId}Node: {selectedFile.nodeId}Service: {selectedFile.role}
            </span>
            {canManage && (
              <button 
                onClick={reviewChange} 
                disabled={!!working}
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: '6px',
                  height: '38px',
                  padding: '0 20px',
                  borderRadius: '8px',
                  background: '#3E1363',
                  color: '#fff',
                  border: 'none',
                  fontSize: '13px',
                  fontWeight: 500,
                  cursor: !!working ? 'not-allowed' : 'pointer'
                }}
              >
                {working === 'preview' ? <Loader2 size={14} className="spin" /> : <RefreshCw size={14} />} 
                Review &amp; validate
              </button>
            )}
          </div>
        </section>

        {preview && <section className="node-config-section config-review" style={{ borderColor: '#e2e8f0', marginTop: '1.5rem' }}>
          <div className="node-config-section-title">
            <span style={{ border: '1px solid #7C3AED', background: '#fff', color: '#7C3AED' }}>3</span>
            <div><h3>Old vs New</h3><p>The server validates this exact snapshot again when the version is saved.</p></div>
          </div>
          {preview.errors.length > 0 && <div className="config-messages error">{preview.errors.map(message => <p key={message}>{message}</p>)}</div>}
          {preview.warnings.length > 0 && <div className="config-messages warning">{preview.warnings.map(message => <p key={message}>{message}</p>)}</div>}
          <div className="config-diff-list" style={{ display: 'grid', gap: '12px', marginTop: '12px' }}>
            {preview.diff.map(item => (
              <div key={item.key} style={{
                display: 'flex',
                alignItems: 'center',
                gap: '24px',
                padding: '16px 0px',
                borderBottom: '1px solid #f1f5f9'
              }}>
                <div style={{ display: 'flex', flexDirection: 'column', gap: '4px', width: '150px' }}>
                  <span style={{ fontSize: '11px', fontWeight: 600, color: '#94a3b8' }}>{item.type}</span>
                  <span style={{ fontSize: '13px', fontWeight: 500, color: '#332849' }}>{item.key}</span>
                </div>
                <div style={{ display: 'flex', gap: '16px', flex: 1 }}>
                  <div style={{
                    padding: '10px 14px',
                    border: '1px solid #e2e8f0',
                    borderRadius: '8px',
                    background: '#fff',
                    fontSize: '13px',
                    color: '#332849',
                    fontFamily: 'Consolas, monospace',
                    flex: 1
                  }}>{item.oldValue || 'empty'}</div>
                  <div style={{
                    padding: '10px 14px',
                    border: '1px solid #e2e8f0',
                    borderRadius: '8px',
                    background: '#fff',
                    fontSize: '13px',
                    color: '#332849',
                    fontFamily: 'Consolas, monospace',
                    flex: 1
                  }}>{item.newValue || 'empty'}</div>
                </div>
              </div>
            ))}
          </div>
          <div className="config-review-footer" style={{ marginTop: '20px', borderTop: '1px solid #f1f5f9', paddingTop: '16px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span style={{ fontSize: '12px', color: '#64748b' }}>{preview.valid ? 'Validation passed. Saving creates history only; it does not apply the file.' : 'Fix validation errors before saving.'}</span>
            {canManage && (
              <button 
                onClick={saveVersion} 
                disabled={!preview.valid || !!working}
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: '6px',
                  height: '38px',
                  padding: '0 16px',
                  borderRadius: '8px',
                  background: '#3E1363',
                  color: '#fff',
                  border: 'none',
                  fontSize: '13px',
                  fontWeight: 500,
                  cursor: !preview.valid || !!working ? 'not-allowed' : 'pointer',
                  opacity: !preview.valid || !!working ? 0.5 : 1
                }}
              >
                {working === 'save' ? <Loader2 size={14} className="spin" /> : <Save size={14} />}
                Save as new Version
              </button>
            )}
          </div>
        </section>}
      </> : <div className="empty-state">No managed configuration file is available for this node.</div>}
    </div>
  );
}
