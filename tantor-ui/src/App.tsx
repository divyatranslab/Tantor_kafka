import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import { Sidebar } from './components/Sidebar';
import { Dashboard } from './pages/Dashboard';
import { Hosts } from './pages/Hosts';
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
