import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import {
  Server, Cpu, Activity,
  AlertCircle, CheckCircle2, XCircle,
  Database, Share2, Search
} from 'lucide-react';
import './Brokers.css';

type RoleFilter = 'all' | 'broker' | 'controller' | 'broker_controller';

interface Broker {
  brokerId: number;
  hostname: string;
  role: string;
  brokerHealth: string; // HEALTHY | DEGRADED | OFFLINE
  controller: boolean;
  jmxReachable: boolean;
  cpuUsagePct: number;
  memoryUsedMb: number;
  memoryTotalMb: number;
  diskUsedGb: number;
  diskTotalGb: number;
  messagesInPerSec: number;
  bytesInPerSec: number;
  lastHeartbeat: string;
}

export function Brokers() {
  const { id } = useParams<{ id: string }>();
  const [brokers, setBrokers] = useState<Broker[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [sortField, setSortField] = useState<keyof Broker>('brokerId');
  const [sortOrder, setSortOrder] = useState<'asc' | 'desc'>('asc');
  const [roleFilter, setRoleFilter] = useState<RoleFilter>('all');
  const [search, setSearch] = useState('');

  const fetchBrokers = async () => {
    try {
      const res = await fetch(`/api/v1/clusters/${id}/brokers`);
      if (!res.ok) throw new Error('Failed to fetch brokers');
      setBrokers(await res.json());
      setError(null);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchBrokers();
    const interval = setInterval(fetchBrokers, 10000);
    return () => clearInterval(interval);
  }, [id]);

  const handleSort = (field: keyof Broker) => {
    if (sortField === field) {
      setSortOrder(sortOrder === 'asc' ? 'desc' : 'asc');
    } else {
      setSortField(field);
      setSortOrder('asc');
    }
  };

  const sortIndicator = (field: keyof Broker) =>
    sortField === field ? (sortOrder === 'asc' ? ' ↑' : ' ↓') : '';

  const getHealthIcon = (health: string) => {
    switch (health) {
      case 'HEALTHY':
        return <AlertCircle style={{ color: '#E08E40' }} size={16} />;
      case 'DEGRADED':
        return <AlertCircle className="text-yellow" size={15} />;
      case 'OFFLINE':
        return <XCircle className="text-red" size={15} />;
      default:
        return <Server className="text-gray" size={15} />;
    }
  };

  const normalizeRole = (broker: Broker): Exclude<RoleFilter, 'all'> | 'unknown' => {
    const roleParts = String(broker.role || '')
      .toLowerCase()
      .split(/[_\s,+/-]+/)
      .filter(Boolean);
    const isBroker = roleParts.includes('broker');
    const isController = roleParts.includes('controller') || broker.controller;

    if (isBroker && isController) return 'broker_controller';
    if (isController) return 'controller';
    if (isBroker) return 'broker';
    return 'unknown';
  };

  const matchesRoleFilter = (broker: Broker, filter: RoleFilter) =>
    filter === 'all' || normalizeRole(broker) === filter;

  const formatRole = (role: string) => role
    .split(/[_-]+/)
    .filter(Boolean)
    .map(part => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' + ');

  const filteredBrokers = brokers
    .filter(b => matchesRoleFilter(b, roleFilter))
    .filter(b =>
      b.hostname.toLowerCase().includes(search.toLowerCase()) ||
      b.brokerId.toString().includes(search)
    )
    .sort((a, b) => {
      const aVal = a[sortField];
      const bVal = b[sortField];
      if (typeof aVal === 'string' && typeof bVal === 'string')
        return sortOrder === 'asc' ? aVal.localeCompare(bVal) : bVal.localeCompare(aVal);
      return sortOrder === 'asc'
        ? (aVal as number) - (bVal as number)
        : (bVal as number) - (aVal as number);
    });

  const agg = {
    totalMsgIn: brokers.reduce((s, b) => s + (b.messagesInPerSec || 0), 0),
    totalBytesIn: brokers.reduce((s, b) => s + (b.bytesInPerSec || 0), 0),
    avgCpu: brokers.reduce((s, b) => s + (b.cpuUsagePct || 0), 0) / (brokers.length || 1),
    offline: brokers.filter(b => b.brokerHealth === 'OFFLINE').length,
  };

  const formatBytes = (bytes: number) => {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  const ProgressBar = ({ value, max }: { value: number; max: number }) => {
    const pct = Math.min(100, Math.max(0, (value / max) * 100));
    return (
      <div
        className="progress-bar-container"
        title={`${value.toFixed(1)} / ${max.toFixed(1)}`}
      >
        <div className="progress-bar-fill" style={{ width: `${pct}%` }} />
      </div>
    );
  };

  if (loading && brokers.length === 0) {
    return <div className="state-center">Loading broker metrics…</div>;
  }

  return (
    <div className="brokers-dashboard animate-fade-in">

      {/* ── Overview KPI cards ── */}
      <div className="brokers-overview-gradient">
        <div className="metric-card figma-card">
          <div className="card-header">
            <span className="label">Total Ingestion</span>
          </div>
          <div className="card-body">
            <span className="value">{formatBytes(agg.totalBytesIn)}/s</span>
            <span className="subtext">{agg.totalMsgIn.toFixed(0)} msg/s</span>
          </div>
        </div>

        <div className="metric-card figma-card">
          <div className="card-header">
            <span className="label">Active Broker</span>
          </div>
          <div className="card-body">
            <span className="value">{brokers.length - agg.offline}/{brokers.length}</span>
            <span className="subtext">{agg.offline} Offline Nodes</span>
          </div>
        </div>

        <div className="metric-card figma-card">
          <div className="card-header">
            <span className="label">Avg. Cluster CPU</span>
          </div>
          <div className="card-body">
            <span className="value">{agg.avgCpu.toFixed(1)}%</span>
            <span className="subtext">0 Across Node</span>
          </div>
        </div>
      </div>

      {/* ── Controls ── */}
      <div className="brokers-list-header">
        <h2 className="brokers-list-title cluster-section-heading">Brokers List</h2>
        <div className="brokers-controls figma-controls">
          <div className="search-wrapper">
            <Search size={16} className="search-icon" />
            <input
              type="text"
              placeholder="Search hostname or ID..."
              value={search}
              onChange={e => setSearch(e.target.value)}
              className="search-input"
            />
          </div>
          <select
            value={roleFilter}
            onChange={event => setRoleFilter(event.target.value as RoleFilter)}
            className="role-select"
          >
            <option value="all">All Roles</option>
            <option value="broker">Broker Only</option>
            <option value="controller">Controller Only</option>
            <option value="broker_controller">Broker + Controller</option>
          </select>
        </div>
      </div>

      {/* ── Error ── */}
      {error && <div className="error-alert">{error}</div>}

      {/* ── Table ── */}
      <div className="brokers-table-container">
        <table className="data-table">
          <thead>
            <tr>
              <th onClick={() => handleSort('brokerId')} className="sortable">
                ID
              </th>
              <th onClick={() => handleSort('hostname')} className="sortable">
                Host Name
              </th>
              <th>Role</th>
              <th onClick={() => handleSort('cpuUsagePct')} className="sortable">
                CPU
              </th>
              <th onClick={() => handleSort('memoryUsedMb')} className="sortable">
                RAM
              </th>
              <th onClick={() => handleSort('diskUsedGb')} className="sortable">
                Disk
              </th>
              <th onClick={() => handleSort('messagesInPerSec')} className="sortable">
                Msg/S
              </th>
              <th>Last Update</th>
            </tr>
          </thead>
          <tbody>
            {filteredBrokers.map(broker => (
              <tr key={broker.brokerId}>

                {/* ID */}
                <td>
                  <div className="broker-id-cell">
                    {getHealthIcon(broker.brokerHealth)}
                    <span>{broker.brokerId}</span>
                    {broker.controller && (
                      <span className="controller-badge" title="Controller">C</span>
                    )}
                  </div>
                </td>

                {/* Hostname */}
                <td className="font-mono">{broker.hostname}</td>

                {/* Role */}
                <td>
                  <span className="role-badge">
                    {normalizeRole(broker) === 'broker_controller'
                      ? 'Broker + Controller'
                      : formatRole(broker.role)}
                  </span>
                </td>

                {/* CPU */}
                <td>
                  <div className="metric-cell figma-metric">
                    <span className="metric-val">{broker.cpuUsagePct.toFixed(1)}%</span>
                    <ProgressBar value={broker.cpuUsagePct} max={100} />
                  </div>
                </td>

                {/* RAM */}
                <td>
                  <div className="metric-cell figma-metric">
                    <span className="metric-val">
                      {formatBytes(broker.memoryUsedMb * 1024 * 1024)}
                    </span>
                    <ProgressBar value={broker.memoryUsedMb} max={broker.memoryTotalMb} />
                  </div>
                </td>

                {/* Disk */}
                <td>
                  <div className="metric-cell figma-metric">
                    <span className="metric-val">{broker.diskUsedGb} GB</span>
                    <ProgressBar value={broker.diskUsedGb} max={broker.diskTotalGb} />
                  </div>
                </td>

                {/* Msg/s */}
                <td className="font-mono">
                  {broker.messagesInPerSec ? broker.messagesInPerSec.toFixed(1) : '0'}
                </td>

                {/* Heartbeat */}
                <td className="text-muted text-sm">
                  {broker.lastHeartbeat ? new Date(broker.lastHeartbeat).toLocaleTimeString() : '-'}
                </td>

              </tr>
            ))}

            {filteredBrokers.length === 0 && (
              <tr>
                <td colSpan={8} className="text-center py-4 text-muted">
                  No brokers found matching criteria
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

    </div>
  );
}
