package com.allen.questionbank.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TokenService {
    private final Duration ttl;
    private final Map<String, TokenRecord> tokens = new ConcurrentHashMap<>();

    public TokenService(@Value("${app.token-ttl:PT8H}") Duration ttl) {
        this.ttl = ttl;
    }

    public String issue(UserAccount user) {
        String token = UUID.randomUUID().toString();
        tokens.put(token, new TokenRecord(user.getId(), user.getUsername(), user.getRole(), Instant.now().plus(ttl)));
        return token;
    }

    public TokenRecord resolve(String token) {
        TokenRecord record = tokens.get(token);
        if (record == null || record.expiresAt().isBefore(Instant.now())) {
            if (record != null) tokens.remove(token);
            return null;
        }
        return record;
    }

    public record TokenRecord(Long userId, String username, Role role, Instant expiresAt) {}
}
