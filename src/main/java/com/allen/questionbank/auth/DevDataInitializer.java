package com.allen.questionbank.auth;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
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
