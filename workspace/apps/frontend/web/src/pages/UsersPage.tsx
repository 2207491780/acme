import { useCallback, useEffect, useId, useMemo, useState } from 'react';
import { Alert, App, Button, Checkbox, Form, Input, Popconfirm, Select, Space, Tag, TreeSelect, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { ApiError, Auth, formatDate, systemApi, useCrud, useMeStore, type AdminDeptView, type AdminUserView, type UserFormOptionsView } from '@acme/core';
import { CrudFormModal } from '../components/CrudFormModal';
import { CrudTable } from '../components/CrudTable';
import { FilterField } from '../components/FilterPanel';
import { toDeptTreeOptions } from '../utils/dept';

const { Text } = Typography;

type UserFilters = {
  status?: 'ENABLED' | 'DISABLED';
  deptId?: string;
};

interface UserCreateValues {
  username: string;
  nickname: string;
  email?: string;
  phone?: string;
  roleIds: string[];
  deptId?: string;
  positionId?: string;
}

interface UserEditValues {
  nickname: string;
  email?: string;
  phone?: string;
  deptId?: string;
  positionId?: string;
}

interface RoleBindValues {
  roleIds: string[];
}

interface ResetPasswordValues {
  password: string;
}

/** 用户管理（p2-admin）：分页/搜索/新建/编辑/启停/删除/角色绑定。
 *  通用 CRUD 范本：headless 状态机走 @acme/core useCrud，展示层为 CrudTable + CrudFormModal。
 *  角色选择器来自角色目录；内置账号（builtIn）禁止删除。
 *  REQ-20261125-230807（AC-1/2/3/27）：目录源统一走 GET /system/user-form-options
 *  （权限挂 system:user:list，替代 dept:list/position:list/角色分页三路依赖）——
 *  ① 列表新增「部门」「岗位」列（映射自目录源，空值 —）；② 部门选择改 TreeSelect 树形
 *  （层级展示+搜索，treeNodeLabelProp="pathTitle" 层级路径回显，选项文本无前导空格缩进）；③ 目录加载失败
 *  页面可见警告+重试，不再静默空选项；④ 打开表单时后台刷新目录（TabKeepAlive 存续期保鲜）。
 *  动作按钮按 me.permissions 权限码显隐（fail-closed）。
 *  文案经 users 域资源包（zh 基线=既有用例断言原文）。 */
export function UsersPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];
  const filterId = useId();

  const crud = useCrud<AdminUserView, UserFilters>({
    fetchPage: ({ page, size, keyword, filters, signal }) =>
      systemApi.userList(page, size, keyword || undefined, filters?.status, filters?.deptId, signal),
    filters: { status: undefined, deptId: undefined },
    permissions,
    pageSize: 10,
    errorMessage: t('users.errors.loadFailed'),
  });

  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<AdminUserView | null>(null);
  const [binding, setBinding] = useState<AdminUserView | null>(null);
  const [resettingPwd, setResettingPwd] = useState<AdminUserView | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [createForm] = Form.useForm<UserCreateValues>();
  const [editForm] = Form.useForm<UserEditValues>();
  const [bindForm] = Form.useForm<RoleBindValues>();
  const [pwdForm] = Form.useForm<ResetPasswordValues>();

  // 目录源（REQ-20261125-230807 AC-3）：单一 user-form-options 端点；失败可见警告+重试（AC-27）
  const [options, setOptions] = useState<UserFormOptionsView | null>(null);
  const [optionsError, setOptionsError] = useState<string | null>(null);
  const [optionsLoading, setOptionsLoading] = useState(false);

  const loadOptions = useCallback(() => {
    setOptionsLoading(true);
    setOptionsError(null);
    systemApi.userFormOptions()
      .then((data) => setOptions(data))
      .catch((ex: unknown) => {
        setOptionsError(ex instanceof ApiError ? ex.message : t('users.errors.optionsLoadFailed'));
      })
      .finally(() => setOptionsLoading(false));
  }, [t]);

  useEffect(() => {
    loadOptions();
  }, [loadOptions]);

  const deptTree: AdminDeptView[] = options?.deptTree ?? [];
  const roles = useMemo(() => options?.roles ?? [], [options]);
  const positions = useMemo(() => options?.positions ?? [], [options]);

  // 列名称映射（AC-1）：部门/岗位列按目录 id→名称映射动态显示，空值 —
  const deptNameById = useMemo(() => {
    const map = new Map<string, string>();
    const walk = (nodes: AdminDeptView[]) => {
      for (const node of nodes) {
        map.set(node.id, node.deptName);
        walk(node.children ?? []);
      }
    };
    walk(deptTree);
    return map;
  }, [deptTree]);
  const positionNameById = useMemo(
    () => new Map(positions.map((p) => [p.id, p.positionName])),
    [positions],
  );

  // TreeSelect 数据源（AC-2）：层级由树形结构承载、选项无前导空格缩进；叶子不携带空 children 键（AC-9）
  const deptFilterTree = useMemo(() => toDeptTreeOptions(deptTree), [deptTree]);
  const deptFormTree = useMemo(() => toDeptTreeOptions(deptTree, { enabledOnly: true }), [deptTree]);

  const openCreate = () => {
    createForm.resetFields();
    createForm.setFieldsValue({ roleIds: [] });
    setCreating(true);
    loadOptions();
  };

  const openEdit = (record: AdminUserView) => {
    setEditing(record);
    editForm.setFieldsValue({
      nickname: record.nickname ?? undefined,
      email: record.email ?? undefined,
      phone: record.phone ?? undefined,
      deptId: record.deptId ?? undefined,
      positionId: record.positionId ?? undefined,
    });
    loadOptions();
  };

  const openBind = (record: AdminUserView) => {
    setBinding(record);
    bindForm.setFieldsValue({ roleIds: [...record.roleIds] });
    loadOptions();
  };

  /** 打开重置密码弹窗（REQ-20261124-213008 范式 A；权限显隐见行内 Auth）：打开前清空输入。 */
  const openResetPassword = (record: AdminUserView) => {
    setResettingPwd(record);
    pwdForm.resetFields();
  };

  /** 提交重置密码：成功提示并关闭弹窗；策略违反（PASSWORD_POLICY_VIOLATED）转译可读提示，
   *  弹窗保持打开、输入保留（不 resetFields）；其余错误沿用统一转译。 */
  const submitResetPassword = async () => {
    if (!resettingPwd) return;
    const values = await pwdForm.validateFields();
    setSubmitting(true);
    try {
      await systemApi.userPasswordReset(resettingPwd.id, values.password);
      message.success(t('users.resetPassword.success'));
      setResettingPwd(null);
      pwdForm.resetFields();
    } catch (ex: unknown) {
      if (ex instanceof ApiError && ex.code === 'PASSWORD_POLICY_VIOLATED') {
        message.error(t('users.resetPassword.policyViolated'));
      } else {
        message.error(ex instanceof ApiError ? ex.message : t('common.errors.operationFailed'));
      }
    } finally {
      setSubmitting(false);
    }
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
        deptId: values.deptId ?? null,
        positionId: values.positionId ?? null,
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
        deptId: values.deptId ?? null,
        positionId: values.positionId ?? null,
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
    { title: t('users.columns.username'), dataIndex: 'username', key: 'username', minWidth: 160, fixed: 'left' },
    { title: t('users.columns.nickname'), dataIndex: 'nickname', key: 'nickname', minWidth: 120, render: (v: string | null) => v ?? '—' },
    {
      // AC-1：部门列（映射自目录源；空值 —）
      title: t('users.columns.dept'), dataIndex: 'deptId', key: 'dept', minWidth: 120,
      render: (_, record) => (record.deptId ? deptNameById.get(record.deptId) ?? '—' : '—'),
    },
    {
      // AC-1：岗位列（映射自目录源；空值 —）
      title: t('users.columns.position'), dataIndex: 'positionId', key: 'position', minWidth: 120,
      render: (_, record) => (record.positionId ? positionNameById.get(record.positionId) ?? '—' : '—'),
    },
    {
      title: t('users.columns.status'), dataIndex: 'status', key: 'status', minWidth: 90,
      render: (status: AdminUserView['status']) => (
        <Tag color={status === 'ENABLED' ? 'success' : 'default'}>{status === 'ENABLED' ? t('users.status.enabled') : t('users.status.disabled')}</Tag>
      ),
    },
    {
      title: t('users.columns.roles'), dataIndex: 'roleIds', key: 'roleIds', minWidth: 160,
      render: (_, record) => {
        const names = record.roleIds
          .map((id) => roles.find((r) => r.id === id)?.roleName ?? roles.find((r) => r.id === id)?.roleCode)
          .filter(Boolean);
        return names.length ? <Space size={4} wrap>{names.map((n) => <Tag key={n as string}>{n as string}</Tag>)}</Space> : '—';
      },
    },
    { title: t('users.columns.updatedAt'), dataIndex: 'updatedAt', key: 'updatedAt', minWidth: 180, render: (v: string) => formatDate(v) },
    {
      title: t('users.columns.actions'), key: 'actions', minWidth: 300, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          <Auth code="system:user:update">
            <Button size="small" onClick={() => openEdit(record)}>{t('users.actions.edit')}</Button>
          </Auth>
          <Auth code="system:user:assign-role">
            <Button size="small" onClick={() => openBind(record)}>{t('users.actions.assignRoles')}</Button>
          </Auth>
          <Auth code="auth:credential:manage">
            <Button size="small" onClick={() => openResetPassword(record)}>{t('users.actions.resetPassword')}</Button>
          </Auth>
          <Auth code="system:user:update">
            <Popconfirm title={t('users.status.toggleConfirm', { action: record.status === 'ENABLED' ? t('users.status.disabled') : t('users.status.enabled'), name: record.username })} onConfirm={() => void toggleStatus(record)}>
              <Button size="small" danger={record.status === 'ENABLED'}>{record.status === 'ENABLED' ? t('users.status.disabled') : t('users.status.enabled')}</Button>
            </Popconfirm>
          </Auth>
          <Auth code="system:user:delete">
            {!record.builtIn ? (
              <Popconfirm title={t('users.actions.deleteConfirm', { name: record.username })} onConfirm={() => void remove(record)}>
                <Button size="small" danger>{t('users.actions.delete')}</Button>
              </Popconfirm>
            ) : null}
          </Auth>
          {record.builtIn ? <Text type="secondary" style={{ fontSize: 12 }}>{t('users.actions.builtIn')}</Text> : null}
        </Space>
      ),
    },
  ];

  return (
    <>
      <CrudTable<AdminUserView, UserFilters>
        crud={crud}
        tableKey="users"
        title={t('users.title')}
        columns={columns}
        searchPlaceholder={t('users.search.placeholder')}
        reloadAriaLabel={t('users.reload')}
        createPermission="system:user:create"
        createLabel={t('users.create')}
        onCreate={openCreate}
        totalText={(total) => t('users.total', { total })}
        alert={optionsError ? (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 16 }}
            message={t('users.errors.optionsLoadFailed')}
            description={optionsError}
            action={<Button size="small" onClick={loadOptions} loading={optionsLoading}>{t('common.actions.retry')}</Button>}
          />
        ) : undefined}
        filters={(
          <>
            <FilterField label={t('users.form.dept')} htmlFor={`${filterId}-dept`}>
              <TreeSelect<string>
                id={`${filterId}-dept`}
                allowClear
                aria-label={t('users.filters.dept')}
                placeholder={t('users.filters.dept')}
                treeData={deptFilterTree}
                treeNodeFilterProp="title"
                treeNodeLabelProp="pathTitle"
                treeDefaultExpandAll
                showSearch
                value={crud.filters.deptId}
                onChange={(value) => crud.setFilter('deptId', value ?? undefined)}
              />
            </FilterField>
            <FilterField label={t('users.columns.status')} htmlFor={`${filterId}-status`}>
              <Select<'ENABLED' | 'DISABLED'>
                id={`${filterId}-status`}
                allowClear
                aria-label={t('users.filters.status')}
                placeholder={t('users.filters.status')}
                options={[
                  { value: 'ENABLED', label: t('users.status.enabled') },
                  { value: 'DISABLED', label: t('users.status.disabled') },
                ]}
                value={crud.filters.status}
                onChange={(value) => crud.setFilter('status', value)}
              />
            </FilterField>
          </>
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
        {/* AC-2：部门选择为树形下拉（层级展示+搜索），选中回显层级路径（treeNodeLabelProp="pathTitle"），
            选项无前导空格缩进 */}
        <Form.Item name="deptId" label={t('users.form.dept')}>
          <TreeSelect<string>
            allowClear
            placeholder={t('users.form.deptPlaceholder')}
            treeData={deptFormTree}
            treeNodeFilterProp="title"
            treeNodeLabelProp="pathTitle"
            treeDefaultExpandAll
            showSearch
          />
        </Form.Item>
        <Form.Item name="positionId" label={t('users.form.position')}>
          <Select
            allowClear
            placeholder={t('users.form.positionPlaceholder')}
            optionFilterProp="label"
            showSearch
            options={positions.filter((p) => p.status === 'ENABLED').map((p) => ({ value: p.id, label: p.positionName }))}
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
        {/* AC-2：编辑路径部门树形下拉同口径（treeNodeLabelProp="pathTitle" 层级路径回显） */}
        <Form.Item name="deptId" label={t('users.form.dept')}>
          <TreeSelect<string>
            allowClear
            placeholder={t('users.form.deptPlaceholder')}
            treeData={deptFormTree}
            treeNodeFilterProp="title"
            treeNodeLabelProp="pathTitle"
            treeDefaultExpandAll
            showSearch
          />
        </Form.Item>
        <Form.Item name="positionId" label={t('users.form.position')}>
          <Select
            allowClear
            placeholder={t('users.form.positionPlaceholder')}
            optionFilterProp="label"
            showSearch
            options={positions.filter((p) => p.status === 'ENABLED').map((p) => ({ value: p.id, label: p.positionName }))}
          />
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

      <CrudFormModal<ResetPasswordValues>
        open={resettingPwd !== null}
        title={t('users.resetPassword.title', { name: resettingPwd?.username ?? '' })}
        form={pwdForm}
        submitting={submitting}
        onOk={() => void submitResetPassword()}
        onCancel={() => { setResettingPwd(null); pwdForm.resetFields(); }}
      >
        <Form.Item
          name="password"
          label={t('users.resetPassword.newPassword')}
          rules={[
            { required: true, message: t('users.resetPassword.passwordRequired') },
            { min: 1, max: 64, message: t('users.resetPassword.passwordLength') },
          ]}
        >
          <Input.Password placeholder={t('users.resetPassword.newPassword')} autoComplete="new-password" />
        </Form.Item>
        <Text type="secondary" style={{ fontSize: 12 }}>{t('users.resetPassword.submitNote')}</Text>
      </CrudFormModal>
    </>
  );
}
