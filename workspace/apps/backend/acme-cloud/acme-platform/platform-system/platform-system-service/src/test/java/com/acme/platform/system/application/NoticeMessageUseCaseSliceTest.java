package com.acme.platform.system.application;

import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.common.contract.PageQuery;
import com.acme.common.core.BizException;
import com.acme.platform.system.PlatformSystemApplication;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.SystemErrorCodes;
import com.acme.platform.system.domain.SysMessage;
import com.acme.platform.system.domain.SysNotice;
import com.acme.platform.system.domain.SysUser;
import com.acme.platform.system.infrastructure.repository.SysMessageRepository;
import com.acme.platform.system.infrastructure.repository.SysNoticeRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import com.acme.platform.system.notify.WsSessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知公告 / 站内信用例切片测试（H2 + MyBatis-Flex 真实 SQL 面，REQ-20261114-120102）：
 * 公告 CRUD/发布幂等/已发布守卫（AC-1）、发布扇出（AC-2）、收件箱可见性（AC-3）、
 * 已读与未读数（AC-4）、越权拒绝（AC-5）、租户隔离，以及发送→推送事件→本地会话帧投递闭环（AC-7 本实例面）。
 */
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.flyway.enabled=false",
        "acme.flex.slow-sql.enabled=false"
})
class NoticeMessageUseCaseSliceTest {

    @Autowired
    NoticeUseCase noticeUseCase;

    @Autowired
    MessageUseCase messageUseCase;

    @Autowired
    SysNoticeRepository noticeRepository;

    @Autowired
    SysMessageRepository messageRepository;

    @Autowired
    SysUserRepository userRepository;

