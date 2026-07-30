import { useState, useEffect, useCallback } from 'react';
import {
  Shield, Plus, Trash2, RefreshCw, Loader2, Search, Check, AlertCircle,
} from 'lucide-react';
import {
  getAcls, createAcl, deleteAcl,
} from '../lib/api';
import { usePermissions } from '../hooks/usePermissions';
import { CustomSelect } from './CustomSelect';

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
  const [aclPrincipal, setAclPrincipal] = useState('http://');
  const [aclResourceType, setAclResourceType] = useState('Topic');
  const [aclResourceName, setAclResourceName] = useState('');
  const [aclPatternType, setAclPatternType] = useState('Literal');
  const [aclOperations, setAclOperations] = useState<string[]>([]);
  const [aclPermission, setAclPermission] = useState('Allow');
  const [aclHost, setAclHost] = useState('');
  const [aclCreating, setAclCreating] = useState(false);
  const [aclFilterPrincipal, setAclFilterPrincipal] = useState('');
  const [aclFilterResource, setAclFilterResource] = useState('');
  const [aclToDelete, setAclToDelete] = useState<AclEntry | null>(null);
  const [alertMessage, setAlertMessage] = useState<string | null>(null);

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
      setAlertMessage("Please select at least one operation.");
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
      setAlertMessage(err.response?.data?.detail || err.message || 'Failed to create ACL');
    } finally {
      setAclCreating(false);
    }
  };

  const handleDeleteAcl = (acl: AclEntry) => {
    if (!canManage) return;
    setAclToDelete(acl);
  };

  const confirmDeleteAcl = async (acl: AclEntry) => {
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
      setAlertMessage(err.response?.data?.detail || err.message || 'Failed to delete ACL');
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
    <div className="security-manager" style={{ padding: '0px 10px' }}>
      <div style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        gap: '1rem',
        marginBottom: '1.25rem',
        marginTop: '0.5rem'
      }}>
        {/* Left side: Filters */}
        <div style={{ display: 'flex', gap: '1rem', flex: 1 }}>
          <div style={{ position: 'relative', flex: 1, maxWidth: '240px' }}>
            <Search size={16} style={{ position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)', color: '#9ca3af' }} />
            <input 
              type="text" 
              placeholder="Filter by principle..." 
              value={aclFilterPrincipal} 
              onChange={e => setAclFilterPrincipal(e.target.value)} 
              style={{
                width: '100%',
                padding: '10px 12px 10px 36px',
                borderRadius: '8px',
                border: '1px solid #e2e8f0',
                fontSize: '14px',
                fontFamily: 'Satoshi, Inter, sans-serif',
                outline: 'none',
                color: '#332849'
              }}
            />
          </div>
          <div style={{ position: 'relative', flex: 1, maxWidth: '240px' }}>
            <Search size={16} style={{ position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)', color: '#9ca3af' }} />
            <input 
              type="text" 
              placeholder="Filter by resources..." 
              value={aclFilterResource} 
              onChange={e => setAclFilterResource(e.target.value)} 
              style={{
                width: '100%',
                padding: '10px 12px 10px 36px',
                borderRadius: '8px',
                border: '1px solid #e2e8f0',
                fontSize: '14px',
                fontFamily: 'Satoshi, Inter, sans-serif',
                outline: 'none',
                color: '#332849'
              }}
            />
          </div>
        </div>

        {/* Right side: Actions */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <button 
            onClick={fetchAcls} 
            disabled={aclsLoading} 
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              width: '42px',
              height: '42px',
              borderRadius: '8px',
              border: '1px solid #e2e8f0',
              background: '#fff',
              cursor: 'pointer',
              color: '#475569',
              transition: 'all 0.2s'
            }}
            title="Refresh"
          >
            <RefreshCw size={18} className={aclsLoading ? 'spin' : ''} />
          </button>
          {canManage && (
            <button 
              onClick={() => setShowCreateAcl(true)} 
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '8px',
                height: '42px',
                padding: '0 20px',
                borderRadius: '8px',
                background: '#3E1363',
                color: '#fff',
                fontWeight: 500,
                fontSize: '14px',
                border: 'none',
                cursor: 'pointer',
                transition: 'all 0.2s'
              }}
            >
              <Plus size={16} /> Add ACL
            </button>
          )}
        </div>
      </div>

      {aclsError && (
        <div style={{
          display: 'flex',
          alignItems: 'center',
          gap: '10px',
          background: '#FFF9EB',
          border: '1px solid #FFE0B2',
          borderRadius: '8px',
          padding: '12px 16px',
          color: '#B78103',
          fontSize: '14px',
          fontWeight: 500,
          marginBottom: '1.25rem',
          fontFamily: 'Satoshi, Inter, sans-serif'
        }}>
          <AlertCircle size={18} style={{ color: '#F5A623' }} />
          <span>Failed to load ACLs</span>
        </div>
      )}

      {canManage && showCreateAcl && (
        <div style={{
          position: 'fixed',
          top: 0,
          left: 0,
          right: 0,
          bottom: 0,
          background: 'rgba(0, 0, 0, 0.4)',
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
          zIndex: 1000,
          fontFamily: 'Satoshi, Inter, sans-serif'
        }}>
          <div style={{
            background: '#fff',
            borderRadius: '16px',
            width: '100%',
            maxWidth: '780px',
            boxShadow: '0 10px 25px rgba(0,0,0,0.1)',
            overflow: 'hidden'
          }}>
            {/* Modal Header */}
            <div style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              padding: '20px 24px',
              borderBottom: '1px solid #f1f5f9'
            }}>
              <h3 style={{ margin: 0, fontSize: '20px', fontWeight: 500, color: '#332849' }}>Add New ACL Binding</h3>
              <button 
                onClick={() => setShowCreateAcl(false)} 
                style={{
                  background: 'none',
                  border: 'none',
                  fontSize: '24px',
                  color: '#94a3b8',
                  cursor: 'pointer',
                  padding: '4px'
                }}
              >
                ✕
              </button>
            </div>

            {/* Modal Body */}
            <form onSubmit={handleCreateAcl} style={{ padding: '24px' }}>
              <div style={{
                background: '#F9F9FB',
                borderRadius: '8px',
                padding: '24px',
                display: 'grid',
                gridTemplateColumns: 'repeat(3, 1fr)',
                gap: '20px 16px',
                marginBottom: '20px'
              }}>
                {/* Principle (Username) */}
                <div>
                  <label style={{ display: 'block', marginBottom: '8px', fontWeight: 500, fontSize: '14px', color: '#332849' }}>Principle (Username)</label>
                  <CustomSelect
                    value={aclPrincipal}
                    onChange={setAclPrincipal}
                    width="100%"
                    variant="audit"
                    options={[
                      { value: 'http://', label: 'http://' },
                      { value: 'User:*', label: 'User:*' },
                      { value: 'User:alice', label: 'User:alice' },
                      { value: 'User:bob', label: 'User:bob' },
                      { value: 'User:anuj', label: 'User:anuj' },
                      { value: 'User:admin', label: 'User:admin' },
                    ]}
                  />
                </div>

                {/* Host */}
                <div>
                  <label style={{ display: 'block', marginBottom: '8px', fontWeight: 500, fontSize: '14px', color: '#332849' }}>Host</label>
                  <input 
                    type="text" 
                    value={aclHost} 
                    onChange={e => setAclHost(e.target.value)} 
                    placeholder="* or client IP"
                    required 
                    style={{
                      width: '100%',
                      padding: '10px 12px',
                      borderRadius: '8px',
                      border: '1px solid #CCCCCC',
                      fontSize: '14px',
                      background: '#fff',
                      color: '#332849',
                      outline: 'none'
                    }}
                  />
                </div>

                {/* Resource Type */}
                <div>
                  <label style={{ display: 'block', marginBottom: '8px', fontWeight: 500, fontSize: '14px', color: '#332849' }}>Resource Type</label>
                  <CustomSelect
                    value={aclResourceType}
                    onChange={setAclResourceType}
                    width="100%"
                    variant="audit"
                    options={[
                      { value: 'Topic', label: 'Topic' },
                      { value: 'Group', label: 'Group' },
                      { value: 'Cluster', label: 'Cluster' },
                      { value: 'TransactionalId', label: 'TransactionalId' },
                    ]}
                  />
                </div>

                {/* Resource Name */}
                <div>
                  <label style={{ display: 'block', marginBottom: '8px', fontWeight: 500, fontSize: '14px', color: '#332849' }}>Resource Name</label>
                  <input 
                    type="text" 
                    value={aclResourceName} 
                    onChange={e => setAclResourceName(e.target.value)} 
                    placeholder="e.g. topics_name" 
                    required 
                    style={{
                      width: '100%',
                      padding: '10px 12px',
                      borderRadius: '8px',
                      border: '1px solid #CCCCCC',
                      fontSize: '14px',
                      background: '#fff',
                      color: '#332849',
                      outline: 'none'
                    }}
                  />
                </div>

                {/* Pattern Type */}
                <div>
                  <label style={{ display: 'block', marginBottom: '8px', fontWeight: 500, fontSize: '14px', color: '#332849' }}>Pattern Type</label>
                  <CustomSelect
                    value={aclPatternType}
                    onChange={setAclPatternType}
                    width="100%"
                    variant="audit"
                    options={[
                      { value: 'Literal', label: 'Literal' },
                      { value: 'Prefixed', label: 'Prefixed' },
                    ]}
                  />
                </div>

                {/* Permission */}
                <div>
                  <label style={{ display: 'block', marginBottom: '8px', fontWeight: 500, fontSize: '14px', color: '#332849' }}>Permission</label>
                  <CustomSelect
                    value={aclPermission}
                    onChange={setAclPermission}
                    width="100%"
                    variant="audit"
                    options={[
                      { value: 'Allow', label: 'Allow' },
                      { value: 'Deny', label: 'Deny' },
                    ]}
                  />
                </div>
              </div>

              {/* Operations */}
              <div style={{
                background: '#F9F9FB',
                borderRadius: '8px',
                padding: '24px',
                marginBottom: '24px'
              }}>
                <label style={{ display: 'block', marginBottom: '16px', fontWeight: 500, fontSize: '16px', color: '#5B327F' }}>Operations</label>
                <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
                  {OPERATIONS.map(op => {
                    const isSelected = aclOperations.includes(op);
                    return (
                      <button 
                        type="button" 
                        key={op} 
                        onClick={() => toggleAclOperation(op)}
                        style={{
                          padding: '8px 16px',
                          borderRadius: '8px',
                          fontSize: '14px',
                          fontWeight: 500,
                          border: '1px solid #CCCCCC',
                          background: isSelected ? '#3E1363' : '#FFFFFF',
                          color: isSelected ? '#FFFFFF' : '#5F6368',
                          cursor: 'pointer',
                          transition: 'all 0.15s'
                        }}
                      >
                        {op}
                      </button>
                    );
                  })}
                </div>
              </div>

              {/* Modal Footer Actions */}
              <div style={{
                display: 'flex',
                justifyContent: 'flex-end',
                gap: '12px',
                borderTop: '1px solid #f1f5f9',
                paddingTop: '20px',
                marginTop: '10px'
              }}>
                <button 
                  type="button" 
                  onClick={() => setShowCreateAcl(false)} 
                  style={{
                    height: '38px',
                    padding: '0 24px',
                    borderRadius: '8px',
                    border: '1px solid #3E1363',
                    background: '#fff',
                    color: '#3E1363',
                    fontWeight: 500,
                    fontSize: '14px',
                    cursor: 'pointer'
                  }}
                >
                  Cancel
                </button>
                <button 
                  type="submit" 
                  disabled={aclCreating} 
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '6px',
                    height: '38px',
                    padding: '0 24px',
                    borderRadius: '8px',
                    background: '#3E1363',
                    color: '#fff',
                    fontWeight: 500,
                    fontSize: '14px',
                    border: 'none',
                    cursor: 'pointer'
                  }}
                >
                  {aclCreating && <Loader2 size={14} className="spin" />}
                  Create
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

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

      {aclToDelete && (
        <div style={{
          position: 'fixed',
          top: 0,
          left: 0,
          right: 0,
          bottom: 0,
          background: 'rgba(0, 0, 0, 0.4)',
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
          zIndex: 1000,
          fontFamily: 'Satoshi, Inter, sans-serif'
        }}>
          <div style={{
            background: '#fff',
            borderRadius: '16px',
            width: '100%',
            maxWidth: '540px',
            boxShadow: '0 10px 25px rgba(0,0,0,0.1)',
            overflow: 'hidden'
          }}>
            {/* Banner */}
            <div className="confirm-modal-banner" style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', padding: '0 24px', boxSizing: 'border-box', height: '72px' }}>
              <button onClick={() => setAclToDelete(null)} className="confirm-modal-close-btn" style={{ color: '#818181', background: 'transparent', border: 'none', cursor: 'pointer', fontSize: '20px' }} aria-label="Close modal">
                ✕
              </button>
            </div>
            
            {/* Body */}
            <div className="confirm-modal-body" style={{ padding: '24px', display: 'flex', flexDirection: 'column', gap: '16px', boxSizing: 'border-box' }}>
              <div className="confirm-modal-title-row" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <AlertCircle size={20} color="#EF4D5F" style={{ flexShrink: 0 }} />
                <h2 style={{ margin: 0, fontSize: '18px', fontWeight: 600, color: '#332849' }}>
                  Confirm action
                </h2>
              </div>
              
              <p style={{ margin: 0, fontSize: '15px', color: '#5F6368', lineHeight: '1.5' }}>
                Are you sure you want to delete this ACL binding?
                <br />
                <span style={{ fontWeight: 600, color: '#332849', display: 'inline-block', marginTop: '8px' }}>
                  Delete ACL for {aclToDelete.principal} on {aclToDelete.resourceType} {aclToDelete.resourceName}?
                </span>
              </p>
              
              {/* Footer Actions */}
              <div style={{
                display: 'flex',
                justifyContent: 'flex-end',
                gap: '12px',
                marginTop: '8px'
              }}>
                <button 
                  type="button" 
                  onClick={() => setAclToDelete(null)} 
                  style={{
                    height: '38px',
                    padding: '0 24px',
                    borderRadius: '8px',
                    border: '1px solid #EF4D5F',
                    background: '#fff',
                    color: '#EF4D5F',
                    fontWeight: 500,
                    fontSize: '14px',
                    cursor: 'pointer'
                  }}
                >
                  Cancel
                </button>
                <button 
                  onClick={async () => {
                    const acl = aclToDelete;
                    setAclToDelete(null);
                    await confirmDeleteAcl(acl);
                  }} 
                  style={{
                    height: '38px',
                    padding: '0 24px',
                    borderRadius: '8px',
                    background: '#3E1363',
                    color: '#fff',
                    fontWeight: 500,
                    fontSize: '14px',
                    border: 'none',
                    cursor: 'pointer'
                  }}
                >
                  OK
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {alertMessage && (
        <div style={{
          position: 'fixed',
          top: 0,
          left: 0,
          right: 0,
          bottom: 0,
          background: 'rgba(0, 0, 0, 0.4)',
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
          zIndex: 1100,
          fontFamily: 'Satoshi, Inter, sans-serif'
        }}>
          <div style={{
            background: '#fff',
            borderRadius: '16px',
            width: '100%',
            maxWidth: '480px',
            boxShadow: '0 10px 25px rgba(0,0,0,0.1)',
            overflow: 'hidden'
          }}>
            {/* Banner */}
            <div className="confirm-modal-banner" style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', padding: '0 24px', boxSizing: 'border-box', height: '72px' }}>
              <button onClick={() => setAlertMessage(null)} className="confirm-modal-close-btn" style={{ color: '#818181', background: 'transparent', border: 'none', cursor: 'pointer', fontSize: '20px' }} aria-label="Close modal">
                ✕
              </button>
            </div>
            
            {/* Body */}
            <div className="confirm-modal-body" style={{ padding: '24px', display: 'flex', flexDirection: 'column', gap: '16px', boxSizing: 'border-box' }}>
              <div className="confirm-modal-title-row" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <AlertCircle size={20} color="#EF4D5F" style={{ flexShrink: 0 }} />
                <h2 style={{ margin: 0, fontSize: '18px', fontWeight: 600, color: '#332849' }}>
                  Notice
                </h2>
              </div>
              
              <p style={{ margin: 0, fontSize: '15px', color: '#5F6368', lineHeight: '1.5' }}>
                {alertMessage}
              </p>
              
              {/* Footer Actions */}
              <div style={{
                display: 'flex',
                justifyContent: 'flex-end',
                marginTop: '8px'
              }}>
                <button 
                  onClick={() => setAlertMessage(null)} 
                  style={{
                    height: '38px',
                    padding: '0 28px',
                    borderRadius: '8px',
                    background: '#3E1363',
                    color: '#fff',
                    fontWeight: 500,
                    fontSize: '14px',
                    border: 'none',
                    cursor: 'pointer'
                  }}
                >
                  OK
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
