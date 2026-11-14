import { apiFetch } from '../http/client';
import type { PageResult } from './system';

/**
 * 通知公告 / 站内信域（REQ-20261114-120102；契约 §API 逐字对齐）：
 * - 公告生命周期 DRAFT→PUBLISHED→OFFLINE（发布扇出全员站内信；OFFLINE 后可编辑/删除/再发布）；
 * - 站内信收件箱分页 / 未读数（Result<number> 裸数字，契约裁决⑩）/ 单条与全部已读；
 * - DTO camelCase：id 字符串、时间 ISO-8601（AdminNoticeView/AdminMessageView，契约裁决⑪）。
 */

/** 公告状态机（契约裁决⑤：sys_notice.status 含 OFFLINE）。 */
export type NoticeStatus = 'DRAFT' | 'PUBLISHED' | 'OFFLINE';

/** 公告视图（契约对齐 AdminNoticeView；publishAt 仅 PUBLISHED 有值）。 */
export interface AdminNoticeView {
  id: string;
  title: string;
  /** 富文本 HTML 正文。 */
  content: string;
  status: NoticeStatus;
  publishAt: string | null;
  createdAt: string;
  updatedAt: string;
  version: string;
}

/** 站内信视图（契约对齐 AdminMessageView）。 */
export interface AdminMessageView {
  id: string;
  senderId: string;
  title: string;
  /** 富文本 HTML 正文。 */
  content: string;
  isRead: boolean;
  readAt: string | null;
  createdAt: string;
  version: string;
}

/** 站内信发送载荷（recipientIds=收件人用户 id 集合）。 */
export interface AdminMessageSend {
  recipientIds: number[];
  title: string;
  content: string;
}

/** WS 实时事件帧（契约 §实时推送，原样透传给 store/订阅者）。 */
export interface NotifyMessageNewFrame {
  type: 'message:new';
  messageId: string;
  title: string;
}

/** 通知域接口（platform-system；权限 system:notice:* / system:message:*）。 */
export const notifyApi = {
  // ---- 通知公告（system:notice:*） ----
  /** 公告分页（keyword 匹配标题）。 */
  noticeList: (page: number, size: number, keyword?: string): Promise<PageResult<AdminNoticeView>> =>
    apiFetch<PageResult<AdminNoticeView>>(`/admin/v1/system/notices?page=${page}&size=${size}${keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''}`),
  /** 公告详情（编辑回显兜底入口）。 */
  noticeDetail: (noticeId: string): Promise<AdminNoticeView> =>
    apiFetch<AdminNoticeView>(`/admin/v1/system/notices/${noticeId}`),
  /** 新建公告草稿（title/content 富文本 HTML）。 */
  noticeCreate: (payload: { title: string; content: string }): Promise<AdminNoticeView> =>
    apiFetch<AdminNoticeView>('/admin/v1/system/notices', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  /** 编辑公告（version 乐观锁；PUBLISHED 由后端拒绝）。 */
  noticeUpdate: (noticeId: string, payload: { title: string; content: string; version: string }): Promise<AdminNoticeView> =>
    apiFetch<AdminNoticeView>(`/admin/v1/system/notices/${noticeId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  /** 发布公告（触发全员扇出；重复发布幂等，OFFLINE 再发布为新一轮扇出）。 */
  noticePublish: (noticeId: string): Promise<AdminNoticeView> =>
    apiFetch<AdminNoticeView>(`/admin/v1/system/notices/${noticeId}/publish`, { method: 'PUT' }),
  /** 下线公告（裁决⑤：PUBLISHED→OFFLINE 幂等；权限=system:notice:publish）。 */
  noticeOffline: (noticeId: string): Promise<AdminNoticeView> =>
    apiFetch<AdminNoticeView>(`/admin/v1/system/notices/${noticeId}/offline`, { method: 'PUT' }),
  /** 删除公告（软删；PUBLISHED 需先下线——NOTICE_PUBLISHED；不存在=NOTICE_NOT_FOUND）。 */
  noticeDelete: (noticeId: string): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/notices/${noticeId}`, { method: 'DELETE' }),

  // ---- 站内信（system:message:*） ----
  /** 发送站内信给指定用户（system:message:send）。 */
  messageSend: (payload: AdminMessageSend): Promise<void> =>
    apiFetch<void>('/admin/v1/system/messages', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  /** 我的收件箱分页（isRead 筛选可选）。 */
  messageList: (page: number, size: number, isRead?: boolean): Promise<PageResult<AdminMessageView>> =>
    apiFetch<PageResult<AdminMessageView>>(`/admin/v1/system/messages?page=${page}&size=${size}${isRead === undefined ? '' : `&isRead=${isRead}`}`),
  /** 未读数（契约裁决⑩：data=裸数字）。 */
  messageUnreadCount: (): Promise<number> =>
    apiFetch<number>('/admin/v1/system/messages/unread-count'),
  /** 标记单条已读（他人消息=MESSAGE_FORBIDDEN）。 */
  messageRead: (messageId: string): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/messages/${messageId}/read`, { method: 'PUT' }),
  /** 全部已读。 */
  messageReadAll: (): Promise<void> =>
    apiFetch<void>('/admin/v1/system/messages/read-all', { method: 'PUT' }),
};
