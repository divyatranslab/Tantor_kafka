import { useEffect, useState, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, XCircle, RefreshCw, AlertTriangle, Terminal, Undo2, Server, CheckCircle2, MoreVertical, Activity } from 'lucide-react';
import './JobStatusPage.css';

type Job = {
  id: string;
  type: string;
  status: string;
  requestedBy: string;
  startTime: string;
  endTime: string;
  progress: string;
  logs: string;
  rollbackSupported: boolean;
  retryCount: number;
};

type JobStep = {
  id: string;
  stepOrder: number;
  name: string;
  targetId: string;
  status: string;
  agentTaskId?: string;
  logs?: string;
  retryCount: number;
  startTime?: string;
  endTime?: string;
};

function getBusinessStepName(rawName: string): string {
  if (!rawName) return '';

  if (rawName.startsWith('Deploy ')) {
    const match = rawName.match(/Deploy (.+) node (\d+)/i);
    if (match) {
      let role = match[1].toLowerCase();
      if (role === 'broker_controller') role = 'Broker/Controller';
      else if (role === 'broker') role = 'Broker';
      else if (role === 'controller') role = 'Controller';
      else if (role === 'zookeeper') role = 'ZooKeeper';
      else role = role.charAt(0).toUpperCase() + role.slice(1);
      
      return `Provision ${role} Node ${match[2]}`;
    }
    return 'Provision Kafka Node';
  }

  if (rawName.startsWith('Check controller connectivity')) {
    return 'Verify Internal Connectivity';
  }

  if (rawName.includes('Verify ZooKeeper quorum')) {
    return 'Validate ZooKeeper Quorum Health';
  }

  if (rawName.includes('Verify KRaft leader')) {
    return 'Validate KRaft Cluster Health';
  }

  // Rolling Config Update Steps
  if (rawName === 'PREFLIGHT') return 'Preflight Validation';
  if (rawName === 'BACKUP_ALL') return 'Global Configuration Backup';
  if (rawName === 'WRITE_CONFIG') return 'Write Configuration';
  if (rawName === 'RESTART_SERVICE') return 'Restart Service';
  if (rawName === 'HEALTH_CHECK') return 'Health Check';
  if (rawName === 'ROLLBACK') return 'Rollback Configuration';
  if (rawName === 'FINAL_VERIFY') return 'Final Verification';

  return rawName;
}

