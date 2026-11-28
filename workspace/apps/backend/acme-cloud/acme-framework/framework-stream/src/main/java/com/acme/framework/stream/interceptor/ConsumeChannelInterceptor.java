package com.acme.framework.stream.interceptor;

import com.acme.common.contract.ServiceHeaders;
import com.acme.common.context.ContextScope;
import com.acme.common.core.BizException;
import com.acme.framework.stream.config.MissingIdempotentKeyPolicy;
import com.acme.framework.stream.config.StreamProperties;
import com.acme.framework.stream.internal.ConsumeContexts;
import com.acme.framework.stream.support.IdempotentGuard;
import com.acme.framework.stream.support.StreamError;
import com.acme.framework.trace.support.TraceMessages;
import com.acme.framework.trace.support.TraceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;

/**
 * 消费通道拦截器（FR-014/024/032、AC-014/015/036，架构设计 8.4；Q-D003）：
 * ① trace 提取（TraceMessages，P2 接线）→ ② 上下文恢复（ContextHeaders，finally 清理）
 * → ③ 幂等门（Redis 去重键，fail-closed）→ ④ 业务执行（函数式入口，经包装器跳过/放行）
 * → ⑤ 成功标记去重键 / 失败释放进重试（10.3.3）。
 *
 * <p>实现形态说明：通道拦截器（真实消息头预解析取键）+ 函数入口包装器（{@code ConsumePipeline}）
 * 双件织入——设计草图的 BeanPostProcessor 单件形态无法获取消息头（SCSt 函数调用面载荷代理不带头），
 * 且重复消息以 preSend 置空拦截会被 binder 视为投递失败进重试；双件形态下重复消息在包装器内干净跳过
 * （投递成功、业务不执行），职责与顺序与 8.4 一致。</p>
 */
public class ConsumeChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ConsumeChannelInterceptor.class);

    /** 管线状态消息头（REQ-20261127-161433 P2-22）：状态随消息头传递（主面，线程模型无关），
     *  ThreadLocal 仅保留为非 Message 函数形态的回退面。 */
    public static final String STATE_HEADER = "acme_stream_consume_state";

    private static final ThreadLocal<PipelineState> STATE = new ThreadLocal<>();

    private final IdempotentGuard guard;
    private final StreamProperties properties;

    public ConsumeChannelInterceptor(IdempotentGuard guard, StreamProperties properties) {
        this.guard = guard;
        this.properties = properties;
    }

    /** 当前管线状态（非 Message 函数形态的 ThreadLocal 回退面；无状态返回 null）。 */
    public static PipelineState currentState() {
        return STATE.get();
    }

    /** 是否待跳过（非 Message 函数形态回退面；无状态/首投=false）。 */
    public static boolean isSkipPending() {
        PipelineState state = STATE.get();
        return state != null && state.skip();
    }

    /** 从消息头读管线状态（P2-22 主面：preSend 注入、包装器/收尾消费，跨线程携带）。 */
    public static PipelineState stateOf(Message<?> message) {
        if (message != null && message.getHeaders().get(STATE_HEADER) instanceof PipelineState state) {
            return state;
        }
        return null;
    }

    /** 消费入口：去重门（反序列化前取键，8.3）+ trace/上下文绑定。 */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        String idempotentKey = resolveIdempotentKey(message);
        boolean skip = false;
        boolean claimed = false;
        if (idempotentKey == null) {
            if (properties.getIdempotency().getMissingKeyPolicy() == MissingIdempotentKeyPolicy.REJECT) {
                throw new BizException(StreamError.ENVELOPE_INVALID, "消息缺少幂等键（FR-014）");
            }
            log.warn("消息缺少幂等键，放行但不去重（ACCEPT_WITH_WARN，10.5.4）: messageId={}",
                    message.getHeaders().get(ServiceHeaders.MESSAGE_ID));
        } else {
            IdempotentGuard.Begin begin = guard.tryBegin(idempotentKey);
            if (begin == IdempotentGuard.Begin.STARTED) {
                claimed = true;
            } else {
                // 重复/在途投递：标记跳过（包装器不执行业务；投递正常完成，AC-015）
                if (begin == IdempotentGuard.Begin.DUPLICATE) {
                    log.info("重复消息已跳过（FR-014 幂等去重）: idempotentKey={}", idempotentKey);
                } else {
                    log.info("在途消息已跳过（FR-014 幂等占位未过期）: idempotentKey={}", idempotentKey);
                }
                skip = true;
            }
        }
        try {
            TraceScope traceScope = TraceMessages.extract(message.getHeaders());
            ContextScope contextScope = ConsumeContexts.restore(message.getHeaders());
            PipelineState state = new PipelineState(idempotentKey, skip, traceScope, contextScope);
            STATE.set(state);
            // P2-22：状态随消息头携带（主面）——函数包装器/收尾消费头面优先，跨线程派发不依赖同线程假设
            return MessageBuilder.fromMessage(message)
                    .setHeader(STATE_HEADER, state)
                    .build();
        } catch (RuntimeException | Error ex) {
            // 认领后到状态绑定前失败：释放占位防孤儿（M-12），再原样抛出
            if (claimed && idempotentKey != null) {
                try {
                    guard.markFailed(idempotentKey);
                } catch (RuntimeException suppressed) {
                    ex.addSuppressed(suppressed);
                }
            }
            STATE.remove();
            throw ex;
        }
    }

    /** 收尾（业务执行完成/失败后）：清理上下文 + 去重键标记/释放（异常路径同样清理，FR-032）。
     *  状态读面=消息头优先（P2-22 主面，跨线程派发形态），ThreadLocal 回退（同线程收尾/既有
     *  fresh-message 测试与调用面）——双面取到即清理。 */
    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel, boolean sent, Exception ex) {
        PipelineState current = stateOf(message);
        if (current == null) {
            current = STATE.get();
        }
        STATE.remove();
        if (current == null) {
            return;
        }
        try {
            current.contextScope.close();
            current.traceScope.close();
        } finally {
            if (current.idempotentKey() != null && !current.skip()) {
                if (ex == null && sent) {
                    guard.markDone(current.idempotentKey());
                } else {
                    // 失败释放占位，binder 重投可再处理（10.3.3、8.4 ⑤）
                    guard.markFailed(current.idempotentKey());
                }
            }
        }
    }

    private String resolveIdempotentKey(Message<?> message) {
        Object key = message.getHeaders().get(ServiceHeaders.IDEMPOTENT_KEY);
        if (key == null || String.valueOf(key).isBlank()) {
            key = message.getHeaders().get(ServiceHeaders.MESSAGE_ID);
        }
        return key == null || String.valueOf(key).isBlank() ? null : String.valueOf(key).trim();
    }

    /** 管线状态（幂等键 + 跳过标记 + 作用域收尾位）。 */
    public record PipelineState(String idempotentKey, boolean skip, TraceScope traceScope, ContextScope contextScope) {
    }
}
