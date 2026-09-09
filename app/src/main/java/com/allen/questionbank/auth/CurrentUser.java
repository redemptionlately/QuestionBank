package com.allen.questionbank.auth;

import com.allen.questionbank.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前认证用户上下文（归 auth 域）：语义上就是认证的一部分，放 common 会造成
 * 基础设施包反向依赖业务域——ArchUnit 规则上线后抓出的第一处真实架构债，据此搬家。
 */
public final class CurrentUser {
    private CurrentUser() {}

    public static ApiTokenFilter.AuthPrincipal require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof ApiTokenFilter.AuthPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "需要登录");
        }
        return principal;
    }
}
