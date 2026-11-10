package com.edrdog.apiservice.auth.web;

import com.edrdog.apiservice.auth.AppUser;
import com.edrdog.apiservice.auth.AuthService;
import com.edrdog.apiservice.auth.UserRepository;
import com.edrdog.apiservice.notify.SlackWebhookClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KISA BF-01~05, 08, 12 배선: 패스워드 규칙과 변경, 연속 실패 잠금과 통보, 실패 사유 비공개, 세션 토큰 해시 저장,
 * 보안정책 API, 기본 패스워드 강제 변경, 유휴 종료, 중복 접속. 근거는 ADR 031, 032.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {"spring.kafka.listener.auto-startup=false", "edrdog.selftest.run-on-startup=false"})
class AuthSecurityIntegrationTest {

    private static final String PW = "Tr1cky#Pwd";
    private static final String NEW_PW = "N3w#Valid-Pw";
    private static final String WRONG_PW = "Wr0ng#Passw";
    private static final String HOOK = "https://hooks.slack.com/services/T000/B000/lockout";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository users;
    @Autowired
    private BCryptPasswordEncoder encoder;
    @Autowired
    private AuthService auth;
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mappings;

    @MockitoBean
    private SlackWebhookClient slack;

    private record Acct(String token, long tenantId, String email) {
    }

