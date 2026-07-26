export async function apiError(response: Response): Promise<string> {
  const body = await response.json().catch(() => null);
  return body?.message || body?.error || 'Request failed (HTTP ' + response.status + ')';
}
