/** 开发阶段登录页默认凭据预填（login-ui 开发态便利性）。 */

/** 登录表单开发态预填值。 */
export interface LoginFormDefaults {
  username: string;
  password: string;
}

/**
 * 开发态默认超管凭据：仅 `isDev`（import.meta.env.DEV，即 dev server）返回预填值，
 * 生产构建恒为 null（预填不进任何构建产物）。password 为开发期约定默认值，
 * 需与开发库种子/重置口径一致（ACME_SEED_SUPERADMIN_PASSWORD 或 dev 库重置值）。
 */
// TODO(release): 进入发布阶段前整体移除本文件与 LoginPage 预填接线（负责人：项目组）
export function loginFormDefaults(isDev: boolean): LoginFormDefaults | null {
  if (!isDev) {
    return null;
  }
  return { username: 'admin', password: 'admin123' };
}
