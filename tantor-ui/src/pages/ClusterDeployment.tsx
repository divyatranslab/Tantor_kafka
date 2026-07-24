import { Loader2, X } from 'lucide-react';
import { AgentConnectivityModal } from '../components/AgentConnectivityModal';
import './ClusterDeployment.css';
import { useClusterDeployment } from '../hooks/useClusterDeployment';
import { DeploymentStepIndicator } from '../components/deployment/DeploymentStepIndicator';
import { ClusterDetailsStep } from '../components/deployment/ClusterDetailsStep';
import { NodeSelectionStep } from '../components/deployment/NodeSelectionStep';
import { ReviewDeploymentStep } from '../components/deployment/ReviewDeploymentStep';
import { KafkaConfigurationStep } from '../components/deployment/KafkaConfigurationStep';

export function ClusterDeployment({ onClose }: { onClose?: () => void }) {
  const hook = useClusterDeployment(onClose);
  const {
    stage,
    setStage,
    navigate,
    isAddNodeMode,
    canPreview,
    validatingKraft,
    openPreview,
    deploying,
    deployCluster,
    checkingPrereqs,
    pathErrors,
    configBlockingIssues,
    prerequisiteComplete,
    kraftDeploymentBlocked,
    showEnrollModal,
    setShowEnrollModal,
    loadHosts,
  } = hook;

  const mainContent = (
    <div 
      className={`cluster-deploy-page ${onClose ? 'modal-version' : ''} animate-fade-in`}
      style={!onClose ? { maxWidth: '100%' } : {}}
    >
      {(!onClose || stage === 'preview') && (
        <DeploymentStepIndicator hook={hook} />
      )}

      {stage === 'details' ? (
        <div className="cd-layout">
          <ClusterDetailsStep hook={hook} />
          <NodeSelectionStep hook={hook} />
          
          <div className="cd-footer-actions">
            <button className="cd-secondary-btn" onClick={() => onClose ? onClose() : navigate(-1)}>Cancel</button>
            <button className="cd-primary-btn" disabled={!canPreview || validatingKraft} onClick={openPreview}>
              {validatingKraft && <Loader2 size={15} className="spin" />}
              {isAddNodeMode ? 'Preview add node' : validatingKraft ? 'Validating topology' : 'Preview'}
            </button>
          </div>
        </div>
      ) : (
        <div className="cd-layout">
          <ReviewDeploymentStep hook={hook} />

          <div className="cd-footer-actions">
            <button className="cd-secondary-btn" disabled={checkingPrereqs || deploying} onClick={() => setStage('details')}>Cancel</button>
            <button className="cd-primary-btn" disabled={!prerequisiteComplete || deploying || pathErrors.length > 0 || configBlockingIssues.length > 0 || kraftDeploymentBlocked} onClick={deployCluster}>
              {deploying && <Loader2 size={15} className="spin" />}
              {isAddNodeMode ? 'Add node' : 'Deploy'}
            </button>
          </div>
        </div>
      )}
      
      {showEnrollModal && (
        <AgentConnectivityModal onClose={() => {
          setShowEnrollModal(false);
          loadHosts();
        }} />
      )}
      
      <KafkaConfigurationStep hook={hook} />
    </div>
  );

  if (onClose) {
    if (stage === 'preview') {
      return (
        <div className="cd-preview-fullscreen-container animate-fade-in">
          {mainContent}
        </div>
      );
    }
    return (
      <div className="cd-modal-backdrop" onMouseDown={onClose}>
        <div className="cd-deployment-modal-container" onMouseDown={e => e.stopPropagation()}>
          <header className="cd-deployment-modal-header">
            <div>
              <h2>Create New Cluster</h2>
              <p>Configure and deploy a Kafka cluster to your hosts</p>
            </div>
            <button className="cd-modal-close-btn" onClick={onClose} title="Close">
              <X size={20} />
            </button>
          </header>
          <div className="cd-deployment-modal-body">
            {mainContent}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="cluster-deployment-page-wrapper" style={{ padding: '24px', backgroundColor: '#F5F6FA', flex: 1, minHeight: 'calc(100vh - 60px)', display: 'flex', flexDirection: 'column', alignItems: 'stretch', width: '100%', boxSizing: 'border-box' }}>
      {mainContent}
    </div>
  );
}
