import { useState } from 'react';
import { App, Button, Form, Input, Popconfirm, Select, Space, Switch, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import {
  Auth,
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
 *  REQ-20261124-213008 范式 C：「状态」列改为启停 Switch（checked=ENABLED；无 system:position:update
 *  权限置 disabled；成功后刷新列表；失败开关保持切换前值并转译提示——受控值不本地翻转）。
 *  被用户引用的岗位删除由后端拒绝（POSITION_IN_USE）→ 统一转译反馈（system-errors，尾部附码）。 */
export function PositionsPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];
  const canToggle = permissions.includes('system:position:update');

  const crud = useCrud<AdminPositionView>({
    fetchPage: ({ page, size, keyword, signal }) =>
      systemApi.positionList(page, size, keyword || undefined, signal),
    permissions,
    errorMessage: t('positions.errors.loadFailed'),
  });

  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<AdminPositionView | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [togglingId, setTogglingId] = useState<string | null>(null);
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

  /** 启停切换（REQ-20261124-213008 契约③）：成功刷新列表；失败转译提示（开关受控值不翻转=
   *  自动保持切换前值，AC-8 回滚口径）。 */
  const toggleStatus = async (record: AdminPositionView) => {
    const next = record.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
    setTogglingId(record.id);
    try {
      await systemApi.positionStatus(record.id, next);
      message.success(next === 'ENABLED' ? t('positions.status.enabledDone') : t('positions.status.disabledDone'));
      crud.reload();
    } catch (ex: unknown) {
      message.error(systemErrorMessage(ex, t('positions.errors.statusFailed')));
    } finally {
      setTogglingId(null);
    }
  };

  const columns: ColumnsType<AdminPositionView> = [
    { title: t('positions.columns.positionCode'), dataIndex: 'positionCode', key: 'positionCode', minWidth: 160, fixed: 'left' },
    { title: t('positions.columns.positionName'), dataIndex: 'positionName', key: 'positionName', minWidth: 160 },
    {
      title: t('positions.columns.status'), dataIndex: 'status', key: 'status', minWidth: 90,
      render: (status: AdminPositionView['status'], record) => (
        <Switch
          size="small"
          checked={status === 'ENABLED'}
          disabled={!canToggle}
          loading={togglingId === record.id}
          aria-label={`${record.positionName} ${t('positions.columns.status')}`}
          onChange={() => void toggleStatus(record)}
        />
      ),
    },
    { title: t('positions.columns.createdAt'), dataIndex: 'createdAt', key: 'createdAt', minWidth: 180, render: (v: string) => formatDate(v) },
    {
      title: t('positions.columns.actions'), key: 'actions', minWidth: 160, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          <Auth code="system:position:update">
            <Button size="small" onClick={() => openEdit(record)}>{t('positions.actions.edit')}</Button>
          </Auth>
          <Auth code="system:position:delete">
            <Popconfirm title={t('positions.actions.deleteConfirm', { name: record.positionName })} onConfirm={() => void remove(record)}>
              <Button size="small" danger>{t('positions.actions.delete')}</Button>
            </Popconfirm>
          </Auth>
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
