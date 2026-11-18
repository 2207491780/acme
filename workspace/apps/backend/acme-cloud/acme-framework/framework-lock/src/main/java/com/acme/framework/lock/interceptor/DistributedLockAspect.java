package com.acme.framework.lock.interceptor;

import com.acme.framework.lock.support.DistributedLock;
import com.acme.framework.lock.support.DistributedLocks;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

/**
 * {@link DistributedLock} AOP（FR-010/AC-010）：加锁（异常路径必释放）→ 执行 → 释放；
 * SKIP 策略按方法返回类型分流（void=跳过，非 void=显式失败，取舍 #8）。
 */
@Aspect
public class DistributedLockAspect {

    private final DistributedLocks locks;

    public DistributedLockAspect(DistributedLocks locks) {
        this.locks = locks;
    }

    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        boolean voidMethod = signature.getReturnType() == void.class;
        String lockName = distributedLock.key();
        if (voidMethod) {
            locks.execute(lockName, distributedLock.policy(),
                    distributedLock.waitMillis(), distributedLock.leaseSeconds(),
                    () -> proceedQuietly(joinPoint));
            return null;
        }
        return locks.execute(lockName, distributedLock.policy(),
                distributedLock.waitMillis(), distributedLock.leaseSeconds(),
                () -> proceed(joinPoint));
    }

    private static Object proceed(ProceedingJoinPoint joinPoint) {
        try {
            return joinPoint.proceed();
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new IllegalStateException("受锁方法执行失败", ex);
        }
    }

    private static void proceedQuietly(ProceedingJoinPoint joinPoint) {
        proceed(joinPoint);
    }
}
