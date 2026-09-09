package com.allen.cloud.auth;

import java.util.Optional;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 认证接口。
 *
 * <p>{@code /internal/token/verify} 是给网关与内部服务用的，按约定不对外暴露
 * （网关只把 /api/** 暴露出去，/internal/** 没有对应路由，外部打不到）。
 * 这是"内部接口靠网络边界保护"而非靠鉴权——在集群内部全通的前提下，
 * 更稳的做法还要加上服务间 mTLS 或内部 token，属于未接入项，见覆盖度文档。
 */
@RestController
public class AuthController {

    private final UserAccountRepository users;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthController(UserAccountRepository users, TokenService tokenService) {
        this.users = users;
        this.tokenService = tokenService;
    }

    public record LoginRequest(@NotBlank(message = "username 不能为空") String username,
                               @NotBlank(message = "password 不能为空") String password) {
    }

    public record LoginResponse(String token, Long userId, String username, String role, long expiresInMillis) {
    }

    public record VerifyResponse(Long userId, String username, String role) {
    }

    @PostMapping("/api/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        UserAccount user = users.findByUsername(request.username())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // 故意不区分"用户不存在"与"密码错误"，避免账号枚举
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        long ttl = 3_600_000L;
        return new LoginResponse(tokenService.issue(user), user.getId(), user.getUsername(),
                user.getRole().name(), ttl);
    }

    @GetMapping("/internal/token/verify")
    public ResponseEntity<VerifyResponse> verify(@RequestHeader("Authorization") String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Optional<TokenService.Principal> principal = tokenService.verify(authorization.substring(7));
        return principal
                .map(p -> ResponseEntity.ok(new VerifyResponse(p.userId(), p.username(), p.role())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }
}
