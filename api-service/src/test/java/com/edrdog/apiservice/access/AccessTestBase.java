package com.edrdog.apiservice.access;

import com.edrdog.apiservice.auth.AppUser;
import com.edrdog.apiservice.auth.SessionRepository;
import com.edrdog.apiservice.auth.UserRepository;
import com.edrdog.apiservice.auth.UserSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 접근통제 통합 테스트 공통 준비. 같은 설정이라 스프링 컨텍스트를 한 번만 띄운다.
 * 감사기록 개수를 세는 테스트에 서버 전체 자체시험 기록이 섞이지 않게 기동 시 자체시험은 끈다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {"spring.kafka.listener.auto-startup=false", "edrdog.selftest.run-on-startup=false"})
abstract class AccessTestBase {

    static final String PASSWORD = "password1";

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper om;
    @Autowired
    protected UserRepository users;
    @Autowired
    protected SessionRepository sessions;

    /** 가입한 admin 의 세션 토큰과 조직. */
    record Account(String email, String token, long tenantId) {
    }

    protected Account signup(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode n = om.readTree(r.getResponse().getContentAsString());
        return new Account(email, n.get("token").asText(), n.get("tenantId").asLong());
    }

    /** 같은 조직에 role 역할 계정을 DB 에 직접 만들고 그 세션 토큰을 돌려준다(역할 만드는 API 와 무관하게 쓴다). */
    protected String sessionOf(Account org, String email, String role) {
        AppUser u = users.save(AppUser.of(email, "hash", org.tenantId(), role, Instant.now()));
        String token = "tok-" + email;
        sessions.save(UserSession.of(token, u.getId(), u.getTenantId(), Instant.now().plusSeconds(600)));
        return token;
    }

    /** 세션 토큰과 프론트 API 키를 함께 실어 보낸다(/api/tenant/** 는 둘 다 요구한다). */
    protected static RequestPostProcessor as(String token) {
        return req -> {
            req.addHeader("Authorization", "Bearer " + token);
            req.addHeader("X-API-Key", "dev-api-key");
            return req;
        };
    }

    /** 요청이 들어온 접속 IP. MockMvc 기본은 127.0.0.1 이다. */
    protected static RequestPostProcessor from(String ip) {
        return req -> {
            req.setRemoteAddr(ip);
            return req;
        };
    }

    /** 감사기록 조회(admin). 접속 IP 제한에 안 걸리도록 기본 IP 로 부른다. */
    protected JsonNode audits(Account admin, String query) throws Exception {
        MvcResult r = mvc.perform(get("/api/audit" + query).with(as(admin.token())))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString());
    }
}
