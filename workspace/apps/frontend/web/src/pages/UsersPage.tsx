import { useEffect, useState } from 'react';
import { App, Button, Checkbox, Form, Input, Popconfirm, Select, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import {
  ApiError,
  formatDate,
  systemApi,
  useCrud,
  useMeStore,
  type AdminRoleView,
  type AdminUserView,
} from '@acme/core';
import { CrudFormModal } from '../components/CrudFormModal';
import { CrudTable } from '../components/CrudTable';

const { Text } = Typography;

type UserFilters = {
  status?: 'ENABLED' | 'DISABLED';
};

interface UserCreateValues {
  username: string;
  nickname: string;
  email?: string;
  phone?: string;
  roleIds: string[];
}

interface UserEditValues {
  nickname: string;
  email?: string;
  phone?: string;
}

interface RoleBindValues {
  roleIds: string[];
}

/** 用户管理（p2-admin）：分页/搜索/新建/编辑/启停/删除/角色绑定。
 *  通用 CRUD 范本：headless 状态机走 @acme/core useCrud，展示层为 CrudTable + CrudFormModal。
 *  角色选择器来自角色目录（/system/roles 首页大页）；内置账号（builtIn）禁止删除。
 *  动作按钮按 me.permissions 权限码显隐（fail-closed）。
 *  文案经 users 域资源包（zh 基线=既有用例断言原文，REQ-20261114-120103）。 */
export function UsersPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];

  const crud = useCrud<AdminUserView, UserFilters>({
    fetchPage: ({ page, size, keyword, filters, signal }) =>
      systemApi.userList(page, size, keyword || undefined, filters?.status, signal),
    filters: { status: undefined },
    permissions,
    pageSize: 10,
    errorMessage: t('users.errors.loadFailed'),
  });

  const [roles, setRoles] = useState<AdminRoleView[]>([]);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<AdminUserView | null>(null);
  const [binding, setBinding] = useState<AdminUserView | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [createForm] = Form.useForm<UserCreateValues>();
  const [editForm] = Form.useForm<UserEditValues>();
  const [bindForm] = Form.useForm<RoleBindValues>();

  // 权限就绪后加载角色目录（选择器数据源）
  useEffect(() => {
    if (crud.can('system:user:assign-role')) {
      systemApi.roleList()
        .then((result) => setRoles(result.records))
        .catch(() => setRoles([]));
    }
  }, [permissions.length]);

  const openCreate = () => {
    createForm.resetFields();
    createForm.setFieldsValue({ roleIds: [] });
    setCreating(true);
  };

  const openEdit = (record: AdminUserView) => {
    setEditing(record);
    editForm.setFieldsValue({
      nickname: record.nickname ?? undefined,
      email: record.email ?? undefined,
      phone: record.phone ?? undefined,
    });
  };

  const openBind = (record: AdminUserView) => {
    setBinding(record);
    bindForm.setFieldsValue({ roleIds: [...record.roleIds] });
  };

  const submitCreate = async () => {
    const values = await createForm.validateFields();
    setSubmitting(true);
    try {
      await systemApi.userCreate({
        username: values.username,
        nickname: values.nickname,
        email: values.email,
        phone: values.phone,
        roleIds: values.roleIds ?? [],
      });
      message.success(t('users.form.createSuccess', { name: values.username }));
      setCreating(false);
      crud.reload(1);
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.createFailed'));
    } finally {
      setSubmitting(false);
    }
  };

  const submitEdit = async () => {
    if (!editing) return;
    const values = await editForm.validateFields();
    setSubmitting(true);
    try {
      await systemApi.userUpdate(editing.id, {
        nickname: values.nickname,
        email: values.email,
        phone: values.phone,
        version: editing.version,
      });
      message.success(t('users.form.updateSuccess'));
      setEditing(null);
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.updateFailed'));
    } finally {
      setSubmitting(false);
    }
  };

  const submitBind = async () => {
    if (!binding) return;
    const values = await bindForm.validateFields();
    setSubmitting(true);
    try {
      await systemApi.userBindRoles(binding.id, values.roleIds ?? []);
      message.success(t('users.bind.success', { name: binding.username }));
      setBinding(null);
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('users.bind.failed'));
    } finally {
      setSubmitting(false);
    }
  };

  const toggleStatus = async (record: AdminUserView) => {
    const next = record.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
    try {
      await systemApi.userStatus(record.id, next);
      message.success(next === 'ENABLED' ? t('users.status.enabledDone') : t('users.status.disabledDone'));
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.operationFailed'));
    }
  };

  const remove = async (record: AdminUserView) => {
    try {
      await systemApi.userDelete(record.id);
      message.success(t('users.deleted', { name: record.username }));
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.deleteFailed'));
    }
  };

  const columns: ColumnsType<AdminUserView> = [
    { title: t('users.columns.username'), dataIndex: 'username', key: 'username', width: 160 },
    { title: t('users.columns.nickname'), dataIndex: 'nickname', key: 'nickname', render: (v: string | null) => v ?? '—' },
    {
      title: t('users.columns.status'), dataIndex: 'status', key: 'status', width: 90,
      render: (status: AdminUserView['status']) => (
        <Tag color={status === 'ENABLED' ? 'success' : 'default'}>{status === 'ENABLED' ? t('users.status.enabled') : t('users.status.disabled')}</Tag>
      ),
    },
    {
      title: t('users.columns.roles'), dataIndex: 'roleIds', key: 'roleIds',
      render: (_, record) => {
        const names = record.roleIds
          .map((id) => roles.find((r) => r.id === id)?.roleName ?? roles.find((r) => r.id === id)?.roleCode)
          .filter(Boolean);
        return names.length ? <Space size={4} wrap>{names.map((n) => <Tag key={n as string}>{n as string}</Tag>)}</Space> : '—';
      },
    },
    { title: t('users.columns.updatedAt'), dataIndex: 'updatedAt', key: 'updatedAt', width: 180, render: (v: string) => formatDate(v) },
    {
      title: t('users.columns.actions'), key: 'actions', width: 300,
      render: (_, record) => (
        <Space size={4}>
          {crud.can('system:user:update') ? <Button size="small" onClick={() => openEdit(record)}>{t('users.actions.edit')}</Button> : null}
          {crud.can('system:user:assign-role') ? <Button size="small" onClick={() => openBind(record)}>{t('users.actions.assignRoles')}</Button> : null}
          {crud.can('system:user:update') ? (
            <Popconfirm title={t('users.status.toggleConfirm', { action: record.status === 'ENABLED' ? t('users.status.disabled') : t('users.status.enabled'), name: record.username })} onConfirm={() => void toggleStatus(record)}>
              <Button size="small" danger={record.status === 'ENABLED'}>{record.status === 'ENABLED' ? t('users.status.disabled') : t('users.status.enabled')}</Button>
            </Popconfirm>
          ) : null}
          {crud.can('system:user:delete') && !record.builtIn ? (
            <Popconfirm title={t('users.actions.deleteConfirm', { name: record.username })} onConfirm={() => void remove(record)}>
              <Button size="small" danger>{t('users.actions.delete')}</Button>
            </Popconfirm>
          ) : null}
          {record.builtIn ? <Text type="secondary" style={{ fontSize: 12 }}>{t('users.actions.builtIn')}</Text> : null}
        </Space>
      ),
    },
  ];

  return (
    <>
      <CrudTable<AdminUserView, UserFilters>
        crud={crud}
        title={t('users.title')}
        columns={columns}
        searchPlaceholder={t('users.search.placeholder')}
        searchWidth={200}
        reloadAriaLabel={t('users.reload')}
        createPermission="system:user:create"
        createLabel={t('users.create')}
        onCreate={openCreate}
        totalText={(total) => t('users.total', { total })}
        filters={(
          <Select<'ENABLED' | 'DISABLED'>
            allowClear
            placeholder={t('users.filters.status')}
            style={{ width: 120 }}
            options={[
              { value: 'ENABLED', label: t('users.status.enabled') },
              { value: 'DISABLED', label: t('users.status.disabled') },
            ]}
            value={crud.filters.status}
            onChange={(value) => crud.setFilter('status', value)}
          />
        )}
      />

      <CrudFormModal<UserCreateValues>
        open={creating}
        title={t('users.create')}
        form={createForm}
        submitting={submitting}
        onOk={() => void submitCreate()}
        onCancel={() => setCreating(false)}
      >
        <Form.Item
          name="username"
          label={t('users.form.username')}
          rules={[
            { required: true, message: t('users.form.usernameRequired') },
            { min: 3, max: 64, message: t('common.validation.lengthRange', { min: 3, max: 64 }) },
          ]}
        >
          <Input placeholder={t('users.form.usernamePlaceholder')} autoComplete="off" />
        </Form.Item>
        <Form.Item
          name="nickname"
          label={t('users.form.nickname')}
          rules={[
            { required: true, message: t('users.form.nicknameRequired') },
            { min: 1, max: 64, message: t('common.validation.lengthRange', { min: 1, max: 64 }) },
          ]}
        >
          <Input placeholder={t('users.form.nicknamePlaceholder')} />
        </Form.Item>
        <Form.Item name="email" label={t('users.form.email')} rules={[{ type: 'email', message: t('users.form.emailInvalid') }, { max: 128, message: t('common.validation.maxLength', { max: 128 }) }]}>
          <Input placeholder={t('users.form.optional')} autoComplete="off" />
        </Form.Item>
        <Form.Item name="phone" label={t('users.form.phone')} rules={[{ max: 32, message: t('common.validation.maxLength', { max: 32 }) }]}>
          <Input placeholder={t('users.form.optional')} autoComplete="off" />
        </Form.Item>
        <Form.Item name="roleIds" label={t('users.form.roles')}>
          <Select
            mode="multiple"
            placeholder={t('users.form.rolesPlaceholder')}
            options={roles.filter((r) => r.status === 'ENABLED').map((r) => ({ value: r.id, label: r.roleName }))}
            optionFilterProp="label"
          />
        </Form.Item>
      </CrudFormModal>

      <CrudFormModal<UserEditValues>
        open={editing !== null}
        title={t('users.form.editTitle', { name: editing?.username ?? '' })}
        form={editForm}
        submitting={submitting}
        onOk={() => void submitEdit()}
        onCancel={() => setEditing(null)}
      >
        <Form.Item
          name="nickname"
          label={t('users.form.nickname')}
          rules={[
            { required: true, message: t('users.form.nicknameRequired') },
            { min: 1, max: 64, message: t('common.validation.lengthRange', { min: 1, max: 64 }) },
          ]}
        >
          <Input placeholder={t('users.form.nicknamePlaceholder')} />
        </Form.Item>
        <Form.Item name="email" label={t('users.form.email')} rules={[{ type: 'email', message: t('users.form.emailInvalid') }, { max: 128, message: t('common.validation.maxLength', { max: 128 }) }]}>
          <Input placeholder={t('users.form.optional')} />
        </Form.Item>
        <Form.Item name="phone" label={t('users.form.phone')} rules={[{ max: 32, message: t('common.validation.maxLength', { max: 32 }) }]}>
          <Input placeholder={t('users.form.optional')} />
        </Form.Item>
        <Text type="secondary" style={{ fontSize: 12 }}>
          {t('users.form.editLockNote', { version: editing?.version ?? '—' })}
        </Text>
      </CrudFormModal>

      <CrudFormModal<RoleBindValues>
        open={binding !== null}
        title={t('users.bind.title', { name: binding?.username ?? '' })}
        form={bindForm}
        submitting={submitting}
        onOk={() => void submitBind()}
        onCancel={() => setBinding(null)}
      >
        <Form.Item name="roleIds" label={t('users.bind.rolesLabel')} rules={[{ required: true, message: t('users.bind.rolesRequired') }]}>
          <Checkbox.Group style={{ display: 'grid', gap: 8 }}>
            {roles.filter((r) => r.status === 'ENABLED').map((r) => (
              <Checkbox key={r.id} value={r.id}>{`${r.roleName}（${r.roleCode}）`}</Checkbox>
            ))}
          </Checkbox.Group>
        </Form.Item>
        <Text type="secondary" style={{ fontSize: 12 }}>{t('users.bind.submitNote')}</Text>
      </CrudFormModal>
    </>
  );
}
