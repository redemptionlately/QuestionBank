package com.allen.questionbank.common;

import com.allen.questionbank.entity.PaperVersion;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;
import java.util.List;

@Configuration
public class CacheConfig {
    @Bean
    ExpiringCache<String, List<PaperVersion>> localCache() { return new ExpiringCache<>(Duration.ofMinutes(2)); }
}
