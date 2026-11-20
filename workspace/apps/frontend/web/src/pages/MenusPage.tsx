import { useId, useState } from 'react';
import { App, Button, Form, Input, InputNumber, Popconfirm, Select, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { ApiError, Auth, systemApi, useCrud, useMeStore, type AdminMenuView } from '@acme/core';
import { CrudFormModal } from '../components/CrudFormModal';
import { CrudTable } from '../components/CrudTable';
import { FilterField, FilterPanel } from '../components/FilterPanel';
import { MenuIconPicker } from '../components/MenuIconPicker';

const { Text } = Typography;

/** 筛选集合（REQ-20261120-213603）：值域对齐 CrudFilters（string|undefined），
 *  visible 以 'true'/'false' 字符串承载，请求适配方转 boolean（空值不进查询串）。 */
type MenuFilters = {
  menuType?: 'DIRECTORY' | 'MENU' | 'BUTTON';
  status?: 'ENABLED' | 'DISABLED';
  visible?: 'true' | 'false';
};

interface MenuFormValues {
  parentId?: string;
  menuName: string;
  menuType: string;
  path?: string;
  component?: string;
  icon?: string;
  sortOrder: number;
  visible?: boolean;
  permissionCode?: string;
}

/** 菜单管理（p3-admin）：树形表格全量展示 + 筛选 + 新建/编辑/删除；parent 下拉来自当前树。
 *  迁移 CRUD 范本（REQ-20261114-120101）：useCrud + CrudTable（树表 paged=false）+ CrudFormModal。
 *  REQ-20261119-051119：menuTypes 增 BUTTON（按钮级权限投影）——BUTTON 节点必须挂 permissionCode、
 *  path 无路由语义（可留空提示）。
 *  REQ-20261120-213603：启用关键字搜索＋类型/状态/可见性筛选（命中节点∪祖先链由后端组装）
 *  ＋重置入口（一键清空恢复全量树）。 */
export function MenusPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];
  const filterId = useId();

  const menuTypes: { value: NonNullable<MenuFilters['menuType']>; label: string }[] = [
    { value: 'DIRECTORY', label: t('menus.type.directoryOption') },
    { value: 'MENU', label: t('menus.type.menuOption') },
    { value: 'BUTTON', label: t('menus.type.buttonOption') },
  ];

  const crud = useCrud<AdminMenuView, MenuFilters>({
    // 全量树：page/size 仅随请求携带（统一列表分页口径），后端按全量返回；
    // REQ-20261120-213603：keyword＋类型/状态/可见性随请求携带（空值不进查询串，不传=全量树）；
    // 响应兼容运行时校验：数组（既有口径）或 { records } 分页信封（外部输入不信任）
    fetchPage: async ({ page, size, keyword, filters, signal }) => {
      const tree = await systemApi.menuTree(page, size, signal, {
        keyword: keyword || undefined,
        menuType: filters?.menuType,
        status: filters?.status,
        visible: filters?.visible === 'true' ? true : filters?.visible === 'false' ? false : undefined,
      });
      const rows = Array.isArray(tree)
        ? tree
        : ((tree as unknown as { records?: AdminMenuView[] }).records ?? []);
      return { records: rows, total: rows.length };
    },
    filters: { menuType: undefined, status: undefined, visible: undefined },
    permissions,
    errorMessage: t('menus.errors.loadFailed'),
  });

  // 关键字搜索框受控（重置需可见清空；提交语义与 UsersPage 一致：Enter/搜索钮/清除 × 均触发）
  const [searchText, setSearchText] = useState('');
  const submitSearch = (value: string) => {
    setSearchText(value);
    crud.search(value.trim());
  };

  // 重置（AC-12）：清空关键字＋全部下拉并重取（恢复全量树）；同一事件批内合并为一次拉取
  const resetAll = () => {
    setSearchText('');
    crud.search('');
    crud.resetFilters();
  };

  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<AdminMenuView | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<MenuFormValues>();
  // BUTTON 校验联动（REQ-20261119-051119）：按钮节点必须挂权限码、path 可留空
  const menuType = Form.useWatch('menuType', form);

  const openCreate = (parentId?: number) => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ parentId: parentId ? String(parentId) : undefined, sortOrder: 0, menuType: 'MENU', visible: true });
    setCreating(true);
  };

  const openEdit = (record: AdminMenuView) => {
    setEditing(record);
    form.setFieldsValue({
      parentId: record.parentId && record.parentId !== 0 ? String(record.parentId) : undefined,
      menuName: record.menuName,
      menuType: record.menuType,
      path: record.path ?? undefined,
      component: record.component ?? undefined,
      icon: record.icon ?? undefined,
      sortOrder: record.sortOrder ?? 0,
      visible: record.visible ?? true,
      permissionCode: record.permissionCode ?? undefined,
    });
    setCreating(false);
  };

  const submit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (editing) {
        await systemApi.menuUpdate(editing.id, {
          menuName: values.menuName,
          path: values.path,
          component: values.component,
          icon: values.icon,
          sortOrder: values.sortOrder,
          visible: values.visible ?? true,
          permissionCode: values.permissionCode,
          version: editing.version,
        });
        message.success(t('menus.form.updated'));
      } else {
        await systemApi.menuCreate({
          parentId: values.parentId ? Number(values.parentId) : null,
          menuName: values.menuName,
          menuType: values.menuType,
          path: values.path,
          component: values.component,
          icon: values.icon,
          sortOrder: values.sortOrder,
          visible: values.visible ?? true,
          permissionCode: values.permissionCode,
        });
        message.success(t('menus.form.created'));
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

  const remove = async (record: AdminMenuView) => {
    try {
      await systemApi.menuDelete(record.id);
      message.success(t('menus.deleted', { name: record.menuName }));
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.deleteFailed'));
    }
  };

  const columns: ColumnsType<AdminMenuView> = [
    { title: t('menus.columns.menuName'), dataIndex: 'menuName', key: 'menuName', minWidth: 200, fixed: 'left' },
    { title: t('menus.columns.menuType'), dataIndex: 'menuType', key: 'menuType', minWidth: 110, render: (v: string) => <Tag color={v === 'DIRECTORY' ? 'processing' : v === 'BUTTON' ? 'purple' : 'success'}>{v === 'DIRECTORY' ? t('menus.type.directory') : v === 'BUTTON' ? t('menus.type.button') : t('menus.type.menu')}</Tag> },
    { title: t('menus.columns.path'), dataIndex: 'path', key: 'path', minWidth: 180, ellipsis: true, render: (v: string | null) => v ?? '—' },
    { title: t('menus.columns.component'), dataIndex: 'component', key: 'component', minWidth: 200, ellipsis: true, render: (v: string | null) => v ?? '—' },
    { title: t('menus.columns.permissionCode'), dataIndex: 'permissionCode', key: 'permissionCode', minWidth: 200, ellipsis: true, render: (v: string | null) => (v ? <Text code>{v}</Text> : '—') },
    { title: t('menus.columns.sortOrder'), dataIndex: 'sortOrder', key: 'sortOrder', minWidth: 70 },
    {
      title: t('menus.columns.visible'), dataIndex: 'visible', key: 'visible', minWidth: 80,
      render: (v: boolean | null) => (v === false ? <Tag>{t('common.status.hidden')}</Tag> : <Tag color="success">{t('common.status.visible')}</Tag>),
    },
    {
      title: t('menus.columns.actions'), key: 'actions', minWidth: 180, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          <Auth code="system:menu:update">
            <Button size="small" onClick={() => openEdit(record)}>{t('menus.actions.edit')}</Button>
          </Auth>
          <Auth code="system:menu:create">
            <Button size="small" onClick={() => openCreate(record.id)}>{t('menus.actions.addChild')}</Button>
          </Auth>
          <Auth code="system:menu:delete">
            <Popconfirm title={t('menus.actions.deleteConfirm', { name: record.menuName })} onConfirm={() => void remove(record)}>
              <Button size="small" danger>{t('menus.actions.delete')}</Button>
            </Popconfirm>
          </Auth>
        </Space>
      ),
    },
  ];

  return (
    <>
      <CrudTable<AdminMenuView, MenuFilters>
        crud={crud}
        tableKey="menus"
        title={t('menus.title')}
        columns={columns}
        paged={false}
        reloadAriaLabel={t('menus.reload')}
        createPermission="system:menu:create"
        createLabel={t('menus.create')}
        onCreate={() => openCreate()}
        filterPanel={
          <FilterPanel
            actions={
              <Button onClick={resetAll}>{t('menus.filters.reset')}</Button>
            }
          >
            <FilterField label={t('common.filters.keyword')} htmlFor={`${filterId}-keyword`}>
              <Input.Search
                id={`${filterId}-keyword`}
                placeholder={t('menus.search.placeholder')}
                allowClear
                value={searchText}
                onChange={(event) => setSearchText(event.target.value)}
                onSearch={(value) => submitSearch(value.trim())}
              />
            </FilterField>
            <FilterField label={t('menus.filters.menuType')} htmlFor={`${filterId}-menuType`}>
              <Select<MenuFilters['menuType']>
                id={`${filterId}-menuType`}
                allowClear
                aria-label={t('menus.filters.menuType')}
                placeholder={t('menus.filters.menuType')}
                options={menuTypes}
                value={crud.filters.menuType}
                onChange={(value) => crud.setFilter('menuType', value)}
              />
            </FilterField>
            <FilterField label={t('menus.filters.status')} htmlFor={`${filterId}-status`}>
              <Select<MenuFilters['status']>
                id={`${filterId}-status`}
                allowClear
                aria-label={t('menus.filters.status')}
                placeholder={t('menus.filters.status')}
                options={[
                  { value: 'ENABLED', label: t('common.status.enabled') },
                  { value: 'DISABLED', label: t('common.status.disabled') },
                ]}
                value={crud.filters.status}
                onChange={(value) => crud.setFilter('status', value)}
              />
            </FilterField>
            <FilterField label={t('menus.filters.visible')} htmlFor={`${filterId}-visible`}>
              <Select<MenuFilters['visible']>
                id={`${filterId}-visible`}
                allowClear
                aria-label={t('menus.filters.visible')}
                placeholder={t('menus.filters.visible')}
                options={[
                  { value: 'true', label: t('common.status.visible') },
                  { value: 'false', label: t('common.status.hidden') },
                ]}
                value={crud.filters.visible}
                onChange={(value) => crud.setFilter('visible', value)}
              />
            </FilterField>
          </FilterPanel>
        }
      />

      <CrudFormModal<MenuFormValues>
        open={creating || editing !== null}
        title={editing ? t('menus.form.editTitle', { name: editing.menuName }) : t('menus.form.createTitle')}
        form={form}
        submitting={submitting}
        onOk={() => void submit()}
        onCancel={() => { setCreating(false); setEditing(null); }}
      >
        <Form.Item name="parentId" label={t('menus.form.parent')}>
          <Select
            allowClear
            placeholder={t('menus.form.parentPlaceholder')}
            optionFilterProp="label"
            showSearch
            options={flattenMenuOptions(crud.records)}
          />
        </Form.Item>
        <Form.Item name="menuName" label={t('menus.form.name')} rules={[{ required: true, message: t('menus.form.nameRequired') }, { min: 1, max: 64, message: t('common.validation.lengthRange', { min: 1, max: 64 }) }]}>
          <Input placeholder={t('menus.form.namePlaceholder')} />
        </Form.Item>
        <Form.Item name="menuType" label={t('menus.form.type')} rules={[{ required: true, message: t('menus.form.typeRequired') }]}>
          <Select options={menuTypes} />
        </Form.Item>
        <Form.Item
          name="path"
          label={t('menus.form.path')}
          rules={[{ max: 255, message: t('common.validation.maxLength', { max: 255 }) }]}
          extra={menuType === 'BUTTON' ? t('menus.form.pathButtonOptional') : undefined}
        >
          <Input placeholder={t('menus.form.pathPlaceholder')} autoComplete="off" />
        </Form.Item>
        <Form.Item name="component" label={t('menus.form.component')} rules={[{ max: 255, message: t('common.validation.maxLength', { max: 255 }) }]}>
          <Input placeholder={t('menus.form.componentPlaceholder')} autoComplete="off" />
        </Form.Item>
        <Form.Item name="icon" label={t('menus.form.icon')} rules={[{ max: 64, message: t('common.validation.maxLength', { max: 64 }) }]}>
          <MenuIconPicker placeholder={t('menus.form.iconPlaceholder')} />
        </Form.Item>
        <Form.Item name="sortOrder" label={t('menus.form.sortOrder')} rules={[{ required: true, message: t('menus.form.sortOrderRequired') }]}>
          <InputNumber min={0} max={9999} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="visible" valuePropName="checked" label={t('menus.form.visible')}>
          <Select
            options={[
              { value: true, label: t('common.status.visible') },
              { value: false, label: t('common.status.hidden') },
            ]}
          />
        </Form.Item>
        <Form.Item
          name="permissionCode"
          label={t('menus.form.permissionCode')}
          rules={[
            // BUTTON 节点必须挂码（操作权限投影）；其余类型仅约束长度
            ...(menuType === 'BUTTON' ? [{ required: true, message: t('menus.form.permissionCodeRequired') }] : []),
            { max: 128, message: t('common.validation.maxLength', { max: 128 }) },
          ]}
        >
          <Input placeholder={t('menus.form.permissionCodePlaceholder')} autoComplete="off" />
        </Form.Item>
        {editing ? (
          <Text type="secondary" style={{ fontSize: 12 }}>{t('menus.form.lockNote', { version: editing.version })}</Text>
        ) : null}
      </CrudFormModal>
    </>
  );
}

/** 展平菜单树为父级下拉选项（含层级缩进）。 */
function flattenMenuOptions(nodes: AdminMenuView[], depth = 0): { value: string; label: string }[] {
  const out: { value: string; label: string }[] = [];
  for (const n of nodes) {
    out.push({ value: String(n.id), label: `${'　'.repeat(depth)}${n.menuName}` });
    if (n.children?.length) {
      out.push(...flattenMenuOptions(n.children, depth + 1));
    }
  }
  return out;
}
