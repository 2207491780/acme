import { useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { App, Button, Card, Col, Input, Row, Select, Space, Switch, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import {
  DatabaseOutlined,
  DownloadOutlined,
  GlobalOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  ThunderboltOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import type { Accent, AppLocale } from '@acme/core';
import { SUPPORTED_LOCALES, useLocaleStore, useLockStore, useUiPrefsStore } from '@acme/core';
import { useStore } from '../store/useStore';
import { PageHead } from '../components/PageHead';
import { ACCENTS, ACCENT_ORDER } from '../theme';
import { detectPlatform, download } from '../utils/dom';

const { Text } = Typography;

/** 语言选项（展示名复用 i18n 资源包 nav.userMenu.language*，语言名以自身语言展示）。 */
const LOCALE_LABEL_KEYS: Record<AppLocale, string> = {
  'zh-CN': 'nav.userMenu.languageZh',
  'en-US': 'nav.userMenu.languageEn',
};

function SettingRow({ label, desc, control }: { label: string; desc: string; control: ReactNode }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, padding: '14px 0', borderBottom: '1px solid var(--border)' }}>
      <div>
        <Text strong>{label}</Text>
        <div><Text type="secondary" style={{ fontSize: 12 }}>{desc}</Text></div>
      </div>
      {control}
    </div>
  );
}

export function Settings() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const settings = useStore((s) => s.state.settings);
  const state = useStore((s) => s.state);
  const dispatch = useStore((s) => s.dispatch);
  const resetData = useStore((s) => s.resetData);
  const fileRef = useRef<HTMLInputElement>(null);
  const platform = detectPlatform();

  const onImport = (file: File) => {
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const data: unknown = JSON.parse(String(reader.result));
        const ok = dispatch({ type: 'IMPORT', data });
        message[ok ? 'success' : 'error'](ok ? t('settings.data.importDone') : t('settings.data.importInvalid'));
      } catch {
        message.error(t('settings.data.importParseFailed'));
      }
    };
    reader.readAsText(file);
  };

  const runtime =
    platform === 'desktop' && window.acmeDesktop
      ? { info: `Electron ${window.acmeDesktop.versions.electron} · ${window.acmeDesktop.platform}`, badge: 'Desktop' }
      : platform === 'mobile'
        ? { info: t('settings.runtime.mobileInfo'), badge: 'Mobile' }
        : { info: t('settings.runtime.webInfo'), badge: 'Web' };

  return (
    <>
      <PageHead eyebrow={t('settings.head.eyebrow')} title={t('settings.head.title')} sub={t('settings.head.sub')} />
      <Row gutter={[18, 18]}>
        <Col xs={24} lg={12}>
          <Card title={<Space><ThunderboltOutlined style={{ color: 'var(--accent)' }} /> {t('settings.appearance.title')}</Space>} style={{ height: '100%' }}>
            <SettingRow
              label={t('settings.appearance.theme')}
              desc={t('settings.appearance.themeDesc')}
              control={
                <Switch
                  checked={settings.theme === 'dark'}
                  onChange={(checked) => dispatch({ type: 'UPDATE_SETTINGS', patch: { theme: checked ? 'dark' : 'light' } })}
                />
              }
            />
            <SettingRow
              label={t('settings.appearance.density')}
              desc={t('settings.appearance.densityDesc')}
              control={
                <Select
                  style={{ width: 120 }}
                  value={settings.density}
                  onChange={(v) => dispatch({ type: 'UPDATE_SETTINGS', patch: { density: v } })}
                  options={[
                    { value: 'comfortable', label: t('settings.appearance.densityComfortable') },
                    { value: 'compact', label: t('settings.appearance.densityCompact') },
                  ]}
                />
              }
            />
            <SettingRow
              label={t('settings.appearance.accent')}
              desc={t('settings.appearance.accentDesc')}
              control={
                <Space>
                  {ACCENT_ORDER.map((name: Accent) => (
                    <button
                      key={name}
                      aria-label={name}
                      title={name}
                      onClick={() => dispatch({ type: 'UPDATE_SETTINGS', patch: { accent: name } })}
                      style={{
                        width: 30,
                        height: 30,
                        borderRadius: '50%',
                        cursor: 'pointer',
                        background: ACCENTS[name],
                        border: settings.accent === name ? '2px solid var(--text)' : '2px solid transparent',
                        boxShadow: settings.accent === name ? `0 0 0 3px ${ACCENTS[name]}59` : 'none',
                      }}
                    />
                  ))}
                </Space>
              }
            />
            <SettingRow
              label={t('settings.appearance.motion')}
              desc={t('settings.appearance.motionDesc')}
              control={
                <Switch
                  checked={settings.motion}
                  onChange={(checked) => dispatch({ type: 'UPDATE_SETTINGS', patch: { motion: checked } })}
                />
              }
            />
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <SecurityCard />
        </Col>
        <Col xs={24} lg={12}>
          <Card title={<Space><DatabaseOutlined style={{ color: 'var(--accent)' }} /> {t('settings.data.title')}</Space>} style={{ height: '100%' }}>
            <SettingRow
              label={t('settings.data.export')}
              desc={t('settings.data.exportDesc')}
              control={
                <Button
                  icon={<DownloadOutlined />}
                  onClick={() => {
                    download(`acme-frontend-ops-${new Date().toISOString().slice(0, 10)}.json`, JSON.stringify(state, null, 2));
                    message.success(t('settings.data.exportDone'));
                  }}
                >
                  {t('settings.data.exportAction')}
                </Button>
              }
            />
            <SettingRow
              label={t('settings.data.import')}
              desc={t('settings.data.importDesc')}
              control={
                <>
                  <Button icon={<UploadOutlined />} onClick={() => fileRef.current?.click()}>{t('settings.data.importAction')}</Button>
                  <input
                    ref={fileRef}
                    type="file"
                    accept="application/json,.json"
                    hidden
                    onChange={(e) => {
                      const file = e.target.files?.[0];
                      if (file) onImport(file);
                      e.target.value = '';
                    }}
                  />
                </>
              }
            />
            <SettingRow
              label={t('settings.data.reset')}
              desc={t('settings.data.resetDesc')}
              control={
                <Button
                  danger
                  icon={<ReloadOutlined />}
                  onClick={() => {
                    const ok = resetData();
                    message[ok ? 'success' : 'warning'](ok ? t('settings.data.resetDone') : t('settings.data.resetConflict'));
                  }}
                >
                  {t('settings.data.resetAction')}
                </Button>
              }
            />
            <SettingRow label={t('settings.runtime.label')} desc={runtime.info} control={<Text style={{ color: 'var(--accent)' }}>{runtime.badge}</Text>} />
            <SettingRow
              label={t('settings.language.label')}
              desc={t('settings.language.desc')}
              control={
                <LanguageSelect />
              }
            />
          </Card>
        </Col>
      </Row>
    </>
  );
}

