import { useState, useEffect } from 'react';
import {
  Shield, ShieldCheck, ShieldAlert, AlertTriangle, CheckCircle,
  XCircle, ChevronDown, ChevronUp, Loader2, Info,
} from 'lucide-react';

interface Cluster {
  id: string;
  name: string;
  state: string;
  kind?: string;
  kafka_version?: string;
}

interface Finding {
  id: string;
  category: string;
  check: string;
  severity: string;
  status: string;
  message: string;
  recommendation: string;
  details?: Record<string, unknown>;
}

interface CategorySummary {
  total: number;
  passed: number;
  failed: number;
  warnings: number;
  errors: number;
  score: number;
}

interface ScanResult {
  cluster_id: string;
  cluster_name: string;
  score: number;
  grade: string;
  total_checks: number;
  passed: number;
  failed: number;
  critical_issues: number;
  high_issues: number;
  findings: Finding[];
  summary: Record<string, CategorySummary>;
}

const SEVERITY_COLORS: Record<string, string> = {
  critical: 'badge-error',
  high: 'badge-warning',
  medium: 'badge-info',
  low: 'badge-neutral',
};

const STATUS_ICONS: Record<string, React.ReactNode> = {
  pass: <CheckCircle size={16} style={{ color: 'var(--color-success)' }} />,
  fail: <XCircle size={16} style={{ color: 'var(--color-error)' }} />,
  warning: <AlertTriangle size={16} style={{ color: 'var(--color-warning)' }} />,
  error: <Info size={16} style={{ color: 'var(--text-tertiary)' }} />,
};

const GRADE_COLORS: Record<string, string> = {
  A: 'var(--color-success)',
  B: 'var(--color-info)',
  C: 'var(--color-warning)',
  D: 'var(--color-warning)', // using orange-like color
  F: 'var(--color-error)',
};

