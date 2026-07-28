import { AlertTriangle, Loader2, RefreshCw, Settings2, XCircle } from 'lucide-react';
import { useClusterDeployment } from '../../hooks/useClusterDeployment';
import type { PrereqStatus, PrereqResult } from '../../types/clusterDeployment.types';
import { displayIp } from '../../hooks/useClusterDeployment';

type ReviewDeploymentStepProps = {
  hook: ReturnType<typeof useClusterDeployment>;
};

function PrerequisiteLog({ result }: { result: PrereqResult }) {
  const lines = [result.errorMsg, result.logOutput].filter(Boolean).join('\n\n').split('\n');
  return <div className="cd-prereq-log">
    {lines.map((line, index) => {
      const tone = line.startsWith('[PASS]') ? 'pass'
        : line.startsWith('[FAIL]') || line.toLowerCase().includes('gate failed') ? 'fail'
          : line.startsWith('[WARN]') ? 'warn' : 'neutral';
      return <span className={tone} key={`${index}-${line}`}>{line || '\u00a0'}</span>;
    })}
  </div>;
}

function StatusBadge({ status }: { status: PrereqStatus }) {
  const normalized = status || 'IDLE';
  const icon = normalized === 'SUCCESS'
    ? null
    : normalized === 'FAILED'
      ? <XCircle size={13} />
      : normalized === 'REBOOT_REQUIRED'
        ? <AlertTriangle size={13} />
      : normalized === 'RUNNING' || normalized === 'QUEUED'
        ? <Loader2 size={13} className="spin" />
        : null;
        
  let text = '';
  if (normalized === 'IDLE') {
    text = 'Idel'; // Kept typo for behavior preserving
  } else if (normalized === 'SUCCESS') {
    text = 'Success';
  } else if (normalized === 'FAILED') {
    text = 'Failed';
  } else if (normalized === 'RUNNING') {
    text = 'Running';
  } else if (normalized === 'QUEUED') {
    text = 'Queued';
  } else if (normalized === 'REBOOT_REQUIRED') {
    text = 'Reboot Required';
  } else {
    text = normalized;
  }
  
  return <span className={`cd-status ${normalized.toLowerCase()}`}>{icon}{text}</span>;
}

