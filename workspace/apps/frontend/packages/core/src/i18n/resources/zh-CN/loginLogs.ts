/** zh-CN 登录日志资源包（REQ-20261114-120105）。 */
export const loginLogs = {
  title: '登录日志',
  reload: '刷新登录日志',
  total: '共 {{total}} 条',
  columns: {
    createdAt: '时间',
    username: '用户名',
    userType: '端',
    ip: 'IP',
    userAgent: 'User-Agent',
    result: '结果',
    failReason: '失败原因',
    traceId: 'Trace',
  },
  filters: {
    username: '用户名',
    userType: '端（userType）',
    result: '结果（如 SUCCESS）',
    query: '查询',
    reset: '重置',
  },
  errors: {
    loadFailed: '登录日志加载失败',
  },
};
