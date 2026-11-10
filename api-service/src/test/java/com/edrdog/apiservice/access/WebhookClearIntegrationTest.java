package com.edrdog.apiservice.access;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Slack webhook 을 비우는 API(조직 DELETE /api/tenant/webhook, 개인 DELETE /api/me/webhook). 한 번 저장하면 못 비우던 문제의 수정. */
class WebhookClearIntegrationTest extends AccessTestBase {

    private static final String URL = "https://hooks.slack.com/services/T/B/clear";

    private void save(String path, String token) throws Exception {
        mvc.perform(put(path).with(as(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"webhookUrl\":\"" + URL + "\"}")).andExpect(status().isOk());
    }

    @Test
    void adminClearsTheOrganizationWebhookAndItIsAudited() throws Exception {
        Account org = signup("clear-tenant@edrdog.com");
        save("/api/tenant/webhook", org.token());

        mvc.perform(delete("/api/tenant/webhook").with(as(org.token()))).andExpect(status().isNoContent());

        mvc.perform(get("/api/tenant/webhook").with(as(org.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.webhookUrl").doesNotExist());
        mvc.perform(get("/api/internal/tenants/" + org.tenantId() + "/webhook").header("X-Internal-Key", "dev-internal-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.webhookUrl").doesNotExist());
        JsonNode rows = audits(org, "?type=webhook_change");
        assertEquals(1, rows.size());
        assertEquals("tenant", rows.get(0).get("target").asText());
        assertEquals("remove", rows.get(0).get("detail").asText());
        assertEquals(org.email(), rows.get(0).get("actor").asText());
        assertEquals("success", rows.get(0).get("result").asText());
    }

    @Test
    void clearingAnEmptyWebhookIsStillNoContentAndAnotherUrlCanBeSavedAfterwards() throws Exception {
        Account org = signup("clear-twice@edrdog.com");

        mvc.perform(delete("/api/tenant/webhook").with(as(org.token()))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/tenant/webhook").with(as(org.token()))).andExpect(status().isNoContent());
        save("/api/tenant/webhook", org.token());
        mvc.perform(get("/api/tenant/webhook").with(as(org.token()))).andExpect(jsonPath("$.webhookUrl").value(URL));
    }

    @Test
    void clearingOneOrganizationLeavesOthersAlone() throws Exception {
        Account a = signup("clear-iso-a@edrdog.com");
        Account b = signup("clear-iso-b@edrdog.com");
        save("/api/tenant/webhook", a.token());
        save("/api/tenant/webhook", b.token());

        mvc.perform(delete("/api/tenant/webhook").with(as(a.token()))).andExpect(status().isNoContent());

        mvc.perform(get("/api/tenant/webhook").with(as(b.token()))).andExpect(jsonPath("$.webhookUrl").value(URL));
    }

    @Test
    void viewerAndUnauthenticatedCallersCannotClearTheOrganizationWebhook() throws Exception {
        Account org = signup("clear-deny@edrdog.com");
        save("/api/tenant/webhook", org.token());
        String viewer = sessionOf(org, "clear-deny-viewer@edrdog.com", "viewer");

        mvc.perform(delete("/api/tenant/webhook").with(as(viewer))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/tenant/webhook").header("X-API-Key", "dev-api-key")).andExpect(status().isUnauthorized());

        mvc.perform(get("/api/tenant/webhook").with(as(org.token()))).andExpect(jsonPath("$.webhookUrl").value(URL));
        assertEquals(0, audits(org, "?type=webhook_change").size());
    }

    @Test
    void adminClearsTheirPersonalWebhookAndViewerCannot() throws Exception {
        Account org = signup("clear-me@edrdog.com");
        save("/api/me/webhook", org.token());
        String viewer = sessionOf(org, "clear-me-viewer@edrdog.com", "viewer");

        mvc.perform(delete("/api/me/webhook").with(as(viewer))).andExpect(status().isForbidden());
        mvc.perform(get("/api/me/webhook").with(as(org.token()))).andExpect(jsonPath("$.webhookUrl").value(URL));

        mvc.perform(delete("/api/me/webhook").with(as(org.token()))).andExpect(status().isNoContent());

        mvc.perform(get("/api/me/webhook").with(as(org.token()))).andExpect(jsonPath("$.webhookUrl").doesNotExist());
        JsonNode rows = audits(org, "?type=webhook_change");
        assertEquals(1, rows.size());
        assertEquals("me", rows.get(0).get("target").asText());
        assertEquals("remove", rows.get(0).get("detail").asText());
    }
}
