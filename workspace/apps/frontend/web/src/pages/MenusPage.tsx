import { useId, useState } from 'react';
import { Button, Input, Select, Tag } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { systemApi, useCrud, useMeStore, type AdminMenuView } from '@acme/core';
import { CrudTable } from '../components/CrudTable';
import { FilterField, FilterPanel } from '../components/FilterPanel';
import { stripEmptyChildren } from '../utils/tree';

/** 筛选集合（REQ-20261120-213603）：值域对齐 CrudFilters（string|undefined），
 *  visible 以 'true'/'false' 字符串承载，请求适配方转 boolean（空值不进查询串）。 */
type MenuFilters = {
  menuType?: 'DIRECTORY' | 'MENU' | 'BUTTON';
  status?: 'ENABLED' | 'DISABLED';
  visible?: 'true' | 'false';
};

/** 菜单管理（p3-admin，REQ-20261125-230807 确认②仅浏览）：树形表格全量展示 + 筛选；
 *  无新建/加子菜单/编辑/删除入口（前端收口，后端 mutation 端点保留可审计）。
 *  REQ-20261119-051119：BUTTON 节点（按钮级操作权限投影）仍以类型列展示。
 *  REQ-20261120-213603：关键字搜索＋类型/状态/可见性筛选（命中节点∪祖先链由后端组装）
 *  ＋重置入口（一键清空恢复全量树）。
 *  REQ-20261125-230807 AC-8/9：展示层剥空 children——无子集行无折叠/展开图标。 */
export function MenusPage() {
  const { t } = useTranslation();
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
    // 响应兼容运行时校验：数组（既有口径）或 { records } 分页信封（外部输入不信任）；
    // AC-8：展示层剥空 children（叶子无无效展开图标）
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
      return { records: stripEmptyChildren(rows), total: rows.length };
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

  const columns: ColumnsType<AdminMenuView> = [
    { title: t('menus.columns.menuName'), dataIndex: 'menuName', key: 'menuName', minWidth: 200, fixed: 'left' },
    { title: t('menus.columns.menuType'), dataIndex: 'menuType', key: 'menuType', minWidth: 110, render: (v: string) => <Tag color={v === 'DIRECTORY' ? 'processing' : v === 'BUTTON' ? 'purple' : 'success'}>{v === 'DIRECTORY' ? t('menus.type.directory') : v === 'BUTTON' ? t('menus.type.button') : t('menus.type.menu')}</Tag> },
    { title: t('menus.columns.path'), dataIndex: 'path', key: 'path', minWidth: 180, ellipsis: true, render: (v: string | null) => v ?? '—' },
    { title: t('menus.columns.component'), dataIndex: 'component', key: 'component', minWidth: 200, ellipsis: true, render: (v: string | null) => v ?? '—' },
    { title: t('menus.columns.permissionCode'), dataIndex: 'permissionCode', key: 'permissionCode', minWidth: 200, ellipsis: true, render: (v: string | null) => (v ? <Tag>{v}</Tag> : '—') },
    { title: t('menus.columns.sortOrder'), dataIndex: 'sortOrder', key: 'sortOrder', minWidth: 70 },
    {
      title: t('menus.columns.visible'), dataIndex: 'visible', key: 'visible', minWidth: 80,
      render: (v: boolean | null) => (v === false ? <Tag>{t('common.status.hidden')}</Tag> : <Tag color="success">{t('common.status.visible')}</Tag>),
    },
  ];

  return (
    <CrudTable<AdminMenuView, MenuFilters>
      crud={crud}
      tableKey="menus"
      title={t('menus.title')}
      columns={columns}
      paged={false}
      reloadAriaLabel={t('menus.reload')}
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
  );
}
