package com.edrdog.apiservice.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.edrdog.apiservice.security.Tokens;

import java.time.Duration;
import java.time.Instant;

/**
 * 세션. opaque 랜덤 토큰의 SHA-256 해시를 PK 로 저장하고(KISA BF-03, 원문은 DB 에 없다), 로그아웃·만료·유휴·중복 접속 시 삭제로 무효화한다.
 * 마지막 사용 시각은 유휴 종료(BF-12.1)가 본다.
 */
@Entity
@Table(name = "sessions")
public class UserSession {

    /** 마지막 사용 시각을 갱신하는 최소 간격. 유휴 종료 시각은 이만큼(1분) 어긋날 수 있다. */
    public static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    /** 컬럼 이름은 옛 평문 토큰 때와 같다(기본키라 바꿀 수 없다). 값은 해시다. */
    @Id
    @Column(name = "token")
    private String tokenHash;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column
    private Instant lastUsedAt;

    protected UserSession() {
    }

    private UserSession(String tokenHash, Long userId, Long tenantId, Instant expiresAt, Instant lastUsedAt) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.tenantId = tenantId;
        this.expiresAt = expiresAt;
        this.lastUsedAt = lastUsedAt;
    }

    /** 새 세션. token 은 클라이언트에 준 원문이고, 저장하는 것은 그 해시다. */
    public static UserSession of(String token, Long userId, Long tenantId, Instant expiresAt) {
        return new UserSession(Tokens.hash(token), userId, tenantId, expiresAt, Instant.now());
    }

    /** 마지막 사용 시각을 지정해 만든다(유휴 종료 시험용). */
    public static UserSession of(String token, Long userId, Long tenantId, Instant expiresAt, Instant lastUsedAt) {
        return new UserSession(Tokens.hash(token), userId, tenantId, expiresAt, lastUsedAt);
    }

    /** 주어진 시각 기준으로 만료됐는지(경계 포함: expiresAt 도달 시 만료). */
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** 마지막 사용 이후 idle 이 지났는지(경계 포함). 시각이 없는 행은 유휴로 보지 않는다. */
    public boolean isIdle(Instant now, Duration idle) {
        return lastUsedAt != null && !now.isBefore(lastUsedAt.plus(idle));
    }

    /** 사용 시각을 요청마다 쓰면 읽기 요청이 전부 DB 쓰기가 된다. 이 간격 이상 지났을 때만 갱신한다. */
    public boolean needsTouch(Instant now) {
        return lastUsedAt == null || !now.isBefore(lastUsedAt.plus(TOUCH_INTERVAL));
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
