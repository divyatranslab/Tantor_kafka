import { useState, useEffect, useRef } from 'react';
import {
  Package, Upload, CheckCircle, XCircle, ChevronDown, ChevronUp,
  Loader2, HardDrive, X, RefreshCw, Server, DownloadCloud,
  Power, PowerOff, Trash2, AlertTriangle, MoreVertical, FileText
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
  agentStatus?: string;
  available?: boolean;
  ipAddress?: string;
  ipAddresses?: string;
}

interface HostParcel {
  id: string;
  hostId: string;
  hostIp?: string;
  parcelDir?: string;
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

interface ArtifactAuditEvent {
  id: string;
  userName?: string;
  category?: string;
  action: string;
  resourceType?: string;
  artifactId?: string;
  status: string;
  details?: unknown;
  createdAt?: string;
}

const artifactServiceOptions = [
  {
    value: 'KAFKA',
    label: 'Apache Kafka',
    versionPlaceholder: 'e.g. 3.9.2',
    directoryPlaceholder: 'custom/kafka (under configured repository root)',
    fileLabel: 'Kafka parcel binary (.tgz or .tar.gz)',
    fileAccept: '.tgz,.tar.gz',
    helper: 'Kafka binaries appear in the parcel list and can be distributed to hosts.',
  },
  {
    value: 'JMX_EXPORTER',
    label: 'JMX Exporter',
    versionPlaceholder: 'e.g. 0.20.0',
    directoryPlaceholder: 'custom/jmx-exporter (under configured repository root)',
    fileLabel: 'JMX exporter jar (.jar)',
    fileAccept: '.jar',
    helper: 'JMX exporter jars are stored for Kafka monitoring deployments.',
  },
];

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
  const [uploadDirectory, setUploadDirectory] = useState('');
  const [universalDistributionDir, setUniversalDistributionDir] = useState(
    () => window.localStorage.getItem('tantor.universalDistributionDir') || '/srv/apps/tantor/parcels',
  );
  const [distributionDirs, setDistributionDirs] = useState<Record<string, string>>({});
  const [hostDistributionDirs, setHostDistributionDirs] = useState<Record<string, string>>({});
  const [selectedHosts, setSelectedHosts] = useState<Record<string, string[]>>({});
  const [openArtifactMenuId, setOpenArtifactMenuId] = useState<string | null>(null);
  const [auditModalArtifact, setAuditModalArtifact] = useState<ArtifactVersion | null>(null);
  const [artifactAuditEvents, setArtifactAuditEvents] = useState<ArtifactAuditEvent[]>([]);
  const [artifactAuditLoading, setArtifactAuditLoading] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);
  const selectedServiceOption = artifactServiceOptions.find(option => option.value === serviceType) || artifactServiceOptions[0];
  const fileMatchesServiceType = !file || selectedServiceOption.fileAccept
    .split(',')
    .some(extension => file.name.toLowerCase().endsWith(extension.trim().toLowerCase()));

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

  useEffect(() => {
    window.localStorage.setItem('tantor.universalDistributionDir', universalDistributionDir);
  }, [universalDistributionDir]);

  const handleUploadSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file || !versionInput || !fileMatchesServiceType) return;
    setUploading(true);
    setUploadMsg(null);

    const form = new FormData();
    form.append('file', file);
    form.append('serviceType', serviceType);
    form.append('version', versionInput);
    if (uploadDirectory.trim()) form.append('storageDirectory', uploadDirectory.trim());
    form.append('overwrite', 'false');

    try {
      const res = await fetch('/api/v1/artifacts', { method: 'POST', body: form });
      if (res.ok) {
        setUploadMsg({ text: `Uploaded ${file.name} (${(file.size / 1024 / 1024).toFixed(1)} MB)`, ok: true });
        setShowUploadModal(false);
        setFile(null);
        setVersionInput('');
        setUploadDirectory('');
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

  const isHostOnline = (host: Host) => {
    const status = (host.status || '').toUpperCase();
    const agentStatus = (host.agentStatus || '').toUpperCase();
    return agentStatus === 'ONLINE' || status === 'ONLINE' || status === 'AVAILABLE';
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
          checksum: ver.sha256,
          serviceType: ver.service_type,
          version: ver.version,
          fileName: ver.filename,
          parcelDir: hostDistributionDirs[`${ver.id}:${host.id}`] || distributionDirs[ver.id] || universalDistributionDir,
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

  const distributeAll = async (ver: ArtifactVersion) => {
    const eligible = hosts.filter(host =>
      isHostOnline(host)
      && (() => {
        const state = getHostParcel(ver.id, host.id);
        return !state || ['FAILED', 'REMOVED'].includes(state.status);
      })()
    );
    if (eligible.length === 0) {
      setUploadMsg({ text: 'No online hosts are waiting for this parcel.', ok: false });
      return;
    }
    const key = `distribute-all-${ver.id}`;
    setActingKey(key);
    try {
      const res = await fetch(`/api/v1/ui/parcels/${ver.id}/distribute`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          hostIds: eligible.map(host => host.id),
          checksum: ver.sha256,
          serviceType: ver.service_type,
          version: ver.version,
          fileName: ver.filename,
          parcelDir: distributionDirs[ver.id] || universalDistributionDir,
          parcelDirs: Object.fromEntries(eligible.map(host => [
            host.id,
            hostDistributionDirs[`${ver.id}:${host.id}`] || distributionDirs[ver.id] || universalDistributionDir,
          ])),
        }),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error(body.message || body.error || 'Distribute all failed.');
      }
      setUploadMsg({ text: `Distribution scheduled on ${eligible.length} hosts.`, ok: true });
      await fetchParcelState();
    } catch (e: any) {
      setUploadMsg({ text: e.message || 'Distribute all failed.', ok: false });
    } finally {
      setActingKey(null);
    }
  };

  const distributeSelected = async (ver: ArtifactVersion) => {
    const ids = selectedHosts[ver.id] || [];
    const targets = hosts.filter(host => ids.includes(host.id));
    if (!targets.length) {
      setUploadMsg({ text: 'Select at least one host.', ok: false });
      return;
    }
    const key = `distribute-selected-${ver.id}`;
    setActingKey(key);
    try {
      const res = await fetch(`/api/v1/ui/parcels/${ver.id}/distribute`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          hostIds: targets.map(host => host.id),
          checksum: ver.sha256,
          serviceType: ver.service_type,
          version: ver.version,
          fileName: ver.filename,
          parcelDir: distributionDirs[ver.id] || universalDistributionDir,
          parcelDirs: Object.fromEntries(targets.map(host => [
            host.id,
            hostDistributionDirs[`${ver.id}:${host.id}`] || distributionDirs[ver.id] || universalDistributionDir,
          ])),
        }),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error(body.message || body.error || 'Selected-host distribution failed.');
      }
      setUploadMsg({ text: `Distribution scheduled on ${targets.length} selected host${targets.length === 1 ? '' : 's'}.`, ok: true });
      setSelectedHosts(current => ({ ...current, [ver.id]: [] }));
      await fetchParcelState();
    } catch (e: any) {
      setUploadMsg({ text: e.message || 'Selected-host distribution failed.', ok: false });
    } finally {
      setActingKey(null);
    }
  };

  const toggleHostSelection = (artifactId: string, hostId: string) => {
    setSelectedHosts(current => {
      const selected = current[artifactId] || [];
      return { ...current, [artifactId]: selected.includes(hostId)
        ? selected.filter(id => id !== hostId)
        : [...selected, hostId] };
    });
  };

  const deleteArtifactBinary = async (ver: ArtifactVersion) => {
    setOpenArtifactMenuId(null);
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

  const openArtifactLogs = async (ver: ArtifactVersion) => {
    setOpenArtifactMenuId(null);
    setAuditModalArtifact(ver);
    setArtifactAuditEvents([]);
    setArtifactAuditLoading(true);
    try {
      const res = await fetch(`/api/v1/artifacts/audit/${ver.id}`);
      if (!res.ok) throw new Error('Unable to load artifact logs.');
      const body = await res.json();
      setArtifactAuditEvents(body.events || []);
    } catch (e: any) {
      setUploadMsg({ text: e.message || 'Unable to load artifact logs.', ok: false });
    } finally {
      setArtifactAuditLoading(false);
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
    const hostOnline = isHostOnline(host);
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
    <div className="artifacts-page animate-fade-in" onClick={() => setOpenArtifactMenuId(null)}>
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

      <section className="universal-distribution-directory">
        <div><strong>Universal distribution directory</strong><span>Default destination for every Kafka binary on every host.</span></div>
        <input
          className="form-control"
          value={universalDistributionDir}
          onChange={event => setUniversalDistributionDir(event.target.value)}
          placeholder="/srv/apps/tantor/parcels"
        />
      </section>

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
                        {ver.filename && <span className="filename">{ver.filename}</span>}
                      </div>
                    </div>
                    <span className="chevron">{isOpen ? <ChevronUp size={16} /> : <ChevronDown size={16} />}</span>
                  </button>
                  <div className="version-card-tools">
                    <div className="artifact-menu-anchor" onClick={event => event.stopPropagation()}>
                      <button
                        className="artifact-menu-button"
                        onClick={() => setOpenArtifactMenuId(openArtifactMenuId === ver.id ? null : ver.id)}
                        title="Artifact actions"
                      >
                        <MoreVertical size={15} />
                      </button>
                      {openArtifactMenuId === ver.id && (
                        <div className="artifact-action-menu">
                          <button onClick={() => openArtifactLogs(ver)}>
                            <FileText size={14} />
                            View Log
                          </button>
                        </div>
                      )}
                    </div>
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
                    <div className="parcel-distribution-controls">
                      <label>
                        Artifact directory override (optional)
                        <input
                          className="form-control"
                          value={distributionDirs[ver.id] || ''}
                          onChange={event => setDistributionDirs(current => ({ ...current, [ver.id]: event.target.value }))}
                          placeholder={universalDistributionDir}
                        />
                      </label>
                      <button className="btn btn-primary-action" onClick={() => distributeAll(ver)} disabled={actingKey !== null}>
                        {actingKey === `distribute-all-${ver.id}` ? <Loader2 size={14} className="spin" /> : <DownloadCloud size={14} />}
                        Distribute all
                      </button>
                      <button className="btn" onClick={() => distributeSelected(ver)} disabled={actingKey !== null || !(selectedHosts[ver.id]?.length)}>
                        {actingKey === `distribute-selected-${ver.id}` ? <Loader2 size={14} className="spin" /> : <DownloadCloud size={14} />}
                        Distribute selected ({selectedHosts[ver.id]?.length || 0})
                      </button>
                    </div>
                    {hosts.length === 0 ? (
                      <div className="parcel-empty-hosts">
                        <Server size={18} />
                        No hosts are registered yet.
                      </div>
                    ) : (
                      <div className="parcel-host-table">
                        <div className="parcel-host-row header">
                          <span>
                            <input
                              type="checkbox"
                              checked={hosts.length > 0 && (selectedHosts[ver.id]?.length || 0) === hosts.length}
                              onChange={event => setSelectedHosts(current => ({
                                ...current,
                                [ver.id]: event.target.checked ? hosts.map(host => host.id) : [],
                              }))}
                            /> Host
                          </span>
                          <span>State</span>
                          <span>Destination path / IP</span>
                          <span>Actions</span>
                        </div>
                        {hosts.map(host => {
                          const state = getHostParcel(ver.id, host.id);
                          const status = state?.status || 'AVAILABLE';
                          return (
                            <div key={host.id} className="parcel-host-row">
                              <div className="parcel-host">
                                <input
                                  type="checkbox"
                                  checked={(selectedHosts[ver.id] || []).includes(host.id)}
                                  onChange={() => toggleHostSelection(ver.id, host.id)}
                                />
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
                              <div className="parcel-host-destination">
                                <input
                                  className="form-control"
                                  value={hostDistributionDirs[`${ver.id}:${host.id}`] || ''}
                                  onChange={event => setHostDistributionDirs(current => ({
                                    ...current,
                                    [`${ver.id}:${host.id}`]: event.target.value,
                                  }))}
                                  placeholder={distributionDirs[ver.id] || universalDistributionDir}
                                />
                                <small>{state?.hostIp || '-'}</small>
                              </div>
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
                <select
                  className="form-control"
                  value={serviceType}
                  onChange={e => {
                    setServiceType(e.target.value);
                    setFile(null);
                    if (fileRef.current) fileRef.current.value = '';
                  }}
                >
                  {artifactServiceOptions.map(option => (
                    <option key={option.value} value={option.value}>{option.label}</option>
                  ))}
                </select>
                <small className="form-hint">{selectedServiceOption.helper}</small>
              </div>

              <div className="form-group">
                <label>Version number</label>
                <input
                  type="text"
                  className="form-control"
                  value={versionInput}
                  onChange={e => setVersionInput(e.target.value)}
                  placeholder={selectedServiceOption.versionPlaceholder}
                  required
                />
              </div>

              <div className="form-group">
                <label>Repository subdirectory (optional)</label>
                <input
                  type="text"
                  className="form-control"
                  value={uploadDirectory}
                  onChange={e => setUploadDirectory(e.target.value)}
                  placeholder={selectedServiceOption.directoryPlaceholder}
                />
              </div>

              <div className="form-group">
                <label>{selectedServiceOption.fileLabel}</label>
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
                    accept={selectedServiceOption.fileAccept}
                  />
                </div>
                {!fileMatchesServiceType && (
                  <small className="form-error">Selected file does not match {selectedServiceOption.label}.</small>
                )}
              </div>

              <div className="modal-footer">
                <button type="button" className="btn" onClick={() => setShowUploadModal(false)}>Cancel</button>
                <button type="submit" className="btn btn-primary-action" disabled={uploading || !file || !versionInput || !fileMatchesServiceType}>
                  {uploading ? 'Uploading...' : 'Upload'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {auditModalArtifact && (
        <div className="modal-overlay" onClick={() => setAuditModalArtifact(null)}>
          <div className="modal artifact-log-modal" onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <h2>Artifact audit log</h2>
              <button className="modal-close" onClick={() => setAuditModalArtifact(null)}>
                <X size={14} />
              </button>
            </div>
            <p className="modal-subtitle">Kafka {auditModalArtifact.version} - {auditModalArtifact.filename}</p>

            {artifactAuditLoading ? (
              <div className="artifact-log-empty"><Loader2 size={18} className="spin" /> Loading audit log...</div>
            ) : artifactAuditEvents.length === 0 ? (
              <div className="artifact-log-empty">No audit log entries found for this artifact.</div>
            ) : (
              <div className="artifact-log-list">
                {artifactAuditEvents.map(event => {
                  const created = event.createdAt ? new Date(event.createdAt) : null;
                  return (
                    <article key={event.id} className="artifact-log-row">
                      <div>
                        <strong>{String(event.action || '').replaceAll('_', ' ')}</strong>
                        <span>{event.category || 'ARTIFACT'} - {event.status}</span>
                      </div>
                      <time>{created && !Number.isNaN(created.getTime()) ? created.toLocaleString() : '-'}</time>
                    </article>
                  );
                })}
              </div>
            )}
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

