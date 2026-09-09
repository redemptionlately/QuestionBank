package com.allen.cloud.practice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.allen.cloud.common.BankClient;
import com.allen.cloud.common.PaperSnapshot;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

/**
 * 对 bank-service 调用的门面，只暴露一个动作：拉试卷快照。
 *
 * <p>为什么单独包一层而不是在业务 Service 里直接注入 Feign 接口：
 * 熔断、超时、重试、降级都是"跨服务调用"的属性，不是业务逻辑的属性。
 * 混在一起，业务单测每次都要先处理一次远程调用。
 *
 * <p>降级策略（说清楚，别吹）：这里是<b>快速失败</b>，不是"返回兜底数据"。
 * 开新会话必须有真实题目，返回空卷没有意义；
 * 降级的真实价值是"立刻失败并给出明确错误码"，不让线程池被下游拖死。
 * 能返回兜底数据的接口应该是"允许质量下降"的那类（读缓存、读推荐）。
 */
@Component
public class BankSnapshotGateway {

    private static final Logger log = LoggerFactory.getLogger(BankSnapshotGateway.class);

    private final BankClient bankClient;

    public BankSnapshotGateway(BankClient bankClient) {
        this.bankClient = bankClient;
    }

    @CircuitBreaker(name = "bankService", fallbackMethod = "fallback")
    public PaperSnapshot fetchSnapshot(Long paperId) {
        return bankClient.snapshot(paperId);
    }

    @SuppressWarnings("unused")
    private PaperSnapshot fallback(Long paperId, Throwable cause) {
        log.warn("[circuit-breaker] bank-service 调用失败，快速降级 paperId={} 原因={}",
                paperId, cause.getClass().getSimpleName());
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "bank-service 暂不可用，无法创建新会话（熔断已生效，快速失败）");
    }
}
