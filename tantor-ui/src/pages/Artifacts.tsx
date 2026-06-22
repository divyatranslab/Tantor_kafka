import { useState, useEffect, useRef } from 'react';
import {
  Package, Upload, CheckCircle, XCircle, ChevronDown, ChevronUp,
  Loader2, HardDrive, X, RefreshCw, Server, DownloadCloud,
  Power, PowerOff, Trash2, AlertTriangle
} from 'lucide-react';
import './Artifacts.css';

interface ArtifactVersion {
  id: string;
  service_type: string;
  version: string;
  available: boolean;
  release_date: string;
  size_mb: string;
  filename: string;
  sha256: string;
  download_url: string;
}

interface Host {
  id: string;
  hostname: string;
  status: string;
  ipAddress?: string;
  ipAddresses?: string;
}

interface HostParcel {
  id: string;
  hostId: string;
  artifactId: string;
  serviceType: string;
  version: string;
  status: string;
  active: boolean;
  lastTaskId?: string;
  errorMsg?: string;
  updatedAt?: string;
}

type ParcelAction = 'distribute' | 'activate' | 'deactivate' | 'remove';

export function Artifacts() {
  const [versions, setVersions] = useState<ArtifactVersion[]>([]);
  const [hosts, setHosts] = useState<Host[]>([]);
  const [hostParcels, setHostParcels] = useState<HostParcel[]>([]);
  const [loading, setLoading] = useState(true);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [actingKey, setActingKey] = useState<string | null>(null);
  const [uploadMsg, setUploadMsg] = useState<{ text: string; ok: boolean } | null>(null);
  const [showUploadModal, setShowUploadModal] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [serviceType, setServiceType] = useState('KAFKA');
  const [versionInput, setVersionInput] = useState('');
  const fileRef = useRef<HTMLInputElement>(null);
  const artifactRepoBaseUrl = import.meta.env.VITE_ARTIFACT_REPO_URL || `http://${window.location.hostname || 'localhost'}:8081`;

  const fetchVersions = async () => {
    const res = await fetch('/api/v1/artifacts?serviceType=KAFKA&status=AVAILABLE');
    if (!res.ok) return;
    const data = await res.json();
    setVersions((data.content || []).map((a: any) => ({
      id: a.id,
      service_type: a.serviceType || 'KAFKA',
      version: a.version,
      available: a.status === 'AVAILABLE',
      release_date: a.createdAt ? new Date(a.createdAt).toLocaleDateString() : '',
      size_mb: (a.fileSizeBytes / 1024 / 1024).toFixed(1),
      filename: a.fileName,
      sha256: a.sha256,
      download_url: a.downloadUrl || `/api/v1/artifacts/${a.id}/download`,
    })));
  };

  const fetchHosts = async () => {
    const res = await fetch('/api/v1/ui/hosts');
    if (!res.ok) return;
    setHosts(await res.json());
  };

  const fetchParcelState = async () => {
    const res = await fetch('/api/v1/ui/parcels');
    if (!res.ok) return;
    setHostParcels(await res.json());
  };

  const refreshAll = async () => {
    setLoading(true);
    try {
      await Promise.all([fetchVersions(), fetchHosts(), fetchParcelState()]);
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    refreshAll();
    const timer = window.setInterval(fetchParcelState, 5000);
    return () => window.clearInterval(timer);
  }, []);

  const handleUploadSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file || !versionInput) return;
    setUploading(true);
    setUploadMsg(null);

    const form = new FormData();
    form.append('file', file);
    form.append('serviceType', serviceType);
    form.append('version', versionInput);
    form.append('overwrite', 'true');

    try {
      const res = await fetch('/api/v1/artifacts', { method: 'POST', body: form });
      if (res.ok) {
        setUploadMsg({ text: `Uploaded ${file.name} (${(file.size / 1024 / 1024).toFixed(1)} MB)`, ok: true });
        setShowUploadModal(false);
        setFile(null);
        setVersionInput('');
        await refreshAll();
      } else {
        const err = await res.json().catch(() => ({}));
        setUploadMsg({ text: err.detail || err.message || 'Upload failed.', ok: false });
      }
    } catch {
      setUploadMsg({ text: 'Upload failed due to a network error.', ok: false });
    } finally {
      setUploading(false);
      if (fileRef.current) fileRef.current.value = '';
    }
  };

  const getHostParcel = (artifactId: string, hostId: string) =>
    hostParcels.find(p => p.artifactId === artifactId && p.hostId === hostId);

  const artifactUrlForAgent = (ver: ArtifactVersion) => {
    if (ver.download_url?.startsWith('http')) return ver.download_url;
    const path = ver.download_url || `/api/v1/artifacts/${ver.id}/download`;
    return `${artifactRepoBaseUrl}${path.startsWith('/') ? path : `/${path}`}`;
  };

  const runParcelAction = async (action: ParcelAction, ver: ArtifactVersion, host: Host) => {
    const key = `${action}-${ver.id}-${host.id}`;
    setActingKey(key);
    setUploadMsg(null);
    try {
      const res = await fetch(`/api/v1/ui/parcels/${ver.id}/${action}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          hostIds: [host.id],
          artifactUrl: artifactUrlForAgent(ver),
          checksum: ver.sha256,
          serviceType: ver.service_type,
          version: ver.version,
          fileName: ver.filename,
        }),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || err.error || `${action} failed`);
      }
      await fetchParcelState();
      setUploadMsg({ text: `${actionLabel(action)} scheduled on ${host.hostname || host.id}`, ok: true });
    } catch (e: any) {
      setUploadMsg({ text: e.message || `${actionLabel(action)} failed`, ok: false });
    } finally {
      setActingKey(null);
    }
  };

  const deleteArtifactBinary = async (ver: ArtifactVersion) => {
    const inUse = hostParcels.some(p => p.artifactId === ver.id && p.status !== 'REMOVED');
    if (inUse) {
      setUploadMsg({
        text: `Remove Kafka ${ver.version} from all hosts before deleting the binary.`,
        ok: false,
      });
      return;
    }

    const confirmed = window.confirm(
      `Delete Kafka ${ver.version} binary "${ver.filename}" from the artifact repository?`
    );
    if (!confirmed) return;

    const key = `delete-artifact-${ver.id}`;
    setActingKey(key);
    setUploadMsg(null);
    try {
      const res = await fetch(`/api/v1/artifacts/${ver.id}`, {
        method: 'DELETE',
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.detail || err.message || err.error || 'Delete failed.');
      }
      if (expanded === ver.id) setExpanded(null);
      setUploadMsg({ text: `Deleted Kafka ${ver.version} binary.`, ok: true });
      await refreshAll();
    } catch (e: any) {
      setUploadMsg({ text: e.message || 'Delete failed.', ok: false });
    } finally {
      setActingKey(null);
    }
  };

  const actionButton = (action: ParcelAction, ver: ArtifactVersion, host: Host, disabled = false) => {
    const key = `${action}-${ver.id}-${host.id}`;
    const Icon = actionIcon(action);
    return (
      <button
        key={action}
        className={`parcel-action ${action}`}
        disabled={disabled || actingKey !== null}
        onClick={() => runParcelAction(action, ver, host)}
        title={actionLabel(action)}
      >
        {actingKey === key ? <Loader2 size={13} className="spin" /> : <Icon size={13} />}
        {actionLabel(action)}
      </button>
    );
  };

  const renderActions = (ver: ArtifactVersion, host: Host, state?: HostParcel) => {
    const hostOnline = host.status === 'ONLINE' || host.status === 'online';
    if (!hostOnline) {
      return (
        <span className="parcel-blocked">
          <AlertTriangle size={13} />
          Host offline
        </span>
      );
    }
    if (!ver.available) {
      return (
        <span className="parcel-blocked">
          <XCircle size={13} />
          Artifact unavailable
        </span>
      );
    }
    const status = state?.status || 'AVAILABLE';
    if (['DISTRIBUTING', 'ACTIVATING', 'DEACTIVATING', 'REMOVING'].includes(status)) {
      return <span className="parcel-progress"><Loader2 size={13} className="spin" /> {status}</span>;
    }
    if (!state || status === 'REMOVED') {
      return actionButton('distribute', ver, host);
    }
    if (status === 'FAILED') {
      return (
        <>
          {actionButton('distribute', ver, host)}
          {actionButton('remove', ver, host)}
        </>
      );
    }
    if (status === 'ACTIVE') {
      return actionButton('deactivate', ver, host);
    }
    return (
      <>
        {actionButton('activate', ver, host)}
        {actionButton('remove', ver, host)}
      </>
    );
  };

  return (
    <div className="artifacts-page animate-fade-in">
      <header className="page-header flex-between">
        <div>
          <h1>Parcels</h1>
          <p>Distribute, activate, deactivate, and remove Kafka parcels on managed hosts</p>
        </div>
        <div className="header-actions">
          {uploadMsg && (
            <span className={`upload-msg ${uploadMsg.ok ? 'ok' : 'err'}`}>
              {uploadMsg.text}
            </span>
          )}
          <button className="btn" onClick={refreshAll} disabled={loading || actingKey !== null}>
            <RefreshCw size={14} className={loading ? 'spin' : ''} />
            Sync
          </button>
          <button className="btn btn-primary-action" onClick={() => setShowUploadModal(true)} disabled={uploading}>
            {uploading ? <Loader2 size={14} className="spin" /> : <Upload size={14} />}
            Upload binary
          </button>
        </div>
      </header>

      {loading ? (
        <div className="state-center">
          <Loader2 size={28} className="spin" />
          <p>Loading parcels...</p>
        </div>
      ) : versions.length === 0 ? (
        <div className="state-center">
          <Package size={36} />
          <p>No Kafka parcels found.</p>
          <p className="sub">Upload a .tgz binary to get started.</p>
        </div>
      ) : (
        <div className="versions-list">
          {versions.map(ver => {
            const isOpen = expanded === ver.id;
            const distributed = hostParcels.filter(p => p.artifactId === ver.id && p.status !== 'REMOVED').length;
            const active = hostParcels.filter(p => p.artifactId === ver.id && p.active).length;
            const deleteKey = `delete-artifact-${ver.id}`;
            const canDeleteBinary = distributed === 0 && active === 0;
            return (
              <div key={ver.id} className="version-card">
                <div className="version-card-top">
                  <button className="version-card-header" onClick={() => setExpanded(isOpen ? null : ver.id)}>
                    <div className="version-info">
                      <div className="version-title-row">
                        <span className="version-name">Kafka {ver.version}</span>
                        {ver.available ? (
                          <span className="status-badge available"><CheckCircle size={11} /> Available</span>
                        ) : (
                          <span className="status-badge unavailable"><XCircle size={11} /> Not downloaded</span>
                        )}
                        {active > 0 && <span className="status-badge active"><Power size={11} /> Active on {active}</span>}
                      </div>
                      <div className="version-meta">
                        {ver.release_date && <span>Uploaded {ver.release_date}</span>}
                        <span>{ver.size_mb} MB</span>
                        <span>{distributed} host state{distributed === 1 ? '' : 's'}</span>
                        {ver.filename && <span className="filename">{ver.filename}</span>}
                      </div>
                    </div>
                    <span className="chevron">{isOpen ? <ChevronUp size={16} /> : <ChevronDown size={16} />}</span>
                  </button>
                  <div className="version-card-tools">
                    <button
                      className="artifact-delete-button"
                      disabled={!canDeleteBinary || actingKey !== null}
                      onClick={() => deleteArtifactBinary(ver)}
                      title={canDeleteBinary ? 'Delete uploaded binary' : 'Remove from all hosts before deleting binary'}
                    >
                      {actingKey === deleteKey ? <Loader2 size={15} className="spin" /> : <Trash2 size={15} />}
                    </button>
                  </div>
                </div>

                {isOpen && (
                  <div className="version-card-body">
                    {hosts.length === 0 ? (
                      <div className="parcel-empty-hosts">
                        <Server size={18} />
                        No hosts are registered yet.
                      </div>
                    ) : (
                      <div className="parcel-host-table">
                        <div className="parcel-host-row header">
                          <span>Host</span>
                          <span>State</span>
                          <span>Last update</span>
                          <span>Actions</span>
                        </div>
                        {hosts.map(host => {
                          const state = getHostParcel(ver.id, host.id);
                          const status = state?.status || 'AVAILABLE';
                          return (
                            <div key={host.id} className="parcel-host-row">
                              <div className="parcel-host">
                                <Server size={14} />
                                <div>
                                  <strong>{host.hostname || host.id}</strong>
                                  <span>{host.id}</span>
                                </div>
                              </div>
                              <div>
                                <span className={`parcel-status ${status.toLowerCase()}`}>
                                  {state?.active ? <Power size={11} /> : status === 'FAILED' ? <AlertTriangle size={11} /> : <Package size={11} />}
                                  {status}
                                </span>
                                {state?.errorMsg && <p className="parcel-error">{state.errorMsg}</p>}
                              </div>
                              <span className="parcel-updated">
                                {state?.updatedAt ? new Date(state.updatedAt).toLocaleTimeString() : '-'}
                              </span>
                              <div className="parcel-actions">
                                {renderActions(ver, host, state)}
                              </div>
                            </div>
                          );
                        })}
                      </div>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}

      {showUploadModal && (
        <div className="modal-overlay" onClick={() => setShowUploadModal(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <h2>Upload parcel binary</h2>
              <button className="modal-close" onClick={() => setShowUploadModal(false)}>
                <X size={14} />
              </button>
            </div>
            <p className="modal-subtitle">Upload a Kafka <code>.tgz</code> binary or a JMX <code>.jar</code> to the internal artifact repository.</p>

            <form onSubmit={handleUploadSubmit}>
              <div className="form-group">
                <label>Service type</label>
                <select className="form-control" value={serviceType} onChange={e => setServiceType(e.target.value)} disabled>
                  <option value="KAFKA">Apache Kafka</option>
                </select>
              </div>

              <div className="form-group">
                <label>Version number</label>
                <input
                  type="text"
                  className="form-control"
                  value={versionInput}
                  onChange={e => setVersionInput(e.target.value)}
                  placeholder="e.g. 3.7.0"
                  required
                />
              </div>

              <div className="form-group">
                <label>Binary file (.tgz or .jar)</label>
                <div className="upload-dropzone" onClick={() => fileRef.current?.click()}>
                  <HardDrive size={28} style={{ color: 'var(--accent-primary)' }} />
                  {file ? (
                    <>
                      <span className="dropzone-filename">{file.name}</span>
                      <span className="dropzone-size">{(file.size / 1024 / 1024).toFixed(2)} MB</span>
                    </>
                  ) : (
                    <span className="dropzone-hint">Click to select a binary file</span>
                  )}
                  <input
                    type="file"
                    ref={fileRef}
                    style={{ display: 'none' }}
                    onChange={e => setFile(e.target.files?.[0] ?? null)}
                    accept=".tgz,.tar.gz,.jar"
                  />
                </div>
              </div>

              <div className="modal-footer">
                <button type="button" className="btn" onClick={() => setShowUploadModal(false)}>Cancel</button>
                <button type="submit" className="btn btn-primary-action" disabled={uploading || !file || !versionInput}>
                  {uploading ? 'Uploading...' : 'Upload'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}

function actionLabel(action: ParcelAction): string {
  return {
    distribute: 'Distribute',
    activate: 'Activate',
    deactivate: 'Deactivate',
    remove: 'Remove',
  }[action];
}

function actionIcon(action: ParcelAction) {
  return {
    distribute: DownloadCloud,
    activate: Power,
    deactivate: PowerOff,
    remove: Trash2,
  }[action];
}

