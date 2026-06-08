import { useState, useEffect, useRef } from 'react';
import {
  Package, Upload, CheckCircle, XCircle, ChevronDown, ChevronUp,
  Shield, Sparkles, ArrowUpCircle, Loader2, HardDrive
} from 'lucide-react';
import './Artifacts.css';

export function Artifacts() {
  const [versions, setVersions] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadMsg, setUploadMsg] = useState<{ text: string; ok: boolean } | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  // New states for the custom Tantor UI upload fields
  const [showUploadModal, setShowUploadModal] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [serviceType, setServiceType] = useState('KAFKA');
  const [versionInput, setVersionInput] = useState('');

  const fetchVersions = async () => {
    setLoading(true);
    try {
      const response = await fetch('/api/v1/artifacts?serviceType=KAFKA');
      if (response.ok) {
        const data = await response.json();
        // Map the backend data to look like the old UI's data
        const mappedVersions = (data.content || []).map((a: any) => ({
          version: a.version,
          available: a.status === 'AVAILABLE',
          scala_version: a.attributes?.scala_version || '2.13',
          release_date: a.attributes?.release_date || new Date(a.createdAt).toLocaleDateString(),
          size_mb: (a.fileSizeBytes / 1024 / 1024).toFixed(1),
          filename: a.fileName,
          features: a.attributes?.features || ['KRaft support', 'Tiered Storage'],
          security_fixes: a.attributes?.security_fixes || [],
          upgrade_notes: a.attributes?.upgrade_notes || '',
          id: a.id
        }));
        setVersions(mappedVersions);
      }
    } catch (error) {
      console.error('Error fetching versions:', error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchVersions();
  }, []);

  const handleUploadSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file || !versionInput) return;
    setUploading(true);
    setUploadMsg(null);
    
    const formData = new FormData();
    formData.append('file', file);
    formData.append('serviceType', serviceType);
    formData.append('version', versionInput);
    formData.append('overwrite', 'true');

    try {
      const response = await fetch('/api/v1/artifacts', {
        method: 'POST',
        body: formData,
      });

      if (response.ok) {
        setUploadMsg({ text: `Uploaded ${file.name} (${(file.size / 1024 / 1024).toFixed(1)} MB)`, ok: true });
        setShowUploadModal(false);
        setFile(null);
        setVersionInput('');
        fetchVersions();
      } else {
        const errData = await response.json().catch(() => ({}));
        setUploadMsg({ text: errData.detail || 'Upload failed.', ok: false });
      }
    } catch (err: any) {
      setUploadMsg({ text: 'Upload failed due to a network error.', ok: false });
    } finally {
      setUploading(false);
      if (fileRef.current) fileRef.current.value = '';
    }
  };

  return (
    <div className="artifacts-page animate-fade-in p-8" style={{ height: '100%', overflowY: 'auto' }}>
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold text-gray-100">Kafka Versions</h1>
          <p className="text-sm text-gray-400 mt-1">
            Manage locally available Kafka binaries for airgapped deployment
          </p>
        </div>
        <div className="flex items-center gap-3">
          {uploadMsg && (
            <span className={`text-sm ${uploadMsg.ok ? 'text-green-500' : 'text-red-500'}`}>{uploadMsg.text}</span>
          )}
          <button 
            className="flex items-center gap-2 px-4 py-2 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700 cursor-pointer"
            onClick={() => setShowUploadModal(true)}
            disabled={uploading}
          >
            {uploading ? <Loader2 size={16} className="animate-spin" /> : <Upload size={16} />}
            Upload Binary
          </button>
        </div>
      </div>

      {loading ? (
        <div className="text-center py-12 text-gray-400 flex flex-col items-center gap-2">
          <Loader2 size={32} className="animate-spin" />
          Loading versions...
        </div>
      ) : versions.length === 0 ? (
        <div className="text-center py-12">
          <Package size={48} className="mx-auto text-gray-500 mb-3" />
          <p className="text-gray-400">No Kafka versions found.</p>
          <p className="text-sm text-gray-500 mt-1">Upload a .tgz binary to get started.</p>
        </div>
      ) : (
        <div className="space-y-3">
          {versions.map(ver => {
            const isExpanded = expanded === ver.version;
            return (
              <div key={ver.version} className="bg-gray-800 border border-gray-700 rounded-xl overflow-hidden shadow-lg transition-all">
                <button
                  onClick={() => setExpanded(isExpanded ? null : ver.version)}
                  className="w-full flex items-center gap-4 px-5 py-4 text-left hover:bg-gray-700 transition-colors"
                >
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-3">
                      <span className="text-lg font-semibold text-gray-100">
                        Kafka {ver.version}
                      </span>
                      {ver.available ? (
                        <span className="flex items-center gap-1 text-xs font-medium text-green-400 bg-green-900/30 border border-green-800 px-2 py-0.5 rounded-full">
                          <CheckCircle size={12} /> Available
                        </span>
                      ) : (
                        <span className="flex items-center gap-1 text-xs font-medium text-gray-400 bg-gray-900 px-2 py-0.5 rounded-full">
                          <XCircle size={12} /> Not Downloaded
                        </span>
                      )}
                    </div>
                    <div className="flex items-center gap-4 mt-1 text-xs text-gray-400">
                      <span>Scala {ver.scala_version}</span>
                      {ver.release_date && <span>Released {ver.release_date}</span>}
                      {ver.available && <span>{ver.size_mb} MB</span>}
                      <span className="font-mono text-gray-500">{ver.filename}</span>
                    </div>
                  </div>
                  {isExpanded ? <ChevronUp size={18} className="text-gray-400" /> : <ChevronDown size={18} className="text-gray-400" />}
                </button>

                {isExpanded && (
                  <div className="px-5 pb-5 border-t border-gray-700 bg-gray-900/50 space-y-4 pt-4">
                    {ver.features && ver.features.length > 0 && (
                      <div>
                        <h4 className="flex items-center gap-2 text-sm font-semibold text-gray-200 mb-2">
                          <Sparkles size={14} className="text-blue-400" /> Features
                        </h4>
                        <ul className="list-disc list-inside text-sm text-gray-400 space-y-1 ml-1">
                          {ver.features.map((f: string, i: number) => <li key={i}>{f}</li>)}
                        </ul>
                      </div>
                    )}

                    {ver.security_fixes && ver.security_fixes.length > 0 && (
                      <div>
                        <h4 className="flex items-center gap-2 text-sm font-semibold text-gray-200 mb-2">
                          <Shield size={14} className="text-red-400" /> Security Fixes
                        </h4>
                        <ul className="list-disc list-inside text-sm text-gray-400 space-y-1 ml-1">
                          {ver.security_fixes.map((f: string, i: number) => <li key={i}>{f}</li>)}
                        </ul>
                      </div>
                    )}

                    {ver.upgrade_notes && (
                      <div>
                        <h4 className="flex items-center gap-2 text-sm font-semibold text-gray-200 mb-2">
                          <ArrowUpCircle size={14} className="text-purple-400" /> Upgrade Notes
                        </h4>
                        <p className="text-sm text-gray-400 ml-1">{ver.upgrade_notes}</p>
                      </div>
                    )}

                    {!ver.features?.length && !ver.security_fixes?.length && !ver.upgrade_notes && (
                      <p className="text-sm text-gray-500 italic">
                        No additional metadata available for this version.
                      </p>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}

      {/* Upload Modal */}
      {showUploadModal && (
        <div className="modal-overlay z-50 fixed inset-0 bg-black/50 flex items-center justify-center">
          <div className="glass-panel modal-content animate-fade-in" style={{ padding: '2rem', maxWidth: '500px', width: '100%', position: 'relative' }}>
            <button 
              onClick={() => setShowUploadModal(false)}
              className="absolute top-4 right-4 text-gray-400 hover:text-white"
            >
              <XCircle size={24} />
            </button>
            <h2 className="text-xl font-bold text-white mb-2">Upload Artifact</h2>
            <p className="text-gray-400 text-sm mb-6">Upload a Kafka `.tgz` binary to the secure internal repository.</p>
            
            <form onSubmit={handleUploadSubmit}>
              <div className="form-group mb-4">
                <label className="block text-sm font-medium text-gray-300 mb-1">Service Type</label>
                <select className="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-white" value={serviceType} onChange={e => setServiceType(e.target.value)} disabled>
                  <option value="KAFKA">Apache Kafka</option>
                  <option value="MONITORING">Monitoring Stack</option>
                </select>
              </div>

              <div className="form-group mb-4">
                <label className="block text-sm font-medium text-gray-300 mb-1">Version Number</label>
                <input 
                  type="text" 
                  className="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-white" 
                  value={versionInput} 
                  onChange={e => setVersionInput(e.target.value)}
                  placeholder="e.g. 3.7.0"
                  required
                />
              </div>

              <div className="form-group mb-6">
                <label className="block text-sm font-medium text-gray-300 mb-1">Binary File (.tgz)</label>
                <div 
                  className="upload-dropzone border-2 border-dashed border-gray-600 rounded-lg p-8 text-center cursor-pointer hover:border-blue-500 hover:bg-gray-800/50 transition-colors" 
                  onClick={() => fileRef.current?.click()}
                >
                  <HardDrive size={32} className="text-blue-500 mx-auto mb-3" />
                  {file ? (
                    <div>
                      <div className="text-white font-medium">{file.name}</div>
                      <div className="text-gray-400 text-sm">{(file.size / 1024 / 1024).toFixed(2)} MB</div>
                    </div>
                  ) : (
                    <div className="text-gray-400">Click to select a binary file</div>
                  )}
                  <input 
                    type="file" 
                    ref={fileRef} 
                    className="hidden"
                    onChange={e => setFile(e.target.files ? e.target.files[0] : null)}
                    accept=".tgz,.tar.gz"
                  />
                </div>
              </div>

              <div className="flex justify-end gap-3">
                <button type="button" className="px-4 py-2 rounded-lg text-sm font-medium text-gray-300 hover:text-white" onClick={() => setShowUploadModal(false)}>
                  Cancel
                </button>
                <button type="submit" className="px-4 py-2 rounded-lg text-sm font-medium bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50" disabled={uploading || !file || !versionInput}>
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
