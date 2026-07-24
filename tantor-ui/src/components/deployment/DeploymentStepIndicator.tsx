import { ChevronLeft } from 'lucide-react';
import { useClusterDeployment } from '../../hooks/useClusterDeployment';

type DeploymentStepIndicatorProps = {
  hook: ReturnType<typeof useClusterDeployment>;
};

export function DeploymentStepIndicator({ hook }: DeploymentStepIndicatorProps) {
  const {
    stage,
    setStage,
    isAddNodeMode,
    canPreview,
    openPreview,
  } = hook;

  return (
    <header className="cd-header">
      <div>
        <h1>
          <ChevronLeft size={24} color="#818181" className="cd-back-icon" onClick={() => {
            if (stage === 'preview') {
              setStage('details');
            } else {
              window.history.back();
            }
          }} />
          {stage === 'details' ? (isAddNodeMode ? 'Add Node to Cluster' : 'Create Kafka Cluster') : (isAddNodeMode ? 'Preview Node Addition' : 'Preview Deployment')}
        </h1>
        <p>{stage === 'details'
          ? isAddNodeMode
            ? 'External cluster details are loaded. Select new nodes and roles to add.'
            : 'Define the cluster, select nodes, and choose roles.'
          : 'Run prerequisites across every selected node before deployment.'}</p>
      </div>
      <div className="cd-header-side">
        <div className="cd-stage-tabs" aria-label="Deployment progress">
          <span 
            className={stage === 'details' ? 'active' : ''} 
            onClick={() => setStage('details')} 
            style={{ cursor: 'pointer' }}
          >
            Details
          </span>
          <span 
            className={`${stage === 'preview' ? 'active' : ''} ${!canPreview ? 'disabled' : ''}`} 
            onClick={() => {
              if (canPreview) {
                if (stage === 'details') {
                  openPreview();
                } else {
                  setStage('preview');
                }
              }
            }} 
            style={{ cursor: canPreview ? 'pointer' : 'not-allowed', opacity: canPreview ? 1 : 0.5 }}
          >
            Preview
          </span>
        </div>
      </div>
    </header>
  );
}
