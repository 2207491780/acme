import { useMemo, useState, type ReactNode } from 'react';
import { App, Button, Checkbox, Form, Input, Popconfirm, Space, Tag, Tree, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import {
  ApiError,
  Auth,
  formatDate,
  systemApi,
  tenantPackageApi,
  useCrud,
  useMeStore,
  type AdminCapabilityView,
  type AdminMenuView,
  type AdminTenantPackageView,
} from '@acme/core';
import { CrudFormModal } from '../components/CrudFormModal';
import { CrudTable } from '../components/CrudTable';
import { stripEmptyChildren } from '../utils/tree';

interface PackageFormValues {
  packageName: string;
  remark?: string;
}

/** 套餐管理（REQ-20261121-022910）：分页/搜索/新建/编辑/删除；
 *  新建/编辑弹窗 = 名称 + 备注 + 菜单树勾选（Tree checkable，数据源 GET 既有菜单树接口）
 *  + 能力勾选（REQ-20261121-054613：Checkbox 组，数据源 GET /system/capabilities 能力目录，
 *  label=能力名+码副文本；提交随 capabilities 全量替换，编辑从 detail 回显）；
 *  删除 Popconfirm，被租户绑定时后端拒绝（TENANT_PACKAGE_IN_USE）→ 展示后端业务错误文案。 */
export function TenantPackagesPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];

  const crud = useCrud<AdminTenantPackageView>({
    fetchPage: ({ page, size, keyword, signal }) =>
      tenantPackageApi.page({ page, size, keyword: keyword || undefined }, signal),
    permissions,
    pageSize: 10,
    errorMessage: t('tenantPackages.errors.loadFailed'),
  });

  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<AdminTenantPackageView | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<PackageFormValues>();
  // 菜单树勾选（弹窗表单态）：Tree 勾选集（string 口径），提交转 number[] 全量提交
  const [checkedMenuIds, setCheckedMenuIds] = useState<string[]>([]);
  // 能力勾选（REQ-20261121-054613 弹窗表单态）：勾选集=能力码 string[]（提交原样随 capabilities
  // 全量替换；创建清空、编辑从 detail.capabilities 回显）
  const [checkedCapabilities, setCheckedCapabilities] = useState<string[]>([]);
  // 乐观锁版本（REQ-20261121-022910 round 2：后端 AdminTenantPackageUpdate @NotNull version——
  // 编辑随体提交；创建不带 version）。回显优先取 detail.version（新于列表行），列表行兜底；
  // round 2 补：wire 字符串口径（FR-024 Long→string），内部 Number() 归一容错，提交 String 化。
  const [editVersion, setEditVersion] = useState<number | null>(null);

  // 菜单树数据源（GET 既有菜单树接口，全量含 BUTTON）：每次打开弹窗后台刷新（AC-21——
  // TabKeepAlive 页签存续期目录保鲜，不用 useRef 永久缓存）；刷新期间已有数据保持展示
  const [menuTree, setMenuTree] = useState<AdminMenuView[]>([]);
  const [menuTreeLoading, setMenuTreeLoading] = useState(false);
  const loadMenuTree = () => {
    setMenuTreeLoading(true);
    systemApi.menuTree()
      .then((tree) => setMenuTree(Array.isArray(tree) ? tree : []))
      .catch(() => setMenuTree([]))
      .finally(() => setMenuTreeLoading(false));
  };

  // 能力目录数据源（REQ-20261121-054613 GET /system/capabilities，仅启用行）：每次打开弹窗后台刷新（AC-21 同口径）；
  // 目录为套餐管理面组成，读权限挂 system:tenant-package:list（无新权限点）。
  // 运行时容错：data 缺失/非数组（异常信封/旧口径 wire）→ 空目录降级展示
  const [capabilities, setCapabilities] = useState<AdminCapabilityView[]>([]);
  const [capabilitiesLoading, setCapabilitiesLoading] = useState(false);
  const loadCapabilities = () => {
    setCapabilitiesLoading(true);
    systemApi.capabilityList()
      .then((rows) => {
        if (Array.isArray(rows)) {
          setCapabilities(rows);
        }
      })
      .catch(() => setCapabilities([]))
      .finally(() => setCapabilitiesLoading(false));
  };

  // 能力 checkbox 组选项：label = 能力名 + 码副文本（目录码为契约主键，直出原文不译）
  const capabilityOptions = useMemo(
    () => capabilities.map((item) => ({
      value: item.capabilityCode,
      label: (
        <span>
          {item.capabilityName}
          <Typography.Text type="secondary" style={{ fontSize: 12, marginLeft: 8 }}>
            {item.capabilityCode}
          </Typography.Text>
        </span>
      ),
    })),
    [capabilities],
  );

  const openCreate = () => {
    setEditing(null);
    setCheckedMenuIds([]);
    setCheckedCapabilities([]);
    setEditVersion(null);
    form.resetFields();
    loadMenuTree();
    loadCapabilities();
    setCreating(true);
  };

  const openEdit = (record: AdminTenantPackageView) => {
    setEditing(record);
    setCheckedMenuIds([]);
    setCheckedCapabilities([]);
    setEditVersion(record.version != null && Number.isFinite(Number(record.version)) ? Number(record.version) : null);
    form.setFieldsValue({ packageName: record.packageName, remark: record.remark ?? undefined });
    loadMenuTree();
    loadCapabilities();
    setCreating(false);
    // 详情回填勾选集与乐观锁版本（AdminTenantPackageDetail.menuIds/capabilities/version）；失败提示并保留弹窗（可重开重试）
    tenantPackageApi.detail(record.id)
      .then((detail) => {
        setCheckedMenuIds(detail.menuIds.map(String));
        // wire 容错：capabilities 缺失（旧口径 wire）按空集回显；能力码原样透传不归一
        setCheckedCapabilities(Array.isArray(detail.capabilities) ? detail.capabilities : []);
        // wire 容错：version 缺失/非法（异常信封或旧口径）不回填，提交时缺 version 由后端 400 明示
        const detailVersion = Number(detail.version);
        if (Number.isFinite(detailVersion)) {
          setEditVersion(detailVersion);
        }
      })
      .catch((ex: unknown) => {
        message.error(ex instanceof ApiError ? ex.message : t('tenantPackages.errors.detailFailed'));
      });
  };

  const submit = async () => {
    const values = await form.validateFields();
    // 公共段：name/remark/menuIds/capabilities（REQ-20261121-054613 创建/更新均显式提交，
    // 全量替换口径同 menuIds）；编辑另随体 version（round 2 乐观锁，创建不带 version）
    const base = {
      packageName: values.packageName.trim(),
      remark: values.remark?.trim() || null,
      // AC-26：menuIds 字符串原样提交（不做 map(Number) 数值化，防雪花精度丢失）
      menuIds: checkedMenuIds,
      capabilities: checkedCapabilities,
    };
    setSubmitting(true);
    try {
      if (editing) {
        // round 2 补：version wire 字符串口径（FR-024 Long→string），提交 String 化；缺失→后端 @NotNull 400
        await tenantPackageApi.update(editing.id, {
          ...base,
          version: editVersion === null ? undefined : String(editVersion),
        });
        message.success(t('tenantPackages.form.updated'));
      } else {
        await tenantPackageApi.create(base);
        message.success(t('tenantPackages.form.created'));
      }
      setEditing(null);
      setCreating(false);
      crud.reload();
    } catch (ex: unknown) {
      // 业务失败（TENANT_PACKAGE_DUPLICATE/MENU_INVALID/TENANT_CAPABILITY_INVALID 等）→ 透出后端信封文案
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.operationFailed'));
    } finally {
      setSubmitting(false);
    }
  };

  const remove = async (record: AdminTenantPackageView) => {
    try {
      await tenantPackageApi.remove(record.id);
      message.success(t('tenantPackages.deleted', { name: record.packageName }));
      crud.reload();
    } catch (ex: unknown) {
      // 占用删除失败（TENANT_PACKAGE_IN_USE）→ 展示后端业务错误文案；失败不重取（行保留）
      message.error(ex instanceof ApiError ? ex.message : t('tenantPackages.errors.deleteFailed'));
    }
  };

  const columns: ColumnsType<AdminTenantPackageView> = [
    { title: t('tenantPackages.columns.packageName'), dataIndex: 'packageName', key: 'packageName', minWidth: 160, fixed: 'left' },
    { title: t('tenantPackages.columns.remark'), dataIndex: 'remark', key: 'remark', minWidth: 200, ellipsis: true, render: (v: string | null) => v ?? '—' },
    { title: t('tenantPackages.columns.menuCount'), dataIndex: 'menuCount', key: 'menuCount', minWidth: 100 },
    { title: t('tenantPackages.columns.tenantCount'), dataIndex: 'tenantCount', key: 'tenantCount', minWidth: 100 },
    { title: t('tenantPackages.columns.createdAt'), dataIndex: 'createdAt', key: 'createdAt', minWidth: 180, render: (v: string) => formatDate(v) },
    { title: t('tenantPackages.columns.updatedAt'), dataIndex: 'updatedAt', key: 'updatedAt', minWidth: 180, render: (v: string) => formatDate(v) },
    {
      title: t('tenantPackages.columns.actions'), key: 'actions', minWidth: 160, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          <Auth code="system:tenant-package:update">
            {/* AC-20/21（套餐半句）+AC-24：行内「绑定菜单」入口——复用既有套餐弹窗
                （菜单树+能力勾选同屏，限高滚动收口不变；打开即实时拉目录 AC-21） */}
            <Button size="small" onClick={() => openEdit(record)}>{t('tenantPackages.actions.bindMenus')}</Button>
          </Auth>
          <Auth code="system:tenant-package:update">
            <Button size="small" onClick={() => openEdit(record)}>{t('tenantPackages.actions.edit')}</Button>
          </Auth>
          <Auth code="system:tenant-package:delete">
            <Popconfirm
              title={t('tenantPackages.actions.deleteConfirm', { name: record.packageName })}
              onConfirm={() => void remove(record)}
            >
              <Button size="small" danger>{t('tenantPackages.actions.delete')}</Button>
            </Popconfirm>
          </Auth>
        </Space>
      ),
    },
  ];

  return (
    <>
      <CrudTable<AdminTenantPackageView>
        crud={crud}
        tableKey="tenant-packages"
        title={t('tenantPackages.title')}
        columns={columns}
        searchPlaceholder={t('tenantPackages.search.placeholder')}
        reloadAriaLabel={t('tenantPackages.reload')}
        createPermission="system:tenant-package:create"
        createLabel={t('tenantPackages.create')}
        onCreate={openCreate}
        totalText={(total) => t('tenantPackages.total', { total })}
      />

      <CrudFormModal<PackageFormValues>
        open={creating || editing !== null}
        title={editing ? t('tenantPackages.form.editTitle', { name: editing.packageName }) : t('tenantPackages.form.createTitle')}
        form={form}
        submitting={submitting}
        onOk={() => void submit()}
        onCancel={() => { setCreating(false); setEditing(null); }}
        width={640}
        /* footer 显式 i18n 文案（AC-24：确定/取消中文字径钉死，不随装配 locale 缺省漂移） */
        okText={t('common.actions.confirm')}
        cancelText={t('common.actions.cancel')}
      >
        <Form.Item
          name="packageName"
          label={t('tenantPackages.form.name')}
          rules={[
            { required: true, message: t('tenantPackages.form.nameRequired') },
            { min: 1, max: 128, message: t('common.validation.lengthRange', { min: 1, max: 128 }) },
          ]}
        >
          <Input placeholder={t('tenantPackages.form.namePlaceholder')} maxLength={128} autoComplete="off" />
        </Form.Item>
        <Form.Item name="remark" label={t('tenantPackages.form.remark')} rules={[{ max: 255, message: t('common.validation.maxLength', { max: 255 }) }]}>
          <Input.TextArea rows={2} />
        </Form.Item>
        {/* AC-24：菜单树在限高容器内滚动——弹窗整体不无限撑高，footer 按钮始终可见 */}
        <Form.Item label={t('tenantPackages.form.menus')} extra={t('tenantPackages.form.menusExtra')}>
          {menuTree.length > 0 ? (
            <div style={{ maxHeight: 320, overflow: 'auto' }}>
              <PackageMenuTree tree={menuTree} checkedIds={checkedMenuIds} onChange={setCheckedMenuIds} />
            </div>
          ) : (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {menuTreeLoading ? t('tenantPackages.form.menusLoading') : t('tenantPackages.form.menusEmpty')}
            </Typography.Text>
          )}
        </Form.Item>
        {/* 能力组（REQ-20261121-054613）：Checkbox 组勾选能力码，语义同菜单勾选（全量替换）；
            AC-24：能力复选组同样限高滚动 */}
        <Form.Item label={t('tenantPackages.form.capabilities')} extra={t('tenantPackages.form.capabilitiesExtra')}>
          {capabilities.length > 0 ? (
            <div style={{ maxHeight: 200, overflow: 'auto' }}>
              <Checkbox.Group
                value={checkedCapabilities}
                onChange={(values) => setCheckedCapabilities(values as string[])}
                options={capabilityOptions}
              />
            </div>
          ) : (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {capabilitiesLoading ? t('tenantPackages.form.capabilitiesLoading') : t('tenantPackages.form.capabilitiesEmpty')}
            </Typography.Text>
          )}
        </Form.Item>
      </CrudFormModal>
    </>
  );
}

