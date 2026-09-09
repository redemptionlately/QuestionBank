package com.allen.cloud.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 无状态 token：{@code base64url(payload).base64url(HMAC-SHA256(payload))}。
 *
 * <p>为什么服务间要用"签名 token"而不是"每次都来 auth 查一次 session"：
 * 无状态 token 让网关验签不需要访问共享存储，auth 短暂抖动时已签发的 token 仍可用；
 * 代价是吊销困难——所以有效期设得短，并且<b>网关每次请求都回来验签</b>，
 * 服务端握有最终否决权，这不违反无状态（验签无副作用、不写库）。
 *
 * <p>这里刻意不用 JWT 库，把签名与验签写清楚：面试时能说清
 * "签名防什么（篡改）、不防什么（泄露）"比会调一个 API 重要。
 */
@Service
public class TokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final long ttlMillis;

    public TokenService(@Value("${auth.token.secret}") String secret,
                        @Value("${auth.token.ttl-millis:3600000}") long ttlMillis) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlMillis = ttlMillis;
    }

    public String issue(UserAccount user) {
        // 载荷用 "|" 分隔且没有转义机制：username 含 "|" 会让 verify 端 split 出 >4 段，
        // 这个用户签出的 token 永远验不过。约束是"任何注册/播种入口必须拒绝含 | 的
        // username"；这里在签发点兜底快速失败——宁可让坏账号在创建后第一次登录时
        // 显式报错，也不静默签出一个注定作废的 token（当前无注册接口，播种用户名是常量）。
        if (user.getUsername().indexOf('|') >= 0) {
            throw new IllegalArgumentException("username 不能包含 '|'（token 载荷分隔符）");
        }
        long expiresAt = System.currentTimeMillis() + ttlMillis;
        String payload = user.getId() + "|" + user.getUsername() + "|" + user.getRole().name() + "|" + expiresAt;
        return encode(payload) + "." + sign(payload);
    }

    /**
     * 验签并返回身份；签名不符、格式不对、已过期一律返回 empty。
     * 注意：验签失败的原因不区分返回给调用方，避免给攻击者提供"签名错 vs 过期"的探测信号。
     */
    public Optional<Principal> verify(String token) {
        if (token == null || token.indexOf('.') < 0) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        String payloadPart = token.substring(0, dot);
        String signaturePart = token.substring(dot + 1);

        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(payloadPart), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!constantTimeEquals(signaturePart, sign(payload))) {
            return Optional.empty();
        }

        String[] parts = payload.split("\\|");
        if (parts.length != 4) {
            return Optional.empty();
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (System.currentTimeMillis() > expiresAt) {
            return Optional.empty();
        }
        return Optional.of(new Principal(Long.parseLong(parts[0]), parts[1], parts[2]));
    }

    public record Principal(Long userId, String username, String role) {
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 签名失败", e);
        }
    }

    private static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 定长比较，避免通过响应时间差逐字节猜签名 */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    public Instant expiryOfNow() {
        return Instant.now().plusMillis(ttlMillis);
    }
}
