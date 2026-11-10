package com.edrdog.apiservice.selftest.web;

import com.edrdog.apiservice.auth.AppUser;
import com.edrdog.apiservice.auth.UserRepository;
import com.edrdog.apiservice.selftest.SelfTestRun;
import com.edrdog.apiservice.selftest.SelfTestRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 전체 컨텍스트로 자체시험 API 배선을 본다. 세션 Bearer 로만 인증하고(/api/operations 는 X-API-Key 예외),
 * 로그인한 admin 만 조회·요청할 수 있다. 기동 시 실행과 주기 등록도 여기서 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class SelfTestApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @Autowired
    private SelfTestRunRepository runs;

    @Autowired
    private UserRepository users;

    @Autowired
    private BCryptPasswordEncoder encoder;

    @Autowired
    private ScheduledAnnotationBeanPostProcessor scheduler;

    private String signupAdmin(String email) throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Tr1cky#Pwd\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    /** 가입은 항상 admin 이라 admin 이 아닌 유저는 DB 에 직접 넣고 로그인한다. */
    private String loginNonAdmin(String email) throws Exception {
        users.save(AppUser.of(email, encoder.encode("Tr1cky#Pwd"), 1L, "viewer", Instant.now()));
        MvcResult res = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Tr1cky#Pwd\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void startupRunIsRecordedWhenTheAppComesUp() {
        assertTrue(runs.findAll().stream().anyMatch(r -> r.getTriggerType() == SelfTestRun.Trigger.STARTUP));
    }

    @Test
    void periodicRunIsRegisteredWithTheScheduler() {
        boolean registered = scheduler.getScheduledTasks().stream()
                .anyMatch(t -> t.toString().contains("SelfTestService.runScheduled"));
        assertTrue(registered);
    }

    @Test
    void withoutTokenGetAndPostReturn401() throws Exception {
        mvc.perform(get("/api/operations/selftest")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/operations/selftest")).andExpect(status().isUnauthorized());
    }

    @Test
    void nonAdminGetAndPostReturn403() throws Exception {
        String token = loginNonAdmin("viewer-selftest@edrdog.com");

        mvc.perform(get("/api/operations/selftest").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/operations/selftest").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminRunsOnRequestAndSeesItInTheList() throws Exception {
        String token = signupAdmin("admin-selftest@edrdog.com");

        mvc.perform(post("/api/operations/selftest").header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trigger").value("MANUAL"))
                .andExpect(jsonPath("$.requestedBy").value("admin-selftest@edrdog.com"))
                .andExpect(jsonPath("$.status").value("PASS"))
                .andExpect(jsonPath("$.items[?(@.name=='database')].status").value("PASS"))
                .andExpect(jsonPath("$.items[?(@.name=='baseline')].status").value("SKIP"));

        mvc.perform(get("/api/operations/selftest").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].trigger").value("MANUAL"))
                .andExpect(jsonPath("$[0].items[?(@.category=='integrity')]").isNotEmpty())
                .andExpect(jsonPath("$[0].items[?(@.category=='selftest')]").isNotEmpty());
    }

    @Test
    void listIsNewestFirstAndLimited() throws Exception {
        String token = signupAdmin("admin-selftest-limit@edrdog.com");
        mvc.perform(post("/api/operations/selftest").header("Authorization", "Bearer " + token));
        mvc.perform(post("/api/operations/selftest").header("Authorization", "Bearer " + token));

        mvc.perform(get("/api/operations/selftest?limit=1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].trigger").value("MANUAL"));
    }
}
