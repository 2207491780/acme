import { useId } from 'react';
import { Button, Select, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { systemApi, useCrud, useMeStore, type AdminPermissionView } from '@acme/core';
import { CrudTable } from '../components/CrudTable';
import { FilterField } from '../components/FilterPanel';

const { Text } = Typography;

/** HTTP 方法筛选项（与后端动词白名单一致）。 */
const HTTP_METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const;

/** 请求方式 → AntD 预设色名（REQ-20261125-230807 AC-14 契约字面：GET=blue/POST=green/PUT=orange/
 *  PATCH=purple/DELETE=red/空=default；预设名经 AntD 派生，不塞 hex——UI-SPEC §0 口径）。 */
const HTTP_METHOD_ANTD: Record<string, string> = {
  GET: 'blue',
  POST: 'green',
  PUT: 'orange',
  PATCH: 'purple',
  DELETE: 'red',
};

/** 进阶筛选集合（keyword 走 crud.search，不进 filters）。 */
type PermissionFilters = {
  status?: 'ENABLED' | 'DISABLED';
  httpMethod?: string;
};

/** 权限点管理（p3-admin，REQ-20261125-230807 确认②仅浏览）：平台级目录分页浏览；
 *  无新建/编辑/删除入口（前端收口，后端 mutation 端点与权限码保留可审计）。
 *  列表（AC-14/15）：新增「请求方式」彩色标签列（值来自既有 httpMethod）与「所属服务」列
 *  （V36 service）；原 method+path 合并列改为仅路径展示。
 *  筛选（REQ-20261120-214653）：关键字搜索 + 状态/HTTP 方法下拉 + 重置入口；空值不进查询串。 */
export function PermissionsPage() {
  const { t } = useTranslation();
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
      // AC-14：请求方式列（彩色标签；空=default）
      title: t('permissions.columns.httpMethod'), dataIndex: 'httpMethod', key: 'httpMethod', minWidth: 100,
      render: (v: string | null) => (v ? <Tag color={HTTP_METHOD_ANTD[v] ?? 'default'}>{v}</Tag> : <Tag>{t('permissions.columns.noMethod')}</Tag>),
    },
    {
      // AC-14：原 method+path 合并列改为仅路径
      title: t('permissions.columns.httpPath'), dataIndex: 'httpPath', key: 'httpPath', minWidth: 220, ellipsis: true,
      render: (v: string | null) => (v ? <Text code>{v}</Text> : '—'),
    },
    {
      // AC-15：所属服务列（V36 service）
      title: t('permissions.columns.service'), dataIndex: 'service', key: 'service', minWidth: 110,
      render: (v: string | null) => v ?? '—',
    },
  ];

  return (
    <CrudTable<AdminPermissionView, PermissionFilters>
      crud={crud}
      tableKey="permissions"
      title={t('permissions.title')}
      columns={columns}
      searchPlaceholder={t('permissions.search.placeholder')}
      reloadAriaLabel={t('permissions.reload')}
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
          <FilterField label={t('permissions.filters.httpMethod')} htmlFor={`${filterId}-http-method`}>
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
  );
}
