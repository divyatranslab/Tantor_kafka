import { useState, useEffect } from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate, useParams, useNavigate } from 'react-router-dom';
import { Sidebar } from './components/Sidebar';
import { Dashboard } from './pages/Dashboard';
import { Hosts } from './pages/Hosts';
import { Clusters } from './pages/Clusters';
import { Artifacts } from './pages/Artifacts';
import { Monitoring } from './pages/Monitoring';
import { Alerts } from './pages/Alerts';
import { AuditLogs } from './pages/AuditLogs';
import { ClusterDetails } from './pages/ClusterDetails';
import { ClusterOverview } from './pages/ClusterOverview';
import { Topics } from './pages/Topics';
import { TopicDetails } from './pages/TopicDetails';
import { Consumers } from './pages/Consumers';
import { ConfigEditor } from './pages/ConfigEditor';
import { Partitions } from './pages/Partitions';
import { ClusterActions } from './pages/ClusterActions';
import { DeploymentLogs } from './pages/DeploymentLogs';
import { Brokers } from './pages/Brokers';
import { ExternalClusters } from './pages/ExternalClusters';
import { ClusterDeployment } from './pages/ClusterDeployment';
import { LdapSettings } from './pages/LdapSettings';
import { SchemaRegistry } from './pages/SchemaRegistry';
import { KafkaConnect } from './pages/KafkaConnect';
import UserManagement from './pages/UserManagement';
import { ClusterProvider } from './contexts/ClusterContext';
import { TopNavbar } from './components/TopNavbar';
import { ClusterNodes } from './pages/ClusterNodes';
import { JobsList } from './pages/JobsList';
import { JobStatusPage } from './pages/JobStatusPage';
import { ClusterSecurity } from './pages/ClusterSecurity';
import { usePermissions } from './hooks/usePermissions';
import './App.css';

/** Guard component: redirects External clusters away from Deployment Logs */
function DeploymentLogsGuard() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [checked, setChecked] = useState(false);
  const [allowed, setAllowed] = useState(false);

  useEffect(() => {
    if (!id) { setChecked(true); setAllowed(false); return; }
    fetch(`/api/v1/ui/clusters/${id}`)
      .then(res => res.json())
      .then(data => {
        if (data.mode === 'EXTERNAL') {
          navigate(`/clusters/${id}/overview`, { replace: true });
        } else {
          setAllowed(true);
        }
      })
      .catch(() => setAllowed(true))
      .finally(() => setChecked(true));
  }, [id, navigate]);

  if (!checked) return null;
  if (!allowed) return null;
  return <DeploymentLogs />;
}

function App() {
  const { isAdmin } = usePermissions();

  return (
    <ClusterProvider>
      <Router>
        <div className="app-container flex-col">
          <TopNavbar />
          <div className="app-body">
            <Sidebar />
            <main className="main-content">
              <Routes>
                <Route path="/" element={<Navigate to="/dashboard" replace />} />
                <Route path="/dashboard" element={<Dashboard />} />
                <Route path="/hosts" element={<Hosts />} />
              <Route path="/clusters" element={<Clusters />} />
              <Route path="/cluster-deployment" element={<ClusterDeployment />} />
              <Route path="/external-clusters" element={<ExternalClusters />} />
              <Route path="/clusters/:id" element={<ClusterDetails />}>
                <Route path="overview" element={<ClusterOverview />} />
                <Route path="nodes" element={<ClusterNodes />} />
                <Route path="brokers" element={<Brokers />} />
                <Route path="partitions" element={<Partitions />} />
                <Route path="topics" element={<Topics />} />
                <Route path="topics/:topicName" element={<TopicDetails />} />
                <Route path="consumers" element={<Consumers />} />
                <Route path="config" element={<ConfigEditor />} />
                <Route path="actions" element={<ClusterActions />} />
                <Route path="logs" element={<DeploymentLogsGuard />} />
                <Route path="security" element={<ClusterSecurity />} />
                <Route path="schema-registry" element={<SchemaRegistry />} />
                <Route path="kafka-connect" element={<KafkaConnect />} />
              </Route>
              <Route path="/artifacts" element={<Artifacts />} />
              <Route path="/jobs" element={<JobsList />} />
              <Route path="/jobs/:id" element={<JobStatusPage />} />
              <Route path="/monitoring" element={<Monitoring />} />
              <Route path="/alerts" element={<Alerts />} />
              <Route path="/audit" element={<AuditLogs />} />
              <Route path="/ldap-settings" element={<LdapSettings />} />
              <Route path="/user-management" element={isAdmin ? <UserManagement /> : <Navigate to="/dashboard" replace />} />
              {/* Fallback routes for pages not fully fleshed out yet */}
              <Route path="*" element={
                <div className="animate-fade-in glass-panel" style={{ padding: '2rem', textAlign: 'center' }}>
                  <h2>Page Under Construction</h2>
                  <p style={{ color: 'var(--text-secondary)', marginTop: '1rem' }}>
                    This view is being built in the background.
                  </p>
                </div>
              } />
            </Routes>
          </main>
        </div>
      </div>
    </Router>
    </ClusterProvider>
  );
}

export default App;
