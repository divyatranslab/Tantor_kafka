import { ChevronDown, Download, Loader2, Upload } from 'lucide-react';
import { useClusterDeployment } from '../../hooks/useClusterDeployment';

type ClusterDetailsStepProps = {
  hook: ReturnType<typeof useClusterDeployment>;
};

export function ClusterDetailsStep({ hook }: ClusterDetailsStepProps) {
  const {
    loadingCluster,
    clusterConfigMode,
    selectClusterConfigMode,
    isAddNodeMode,
    customImportSummary,
    importCustomCsv,
    downloadCustomTemplate,
    clusterName,
    setClusterName,
    existingCluster,
    addClusterId,
    kafkaVersion,
    changeKafkaVersion,
    loadingVersions,
    versions,
    availableVersions,
    environment,
    setEnvironment,
    deploymentMode,
    changeDeploymentMode,
    zookeeperSupported,
    installDir,
    setInstallDir,
    dataDir,
    setDataDir,
    logDir,
    setLogDir,
    artifactLoadDir,
    setArtifactLoadDir,
    setCommonConfigOpen,
  } = hook;

  return (
    <>
      {loadingCluster && (
        <section className="cd-panel">
          <div className="cd-template-summary">
            <Loader2 size={16} className="spin" />
            <span>Loading existing cluster details...</span>
          </div>
        </section>
      )}
      <section className="cd-panel">
        <div className="cd-panel-title">
          <h2>Cluster Details</h2>
          <div className="cd-header-toggle">
            <span className={clusterConfigMode === 'default' ? 'active' : ''}>Default</span>
            <label className="cd-toggle-switch">
              <input type="checkbox" checked={clusterConfigMode === 'custom'} onChange={() => selectClusterConfigMode(clusterConfigMode === 'default' ? 'custom' : 'default')} disabled={isAddNodeMode} />
              <span className="cd-toggle-slider"></span>
            </label>
            <span className={clusterConfigMode === 'custom' ? 'active' : ''}>Custom</span>
          </div>
        </div>
        {clusterConfigMode === 'custom' && !isAddNodeMode && (
          <div className="cd-custom-import">
            <div className="cd-custom-import-row">
              <div className="cd-custom-import-info">
                <strong>Install Customs Cluster configurations</strong>
                <p>Use the CSV template to import cluster paths and properties. Host details aren't imported.</p>
              </div>
              <div className="cd-custom-import-actions">
                <label className="cd-custom-btn-upload">
                  <Upload size={16} /> Upload CSV
                  <input type="file" accept=".csv,text/csv" hidden onChange={event => {
                    const selected = event.target.files?.[0];
                    if (selected) void importCustomCsv(selected);
                    event.target.value = '';
                  }} />
                </label>
                <button type="button" className="cd-custom-btn-download" onClick={downloadCustomTemplate}>
                  <Download size={16} /> Download examples
                </button>
              </div>
            </div>
            {customImportSummary && <span className="cd-import-summary">{customImportSummary}</span>}
          </div>
        )}
        <div className="cd-grid-2">
          <label className="cd-field">
            <span>Cluster Name</span>
            <input value={clusterName} onChange={e => setClusterName(e.target.value)} placeholder="production-kraft" disabled={isAddNodeMode} />
          </label>
          {isAddNodeMode && (
            <label className="cd-field">
              <span>Kafka Cluster ID</span>
              <input value={existingCluster?.kafkaClusterId || addClusterId || ''} disabled />
            </label>
          )}
          <label className="cd-field">
            <span>Kafka Version</span>
            <div style={{ position: 'relative', width: '100%' }}>
              <select 
                value={kafkaVersion} 
                onChange={e => changeKafkaVersion(e.target.value)} 
                disabled={isAddNodeMode || loadingVersions || versions.length === 0}
                style={{
                  appearance: 'none',
                  WebkitAppearance: 'none',
                  color: '#818181',
                  paddingRight: '40px'
                }}
              >
                {availableVersions.map(version => (
                  <option key={version.version} value={version.version}>
                    {version.version} ({version.size_mb} MB)
                  </option>
                ))}
                {availableVersions.length === 0 && <option>No available Kafka artifact</option>}
              </select>
              <div style={{ position: 'absolute', right: '16px', top: '10px', pointerEvents: 'none', color: '#818181' }}>
                <ChevronDown size={20} />
              </div>
            </div>
          </label>
          <div className="cd-field">
            <span>Environment (optional)</span>
            <div className="cd-env-buttons">
              {['SIT', 'UAT', 'DEV'].map(env => (
                <button
                  key={env}
                  className={environment.toUpperCase() === env ? 'active' : ''}
                  onClick={() => setEnvironment(env)}
                  disabled={isAddNodeMode}
                >
                  {env}
                </button>
              ))}
            </div>
          </div>
          <div className="cd-field">
            <span>Metadata Mode</span>
            <div className="cd-choice-toggle cd-mode-toggle">
              <button className={deploymentMode === 'kraft' ? 'active' : ''} onClick={() => changeDeploymentMode('kraft')} disabled={isAddNodeMode}>KRaft</button>
              {zookeeperSupported && (
                <button className={deploymentMode === 'zookeeper' ? 'active' : ''} onClick={() => changeDeploymentMode('zookeeper')} disabled={isAddNodeMode}>ZooKeeper</button>
              )}
            </div>
          </div>
        </div>
      </section>

      <section className="cd-panel">
        <div className="cd-panel-title">
          <h2>Deployment Paths</h2>
          <button className="cd-secondary-btn compact" onClick={() => setCommonConfigOpen(true)}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="lucide lucide-file-text">
              <path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z"/>
              <polyline points="14 2 14 8 20 8"/>
              <line x1="16" x2="8" y1="13" y2="13"/>
              <line x1="16" x2="8" y1="17" y2="17"/>
              <line x1="10" x2="8" y1="9" y2="9"/>
            </svg>
            Config
          </button>
        </div>
        <div className="cd-grid-2">
          <label className="cd-field">
            <span>Install directory</span>
            <input value={installDir} onChange={e => setInstallDir(e.target.value)} placeholder="/opt" />
          </label>
          <label className="cd-field">
            <span>Data directory</span>
            <input value={dataDir} onChange={e => setDataDir(e.target.value)} placeholder="/data/kafka" />
          </label>
          <label className="cd-field">
            <span>Log directory</span>
            <input value={logDir} onChange={e => setLogDir(e.target.value)} placeholder="/var/log/kafka" />
          </label>
          <label className="cd-field">
            <span>Artefacts/ Load directory</span>
            <input value={artifactLoadDir} onChange={e => setArtifactLoadDir(e.target.value)} placeholder="/srv/tantor-agent/artifacts" />
          </label>
        </div>
      </section>
    </>
  );
}
