package com.edrdog.apiservice.notify;

import com.edrdog.apiservice.audit.AuditEntry;
import com.edrdog.apiservice.audit.AuditResult;
import com.edrdog.apiservice.audit.AuditType;
import com.edrdog.apiservice.auth.Tenant;
import com.edrdog.apiservice.auth.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 에이전트 자체시험·무결성 실패 통보(KISA BF-10.2, BF-11.2): 상태가 바뀔 때만 조직 webhook 으로 보낸다. */
class AgentCheckNotifierTest {

    private static final String WEBHOOK = "https://hooks.slack.com/services/T/B/x";
    private static final long TENANT = 7L;

    private TenantRepository tenants;
    private SlackWebhookClient slack;
    private AgentCheckNotifier notifier;

    @BeforeEach
    void setUp() {
        tenants = mock(TenantRepository.class);
        slack = mock(SlackWebhookClient.class);
        notifier = new AgentCheckNotifier(tenants, slack);
        withWebhook(TENANT, WEBHOOK);
    }

    private void withWebhook(long tenantId, String url) {
        Tenant t = Tenant.of("org", Instant.now());
        t.updateWebhook(url);
        when(tenants.findById(tenantId)).thenReturn(Optional.of(t));
    }

    private static AuditEntry check(long tenant, String host, String type, AuditResult result) {
        return AuditEntry.agent(Instant.now(), tenant, host, type, result, "random=fail");
    }

    private void receive(AuditEntry... entries) {
        notifier.onRecorded(List.of(entries));
    }

    @Test
    void sameFailureEveryMinuteIsNotifiedOnlyOnce() {
        receive(check(TENANT, "pc-1", AuditType.SECURITY_CHECK, AuditResult.FAILURE));
        receive(check(TENANT, "pc-1", AuditType.SECURITY_CHECK, AuditResult.FAILURE));
        receive(check(TENANT, "pc-1", AuditType.SECURITY_CHECK, AuditResult.FAILURE));

        verify(slack, timeout(2000).times(1)).send(eq(WEBHOOK), contains("pc-1"));
        verify(slack, after(300).times(1)).send(anyString(), anyString());
    }

    @Test
    void recoveryIsNotifiedOnceAndThenAFreshFailureIsNotifiedAgain() {
        receive(check(TENANT, "pc-1", AuditType.INTEGRITY_CHECK, AuditResult.FAILURE));
        receive(check(TENANT, "pc-1", AuditType.INTEGRITY_CHECK, AuditResult.SUCCESS));
        receive(check(TENANT, "pc-1", AuditType.INTEGRITY_CHECK, AuditResult.SUCCESS));
        receive(check(TENANT, "pc-1", AuditType.INTEGRITY_CHECK, AuditResult.FAILURE));

        verify(slack, timeout(2000).times(3)).send(eq(WEBHOOK), anyString());
        verify(slack, after(300).times(3)).send(anyString(), anyString());
        verify(slack, times(1)).send(eq(WEBHOOK), contains("회복"));
    }

    @Test
    void successWithoutAnEarlierFailureSendsNothing() {
        receive(check(TENANT, "pc-1", AuditType.SECURITY_CHECK, AuditResult.SUCCESS));

        verify(slack, after(300).never()).send(anyString(), anyString());
    }

    @Test
    void everyHostAndCheckTypeKeepsItsOwnState() {
        receive(check(TENANT, "pc-1", AuditType.SECURITY_CHECK, AuditResult.FAILURE),
                check(TENANT, "pc-2", AuditType.SECURITY_CHECK, AuditResult.FAILURE),
                check(TENANT, "pc-1", AuditType.INTEGRITY_CHECK, AuditResult.FAILURE));

        verify(slack, timeout(2000).times(3)).send(eq(WEBHOOK), anyString());
    }

    @Test
    void otherAuditTypesAreIgnored() {
        receive(check(TENANT, "pc-1", AuditType.AGENT_ENROLL, AuditResult.FAILURE),
                check(TENANT, "pc-1", "agent_start", AuditResult.FAILURE));

        verify(slack, after(300).never()).send(anyString(), anyString());
    }

    @Test
    void organizationWithoutAWebhookGetsNothing() {
        withWebhook(8L, null);

        receive(check(8L, "pc-1", AuditType.SECURITY_CHECK, AuditResult.FAILURE));
        withWebhook(9L, "  ");
        receive(check(9L, "pc-1", AuditType.SECURITY_CHECK, AuditResult.FAILURE));

        verify(slack, after(300).never()).send(anyString(), anyString());
    }

    @Test
    void failedSendDoesNotThrowAndTheStateStillAdvances() {
        when(slack.send(anyString(), anyString())).thenThrow(new ResourceAccessException("timeout"));

        assertDoesNotThrow(() -> receive(check(TENANT, "pc-1", AuditType.SECURITY_CHECK, AuditResult.FAILURE)));
        verify(slack, timeout(2000).times(1)).send(anyString(), anyString());

        receive(check(TENANT, "pc-1", AuditType.SECURITY_CHECK, AuditResult.FAILURE));   // 같은 실패: 다시 보내지 않는다
        verify(slack, after(300).times(1)).send(anyString(), anyString());
    }

    @Test
    void lookupFailureDoesNotThrow() {
        when(tenants.findById(TENANT)).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> receive(check(TENANT, "pc-1", AuditType.SECURITY_CHECK, AuditResult.FAILURE)));
        verify(slack, never()).send(anyString(), anyString());
    }
}
