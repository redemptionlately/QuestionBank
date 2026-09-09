package com.allen.questionbank;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring 声明式事务的传播行为与代理边界验证。
 * 关键点：REQUIRES_NEW 挂起外层事务独立提交；@Transactional 只在经过代理的调用上生效。
 */
@SpringBootTest
@ActiveProfiles("test")
class TransactionPropagationIntegrationTest {

    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired com.allen.questionbank.bank.BankService bankService;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM user_account WHERE username = 'TX-INNER'");
        jdbcTemplate.update("DELETE FROM question_bank WHERE name = 'TX-OUTER'");
    }

    @Test
    void requiresNewCommitsIndependentlyWhenOuterTransactionRollsBack() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        outer.execute(status -> {
            jdbcTemplate.update("INSERT INTO question_bank (owner_id, name, description, status) "
                    + "VALUES (1, 'TX-OUTER', NULL, 'ACTIVE')");
            inner.execute(innerStatus -> {
                jdbcTemplate.update("INSERT INTO user_account (username, password_hash, role, enabled) "
                        + "VALUES ('TX-INNER', 'not-a-real-hash', 'STUDENT', TRUE)");
                return null;
            });
            status.setRollbackOnly();
            return null;
        });

        assertEquals(0, count("SELECT COUNT(*) FROM question_bank WHERE name = 'TX-OUTER'"),
                "外层事务回滚后，外层插入的数据必须不存在");
        assertEquals(1, count("SELECT COUNT(*) FROM user_account WHERE username = 'TX-INNER'"),
                "REQUIRES_NEW 的内层事务独立提交，外层回滚不能把它一起撤掉");
        System.out.println("[evidence] REQUIRES_NEW：外层回滚=已撤销，内层=保留");
    }

    @Test
    void transactionalServiceIsProxiedSoDeclarativeTransactionCanApply() {
        assertTrue(AopUtils.isAopProxy(bankService),
                "带 @Transactional 的 service 必须被 AOP 代理，否则声明式事务不生效");
        System.out.println("[evidence] BankService 代理类型: " + bankService.getClass().getName());
    }

    private int count(String sql) {
        Integer result = jdbcTemplate.queryForObject(sql, Integer.class);
        return result == null ? 0 : result;
    }
}