export function SecurityScan() {
  const [clusters, setClusters] = useState<Cluster[]>([]);
  const [selectedCluster, setSelectedCluster] = useState<string>('');
  const [scanning, setScanning] = useState(false);
  const [result, setResult] = useState<ScanResult | null>(null);
  const [expandedCategories, setExpandedCategories] = useState<Set<string>>(new Set());
  const [expandedFindings, setExpandedFindings] = useState<Set<string>>(new Set());
  const [filterSeverity, setFilterSeverity] = useState<string>('all');
  const [filterStatus, setFilterStatus] = useState<string>('all');
  const [error, setError] = useState('');
  const admin = true; // Hardcoded true for now since auth is omitted in this context

  useEffect(() => {
    fetch('/api/v1/ui/clusters')
      .then(r => r.json())
      .then(data => {
        const scannable = data.filter((c: Cluster) => c.state === 'running' || (c.kind === 'external' && c.state === 'connected'));
        setClusters(scannable);
        if (scannable.length > 0) setSelectedCluster(scannable[0].id);
      })
      .catch(err => console.error("Failed to fetch clusters for scan", err));
  }, []);

  const runScan = async () => {
    if (!selectedCluster) return;
    setScanning(true);
    setError('');
    setResult(null);
    try {
      const res = await fetch(`/api/v1/security-scan/clusters/${selectedCluster}/scan`, { method: 'POST' });
      if (!res.ok) {
        const err = await res.json();
        throw new Error(err.detail || 'Scan failed');
      }
      const data = await res.json();
      setResult(data);
      setExpandedCategories(new Set(Object.keys(data.summary)));
    } catch (err: any) {
      setError(err.message || 'Scan failed');
    } finally {
      setScanning(false);
    }
  };

  const toggleCategory = (cat: string) => {
    setExpandedCategories(prev => {
      const next = new Set(prev);
      if (next.has(cat)) next.delete(cat); else next.add(cat);
      return next;
    });
  };

  const toggleFinding = (id: string) => {
    setExpandedFindings(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const filteredFindings = (category: string) => {
    if (!result) return [];
    return result.findings.filter(f => {
      if (f.category !== category) return false;
      if (filterSeverity !== 'all' && f.severity !== filterSeverity) return false;
      if (filterStatus !== 'all' && f.status !== filterStatus) return false;
      return true;
    });
  };

  return (
    <div className="migrated-page">
      <div className="page-header">
        <div>
          <h1 className="page-title">
            <Shield size={24} style={{ color: 'var(--accent-primary)' }} />
            Security Scanner
          </h1>
          <p className="page-subtitle">Vulnerability Assessment & Penetration Testing for Kafka</p>
        </div>
      </div>

      {/* Cluster selector + scan button */}
      <div className="migrated-card mb-6">
        <div className="flex-row gap-4" style={{ alignItems: 'flex-end' }}>
          <div className="form-group flex-1" style={{ marginBottom: 0 }}>
            <label className="form-label">Select Cluster</label>
            <select
              value={selectedCluster}
              onChange={e => setSelectedCluster(e.target.value)}
              className="form-select"
            >
              {clusters.length === 0 && <option value="">No scannable clusters</option>}
              {clusters.map(c => (
                <option key={c.id} value={c.id}>
                  {c.name} ({c.kind === 'external' ? 'external' : 'managed'}) - Kafka {c.kafka_version || 'unknown'}
                </option>
              ))}
            </select>
          </div>
          <div>
            <button
              onClick={runScan}
              disabled={scanning || !selectedCluster || !admin}
              className="btn btn-primary"
            >
              {scanning ? (
                <Loader2 size={16} className="animate-spin" />
              ) : (
                <ShieldCheck size={16} />
              )}
              {scanning ? 'Scanning...' : 'Run Security Scan'}
            </button>
          </div>
        </div>
        {!admin && (
          <p className="form-help" style={{ marginTop: '0.5rem' }}>Admin role required to run security scans.</p>
        )}
      </div>

      {error && (
        <div className="alert alert-error mb-4">{error}</div>
      )}

      {/* Scan Results */}
      {result && (
        <>
          {/* Score card */}
          <div className="flex-row gap-4 mb-6">
            <div className="stat-card flex-1" style={{ textAlign: 'center', borderColor: GRADE_COLORS[result.grade] || 'var(--border-default)', backgroundColor: `${GRADE_COLORS[result.grade]}1A` }}>
              <div className="stat-value" style={{ fontSize: '3rem', color: GRADE_COLORS[result.grade] || 'var(--text-primary)' }}>{result.grade}</div>
              <div className="stat-label">Security Grade</div>
              <div className="text-xs mt-1" style={{ color: 'var(--text-tertiary)' }}>{result.score}% score</div>
            </div>
            <div className="stat-card flex-1" style={{ textAlign: 'center' }}>
              <div className="stat-value" style={{ fontSize: '2rem' }}>{result.total_checks}</div>
              <div className="stat-label">Total Checks</div>
              <div className="text-xs mt-1" style={{ color: 'var(--text-secondary)' }}>
                <span style={{ color: 'var(--color-success)' }}>{result.passed} passed</span>
                {' · '}
                <span style={{ color: 'var(--color-error)' }}>{result.failed} failed</span>
              </div>
            </div>
            <div className="stat-card flex-1" style={{ textAlign: 'center' }}>
              <div className="stat-value" style={{ fontSize: '2rem', color: 'var(--color-error)' }}>{result.critical_issues}</div>
              <div className="stat-label">Critical Issues</div>
              <div className="text-xs mt-1" style={{ color: 'var(--text-tertiary)' }}>Requires immediate attention</div>
            </div>
            <div className="stat-card flex-1" style={{ textAlign: 'center' }}>
              <div className="stat-value" style={{ fontSize: '2rem', color: 'var(--color-warning)' }}>{result.high_issues}</div>
              <div className="stat-label">High Issues</div>
              <div className="text-xs mt-1" style={{ color: 'var(--text-tertiary)' }}>Should be addressed soon</div>
            </div>
          </div>

          {/* Filters */}
          <div className="flex-row gap-6 mb-6">
            <div className="flex-row gap-2" style={{ alignItems: 'center' }}>
              <span className="text-sm" style={{ color: 'var(--text-secondary)' }}>Severity:</span>
              {['all', 'critical', 'high', 'medium', 'low'].map(sev => (
                <button
                  key={sev}
                  onClick={() => setFilterSeverity(sev)}
                  className="btn text-xs"
                  style={{
                    padding: '0.25rem 0.5rem',
                    backgroundColor: filterSeverity === sev ? 'var(--color-info-light)' : 'var(--bg-surface)',
                    color: filterSeverity === sev ? 'var(--color-info)' : 'var(--text-secondary)',
                    borderColor: filterSeverity === sev ? 'rgba(24, 95, 165, 0.3)' : 'var(--border-default)'
                  }}
                >
                  {sev === 'all' ? 'All' : sev.charAt(0).toUpperCase() + sev.slice(1)}
                </button>
              ))}
            </div>
            <div className="flex-row gap-2" style={{ alignItems: 'center' }}>
              <span className="text-sm" style={{ color: 'var(--text-secondary)' }}>Status:</span>
              {['all', 'pass', 'fail', 'warning'].map(st => (
                <button
                  key={st}
                  onClick={() => setFilterStatus(st)}
                  className="btn text-xs"
                  style={{
                    padding: '0.25rem 0.5rem',
                    backgroundColor: filterStatus === st ? 'var(--color-info-light)' : 'var(--bg-surface)',
                    color: filterStatus === st ? 'var(--color-info)' : 'var(--text-secondary)',
                    borderColor: filterStatus === st ? 'rgba(24, 95, 165, 0.3)' : 'var(--border-default)'
                  }}
                >
                  {st === 'all' ? 'All' : st.charAt(0).toUpperCase() + st.slice(1)}
                </button>
              ))}
            </div>
          </div>

          {/* Category breakdowns */}
          <div className="flex-col gap-4">
            {Object.entries(result.summary).map(([category, summary]) => {
              const findings = filteredFindings(category);
              const isExpanded = expandedCategories.has(category);
              return (
                <div key={category} className="migrated-card" style={{ padding: 0, overflow: 'hidden' }}>
                  <button
                    onClick={() => toggleCategory(category)}
                    className="w-full flex-row justify-between"
                    style={{ padding: '1rem 1.25rem', backgroundColor: 'var(--bg-surface)', border: 'none', cursor: 'pointer', textAlign: 'left', transition: 'background-color 0.2s' }}
                    onMouseEnter={(e) => e.currentTarget.style.backgroundColor = 'var(--bg-raised)'}
                    onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'var(--bg-surface)'}
                  >
                    <div className="flex-row gap-3" style={{ alignItems: 'center' }}>
                      {summary.failed > 0 ? (
                        <ShieldAlert size={20} style={{ color: 'var(--color-error)' }} />
                      ) : (
                        <ShieldCheck size={20} style={{ color: 'var(--color-success)' }} />
                      )}
                      <span style={{ fontWeight: 600, color: 'var(--text-primary)' }}>{category}</span>
                      <span className="text-xs" style={{ color: 'var(--text-tertiary)' }}>
                        {summary.passed}/{summary.total} passed ({summary.score}%)
                      </span>
                    </div>
                    <div className="flex-row gap-3" style={{ alignItems: 'center' }}>
                      {summary.failed > 0 && (
                        <span className="badge badge-error" style={{ fontSize: '0.75rem', padding: '0.125rem 0.5rem' }}>
                          {summary.failed} failed
                        </span>
                      )}
                      {summary.warnings > 0 && (
                        <span className="badge badge-warning" style={{ fontSize: '0.75rem', padding: '0.125rem 0.5rem' }}>
                          {summary.warnings} warnings
                        </span>
                      )}
                      {isExpanded ? <ChevronUp size={16} style={{ color: 'var(--text-secondary)' }} /> : <ChevronDown size={16} style={{ color: 'var(--text-secondary)' }} />}
                    </div>
                  </button>

                  {isExpanded && (
                    <div style={{ borderTop: '1px solid var(--border-default)', backgroundColor: 'var(--bg-surface)' }}>
                      {findings.length === 0 ? (
                        <div style={{ padding: '1rem', fontSize: '0.875rem', color: 'var(--text-tertiary)', textAlign: 'center' }}>
                          No findings match current filters
                        </div>
                      ) : (
                        <div className="flex-col" style={{ gap: 0 }}>
                          {findings.map(finding => (
                            <div key={finding.id} style={{ padding: '1rem', borderBottom: '1px solid var(--border-default)' }}>
                              <div
                                className="flex-row gap-3 cursor-pointer"
                                style={{ alignItems: 'flex-start' }}
                                onClick={() => toggleFinding(finding.id)}
                              >
                                <div style={{ marginTop: '0.125rem' }}>
                                  {STATUS_ICONS[finding.status] || STATUS_ICONS.error}
                                </div>
                                <div className="flex-1" style={{ minWidth: 0 }}>
                                  <div className="flex-row gap-2" style={{ alignItems: 'center' }}>
                                    <span style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-primary)' }}>{finding.check}</span>
                                    <span className={`badge ${SEVERITY_COLORS[finding.severity] || 'badge-neutral'}`} style={{ fontSize: '10px', padding: '0.125rem 0.375rem' }}>
                                      {finding.severity.toUpperCase()}
                                    </span>
                                  </div>
                                  <p style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', margin: '0.25rem 0 0 0' }}>{finding.message}</p>
                                </div>
                                <div>
                                  {expandedFindings.has(finding.id) ? <ChevronUp size={14} style={{ color: 'var(--text-tertiary)' }} /> : <ChevronDown size={14} style={{ color: 'var(--text-tertiary)' }} />}
                                </div>
                              </div>

                              {expandedFindings.has(finding.id) && (
                                <div className="flex-col gap-2" style={{ marginTop: '0.75rem', marginLeft: '1.75rem' }}>
                                  <div className="alert alert-info" style={{ padding: '0.75rem' }}>
                                    <p style={{ fontSize: '0.75rem', fontWeight: 600, margin: '0 0 0.25rem 0' }}>Recommendation</p>
                                    <p style={{ fontSize: '0.75rem', margin: 0 }}>{finding.recommendation}</p>
                                  </div>
                                  {finding.details && (
                                    <div style={{ backgroundColor: 'var(--bg-raised)', border: '1px solid var(--border-default)', borderRadius: 'var(--radius-md)', padding: '0.75rem' }}>
                                      <p style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-secondary)', margin: '0 0 0.25rem 0' }}>Details</p>
                                      <pre className="font-mono" style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', margin: 0, whiteSpace: 'pre-wrap' }}>
                                        {JSON.stringify(finding.details, null, 2)}
                                      </pre>
                                    </div>
                                  )}
                                </div>
                              )}
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </>
      )}

      {/* Empty state */}
      {!result && !scanning && (
        <div className="empty-state migrated-card">
          <Shield size={40} style={{ opacity: 0.5 }} />
          <h3 style={{ fontSize: '1.125rem', fontWeight: 600, margin: 0, color: 'var(--text-primary)' }}>No Scan Results</h3>
          <p>
            Select a running managed cluster or connected external cluster and click "Run Security Scan" to assess your Kafka cluster's security posture.
          </p>
        </div>
      )}
    </div>
  );
}
