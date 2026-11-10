package com.edrdog.apiservice.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 유저. "user"는 SQL 예약어라 클래스명은 AppUser, 테이블명은 users 로 둔다.
 */
@Entity
@Table(name = "users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private Instant createdAt;

    @Column
    private String slackWebhookUrl;   // 유저 개인 알림 목적지 (자기 소유 host 탐지 알림 수신)

    // 연속 로그인 실패 횟수와 잠금 해제 시각(KISA BF-02). 증가·잠금은 UserRepository 의 UPDATE 쿼리가 원자적으로 한다.
    @Column(nullable = false, columnDefinition = "int default 0")
    private int failedLogins;

    @Column
    private Instant lockedUntil;

    // 기본 패스워드 등 바꿔야 하는 패스워드를 쓰는 계정(KISA BF-08). 바꾸기 전에는 변경·로그아웃·내 정보만 된다.
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean mustChangePassword;

    // DEMO_SEED 시드가 세우는 데모 계정 표시. 이 계정은 공개 데모가 깨지지 않게 기본 패스워드 강제 변경, 중복 접속 차단,
    // 연속 실패 잠금, 패스워드 변경에서 빠진다(ADR 031, 032). 일반 계정에는 이 표시를 세우는 길이 시드 말고 없다.
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean demoAccount;

    protected AppUser() {
    }

    private AppUser(String email, String passwordHash, Long tenantId, String role, Instant createdAt) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.tenantId = tenantId;
        this.role = role;
        this.createdAt = createdAt;
    }

    public static AppUser of(String email, String passwordHash, Long tenantId, String role, Instant createdAt) {
        return new AppUser(email, passwordHash, tenantId, role, createdAt);
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getSlackWebhookUrl() {
        return slackWebhookUrl;
    }

    public void updateWebhook(String url) {
        this.slackWebhookUrl = url;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /** 데모 계정은 표시가 켜져 있어도 변경을 강제하지 않는다. */
    public boolean isMustChangePassword() {
        return mustChangePassword && !demoAccount;
    }

    public boolean isDemoAccount() {
        return demoAccount;
    }

    public void markDemoAccount() {
        this.demoAccount = true;
    }

    public void requirePasswordChange() {
        this.mustChangePassword = true;
    }
}
