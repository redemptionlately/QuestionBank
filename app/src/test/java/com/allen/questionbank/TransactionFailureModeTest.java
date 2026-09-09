package com.allen.questionbank;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Transactional 的"失效"场景变式证据：声明式事务靠 AOP 代理实现，
 * 任何绕过代理或让异常不到达代理的写法都会让回滚失效。
 * 五条路径：代理生效回滚 / 自调用绕过 / 异常被吞 / 受检异常默认提交 / rollbackFor 强制回滚。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TransactionFailureModeTest.TxProbeConfig.class)
class TransactionFailureModeTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TxProbe probe;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM question_bank WHERE name LIKE 'TXF-%'");
    }

    @TestConfiguration
    static class TxProbeConfig {
        @Bean
        TxProbe txProbe(JdbcTemplate jdbc) {
            return new TxProbe(jdbc);
        }
    }

    /** 探针：最小的 @Transactional 写入者，专用于演示失效路径。 */
    static class TxProbe {
        private final JdbcTemplate jdbc;

        TxProbe(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        private void insert(String name) {
            jdbc.update("INSERT INTO question_bank (owner_id, name, description, status) "
                    + "VALUES (1, ?, NULL, 'ACTIVE')", name);
        }

        /** 正常路径：从外部经代理调用，RuntimeException 传播到代理 → 回滚。 */
        @Transactional
        public void writeThenRuntimeThrow(String name) {
            insert(name);
            throw new IllegalStateException("boom: " + name);
        }

        /** 失效路径①：内部 this 调用不走代理，@Transactional 完全不生效。 */
        public void viaSelfInvocation(String name) {
            this.writeThenRuntimeThrow(name);
        }

        /** 失效路径②：异常在事务方法内部被吞，代理永远看不到异常 → 照常提交。 */
        @Transactional
        public void writeAndSwallow(String name) {
            insert(name);
            try {
                throw new IllegalStateException("swallowed: " + name);
            } catch (Exception e) {
                // 吞掉 = 告诉代理"一切正常"
            }
        }

        /** 失效路径③：受检异常默认不回滚（默认只回滚 RuntimeException/Error）。 */
        @Transactional
        public void writeThenChecked(String name) throws Exception {
            insert(name);
            throw new Exception("checked: " + name);
        }

        /** 正确姿势：rollbackFor 把受检异常纳入回滚范围。 */
        @Transactional(rollbackFor = Exception.class)
        public void writeThenCheckedWithRollbackFor(String name) throws Exception {
            insert(name);
            throw new Exception("checked-rollback: " + name);
        }

        public boolean isProxy() {
            // 注意：this 永远是目标对象而非代理——所以这个方法恒为 false，这正是自调用失效的本质
            return AopUtils.isAopProxy(this);
        }
    }

    @Test
    void runtimeExceptionThroughProxyRollsBack() {
        assertThrows(IllegalStateException.class, () -> probe.writeThenRuntimeThrow("TXF-PROXY"));
        assertEquals(0, count("TXF-PROXY"),
                "异常经代理传播 → 事务回滚，数据不存在");
        System.out.println("[evidence] 路径①基准：RuntimeException 经代理传播 = 回滚");
    }

    @Test
    void selfInvocationBypassesProxyAndTransaction() {
        // 外部注入的引用是代理；方法内部的 this 是目标对象——同一实例，两种身份
        assertTrue(AopUtils.isAopProxy(probe), "注入的探针引用必须是代理（事务由它施加）");
        assertTrue(probe.isProxy() == false,
                "方法内部 this 恒为目标对象而非代理——这就是自调用失效的本质");
        // 经代理进入 viaSelfInvocation，但内部 this.writeThenRuntimeThrow() 直达目标对象：
        // 没有事务包裹，insert 逐句自动提交，异常抛回也无法回滚已提交的数据
        assertThrows(IllegalStateException.class, () -> probe.viaSelfInvocation("TXF-SELF"));
        assertEquals(1, count("TXF-SELF"),
                "自调用绕过代理 → @Transactional 不生效 → 数据已逐句自动提交，回滚不可能发生");
        System.out.println("[evidence] 自调用：代理可见性丢失，写入被自动提交保留（回滚失效）");
    }

    @Test
    void swallowedExceptionStillCommits() {
        probe.writeAndSwallow("TXF-SWALLOW");
        assertEquals(1, count("TXF-SWALLOW"),
                "异常被 catch 吞掉，代理看不到异常 → 事务正常提交");
        System.out.println("[evidence] 吞异常：事务方法正常返回 = 提交，吞异常等于宣布成功");
    }

    @Test
    void checkedExceptionDoesNotRollbackByDefault() {
        assertThrows(Exception.class, () -> probe.writeThenChecked("TXF-CHECKED"));
        assertEquals(1, count("TXF-CHECKED"),
                "受检异常默认不在回滚范围（默认只有 RuntimeException/Error）→ 数据已提交");
        System.out.println("[evidence] 受检异常：默认提交（这是 @Transactional 最常见的误用之一）");
    }

    @Test
    void rollbackForBringsCheckedExceptionsIntoRollbackScope() {
        assertThrows(Exception.class, () -> probe.writeThenCheckedWithRollbackFor("TXF-ROLLBACKFOR"));
        assertEquals(0, count("TXF-ROLLBACKFOR"),
                "rollbackFor = Exception.class 后，受检异常也触发回滚");
        System.out.println("[evidence] rollbackFor：受检异常纳入回滚范围 = 数据不存在");
    }

    private int count(String name) {
        Integer result = jdbc.queryForObject(
                "SELECT COUNT(*) FROM question_bank WHERE name = ?", Integer.class, name);
        return result == null ? 0 : result;
    }
}
