import { useMemo, useState, type ReactNode } from 'react';
import { App, Button, Checkbox, Input, Popover, Select, Space, Switch, Typography } from 'antd';
import { SettingOutlined } from '@ant-design/icons';
import type { ColumnsType, ColumnType } from 'antd/es/table';
import type { FilterDropdownProps } from 'antd/es/table/interface';
import { useTranslation } from 'react-i18next';
import { SPACING } from '@acme/core';

/**
 * 通用表格增强（REQ-20261117-140427）：多列组合筛选/排序 + 列设置（显隐/左右固定/边框）。
 * 筛选与排序为 AntD 列头本地语义：分页表仅作用于当前页数据（设置面板内明示口径），
 * 不改变 useCrud 服务端查询契约；列设置按 tableKey 版本化持久化（运行时校验，损坏回退缺省）。
 */

const STORAGE_PREFIX = 'acme.table-settings.v1:';
/** 枚举筛选上限：唯一值超过该数的列改用关键词搜索筛选。 */
const ENUM_FILTER_LIMIT = 20;

export type ColumnFixed = 'left' | 'right' | 'none';

interface ColumnSetting {
  visible: boolean;
  fixed: ColumnFixed;
}

export interface TableSettings {
  bordered: boolean;
  columns: Record<string, ColumnSetting>;
}

export interface TableSettingsControl {
  settings: TableSettings;
  toggleVisible: (key: string) => void;
  setFixed: (key: string, fixed: ColumnFixed) => void;
  setBordered: (value: boolean) => void;
  reset: () => void;
}

function columnKey<T>(column: ColumnsType<T>[number], index: number): string {
  if (column.key !== undefined && column.key !== null) return String(column.key);
  if ('dataIndex' in column && typeof column.dataIndex === 'string') return column.dataIndex;
  return `col-${index}`;
}

function buildDefaults<T>(columns: ColumnsType<T>): TableSettings {
  const entries: Record<string, ColumnSetting> = {};
  columns.forEach((column, index) => {
    const fixed = column.fixed === 'left' || column.fixed === 'right' ? column.fixed : 'none';
    entries[columnKey(column, index)] = { visible: true, fixed };
  });
  return { bordered: false, columns: entries };
}

function isColumnSetting(value: unknown): value is ColumnSetting {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as { visible?: unknown; fixed?: unknown };
  return typeof candidate.visible === 'boolean'
    && (candidate.fixed === 'left' || candidate.fixed === 'right' || candidate.fixed === 'none');
}

/** 读取持久化列设置：损坏/未知形状一律回退缺省（不覆盖原存储）；未知列键忽略，新增列取缺省。 */
function loadSettings<T>(tableKey: string | undefined, columns: ColumnsType<T>): TableSettings {
  const defaults = buildDefaults(columns);
  if (!tableKey) return defaults;
  try {
    const raw = window.localStorage.getItem(STORAGE_PREFIX + tableKey);
    if (!raw) return defaults;
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null) return defaults;
    const candidate = parsed as { bordered?: unknown; columns?: unknown };
    if (typeof candidate.bordered !== 'boolean' || typeof candidate.columns !== 'object' || candidate.columns === null) {
      return defaults;
    }
    const merged = { ...defaults.columns };
    for (const [key, value] of Object.entries(candidate.columns)) {
      if (key in merged && isColumnSetting(value)) merged[key] = value;
    }
    return { bordered: candidate.bordered, columns: merged };
  } catch {
    return defaults;
  }
}

function persistSettings(tableKey: string | undefined, settings: TableSettings): boolean {
  if (!tableKey) return true;
  try {
    window.localStorage.setItem(STORAGE_PREFIX + tableKey, JSON.stringify(settings));
    return true;
  } catch {
    return false;
  }
}

function rawValue<T>(record: T, dataIndex: string): unknown {
  return (record as Record<string, unknown>)[dataIndex];
}

function compareValues(a: unknown, b: unknown): number {
  if (a == null && b == null) return 0;
  if (a == null) return 1;
  if (b == null) return -1;
  if (typeof a === 'number' && typeof b === 'number') return a - b;
  if (typeof a === 'boolean' && typeof b === 'boolean') return Number(a) - Number(b);
  return String(a).localeCompare(String(b), 'zh-Hans-CN', { numeric: true });
}

