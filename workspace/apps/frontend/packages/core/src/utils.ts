import type { Platform, Env, Status, Tone } from './types';

export const esc = (value: unknown): string =>
  String(value ?? '').replace(/[&<>"']/g, (ch) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch]!,
  );

export function uid(prefix = 'id'): string {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

export const clamp = (n: number, min: number, max: number): number =>
  Math.min(max, Math.max(min, n));

export function pad(n: number): string {
  return String(n).padStart(2, '0');
}

export function fmtDate(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  return `${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function fmtFull(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function relTime(iso: string, now: number = Date.now()): string {
  const diff = now - new Date(iso).getTime();
  if (Number.isNaN(diff)) return '—';
  const min = Math.floor(diff / 60000);
  if (min < 1) return '刚刚';
  if (min < 60) return `${min} 分钟前`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr} 小时前`;
  const day = Math.floor(hr / 24);
  if (day < 30) return `${day} 天前`;
  return fmtDate(iso);
}

export function isVersion(value: unknown): boolean {
  return /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(String(value || '').trim());
}

export function compareVersion(a: string, b: string): number {
  const pa = String(a).split('-')[0].split('.').map(Number);
  const pb = String(b).split('-')[0].split('.').map(Number);
  for (let i = 0; i < 3; i += 1) {
    const diff = (pa[i] || 0) - (pb[i] || 0);
    if (diff !== 0) return diff;
  }
  return String(a).localeCompare(String(b));
}

export function debounce<A extends unknown[]>(fn: (...args: A) => void, ms = 180) {
  let timer: ReturnType<typeof setTimeout> | null = null;
  return (...args: A) => {
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => fn(...args), ms);
  };
}

export const platformLabel = (p: Platform | string): string =>
  ({ web: 'Web 端', desktop: 'PC 桌面端', mobile: '移动端' })[p as Platform] || p;

export const platformShort = (p: Platform | string): string =>
  ({ web: 'Web', desktop: 'Electron', mobile: 'Mobile' })[p as Platform] || p;

export const envLabel = (e: Env | string): string =>
  ({ dev: '开发', test: '测试', prod: '生产' })[e as Env] || e;

export const statusLabel = (s: Status | string): string =>
  ({
    draft: '草稿',
    building: '构建中',
    testing: '测试中',
    approved: '待发布',
    published: '已发布',
    failed: '失败',
    cancelled: '已取消',
  })[s as Status] || s;

export function statusTone(s: Status | string): Tone {
  return (
    ({
      draft: 'muted',
      building: 'info',
      testing: 'violet',
      approved: 'warning',
      published: 'success',
      failed: 'danger',
      cancelled: 'muted',
    })[s as Status] || 'muted'
  ) as Tone;
}