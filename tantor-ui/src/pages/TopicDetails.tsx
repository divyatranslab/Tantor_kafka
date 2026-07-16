import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  AlertTriangle, ArrowLeft, BarChart3, CheckCircle2, ChevronDown, ChevronRight,
  Clock3, Database, Edit3, Gauge, KeyRound, MessageSquare,
  MoreVertical, RefreshCw, RotateCcw, Save, Search, Send, Settings2,
  ShieldCheck, Trash2, Users, X
} from 'lucide-react';
import { usePermissions } from '../hooks/usePermissions';
import { CustomSelect } from '../components/CustomSelect';
import './TopicDetails.css';

type Tab = 'overview' | 'messages' | 'consumers' | 'settings' | 'statistics' | 'acls';

interface PartitionDetail {
  partition: number;
  leader: number | null;
  replicas: number[];
  inSyncReplicas: number[];
  underReplicated: boolean;
  firstOffset: number;
  nextOffset: number;
  messageCount: number;
}

interface TopicDetail {
  name: string;
  internal: boolean;
  partitionCount: number;
  replicationFactor: number;
  underReplicated: number;
  inSyncReplicas: number;
  totalReplicas: number;
  messageCount: number;
  storedBytes: number;
  segmentCount: number | null;
  cleanupPolicy: string;
  partitions: PartitionDetail[];
}

interface TopicMessage {
  partition: number;
  offset: number;
  timestamp: number;
  key: string | null;
  value: string | null;
  keySize: number;
  valueSize: number;
  headers: Record<string, string[]>;
}

interface MessageResponse {
  messages: TopicMessage[];
  count: number;
  bytes: number;
  elapsedMs: number;
}

interface ConsumerGroup {
  groupId: string;
  activeConsumers: number;
  lag: number;
  coordinator: string | null;
  state: string;
}

interface TopicConfig {
  name: string;
  value: string | null;
  defaultValue: string | null;
  source: string;
  readOnly: boolean;
  sensitive: boolean;
}

interface AclRow {
  principal: string;
  host: string;
  operation: string;
  permissionType: string;
  patternType: string;
  resourceName: string;
}

interface SizeStatistics {
  total: number;
  min: number;
  max: number;
  average: number;
  p50: number;
  p75: number;
  p95: number;
  p99: number;
  p999: number;
}

interface TopicStatistics {
  analyzedAt: number;
  sampleLimit: number;
  truncated: boolean;
  messageCount: number;
  minOffset: number;
  maxOffset: number;
  minTimestamp: number;
  maxTimestamp: number;
  nullKeys: number;
  uniqueKeys: number;
  nullValues: number;
  uniqueValues: number;
  keySize: SizeStatistics;
  valueSize: SizeStatistics;
  partitions: Array<{ partition: number; totalMessages: number; minOffset: number; maxOffset: number }>;
}

const tabs: Array<{ id: Tab; label: string; icon: typeof Gauge }> = [
  { id: 'overview', label: 'Overview', icon: Gauge },
  { id: 'messages', label: 'Messages', icon: MessageSquare },
  { id: 'consumers', label: 'Consumers', icon: Users },
  { id: 'settings', label: 'Settings', icon: Settings2 },
  { id: 'statistics', label: 'Statistics', icon: BarChart3 },
  { id: 'acls', label: 'ACLs', icon: ShieldCheck }
];

async function responseError(response: Response) {
  const body = await response.json().catch(() => null);
  return body?.message || body?.error || 'Request failed (HTTP ' + response.status + ')';
}

function formatBytes(value: number) {
  if (value < 0) return 'Unavailable';
  if (value < 1024) return value.toLocaleString() + ' B';
  const units = ['KB', 'MB', 'GB', 'TB'];
  let amount = value;
  let unit = -1;
  do {
    amount /= 1024;
    unit += 1;
  } while (amount >= 1024 && unit < units.length - 1);
  return amount.toLocaleString(undefined, { maximumFractionDigits: 1 }) + ' ' + units[unit];
}

function formatDate(value: number) {
  return value > 0 ? new Date(value).toLocaleString() : '—';
}