function SearchFilterDropdown({ setSelectedKeys, selectedKeys, confirm, clearFilters }: FilterDropdownProps) {
  const { t } = useTranslation();
  return (
    <div style={{ padding: SPACING.sm }} onKeyDown={(e) => e.stopPropagation()}>
      <Input
        allowClear
        autoFocus
        value={typeof selectedKeys[0] === 'string' ? selectedKeys[0] : ''}
        onChange={(e) => setSelectedKeys(e.target.value ? [e.target.value] : [])}
        onPressEnter={() => confirm()}
        placeholder={t('common.tableSettings.filterPlaceholder')}
        style={{ width: 180, marginBottom: SPACING.sm, display: 'block' }}
      />
      <Space>
        <Button type="primary" size="small" onClick={() => confirm()}>{t('common.actions.confirm')}</Button>
        <Button size="small" onClick={() => { clearFilters?.(); confirm(); }}>{t('common.actions.reset')}</Button>
      </Space>
    </div>
  );
}

/**
 * 列增强：按设置显隐/固定（固定列归位到两侧边缘），并为原始值列注入
 * 多列排序（multiple 优先级=列序）与多列筛选（低基数枚举多选，高基数关键词搜索）。
 * 已自带 sorter/filters 的列不覆盖；无 dataIndex 的复合展示列保持原样。
 */
