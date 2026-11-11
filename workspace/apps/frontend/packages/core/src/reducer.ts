import { seedState } from './seed';
import { parseState } from './parse-state';
import { uid, clamp, isVersion, statusLabel } from './utils';
import { STORAGE_KEY, ENVS, STATUSES } from './constants';
import type {
  Action,
  AppState,
  App,
  Release,
  ReleaseInput,
  StorageLike,
  Tone,
  ValidationErrors,
} from './types';

const nowIso = (now: number): string => new Date(now).toISOString();

function pushActivity(state: AppState, text: string, tone: Tone = 'info', now: number = Date.now()): void {
  state.activity.unshift({ id: uid('act'), at: nowIso(now), text, tone });
  state.activity = state.activity.slice(0, 60);
}

export function loadState(storage: StorageLike, now: number = Date.now()): AppState {
  try {
    const raw = storage.getItem(STORAGE_KEY);
    return (raw ? parseState(JSON.parse(raw), now) : null) ?? seedState(now);
  } catch {
    return seedState(now);
  }
}

/** 返回持久化是否成功，存储不可用时保留内存态。 */
export function saveState(storage: StorageLike, state: AppState): boolean {
  try {
    storage.setItem(STORAGE_KEY, JSON.stringify(state));
    return true;
  } catch {
    return false;
  }
}

export function validateReleaseInput(input: ReleaseInput, apps: App[]): ValidationErrors {
  const errors: ValidationErrors = {};
  const title = String(input.title || '').trim();
  const version = String(input.version || '').trim();
  const owner = String(input.owner || '').trim();
  if (!title) errors.title = '请输入发布标题';
  else if (title.length > 60) errors.title = '标题不能超过 60 个字符';
  if (!apps.some((a) => a.id === input.appId)) errors.appId = '请选择目标应用';
  if (!isVersion(version)) errors.version = '版本号格式应为 1.2.3 或 1.2.3-beta.1';
  if (!ENVS.includes(input.env)) errors.env = '请选择环境';
  if (!owner) errors.owner = '请输入负责人';
  const priority = Number(input.priority);
  if (!Number.isFinite(priority) || priority < 1 || priority > 999) errors.priority = '优先级应为 1-999';
  return errors;
}

