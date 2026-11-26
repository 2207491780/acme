import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { Alert, App, Button, Card, Checkbox, Drawer, Form, Input, Modal, Popconfirm, Segmented, Select, Space, Spin, Table, Tag, Tree, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  ApiError,
  Auth,
  DATA_SCOPE_VALUES,
  systemApi,
  systemErrorMessage,
  useCrud,
  useDeptTree,
  useMeStore,
  type AdminMenuView,
  type AdminPermissionView,
  type AdminRoleTreeNode,
  type AdminRoleView,
  type RoleDataScope,
} from '@acme/core';
import { CrudFormModal } from '../components/CrudFormModal';
import { CrudTable } from '../components/CrudTable';
import { flattenDeptOptions } from '../utils/dept';
import { stripEmptyChildren } from '../utils/tree';

const { Text } = Typography;

const CODE_PATTERN = /^[a-z][a-z0-9_-]*$/;

/** 权限点目录单页上界（=后端 PageQuery.MAX_SIZE；超出则目录可能被截断——AC-6 告警口径）。 */
const PERMISSION_PAGE_SIZE = 200;

interface RoleFormValues {
  parentId?: string;
  roleCode: string;
  roleName: string;
  remark?: string;
}

/** 平铺行兼容口径（AC-8）：wire DTO 归一可携带 children:[]（树响应口径），类型面补可选 children
 *  供 stripEmptyChildren 收口（展示层剥空 children，叶子无无效展开图标）。 */
