import { useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { CheckCircle2, Clock, Copy, Loader2, RefreshCw, Server, Terminal, XCircle } from 'lucide-react';
import './DeploymentLogs.css';

interface Task {
  id: string;
  hostId: string;
  command: string;
  status: string;
  logOutput: string;
  errorMsg: string;
  createdAt: string;
  updatedAt: string;
}

interface ClusterInfo {
  status: string;
}

const activeStatus = (status: string) => ['PENDING', 'RUNNING', 'VALIDATING', 'IN_PROGRESS'].includes(String(status).toUpperCase());

export function DeploymentLogs() {
  const { id } = useParams<{ id: string }>();
  const [cluster, setCluster] = useState<ClusterInfo | null>(null);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [selectedTaskId, setSelectedTaskId] = useState('');
  const [loading, setLoading] = useState(true);
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
  }, [selectedTask?.logOutput]);

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

  const copyLogs = async () => {
    await navigator.clipboard.writeText(selectedTask.logOutput || selectedTask.errorMsg || '');
  };

  return (
    <div className="deployment-log-view animate-fade-in">
      <div className="deployment-log-toolbar">
        <div>
          <strong>Task output</strong>
          <span>{tasks.length} task{tasks.length === 1 ? '' : 's'} recorded</span>
        </div>
        <div className="deployment-log-actions">
          <button onClick={copyLogs} title="Copy selected logs"><Copy size={14} /> Copy</button>
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

      {selectedTask.errorMsg && <div className="deployment-task-error"><strong>Error</strong><span>{selectedTask.errorMsg}</span></div>}

      <div className="deployment-console">
        <div className="deployment-console-header"><Terminal size={14} /><span>logs</span></div>
        <div className="deployment-console-body" ref={logBodyRef}>
          <pre>{selectedTask.logOutput || 'Waiting for the agent to report output...'}</pre>
        </div>
      </div>
    </div>
  );
}
