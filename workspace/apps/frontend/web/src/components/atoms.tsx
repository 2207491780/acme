import { Tag } from 'antd';
import { DesktopOutlined, GlobalOutlined, MobileOutlined } from '@ant-design/icons';
import {
  ENV_ANTD,
  HEALTH_ANTD,
  HEALTH_LABELS,
  PLATFORM_ANTD,
  TONE_ANTD,
  envLabel,
  platformShort,
  statusLabel,
  statusTone,
  type Env,
  type Health,
  type Platform,
  type Status,
} from '@acme/core';

export function StatusTag({ status }: { status: Status }) {
  return <Tag color={TONE_ANTD[statusTone(status)]}>{statusLabel(status)}</Tag>;
}

const PLATFORM_ICON: Record<Platform, typeof GlobalOutlined> = {
  web: GlobalOutlined,
  desktop: DesktopOutlined,
  mobile: MobileOutlined,
};

export function PlatformTag({ platform }: { platform: Platform }) {
  const Icon = PLATFORM_ICON[platform];
  return (
    <Tag color={PLATFORM_ANTD[platform]} icon={<Icon />}>
      {platformShort(platform)}
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
  return <Tag color={ENV_ANTD[env]}>{envLabel(env)}</Tag>;
}

export function HealthTag({ health }: { health: Health }) {
  return <Tag color={HEALTH_ANTD[health]}>{HEALTH_LABELS[health]}</Tag>;
}