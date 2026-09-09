package com.allen.cloud.auth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 演示数据：两个账号，教师用于出题发布，学生用于练习。
 * 幂等——已存在则跳过，重跑证据脚本不会产生重复数据。
 */
@Component
public class DemoUserInitializer implements ApplicationRunner {

    private final UserAccountRepository users;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public DemoUserInitializer(UserAccountRepository users) {
        this.users = users;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed("teacher", "teacher123", Role.TEACHER);
        seed("student", "student123", Role.STUDENT);
    }

    private void seed(String username, String rawPassword, Role role) {
        if (users.findByUsername(username).isEmpty()) {
            users.save(new UserAccount(username, encoder.encode(rawPassword), role));
        }
    }
}
