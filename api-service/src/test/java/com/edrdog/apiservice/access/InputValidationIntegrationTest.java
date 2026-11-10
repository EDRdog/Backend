package com.edrdog.apiservice.access;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리 API 입력값 검증(KISA EDR-BF-19.1): 길이, 허용되지 않는 문자, 숫자 범위, 본문 형식을 400 으로 거절하고
 * 오류 본문에 내부 정보를 싣지 않는다. 이미 검증하던 것(offset 상한, status, bucket 등)은 각자의 테스트가 본다.
 */
class InputValidationIntegrationTest extends AccessTestBase {

    private static final String LONG = "a".repeat(300);

    private static String json(String email, String password, String orgName) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\""
                + (orgName != null ? ",\"orgName\":\"" + orgName + "\"" : "") + "}";
    }

    @Test
    void overlongQueryParametersAndPathVariablesAreRejected() throws Exception {
        Account a = signup("input-long@edrdog.com");

        mvc.perform(get("/api/events").param("host", LONG).with(as(a.token()))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(get("/api/alerts").param("domain", LONG).with(as(a.token()))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/search").param("q", LONG).with(as(a.token()))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/hosts/" + LONG + "/process-tree").with(as(a.token()))).andExpect(status().isBadRequest());
    }

    @Test
    void controlCharactersInParametersAreRejected() throws Exception {
        Account a = signup("input-ctrl@edrdog.com");

        for (String bad : List.of("a\u0000b", "a\nb", "a\rb", "a\u007fb")) {
            mvc.perform(get("/api/events").param("host", bad).with(as(a.token()))).andExpect(status().isBadRequest());
        }
    }

    @Test
    void timeRangeMustBeNonNegativeAndOrdered() throws Exception {
        Account a = signup("input-time@edrdog.com");

        mvc.perform(get("/api/events").param("from", "-5").with(as(a.token()))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/events").param("to", "-1").with(as(a.token()))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/events").param("from", "2000").param("to", "1000").with(as(a.token())))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/audit").param("from", "1000").param("to", "2000").with(as(a.token())))
                .andExpect(status().isOk());
    }

    @Test
    void signupRejectsBadEmailOverlongFieldsAndControlCharacters() throws Exception {
        for (String bad : List.of(
                json("a b@edrdog.com", "Tr1cky#Pwd", null),
                json("<script>@edrdog.com", "Tr1cky#Pwd", null),
                json("a'--@edrdog.com", "Tr1cky#Pwd", null),
                json("x".repeat(250) + "@edrdog.com", "Tr1cky#Pwd", null),
                json("ok1@edrdog.com", "p".repeat(80), null),
                json("ok2@edrdog.com", "Tr1cky#Pwd", LONG),
                json("ok3@edrdog.com", "Tr1cky#Pwd", "org\\u0000name"))) {
            mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void loginRejectsOverlongInputWithoutChangingTheUsualFailure() throws Exception {
        signup("input-login@edrdog.com");

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(LONG + "@edrdog.com", "Tr1cky#Pwd", null))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json("input-login@edrdog.com", "p".repeat(200), null))).andExpect(status().isBadRequest());
        // 형식이 이상한 이메일은 가입 검증이 아니라 로그인 실패(401)다. 옛 데모 계정 이메일("test")이 로그인돼야 해서 형식은 안 본다.
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json("no-such-user", "Tr1cky#Pwd", null))).andExpect(status().isUnauthorized());
    }

    @Test
    void webhookUrlMustBeAShortWellFormedHttpsUrl() throws Exception {
        Account a = signup("input-webhook@edrdog.com");

        for (String bad : List.of("https://", "https://" + LONG + ".example.com/x", "https://hooks.slack.com/a b",
                "https://hooks.slack.com/a\\nb", "https:///path-only")) {
            mvc.perform(put("/api/tenant/webhook").with(as(a.token())).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"webhookUrl\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(put("/api/me/webhook").with(as(a.token())).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"webhookUrl\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/tenant/webhook").with(as(a.token())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"webhookUrl\":\"https://hooks.slack.com/services/T0/B0/xyz\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void registeredHostMustBeShortAndFreeOfControlCharacters() throws Exception {
        Account a = signup("input-host@edrdog.com");

        for (String bad : List.of(LONG, "bad\\nhost", "bad\\u0000host")) {
            mvc.perform(post("/api/me/hosts").with(as(a.token())).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"host\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/me/hosts").with(as(a.token())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"macbook-01\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void malformedJsonIsRejectedWithoutFrameworkDetails() throws Exception {
        Account a = signup("input-json@edrdog.com");

        mvc.perform(put("/api/tenant/webhook").with(as(a.token())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"webhookUrl\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(content().string(not(containsString("com.fasterxml"))))
                .andExpect(content().string(not(containsString("Exception"))));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("Exception"))));
    }
}
