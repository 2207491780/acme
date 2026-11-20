import { useId, useState } from 'react';
import { App, Button, Form, Input, Popconfirm, Select, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { ApiError, Auth, systemApi, useCrud, useMeStore, type AdminPermissionView } from '@acme/core';
import { CrudFormModal } from '../components/CrudFormModal';
import { CrudTable } from '../components/CrudTable';
import { FilterField } from '../components/FilterPanel';

const { Text } = Typography;

const CODE_PATTERN = /^[a-z][a-z0-9:-]*$/;

/** 权限类型选项（value 恒为 API；label 文案经 i18n）。 */
const PERMISSION_TYPE_API = 'API';

/** HTTP 方法筛选项（与后端动词白名单一致）。 */
const HTTP_METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const;

interface PermissionFormValues {
  permissionCode: string;
  permissionName: string;
  permissionType: string;
  httpMethod?: string;
  httpPath?: string;
}

/** 进阶筛选集合（keyword 走 crud.search，不进 filters）。 */
type PermissionFilters = {
  status?: 'ENABLED' | 'DISABLED';
  httpMethod?: string;
};

/** 权限点管理（p3-admin）：平台级目录分页 + 新建/编辑/删除。
 *  code 创建后不可改；编辑仅名称与方法/路径（version 乐观锁）。
 *  迁移 CRUD 范本（REQ-20261114-120101）：useCrud + CrudTable/CrudFormModal。
 *  筛选（REQ-20261120-214653）：关键字搜索 + 状态/HTTP 方法下拉（交互形态参照 UsersPage：
 *  搜索框在基础行、下拉在进阶筛选区）+ 重置入口；空值不进查询串。 */
export function PermissionsPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];
  const filterId = useId();

  const crud = useCrud<AdminPermissionView, PermissionFilters>({
    fetchPage: ({ page, size, keyword, filters, signal }) =>
      systemApi.permissionList(page, size, signal, {
        keyword: keyword || undefined,
        status: filters?.status,
        httpMethod: filters?.httpMethod,
      }),
    filters: { status: undefined, httpMethod: undefined },
    permissions,
    pageSize: 50,
    errorMessage: t('permissions.errors.loadFailed'),
  });

  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<AdminPermissionView | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<PermissionFormValues>();

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    setCreating(true);
  };

  const openEdit = (record: AdminPermissionView) => {
    setEditing(record);
    form.setFieldsValue({ ...record, httpMethod: record.httpMethod ?? undefined, httpPath: record.httpPath ?? undefined });
    setCreating(false);
  };

  const submit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (editing) {
        await systemApi.permissionUpdate(editing.id, {
          permissionName: values.permissionName,
          httpMethod: values.httpMethod,
          httpPath: values.httpPath,
          version: editing.version,
        });
        message.success(t('permissions.form.updated'));
      } else {
        await systemApi.permissionCreate(values);
        message.success(t('permissions.form.created'));
      }
      setEditing(null);
      setCreating(false);
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.operationFailed'));
    } finally {
      setSubmitting(false);
    }
  };

  const remove = async (record: AdminPermissionView) => {
    try {
      await systemApi.permissionDelete(record.id);
      message.success(t('permissions.deleted', { code: record.permissionCode }));
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.deleteFailed'));
    }
  };

  /** 重置入口（REQ-20261120-214653 AC-12）：清空关键字与全部筛选并回到第 1 页（同一事件批合并为一次拉取）。 */
  const resetAll = () => {
    crud.search('');
    crud.resetFilters();
  };

  const columns: ColumnsType<AdminPermissionView> = [
    { title: t('permissions.columns.permissionCode'), dataIndex: 'permissionCode', key: 'permissionCode', minWidth: 220, fixed: 'left' },
    { title: t('permissions.columns.permissionName'), dataIndex: 'permissionName', key: 'permissionName', minWidth: 160 },
    {
      title: t('permissions.columns.status'), dataIndex: 'status', key: 'status', minWidth: 90,
      render: (status: AdminPermissionView['status']) => (
        <Tag color={status === 'ENABLED' ? 'success' : 'default'}>
          {status === 'ENABLED' ? t('permissions.status.enabled') : t('permissions.status.disabled')}
        </Tag>
      ),
    },
    { title: t('permissions.columns.permissionType'), dataIndex: 'permissionType', key: 'permissionType', minWidth: 110, render: (v: string) => <Tag>{v}</Tag> },
    {
      title: t('permissions.columns.http'), key: 'http', minWidth: 220,
      render: (_, record) => (record.httpMethod ? <Text code>{record.httpMethod} {record.httpPath}</Text> : '—'),
    },
    {
      title: t('permissions.columns.actions'), key: 'actions', minWidth: 140, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          <Auth code="system:permission:update">
            <Button size="small" onClick={() => openEdit(record)}>{t('permissions.actions.edit')}</Button>
          </Auth>
          <Auth code="system:permission:delete">
            <Popconfirm title={t('permissions.actions.deleteConfirm', { code: record.permissionCode })} onConfirm={() => void remove(record)}>
              <Button size="small" danger>{t('permissions.actions.delete')}</Button>
            </Popconfirm>
          </Auth>
        </Space>
      ),
    },
  ];

  return (
    <>
      <CrudTable<AdminPermissionView, PermissionFilters>
        crud={crud}
        tableKey="permissions"
        title={t('permissions.title')}
        columns={columns}
        searchPlaceholder={t('permissions.search.placeholder')}
        reloadAriaLabel={t('permissions.reload')}
        createPermission="system:permission:create"
        createLabel={t('permissions.create')}
        onCreate={openCreate}
        totalText={(total) => t('permissions.total', { total })}
        filters={(
          <>
            <FilterField label={t('permissions.columns.status')} htmlFor={`${filterId}-status`}>
              <Select<'ENABLED' | 'DISABLED'>
                id={`${filterId}-status`}
                allowClear
                aria-label={t('permissions.filters.status')}
                placeholder={t('permissions.filters.status')}
                options={[
                  { value: 'ENABLED', label: t('permissions.status.enabled') },
                  { value: 'DISABLED', label: t('permissions.status.disabled') },
                ]}
                value={crud.filters.status}
                onChange={(value) => crud.setFilter('status', value)}
              />
            </FilterField>
            <FilterField label={t('permissions.form.httpMethod')} htmlFor={`${filterId}-http-method`}>
              <Select
                id={`${filterId}-http-method`}
                allowClear
                aria-label={t('permissions.filters.httpMethod')}
                placeholder={t('permissions.filters.httpMethod')}
                options={HTTP_METHODS.map((m) => ({ value: m, label: m }))}
                value={crud.filters.httpMethod}
                onChange={(value) => crud.setFilter('httpMethod', value)}
              />
            </FilterField>
            <div className="filter-field">
              <Button onClick={resetAll}>{t('common.actions.reset')}</Button>
            </div>
          </>
        )}
      />

      <CrudFormModal<PermissionFormValues>
        open={creating || editing !== null}
        title={editing ? t('permissions.form.editTitle', { code: editing.permissionCode }) : t('permissions.form.createTitle')}
        form={form}
        submitting={submitting}
        onOk={() => void submit()}
        onCancel={() => { setCreating(false); setEditing(null); }}
      >
        <Form.Item
          name="permissionCode"
          label={t('permissions.form.code')}
          rules={[
            { required: true, message: t('permissions.form.codeRequired') },
            { pattern: CODE_PATTERN, message: t('common.validation.codePatternColon') },
            { min: 3, max: 128, message: t('common.validation.lengthRange', { min: 3, max: 128 }) },
          ]}
          extra={editing ? t('permissions.form.codeImmutable') : t('permissions.form.codeExtra')}
        >
          <Input placeholder={t('permissions.form.codePlaceholder')} disabled={editing !== null} autoComplete="off" />
        </Form.Item>
        <Form.Item
          name="permissionName"
          label={t('permissions.form.name')}
          rules={[
            { required: true, message: t('permissions.form.nameRequired') },
            { min: 1, max: 64, message: t('common.validation.lengthRange', { min: 1, max: 64 }) },
          ]}
        >
          <Input placeholder={t('permissions.form.namePlaceholder')} />
        </Form.Item>
        <Form.Item name="permissionType" label={t('permissions.form.type')} rules={[{ required: true, message: t('permissions.form.typeRequired') }]}>
          <Select
            options={[{ value: PERMISSION_TYPE_API, label: t('permissions.form.typeApi') }]}
            placeholder={t('permissions.form.typePlaceholder')}
          />
        </Form.Item>
        <Space.Compact style={{ width: '100%' }}>
          <Form.Item name="httpMethod" label={t('permissions.form.httpMethod')} style={{ width: 140 }}>
            <Select
              allowClear
              placeholder={t('permissions.form.httpMethodPlaceholder')}
              options={HTTP_METHODS.map((m) => ({ value: m, label: m }))}
            />
          </Form.Item>
          <Form.Item name="httpPath" label={t('permissions.form.httpPath')} style={{ flex: 1 }} rules={[{ max: 255, message: t('common.validation.maxLength', { max: 255 }) }]}>
            <Input placeholder={t('permissions.form.httpPathPlaceholder')} autoComplete="off" />
          </Form.Item>
        </Space.Compact>
        {editing ? (
          <Text type="secondary" style={{ fontSize: 12 }}>{t('permissions.form.lockNote', { version: editing.version })}</Text>
        ) : null}
      </CrudFormModal>
    </>
  );
}
