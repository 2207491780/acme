import { Button, Select, Spin } from 'antd';
import { useDict, useMeStore } from '@acme/core';

export interface DictSelectProps {
  /** 字典类型代码（如 release_env；经 core useDict 模块缓存共享拉取）。 */
  dictCode: string;
  /** 受控值（字典项 itemValue）。 */
  value?: string;
  /** 受控变更（字典项 itemValue）。 */
  onChange?: (value: string) => void;
  placeholder?: string;
  allowClear?: boolean;
  disabled?: boolean;
  /** 消费权限码（缺省 system:dict:list；无权限不渲染，fail-closed）。 */
  permission?: string;
  id?: string;
  'aria-label'?: string;
}

/**
 * 字典选择器（AntD5 展示层，REQ-20261114-120101）：
 * 受控 value/onChange；选项按字典项（ENABLED，sortOrder 升序）渲染；
 * 无消费权限不渲染；加载/失败态内联反馈并支持显式刷新（复用 useDict.refresh）。
 */
export function DictSelect({
  dictCode,
  value,
  onChange,
  placeholder,
  allowClear,
  disabled,
  permission = 'system:dict:list',
  id,
  'aria-label': ariaLabel,
}: DictSelectProps) {
  const permissions = useMeStore((s) => s.me?.permissions ?? []);
  const { items, loading, error, refresh } = useDict(dictCode);

  if (!permissions.includes(permission)) {
    return null;
  }

  return (
    <Select<string>
      id={id}
      aria-label={ariaLabel}
      value={value}
      onChange={onChange}
      placeholder={placeholder}
      allowClear={allowClear}
      disabled={disabled}
      loading={loading}
      style={{ minWidth: 160 }}
      optionFilterProp="label"
      showSearch
      options={items
        .filter((item) => item.status === 'ENABLED')
        .map((item) => ({ value: item.itemValue, label: item.itemLabel }))}
      notFoundContent={
        error ? (
          <span>
            {error}
            <Button type="link" size="small" onClick={refresh}>重试</Button>
          </span>
        ) : (
          <Spin size="small" />
        )
      }
    />
  );
}
