package com.allen.questionbank;

import com.allen.questionbank.auth.ApiTokenFilter;
import com.allen.questionbank.auth.Role;
import com.allen.questionbank.auth.UserAccountRepository;
import com.allen.questionbank.bank.BankController;
import com.allen.questionbank.bank.BankService;
import com.allen.questionbank.bank.PaperVersion;
import com.allen.questionbank.bank.QuestionBank;
import com.allen.questionbank.bank.QuestionType;
import com.allen.questionbank.common.ApiException;
import com.allen.questionbank.practice.PracticeService;
import com.allen.questionbank.practice.PracticeSession;
import com.allen.questionbank.practice.RedisGuardedPracticeSubmitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 提交路径上 Redis 锁与数据库兜底的关系（开关 REDIS_EVIDENCE=true）。
 * 这两个用例一起才说明白：锁负责减少冲突，数据库唯一键负责保证正确——锁可以失效，正确性不能。
 */
@SpringBootTest
@ActiveProfiles({"test", "redis"})
@EnabledIfEnvironmentVariable(named = "REDIS_EVIDENCE", matches = "true")
class RedisGuardedSubmitIntegrationTest {

    @Autowired RedisGuardedPracticeSubmitter guardedSubmitter;
    @Autowired PracticeService practiceService;
    @Autowired BankService bankService;
    @Autowired UserAccountRepository users;

    @Test
    void redisLockSerializesConcurrentSubmitsOnTheSameSession() throws Exception {
        ApiTokenFilter.AuthPrincipal admin = principal("admin", Role.ADMIN);
        ApiTokenFilter.AuthPrincipal student = principal("student", Role.STUDENT);
        PracticeSession session = newSession(admin, student, "Lock Serialize");

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    return "OK:" + guardedSubmitter.submit(student, session.getId(), "guarded-key").totalScore();
                } catch (ApiException rejected) {
                    return "REJECTED:" + rejected.code();
                }
            }));
        }
        ready.await();
        start.countDown();

        List<String> outcomes = new ArrayList<>();
        for (Future<String> future : futures) {
            outcomes.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        long ok = outcomes.stream().filter(value -> value.startsWith("OK")).count();
        long rejected = outcomes.stream().filter(value -> value.startsWith("REJECTED")).count();
        System.out.println("[evidence] 加锁并发提交结果 = " + outcomes);

        assertEquals(1, ok, "同一时刻只允许一个提交持有锁，实际: " + outcomes);
        assertEquals(threads - 1, rejected, "其余并发提交必须被显式拒绝，实际: " + outcomes);
    }

    @Test
    void databaseRowLockStillGuardsCorrectnessWhenRedisLockIsBypassed() throws Exception {
        ApiTokenFilter.AuthPrincipal admin = principal("admin", Role.ADMIN);
        ApiTokenFilter.AuthPrincipal student = principal("student", Role.STUDENT);
        PracticeSession session = newSession(admin, student, "Lock Bypassed");

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                // 故意绕过 Redis 锁：模拟锁因 GC 停顿 / TTL 到期 / 主从切换而失效
                return String.valueOf(practiceService.submit(student, session.getId(), "bypass-key").totalScore());
            }));
        }
        ready.await();
        start.countDown();

        List<String> scores = new ArrayList<>();
        for (Future<String> future : futures) {
            scores.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        long distinct = scores.stream().distinct().count();
        System.out.println("[evidence] 绕过锁的并发提交得分 = " + scores);
        assertEquals(1, distinct,
                "即使分布式锁完全失效，数据库行锁 + 幂等键也必须给出一致结果，实际: " + scores);
    }

    private PracticeSession newSession(ApiTokenFilter.AuthPrincipal admin, ApiTokenFilter.AuthPrincipal student,
                                       String title) {
        QuestionBank bank = bankService.createBank(admin, title + " Bank", null);
        PaperVersion paper = bankService.createDraft(admin, bank.getId(), title + " Paper",
                List.of(new BankController.QuestionInput("1+1=?", QuestionType.SINGLE,
                        List.of("A", "B"), List.of("A"), 1, null)));
        bankService.publish(admin, paper.getId());
        return practiceService.create(student, paper.getId());
    }

    private ApiTokenFilter.AuthPrincipal principal(String username, Role role) {
        var user = users.findByUsername(username).orElseThrow();
        return new ApiTokenFilter.AuthPrincipal(user.getId(), username, role);
    }
}
