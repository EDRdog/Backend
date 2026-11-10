package com.edrdog.collectorservice.agent;

import com.edrdog.collectorservice.responder.ResponderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 에이전트 감사기록 수신(POST /api/agent/audit): 인증, 상한, 검증, api-service 로 전달.
 * api-service(AuditClient), enroll secret 검증(TenantResolverClient), responder 는 목이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class AgentAuditIntegrationTest {

    private static final String SECRET = "tenant-7-secret";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @MockitoBean
    private EventsProducer producer;

    @MockitoBean
    private TenantResolverClient tenants;

    @MockitoBean
    private ResponderClient responder;

    @MockitoBean
    private AuditClient audit;

    private String enroll(String host) throws Exception {
        when(tenants.resolve(SECRET)).thenReturn(Optional.of(7L));
        MvcResult r = mvc.perform(post("/api/agent/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enroll_secret\":\"" + SECRET + "\",\"host_identifier\":\"" + host + "\","
                                + "\"platform\":\"darwin\",\"agent_version\":\"0.1.0\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(r.getResponse().getContentAsString()).get("node_key").asText();
    }

    private static String event(String type, String result, String detail) {
        return "{\"ts\":1785341400000,\"type\":\"" + type + "\",\"result\":\"" + result + "\",\"detail\":\"" + detail + "\"}";
    }

    @SuppressWarnings("unchecked")
    private List<AuditEvent> forwardedEvents(long tenantId, String host) {
        ArgumentCaptor<List<AuditEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(audit).forward(eq(tenantId), eq(host), captor.capture());
        return captor.getValue();
    }

    @Test
    void auditForwardsEventsWithTenantAndHostResolvedFromNodeKey() throws Exception {
        String nodeKey = enroll("mac-audit-1");
        // 에이전트가 본문에 다른 기기와 조직을 대도 믿지 않는다.
        String body = "{\"tenantId\":999,\"host\":\"spoofed\",\"events\":[" + event("agent_start", "success", "version=0.1.0") + "]}";

        mvc.perform(post("/api/agent/audit")
                        .header("X-Node-Key", nodeKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1));

        List<AuditEvent> sent = forwardedEvents(7L, "mac-audit-1");
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).type()).isEqualTo("agent_start");
        assertThat(sent.get(0).result()).isEqualTo("success");
        assertThat(sent.get(0).ts()).isEqualTo(1785341400000L);
        assertThat(sent.get(0).detail()).isEqualTo("version=0.1.0");
    }

    @Test
    void auditRejectsMissingNodeKeyWith401() throws Exception {
        mvc.perform(post("/api/agent/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[" + event("agent_start", "success", "") + "]}"))
                .andExpect(status().isUnauthorized());

        verify(audit, never()).forward(anyLong(), anyString(), anyList());
    }

    @Test
    void auditRejectsWrongNodeKeyWith401() throws Exception {
        mvc.perform(post("/api/agent/audit")
                        .header("X-Node-Key", "not-a-real-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[" + event("agent_start", "success", "") + "]}"))
                .andExpect(status().isUnauthorized());

        verify(audit, never()).forward(anyLong(), anyString(), anyList());
    }

    /** 요청당 100건이 상한이다. 에이전트는 100건씩 나눠 보낸다. */
    @Test
    void auditRejectsMoreThan100EventsWith400() throws Exception {
        String nodeKey = enroll("mac-audit-2");
        List<String> events = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            events.add(event("agent_start", "success", ""));
        }

        mvc.perform(post("/api/agent/audit")
                        .header("X-Node-Key", nodeKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[" + String.join(",", events) + "]}"))
                .andExpect(status().isBadRequest());

        verify(audit, never()).forward(anyLong(), anyString(), anyList());
    }

    @Test
    void auditAcceptsExactly100Events() throws Exception {
        String nodeKey = enroll("mac-audit-3");
        List<String> events = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            events.add(event("agent_start", "success", ""));
        }

        mvc.perform(post("/api/agent/audit")
                        .header("X-Node-Key", nodeKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[" + String.join(",", events) + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(100));
    }

    @Test
    void auditRejectsMissingEventsWith400() throws Exception {
        String nodeKey = enroll("mac-audit-4");

        mvc.perform(post("/api/agent/audit")
                        .header("X-Node-Key", nodeKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(audit, never()).forward(anyLong(), anyString(), anyList());
    }

    /** 모양이 틀린 한 건이 배치 전체를 막으면 정상 기록까지 영영 못 간다. 그 건만 버린다. */
    @Test
    void auditDropsMalformedEventsAndKeepsTheRest() throws Exception {
        String nodeKey = enroll("mac-audit-5");
        String body = "{\"events\":["
                + event("agent_start", "success", "ok") + ","
                + event("agent_stop", "maybe", "결과 값이 틀리다") + ","
                + event("", "success", "type 이 비었다") + ","
                + event("Bad Type!", "success", "type 모양이 틀리다") + ","
                + event("sensor_stop", "failure", "sensor=eslogger") + "]}";

        mvc.perform(post("/api/agent/audit")
                        .header("X-Node-Key", nodeKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(2));

        List<AuditEvent> sent = forwardedEvents(7L, "mac-audit-5");
        assertThat(sent).extracting(AuditEvent::type).containsExactly("agent_start", "sensor_stop");
    }

    @Test
    void auditTruncatesOverlongDetail() throws Exception {
        String nodeKey = enroll("mac-audit-6");

        mvc.perform(post("/api/agent/audit")
                        .header("X-Node-Key", nodeKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[" + event("security_check", "failure", "x".repeat(5000)) + "]}"))
                .andExpect(status().isOk());

        assertThat(forwardedEvents(7L, "mac-audit-6").get(0).detail()).hasSize(1000);
    }

    @Test
    void auditWithOnlyMalformedEventsDoesNotCallApiService() throws Exception {
        String nodeKey = enroll("mac-audit-7");

        mvc.perform(post("/api/agent/audit")
                        .header("X-Node-Key", nodeKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[" + event("agent_start", "maybe", "") + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0));

        verify(audit, never()).forward(anyLong(), anyString(), anyList());
    }

    /** 감사기록은 잃으면 안 된다. api-service 가 못 받으면 503 이라 에이전트가 같은 기록을 다시 보낸다. */
    @Test
    void auditReturns503WhenApiServiceUnavailable() throws Exception {
        String nodeKey = enroll("mac-audit-8");
        doThrow(new AuditForwardException(new RuntimeException("api down"))).when(audit).forward(anyLong(), anyString(), anyList());

        mvc.perform(post("/api/agent/audit")
                        .header("X-Node-Key", nodeKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[" + event("agent_start", "success", "") + "]}"))
                .andExpect(status().isServiceUnavailable());
    }

    /** 다른 에이전트 경로처럼 본문 8MB 상한이 걸린다. 없으면 인증된 에이전트 하나가 힙을 터뜨린다. */
    @Test
    void auditRejectsBodyOver8MbWith413() throws Exception {
        String nodeKey = enroll("mac-audit-9");
        String big = "{\"events\":[" + event("agent_start", "success", "a".repeat(9 * 1024 * 1024)) + "]}";

        mvc.perform(post("/api/agent/audit")
                        .header("X-Node-Key", nodeKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(big))
                .andExpect(status().isPayloadTooLarge());

        verify(audit, never()).forward(anyLong(), anyString(), anyList());
    }
}
