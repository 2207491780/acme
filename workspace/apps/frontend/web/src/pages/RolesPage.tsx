import { useEffect, useState } from 'react';
import { App, Button, Checkbox, Form, Input, Modal, Popconfirm, Select, Space, Tag, Tree, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import {
  ApiError,
  DATA_SCOPE_VALUES,
  systemApi,
  systemErrorMessage,
  useCrud,
  useDeptTree,
  useMeStore,
  type AdminMenuView,
  type AdminPermissionView,
  type AdminRoleView,
  type RoleDataScope,
} from '@acme/core';
import { CrudFormModal } from '../components/CrudFormModal';
import { CrudTable } from '../components/CrudTable';
import { flattenDeptOptions } from '../utils/dept';

const { Text } = Typography;

const CODE_PATTERN = /^[a-z][a-z0-9_-]*$/;

interface RoleFormValues {
  parentId?: string;
  roleCode: string;
  roleName: string;
  remark?: string;
}

interface DataScopeValues {
  dataScope?: RoleDataScope;
  deptIds?: string[];
}

/** 数据范围枚举 → i18n 显示名（roles.scope.options；显示名文案经资源包，无硬编码中文）。 */
const DATA_SCOPE_LABEL_KEYS: Record<RoleDataScope, string> = {
  ALL: 'roles.scope.options.all',
  DEPT: 'roles.scope.options.dept',
  DEPT_AND_CHILD: 'roles.scope.options.deptAndChild',
  SELF: 'roles.scope.options.self',
  CUSTOM: 'roles.scope.options.custom',
};

/** 角色管理（p3-admin）：分页/搜索/新建/编辑/启停/删除 + 权限点绑定抽屉（system:role:grant）。
 *  菜单绑定同抽屉形态（tree 勾选）随本页第二抽屉交付。
 *  REQ-20261114-120104 扩展：行内「数据范围」弹窗（system:role:scope）——五档 Select +
 *  CUSTOM 部门多选（useDeptTree），提交 PUT data-scope（dataScope+deptIds）。
 *  迁移 CRUD 范本（REQ-20261114-120101）：列表/表单走 useCrud + CrudTable/CrudFormModal；
 *  授权/数据范围弹窗为绑定语义（非 CRUD 表单），保持原 Modal 形态。 */
export function RolesPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];

  const crud = useCrud<AdminRoleView>({
    fetchPage: ({ page, size, keyword, signal }) => systemApi.roleList(page, size, keyword || undefined, signal),
    permissions,
    pageSize: 10,
    errorMessage: t('roles.errors.loadFailed'),
  });

  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<AdminRoleView | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<RoleFormValues>();

  const [granting, setGranting] = useState<AdminRoleView | null>(null);
  const [grantKind, setGrantKind] = useState<'permissions' | 'menus'>('permissions');
  const [catalog, setCatalog] = useState<AdminPermissionView[]>([]);
  const [grantedIds, setGrantedIds] = useState<string[]>([]);
  const [grantLoading, setGrantLoading] = useState(false);

  // 数据范围弹窗（REQ-20261114-120104）：scope 五档 + CUSTOM 部门多选（选项源 useDeptTree 模块缓存）
  const deptTree = useDeptTree();
  const [scoping, setScoping] = useState<AdminRoleView | null>(null);
  const [scopeSubmitting, setScopeSubmitting] = useState(false);
  const [scopeForm] = Form.useForm<DataScopeValues>();
  const scopeValue = Form.useWatch('dataScope', scopeForm);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    setCreating(true);
  };

  /** 打开数据范围弹窗：回填该角色当前 scope（wire 缺省回退 ALL）。 */
  const openScope = (record: AdminRoleView) => {
    setScoping(record);
    scopeForm.resetFields();
    scopeForm.setFieldsValue({
      dataScope: (record.dataScope as RoleDataScope) ?? 'ALL',
      deptIds: [],
    });
  };

  /** 提交数据范围：PUT /roles/{id}/data-scope（dataScope + CUSTOM 部门集；校验由后端兜底）。 */
  const submitScope = async () => {
    if (!scoping) return;
    const values = await scopeForm.validateFields();
    setScopeSubmitting(true);
    try {
      await systemApi.roleDataScope(scoping.id, values.dataScope ?? 'ALL', values.deptIds ?? []);
      message.success(t('roles.scope.updated', { code: scoping.roleCode }));
      setScoping(null);
      crud.reload();
    } catch (ex: unknown) {
      message.error(systemErrorMessage(ex, t('roles.scope.failed')));
    } finally {
      setScopeSubmitting(false);
    }
  };

  const openEdit = (record: AdminRoleView) => {
    setEditing(record);
    form.setFieldsValue({
      parentId: record.parentId ?? undefined,
      roleCode: record.roleCode,
      roleName: record.roleName,
      remark: record.remark ?? undefined,
    });
    setCreating(false);
  };

  const submit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (editing) {
        await systemApi.roleUpdate(editing.id, {
          roleName: values.roleName,
          remark: values.remark,
          parentId: values.parentId ? Number(values.parentId) : null,
          version: editing.version,
        });
        message.success(t('roles.form.updated'));
      } else {
        await systemApi.roleCreate({
          parentId: values.parentId ? Number(values.parentId) : null,
          roleCode: values.roleCode,
          roleName: values.roleName,
          remark: values.remark,
        });
        message.success(t('roles.form.created'));
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

  const toggleStatus = async (record: AdminRoleView) => {
    const next = record.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
    try {
      await systemApi.roleStatus(record.id, next);
      message.success(next === 'ENABLED' ? t('roles.actions.enabledDone') : t('roles.actions.disabledDone'));
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.operationFailed'));
    }
  };

  const remove = async (record: AdminRoleView) => {
    try {
      await systemApi.roleDelete(record.id);
      message.success(t('roles.deleted', { code: record.roleCode }));
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.deleteFailed'));
    }
  };

  /** 打开授权抽屉：加载角色已授予集合 + 目录（权限点全量 / 菜单树）。 */
  const openGrant = async (record: AdminRoleView, kind: 'permissions' | 'menus') => {
    setGranting(record);
    setGrantKind(kind);
    setGrantLoading(true);
    try {
      if (kind === 'permissions') {
        const [granted, all] = await Promise.all([
          systemApi.rolePermissions(record.id),
          systemApi.permissionList(1, 500),
        ]);
        setCatalog(all.records);
        setGrantedIds(granted.map((p) => p.id));
      } else {
        const granted = await systemApi.roleMenus(record.id);
        setGrantedIds(collectTreeIds(granted));
      }
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('roles.grant.loadFailed'));
      setGranting(null);
    } finally {
      setGrantLoading(false);
    }
  };

  const submitGrant = async () => {
    if (!granting) return;
    setSubmitting(true);
    try {
      if (grantKind === 'permissions') {
        await systemApi.roleBindPermissions(granting.id, grantedIds);
        message.success(t('roles.grant.permissionsUpdated', { code: granting.roleCode }));
      } else {
        await systemApi.roleBindMenus(granting.id, grantedIds.map(Number));
        message.success(t('roles.grant.menusUpdated', { code: granting.roleCode }));
      }
      setGranting(null);
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('roles.grant.failed'));
    } finally {
      setSubmitting(false);
    }
  };

  const columns: ColumnsType<AdminRoleView> = [
    { title: t('roles.columns.roleCode'), dataIndex: 'roleCode', key: 'roleCode', minWidth: 180, fixed: 'left' },
    { title: t('roles.columns.roleName'), dataIndex: 'roleName', key: 'roleName', minWidth: 140 },
    {
      title: t('roles.columns.status'), dataIndex: 'status', key: 'status', minWidth: 90,
      render: (status: AdminRoleView['status']) => (
        <Tag color={status === 'ENABLED' ? 'success' : 'default'}>{status === 'ENABLED' ? t('common.status.enabled') : t('common.status.disabled')}</Tag>
      ),
    },
    { title: t('roles.columns.remark'), dataIndex: 'remark', key: 'remark', minWidth: 200, ellipsis: true, render: (v: string | null) => v ?? '—' },
    {
      title: t('roles.columns.actions'), key: 'actions', minWidth: 380, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          {crud.can('system:role:update') ? <Button size="small" onClick={() => openEdit(record)}>{t('roles.actions.edit')}</Button> : null}
          {crud.can('system:role:grant') ? (
            <Button size="small" onClick={() => void openGrant(record, 'permissions')}>{t('roles.actions.grantPermissions')}</Button>
          ) : null}
          {crud.can('system:role:grant') ? (
            <Button size="small" onClick={() => void openGrant(record, 'menus')}>{t('roles.actions.grantMenus')}</Button>
          ) : null}
          {crud.can('system:role:scope') ? (
            <Button size="small" onClick={() => openScope(record)}>{t('roles.actions.dataScope')}</Button>
          ) : null}
          {crud.can('system:role:update') ? (
            <Popconfirm title={t('roles.actions.toggleConfirm', { action: record.status === 'ENABLED' ? t('common.status.disabled') : t('common.status.enabled'), name: record.roleName })} onConfirm={() => void toggleStatus(record)}>
              <Button size="small" danger={record.status === 'ENABLED'}>{record.status === 'ENABLED' ? t('common.status.disabled') : t('common.status.enabled')}</Button>
            </Popconfirm>
          ) : null}
          {crud.can('system:role:delete') && !record.builtIn ? (
            <Popconfirm title={t('roles.actions.deleteConfirm', { code: record.roleCode })} onConfirm={() => void remove(record)}>
              <Button size="small" danger>{t('roles.actions.delete')}</Button>
            </Popconfirm>
          ) : null}
          {record.builtIn ? <Text type="secondary" style={{ fontSize: 12 }}>{t('roles.actions.builtIn')}</Text> : null}
        </Space>
      ),
    },
  ];

  return (
    <>
      <CrudTable<AdminRoleView>
        crud={crud}
        title={t('roles.title')}
        columns={columns}
        searchPlaceholder={t('roles.search.placeholder')}
        searchWidth={220}
        reloadAriaLabel={t('roles.reload')}
        createPermission="system:role:create"
        createLabel={t('roles.create')}
        onCreate={openCreate}
        totalText={(total) => t('roles.total', { total })}
      />

      <CrudFormModal<RoleFormValues>
        open={creating || editing !== null}
        title={editing ? t('roles.form.editTitle', { code: editing.roleCode }) : t('roles.form.createTitle')}
        form={form}
        submitting={submitting}
        onOk={() => void submit()}
        onCancel={() => { setCreating(false); setEditing(null); }}
      >
        <Form.Item name="parentId" label={t('roles.form.parent')}>
          <Select
            allowClear
            placeholder={t('roles.form.parentPlaceholder')}
            options={crud.records.filter((r) => r.id !== editing?.id).map((r) => ({ value: r.id, label: r.roleName }))}
            optionFilterProp="label"
            showSearch
          />
        </Form.Item>
        <Form.Item
          name="roleCode"
          label={t('roles.form.code')}
          rules={[
            { required: true, message: t('roles.form.codeRequired') },
            { pattern: CODE_PATTERN, message: t('common.validation.codePattern') },
            { min: 2, max: 64, message: t('common.validation.lengthRange', { min: 2, max: 64 }) },
          ]}
          extra={editing ? t('roles.form.codeImmutable') : t('roles.form.codeExtra')}
        >
          <Input placeholder={t('roles.form.codePlaceholder')} disabled={editing !== null} autoComplete="off" />
        </Form.Item>
        <Form.Item
          name="roleName"
          label={t('roles.form.name')}
          rules={[
            { required: true, message: t('roles.form.nameRequired') },
            { min: 1, max: 64, message: t('common.validation.lengthRange', { min: 1, max: 64 }) },
          ]}
        >
          <Input placeholder={t('roles.form.namePlaceholder')} />
        </Form.Item>
        <Form.Item name="remark" label={t('roles.form.remark')} rules={[{ max: 255, message: t('common.validation.maxLength', { max: 255 }) }]}>
          <Input.TextArea placeholder={t('common.validation.optional')} rows={2} />
        </Form.Item>
        {editing ? (
          <Text type="secondary" style={{ fontSize: 12 }}>{t('roles.form.lockNote', { version: editing.version })}</Text>
        ) : null}
      </CrudFormModal>

      <Modal
        open={granting !== null}
        title={
          granting
            ? `${grantKind === 'permissions' ? t('roles.grant.permissionsTitle', { name: granting.roleName }) : t('roles.grant.menusTitle', { name: granting.roleName })}`
            : ''
        }
        width={grantKind === 'menus' ? 560 : 640}
        onCancel={() => setGranting(null)}
        onOk={() => void submitGrant()}
        confirmLoading={submitting || grantLoading}
        okButtonProps={{ disabled: grantLoading }}
        destroyOnClose
      >
        {grantKind === 'permissions' ? (
          <Checkbox.Group style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, maxHeight: 420, overflow: 'auto' }} value={grantedIds} onChange={(v) => setGrantedIds(v as string[])}>
            {catalog.map((p) => (
              <Checkbox key={p.id} value={p.id}>{`${p.permissionName}（${p.permissionCode}）`}</Checkbox>
            ))}
          </Checkbox.Group>
        ) : (
          <div style={{ maxHeight: 420, overflow: 'auto' }}>
            <RoleMenuTree checkedIds={grantedIds} onChange={setGrantedIds} />
          </div>
        )}
        <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 12 }}>
          {t('roles.grant.submitNote')}
        </Text>
      </Modal>

      <Modal
        open={scoping !== null}
        title={scoping ? t('roles.scope.title', { name: scoping.roleName }) : ''}
        width={480}
        onCancel={() => setScoping(null)}
        onOk={() => void submitScope()}
        confirmLoading={scopeSubmitting}
        destroyOnClose
      >
        <Form form={scopeForm} layout="vertical">
          <Form.Item name="dataScope" label={t('roles.scope.scopeLabel')}>
            <Select<RoleDataScope>
              options={DATA_SCOPE_VALUES.map((value) => ({ value, label: t(DATA_SCOPE_LABEL_KEYS[value]) }))}
            />
          </Form.Item>
          {scopeValue === 'CUSTOM' ? (
            <Form.Item
              name="deptIds"
              label={t('roles.scope.customLabel')}
              rules={[{ required: true, message: t('roles.scope.customRequired') }]}
            >
              <Select
                mode="multiple"
                allowClear
                placeholder={t('roles.scope.customPlaceholder')}
                optionFilterProp="label"
                showSearch
                options={flattenDeptOptions(deptTree.tree, { enabledOnly: true })}
              />
            </Form.Item>
          ) : null}
        </Form>
        <Text type="secondary" style={{ fontSize: 12 }}>
          {t('roles.scope.submitNote')}
        </Text>
      </Modal>
    </>
  );
}

