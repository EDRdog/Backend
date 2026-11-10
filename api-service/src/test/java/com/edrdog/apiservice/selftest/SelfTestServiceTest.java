package com.edrdog.apiservice.selftest;

import com.edrdog.apiservice.auth.Principal;
import com.edrdog.apiservice.notify.SlackWebhookClient;
import com.edrdog.apiservice.operations.DependencyResult;
import com.edrdog.apiservice.selftest.SelfTestItem.Status;
import com.edrdog.apiservice.selftest.SelfTestRun.Trigger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 실행(기동, 주기, 요청), 결과 저장, 실패 시 대응(경고 로그, 헬스, 통보). 항목 시험 자체는 따로 본다. */
class SelfTestServiceTest {

    private static final String WEBHOOK = "https://hooks.slack.com/services/T/B/x";
    private static final Principal ADMIN = new Principal(1L, 5L, "admin@edrdog.com", "admin");

    private SelfTestRunRepository runs;
    private SelfTestChecks checks;
    private IntegrityVerifier integrity;
    private SlackWebhookClient slack;
    private com.edrdog.apiservice.audit.AuditService audit;

    @BeforeEach
    void setUp() {
        runs = mock(SelfTestRunRepository.class);
        checks = mock(SelfTestChecks.class);
        integrity = mock(IntegrityVerifier.class);
        slack = mock(SlackWebhookClient.class);
        audit = mock(com.edrdog.apiservice.audit.AuditService.class);
        when(runs.save(any(SelfTestRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(checks.run()).thenReturn(List.of(SelfTestItem.pass(SelfTestItem.SELFTEST, "database", "ok")));
        when(integrity.verify()).thenReturn(List.of(SelfTestItem.skip(SelfTestItem.INTEGRITY, "baseline", "없음")));
    }

    private SelfTestService service(String webhookUrl) {
        return new SelfTestService(runs, checks, integrity, slack, webhookUrl, audit);
    }

    private static SelfTestItem failed(String name) {
        return SelfTestItem.fail(SelfTestItem.SELFTEST, name, "깨짐");
    }

    @Test
    void manualRunStoresItemsFromSelfTestAndIntegrity() {
        SelfTestRun run = service("").run(Trigger.MANUAL, ADMIN);

        assertEquals(Trigger.MANUAL, run.getTriggerType());
        assertEquals("admin@edrdog.com", run.getRequestedBy());
        assertEquals(5L, run.getRequestedByTenantId());   // 다른 조직에 요청자 이메일을 가릴 때 쓴다
        assertEquals(List.of("database", "baseline"), run.getItems().stream().map(SelfTestItem::name).toList());
        assertEquals(Status.PASS, run.getStatus());   // SKIP 은 실패가 아니다
        verify(runs).save(run);
    }

    @Test
    void scheduledAndStartupRunsAreRecordedWithTheirTrigger() {
        SelfTestService service = service("");

        service.runScheduled();
        service.runOnStartup();

        org.mockito.ArgumentCaptor<SelfTestRun> saved = org.mockito.ArgumentCaptor.forClass(SelfTestRun.class);
        verify(runs, times(2)).save(saved.capture());
        assertEquals(List.of(Trigger.SCHEDULED, Trigger.STARTUP),
                saved.getAllValues().stream().map(SelfTestRun::getTriggerType).toList());
    }

    @Test
    void anyFailedItemMakesTheRunFail() {
        when(checks.run()).thenReturn(List.of(failed("random")));

        assertEquals(Status.FAIL, service("").run(Trigger.MANUAL, null).getStatus());
    }

    @Test
    void failureIsLoggedAsWarningWithTheFailedItemNames() {
        Logger logger = (Logger) LoggerFactory.getLogger(SelfTestService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        when(checks.run()).thenReturn(List.of(failed("random")));
        try {
            service("").run(Trigger.MANUAL, null);
        } finally {
            logger.detachAppender(appender);
        }

        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("[SELFTEST-FAIL]") && e.getFormattedMessage().contains("random")));
    }

    @Test
    void failureIsNotifiedOnlyWhenTheFailedSetChanges() {
        when(checks.run()).thenReturn(List.of(failed("random")));
        SelfTestService service = service(WEBHOOK);

        service.run(Trigger.SCHEDULED, null);   // 직전 실행 없음: 통보
        verify(slack, times(1)).send(eq(WEBHOOK), contains("random"));

        when(runs.findFirstByOrderByIdDesc()).thenReturn(Optional.of(
                SelfTestRun.of(Trigger.SCHEDULED, Instant.now(), null, null, List.of(failed("random")))));
        service.run(Trigger.SCHEDULED, null);   // 같은 실패가 이어짐: 다시 통보하지 않는다
        verify(slack, times(1)).send(anyString(), anyString());

        when(checks.run()).thenReturn(List.of(failed("random"), failed("sha256")));
        service.run(Trigger.SCHEDULED, null);   // 실패 항목이 늘었다: 통보
        verify(slack, times(2)).send(anyString(), anyString());
    }

    @Test
    void noWebhookMeansNoNotification() {
        when(checks.run()).thenReturn(List.of(failed("random")));

        service("").run(Trigger.MANUAL, null);

        verify(slack, never()).send(anyString(), anyString());
    }

    @Test
    void notificationFailureDoesNotBreakTheRun() {
        when(checks.run()).thenReturn(List.of(failed("random")));
        when(slack.send(anyString(), anyString())).thenThrow(new ResourceAccessException("timeout"));

        SelfTestRun run = assertDoesNotThrow(() -> service(WEBHOOK).run(Trigger.MANUAL, null));

        assertEquals(Status.FAIL, run.getStatus());
        verify(runs).save(run);
    }

    @Test
    void startupAndScheduledRunsSwallowFailuresSoTheAppKeepsRunning() {
        when(runs.save(any(SelfTestRun.class))).thenThrow(new IllegalStateException("db down"));
        SelfTestService service = service("");

        assertDoesNotThrow(service::runOnStartup);
        assertDoesNotThrow(service::runScheduled);
    }

    @Test
    void dependencyIsUnknownBeforeTheFirstRun() {
        when(runs.findFirstByOrderByIdDesc()).thenReturn(Optional.empty());

        assertEquals("unknown", service("").dependency().status());
    }

    @Test
    void dependencyIsUpWhenLatestRunPassed() {
        when(runs.findFirstByOrderByIdDesc()).thenReturn(Optional.of(
                SelfTestRun.of(Trigger.STARTUP, Instant.now(), null, null, List.of(SelfTestItem.pass("selftest", "database", "ok")))));

        DependencyResult dep = service("").dependency();

        assertEquals("selftest", dep.name());
        assertEquals("up", dep.status());
    }

    @Test
    void dependencyIsDownAndNamesTheFailedItems() {
        when(runs.findFirstByOrderByIdDesc()).thenReturn(Optional.of(
                SelfTestRun.of(Trigger.STARTUP, Instant.now(), null, null, List.of(failed("random")))));

        DependencyResult dep = service("").dependency();

        assertEquals("down", dep.status());
        assertTrue(dep.error().contains("random"));
    }

    @Test
    void dependencyIsUnknownWhenTheLookupFails() {
        when(runs.findFirstByOrderByIdDesc()).thenThrow(new IllegalStateException("db down"));

        assertEquals("unknown", service("").dependency().status());
    }

    // 자체시험·무결성 결과는 서버 전체 사건이라 tenant 없이 system 행위자로 남는다(KISA BF-13 감사사건)
    @Test
    void eachRunRecordsSelfTestAndIntegrityResultsInTheAuditLog() {
        when(checks.run()).thenReturn(List.of(failed("database")));

        service("").run(Trigger.MANUAL, ADMIN);

        var captor = org.mockito.ArgumentCaptor.forClass(com.edrdog.apiservice.audit.AuditEntry.class);
        verify(audit, org.mockito.Mockito.times(2)).record(captor.capture());
        var entries = captor.getAllValues();
        assertEquals(List.of(com.edrdog.apiservice.audit.AuditType.SELF_TEST_RESULT,
                        com.edrdog.apiservice.audit.AuditType.INTEGRITY_CHECK_RESULT),
                entries.stream().map(com.edrdog.apiservice.audit.AuditEntry::type).toList());
        assertEquals(com.edrdog.apiservice.audit.AuditResult.FAILURE, entries.get(0).result());
        assertEquals(com.edrdog.apiservice.audit.AuditResult.SUCCESS, entries.get(1).result());   // SKIP 은 실패가 아니다
        assertEquals(com.edrdog.apiservice.audit.AuditActor.SYSTEM, entries.get(0).actorType());
        assertEquals(null, entries.get(0).tenantId());
    }
}
