import { apiFetch } from './client';

/** 用户视图（契约对齐 AdminUserView；id/version/roleIds 序列化为字符串）。 */
export interface AdminUserView {
  id: string;
  username: string;
  nickname: string | null;
  email: string | null;
  phone: string | null;
  status: 'ENABLED' | 'DISABLED';
  builtIn: boolean;
  roleIds: string[];
  createdAt: string;
  updatedAt: string;
  version: string;
}

/** 菜单节点（树形，children 嵌套；契约对齐 AdminMenuView）。 */
export interface AdminMenuView {
  id: number;
  parentId: number;
  menuName: string;
  menuType: string;
  path: string | null;
  component: string | null;
  icon: string | null;
  sortOrder: number | null;
  visible: boolean | null;
  permissionCode: string | null;
  status: 'ENABLED' | 'DISABLED';
  children: AdminMenuView[];
  version: string;
}

/** 当前登录者视图（契约对齐 AdminMeView；FR-008 权限声明与菜单可见性依据）。 */
export interface AdminMeView {
  user: AdminUserView;
  roles: string[];
  permissions: string[];
  menus: AdminMenuView[];
}

/** 系统域接口（platform-system；P1 打通 me，后续管理域随 P2+ 扩展）。 */
export const systemApi = {
  /** 当前登录者视图（认证即可访问；会话恢复校验 + 身份/角色/权限/菜单引导）。 */
  me: (): Promise<AdminMeView> => apiFetch<AdminMeView>('/admin/v1/system/me'),
};
