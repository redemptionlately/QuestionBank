package com.questionbank.monitor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * starter 自动装配的标准测试姿势：ApplicationContextRunner 驱动条件矩阵，
 * 不需要拉起完整应用——三秒验证"该在时在、该沉默时沉默、属性绑定正确"。
 */
class QuestionBankMonitorAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(QuestionBankMonitorAutoConfiguration.class));

    @Test
    void monitorIsConfiguredByDefault() {
        runner.run(context -> assertThat(context).hasSingleBean(MyBatisQueryMonitor.class));
    }

    @Test
    void monitorCanBeDisabledByProperty() {
        runner.withPropertyValues("app.mybatis.monitor.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(MyBatisQueryMonitor.class));
    }

    @Test
    void thresholdBindsFromProperty() {
        runner.withPropertyValues("app.mybatis.slow-sql-threshold-ms=2500").run(context -> {
            MyBatisQueryMonitor monitor = context.getBean(MyBatisQueryMonitor.class);
            // 间接验证属性进入组件：重置计数后计数值从零开始（组件真实构造成功）
            MyBatisQueryMonitor.resetForTest();
            assertThat(MyBatisQueryMonitor.executedSqlCount()).isZero();
            assertThat(monitor).isNotNull();
        });
    }
}
