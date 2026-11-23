import { ENVS, PLATFORMS, SORTS, STATUSES } from './constants';
import {
  buildSettings,
  isAccentKey,
  isContentWidth,
  isDensityValue,
  isHexColor,
  isLayoutMode,
  isRadiusScale,
  isSemanticColorOverrides,
  isSiderTone,
  isTabStyle,
  isThemeModeValue,
  isTopbarWidgets,
  type ThemePreferences,
} from './preferences';
import { seedState } from './seed';
import type {
  Activity,
  App,
  AppState,
  Artifact,
  Query,
  Release,
  SavedView,
  Settings,
  TimelineEntry,
} from './types';

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isString(value: unknown): value is string {
  return typeof value === 'string';
}

function isId(value: unknown): value is string {
  return isString(value) && value.trim().length > 0;
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function isDate(value: unknown): value is string {
  return isString(value) && Number.isFinite(Date.parse(value));
}

function isOneOf<T extends string>(value: unknown, choices: readonly T[]): value is T {
  return choices.some((choice) => choice === value);
}

function isArrayOf<T>(value: unknown, isItem: (item: unknown) => item is T): value is T[] {
  if (!Array.isArray(value)) return false;
  for (const item of value) {
    if (!isItem(item)) return false;
  }
  return true;
}

function hasUniqueIds(items: { id: string }[]): boolean {
  return new Set(items.map((item) => item.id)).size === items.length;
}

function isApp(value: unknown): value is App {
  return isRecord(value)
    && isId(value.id)
    && isString(value.name)
    && isOneOf(value.platform, PLATFORMS)
    && isString(value.path)
    && isString(value.version)
    && isString(value.owner)
    && isOneOf(value.health, ['healthy', 'warning', 'critical'])
    && isString(value.description);
}

/** UPDATE_APP 局部补丁校验（review 修复）：仅允许已知字段且逐字段类型/枚举合规；
 * 未知字段（含 id）一律拒绝，避免外部输入绕过 IMPORT 的校验标准直接入库。 */
export function isAppPatch(value: unknown): value is Partial<App> {
  if (!isRecord(value)) return false;
  return Object.entries(value).every(([key, v]) => {
    switch (key) {
      case 'name':
      case 'path':
      case 'version':
      case 'owner':
      case 'description':
        return isString(v);
      case 'platform':
        return isOneOf(v, PLATFORMS);
      case 'health':
        return isOneOf(v, ['healthy', 'warning', 'critical']);
      default:
        return false;
    }
  });
}

function isArtifact(value: unknown): value is Artifact {
  return isRecord(value) && isString(value.name) && isString(value.size);
}

function isTimelineEntry(value: unknown): value is TimelineEntry {
  return isRecord(value) && isDate(value.at) && isString(value.action) && isString(value.by);
}

function isRelease(value: unknown): value is Release {
  return isRecord(value)
    && isId(value.id)
    && isString(value.title)
    && isId(value.appId)
    && isOneOf(value.platform, PLATFORMS)
    && isString(value.version)
    && isOneOf(value.env, ENVS)
    && isOneOf(value.status, STATUSES)
    && isFiniteNumber(value.priority)
    && isString(value.owner)
    && isFiniteNumber(value.progress)
    && isString(value.notes)
    && isArrayOf(value.artifacts, isArtifact)
    && isArrayOf(value.timeline, isTimelineEntry)
    && isDate(value.createdAt)
    && isDate(value.updatedAt);
}

/** Query 运行时校验（导出供 SAVE_VIEW 等入口复用，与 IMPORT 同一标准）。 */
export function isQuery(value: unknown): value is Query {
  return isRecord(value)
    && isString(value.search)
    && (value.platform === 'all' || isOneOf(value.platform, PLATFORMS))
    && (value.status === 'all' || value.status === 'active' || isOneOf(value.status, STATUSES))
    && (value.env === 'all' || isOneOf(value.env, ENVS))
    && isOneOf(value.sort, SORTS.map(([key]) => key));
}

function isSavedView(value: unknown): value is SavedView {
  return isRecord(value) && isId(value.id) && isString(value.name) && isQuery(value.query);
}

function isLegacyTheme(value: unknown): value is 'dark' | 'light' {
  return value === 'dark' || value === 'light';
}

/**
 * settings 归一化（REQ-20261121-010725；THEME-2 v3 字段同口径）：blob 内嵌 preferences 新形状
 * 与 legacy 扁平形状（迁移）双口径统一收敛为 `buildSettings` 产物（schemaVersion + preferences）。
 * 口径 = fail-closed（与 IMPORT/存储冲突校验同标准）：任何「显式存在但非法」的字段返回 null
 * （整个 blob 拒绝，不静默降级）；「未提供」的字段沿用种子偏好，保持旧数据的部分合并语义。
 * 逐字段降级语义在偏好引擎层（validateThemePreferences/migrateSettings），两者各司其职。
 * THEME-3（REQ-20261121-200415 契约⑧）：投影 fail-closed 校验已物理删除——迁移窗口内旧 blob
 * 的 4 个投影键仍被接受但值一律忽略（由 preferences 单向重建）。
 */
function normalizeStoredSettings(raw: unknown, base: Settings): Settings | null {
  if (!isRecord(raw)) return null;
  const prefs: ThemePreferences = { ...base.preferences };
  const p = isRecord(raw.preferences) ? raw.preferences : null;
  if (p) {
    // 新形状：根键 = schemaVersion + preferences（+ 迁移窗口内的旧投影键，值一律忽略——
    // 投影校验随投影字段一并删除，见 THEME-3 契约⑧）；其余未知键（含 legacy dark/customAccent）
    // 视为非法数据，fail-closed。
    const allowed = ['schemaVersion', 'preferences', 'theme', 'accent', 'density', 'motion'];
    if (Object.keys(raw).some((key) => !allowed.includes(key))) return null;
    if ('mode' in p && !isThemeModeValue(p.mode)) return null;
    if ('accent' in p && !isAccentKey(p.accent)) return null;
    if ('customAccent' in p && p.customAccent !== null && !isHexColor(p.customAccent)) return null;
    if ('density' in p && !isDensityValue(p.density)) return null;
    if ('motionEnabled' in p && typeof p.motionEnabled !== 'boolean') return null;
    // v3 字段（REQ-20261121-043502 确认②）：显式存在但非法 → fail-closed；合法 → 逐项拷贝。
    // v2 blob 缺省 v3 字段时沿用种子默认（迁移口径），不被静默丢弃。
    if ('radius' in p && !isRadiusScale(p.radius)) return null;
    if ('grayMode' in p && typeof p.grayMode !== 'boolean') return null;
    if ('weakMode' in p && typeof p.weakMode !== 'boolean') return null;
    if ('contentWidth' in p && !isContentWidth(p.contentWidth)) return null;
    if ('semanticColors' in p && !isSemanticColorOverrides(p.semanticColors)) return null;
    // v4 字段（REQ-20261122-233427 确认②）：fail-closed 同口径；缺省沿用种子默认（迁移无损）。
    if ('layoutMode' in p && !isLayoutMode(p.layoutMode)) return null;
    if ('tabStyle' in p && !isTabStyle(p.tabStyle)) return null;
    if ('siderTone' in p && !isSiderTone(p.siderTone)) return null;
    if ('topbarWidgets' in p && !isTopbarWidgets(p.topbarWidgets)) return null;
    if (isThemeModeValue(p.mode)) prefs.mode = p.mode;
    if (isAccentKey(p.accent)) prefs.accent = p.accent;
    if (p.customAccent === null || isHexColor(p.customAccent)) prefs.customAccent = p.customAccent;
    if (isDensityValue(p.density)) prefs.density = p.density;
    if (typeof p.motionEnabled === 'boolean') prefs.motionEnabled = p.motionEnabled;
    if (isRadiusScale(p.radius)) prefs.radius = p.radius;
    if (typeof p.grayMode === 'boolean') prefs.grayMode = p.grayMode;
    if (typeof p.weakMode === 'boolean') prefs.weakMode = p.weakMode;
    if (isContentWidth(p.contentWidth)) prefs.contentWidth = p.contentWidth;
    if (isSemanticColorOverrides(p.semanticColors)) prefs.semanticColors = p.semanticColors;
    if (isLayoutMode(p.layoutMode)) prefs.layoutMode = p.layoutMode;
    if (isTabStyle(p.tabStyle)) prefs.tabStyle = p.tabStyle;
    if (isSiderTone(p.siderTone)) prefs.siderTone = p.siderTone;
    if (isTopbarWidgets(p.topbarWidgets)) prefs.topbarWidgets = { ...p.topbarWidgets };
  } else {
    // legacy 扁平形状（schemaVersion 前的 blob）：theme('dark'|'light')→mode（dark:boolean 同口径
    // 兜底，theme 优先），accent/density/motion 同名映射（确认记录③④ 旧语义）
    if ('theme' in raw && !isLegacyTheme(raw.theme)) return null;
    if ('accent' in raw && !isAccentKey(raw.accent)) return null;
    if ('density' in raw && !isDensityValue(raw.density)) return null;
    if ('motion' in raw && typeof raw.motion !== 'boolean') return null;
    if (isLegacyTheme(raw.theme)) prefs.mode = raw.theme;
    else if (typeof raw.dark === 'boolean') prefs.mode = raw.dark ? 'dark' : 'light';
    if (isAccentKey(raw.accent)) prefs.accent = raw.accent;
    if (isDensityValue(raw.density)) prefs.density = raw.density;
    if (typeof raw.motion === 'boolean') prefs.motionEnabled = raw.motion;
  }
  return buildSettings(prefs);
}

function isActivity(value: unknown): value is Activity {
  return isRecord(value)
    && isId(value.id)
    && isDate(value.at)
    && isString(value.text)
    && isOneOf(value.tone, ['muted', 'info', 'violet', 'warning', 'success', 'danger']);
}

/** 校验外部状态并补齐缺省字段；非法数据返回 null。 */
export function parseState(data: unknown, now: number = Date.now()): AppState | null {
  if (!isRecord(data) || !isArrayOf(data.apps, isApp) || !isArrayOf(data.releases, isRelease)) {
    return null;
  }
  if (!hasUniqueIds(data.apps) || !hasUniqueIds(data.releases)) return null;
  const apps = new Map(data.apps.map((app) => [app.id, app]));
  if (data.releases.some((release) => apps.get(release.appId)?.platform !== release.platform)) {
    return null;
  }

  const base = seedState(now);
  const order = 'order' in data ? data.order : [];
  const savedViews = 'savedViews' in data ? data.savedViews : base.savedViews;
  const activity = 'activity' in data ? data.activity : base.activity;
  const version = 'version' in data ? data.version : base.version;
  const settings = normalizeStoredSettings('settings' in data ? data.settings : {}, base.settings);
  if (!settings || !isFiniteNumber(version) || !isArrayOf(order, isString)) return null;
  const releaseIds = new Set(data.releases.map((release) => release.id));
  if (new Set(order).size !== order.length || order.some((id) => !releaseIds.has(id))) return null;
  if (!isArrayOf(savedViews, isSavedView) || !hasUniqueIds(savedViews)) return null;
  if (!isArrayOf(activity, isActivity) || !hasUniqueIds(activity)) return null;

  return {
    version,
    apps: data.apps,
    releases: data.releases,
    settings,
    order: order.length ? [...order] : data.releases.map((release) => release.id),
    savedViews,
    activity: [...activity],
  };
}
