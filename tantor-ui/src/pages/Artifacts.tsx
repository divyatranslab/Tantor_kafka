import { useState, useEffect, useRef } from 'react';
import {
  Package, Upload, CheckCircle, XCircle,
  ChevronDown, ChevronUp, Shield, Sparkles,
  ArrowUpCircle, Loader2, HardDrive, X
} from 'lucide-react';
import './Artifacts.css';

export function Artifacts() {
  const [versions, setVersions]           = useState<any[]>([]);
  const [loading, setLoading]             = useState(true);
  const [expanded, setExpanded]           = useState<string | null>(null);
  const [uploading, setUploading]         = useState(false);
  const [uploadMsg, setUploadMsg]         = useState<{ text: string; ok: boolean } | null>(null);
  const [showUploadModal, setShowUploadModal] = useState(false);
  const [file, setFile]                   = useState<File | null>(null);
  const [serviceType, setServiceType]     = useState('KAFKA');
  const [versionInput, setVersionInput]   = useState('');
  const fileRef = useRef<HTMLInputElement>(null);

  const fetchVersions = async () => {
    setLoading(true);
    try {
      const res = await fetch('/api/v1/artifacts?serviceType=KAFKA');
      if (res.ok) {
        const data = await res.json();
        setVersions((data.content || []).map((a: any) => ({
          id:             a.id,
          version:        a.version,
          available:      a.status === 'AVAILABLE',
          scala_version:  a.attributes?.scala_version || '2.13',
          release_date:   a.attributes?.release_date || new Date(a.createdAt).toLocaleDateString(),
          size_mb:        (a.fileSizeBytes / 1024 / 1024).toFixed(1),
          filename:       a.fileName,
          features:       a.attributes?.features || ['KRaft support', 'Tiered Storage'],
          security_fixes: a.attributes?.security_fixes || [],
          upgrade_notes:  a.attributes?.upgrade_notes || '',
        })));
      }
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchVersions(); }, []);

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
      // Send directly to the artifact repo (port 8081) to bypass Vite proxy limits for large files
      const res = await fetch('http://localhost:8081/api/v1/artifacts', { method: 'POST', body: form });
      if (res.ok) {
        setUploadMsg({ text: `Uploaded ${file.name} (${(file.size / 1024 / 1024).toFixed(1)} MB)`, ok: true });
        setShowUploadModal(false);
        setFile(null);
        setVersionInput('');
        fetchVersions();
      } else {
        const err = await res.json().catch(() => ({}));
        setUploadMsg({ text: err.detail || 'Upload failed.', ok: false });
      }
    } catch {
      setUploadMsg({ text: 'Upload failed due to a network error.', ok: false });
    } finally {
      setUploading(false);
      if (fileRef.current) fileRef.current.value = '';
    }
  };

  return (
    <div className="artifacts-page animate-fade-in">

      <header className="page-header flex-between">
        <div>
          <h1>Kafka versions</h1>
          <p>Manage locally available Kafka binaries for airgapped deployment</p>
        </div>
        <div className="header-actions">
          {uploadMsg && (
            <span className={`upload-msg ${uploadMsg.ok ? 'ok' : 'err'}`}>
              {uploadMsg.text}
            </span>
          )}
          <button
            className="btn btn-primary-action"
            onClick={() => setShowUploadModal(true)}
            disabled={uploading}
          >
            {uploading
              ? <Loader2 size={14} className="spin" />
              : <Upload size={14} />}
            Upload binary
          </button>
        </div>
      </header>

      {loading ? (
        <div className="state-center">
          <Loader2 size={28} className="spin" />
          <p>Loading versions…</p>
        </div>
      ) : versions.length === 0 ? (
        <div className="state-center">
          <Package size={36} />
          <p>No Kafka versions found.</p>
          <p className="sub">Upload a .tgz binary to get started.</p>
        </div>
      ) : (
        <div className="versions-list">
          {versions.map(ver => {
            const isOpen = expanded === ver.version;
            return (
              <div key={ver.version} className="version-card">
                <button
                  className="version-card-header"
                  onClick={() => setExpanded(isOpen ? null : ver.version)}
                >
                  <div className="version-info">
                    <div className="version-title-row">
                      <span className="version-name">Kafka {ver.version}</span>
                      {ver.available ? (
                        <span className="status-badge available">
                          <CheckCircle size={11} /> Available
                        </span>
                      ) : (
                        <span className="status-badge unavailable">
                          <XCircle size={11} /> Not downloaded
                        </span>
                      )}
                    </div>
                    <div className="version-meta">
                      <span>Scala {ver.scala_version}</span>
                      {ver.release_date && <span>Released {ver.release_date}</span>}
                      {ver.available && <span>{ver.size_mb} MB</span>}
                      {ver.filename && <span className="filename">{ver.filename}</span>}
                    </div>
                  </div>
                  <span className="chevron">
                    {isOpen ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
                  </span>
                </button>

                {isOpen && (
                  <div className="version-card-body">
                    {ver.features?.length > 0 && (
                      <div className="detail-section">
                        <h4><Sparkles size={13} style={{ color: '#185FA5' }} /> Features</h4>
                        <ul>{ver.features.map((f: string, i: number) => <li key={i}>{f}</li>)}</ul>
                      </div>
                    )}
                    {ver.security_fixes?.length > 0 && (
                      <div className="detail-section">
                        <h4><Shield size={13} style={{ color: '#791F1F' }} /> Security fixes</h4>
                        <ul>{ver.security_fixes.map((f: string, i: number) => <li key={i}>{f}</li>)}</ul>
                      </div>
                    )}
                    {ver.upgrade_notes && (
                      <div className="detail-section">
                        <h4><ArrowUpCircle size={13} style={{ color: '#534AB7' }} /> Upgrade notes</h4>
                        <p>{ver.upgrade_notes}</p>
                      </div>
                    )}
                    {!ver.features?.length && !ver.security_fixes?.length && !ver.upgrade_notes && (
                      <p className="no-meta">No additional metadata available for this version.</p>
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
              <h2>Upload artifact</h2>
              <button className="modal-close" onClick={() => setShowUploadModal(false)}>
                <X size={14} />
              </button>
            </div>
            <p className="modal-subtitle">
              Upload a Kafka <code>.tgz</code> binary to the secure internal repository.
            </p>

            <form onSubmit={handleUploadSubmit}>
              <div className="form-group">
                <label>Service type</label>
                <select
                  className="form-control"
                  value={serviceType}
                  onChange={e => setServiceType(e.target.value)}
                  disabled
                >
                  <option value="KAFKA">Apache Kafka</option>
                  <option value="MONITORING">Monitoring stack</option>
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
                <label>Binary file (.tgz)</label>
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
                    accept=".tgz,.tar.gz"
                  />
                </div>
              </div>

              <div className="modal-footer">
                <button
                  type="button"
                  className="btn"
                  onClick={() => setShowUploadModal(false)}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="btn btn-primary-action"
                  disabled={uploading || !file || !versionInput}
                >
                  {uploading ? 'Uploading…' : 'Upload'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}