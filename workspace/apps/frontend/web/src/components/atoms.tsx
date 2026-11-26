import { Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import { DesktopOutlined, GlobalOutlined, MobileOutlined } from '@ant-design/icons';
import {
  HEALTH_ANTD,
  PLATFORM_ANTD,
  type Health,
  type Platform,
} from '@acme/core';
import { healthLabelKey, platformShortKey } from '../labels';

/**
 * 分类标签原子（色彩映射仍取 @acme/core 令牌映射；文案经 i18n 资源包渲染，
 * 支持语言切换——zh 口径与 core 常量逐字一致，REQ-20261114-120103）。
 * REQ-20261126-125836：状态/环境标签随发布单演示域删除；平台/健康保留供多端基座页面使用。
 */

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

export function HealthTag({ health }: { health: Health }) {
  const { t } = useTranslation();
  return <Tag color={HEALTH_ANTD[health]}>{t(healthLabelKey(health))}</Tag>;
}
