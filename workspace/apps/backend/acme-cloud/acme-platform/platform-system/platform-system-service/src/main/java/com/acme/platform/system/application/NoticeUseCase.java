package com.acme.platform.system.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.framework.core.support.TransactionEventPublisher;
import com.acme.framework.tenant.support.IgnoreTenant;
import com.acme.framework.web.support.WebError;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.NoticeStatus;
import com.acme.platform.system.api.dto.AdminNoticeCreate;
import com.acme.platform.system.api.dto.AdminNoticeUpdate;
import com.acme.platform.system.domain.SysMessage;
import com.acme.platform.system.domain.SysNotice;
import com.acme.platform.system.domain.SysUser;
import com.acme.platform.system.infrastructure.repository.SysMessageRepository;
import com.acme.platform.system.infrastructure.repository.SysNoticeRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import com.acme.platform.system.notify.NotifyPushEvent;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 通知公告用例（REQ-20261114-120102；确认记录⑤状态机）：CRUD + 发布 + 下线。
 * 发布=非 PUBLISHED 态置 PUBLISHED 并扇出全员站内信（确认记录③写放大：每启用用户一条 sys_message
 * 批量插入）；PUBLISHED 重复发布幂等不重复扇出（AC-2）；OFFLINE 再发布=新发布周期重新扇出（确认记录⑤）。
 * 已发布禁改禁删=NOTICE_PUBLISHED；OFFLINE 可编辑可删除；不存在 id 显式 NOTICE_NOT_FOUND（确认记录⑥）。
 * 推送经事务后事件（TransactionEventPublisher）→ NotifyPushBus 扇出，落库与推送同事务边界（BR-013）。
 */
@Service
public class NoticeUseCase {

    private static final Logger log = LoggerFactory.getLogger(NoticeUseCase.class);

    /** 无上下文主体兜底（内部触发场景）：sender_id 恒非空，0 表示系统行为。 */
    private static final long SYSTEM_SENDER_ID = 0L;

    private final SysNoticeRepository noticeRepository;
    private final SysMessageRepository messageRepository;
    private final SysUserRepository userRepository;
    private final TransactionEventPublisher eventPublisher;

