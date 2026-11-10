package com.edrdog.apiservice.audit.web;

import com.edrdog.apiservice.alert.AlertService;
import com.edrdog.apiservice.alert.web.AlertResponse;
import com.edrdog.apiservice.audit.AuditActor;
import com.edrdog.apiservice.audit.AuditEntry;
import com.edrdog.apiservice.audit.AuditResult;
import com.edrdog.apiservice.audit.AuditService;
import com.edrdog.apiservice.audit.AuditType;
import com.edrdog.apiservice.auth.AppUser;
import com.edrdog.apiservice.auth.SessionRepository;
import com.edrdog.apiservice.auth.UserRepository;
import com.edrdog.apiservice.auth.UserSession;
import com.edrdog.apiservice.responder.KillResult;
import com.edrdog.apiservice.responder.ResponderClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/audit 와 사건 기록 배선: 가입·로그인·로그아웃·kill 요청이 실제로 남는지, 조회 권한(비로그인, 다른 tenant,
 * admin 아닌 역할), 검색·정렬·페이지, 비밀값이 안 남는지.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {"spring.kafka.listener.auto-startup=false", "edrdog.selftest.run-on-startup=false"})
class AuditApiIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private AuditService audit;
    @Autowired
    private UserRepository users;
    @Autowired
    private SessionRepository sessions;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private ResponderClient responder;
    @MockitoBean
    private AlertService alerts;

    /** 가입하고 {token, tenantId} 를 돌려준다. */
    private String[] signup(String email, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode n = om.readTree(r.getResponse().getContentAsString());
        return new String[]{n.get("token").asText(), n.get("tenantId").asText()};
    }

    private JsonNode audits(String token, String query) throws Exception {
        MvcResult r = mvc.perform(get("/api/audit" + query).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString());
    }

    private static List<String> field(JsonNode rows, String name) {
        List<String> out = new ArrayList<>();
        rows.forEach(n -> out.add(n.get(name).asText()));
        return out;
    }

    @Test
    void signupLoginAndLogoutAreRecordedWithActorAndIp() throws Exception {
        String[] a = signup("flow-audit@edrdog.com", "password1");
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"flow-audit@edrdog.com\",\"password\":\"password1\"}"))
                .andExpect(status().isOk()).andReturn();
        String token = om.readTree(login.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        JsonNode rows = audits(a[0], "?order=asc");
        assertEquals(List.of("signup", "login_success", "logout"), field(rows, "type"));
        JsonNode login1 = rows.get(1);
        assertEquals("user", login1.get("actorType").asText());
        assertEquals("flow-audit@edrdog.com", login1.get("actor").asText());
        assertEquals("success", login1.get("result").asText());
        assertEquals(Long.parseLong(a[1]), login1.get("tenantId").asLong());
        assertTrue(login1.get("actorUserId").asLong() > 0);
        assertFalse(login1.get("ip").asText().isBlank());
        assertTrue(login1.get("ts").asLong() > 0);
    }

    @Test
    void failedLoginOfARegisteredEmailIsRecordedInThatTenant() throws Exception {
        String[] a = signup("fail-audit@edrdog.com", "password1");
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"fail-audit@edrdog.com\",\"password\":\"wrong-pass-1\"}"))
                .andExpect(status().isUnauthorized());

        JsonNode rows = audits(a[0], "?type=login_failure");
        assertEquals(1, rows.size());
        assertEquals("failure", rows.get(0).get("result").asText());
        assertEquals("fail-audit@edrdog.com", rows.get(0).get("actor").asText());
    }

    @Test
    void secretsNeverReachTheAuditTable() throws Exception {
        String pw = "Sup3r-Secret-Pw!";
        String[] a = signup("secret-audit@edrdog.com", pw);
        // 틀린 비밀번호, 그리고 이메일 칸에 비밀번호를 잘못 붙여 넣은 시도(tenant 가 없어 조회에는 안 나오지만 DB 에는 남는다)
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"secret-audit@edrdog.com\",\"password\":\"bad-" + pw + "\"}"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + pw + "\",\"password\":\"x\"}"));
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"secret-audit@edrdog.com\",\"password\":\"" + pw + "\"}")).andReturn();
        String token = om.readTree(login.getResponse().getContentAsString()).get("token").asText();

        String api = mvc.perform(get("/api/audit").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        // 응답뿐 아니라 테이블 전체(tenant 없는 행 포함)를 본다
        String table = jdbc.queryForList("select * from audit_log").toString();
        for (String where : List.of(api, table)) {
            assertFalse(where.contains(pw), "패스워드가 남았다");
            assertFalse(where.contains(token), "세션 토큰이 남았다");
            assertFalse(where.contains(a[0]), "가입 때 받은 세션 토큰이 남았다");
            assertFalse(where.contains("$2a$"), "비밀번호 해시가 남았다");
        }
    }

    @Test
    void killRequestRecordsTheRequesterAndTheHost() throws Exception {
        String[] a = signup("kill-audit@edrdog.com", "password1");
        AlertResponse alert = mock(AlertResponse.class);
        when(alert.host()).thenReturn("hostK");
        when(alert.target()).thenReturn("evil.exe");
        when(alerts.get(a[1], "alert-1")).thenReturn(alert);
        when(responder.kill(a[1], "hostK", "evil.exe"))
                .thenReturn(new KillResult(a[1], "hostK", "evil.exe", "PENDING", "exec-9"));

        mvc.perform(post("/api/alerts/alert-1/respond").header("Authorization", "Bearer " + a[0])
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isAccepted());

        JsonNode rows = audits(a[0], "?type=kill_request");
        assertEquals(1, rows.size());
        JsonNode r = rows.get(0);
        assertEquals("kill-audit@edrdog.com", r.get("actor").asText());
        assertEquals("hostK", r.get("target").asText());
        assertEquals("success", r.get("result").asText());
        assertTrue(r.get("detail").asText().contains("evil.exe"));
        assertTrue(r.get("detail").asText().contains("exec-9"));
    }

    @Test
    void killRequestThatTheResponderFailedIsRecordedAsFailure() throws Exception {
        String[] a = signup("killfail-audit@edrdog.com", "password1");
        AlertResponse alert = mock(AlertResponse.class);
        when(alert.host()).thenReturn("hostF");
        when(alert.target()).thenReturn("evil.exe");
        when(alerts.get(a[1], "alert-2")).thenReturn(alert);
        when(responder.kill(a[1], "hostF", "evil.exe"))
                .thenReturn(new KillResult(a[1], "hostF", "evil.exe", "FAILED", null));

        mvc.perform(post("/api/alerts/alert-2/respond").header("Authorization", "Bearer " + a[0])
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());

        assertEquals(List.of("failure"), field(audits(a[0], "?type=kill_request"), "result"));
    }

    @Test
    void agentEnrollResultIsRecordedWithoutTheSecret() throws Exception {
        String[] a = signup("enroll-audit@edrdog.com", "password1");
        MvcResult sec = mvc.perform(get("/api/tenant/enroll-secret")
                .header("Authorization", "Bearer " + a[0]).header("X-API-Key", "dev-api-key")).andReturn();
        String secret = om.readTree(sec.getResponse().getContentAsString()).get("enrollSecret").asText();

        mvc.perform(post("/api/internal/agent/resolve-tenant").header("X-Internal-Key", "dev-internal-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"enrollSecret\":\"" + secret + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/internal/agent/resolve-tenant").header("X-Internal-Key", "dev-internal-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"enrollSecret\":\"wrong-secret-value\"}"))
                .andExpect(status().isNotFound());

        // 성공은 그 tenant 의 기록으로 남는다
        JsonNode rows = audits(a[0], "?type=agent_enroll");
        assertEquals(List.of("success"), field(rows, "result"));
        // 실패는 tenant 를 알 수 없어 조회에는 안 나오고 DB 에만 남는다
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from audit_log where event_type = 'agent_enroll' and result = 'FAILURE' and tenant_id is null",
                Integer.class));
        String table = jdbc.queryForList("select * from audit_log").toString();
        assertFalse(table.contains(secret), "enroll secret 이 남았다");
        assertFalse(table.contains("wrong-secret-value"), "시도한 secret 이 남았다");
    }

    @Test
    void requestWithoutTokenIsRejected() throws Exception {
        mvc.perform(get("/api/audit")).andExpect(status().isUnauthorized());
    }

    @Test
    void otherTenantOnlySeesItsOwnRecords() throws Exception {
        String[] a = signup("tenant-a-audit@edrdog.com", "password1");
        String[] b = signup("tenant-b-audit@edrdog.com", "password1");

        JsonNode rowsA = audits(a[0], "");
        JsonNode rowsB = audits(b[0], "");
        rowsA.forEach(n -> assertEquals(Long.parseLong(a[1]), n.get("tenantId").asLong()));
        rowsB.forEach(n -> assertEquals(Long.parseLong(b[1]), n.get("tenantId").asLong()));
        assertTrue(rowsA.toString().contains("tenant-a-audit@edrdog.com"));
        assertFalse(rowsA.toString().contains("tenant-b-audit@edrdog.com"));
        assertFalse(rowsB.toString().contains("tenant-a-audit@edrdog.com"));
    }

    @Test
    void nonAdminRoleIsForbidden() throws Exception {
        String[] a = signup("admin-for-viewer@edrdog.com", "password1");
        AppUser viewer = users.save(AppUser.of("viewer-audit@edrdog.com", "hash", Long.parseLong(a[1]),
                "viewer", Instant.now()));
        sessions.save(UserSession.of("viewer-token-audit", viewer.getId(), viewer.getTenantId(),
                Instant.now().plusSeconds(600)));

        mvc.perform(get("/api/audit").header("Authorization", "Bearer viewer-token-audit"))
                .andExpect(status().isForbidden());
        // 같은 tenant 의 admin 은 읽는다
        mvc.perform(get("/api/audit").header("Authorization", "Bearer " + a[0])).andExpect(status().isOk());
    }

    @Test
    void searchSortAndPageByQueryParameters() throws Exception {
        String[] a = signup("search-audit@edrdog.com", "password1");
        long tenant = Long.parseLong(a[1]);
        for (int i = 1; i <= 4; i++) {
            audit.record(new AuditEntry(Instant.ofEpochMilli(i * 1000L), tenant, AuditActor.SYSTEM, null,
                    "system", AuditType.SELF_TEST_RESULT, null,
                    i % 2 == 0 ? AuditResult.FAILURE : AuditResult.SUCCESS, "n" + i, null));
        }

        // 유형 + 결과 + 기간 + 내림차순
        JsonNode rows = audits(a[0], "?type=self_test_result&result=failure&from=1500&to=4000&order=desc");
        assertEquals(List.of("n4", "n2"), field(rows, "detail"));
        // 오름차순 + 페이지
        MvcResult page = mvc.perform(get("/api/audit?type=self_test_result&order=asc&limit=2&offset=1&withTotal=true")
                        .header("Authorization", "Bearer " + a[0]))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Has-More", "true"))
                .andExpect(header().string("X-Total-Count", "4"))
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();
        assertEquals(List.of("n2", "n3"), field(om.readTree(page.getResponse().getContentAsString()), "detail"));
        // 행위자
        assertEquals(4, audits(a[0], "?type=self_test_result&actor=system").size());
        assertEquals(0, audits(a[0], "?type=self_test_result&actor=nobody").size());
    }

    @Test
    void badSearchParametersAreRejected() throws Exception {
        String[] a = signup("badparam-audit@edrdog.com", "password1");
        for (String q : List.of("?order=sideways", "?result=maybe", "?offset=-1", "?offset=999999", "?from=abc")) {
            mvc.perform(get("/api/audit" + q).header("Authorization", "Bearer " + a[0]))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void thereIsNoApiToChangeOrDeleteRecords() throws Exception {
        String[] a = signup("immutable-audit@edrdog.com", "password1");
        var requests = List.of(delete("/api/audit"), put("/api/audit"), patch("/api/audit"),
                delete("/api/audit/1"), post("/api/audit"));
        for (var req : requests) {
            int s = mvc.perform(req.header("Authorization", "Bearer " + a[0]).contentType(MediaType.APPLICATION_JSON)
                    .content("{}")).andReturn().getResponse().getStatus();
            assertTrue(s == 404 || s == 405, "status " + s);
        }
    }
}
