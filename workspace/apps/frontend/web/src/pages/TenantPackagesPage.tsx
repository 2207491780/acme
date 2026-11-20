import { useMemo, useRef, useState, type ReactNode } from 'react';
import { App, Button, Form, Input, Popconfirm, Space, Tag, Tree, Typography } from 'antd';
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
  type AdminMenuView,
  type AdminTenantPackageView,
} from '@acme/core';
import { CrudFormModal } from '../components/CrudFormModal';
import { CrudTable } from '../components/CrudTable';

interface PackageFormValues {
  packageName: string;
  remark?: string;
}

/** 套餐管理（REQ-20261121-022910）：分页/搜索/新建/编辑/删除；
 *  新建/编辑弹窗 = 名称 + 备注 + 菜单树勾选（Tree checkable，数据源 GET 既有菜单树接口）；
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
  // 乐观锁版本（REQ-20261121-022910 round 2：后端 AdminTenantPackageUpdate @NotNull version——
  // 编辑随体提交；创建不带 version）。回显优先取 detail.version（新于列表行），列表行兜底；
  // round 2 补：wire 字符串口径（FR-024 Long→string），内部 Number() 归一容错，提交 String 化。
  const [editVersion, setEditVersion] = useState<number | null>(null);

  // 菜单树数据源（GET 既有菜单树接口，全量含 BUTTON）：首次打开弹窗时懒加载一次，弹窗复用
  const [menuTree, setMenuTree] = useState<AdminMenuView[]>([]);
  const [menuTreeLoading, setMenuTreeLoading] = useState(false);
  const menuTreeRef = useRef<AdminMenuView[] | null>(null);
  const loadMenuTree = () => {
    if (menuTreeRef.current) {
      setMenuTree(menuTreeRef.current);
      return;
    }
    setMenuTreeLoading(true);
    systemApi.menuTree()
      .then((tree) => {
        menuTreeRef.current = tree;
        setMenuTree(tree);
      })
      .catch(() => setMenuTree([]))
      .finally(() => setMenuTreeLoading(false));
  };

  const openCreate = () => {
    setEditing(null);
    setCheckedMenuIds([]);
    setEditVersion(null);
    form.resetFields();
    loadMenuTree();
    setCreating(true);
  };

  const openEdit = (record: AdminTenantPackageView) => {
    setEditing(record);
    setCheckedMenuIds([]);
    setEditVersion(record.version != null && Number.isFinite(Number(record.version)) ? Number(record.version) : null);
    form.setFieldsValue({ packageName: record.packageName, remark: record.remark ?? undefined });
    loadMenuTree();
    setCreating(false);
    // 详情回填勾选集与乐观锁版本（AdminTenantPackageDetail.menuIds/version）；失败提示并保留弹窗（可重开重试）
    tenantPackageApi.detail(record.id)
      .then((detail) => {
        setCheckedMenuIds(detail.menuIds.map(String));
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
    // 公共段：name/remark/menuIds；编辑另随体 version（round 2 乐观锁，创建不带 version）
    const base = {
      packageName: values.packageName.trim(),
      remark: values.remark?.trim() || null,
      menuIds: checkedMenuIds.map(Number),
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
      // 业务失败（TENANT_PACKAGE_DUPLICATE/MENU_INVALID 等）→ 透出后端信封文案
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
        <Form.Item label={t('tenantPackages.form.menus')} extra={t('tenantPackages.form.menusExtra')}>
          {menuTree.length > 0 ? (
            <PackageMenuTree tree={menuTree} checkedIds={checkedMenuIds} onChange={setCheckedMenuIds} />
          ) : (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {menuTreeLoading ? t('tenantPackages.form.menusLoading') : t('tenantPackages.form.menusEmpty')}
            </Typography.Text>
          )}
        </Form.Item>
      </CrudFormModal>
    </>
  );
}

/** 套餐菜单树形勾选（antd Tree checkable；fieldNames 对齐 AdminMenuView）：
 *  checkStrictly 显式勾选集（menuIds 全量替换语义，与角色菜单分配树同口径）；
 *  BUTTON 节点标题附「按钮」Tag（REQ-20261119-051119 按钮级权限投影口径）。 */
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
  const treeData = useMemo(() => decorateButtonTitles(tree, t('menus.type.button')), [tree, t]);
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
  children: MenuTreeNodeTitle[];
}

/** 递归装饰菜单树标题：menuType=BUTTON 节点附「按钮」Tag（其余节点原样）。 */
function decorateButtonTitles(nodes: AdminMenuView[], buttonLabel: string): MenuTreeNodeTitle[] {
  return nodes.map((node) => ({
    ...node,
    menuName: node.menuType === 'BUTTON'
      ? (
          <span>
            {node.menuName}
            <Tag style={{ marginLeft: 8 }} color="purple">{buttonLabel}</Tag>
          </span>
        )
      : node.menuName,
    children: node.children?.length ? decorateButtonTitles(node.children, buttonLabel) : [],
  }));
}
