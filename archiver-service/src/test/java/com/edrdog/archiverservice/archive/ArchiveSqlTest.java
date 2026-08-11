package com.edrdog.archiverservice.archive;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 아카이빙 SQL 생성 순수 로직. 하루치 구간과 대상 경로가 날짜만으로 결정돼야 재실행이 멱등해진다.
 */
class ArchiveSqlTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 4);

    @Test
    void 경로는_날짜로_갈린다() {
        assertEquals("http://minio:9000/edrdog-archive/events/dt=2026-08-04/data.parquet",
                ArchiveSql.objectUrl("http://minio:9000/edrdog-archive", DAY));
    }

    /** 설정값 끝에 슬래시가 붙어 오면 경로가 갈려 같은 날이 두 곳에 쌓인다. */
    @Test
    void 베이스_끝_슬래시가_있어도_같은_경로() {
        assertEquals(ArchiveSql.objectUrl("http://minio:9000/edrdog-archive", DAY),
                ArchiveSql.objectUrl("http://minio:9000/edrdog-archive/", DAY));
    }

    @Test
    void 내보내는_구간은_그_날_하루() {
        String sql = ArchiveSql.export("edrdog.events", "http://minio:9000/b", "ak", "sk", DAY);
        assertTrue(sql.contains("ingested_at >= toDateTime('2026-08-04 00:00:00')"), sql);
        assertTrue(sql.contains("ingested_at < toDateTime('2026-08-05 00:00:00')"), sql);
    }

    @Test
    void 포맷은_Parquet() {
        String sql = ArchiveSql.export("edrdog.events", "http://minio:9000/b", "ak", "sk", DAY);
        assertTrue(sql.contains("'Parquet'"), sql);
    }

    /** 이게 없으면 같은 날을 두 번 돌렸을 때 파일이 하나 더 생기거나 INSERT 가 실패한다. */
    @Test
    void 같은_날을_다시_내보내면_덮어쓴다() {
        String sql = ArchiveSql.export("edrdog.events", "http://minio:9000/b", "ak", "sk", DAY);
        assertTrue(sql.contains("s3_truncate_on_insert = 1"), sql);
    }

    /** 키에 작은따옴표가 있으면 SQL 문자열이 그 자리에서 끊겨 문법 오류가 난다. */
    @Test
    void 작은따옴표가_든_키는_이스케이프된다() {
        String sql = ArchiveSql.export("edrdog.events", "http://minio:9000/b", "a'k", "s'k", DAY);
        assertTrue(sql.contains("'a''k'"), sql);
        assertTrue(sql.contains("'s''k'"), sql);
    }

    @Test
    void 내보낼_날은_보관일수만큼_지난_날() {
        assertEquals(LocalDate.of(2026, 8, 4), ArchiveSql.targetDay(LocalDate.of(2026, 8, 10), 6));
    }
}
