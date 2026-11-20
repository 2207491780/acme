import { App as AntApp, Button, ColorPicker, Drawer, Popconfirm, Segmented, Switch, Typography } from 'antd';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { DesktopOutlined, MoonOutlined, SunOutlined } from '@ant-design/icons';
import {
  ACCENT_ORDER,
  ACCENTS,
  DEFAULT_PREFERENCES,
  resolveAccentColor,
  SEMANTIC_PRESETS,
  TONES,
  type ContentWidth,
  type Density,
  type RadiusScale,
  type SemanticColorKey,
  type ThemeMode,
  type ThemePreferences,
} from '@acme/core';
import { useStore } from '../store/useStore';

const { Text } = Typography;
const { useApp } = AntApp;

/**
 * 偏好抽屉（REQ-20261121-010725；REQ-20261121-043502 外观能力包）：右上角 360px 抽屉，分组渲染
 * 主题模式（三态 Segmented）/ 强调色（12 预设网格 + ColorPicker 自定义）/ 密度（2 档）/
 * 外观（圆角四档 Segmented / 内容宽度两档 Segmented / 灰度 Switch / 色弱 Switch）/
 * 动效（Switch）/ 语义色（success/warning/danger 三行：2 预设 + ColorPicker + 清除）/
 * 恢复默认（Popconfirm 二次确认）。
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

/** 语义色行（预设圆点用色经 SEMANTIC_PRESETS 取 core 派生值，禁硬编码）。 */
const SEMANTIC_ROWS: { key: SemanticColorKey; labelKey: string }[] = [
  { key: 'success', labelKey: 'settings.preferences.semanticSuccess' },
  { key: 'warning', labelKey: 'settings.preferences.semanticWarning' },
  { key: 'danger', labelKey: 'settings.preferences.semanticDanger' },
];

export function PreferencesDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const { message } = useApp();
  const preferences = useStore((s) => s.state.settings.preferences);
  const dispatch = useStore((s) => s.dispatch);

  // 偏好补丁统一入口：preferences 为唯一事实源，合并后整体提交（即时生效 + 即时持久化）
  const update = (partial: Partial<ThemePreferences>) =>
    dispatch({ type: 'UPDATE_SETTINGS', patch: { preferences: { ...preferences, ...partial } } });

  const accentHex = resolveAccentColor(preferences);

  // 语义色单键写入（THEME-2 确认⑤）：semanticColors 子对象整体替换，null = 清除回 TONES 基线
  const setSemanticColor = (key: SemanticColorKey, hex: string | null) =>
    update({
      semanticColors: {
        success: preferences.semanticColors?.success ?? null,
        warning: preferences.semanticColors?.warning ?? null,
        danger: preferences.semanticColors?.danger ?? null,
        [key]: hex,
      },
    });

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

      {/* 外观组（THEME-2 确认⑦）：圆角四档 / 内容宽度两档 / 灰度 / 色弱，全部即时生效 + 持久化。
          圆角/宽度各包独立 wrapper：分组标签向上找最近 .ant-segmented 时互不串组。 */}
      <Group title={t('settings.preferences.appearance')} desc={t('settings.preferences.appearanceDesc')}>
        <div>
          <Text type="secondary" style={{ fontSize: 12 }}>{t('settings.preferences.radius')}</Text>
          <div style={{ marginTop: 8 }}>
            <Segmented<RadiusScale>
              block
              value={preferences.radius}
              onChange={(value) => update({ radius: value })}
              options={[
                { value: 'small', label: t('settings.preferences.radiusSmall') },
                { value: 'medium', label: t('settings.preferences.radiusMedium') },
                { value: 'large', label: t('settings.preferences.radiusLarge') },
                { value: 'full', label: t('settings.preferences.radiusFull') },
              ]}
            />
          </div>
        </div>
        <div style={{ marginTop: 14 }}>
          <Text type="secondary" style={{ fontSize: 12 }}>{t('settings.preferences.contentWidth')}</Text>
          <div style={{ marginTop: 8 }}>
            <Segmented<ContentWidth>
              block
              value={preferences.contentWidth}
              onChange={(value) => update({ contentWidth: value })}
              options={[
                { value: 'fluid', label: t('settings.preferences.contentWidthFluid') },
                { value: 'compact', label: t('settings.preferences.contentWidthCompact') },
              ]}
            />
          </div>
        </div>
        {/* 灰度/色弱行各自独立三层包裹：两开关向上 4 层祖先链内不得出现对方文案，
            否则按文案定位开关会交叉误命中（灰度行祖先含「色弱」会被当色弱开关）。 */}
        <div style={{ marginTop: 14 }}>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <Text type="secondary" style={{ fontSize: 12 }}>{t('settings.preferences.grayModeDesc')}</Text>
              <Switch
                checked={preferences.grayMode}
                onChange={(checked) => update({ grayMode: checked })}
                aria-label={t('settings.preferences.grayMode')}
              />
            </div>
          </div>
        </div>
        <div style={{ marginTop: 10 }}>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <Text type="secondary" style={{ fontSize: 12 }}>{t('settings.preferences.weakModeDesc')}</Text>
              <Switch
                checked={preferences.weakMode}
                onChange={(checked) => update({ weakMode: checked })}
                aria-label={t('settings.preferences.weakMode')}
              />
            </div>
          </div>
        </div>
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

      {/* 语义色组（THEME-2 确认⑤/⑦）：success/warning/danger 各行 2 预设 + 自定义 + 清除，
          经 buildTheme colorSuccess/Warning/Error 与 themeSnapshot 即时联动 */}
      <Group title={t('settings.preferences.semantic')} desc={t('settings.preferences.semanticDesc')}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {SEMANTIC_ROWS.map(({ key, labelKey }) => {
            const label = t(labelKey);
            const override = preferences.semanticColors?.[key] ?? null;
            const baseline = TONES[key];
            return (
              <div key={key} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <Text type="secondary" style={{ fontSize: 12, width: 44, flex: 'none' }}>{label}</Text>
                {SEMANTIC_PRESETS[key].map((hex) => {
                  const selected = override === hex;
                  return (
                    <button
                      key={hex}
                      type="button"
                      aria-label={t('settings.preferences.semanticPresetAria', { name: label, hex })}
                      aria-pressed={selected}
                      title={hex}
                      onClick={() => setSemanticColor(key, hex)}
                      style={{
                        width: 22,
                        height: 22,
                        borderRadius: '50%',
                        cursor: 'pointer',
                        background: hex,
                        border: selected ? '2px solid var(--text)' : '1px solid var(--border)',
                        boxShadow: selected ? `0 0 0 3px ${hex}59` : 'none',
                        padding: 0,
                        flex: 'none',
                      }}
                    />
                  );
                })}
                <ColorPicker
                  size="small"
                  value={override ?? baseline}
                  onChange={(color) => setSemanticColor(key, color.toHexString())}
                  disabledAlpha
                />
                <Button
                  size="small"
                  type="text"
                  disabled={override === null}
                  onClick={() => setSemanticColor(key, null)}
                  aria-label={t('settings.preferences.semanticClearAria', { name: label })}
                >
                  {t('settings.preferences.semanticClear')}
                </Button>
              </div>
            );
          })}
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
