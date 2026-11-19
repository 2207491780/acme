package com.acme.framework.core.support;

import com.acme.common.context.UserContext;

import java.util.Set;
import java.util.function.Supplier;

/**
 * 服务身份出站作用域（Q-D08 收敛单源）：跨域出站调用前以服务身份主体（userId=服务名、空角色集）
 * 临时替换 UserContext，作用域结束恢复进入前绑定（含未绑定为 null 的场景——{@code bind(null)} 即解绑）。
 * 收敛自 auth 画像/配置/租户目录网关、system 授权撤销网关与 Dashboard 概览、bootstrap 本地适配器
 * 的同款 try/finally 样板（各调用方仍各自持有 {@code SERVICE_IDENTITY} 常量，服务名归属不变）。
 *
 * <p>调用方主体不被出站覆盖；作用域内异常同样恢复（try/finally 语义，S14 纯重构不改行为）。</p>
 */
public final class ServiceIdentityScope {

    private ServiceIdentityScope() {
    }

    /** 以服务身份主体执行并返回结果，作用域结束恢复进入前 UserContext（Q-D08 口径）。 */
    public static <T> T runAs(String serviceIdentity, Supplier<T> work) {
        UserContext previous = UserContext.get();
        UserContext.bind(new UserContext(serviceIdentity, Set.of()));
        try {
            return work.get();
        } finally {
            UserContext.bind(previous);
        }
    }

    /** 以服务身份主体执行无返回值工作，作用域结束恢复进入前 UserContext（Q-D08 口径）。 */
    public static void runAs(String serviceIdentity, Runnable work) {
        runAs(serviceIdentity, () -> {
            work.run();
            return null;
        });
    }
}
