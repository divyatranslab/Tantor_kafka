import type { UserResponse } from '../types/index.ts';

function getToken() {
  return localStorage.getItem('token');
}

async function fetchWithAuth(url: string, options: RequestInit = {}) {
  const token = getToken();
  const headers = new Headers(options.headers || {});
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  headers.set('Content-Type', 'application/json');

  const res = await fetch(url, { ...options, headers });
  if (!res.ok) {
    const errorData = await res.json().catch(() => ({}));
    throw { response: { data: errorData } };
  }
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

export async function getUsers(): Promise<UserResponse[]> {
  return fetchWithAuth('/api/v1/auth/users');
}

export async function createAuthUser(data: any): Promise<UserResponse> {
  return fetchWithAuth('/api/v1/auth/users', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export async function updateAuthUser(id: string, data: any): Promise<UserResponse> {
  return fetchWithAuth(`/api/v1/auth/users/${id}`, {
    method: 'PUT',
    body: JSON.stringify(data),
  });
}

export async function deleteAuthUser(id: string): Promise<any> {
  return fetchWithAuth(`/api/v1/auth/users/${id}`, {
    method: 'DELETE',
  });
}

export async function retryTask(clusterId: string, taskId: string): Promise<any> {
  return fetchWithAuth(`/api/v1/clusters/${clusterId}/actions/tasks/${taskId}/retry`, {
    method: 'POST',
  });
}

export async function resumeTask(clusterId: string, taskId: string): Promise<any> {
  return fetchWithAuth(`/api/v1/clusters/${clusterId}/actions/tasks/${taskId}/resume`, {
    method: 'POST',
  });
}

export async function rollbackTask(clusterId: string, taskId: string): Promise<any> {
  return fetchWithAuth(`/api/v1/clusters/${clusterId}/actions/tasks/${taskId}/rollback`, {
    method: 'POST',
  });
}

export async function cleanupTask(clusterId: string, taskId: string): Promise<any> {
  return fetchWithAuth(`/api/v1/clusters/${clusterId}/actions/tasks/${taskId}/cleanup`, {
    method: 'POST',
  });
}

// ── Security - ACLs ──────────────────────────────────
export const getAcls = (clusterId: string, params?: { principal?: string; resource_type?: string; resource_name?: string }) => {
  const query = new URLSearchParams(params as any).toString();
  return fetchWithAuth(`/api/v1/clusters/${clusterId}/security/acls${query ? '?' + query : ''}`);
};

export const createAcl = (clusterId: string, data: any) =>
  fetchWithAuth(`/api/v1/clusters/${clusterId}/security/acls`, {
    method: 'POST',
    body: JSON.stringify(data),
  });

export const deleteAcl = (clusterId: string, data: any) =>
  fetchWithAuth(`/api/v1/clusters/${clusterId}/security/acls`, {
    method: 'DELETE',
    body: JSON.stringify(data),
  });

