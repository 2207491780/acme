import { App as AntApp, Button, ColorPicker, Drawer, Popconfirm, Segmented, Switch, Typography } from 'antd';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { DesktopOutlined, MoonOutlined, SunOutlined } from '@ant-design/icons';
import {
  ACCENT_ORDER,
  ACCENTS,
  DEFAULT_PREFERENCES,
  resolveAccentColor,
  type Density,
  type ThemeMode,
  type ThemePreferences,
} from '@acme/core';
import { useStore } from '../store/useStore';

const { Text } = Typography;
const { useApp } = AntApp;

/**
 * 偏好抽屉（REQ-20261121-010725）：右上角 360px 抽屉，分组渲染
 * 主题模式（三态 Segmented）/ 强调色（12 预设网格 + ColorPicker 自定义）/ 密度（2 档）/
 * 动效（Switch）/ 恢复默认（Popconfirm 二次确认）。
 * 所有修改即时生效并即时持久化（UPDATE_SETTINGS patch preferences，无「保存」按钮）；
 * mask=false：打开不阻塞页面交互（契约交互规格）。Settings 页外观卡已由本抽屉替代（AC-10）。
 */

function Group({ title, desc, children }: { title: string; desc?: string; children: ReactNode }) {
  return (
    <div style={{ padding: '14px 0', borderBottom: '1px solid var(--border)' }}>
      <Text strong>{title}</Text>
      {desc && (
        <div>
          <Text type="secondary" style={{ fontSize: 12 }}>{desc}</Text>
        </div>
      )}
      <div style={{ marginTop: 10 }}>{children}</div>
    </div>
  );
}

export function PreferencesDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const { message } = useApp();
  const preferences = useStore((s) => s.state.settings.preferences);
  const dispatch = useStore((s) => s.dispatch);

  // 偏好补丁统一入口：preferences 为唯一事实源，合并后整体提交（即时生效 + 即时持久化）
  const update = (partial: Partial<ThemePreferences>) =>
    dispatch({ type: 'UPDATE_SETTINGS', patch: { preferences: { ...preferences, ...partial } } });

  const accentHex = resolveAccentColor(preferences);

  const resetToDefaults = () => {
    dispatch({ type: 'UPDATE_SETTINGS', patch: { preferences: { ...DEFAULT_PREFERENCES } } });
    message.success(t('settings.preferences.resetDone'));
  };

  return (
    <Drawer
      open={open}
      onClose={onClose}
      placement="right"
      width={360}
      mask={false}
      title={t('settings.preferences.title')}
    >
      <Group title={t('settings.preferences.mode')} desc={t('settings.preferences.modeDesc')}>
        <Segmented<ThemeMode>
          block
          value={preferences.mode}
          onChange={(value) => update({ mode: value })}
          options={[
            { value: 'light', label: t('settings.preferences.modeLight'), icon: <SunOutlined /> },
            { value: 'dark', label: t('settings.preferences.modeDark'), icon: <MoonOutlined /> },
            { value: 'system', label: t('settings.preferences.modeSystem'), icon: <DesktopOutlined /> },
          ]}
        />
      </Group>

      <Group title={t('settings.preferences.accent')} desc={t('settings.preferences.accentDesc')}>
        <Text type="secondary" style={{ fontSize: 12 }}>{t('settings.preferences.accentPreset')}</Text>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(6, 30px)', gap: 10, marginTop: 8, justifyContent: 'start' }}>
          {ACCENT_ORDER.map((name) => {
            const selected = preferences.customAccent === null && preferences.accent === name;
            return (
              <button
                key={name}
                type="button"
                aria-label={t('settings.preferences.accentAria', { name })}
                aria-pressed={selected}
                title={name}
                onClick={() => update({ accent: name, customAccent: null })}
                style={{
                  width: 30,
                  height: 30,
                  borderRadius: '50%',
                  cursor: 'pointer',
                  background: ACCENTS[name],
                  border: selected ? '2px solid var(--text)' : '2px solid transparent',
                  boxShadow: selected ? `0 0 0 3px ${ACCENTS[name]}59` : 'none',
                  padding: 0,
                }}
              />
            );
          })}
        </div>
        <div style={{ marginTop: 14, display: 'flex', alignItems: 'center', gap: 10 }}>
          <ColorPicker
            value={preferences.customAccent ?? accentHex}
            onChange={(color) => update({ customAccent: color.toHexString() })}
            disabledAlpha
          />
          <Text type="secondary" style={{ fontSize: 12 }}>{t('settings.preferences.accentCustom')}</Text>
          {preferences.customAccent !== null && (
            <span
              aria-hidden
              style={{ width: 14, height: 14, borderRadius: '50%', background: preferences.customAccent, border: '1px solid var(--border)' }}
            />
          )}
        </div>
      </Group>

      <Group title={t('settings.preferences.density')} desc={t('settings.preferences.densityDesc')}>
        <Segmented<Density>
          block
          value={preferences.density}
          onChange={(value) => update({ density: value })}
          options={[
            { value: 'comfortable', label: t('settings.preferences.densityComfortable') },
            { value: 'compact', label: t('settings.preferences.densityCompact') },
          ]}
        />
      </Group>

      <Group title={t('settings.preferences.motion')}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <Text type="secondary" style={{ fontSize: 12 }}>{t('settings.preferences.motionDesc')}</Text>
          <Switch
            checked={preferences.motionEnabled}
            onChange={(checked) => update({ motionEnabled: checked })}
            aria-label={t('settings.preferences.motion')}
          />
        </div>
      </Group>

      <Group title={t('settings.preferences.reset')} desc={t('settings.preferences.resetDesc')}>
        <Popconfirm
          title={t('settings.preferences.resetConfirmTitle')}
          description={t('settings.preferences.resetConfirmDescription')}
          okText={t('settings.preferences.resetConfirmOk')}
          cancelText={t('settings.preferences.resetConfirmCancel')}
          onConfirm={resetToDefaults}
        >
          <Button danger>{t('settings.preferences.reset')}</Button>
        </Popconfirm>
      </Group>
    </Drawer>
  );
}
