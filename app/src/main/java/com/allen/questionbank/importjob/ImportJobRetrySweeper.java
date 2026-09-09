package com.allen.questionbank.importjob;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 导入任务重试清扫。修复前 FAILED 任务永久停留（无调度器补扫）——attempt 列
 * （V3 schema）与 start() 的自增早已就位，缺的只是触发器，本类补上。
 *
 * <p>设计：
 * <ul>
 *   <li>指数退避：第 n 次尝试失败后至少等待 30s × 2^(n-1)。等待起点取 updatedAt
 *       （fail() 落库时由 @PreUpdate 刷新），避免对同一失败任务连续冲击；</li>
 *   <li>attempt 上限 {@value #MAX_ATTEMPTS}：超限任务保持 FAILED 终态，error 字段
 *       保留最后一次失败原因，人工介入而不是无限重试毒丸任务；</li>
 *   <li>状态翻转 FAILED → RECEIVED 后跨 bean 调用 worker.process()——@Async 代理
 *       在跨 bean 调用时才生效，重活交给 import 池而非占用调度线程（同 bean 内
 *       this.process() 会绕过代理，这是清扫逻辑单出一个组件的原因之一）。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.import.retry.enabled", havingValue = "true", matchIfMissing = true)
public class ImportJobRetrySweeper {

    static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_SECONDS = 30;
    private static final long MAX_BACKOFF_SECONDS = 600;

    private final ImportJobRepository jobs;
    private final ImportJobWorker worker;

    public ImportJobRetrySweeper(ImportJobRepository jobs, ImportJobWorker worker) {
        this.jobs = jobs;
        this.worker = worker;
    }

    /** @return 本次成功重新入队的任务数（供测试断言与日志观察） */
    @Scheduled(fixedDelayString = "${app.import.retry.sweep-ms:60000}")
    public int retryFailedJobs() {
        List<ImportJob> candidates = jobs.findByStatusAndAttemptLessThan(ImportJobStatus.FAILED, MAX_ATTEMPTS);
        if (candidates.isEmpty()) return 0;
        Instant now = Instant.now();
        int requeued = 0;
        for (ImportJob job : candidates) {
            Instant backoffCutoff = now.minusSeconds(backoffSeconds(job.getAttempt()));
            if (job.getUpdatedAt().isAfter(backoffCutoff)) continue; // 退避窗口未过
            job.retry();
            jobs.save(job);
            worker.process(job.getId());
            requeued++;
        }
        return requeued;
    }

    /** 第 n 次尝试失败后的最小等待秒数：30 × 2^(n-1)，封顶 10 分钟。 */
    static long backoffSeconds(int attempt) {
        long seconds = BASE_BACKOFF_SECONDS << Math.max(0, attempt - 1);
        return Math.min(seconds, MAX_BACKOFF_SECONDS);
    }
}
