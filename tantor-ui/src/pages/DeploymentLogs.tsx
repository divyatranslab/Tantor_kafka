import { useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { CheckCircle2, Clock, Copy, Loader2, RefreshCw, Server, Terminal, XCircle, RotateCcw, PlayCircle, Trash2, Download } from 'lucide-react';
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
          <strong>Error</strong>
          <span>{selectedTask.failedReason || selectedTask.errorMsg}</span>
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

        <div className="deployment-console" style={{ flex: 1, minHeight: 400 }}>
          <div className="deployment-console-header"><Terminal size={14} /><span>Live Logs {selectedTask.currentStep ? `(${selectedTask.currentStep})` : ''}</span></div>
          <div className="deployment-console-body" ref={logBodyRef}>
            <pre>
              {selectedTask.currentStep && stepLogsObj[selectedTask.currentStep]
                ? stepLogsObj[selectedTask.currentStep]
                : (selectedTask.logOutput || 'Waiting for the agent to report output...')}
            </pre>
          </div>
        </div>
      </div>
    </div>
  );
}
