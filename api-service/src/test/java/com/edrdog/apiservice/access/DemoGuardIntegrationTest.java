package com.edrdog.apiservice.access;

import com.edrdog.apiservice.auth.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 공개 데모 계정(demo_account)은 관리 IP, 보안정책, 계정 생성을 바꿀 수 없고(403, 상태 그대로) 조회는 된다. 일반 admin 은 그대로다. */
class DemoGuardIntegrationTest extends AccessTestBase {

    private static final String POLICY = "{\"maxLoginFailures\":3,\"lockMinutes\":15,\"idleMinutes\":20}";

    private Account demoSignup(String email) throws Exception {
        Account a = signup(email);
        AppUser u = users.findByEmail(email).orElseThrow();
        u.markDemoAccount();
        users.save(u);
        return a;
    }

    @Test
    void demoAccountCannotChangeAdminIpsPolicyOrUsers() throws Exception {
        Account d = demoSignup("guard-demo@edrdog.com");
        long usersBefore = users.count();

        mvc.perform(post("/api/tenant/admin-ips").with(as(d.token())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ip\":\"127.0.0.1\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").exists());
        mvc.perform(delete("/api/tenant/admin-ips/1").with(as(d.token())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").exists());
        mvc.perform(put("/api/auth/security-policy").with(as(d.token())).contentType(MediaType.APPLICATION_JSON)
                        .content(POLICY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").exists());
        mvc.perform(post("/api/tenant/users").with(as(d.token())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"guard-new@edrdog.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").exists());

        // 상태가 그대로다
        mvc.perform(get("/api/tenant/admin-ips").with(as(d.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowedIps.length()").value(0));
        mvc.perform(get("/api/auth/security-policy").with(as(d.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.idleMinutes").value(10));
        assertEquals(usersBefore, users.count());
        assertEquals(true, users.findByEmail("guard-new@edrdog.com").isEmpty());
    }

    @Test
    void regularAdminStillChangesThem() throws Exception {
        Account a = signup("guard-regular@edrdog.com");

        mvc.perform(post("/api/tenant/admin-ips").with(as(a.token())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ip\":\"127.0.0.1\"}"))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/auth/security-policy").with(as(a.token())).contentType(MediaType.APPLICATION_JSON)
                        .content(POLICY))
                .andExpect(status().isOk());
        mvc.perform(post("/api/tenant/users").with(as(a.token())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"guard-viewer@edrdog.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated());
    }
}
