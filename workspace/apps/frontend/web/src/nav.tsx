import type { ReactNode } from 'react';
import type { MenuProps } from 'antd';
import { AppstoreOutlined, ApartmentOutlined, BookOutlined, DashboardOutlined, FileSearchOutlined, FolderOutlined, MenuOutlined, SafetyCertificateOutlined, SettingOutlined, TeamOutlined, UnorderedListOutlined, UserOutlined } from '@ant-design/icons';
import type { AdminMenuView } from '@acme/core';

/**
 * 侧边导航动态化（REQ-20261114-120101，AC-7）：
 * 登录后按 me.menus 菜单树渲染导航；me.menus 缺失/为空（或过滤后无可见项）时回退静态 NAV
 * （fail-closed 不变）。menus 仅控导航显隐，不做动态路由注册——叶子 path 仍指向既有页面路由。
 */

/** 导航叶子条目（key/导航路径一体，叶子 path 即路由路径）。 */
export interface NavEntry {
  path: string;
  icon: ReactNode;
  label: string;
}

/** 目录节点（无 path，仅分组折叠）。 */
interface NavNode {
  key: string;
  path: string | null;
  icon: ReactNode;
  label: string;
  children?: NavNode[];
}

/** 菜单 icon 标识 → 图标（种子外标识统一回退，不猜新图标）。 */
const MENU_ICON_MAP: Record<string, ReactNode> = {
  dashboard: <DashboardOutlined />,
  releases: <UnorderedListOutlined />,
  apps: <AppstoreOutlined />,
  tenants: <ApartmentOutlined />,
  users: <UserOutlined />,
  roles: <TeamOutlined />,
  permissions: <SafetyCertificateOutlined />,
  menus: <MenuOutlined />,
  dicts: <BookOutlined />,
  audit: <FileSearchOutlined />,
  files: <FolderOutlined />,
  settings: <SettingOutlined />,
};

const FALLBACK_ICON = <AppstoreOutlined />;

function resolveMenuIcon(icon: string | null): ReactNode {
  if (!icon) {
    return FALLBACK_ICON;
  }
  return MENU_ICON_MAP[icon.toLowerCase()] ?? FALLBACK_ICON;
}

/** 单节点转换：visible=false / DISABLED / permissionCode 无权限 → 剔除；目录需保有意向子节点。 */
function toNavNode(node: AdminMenuView, permissions: string[]): NavNode | null {
  if (node.visible === false || node.status !== 'ENABLED') {
    return null;
  }
  if (node.permissionCode && !permissions.includes(node.permissionCode)) {
    return null;
  }
  const icon = resolveMenuIcon(node.icon);
  if (node.menuType === 'DIRECTORY') {
    const children = (node.children ?? [])
      .map((child) => toNavNode(child, permissions))
      .filter((child): child is NavNode => child !== null);
    if (children.length === 0) {
      return null;
    }
    return { key: `dir-${node.id}`, path: null, icon, label: node.menuName, children };
  }
  // 叶子无 path 不可导航 → 剔除（导航只承载既有路由显隐）
  if (!node.path) {
    return null;
  }
  return { key: node.path, path: node.path, icon, label: node.menuName };
}

export interface DynamicNav {
  /** 扁平叶子（移动端底栏/选中态用）。 */
  entries: NavEntry[];
  /** antd Menu items（目录为分组子菜单；叶子 key=path）。 */
  items: MenuProps['items'];
  /** 目录分组 key 集合（受控默认展开，保证分组子项在导航中直达可见）。 */
  openKeys: string[];
}

/** 叶子 label 装饰回调（如发布单活跃数 Badge；返回替换态 label）。 */
export type NavLabelDecorator = (label: string, entry: NavEntry) => ReactNode;

/**
 * me.menus → 导航结构：
 * - 叶子（MENU，有 path）→ 可导航条目（key=path）；
 * - 目录（DIRECTORY）→ 分组（剔除后无可见子节点则整组剔除）；
 * - 过滤后为空 → null（调用方回退静态 NAV）。
 */
export function buildDynamicNav(
  menus: AdminMenuView[],
  permissions: string[],
  decorateLabel?: NavLabelDecorator,
): DynamicNav | null {
  const roots = menus
    .map((menu) => toNavNode(menu, permissions))
    .filter((node): node is NavNode => node !== null);
  if (roots.length === 0) {
    return null;
  }

  const entries: NavEntry[] = [];
  const dirKeys: string[] = [];
  const collectLeaves = (nodes: NavNode[]): void => {
    for (const node of nodes) {
      if (node.path) {
        entries.push({ path: node.path, icon: node.icon, label: node.label });
      } else {
        dirKeys.push(node.key);
      }
      if (node.children?.length) {
        collectLeaves(node.children);
      }
    }
  };
  collectLeaves(roots);

  const toMenuItem = (node: NavNode): NonNullable<MenuProps['items']>[number] => {
    if (node.path) {
      const entry: NavEntry = { path: node.path, icon: node.icon, label: node.label };
      return { key: node.key, icon: node.icon, label: decorateLabel ? decorateLabel(node.label, entry) : node.label };
    }
    return {
      key: node.key,
      icon: node.icon,
      label: node.label,
      ...(node.children?.length ? { children: node.children.map(toMenuItem) } : {}),
    };
  };

  return { entries, items: roots.map(toMenuItem), openKeys: dirKeys };
}
