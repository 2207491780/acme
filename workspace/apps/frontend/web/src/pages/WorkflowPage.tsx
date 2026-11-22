import { useState } from 'react';
import { App, Button, Drawer, Form, Input, Popconfirm, Select, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { ApiError, Auth, formatDate, statusColor, systemErrorMessage, TONE_ANTD, useCrud, useMeStore, workflowApi, WORKFLOW_ERROR_CODES, workflowValidationErrorsOf } from '@acme/core';
import type { ProcessDefinitionView } from '@acme/core';
import { CrudTable } from '../components/CrudTable';

const { Text } = Typography;

/**
 * 流程定义列表页（REQ-20261122-023441 确认④，最小定义列表）：
 * key/name/version/status 分页过滤 + 操作列分控（设计=list、部署/停用/新建=deploy 79）
 * + 新建 Drawer → POST 创建草稿 → 跳转设计器空画布（AC-1/AC-2）。BPM-3 在此扩实例/版本管理。
 */

type DefinitionStatus = ProcessDefinitionView['status'];

/** 定义状态 → 语义 Tone（UI-SPEC §2.2：DRAFT=muted、DEPLOYED=success、DISABLED=danger）。 */
function statusToneOf(status: DefinitionStatus): keyof typeof TONE_ANTD {
  if (status === 'DEPLOYED') return 'success';
  if (status === 'DISABLED') return 'danger';
  return 'muted';
}

export function WorkflowPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];

  const crud = useCrud<ProcessDefinitionView, { status?: string }>({
    fetchPage: ({ page, size, keyword, filters, signal }) =>
      workflowApi.list(page, size, {
        keyword: keyword,
        status: (filters?.status as DefinitionStatus | undefined) ?? undefined,
      }, signal),
    filters: { status: undefined },
    permissions,
    errorMessage: t('workflow.list.errors.loadFailed'),
  });

  const [createOpen, setCreateOpen] = useState(false);
  const [createSubmitting, setCreateSubmitting] = useState(false);
  const [form] = Form.useForm<{ key: string; name: string }>();

  const openCreate = () => {
    form.resetFields();
    setCreateOpen(true);
  };

  const submitCreate = async () => {
    const values = await form.validateFields();
    setCreateSubmitting(true);
    try {
      const created = await workflowApi.create({ key: values.key.trim(), name: values.name.trim() });
      message.success(t('workflow.list.toast.created'));
      setCreateOpen(false);
      // AC-1：创建草稿后跳转设计器（definitionId 变化=同签页内路由替换）
      navigate(`/workflow/designer/${created.id}`);
      crud.reload();
    } catch (ex: unknown) {
      if (ex instanceof ApiError && ex.code === WORKFLOW_ERROR_CODES.KEY_DUPLICATE) {
        message.error(ex.message);
      } else {
        message.error(systemErrorMessage(ex, t('workflow.list.errors.createFailed')));
      }
    } finally {
      setCreateSubmitting(false);
    }
  };

  const deployDefinition = async (record: ProcessDefinitionView) => {
    try {
      await workflowApi.deploy(record.id);
      message.success(t('workflow.list.toast.deployed'));
      crud.reload();
    } catch (ex: unknown) {
      if (ex instanceof ApiError && ex.code === WORKFLOW_ERROR_CODES.VALIDATION_FAILED) {
        const details = workflowValidationErrorsOf(ex);
        message.error(`${ex.message}（${details.length}）`);
      } else {
        message.error(systemErrorMessage(ex, t('workflow.list.errors.deployFailed')));
      }
    }
  };

  const disableDefinition = async (record: ProcessDefinitionView) => {
    try {
      await workflowApi.disable(record.id);
      message.success(t('workflow.list.toast.disabled'));
      crud.reload();
    } catch (ex: unknown) {
      message.error(systemErrorMessage(ex, t('workflow.list.errors.disableFailed')));
    }
  };

  const columns: ColumnsType<ProcessDefinitionView> = [
    { title: t('workflow.list.columns.key'), dataIndex: 'key', key: 'key', minWidth: 160, fixed: 'left', render: (v: string) => <Text code>{v}</Text> },
    { title: t('workflow.list.columns.name'), dataIndex: 'name', key: 'name', minWidth: 180, ellipsis: true },
    { title: t('workflow.list.columns.version'), dataIndex: 'version', key: 'version', minWidth: 80, render: (v: string) => <Tag>v{v}</Tag> },
    {
      title: t('workflow.list.columns.status'), dataIndex: 'status', key: 'status', minWidth: 110,
      render: (v: DefinitionStatus) => <Tag color={TONE_ANTD[statusToneOf(v)]} style={{ color: statusColor(v) }}>{t(`workflow.list.status.${v}`)}</Tag>,
    },
    { title: t('workflow.list.columns.createdAt'), dataIndex: 'createdAt', key: 'createdAt', minWidth: 170, render: (v: string) => formatDate(v) },
    { title: t('workflow.list.columns.deployedAt'), dataIndex: 'deployedAt', key: 'deployedAt', minWidth: 170, render: (v: string | null) => (v ? formatDate(v) : '—') },
    {
      title: t('workflow.list.columns.actions'), key: 'actions', minWidth: 210, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          <Button size="small" onClick={() => navigate(`/workflow/designer/${record.id}`)}>
            {t('workflow.list.actions.design')}
          </Button>
          <Auth code="workflow:definition:deploy">
            <Popconfirm title={t('workflow.list.confirm.deploy')} onConfirm={() => void deployDefinition(record)}>
              <Button size="small">{t('workflow.list.actions.deploy')}</Button>
            </Popconfirm>
          </Auth>
          <Auth code="workflow:definition:deploy">
            <Popconfirm title={t('workflow.list.confirm.disable')} onConfirm={() => void disableDefinition(record)}>
              <Button size="small" danger>{t('workflow.list.actions.disable')}</Button>
            </Popconfirm>
          </Auth>
        </Space>
      ),
    },
  ];

  return (
    <>
      <CrudTable<ProcessDefinitionView, { status?: string }>
        crud={crud}
        title={t('workflow.list.title')}
        columns={columns}
        searchPlaceholder={t('workflow.list.filter.keywordPlaceholder')}
        reloadAriaLabel={t('workflow.list.errors.reloadAria')}
        createPermission="workflow:definition:deploy"
        createLabel={t('workflow.list.create.button')}
        onCreate={openCreate}
        filters={(
          <Form.Item label={t('workflow.list.filter.status')} style={{ marginBottom: 0 }}>
            <Select
              style={{ minWidth: 140 }}
              allowClear
              placeholder={t('workflow.list.filter.statusAll')}
              value={crud.filters.status}
              onChange={(value) => crud.setFilter('status', value)}
              options={[
                { value: 'DRAFT', label: t('workflow.list.status.DRAFT') },
                { value: 'DEPLOYED', label: t('workflow.list.status.DEPLOYED') },
                { value: 'DISABLED', label: t('workflow.list.status.DISABLED') },
              ]}
            />
          </Form.Item>
        )}
      />
      <Drawer
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title={t('workflow.list.create.title')}
        width={420}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="key"
            label={t('workflow.list.create.key')}
            rules={[
              { required: true },
              { max: 64 },
              { pattern: /^[a-zA-Z][a-zA-Z0-9-]*$/, message: 'key 仅限字母/数字/连字符且字母开头' },
            ]}
          >
            <Input allowClear placeholder={t('workflow.list.create.keyPlaceholder')} />
          </Form.Item>
          <Form.Item name="name" label={t('workflow.list.create.name')} rules={[{ required: true }, { max: 128 }]}>
            <Input allowClear placeholder={t('workflow.list.create.namePlaceholder')} />
          </Form.Item>
          <Space>
            <Button type="primary" loading={createSubmitting} onClick={() => void submitCreate()}>
              {t('workflow.list.create.submit')}
            </Button>
            <Button onClick={() => setCreateOpen(false)}>{t('workflow.list.create.cancel')}</Button>
          </Space>
        </Form>
      </Drawer>
    </>
  );
}