export function TopicDetails() {
  const { id, topicName: rawTopicName } = useParams<{ id: string; topicName: string }>();
  const topicName = rawTopicName ? decodeURIComponent(rawTopicName) : '';
  const navigate = useNavigate();
  const { canManage } = usePermissions();
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedTab = searchParams.get('tab') as Tab | null;
  const activeTab: Tab = tabs.some(tab => tab.id === requestedTab) ? requestedTab as Tab : 'overview';

  const [detail, setDetail] = useState<TopicDetail | null>(null);
  const [loadingDetail, setLoadingDetail] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionMenu, setActionMenu] = useState(false);
  const [confirmAction, setConfirmAction] = useState<'clear' | 'recreate' | 'remove' | null>(null);
  const [acting, setActing] = useState(false);
  const [showProduce, setShowProduce] = useState(false);
  const [producing, setProducing] = useState(false);
  const [produceForm, setProduceForm] = useState({ partition: '', key: '', value: '' });

  const [messages, setMessages] = useState<MessageResponse | null>(null);
  const [messagesLoading, setMessagesLoading] = useState(false);
  const [messageOrder, setMessageOrder] = useState('newest');
  const [messagePartition, setMessagePartition] = useState('');
  const [messageSearch, setMessageSearch] = useState('');
  const [expandedMessage, setExpandedMessage] = useState<string | null>(null);

  const [consumers, setConsumers] = useState<ConsumerGroup[]>([]);
  const [consumerSearch, setConsumerSearch] = useState('');
  const [configs, setConfigs] = useState<TopicConfig[]>([]);
  const [configSearch, setConfigSearch] = useState('');
  const [editingConfig, setEditingConfig] = useState<TopicConfig | null>(null);
  const [configValue, setConfigValue] = useState('');
  const [savingConfig, setSavingConfig] = useState(false);
  const [statistics, setStatistics] = useState<TopicStatistics | null>(null);
  const [statisticsLoading, setStatisticsLoading] = useState(false);
  const [acls, setAcls] = useState<AclRow[]>([]);
  const [tabLoading, setTabLoading] = useState(false);

  const [keyDeserializer, setKeyDeserializer] = useState('string');
  const [valueDeserializer, setValueDeserializer] = useState('string');

  const orderOptions = [
    { value: 'newest', label: 'Newest first' },
    { value: 'oldest', label: 'Oldest first' }
  ];

  const partitionOptions = useMemo(() => {
    const opts = [{ value: '', label: 'All partitions' }];
    if (detail?.partitions) {
      detail.partitions.forEach(p => {
        opts.push({ value: String(p.partition), label: `Partition ${p.partition}` });
      });
    }
    return opts;
  }, [detail]);

  const keyDeserializerOptions = [
    { value: 'string', label: 'Key: String' },
    { value: 'raw', label: 'Key: Raw UTF-8' }
  ];

  const valueDeserializerOptions = [
    { value: 'string', label: 'Value: String' },
    { value: 'raw', label: 'Value: Raw UTF-8' }
  ];

  const baseUrl = '/api/v1/clusters/' + id + '/topics/' + encodeURIComponent(topicName);

  const loadDetail = useCallback(async () => {
    if (!id || !topicName) return;
    setLoadingDetail(true);
    try {
      const response = await fetch(baseUrl);
      if (!response.ok) throw new Error(await responseError(response));
      setDetail(await response.json());
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : 'Failed to load topic');
    } finally {
      setLoadingDetail(false);
    }
  }, [baseUrl, id, topicName]);

  const loadMessages = useCallback(async () => {
    if (!id || !topicName) return;
    setMessagesLoading(true);
    setError(null);
    try {
      const params = new URLSearchParams({ order: messageOrder, limit: '100' });
      if (messagePartition) params.set('partitions', messagePartition);
      if (messageSearch.trim()) params.set('search', messageSearch.trim());
      const response = await fetch(baseUrl + '/messages?' + params);
      if (!response.ok) throw new Error(await responseError(response));
      setMessages(await response.json());
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : 'Failed to browse messages');
    } finally {
      setMessagesLoading(false);
    }
  }, [baseUrl, id, messageOrder, messagePartition, messageSearch, topicName]);

  const loadSimpleTab = useCallback(async (tab: 'consumers' | 'configs' | 'acls') => {
    setTabLoading(true);
    setError(null);
    try {
      const response = await fetch(baseUrl + '/' + tab);
      if (!response.ok) throw new Error(await responseError(response));
      const body = await response.json();
      if (tab === 'consumers') setConsumers(body);
      if (tab === 'configs') setConfigs(body);
      if (tab === 'acls') setAcls(body);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : 'Failed to load ' + tab);
    } finally {
      setTabLoading(false);
    }
  }, [baseUrl]);

  const loadStatistics = useCallback(async () => {
    setStatisticsLoading(true);
    setError(null);
    try {
      const response = await fetch(baseUrl + '/statistics?limit=10000');
      if (!response.ok) throw new Error(await responseError(response));
      setStatistics(await response.json());
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : 'Failed to analyze topic');
    } finally {
      setStatisticsLoading(false);
    }
  }, [baseUrl]);

  // eslint-disable-next-line react-hooks/set-state-in-effect -- fetch synchronizes Kafka data
  useEffect(() => { loadDetail(); }, [loadDetail]);
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- fetch synchronizes tab data
    if (activeTab === 'messages' && !messages) loadMessages();
    if (activeTab === 'consumers' && consumers.length === 0) loadSimpleTab('consumers');
    if (activeTab === 'settings' && configs.length === 0) loadSimpleTab('configs');
    if (activeTab === 'statistics' && !statistics) loadStatistics();
    if (activeTab === 'acls' && acls.length === 0) loadSimpleTab('acls');
  }, [activeTab, acls.length, configs.length, consumers.length, loadMessages, loadSimpleTab, loadStatistics, messages, statistics]);

  const changeTab = (tab: Tab) => setSearchParams(tab === 'overview' ? {} : { tab });

  const runAction = async () => {
    if (!canManage) return;
    if (!confirmAction) return;
    setActing(true);
    setError(null);
    try {
      let url = baseUrl;
      let method = 'DELETE';
      if (confirmAction === 'clear') url += '/messages';
      if (confirmAction === 'recreate') {
        url += '/recreate';
        method = 'POST';
      }
      const response = await fetch(url, { method });
      if (!response.ok) throw new Error(await responseError(response));
      if (confirmAction === 'remove') {
        navigate('/clusters/' + id + '/topics');
        return;
      }
      setConfirmAction(null);
      setMessages(null);
      setStatistics(null);
      await loadDetail();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : 'Topic action failed');
    } finally {
      setActing(false);
    }
  };

  const produceMessage = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!canManage) return;
    setProducing(true);
    setError(null);
    try {
      const response = await fetch(baseUrl + '/messages', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          partition: produceForm.partition === '' ? null : Number(produceForm.partition),
          key: produceForm.key === '' ? null : produceForm.key,
          value: produceForm.value,
          headers: {}
        })
      });
      if (!response.ok) throw new Error(await responseError(response));
      setShowProduce(false);
      setProduceForm({ partition: '', key: '', value: '' });
      setMessages(null);
      setStatistics(null);
      await loadDetail();
      if (activeTab === 'messages') await loadMessages();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : 'Failed to produce message');
    } finally {
      setProducing(false);
    }
  };

  const saveConfig = async () => {
    if (!canManage) return;
    if (!editingConfig) return;
    setSavingConfig(true);
    try {
      const response = await fetch(baseUrl + '/configs/' + encodeURIComponent(editingConfig.name), {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ value: configValue })
      });
      if (!response.ok) throw new Error(await responseError(response));
      setEditingConfig(null);
      await loadSimpleTab('configs');
      await loadDetail();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : 'Failed to update setting');
    } finally {
      setSavingConfig(false);
    }
  };

  const resetConfig = async (config: TopicConfig) => {
    if (!canManage) return;
    try {
      const response = await fetch(baseUrl + '/configs/' + encodeURIComponent(config.name), { method: 'DELETE' });
      if (!response.ok) throw new Error(await responseError(response));
      await loadSimpleTab('configs');
      await loadDetail();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : 'Failed to reset setting');
    }
  };

  const filteredConsumers = useMemo(() => consumers.filter(group =>
    group.groupId.toLowerCase().includes(consumerSearch.toLowerCase())), [consumerSearch, consumers]);
  const filteredConfigs = useMemo(() => configs.filter(config =>
    config.name.toLowerCase().includes(configSearch.toLowerCase())), [configSearch, configs]);

  if (loadingDetail && !detail) {
    return <div className="topic-detail-state"><RefreshCw className="spin" /> Loading topic…</div>;
  }

  if (!detail) {
    return <div className="topic-detail-state error"><AlertTriangle /> {error || 'Topic was not found.'}</div>;
  }

  return (
    <section className="topic-detail-page animate-fade-in">
      <div className="topic-detail-heading">
        <div>
          <button className="topic-back" onClick={() => navigate('/clusters/' + id + '/topics')}><ArrowLeft size={15} /> Topics</button>
          <div className="topic-detail-title">
            <div className="topic-resource-icon"><Database size={20} /></div>
            <div><p>Topic</p><h2>{detail.name}</h2></div>
            {detail.internal && <span className="topic-type-badge">Internal</span>}
          </div>
        </div>
        {canManage && <div className="topic-heading-actions">
          <button className="topic-detail-button primary" onClick={() => setShowProduce(true)}><Send size={16} /> Produce message</button>
          <div className="detail-menu-wrap">
            <button className="detail-icon-button" aria-label="Topic actions" onClick={() => setActionMenu(current => !current)}><MoreVertical size={19} /></button>
            {actionMenu && (
              <div className="detail-action-menu">
                <button onClick={() => { setConfirmAction('clear'); setActionMenu(false); }}>Clear messages</button>
                <button onClick={() => { setConfirmAction('recreate'); setActionMenu(false); }}>Recreate topic</button>
                <button onClick={() => { setConfirmAction('remove'); setActionMenu(false); }}>Remove topic</button>
              </div>
            )}
          </div>
        </div>}
      </div>

      <nav className="topic-detail-tabs" aria-label="Topic sections">
        {tabs.map(tab => (
          <button key={tab.id} className={activeTab === tab.id ? 'active' : ''} onClick={() => changeTab(tab.id)}>
            <tab.icon size={15} /> {tab.label}
          </button>
        ))}
      </nav>

      {error && <div className="detail-alert"><AlertTriangle size={17} /><span>{error}</span><button onClick={() => setError(null)}><X size={15} /></button></div>}

      <div className="topic-tab-content">
        {activeTab === 'overview' && <OverviewTab detail={detail} />}

        {activeTab === 'messages' && (
          <div className="messages-tab">
            <div className="message-toolbar">
              <CustomSelect
                value={messageOrder}
                onChange={setMessageOrder}
                options={orderOptions}
              />
              <CustomSelect
                value={messagePartition}
                onChange={setMessagePartition}
                options={partitionOptions}
              />
              <CustomSelect
                value={keyDeserializer}
                onChange={setKeyDeserializer}
                options={keyDeserializerOptions}
              />
              <CustomSelect
                value={valueDeserializer}
                onChange={setValueDeserializer}
                options={valueDeserializerOptions}
              />
              <button onClick={loadMessages} disabled={messagesLoading}><RefreshCw className={messagesLoading ? 'spin' : ''} size={15} /> Refresh</button>
              <label><Search size={16} /><input value={messageSearch} onChange={event => setMessageSearch(event.target.value)} onKeyDown={event => event.key === 'Enter' && loadMessages()} placeholder="Search key or value" /></label>
            </div>
            {messages && <div className="message-fetch-meta"><span><Clock3 size={13} /> {messages.elapsedMs} ms</span><span>{formatBytes(messages.bytes)}</span><span>{messages.count} messages consumed</span></div>}
            <div className="detail-table-wrap">
              <table className="detail-table messages-table">
                <thead><tr><th /><th>Offset</th><th>Partition</th><th>Timestamp</th><th>Key preview</th><th>Value preview</th></tr></thead>
                <tbody>
                  {messagesLoading && !messages ? <LoadingRow columns={6} /> : !messages?.messages.length ? <EmptyRow columns={6} text="No messages matched this request." /> : messages.messages.map(message => {
                    const rowId = message.partition + '-' + message.offset;
                    const expanded = expandedMessage === rowId;
                    return [
                      <tr key={rowId} onClick={() => setExpandedMessage(expanded ? null : rowId)}>
                        <td><button className="expand-button">{expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}</button></td>
                        <td>{message.offset}</td><td>{message.partition}</td><td>{formatDate(message.timestamp)}</td>
                        <td className="preview-cell">{message.key ?? <span className="null-value">null</span>}</td>
                        <td className="preview-cell">{message.value ?? <span className="null-value">null</span>}</td>
                      </tr>,
                      expanded && <tr className="message-expanded" key={rowId + '-expanded'}><td colSpan={6}>
                        <div><section><h4>Key · {formatBytes(message.keySize)}</h4><pre>{message.key ?? 'null'}</pre></section><section><h4>Value · {formatBytes(message.valueSize)}</h4><pre>{message.value ?? 'null'}</pre></section></div>
                        {Object.keys(message.headers).length > 0 && <section><h4>Headers</h4><pre>{JSON.stringify(message.headers, null, 2)}</pre></section>}
                      </td></tr>
                    ];
                  })}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {activeTab === 'consumers' && (
          <div>
            <div className="tab-toolbar"><label><Search size={16} /><input value={consumerSearch} onChange={event => setConsumerSearch(event.target.value)} placeholder="Search by consumer name" /></label><button onClick={() => loadSimpleTab('consumers')}><RefreshCw size={15} /> Refresh</button></div>
            <div className="detail-table-wrap"><table className="detail-table"><thead><tr><th>Consumer group ID</th><th>Active consumers</th><th>Consumer lag</th><th>Coordinator</th><th>State</th></tr></thead>
              <tbody>{tabLoading && consumers.length === 0 ? <LoadingRow columns={5} /> : filteredConsumers.length === 0 ? <EmptyRow columns={5} text="No consumer groups use this topic." /> : filteredConsumers.map(group => <tr key={group.groupId}><td><strong>{group.groupId}</strong></td><td>{group.activeConsumers}</td><td>{group.lag.toLocaleString()}</td><td>{group.coordinator || '—'}</td><td><span className={'state-pill ' + group.state.toLowerCase()}>{group.state}</span></td></tr>)}</tbody>
            </table></div>
          </div>
        )}

        {activeTab === 'settings' && (
          <div>
            <div className="tab-toolbar"><label><Search size={16} /><input value={configSearch} onChange={event => setConfigSearch(event.target.value)} placeholder="Search settings" /></label><span>{filteredConfigs.length} settings</span></div>
            <div className="detail-table-wrap"><table className="detail-table settings-table"><thead><tr><th>Key</th><th>Value</th><th>Default value</th><th>Source</th><th /></tr></thead>
              <tbody>{tabLoading && configs.length === 0 ? <LoadingRow columns={5} /> : filteredConfigs.map(config => <tr key={config.name}><td><strong>{config.name}</strong></td><td>{config.sensitive ? '••••••' : config.value ?? '—'}</td><td>{config.defaultValue ?? '—'}</td><td><span className="source-pill">{config.source.replaceAll('_', ' ')}</span></td><td className="setting-actions">{canManage && !config.readOnly && !config.sensitive && <><button title="Edit setting" onClick={() => { setEditingConfig(config); setConfigValue(config.value || ''); }}><Edit3 size={14} /></button>{config.source !== 'DEFAULT_CONFIG' && <button title="Reset to default" onClick={() => resetConfig(config)}><RotateCcw size={14} /></button>}</>}</td></tr>)}</tbody>
            </table></div>
          </div>
        )}

        {activeTab === 'statistics' && (
          <div className="statistics-tab">
            <div className="statistics-heading"><div><p>Message analysis</p><span>{statistics ? 'Analyzed ' + formatDate(statistics.analyzedAt) + (statistics.truncated ? ' · capped at ' + statistics.sampleLimit.toLocaleString() : '') : 'Scan a bounded sample from the topic.'}</span></div><button onClick={loadStatistics} disabled={statisticsLoading}><RefreshCw className={statisticsLoading ? 'spin' : ''} size={15} /> Restart analysis</button></div>
            {statisticsLoading && !statistics ? <div className="analysis-loading"><RefreshCw className="spin" /> Reading topic messages…</div> : statistics && <StatisticsView statistics={statistics} />}
          </div>
        )}

        {activeTab === 'acls' && (
          <div>
            <div className="tab-toolbar"><div><ShieldCheck size={17} /> Topic access control</div><button onClick={() => loadSimpleTab('acls')}><RefreshCw size={15} /> Refresh</button></div>
            <div className="detail-table-wrap"><table className="detail-table"><thead><tr><th>Principal</th><th>Host</th><th>Operation</th><th>Permission</th><th>Pattern</th></tr></thead>
              <tbody>{tabLoading && acls.length === 0 ? <LoadingRow columns={5} /> : acls.length === 0 ? <EmptyRow columns={5} text="No ACL entries match this topic." /> : acls.map((acl, index) => <tr key={acl.principal + acl.operation + index}><td><strong>{acl.principal}</strong></td><td>{acl.host}</td><td>{acl.operation}</td><td><span className={'permission-pill ' + acl.permissionType.toLowerCase()}>{acl.permissionType}</span></td><td>{acl.patternType} · {acl.resourceName}</td></tr>)}</tbody>
            </table></div>
          </div>
        )}
      </div>

      {canManage && showProduce && (
        <div className="detail-modal-backdrop" onMouseDown={() => setShowProduce(false)}>
          <div className="detail-modal produce-modal" onMouseDown={event => event.stopPropagation()}>
            <header><div><span>Write to topic</span><h3>Produce message</h3></div><button onClick={() => setShowProduce(false)}><X size={18} /></button></header>
            <form onSubmit={produceMessage}>
              <label>Partition<select value={produceForm.partition} onChange={event => setProduceForm(current => ({ ...current, partition: event.target.value }))}><option value="">Automatic</option>{detail.partitions.map(partition => <option key={partition.partition} value={partition.partition}>Partition {partition.partition}</option>)}</select></label>
              <label>Key <span className="optional">optional</span><textarea rows={3} value={produceForm.key} onChange={event => setProduceForm(current => ({ ...current, key: event.target.value }))} placeholder="Message key" /></label>
              <label>Value<textarea rows={7} required value={produceForm.value} onChange={event => setProduceForm(current => ({ ...current, value: event.target.value }))} placeholder="Message value" /></label>
              <footer><button className="primary" disabled={producing}><Send size={15} /> {producing ? 'Producing…' : 'Produce message'}</button><button type="button" onClick={() => setShowProduce(false)}>Cancel</button></footer>
            </form>
          </div>
        </div>
      )}

      {canManage && confirmAction && (
        <div className="detail-modal-backdrop" onMouseDown={() => !acting && setConfirmAction(null)}>
          <div className="detail-modal confirm-modal" onMouseDown={event => event.stopPropagation()}>
            <div className="confirm-warning"><AlertTriangle size={22} /></div>
            <h3>{confirmAction === 'clear' ? 'Clear all messages?' : confirmAction === 'recreate' ? 'Recreate this topic?' : 'Remove this topic?'}</h3>
            <p>{confirmAction === 'clear' ? 'Every currently readable record will become inaccessible.' : confirmAction === 'recreate' ? 'All messages will be deleted. Partition assignments and explicit settings will be restored.' : 'The topic and all of its data will be permanently deleted.'}</p>
            <code>{detail.name}</code>
            <footer><button onClick={() => setConfirmAction(null)} disabled={acting}>Cancel</button><button className="danger" onClick={runAction} disabled={acting}>{acting ? 'Working…' : confirmAction === 'clear' ? 'Clear messages' : confirmAction === 'recreate' ? 'Recreate topic' : 'Remove topic'}</button></footer>
          </div>
        </div>
      )}

      {canManage && editingConfig && (
        <div className="detail-modal-backdrop" onMouseDown={() => setEditingConfig(null)}>
          <div className="detail-modal config-modal" onMouseDown={event => event.stopPropagation()}>
            <header><div><span>Topic setting</span><h3>{editingConfig.name}</h3></div><button onClick={() => setEditingConfig(null)}><X size={18} /></button></header>
            <div className="config-edit-body"><label>Value<input autoFocus value={configValue} onChange={event => setConfigValue(event.target.value)} /></label><p>Default: {editingConfig.defaultValue ?? 'not defined'}</p></div>
            <footer><button onClick={() => setEditingConfig(null)}>Cancel</button><button className="primary" onClick={saveConfig} disabled={savingConfig}><Save size={15} /> {savingConfig ? 'Saving…' : 'Save setting'}</button></footer>
          </div>
        </div>
      )}
    </section>
  );
}

function OverviewTab({ detail }: { detail: TopicDetail }) {
  const cards = [
    ['Partitions', detail.partitionCount.toLocaleString(), Database],
    ['Replication factor', detail.replicationFactor.toLocaleString(), CopyIcon],
    ['Under-replicated', detail.underReplicated.toLocaleString(), AlertTriangle],
    ['In-sync replicas', detail.inSyncReplicas + ' of ' + detail.totalReplicas, CheckCircle2],
    ['Stored data', formatBytes(detail.storedBytes), Gauge],
    ['Messages', detail.messageCount.toLocaleString(), MessageSquare],
    ['Cleanup policy', detail.cleanupPolicy.toUpperCase(), Trash2],
    ['Segment count', detail.segmentCount === null ? 'Not exposed' : detail.segmentCount.toLocaleString(), BarChart3]
  ];
  return <div className="overview-tab">
    <div className="topic-metric-grid">{cards.map(([label, value, Icon]) => <article key={String(label)}><div><span>{String(label)}</span><strong>{String(value)}</strong></div><Icon size={18} /></article>)}</div>
    <div className="overview-note"><Gauge size={16} /><span>Stored data is calculated from broker replica log sizes. Kafka does not expose physical segment count through the Admin API.</span></div>
    <div className="detail-table-wrap"><table className="detail-table"><thead><tr><th>Partition ID</th><th>Leader</th><th>Replicas</th><th>In-sync replicas</th><th>First offset</th><th>Next offset</th><th>Messages</th></tr></thead>
      <tbody>{detail.partitions.map(partition => <tr key={partition.partition}><td><strong>{partition.partition}</strong></td><td>{partition.leader ?? '—'}</td><td>{partition.replicas.join(', ')}</td><td className={partition.underReplicated ? 'replicas-warning' : 'replicas-ok'}>{partition.inSyncReplicas.join(', ')}</td><td>{partition.firstOffset.toLocaleString()}</td><td>{partition.nextOffset.toLocaleString()}</td><td>{partition.messageCount.toLocaleString()}</td></tr>)}</tbody>
    </table></div>
  </div>;
}

function CopyIcon({ size }: { size?: number }) {
  return <KeyRound size={size} />;
}

function StatisticsView({ statistics }: { statistics: TopicStatistics }) {
  return <div>
    <h3 className="stat-section-title">Messages</h3>
    <div className="statistics-grid message-stats">
      <StatCard label="Total number" value={statistics.messageCount.toLocaleString()} />
      <StatCard label="Offsets min–max" value={statistics.minOffset + ' – ' + statistics.maxOffset} />
      <StatCard label="Timestamp min–max" value={formatDate(statistics.minTimestamp) + ' – ' + formatDate(statistics.maxTimestamp)} wide />
      <StatCard label="Null keys" value={statistics.nullKeys.toLocaleString()} />
      <StatCard label="Unique keys" value={statistics.uniqueKeys.toLocaleString()} />
      <StatCard label="Null values" value={statistics.nullValues.toLocaleString()} />
      <StatCard label="Unique values" value={statistics.uniqueValues.toLocaleString()} />
    </div>
    <SizeStatSection title="Key size" stats={statistics.keySize} />
    <SizeStatSection title="Value size" stats={statistics.valueSize} />
    <div className="detail-table-wrap"><table className="detail-table"><thead><tr><th>Partition ID</th><th>Total messages</th><th>Min offset</th><th>Max offset</th></tr></thead><tbody>{statistics.partitions.map(partition => <tr key={partition.partition}><td>{partition.partition}</td><td>{partition.totalMessages}</td><td>{partition.minOffset}</td><td>{partition.maxOffset}</td></tr>)}</tbody></table></div>
  </div>;
}

function SizeStatSection({ title, stats }: { title: string; stats: SizeStatistics }) {
  return <div className="size-stat-section"><h3 className="stat-section-title">{title}</h3><div className="statistics-grid size-stats">
    <StatCard label="Total size" value={formatBytes(stats.total)} /><StatCard label="Min size" value={formatBytes(stats.min)} />
    <StatCard label="Max size" value={formatBytes(stats.max)} /><StatCard label="Average" value={formatBytes(Math.round(stats.average))} />
    <StatCard label="Percentile 50" value={formatBytes(stats.p50)} /><StatCard label="Percentile 75" value={formatBytes(stats.p75)} />
    <StatCard label="Percentile 95" value={formatBytes(stats.p95)} /><StatCard label="Percentile 99" value={formatBytes(stats.p99)} />
    <StatCard label="Percentile 999" value={formatBytes(stats.p999)} />
  </div></div>;
}

function StatCard({ label, value, wide = false }: { label: string; value: string; wide?: boolean }) {
  return <article className={wide ? 'wide' : ''}><span>{label}</span><strong>{value}</strong></article>;
}

function LoadingRow({ columns }: { columns: number }) {
  return <tr><td colSpan={columns}><div className="table-state"><RefreshCw className="spin" size={18} /> Loading live Kafka data…</div></td></tr>;
}

function EmptyRow({ columns, text }: { columns: number; text: string }) {
  return <tr><td colSpan={columns}><div className="table-state">{text}</div></td></tr>;
}
