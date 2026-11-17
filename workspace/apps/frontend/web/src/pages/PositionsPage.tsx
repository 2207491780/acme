import { useState } from 'react';
import { App, Button, Form, Input, Popconfirm, Select, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import {
  formatDate,
  systemApi,
  systemErrorMessage,
  useCrud,
  useMeStore,
  type AdminPositionView,
} from '@acme/core';
import { CrudFormModal } from '../components/CrudFormModal';
import { CrudTable } from '../components/CrudTable';

const { Text } = Typography;

interface PositionFormValues {
  positionCode: string;
  positionName: string;
  status?: 'ENABLED' | 'DISABLED';
}

/** 岗位管理（REQ-20261114-120104）：分页/搜索/新建/编辑/删除（标准 CRUD 分页范本）；
 *  被用户引用的岗位删除由后端拒绝（POSITION_IN_USE）→ 统一转译反馈（system-errors，尾部附码）。 */
export function PositionsPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];

  const crud = useCrud<AdminPositionView>({
    fetchPage: ({ page, size, keyword, signal }) =>
      systemApi.positionList(page, size, keyword || undefined, signal),
    permissions,
    errorMessage: t('positions.errors.loadFailed'),
  });

  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<AdminPositionView | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<PositionFormValues>();

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ status: 'ENABLED' });
    setCreating(true);
  };

  const openEdit = (record: AdminPositionView) => {
    setEditing(record);
    form.setFieldsValue({
      positionName: record.positionName,
      positionCode: record.positionCode,
      status: record.status ?? 'ENABLED',
    });
    setCreating(false);
  };

  const submit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (editing) {
        await systemApi.positionUpdate(editing.id, {
          positionCode: values.positionCode,
          positionName: values.positionName,
          status: values.status ?? 'ENABLED',
          version: editing.version,
        });
        message.success(t('positions.form.updated'));
      } else {
        await systemApi.positionCreate({
          positionCode: values.positionCode,
          positionName: values.positionName,
          status: values.status ?? 'ENABLED',
        });
        message.success(t('positions.form.created'));
      }
      setEditing(null);
      setCreating(false);
      crud.reload();
    } catch (ex: unknown) {
      message.error(systemErrorMessage(ex, t('common.errors.operationFailed')));
    } finally {
      setSubmitting(false);
    }
  };

  const remove = async (record: AdminPositionView) => {
    try {
      await systemApi.positionDelete(record.id);
      message.success(t('positions.deleted', { name: record.positionName }));
      crud.reload();
    } catch (ex: unknown) {
      // 删除守卫（POSITION_IN_USE）→ 统一转译反馈；失败不重取（行保留）
      message.error(systemErrorMessage(ex, t('positions.errors.deleteFailed')));
    }
  };

  const columns: ColumnsType<AdminPositionView> = [
    { title: t('positions.columns.positionCode'), dataIndex: 'positionCode', key: 'positionCode', minWidth: 160, fixed: 'left' },
    { title: t('positions.columns.positionName'), dataIndex: 'positionName', key: 'positionName', minWidth: 160 },
    {
      title: t('positions.columns.status'), dataIndex: 'status', key: 'status', minWidth: 90,
      render: (status: AdminPositionView['status']) => (
        <Tag color={status === 'ENABLED' ? 'success' : 'default'}>{status === 'ENABLED' ? t('common.status.enabled') : t('common.status.disabled')}</Tag>
      ),
    },
    { title: t('positions.columns.createdAt'), dataIndex: 'createdAt', key: 'createdAt', minWidth: 180, render: (v: string) => formatDate(v) },
    {
      title: t('positions.columns.actions'), key: 'actions', minWidth: 160, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          {crud.can('system:position:update') ? <Button size="small" onClick={() => openEdit(record)}>{t('positions.actions.edit')}</Button> : null}
          {crud.can('system:position:delete') ? (
            <Popconfirm title={t('positions.actions.deleteConfirm', { name: record.positionName })} onConfirm={() => void remove(record)}>
              <Button size="small" danger>{t('positions.actions.delete')}</Button>
            </Popconfirm>
          ) : null}
        </Space>
      ),
    },
  ];

  return (
    <>
      <CrudTable<AdminPositionView>
        crud={crud}
        tableKey="positions"
        title={t('positions.title')}
        columns={columns}
        searchPlaceholder={t('positions.search.placeholder')}
        searchWidth={200}
        reloadAriaLabel={t('positions.reload')}
        createPermission="system:position:create"
        createLabel={t('positions.create')}
        onCreate={openCreate}
        totalText={(total) => t('positions.total', { total })}
      />

      <CrudFormModal<PositionFormValues>
        open={creating || editing !== null}
        title={editing ? t('positions.form.editTitle', { name: editing.positionName }) : t('positions.form.createTitle')}
        form={form}
        submitting={submitting}
        onOk={() => void submit()}
        onCancel={() => { setCreating(false); setEditing(null); }}
      >
        <Form.Item name="positionName" label={t('positions.form.name')} rules={[{ required: true, message: t('positions.form.nameRequired') }, { min: 1, max: 64, message: t('common.validation.lengthRange', { min: 1, max: 64 }) }]}>
          <Input placeholder={t('positions.form.namePlaceholder')} />
        </Form.Item>
        <Form.Item name="positionCode" label={t('positions.form.code')} rules={[{ required: true, message: t('positions.form.codeRequired') }, { min: 2, max: 64, message: t('common.validation.lengthRange', { min: 2, max: 64 }) }]}>
          <Input placeholder={t('positions.form.codePlaceholder')} autoComplete="off" />
        </Form.Item>
        <Form.Item name="status" label={t('positions.form.status')}>
          <Select
            options={[
              { value: 'ENABLED', label: t('common.status.enabled') },
              { value: 'DISABLED', label: t('common.status.disabled') },
            ]}
          />
        </Form.Item>
        {editing ? (
          <Text type="secondary" style={{ fontSize: 12 }}>{t('positions.form.lockNote', { version: editing.version })}</Text>
        ) : null}
      </CrudFormModal>
    </>
  );
}
