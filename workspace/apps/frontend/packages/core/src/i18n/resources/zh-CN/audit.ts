/** zh-CN 审计事件资源包（p3-audit）。 */
export const audit = {
  title: '审计事件',
  reload: '刷新审计事件',
  total: '共 {{total}} 条',
  columns: {
    occurredAt: '时间',
    operatorId: '操作者',
    channel: '端',
    action: '动作',
    target: '对象',
    content: '内容',
    traceId: 'Trace',
  },
  filters: {
    operatorId: '操作者 ID',
    action: '动作（如 release.create）',
    traceId: 'Trace ID',
    query: '查询',
    reset: '重置',
  },
  actions: {
    export: '导出 Excel',
  },
  toast: {
    exported: '导出成功，已开始下载',
  },
  errors: {
    loadFailed: '审计事件加载失败',
    exportFailed: '导出失败，请稍后重试',
  },
};