    private static String body(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private Acct signup(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(body(email, PW))).andExpect(status().isCreated()).andReturn();
        JsonNode n = om.readTree(r.getResponse().getContentAsString());
        return new Acct(n.get("token").asText(), n.get("tenantId").asLong(), email);
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body(email, password))).andReturn();
    }

    private String loginToken(String email, String password) throws Exception {
        MvcResult r = login(email, password);
        assertEquals(200, r.getResponse().getStatus());
        return om.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private int call(String method, String path, String token, String json) throws Exception {
        var req = method.equals("PUT") ? put(path) : method.equals("POST") ? post(path) : get(path);
        req = req.header("X-API-Key", "dev-api-key");
        if (token != null) {
            req = req.header("Authorization", "Bearer " + token);
        }
        if (json != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private int auditCount(long tenantId, String type) {
        return jdbc.queryForObject("select count(*) from audit_log where tenant_id = ? and event_type = ?",
                Integer.class, tenantId, type);
    }

    private List<String> auditDetails(long tenantId, String type) {
        return jdbc.queryForList("select detail from audit_log where tenant_id = ? and event_type = ? order by id",
                String.class, tenantId, type);
    }

    private void putPolicy(String token, int max, int lock, int idle) throws Exception {
        assertEquals(200, call("PUT", "/api/auth/security-policy", token,
                "{\"maxLoginFailures\":" + max + ",\"lockMinutes\":" + lock + ",\"idleMinutes\":" + idle + "}"));
    }

    // ---- BF-01.2 패스워드 규칙 ----

    @Test
    void signupRejectsPasswordsThatBreakTheRules() throws Exception {
        for (String weak : List.of("Sh0rt!a", "nouppercase1!", "NOLOWERCASE1!", "NoDigits!!xy", "NoSpecial7qa",
                "Kd#7777xPw", "Hqwe#9zLkP")) {
            mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                            .content(body("weak-" + Math.abs(weak.hashCode()) + "@edrdog.com", weak)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void signupRejectsPasswordSameAsAccountId() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(body("Same.Pw9xk@edrdog.com", "Same.Pw9xk")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("계정")));
    }

    // ---- BF-01.2 패스워드 변경 API ----

    @Test
    void changePasswordSucceedsAndOldPasswordStopsWorking() throws Exception {
        Acct a = signup("chg-ok@edrdog.com");
        String t = loginToken(a.email(), PW);

        mvc.perform(post("/api/auth/password").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"" + NEW_PW + "\"}"))
                .andExpect(status().isNoContent());

        assertEquals(401, login(a.email(), PW).getResponse().getStatus());
        assertEquals(200, login(a.email(), NEW_PW).getResponse().getStatus());
        assertEquals(1, auditCount(a.tenantId(), "password_change"));
        String table = jdbc.queryForList("select * from audit_log").toString();
        assertFalse(table.contains(NEW_PW));
        assertFalse(table.contains(PW));
    }

    @Test
    void changePasswordEndsTheUsersOtherSessions() throws Exception {
        Acct a = signup("chg-other@edrdog.com");
        String t1 = loginToken(a.email(), PW);
        // 중복 접속은 막히므로 두 번째 세션을 DB 에 직접 만든다
        String t2 = "second-session-token-chg-other";
        jdbc.update("insert into sessions (token, user_id, tenant_id, expires_at, last_used_at) "
                        + "select ?, user_id, tenant_id, expires_at, current_timestamp from sessions where token = ?",
                sha256(t2), sha256(t1));

        mvc.perform(post("/api/auth/password").header("Authorization", "Bearer " + t1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"" + NEW_PW + "\"}"))
                .andExpect(status().isNoContent());

        assertEquals(200, call("GET", "/api/auth/me", t1, null));
        assertEquals(401, call("GET", "/api/auth/me", t2, null));
    }

    @Test
    void changePasswordRejectsWrongCurrentRuleViolationAndReuse() throws Exception {
        Acct a = signup("chg-bad@edrdog.com");
        String t = loginToken(a.email(), PW);
        String path = "/api/auth/password";
        // 현재 패스워드가 틀림
        assertEquals(400, call("POST", path, t,
                "{\"currentPassword\":\"" + WRONG_PW + "\",\"newPassword\":\"" + NEW_PW + "\"}"));
        // 규칙 위반(길이)
        assertEquals(400, call("POST", path, t,
                "{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"Sh0rt!a\"}"));
        // 직전 패스워드 재사용
        assertEquals(400, call("POST", path, t,
                "{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"" + PW + "\"}"));
        // 아무것도 바뀌지 않았고, 거절은 실패로 남는다
        assertEquals(200, login(a.email(), PW).getResponse().getStatus());
        assertEquals(3, jdbc.queryForObject("select count(*) from audit_log where tenant_id = ? "
                + "and event_type = 'password_change' and result = 'FAILURE'", Integer.class, a.tenantId()));
    }

    @Test
    void changePasswordWithoutTokenIs401() throws Exception {
        assertEquals(401, call("POST", "/api/auth/password", null,
                "{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"" + NEW_PW + "\"}"));
    }

    // ---- BF-02 잠금과 통보, BF-04.2 사유 비공개 ----

    @Test
    void fiveFailuresLockTheAccountEvenForTheRightPassword() throws Exception {
        Acct a = signup("lock-five@edrdog.com");
        assertEquals(200, call("PUT", "/api/tenant/webhook", a.token(), "{\"webhookUrl\":\"" + HOOK + "\"}"));
        for (int i = 0; i < 5; i++) {
            assertEquals(401, login(a.email(), WRONG_PW).getResponse().getStatus());
        }

        assertEquals(401, login(a.email(), PW).getResponse().getStatus());
        assertEquals(1, auditCount(a.tenantId(), "auth_lockout"));
        verify(slack, timeout(3000)).send(eq(HOOK), contains("lock-five@edrdog.com"));
    }

    @Test
    void lockEndsAfterTheLockTime() throws Exception {
        Acct a = signup("lock-expire@edrdog.com");
        for (int i = 0; i < 5; i++) {
            login(a.email(), WRONG_PW);
        }
        assertEquals(401, login(a.email(), PW).getResponse().getStatus());

        jdbc.update("update users set locked_until = dateadd('SECOND', -1, current_timestamp) where email = ?", a.email());

        assertEquals(200, login(a.email(), PW).getResponse().getStatus());
    }

    @Test
    void aSuccessfulLoginResetsTheFailureCounter() throws Exception {
        Acct a = signup("lock-reset@edrdog.com");
        for (int i = 0; i < 4; i++) {
            login(a.email(), WRONG_PW);
        }
        assertEquals(200, login(a.email(), PW).getResponse().getStatus());
        for (int i = 0; i < 4; i++) {
            login(a.email(), WRONG_PW);
        }

        assertEquals(200, login(a.email(), PW).getResponse().getStatus());
        assertEquals(0, auditCount(a.tenantId(), "auth_lockout"));
    }

    @Test
    void unknownWrongPasswordAndLockedAccountsAnswerIdentically() throws Exception {
        Acct a = signup("lock-same@edrdog.com");
        MvcResult unknown = login("nobody-here@edrdog.com", PW);
        MvcResult wrong = login(a.email(), WRONG_PW);
        for (int i = 0; i < 4; i++) {
            login(a.email(), WRONG_PW);
        }
        MvcResult locked = login(a.email(), PW);

        for (MvcResult r : List.of(wrong, locked)) {
            assertEquals(unknown.getResponse().getStatus(), r.getResponse().getStatus());
            assertEquals(unknown.getResponse().getContentAsString(), r.getResponse().getContentAsString());
        }
        assertEquals(401, unknown.getResponse().getStatus());
    }

    @Test
    void lockoutFollowsThePolicyOfTheTenant() throws Exception {
        Acct a = signup("lock-policy@edrdog.com");
        Acct b = signup("lock-policy-b@edrdog.com");
        putPolicy(a.token(), 3, 5, 10);

        for (int i = 0; i < 3; i++) {
            login(a.email(), WRONG_PW);
            login(b.email(), WRONG_PW);
        }

        assertEquals(401, login(a.email(), PW).getResponse().getStatus());   // 3회로 잠김
        assertEquals(200, login(b.email(), PW).getResponse().getStatus());   // 다른 조직은 기본 5회
    }

    // ---- BF-03 세션 토큰 ----

    @Test
    void sessionTokenIsStoredAsHashAndNeverAsPlaintext() throws Exception {
        Acct a = signup("hash-store@edrdog.com");

        assertEquals(0, jdbc.queryForObject("select count(*) from sessions where token = ?", Integer.class, a.token()));
        assertEquals(1, jdbc.queryForObject("select count(*) from sessions where token = ?", Integer.class,
                sha256(a.token())));
        assertEquals(200, call("GET", "/api/auth/me", a.token(), null));
        // 저장된 해시값 자체는 토큰으로 쓸 수 없다
        assertEquals(401, call("GET", "/api/auth/me", sha256(a.token()), null));
    }

    @Test
    void sessionsStoredAsPlaintextBeforeTheHashChangeArePurgedAtStartup() throws Exception {
        Acct a = signup("hash-legacy@edrdog.com");
        // 옛 방식: 토큰 원문(43자)이 그대로 키다
        jdbc.update("insert into sessions (token, user_id, tenant_id, expires_at) "
                        + "select ?, user_id, tenant_id, expires_at from sessions where token = ?",
                "legacy-plaintext-token-" + a.tenantId(), sha256(a.token()));

        auth.purgeLegacySessions();

        assertEquals(0, jdbc.queryForObject("select count(*) from sessions where length(token) <> 64", Integer.class));
        assertEquals(200, call("GET", "/api/auth/me", a.token(), null));   // 해시로 저장된 세션은 남는다
    }

    @Test
    void expiredAndLoggedOutTokensCannotBeUsedAgain() throws Exception {
        Acct a = signup("hash-expire@edrdog.com");
        String t = loginToken(a.email(), PW);
        jdbc.update("update sessions set expires_at = dateadd('SECOND', -1, current_timestamp) where token = ?", sha256(t));
        assertEquals(401, call("GET", "/api/auth/me", t, null));

        String t2 = loginToken(a.email(), PW);
        assertEquals(204, call("POST", "/api/auth/logout", t2, null));
        assertEquals(401, call("GET", "/api/auth/me", t2, null));
    }

    // ---- BF-05.1 보안정책 ----

    @Test
    void policyDefaultsAndUpdateAreVisibleAndAudited() throws Exception {
        Acct a = signup("policy-ok@edrdog.com");
        mvc.perform(get("/api/auth/security-policy").header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxLoginFailures").value(5))
                .andExpect(jsonPath("$.lockMinutes").value(5))
                .andExpect(jsonPath("$.idleMinutes").value(10));

        putPolicy(a.token(), 3, 15, 20);

        mvc.perform(get("/api/auth/security-policy").header("Authorization", "Bearer " + a.token()))
                .andExpect(jsonPath("$.maxLoginFailures").value(3))
                .andExpect(jsonPath("$.lockMinutes").value(15))
                .andExpect(jsonPath("$.idleMinutes").value(20));
        List<String> details = auditDetails(a.tenantId(), "security_policy_change");
        assertEquals(1, details.size());
        assertTrue(details.get(0).contains("5->3"), details.get(0));
        assertTrue(details.get(0).contains("5->15"), details.get(0));
        assertTrue(details.get(0).contains("10->20"), details.get(0));
    }

    @Test
    void policyOutsideTheAllowedRangeIs400() throws Exception {
        Acct a = signup("policy-range@edrdog.com");
        for (String bad : List.of(
                "{\"maxLoginFailures\":6,\"lockMinutes\":5,\"idleMinutes\":10}",
                "{\"maxLoginFailures\":0,\"lockMinutes\":5,\"idleMinutes\":10}",
                "{\"maxLoginFailures\":5,\"lockMinutes\":4,\"idleMinutes\":10}",
                "{\"maxLoginFailures\":5,\"lockMinutes\":5,\"idleMinutes\":0}",
                "{\"maxLoginFailures\":5,\"lockMinutes\":5,\"idleMinutes\":61}",
                "{\"maxLoginFailures\":5,\"lockMinutes\":5}")) {
            assertEquals(400, call("PUT", "/api/auth/security-policy", a.token(), bad), bad);
        }
        assertEquals(0, auditCount(a.tenantId(), "security_policy_change"));
    }

    @Test
    void policyIsAdminOnlyAndNeedsAToken() throws Exception {
        Acct a = signup("policy-admin@edrdog.com");
        users.save(AppUser.of("policy-viewer@edrdog.com", encoder.encode(PW), a.tenantId(), "viewer", Instant.now()));
        String viewer = loginToken("policy-viewer@edrdog.com", PW);

        assertEquals(403, call("GET", "/api/auth/security-policy", viewer, null));
        assertEquals(403, call("PUT", "/api/auth/security-policy", viewer,
                "{\"maxLoginFailures\":3,\"lockMinutes\":5,\"idleMinutes\":10}"));
        assertEquals(401, call("GET", "/api/auth/security-policy", null, null));
    }

    // ---- BF-08 기본 패스워드 강제 변경 ----

    @Test
    void flaggedAccountCanOnlyChangePasswordLogoutAndSeeMe() throws Exception {
        Acct a = signup("must-change@edrdog.com");
        jdbc.update("update users set must_change_password = true where email = ?", a.email());
        MvcResult login = login(a.email(), PW);
        String t = om.readTree(login.getResponse().getContentAsString()).get("token").asText();
        assertTrue(om.readTree(login.getResponse().getContentAsString()).get("mustChangePassword").asBoolean());

        // 허용: 내 정보(상태를 알린다)
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(true));
        // 거부: 그 밖의 API 는 403 이고 코드로 일반 403 과 구분된다
        mvc.perform(get("/api/audit").header("Authorization", "Bearer " + t))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("password_change_required"));
        mvc.perform(get("/api/auth/security-policy").header("Authorization", "Bearer " + t))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("password_change_required"));
        // 허용: 변경 뒤에는 풀린다
        assertEquals(204, call("POST", "/api/auth/password", t,
                "{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"" + NEW_PW + "\"}"));
        assertEquals(200, call("GET", "/api/audit", t, null));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + t))
                .andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    @Test
    void flaggedAccountCanLogOut() throws Exception {
        Acct a = signup("must-logout@edrdog.com");
        jdbc.update("update users set must_change_password = true where email = ?", a.email());
        String t = loginToken(a.email(), PW);

        assertEquals(204, call("POST", "/api/auth/logout", t, null));
        assertEquals(401, call("GET", "/api/auth/me", t, null));
        assertEquals(1, auditCount(a.tenantId(), "logout"));
    }

    // ---- BF-12.1 유휴 종료 ----

    @Test
    void sessionIdleLongerThanThePolicyIsTerminatedAndAudited() throws Exception {
        Acct a = signup("idle-end@edrdog.com");
        jdbc.update("update sessions set last_used_at = dateadd('MINUTE', -11, current_timestamp) where token = ?",
                sha256(a.token()));

        assertEquals(401, call("GET", "/api/auth/me", a.token(), null));

        assertEquals(0, jdbc.queryForObject("select count(*) from sessions where token = ?", Integer.class,
                sha256(a.token())));
        List<String> details = auditDetails(a.tenantId(), "session_terminate");
        assertEquals(1, details.size());
        assertTrue(details.get(0).contains("idle"), details.get(0));
    }

    @Test
    void idleTimeFollowsThePolicy() throws Exception {
        Acct a = signup("idle-policy@edrdog.com");
        putPolicy(a.token(), 5, 5, 1);
        jdbc.update("update sessions set last_used_at = dateadd('MINUTE', -2, current_timestamp) where token = ?",
                sha256(a.token()));

        assertEquals(401, call("GET", "/api/auth/me", a.token(), null));
    }

    @Test
    void lastUsedTimeIsRefreshedOnlyOncePerMinute() throws Exception {
        Acct a = signup("idle-touch@edrdog.com");
        String h = sha256(a.token());
        jdbc.update("update sessions set last_used_at = dateadd('SECOND', -30, current_timestamp) where token = ?", h);
        assertEquals(200, call("GET", "/api/auth/me", a.token(), null));
        // 30초 전이면 쓰지 않는다
        assertEquals(1, jdbc.queryForObject("select count(*) from sessions where token = ? "
                + "and last_used_at < dateadd('SECOND', -20, current_timestamp)", Integer.class, h));

        jdbc.update("update sessions set last_used_at = dateadd('MINUTE', -2, current_timestamp) where token = ?", h);
        assertEquals(200, call("GET", "/api/auth/me", a.token(), null));
        // 2분 전이면 갱신한다
        assertEquals(1, jdbc.queryForObject("select count(*) from sessions where token = ? "
                + "and last_used_at > dateadd('SECOND', -20, current_timestamp)", Integer.class, h));
    }

    // ---- BF-12.2 중복 접속 ----

    @Test
    void secondLoginEndsTheFirstSessionAndIsAudited() throws Exception {
        Acct a = signup("dup-login@edrdog.com");
        String first = loginToken(a.email(), PW);
        String second = loginToken(a.email(), PW);

        assertEquals(401, call("GET", "/api/auth/me", first, null));
        assertEquals(401, call("GET", "/api/auth/me", a.token(), null));
        assertEquals(200, call("GET", "/api/auth/me", second, null));
        List<String> details = auditDetails(a.tenantId(), "session_terminate");
        assertFalse(details.isEmpty());
        details.forEach(d -> assertTrue(d.contains("duplicate"), d));
    }

    // ---- 접근통제 관문(@RequiresRole)을 지나는 모든 경로 ----

    /** 관문이 보는 세션 인증 매핑 전부. 경로 변수는 아무 값으로 채운다. */
    private record Guarded(String method, String path, boolean allowsPasswordChange) {
    }

    private List<Guarded> guardedMappings() {
        List<Guarded> out = new java.util.ArrayList<>();
        mappings.getHandlerMethods().forEach((info, h) -> {
            var role = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                    h.getMethod(), com.edrdog.apiservice.access.RequiresRole.class);
            if (role == null) {
                role = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                        h.getBeanType(), com.edrdog.apiservice.access.RequiresRole.class);
            }
            if (role == null) {
                return;
            }
            for (String path : info.getPathPatternsCondition().getPatternValues()) {
                for (var m : info.getMethodsCondition().getMethods()) {
                    out.add(new Guarded(m.name(), path.replaceAll("\\{[^}]+}", "x"), role.allowPasswordChange()));
                }
            }
        });
        return out;
    }

    private int callGuarded(Guarded g, String token) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .request(org.springframework.http.HttpMethod.valueOf(g.method()), g.path())
                        .header("X-API-Key", "dev-api-key").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void forcedPasswordChangeStopsEverySessionMappingExceptTheAllowedOnes() throws Exception {
        Acct a = signup("gate-force@edrdog.com");
        jdbc.update("update users set must_change_password = true where email = ?", a.email());
        String t = loginToken(a.email(), PW);
        List<Guarded> all = guardedMappings();
        assertTrue(all.size() > 40, "세션 매핑을 못 찾았다: " + all.size());

        int allowed = 0;
        for (Guarded g : all) {
            if (g.allowsPasswordChange()) {
                allowed++;
                assertTrue(callGuarded(g, t) != 403, g.method() + " " + g.path());
                continue;
            }
            MvcResult r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .request(org.springframework.http.HttpMethod.valueOf(g.method()), g.path())
                            .header("X-API-Key", "dev-api-key").header("Authorization", "Bearer " + t)
                            .contentType(MediaType.APPLICATION_JSON)).andReturn();
            assertEquals(403, r.getResponse().getStatus(), g.method() + " " + g.path());
            assertTrue(r.getResponse().getContentAsString().contains("password_change_required"), g.method() + " " + g.path());
        }
        assertEquals(2, allowed);   // 내 정보, 패스워드 변경
    }

    @Test
    void idleAndDuplicateEndingApplyToEverySessionMapping() throws Exception {
        Acct a = signup("gate-idle@edrdog.com");
        for (Guarded g : guardedMappings()) {
            String t = loginToken(a.email(), PW);
            jdbc.update("update sessions set last_used_at = dateadd('MINUTE', -11, current_timestamp) where token = ?",
                    sha256(t));
            assertEquals(401, callGuarded(g, t), "유휴 " + g.method() + " " + g.path());

            String first = loginToken(a.email(), PW);
            loginToken(a.email(), PW);   // 두 번째 로그인이 첫 세션을 끊는다
            assertEquals(401, callGuarded(g, first), "중복 " + g.method() + " " + g.path());
        }
    }

    // ---- admin 이 만드는 viewer 계정 ----

    @Test
    void viewerCreatedByAdminFollowsPasswordRulesAndMustChangeAtFirstLogin() throws Exception {
        Acct admin = signup("gate-viewer-admin@edrdog.com");
        for (String weak : List.of("Sh0rt!a", "nouppercase1!", "Kd#7777xPw")) {
            assertEquals(400, call("POST", "/api/tenant/users", admin.token(),
                    "{\"email\":\"weak-viewer@edrdog.com\",\"password\":\"" + weak + "\"}"));
        }
        assertEquals(201, call("POST", "/api/tenant/users", admin.token(),
                "{\"email\":\"gate-viewer@edrdog.com\",\"password\":\"" + PW + "\"}"));

        MvcResult login = login("gate-viewer@edrdog.com", PW);
        assertTrue(om.readTree(login.getResponse().getContentAsString()).get("mustChangePassword").asBoolean());
        String t = om.readTree(login.getResponse().getContentAsString()).get("token").asText();
        assertEquals(403, call("GET", "/api/hosts", t, null));   // 바꾸기 전에는 읽기 API 도 막힌다
        assertEquals(204, call("POST", "/api/auth/password", t,
                "{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"" + NEW_PW + "\"}"));
        // 바꾼 뒤에는 강제 변경이 아니라 역할(viewer)로 판정한다
        MvcResult after = mvc.perform(get("/api/auth/security-policy").header("Authorization", "Bearer " + t)).andReturn();
        assertEquals(403, after.getResponse().getStatus());
        assertFalse(after.getResponse().getContentAsString().contains("password_change_required"));
    }
}
