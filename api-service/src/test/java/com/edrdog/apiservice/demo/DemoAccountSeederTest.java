package com.edrdog.apiservice.demo;

import com.edrdog.apiservice.auth.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 시드 플래그를 켜고 전체 컨텍스트를 띄워, 부팅 직후 데모 계정으로 실제 로그인이 되는지 검증한다.
 * tenant 는 네이티브 INSERT 로 넣기 때문에 컬럼명이 어긋나면 여기서 바로 드러난다(발표 당일 대신).
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {
        "spring.kafka.listener.auto-startup=false",
        "edrdog.selftest.run-on-startup=false",
        "edrdog.demo.seed=true"
})
class DemoAccountSeederTest {

    private static final String LOGIN_BODY = "{\"email\":\"test@edrdog.local\",\"password\":\"1234\"}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DemoAccountSeeder seeder;

    @Autowired
    private ObjectMapper om;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository users;

    @Test
    void 부팅_직후_test_1234_로_로그인되고_tenantId_는_고정값이다() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.email").value("test@edrdog.local"))
                .andExpect(jsonPath("$.tenantId").value((int) DemoAccountSeeder.TENANT_ID))
                .andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    private String demoToken() throws Exception {
        return om.readTree(mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void demoAccountIsExemptFromForcedPasswordChange() throws Exception {
        String token = demoToken();

        mvc.perform(get("/api/audit").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        // 표시가 켜져 있어도 데모 계정은 강제하지 않는다
        jdbc.update("update users set must_change_password = true where email = ?", DemoAccountSeeder.EMAIL);
        mvc.perform(get("/api/audit").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        jdbc.update("update users set must_change_password = false where email = ?", DemoAccountSeeder.EMAIL);
    }

    @Test
    void demoAccountLoginsDoNotEndEachOther() throws Exception {
        String first = demoToken();
        String second = demoToken();

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + first)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + second)).andExpect(status().isOk());
    }

    @Test
    void demoAccountIsNeverLockedByWrongPasswords() throws Exception {
        for (int i = 0; i < 7; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"test@edrdog.local\",\"password\":\"wrong-pw-" + i + "\"}"))
                    .andExpect(status().isUnauthorized());
        }

        demoToken();
    }

    @Test
    void demoAccountPasswordCannotBeChanged() throws Exception {
        String token = demoToken();

        mvc.perform(post("/api/auth/password").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"1234\",\"newPassword\":\"N3w#Valid-Pw\"}"))
                .andExpect(status().isBadRequest());

        demoToken();   // 그대로 로그인된다
    }

    @Test
    void seedingAgainMarksAnExistingAccountAsDemo() {
        jdbc.update("update users set demo_account = false where email = ?", DemoAccountSeeder.EMAIL);

        seeder.seed();

        assertTrue(users.findByEmail(DemoAccountSeeder.EMAIL).orElseThrow().isDemoAccount());
    }

    @Test
    void 시더를_다시_돌려도_중복_없이_로그인이_유지된다() throws Exception {
        seeder.seed();

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value((int) DemoAccountSeeder.TENANT_ID));
    }

    @Test
    void 잘못된_비밀번호는_그대로_거부된다() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@edrdog.local\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }
}
