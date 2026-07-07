import { useEffect, useState, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, XCircle, RefreshCw, AlertTriangle, Terminal, Undo2, Server, CheckCircle2 } from 'lucide-react';
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
                  {step.name}
                </span>
              </div>
            ))}
            {steps.length === 0 && <div className="empty-state">No steps recorded</div>}
          </div>
        </div>

        <div className="job-logs-container glass-panel">
          <div className="logs-header">
            <Terminal size={18} />
            <span>Live Logs</span>
            {!isFinished && <RefreshCw size={14} className="spin log-spin" />}
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