export function ReviewDeploymentStep({ hook }: ReviewDeploymentStepProps) {
  const {
    deploymentMode,
    isAddNodeMode,
    kraftValidation,
    hosts,
    selectedHosts,
    allRoleOptions,
    rolesByHost,
    defaultRoleForMode,
    prereqResults,
    warnings,
    replicationWarnings,
    pathErrors,
    configBlockingIssues,
    checkingPrereqs,
    checkPrerequisites,
    fixPrerequisites,
    rebootHost,
    kraftRiskAcknowledged,
    setKraftRiskAcknowledged,
  } = hook;

  const prerequisiteCheckDisabled = checkingPrereqs || selectedHosts.length === 0;
  const prerequisiteCheckTitle = checkingPrereqs
    ? 'A prerequisite check is already running.'
    : selectedHosts.length === 0
      ? 'Select at least one node before checking prerequisites.'
      : 'Run operating-system and port prerequisite checks on every selected node.';

  return (
    <>
      {deploymentMode === 'kraft' && !isAddNodeMode && kraftValidation && (
        <section className="cd-panel cd-kraft-validation">
          <div className="cd-panel-title">
            <h2>KRaft Topology Validation</h2>
            <span className={`cd-validation-state ${kraftValidation.valid ? 'valid' : 'invalid'}`}>
              {!kraftValidation.valid && <XCircle size={14} />}
              {kraftValidation.valid ? 'Topology valid' : 'Changes required'}
            </span>
          </div>

          <div className="cd-kraft-facts">
            <div><span>Kafka cluster ID</span><strong>{kraftValidation.clusterId}</strong></div>
            <div><span>Quorum mode</span><strong>{kraftValidation.quorumMode}</strong></div>
            <div><span>Controllers</span><strong>{kraftValidation.controllerCount}</strong></div>
            <div><span>Brokers</span><strong>{kraftValidation.brokerCount}</strong></div>
            <div><span>Failure tolerance</span><strong>{kraftValidation.failureTolerance} controller{kraftValidation.failureTolerance === 1 ? '' : 's'}</strong></div>
          </div>

          <div className="cd-quorum-value">
            <span>{kraftValidation.quorumMode === 'static' ? 'controller.quorum.voters' : 'controller.quorum.bootstrap.servers'}</span>
            <code>{kraftValidation.controllerQuorum}</code>
          </div>

          <div className="cd-kraft-table-wrap">
            <table className="cd-kraft-table">
              <thead><tr><th>Host</th><th>Address</th><th>Node ID</th><th>Role</th></tr></thead>
              <tbody>
                {kraftValidation.nodes.map(node => (
                  <tr key={`${node.hostId}-${node.nodeId}`}>
                    <td>{hosts.find(host => host.id === node.hostId)?.hostname || node.hostId}</td>
                    <td>{node.address}</td>
                    <td>{node.nodeId}</td>
                    <td>{node.role.replace('_', ' + ')}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {kraftValidation.errors.length > 0 && (
            <div className="cd-inline-errors">
              {kraftValidation.errors.map(error => <span key={error}><XCircle size={13} /> {error}</span>)}
            </div>
          )}
          {kraftValidation.warnings.length > 0 && (
            <div className="cd-warning-list">
              {kraftValidation.warnings.map(warning => <span key={warning}><AlertTriangle size={13} /> {warning}</span>)}
            </div>
          )}
          {kraftValidation.acknowledgementRequired && (
            <label className="cd-risk-ack">
              <input
                type="checkbox"
                checked={kraftRiskAcknowledged}
                onChange={event => setKraftRiskAcknowledged(event.target.checked)}
              />
              <span>I understand this controller topology has reduced availability and want to continue.</span>
            </label>
          )}
        </section>
      )}
      <section className="cd-panel">
        <div className="cd-panel-title">
          <h2>Nodes Selected for Deployment</h2>
        </div>
        <div className="cd-preview-list">
          {selectedHosts.map(host => {
            const role = allRoleOptions.find(item => item.id === (rolesByHost[host.id] || defaultRoleForMode));
            const result = prereqResults[host.id];
            return (
              <div className="cd-preview-row" key={host.id}>
                <div className="cd-node-main">
                  <strong>{host.hostname}</strong>
                  <span>{displayIp(host)}</span>
                </div>
                <div className="cd-role-copy">
                  <strong>{role?.label}</strong>
                  <span>{role?.detail}</span>
                </div>
                <StatusBadge status={result?.status || 'IDLE'} />
              </div>
            );
          })}
        </div>
        {[...warnings, ...replicationWarnings].length > 0 && (
          <div className="cd-warning-list">
            {[...warnings, ...replicationWarnings].map(warning => <span key={warning}><AlertTriangle size={13} /> {warning}</span>)}
          </div>
        )}
        {[...pathErrors, ...configBlockingIssues].length > 0 && (
          <div className="cd-inline-errors">
            {[...pathErrors, ...configBlockingIssues].map(item => <span key={item}><AlertTriangle size={13} /> {item}</span>)}
          </div>
        )}
      </section>

      <section className="cd-panel">
        <div className="cd-panel-title">
          <h2>Prerequisites</h2>
          <button
            type="button"
            className="cd-prereqs-check-btn"
            disabled={prerequisiteCheckDisabled}
            title={prerequisiteCheckTitle}
            onClick={checkPrerequisites}
          >
            {checkingPrereqs ? <Loader2 size={14} className="spin" /> : <RefreshCw size={14} />}
            Check prerequisites on all nodes
          </button>
          {selectedHosts.some(host => prereqResults[host.id]?.status === 'FAILED') && (
            <button className="cd-secondary-btn compact" disabled={checkingPrereqs} onClick={fixPrerequisites}>
              <Settings2 size={14} /> Fix failed prerequisites
            </button>
          )}
        </div>
        {checkingPrereqs && <div className="cd-progress"><span /></div>}
        <div className="cd-prereq-grid">
          {selectedHosts.map(host => {
            const result = prereqResults[host.id] || { status: 'IDLE', logOutput: '', errorMsg: '' };
            return (
              <details className="cd-prereq-card" key={host.id} open={result.status === 'FAILED' || result.status === 'REBOOT_REQUIRED'}>
                <summary>
                  <span>{host.hostname}</span>
                  <StatusBadge status={result.status} />
                </summary>
                {result.errorMsg || result.logOutput
                  ? <PrerequisiteLog result={result} />
                  : <div className="cd-prereq-log"><span className="neutral">Waiting for prerequisite run...</span></div>}
                {result.status === 'REBOOT_REQUIRED' && (
                  <div className="cd-prereq-remediation-actions">
                    <span><AlertTriangle size={14} /> Persistent settings were applied, but this host must reboot.</span>
                    <button className="cd-secondary-btn compact" disabled={checkingPrereqs} onClick={() => rebootHost(host)}>Reboot host</button>
                  </div>
                )}
              </details>
            );
          })}
        </div>
      </section>
    </>
  );
}