/**
 * 安全与水印（REQ-20261114-120112，AC-3/AC-4）：
 * 水印开关 → useUiPrefsStore（持久化，关闭立即生效）；锁屏口令 → useLockStore
 * （本地会话口令）；闲置锁屏时长 → useUiPrefsStore + useLockStore.setIdleTimeoutMs。
 */
function SecurityCard() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const watermarkEnabled = useUiPrefsStore((s) => s.watermarkEnabled);
  const setWatermarkEnabled = useUiPrefsStore((s) => s.setWatermarkEnabled);
  const lockIdleTimeoutMs = useUiPrefsStore((s) => s.lockIdleTimeoutMs);
  const setLockIdleTimeoutMs = useUiPrefsStore((s) => s.setLockIdleTimeoutMs);
  const [passcode, setPasscode] = useState('');

  const savePasscode = (value: string) => {
    const code = value.trim();
    if (!code) {
      return; // 空值不清除既有口令（避免误触失焦清空）
    }
    useLockStore.getState().setPasscode(code);
    message.success(t('settings.security.passcodeSaved'));
    setPasscode(code);
  };

  const onIdleLockChange = (ms: number) => {
    setLockIdleTimeoutMs(ms);
    useLockStore.getState().setIdleTimeoutMs(ms); // 立即生效（重新武装闲置定时器）
  };

  return (
    <Card
      title={<Space><SafetyCertificateOutlined style={{ color: 'var(--accent)' }} /> {t('settings.security.title')}</Space>}
      style={{ height: '100%' }}
    >
      <SettingRow
        label={t('settings.security.watermark')}
        desc={t('settings.security.watermarkDesc')}
        control={<Switch checked={watermarkEnabled} onChange={setWatermarkEnabled} />}
      />
      <SettingRow
        label={t('settings.security.passcode')}
        desc={t('settings.security.passcodeDesc')}
        control={
          <Input
            type="password"
            style={{ width: 160 }}
            value={passcode}
            placeholder={t('settings.security.passcodePlaceholder')}
            onChange={(e) => setPasscode(e.target.value)}
            onPressEnter={(e) => savePasscode(e.currentTarget.value)}
            onBlur={(e) => savePasscode(e.target.value)}
          />
        }
      />
      <SettingRow
        label={t('settings.security.idleLock')}
        desc={t('settings.security.idleLockDesc')}
        control={
          <Select
            style={{ width: 120 }}
            value={lockIdleTimeoutMs}
            onChange={onIdleLockChange}
            options={[
              { value: 0, label: t('settings.security.idleOff') },
              { value: 60_000, label: t('settings.security.idle1') },
              { value: 300_000, label: t('settings.security.idle5') },
              { value: 900_000, label: t('settings.security.idle15') },
            ]}
          />
        }
      />
    </Card>
  );
}

/** 语言切换入口（Settings；locale 经 useLocaleStore 持久化并联动 i18n/日期 formatter）。 */
function LanguageSelect() {
  const { t } = useTranslation();
  const locale = useLocaleStore((s) => s.locale);
  const setLocale = useLocaleStore((s) => s.setLocale);
  return (
    <Select<AppLocale>
      style={{ width: 140 }}
      value={locale}
      onChange={(next) => setLocale(next)}
      aria-label={t('settings.language.label')}
      suffixIcon={<GlobalOutlined />}
      options={SUPPORTED_LOCALES.map((value) => ({ value, label: t(LOCALE_LABEL_KEYS[value]) }))}
    />
  );
}
