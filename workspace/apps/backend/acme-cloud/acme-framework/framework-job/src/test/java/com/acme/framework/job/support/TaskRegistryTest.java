package com.acme.framework.job.support;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务注册（FR-026/AC-026：登记与重复注册显式失败）。
 */
class TaskRegistryTest {

    static class Demo {
        void run() {
        }
    }

    private final TaskRegistry registry = new TaskRegistry();
    private final Method method;

    TaskRegistryTest() throws NoSuchMethodException {
        this.method = Demo.class.getDeclaredMethod("run");
    }

    private TaskDefinition definition(String taskId) {
        return new TaskDefinition(taskId, MutexMode.GLOBAL, Duration.ofMinutes(1), Duration.ofSeconds(1),
                new Demo(), method, "Demo#run");
    }

    @Test
    void registersAndFinds() {
        registry.register(definition("t-1"));
        assertThat(registry.find("t-1")).isPresent();
        assertThat(registry.find("t-2")).isEmpty();
        assertThat(registry.all()).hasSize(1);
    }

    @Test
    void duplicateRegistrationFailsExplicitly() {
        registry.register(definition("t-1"));
        assertThatThrownBy(() -> registry.register(definition("t-1")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blankTaskIdRejected() {
        assertThatThrownBy(() -> new TaskDefinition(" ", MutexMode.GLOBAL, null, null, null, method, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
