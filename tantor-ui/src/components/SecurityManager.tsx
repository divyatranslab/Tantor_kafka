import { useState, useEffect, useCallback } from 'react';
import {
  Shield, Plus, Trash2, RefreshCw, Loader2, Search, Check,
} from 'lucide-react';
import {
  getAcls, createAcl, deleteAcl,
} from '../lib/api';
import { usePermissions } from '../hooks/usePermissions';

interface Props {
  clusterId: string;
}

export interface AclEntry {
  principal: string;
  host: string;
  operation: string;
  permissionType: string;
  resourceType: string;
  resourceName: string;
  patternType: string;
}

const OPERATIONS = ['Read', 'Write', 'Create', 'Describe', 'Alter', 'Delete', 'All'];
const RESOURCE_TYPES = ['topic', 'group', 'cluster', 'transactional-id'];

export default function SecurityManager({ clusterId }: Props) {
  const { canManage } = usePermissions();
  // ── ACLs state ──
  const [acls, setAcls] = useState<AclEntry[]>([]);
  const [aclsLoading, setAclsLoading] = useState(false);
  const [aclsError, setAclsError] = useState('');
  const [showCreateAcl, setShowCreateAcl] = useState(false);
  const [aclPrincipal, setAclPrincipal] = useState('');
  const [aclResourceType, setAclResourceType] = useState('topic');
  const [aclResourceName, setAclResourceName] = useState('');
  const [aclPatternType, setAclPatternType] = useState('literal');
  const [aclOperations, setAclOperations] = useState<string[]>([]);
  const [aclPermission, setAclPermission] = useState('Allow');
  const [aclHost, setAclHost] = useState('*');
  const [aclCreating, setAclCreating] = useState(false);
  const [aclFilterPrincipal, setAclFilterPrincipal] = useState('');
  const [aclFilterResource, setAclFilterResource] = useState('');

  const fetchAcls = useCallback(async () => {
    setAclsLoading(true);
    setAclsError('');
    try {
      const data = await getAcls(clusterId);
      setAcls(data.acls || []);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to load ACLs';
      const axErr = err as { response?: { data?: { detail?: string } } };
      setAclsError(axErr.response?.data?.detail || msg);
    } finally {
      setAclsLoading(false);
    }
  }, [clusterId]);

  useEffect(() => {
    fetchAcls();
  }, [fetchAcls]);

  const handleCreateAcl = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canManage) return;
    if (aclOperations.length === 0) {
      alert("Please select at least one operation.");
      return;
    }
    setAclCreating(true);
    try {
      for (const op of aclOperations) {
        await createAcl(clusterId, {
          resource_type: aclResourceType,
          resource_name: aclResourceName,
          pattern_type: aclPatternType,
          principal: 'User:' + aclPrincipal.replace(/^User:/, ''),
          host: aclHost,
          operation: op,
          permission_type: aclPermission,
        });
      }
      setShowCreateAcl(false);
      setAclOperations([]);
      setAclPrincipal('');
      setAclResourceName('');
      fetchAcls();
    } catch (err: any) {
      alert(err.response?.data?.detail || err.message || 'Failed to create ACL');
    } finally {
      setAclCreating(false);
    }
  };

  const handleDeleteAcl = async (acl: AclEntry) => {
    if (!canManage) return;
    if (!confirm(`Delete ACL for ${acl.principal} on ${acl.resourceType} ${acl.resourceName}?`)) return;
    try {
      await deleteAcl(clusterId, {
        resource_type: acl.resourceType,
        resource_name: acl.resourceName,
        pattern_type: acl.patternType,
        principal: acl.principal,
        host: acl.host,
        operation: acl.operation,
        permission_type: acl.permissionType,
      });
      fetchAcls();
    } catch (err: any) {
      alert(err.response?.data?.detail || err.message || 'Failed to delete ACL');
    }
  };

  const toggleAclOperation = (op: string) => {
    if (!canManage) return;
    if (op === 'All') {
      setAclOperations(prev => prev.includes('All') ? [] : ['All']);
    } else {
      setAclOperations(prev => {
        const withoutAll = prev.filter(o => o !== 'All');
        if (withoutAll.includes(op)) return withoutAll.filter(o => o !== op);
        return [...withoutAll, op];
      });
    }
  };

  const filteredAcls = acls.filter(acl => {
    if (aclFilterPrincipal && !acl.principal.toLowerCase().includes(aclFilterPrincipal.toLowerCase())) return false;
    if (aclFilterResource && !acl.resourceName.toLowerCase().includes(aclFilterResource.toLowerCase())) return false;
    return true;
  });

  return (
    <div className="security-manager">
      <div className="section-header" style={{display:'flex',justifyContent:'space-between',alignItems:'center',marginBottom:'1rem'}}>
        <div>
          <h2 className="cluster-section-heading"><Shield size={20} style={{display:'inline',marginRight:8,verticalAlign:'text-bottom'}} /> Access Control Lists (ACLs)</h2>
          <p className="mono-muted">Manage fine-grained permissions for Kafka resources.</p>
        </div>
        <div className="header-actions">
          <button onClick={fetchAcls} disabled={aclsLoading} className="btn-secondary">
            <RefreshCw size={16} className={aclsLoading ? 'spin' : ''} /> Refresh
          </button>
          {canManage && (
            <button onClick={() => setShowCreateAcl(true)} className="btn-primary">
              <Plus size={16} /> Add ACL
            </button>
          )}
        </div>
      </div>

      {aclsError && <div className="error-banner">{aclsError}</div>}

      {canManage && showCreateAcl && (
        <div className="create-panel" style={{background:'#f9fafb',padding:'1.5rem',borderRadius:8,marginBottom:'1.5rem',border:'1px solid #e5e7eb'}}>
          <div style={{display:'flex',justifyContent:'space-between',alignItems:'center',marginBottom:'1rem'}}>
            <h3 style={{margin:0,fontSize:'1.1rem'}}>Add New ACL Binding</h3>
            <button onClick={() => setShowCreateAcl(false)} style={{background:'none',border:'none',cursor:'pointer'}}>✕</button>
          </div>
          <form onSubmit={handleCreateAcl}>
            <div style={{display:'grid',gridTemplateColumns:'1fr 1fr',gap:'1rem',marginBottom:'1rem'}}>
              <div>
                <label style={{display:'block',marginBottom:4,fontWeight:500,fontSize:'0.9rem'}}>Principal (Username)</label>
                <input 
                  type="text" 
                  value={aclPrincipal} 
                  onChange={e => setAclPrincipal(e.target.value)} 
                  placeholder="e.g. alice" 
                  required 
                  style={{width:'100%',padding:'0.5rem',borderRadius:4,border:'1px solid #d1d5db'}}
                />
              </div>
              <div>
                <label style={{display:'block',marginBottom:4,fontWeight:500,fontSize:'0.9rem'}}>Host</label>
                <input 
                  type="text" 
                  value={aclHost} 
                  onChange={e => setAclHost(e.target.value)} 
                  placeholder="*" 
                  required 
                  style={{width:'100%',padding:'0.5rem',borderRadius:4,border:'1px solid #d1d5db'}}
                />
              </div>
              <div>
                <label style={{display:'block',marginBottom:4,fontWeight:500,fontSize:'0.9rem'}}>Resource Type</label>
                <select value={aclResourceType} onChange={e => setAclResourceType(e.target.value)} style={{width:'100%',padding:'0.5rem',borderRadius:4,border:'1px solid #d1d5db'}}>
                  {RESOURCE_TYPES.map(rt => <option key={rt} value={rt}>{rt.toUpperCase()}</option>)}
                </select>
              </div>
              <div>
                <label style={{display:'block',marginBottom:4,fontWeight:500,fontSize:'0.9rem'}}>Resource Name</label>
                <input 
                  type="text" 
                  value={aclResourceName} 
                  onChange={e => setAclResourceName(e.target.value)} 
                  placeholder="e.g. * or topic_name" 
                  required 
                  style={{width:'100%',padding:'0.5rem',borderRadius:4,border:'1px solid #d1d5db'}}
                />
              </div>
              <div>
                <label style={{display:'block',marginBottom:4,fontWeight:500,fontSize:'0.9rem'}}>Pattern Type</label>
                <select value={aclPatternType} onChange={e => setAclPatternType(e.target.value)} style={{width:'100%',padding:'0.5rem',borderRadius:4,border:'1px solid #d1d5db'}}>
                  <option value="literal">LITERAL</option>
                  <option value="prefixed">PREFIXED</option>
                </select>
              </div>
              <div>
                <label style={{display:'block',marginBottom:4,fontWeight:500,fontSize:'0.9rem'}}>Permission</label>
                <select value={aclPermission} onChange={e => setAclPermission(e.target.value)} style={{width:'100%',padding:'0.5rem',borderRadius:4,border:'1px solid #d1d5db'}}>
                  <option value="Allow">ALLOW</option>
                  <option value="Deny">DENY</option>
                </select>
              </div>
            </div>

            <div style={{marginBottom:'1.5rem'}}>
              <label style={{display:'block',marginBottom:8,fontWeight:500,fontSize:'0.9rem'}}>Operations</label>
              <div style={{display:'flex',gap:'0.5rem',flexWrap:'wrap'}}>
                {OPERATIONS.map(op => (
                  <button 
                    type="button" 
                    key={op} 
                    onClick={() => toggleAclOperation(op)}
                    style={{
                      padding:'0.25rem 0.75rem',
                      borderRadius:16,
                      fontSize:'0.85rem',
                      border: aclOperations.includes(op) ? '1px solid #3b82f6' : '1px solid #d1d5db',
                      background: aclOperations.includes(op) ? '#eff6ff' : '#fff',
                      color: aclOperations.includes(op) ? '#1d4ed8' : '#374151',
                      cursor:'pointer'
                    }}
                  >
                    {aclOperations.includes(op) && <Check size={12} style={{display:'inline',marginRight:4}}/>}
                    {op}
                  </button>
                ))}
              </div>
            </div>

            <button type="submit" disabled={aclCreating} style={{padding:'0.5rem 1rem',background:'#2563eb',color:'white',border:'none',borderRadius:4,cursor:'pointer',display:'flex',alignItems:'center',gap:'0.5rem'}}>
              {aclCreating ? <Loader2 size={16} className="spin" /> : <Plus size={16} />} 
              Create ACL
            </button>
          </form>
        </div>
      )}

      <div style={{display:'flex',gap:'1rem',marginBottom:'1rem'}}>
        <div style={{position:'relative',flex:1,maxWidth:300}}>
          <Search size={16} style={{position:'absolute',left:10,top:10,color:'#9ca3af'}} />
          <input 
            type="text" 
            placeholder="Filter by Principal..." 
            value={aclFilterPrincipal} 
            onChange={e => setAclFilterPrincipal(e.target.value)} 
            style={{width:'100%',padding:'0.5rem 0.5rem 0.5rem 2rem',borderRadius:4,border:'1px solid #d1d5db'}}
          />
        </div>
        <div style={{position:'relative',flex:1,maxWidth:300}}>
          <Search size={16} style={{position:'absolute',left:10,top:10,color:'#9ca3af'}} />
          <input 
            type="text" 
            placeholder="Filter by Resource..." 
            value={aclFilterResource} 
            onChange={e => setAclFilterResource(e.target.value)} 
            style={{width:'100%',padding:'0.5rem 0.5rem 0.5rem 2rem',borderRadius:4,border:'1px solid #d1d5db'}}
          />
        </div>
      </div>

      <div className="table-container" style={{overflowX:'auto',border:'1px solid #e5e7eb',borderRadius:8}}>
        <table style={{width:'100%',borderCollapse:'collapse',textAlign:'left'}}>
          <thead style={{background:'#f9fafb',borderBottom:'1px solid #e5e7eb'}}>
            <tr>
              <th style={{padding:'0.75rem',fontSize:'0.85rem',fontWeight:600,color:'#4b5563'}}>Principal</th>
              <th style={{padding:'0.75rem',fontSize:'0.85rem',fontWeight:600,color:'#4b5563'}}>Host</th>
              <th style={{padding:'0.75rem',fontSize:'0.85rem',fontWeight:600,color:'#4b5563'}}>Resource</th>
              <th style={{padding:'0.75rem',fontSize:'0.85rem',fontWeight:600,color:'#4b5563'}}>Operation</th>
              <th style={{padding:'0.75rem',fontSize:'0.85rem',fontWeight:600,color:'#4b5563'}}>Permission</th>
              {canManage && <th style={{padding:'0.75rem',fontSize:'0.85rem',fontWeight:600,color:'#4b5563'}}>Actions</th>}
            </tr>
          </thead>
          <tbody>
            {aclsLoading && acls.length === 0 ? (
              <tr><td colSpan={canManage ? 6 : 5} style={{padding:'2rem',textAlign:'center'}}><Loader2 className="spin" size={24} style={{margin:'0 auto'}}/></td></tr>
            ) : filteredAcls.length === 0 ? (
              <tr><td colSpan={canManage ? 6 : 5} style={{padding:'2rem',textAlign:'center',color:'#6b7280'}}>No ACLs found.</td></tr>
            ) : (
              filteredAcls.map((acl, i) => (
                <tr key={i} style={{borderBottom:'1px solid #e5e7eb'}}>
                  <td style={{padding:'0.75rem',fontWeight:500}}>{acl.principal}</td>
                  <td style={{padding:'0.75rem',color:'#6b7280'}}>{acl.host}</td>
                  <td style={{padding:'0.75rem'}}>
                    <span style={{fontSize:'0.75rem',textTransform:'uppercase',background:'#f3f4f6',padding:'2px 6px',borderRadius:4,marginRight:6}}>{acl.resourceType}</span>
                    <span style={{fontWeight:500}}>{acl.resourceName}</span>
                    {acl.patternType !== 'LITERAL' && <span style={{fontSize:'0.75rem',color:'#9ca3af',marginLeft:6}}>({acl.patternType})</span>}
                  </td>
                  <td style={{padding:'0.75rem'}}>{acl.operation}</td>
                  <td style={{padding:'0.75rem'}}>
                    <span style={{
                      padding:'2px 8px', borderRadius:12, fontSize:'0.75rem', fontWeight:600,
                      background: acl.permissionType === 'ALLOW' ? '#dcfce7' : '#fee2e2',
                      color: acl.permissionType === 'ALLOW' ? '#166534' : '#991b1b'
                    }}>
                      {acl.permissionType}
                    </span>
                  </td>
                  {canManage && (
                    <td style={{padding:'0.75rem'}}>
                      <button onClick={() => handleDeleteAcl(acl)} style={{color:'#ef4444',background:'none',border:'none',cursor:'pointer',padding:4}} title="Delete ACL">
                        <Trash2 size={16} />
                      </button>
                    </td>
                  )}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
