import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  CheckCircle2, FileText, GitCompare, History, Loader2, Plus, RefreshCw,
  RotateCcw, Save, Server, ShieldCheck, Trash2, UploadCloud,
} from 'lucide-react';
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

const generatedKeys = new Set([
  'process.roles', 'node.id', 'broker.id', 'listeners', 'advertised.listeners',
  'controller.quorum.voters', 'controller.quorum.bootstrap.servers',
  'zookeeper.connect', 'dataDir', 'clientPort', 'servers',
]);

const editableVersionStatuses = new Set(['VALIDATED', 'APPROVED', 'FAILED']);

export function ConfigEditor() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
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
  const [approvalRequired, setApprovalRequired] = useState(true);
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
  }, [selectedFile?.id]);

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
    setDraftProperties(current => updater(current));
    setPreview(null);
  };

  const addProperty = () => {
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

  const versionAction = async (version: ConfigVersion, action: 'approve' | 'apply' | 'rollback') => {
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

  if (loading && !payload) {
    return <div className="state-center"><Loader2 className="spin" /> Loading node configurations...</div>;
  }

  const activeVersion = versions.find(version => version.status === 'APPLIED');

  return (
    <div className="node-config-page versioned-config-page">
      <header className="node-config-header">
        <div><h2>Versioned Configuration Change</h2><p>Review, validate and save an immutable version before anything reaches a node.</p></div>
        <button onClick={fetchConfigs} disabled={loading}><RefreshCw size={14} className={loading ? 'spin' : ''} /> Refresh</button>
      </header>

      <div className="config-flow-strip">
        <span><GitCompare size={14} /> Diff</span><b>→</b><span><CheckCircle2 size={14} /> Validate</span><b>→</b>
        <span><Save size={14} /> Save version</span><b>→</b><span><ShieldCheck size={14} /> Approve</span><b>→</b>
        <span><UploadCloud size={14} /> Backup &amp; apply</span>
      </div>

      <section className="node-config-section">
        <div className="node-config-section-title"><span>1</span><div><h3>Select node</h3><p>Each VM may contain one or more Kafka services.</p></div></div>
        <div className="node-config-hosts">
          {hosts.map(host => (
            <button key={host.id} className={selectedHostId === host.id ? 'active' : ''} onClick={() => selectHost(host.id)}>
              <Server size={16} /><span><strong>{host.address}</strong><small>{host.id} · {host.services} service{host.services === 1 ? '' : 's'}</small></span>
            </button>
          ))}
        </div>
      </section>

      <section className="node-config-section">
        <div className="node-config-section-title"><span>2</span><div><h3>Select configuration file</h3><p>Only files belonging to the selected node are shown.</p></div></div>
        <div className="node-config-files">
          {hostFiles.map(file => (
            <button key={file.id} className={selectedFile?.id === file.id ? 'active' : ''} onClick={() => setSelectedFileId(file.id)}>
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
              <label><input type="checkbox" checked={approvalRequired} onChange={event => setApprovalRequired(event.target.checked)} /> Require approval</label>
              <label><input type="checkbox" checked={restart} onChange={event => { setRestart(event.target.checked); setPreview(null); }} /> Restart after apply</label>
            </div>
          </div>

          <div className="node-config-table-wrap">
            <table className="node-config-table">
              <thead><tr><th>Property</th><th>Value</th><th>Action</th></tr></thead>
              <tbody>
                {Object.entries(draftProperties).sort(([a], [b]) => a.localeCompare(b)).map(([key, value]) => {
                  const generated = generatedKeys.has(key);
                  return <tr key={key}>
                    <td><code>{key}</code>{generated && <small>Managed by Tantor</small>}</td>
                    <td><input value={value} disabled={generated} onChange={event => mutateDraft(current => ({ ...current, [key]: event.target.value }))} /></td>
                    <td><button title={`Remove ${key}`} disabled={generated} onClick={() => mutateDraft(current => { const next = { ...current }; delete next[key]; return next; })}><Trash2 size={14} /></button></td>
                  </tr>;
                })}
              </tbody>
            </table>
          </div>

          <div className="node-config-add">
            <input placeholder="property.key" value={newKey} onChange={event => setNewKey(event.target.value)} />
            <input placeholder="value" value={newValue} onChange={event => setNewValue(event.target.value)} />
            <button onClick={addProperty}><Plus size={14} /> Add property</button>
          </div>

          <div className="node-config-footer">
            <span>Target: {selectedFile.hostId} · node {selectedFile.nodeId} · {selectedFile.role}</span>
            <button onClick={reviewChange} disabled={!!working}>{working === 'preview' ? <Loader2 size={14} className="spin" /> : <GitCompare size={14} />} Review &amp; validate</button>
          </div>
        </section>

        {preview && <section className="node-config-section config-review">
          <div className="node-config-section-title"><span>3</span><div><h3>Old vs new</h3><p>The server validates this exact snapshot again when the version is saved.</p></div></div>
          {preview.errors.length > 0 && <div className="config-messages error">{preview.errors.map(message => <p key={message}>{message}</p>)}</div>}
          {preview.warnings.length > 0 && <div className="config-messages warning">{preview.warnings.map(message => <p key={message}>{message}</p>)}</div>}
          <div className="config-diff-list">
            {preview.diff.map(item => <div className={`config-diff-row ${item.type.toLowerCase()}`} key={item.key}>
              <div><span>{item.type}</span><code>{item.key}</code></div>
              <pre>{item.oldValue || '∅'}</pre><b>→</b><pre>{item.newValue || '∅'}</pre>
            </div>)}
          </div>
          <div className="config-review-footer">
            <span>{preview.valid ? 'Validation passed. Saving creates history only; it does not apply the file.' : 'Fix validation errors before saving.'}</span>
            <button onClick={saveVersion} disabled={!preview.valid || !!working}>{working === 'save' ? <Loader2 size={14} className="spin" /> : <Save size={14} />} Save as new version</button>
          </div>
        </section>}

        <section className="node-config-section config-history">
          <div className="node-config-section-title"><span>4</span><div><h3>Version history</h3><p>Backups, approvals, applies and rollback lineage remain auditable.</p></div></div>
          {versions.length === 0 ? <div className="empty-state"><History size={18} /> No saved versions yet.</div> :
            <div className="version-list">{versions.map(version => <article key={version.id} className={version.id === activeVersion?.id ? 'active-version' : ''}>
              <div className="version-main">
                <strong>v{version.configVersion}</strong><span className={`version-status ${version.status.toLowerCase()}`}>{version.status.replaceAll('_', ' ')}</span>
                {version.rollbackVersion && <span className="rollback-tag">restores v{version.rollbackVersion}</span>}
                <small>created by {version.createdBy || 'unknown'} · {new Date(version.createdAt).toLocaleString()}</small>
                {version.approvedBy && <small>approved by {version.approvedBy}</small>}
              </div>
              <div className="version-actions">
                {version.status === 'PENDING_APPROVAL' && <button onClick={() => versionAction(version, 'approve')} disabled={!!working}><ShieldCheck size={13} /> Approve</button>}
                {editableVersionStatuses.has(version.status) && (!version.approvalRequired || !!version.approvedBy) && <button className="primary" onClick={() => versionAction(version, 'apply')} disabled={!!working}><UploadCloud size={13} /> Apply</button>}
                {version.jobId && <button onClick={() => navigate(`/jobs/${version.jobId}`)}>View job</button>}
                {version.status !== 'APPLIED' && ['SUPERSEDED'].includes(version.status) && <button onClick={() => versionAction(version, 'rollback')} disabled={!!working}><RotateCcw size={13} /> Restore</button>}
              </div>
            </article>)}</div>}
        </section>
      </> : <div className="empty-state">No managed configuration file is available for this node.</div>}
    </div>
  );
}
