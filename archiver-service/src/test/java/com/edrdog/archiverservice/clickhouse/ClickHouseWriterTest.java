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

    private final ClickHouseHttp http = mock(ClickHouseHttp.class);

    /** showCreate 는 이미 존재하는 테이블의 SHOW CREATE 응답(신규 환경이면 빈 문자열). */
    private void bootWith(String showCreate) {
        when(http.query(anyString())).thenReturn(showCreate);
        new ClickHouseWriter(http, TABLE, new ObjectMapper()).ensureSchema();
    }

    /** 파티션이 없으면 TTL 만료가 파트를 버리지 못하고 살아 있는 행까지 다시 쓴다. */
    @Test
    void 신규_테이블은_적재일_단위로_파티션된다() {
        bootWith("");

        assertTrue(createStatement().contains("PARTITION BY toYYYYMMDD(ingested_at)"), createStatement());
    }

    /** 기본값 0 이면 파티션을 나눠도 만료 행만 걸러내는 머지가 돈다. 파티션과 이 설정은 같이 가야 한다. */
    @Test
    void 만료된_파트를_통째로_버리게_설정한다() {
        bootWith("");

        assertTrue(createStatement().contains("ttl_only_drop_parts = 1"), createStatement());
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

    private String createStatement() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(http, atLeastOnce()).execute(sql.capture());
        List<String> all = sql.getAllValues();
        return all.stream()
                .filter(s -> s.startsWith("CREATE TABLE"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("CREATE TABLE 문이 나가지 않았다: " + all));
    }
}