/** 套餐菜单树形勾选（antd Tree checkable；fieldNames 对齐 AdminMenuView）：
 *  checkStrictly 显式勾选集（menuIds 全量替换语义，与角色菜单分配树同口径）；
 *  BUTTON 节点标题附「按钮」Tag（REQ-20261119-051119 按钮级权限投影口径）；
 *  AC-9：树数据剥空 children（叶子无展开箭头）。 */
function PackageMenuTree({
  tree,
  checkedIds,
  onChange,
}: {
  tree: AdminMenuView[];
  checkedIds: string[];
  onChange: (ids: string[]) => void;
}) {
  const { t } = useTranslation();
  // BUTTON 节点标题 → ReactNode（menuName + 「按钮」Tag）；其余保持纯文本（antd Tree title 接受 ReactNode）
  const treeData = useMemo(
    () => decorateButtonTitles(stripEmptyChildren(tree), t('menus.type.button')),
    [tree, t],
  );
  return (
    <Tree
      checkable
      checkStrictly
      selectable={false}
      defaultExpandAll
      blockNode
      treeData={treeData as never[]}
      fieldNames={{ title: 'menuName', key: 'id', children: 'children' }}
      checkedKeys={checkedIds}
      onCheck={(checked) => {
        const shape = checked as { checked: (string | number)[] };
        onChange(shape.checked.map(String));
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
