package com.allen.questionbank.practice;

import com.allen.questionbank.auth.ApiTokenFilter;
import com.allen.questionbank.common.ApiException;
import com.allen.questionbank.redis.RedisLockService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * 提交路径的 Redis 锁协调器（app.lock.backend=redis 时启用，默认关闭）。
 *
 * 两条必须遵守的顺序约束，违反任一条锁就形同虚设：
 * 1. **锁必须在事务外获取**。PracticeService.submit 自己开事务；如果锁在事务内获取，
 *    本事务还没提交别的实例就能拿到锁，读到旧状态后重复提交。
 * 2. **锁必须在事务提交之后释放**。submit 返回时事务已提交，此刻释放才安全。
 *
 * 这不是唯一防线：即使这把锁因为 GC 停顿、TTL 到期或主从切换而失效，
 * 数据库侧的 SELECT ... FOR UPDATE 行锁与 uk_submission_question 唯一键仍会挡住重复写入。
 * 分布式锁负责减少冲突和数据库压力，唯一键负责兜底正确性。
 */
@Service
@ConditionalOnProperty(name = "app.lock.backend", havingValue = "redis")
public class RedisGuardedPracticeSubmitter {

    private final PracticeService practiceService;
    private final RedisLockService lockService;

    public RedisGuardedPracticeSubmitter(PracticeService practiceService, RedisLockService lockService) {
        this.practiceService = practiceService;
        this.lockService = lockService;
    }

    public PracticeService.SubmitResult submit(ApiTokenFilter.AuthPrincipal user, Long sessionId, String idempotencyKey) {
        Optional<RedisLockService.LockHandle> lock =
                lockService.tryLock("practice-submit:" + sessionId, Duration.ofSeconds(10));
        if (lock.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "SUBMIT_IN_PROGRESS", "该练习正在提交中，请稍后重试");
        }
        try {
            return practiceService.submit(user, sessionId, idempotencyKey);
        } finally {
            lock.get().unlock();
        }
    }
}
