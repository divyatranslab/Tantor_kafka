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
  return res.json();
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
