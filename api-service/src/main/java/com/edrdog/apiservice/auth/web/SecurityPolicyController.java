package com.edrdog.apiservice.auth.web;

import com.edrdog.apiservice.access.AccessInterceptor;
import com.edrdog.apiservice.access.RequiresRole;
import com.edrdog.apiservice.access.Role;
import com.edrdog.apiservice.audit.AuditEntry;
import com.edrdog.apiservice.audit.AuditResult;
import com.edrdog.apiservice.audit.AuditService;
import com.edrdog.apiservice.audit.AuditType;
import com.edrdog.apiservice.auth.Principal;
import com.edrdog.apiservice.auth.SecurityPolicy;
import com.edrdog.apiservice.auth.SecurityPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 조직별 보안정책 설정·조회(KISA BF-05.1). admin 만 쓴다(`@RequiresRole`, 접근통제 관문이 본다). 경로가 /api/auth 아래라 X-API-Key 예외다(ApiKeyPolicy).
 */
@RestController
@RequestMapping("/api/auth/security-policy")
@Tag(name = "auth", description = "회원가입/로그인/로그아웃 (세션 토큰)")
public class SecurityPolicyController {

    private final SecurityPolicyService policies;
    private final AuditService audit;

    public SecurityPolicyController(SecurityPolicyService policies, AuditService audit) {
        this.policies = policies;
        this.audit = audit;
    }

    @Operation(summary = "보안정책 조회", description = "내 조직의 정책. 저장된 적이 없으면 기본값(5회, 5분, 10분). admin 만(아니면 403).")
    @RequiresRole(Role.ADMIN)
    @GetMapping
    public SecurityPolicyResponse get(@RequestAttribute(AccessInterceptor.PRINCIPAL) Principal p) {
        return SecurityPolicyResponse.from(policies.get(p.tenantId()));
    }

    @Operation(summary = "보안정책 변경",
            description = "maxLoginFailures(연속 실패 허용 횟수, 1~5), lockMinutes(잠금 분, 5~1440), idleMinutes(유휴 세션 종료 분, 1~60). "
                    + "셋 다 필요하고 범위 밖이면 400. 바뀐 값은 이전·이후가 감사기록(security_policy_change)으로 남는다.")
    @RequiresRole(Role.ADMIN)
    @PutMapping
    public SecurityPolicyResponse put(@RequestAttribute(AccessInterceptor.PRINCIPAL) Principal p,
                                      @RequestBody SecurityPolicyRequest req, HttpServletRequest http) {
        SecurityPolicy before = policies.get(p.tenantId());
        SecurityPolicy after = policies.update(p.tenantId(), req.maxLoginFailures(), req.lockMinutes(), req.idleMinutes());
        String detail = "maxLoginFailures " + before.getMaxLoginFailures() + "->" + after.getMaxLoginFailures()
                + ", lockMinutes " + before.getLockMinutes() + "->" + after.getLockMinutes()
                + ", idleMinutes " + before.getIdleMinutes() + "->" + after.getIdleMinutes();
        audit.record(AuditEntry.user(p, AuditType.SECURITY_POLICY_CHANGE, "tenant:" + p.tenantId(),
                AuditResult.SUCCESS, detail, http.getRemoteAddr()));
        return SecurityPolicyResponse.from(after);
    }
}
