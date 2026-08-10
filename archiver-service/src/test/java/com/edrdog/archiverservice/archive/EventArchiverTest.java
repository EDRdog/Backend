package com.edrdog.archiverservice.archive;

import com.edrdog.archiverservice.clickhouse.ClickHouseHttp;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 아카이빙 주기 작업. 스위치가 꺼져 있거나 자격이 비면 아무 SQL 도 나가면 안 된다.
 */
class EventArchiverTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T03:30:00Z"), ZoneOffset.UTC);

    private final ClickHouseHttp http = mock(ClickHouseHttp.class);

    private EventArchiver archiver(boolean enabled, String accessKey, String secretKey) {
        return new EventArchiver(http, "edrdog.events", enabled,
                "http://minio:9000/edrdog-archive", accessKey, secretKey, 6, CLOCK);
    }

    @Test
    void 꺼져_있으면_내보내지_않는다() {
        archiver(false, "ak", "sk").archive();
        verify(http, never()).execute(anyString());
    }

    /** 자격이 비면 ClickHouse 가 인증 오류를 낼 뿐이라, 설정이 덜 된 상태를 여기서 끊는다. */
    @Test
    void 자격이_비면_내보내지_않는다() {
        archiver(true, "", "").archive();
        verify(http, never()).execute(anyString());
    }

    @Test
    void 보관일수만큼_지난_하루를_내보낸다() {
        archiver(true, "ak", "sk").archive();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(http).execute(sql.capture());
        assertTrue(sql.getValue().contains("dt=2026-08-04/data.parquet"), sql.getValue());
        assertTrue(sql.getValue().contains("ingested_at >= toDateTime('2026-08-04 00:00:00')"), sql.getValue());
    }
}
