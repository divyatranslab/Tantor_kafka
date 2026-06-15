import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import { Sidebar } from './components/Sidebar';
import { Dashboard } from './pages/Dashboard';
import { Hosts } from './pages/Hosts';
import { Clusters } from './pages/Clusters';
import { Artifacts } from './pages/Artifacts';
import { Monitoring } from './pages/Monitoring';
import { Alerts } from './pages/Alerts';
import { AuditLogs } from './pages/AuditLogs';
import { ClusterDetails } from './pages/ClusterDetails';
import { Topics } from './pages/Topics';
import { Consumers } from './pages/Consumers';
import { ConfigEditor } from './pages/ConfigEditor';
import { Partitions } from './pages/Partitions';
import { ClusterActions } from './pages/ClusterActions';
import { DeploymentLogs } from './pages/DeploymentLogs';
import { Brokers } from './pages/Brokers';
import ClusterWizard from './components/ClusterWizard';
import { DataServices } from './pages/DataServices';
import './App.css';

function App() {
  return (
    <Router>
      <div className="app-container">
        <Sidebar />
        <main className="main-content">
          <Routes>
            <Route path="/" element={<Dashboard />} />
            <Route path="/hosts" element={<Hosts />} />
            <Route path="/clusters" element={<Clusters />} />
            <Route path="/clusters/new" element={<ClusterWizard />} />
            <Route path="/clusters/:id" element={<ClusterDetails />}>
              <Route path="brokers" element={<Brokers />} />
              <Route path="partitions" element={<Partitions />} />
              <Route path="topics" element={<Topics />} />
              <Route path="consumers" element={<Consumers />} />
              <Route path="config" element={<ConfigEditor />} />
              <Route path="actions" element={<ClusterActions />} />
              <Route path="logs" element={<DeploymentLogs />} />
            </Route>
            <Route path="/artifacts" element={<Artifacts />} />
            <Route path="/monitoring" element={<Monitoring />} />
            <Route path="/alerts" element={<Alerts />} />
            <Route path="/audit" element={<AuditLogs />} />
            <Route path="/services" element={<DataServices />} />
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
    </Router>
  );
}

export default App;
