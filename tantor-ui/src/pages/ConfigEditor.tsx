import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { FileText, Loader2, Plus, RefreshCw, Save, Server, Trash2 } from 'lucide-react';
import './ConfigEditor.css';

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
  staticConfigs: {
    configFiles?: StaticConfigFile[];
  };
}

const generatedKeys = new Set([
  'process.roles', 'node.id', 'broker.id', 'listeners', 'advertised.listeners',
  'controller.quorum.voters', 'controller.quorum.bootstrap.servers',
  'zookeeper.connect', 'dataDir', 'clientPort', 'servers',
]);

export function ConfigEditor() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [payload, setPayload] = useState<ConfigPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [selectedHostId, setSelectedHostId] = useState('');
  const [selectedFileId, setSelectedFileId] = useState('');
  const [draftProperties, setDraftProperties] = useState<Record<string, string>>({});
  const [newKey, setNewKey] = useState('');
  const [newValue, setNewValue] = useState('');
  const [restart, setRestart] = useState(true);

  const fetchConfigs = async () => {
    setLoading(true);
    try {
      const response = await fetch(`/api/v1/clusters/${id}/config`);
      if (response.ok) setPayload(await response.json());
    } catch (error) {
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchConfigs();
  }, [id]);

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
    setDraftProperties(Object.fromEntries(
      Object.entries(selectedFile.properties || {}).map(([key, value]) => [key, String(value ?? '')])
    ));
  }, [selectedFile?.id]);

  const selectHost = (hostId: string) => {
    setSelectedHostId(hostId);
    const firstFile = files.find(file => file.hostId === hostId);
    setSelectedFileId(firstFile?.id || '');
  };

  const addProperty = () => {
    const key = newKey.trim();
    if (!key || !/^[A-Za-z0-9._-]+$/.test(key)) {
      alert('Enter a valid Kafka property key.');
      return;
    }
    setDraftProperties(current => ({ ...current, [key]: newValue }));
    setNewKey('');
    setNewValue('');
  };

  const saveConfiguration = async () => {
    if (!selectedFile?.serviceId) {
      alert('This configuration is not attached to a managed node service.');
      return;
    }
    setSaving(true);
    try {
      const response = await fetch(`/api/v1/clusters/${id}/config/services/${selectedFile.serviceId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ properties: draftProperties, restart }),
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) {
        alert(data.message || 'Failed to update configuration.');
        return;
      }
      navigate(`/clusters/${id}/logs`);
    } catch {
      alert('Network error while updating configuration.');
    } finally {
      setSaving(false);
    }
  };

  if (loading && !payload) {
    return <div className="state-center"><Loader2 className="spin" /> Loading node configurations...</div>;
  }

  return (
    <div className="node-config-page">
      <header className="node-config-header">
        <div><h2>Configuration Change</h2><p>Select a node, choose its configuration file, then apply the change to that service.</p></div>
        <button onClick={fetchConfigs} disabled={loading}><RefreshCw size={14} className={loading ? 'spin' : ''} /> Refresh</button>
      </header>

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

      {selectedFile ? (
        <section className="node-config-section editor">
          <div className="node-config-editor-head">
            <div><h3>{selectedFile.label}</h3><p>{selectedFile.path}</p></div>
            <label><input type="checkbox" checked={restart} onChange={event => setRestart(event.target.checked)} /> Restart this service after saving</label>
          </div>

          <div className="node-config-table-wrap">
            <table className="node-config-table">
              <thead><tr><th>Property</th><th>Value</th><th>Action</th></tr></thead>
              <tbody>
                {Object.entries(draftProperties).sort(([a], [b]) => a.localeCompare(b)).map(([key, value]) => {
                  const generated = generatedKeys.has(key);
                  return (
                    <tr key={key}>
                      <td><code>{key}</code>{generated && <small>Managed by Tantor</small>}</td>
                      <td><input value={value} disabled={generated} onChange={event => setDraftProperties(current => ({ ...current, [key]: event.target.value }))} /></td>
                      <td><button title={`Remove ${key}`} disabled={generated} onClick={() => setDraftProperties(current => { const next = { ...current }; delete next[key]; return next; })}><Trash2 size={14} /></button></td>
                    </tr>
                  );
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
            <button onClick={saveConfiguration} disabled={saving}>{saving ? <Loader2 size={14} className="spin" /> : <Save size={14} />} Save configuration</button>
          </div>
        </section>
      ) : <div className="empty-state">No managed configuration file is available for this node.</div>}
    </div>
  );
}
