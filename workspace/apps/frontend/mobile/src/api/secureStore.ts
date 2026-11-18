import * as SecureStore from 'expo-secure-store';

/**
 * 会话安全存储封装（login-app）：令牌经系统安全存储（Android EncryptedSharedPreferences /
 * iOS Keychain）持久化，不落明文。测试环境（Node/vitest）无原生模块 → 内存兜底。
 */
const memory = new Map<string, string>();

const isNative = typeof SecureStore.getItemAsync === 'function';

export async function secureGet(key: string): Promise<string | null> {
  // 内存优先（含测试桩与安全存储写入失败兜底）
  const memo = memory.get(key);
  if (memo != null || !isNative) {
    return memo ?? null;
  }
  try {
    return await SecureStore.getItemAsync(key);
  } catch {
    return null;
  }
}

export async function secureSet(key: string, value: string): Promise<void> {
  memory.set(key, value);
  if (!isNative) {
    return;
  }
  try {
    await SecureStore.setItemAsync(key, value);
  } catch {
    // 安全存储不可用：仅保留内存态（会话不跨启动）
  }
}

export async function secureDelete(key: string): Promise<void> {
  memory.delete(key);
  if (!isNative) {
    return;
  }
  try {
    await SecureStore.deleteItemAsync(key);
  } catch {
    // 忽略：键不存在等场景
  }
}
