import { useId, type ReactNode } from 'react';
import { Alert, Button, Card, Input, Space, Table } from 'antd';
import { useTranslation } from 'react-i18next';
import type { ColumnsType } from 'antd/es/table';
import type { GetRowKey } from 'antd/es/table/interface';
import type { TableProps } from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { type CrudController } from '@acme/core';
import { FilterField, FilterPanel } from './FilterPanel';
import { TableSettingsButton, useTableSettings } from './tableSettings';

type RowClassName<T> = (record: T, index: number) => string;
type OnRow<T> = NonNullable<TableProps<T>['onRow']>;

export interface CrudTableProps<T extends object, F extends Record<string, string | undefined> = Record<string, string | undefined>> {
  /** useCrud headless 状态机控制器（@acme/core）。 */
  crud: CrudController<T, F>;
  /** 卡片标题。 */
  title?: ReactNode;
  columns: ColumnsType<T>;
  /** 行键（字段名或取键函数；默认 'id'）。 */
  rowKey?: string | GetRowKey<T>;
  /** 关键字搜索占位文案（缺省 i18n common.table.searchPlaceholder）。 */
  searchPlaceholder?: string;
  /** 是否渲染关键字搜索框（默认 true）。 */
  searchable?: boolean;
  /** 进阶筛选控件（受控值接 crud.filters / crud.setFilter）。 */
  filters?: ReactNode;
  /** 表格工具栏附加操作。 */
  actions?: ReactNode;
  /** 新建按钮权限码（显隐 fail-closed 交给 crud.can）。 */
  createPermission?: string;
  /** 新建按钮文案（缺省 i18n common.actions.create）。 */
  createLabel?: ReactNode;
  /** 新建点击回调（与 createPermission 同时提供才渲染按钮）。 */
  onCreate?: () => void;
  /** 刷新按钮 aria-label（无障碍语义；缺省 i18n 刷新列表文案）。 */
  reloadAriaLabel?: string;
  /** 分页总数文案（缺省 i18n common.table.total）。 */
  totalText?: (total: number) => string;
  /** 表格尺寸（透传 antd Table size；主从/紧凑表用 small）。 */
  size?: TableProps<T>['size'];
  /** 行点击等事件（透传 antd Table onRow；主从表行选中）。 */
  onRow?: OnRow<T>;
  /** 行类名（透传 antd Table rowClassName；选中行高亮）。 */
  rowClassName?: RowClassName<T>;
  /** 是否分页（默认 true；树形/全量小表传 false 隐藏分页器）。 */
  paged?: boolean;
  /** 独立筛选区域，替代默认关键词与进阶筛选。 */
  filterPanel?: ReactNode;
  /** 卡片体内、表格之下的补充内容（操作提示等）。 */
  footer?: ReactNode;
  /** 列设置持久化键（REQ-20261117-140427）：提供后启用多列筛选/排序与列显隐/固定/边框设置。 */
  tableKey?: string;
}

/**
 * 通用 CRUD 列表（AntD5 展示层，消费 core useCrud headless 状态机）：
 * 搜索 / 筛选 / 刷新 / 新建（权限显隐）/ 错误重试 / 分页一应俱全；
 * 表单弹窗用 CrudFormModal；行内动作由页面在 columns 里按 crud.can 自行显隐。
 */
export function CrudTable<T extends object, F extends Record<string, string | undefined> = Record<string, string | undefined>>({
  crud,
  title,
  columns,
  rowKey,
  searchPlaceholder,
  searchable = true,
  filters,
  actions,
  createPermission,
  createLabel,
  onCreate,
  reloadAriaLabel,
  totalText,
  size,
  onRow,
  rowClassName,
  paged = true,
  filterPanel,
  footer,
  tableKey,
}: CrudTableProps<T, F>) {
  const { t } = useTranslation();
  const searchId = useId();
  const tableSettings = useTableSettings(tableKey, columns, crud.records);
  return (
    <>
      {filterPanel ?? (searchable || filters ? (
        <FilterPanel
          advanced={searchable ? filters : undefined}
          advancedActive={Object.values(crud.filters).some((value) => value !== undefined && value !== '')}
        >
          {searchable ? (
            <FilterField label={t('common.filters.keyword')} htmlFor={searchId}>
              <Input.Search
                id={searchId}
                placeholder={searchPlaceholder ?? t('common.table.searchPlaceholder')}
                allowClear
                onSearch={(value) => crud.search(value.trim())}
              />
            </FilterField>
          ) : filters}
        </FilterPanel>
      ) : null)}
      <Card
        className="crud-table-card"
        title={title}
        extra={
          <Space wrap>
            {tableKey ? <TableSettingsButton control={tableSettings} columns={columns} paged={paged} /> : null}
            <Button icon={<ReloadOutlined />} onClick={() => crud.reload()} aria-label={reloadAriaLabel ?? t('common.table.reloadAria')} />
            {createPermission && onCreate && crud.can(createPermission) ? (
              <Button type="primary" icon={<PlusOutlined />} onClick={onCreate}>{createLabel ?? t('common.actions.create')}</Button>
            ) : null}
            {actions}
          </Space>
        }
      >
        {crud.error ? (
          <Alert
            type="error"
            showIcon
            style={{ marginBottom: 16 }}
            message={crud.error}
            action={<Button size="small" onClick={() => crud.reload()}>{t('common.actions.retry')}</Button>}
          />
        ) : null}
        <Table<T>
          rowKey={rowKey ?? 'id'}
          columns={tableSettings.enhancedColumns}
          dataSource={crud.records}
          loading={crud.loading}
          size={size}
          onRow={onRow}
          rowClassName={rowClassName}
          bordered={tableKey ? tableSettings.settings.bordered : undefined}
          scroll={{ x: 'max-content' }}
          pagination={paged ? {
            current: crud.page,
            pageSize: crud.size,
            total: crud.total,
            showTotal: totalText ?? ((total) => t('common.table.total', { total })),
            onChange: (p) => crud.gotoPage(p),
          } : false}
        />
        {footer}
      </Card>
    </>
  );
}
