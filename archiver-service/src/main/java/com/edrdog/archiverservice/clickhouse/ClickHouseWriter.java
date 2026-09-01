package com.edrdog.archiverservice.clickhouse;

import com.edrdog.schema.Event;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ClickHouse 로 events 를 적재하고, 부팅 시 테이블 스키마를 보장한다.
 * 쿼리는 POST 본문 첫 줄에, 데이터(JSONEachRow)는 그 다음 줄부터 실어 URL 인코딩을 피한다.
 */
@Component
public class ClickHouseWriter {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseWriter.class);

    private final ClickHouseHttp http;
    private final ObjectMapper mapper;
    private final String table;

    public ClickHouseWriter(
            ClickHouseHttp http,
            @Value("${edrdog.clickhouse.table}") String table,
            ObjectMapper mapper) {
        this.http = http;
        this.table = table;
        this.mapper = mapper;
    }

    /** 나중에 추가돼 ALTER 로 한 번 더 보장하는 컬럼. */
    private static final List<String> ADDED_COLUMNS = List.of(
            "domain String",
            "detail String",
            "sha256 String"
    );

    /** 보관기간 7일. SHOW CREATE 결과와 문자열 비교하므로 정규형으로 쓴다. */
    private static final String TTL = "toDateTime(ingested_at) + toIntervalDay(7)";

    /**
     * 부팅 시 events 테이블 생성 (개발용: 매 기동마다 IF NOT EXISTS).
     *
     * <p>PARTITION BY 가 없으면 테이블 전체가 단일 파티션이라 TTL 만료를 파트마다 살아 있는 행까지
     * 다시 쓰는 머지로 처리한다. 적재일로 나누면 만료된 날 파티션을 메타데이터만 건드려 버린다.
     * ttl_only_drop_parts 는 기본값이 0 이라 켜지 않으면 파티션을 나눠도 그 머지가 그대로 돈다.
     * 둘은 같이 가야 한다. 파티션 없이 이 설정만 켜면 파트가 영영 전부 만료되지 않아 삭제가 멈춘다.
     */
    @PostConstruct
    void ensureSchema() {
        http.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    host String,
                    tenant_id String,
                    type LowCardinality(String),
                    ts UInt64,
                    process String,
                    parent String,
                    cmdline String,
                    dest_ip String,
                    dest_port UInt16,
                    domain String,
                    detail String,
                    sha256 String,
                    ingested_at DateTime64(3) DEFAULT now64(3)
                ) ENGINE = MergeTree
                PARTITION BY toYYYYMMDD(ingested_at)
                ORDER BY (tenant_id, host, ts)
                TTL %s
                SETTINGS ttl_only_drop_parts = 1
                """.formatted(table, TTL));

        // 기존 테이블에는 위 DDL 이 새 컬럼을 못 붙인다. 나중에 늘어난 컬럼은 ALTER 로 보장한다.
        for (String column : ADDED_COLUMNS) {
            http.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + column);
        }
        String ddl = http.query("SHOW CREATE TABLE " + table);
        ensureTtl(ddl);
        warnIfNotPartitioned(ddl);
        log.info("ClickHouse 스키마 준비 완료: {}", table);
    }

    // 매번 걸면 전 파트 재계산 mutation 이 돌아 없을 때만 건다.
    private void ensureTtl(String ddl) {
        if (ddl.contains(TTL)) {
            return;
        }
        http.execute("ALTER TABLE " + table + " MODIFY TTL " + TTL);
        log.info("ClickHouse TTL 적용: {} TTL {}", table, TTL);
    }

    // PARTITION BY 는 ALTER 로 못 바꾼다. 이미 있는 테이블에는 위 DDL 이 안 먹으니 조용히 넘어가지 않게 이유를 남긴다.
    private void warnIfNotPartitioned(String ddl) {
        if (ddl.contains("PARTITION BY")) {
            return;
        }
        log.warn("{} 에 PARTITION BY 가 없다. TTL 만료가 파트를 통째로 못 버리고 매번 다시 쓴다."
                + " ALTER 로는 못 바꾸니 교체가 필요하다: 이 서비스를 내린 뒤"
                + " RENAME TABLE {} TO {}_old 를 실행하고 다시 올리면 새 DDL 로 만들어진다.", table, table, table);
    }

    // 건별 INSERT 는 파트가 건수만큼 쌓여 Too many parts 로 적재가 끊긴다.
    public void insert(List<Event> events) {
        if (events.isEmpty()) {
            return;
        }
        String body = "INSERT INTO " + table + " FORMAT JSONEachRow\n"
                + EventRow.toJsonRows(events, mapper);
        http.execute(body);
    }
}