function enhanceColumns<T extends object>(columns: ColumnsType<T>, settings: TableSettings, data: T[]): ColumnsType<T> {
  const enhanced: ColumnsType<T> = [];
  columns.forEach((column, index) => {
    const key = columnKey(column, index);
    const setting = settings.columns[key];
    if (setting && !setting.visible) return;
    const col: ColumnType<T> = { ...column };
    if (setting) col.fixed = setting.fixed === 'none' ? undefined : setting.fixed;
    const dataIndex = typeof col.dataIndex === 'string' ? col.dataIndex : null;
    if (dataIndex && !col.sorter && !col.filters && !col.filterDropdown) {
      const values = data.map((record) => rawValue(record, dataIndex));
      const primitive = values.every((v) => v == null || typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean');
      const present = values.filter((v) => v != null);
      if (primitive && present.length > 0) {
        col.sorter = {
          compare: (a, b) => compareValues(rawValue(a, dataIndex), rawValue(b, dataIndex)),
          multiple: columns.length - index,
        };
        const seen = new Set<unknown>();
        const unique: { value: string | number | boolean; label: ReactNode }[] = [];
        for (const record of data) {
          const value = rawValue(record, dataIndex);
          if (value == null || seen.has(value)) continue;
          seen.add(value);
          unique.push({
            value: value as string | number | boolean,
            label: col.render ? (col.render(value, record, 0) as ReactNode) : String(value),
          });
        }
        if (unique.length > 1 && unique.length <= ENUM_FILTER_LIMIT) {
          col.filters = unique.map(({ value, label }) => ({ text: label, value }));
          col.onFilter = (value, record) => rawValue(record, dataIndex) === value;
        } else if (unique.length > ENUM_FILTER_LIMIT) {
          col.filterDropdown = (props) => <SearchFilterDropdown {...props} />;
          col.onFilter = (value, record) => String(rawValue(record, dataIndex) ?? '').toLowerCase().includes(String(value).toLowerCase());
        }
      }
    }
    enhanced.push(col);
  });
  return [
    ...enhanced.filter((col) => col.fixed === 'left'),
    ...enhanced.filter((col) => !col.fixed),
    ...enhanced.filter((col) => col.fixed === 'right'),
  ];
}

/**
 * 表格列设置 hook：tableKey 缺省时完全旁路（列原样、不持久化），供 CrudTable 无条件调用。
 * 存储写入失败经 message 明示「仅当前会话生效」，不静默假装已保存。
 */
export function useTableSettings<T extends object>(
  tableKey: string | undefined,
  columns: ColumnsType<T>,
  data: T[],
): TableSettingsControl & { enhancedColumns: ColumnsType<T> } {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [settings, setSettings] = useState<TableSettings>(() => loadSettings(tableKey, columns));

  const update = (next: TableSettings) => {
    setSettings(next);
    if (!persistSettings(tableKey, next)) {
      message.warning(t('common.tableSettings.saveFailed'));
    }
  };

  const visibleCount = columns.filter((column, index) => settings.columns[columnKey(column, index)]?.visible !== false).length;

  const control: TableSettingsControl = {
    settings,
    toggleVisible: (key) => {
      const current = settings.columns[key] ?? { visible: true, fixed: 'none' };
      if (current.visible && visibleCount <= 1) return;
      update({ ...settings, columns: { ...settings.columns, [key]: { ...current, visible: !current.visible } } });
    },
    setFixed: (key, fixed) => {
      const current = settings.columns[key] ?? { visible: true, fixed: 'none' };
      update({ ...settings, columns: { ...settings.columns, [key]: { ...current, fixed } } });
    },
    setBordered: (bordered) => update({ ...settings, bordered }),
    reset: () => update(buildDefaults(columns)),
  };

  const enhancedColumns = useMemo(
    () => (tableKey ? enhanceColumns(columns, settings, data) : columns),
    [tableKey, columns, settings, data],
  );

  return { ...control, enhancedColumns };
}

/** 列设置入口（表格工具栏按钮 + 弹层）：边框开关、逐列显隐与左右固定、重置默认、筛选排序口径提示。 */
export function TableSettingsButton<T extends object>({
  control,
  columns,
  paged = true,
}: {
  control: TableSettingsControl;
  columns: ColumnsType<T>;
  paged?: boolean;
}) {
  const { t } = useTranslation();
  const visibleCount = columns.filter((column, index) => control.settings.columns[columnKey(column, index)]?.visible !== false).length;
  const content = (
    <div style={{ width: 300 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: SPACING.md }}>
        <Typography.Text strong>{t('common.tableSettings.title')}</Typography.Text>
        <Button type="link" size="small" onClick={control.reset}>{t('common.tableSettings.reset')}</Button>
      </div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: SPACING.md }}>
        <span>{t('common.tableSettings.bordered')}</span>
        <Switch
          size="small"
          checked={control.settings.bordered}
          onChange={control.setBordered}
          aria-label={t('common.tableSettings.bordered')}
        />
      </div>
      <div style={{ maxHeight: 320, overflow: 'auto', display: 'grid', gap: SPACING.sm }}>
        {columns.map((column, index) => {
          const key = columnKey(column, index);
          const setting = control.settings.columns[key] ?? { visible: true, fixed: 'none' };
          const titleText = typeof column.title === 'string' ? column.title : key;
          return (
            <div key={key} style={{ display: 'flex', alignItems: 'center', gap: SPACING.sm }}>
              <Checkbox
                checked={setting.visible}
                disabled={setting.visible && visibleCount <= 1}
                onChange={() => control.toggleVisible(key)}
                style={{ flex: 1, minWidth: 0 }}
              >
                {column.title as ReactNode}
              </Checkbox>
              <Select<ColumnFixed>
                size="small"
                style={{ width: 110 }}
                value={setting.fixed}
                onChange={(value) => control.setFixed(key, value)}
                aria-label={`${t('common.tableSettings.fixed')} ${titleText}`}
                options={[
                  { value: 'none', label: t('common.tableSettings.fixedNone') },
                  { value: 'left', label: t('common.tableSettings.fixedLeft') },
                  { value: 'right', label: t('common.tableSettings.fixedRight') },
                ]}
              />
            </div>
          );
        })}
      </div>
      <Typography.Text type="secondary" style={{ display: 'block', marginTop: SPACING.md, fontSize: 12 }}>
        {t(paged ? 'common.tableSettings.scopePaged' : 'common.tableSettings.scopeAll')}
      </Typography.Text>
    </div>
  );
  return (
    <Popover trigger="click" placement="bottomRight" content={content}>
      <Button icon={<SettingOutlined aria-hidden />} aria-label={t('common.tableSettings.title')} />
    </Popover>
  );
}