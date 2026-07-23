import { useEffect, useRef } from 'react';

export function usePolling(
  callback: (signal: AbortSignal) => Promise<void> | void,
  intervalMs: number,
  enabled: boolean = true
) {
  const savedCallback = useRef(callback);

  useEffect(() => {
    savedCallback.current = callback;
  }, [callback]);

  useEffect(() => {
    if (!enabled) return;

    let timeoutId: number | null = null;
    let abortController = new AbortController();
    let isMounted = true;

    const executePoll = async () => {
      if (!isMounted) return;
      if (document.hidden) {
        // Skip this run if the document is hidden, try again after interval
        timeoutId = window.setTimeout(executePoll, intervalMs);
        return;
      }

      try {
        await savedCallback.current(abortController.signal);
      } catch (err: any) {
        if (err.name !== 'AbortError') {
          console.error('Polling error:', err);
        }
      } finally {
        if (isMounted) {
          timeoutId = window.setTimeout(executePoll, intervalMs);
        }
      }
    };

    const handleVisibilityChange = () => {
      if (!document.hidden && isMounted) {
        if (timeoutId) window.clearTimeout(timeoutId);
        abortController.abort();
        abortController = new AbortController();
        executePoll();
      }
    };

    document.addEventListener('visibilitychange', handleVisibilityChange);
    
    // Initial poll
    executePoll();

    return () => {
      isMounted = false;
      if (timeoutId) window.clearTimeout(timeoutId);
      abortController.abort();
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, [intervalMs, enabled]);
}
