package com.edrdog.apiservice.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 조직(tenant)별 보안정책(KISA BF-05.1). 행이 없는 조직은 기본값을 쓴다. 범위는 KISA BF-02 기준(5회 이하, 5분 이상)을 따른다.
 */
@Entity
@Table(name = "security_policies")
public class SecurityPolicy {

    public static final int DEFAULT_MAX_LOGIN_FAILURES = 5;
    public static final int MAX_LOGIN_FAILURES_LIMIT = 5;
    public static final int DEFAULT_LOCK_MINUTES = 5;
    public static final int MIN_LOCK_MINUTES = 5;
    public static final int MAX_LOCK_MINUTES = 1440;
    public static final int DEFAULT_IDLE_MINUTES = 10;
    public static final int MAX_IDLE_MINUTES = 60;

    @Id
    private Long tenantId;

    @Column(nullable = false)
    private int maxLoginFailures;

    @Column(nullable = false)
    private int lockMinutes;

    @Column(nullable = false)
    private int idleMinutes;

    @Column(nullable = false)
    private Instant updatedAt;

    protected SecurityPolicy() {
    }

    public SecurityPolicy(Long tenantId, int maxLoginFailures, int lockMinutes, int idleMinutes, Instant updatedAt) {
        this.tenantId = tenantId;
        this.maxLoginFailures = maxLoginFailures;
        this.lockMinutes = lockMinutes;
        this.idleMinutes = idleMinutes;
        this.updatedAt = updatedAt;
    }

    public static SecurityPolicy defaults(Long tenantId) {
        return new SecurityPolicy(tenantId, DEFAULT_MAX_LOGIN_FAILURES, DEFAULT_LOCK_MINUTES,
                DEFAULT_IDLE_MINUTES, Instant.now());
    }

    /** 범위 밖이면 400. 값은 자르지 않고 거절한다(조용히 바꾸면 관리자가 설정이 적용된 줄 안다). */
    public static void validate(Integer maxLoginFailures, Integer lockMinutes, Integer idleMinutes) {
        if (maxLoginFailures == null || maxLoginFailures < 1 || maxLoginFailures > MAX_LOGIN_FAILURES_LIMIT) {
            throw AuthException.invalidInput("maxLoginFailures 는 1~" + MAX_LOGIN_FAILURES_LIMIT + " 여야 합니다");
        }
        if (lockMinutes == null || lockMinutes < MIN_LOCK_MINUTES || lockMinutes > MAX_LOCK_MINUTES) {
            throw AuthException.invalidInput("lockMinutes 는 " + MIN_LOCK_MINUTES + "~" + MAX_LOCK_MINUTES + " 여야 합니다");
        }
        if (idleMinutes == null || idleMinutes < 1 || idleMinutes > MAX_IDLE_MINUTES) {
            throw AuthException.invalidInput("idleMinutes 는 1~" + MAX_IDLE_MINUTES + " 여야 합니다");
        }
    }

    public Long getTenantId() {
        return tenantId;
    }

    public int getMaxLoginFailures() {
        return maxLoginFailures;
    }

    public int getLockMinutes() {
        return lockMinutes;
    }

    public int getIdleMinutes() {
        return idleMinutes;
    }
}
