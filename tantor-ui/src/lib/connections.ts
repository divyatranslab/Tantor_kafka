/**
 * Shared connection utility — extracted from SchemaRegistry and KafkaConnect
 * to eliminate duplicate withConnId implementations. Safely appends
 * ?connectionId=... to any URL using URLSearchParams.
 */
export const withConnId = (url: string, connId: string | null): string => {
  if (!connId) return url;
  const [base, existing] = url.split('?');
  const params = new URLSearchParams(existing || '');
  params.set('connectionId', connId);
  return base + '?' + params.toString();
};
