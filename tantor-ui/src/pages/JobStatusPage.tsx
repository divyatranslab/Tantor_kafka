import { useEffect, useState, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, XCircle, RefreshCw, AlertTriangle, Terminal, Undo2, Server, CheckCircle2, MoreVertical } from 'lucide-react';
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

export function JobStatusPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [job, setJob] = useState<Job | null>(null);
  const [steps, setSteps] = useState<JobStep[]>([]);
  const [loading, setLoading] = useState(true);
  const [showLiveLogs, setShowLiveLogs] = useState(false);
  const [openLogsMenu, setOpenLogsMenu] = useState(false);
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

  const formatStepName = (name: string) => {
    return name
      .replace(/ on [0-9a-fA-F-]+/g, '')
      .replace(/\bbroker_controller\b/g, 'Broker & Controller')
      .replace(/\bbroker_zookeeper\b/g, 'Broker & ZooKeeper')
      .replace(/\bbroker\b/g, 'Broker')
      .replace(/\bcontroller\b/g, 'Controller')
      .replace(/\bzookeeper\b/g, 'ZooKeeper');
  };

  const totalSteps = steps.length;
  const completedSteps = steps.filter(s => s.status === 'SUCCESS' || s.status === 'ROLLED_BACK').length;
  const progressPercent = totalSteps > 0 ? Math.round((completedSteps / totalSteps) * 100) : 0;

  return (
    <div className="job-status-page animate-fade-in">
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
            {steps.map(step => (
              <div className="step-item" key={step.id}>
                <div className={`step-icon ${step.status.toLowerCase()}`}>
                  {getStatusIcon(step.status, 16)}
                </div>
                <span className={`step-name ${step.status === 'PENDING' ? 'pending-text' : ''}`}>
                  {formatStepName(step.name)}
                </span>
              </div>
            ))}
            {steps.length === 0 && <div className="empty-state">No steps recorded</div>}
          </div>
        </div>

        <div className="job-logs-container glass-panel">
          <div className="logs-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <Terminal size={18} />
              <span>{showLiveLogs ? 'Live Logs' : 'Deployment Progress'}</span>
              {!isFinished && <RefreshCw size={14} className="spin log-spin" />}
            </div>
            <div style={{ position: 'relative' }}>
              <button className="btn btn-ghost icon-only" onClick={() => setOpenLogsMenu(!openLogsMenu)}>
                <MoreVertical size={18} />
              </button>
              {openLogsMenu && (
                <div className="logs-action-menu" style={{ position: 'absolute', top: '100%', right: 0, background: '#fff', border: '1px solid #eaeaea', borderRadius: '4px', boxShadow: '0 4px 12px rgba(0,0,0,0.1)', padding: '4px 0', zIndex: 10, minWidth: '150px' }}>
                  <button style={{ width: '100%', padding: '8px 16px', background: 'none', border: 'none', textAlign: 'left', cursor: 'pointer', color: '#333' }} onClick={() => { setShowLiveLogs(!showLiveLogs); setOpenLogsMenu(false); }}>
                    {showLiveLogs ? 'Show Progress Bar' : 'Show Live Logs'}
                  </button>
                </div>
              )}
            </div>
          </div>
          
          {!showLiveLogs ? (
            <div className="progress-content" style={{ padding: '40px', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '100%' }}>
              <h3 style={{ margin: '0 0 20px', color: '#333', fontSize: '18px' }}>
                {isFinished ? (job.status === 'SUCCESS' ? 'Deployment Complete' : 'Deployment Finished with Errors') : 'Deployment in Progress...'}
              </h3>
              <div className="progress-bar-bg" style={{ width: '100%', maxWidth: '500px', height: '12px', background: '#eaeaea', borderRadius: '6px', overflow: 'hidden', marginBottom: '15px' }}>
                <div className="progress-bar-fill" style={{ width: `${progressPercent}%`, height: '100%', background: job.status === 'FAILED' ? '#dc3545' : '#4c6fff', transition: 'width 0.5s ease-in-out' }} />
              </div>
              <div className="progress-stats" style={{ color: '#666', fontSize: '14px', fontWeight: 500 }}>
                {progressPercent}% Complete ({completedSteps} / {totalSteps} steps)
              </div>
            </div>
          ) : (
            <div className="logs-content">
              <pre className="logs-text">
                {renderLogs(job.logs)}
              </pre>
              <div ref={logsEndRef} />
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
