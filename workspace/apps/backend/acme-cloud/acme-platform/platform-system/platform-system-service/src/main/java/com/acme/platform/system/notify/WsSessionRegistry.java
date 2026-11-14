package com.acme.platform.system.notify;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WS 会话注册表（REQ-20261114-120102）：userId → 本实例持有 sessions（session 注册表，多实例扇出的
 * 本地投递面）；心跳活动时间按 session 维护，{@code sweepIdle} 对静默超心跳周期（默认 30s）的连接
 * 服务端主动断开并回收（确认记录④、AC-9）。单 session 并发发送互斥（WebSocketSession 非线程安全）。
 */
@Component
public class WsSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(WsSessionRegistry.class);

    /** 会话属性键：验签后的用户 id（握手拦截器写入）。 */
    public static final String ATTR_USER_ID = "notify.userId";

    private final Map<String, Set<WebSocketSession>> sessionsByUser = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lastActivityBySession = new ConcurrentHashMap<>();

    private final NotifyProperties properties;
    private ScheduledExecutorService sweeper;

    public WsSessionRegistry(NotifyProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void startSweeper() {
        sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "notify-ws-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        sweeper.scheduleWithFixedDelay(this::sweepSafely,
                properties.getSweepInterval().toMillis(), properties.getSweepInterval().toMillis(),
                TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopSweeper() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
    }

    /** 注册会话（握手验签通过后调用；写入即视为活跃）。 */
    public void register(String userId, WebSocketSession session) {
        sessionsByUser.computeIfAbsent(userId, key -> new CopyOnWriteArraySet<>()).add(session);
        touch(session);
        log.debug("WS 会话注册: userId={}, sessionId={}, 本实例会话数={}", userId, session.getId(), size());
    }

    /** 注销会话（连接关闭 / 回收统一走此面，幂等）。 */
    public void unregister(WebSocketSession session) {
        Object userId = session.getAttributes().get(ATTR_USER_ID);
        lastActivityBySession.remove(session.getId());
        if (userId != null) {
            Set<WebSocketSession> sessions = sessionsByUser.get(String.valueOf(userId));
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    sessionsByUser.remove(String.valueOf(userId), sessions);
                }
            }
        }
        log.debug("WS 会话注销: sessionId={}, 本实例会话数={}", session.getId(), size());
    }

    /** 心跳活动刷新（任意上行帧刷新；30s 静默从最后一帧起算）。 */
    public void touch(WebSocketSession session) {
        lastActivityBySession
                .computeIfAbsent(session.getId(), key -> new AtomicLong())
                .set(System.currentTimeMillis());
    }

    /** 向该用户全部本地会话推送帧文本；发送失败会话即时回收（对端半开兜底）。 */
    public void push(String userId, String frame) {
        Set<WebSocketSession> sessions = sessionsByUser.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        for (WebSocketSession session : sessions) {
            send(session, frame);
        }
    }

    /** 本实例在线会话数（测试 / 观测面）。 */
    public int size() {
        return lastActivityBySession.size();
    }

    /** 指定用户当前是否有本地会话（测试 / 观测面）。 */
    public boolean hasSessionsFor(String userId) {
        Set<WebSocketSession> sessions = sessionsByUser.get(userId);
        return sessions != null && !sessions.isEmpty();
    }

    /** 回收静默超期的全部会话（AC-9：服务端断开并回收会话）。 */
    void sweepIdle() {
        long deadline = System.currentTimeMillis() - properties.getIdleTimeout().toMillis();
        for (Map.Entry<String, AtomicLong> entry : lastActivityBySession.entrySet()) {
            if (entry.getValue().get() < deadline) {
                closeQuietly(entry.getKey());
            }
        }
    }

    private void sweepSafely() {
        try {
            sweepIdle();
        } catch (RuntimeException ex) {
            log.warn("WS 空闲回收扫描异常（下一轮继续）: {}", ex.getMessage());
        }
    }

    private void closeQuietly(String sessionId) {
        for (Set<WebSocketSession> sessions : sessionsByUser.values()) {
            for (WebSocketSession session : sessions) {
                if (session.getId().equals(sessionId)) {
                    sessions.remove(session);
                    lastActivityBySession.remove(sessionId);
                    try {
                        session.close(CloseStatus.GOING_AWAY);
                        log.info("WS 会话静默超时回收（idle-timeout={}）: sessionId={}",
                                properties.getIdleTimeout(), sessionId);
                    } catch (IOException | RuntimeException ex) {
                        log.debug("WS 会话回收关闭异常（已从注册表移除）: sessionId={}", sessionId);
                    }
                    return;
                }
            }
        }
        lastActivityBySession.remove(sessionId);
    }

    private void send(WebSocketSession session, String frame) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(frame));
                }
            }
        } catch (IOException | RuntimeException ex) {
            log.debug("WS 帧发送失败，回收会话: sessionId={}", session.getId());
            unregister(session);
            try {
                session.close(CloseStatus.SESSION_NOT_RELIABLE);
            } catch (IOException | RuntimeException ignored) {
                // 关闭失败不再处理（对端半开由容器超时兜底）
            }
        }
    }
}
