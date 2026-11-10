package com.edrdog.apiservice.access;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리 IP 제한(KISA EDR-BF-07): 조직별 허용 IP 목록. 비어 있으면 제한이 없고, 있으면 그 조직 계정의 로그인과
 * 모든 관리 API 를 다른 IP 에서 거부(403)하고 admin_ip_block 을 남긴다. 단일 IP 만 등록되고 자기 자신이 잠기는 변경은 거부된다.
 */
class AdminIpApiIntegrationTest extends AccessTestBase {

    private static final String ME = "127.0.0.1";       // MockMvc 기본 접속 IP
    private static final String OUTSIDER = "203.0.113.9";

    private long add(Account a, String ip, String fromIp) throws Exception {
        MvcResult r = mvc.perform(post("/api/tenant/admin-ips").with(as(a.token())).with(from(fromIp))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ip\":\"" + ip + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    private JsonNode list(Account a, String fromIp) throws Exception {
        MvcResult r = mvc.perform(get("/api/tenant/admin-ips").with(as(a.token())).with(from(fromIp)))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void emptyListMeansNoRestrictionAndShowsTheRequestersIp() throws Exception {
        Account a = signup("ip-empty@edrdog.com");

        JsonNode body = list(a, OUTSIDER);
        assertEquals(0, body.get("allowedIps").size());
        assertEquals(OUTSIDER, body.get("yourIp").asText());
        mvc.perform(get("/api/auth/me").with(as(a.token())).with(from(OUTSIDER))).andExpect(status().isOk());
    }

    @Test
    void firstIpMustBeTheRequestersOwnIpSoNobodyLocksThemselvesOut() throws Exception {
        Account a = signup("ip-first@edrdog.com");

        mvc.perform(post("/api/tenant/admin-ips").with(as(a.token()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ip\":\"198.51.100.7\"}"))
                .andExpect(status().isConflict());
        assertEquals(0, list(a, ME).get("allowedIps").size());

        add(a, ME, ME);
        JsonNode ips = list(a, ME).get("allowedIps");
        assertEquals(1, ips.size());
        assertEquals(ME, ips.get(0).get("ip").asText());
    }

    @Test
    void otherIpIsRejectedWith403AndRecorded() throws Exception {
        Account a = signup("ip-block@edrdog.com");
        add(a, ME, ME);
        add(a, "10.0.0.5", ME);

        mvc.perform(get("/api/auth/me").with(as(a.token())).with(from(OUTSIDER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(get("/api/auth/me").with(as(a.token())).with(from("10.0.0.5"))).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").with(as(a.token())).with(from(ME))).andExpect(status().isOk());

        JsonNode rows = audits(a, "?type=admin_ip_block");
        assertEquals(1, rows.size());
        assertEquals("failure", rows.get(0).get("result").asText());
        assertEquals(OUTSIDER, rows.get(0).get("ip").asText());
        assertEquals(a.email(), rows.get(0).get("actor").asText());
        assertEquals(a.tenantId(), rows.get(0).get("tenantId").asLong());
    }

    @Test
    void everyManagementApiIsCoveredNotOnlyOne() throws Exception {
        Account a = signup("ip-all@edrdog.com");
        add(a, ME, ME);

        for (String path : List.of("/api/audit", "/api/me/hosts", "/api/tenant/enroll-secret",
                "/api/operations/selftest", "/api/alerts/rules", "/api/tenant/admin-ips")) {
            mvc.perform(get(path).with(as(a.token())).with(from(OUTSIDER))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/tenant/install-link").with(as(a.token())).with(from(OUTSIDER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void otherTenantsAreNotAffectedByTheList() throws Exception {
        Account restricted = signup("ip-restricted@edrdog.com");
        Account open = signup("ip-open@edrdog.com");
        add(restricted, ME, ME);

        mvc.perform(get("/api/auth/me").with(as(open.token())).with(from(OUTSIDER))).andExpect(status().isOk());
    }

    @Test
    void loginFromOtherIpIsRejectedWithoutIssuingASession() throws Exception {
        Account a = signup("ip-login@edrdog.com");
        add(a, ME, ME);
        long sessionsBefore = sessions.count();
        String good = "{\"email\":\"ip-login@edrdog.com\",\"password\":\"" + PASSWORD + "\"}";
        String bad = "{\"email\":\"ip-login@edrdog.com\",\"password\":\"wrong-pass-1\"}";

        mvc.perform(post("/api/auth/login").with(from(OUTSIDER)).contentType(MediaType.APPLICATION_JSON).content(good))
                .andExpect(status().isForbidden());
        // 비밀번호가 틀려도 같다. 허용 밖 IP 에서는 비밀번호를 맞춰 볼 수조차 없다.
        mvc.perform(post("/api/auth/login").with(from(OUTSIDER)).contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isForbidden());
        assertEquals(sessionsBefore, sessions.count());
        // 이 로그인이 가입 때 받은 세션을 끊으므로(중복 접속 차단, ADR 032) 조회는 이 토큰으로 한다
        MvcResult ok = mvc.perform(post("/api/auth/login").with(from(ME)).contentType(MediaType.APPLICATION_JSON)
                        .content(good))
                .andExpect(status().isOk()).andReturn();
        Account reader = new Account(a.email(), om.readTree(ok.getResponse().getContentAsString()).get("token").asText(),
                a.tenantId());

        JsonNode rows = audits(reader, "?type=admin_ip_block");
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).get("detail").asText().contains("login"));
    }

    @Test
    void rangesWildcardsAndNamesAreRejected() throws Exception {
        Account a = signup("ip-range@edrdog.com");

        for (String bad : List.of("10.0.0.0/24", "10.0.0.*", "10.0.0.1-10.0.0.9", "10.0.0.1,10.0.0.2", "example.com",
                "0.0.0.0/0", "::/0", "999.1.1.1", "01.2.3.4", "fe80::1%eth0", "", "  ")) {
            mvc.perform(post("/api/tenant/admin-ips").with(as(a.token()))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"ip\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/tenant/admin-ips").with(as(a.token()))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        assertEquals(0, list(a, ME).get("allowedIps").size());
    }

    @Test
    void ipv6SingleAddressIsStoredInCanonicalFormAndMatchesTomcatStyleRemoteAddress() throws Exception {
        Account a = signup("ip-v6@edrdog.com");
        String loopbackAsTomcatReports = "0:0:0:0:0:0:0:1";

        add(a, "::1", loopbackAsTomcatReports);
        add(a, "2001:DB8:0:0:0:0:0:1", loopbackAsTomcatReports);

        JsonNode ips = list(a, loopbackAsTomcatReports).get("allowedIps");
        assertEquals(List.of("::1", "2001:db8::1"), List.of(ips.get(0).get("ip").asText(), ips.get(1).get("ip").asText()));
        mvc.perform(get("/api/auth/me").with(as(a.token())).with(from("2001:db8::1"))).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").with(as(a.token())).with(from("2001:db8::2"))).andExpect(status().isForbidden());
    }

    @Test
    void duplicateIpIsConflict() throws Exception {
        Account a = signup("ip-dup@edrdog.com");
        add(a, ME, ME);

        mvc.perform(post("/api/tenant/admin-ips").with(as(a.token()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ip\":\"127.0.0.1\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void removingTheRequestersOwnIpWhileOthersRemainIsRejected() throws Exception {
        Account a = signup("ip-remove@edrdog.com");
        long mine = add(a, ME, ME);
        long other = add(a, "10.0.0.5", ME);

        mvc.perform(delete("/api/tenant/admin-ips/" + mine).with(as(a.token()))).andExpect(status().isConflict());
        mvc.perform(delete("/api/tenant/admin-ips/" + other).with(as(a.token()))).andExpect(status().isNoContent());
        // 마지막 하나를 지우면 목록이 비어 제한이 풀린다
        mvc.perform(delete("/api/tenant/admin-ips/" + mine).with(as(a.token()))).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").with(as(a.token())).with(from(OUTSIDER))).andExpect(status().isOk());
    }

    @Test
    void unknownOrOtherTenantsIdIsNotFound() throws Exception {
        Account a = signup("ip-404a@edrdog.com");
        Account b = signup("ip-404b@edrdog.com");
        long idOfB = add(b, ME, ME);

        mvc.perform(delete("/api/tenant/admin-ips/" + idOfB).with(as(a.token()))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/tenant/admin-ips/999999").with(as(a.token()))).andExpect(status().isNotFound());
        assertEquals(1, list(b, ME).get("allowedIps").size());
    }

    @Test
    void forwardedForHeaderIsNotTrusted() throws Exception {
        Account a = signup("ip-xff@edrdog.com");
        add(a, ME, ME);

        mvc.perform(get("/api/auth/me").with(as(a.token())).with(from(OUTSIDER)).header("X-Forwarded-For", ME))
                .andExpect(status().isForbidden());
    }

    @Test
    void viewerCannotUseTheListAndChangesAreAudited() throws Exception {
        Account a = signup("ip-audit@edrdog.com");
        String viewer = sessionOf(a, "ip-audit-viewer@edrdog.com", "viewer");

        mvc.perform(get("/api/tenant/admin-ips").with(as(viewer))).andExpect(status().isForbidden());
        mvc.perform(post("/api/tenant/admin-ips").with(as(viewer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"ip\":\"127.0.0.1\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/tenant/admin-ips/1").with(as(viewer))).andExpect(status().isForbidden());

        long id = add(a, ME, ME);
        mvc.perform(delete("/api/tenant/admin-ips/" + id).with(as(a.token()))).andExpect(status().isNoContent());

        JsonNode rows = audits(a, "?type=admin_ip_change&order=asc");
        assertEquals(2, rows.size());
        assertEquals(ME, rows.get(0).get("target").asText());
        assertTrue(rows.get(0).get("detail").asText().contains("add"));
        assertTrue(rows.get(1).get("detail").asText().contains("remove"));
        assertEquals(a.email(), rows.get(0).get("actor").asText());
    }

    @Test
    void agentAndInternalApisAreNotRestrictedByTheList() throws Exception {
        Account a = signup("ip-internal@edrdog.com");
        add(a, ME, ME);

        // 내부 키로 오는 서비스 간 호출은 관리 단말이 아니다. 시크릿이 틀려 404 이지 IP 로 막힌 403 이 아니다.
        mvc.perform(post("/api/internal/agent/resolve-tenant").with(from(OUTSIDER))
                        .header("X-Internal-Key", "dev-internal-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enrollSecret\":\"nope\"}"))
                .andExpect(status().isNotFound());
    }
}
