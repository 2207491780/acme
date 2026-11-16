import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { App, Button, Card, Col, Input, Row, Select, Space, Switch, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import {
  CalendarOutlined,
  CloudDownloadOutlined,
  DatabaseOutlined,
  DownloadOutlined,
  FieldTimeOutlined,
  GlobalOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SyncOutlined,
  ThunderboltOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import type { Accent, AppLocale, DateFormatTemplate, TimezonePref } from '@acme/core';
import {
  DATE_FORMAT_TEMPLATES,
  formatDate,
  SUPPORTED_LOCALES,
  SUPPORTED_TIMEZONES,
  useDateFormatStore,
  useLocaleStore,
  useLockStore,
  useTimezoneStore,
} from '@acme/core';
import dayjs from 'dayjs';
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

/** 检查更新结果态 → i18n 文案键（REQ-20261114-120111 AC-1/AC-2）。 */
type UpdateCheckStatus = 'disabled' | 'up-to-date' | 'ready' | 'unreachable';

const UPDATE_RESULT_KEYS: Record<UpdateCheckStatus, string> = {
  disabled: 'settings.updater.result.disabled',
  'up-to-date': 'settings.updater.result.upToDate',
  ready: 'settings.updater.result.ready',
  unreachable: 'settings.updater.result.unreachable',
};

/** 桌面端更新小节：当前版本 + 手动检查 + 结果提示 + 就绪重启安装（仅桌面桥接可达时挂载）。 */
function UpdaterCard() {
  const { t } = useTranslation();
  const bridge = window.acmeDesktop;
  const [version, setVersion] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);
  const [result, setResult] = useState<{ status: UpdateCheckStatus; version?: string | null } | null>(null);

  useEffect(() => {
    let alive = true;
    bridge?.getInfo().then((info) => {
      if (alive) setVersion(info.version ?? null);
    }).catch(() => {});
    return () => {
      alive = false;
    };
  }, [bridge]);

  const onCheck = async () => {
    if (!bridge?.checkUpdates || checking) return;
    setChecking(true);
    try {
      setResult(await bridge.checkUpdates());
    } catch {
      setResult({ status: 'unreachable' });
    } finally {
      setChecking(false);
    }
  };

  const onInstall = () => {
    bridge?.installUpdates?.().catch(() => {});
  };

  return (
    <Card title={<Space><CloudDownloadOutlined style={{ color: 'var(--accent)' }} /> {t('settings.updater.title')}</Space>}>
      <SettingRow
        label={t('settings.updater.currentVersion')}
        desc={t('settings.updater.desc')}
        control={<Text style={{ color: 'var(--accent)' }}>{version ?? t('settings.updater.versionUnknown')}</Text>}
      />
      <SettingRow
        label={t('settings.updater.label')}
        desc={result ? t(UPDATE_RESULT_KEYS[result.status], { version: result.version ?? '' }) : ''}
        control={
          result?.status === 'ready'
            ? <Button type="primary" icon={<SyncOutlined />} onClick={onInstall}>{t('settings.updater.installAction')}</Button>
            : <Button icon={<CloudDownloadOutlined />} loading={checking} onClick={onCheck}>{t('settings.updater.checkAction')}</Button>
        }
      />
    </Card>
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
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <LanguageCard />
        </Col>
        <Col xs={24} lg={12}>
          <TimezoneCard />
        </Col>
        <Col xs={24} lg={12}>
          <DateFormatCard />
        </Col>
      </Row>
      {platform === 'desktop' && window.acmeDesktop?.checkUpdates && (
        <Row gutter={[18, 18]} style={{ marginTop: 18 }}>
          <Col xs={24} lg={12}>
            <UpdaterCard />
          </Col>
        </Row>
      )}
    </>
  );
}

/**
 * 安全（REQ-20261116-230354）：仅保留锁屏口令（个人本地会话口令，useLockStore）。
 * 水印开关/闲置锁屏时长已上收为租户系统配置（系统配置页管理，全员强制生效）。
 */
function SecurityCard() {
  const { t } = useTranslation();
  const { message } = App.useApp();
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

  return (
    <Card
      title={<Space><SafetyCertificateOutlined style={{ color: 'var(--accent)' }} /> {t('settings.security.title')}</Space>}
      style={{ height: '100%' }}
    >
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
    </Card>
  );
}

/** 语言卡片（独立小节；locale 经 useLocaleStore 持久化并联动 i18n/日期 formatter）。 */
function LanguageCard() {
  const { t } = useTranslation();
  return (
    <Card title={<Space><GlobalOutlined style={{ color: 'var(--accent)' }} /> {t('settings.language.title')}</Space>} style={{ height: '100%' }}>
      <SettingRow label={t('settings.language.label')} desc={t('settings.language.desc')} control={<LanguageSelect />} />
    </Card>
  );
}

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

/** 时区卡片（独立小节；经 useTimezoneStore 持久化并联动日期 formatter，全站日期随所选时区展示）。 */
function TimezoneCard() {
  const { t } = useTranslation();
  return (
    <Card title={<Space><FieldTimeOutlined style={{ color: 'var(--accent)' }} /> {t('settings.timezone.title')}</Space>} style={{ height: '100%' }}>
      <SettingRow label={t('settings.timezone.label')} desc={t('settings.timezone.desc')} control={<TimezoneSelect />} />
    </Card>
  );
}

function TimezoneSelect() {
  const { t } = useTranslation();
  const timezone = useTimezoneStore((s) => s.timezone);
  const setTimezone = useTimezoneStore((s) => s.setTimezone);
  return (
    <Select<TimezonePref>
      style={{ width: 210 }}
      value={timezone}
      onChange={(next) => setTimezone(next)}
      aria-label={t('settings.timezone.label')}
      options={SUPPORTED_TIMEZONES.map((value) => ({
        value,
        label:
          value === 'system'
            ? t('settings.timezone.system')
            : value === 'UTC'
              ? 'UTC'
              : `${t(`settings.timezone.zones.${value}`)} (UTC${dayjs().tz(value).format('Z')})`,
      }))}
    />
  );
}

/** 日期格式卡片（独立小节；模板偏好经 useDateFormatStore 持久化，示例随语言/时区/模板即时联动）。 */
function DateFormatCard() {
  const { t } = useTranslation();
  // 订阅模板/语言/时区变更：示例经全局 formatter 取值，需随其重渲染
  useDateFormatStore((s) => s.template);
  useLocaleStore((s) => s.locale);
  useTimezoneStore((s) => s.timezone);
  return (
    <Card title={<Space><CalendarOutlined style={{ color: 'var(--accent)' }} /> {t('settings.dateFormat.title')}</Space>} style={{ height: '100%' }}>
      <SettingRow label={t('settings.dateFormat.label')} desc={t('settings.dateFormat.desc')} control={<DateFormatSelect />} />
      <div style={{ paddingTop: 12 }}>
        <Text type="secondary" style={{ fontSize: 12 }}>{t('settings.dateFormat.preview')}：</Text>
        <Text style={{ color: 'var(--accent)' }}>{formatDate(Date.now())}</Text>
      </div>
    </Card>
  );
}

function DateFormatSelect() {
  const { t } = useTranslation();
  const template = useDateFormatStore((s) => s.template);
  const setTemplate = useDateFormatStore((s) => s.setTemplate);
  return (
    <Select<DateFormatTemplate>
      style={{ width: 210 }}
      value={template}
      onChange={(next) => setTemplate(next)}
      aria-label={t('settings.dateFormat.label')}
      options={DATE_FORMAT_TEMPLATES.map((value) => ({
        value,
        label: value === 'auto' ? t('settings.dateFormat.auto') : value,
      }))}
    />
  );
}
