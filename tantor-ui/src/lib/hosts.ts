export function parseIpList(raw: any): string[] {
  if (Array.isArray(raw)) return raw.map(String).map(ip => ip.trim()).filter(Boolean);
  if (typeof raw === 'string' && raw.startsWith('[')) {
    try {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) return parsed.map(String).map(ip => ip.trim()).filter(Boolean);
    } catch {}
  }
  if (typeof raw === 'string') return raw.split(',').map(ip => ip.trim()).filter(Boolean);
  return [];
}
