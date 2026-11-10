package com.edrdog.apiservice.auth.web;

import com.edrdog.apiservice.auth.AuthResult;

/** mustChangePassword 가 true 면 패스워드를 바꾸기 전에는 변경·로그아웃·내 정보 말고는 403(code=password_change_required)이다. */
public record AuthResponse(String token, Long userId, Long tenantId, String email, String role,
                           boolean mustChangePassword) {

    public static AuthResponse from(AuthResult r) {
        return new AuthResponse(r.token(), r.userId(), r.tenantId(), r.email(), r.role(), r.mustChangePassword());
    }
}
