import type { CSSProperties } from 'react';

export interface AvatarProps {
  /** 头像文件 URL（空/null 时回退昵称首字符，REQ-20261119-082531 契约⑦）。 */
  avatarUrl?: string | null;
  /** 展示名（回退首字符与无障碍标签来源；空名回退占位符）。 */
  name: string;
  /** 直径像素（缺省 26，适配顶栏用户菜单触发器）。 */
  size?: number;
  style?: CSSProperties;
}

/**
 * 头像组件（REQ-20261119-082531 契约⑦）：有 avatarUrl 渲染圆形 img，否则渲染
 * 昵称首字符 + accent 底的圆形占位；色值一律走 global.css 令牌（--accent/--on-accent），
 * 不引第二套强调色。纯展示组件（无状态），顶栏用户区与个人中心资料卡共用。
 */
export function Avatar({ avatarUrl, name, size = 26, style }: AvatarProps) {
  const shared: CSSProperties = {
    width: size,
    height: size,
    borderRadius: '50%',
    flexShrink: 0,
    ...style,
  };
  if (avatarUrl) {
    return (
      <img
        src={avatarUrl}
        alt={name || 'avatar'}
        style={{ ...shared, objectFit: 'cover', display: 'block' }}
      />
    );
  }
  const initial = (name ?? '').trim().charAt(0).toUpperCase();
  return (
    <span
      role="img"
      aria-label={name || 'avatar'}
      style={{
        ...shared,
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'var(--accent)',
        color: 'var(--on-accent)',
        fontSize: Math.max(10, Math.round(size * 0.45)),
        fontWeight: 700,
        lineHeight: 1,
        userSelect: 'none',
        overflow: 'hidden',
      }}
    >
      {initial || '?'}
    </span>
  );
}
