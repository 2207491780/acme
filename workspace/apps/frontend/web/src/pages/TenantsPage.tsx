import { useState } from 'react';
import { App, Button, Form, Input, Popconfirm, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { ApiError, formatDate, systemApi, useCrud, useMeStore, type AdminTenantView } from '@acme/core';
import { CrudFormModal } from '../components/CrudFormModal';
import { CrudTable } from '../components/CrudTable';

const { Text } = Typography;

const CODE_PATTERN = /^[a-z][a-z0-9_-]*$/;

interface TenantFormValues {
  tenantCode: string;
  tenantName: string;
  remark?: string;
}

/** 租户管理（p2-admin）：分页/搜索/新建/改名/启停；无删除能力——停用代替（FR-007）。
 *  迁移 CRUD 范本（REQ-20261114-120101）：headless 状态机走 @acme/core useCrud，
 *  展示层为 CrudTable + CrudFormModal；动作按钮按 me.permissions 权限码显隐（fail-closed）。 */
export function TenantsPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];

  const crud = useCrud<AdminTenantView>({
    fetchPage: ({ page, size, keyword, signal }) =>
      systemApi.tenantList(page, size, keyword || undefined, signal),
    permissions,
    pageSize: 10,
    errorMessage: t('tenants.errors.loadFailed'),
  });

  const [editing, setEditing] = useState<AdminTenantView | null>(null);
  const [creating, setCreating] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<TenantFormValues>();

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    setCreating(true);
  };

  const openEdit = (record: AdminTenantView) => {
    setEditing(record);
    form.setFieldsValue({ tenantCode: record.tenantCode, tenantName: record.tenantName, remark: record.remark ?? undefined });
    setCreating(false);
  };

  const submit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (editing) {
        await systemApi.tenantUpdate(editing.id, {
          tenantName: values.tenantName,
          remark: values.remark,
          version: editing.version,
        });
        message.success(t('tenants.form.updated'));
      } else {
        await systemApi.tenantCreate(values);
        message.success(t('tenants.form.created'));
      }
      setEditing(null);
      setCreating(false);
      crud.reload(1);
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.operationFailed'));
    } finally {
      setSubmitting(false);
    }
  };

  const toggleStatus = async (record: AdminTenantView) => {
    const next = record.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
    try {
      await systemApi.tenantStatus(record.id, next);
      message.success(next === 'ENABLED' ? t('tenants.status.enabled') : t('tenants.status.disabled'));
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.operationFailed'));
    }
  };

  const columns: ColumnsType<AdminTenantView> = [
    { title: t('tenants.columns.tenantCode'), dataIndex: 'tenantCode', key: 'tenantCode', width: 160 },
    { title: t('tenants.columns.tenantName'), dataIndex: 'tenantName', key: 'tenantName' },
    {
      title: t('tenants.columns.status'), dataIndex: 'status', key: 'status', width: 100,
      render: (status: AdminTenantView['status']) => (
        <Tag color={status === 'ENABLED' ? 'success' : 'default'}>{status === 'ENABLED' ? t('common.status.enabled') : t('common.status.disabled')}</Tag>
      ),
    },
    { title: t('tenants.columns.remark'), dataIndex: 'remark', key: 'remark', ellipsis: true, render: (v: string | null) => v ?? '—' },
    { title: t('tenants.columns.createdAt'), dataIndex: 'createdAt', key: 'createdAt', width: 180, render: (v: string) => formatDate(v) },
    {
      title: t('tenants.columns.actions'), key: 'actions', width: 180,
      render: (_, record) => (
        <Space size={4}>
          {crud.can('system:tenant:update') ? <Button size="small" onClick={() => openEdit(record)}>{t('common.actions.edit')}</Button> : null}
          {crud.can('system:tenant:update') ? (
            <Popconfirm
              title={t('tenants.status.toggleConfirm', { action: record.status === 'ENABLED' ? t('common.status.disabled') : t('common.status.enabled'), code: record.tenantCode })}
              onConfirm={() => void toggleStatus(record)}
            >
              <Button size="small" danger={record.status === 'ENABLED'}>
                {record.status === 'ENABLED' ? t('common.status.disabled') : t('common.status.enabled')}
              </Button>
            </Popconfirm>
          ) : null}
        </Space>
      ),
    },
  ];

  return (
    <>
      <CrudTable<AdminTenantView>
        crud={crud}
        title={t('tenants.title')}
        columns={columns}
        searchPlaceholder={t('tenants.search.placeholder')}
        searchWidth={240}
        reloadAriaLabel={t('tenants.reload')}
        createPermission="system:tenant:create"
        createLabel={t('tenants.create')}
        onCreate={openCreate}
        totalText={(total) => t('tenants.total', { total })}
      />

      <CrudFormModal<TenantFormValues>
        open={creating || editing !== null}
        title={editing ? t('tenants.form.editTitle', { code: editing.tenantCode }) : t('tenants.form.createTitle')}
        form={form}
        submitting={submitting}
        onOk={() => void submit()}
        onCancel={() => { setCreating(false); setEditing(null); }}
      >
        <Form.Item
          name="tenantCode"
          label={t('tenants.form.code')}
          rules={[
            { required: true, message: t('tenants.form.codeRequired') },
            { pattern: CODE_PATTERN, message: t('common.validation.codePattern') },
            { min: 2, max: 64, message: t('common.validation.lengthRange', { min: 2, max: 64 }) },
          ]}
          extra={editing ? t('tenants.form.codeImmutable') : t('tenants.form.codeExtra')}
        >
          <Input placeholder={t('tenants.form.codePlaceholder')} disabled={editing !== null} autoComplete="off" />
        </Form.Item>
        <Form.Item
          name="tenantName"
          label={t('tenants.form.name')}
          rules={[
            { required: true, message: t('tenants.form.nameRequired') },
            { min: 1, max: 128, message: t('common.validation.lengthRange', { min: 1, max: 128 }) },
          ]}
        >
          <Input placeholder={t('tenants.form.namePlaceholder')} />
        </Form.Item>
        <Form.Item name="remark" label={t('tenants.form.remark')} rules={[{ max: 255, message: t('common.validation.maxLength', { max: 255 }) }]}>
          <Input.TextArea placeholder={t('common.validation.optional')} rows={2} />
        </Form.Item>
        {editing ? (
          <Text type="secondary" style={{ fontSize: 12 }}>
            {t('common.validation.optimisticLock', { version: editing.version })}{t('common.validation.optimisticLockConflict')}
          </Text>
        ) : null}
      </CrudFormModal>
    </>
  );
}
