export type TopicActionKind = 'clear' | 'recreate' | 'remove';

export const topicActionCopy: Record<TopicActionKind, { title: string; description: string; button: string }> = {
  clear: {
    title: 'Clear all messages?',
    description: 'Kafka will advance the low watermark for every partition. This cannot be undone and requires a DELETE cleanup policy.',
    button: 'Clear messages'
  },
  recreate: {
    title: 'Recreate this topic?',
    description: 'All existing messages will be permanently deleted and cannot be recovered. The topic will be deleted and recreated with its original partition assignments and explicit settings. If recreation encounters an error, the topic may remain absent until restored by an administrator.',
    button: 'Recreate topic'
  },
  remove: {
    title: 'Remove this topic?',
    description: 'The topic and all associated data will be permanently deleted.',
    button: 'Remove topic'
  }
};
