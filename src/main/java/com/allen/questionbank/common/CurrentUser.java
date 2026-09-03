package com.allen.questionbank.common;

import com.allen.questionbank.auth.ApiTokenFilter;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

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