export function reduce(
  state: AppState,
  action: Action,
  now: number = Date.now(),
  rand: () => number = Math.random,
): AppState {
  const s = structuredClone(state);
  switch (action.type) {
    case 'CREATE_RELEASE': {
      const input = action.payload;
      const errors = validateReleaseInput(input, s.apps);
      if (Object.keys(errors).length) return state;
      const app = s.apps.find((a) => a.id === input.appId)!;
      const id = uid('REL');
      const at = nowIso(now);
      const release: Release = {
        id,
        title: String(input.title).trim(),
        appId: app.id,
        platform: app.platform,
        version: String(input.version).trim(),
        env: input.env,
        status: 'draft',
        priority: Number(input.priority),
        owner: String(input.owner).trim(),
        progress: 0,
        notes: String(input.notes || '').trim(),
        artifacts: [],
        timeline: [{ at, action: '创建发布单', by: String(input.owner).trim() }],
        createdAt: at,
        updatedAt: at,
      };
      s.releases.unshift(release);
      s.order.unshift(id);
      pushActivity(s, `${id} ${release.title} 已创建`, 'info', now);
      return s;
    }
    case 'UPDATE_RELEASE': {
      const release = s.releases.find((r) => r.id === action.id);
      if (!release) return state;
      const errors = validateReleaseInput({ ...release, ...action.payload }, s.apps);
      if (Object.keys(errors).length) return state;
      const app = s.apps.find((a) => a.id === action.payload.appId)!;
      const version = String(action.payload.version).trim();
      const needsRebuild = release.appId !== app.id
        || release.version !== version
        || release.env !== action.payload.env;
      Object.assign(release, {
        title: String(action.payload.title).trim(),
        appId: app.id,
        platform: app.platform,
        version,
        env: action.payload.env,
        priority: Number(action.payload.priority),
        owner: String(action.payload.owner).trim(),
        notes: String(action.payload.notes || '').trim(),
        updatedAt: nowIso(now),
      });
      if (needsRebuild) {
        release.status = 'draft';
        release.progress = 0;
        release.artifacts = [];
      }
      release.timeline.push({
        at: nowIso(now),
        action: needsRebuild ? '编辑发布单：发布目标变更，重置为草稿，需重新构建' : '编辑发布单',
        by: release.owner,
      });
      pushActivity(s, `${release.id} 已更新`, 'info', now);
      return s;
    }
    case 'DELETE_RELEASES': {
      const ids = new Set(action.ids);
      s.releases = s.releases.filter((r) => !ids.has(r.id));
      const deleted = state.releases.length - s.releases.length;
      if (!deleted) return state;
      s.order = s.order.filter((id) => !ids.has(id));
      pushActivity(s, `删除 ${deleted} 个发布单`, 'danger', now);
      return s;
    }
    case 'RESTORE_RELEASES': {
      const items = [...(action.items || [])].sort((a, b) => a.index - b.index);
      let restored = 0;
      for (const { release, index } of items) {
        if (s.releases.some((r) => r.id === release.id)) continue;
        s.releases.splice(clamp(index, 0, s.releases.length), 0, structuredClone(release));
        if (!s.order.includes(release.id)) s.order.splice(clamp(index, 0, s.order.length), 0, release.id);
        restored += 1;
      }
      if (!restored) return state;
      pushActivity(s, `恢复 ${restored} 个发布单`, 'success', now);
      return s;
    }
    case 'SET_STATUS': {
      const release = s.releases.find((r) => r.id === action.id);
      if (!release || !STATUSES.includes(action.status)) return state;
      const from = release.status;
      release.status = action.status;
      release.updatedAt = nowIso(now);
      if (action.status === 'building') release.progress = 0;
      if (['testing', 'approved', 'published'].includes(action.status)) release.progress = 100;
      if (action.status === 'published' && !release.artifacts.length) {
        release.artifacts.push({
          name: `${release.appId}-${release.version}.zip`,
          size:
            release.platform === 'desktop' ? '92.4 MB' : release.platform === 'mobile' ? '31.8 MB' : '8.6 MB',
        });
      }
      release.timeline.push({
        at: nowIso(now),
        action: action.note || `${statusLabel(from)} → ${statusLabel(action.status)}`,
        by: action.by || release.owner,
      });
      pushActivity(
        s,
        `${release.id} ${statusLabel(action.status)}`,
        action.status === 'failed' ? 'danger' : action.status === 'published' ? 'success' : 'info',
        now,
      );
      return s;
    }
    case 'TICK': {
      let changed = false;
      for (const release of s.releases) {
        if (release.status !== 'building') continue;
        changed = true;
        release.progress = clamp(release.progress + 3 + Math.floor(rand() * 8), 0, 100);
        release.updatedAt = nowIso(now);
        if (release.progress >= 100) {
          release.status = 'testing';
          release.timeline.push({ at: nowIso(now), action: '构建完成，进入测试', by: 'CI' });
          pushActivity(s, `${release.id} 构建完成，进入测试`, 'violet', now);
        }
      }
      return changed ? s : state;
    }
    case 'REORDER': {
      const { sourceId, targetId } = action;
      if (sourceId === targetId) return state;
      const from = s.order.indexOf(sourceId);
      const to = s.order.indexOf(targetId);
      if (from < 0 || to < 0) return state;
      s.order.splice(from, 1);
      s.order.splice(to, 0, sourceId);
      return s;
    }
    case 'SAVE_VIEW': {
      const name = String(action.name || '').trim();
      if (!name) return state;
      s.savedViews.push({ id: uid('view'), name: name.slice(0, 20), query: structuredClone(action.query) });
      pushActivity(s, `保存视图「${name}」`, 'info', now);
      return s;
    }
    case 'DELETE_VIEW': {
      s.savedViews = s.savedViews.filter((v) => v.id !== action.id);
      return s;
    }
    case 'UPDATE_SETTINGS': {
      s.settings = { ...s.settings, ...action.patch };
      return s;
    }
    case 'UPDATE_APP': {
      const app = s.apps.find((a) => a.id === action.id);
      if (!app) return state;
      Object.assign(app, action.patch);
      pushActivity(s, `${app.name} 信息已更新`, 'info', now);
      return s;
    }
    case 'IMPORT': {
      const next = parseState(action.data, now);
      if (!next) return state;
      pushActivity(next, '导入数据成功', 'success', now);
      return next;
    }
    case 'RESET': {
      return seedState(now);
    }
    default:
      return state;
  }
}