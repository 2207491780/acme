package com.acme.common.core.internal;

import com.acme.common.core.Clock;

import java.time.Instant;

/**
 * 系统时钟默认实现（Modules.md 2.3：实现细节，模块外不得引用）。
 */
public final class SystemClock implements Clock {

    public static final SystemClock INSTANCE = new SystemClock();

    private SystemClock() {
    }

    @Override
    public Instant now() {
        return Instant.now();
    }
}
