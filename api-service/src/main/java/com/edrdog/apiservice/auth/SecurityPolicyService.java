package com.edrdog.apiservice.auth;

import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 조직별 보안정책 조회·변경. 로그인 잠금(AuthService)과 보안정책 API 가 같은 값을 읽는다.
 */
@Service
public class SecurityPolicyService {

    private final SecurityPolicyRepository policies;

    public SecurityPolicyService(SecurityPolicyRepository policies) {
        this.policies = policies;
    }

    /** 저장된 정책, 없으면 기본값. */
    public SecurityPolicy get(Long tenantId) {
        return policies.findById(tenantId).orElseGet(() -> SecurityPolicy.defaults(tenantId));
    }

    public SecurityPolicy update(Long tenantId, Integer maxLoginFailures, Integer lockMinutes, Integer idleMinutes) {
        SecurityPolicy.validate(maxLoginFailures, lockMinutes, idleMinutes);
        return policies.save(new SecurityPolicy(tenantId, maxLoginFailures, lockMinutes, idleMinutes, Instant.now()));
    }
}
