/** zh-CN 定时任务资源包（REQ-20261117-061017）。 */
export const jobs = {
  title: '定时任务',
  reload: '刷新定时任务',
  total: '共 {{total}} 个',
  columns: {
    jobKey: '任务标识',
    description: '描述',
    cron: 'Cron 表达式',
    lastStatus: '最近执行',
    lastStartedAt: '最近开始',
    lastDurationMs: '最近耗时',
    lastTriggerType: '触发方式',
    lastError: '最近错误',
    actions: '操作',
  },
  actions: {
    run: '手动执行',
    runConfirm: '确定手动执行该任务？本次执行将产生一条 MANUAL 触发记录。',
    executions: '执行记录',
  },
  executions: {
    title: '执行记录：{{key}}',
    reload: '刷新执行记录',
    total: '共 {{total}} 条',
    columns: {
      status: '状态',
      startedAt: '开始时间',
      finishedAt: '结束时间',
      durationMs: '耗时',
      triggerType: '触发方式',
      error: '错误',
    },
    empty: '暂无执行记录',
  },
  toast: {
    runTriggered: '已触发手动执行：{{key}}',
  },
  errors: {
    loadFailed: '定时任务加载失败',
    runFailed: '手动执行失败，请稍后重试',
    executionsLoadFailed: '执行记录加载失败',
  },
};
