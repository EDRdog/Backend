package com.edrdog.apiservice.auth;

import com.edrdog.apiservice.audit.AuditService;
import com.edrdog.apiservice.notify.SlackWebhookClient;
import com.edrdog.apiservice.security.Tokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthService: 리포지토리는 목킹, BCrypt/AuthValidation 은 실제로 사용.
 */
class AuthServiceTest {

    private UserRepository users;
    private TenantRepository tenants;
    private SessionRepository sessions;
    private BCryptPasswordEncoder encoder;
    private SecurityPolicyService policies;
    private AuditService audit;
    private AuthService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        tenants = mock(TenantRepository.class);
        sessions = mock(SessionRepository.class);
        encoder = spy(new BCryptPasswordEncoder());
        policies = mock(SecurityPolicyService.class);
        when(policies.get(anyLong())).thenAnswer(i -> SecurityPolicy.defaults(i.getArgument(0)));
        audit = mock(AuditService.class);
        service = new AuthService(users, tenants, sessions, encoder, policies, audit,
                mock(SlackWebhookClient.class), 168L);
    }

    private static Tenant tenantWithId(long id) {
        Tenant t = Tenant.of("org", Instant.now());
        ReflectionTestUtils.setField(t, "id", id);
        return t;
    }

    private static AppUser userWithId(long id, String email, String hash) {
        AppUser u = AppUser.of(email, hash, 10L, "admin", Instant.now());
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    @Test
    void 회원가입시_tenant와_user가_생성되고_토큰이_반환된다() {
        when(users.existsByEmail("a@b.com")).thenReturn(false);
        when(tenants.save(any(Tenant.class))).thenReturn(tenantWithId(10L));
        when(users.save(any(AppUser.class))).thenReturn(userWithId(1L, "a@b.com", "hash"));

        AuthResult r = service.signup("a@b.com", "Tr1cky#Pwd", "MyOrg");

        assertNotNull(r.token());
        assertEquals(1L, r.userId());
        assertEquals(10L, r.tenantId());
        assertEquals("admin", r.role());
        verify(tenants).save(any(Tenant.class));
        verify(users).save(any(AppUser.class));
        verify(sessions).save(any(UserSession.class));
    }

    @Test
    void 중복_이메일이면_예외() {
        when(users.existsByEmail("a@b.com")).thenReturn(true);

        AuthException e = assertThrows(AuthException.class,
                () -> service.signup("a@b.com", "Tr1cky#Pwd", null));
        assertEquals(AuthException.Kind.DUPLICATE, e.getKind());
    }

    @Test
    void 로그인_성공시_토큰_반환() {
        String hash = encoder.encode("Tr1cky#Pwd");
        when(users.findByEmail("a@b.com")).thenReturn(Optional.of(userWithId(1L, "a@b.com", hash)));

        AuthResult r = service.login("a@b.com", "Tr1cky#Pwd");

        assertNotNull(r.token());
        assertEquals(1L, r.userId());
    }

    @Test
    void 잘못된_비밀번호면_401예외() {
        String hash = encoder.encode("Tr1cky#Pwd");
        when(users.findByEmail("a@b.com")).thenReturn(Optional.of(userWithId(1L, "a@b.com", hash)));

        AuthException e = assertThrows(AuthException.class,
                () -> service.login("a@b.com", "wrongpass"));
        assertEquals(AuthException.Kind.UNAUTHORIZED, e.getKind());
    }

    @Test
    void 로그아웃후_resolve_실패() {
        service.logout("tok");
        verify(sessions).deleteById(Tokens.hash("tok"));

        when(sessions.findById(Tokens.hash("tok"))).thenReturn(Optional.empty());
        AuthException e = assertThrows(AuthException.class, () -> service.resolve("tok"));
        assertEquals(AuthException.Kind.UNAUTHORIZED, e.getKind());
    }

    @Test
    void 만료된_세션_resolve_실패() {
        UserSession expired = UserSession.of("tok", 1L, 10L, Instant.now().minusSeconds(60));
        when(sessions.findById(Tokens.hash("tok"))).thenReturn(Optional.of(expired));

        AuthException e = assertThrows(AuthException.class, () -> service.resolve("tok"));
        assertEquals(AuthException.Kind.UNAUTHORIZED, e.getKind());
    }

    @Test
    void 유효한_세션_resolve_성공() {
        UserSession valid = UserSession.of("tok", 1L, 10L, Instant.now().plusSeconds(3600));
        when(sessions.findById(Tokens.hash("tok"))).thenReturn(Optional.of(valid));
        when(users.findById(1L)).thenReturn(Optional.of(userWithId(1L, "a@b.com", "hash")));

        Principal p = service.resolve("tok");
        assertEquals(1L, p.userId());
        assertEquals("a@b.com", p.email());
    }

    @Test
    void unknownEmailStillRunsOneBcryptComparison() {
        when(users.findByEmail("none@b.com")).thenReturn(Optional.empty());

        AuthException e = assertThrows(AuthException.class, () -> service.login("none@b.com", "Tr1cky#Pwd"));

        assertEquals(AuthException.Kind.UNAUTHORIZED, e.getKind());
        verify(encoder, times(1)).matches(any(), any());
    }

    @Test
    void lockedAccountRejectsTheRightPasswordAfterOneBcryptComparison() {
        AppUser locked = userWithId(1L, "a@b.com", encoder.encode("Tr1cky#Pwd"));
        ReflectionTestUtils.setField(locked, "lockedUntil", Instant.now().plusSeconds(60));
        when(users.findByEmail("a@b.com")).thenReturn(Optional.of(locked));

        AuthException e = assertThrows(AuthException.class, () -> service.login("a@b.com", "Tr1cky#Pwd"));

        assertEquals(AuthException.Kind.UNAUTHORIZED, e.getKind());
        assertEquals("이메일 또는 비밀번호가 올바르지 않습니다", e.getMessage());
        verify(encoder, times(1)).matches(any(), any());
    }

    @Test
    void sessionStoredByTheServiceKeepsOnlyTheHashOfTheIssuedToken() {
        String hash = encoder.encode("Tr1cky#Pwd");
        when(users.findByEmail("a@b.com")).thenReturn(Optional.of(userWithId(1L, "a@b.com", hash)));

        AuthResult r = service.login("a@b.com", "Tr1cky#Pwd");

        org.mockito.ArgumentCaptor<UserSession> saved = org.mockito.ArgumentCaptor.forClass(UserSession.class);
        verify(sessions).save(saved.capture());
        assertEquals(Tokens.hash(r.token()), saved.getValue().getTokenHash());
        assertNotEquals(r.token(), saved.getValue().getTokenHash());
    }

    @Test
    void flaggedAccountFailsStrictResolveButPassesTheAllowingOne() {
        UserSession valid = UserSession.of("tok", 1L, 10L, Instant.now().plusSeconds(3600));
        AppUser flagged = userWithId(1L, "a@b.com", "hash");
        flagged.requirePasswordChange();
        when(sessions.findById(Tokens.hash("tok"))).thenReturn(Optional.of(valid));
        when(users.findById(1L)).thenReturn(Optional.of(flagged));

        AuthException e = assertThrows(AuthException.class, () -> service.resolve("tok"));

        assertEquals(AuthException.Kind.PASSWORD_CHANGE_REQUIRED, e.getKind());
        assertEquals(1L, service.resolveAllowingPasswordChange("tok").userId());
    }
}
