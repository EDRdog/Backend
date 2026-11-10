package com.edrdog.apiservice.audit;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AuditService: 기록과 조회(걸러내기, 정렬, 페이지). 테스트마다 tenant 번호를 따로 써서 서로의 기록과 섞이지 않게 한다.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class AuditServiceTest {

    @Autowired
    private AuditService audit;

    private static AuditEntry at(long tenant, long epochMs, String type, AuditResult result, String actor) {
        return new AuditEntry(Instant.ofEpochMilli(epochMs), tenant, AuditActor.USER, 7L, actor,
                type, "target", result, "detail", "10.0.0.1");
    }

    private AuditPage all(long tenant, boolean ascending, int limit, int offset, boolean withTotal) {
        return audit.search(new AuditFilter(tenant, null, null, null, null, null, ascending, limit, offset, withTotal));
    }

    @Test
    void recordStoresEveryField() {
        long tenant = 9101;
        audit.record(at(tenant, 1_700_000_000_000L, AuditType.LOGIN_SUCCESS, AuditResult.SUCCESS, "a@b.com"));

        AuditRecord r = all(tenant, false, 10, 0, false).items().get(0);
        assertNotNull(r.getId());
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), r.getTs());
        assertEquals(tenant, r.getTenantId());
        assertEquals(AuditActor.USER, r.getActorType());
        assertEquals(7L, r.getActorUserId());
        assertEquals("a@b.com", r.getActor());
        assertEquals("login_success", r.getEventType());
        assertEquals("target", r.getTarget());
        assertEquals(AuditResult.SUCCESS, r.getResult());
        assertEquals("detail", r.getDetail());
        assertEquals("10.0.0.1", r.getIp());
    }

    @Test
    void recordFillsNowWhenTsIsMissing() {
        long tenant = 9102;
        Instant before = Instant.now().minusSeconds(1);
        audit.record(AuditEntry.system(tenant, AuditType.SELF_TEST_RESULT, null, AuditResult.SUCCESS, "ok"));

        AuditRecord r = all(tenant, false, 10, 0, false).items().get(0);
        assertTrue(r.getTs().isAfter(before));
        assertEquals(AuditActor.SYSTEM, r.getActorType());
    }

    @Test
    void recordNeverThrowsEvenWhenTheStoreFails() {
        AuditRepository broken = mock(AuditRepository.class);
        when(broken.save(any())).thenThrow(new IllegalStateException("db down"));
        AuditService s = new AuditService(broken, mock(EntityManager.class), mock(PlatformTransactionManager.class));

        assertDoesNotThrow(() -> s.record(at(1, 1L, AuditType.LOGOUT, AuditResult.SUCCESS, "a@b.com")));
    }

    @Test
    void recordCutsOverlongTextToTheColumnLimits() {
        long tenant = 9103;
        String huge = "x".repeat(5000);
        audit.record(new AuditEntry(null, tenant, AuditActor.USER, 1L, huge, AuditType.LOGOUT, huge,
                AuditResult.SUCCESS, huge, huge));

        AuditRecord r = all(tenant, false, 10, 0, false).items().get(0);
        assertEquals(AuditService.MAX_ACTOR, r.getActor().length());
        assertEquals(AuditService.MAX_TARGET, r.getTarget().length());
        assertEquals(AuditService.MAX_DETAIL, r.getDetail().length());
        assertEquals(AuditService.MAX_IP, r.getIp().length());
    }

    @Test
    void auditCodeHasNoUpdateOrDeletePath() {
        // 수정·삭제 코드를 두지 않는다는 기준을 구조로 고정한다: 저장소, 서비스, 엔티티 어디에도 그런 이름의 메서드가 없다.
        for (Class<?> c : List.of(AuditRepository.class, AuditService.class, AuditRecord.class)) {
            for (Method m : c.getMethods()) {
                String n = m.getName().toLowerCase();
                assertFalse(n.startsWith("delete") || n.startsWith("remove") || n.startsWith("update")
                                || n.startsWith("set") || n.startsWith("merge") || n.startsWith("truncate"),
                        c.getSimpleName() + "." + m.getName());
            }
        }
        assertEquals(List.of("save", "saveAll"),
                Arrays.stream(AuditRepository.class.getMethods()).map(Method::getName).sorted().toList());
    }

    @Test
    void searchStaysInsideTheTenant() {
        audit.record(at(9104, 1000, AuditType.LOGOUT, AuditResult.SUCCESS, "a@b.com"));
        audit.record(at(9105, 1000, AuditType.LOGOUT, AuditResult.SUCCESS, "c@d.com"));

        List<AuditRecord> items = all(9104, false, 10, 0, false).items();
        assertEquals(1, items.size());
        assertEquals("a@b.com", items.get(0).getActor());
    }

    @Test
    void searchFiltersByRangeTypeActorAndResult() {
        long t = 9106;
        audit.record(at(t, 1000, AuditType.LOGIN_SUCCESS, AuditResult.SUCCESS, "a@b.com"));
        audit.record(at(t, 2000, AuditType.LOGIN_FAILURE, AuditResult.FAILURE, "a@b.com"));
        audit.record(at(t, 3000, AuditType.LOGIN_FAILURE, AuditResult.FAILURE, "c@d.com"));
        audit.record(at(t, 4000, AuditType.LOGOUT, AuditResult.SUCCESS, "a@b.com"));

        // 기간(양끝 포함)
        assertEquals(2, audit.search(new AuditFilter(t, Instant.ofEpochMilli(2000), Instant.ofEpochMilli(3000),
                null, null, null, true, 10, 0, false)).items().size());
        // 유형
        assertEquals(2, audit.search(new AuditFilter(t, null, null, AuditType.LOGIN_FAILURE, null, null,
                true, 10, 0, false)).items().size());
        // 행위자
        assertEquals(3, audit.search(new AuditFilter(t, null, null, null, "a@b.com", null,
                true, 10, 0, false)).items().size());
        // 결과
        assertEquals(2, audit.search(new AuditFilter(t, null, null, null, null, AuditResult.FAILURE,
                true, 10, 0, false)).items().size());
        // 조건을 겹치면 모두 만족하는 것만
        List<AuditRecord> one = audit.search(new AuditFilter(t, null, null, AuditType.LOGIN_FAILURE, "c@d.com",
                AuditResult.FAILURE, true, 10, 0, false)).items();
        assertEquals(1, one.size());
        assertEquals(Instant.ofEpochMilli(3000), one.get(0).getTs());
    }

    @Test
    void searchSortsByTimeInBothDirections() {
        long t = 9107;
        audit.record(at(t, 2000, AuditType.LOGOUT, AuditResult.SUCCESS, "b"));
        audit.record(at(t, 1000, AuditType.LOGOUT, AuditResult.SUCCESS, "a"));
        audit.record(at(t, 3000, AuditType.LOGOUT, AuditResult.SUCCESS, "c"));

        assertEquals(List.of("a", "b", "c"),
                all(t, true, 10, 0, false).items().stream().map(AuditRecord::getActor).toList());
        assertEquals(List.of("c", "b", "a"),
                all(t, false, 10, 0, false).items().stream().map(AuditRecord::getActor).toList());
    }

    @Test
    void searchPagesWithLimitOffsetHasMoreAndTotal() {
        long t = 9108;
        for (int i = 1; i <= 5; i++) {
            audit.record(at(t, i * 1000L, AuditType.LOGOUT, AuditResult.SUCCESS, "u" + i));
        }

        AuditPage first = all(t, true, 2, 0, true);
        assertEquals(List.of("u1", "u2"), first.items().stream().map(AuditRecord::getActor).toList());
        assertTrue(first.hasMore());
        assertEquals(5L, first.total());

        AuditPage last = all(t, true, 2, 4, false);
        assertEquals(List.of("u5"), last.items().stream().map(AuditRecord::getActor).toList());
        assertFalse(last.hasMore());
        assertNull(last.total());
    }
}
