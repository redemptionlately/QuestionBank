package com.allen.questionbank.bank;

import com.allen.questionbank.common.ExpiringCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * 题库域的本地缓存装配：泛型绑死 PaperResponse，所以归属 bank 域而不是 common——
 * 通用组件（ExpiringCache）留在 common，业务参数化发生在使用侧（ArchUnit 抓出的架构债）。
 */
@Configuration
public class BankCacheConfig {
    @Bean
    ExpiringCache<String, List<PaperResponse>> localCache() {
        return new ExpiringCache<>(Duration.ofMinutes(2));
    }
}
