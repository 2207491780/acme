/** en-US scheduled jobs resource pack (REQ-20261117-061017). */
export const jobs = {
  title: 'Scheduled Jobs',
  reload: 'Refresh scheduled jobs',
  total: '{{total}} jobs in total',
  columns: {
    jobKey: 'Job key',
    description: 'Description',
    cron: 'Cron expression',
    lastStatus: 'Last run',
    lastStartedAt: 'Last started',
    lastDurationMs: 'Last duration',
    lastTriggerType: 'Trigger type',
    lastError: 'Last error',
    actions: 'Actions',
  },
  actions: {
    run: 'Run now',
    runConfirm: 'Run this job now? A MANUAL trigger record will be created.',
    executions: 'Executions',
  },
  executions: {
    title: 'Executions: {{key}}',
    reload: 'Refresh executions',
    total: '{{total}} items in total',
    columns: {
      status: 'Status',
      startedAt: 'Started at',
      finishedAt: 'Finished at',
      durationMs: 'Duration',
      triggerType: 'Trigger type',
      error: 'Error',
    },
    empty: 'No executions yet',
  },
  toast: {
    runTriggered: 'Manual run triggered: {{key}}',
  },
  errors: {
    loadFailed: 'Failed to load scheduled jobs',
    runFailed: 'Failed to trigger the job, please try again later',
    executionsLoadFailed: 'Failed to load executions',
  },
};
