package com.acme.framework.web.internal;

import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 暂存属性快照请求包装（5.5/5.6 协作）：访问日志在外圈（order+50）于链路结束后落盘，
 * 而提取过滤器（order+100）的 finally 清理先于日志语句执行；本包装仅快照访问日志实际读取的
 * 暂存位（{@link ClientContextDraft}、{@link AuthenticatedSubject}），使日志仍可取到
 * channel/deviceId/userId（FR-006 要素完整），且不改变清理语义（BR-025）。
 */
public final class StagingSnapshotRequest extends HttpServletRequestWrapper {

    /** 仅快照访问日志读取的暂存位（FR-006），不做全量属性快照。 */
    private static final Set<String> STAGED_ATTRIBUTES = Set.of(
            ClientContextDraft.ATTRIBUTE_NAME, AuthenticatedSubject.ATTRIBUTE_NAME);

    private final Map<String, Object> snapshot = new HashMap<>();

    public StagingSnapshotRequest(HttpServletRequest request) {
        super(request);
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (STAGED_ATTRIBUTES.contains(name)) {
            snapshot.put(name, value);
        }
        super.setAttribute(name, value);
    }

    @Override
    public Object getAttribute(String name) {
        Object value = super.getAttribute(name);
        return value != null ? value : snapshot.get(name);
    }

    @Override
    public void removeAttribute(String name) {
        super.removeAttribute(name);
    }
}
