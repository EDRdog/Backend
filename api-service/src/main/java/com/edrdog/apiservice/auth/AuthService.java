package com.edrdog.apiservice.auth;

import com.edrdog.apiservice.audit.AuditEntry;
import com.edrdog.apiservice.audit.AuditResult;
import com.edrdog.apiservice.audit.AuditService;
import com.edrdog.apiservice.audit.AuditType;
import com.edrdog.apiservice.notify.SlackWebhookClient;
import com.edrdog.apiservice.security.Tokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 회원가입/로그인/로그아웃/토큰검증/패스워드 변경. 비밀번호는 BCrypt, 세션은 opaque 토큰(DB 에는 해시만) 방식.
 * 로그인 잠금, 유휴·중복 세션 종료, 기본 패스워드 강제 변경도 여기서 한다(근거는 ADR 031, 032).
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    // 로그인 실패는 어떤 이유든 이 메시지 하나다(KISA BF-04.2). 이유를 구분해 알려 주면 계정 존재 여부를 캐낼 수 있다.
    private static final String LOGIN_FAILED = "이메일 또는 비밀번호가 올바르지 않습니다";
    private static final String TOKEN_INVALID = "유효하지 않거나 만료된 토큰입니다";

    private final UserRepository users;
    private final TenantRepository tenants;
    private final SessionRepository sessions;
    private final BCryptPasswordEncoder encoder;
    private final SecurityPolicyService policies;
    private final AuditService audit;
    private final SlackWebhookClient slack;
    private final Duration sessionTtl;
    // 없는 계정에도 BCrypt 비교를 한 번 하려고 둔다. 응답 시간으로 계정 존재 여부가 드러나지 않게 한다.
    private final String dummyHash;

    public AuthService(UserRepository users,
                       TenantRepository tenants,
                       SessionRepository sessions,
                       BCryptPasswordEncoder encoder,
                       SecurityPolicyService policies,
                       AuditService audit,
                       SlackWebhookClient slack,
                       @Value("${edrdog.auth.session-ttl-hours:168}") long sessionTtlHours) {
        this.users = users;
        this.tenants = tenants;
        this.sessions = sessions;
        this.encoder = encoder;
        this.policies = policies;
        this.audit = audit;
        this.slack = slack;
        this.sessionTtl = Duration.ofHours(sessionTtlHours);
        this.dummyHash = encoder.encode(Tokens.newToken());
    }

    /** 기동하면 해시 도입 전의 평문 토큰 세션을 지운다. 그 토큰은 이제 쓸 수 없으니(전원 재로그인) 남겨 둘 이유가 없다. */
    @EventListener(ApplicationReadyEvent.class)
    public void purgeLegacySessions() {
        int n = sessions.deleteLegacyPlaintext();
        if (n > 0) {
            log.info("평문 토큰으로 저장된 옛 세션 {}개를 지웠습니다. 해당 유저는 다시 로그인해야 합니다", n);
        }
    }

    // 한 트랜잭션이 아니면 user 생성이 실패했을 때 tenant 만 남아 고아가 된다.
    @Transactional
    public AuthResult signup(String email, String password, String orgName) {
        if (!AuthValidation.validEmail(email)) {
            throw AuthException.invalidInput("이메일 형식이 올바르지 않습니다");
        }
        String weak = AuthValidation.passwordError(password, email);
        if (weak != null) {
            throw AuthException.invalidInput(weak);
        }
        if (users.existsByEmail(email)) {
            throw AuthException.duplicate("이미 사용 중인 이메일입니다");
        }

        Instant now = Instant.now();
        String name = (orgName == null || orgName.isBlank()) ? localPart(email) : orgName;
        try {
            Tenant tenant = tenants.save(Tenant.of(name, now));
            AppUser user = users.save(AppUser.of(email, encoder.encode(password), tenant.getId(), "admin", now));
            return issueSession(user, now);
        } catch (DataIntegrityViolationException e) {
            // existsByEmail 통과 후 동시 요청이 unique 제약을 밟는 경로. 안 잡으면 409 대신 500 이 나간다.
            throw AuthException.duplicate("이미 사용 중인 이메일입니다");
        }
    }

    /**
     * 없는 계정, 틀린 패스워드, 잠긴 계정은 같은 예외·같은 BCrypt 비용으로 끝낸다(BF-04.2).
     * 잠긴 계정은 맞는 패스워드도 거부한다(BF-02.1). 로그인에 성공하면 이 유저의 다른 세션은 끊는다(BF-12.2).
     */
    public AuthResult login(String email, String password) {
        AppUser user = email == null ? null : users.findByEmail(email).orElse(null);
        boolean matches = encoder.matches(password == null ? "" : password,
                user != null ? user.getPasswordHash() : dummyHash);
        Instant now = Instant.now();
        if (user == null || user.isLocked(now)) {
            throw AuthException.unauthorized(LOGIN_FAILED);
        }
        if (!matches) {
            recordFailure(user, now);
            throw AuthException.unauthorized(LOGIN_FAILED);
        }
        users.resetFailures(user.getId());
        // 데모 계정은 여럿이 같이 쓰므로 서로 끊지 않는다
        int ended = user.isDemoAccount() ? 0 : sessions.deleteAllOfUser(user.getId());
        if (ended > 0) {
            audit.record(AuditEntry.user(principal(user), AuditType.SESSION_TERMINATE, user.getEmail(),
                    AuditResult.SUCCESS, "duplicate: 새 로그인으로 기존 세션 " + ended + "개 종료", null));
        }
        return issueSession(user, now);
    }

    /** 세션 삭제. 없어도 조용히 성공(멱등). */
    public void logout(String token) {
        if (token != null) {
            sessions.deleteById(Tokens.hash(token));
        }
    }

    /** 감사기록용. 가입된 이메일이면 그 유저의 Principal, 아니면 빈 값. 로그인 실패 기록에 tenant 를 붙일 때 쓴다. */
    public Optional<Principal> principalOfEmail(String email) {
        return users.findByEmail(email).map(AuthService::principal);
    }

    /** 토큰을 확인한다. 패스워드를 바꿔야 하는 계정이면 403(password_change_required)이다. */
    public Principal resolve(String token) {
        AppUser user = authenticate(token);
        if (user.isMustChangePassword()) {
            throw AuthException.passwordChangeRequired("패스워드를 변경해야 다른 기능을 쓸 수 있습니다");
        }
        return principal(user);
    }

    /** 패스워드 변경이 필요한 계정도 통과한다. 내 정보, 로그아웃, 패스워드 변경만 이걸 쓴다. */
    public Principal resolveAllowingPasswordChange(String token) {
        return principal(authenticate(token));
    }

    public boolean isDemoAccount(Long userId) {
        return users.findById(userId).map(AppUser::isDemoAccount).orElse(false);
    }

    public boolean mustChangePassword(Long userId) {
        return users.findById(userId).map(AppUser::isMustChangePassword).orElse(false);
    }

    /**
     * 패스워드를 바꾼다. 현재 패스워드가 틀리면 로그인 실패와 같이 센다(탈취한 세션으로 패스워드를 알아내는 길을 막는다).
     * 직전(현재) 패스워드는 재사용할 수 없다. 바꾸면 이 세션 말고 그 유저의 다른 세션은 끊는다.
     */
    public void changePassword(Long userId, String token, String current, String next) {
        AppUser user = users.findById(userId).orElseThrow(() -> AuthException.unauthorized(TOKEN_INVALID));
        if (user.isDemoAccount()) {
            throw AuthException.invalidInput("데모 계정의 패스워드는 바꿀 수 없습니다");   // 바꾸면 공개 데모에 아무도 못 들어간다
        }
        if (current == null || !encoder.matches(current, user.getPasswordHash())) {
            recordFailure(user, Instant.now());
            throw AuthException.invalidInput("현재 패스워드가 올바르지 않습니다");
        }
        String weak = AuthValidation.passwordError(next, user.getEmail());
        if (weak != null) {
            throw AuthException.invalidInput(weak);
        }
        if (encoder.matches(next, user.getPasswordHash())) {
            throw AuthException.invalidInput("직전에 사용한 패스워드는 다시 쓸 수 없습니다");
        }
        users.updatePassword(userId, encoder.encode(next));
        sessions.deleteOthersOfUser(userId, Tokens.hash(token));
    }

    // 토큰 해시로 세션을 찾고 만료·유휴를 가린다. 끝난 세션은 행을 지워 다시 못 쓰게 한다(BF-03).
    private AppUser authenticate(String token) {
        UserSession session = token == null ? null : sessions.findById(Tokens.hash(token)).orElse(null);
        if (session == null) {
            throw AuthException.unauthorized(TOKEN_INVALID);
        }
        Instant now = Instant.now();
        if (session.isExpired(now)) {
            sessions.delete(session);
            throw AuthException.unauthorized(TOKEN_INVALID);
        }
        AppUser user = users.findById(session.getUserId())
                .orElseThrow(() -> AuthException.unauthorized("유효하지 않은 토큰입니다"));
        int idleMinutes = policies.get(session.getTenantId()).getIdleMinutes();
        if (session.isIdle(now, Duration.ofMinutes(idleMinutes))) {
            sessions.delete(session);
            audit.record(AuditEntry.user(principal(user), AuditType.SESSION_TERMINATE, user.getEmail(),
                    AuditResult.SUCCESS, "idle: 유휴 " + idleMinutes + "분 초과로 종료", null));
            throw AuthException.unauthorized(TOKEN_INVALID);
        }
        if (session.needsTouch(now)) {
            sessions.touch(session.getTokenHash(), now);
        }
        return user;
    }

    // 실패를 센다. 정책 횟수에 닿으면 잠그고 관리자에게 알린다(BF-02). 횟수 증가와 잠금은 DB 에서 원자적으로 한다.
    private void recordFailure(AppUser user, Instant now) {
        if (user.isDemoAccount()) {
            return;   // 패스워드가 공개된 데모 계정은 잠그면 아무나 데모를 막을 수 있다. 잠가도 얻는 보호가 없다
        }
        SecurityPolicy policy = policies.get(user.getTenantId());
        users.addFailure(user.getId());
        Instant until = now.plus(Duration.ofMinutes(policy.getLockMinutes()));
        if (users.lockIfReached(user.getId(), policy.getMaxLoginFailures(), until) == 1) {
            String detail = "연속 " + policy.getMaxLoginFailures() + "회 인증 실패, " + policy.getLockMinutes() + "분 잠금";
            audit.record(AuditEntry.user(principal(user), AuditType.AUTH_LOCKOUT, user.getEmail(),
                    AuditResult.FAILURE, detail, null));
            notifyLockout(user, detail);
        }
    }

    // 조직 Slack webhook 으로 알린다. 응답을 늦추지 않게 따로 보내고, 실패해도 로그인 흐름을 깨지 않는다(감사기록은 이미 남았다).
    private void notifyLockout(AppUser user, String detail) {
        tenants.findById(user.getTenantId()).map(Tenant::getSlackWebhookUrl)
                .filter(url -> url != null && !url.isBlank())
                .ifPresent(url -> CompletableFuture.runAsync(() -> {
                    try {
                        slack.send(url, "[EDRdog] 계정 잠금: " + user.getEmail() + " (" + detail + ")");
                    } catch (RuntimeException e) {
                        log.warn("계정 잠금 통보 실패 tenant={} err={}", user.getTenantId(), e.toString());
                    }
                }));
    }

    private AuthResult issueSession(AppUser user, Instant now) {
        String token = Tokens.newToken();
        sessions.save(UserSession.of(token, user.getId(), user.getTenantId(), now.plus(sessionTtl)));
        return new AuthResult(token, user.getId(), user.getTenantId(), user.getEmail(), user.getRole(),
                user.isMustChangePassword());
    }

    private static Principal principal(AppUser u) {
        return new Principal(u.getId(), u.getTenantId(), u.getEmail(), u.getRole());
    }

    private static String localPart(String email) {
        return email.substring(0, email.indexOf('@'));
    }
}