/** 递归收集菜单树全部节点 id（授权抽屉树形勾选的全集）。 */
function collectTreeIds(nodes: AdminMenuView[]): string[] {
  const out: string[] = [];
  const walk = (list: AdminMenuView[]) => {
    for (const n of list) {
      out.push(String(n.id));
      if (n.children?.length) walk(n.children);
    }
  };
  walk(nodes);
  return out;
}

/** 角色菜单树形勾选（antd Tree checkable；fieldNames 对齐 AdminMenuView）。 */
function RoleMenuTree({ checkedIds, onChange }: { checkedIds: string[]; onChange: (ids: string[]) => void }) {
  const [tree, setTree] = useState<{ id: number; menuName: string; children?: unknown }[]>([]);
  useEffect(() => {
    systemApi.menuTree()
      .then(setTree)
      .catch(() => setTree([]));
  }, []);
  return (
    <Tree
      checkable
      checkStrictly
      selectable={false}
      defaultExpandAll
      blockNode
      treeData={tree as never[]}
      fieldNames={{ title: 'menuName', key: 'id', children: 'children' }}
      checkedKeys={checkedIds}
      onCheck={(checked) => {
        const shape = checked as { checked: (string | number)[] };
        onChange(shape.checked.map(String));
      }}
    />
  );
}
