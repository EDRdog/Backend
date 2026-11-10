package com.edrdog.apiservice.audit.web;

import com.edrdog.apiservice.audit.AuditFilter;
import com.edrdog.apiservice.audit.AuditService;
import com.edrdog.apiservice.notify.SlackWebhookClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.ResourceAccessException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 에이전트가 올린 감사기록이 조직 webhook 통보로 이어지는 배선(KISA BF-10.2, BF-11.2). Slack 은 목이다. */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {"spring.kafka.listener.auto-startup=false", "edrdog.selftest.run-on-startup=false"})
class AgentCheckNotifyIntegrationTest {

    private static final String WEBHOOK = "https://hooks.slack.com/services/T/B/agent-check";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private AuditService audit;

    @MockitoBean
    private SlackWebhookClient slack;

    private record Org(String token, long tenantId) {
    }

    private Org signup(String email, boolean withWebhook) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"Tr1cky#Pwd\"}")).andReturn();
        JsonNode n = om.readTree(r.getResponse().getContentAsString());
        Org org = new Org(n.get("token").asText(), n.get("tenantId").asLong());
        if (withWebhook) {
            mvc.perform(put("/api/tenant/webhook").header("Authorization", "Bearer " + org.token())
                            .header("X-API-Key", "dev-api-key").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"webhookUrl\":\"" + WEBHOOK + "\"}"))
                    .andExpect(status().isOk());
        }
        return org;
    }

    private void send(long tenant, String host, String type, String result) throws Exception {
        String body = "{\"tenantId\":" + tenant + ",\"host\":\"" + host + "\",\"events\":[{\"ts\":"
                + (System.currentTimeMillis() - 1) + ",\"type\":\"" + type + "\",\"result\":\"" + result
                + "\",\"detail\":\"x\"}]}";
        mvc.perform(post("/api/internal/audit").header("X-Internal-Key", "dev-internal-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1));
    }

    private int stored(long tenant, String type) {
        return audit.search(new AuditFilter(tenant, null, null, type, null, null, true, 100, 0, false)).items().size();
    }

    @Test
    void failureThenRecoveryOfAnAgentCheckReachesTheOrganizationWebhookOnceEach() throws Exception {
        Org org = signup("agent-check-notify@edrdog.com", true);

        send(org.tenantId(), "pc-notify", "security_check", "failure");
        send(org.tenantId(), "pc-notify", "security_check", "failure");
        verify(slack, timeout(2000).times(1)).send(eq(WEBHOOK), contains("pc-notify"));

        send(org.tenantId(), "pc-notify", "security_check", "success");
        verify(slack, timeout(2000).times(2)).send(eq(WEBHOOK), contains("pc-notify"));
        verify(slack, after(300).times(2)).send(anyString(), anyString());
        assertEquals(3, stored(org.tenantId(), "security_check"));
    }

    @Test
    void aBrokenWebhookNeverBreaksSavingTheAuditRecords() throws Exception {
        Org org = signup("agent-check-broken@edrdog.com", true);
        when(slack.send(anyString(), anyString())).thenThrow(new ResourceAccessException("timeout"));

        send(org.tenantId(), "pc-broken", "integrity_check", "failure");

        verify(slack, timeout(2000).times(1)).send(anyString(), anyString());
        assertEquals(1, stored(org.tenantId(), "integrity_check"));
    }

    @Test
    void organizationWithoutAWebhookStoresTheRecordAndSendsNothing() throws Exception {
        Org org = signup("agent-check-nohook@edrdog.com", false);

        send(org.tenantId(), "pc-nohook", "integrity_check", "failure");

        verify(slack, after(300).times(0)).send(anyString(), anyString());
        assertEquals(1, stored(org.tenantId(), "integrity_check"));
    }
}
