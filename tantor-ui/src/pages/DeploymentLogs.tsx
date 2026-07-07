import { useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { CheckCircle2, Clock, Copy, Loader2, RefreshCw, Server, Terminal, XCircle, RotateCcw, PlayCircle, Trash2, Download, MoreVertical } from 'lucide-react';
import { retryTask, resumeTask, rollbackTask, cleanupTask } from '../lib/api';
import './DeploymentLogs.css';

interface Task {
  id: string;
  hostId: string;
  command: string;
  status: string;
  logOutput: string;
  errorMsg: string;
  currentStep?: string;
  failedReason?: string;
  stepLogs?: string; // JSON String from backend representing Map<string, string>
  createdAt: string;
  updatedAt: string;
}

interface ClusterInfo {
  status: string;
}

const activeStatus = (status: string) => ['PENDING', 'RUNNING', 'VALIDATING', 'IN_PROGRESS'].includes(String(status).toUpperCase());

const friendlyFailure = (task: Task) => {
  if (task.failedReason && !task.failedReason.includes('Exception') && task.failedReason.length < 500) return task.failedReason;
  const error = `${task.errorMsg || ''} ${task.failedReason || ''}`.toLowerCase();
  if (error.includes('404') || error.includes('download')) return 'Kafka could not be downloaded. Verify the artifact repository URL and network access from this host.';
  if (error.includes('checksum')) return 'The Kafka package failed its integrity check. Distribute the binary again and retry.';
  if (error.includes('permission denied')) return 'The agent lacks permission to write files or manage the Kafka service.';
  if (error.includes('no space left')) return 'The target host does not have enough disk space.';
  if (error.includes('systemctl') || error.includes('service')) return 'Kafka files were prepared, but the Kafka service could not start.';
  if (error.includes('port') || error.includes('listening')) return 'Kafka did not become reachable on its configured port.';
  return `Deployment stopped during ${task.currentStep || 'an unknown step'}. Review the technical log below.`;
};

const DEPLOYMENT_STEPS = [
  'Validate agent',
  'Validate host prerequisites',
  'Validate package',
  'Download package to agent',
  'Verify checksum',
  'Extract Kafka',
  'Backup old config if exists',
  'Generate config',
  'Format KRaft storage / setup Zookeeper',
  'Create systemd service',
  'Start service',
  'Validate port',
  'Validate Kafka AdminClient connection',
  'Validate cluster health',
  'Mark DB state RUNNING',
];

export function DeploymentLogs() {
  const { id } = useParams<{ id: string }>();
  const [cluster, setCluster] = useState<ClusterInfo | null>(null);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [selectedTaskId, setSelectedTaskId] = useState('');
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);
  const logBodyRef = useRef<HTMLDivElement>(null);
  const [showLiveLogs, setShowLiveLogs] = useState(false);
  const [openLogsMenu, setOpenLogsMenu] = useState(false);

  const fetchTasks = async () => {
    try {
      const [clusterRes, tasksRes] = await Promise.all([
        fetch(`/api/v1/ui/clusters/${id}`),
        fetch(`/api/v1/ui/clusters/${id}/tasks`),
      ]);
      if (clusterRes.ok) setCluster(await clusterRes.json());
      if (tasksRes.ok) {
        const nextTasks: Task[] = await tasksRes.json();
        setTasks(nextTasks);
        setSelectedTaskId(current => current && nextTasks.some(task => task.id === current) ? current : nextTasks[0]?.id || '');
      }
    } catch (error) {
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchTasks();
  }, [id]);

  const shouldPoll = tasks.some(task => activeStatus(task.status))
    || ['PENDING', 'RUNNING', 'VALIDATING', 'DELETING'].includes(cluster?.status || '');

  useEffect(() => {
    if (!shouldPoll) return;
    const interval = window.setInterval(fetchTasks, 3000);
    return () => window.clearInterval(interval);
  }, [id, shouldPoll]);

  const selectedTask = tasks.find(task => task.id === selectedTaskId) || tasks[0];

  useEffect(() => {
    if (logBodyRef.current) logBodyRef.current.scrollTop = logBodyRef.current.scrollHeight;
  }, [selectedTask?.logOutput, selectedTask?.stepLogs]);

  const statusIcon = (status: string) => {
    if (status === 'SUCCESS') return <CheckCircle2 size={15} />;
    if (status === 'FAILED') return <XCircle size={15} />;
    if (activeStatus(status)) return <Loader2 size={15} className="spin" />;
    return <Clock size={15} />;
  };

  if (loading && tasks.length === 0) {
    return <div className="state-center"><Loader2 className="spin" /> Loading deployment logs...</div>;
  }

  if (!selectedTask) {
    return (
      <div className="empty-state deployment-log-empty">
        <Terminal size={32} />
        <h3>No deployment logs</h3>
        <p>No task has been executed for this cluster.</p>
      </div>
    );
  }

  let stepLogsObj: Record<string, string> = {};
  try {
    if (selectedTask.stepLogs) {
      stepLogsObj = JSON.parse(selectedTask.stepLogs);
    }
  } catch (e) {
    console.error("Failed to parse step logs", e);
  }

  const handleRetry = async () => {
    if (!id || !selectedTask) return;
    setActionLoading(true);
    try {
      await retryTask(id, selectedTask.id);
      fetchTasks();
    } catch (e) {
      console.error(e);
      alert("Failed to retry task.");
    } finally {
      setActionLoading(false);
    }
  };

  const handleRollback = async () => {
    if (!id || !selectedTask) return;
    if (!confirm("Are you sure you want to rollback this deployment? (Services will be stopped but logs and configs remain)")) return;
    
    setActionLoading(true);
    try {
      await rollbackTask(id, selectedTask.id);
      fetchTasks();
    } catch (e) {
      console.error(e);
      alert("Failed to trigger rollback.");
    } finally {
      setActionLoading(false);
    }
  };

  const handleResume = async () => {
    if (!id || !selectedTask) return;
    setActionLoading(true);
    try {
      await resumeTask(id, selectedTask.id);
      fetchTasks();
    } catch (e) {
      console.error(e);
      alert("Failed to resume task.");
    } finally {
      setActionLoading(false);
    }
  };

  const handleCleanup = async () => {
    if (!id || !selectedTask) return;
    if (!confirm("Are you sure you want to completely clean up this deployment? (All files and logs on the node will be deleted)")) return;
    
    setActionLoading(true);
    try {
      await cleanupTask(id, selectedTask.id);
      fetchTasks();
    } catch (e) {
      console.error(e);
      alert("Failed to trigger cleanup.");
    } finally {
      setActionLoading(false);
    }
  };

  const handleDownloadLogs = () => {
    if (!selectedTask) return;
    const content = selectedTask.logOutput || selectedTask.errorMsg || '';
    const blob = new Blob([content], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `tantor-deployment-${selectedTask.id}.log`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  const activeStepIndex = DEPLOYMENT_STEPS.indexOf(selectedTask.currentStep || '');
  const isFailed = selectedTask.status === 'FAILED';
  const isSuccess = selectedTask.status === 'SUCCESS';
  
  const progressPercent = isSuccess 
    ? 100 
    : (activeStepIndex === -1 
      ? (isFailed ? 100 : 0) 
      : Math.round((activeStepIndex / DEPLOYMENT_STEPS.length) * 100));

  return (
    <div className="deployment-log-view animate-fade-in">
      <div className="deployment-log-toolbar">
        <div>
          <strong>Task output</strong>
          <span>{tasks.length} task{tasks.length === 1 ? '' : 's'} recorded</span>
        </div>
        <div className="deployment-log-actions">
          <button onClick={() => navigator.clipboard.writeText(selectedTask.logOutput || selectedTask.errorMsg || '')} title="Copy selected logs"><Copy size={14} /> Copy</button>
          <button onClick={fetchTasks} disabled={loading}><RefreshCw size={14} className={loading ? 'spin' : ''} /> Refresh</button>
        </div>
      </div>

      <div className="deployment-task-picker">
        <label htmlFor="deployment-task">Task</label>
        <select id="deployment-task" value={selectedTask.id} onChange={event => setSelectedTaskId(event.target.value)}>
          {tasks.map(task => (
            <option key={task.id} value={task.id}>
              {task.command} · {task.hostId} · {task.status}
            </option>
          ))}
        </select>
      </div>

      <div className="deployment-task-summary">
        <div><span>Status</span><strong className={`log-status ${selectedTask.status.toLowerCase()}`}>{statusIcon(selectedTask.status)} {selectedTask.status}</strong></div>
        <div><span>Host</span><strong><Server size={14} /> {selectedTask.hostId}</strong></div>
        <div><span>Started</span><strong>{new Date(selectedTask.createdAt).toLocaleString()}</strong></div>
        <div><span>Updated</span><strong>{new Date(selectedTask.updatedAt).toLocaleString()}</strong></div>
      </div>

      {isFailed && selectedTask.command === 'INSTALL_KAFKA' && (
        <div className="deployment-action-bar">
           <button className="btn-primary" onClick={handleRetry} disabled={actionLoading}>
             {actionLoading ? <Loader2 size={16} className="spin" /> : <RefreshCw size={16} />}
             Retry
           </button>
           <button className="btn-secondary" onClick={handleResume} disabled={actionLoading}>
             {actionLoading ? <Loader2 size={16} className="spin" /> : <PlayCircle size={16} />}
             Resume
           </button>
           <button className="btn-warning" onClick={handleRollback} disabled={actionLoading}>
             {actionLoading ? <Loader2 size={16} className="spin" /> : <RotateCcw size={16} />}
             Rollback
           </button>
           <button className="btn-danger" onClick={handleCleanup} disabled={actionLoading}>
             {actionLoading ? <Loader2 size={16} className="spin" /> : <Trash2 size={16} />}
             Cleanup
           </button>
           <button className="btn-secondary" onClick={handleDownloadLogs}>
             <Download size={16} /> Download Logs
           </button>
        </div>
      )}

      {selectedTask.errorMsg && (
        <div className="deployment-task-error">
          <strong>What happened</strong>
          <span>{friendlyFailure(selectedTask)}</span>
          {selectedTask.currentStep && <small>Failed step: {selectedTask.currentStep}</small>}
          <details>
            <summary>Technical error</summary>
            <pre>{selectedTask.errorMsg}</pre>
          </details>
        </div>
      )}

      <div className="deployment-layout-split">
        {selectedTask.command === 'INSTALL_KAFKA' && (
          <div className="deployment-steps-panel">
            <h3>Deployment Steps</h3>
            <div className="steps-list">
              {DEPLOYMENT_STEPS.map((step, idx) => {
                let stepState = 'pending'; // pending, running, completed, failed
                
                if (isSuccess) {
                  stepState = 'completed';
                } else if (isFailed) {
                  if (activeStepIndex === idx) stepState = 'failed';
                  else if (idx < activeStepIndex || (activeStepIndex === -1 && stepLogsObj[step])) stepState = 'completed';
                } else {
                  if (activeStepIndex === idx) stepState = 'running';
                  else if (idx < activeStepIndex) stepState = 'completed';
                }

                return (
                  <div key={step} className={`step-item ${stepState}`}>
                    <div className="step-icon">
                      {stepState === 'completed' && <CheckCircle2 size={16} />}
                      {stepState === 'running' && <Loader2 size={16} className="spin" />}
                      {stepState === 'failed' && <XCircle size={16} />}
                      {stepState === 'pending' && <div className="step-dot" />}
                    </div>
                    <div className="step-name">{step}</div>
                  </div>
                );
              })}
            </div>
          </div>
        )}

        <div className="deployment-console" style={{ flex: 1, minHeight: 400, position: 'relative', display: 'flex', flexDirection: 'column' }}>
          <div className="deployment-console-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 15px', background: '#2d2d2d', color: '#fff', borderTopLeftRadius: '6px', borderTopRightRadius: '6px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <Terminal size={14} />
              <span>{showLiveLogs ? `Live Logs ${selectedTask.currentStep ? `(${selectedTask.currentStep})` : ''}` : 'Deployment Progress'}</span>
            </div>
            <div style={{ position: 'relative' }}>
              <button className="btn btn-ghost icon-only" style={{ color: '#fff', background: 'none', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center' }} onClick={() => setOpenLogsMenu(!openLogsMenu)}>
                <MoreVertical size={16} />
              </button>
              {openLogsMenu && (
                <div className="logs-action-menu" style={{ position: 'absolute', top: '100%', right: 0, background: '#fff', border: '1px solid #eaeaea', borderRadius: '4px', boxShadow: '0 4px 12px rgba(0,0,0,0.2)', padding: '4px 0', zIndex: 10, minWidth: '160px' }}>
                  <button style={{ width: '100%', padding: '8px 16px', background: 'none', border: 'none', textAlign: 'left', cursor: 'pointer', color: '#333' }} onClick={() => { setShowLiveLogs(!showLiveLogs); setOpenLogsMenu(false); }}>
                    {showLiveLogs ? 'Show Progress Bar' : 'Show Live Logs'}
                  </button>
                </div>
              )}
            </div>
          </div>
          
          {!showLiveLogs ? (
            <div className="progress-content" style={{ flex: 1, padding: '40px', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', background: '#fff', border: '1px solid #eaeaea', borderTop: 'none', borderBottomLeftRadius: '6px', borderBottomRightRadius: '6px' }}>
              <h3 style={{ margin: '0 0 20px', color: '#333', fontSize: '18px' }}>
                {isSuccess ? 'Deployment Complete' : (isFailed ? 'Deployment Finished with Errors' : 'Deployment in Progress...')}
              </h3>
              <div className="progress-bar-bg" style={{ width: '100%', maxWidth: '500px', height: '12px', background: '#eaeaea', borderRadius: '6px', overflow: 'hidden', marginBottom: '15px' }}>
                <div className="progress-bar-fill" style={{ width: `${progressPercent}%`, height: '100%', background: isFailed ? '#dc3545' : '#4c6fff', transition: 'width 0.5s ease-in-out' }} />
              </div>
              <div className="progress-stats" style={{ color: '#666', fontSize: '14px', fontWeight: 500 }}>
                {progressPercent}% Complete
              </div>
            </div>
          ) : (
            <div className="deployment-console-body" ref={logBodyRef} style={{ flex: 1 }}>
              <pre>
                {selectedTask.currentStep && stepLogsObj[selectedTask.currentStep]
                  ? stepLogsObj[selectedTask.currentStep]
                  : (selectedTask.logOutput || 'Waiting for the agent to report output...')}
              </pre>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
