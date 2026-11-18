package com.acme.common.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ContainerSupport 测试（FR-032；DP-014 定稿）：单例复用与参数校验。
 * 以覆写 start() 的假容器隔离 Docker 依赖，仅验证封装语义（Backend.md 16.2.1 集成容器由使用方接入）。
 */
class ContainerSupportTest {

    @AfterEach
    void cleanup() {
        ContainerSupport.resetSingletons();
    }

    /** 假容器：记录 start 调用次数，不触碰 Docker。 */
    static final class FakeContainer extends GenericContainer<FakeContainer> {

        int started;

        FakeContainer() {
            super("alpine:3.20");
        }

        @Override
        public void start() {
            started++;
        }
    }

    @Test
    void singletonReusesSameInstancePerKey() {
        FakeContainer first = ContainerSupport.singleton("alpine:3.20", FakeContainer::new);
        FakeContainer second = ContainerSupport.singleton("alpine:3.20", FakeContainer::new);

        assertSame(first, second);
        assertEquals(1, first.started, "同 key 只应启动一次");
    }

    @Test
    void distinctKeysGetDistinctInstances() {
        FakeContainer a = ContainerSupport.singleton("key-a", FakeContainer::new);
        FakeContainer b = ContainerSupport.singleton("key-b", FakeContainer::new);

        assertTrue(a != b);
        assertEquals(1, a.started);
        assertEquals(1, b.started);
    }

    @Test
    void nullArgumentsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ContainerSupport.start(null));
        assertThrows(IllegalArgumentException.class, () -> ContainerSupport.singleton(null, FakeContainer::new));
        assertThrows(IllegalArgumentException.class, () -> ContainerSupport.singleton("k", null));
    }
}
