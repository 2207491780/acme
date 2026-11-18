/** en-US login log resource pack (REQ-20261114-120105). */
export const loginLogs = {
  title: 'Login logs',
  reload: 'Refresh login logs',
  total: '{{total}} items in total',
  columns: {
    createdAt: 'Time',
    username: 'Username',
    userType: 'Channel',
    ip: 'IP',
    userAgent: 'User-Agent',
    result: 'Result',
    failReason: 'Failure reason',
    traceId: 'Trace',
  },
  filters: {
    username: 'Username',
    userType: 'Channel (userType)',
    result: 'Result (e.g. SUCCESS)',
    query: 'Query',
    reset: 'Reset',
  },
  actions: {
    export: 'Export Excel',
  },
  toast: {
    exported: 'Export succeeded, download started',
  },
  errors: {
    loadFailed: 'Failed to load login logs',
    exportFailed: 'Export failed, please try again later',
  },
};
