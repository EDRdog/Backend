package com.edrdog.archiverservice.clickhouse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 부팅 시 나가는 스키마 DDL. 파티션 키와 ttl_only_drop_parts 는 TTL 삭제 비용을 좌우하는 값이라
 * 나중에 DDL 을 손대다 조용히 빠지면 안 된다(#180).
 */
class ClickHouseWriterTest {

    private static final String TABLE = "edrdog.events";
    private static final String ROLLUP = "edrdog.events_hourly";

    private final ClickHouseHttp http = mock(ClickHouseHttp.class);

    /** showCreate 는 이미 존재하는 테이블의 SHOW CREATE 응답(신규 환경이면 빈 문자열). */
    private void bootWith(String showCreate) {
        when(http.query(anyString())).thenReturn(showCreate);
        new ClickHouseWriter(http, TABLE, ROLLUP, new ObjectMapper()).ensureSchema();
    }

    /** 파티션이 없으면 TTL 만료가 파트를 버리지 못하고 살아 있는 행까지 다시 쓴다. */
    @Test
    void 신규_테이블은_적재일_단위로_파티션된다() {
        bootWith("");

        String sql = eventsCreateStatement();
        assertTrue(sql.contains("PARTITION BY toYYYYMMDD(ingested_at)"), sql);
    }

    /** 기본값 0 이면 파티션을 나눠도 만료 행만 걸러내는 머지가 돈다. 파티션과 이 설정은 같이 가야 한다. */
    @Test
    void 만료된_파트를_통째로_버리게_설정한다() {
        bootWith("");

        String sql = eventsCreateStatement();
        assertTrue(sql.contains("ttl_only_drop_parts = 1"), sql);
    }

    /** 매번 걸면 전 파트 재계산 mutation 이 돈다. */
    @Test
    void TTL_이_이미_있으면_다시_걸지_않는다() {
        bootWith("... TTL toDateTime(ingested_at) + toIntervalDay(7) ...");

        verify(http, never()).execute(contains("MODIFY TTL"));
    }

    @Test
    void TTL_이_없으면_건다() {
        bootWith("... 아직 TTL 이 없는 테이블 ...");

        verify(http).execute(contains("MODIFY TTL"));
    }

    // --- 시간 단위 롤업(#298) ---

    /** 롤업도 처음부터 파티션을 넣는다(#180 을 되풀이하지 않는다). 180일 보관이면 파티션이 월 단위로 6~7개다. */
    @Test
    void 롤업은_월_단위로_파티션하고_180일_보관한다() {
        bootWith("");

        String sql = statementStartingWith("CREATE TABLE IF NOT EXISTS " + ROLLUP);
        assertTrue(sql.contains("PARTITION BY toYYYYMM(hour)"), sql);
        assertTrue(sql.contains("TTL hour + toIntervalDay(180)"), sql);
    }

    /** 측정이 count 하나뿐이라 -State/-Merge 가 필요 없다. 읽는 쪽이 sum(cnt) 로 끝난다. */
    @Test
    void 롤업은_SummingMergeTree_다() {
        bootWith("");

        assertTrue(statementStartingWith("CREATE TABLE IF NOT EXISTS " + ROLLUP)
                .contains("ENGINE = SummingMergeTree"), "SummingMergeTree 가 아니다");
    }

    /** events.ts 는 UInt64 밀리초다. 그대로 toStartOfHour 에 넣으면 1970년 언저리로 접힌다. */
    @Test
    void MV_는_밀리초_ts_를_시각으로_바꿔_시간에_접는다() {
        bootWith("");

        String sql = statementStartingWith("CREATE MATERIALIZED VIEW");
        assertTrue(sql.contains("toStartOfHour(fromUnixTimestamp64Milli(ts))"), sql);
        assertTrue(sql.contains("TO " + ROLLUP), sql);
        assertTrue(sql.contains("FROM " + TABLE), sql);
    }

    /** MV 는 소스 테이블이 있어야 만들어진다. 순서가 뒤집히면 부팅이 깨진다. */
    @Test
    void MV_는_events_테이블_생성_뒤에_만든다() {
        bootWith("");

        List<String> all = executed();
        assertTrue(indexOfStatementStartingWith(all, "CREATE TABLE IF NOT EXISTS " + TABLE)
                        < indexOfStatementStartingWith(all, "CREATE MATERIALIZED VIEW"),
                all.toString());
    }

    private String statementStartingWith(String prefix) {
        List<String> all = executed();
        return all.get(indexOfStatementStartingWith(all, prefix));
    }

    private static int indexOfStatementStartingWith(List<String> all, String prefix) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).startsWith(prefix)) {
                return i;
            }
        }
        throw new AssertionError(prefix + " 로 시작하는 문장이 나가지 않았다: " + all);
    }

    private List<String> executed() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(http, atLeastOnce()).execute(sql.capture());
        return sql.getAllValues();
    }

    private String eventsCreateStatement() {
        return statementStartingWith("CREATE TABLE IF NOT EXISTS " + TABLE);
    }
}
