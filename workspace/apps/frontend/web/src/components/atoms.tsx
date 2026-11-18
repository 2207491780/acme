import { Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import { DesktopOutlined, GlobalOutlined, MobileOutlined } from '@ant-design/icons';
import {
  ENV_ANTD,
  HEALTH_ANTD,
  PLATFORM_ANTD,
  TONE_ANTD,
  statusTone,
  type Env,
  type Health,
  type Platform,
  type Status,
} from '@acme/core';
import { envLabelKey, healthLabelKey, platformShortKey, statusLabelKey } from '../labels';

/**
 * 分类标签原子（色彩映射仍取 @acme/core 令牌映射；文案经 i18n 资源包渲染，
 * 支持语言切换——zh 口径与 core 常量逐字一致，REQ-20261114-120103）。
 */

export function StatusTag({ status }: { status: Status }) {
  const { t } = useTranslation();
  return <Tag color={TONE_ANTD[statusTone(status)]}>{t(statusLabelKey(status))}</Tag>;
}

const PLATFORM_ICON: Record<Platform, typeof GlobalOutlined> = {
  web: GlobalOutlined,
  desktop: DesktopOutlined,
  mobile: MobileOutlined,
};

export function PlatformTag({ platform }: { platform: Platform }) {
  const { t } = useTranslation();
  const Icon = PLATFORM_ICON[platform];
  return (
    <Tag color={PLATFORM_ANTD[platform]} icon={<Icon />}>
      {t(platformShortKey(platform))}
    </Tag>
  );
}

export function PlatformIcon({ platform, size = 22 }: { platform: Platform; size?: number }) {
  const Icon = PLATFORM_ICON[platform];
  return (
    <div className={`platform-ico ${platform}`}>
      <Icon style={{ fontSize: size }} />
    </div>
  );
}

export function EnvTag({ env }: { env: Env }) {
  const { t } = useTranslation();
  return <Tag color={ENV_ANTD[env]}>{t(envLabelKey(env))}</Tag>;
}

export function HealthTag({ health }: { health: Health }) {
  const { t } = useTranslation();
  return <Tag color={HEALTH_ANTD[health]}>{t(healthLabelKey(health))}</Tag>;
}
