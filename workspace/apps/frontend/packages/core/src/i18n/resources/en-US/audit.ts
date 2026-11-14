/** en-US audit events resource pack (p3-audit). */
export const audit = {
  title: 'Audit events',
  reload: 'Refresh audit events',
  total: '{{total}} items in total',
  columns: {
    occurredAt: 'Time',
    operatorId: 'Operator',
    channel: 'Channel',
    action: 'Action',
    target: 'Target',
    content: 'Content',
    traceId: 'Trace',
  },
  filters: {
    operatorId: 'Operator ID',
    action: 'Action (e.g. release.create)',
    traceId: 'Trace ID',
    query: 'Query',
    reset: 'Reset',
  },
  errors: {
    loadFailed: 'Failed to load audit events',
  },
};
