package com.edrdog.apiservice.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 롤업(events_hourly) 추세 SQL 생성의 순수 로직 검증.
 * 원본 events 가 TTL 로 사라진 뒤에도 답하는 경로라 대상 테이블이 롤업이어야 하고,
 * 다른 빌더와 마찬가지로 tenant 격리와 파라미터 바인딩이 SQL 에 박혀 있어야 한다.
 */
class EventTrendQueryBuilderTest {

    private static final String TENANT = "1";

    private final EventTrendQueryBuilder builder = new EventTrendQueryBuilder("edrdog.events_hourly");

    @Test
    void 원본이_아니라_롤업을_읽는다() {
        ClickHouseQuery q = builder.hourlyTrend(TENANT, null, null, 0, 1);
        assertTrue(q.sql().contains("FROM edrdog.events_hourly"), q.sql());
        assertFalse(q.sql().contains("edrdog.events "), q.sql());
    }

    /** SummingMergeTree 는 병합 전 행이 남아 있어 sum 없이 읽으면 같은 시간이 여러 줄로 나온다. */
    @Test
    void 병합_전_행이_섞이지_않게_sum_으로_접는다() {
        ClickHouseQuery q = builder.hourlyTrend(TENANT, null, null, 0, 1);
        assertTrue(q.sql().contains("sum(cnt)"), q.sql());
        assertTrue(q.sql().contains("GROUP BY hour, type"), q.sql());
    }

    @Test
    void tenant_가_없으면_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> builder.hourlyTrend(null, null, null, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> builder.hourlyTrend(" ", null, null, 0, 1));
    }

    @Test
    void tenant_는_항상_조건에_들어간다() {
        ClickHouseQuery q = builder.hourlyTrend(TENANT, null, null, 0, 1);
        assertTrue(q.sql().contains("tenant_id = {tenant:String}"), q.sql());
        assertEquals(TENANT, q.params().get("tenant"));
    }

    /** 롤업의 hour 는 DateTime 이고 API 는 밀리초를 받는다. 나누지 않으면 1970년으로 접힌다. */
    @Test
    void 밀리초_범위를_초로_바꿔_hour_에_건다() {
        ClickHouseQuery q = builder.hourlyTrend(TENANT, null, null, 1_700_000_000_000L, 1_700_003_600_000L);
        assertTrue(q.sql().contains("hour < toDateTime({to:UInt64} / 1000)"), q.sql());
        assertEquals("1700000000000", q.params().get("from"));
        assertEquals("1700003600000", q.params().get("to"));
    }

    /**
     * from 이 시 중간이면 그 시각이 든 버킷은 시작이 from 보다 앞이라 통째로 빠진다.
     * 화면 왼쪽 끝 한 시간이 조용히 사라지므로 from 을 시로 내려서 건다(to 는 이미 부분 시간을 포함한다).
     */
    @Test
    void from_이_든_시간_버킷을_빠뜨리지_않는다() {
        ClickHouseQuery q = builder.hourlyTrend(TENANT, null, null, 0, 1);
        assertTrue(q.sql().contains("hour >= toStartOfHour(toDateTime({from:UInt64} / 1000))"), q.sql());
    }

    @Test
    void host_와_type_은_없으면_조건을_안_건다() {
        ClickHouseQuery q = builder.hourlyTrend(TENANT, null, "  ", 0, 1);
        assertFalse(q.sql().contains("host ="), q.sql());
        assertFalse(q.sql().contains("type ="), q.sql());
    }

    @Test
    void host_와_type_은_값으로_바인딩한다() {
        ClickHouseQuery q = builder.hourlyTrend(TENANT, "h1", "network", 0, 1);
        assertTrue(q.sql().contains("host = {host:String}"), q.sql());
        assertTrue(q.sql().contains("type = {type:String}"), q.sql());
        assertEquals("h1", q.params().get("host"));
        assertEquals("network", q.params().get("type"));
    }

    /** 값이 SQL 본문에 이어붙으면 인젝션이 열린다. */
    @Test
    void 필터값은_SQL_본문에_들어가지_않는다() {
        ClickHouseQuery q = builder.hourlyTrend(TENANT, "h1' OR 1=1 --", null, 0, 1);
        assertFalse(q.sql().contains("OR 1=1"), q.sql());
    }

    /** 시간순이 아니면 화면이 선을 못 긋는다. */
    @Test
    void 시간_오름차순으로_정렬한다() {
        assertTrue(builder.hourlyTrend(TENANT, null, null, 0, 1).sql().contains("ORDER BY hour"));
    }
}
