import type { ReactNode } from 'react';
import { Alert, Button, Card, Input, Space, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { GetRowKey } from 'antd/es/table/interface';
import type { TableProps } from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import type { CrudController } from '@acme/core';

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
  /** 关键字搜索占位文案。 */
  searchPlaceholder?: string;
  /** 搜索框宽度（默认 200）。 */
  searchWidth?: number;
  /** 是否渲染关键字搜索框（默认 true）。 */
  searchable?: boolean;
  /** 附加筛选控件（受控值接 crud.filters / crud.setFilter）。 */
  filters?: ReactNode;
  /** 工具栏附加内容（搜索/筛选/刷新/新建之后）。 */
  actions?: ReactNode;
  /** 新建按钮权限码（显隐 fail-closed 交给 crud.can）。 */
  createPermission?: string;
  /** 新建按钮文案。 */
  createLabel?: ReactNode;
  /** 新建点击回调（与 createPermission 同时提供才渲染按钮）。 */
  onCreate?: () => void;
  /** 刷新按钮 aria-label（无障碍语义）。 */
  reloadAriaLabel?: string;
  /** 分页总数文案（默认「共 X 条」）。 */
  totalText?: (total: number) => string;
  /** 表格尺寸（透传 antd Table size；主从/紧凑表用 small）。 */
  size?: TableProps<T>['size'];
  /** 行点击等事件（透传 antd Table onRow；主从表行选中）。 */
  onRow?: OnRow<T>;
  /** 行类名（透传 antd Table rowClassName；选中行高亮）。 */
  rowClassName?: RowClassName<T>;
  /** 是否分页（默认 true；树形/全量小表传 false 隐藏分页器）。 */
  paged?: boolean;
  /** 卡片体内、表格之上的筛选面板（表单驱动筛选页保持原有布局）。 */
  filterPanel?: ReactNode;
  /** 卡片体内、表格之下的补充内容（操作提示等）。 */
  footer?: ReactNode;
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
  searchPlaceholder = '搜索',
  searchWidth = 200,
  searchable = true,
  filters,
  actions,
  createPermission,
  createLabel = '新建',
  onCreate,
  reloadAriaLabel = '刷新列表',
  totalText,
  size,
  onRow,
  rowClassName,
  paged = true,
  filterPanel,
  footer,
}: CrudTableProps<T, F>) {
  return (
    <Card
      title={title}
      extra={
        <Space wrap>
          {searchable ? (
            <Input.Search
              placeholder={searchPlaceholder}
              allowClear
              style={{ width: searchWidth }}
              onSearch={(value) => crud.search(value.trim())}
            />
          ) : null}
          {filters}
          <Button icon={<ReloadOutlined />} onClick={() => crud.reload()} aria-label={reloadAriaLabel} />
          {createPermission && onCreate && crud.can(createPermission) ? (
            <Button type="primary" icon={<PlusOutlined />} onClick={onCreate}>{createLabel}</Button>
          ) : null}
          {actions}
        </Space>
      }
    >
      {filterPanel}
      {crud.error ? (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message={crud.error}
          action={<Button size="small" onClick={() => crud.reload()}>重试</Button>}
        />
      ) : null}
      <Table<T>
        rowKey={rowKey ?? 'id'}
        columns={columns}
        dataSource={crud.records}
        loading={crud.loading}
        size={size}
        onRow={onRow}
        rowClassName={rowClassName}
        pagination={paged ? {
          current: crud.page,
          pageSize: crud.size,
          total: crud.total,
          showTotal: totalText ?? ((t) => `共 ${t} 条`),
          onChange: (p) => crud.gotoPage(p),
        } : false}
      />
      {footer}
    </Card>
  );
}
