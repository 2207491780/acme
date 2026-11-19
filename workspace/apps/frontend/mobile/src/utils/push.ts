import { Platform } from 'react-native';
import * as Notifications from 'expo-notifications';
import type { PushTokenPlatform } from '@acme/core';
import { useBSessionStore } from '../store/bSessionStore';
import { profileApi } from '../api/profile';

/** 推送令牌注册结果（契约⑧降级面）：skipped-no-project=未配置 EAS projectId（零外呼零系统调用）。 */
export type PushRegisterOutcome =
  | 'registered'
  | 'skipped-no-project'
  | 'skipped-no-session'
  | 'failed';

/** EAS projectId 读取（EXPO_PUBLIC_PROJECT_ID 为 Expo 构建期内联 env；空/空白视为未配置）。 */
export function expoProjectId(): string | undefined {
  const raw = process.env.EXPO_PUBLIC_PROJECT_ID;
  return raw && raw.trim() ? raw.trim() : undefined;
}

/** 设备平台 wire 值（sys_push_token.platform：ios/android；web 保留枚举但 APP 端不产生）。 */
function devicePlatform(): PushTokenPlatform {
  return Platform.OS === 'android' ? 'android' : 'ios';
}

/**
 * 登录后推送令牌注册（REQ-20261119-082531 契约⑧，AC-8）：员工 B 会话建立后调用——
 * - EXPO_PUBLIC_PROJECT_ID 缺省 → 跳过（不触 expo-notifications、零网络外呼，降级）；
 * - 有 B 会话 → getDevicePushTokenAsync 取设备令牌 → POST /admin/v1/system/me/push-tokens
 *   （B 令牌显式头，同 token upsert 幂等）；
 * - 失败静默（fire-and-forget：注册失败不阻塞登录/设置流，仅返回 failed 供调用方提示）。
 */
export async function registerPushTokenAfterLogin(): Promise<PushRegisterOutcome> {
  if (!expoProjectId()) {
    return 'skipped-no-project';
  }
  if (!useBSessionStore.getState().session) {
    return 'skipped-no-session';
  }
  try {
    const device = await Notifications.getDevicePushTokenAsync();
    // DevicePushToken: {type:'ios'|'android', data:string} | {type:'web', data:PushSubscription}
    const token = typeof device?.data === 'string' ? device.data : '';
    if (!token) {
      return 'failed';
    }
    await profileApi.pushTokenRegister({ token, platform: devicePlatform() });
    return 'registered';
  } catch {
    return 'failed';
  }
}
