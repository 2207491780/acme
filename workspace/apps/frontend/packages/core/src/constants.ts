import type { Platform, Env, Status, SortKey } from './types';

export const STORAGE_KEY = 'acme.frontend.ops.v1';

export const PLATFORMS: Platform[] = ['web', 'desktop', 'mobile'];
export const ENVS: Env[] = ['dev', 'test', 'prod'];
export const STATUSES: Status[] = [
  'draft',
  'building',
  'testing',
  'approved',
  'published',
  'failed',
  'cancelled',
];
export const ACTIVE_STATUSES: Status[] = ['building', 'testing', 'approved'];

export const SORTS: [SortKey, string][] = [
  ['manual', '手动优先级'],
  ['updated', '最近更新'],
  ['priority', '优先级编号'],
  ['version', '版本号'],
  ['title', '标题'],
];

export const NEXT_STATUS: Partial<Record<Status, Status>> = {
  draft: 'building',
  building: 'testing',
  testing: 'approved',
  approved: 'published',
  failed: 'building',
  cancelled: 'draft',
};

export const STATUS_ACTION: Partial<Record<Status, string>> = {
  draft: '开始构建',
  building: '完成构建',
  testing: '审批通过',
  approved: '发布',
  failed: '重试构建',
  cancelled: '重新打开',
};