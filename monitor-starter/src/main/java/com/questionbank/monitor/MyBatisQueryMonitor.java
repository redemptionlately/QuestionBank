package com.questionbank.monitor;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.DefaultObjectFactory;
import org.apache.ibatis.reflection.wrapper.DefaultObjectWrapperFactory;
import org.apache.ibatis.session.ResultHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MyBatis 插件机制（Interceptor）：慢 SQL 监控 + 真实执行计数。
 *
 * <p>为什么拦 StatementHandler 而不是 Executor：
 * 二级缓存命中时 CachingExecutor 会在缓存层短路返回，StatementHandler 根本不会被调到——
 * 所以这一层的计数是"真实打到数据库的语句数"，可以直接用来验证缓存命中率。</p>
 *
 * <p>作为 starter 的被装配对象：由 {@code QuestionBankMonitorAutoConfiguration} 条件装配，
 * 阈值经 {@link MonitorProperties} 批量绑定（不再用散装 @Value）。</p>
 */
@Intercepts({
        @Signature(type = StatementHandler.class, method = "query",
                args = {Statement.class, ResultHandler.class}),
        @Signature(type = StatementHandler.class, method = "update",
                args = {Statement.class})
})
public class MyBatisQueryMonitor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(MyBatisQueryMonitor.class);
    private static final AtomicLong EXECUTED_SQL = new AtomicLong();

    private final long slowSqlThresholdMs;

    public MyBatisQueryMonitor(MonitorProperties properties) {
        this.slowSqlThresholdMs = properties.getSlowSqlThresholdMs();
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        long startNanos = System.nanoTime();
        try {
            return invocation.proceed();
        } finally {
            long tookMs = (System.nanoTime() - startNanos) / 1_000_000;
            EXECUTED_SQL.incrementAndGet();
            if (tookMs >= slowSqlThresholdMs) {
                log.warn("[mybatis-slow-sql] {}ms >= {}ms, mapper={}, sql={}",
                        tookMs, slowSqlThresholdMs, statementIdOf(invocation.getTarget()), sqlOf(invocation.getTarget()));
            }
        }
    }

    @Override
    public Object plugin(Object target) {
        return target instanceof StatementHandler ? Plugin.wrap(target, this) : target;
    }

    @Override
    public void setProperties(Properties properties) { /* 阈值走 ConfigurationProperties 绑定 */ }

    /** 真实打到数据库的 SQL 累计条数（启动至今）。 */
    public static long executedSqlCount() { return EXECUTED_SQL.get(); }

    /** 测试隔离用：重置计数（生产代码不得调用）。 */
    public static void resetForTest() { EXECUTED_SQL.set(0); }

    private String statementIdOf(Object statementHandler) {
        try {
            MetaObject meta = metaOf(statementHandler);
            Object mappedStatement = meta.hasGetter("delegate")
                    ? meta.getValue("delegate.mappedStatement")
                    : meta.getValue("mappedStatement");
            return ((MappedStatement) mappedStatement).getId();
        } catch (Exception exception) {
            return "unknown";
        }
    }

    private String sqlOf(Object statementHandler) {
        try {
            MetaObject meta = metaOf(statementHandler);
            Object boundSql = meta.hasGetter("delegate")
                    ? meta.getValue("delegate.boundSql")
                    : meta.getValue("boundSql");
            String sql = String.valueOf(metaOf(boundSql).getValue("sql"));
            return sql.length() > 200 ? sql.substring(0, 200) + "..." : sql;
        } catch (Exception exception) {
            return "unknown";
        }
    }

    /** 新版 MyBatis 移除了 SystemMetaObject 单参 forObject，显式给默认三工厂。 */
    private MetaObject metaOf(Object target) {
        return MetaObject.forObject(target, new DefaultObjectFactory(),
                new DefaultObjectWrapperFactory(), new DefaultReflectorFactory());
    }

    /** starter 属性（前缀 app.mybatis，向后兼容原 yml 配置键）。 */
    @ConfigurationProperties(prefix = "app.mybatis")
    public static class MonitorProperties {
        /** 慢 SQL 阈值（毫秒），超过即打 warn 日志。 */
        private long slowSqlThresholdMs = 1000;

        public long getSlowSqlThresholdMs() { return slowSqlThresholdMs; }
        public void setSlowSqlThresholdMs(long slowSqlThresholdMs) { this.slowSqlThresholdMs = slowSqlThresholdMs; }
    }
}
