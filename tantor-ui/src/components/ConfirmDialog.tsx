import { useEffect, useRef, useState } from 'react';
import { X } from 'lucide-react';
import orangeBanner from '../assets/orange.png';
import './ConfirmDialog.css';

type ConfirmRequest = {
  message: string;
  title?: string;
  confirmLabel?: string;
  showCancel?: boolean;
  resolve?: (confirmed: boolean) => void;
};

const CONFIRM_EVENT = 'tantor:confirm';

export function confirmAction(
  message: string,
  options: { title?: string; confirmLabel?: string } = {},
) {
  return new Promise<boolean>(resolve => {
    window.dispatchEvent(new CustomEvent<ConfirmRequest>(CONFIRM_EVENT, {
      detail: { message, resolve, ...options },
    }));
  });
}

export function notifyAction(
  message: string,
  options: { title?: string; confirmLabel?: string } = {},
) {
  window.dispatchEvent(new CustomEvent<ConfirmRequest>(CONFIRM_EVENT, {
    detail: {
      message,
      title: options.title || 'Notice',
      confirmLabel: options.confirmLabel || 'OK',
      showCancel: false,
    },
  }));
}

export function GlobalConfirmDialog() {
  const [request, setRequest] = useState<ConfirmRequest | null>(null);
  const confirmButtonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const handleRequest = (event: Event) => {
      setRequest((event as CustomEvent<ConfirmRequest>).detail);
    };
    window.addEventListener(CONFIRM_EVENT, handleRequest);
    return () => window.removeEventListener(CONFIRM_EVENT, handleRequest);
  }, []);

  useEffect(() => {
    if (!request) return;
    confirmButtonRef.current?.focus();
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') finish(false);
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [request]);

  function finish(confirmed: boolean) {
    if (!request) return;
    request.resolve?.(confirmed);
    setRequest(null);
  }

  if (!request) return null;

  return (
    <div className="app-confirm-backdrop" onMouseDown={() => finish(false)}>
      <section
        className="app-confirm-dialog"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="app-confirm-title"
        aria-describedby="app-confirm-message"
        onMouseDown={event => event.stopPropagation()}
      >
        <div className="app-confirm-banner">
          <img src={orangeBanner} alt="" aria-hidden="true" />
          <button type="button" onClick={() => finish(false)} aria-label="Close confirmation">
            <X size={20} />
          </button>
        </div>
        <div className="app-confirm-content">
          <h2 id="app-confirm-title">{request.title || 'Please confirm'}</h2>
          <p id="app-confirm-message">{request.message}</p>
          <div className="app-confirm-actions">
            {request.showCancel !== false && (
              <button type="button" className="app-confirm-cancel" onClick={() => finish(false)}>Cancel</button>
            )}
            <button ref={confirmButtonRef} type="button" className="app-confirm-primary" onClick={() => finish(true)}>
              {request.confirmLabel || 'OK'}
            </button>
          </div>
        </div>
      </section>
    </div>
  );
}