interface AdminRoleRow extends AdminRoleView {
  children?: AdminRoleRow[];
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

/** 角色管理（p3-admin）：分页/搜索/新建/编辑/启停/删除 + 行内「授权」单一入口打开授权抽屉
 *  （system:role:grant；REQ-20261125-230807 AC-6/7/9：权限点域分组列表+菜单授权树两块同屏）。
 *  REQ-20261114-120104 扩展：行内「数据范围」弹窗（system:role:scope）——五档 Select +
 *  CUSTOM 部门多选（useDeptTree），提交 PUT data-scope（dataScope+deptIds）。
 *  迁移 CRUD 范本（REQ-20261114-120101）：列表/表单走 useCrud + CrudTable/CrudFormModal；
 *  授权/数据范围弹窗为绑定语义（非 CRUD 表单），保持原 Modal 形态。
 *  REQ-20261124-213008 范式 D：工具栏 antd Segmented「平铺/树形」视图切换，默认平铺（现状不回归）；
 *  树形调 systemApi.roleTree（GET /system/roles/tree）按 children 递归渲染层级
 *  （树形数据处理先例 TenantsPage 的 AdminTenantTreeNode 用法），节点展示既有列关键字段。 */
export function RolesPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];

  const crud = useCrud<AdminRoleView>({
    fetchPage: async ({ page, size, keyword, signal }) => {
      const result = await systemApi.roleList(page, size, keyword || undefined, signal);
      // AC-8（REQ-20261125-230807）：平铺行 wire 同样携带 children:[]（树响应 DTO 归一口径）
      // → 展示层剥空 children（叶子无无效展开图标），与 utils/tree.ts 树表统一收口一致
      return { ...result, records: stripEmptyChildren<AdminRoleRow>(result.records) };
    },
    permissions,
    pageSize: 10,
    errorMessage: t('roles.errors.loadFailed'),
  });

  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<AdminRoleView | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<RoleFormValues>();

  const [granting, setGranting] = useState<AdminRoleView | null>(null);
  const [catalog, setCatalog] = useState<AdminPermissionView[]>([]);
  const [permTotal, setPermTotal] = useState(0);
  // AC-6 域分组列表：关键字过滤（匹配 code/name）+ 过滤结果全选/反选 + 已选计数
  const [permFilter, setPermFilter] = useState('');
  const [grantedIds, setGrantedIds] = useState<string[]>([]);
  // 菜单授权树勾选集（AC-7）：与权限点勾选集分离；抽屉打开从空集开始（提交=全量覆盖）
  const [menuCheckedIds, setMenuCheckedIds] = useState<string[]>([]);
  const [grantLoading, setGrantLoading] = useState(false);

  // 平铺/树形视图切换（REQ-20261124-213008 范式 D）：默认平铺；树形首次切入时拉取
  const [view, setView] = useState<'flat' | 'tree'>('flat');
  const [treeData, setTreeData] = useState<AdminRoleTreeNode[] | null>(null);
  const [treeLoading, setTreeLoading] = useState(false);
  const [treeError, setTreeError] = useState<string | null>(null);

  const loadTree = useCallback(() => {
    setTreeLoading(true);
    setTreeError(null);
    systemApi.roleTree()
      .then((tree) => setTreeData(Array.isArray(tree) ? tree : []))
      .catch(() => setTreeError(t('roles.errors.treeFailed')))
      .finally(() => setTreeLoading(false));
  }, [t]);

  const changeView = (next: 'flat' | 'tree') => {
    setView(next);
    if (next === 'tree' && treeData === null) {
      loadTree();
    }
  };

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
          // AC-10 三态哨兵：清空→0（移为根，显式判空防 0 被 falsy 吞）；设父→字符串原样（防雪花精度丢失）
          parentId: values.parentId != null && values.parentId !== '' ? values.parentId : 0,
          version: editing.version,
        });
        message.success(t('roles.form.updated'));
      } else {
        await systemApi.roleCreate({
          // 新建：清空=顶级（null）；设父=字符串原样（AC-26）
          parentId: values.parentId != null && values.parentId !== '' ? values.parentId : null,
          roleCode: values.roleCode,
          roleName: values.roleName,
          remark: values.remark,
        });
        message.success(t('roles.form.created'));
      }
      setEditing(null);
      setCreating(false);
      // AC-11 写后失效：平铺侧写操作成功后树形视图重拉（非旧快照）
      setTreeData(null);
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
      // AC-11 写后失效
      setTreeData(null);
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.operationFailed'));
    }
  };

  const remove = async (record: AdminRoleView) => {
    try {
      await systemApi.roleDelete(record.id);
      message.success(t('roles.deleted', { code: record.roleCode }));
      // AC-11 写后失效
      setTreeData(null);
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.deleteFailed'));
    }
  };

  /** 打开授权抽屉（AC-6/AC-7/AC-9）：加载权限点已授集合+全量目录；菜单树勾选集从空集开始
   *  （提交=全量覆盖，AC-7 口径「勾选父节点→级联勾选→提交」）。响应兼容运行时校验：
   *  数组/信封缺失降级空集（外部输入不信任，不因异常信封崩渲染）。 */
  const openGrant = async (record: AdminRoleView) => {
    setGranting(record);
    setPermFilter('');
    setMenuCheckedIds([]);
    setGrantLoading(true);
    try {
      const [granted, all] = await Promise.all([
        systemApi.rolePermissions(record.id),
        // size 上界=后端 PageQuery.MAX_SIZE（200），超界整单 400；此处单页取全量目录
        systemApi.permissionList(1, PERMISSION_PAGE_SIZE),
      ]);
      setCatalog(Array.isArray(all?.records) ? all.records : []);
      setPermTotal(all?.total ?? 0);
      setGrantedIds(Array.isArray(granted) ? granted.map((p) => p.id) : []);
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
      await systemApi.roleBindPermissions(granting.id, grantedIds);
      message.success(t('roles.grant.permissionsUpdated', { code: granting.roleCode }));
      // AC-26：menuIds 字符串原样提交（不做 map(Number) 数值化，防雪花精度丢失）
      await systemApi.roleBindMenus(granting.id, menuCheckedIds);
      message.success(t('roles.grant.menusUpdated', { code: granting.roleCode }));
      setGranting(null);
      // AC-11 写后失效：授权影响树形展示语义不变，但保持写后重拉口径一致
      setTreeData(null);
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('roles.grant.failed'));
    } finally {
      setSubmitting(false);
    }
  };

  const viewColumns: ColumnsType<AdminRoleView> = [
    { title: t('roles.columns.roleCode'), dataIndex: 'roleCode', key: 'roleCode', minWidth: 180, fixed: 'left' },
    { title: t('roles.columns.roleName'), dataIndex: 'roleName', key: 'roleName', minWidth: 140 },
    {
      title: t('roles.columns.status'), dataIndex: 'status', key: 'status', minWidth: 90,
      render: (status: AdminRoleView['status']) => (
        <Tag color={status === 'ENABLED' ? 'success' : 'default'}>{status === 'ENABLED' ? t('common.status.enabled') : t('common.status.disabled')}</Tag>
      ),
    },
    { title: t('roles.columns.remark'), dataIndex: 'remark', key: 'remark', minWidth: 200, ellipsis: true, render: (v: string | null) => v ?? '—' },
  ];

  const columns: ColumnsType<AdminRoleView> = [
    ...viewColumns,
    {
      title: t('roles.columns.actions'), key: 'actions', minWidth: 380, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          <Auth code="system:role:update">
            <Button size="small" onClick={() => openEdit(record)}>{t('roles.actions.edit')}</Button>
          </Auth>
          <Auth code="system:role:grant">
            {/* AC-6/AC-7/AC-9：行内「授权」单一入口 → 授权抽屉（权限点域分组列表+菜单授权树同屏） */}
            <Button size="small" onClick={() => void openGrant(record)}>{t('roles.actions.grant')}</Button>
          </Auth>
          <Auth code="system:role:scope">
            <Button size="small" onClick={() => openScope(record)}>{t('roles.actions.dataScope')}</Button>
          </Auth>
          <Auth code="system:role:update">
            <Popconfirm title={t('roles.actions.toggleConfirm', { action: record.status === 'ENABLED' ? t('common.status.disabled') : t('common.status.enabled'), name: record.roleName })} onConfirm={() => void toggleStatus(record)}>
              <Button size="small" danger={record.status === 'ENABLED'}>{record.status === 'ENABLED' ? t('common.status.disabled') : t('common.status.enabled')}</Button>
            </Popconfirm>
          </Auth>
          <Auth code="system:role:delete">
            {!record.builtIn ? (
              <Popconfirm title={t('roles.actions.deleteConfirm', { code: record.roleCode })} onConfirm={() => void remove(record)}>
                <Button size="small" danger>{t('roles.actions.delete')}</Button>
              </Popconfirm>
            ) : null}
          </Auth>
          {record.builtIn ? <Text type="secondary" style={{ fontSize: 12 }}>{t('roles.actions.builtIn')}</Text> : null}
        </Space>
      ),
    },
  ];

  const viewSegmented = (
    <Segmented<'flat' | 'tree'>
      value={view}
      onChange={(value) => changeView(value)}
      options={[
        { value: 'flat', label: t('roles.view.flat') },
        { value: 'tree', label: t('roles.view.tree') },
      ]}
    />
  );

  /** 树节点总数（全量计数，含子节点；仅用于总数文案）。 */
  const countNodes = (nodes: AdminRoleTreeNode[]): number =>
    nodes.reduce((acc, node) => acc + 1 + countNodes(node.children ?? []), 0);

  // AC-6 域分组列表：关键字过滤（匹配 code/name）→ 按域（permissionCode 首段）分组展示
  const filteredPermissions = useMemo(() => {
    const needle = permFilter.trim().toLowerCase();
    if (!needle) {
      return catalog;
    }
    return catalog.filter((p) =>
      p.permissionCode.toLowerCase().includes(needle)
      || p.permissionName.toLowerCase().includes(needle));
  }, [catalog, permFilter]);
  const permissionGroups = useMemo(() => {
    const groups = new Map<string, AdminPermissionView[]>();
    for (const p of filteredPermissions) {
      const domain = p.permissionCode.split(':')[0] || p.permissionCode;
      const bucket = groups.get(domain);
      if (bucket) {
        bucket.push(p);
      } else {
        groups.set(domain, [p]);
      }
    }
    return Array.from(groups.entries())
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([domain, items]) => ({ domain, items }));
  }, [filteredPermissions]);
  const filteredPermissionIds = useMemo(() => filteredPermissions.map((p) => p.id), [filteredPermissions]);
  /** 已选计数（AC-6）：当前过滤范围内已勾选数（未过滤=全部已选），与全选/反选同范围口径。 */
  const selectedInFilterCount = useMemo(
    () => filteredPermissionIds.filter((id) => grantedIds.includes(id)).length,
    [filteredPermissionIds, grantedIds],
  );
  /** 过滤结果全选/反选（AC-6）：反选=过滤集内勾选状态翻转，过滤集外保持不变。 */
  const selectAllFiltered = () =>
    setGrantedIds((prev) => Array.from(new Set([...prev, ...filteredPermissionIds])));
  const invertFiltered = () =>
    setGrantedIds((prev) => {
      const filtered = new Set(filteredPermissionIds);
      const toggled = filteredPermissionIds.filter((id) => !prev.includes(id));
      return [...prev.filter((id) => !filtered.has(id)), ...toggled];
    });
  const togglePermission = (id: string, checked: boolean) =>
    setGrantedIds((prev) => (checked ? (prev.includes(id) ? prev : [...prev, id]) : prev.filter((x) => x !== id)));

  return (
    <>
      {view === 'tree' ? (
        <Card
          title={t('roles.title')}
          extra={
            <Space wrap>
              {viewSegmented}
              <Button icon={<ReloadOutlined />} onClick={loadTree} loading={treeLoading} aria-label={t('roles.tree.reload')} />
            </Space>
          }
        >
          {treeError ? (
            <Alert
              type="error"
              showIcon
              style={{ marginBottom: 16 }}
              message={treeError}
              action={<Button size="small" onClick={loadTree}>{t('common.actions.retry')}</Button>}
            />
          ) : null}
          {/* 数据到位后才挂 Table：defaultExpandAllRows 生效（首挂即全展开） */}
          {treeData !== null ? (
            <Table<AdminRoleView>
              rowKey="id"
              columns={viewColumns}
              dataSource={stripEmptyChildren(treeData)}
              loading={treeLoading}
              size="small"
              scroll={{ x: 'max-content' }}
              pagination={false}
              expandable={{ defaultExpandAllRows: true }}
              footer={() => t('common.table.total', { total: countNodes(treeData) })}
            />
          ) : (
            <div style={{ padding: 32, textAlign: 'center', color: 'var(--muted)' }}>{t('roles.tree.empty')}</div>
          )}
        </Card>
      ) : (
        <CrudTable<AdminRoleView>
          crud={crud}
          tableKey="roles"
          title={t('roles.title')}
          columns={columns}
          searchPlaceholder={t('roles.search.placeholder')}
          reloadAriaLabel={t('roles.reload')}
          createPermission="system:role:create"
          createLabel={t('roles.create')}
          onCreate={openCreate}
          totalText={(total) => t('roles.total', { total })}
          actions={viewSegmented}
        />
      )}

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

      {/* 授权抽屉（AC-6/AC-7/AC-9 契约形态）：权限点域分组列表 + 菜单授权树两块同屏；
          行内「授权」单一入口打开；footer 保存/取消经 i18n 显式文案（不随装配 locale 缺省漂移）。 */}
      <Drawer
        open={granting !== null}
        title={granting ? t('roles.grant.title', { name: granting.roleName }) : ''}
        width={640}
        destroyOnClose
        onClose={() => setGranting(null)}
        footer={
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
            <Button onClick={() => setGranting(null)}>{t('common.actions.cancel')}</Button>
            <Button
              type="primary"
              loading={submitting || grantLoading}
              disabled={grantLoading}
              onClick={() => void submitGrant()}
            >
              {t('common.actions.save')}
            </Button>
          </div>
        }
      >
        <Text strong style={{ display: 'block', marginBottom: 12 }}>
          {granting ? t('roles.grant.permissionsTitle', { name: granting.roleName }) : ''}
        </Text>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            {/* AC-6 域分组列表：关键字过滤（匹配 code/name） */}
            <Input
              allowClear
              placeholder={t('roles.grant.filterPlaceholder')}
              aria-label={t('roles.grant.filterPlaceholder')}
              value={permFilter}
              onChange={(event) => setPermFilter(event.target.value)}
            />
            <Space align="center" wrap>
              <Text type="secondary" style={{ fontSize: 12 }}>
                {t('roles.grant.selectedCount', { count: selectedInFilterCount })}
              </Text>
              <Button size="small" disabled={filteredPermissionIds.length === 0} onClick={selectAllFiltered}>
                {t('roles.grant.selectAll')}
              </Button>
              <Button size="small" disabled={filteredPermissionIds.length === 0} onClick={invertFiltered}>
                {t('roles.grant.invert')}
              </Button>
              {/* AC-6：目录总数超过单页上界 → 目录可能不完整告警 */}
              {permTotal > PERMISSION_PAGE_SIZE ? <Tag color="warning">{t('roles.grant.truncatedWarning')}</Tag> : null}
            </Space>
            <div style={{ maxHeight: 420, overflow: 'auto' }}>
              {permissionGroups.map((group) => (
                <div key={group.domain} style={{ marginBottom: 12 }}>
                  <Text strong style={{ display: 'block', marginBottom: 8 }}>
                    {t('roles.grant.domainTitle', { domain: group.domain, count: group.items.length })}
                  </Text>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                    {group.items.map((p) => (
                      <Checkbox
                        key={p.id}
                        checked={grantedIds.includes(p.id)}
                        onChange={(e) => togglePermission(p.id, e.target.checked)}
                      >
                        {/* 名称/编码独立文本节点：编码可被精确定位，语义不只靠颜色（UI-SPEC §7） */}
                        <span>{p.permissionName}</span>
                        {'（'}
                        <Text code style={{ fontSize: 12 }}>{p.permissionCode}</Text>
                        {'）'}
                      </Checkbox>
                    ))}
                  </div>
                </div>
              ))}
              {permissionGroups.length === 0 ? <Text type="secondary">{t('roles.grant.noMatch')}</Text> : null}
          </div>
        </div>
        {/* 菜单授权树（AC-7 级联勾选 + AC-9 剥空 children 叶子无箭头）：勾选集独立于权限点（全量覆盖提交） */}
        <Text strong style={{ display: 'block', margin: '16px 0 12px' }}>
          {granting ? t('roles.grant.menusTitle', { name: granting.roleName }) : ''}
        </Text>
        <div style={{ maxHeight: 420, overflow: 'auto' }}>
          <RoleMenuTree checkedIds={menuCheckedIds} onChange={setMenuCheckedIds} />
        </div>
        <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 12 }}>
          {t('roles.grant.menusButtonNote')}
        </Text>
        <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 12 }}>
          {t('roles.grant.submitNote')}
        </Text>
      </Drawer>

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

