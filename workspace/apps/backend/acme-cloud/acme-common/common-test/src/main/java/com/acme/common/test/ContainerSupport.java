package com.acme.common.test;

import org.testcontainers.containers.GenericContainer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Testcontainers 封装（FR-032；DP-014 定稿）：显式启动与单例复用两种形态；
 * 容器启动失败使测试显式失败（FR-032 异常分支）。测试栈仅限测试域（BR-023、Q-012）。
 *
 * <p>单例复用：同 JVM 内多个测试类按 key 共享一个容器实例（首次获取即启动，
 * Ryuk 在 JVM 退出时统一回收，Backend.md 16.2.1 不依赖开发者本地常驻容器）。</p>
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class ContainerSupport {

    private static final Map<String, GenericContainer<?>> SINGLETONS = new ConcurrentHashMap<>();

    private ContainerSupport() {
    }

    /** 启动容器（FR-032）；container 为 null 或启动失败显式失败；一次性形态（每个实例独立生命周期）。 */
    public static <T extends GenericContainer<?>> T start(T container) {
        if (container == null) {
            throw new IllegalArgumentException("container 不能为空");
        }
        container.start();
        return container;
    }

    /**
     * 单例容器（DP-014 定稿）：同 key 首次获取时创建并启动，后续获取复用同实例。
     *
     * @param key     复用键（建议容器镜像名 + 版本，如 {@code postgres:17}）
     * @param factory 容器工厂（仅在首次创建时调用）
     */
    public static <T extends GenericContainer<?>> T singleton(String key, Supplier<T> factory) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key 不能为空");
        }
        if (factory == null) {
            throw new IllegalArgumentException("factory 不能为空");
        }
        return (T) SINGLETONS.computeIfAbsent(key, unused -> {
            T container = factory.get();
            container.start();
            return container;
        });
    }

    /** 移除单例登记（测试需强制重建容器时使用；容器关闭由调用方负责）。 */
    static void resetSingletons() {
        SINGLETONS.clear();
    }
}
