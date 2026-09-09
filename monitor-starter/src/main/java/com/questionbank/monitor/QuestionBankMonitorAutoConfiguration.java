package com.questionbank.monitor;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 自研 starter 的自动装配入口：
 * - @AutoConfiguration + META-INF/spring/...AutoConfiguration.imports 注册（Boot 2.7+ 机制）
 * - @ConditionalOnClass：classpath 没有 MyBatis 就整个沉默（starter 可被任意 Spring Boot 项目引入）
 * - @ConditionalOnProperty：app.mybatis.monitor.enabled=false 一键关闭（matchIfMissing 默认开）
 */
@AutoConfiguration
@ConditionalOnClass(StatementHandler.class)
@ConditionalOnProperty(prefix = "app.mybatis.monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MyBatisQueryMonitor.MonitorProperties.class)
public class QuestionBankMonitorAutoConfiguration {

    @Bean
    public MyBatisQueryMonitor myBatisQueryMonitor(MyBatisQueryMonitor.MonitorProperties properties) {
        return new MyBatisQueryMonitor(properties);
    }
}
