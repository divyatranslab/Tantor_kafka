import { useState, useEffect, useRef } from 'react';
import { useParams } from 'react-router-dom';
import { Terminal, Clock, CheckCircle, XCircle, Loader2, Server } from 'lucide-react';
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
  id: string;
  status: string;
}

export function DeploymentLogs() {
  const { id } = useParams<{ id: string }>();
  const [cluster, setCluster] = useState<ClusterInfo | null>(null);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const logsEndRef = useRef<HTMLDivElement>(null);

  const fetchTasks = async () => {
    try {
      // Also fetch cluster status to determine if we should stop polling
      const clusterRes = await fetch(`/api/v1/ui/clusters/${id}`);
      if (clusterRes.ok) {
        const clusterData = await clusterRes.json();
        setCluster(clusterData);
      }

      const tasksRes = await fetch(`/api/v1/ui/clusters/${id}/tasks`);
      if (tasksRes.ok) {
        const data = await tasksRes.json();
        setTasks(data);
        if (data.length > 0 && !selectedTaskId) {
          setSelectedTaskId(data[0].id);
        }
      }
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchTasks();
  }, [id]);

  useEffect(() => {
    if (!cluster) return;
    
    const activeStatuses = ['PENDING', 'RUNNING', 'VALIDATING', 'DELETING'];
    let interval: ReturnType<typeof setInterval>;
    
    if (activeStatuses.includes(cluster.status)) {
      interval = setInterval(() => {
        fetchTasks();
      }, 3000);
    }
    
    return () => {
      if (interval) clearInterval(interval);
    };
  }, [cluster, id]);

  // Auto scroll logs
  useEffect(() => {
    if (logsEndRef.current) {
      logsEndRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  }, [tasks, selectedTaskId]);

  if (loading && tasks.length === 0) {
    return <div className="state-center"><Loader2 className="spin" /> Loading tasks...</div>;
  }

  if (tasks.length === 0) {
    return (
      <div className="empty-state">
        <Terminal size={48} />
        <h3>No Deployment Logs</h3>
        <p>There are no tasks executed for this cluster yet.</p>
      </div>
    );
  }

  const selectedTask = tasks.find(t => t.id === selectedTaskId) || tasks[0];

  const getStatusIcon = (status: string) => {
    switch (status) {
      case 'SUCCESS': return <CheckCircle size={16} className="text-green" />;
      case 'FAILED': return <XCircle size={16} className="text-red" />;
      case 'RUNNING':
      case 'VALIDATING':
      case 'PENDING': return <Loader2 size={16} className="spin text-blue" />;
      default: return <Clock size={16} className="text-gray" />;
    }
  };

  return (
    <div className="deployment-logs-container animate-fade-in">
      <div className="task-sidebar">
        <h3>Task Timeline</h3>
        <div className="task-list">
          {tasks.map(task => (
            <div 
              key={task.id} 
              className={`task-item ${task.id === selectedTaskId ? 'active' : ''} ${task.status === 'FAILED' ? 'failed' : ''}`}
              onClick={() => setSelectedTaskId(task.id)}
            >
              <div className="task-item-header">
                <span className="task-command">{task.command}</span>
                {getStatusIcon(task.status)}
              </div>
              <div className="task-item-meta">
                <span><Server size={12} /> {task.hostId}</span>
                <span>{new Date(task.createdAt).toLocaleTimeString()}</span>
              </div>
            </div>
          ))}
        </div>
      </div>
      
      <div className="task-details-pane">
        <div className="task-summary glass-panel">
          <div className="summary-grid">
            <div className="summary-item">
              <label>Task Type</label>
              <div>{selectedTask.command}</div>
            </div>
            <div className="summary-item">
              <label>Status</label>
              <div className={`status-text ${selectedTask.status.toLowerCase()}`}>
                {getStatusIcon(selectedTask.status)} {selectedTask.status}
              </div>
            </div>
            <div className="summary-item">
              <label>Host</label>
              <div>{selectedTask.hostId}</div>
            </div>
            <div className="summary-item">
              <label>Started</label>
              <div>{new Date(selectedTask.createdAt).toLocaleString()}</div>
            </div>
            <div className="summary-item">
              <label>Completed</label>
              <div>
                {selectedTask.status === 'SUCCESS' || selectedTask.status === 'FAILED' 
                  ? new Date(selectedTask.updatedAt).toLocaleString() 
                  : '-'}
              </div>
            </div>
          </div>
          
          {selectedTask.errorMsg && (
            <div className="task-error-alert">
              <strong>Error:</strong> {selectedTask.errorMsg}
            </div>
          )}
        </div>
        
        <div className="terminal-container">
          <div className="terminal-header">
            <div className="terminal-dots">
              <span></span><span></span><span></span>
            </div>
            <div className="terminal-title">Log Output</div>
          </div>
          <div className="terminal-body">
            <pre>
              {selectedTask.logOutput || "Waiting for logs..."}
            </pre>
            <div ref={logsEndRef} />
          </div>
        </div>
      </div>
    </div>
  );
}
