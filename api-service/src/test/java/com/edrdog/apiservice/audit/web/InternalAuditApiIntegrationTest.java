package com.edrdog.apiservice.audit.web;

import com.edrdog.apiservice.audit.AuditFilter;
import com.edrdog.apiservice.audit.AuditRecord;
import com.edrdog.apiservice.audit.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/internal/audit(collector 가 에이전트 감사기록을 넘기는 내부 API) 배선: 인증, 받아 저장, 입력 상한.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class InternalAuditApiIntegrationTest {

    private static final String PATH = "/api/internal/audit";
    private static final String INTERNAL_KEY = "dev-internal-key";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private AuditService audit;

    private MockHttpServletRequestBuilder send(String body) {
        return post(PATH).header("X-Internal-Key", INTERNAL_KEY).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String body(long tenant, String host, String... events) {
        return "{\"tenantId\":" + tenant + ",\"host\":\"" + host + "\",\"events\":[" + String.join(",", events) + "]}";
    }

    private static String ev(long ts, String type, String result, String detail) {
        return "{\"ts\":" + ts + ",\"type\":\"" + type + "\",\"result\":\"" + result + "\",\"detail\":\"" + detail + "\"}";
    }

    private List<AuditRecord> stored(long tenant) {
        return audit.search(new AuditFilter(tenant, null, null, null, null, null, true, 1000, 0, false)).items();
    }

    @Test
    void acceptedEventsAreStoredAsAgentRecordsOfThatTenantAndHost() throws Exception {
        long now = System.currentTimeMillis();
        mvc.perform(send(body(9201, "host-1",
                        ev(now - 2000, "agent_start", "success", "v1"),
                        ev(now - 1000, "integrity_check_result", "failure", "binary hash mismatch"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(2));

        List<AuditRecord> rows = stored(9201);
        assertEquals(2, rows.size());
        AuditRecord first = rows.get(0);
        assertEquals("AGENT", first.getActorType().name());
        assertEquals("host-1", first.getActor());
        assertEquals("agent_start", first.getEventType());
        assertEquals("SUCCESS", first.getResult().name());
        assertEquals(Instant.ofEpochMilli(now - 2000), first.getTs());
        assertEquals("FAILURE", rows.get(1).getResult().name());
        assertEquals("binary hash mismatch", rows.get(1).getDetail());
    }

    @Test
    void agentRecordsAreVisibleToTheTenantAdmin() throws Exception {
        // 가입으로 tenant 를 만들고, 그 tenant 의 admin 이 에이전트 기록을 조회한다.
        MvcResult su = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"agent-view@edrdog.com\",\"password\":\"password1\"}")).andReturn();
        JsonNode n = om.readTree(su.getResponse().getContentAsString());
        String token = n.get("token").asText();
        long tenant = n.get("tenantId").asLong();
        mvc.perform(send(body(tenant, "host-v", ev(System.currentTimeMillis() - 1, "agent_stop", "success", "bye"))))
                .andExpect(status().isOk());

        MvcResult r = mvc.perform(get("/api/audit?type=agent_stop").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        JsonNode rows = om.readTree(r.getResponse().getContentAsString());
        assertEquals(1, rows.size());
        assertEquals("agent", rows.get(0).get("actorType").asText());
        assertEquals("host-v", rows.get(0).get("actor").asText());
    }

    @Test
    void withoutInternalKeyItIsUnauthorizedAndNothingIsStored() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(9202, "h", ev(1000, "agent_start", "success", "x"))))
                .andExpect(status().isUnauthorized());
        assertEquals(0, stored(9202).size());
    }

    @Test
    void frontendApiKeyAloneIsNotEnough() throws Exception {
        mvc.perform(post(PATH).header("X-API-Key", "dev-api-key").contentType(MediaType.APPLICATION_JSON)
                        .content(body(9203, "h", ev(1000, "agent_start", "success", "x"))))
                .andExpect(status().isUnauthorized());
        assertEquals(0, stored(9203).size());
    }

    @Test
    void wrongInternalKeyIsUnauthorized() throws Exception {
        mvc.perform(post(PATH).header("X-Internal-Key", "nope").contentType(MediaType.APPLICATION_JSON)
                        .content(body(9204, "h", ev(1000, "agent_start", "success", "x"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void moreThanTheEventLimitIsRejectedWholesale() throws Exception {
        String[] events = new String[InternalAuditController.MAX_EVENTS + 1];
        Arrays.fill(events, ev(1000, "agent_start", "success", "x"));

        mvc.perform(send(body(9205, "h", events))).andExpect(status().isBadRequest());
        assertEquals(0, stored(9205).size());
    }

    @Test
    void exactlyTheEventLimitIsAccepted() throws Exception {
        String[] events = new String[InternalAuditController.MAX_EVENTS];
        Arrays.fill(events, ev(1000, "agent_start", "success", "x"));

        mvc.perform(send(body(9206, "h", events))).andExpect(status().isOk());
        assertEquals(InternalAuditController.MAX_EVENTS, stored(9206).size());
    }

    @Test
    void overlongDetailIsCutAndOverlongHostOrTypeIsRejected() throws Exception {
        mvc.perform(send(body(9207, "h", ev(1000, "agent_start", "success", "d".repeat(5000)))))
                .andExpect(status().isOk());
        assertEquals(AuditService.MAX_DETAIL, stored(9207).get(0).getDetail().length());

        mvc.perform(send(body(9208, "h".repeat(AuditService.MAX_ACTOR + 1),
                ev(1000, "agent_start", "success", "x")))).andExpect(status().isBadRequest());
        mvc.perform(send(body(9208, "h", ev(1000, "t".repeat(AuditService.MAX_TYPE + 1), "success", "x"))))
                .andExpect(status().isBadRequest());
        assertEquals(0, stored(9208).size());
    }

    @Test
    void malformedBatchesAreRejectedAndNothingIsPartiallyStored() throws Exception {
        String good = ev(1000, "agent_start", "success", "x");
        List<String> bad = List.of(
                body(9209, "h", good, ev(1000, "agent_start", "maybe", "x")),   // 모르는 결과
                body(9209, "h", good, ev(1000, "Bad Type!", "success", "x")),   // 유형 형식
                body(9209, "h", good, ev(0, "agent_start", "success", "x")),    // 시각 없음
                body(9209, "  ", good),                                         // host 없음
                body(9209, "h"),                                                // 빈 events
                "{\"host\":\"h\",\"events\":[" + good + "]}",                   // tenantId 없음
                "{\"tenantId\":9209,\"host\":\"h\"}",                           // events 없음
                "not json");
        for (String b : bad) {
            mvc.perform(send(b)).andExpect(status().isBadRequest());
        }
        assertEquals(0, stored(9209).size());
    }

    @Test
    void futureTimestampsAreClampedToReceiveTime() throws Exception {
        long future = System.currentTimeMillis() + 86_400_000L;
        mvc.perform(send(body(9210, "h", ev(future, "agent_start", "success", "x")))).andExpect(status().isOk());

        assertTrue(stored(9210).get(0).getTs().toEpochMilli() < future);
    }
}