export function JobStatusPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [job, setJob] = useState<Job | null>(null);
  const [steps, setSteps] = useState<JobStep[]>([]);
  const [loading, setLoading] = useState(true);
  const [viewMode, setViewMode] = useState<'progress' | 'logs'>('progress');
  const [openMenu, setOpenMenu] = useState(false);
  const logsEndRef = useRef<HTMLDivElement>(null);

  const fetchJob = async () => {
    try {
      const res = await fetch(`/api/v1/ui/jobs/${id}`);
      if (res.ok) {
        setJob(await res.json());
      }
      const stepsRes = await fetch(`/api/v1/ui/jobs/${id}/steps`);
      if (stepsRes.ok) setSteps(await stepsRes.json());
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchJob();
    const interval = setInterval(() => {
      if (!['SUCCESS', 'FAILED', 'PARTIAL_SUCCESS', 'ROLLED_BACK', 'ROLLBACK_FAILED'].includes(job?.status || '')) {
         fetchJob();
      }
    }, 2000);
    return () => clearInterval(interval);
  }, [id, job?.status]);

  useEffect(() => {
    if (logsEndRef.current) {
      logsEndRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  }, [job?.logs]);

  const handleRetry = async () => {
    try {
      const res = await fetch(`/api/v1/ui/jobs/${id}/retry`, { method: 'POST' });
      if (res.ok) fetchJob();
    } catch (err) {
      console.error(err);
    }
  };

  const handleRollback = async () => {
    if (!window.confirm('Rollback all successfully completed steps for this job?')) return;
    try {
      const res = await fetch(`/api/v1/ui/jobs/${id}/rollback`, { method: 'POST' });
      if (res.ok) fetchJob();
    } catch (err) {
      console.error(err);
    }
  };

  if (loading && !job) {
    return (
      <div className="job-status-page loading-state">
        <RefreshCw className="spin" size={32} />
        <p>Loading job details...</p>
      </div>
    );
  }

  if (!job) {
    return (
      <div className="job-status-page">
        <button className="btn btn-ghost" onClick={() => navigate('/jobs')}><ArrowLeft size={16}/> Back to Jobs</button>
        <h2>Job not found</h2>
      </div>
    );
  }

  const isFinished = ['SUCCESS', 'FAILED', 'PARTIAL_SUCCESS', 'ROLLED_BACK', 'ROLLBACK_FAILED'].includes(job.status);
  
  let displaySteps = steps;
  
  if (steps.length === 0 && job?.logs) {
    const reconstructed: JobStep[] = [];
    const lines = job.logs.split('\n');
    let order = 0;
    
    for (const line of lines) {
      const compMatch = line.match(/\]\s*(.+?)\s+completed/);
      if (compMatch) {
        reconstructed.push({
          id: `recon-${order}`,
          stepOrder: order++,
          name: compMatch[1],
          targetId: '',
          status: 'SUCCESS',
          retryCount: 0
        });
      }
      
      const failMatch = line.match(/\]\s*Job execution failed:\s*(.+)/);
      if (failMatch) {
        reconstructed.push({
          id: `recon-${order}`,
          stepOrder: order++,
          name: failMatch[1].length > 45 ? failMatch[1].substring(0, 45) + '...' : failMatch[1],
          targetId: '',
          status: 'FAILED',
          retryCount: 0
        });
      }
    }
    
    if (reconstructed.length > 0) {
      displaySteps = reconstructed;
    }
  }

  const completedStepsCount = displaySteps.filter(s => s.status === 'SUCCESS' || s.status === 'ROLLED_BACK').length;
  const totalSteps = displaySteps.length;
  
  let progressPercentage = 0;
  if (totalSteps > 0) {
    progressPercentage = Math.round((completedStepsCount / totalSteps) * 100);
  } else if (isFinished && job.status === 'SUCCESS') {
    progressPercentage = 100;
  }
  
  let currentStepName = 'Setting up your environment...';
  if (totalSteps > 0) {
    const activeStep = displaySteps.find(s => s.status === 'IN_PROGRESS' || s.status === 'PENDING');
    const failedStep = displaySteps.find(s => s.status === 'FAILED');
    if (activeStep) {
      currentStepName = getBusinessStepName(activeStep.name);
    } else if (failedStep) {
      currentStepName = `Failed: ${getBusinessStepName(failedStep.name)}`;
    } else if (isFinished) {
      currentStepName = job.status === 'SUCCESS' ? 'Deployment completed successfully' : 'Deployment finished with errors';
    } else if (completedStepsCount === totalSteps) {
      currentStepName = 'All steps completed';
    }
  } else if (isFinished) {
    currentStepName = job.status === 'SUCCESS' ? 'Deployment completed successfully' : 'Deployment finished with errors';
  }

  const getStatusIcon = (status: string, size = 18) => {
    switch (status) {
      case 'SUCCESS':
      case 'ROLLED_BACK':
        return <CheckCircle2 size={size} />;
      case 'FAILED':
      case 'ROLLBACK_FAILED':
        return <XCircle size={size} />;
      case 'IN_PROGRESS':
      case 'ROLLING_BACK':
        return <RefreshCw className="spin" size={size} />;
      case 'PARTIAL_SUCCESS':
        return <AlertTriangle size={size} />;
      case 'PENDING':
      case 'ROLLBACK_PENDING':
      default:
        return <CheckCircle2 size={size} className="status-pending-icon" />;
    }
  };

  const renderLogs = (logsText: string) => {
    if (!logsText) return 'Waiting for execution logs...';
    return logsText.split('\n').map((line, idx) => {
      let className = 'log-line';
      const lowerLine = line.toLowerCase();
      if (lowerLine.includes('completed')) {
        className += ' log-success';
      } else if (lowerLine.includes('failed') || lowerLine.includes('error')) {
        className += ' log-error';
      }
      return <div key={idx} className={className}>{line || ' '}</div>;
    });
  };

  return (
    <div className="job-status-page animate-fade-in" onClick={() => setOpenMenu(false)}>
      <div className="page-header-actions">
        <button className="btn btn-ghost back-btn" onClick={() => navigate('/jobs')}>
          <ArrowLeft size={16} /> Back to Jobs
        </button>
        <div className="action-buttons">
          {(job.status === 'FAILED' || job.status === 'PARTIAL_SUCCESS') && (
            <button className="btn btn-primary" onClick={handleRetry}>
              <RefreshCw size={16} /> Retry Job
            </button>
          )}
          {job.rollbackSupported && ['SUCCESS', 'FAILED', 'PARTIAL_SUCCESS'].includes(job.status) && (
            <button className="btn" onClick={handleRollback}>
              <Undo2 size={16} /> Rollback
            </button>
          )}
        </div>
      </div>
      
      {job.status === 'FAILED' && steps.some(s => s.name === 'ROLLBACK' && s.status === 'SUCCESS') && (
        <div className="overview-alert error" style={{ marginBottom: '16px' }}>
          <AlertTriangle size={17} />
          <span>Previously successful nodes remain changed. Failed node was rolled back.</span>
        </div>
      )}

      <div className="top-status-bar glass-panel">
        <div className="status-col">
          <div className="status-label">STATUS</div>
          <div className={`status-value status-badge ${job.status.toLowerCase()}`}>
            {getStatusIcon(job.status, 20)}
            <span>{job.status}</span>
          </div>
        </div>
        <div className="status-col">
          <div className="status-label">JOB ID</div>
          <div className="status-value with-icon">
            <Server size={20} className="host-icon" />
            <span className="truncate-id">{job.id}</span>
          </div>
        </div>
        <div className="status-col">
          <div className="status-label">STARTED</div>
          <div className="status-value date-val">
            {job.startTime ? new Date(job.startTime).toLocaleString() : '-'}
          </div>
        </div>
        <div className="status-col">
          <div className="status-label">UPDATED</div>
          <div className="status-value date-val">
            {job.endTime ? new Date(job.endTime).toLocaleString() : (job.startTime ? new Date(job.startTime).toLocaleString() : '-')}
          </div>
        </div>
      </div>

      <div className="job-main-layout">
        <div className="job-sidebar glass-panel">
          <h3>Deployment Steps</h3>
          <div className="steps-list">
            {displaySteps.map(step => (
              <div className="step-item" key={step.id}>
                <div className={`step-icon ${step.status.toLowerCase()}`}>
                  {getStatusIcon(step.status, 16)}
                </div>
                <span className={`step-name ${step.status === 'PENDING' ? 'pending-text' : ''}`}>
                  {getBusinessStepName(step.name)}
                </span>
              </div>
            ))}
            {displaySteps.length === 0 && <div className="empty-state">No steps recorded</div>}
          </div>
        </div>

        {viewMode === 'logs' ? (
          <div className="job-logs-container glass-panel">
            <div className="logs-header">
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', flex: 1 }}>
                <Terminal size={18} />
                <span>Live Logs</span>
                {!isFinished && <RefreshCw size={14} className="spin log-spin" />}
              </div>
              <div className="row-actions cluster-menu-anchor" onClick={e => e.stopPropagation()}>
                <button className="btn icon-only" style={{ background: 'transparent', color: '#a6accd', border: 'none' }} onClick={() => setOpenMenu(prev => !prev)} title="View options">
                  <MoreVertical size={16} />
                </button>
                {openMenu && (
                  <div className="cluster-action-menu" style={{ right: 0, top: '100%' }}>
                    <button onClick={() => { setViewMode('progress'); setOpenMenu(false); }}>
                      <Activity size={14} /> Show progress
                    </button>
                  </div>
                )}
              </div>
            </div>
            <div className="logs-content">
              <pre className="logs-text">
                {renderLogs(job.logs)}
              </pre>
              <div ref={logsEndRef} />
            </div>
          </div>
        ) : (
          <div className="job-progress-container glass-panel">
            <div className="progress-header-wrap">
              <div>
                <h3>Deployment Progress</h3>
                <p>{currentStepName}</p>
              </div>
              <div className="row-actions cluster-menu-anchor" onClick={e => e.stopPropagation()}>
                <button className="btn icon-only" onClick={() => setOpenMenu(prev => !prev)} title="View options">
                  <MoreVertical size={16} />
                </button>
                {openMenu && (
                  <div className="cluster-action-menu" style={{ right: 0, top: '100%' }}>
                    <button onClick={() => { setViewMode('logs'); setOpenMenu(false); }}>
                      <Terminal size={14} /> Show live logs
                    </button>
                  </div>
                )}
              </div>
            </div>
            <div className="progress-bar-wrapper">
               <div className="progress-bar-track">
                  <div className="progress-bar-fill" style={{ width: `${progressPercentage}%` }} />
               </div>
               <div className="progress-bar-stats">
                 <span>{progressPercentage}% Complete</span>
                 <span>{completedStepsCount} of {totalSteps} steps</span>
               </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