/** 角色菜单树形勾选（antd Tree checkable；fieldNames 对齐 AdminMenuView）。
 *  REQ-20261119-051119：BUTTON 节点（按钮级操作权限投影）在标题旁展示「按钮」Tag。
 *  REQ-20261125-230807 AC-7：移除 checkStrictly——父子级联（勾选父级全选子级、取消父级级联取消子级），
 *  祖先链由后端 withAncestors 兑底；AC-9：树数据剥空 children（叶子无展开箭头）。 */
function RoleMenuTree({ checkedIds, onChange }: { checkedIds: string[]; onChange: (ids: string[]) => void }) {
  const { t } = useTranslation();
  // AC-7/AC-9：数据到位后再挂 Tree（antd defaultExpandAll 仅首挂生效，异步数据后挂会整树收起，
  // MenusPage「数据到位后才挂 Table」同先例口径）
  const [tree, setTree] = useState<AdminMenuView[] | null>(null);
  useEffect(() => {
    systemApi.menuTree()
      .then((data) => setTree(stripEmptyChildren(Array.isArray(data) ? data : [])))
      .catch(() => setTree([]));
  }, []);
  // BUTTON 节点标题 → ReactNode（menuName + 「按钮」Tag）；其余保持纯文本（antd Tree title 接受 ReactNode）
  const treeData = useMemo(() => decorateButtonTitles(tree ?? [], t('menus.type.button')), [tree, t]);
  if (tree === null) {
    return <Spin size="small" />;
  }
  return (
    <Tree
      checkable
      selectable={false}
      defaultExpandAll
      blockNode
      treeData={treeData as never[]}
      fieldNames={{ title: 'menuName', key: 'id', children: 'children' }}
      checkedKeys={checkedIds}
      onCheck={(checked) => {
        // 非严格模式：checkedKeys 为全选数组（父子级联由 antd Tree 承担）
        const keys = Array.isArray(checked) ? checked : (checked as { checked: (string | number)[] }).checked;
        onChange(keys.map(String));
      }}
    />
  );
}

/** 装饰后树节点：menuName 升级为 ReactNode（BUTTON 节点附「按钮」Tag），children 递归同型。 */
interface MenuTreeNodeTitle extends Omit<AdminMenuView, 'menuName' | 'children'> {
  menuName: ReactNode;
  children?: MenuTreeNodeTitle[];
}

/** 递归装饰菜单树标题：menuType=BUTTON 节点附「按钮」Tag（其余节点原样）；
 *  叶子节点不携带 children 键（AC-9 剥空 children，防无效展开箭头）。 */
function decorateButtonTitles(nodes: AdminMenuView[], buttonLabel: string): MenuTreeNodeTitle[] {
  return nodes.map((node) => {
    const base: MenuTreeNodeTitle = {
      ...node,
      menuName: node.menuType === 'BUTTON'
        ? (
            <span>
              {node.menuName}
              <Tag style={{ marginLeft: 8 }} color="purple">{buttonLabel}</Tag>
            </span>
          )
        : node.menuName,
    };
    if (node.children?.length) {
      return { ...base, children: decorateButtonTitles(node.children, buttonLabel) };
    }
    return base;
  });
}
