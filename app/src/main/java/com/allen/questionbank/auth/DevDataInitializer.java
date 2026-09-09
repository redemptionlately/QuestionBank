package com.allen.questionbank.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 演示种子数据：admin/admin123、student/student123。
 *
 * <p>这是弱密码种子，只能活在开发/证据环境——全部证据与集成测试都依赖这两个账号，
 * 所以默认开启（matchIfMissing=true），不改动任何现有证据链。真正的生产部署
 * 必须显式配置 {@code app.dev-data.enabled=false} 把它关掉；把"默认开着"换成
 * "显式关闭"是这个开关存在的全部意义：安全默认值由部署配置决定，而不是由代码里
 * 有没有这段代码决定。
 */
@Configuration
@ConditionalOnProperty(name = "app.dev-data.enabled", havingValue = "true", matchIfMissing = true)
public class DevDataInitializer {
    @Bean
    CommandLineRunner seedUsers(UserAccountRepository users, PasswordEncoder encoder) {
        return args -> {
            if (users.findByUsername("admin").isEmpty()) {
                users.save(new UserAccount("admin", encoder.encode("admin123"), Role.ADMIN));
            }
            if (users.findByUsername("student").isEmpty()) {
                users.save(new UserAccount("student", encoder.encode("student123"), Role.STUDENT));
            }
        };
    }
}
