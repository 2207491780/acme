package com.acme.framework.core.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.acme.framework.core.internal.AfterCommitEventDispatcher;
import com.acme.framework.core.internal.DefaultTransactionEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 事务提交后事件发布测试（AC-005/006/014/015/016/017；P1 验收二）。
 * 事务以测试替身 PlatformTransactionManager 驱动（纯内存，NFR-006、Backend.md 16.2.1）。
 */
class TransactionEventPublisherTest {

    private StubPlatformTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        transactionManager = new StubPlatformTransactionManager();
        transactionTemplate = new TransactionTemplate(transactionManager);
        Logger logger = (Logger) LoggerFactory.getLogger(AfterCommitEventDispatcher.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(AfterCommitEventDispatcher.class)).detachAppender(logAppender);
        logAppender.stop();
    }

    private static TransactionEventPublisher publisherOf(AfterCommitEventListener<?>... listeners) {
        return new DefaultTransactionEventPublisher(new AfterCommitEventDispatcher(List.of(listeners)));
    }

    // ---------- AC-005（P1 验收二） ----------

    @Test
    void commitDeliversEventExactlyOnceAfterCommit() {
        RecordingListener listener = new RecordingListener();
        TransactionEventPublisher publisher = publisherOf(listener);
        boolean[] zeroBeforeCommit = new boolean[1];

        transactionTemplate.execute(status -> {
            publisher.publish("E");
            zeroBeforeCommit[0] = listener.received.isEmpty();
            return null;
        });

        assertThat(zeroBeforeCommit[0]).isTrue();
        assertThat(listener.received).containsExactly("E");
        assertThat(transactionManager.commits).isEqualTo(1);
    }

    // ---------- AC-006 ----------

    @Test
    void rollbackDiscardsBufferedEvents() {
        RecordingListener listener = new RecordingListener();
        TransactionEventPublisher publisher = publisherOf(listener);

        transactionTemplate.execute(status -> {
            publisher.publish("E");
            status.setRollbackOnly();
            return null;
        });

        assertThat(listener.received).isEmpty();
        assertThat(transactionManager.rollbacks).isEqualTo(1);
    }

    @Test
    void exceptionRollbackDoesNotDeliver() {
        RecordingListener listener = new RecordingListener();
        TransactionEventPublisher publisher = publisherOf(listener);

        assertThatCode(() -> transactionTemplate.execute(status -> {
            publisher.publish("E");
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(listener.received).isEmpty();
        assertThat(transactionManager.rollbacks).isEqualTo(1);
    }

    // ---------- AC-014（Q-002） ----------

    @Test
    void publishWithoutTransactionDeliversBeforeReturn() {
        RecordingListener listener = new RecordingListener();
        TransactionEventPublisher publisher = publisherOf(listener);

        publisher.publish("E");

        // 发布调用返回前恰好被调用一次
        assertThat(listener.received).containsExactly("E");
    }

    // ---------- AC-015（Q-003，含日志捕获断言） ----------

    @Test
    void listenerExceptionIsSwallowedAndLoggedWithoutTransaction() {
        ThrowingListener throwing = new ThrowingListener();
        RecordingListener survivor = new RecordingListener();
        TransactionEventPublisher publisher = publisherOf(throwing, survivor);

        assertThatCode(() -> publisher.publish("E")).doesNotThrowAnyException();

        assertThat(survivor.received).containsExactly("E");
        assertErrorLoggedWithThrowable();
    }

    @Test
    void listenerExceptionIsSwallowedAndLoggedAfterCommit() {
        ThrowingListener throwing = new ThrowingListener();
        TransactionEventPublisher publisher = publisherOf(throwing);

        assertThatCode(() -> transactionTemplate.execute(status -> {
            publisher.publish("E");
            return null;
        })).doesNotThrowAnyException();

        assertThat(transactionManager.commits).isEqualTo(1);
        assertErrorLoggedWithThrowable();
    }

    // ---------- AC-016（Q-004） ----------

    @Test
    void eventsDeliveredInPublishOrder() {
        RecordingListener listener = new RecordingListener();
        TransactionEventPublisher publisher = publisherOf(listener);

        transactionTemplate.execute(status -> {
            publisher.publish("E1");
            publisher.publish("E2");
            publisher.publish("E3");
            return null;
        });

        assertThat(listener.received).containsExactly("E1", "E2", "E3");
    }

    // ---------- AC-017（Q-005） ----------

    @Test
    void arbitraryObjectEventDelivered() {
        RecordingListener listener = new RecordingListener();
        TransactionEventPublisher publisher = publisherOf(listener);
        DemoEvent event = new DemoEvent("payload");

        transactionTemplate.execute(status -> {
            publisher.publish(event);
            return null;
        });

        assertThat(listener.received).containsExactly(event);
    }

    // ---------- DQ-001 泛型订阅 ----------

    @Test
    void genericSubscriptionMatchesEventTypeOnly() {
        RecordingStringListener stringListener = new RecordingStringListener();
        RecordingNumberListener numberListener = new RecordingNumberListener();
        TransactionEventPublisher publisher = publisherOf(stringListener, numberListener);

        transactionTemplate.execute(status -> {
            publisher.publish("text");
            publisher.publish(42);
            return null;
        });

        assertThat(stringListener.received).containsExactly("text");
        assertThat(numberListener.received).containsExactly(42);
    }

    @Test
    void publishDuringDeliveryDispatchesImmediately() {
        TransactionEventPublisher[] holder = new TransactionEventPublisher[1];
        List<Object> received = new CopyOnWriteArrayList<>();
        AfterCommitEventListener<Object> reentrant = event -> {
            received.add(event);
            if ("E1".equals(event)) {
                holder[0].publish("E2");
            }
        };
        holder[0] = publisherOf(reentrant);

        transactionTemplate.execute(status -> {
            holder[0].publish("E1");
            return null;
        });

        assertThat(received).containsExactly("E1", "E2");
    }

    @Test
    void publishWithoutListenersIsNoOp() {
        TransactionEventPublisher publisher = publisherOf();
        assertThatCode(() -> {
            publisher.publish("E");
            transactionTemplate.execute(status -> {
                publisher.publish("E2");
                return null;
            });
        }).doesNotThrowAnyException();
    }

    // ---------- 辅助 ----------

    private void assertErrorLoggedWithThrowable() {
        assertThat(logAppender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getMessage()).contains("boom");
        });
    }

    private record DemoEvent(String payload) {
    }

    private static final class RecordingListener implements AfterCommitEventListener<Object> {
        private final List<Object> received = new CopyOnWriteArrayList<>();

        @Override
        public void onEvent(Object event) {
            received.add(event);
        }
    }

    private static final class RecordingStringListener implements AfterCommitEventListener<String> {
        private final List<String> received = new CopyOnWriteArrayList<>();

        @Override
        public void onEvent(String event) {
            received.add(event);
        }
    }

    private static final class RecordingNumberListener implements AfterCommitEventListener<Number> {
        private final List<Number> received = new CopyOnWriteArrayList<>();

        @Override
        public void onEvent(Number event) {
            received.add(event);
        }
    }

    private static final class ThrowingListener implements AfterCommitEventListener<Object> {
        @Override
        public void onEvent(Object event) {
            throw new IllegalStateException("boom");
        }
    }

    /** 测试替身事务管理器（纯内存）。 */
    private static final class StubPlatformTransactionManager extends AbstractPlatformTransactionManager {
        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // 无资源，仅激活事务同步
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }
}