    @Autowired
    WsSessionRegistry registry;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        TenantContext.clear();
        UserContext.clear();
        jdbcTemplate.update("delete from sys_message");
        jdbcTemplate.update("delete from sys_notice");
        jdbcTemplate.update("delete from sys_user");
    }

    @AfterEach
    void clearContexts() {
        UserContext.clear();
        TenantContext.clear();
    }

    // ---------- 公告 CRUD（AC-1 增改查删） ----------

    @Test
    void noticeCrudLifecycle() {
        TenantContext.bind(new TenantContext("t-1"));
        SysNotice created = noticeUseCase.create(new com.acme.platform.system.api.dto.AdminNoticeCreate(
                "停机维护", "<p>周日凌晨维护</p>"));
        assertThat(created.getId()).isNotNull();
        assertThat(created.getStatus()).isEqualTo(com.acme.platform.system.api.NoticeStatus.DRAFT);
        assertThat(created.getPublishAt()).isNull();

        assertThat(noticeUseCase.get(created.getId()).getTitle()).isEqualTo("停机维护");

        SysNotice updated = noticeUseCase.update(created.getId(),
                new com.acme.platform.system.api.dto.AdminNoticeUpdate("停机维护（更新）", "<p>顺延</p>", null));
        assertThat(updated.getTitle()).isEqualTo("停机维护（更新）");
        // version 传则校验：版本不匹配拒绝（宽松乐观锁口径）
        assertThatThrownBy(() -> noticeUseCase.update(created.getId(),
                new com.acme.platform.system.api.dto.AdminNoticeUpdate("并发覆盖", "<p>x</p>", 99L)))
                .isInstanceOf(BizException.class);

        assertThat(noticeUseCase.page(PageQuery.firstPage(), "停机").total()).isEqualTo(1L);

        noticeUseCase.delete(created.getId());
        assertThat(noticeRepository.findById(created.getId())).isEmpty();
        // 确认记录⑥：delete 对不存在 id 显式失败
        assertThatThrownBy(() -> noticeUseCase.delete(created.getId()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemErrorCodes.NOTICE_NOT_FOUND));
    }

    // ---------- 下线（确认记录⑤）：PUBLISHED→OFFLINE 幂等；可删；再发布=新扇出周期 ----------

    @Test
    void offlineAllowsDeleteAndRepublishWithNewFanoutCycle() {
        TenantContext.bind(new TenantContext("t-1"));
        SysUser alice = userRepository.save(new SysUser("alice", "爱丽丝", null, null, false));
        UserContext.bind(new UserContext("1", java.util.Set.of("super_admin")));
        SysNotice notice = noticeUseCase.create(new com.acme.platform.system.api.dto.AdminNoticeCreate(
                "下线通知", "<p>x</p>"));
        noticeUseCase.publish(notice.getId());
        assertThat(messageRepository.findAll()).hasSize(1);

        // PUBLISHED→OFFLINE；重复下线幂等
        SysNotice offline = noticeUseCase.offline(notice.getId());
        assertThat(offline.getStatus()).isEqualTo(com.acme.platform.system.api.NoticeStatus.OFFLINE);
        assertThat(noticeUseCase.offline(notice.getId()).getStatus())
                .isEqualTo(com.acme.platform.system.api.NoticeStatus.OFFLINE);

        // OFFLINE 可编辑、可删除（确认记录⑤）
        SysNotice edited = noticeUseCase.update(notice.getId(),
                new com.acme.platform.system.api.dto.AdminNoticeUpdate("下线后改题", "<p>y</p>", null));
        assertThat(edited.getTitle()).isEqualTo("下线后改题");

        // OFFLINE 再发布=新发布周期：重新扇出（确认记录⑤）
        java.time.Instant firstPublishAt = noticeUseCase.get(notice.getId()).getPublishAt();
        noticeUseCase.publish(notice.getId());
        assertThat(noticeUseCase.get(notice.getId()).getStatus())
                .isEqualTo(com.acme.platform.system.api.NoticeStatus.PUBLISHED);
        assertThat(noticeUseCase.get(notice.getId()).getPublishAt()).isAfter(firstPublishAt);
        assertThat(messageRepository.findAll()).hasSize(2);
        assertThat(messageRepository.findAll()).allSatisfy(
                message -> assertThat(message.getRecipientId()).isEqualTo(alice.getId()));
    }

    // ---------- 发布扇出（AC-1 发布 / AC-2 全员一条 + 重复发布不重复扇出） ----------

    @Test
    void publishFansOutOneMessagePerEnabledUserOnce() {
        TenantContext.bind(new TenantContext("t-1"));
        SysUser alice = userRepository.save(new SysUser("alice", "爱丽丝", null, null, false));
        SysUser bob = userRepository.save(new SysUser("bob", "鲍勃", null, null, false));
        SysUser carol = userRepository.save(new SysUser("carol", "卡萝", null, null, false));
        carol.setStatus(EnableStatus.DISABLED);
        userRepository.save(carol);
        UserContext.bind(new UserContext("1", java.util.Set.of("super_admin")));

        SysNotice notice = noticeUseCase.create(new com.acme.platform.system.api.dto.AdminNoticeCreate(
                "全员通知", "<p>内容</p>"));
        SysNotice published = noticeUseCase.publish(notice.getId());
        assertThat(published.getStatus()).isEqualTo(com.acme.platform.system.api.NoticeStatus.PUBLISHED);
        assertThat(published.getPublishAt()).isNotNull();

        // 扇出：每启用用户一条（停用用户不收），发送者为发布主体
        List<SysMessage> fanout = messageRepository.findAll();
        assertThat(fanout).hasSize(2);
        assertThat(fanout).extracting(SysMessage::getRecipientId)
                .containsExactlyInAnyOrder(alice.getId(), bob.getId());
        assertThat(fanout).allSatisfy(message -> {
            assertThat(message.getSenderId()).isEqualTo(1L);
            assertThat(message.getIsRead()).isFalse();
            assertThat(message.getTitle()).isEqualTo("全员通知");
        });

        // 重复发布幂等：不重复扇出（AC-2）
        noticeUseCase.publish(notice.getId());
        assertThat(messageRepository.findAll()).hasSize(2);
    }

    // ---------- 已发布守卫（AC-1：已发布禁改禁删=NOTICE_PUBLISHED） ----------

    @Test
    void publishedNoticeBlocksEditAndDeleteWithNoticePublished() {
        TenantContext.bind(new TenantContext("t-1"));
        UserContext.bind(new UserContext("1", java.util.Set.of("super_admin")));
        SysNotice notice = noticeUseCase.create(new com.acme.platform.system.api.dto.AdminNoticeCreate(
                "已发布", "<p>x</p>"));
        noticeUseCase.publish(notice.getId());

        assertThatThrownBy(() -> noticeUseCase.update(notice.getId(),
                new com.acme.platform.system.api.dto.AdminNoticeUpdate("改标题", "<p>y</p>", null)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemErrorCodes.NOTICE_PUBLISHED));
        assertThatThrownBy(() -> noticeUseCase.delete(notice.getId()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemErrorCodes.NOTICE_PUBLISHED));
        // 不存在公告：get/publish 显式失败（实施增补口径），delete 幂等
        assertThatThrownBy(() -> noticeUseCase.get(987654321L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemErrorCodes.NOTICE_NOT_FOUND));
        assertThatThrownBy(() -> noticeUseCase.publish(987654321L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemErrorCodes.NOTICE_NOT_FOUND));
    }

    // ---------- 站内信可见性（AC-3） ----------

    @Test
    void messageVisibleOnlyToRecipient() {
        TenantContext.bind(new TenantContext("t-1"));
        SysUser alice = userRepository.save(new SysUser("alice", "爱丽丝", null, null, false));
        SysUser bob = userRepository.save(new SysUser("bob", "鲍勃", null, null, false));
        UserContext.bind(new UserContext("9", java.util.Set.of("super_admin")));

        List<SysMessage> sent = messageUseCase.send(new com.acme.platform.system.api.dto.AdminMessageSend(
                List.of(alice.getId()), "私信", "<p>hello</p>"));
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getSenderId()).isEqualTo(9L);

        // 收件人可见
        assertThat(messageUseCase.inbox(alice.getId(), null, PageQuery.firstPage()).total()).isEqualTo(1L);
        // 非收件人不可见
        assertThat(messageUseCase.inbox(bob.getId(), null, PageQuery.firstPage()).total()).isZero();
        // 发送者（非收件人）不可见
        assertThat(messageUseCase.inbox(9L, null, PageQuery.firstPage()).total()).isZero();
    }

    // ---------- 已读 / 未读数（AC-4） ----------

    @Test
    void markReadAndReadAllUpdateReadStateAndUnreadCount() {
        TenantContext.bind(new TenantContext("t-1"));
        SysUser alice = userRepository.save(new SysUser("alice", "爱丽丝", null, null, false));
        UserContext.bind(new UserContext("9", java.util.Set.of("super_admin")));
        List<SysMessage> sent = List.of(
                messageUseCase.send(new com.acme.platform.system.api.dto.AdminMessageSend(
                        List.of(alice.getId()), "第一条", "<p>x</p>")).get(0),
                messageUseCase.send(new com.acme.platform.system.api.dto.AdminMessageSend(
                        List.of(alice.getId()), "第二条", "<p>x</p>")).get(0),
                messageUseCase.send(new com.acme.platform.system.api.dto.AdminMessageSend(
                        List.of(alice.getId()), "第三条", "<p>x</p>")).get(0));
        assertThat(messageUseCase.unreadCount(alice.getId())).isEqualTo(3);

        SysMessage read = messageUseCase.markRead(alice.getId(), sent.get(0).getId());
        assertThat(read.getIsRead()).isTrue();
        assertThat(read.getReadAt()).isNotNull();
        assertThat(messageUseCase.unreadCount(alice.getId())).isEqualTo(2);
        assertThat(messageUseCase.inbox(alice.getId(), true, PageQuery.firstPage()).total()).isEqualTo(1L);
        assertThat(messageUseCase.inbox(alice.getId(), false, PageQuery.firstPage()).total()).isEqualTo(2L);

        // 单条已读幂等：不刷新 read_at、不再计数
        java.sql.Timestamp readAtBefore = jdbcTemplate.queryForObject(
                "select read_at from sys_message where id=?", java.sql.Timestamp.class, read.getId());
        messageUseCase.markRead(alice.getId(), read.getId());
        java.sql.Timestamp readAtAfter = jdbcTemplate.queryForObject(
                "select read_at from sys_message where id=?", java.sql.Timestamp.class, read.getId());
        assertThat(readAtAfter).isEqualTo(readAtBefore);

        // 全部已读：read_at 落库、未读数清零（AC-4）
        int marked = messageUseCase.markAllRead(alice.getId());
        assertThat(marked).isEqualTo(2);
        assertThat(messageUseCase.unreadCount(alice.getId())).isZero();
        assertThat(messageUseCase.inbox(alice.getId(), true, PageQuery.firstPage()).total()).isEqualTo(3L);
        Integer rowsWithReadAt = jdbcTemplate.queryForObject(
                "select count(*) from sys_message where recipient_id=? and read_at is not null",
                Integer.class, alice.getId());
        assertThat(rowsWithReadAt).isEqualTo(3);
    }

    // ---------- 越权与不存在（AC-5） ----------

    @Test
    void markReadOtherUsersMessageForbiddenAndUnknownNotFound() {
        TenantContext.bind(new TenantContext("t-1"));
        SysUser alice = userRepository.save(new SysUser("alice", "爱丽丝", null, null, false));
        UserContext.bind(new UserContext("9", java.util.Set.of("super_admin")));
        SysMessage sent = messageUseCase.send(new com.acme.platform.system.api.dto.AdminMessageSend(
                List.of(alice.getId()), "私信", "<p>hello</p>")).get(0);

        // 非本人消息 → MESSAGE_FORBIDDEN
        assertThatThrownBy(() -> messageUseCase.markRead(888888L, sent.getId()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemErrorCodes.MESSAGE_FORBIDDEN));
        // 不存在 → MESSAGE_NOT_FOUND
        assertThatThrownBy(() -> messageUseCase.markRead(alice.getId(), 987654321L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemErrorCodes.MESSAGE_NOT_FOUND));
        // 本人才可标记
        assertThat(messageUseCase.markRead(alice.getId(), sent.getId()).getIsRead()).isTrue();
    }

    // ---------- 租户隔离（12.4.1 fail-closed） ----------

    @Test
    void messageIsolationAcrossTenants() {
        TenantContext.bind(new TenantContext("t-a"));
        SysUser userA = userRepository.save(new SysUser("user-a", "租户A", null, null, false));
        UserContext.bind(new UserContext("9", java.util.Set.of("super_admin")));
        SysMessage sent = messageUseCase.send(new com.acme.platform.system.api.dto.AdminMessageSend(
                List.of(userA.getId()), "A 租户私信", "<p>a</p>")).get(0);

        // B 租户查询同 id：租户过滤读不到 → MESSAGE_NOT_FOUND（fail-closed）
        TenantContext.bind(new TenantContext("t-b"));
        assertThat(messageRepository.findById(sent.getId())).isEmpty();
        assertThatThrownBy(() -> messageUseCase.markRead(999999L, sent.getId()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemErrorCodes.MESSAGE_NOT_FOUND));
    }

    // ---------- 发送 → 推送 → 本地会话帧投递（AC-7 本实例面；多实例扇出经 Redis pub/sub） ----------

    @Test
    void sendDeliversMessageNewFrameToRecipientLocalSession() throws Exception {
        TenantContext.bind(new TenantContext("t-1"));
        SysUser alice = userRepository.save(new SysUser("alice", "爱丽丝", null, null, false));
        UserContext.bind(new UserContext("9", java.util.Set.of("super_admin")));

        WebSocketSession session = sessionMock(String.valueOf(alice.getId()));
        registry.register(String.valueOf(alice.getId()), session);
        try {
            List<SysMessage> sent = messageUseCase.send(new com.acme.platform.system.api.dto.AdminMessageSend(
                    List.of(alice.getId()), "实时私信", "<p>fast</p>"));

            ArgumentCaptor<TextMessage> frame = ArgumentCaptor.forClass(TextMessage.class);
            verify(session, atLeastOnce()).sendMessage(frame.capture());
            // 帧契约形态 {type:"message:new", messageId, title}；messageId 经全局 Long→String 规约
            // （common-serialization JsonModules.standard，雪花大数 JS 安全）——按 JSON 解析断言三字段
            var event = tools.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(frame.getValue().getPayload());
            org.assertj.core.api.Assertions.assertThat(event.path("type").asText()).isEqualTo("message:new");
            org.assertj.core.api.Assertions.assertThat(event.path("messageId").asText())
                    .isEqualTo(String.valueOf(sent.get(0).getId()));
            org.assertj.core.api.Assertions.assertThat(event.path("title").asText()).isEqualTo("实时私信");
        } finally {
            registry.unregister(session);
        }
    }

    /** 构造带身份属性的 mock WS 会话（isOpen 且可捕获 sendMessage）。 */
    private static WebSocketSession sessionMock(String userId) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WsSessionRegistry.ATTR_USER_ID, userId);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getId()).thenReturn("slice-" + userId);
        when(session.isOpen()).thenReturn(true);
        doAnswer(invocation -> null).when(session).sendMessage(any());
        return session;
    }
}
