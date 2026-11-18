import { useState } from 'react';
import { App, Button, Drawer, Popconfirm, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import {TONE_ANTD,
  formatDate,
  infraApi,
  systemErrorMessage,
  useCrud,
  useMeStore,
  type JobExecutionView,
  type JobView,
  Auth,
} from '@acme/core';
import { CrudTable } from '../components/CrudTable';

const { Text } = Typography;

/** 执行/任务状态 → 语义 Tone（UI-SPEC §2.2：SUCCESS=success、FAILED/ERROR=danger、RUNNING=info、其余=muted）。 */
const statusTone = (status: string | null): keyof typeof TONE_ANTD => {
  const key = (status ?? '').toUpperCase();
  if (key === 'SUCCESS') return 'success';
  if (key === 'FAILED' || key === 'ERROR') return 'danger';
  if (key === 'RUNNING') return 'info';
  return 'muted';
};

/** 耗时渲染（毫秒；空值占位）。 */
const renderDuration = (v: number | null | undefined) => (v === null || v === undefined ? '—' : `${v} ms`);

/**
 * 执行记录抽屉（REQ-20261117-061017）：GET /admin/v1/infra/jobs/{jobKey}/executions 分页倒序
 * （排序在后端，started_at DESC）；按 jobKey 挂载即拉取（父级条件渲染保证每次打开均为新实例）。
 */
function JobExecutionsDrawer({ jobKey, onClose }: { jobKey: string; onClose: () => void }) {
  const { t } = useTranslation();

  const crud = useCrud<JobExecutionView>({
    fetchPage: ({ page, size, signal }) => infraApi.jobExecutions(jobKey, page, size, signal),
    pageSize: 10,
    errorMessage: t('jobs.errors.executionsLoadFailed'),
  });

  const columns: ColumnsType<JobExecutionView> = [
    { title: t('jobs.executions.columns.status'), dataIndex: 'status', key: 'status', minWidth: 100, render: (v: string) => <Tag color={TONE_ANTD[statusTone(v)]}>{v}</Tag> },
    { title: t('jobs.executions.columns.startedAt'), dataIndex: 'startedAt', key: 'startedAt', minWidth: 170, render: (v: string | null) => (v ? formatDate(v) : '—') },
    { title: t('jobs.executions.columns.finishedAt'), dataIndex: 'finishedAt', key: 'finishedAt', minWidth: 170, render: (v: string | null) => (v ? formatDate(v) : '—') },
    { title: t('jobs.executions.columns.durationMs'), dataIndex: 'durationMs', key: 'durationMs', minWidth: 100, render: (v: number | null) => renderDuration(v) },
    { title: t('jobs.executions.columns.triggerType'), dataIndex: 'triggerType', key: 'triggerType', minWidth: 110, render: (v: string) => <Tag>{v}</Tag> },
    { title: t('jobs.executions.columns.error'), dataIndex: 'error', key: 'error', minWidth: 240, ellipsis: true, render: (v: string | null) => v ?? '—' },
  ];

  return (
    <Drawer open onClose={onClose} width={880} title={t('jobs.executions.title', { key: jobKey })}>
      {/* 卡片不设标题（抽屉标题已承载语义），Card extra 仅承载刷新动作 */}
      <CrudTable<JobExecutionView>
        crud={crud}
        columns={columns}
        searchable={false}
        reloadAriaLabel={t('jobs.executions.reload')}
        totalText={(total) => t('jobs.executions.total', { total })}
      />
    </Drawer>
  );
}

/**
 * 定时任务管理（REQ-20261117-061017，权限点 infra:job:list / infra:job:run 分控）：
 * 任务清单（jobKey/描述/cron/最近执行摘要）+ 手动执行（Popconfirm 确认，infra:job:run）
 * + 执行记录抽屉（分页倒序）。
 */
export function JobsPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];

  const crud = useCrud<JobView>({
    // 任务清单为非分页数组（契约「列表」口径，同 OnlineUsers 装配）：数组信封兼容运行时校验
    fetchPage: async ({ signal }) => {
      const rows = await infraApi.jobList(signal);
      const records = Array.isArray(rows) ? rows : [];
      return { records, total: records.length };
    },
    permissions,
    errorMessage: t('jobs.errors.loadFailed'),
  });

  const [execJobKey, setExecJobKey] = useState<string | null>(null);

  const runJob = async (record: JobView) => {
    try {
      await infraApi.jobRun(record.key);
      message.success(t('jobs.toast.runTriggered', { key: record.key }));
      crud.reload();
    } catch (ex: unknown) {
      // 失败不重取（行保留）；错误码经 systemErrorMessage 统一转译（尾部附码）
      message.error(systemErrorMessage(ex, t('jobs.errors.runFailed')));
    }
  };

  const columns: ColumnsType<JobView> = [
    { title: t('jobs.columns.jobKey'), dataIndex: 'key', key: 'key', minWidth: 200, fixed: 'left', render: (v: string) => <Text code>{v}</Text> },
    { title: t('jobs.columns.description'), dataIndex: 'description', key: 'description', minWidth: 220, ellipsis: true, render: (v: string | null) => v ?? '—' },
    { title: t('jobs.columns.cron'), dataIndex: 'cron', key: 'cron', minWidth: 150, render: (v: string | null) => (v ? <Text code style={{ fontSize: 11 }}>{v}</Text> : '—') },
    { title: t('jobs.columns.lastStatus'), key: 'lastStatus', minWidth: 110, render: (_, record) => (record.lastExecution?.status ? <Tag color={TONE_ANTD[statusTone(record.lastExecution.status)]}>{record.lastExecution.status}</Tag> : '—') },
    { title: t('jobs.columns.lastStartedAt'), key: 'lastStartedAt', minWidth: 170, render: (_, record) => (record.lastExecution?.startedAt ? formatDate(record.lastExecution.startedAt) : '—') },
    { title: t('jobs.columns.lastError'), key: 'lastError', minWidth: 220, ellipsis: true, render: (_, record) => record.lastExecution?.error ?? '—' },
    {
      title: t('jobs.columns.actions'), key: 'actions', minWidth: 180, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          <Auth code="infra:job:run">
            <Popconfirm title={t('jobs.actions.runConfirm')} onConfirm={() => void runJob(record)}>
              <Button size="small">{t('jobs.actions.run')}</Button>
            </Popconfirm>
          </Auth>
          <Button size="small" onClick={() => setExecJobKey(record.key)}>
            {t('jobs.actions.executions')}
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <>
      <CrudTable<JobView>
        crud={crud}
        title={t('jobs.title')}
        columns={columns}
        searchable={false}
        paged={false}
        reloadAriaLabel={t('jobs.reload')}
        totalText={(total) => t('jobs.total', { total })}
      />
      {execJobKey ? (
        <JobExecutionsDrawer jobKey={execJobKey} onClose={() => setExecJobKey(null)} />
      ) : null}
    </>
  );
}
