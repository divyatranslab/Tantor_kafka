import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Activity, PlayCircle, AlertTriangle, CheckCircle, RefreshCw, XCircle } from 'lucide-react';
import './JobsList.css';

type Job = {
  id: string;
  type: string;
  status: string;
  requestedBy: string;
  startTime: string;
  endTime: string;
  createdAt: string;
};

export function JobsList() {
  const [jobs, setJobs] = useState<Job[]>([]);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  const fetchJobs = async () => {
    try {
      const res = await fetch('/api/v1/ui/jobs');
      if (res.ok) {
        const data = await res.json();
        setJobs(data);
      }
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchJobs();
    const interval = setInterval(fetchJobs, 5000);
    return () => clearInterval(interval);
  }, []);

  const getStatusIcon = (status: string) => {
    switch (status) {
      case 'SUCCESS': return <CheckCircle className="status-icon success" size={18} />;
      case 'FAILED': return <XCircle className="status-icon failed" size={18} />;
      case 'IN_PROGRESS': return <RefreshCw className="status-icon running spin" size={18} />;
      case 'PARTIAL_SUCCESS': return <AlertTriangle className="status-icon warning" size={18} />;
      default: return <PlayCircle className="status-icon pending" size={18} />;
    }
  };

  return (
    <div className="jobs-list-page animate-fade-in">
      <header className="page-header">
        <div>
          <h1><Activity size={28} /> Job History</h1>
          <p>Track all asynchronous operations in the cluster.</p>
        </div>
        <button className="btn btn-secondary" onClick={fetchJobs}>
          <RefreshCw size={16} /> Refresh
        </button>
      </header>

      {loading && jobs.length === 0 ? (
        <div className="loading-state">
          <RefreshCw className="spin" size={32} />
          <p>Loading jobs...</p>
        </div>
      ) : (
        <div className="glass-panel">
          <table className="jobs-table">
            <thead>
              <tr>
                <th>Job ID</th>
                <th>Job Type</th>
                <th>Requested By</th>
                <th>Status</th>
                <th>Started</th>
                <th>Ended</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {jobs.map(job => (
                <tr key={job.id}>
                  <td><code>{job.id.slice(0, 8)}</code></td>
                  <td className="job-type">{job.type.replace('_', ' ')}</td>
                  <td>{job.requestedBy || 'anonymous'}</td>
                  <td>
                    <div className="status-badge">
                      {getStatusIcon(job.status)}
                      <span>{job.status}</span>
                    </div>
                  </td>
                  <td>{job.startTime ? new Date(job.startTime).toLocaleString() : 'N/A'}</td>
                  <td>{job.endTime ? new Date(job.endTime).toLocaleString() : 'N/A'}</td>
                  <td>
                    <button className="btn btn-sm btn-primary" onClick={() => navigate(`/jobs/${job.id}`)}>
                      View Details
                    </button>
                  </td>
                </tr>
              ))}
              {jobs.length === 0 && (
                <tr>
                  <td colSpan={7} className="empty-state">No jobs found.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
