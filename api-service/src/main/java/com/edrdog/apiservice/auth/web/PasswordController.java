package com.edrdog.apiservice.auth.web;

import com.edrdog.apiservice.access.AccessInterceptor;
import com.edrdog.apiservice.access.RequiresRole;
import com.edrdog.apiservice.access.Role;
import com.edrdog.apiservice.audit.AuditEntry;
import com.edrdog.apiservice.audit.AuditResult;
import com.edrdog.apiservice.audit.AuditService;
import com.edrdog.apiservice.audit.AuditType;
import com.edrdog.apiservice.auth.AuthException;
import com.edrdog.apiservice.auth.AuthService;
import com.edrdog.apiservice.auth.Principal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 패스워드 변경(KISA BF-01.2). 기본 패스워드를 바꿔야 하는 계정(BF-08)도 부를 수 있다.
 * 성공과 실패 모두 감사기록 password_change 로 남긴다. 패스워드 값은 어디에도 남기지 않는다.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "auth", description = "회원가입/로그인/로그아웃 (세션 토큰)")
public class PasswordController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService auth;
    private final AuditService audit;

    public PasswordController(AuthService auth, AuditService audit) {
        this.auth = auth;
        this.audit = audit;
    }

    @Operation(summary = "패스워드 변경",
            description = "현재 패스워드를 확인하고 새 패스워드로 바꾼다. 새 패스워드는 9자 이상, 숫자·영대문자·영소문자·특수문자 각 1개 이상, "
                    + "계정과 같거나 같은 문자 3번 이상 연속이나 키보드 연속 3개 이상이면 안 되고 직전 패스워드는 재사용할 수 없다(400). "
                    + "성공하면 이 세션 말고 그 유저의 다른 세션이 끊긴다.")
    @RequiresRole(value = Role.VIEWER, allowPasswordChange = true)
    @PostMapping("/password")
    public ResponseEntity<Void> change(@RequestAttribute(AccessInterceptor.PRINCIPAL) Principal who,
                                       @RequestHeader(name = "Authorization", required = false) String authorization,
                                       @RequestBody PasswordChangeRequest req, HttpServletRequest http) {
        String token = bearerToken(authorization);
        try {
            auth.changePassword(who.userId(), token, req.currentPassword(), req.newPassword());
        } catch (AuthException e) {
            audit.record(AuditEntry.user(who, AuditType.PASSWORD_CHANGE, who.email(), AuditResult.FAILURE,
                    e.getMessage(), http.getRemoteAddr()));
            throw e;
        }
        audit.record(AuditEntry.user(who, AuditType.PASSWORD_CHANGE, who.email(), AuditResult.SUCCESS,
                null, http.getRemoteAddr()));
        return ResponseEntity.noContent().build();
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return authorization.substring(BEARER_PREFIX.length()).trim();
    }
}
