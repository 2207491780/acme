import { useMemo, useState } from 'react';
import { Input, Popover, theme } from 'antd';
import { CloseCircleFilled, SearchOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { MENU_ICON_OPTIONS, resolveMenuIcon, type MenuIconOption } from '../menuIcons';

export interface MenuIconPickerProps {
  /** 受控值（sys_menu.icon 持久化标识）。 */
  value?: string;
  onChange?: (value: string | undefined) => void;
  placeholder?: string;
  id?: string;
  'aria-label'?: string;
}

const SOURCES = ['antd', 'lucide'] as const;

/**
 * 菜单图标方格选择器：触发器回显图标+标识，Popover 内搜索 + 分组方格（AntD/Lucide）。
 * 受控 value/onChange，与 Form.Item 兼容；注册表与导航渲染同源（menuIcons）。
 */
export function MenuIconPicker({ value, onChange, placeholder, id, 'aria-label': ariaLabel }: MenuIconPickerProps) {
  const { t } = useTranslation();
  const { token } = theme.useToken();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');

  const groups = useMemo(() => {
    const q = query.trim().toLowerCase();
    const match = (o: MenuIconOption) => !q || o.code.toLowerCase().includes(q) || o.keywords.toLowerCase().includes(q);
    return SOURCES.map((source) => ({
      source,
      options: MENU_ICON_OPTIONS.filter((o) => o.source === source && match(o)),
    })).filter((g) => g.options.length > 0);
  }, [query]);

  const pick = (code: string) => {
    onChange?.(code);
    setOpen(false);
    setQuery('');
  };

  const panel = (
    <div style={{ width: 384 }}>
      <Input
        allowClear
        autoFocus
        prefix={<SearchOutlined style={{ color: token.colorTextQuaternary }} />}
        placeholder={t('menus.form.iconSearchPlaceholder')}
        value={query}
        onChange={(e) => setQuery(e.target.value)}
      />
      <div style={{ maxHeight: 320, overflowY: 'auto', marginTop: token.marginSM }}>
        {groups.map((g) => (
          <div key={g.source}>
            <div style={{ fontSize: token.fontSizeSM, color: token.colorTextSecondary, margin: `${token.marginXS}px 0` }}>
              {t(g.source === 'antd' ? 'menus.form.iconGroupAntd' : 'menus.form.iconGroupLucide')}
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(44px, 1fr))', gap: token.marginXS }}>
              {g.options.map((o) => {
                const selected = value === o.code;
                return (
                  <button
                    key={o.code}
                    type="button"
                    title={`${o.code} · ${o.keywords}`}
                    aria-label={o.code}
                    aria-pressed={selected}
                    onClick={() => pick(o.code)}
                    style={{
                      height: 44,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      fontSize: token.fontSizeLG,
                      cursor: 'pointer',
                      borderRadius: token.borderRadiusSM,
                      border: `1px solid ${selected ? token.colorPrimary : token.colorBorder}`,
                      background: selected ? token.colorPrimaryBg : token.colorBgContainer,
                      color: selected ? token.colorPrimary : token.colorText,
                    }}
                  >
                    {o.icon}
                  </button>
                );
              })}
            </div>
          </div>
        ))}
        {groups.length === 0 ? (
          <div style={{ padding: token.paddingSM, textAlign: 'center', color: token.colorTextSecondary }}>
            {t('menus.form.iconNoMatch')}
          </div>
        ) : null}
      </div>
    </div>
  );

  return (
    <Popover
      open={open}
      onOpenChange={setOpen}
      trigger="click"
      placement="bottomLeft"
      arrow={false}
      content={panel}
    >
      <button
        id={id}
        type="button"
        aria-label={ariaLabel}
        aria-expanded={open}
        style={{
          width: '100%',
          height: token.controlHeight,
          display: 'flex',
          alignItems: 'center',
          gap: token.paddingXS,
          padding: `0 ${token.paddingSM}px`,
          borderRadius: token.borderRadius,
          border: `1px solid ${open ? token.colorPrimary : token.colorBorder}`,
          background: token.colorBgContainer,
          color: value ? token.colorText : token.colorTextPlaceholder,
          cursor: 'pointer',
        }}
      >
        {value ? (
          <>
            {resolveMenuIcon(value)}
            <span>{value}</span>
          </>
        ) : (
          <span style={{ flex: 1, textAlign: 'left' }}>{placeholder}</span>
        )}
        {value ? (
          <CloseCircleFilled
            aria-label={t('menus.form.iconClear')}
            style={{ marginLeft: 'auto', color: token.colorTextQuaternary }}
            onClick={(e) => {
              e.stopPropagation();
              onChange?.(undefined);
            }}
          />
        ) : null}
      </button>
    </Popover>
  );
}