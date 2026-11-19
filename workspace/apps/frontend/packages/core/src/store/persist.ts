/**
 * localStorage 偏好持久化共享助手（store 样板收敛）：
 * loadPersistedPreference / persistPreference 承载各偏好 store 的公共口径——
 * 运行时校验（外部输入不做类型断言）+ 损坏回退缺省 + 存储异常降级内存态；
 * 各 store 仍保留具名 loadXxx / persistXxx 包装（键名/信封键/校验器各自私有）。
 */

/** 读取持久化偏好值（JSON）：损坏/未知值/存储不可用一律回退缺省（不阻塞应用）。
 *  兼容两种落盘形状：裸字符串 或 { [envelopeKey]: value } 信封（版本化升级预留）。 */
export function loadPersistedPreference<T>(
  storageKey: string,
  envelopeKey: string,
  fallback: T,
  isSupported: (value: unknown) => value is T,
): T {
  try {
    const raw = window.localStorage.getItem(storageKey);
    if (!raw) {
      return fallback;
    }
    const parsed: unknown = JSON.parse(raw);
    // 兼容两种落盘形状：裸字符串 或 { envelopeKey } 信封（版本化升级预留）
    const value = typeof parsed === 'string' ? parsed : (parsed as { [key: string]: unknown })?.[envelopeKey];
    return isSupported(value) ? value : fallback;
  } catch {
    return fallback;
  }
}

/** 持久化偏好值（JSON）：存储不可用（隐私模式等）仅保留内存态。 */
export function persistPreference<T>(storageKey: string, value: T): void {
  try {
    window.localStorage.setItem(storageKey, JSON.stringify(value));
  } catch {
    // 存储不可用（隐私模式等）：仅保留内存态
  }
}
