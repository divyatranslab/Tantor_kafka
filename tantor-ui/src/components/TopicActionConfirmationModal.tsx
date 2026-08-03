import { X } from 'lucide-react';
import './TopicActionConfirmationModal.css';

export type TopicActionKind = 'clear' | 'recreate' | 'remove';

export const topicActionCopy: Record<TopicActionKind, { title: string; description: string; button: string }> = {
  clear: {
    title: 'Clear all messages?',
    description: 'Kafka will advance the low watermark for every partition. This cannot be undone and requires a DELETE cleanup policy.',
    button: 'Clear messages'
  },
  recreate: {
    title: 'Recreate this topic?',
    description: 'All messages will be deleted. Partition assignments and explicit settings will be restored.',
    button: 'Recreate topic'
  },
  remove: {
    title: 'Remove this topic?',
    description: 'The topic and all associated data will be permanently deleted.',
    button: 'Remove topic'
  }
};

interface TopicActionConfirmationModalProps {
  action: TopicActionKind;
  topicNames: string[];
  acting: boolean;
  onClose: () => void;
  onConfirm: () => void;
}

export function TopicActionConfirmationModal({
  action,
  topicNames,
  acting,
  onClose,
  onConfirm
}: TopicActionConfirmationModalProps) {
  const title = action === 'remove' && topicNames.length > 1 ? 'Remove these topics?' : topicActionCopy[action].title;
  const description = action === 'remove' && topicNames.length > 1
    ? 'The topics and all associated data will be permanently deleted.'
    : topicActionCopy[action].description;

  return (
    <div className="topic-action-modal-backdrop" role="presentation" onMouseDown={() => !acting && onClose()}>
      <div className="topic-action-modal" role="alertdialog" aria-modal="true" aria-labelledby="topic-action-confirmation-title" onMouseDown={event => event.stopPropagation()}>
        <div className="topic-action-banner" aria-hidden="true" />
        <button className="topic-action-close" onClick={onClose} disabled={acting} aria-label="Close modal"><X size={22} /></button>
        <div className="topic-action-content">
          <div className="topic-action-title">
            <span className="topic-action-icon" aria-hidden="true" />
            <h3 id="topic-action-confirmation-title">{title}</h3>
          </div>
          <p>{description}</p>
          <div className="topic-action-names">{topicNames.join(', ')}</div>
          <footer>
            <button className="topic-action-cancel" onClick={onClose} disabled={acting}>Cancel</button>
            <button className="topic-action-confirm" onClick={onConfirm} disabled={acting}>{acting ? 'Working...' : topicActionCopy[action].button}</button>
          </footer>
        </div>
      </div>
    </div>
  );
}