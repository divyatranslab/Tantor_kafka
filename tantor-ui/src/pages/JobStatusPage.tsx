import { useEffect, useState, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, XCircle, RefreshCw, AlertTriangle, Terminal, Undo2, CheckCircle2, Maximize2, Minimize2, Check } from 'lucide-react';
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
  const [isLogsExpanded, setIsLogsExpanded] = useState(false);
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
  }, [job?.logs, isLogsExpanded]);

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

  const formatStatus = (status: string) => {
    switch (status) {
      case 'SUCCESS': return 'Success';
      case 'FAILED': return 'Failed';
      case 'IN_PROGRESS': return 'In - progress';
      case 'PARTIAL_SUCCESS': return 'Partial Success';
      case 'ROLLED_BACK': return 'Rolled Back';
      default: return status.replace('_', ' ');
    }
  };

  const getStatusClass = (status: string) => {
    switch (status) {
      case 'SUCCESS': return 'badge-success';
      case 'FAILED': return 'badge-failed';
      case 'IN_PROGRESS': return 'badge-in-progress';
      case 'PARTIAL_SUCCESS': return 'badge-warning';
      case 'ROLLED_BACK': return 'badge-rolled-back';
      default: return 'badge-pending';
    }
  };

  const getStepIcon = (status: string, size = 16) => {
    switch (status) {
      case 'SUCCESS':
      case 'ROLLED_BACK':
        return (
          <svg width={size} height={size} viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" className="step-icon-success">
            <mask id="mask0_1073_8355" style={{ maskType: 'alpha' }} maskUnits="userSpaceOnUse" x="0" y="0" width="20" height="20">
              <rect width="20" height="20" fill="#D9D9D9"/>
            </mask>
            <g mask="url(#mask0_1073_8355)">
              <path d="M9.99984 18.3332C8.84706 18.3332 7.76373 18.1144 6.74984 17.6769C5.73595 17.2394 4.854 16.6457 4.104 15.8957C3.354 15.1457 2.76025 14.2637 2.32275 13.2498C1.88525 12.2359 1.6665 11.1526 1.6665 9.99984C1.6665 8.84706 1.88525 7.76373 2.32275 6.74984C2.76025 5.73595 3.354 4.854 4.104 4.104C4.854 3.354 5.73595 2.76025 6.74984 2.32275C7.76373 1.88525 8.84706 1.6665 9.99984 1.6665C10.9026 1.6665 11.7568 1.79845 12.5623 2.06234C13.3679 2.32623 14.1109 2.69428 14.7915 3.1665L13.5832 4.39567C13.0554 4.06234 12.4929 3.80192 11.8957 3.61442C11.2984 3.42692 10.6665 3.33317 9.99984 3.33317C8.15262 3.33317 6.5797 3.98248 5.28109 5.28109C3.98248 6.5797 3.33317 8.15262 3.33317 9.99984C3.33317 11.8471 3.98248 13.42 5.28109 14.7186C6.5797 16.0172 8.15262 16.6665 9.99984 16.6665C11.8471 16.6665 13.42 16.0172 14.7186 14.7186C16.0172 13.42 16.6665 11.8471 16.6665 9.99984C16.6665 9.74984 16.6526 9.49984 16.6248 9.24984C16.5971 8.99984 16.5554 8.75678 16.4998 8.52067L17.854 7.1665C18.0068 7.61095 18.1248 8.06928 18.2082 8.5415C18.2915 9.01373 18.3332 9.49984 18.3332 9.99984C18.3332 11.1526 18.1144 12.2359 17.6769 13.2498C17.2394 14.2637 16.6457 15.1457 15.8957 15.8957C15.1457 16.6457 14.2637 17.2394 13.2498 17.6769C12.2359 18.1144 11.1526 18.3332 9.99984 18.3332ZM8.83317 13.8332L5.2915 10.2915L6.45817 9.12484L8.83317 11.4998L17.1665 3.14567L18.3332 4.31234L8.83317 13.8332Z" fill="#332849"/>
            </g>
          </svg>
        );
      case 'FAILED':
      case 'ROLLBACK_FAILED':
        return <XCircle size={size} className="step-icon-failed" />;
      case 'IN_PROGRESS':
      case 'ROLLING_BACK':
        return <RefreshCw className="spin step-icon-progress" size={size} />;
      case 'PARTIAL_SUCCESS':
        return <AlertTriangle size={size} className="step-icon-warning" />;
      case 'PENDING':
      case 'ROLLBACK_PENDING':
      default:
        return <div className="step-icon-pending-circle" style={{ width: size, height: size }}></div>;
    }
  };

  const getStepProgressPercentage = (status: string) => {
    switch (status) {
      case 'SUCCESS': return 100;
      case 'ROLLED_BACK': return 100;
      case 'IN_PROGRESS': return 50;
      case 'ROLLING_BACK': return 50;
      case 'FAILED': return 100;
      case 'ROLLBACK_FAILED': return 100;
      default: return 0;
    }
  };

  const getStepProgressText = (status: string) => {
    switch (status) {
      case 'SUCCESS': return '100% Complete';
      case 'ROLLED_BACK': return '100% Rolled Back';
      case 'IN_PROGRESS': return '50% In Progress';
      case 'ROLLING_BACK': return '50% Rolling Back';
      case 'FAILED': return 'Failed';
      case 'ROLLBACK_FAILED': return 'Rollback Failed';
      default: return '0% Pending';
    }
  };

  const getStepBarClass = (status: string) => {
    switch (status) {
      case 'SUCCESS': return 'bar-success';
      case 'ROLLED_BACK': return 'bar-success';
      case 'IN_PROGRESS': return 'bar-in-progress';
      case 'ROLLING_BACK': return 'bar-in-progress';
      case 'FAILED': return 'bar-failed';
      case 'ROLLBACK_FAILED': return 'bar-failed';
      default: return 'bar-pending';
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

  const totalSteps = displaySteps.length;
  const completedStepsCount = displaySteps.filter(s => ['SUCCESS', 'ROLLED_BACK'].includes(s.status)).length;
  const progressPercentage = totalSteps === 0 ? 0 : Math.round((completedStepsCount / totalSteps) * 100);

  return (
    <div className="job-status-page animate-fade-in">
      <div className="page-header-actions">
        <div className="back-nav" onClick={() => navigate('/jobs')} style={{ cursor: 'pointer' }}>
          <ArrowLeft size={16} /> 
          <div className="back-text">
            <span className="back-label">Job ID</span>
            <span className="back-id">{job.id.slice(0, 8)}</span>
          </div>
        </div>
        <div className="action-buttons">
          {(job.status === 'FAILED' || job.status === 'PARTIAL_SUCCESS') && (
            <button className="btn btn-outline-primary" onClick={handleRetry}>
              <RefreshCw size={14} style={{ marginRight: '6px' }} /> Retry Job
            </button>
          )}
          {job.rollbackSupported && ['SUCCESS', 'FAILED', 'PARTIAL_SUCCESS'].includes(job.status) && (
            <button className="btn btn-outline-primary" onClick={handleRollback}>
              <Undo2 size={14} style={{ marginRight: '6px' }} /> Rollback
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

      <div className="job-summary-wrapper">
        <div className="summary-bar-card">
          <div className="summary-col">
            <div className="summary-label">Status</div>
            <div className={`status-pill ${getStatusClass(job.status)}`}>
              {formatStatus(job.status)}
            </div>
          </div>
          <div className="summary-divider" />
          <div className="summary-col">
            <div className="summary-label">Job ID</div>
            <div className="summary-value truncate-id">{job.id}</div>
          </div>
          <div className="summary-divider" />
          <div className="summary-col">
            <div className="summary-label">Started</div>
            <div className="summary-value date-val">
              {job.startTime ? new Date(job.startTime).toLocaleString() : '-'}
            </div>
          </div>
          <div className="summary-divider" />
          <div className="summary-col">
            <div className="summary-label">Updated</div>
            <div className="summary-value date-val">
              {job.endTime ? new Date(job.endTime).toLocaleString() : (job.startTime ? new Date(job.startTime).toLocaleString() : '-')}
            </div>
          </div>
        </div>
      </div>

      <div className={`job-main-layout ${isLogsExpanded ? 'logs-expanded' : ''}`}>
        {!isLogsExpanded && (
          <div className="job-sidebar">
            <h3 className="panel-title" style={{ textAlign: 'left', marginBottom: '8px', color: '#3E1363', fontSize: '18px', fontWeight: 600 }}>Deployment Steps</h3>
            {totalSteps > 0 && (
              <>
                <div style={{ textAlign: 'left', fontSize: '14px', color: '#332849', marginBottom: '16px' }}>
                  {completedStepsCount} of {totalSteps} steps
                </div>
                <div className="global-progress" style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem', marginBottom: '24px' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                      <div style={{ width: '20px', height: '20px', borderRadius: '50%', background: '#3E1363', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                        <Check size={12} color="#FFFFFF" strokeWidth={3} />
                      </div>
                      <span style={{ fontSize: '14px', color: '#818181', fontWeight: 600 }}>
                        {displaySteps.length > 0 ? getBusinessStepName(displaySteps[displaySteps.length - 1].name) : 'Initializing...'}
                      </span>
                    </div>
                    <div style={{ fontSize: '14px', color: '#818181' }}>
                      {progressPercentage}% Complete
                    </div>
                  </div>
                  <div className="step-progress-track" style={{ height: '8px', background: '#F1F1F1', borderRadius: '4px' }}>
                    <div 
                      className="step-progress-fill"
                      style={{ 
                        width: `${progressPercentage}%`, 
                        background: '#818181', 
                        height: '100%', 
                        borderRadius: '4px',
                        transition: 'width 0.5s ease'
                      }} 
                    />
                  </div>
                </div>
              </>
            )}
            <div className="steps-list">
              {displaySteps.map((step, idx) => (
                <div className="step-card" key={step.id} style={{ padding: '12px 16px', border: '1px solid #CCCCCC', borderRadius: '4px', background: '#FFFFFF', marginBottom: '8px' }}>
                  <div className="step-card-header" style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', gap: '8px' }}>
                    <span className="step-name" style={{ color: '#332849', fontSize: '14px', fontWeight: 500 }}>
                      {idx + 1}. {getBusinessStepName(step.name)}
                    </span>
                    {getStepIcon(step.status, 20)}
                  </div>
                </div>
              ))}
              {displaySteps.length === 0 && <div className="empty-state" style={{ textAlign: 'center', color: '#818181' }}>No steps recorded</div>}
            </div>
          </div>
        )}

        <div className="job-logs-container">
          <div className="logs-header">
            <div className="logs-header-title">
              <span>Live Logs</span>
              {!isFinished && <RefreshCw size={14} className="spin log-spin" />}
            </div>
            <button className="btn icon-only toggle-expand-btn" onClick={() => setIsLogsExpanded(!isLogsExpanded)} title={isLogsExpanded ? "Collapse" : "Expand"}>
              {isLogsExpanded ? <Minimize2 size={16} /> : <Maximize2 size={16} />}
            </button>
          </div>
          <div className="logs-content">
            <pre className="logs-text">
              {renderLogs(job.logs)}
            </pre>
            <div ref={logsEndRef} />
          </div>
        </div>
      </div>
    </div>
  );
}
