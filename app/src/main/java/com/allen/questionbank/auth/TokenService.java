package com.allen.questionbank.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录令牌的签发与解析。
 *
 * <p>两种存储：
 * <ul>
 *   <li>{@code app.token-store=local}（默认）——JVM 内存 Map。单机开发够用，
 *       <b>但多实例部署时 token 只在签发它的那个实例上存在</b>，其它实例一律 401。
 *       这是 2026-09-09 多实例证据实测抓出的真缺陷（见 scripts/multi-instance-evidence.sh）。</li>
 *   <li>{@code app.token-store=redis}——写 Redis 并按 TTL 自动过期，多实例共享，水平扩容的前提。
 *       与限流后端（{@code app.rate-limit.backend}）同一套开关思路：本地零依赖可跑，上生产切共享存储。</li>
 * </ul>
 * Redis 不可用时（容器里没有 StringRedisTemplate）会打 WARN 并退化成本地存储——
 * 宁可功能降级，也不要让所有登录在启动期就炸掉。
 */
@Service
public class TokenService {
    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    private static final String KEY_PREFIX = "qb:token:";
    private static final String SEP = "|";

    private final Duration ttl;
    private final boolean redisStore;
    /** 仅 redis 存储模式下非 null。 */
    private final StringRedisTemplate redis;
    private final Map<String, TokenRecord> tokens = new ConcurrentHashMap<>();

    public TokenService(@Value("${app.token-ttl:PT8H}") Duration ttl,
                        @Value("${app.token-store:local}") String store,
                        ObjectProvider<StringRedisTemplate> redisProvider) {
        this.ttl = ttl;
        this.redisStore = "redis".equalsIgnoreCase(store);
        this.redis = redisStore ? redisProvider.getIfAvailable() : null;
        if (redisStore && this.redis == null) {
            log.warn("app.token-store=redis 但容器里没有 StringRedisTemplate，已退化为本地存储——多实例下 token 不共享");
        }
    }

    public String issue(UserAccount user) {
        String token = UUID.randomUUID().toString();
        TokenRecord record = new TokenRecord(user.getId(), user.getUsername(), user.getRole(), Instant.now().plus(ttl));
        if (redis != null) {
            redis.opsForValue().set(KEY_PREFIX + token, encode(record), ttl);
        } else {
            tokens.put(token, record);
        }
        return token;
    }

    public TokenRecord resolve(String token) {
        if (token == null || token.isBlank()) return null;
        if (redis != null) {
            String raw = redis.opsForValue().get(KEY_PREFIX + token);
            return raw == null ? null : decode(raw);
        }
        TokenRecord record = tokens.get(token);
        if (record == null || record.expiresAt().isBefore(Instant.now())) {
            if (record != null) tokens.remove(token);
            return null;
        }
        return record;
    }

    /** 判定当前实例用的是哪种存储，供运维自检（/actuator 或启动日志核对）。 */
    public String storeType() {
        return redis != null ? "redis" : "local";
    }

    private static String encode(TokenRecord record) {
        return record.userId() + SEP + record.username() + SEP + record.role().name() + SEP + record.expiresAt().toEpochMilli();
    }

    private static TokenRecord decode(String raw) {
        String[] parts = raw.split("\\" + SEP);
        if (parts.length != 4) return null;
        Instant expiresAt = Instant.ofEpochMilli(Long.parseLong(parts[3]));
        // Redis TTL 才是过期的主力，这里再兜一道：时钟漂移或 TTL 未生效时也不会放行过期 token
        if (expiresAt.isBefore(Instant.now())) return null;
        return new TokenRecord(Long.parseLong(parts[0]), parts[1], Role.valueOf(parts[2]), expiresAt);
    }

    public record TokenRecord(Long userId, String username, Role role, Instant expiresAt) {}
}
