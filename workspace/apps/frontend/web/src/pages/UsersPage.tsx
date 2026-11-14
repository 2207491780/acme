import { useEffect, useState } from 'react';
import { App, Button, Checkbox, Form, Input, Popconfirm, Select, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
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
 *  动作按钮按 me.permissions 权限码显隐（fail-closed）。 */
export function UsersPage() {
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions ?? []);

  const crud = useCrud<AdminUserView, UserFilters>({
    fetchPage: ({ page, size, keyword, filters }) =>
      systemApi.userList(page, size, keyword || undefined, filters?.status),
    filters: { status: undefined },
    permissions,
    pageSize: 10,
    errorMessage: '用户清单加载失败',
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
    if (crud.can('USER_ASSIGN_ROLE')) {
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
      message.success(`用户 ${values.username} 已创建（首密码请经「重置密码」流程下发）`);
      setCreating(false);
      crud.reload(1);
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : '创建失败，请稍后重试');
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
      message.success('用户已更新');
      setEditing(null);
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : '更新失败，请稍后重试');
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
      message.success(`用户 ${binding.username} 角色已更新`);
      setBinding(null);
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : '角色绑定失败，请稍后重试');
    } finally {
      setSubmitting(false);
    }
  };

  const toggleStatus = async (record: AdminUserView) => {
    const next = record.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
    try {
      await systemApi.userStatus(record.id, next);
      message.success(`用户已${next === 'ENABLED' ? '启用' : '停用'}`);
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : '操作失败，请稍后重试');
    }
  };

  const remove = async (record: AdminUserView) => {
    try {
      await systemApi.userDelete(record.id);
      message.success(`用户 ${record.username} 已删除`);
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : '删除失败，请稍后重试');
    }
  };

  const columns: ColumnsType<AdminUserView> = [
    { title: '账号', dataIndex: 'username', key: 'username', width: 160 },
    { title: '昵称', dataIndex: 'nickname', key: 'nickname', render: (v: string | null) => v ?? '—' },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 90,
      render: (status: AdminUserView['status']) => (
        <Tag color={status === 'ENABLED' ? 'success' : 'default'}>{status === 'ENABLED' ? '启用' : '停用'}</Tag>
      ),
    },
    {
      title: '角色', dataIndex: 'roleIds', key: 'roleIds',
      render: (_, record) => {
        const names = record.roleIds
          .map((id) => roles.find((r) => r.id === id)?.roleName ?? roles.find((r) => r.id === id)?.roleCode)
          .filter(Boolean);
        return names.length ? <Space size={4} wrap>{names.map((n) => <Tag key={n as string}>{n as string}</Tag>)}</Space> : '—';
      },
    },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', width: 180, render: (v: string) => formatDate(v) },
    {
      title: '操作', key: 'actions', width: 300,
      render: (_, record) => (
        <Space size={4}>
          {crud.can('USER_UPDATE') ? <Button size="small" onClick={() => openEdit(record)}>编辑</Button> : null}
          {crud.can('USER_ASSIGN_ROLE') ? <Button size="small" onClick={() => openBind(record)}>分配角色</Button> : null}
          {crud.can('USER_UPDATE') ? (
            <Popconfirm title={`确认${record.status === 'ENABLED' ? '停用' : '启用'} ${record.username}？`} onConfirm={() => void toggleStatus(record)}>
              <Button size="small" danger={record.status === 'ENABLED'}>{record.status === 'ENABLED' ? '停用' : '启用'}</Button>
            </Popconfirm>
          ) : null}
          {crud.can('USER_DELETE') && !record.builtIn ? (
            <Popconfirm title={`确认删除用户 ${record.username}？`} onConfirm={() => void remove(record)}>
              <Button size="small" danger>删除</Button>
            </Popconfirm>
          ) : null}
          {record.builtIn ? <Text type="secondary" style={{ fontSize: 12 }}>内置</Text> : null}
        </Space>
      ),
    },
  ];

  return (
    <>
      <CrudTable<AdminUserView, UserFilters>
        crud={crud}
        title="用户管理"
        columns={columns}
        searchPlaceholder="搜索账号"
        searchWidth={200}
        reloadAriaLabel="刷新用户清单"
        createPermission="USER_CREATE"
        createLabel="新建用户"
        onCreate={openCreate}
        totalText={(t) => `共 ${t} 个用户`}
        filters={(
          <Select<'ENABLED' | 'DISABLED'>
            allowClear
            placeholder="状态过滤"
            style={{ width: 120 }}
            options={[
              { value: 'ENABLED', label: '启用' },
              { value: 'DISABLED', label: '停用' },
            ]}
            value={crud.filters.status}
            onChange={(value) => crud.setFilter('status', value)}
          />
        )}
      />

      <CrudFormModal<UserCreateValues>
        open={creating}
        title="新建用户"
        form={createForm}
        submitting={submitting}
        onOk={() => void submitCreate()}
        onCancel={() => setCreating(false)}
      >
        <Form.Item
          name="username"
          label="账号"
          rules={[
            { required: true, message: '请输入账号' },
            { min: 3, max: 64, message: '长度 3–64 字符' },
          ]}
        >
          <Input placeholder="登录账号（创建后不可改）" autoComplete="off" />
        </Form.Item>
        <Form.Item
          name="nickname"
          label="昵称"
          rules={[
            { required: true, message: '请输入昵称' },
            { min: 1, max: 64, message: '长度 1–64 字符' },
          ]}
        >
          <Input placeholder="显示昵称" />
        </Form.Item>
        <Form.Item name="email" label="邮箱" rules={[{ type: 'email', message: '邮箱格式不正确' }, { max: 128, message: '长度不超过 128 字符' }]}>
          <Input placeholder="选填" autoComplete="off" />
        </Form.Item>
        <Form.Item name="phone" label="手机号" rules={[{ max: 32, message: '长度不超过 32 字符' }]}>
          <Input placeholder="选填" autoComplete="off" />
        </Form.Item>
        <Form.Item name="roleIds" label="角色">
          <Select
            mode="multiple"
            placeholder="选择角色（可多选）"
            options={roles.filter((r) => r.status === 'ENABLED').map((r) => ({ value: r.id, label: r.roleName }))}
            optionFilterProp="label"
          />
        </Form.Item>
      </CrudFormModal>

      <CrudFormModal<UserEditValues>
        open={editing !== null}
        title={`编辑用户：${editing?.username ?? ''}`}
        form={editForm}
        submitting={submitting}
        onOk={() => void submitEdit()}
        onCancel={() => setEditing(null)}
      >
        <Form.Item
          name="nickname"
          label="昵称"
          rules={[
            { required: true, message: '请输入昵称' },
            { min: 1, max: 64, message: '长度 1–64 字符' },
          ]}
        >
          <Input placeholder="显示昵称" />
        </Form.Item>
        <Form.Item name="email" label="邮箱" rules={[{ type: 'email', message: '邮箱格式不正确' }, { max: 128, message: '长度不超过 128 字符' }]}>
          <Input placeholder="选填" />
        </Form.Item>
        <Form.Item name="phone" label="手机号" rules={[{ max: 32, message: '长度不超过 32 字符' }]}>
          <Input placeholder="选填" />
        </Form.Item>
        <Text type="secondary" style={{ fontSize: 12 }}>
          按乐观锁提交（版本 {editing?.version ?? '—'}）；账号与角色绑定不可在此修改。
        </Text>
      </CrudFormModal>

      <CrudFormModal<RoleBindValues>
        open={binding !== null}
        title={`分配角色：${binding?.username ?? ''}`}
        form={bindForm}
        submitting={submitting}
        onOk={() => void submitBind()}
        onCancel={() => setBinding(null)}
      >
        <Form.Item name="roleIds" label="角色（全量覆盖）" rules={[{ required: true, message: '至少选择一个角色' }]}>
          <Checkbox.Group style={{ display: 'grid', gap: 8 }}>
            {roles.filter((r) => r.status === 'ENABLED').map((r) => (
              <Checkbox key={r.id} value={r.id}>{`${r.roleName}（${r.roleCode}）`}</Checkbox>
            ))}
          </Checkbox.Group>
        </Form.Item>
        <Text type="secondary" style={{ fontSize: 12 }}>提交即全量覆盖该用户的角色绑定；权限码随下次令牌签发生效。</Text>
      </CrudFormModal>
    </>
  );
}
