package com.edrdog.apiservice.access;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 권한별 접근통제(KISA EDR-BF-20.2): viewer 는 조회만, admin 은 대응(kill), 설정(webhook, enroll secret, 보안정책, IP 목록),
 * 감사기록·자체시험, 계정 생성까지. 컨트롤러별 권한 표는 ADR 033.
 */
class RoleAccessIntegrationTest extends AccessTestBase {

    /** viewer 가 거절돼야 하는 요청. 대응, 설정, 감사기록, 자체시험, 계정, 개인 설정 쓰기. */
    private record Call(HttpMethod method, String path) {
    }

    private static final List<Call> ADMIN_ONLY = List.of(
            new Call(HttpMethod.POST, "/api/alerts/a1/respond"),
            new Call(HttpMethod.GET, "/api/alerts/a1/respond/e1"),
            new Call(HttpMethod.PATCH, "/api/alerts/a1"),
            new Call(HttpMethod.PATCH, "/api/incidents/i1/status"),
            new Call(HttpMethod.PUT, "/api/tenant/webhook"),
            new Call(HttpMethod.GET, "/api/tenant/webhook"),
            new Call(HttpMethod.POST, "/api/tenant/enroll-secret"),
            new Call(HttpMethod.GET, "/api/tenant/enroll-secret"),
            new Call(HttpMethod.POST, "/api/tenant/install-link"),
            new Call(HttpMethod.GET, "/api/tenant/admin-ips"),
            new Call(HttpMethod.POST, "/api/tenant/admin-ips"),
            new Call(HttpMethod.DELETE, "/api/tenant/admin-ips/1"),
            new Call(HttpMethod.POST, "/api/tenant/users"),
            new Call(HttpMethod.GET, "/api/audit"),
            new Call(HttpMethod.GET, "/api/operations/selftest"),
            new Call(HttpMethod.POST, "/api/operations/selftest"),
            new Call(HttpMethod.PUT, "/api/me/webhook"),
            new Call(HttpMethod.POST, "/api/me/webhook/test"),
            new Call(HttpMethod.POST, "/api/me/hosts"),
            new Call(HttpMethod.DELETE, "/api/me/hosts/h1"));

    @Test
    void viewerIsForbiddenFromEveryResponseSettingAuditAndAccountApi() throws Exception {
        Account org = signup("role-matrix@edrdog.com");
        String viewer = sessionOf(org, "role-matrix-viewer@edrdog.com", "viewer");

        for (Call c : ADMIN_ONLY) {
            mvc.perform(request(c.method(), c.path()).with(as(viewer)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void viewerCanRead() throws Exception {
        Account org = signup("role-read@edrdog.com");
        String viewer = sessionOf(org, "role-read-viewer@edrdog.com", "viewer");

        mvc.perform(get("/api/auth/me").with(as(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("viewer"));
        mvc.perform(get("/api/alerts/rules").with(as(viewer))).andExpect(status().isOk());
        mvc.perform(get("/api/me/hosts").with(as(viewer))).andExpect(status().isOk());
    }

    @Test
    void adminCanUseTheAdminOnlyApis() throws Exception {
        Account org = signup("role-admin@edrdog.com");

        mvc.perform(get("/api/tenant/enroll-secret").with(as(org.token()))).andExpect(status().isOk());
        mvc.perform(get("/api/tenant/webhook").with(as(org.token()))).andExpect(status().isOk());
        mvc.perform(get("/api/tenant/admin-ips").with(as(org.token()))).andExpect(status().isOk());
        mvc.perform(get("/api/audit").with(as(org.token()))).andExpect(status().isOk());
    }

    @Test
    void anUnknownRoleGetsNothing() throws Exception {
        Account org = signup("role-guest@edrdog.com");
        String guest = sessionOf(org, "role-guest-user@edrdog.com", "guest");

        mvc.perform(get("/api/auth/me").with(as(guest))).andExpect(status().isForbidden());
    }

    @Test
    void deniedRequestIsRecordedWithTheRequiredRole() throws Exception {
        Account org = signup("role-audit@edrdog.com");
        String viewer = sessionOf(org, "role-audit-viewer@edrdog.com", "viewer");

        mvc.perform(get("/api/audit").with(as(viewer))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());

        JsonNode rows = audits(org, "?type=access_denied");
        assertEquals(1, rows.size());
        assertEquals("role-audit-viewer@edrdog.com", rows.get(0).get("actor").asText());
        assertEquals("failure", rows.get(0).get("result").asText());
        assertEquals(org.tenantId(), rows.get(0).get("tenantId").asLong());
    }

    @Test
    void adminCreatesAViewerInTheSameOrganizationAndTheViewerCanLogIn() throws Exception {
        Account org = signup("role-create@edrdog.com");

        MvcResult created = mvc.perform(post("/api/tenant/users").with(as(org.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new-viewer@edrdog.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new-viewer@edrdog.com"))
                .andExpect(jsonPath("$.role").value("viewer"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();
        assertFalse(created.getResponse().getContentAsString().contains(PASSWORD));

        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new-viewer@edrdog.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode n = om.readTree(login.getResponse().getContentAsString());
        assertEquals("viewer", n.get("role").asText());
        assertEquals(org.tenantId(), n.get("tenantId").asLong());
        // admin 이 만든 계정은 첫 로그인에서 패스워드를 바꿔야 한다(ADR 031). 바꾼 뒤에야 역할 판정이 난다.
        assertTrue(n.get("mustChangePassword").asBoolean());
        String viewerToken = n.get("token").asText();
        mvc.perform(post("/api/auth/password").with(as(viewerToken)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"N3w#Valid-Pw\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/audit").with(as(viewerToken))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").doesNotExist());

        JsonNode rows = audits(org, "?type=account_create");
        assertEquals(1, rows.size());
        assertEquals("new-viewer@edrdog.com", rows.get(0).get("target").asText());
        assertEquals(org.email(), rows.get(0).get("actor").asText());
    }

    @Test
    void roleInTheRequestBodyCannotMakeAnAdmin() throws Exception {
        Account org = signup("role-escalate@edrdog.com");

        mvc.perform(post("/api/tenant/users").with(as(org.token())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"sneaky@edrdog.com\",\"password\":\"" + PASSWORD + "\",\"role\":\"admin\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("viewer"));
    }

    @Test
    void creatingAViewerRejectsDuplicatesAndBadInput() throws Exception {
        Account org = signup("role-bad@edrdog.com");
        String ok = "{\"email\":\"dup-viewer@edrdog.com\",\"password\":\"" + PASSWORD + "\"}";

        mvc.perform(post("/api/tenant/users").with(as(org.token())).contentType(MediaType.APPLICATION_JSON).content(ok))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/tenant/users").with(as(org.token())).contentType(MediaType.APPLICATION_JSON).content(ok))
                .andExpect(status().isConflict());
        // 가입돼 있는 이메일(다른 조직 admin)도 같다
        mvc.perform(post("/api/tenant/users").with(as(org.token())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"role-bad@edrdog.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isConflict());
        for (String bad : List.of(
                "{\"email\":\"not-an-email\",\"password\":\"" + PASSWORD + "\"}",
                "{\"email\":\"short-pw@edrdog.com\",\"password\":\"1234567\"}",
                "{\"email\":\"no-pw@edrdog.com\"}",
                "{}")) {
            mvc.perform(post("/api/tenant/users").with(as(org.token())).contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
    }
}
