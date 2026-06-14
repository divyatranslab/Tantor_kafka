import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { Server, Cpu, HardDrive, Activity, AlertCircle, CheckCircle2, XCircle } from 'lucide-react';
import './Brokers.css';

interface Broker {
  brokerId: number;
  hostname: string;
  role: string;
  brokerHealth: string; // HEALTHY, DEGRADED, OFFLINE
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
  const [roleFilter, setRoleFilter] = useState<string>('All');
  const [search, setSearch] = useState('');

  const fetchBrokers = async () => {
    try {
      const res = await fetch(`/api/v1/ui/clusters/${id}/brokers`);
      if (!res.ok) throw new Error('Failed to fetch broker metrics');
      const data = await res.json();
      setBrokers(data.brokers || []);
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

  const getHealthIcon = (health: string) => {
    switch (health) {
      case 'HEALTHY': return <CheckCircle2 className="text-green" size={16} title="Healthy: Heartbeat OK & JMX Reachable" />;
      case 'DEGRADED': return <AlertCircle className="text-yellow" size={16} title="Degraded: Heartbeat OK but JMX Unreachable" />;
      case 'OFFLINE': return <XCircle className="text-red" size={16} title="Offline: Heartbeat Missing" />;
      default: return <Server className="text-gray" size={16} />;
    }
  };

  const filteredBrokers = brokers
    .filter(b => roleFilter === 'All' || b.role.includes(roleFilter.toLowerCase()))
    .filter(b => b.hostname.toLowerCase().includes(search.toLowerCase()) || b.brokerId.toString().includes(search))
    .sort((a, b) => {
      let aVal = a[sortField];
      let bVal = b[sortField];
      
      if (typeof aVal === 'string' && typeof bVal === 'string') {
        return sortOrder === 'asc' ? aVal.localeCompare(bVal) : bVal.localeCompare(aVal);
      }
      
      return sortOrder === 'asc' ? (aVal as number) - (bVal as number) : (bVal as number) - (aVal as number);
    });

  const aggregateMetrics = {
    totalMsgIn: brokers.reduce((sum, b) => sum + (b.messagesInPerSec || 0), 0),
    totalBytesIn: brokers.reduce((sum, b) => sum + (b.bytesInPerSec || 0), 0),
    totalCpu: brokers.reduce((sum, b) => sum + (b.cpuUsagePct || 0), 0) / (brokers.length || 1),
    offlineCount: brokers.filter(b => b.brokerHealth === 'OFFLINE').length
  };

  const formatBytes = (bytes: number) => {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  const ProgressBar = ({ value, max }: { value: number, max: number }) => {
    const pct = Math.min(100, Math.max(0, (value / max) * 100));
    let colorClass = 'bg-blue';
    if (pct > 80) colorClass = 'bg-red';
    else if (pct > 60) colorClass = 'bg-yellow';
    
    return (
      <div className="progress-bar-container" title={`${value.toFixed(1)} / ${max.toFixed(1)}`}>
        <div className={`progress-bar-fill ${colorClass}`} style={{ width: `${pct}%` }}></div>
      </div>
    );
  };

  if (loading && brokers.length === 0) {
    return <div className="state-center">Loading Broker metrics...</div>;
  }

  return (
    <div className="brokers-dashboard animate-fade-in">
      <div className="brokers-overview glass-panel">
        <div className="metric-card">
          <Activity className="metric-icon blue" />
          <div className="metric-info">
            <span className="label">Total Ingestion</span>
            <span className="value">{formatBytes(aggregateMetrics.totalBytesIn)}/s</span>
            <span className="subtext">{aggregateMetrics.totalMsgIn.toFixed(0)} msg/s</span>
          </div>
        </div>
        <div className="metric-card">
          <Server className="metric-icon green" />
          <div className="metric-info">
            <span className="label">Active Brokers</span>
            <span className="value">{brokers.length - aggregateMetrics.offlineCount} / {brokers.length}</span>
            <span className="subtext">{aggregateMetrics.offlineCount} offline nodes</span>
          </div>
        </div>
        <div className="metric-card">
          <Cpu className="metric-icon purple" />
          <div className="metric-info">
            <span className="label">Avg Cluster CPU</span>
            <span className="value">{aggregateMetrics.totalCpu.toFixed(1)}%</span>
            <span className="subtext">Across {brokers.length} nodes</span>
          </div>
        </div>
      </div>

      <div className="brokers-controls glass-panel">
        <input 
          type="text" 
          placeholder="Search hostname or ID..." 
          value={search}
          onChange={e => setSearch(e.target.value)}
          className="search-input"
        />
        <select value={roleFilter} onChange={e => setRoleFilter(e.target.value)}>
          <option value="All">All Roles</option>
          <option value="Broker">Broker Only</option>
          <option value="Controller">Controller Only</option>
        </select>
      </div>

      {error && <div className="error-alert">{error}</div>}

      <div className="brokers-table-container glass-panel">
        <table className="data-table">
          <thead>
            <tr>
              <th onClick={() => handleSort('brokerId')} className="sortable">
                ID {sortField === 'brokerId' && (sortOrder === 'asc' ? '↑' : '↓')}
              </th>
              <th onClick={() => handleSort('hostname')} className="sortable">
                Hostname {sortField === 'hostname' && (sortOrder === 'asc' ? '↑' : '↓')}
              </th>
              <th>Role</th>
              <th onClick={() => handleSort('cpuUsagePct')} className="sortable">
                CPU {sortField === 'cpuUsagePct' && (sortOrder === 'asc' ? '↑' : '↓')}
              </th>
              <th onClick={() => handleSort('memoryUsedMb')} className="sortable">
                RAM {sortField === 'memoryUsedMb' && (sortOrder === 'asc' ? '↑' : '↓')}
              </th>
              <th onClick={() => handleSort('diskUsedGb')} className="sortable">
                Disk {sortField === 'diskUsedGb' && (sortOrder === 'asc' ? '↑' : '↓')}
              </th>
              <th onClick={() => handleSort('messagesInPerSec')} className="sortable">
                Msg/Sec {sortField === 'messagesInPerSec' && (sortOrder === 'asc' ? '↑' : '↓')}
              </th>
              <th>Heartbeat</th>
            </tr>
          </thead>
          <tbody>
            {filteredBrokers.map(broker => (
              <tr key={broker.brokerId}>
                <td>
                  <div className="broker-id-cell">
                    {getHealthIcon(broker.brokerHealth)}
                    <span>{broker.brokerId}</span>
                    {broker.controller && <span className="controller-badge">C</span>}
                  </div>
                </td>
                <td className="font-mono">{broker.hostname}</td>
                <td><span className="role-badge">{broker.role}</span></td>
                <td>
                  <div className="metric-cell">
                    <span className="metric-val">{broker.cpuUsagePct.toFixed(1)}%</span>
                    <ProgressBar value={broker.cpuUsagePct} max={100} />
                  </div>
                </td>
                <td>
                  <div className="metric-cell">
                    <span className="metric-val">{formatBytes(broker.memoryUsedMb * 1024 * 1024)}</span>
                    <ProgressBar value={broker.memoryUsedMb} max={broker.memoryTotalMb} />
                  </div>
                </td>
                <td>
                  <div className="metric-cell">
                    <span className="metric-val">{broker.diskUsedGb} GB</span>
                    <ProgressBar value={broker.diskUsedGb} max={broker.diskTotalGb} />
                  </div>
                </td>
                <td className="font-mono">
                  {broker.messagesInPerSec ? broker.messagesInPerSec.toFixed(1) : '0'}
                </td>
                <td className="text-muted text-sm">
                  {new Date(broker.lastHeartbeat).toLocaleTimeString()}
                </td>
              </tr>
            ))}
            {filteredBrokers.length === 0 && (
              <tr>
                <td colSpan={8} className="text-center py-4">No brokers found matching criteria</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
