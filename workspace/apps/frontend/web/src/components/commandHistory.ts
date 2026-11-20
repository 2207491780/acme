/**
 * 命令面板搜索历史（REQ-20261120-120552 AC-6/7/8）：localStorage 持久化的唯一实现点。
 * - 键 acme.commandHistory.v1（版本化）；最近 MAX_HISTORY 条、按 key 去重、最近在前；
 * - 读取做运行时校验：结构损坏/条目非法逐条过滤，解析异常回退空数组（禁类型断言替代校验）；
 * - 写入失败（隐私模式/配额）静默：返回内存态最新列表，不阻塞命令执行。
 */

/** 持久化键（版本化：结构变更时升 v2）。 */
export const STORAGE_KEY = 'acme.commandHistory.v1';

/** 历史上限（最近 N 条）。 */
export const MAX_HISTORY = 8;

export type CommandHistoryKind = 'page' | 'action';

export interface CommandHistoryEntry {
  kind: CommandHistoryKind;
  /** 条目唯一键（与 CommandItem.key 同构：page:<path> / action:<action>）。 */
  key: string;
  /** 执行时的展示文案（记录当时值，不随语言切换重译）。 */
  label: string;
  /** 最近一次执行时间戳（毫秒）。 */
  at: number;
}

export interface CommandHistoryInput {
  kind: CommandHistoryKind;
  key: string;
  label: string;
}

/** 运行时校验单条历史（损坏/非法条目直接过滤，异常回退空的组成部分）。 */
function isValidEntry(value: unknown): value is CommandHistoryEntry {
  if (typeof value !== 'object' || value === null) return false;
  const entry = value as Record<string, unknown>;
  return (entry.kind === 'page' || entry.kind === 'action')
    && typeof entry.key === 'string' && entry.key.length > 0
    && typeof entry.label === 'string' && entry.label.length > 0
    && typeof entry.at === 'number' && Number.isFinite(entry.at);
}

/** 读取历史（损坏/非法回退空；只保留合法条目并截断到上限）。 */
export function readCommandHistory(): CommandHistoryEntry[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw === null) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(isValidEntry).slice(0, MAX_HISTORY);
  } catch {
    return [];
  }
}

/** 记入历史：新条目置顶、按 key 去重（重执行=置顶刷新 at/label）、截断上限并写回。 */
export function recordCommandHistory(entry: CommandHistoryInput): CommandHistoryEntry[] {
  const next: CommandHistoryEntry[] = [
    { kind: entry.kind, key: entry.key, label: entry.label, at: Date.now() },
    ...readCommandHistory().filter((item) => item.key !== entry.key),
  ].slice(0, MAX_HISTORY);
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  } catch {
    // 存储不可用：静默，返回内存态列表（本次会话内仍可见）
  }
  return next;
}

/** 清空历史（移除存储键；存储不可用时静默，内存态由调用方重置）。 */
export function clearCommandHistory(): void {
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    // 静默
  }
}
