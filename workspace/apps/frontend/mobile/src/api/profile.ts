import type { AdminMeView, PushTokenPlatform, UserPreferenceView } from '@acme/core';
import { apiFetch, apiJson } from './client';
import { bAuthHeaders } from '../store/bSessionStore';

/**
 * 员工自助账户接口（REQ-20261119-082531 契约①③④⑤；/admin/v1 认证面，全部免权限点）：
 * 与 qrInfo/qrConfirm 同口径——B 端端点经 bAuthHeaders 显式携带员工会话令牌
 * （apiFetch 的 auth:true 只认 C 端会话；员工账密登录建立 BSession 后可用）。
 */
export const profileApi = {
  /** 本人画像（资料卡回显源：nickname/avatarUrl；GET /me 认证即可）。 */
  me: (): Promise<AdminMeView> =>
    apiFetch<AdminMeView>('/admin/v1/system/me', { headers: bAuthHeaders() }),
  /** 更新本人资料（契约①；APP 精简版仅提交 nickname，可空字段语义由后端裁量）。 */
  meUpdate: (payload: { nickname: string }): Promise<AdminMeView> =>
    apiJson<AdminMeView>('/admin/v1/system/me', 'PUT', payload, { extraHeaders: bAuthHeaders() }),
  /** 自助改密（契约③）：成功后其他会话全部 KICKED+撤销、当前会话保持（Result<Void>）。 */
  selfPassword: (payload: { oldPassword: string; newPassword: string }): Promise<void> =>
    apiJson<void>('/admin/v1/auth/me/password', 'PUT', payload, {
      emptyAsSuccess: true,
      extraHeaders: bAuthHeaders(),
    }),
  /** 下线本人其他会话（契约④；保留当前设备；幂等成功）。 */
  selfKickOthers: (): Promise<void> =>
    apiFetch<void>('/admin/v1/auth/me/sessions/others', {
      method: 'DELETE',
      headers: bAuthHeaders(),
    }, { emptyAsSuccess: true }),
  /** 读取本人通知偏好（契约⑤；无偏好行返回缺省 messagePush=false）。 */
  mePreferences: (): Promise<UserPreferenceView> =>
    apiFetch<UserPreferenceView>('/admin/v1/system/me/preferences', { headers: bAuthHeaders() }),
  /** 更新本人通知偏好（契约⑤；本人行 upsert）。 */
  mePreferencesUpdate: (payload: { messagePush: boolean }): Promise<UserPreferenceView> =>
    apiJson<UserPreferenceView>('/admin/v1/system/me/preferences', 'PUT', payload, { extraHeaders: bAuthHeaders() }),
  /** 注册推送令牌（契约⑤；本人 token upsert，同 token 幂等；void 端点兼容空体）。 */
  pushTokenRegister: (payload: { token: string; platform: PushTokenPlatform }): Promise<void> =>
    apiJson<void>('/admin/v1/system/me/push-tokens', 'POST', payload, {
      emptyAsSuccess: true,
      extraHeaders: bAuthHeaders(),
    }),
};
