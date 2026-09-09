package com.allen.questionbank.ops;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 业务方法耗时埋点。只做观测，不改变返回值、不吞异常：
 * 失败边界是 self-invocation（同类内部方法互调不经过代理，这里也就记不到）。
 */
@Aspect
@Component
public class ServiceLatencyAspect {

    private static final long SLOW_CALL_THRESHOLD_MS = 200;
    private static final Logger log = LoggerFactory.getLogger(ServiceLatencyAspect.class);

    @Around("execution(* com.allen.questionbank..*Service.*(..))")
    public Object measure(ProceedingJoinPoint joinPoint) throws Throwable {
        long startedNanos = System.nanoTime();
        try {
            return joinPoint.proceed();
        } finally {
            long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;
            if (elapsedMillis >= SLOW_CALL_THRESHOLD_MS) {
                log.warn("慢调用 {} 耗时 {} ms", joinPoint.getSignature().toShortString(), elapsedMillis);
            }
        }
    }
}
