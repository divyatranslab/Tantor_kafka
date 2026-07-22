import { useState, useEffect, lazy, Suspense } from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate, useParams, useNavigate } from 'react-router-dom';
import { Sidebar } from './components/Sidebar';
import { TopNavbar } from './components/TopNavbar';
import { GlobalConfirmDialog } from './components/ConfirmDialog';
import { ClusterProvider } from './contexts/ClusterContext';
import { usePermissions } from './hooks/usePermissions';
import { ProtectedRoute } from './components/ProtectedRoute';
import './App.css';

// Lazy loaded page components
const Dashboard = lazy(() => import('./pages/Dashboard').then(m => ({ default: m.Dashboard })));
const Hosts = lazy(() => import('./pages/Hosts').then(m => ({ default: m.Hosts })));
const Clusters = lazy(() => import('./pages/Clusters').then(m => ({ default: m.Clusters })));
const Artifacts = lazy(() => import('./pages/Artifacts').then(m => ({ default: m.Artifacts })));
const Monitoring = lazy(() => import('./pages/Monitoring').then(m => ({ default: m.Monitoring })));
const Alerts = lazy(() => import('./pages/Alerts').then(m => ({ default: m.Alerts })));
const AuditLogs = lazy(() => import('./pages/AuditLogs').then(m => ({ default: m.AuditLogs })));
const ClusterDetails = lazy(() => import('./pages/ClusterDetails').then(m => ({ default: m.ClusterDetails })));
const ClusterOverview = lazy(() => import('./pages/ClusterOverview').then(m => ({ default: m.ClusterOverview })));
const Topics = lazy(() => import('./pages/Topics').then(m => ({ default: m.Topics })));
const TopicDetails = lazy(() => import('./pages/TopicDetails').then(m => ({ default: m.TopicDetails })));
const Consumers = lazy(() => import('./pages/Consumers').then(m => ({ default: m.Consumers })));
const ConfigEditor = lazy(() => import('./pages/ConfigEditor').then(m => ({ default: m.ConfigEditor })));
const Partitions = lazy(() => import('./pages/Partitions').then(m => ({ default: m.Partitions })));
const ClusterActions = lazy(() => import('./pages/ClusterActions').then(m => ({ default: m.ClusterActions })));
const DeploymentLogs = lazy(() => import('./pages/DeploymentLogs').then(m => ({ default: m.DeploymentLogs })));
const Brokers = lazy(() => import('./pages/Brokers').then(m => ({ default: m.Brokers })));
const ExternalClusters = lazy(() => import('./pages/ExternalClusters').then(m => ({ default: m.ExternalClusters })));
const ClusterDeployment = lazy(() => import('./pages/ClusterDeployment').then(m => ({ default: m.ClusterDeployment })));
const LdapSettings = lazy(() => import('./pages/LdapSettings').then(m => ({ default: m.LdapSettings })));
const SchemaRegistry = lazy(() => import('./pages/SchemaRegistry').then(m => ({ default: m.SchemaRegistry })));
const KafkaConnect = lazy(() => import('./pages/KafkaConnect').then(m => ({ default: m.KafkaConnect })));
const UserManagement = lazy(() => import('./pages/UserManagement')); // UserManagement is exported as default
const ClusterNodes = lazy(() => import('./pages/ClusterNodes').then(m => ({ default: m.ClusterNodes })));
const JobsList = lazy(() => import('./pages/JobsList').then(m => ({ default: m.JobsList })));
const JobStatusPage = lazy(() => import('./pages/JobStatusPage').then(m => ({ default: m.JobStatusPage })));
const ClusterSecurity = lazy(() => import('./pages/ClusterSecurity').then(m => ({ default: m.ClusterSecurity })));

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
              <Suspense fallback={
                <div className="animate-fade-in glass-panel" style={{ padding: '2rem', textAlign: 'center', margin: '2rem' }}>
                  <h2>Loading...</h2>
                </div>
              }>
                <Routes>
                  <Route path="/" element={<Navigate to="/dashboard" replace />} />
                  <Route path="/login" element={<Navigate to="/dashboard" replace />} />
                  <Route path="/dashboard" element={<Dashboard />} />
                  <Route path="/hosts" element={<Hosts />} />
                <Route path="/clusters" element={<Clusters />} />
                <Route path="/cluster-deployment" element={
                  <ProtectedRoute><ClusterDeployment /></ProtectedRoute>
                } />
                <Route path="/external-clusters" element={
                  <ProtectedRoute><ExternalClusters /></ProtectedRoute>
                } />
                <Route path="/clusters/:id" element={<ClusterDetails />}>
                  <Route path="overview" element={<ClusterOverview />} />
                  <Route path="nodes" element={<ClusterNodes />} />
                  <Route path="brokers" element={<Brokers />} />
                  <Route path="partitions" element={<Partitions />} />
                  <Route path="topics" element={<Topics />} />
                  <Route path="topics/:topicName" element={<TopicDetails />} />
                  <Route path="consumers" element={<Consumers />} />
                  <Route path="config" element={
                    <ProtectedRoute><ConfigEditor /></ProtectedRoute>
                  } />
                  <Route path="actions" element={
                    <ProtectedRoute><ClusterActions /></ProtectedRoute>
                  } />
                  <Route path="logs" element={
                    <ProtectedRoute><DeploymentLogsGuard /></ProtectedRoute>
                  } />
                  <Route path="security" element={
                    <ProtectedRoute><ClusterSecurity /></ProtectedRoute>
                  } />
                  <Route path="schema-registry" element={<SchemaRegistry />} />
                  <Route path="kafka-connect" element={<KafkaConnect />} />
                </Route>
                <Route path="/artifacts" element={<Artifacts />} />
                <Route path="/jobs" element={
                  <ProtectedRoute><JobsList /></ProtectedRoute>
                } />
                <Route path="/jobs/:id" element={
                  <ProtectedRoute><JobStatusPage /></ProtectedRoute>
                } />
                <Route path="/monitoring" element={<Monitoring />} />
                <Route path="/alerts" element={<Alerts />} />
                <Route path="/audit" element={<AuditLogs />} />
                <Route path="/ldap-settings" element={
                  <ProtectedRoute><LdapSettings /></ProtectedRoute>
                } />
                <Route path="/user-management" element={
                  <ProtectedRoute><UserManagement /></ProtectedRoute>
                } />
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
              </Suspense>
            </main>
          </div>
          <GlobalConfirmDialog />
        </div>
    </Router>
    </ClusterProvider>
  );
}

export default App;
