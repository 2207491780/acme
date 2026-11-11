import { ENVS, PLATFORMS, SORTS, STATUSES } from './constants';
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

function isQuery(value: unknown): value is Query {
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

function isSettings(value: unknown): value is Partial<Settings> {
  return isRecord(value)
    && (!('theme' in value) || isOneOf(value.theme, ['dark', 'light']))
    && (!('density' in value) || isOneOf(value.density, ['comfortable', 'compact']))
    && (!('accent' in value) || isOneOf(value.accent, ['cyan', 'blue', 'violet', 'magenta', 'amber', 'acid']))
    && (!('motion' in value) || typeof value.motion === 'boolean');
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
  const settings = 'settings' in data ? data.settings : {};
  const order = 'order' in data ? data.order : [];
  const savedViews = 'savedViews' in data ? data.savedViews : base.savedViews;
  const activity = 'activity' in data ? data.activity : base.activity;
  const version = 'version' in data ? data.version : base.version;
  if (!isSettings(settings) || !isFiniteNumber(version) || !isArrayOf(order, isString)) return null;
  const releaseIds = new Set(data.releases.map((release) => release.id));
  if (new Set(order).size !== order.length || order.some((id) => !releaseIds.has(id))) return null;
  if (!isArrayOf(savedViews, isSavedView) || !hasUniqueIds(savedViews)) return null;
  if (!isArrayOf(activity, isActivity) || !hasUniqueIds(activity)) return null;

  return {
    version,
    apps: data.apps,
    releases: data.releases,
    settings: { ...base.settings, ...settings },
    order: order.length ? [...order] : data.releases.map((release) => release.id),
    savedViews,
    activity: [...activity],
  };
}