    public NoticeUseCase(SysNoticeRepository noticeRepository, SysMessageRepository messageRepository,
                         SysUserRepository userRepository, TransactionEventPublisher eventPublisher) {
        this.noticeRepository = noticeRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public SysNotice create(AdminNoticeCreate request) {
        return noticeRepository.save(new SysNotice(request.title(), request.content()));
    }

    public SysNotice get(Long noticeId) {
        return noticeRepository.findById(noticeId)
                .orElseThrow(() -> new BizException(SystemError.NOTICE_NOT_FOUND));
    }

    @Transactional
    public SysNotice update(Long noticeId, AdminNoticeUpdate request) {
        SysNotice notice = get(noticeId);
        if (request.version() != null && !request.version().equals(notice.getVersion())) {
            throw new BizException(WebError.CONCURRENCY_CONFLICT);
        }
        if (notice.getStatus() == NoticeStatus.PUBLISHED) {
            throw new BizException(SystemError.NOTICE_PUBLISHED);
        }
        notice.setTitle(request.title());
        notice.setContent(request.content());
        return noticeRepository.save(notice);
    }

    /** 删除公告（确认记录⑥：不存在 id 显式 NOTICE_NOT_FOUND）；已发布拒绝=NOTICE_PUBLISHED（需先下线）。 */
    @Transactional
    public void delete(Long noticeId) {
        SysNotice notice = get(noticeId);
        if (notice.getStatus() == NoticeStatus.PUBLISHED) {
            throw new BizException(SystemError.NOTICE_PUBLISHED);
        }
        noticeRepository.delete(notice);
    }

    /**
     * 下线公告（确认记录⑤）：PUBLISHED→OFFLINE；重复下线幂等（OFFLINE/DRAFT 原状返回）；
     * 下线后可编辑/可删除/可再发布。
     */
    @Transactional
    public SysNotice offline(Long noticeId) {
        SysNotice notice = get(noticeId);
        if (notice.getStatus() != NoticeStatus.PUBLISHED) {
            return notice;
        }
        notice.setStatus(NoticeStatus.OFFLINE);
        return noticeRepository.save(notice);
    }

    /**
     * 发布公告（PUBLISHED 重复发布幂等；DRAFT/OFFLINE 首发或再发触发全员扇出——OFFLINE 再发布
     * 为新发布周期，重新扇出全员 message，确认记录⑤）。
     */
    @Transactional
    public SysNotice publish(Long noticeId) {
        SysNotice notice = get(noticeId);
        if (notice.getStatus() == NoticeStatus.PUBLISHED) {
            return notice;
        }
        fanoutToAllEnabledUsers(notice.getTitle(), notice.getContent());
        notice.setStatus(NoticeStatus.PUBLISHED);
        notice.setPublishAt(Instant.now());
        return noticeRepository.save(notice);
    }

    /**
     * 内部服务告警消息全员扇出（REQ-20261122-150756 确认④ + TL 终裁①终稿；POST /internal/v1/notify/messages 承载）：
     * 复用 publish 的全员扇出管道（sys_message 批插 + 事务后 NotifyPushEvent → WS/Expo），
     * **不落 sys_notice**（终裁钉死：契约④原文「站内信+WS+Expo 现有管道」无公告面字样；
     * 告警≠公告——防公告板刷屏为架构性理由）。sender=服务主体字符串，
     * 仅审计日志承载（sys_message.sender_id 为数值主体，内部触发恒记系统主体 0）。
     *
     * <p>平台级跨租户场景（Q-D08 服务身份调用：内部令牌无租户 claim → TenantContext 缺失，
     * FlexTenantFactory fail-closed 哨兵读恒空集——旁路前扇出零行且无异常）：经
     * {@code @IgnoreTenant} 显式旁路声明 + 跨租户审计（12.4.2 平台管理场景口径）读启用用户全集；
     * sys_message 逐收件人回填其租户（fanout 内统一 stamp，管理面路径由 TenantFactory 覆盖不感知）；
     * content 可空 → NOT NULL 列缺省归一空串。</p>
     */
    @IgnoreTenant("internal-notify-fanout")
    @Transactional
    public void publishInternalMessage(String sender, String title, String content) {
        log.info("内部服务消息扇出：sender={}, title={}", sender, title);
        fanoutToAllEnabledUsers(title, content == null ? "" : content);
    }

    /** 全员扇出管道（publish 与内部告警共用）：启用用户批插 sys_message + 事务后推送事件。 */
    private void fanoutToAllEnabledUsers(String title, String content) {
        List<SysMessage> fanout = userRepository
                .findAll(QueryWrapper.create().where(SysUser::getStatus).eq(EnableStatus.ENABLED))
                .stream()
                .map(user -> {
                    SysMessage message = new SysMessage(currentSenderId(), user.getId(), title, content);
                    // 租户列逐收件人回填（NOT NULL）：管理面路径由 TenantFactory 注入覆盖不感知，
                    // 内部旁路路径（TenantFactory 返回 null=保留实体自设值）由此获得收件人租户
                    message.setTenantId(user.getTenantId());
                    return message;
                })
                .toList();
        messageRepository.insertAll(fanout);
        // 落库后发推送事件：事务内缓冲，提交后经 NotifyPushBus 扇出（同一推送管道，单发一致）
        for (SysMessage message : fanout) {
            eventPublisher.publish(new NotifyPushEvent(message.getRecipientId(), message.getId(), message.getTitle()));
        }
    }

    public PageResult<SysNotice> page(PageQuery query, String keyword) {
        QueryWrapper wrapper = QueryWrapper.create();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.where(SysNotice::getTitle).like("%" + keyword.trim() + "%");
        }
        wrapper.orderBy(SysNotice::getId, false);
        return noticeRepository.findAll(wrapper, query.page(), query.size());
    }

    /** 当前主体 id（上下文缺失 / 非数值兜底系统主体，不阻断发布主流程）。 */
    private static long currentSenderId() {
        UserContext context = UserContext.get();
        if (context == null || context.userId() == null || context.userId().isBlank()) {
            return SYSTEM_SENDER_ID;
        }
        try {
            return Long.parseLong(context.userId());
        } catch (NumberFormatException ex) {
            return SYSTEM_SENDER_ID;
        }
    }
}
