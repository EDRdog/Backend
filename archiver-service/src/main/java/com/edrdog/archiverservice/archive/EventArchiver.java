package com.edrdog.archiverservice.archive;

import com.edrdog.archiverservice.clickhouse.ClickHouseHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * events 원본을 TTL 이 지우기 전에 S3 호환 스토리지(로컬은 MinIO)로 하루치씩 내보낸다.
 *
 * <p>침해는 발각까지 몇 주가 걸리는 일이 흔한데 ClickHouse 쪽 원본은 7일 TTL 로 사라진다.
 * 집계로 접으면 개별 프로세스·명령줄이 남지 않아 조사에 못 쓰므로 원본 행 그대로 내보낸다.
 *
 * <p>내보내기는 ClickHouse 가 직접 한다(s3 테이블 함수). 이벤트가 이 서비스를 거쳐 가지 않으므로
 * 아카이브 양이 늘어도 여기 메모리·네트워크에는 영향이 없다.
 */
@Component
public class EventArchiver {

    private static final Logger log = LoggerFactory.getLogger(EventArchiver.class);

    private final ClickHouseHttp http;
    private final String table;
    private final boolean enabled;
    private final String baseUrl;
    private final String accessKey;
    private final String secretKey;
    private final int delayDays;
    private final Clock clock;

    public EventArchiver(
            ClickHouseHttp http,
            @Value("${edrdog.clickhouse.table}") String table,
            @Value("${edrdog.archive.enabled}") boolean enabled,
            @Value("${edrdog.archive.base-url}") String baseUrl,
            @Value("${edrdog.archive.access-key}") String accessKey,
            @Value("${edrdog.archive.secret-key}") String secretKey,
            @Value("${edrdog.archive.delay-days}") int delayDays,
            Clock clock) {
        this.http = http;
        this.table = table;
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.delayDays = delayDays;
        this.clock = clock;
    }

    /** 하루 1회. 실패하면 다음 회차가 같은 날을 다시 내보낸다(경로가 같아 덮어쓴다). */
    @Scheduled(cron = "${edrdog.archive.cron}")
    public void archive() {
        if (!enabled) {
            return;
        }
        // 자격이 비면 ClickHouse 인증 오류만 남는다. 설정이 덜 된 상태를 여기서 끊고 이유를 남긴다.
        if (accessKey.isBlank() || secretKey.isBlank()) {
            log.warn("아카이빙 자격이 비어 건너뛴다. ARCHIVE_ACCESS_KEY/ARCHIVE_SECRET_KEY 를 확인하세요.");
            return;
        }

        LocalDate day = ArchiveSql.targetDay(LocalDate.now(clock), delayDays);
        http.execute(ArchiveSql.export(table, baseUrl, accessKey, secretKey, day));
        log.info("이벤트 아카이빙 완료: {} -> {}", day, ArchiveSql.objectUrl(baseUrl, day));
    }
}
