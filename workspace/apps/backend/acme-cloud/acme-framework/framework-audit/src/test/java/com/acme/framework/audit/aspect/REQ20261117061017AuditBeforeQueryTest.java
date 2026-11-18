package com.acme.framework.audit.aspect;

import com.acme.common.context.Channel;
import com.acme.common.context.ClientContext;
import com.acme.common.context.TenantContext;
import com.acme.common.context.TraceContext;
import com.acme.common.context.UserContext;
import com.acme.framework.audit.config.AuditProperties;
import com.acme.framework.audit.support.AuditEvent;
import com.acme.framework.audit.support.AuditLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.expression.BeanResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261117-061017 AC-5 审计旧值快照（beforeQuery）单元测试（framework-audit，纯 JUnit 无 Spring
 * 上下文；装配先例 {@code AuditAspectTest}——AspectJProxyFactory + lambda publisher）。
 *
 * <p>覆盖：beforeQuery SpEL 在 proceed 前求值（快照=旧值）、支持 {@code @beanName} 引用、
 * 结果以 {@code before=<摘要>} 前缀并入 content、超长截断、表达式失败静默跳过不阻断业务。</p>
 *
 * <p>口径说明（目标态假设，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 {@code AuditLog} 新增可选属性 {@code beforeQuery()}（SpEL，默认空）——确认③；
 *       注解默认值缺失时本类无法编译，属预期（随实现同步落地）。</li>
 *   <li>AMB-2 {@code AuditAspect} 构造器假设为三参 {@code (AuditEventPublisher, AuditProperties,
 *       BeanResolver)}——{@code @beanName} 引用经 {@code StandardEvaluationContext.setBeanResolver}
 *       注入；若交付为两参＋setter 或 BeanFactory 形参，{@link #proxy} 装配处同步调整。</li>
 *   <li>AMB-3 before= 为 content 前缀（确认③「前缀并入」）：无 contentParams 时 content 即
 *       {@code before=<摘要>}；与 contentParams 共存时 before= 在前、参数摘要随后。</li>
 *   <li>AMB-4 截断阈值取审计内容 512 先例（确认③「截断 512」）：断言
 *       {@code content.length() <= "before=".length() + 512}（无论按「before 部分截断」还是
 *       「合并后总长截断」口径均成立）。</li>
 *   <li>AMB-5 表达式失败静默跳过（确认③）：content 不含 before=（无 contentParams 时为 null），
 *       业务方法正常执行返回。</li>
 *   <li>AMB-6 before 值序列化形态（JSON/toString）不钉死：断言仅锚定取值内容出现在 content 中。</li>
 * </ul>
 */
class REQ20261117061017AuditBeforeQueryTest {

    /** before= 前缀（AMB-3）。 */
    private static final String BEFORE_PREFIX = "before=";

    /** before 摘要截断上界：前缀 + 512（AMB-4）。 */
    private static final int BEFORE_TRUNCATED_TOTAL = BEFORE_PREFIX.length() + 512;

    private final List<AuditEvent> events = new ArrayList<>();

    @AfterEach
    void cleanup() {
        UserContext.clear();
        TenantContext.clear();
        ClientContext.clear();
        TraceContext.clear();
    }

    // ---------- AC-5：beforeQuery 经 @bean 引用求值，content 含 before= 前缀 ----------

    @Test
    void should_prefixBeforeSnapshotResolvedFromBean_when_beforeQuerySpEL() {
        UserContext.bind(new UserContext("op-1", Set.of()));
        TenantContext.bind(new TenantContext("t-1"));
        ClientContext.bind(new ClientContext(Channel.B, "dev-1", "1.0"));
        TraceContext.bind(new TraceContext("trace-1", "span-1"));

        List<AuditEvent> captured = new ArrayList<>();
        SampleConfigUpdate target = new SampleConfigUpdate();
        OldSnapshotLoader loader = new OldSnapshotLoader(target);
        SampleConfigUpdate proxy = proxy(target, loader, captured);

        assertThat(proxy.update("42")).isEqualTo("done");

        assertThat(captured).hasSize(1);
        AuditEvent event = captured.getFirst();
        assertThat(event.action()).isEqualTo("demo.config.update");
        assertThat(event.targetId()).isEqualTo("42");
        assertThat(event.operatorId()).isEqualTo("op-1");
        assertThat(event.traceId()).isEqualTo("trace-1");
        assertThat(event.content())
                .as("@oldSnapshot 引用取值应以 before= 前缀并入 content（AMB-3）")
                .startsWith(BEFORE_PREFIX)
                .contains("old-state-value");
    }

    // ---------- AC-5：proceed 前求值——快照为旧值，方法内变更不进入快照 ----------

    @Test
    void should_snapshotOldValueBeforeProceed_when_methodMutatesTarget() {
        SampleConfigUpdate target = new SampleConfigUpdate();
        OldSnapshotLoader loader = new OldSnapshotLoader(target);
        SampleConfigUpdate proxy = proxy(target, loader, events);

        String result = proxy.update("42");

        assertThat(result).isEqualTo("done");
        assertThat(target.state()).as("业务方法应已执行").isEqualTo("new-state-value");
        assertThat(events.getFirst().content())
                .as("beforeQuery 应在 proceed 前求值：快照=旧值而非方法执行后的新值")
                .contains("old-state-value")
                .doesNotContain("new-state-value");
    }

    // ---------- AC-5：before 摘要超长截断（512 先例，AMB-4） ----------

    @Test
    void should_truncateBeforeSnapshot_when_valueExceedsLimit() {
        SampleConfigUpdate target = new SampleConfigUpdate();
        OldSnapshotLoader loader = new OldSnapshotLoader(target);
        loader.override = "A".repeat(600);
        SampleConfigUpdate proxy = proxy(target, loader, events);

        assertThat(proxy.update("42")).isEqualTo("done");

        String content = events.getFirst().content();
        assertThat(content).startsWith(BEFORE_PREFIX);
        assertThat(content.length())
                .as("before 摘要应截断：content 总长 <= 前缀 + 512（AMB-4）")
                .isLessThanOrEqualTo(BEFORE_TRUNCATED_TOTAL);
        assertThat(content).contains("A".repeat(32));
    }

    // ---------- AC-5：表达式失败静默（bean 缺失）——业务照常、不产 before ----------

    @Test
    void should_proceedAndOmitBefore_when_beanReferenceMissing() {
        SampleMissingBean target = new SampleMissingBean();
        OldSnapshotLoader loader = new OldSnapshotLoader(target);
        SampleMissingBean proxy = proxy(target, loader, events);

        assertThat(proxy.update("42")).as("表达式失败不阻断业务（AMB-5）").isEqualTo("done");
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().content())
                .as("bean 缺失应静默跳过 before 片段（无 contentParams 时 content 为空）")
                .isNullOrEmpty();
    }

    // ---------- AC-5：表达式失败静默（SpEL 非法链）——业务照常、不产 before ----------

    @Test
    void should_proceedAndOmitBefore_when_expressionInvalid() {
        SampleBrokenExpr target = new SampleBrokenExpr();
        OldSnapshotLoader loader = new OldSnapshotLoader(target);
        SampleBrokenExpr proxy = proxy(target, loader, events);

        assertThat(proxy.update("42")).isEqualTo("done");
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().content()).isNullOrEmpty();
    }

    // ---------- AC-5：before 与 contentParams 共存——before= 前缀 + 参数摘要并存 ----------

    @Test
    void should_mergeBeforeWithContentParams_when_bothDeclared() {
        SampleMergedContent target = new SampleMergedContent();
        OldSnapshotLoader loader = new OldSnapshotLoader(target);
        SampleMergedContent proxy = proxy(target, loader, events);

        assertThat(proxy.update("42")).isEqualTo("done");

        String content = events.getFirst().content();
        assertThat(content)
                .as("before= 应为前缀，参数摘要随后（AMB-3）")
                .startsWith(BEFORE_PREFIX)
                .contains("old-state-value")
                .contains("id=42");
    }

    // ---------- 支撑 ----------

    /** 构造被测切面代理（AMB-2 三参构造 + BeanResolver lambda）。 */
    private <T> T proxy(T target, OldSnapshotLoader loader, List<AuditEvent> captured) {
        BeanResolver resolver = (context, beanName) ->
                "oldSnapshot".equals(beanName) ? loader : null;
        AuditAspect aspect = new AuditAspect(captured::add, new AuditProperties(), resolver);
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    /** 旧值快照桩 bean（@oldSnapshot 引用目标；override 供截断用例注入超长值）。
     *  编译对接修正（交付侧）：holder 宽型 Object——SampleConfigUpdate/SampleMergedContent 等
     *  被测面同以反射读 {@code state()}（state() 为包内可见，setAccessible 旁路）；
     *  load 须 public——SpEL ReflectiveMethodResolver 仅解析 public 方法（EL1004E 口径）。 */
    static final class OldSnapshotLoader {
        private final Object stateOwner;
        String override;

        OldSnapshotLoader(Object stateOwner) {
            this.stateOwner = stateOwner;
        }

        public String load(String id) {
            if (override != null) {
                return override;
            }
            try {
                var method = stateOwner.getClass().getDeclaredMethod("state");
                method.setAccessible(true);
                Object result = method.invoke(stateOwner);
                return result == null ? null : String.valueOf(result);
            } catch (ReflectiveOperationException ex) {
                return null;
            }
        }
    }

    /** 被测方法：beforeQuery 经 @bean 引用；方法体内变更状态（proceed 前求值探针）。 */
    static class SampleConfigUpdate {
        private String state = "old-state-value";

        @AuditLog(action = "demo.config.update", targetId = "#p0",
                beforeQuery = "@oldSnapshot.load(#p0)")
        public String update(String id) {
            this.state = "new-state-value";
            return "done";
        }

        String state() {
            return state;
        }
    }

    /** 被测方法：@bean 引用指向不存在的 bean（resolver 返回 null → 静默）。 */
    static class SampleMissingBean {
        @AuditLog(action = "demo.missing.bean", targetId = "#p0",
                beforeQuery = "@noSuchBean.load(#p0)")
        public String update(String id) {
            return "done";
        }
    }

    /** 被测方法：SpEL 链式取值在 null 上失败（#p0 无 chainMissing 属性 → 静默）。 */
    static class SampleBrokenExpr {
        @AuditLog(action = "demo.broken.expr", targetId = "#p0",
                beforeQuery = "#p0.chainMissing()")
        public String update(String id) {
            return "done";
        }
    }

    /** 被测方法：beforeQuery 与 contentParams 共存（合并形态 AMB-3）。 */
    static class SampleMergedContent {
        private String state = "old-state-value";

        @AuditLog(action = "demo.merged.update", targetId = "#p0",
                beforeQuery = "@oldSnapshot.load(#p0)", contentParams = {"id"})
        public String update(String id) {
            this.state = "new-state-value";
            return "done";
        }

        String state() {
            return state;
        }
    }
}
