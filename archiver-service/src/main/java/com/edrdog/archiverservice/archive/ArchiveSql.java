package com.edrdog.archiverservice.archive;

import java.time.LocalDate;

/**
 * 하루치 이벤트를 S3 호환 스토리지로 내보내는 SQL 생성(순수).
 *
 * <p>대상 경로가 날짜만으로 정해지고 {@code s3_truncate_on_insert} 로 덮어쓰기 때문에,
 * 같은 날을 다시 돌려도 파일이 늘지 않는다. 재시도·중복 실행을 그냥 다시 돌리는 것으로 처리한다.
 */
public final class ArchiveSql {

    private ArchiveSql() {
    }

    /** 하루치가 한 파일로 가도록 날짜를 경로에 박는다. 설정값 끝 슬래시는 경로가 갈리지 않게 떼어낸다. */
    public static String objectUrl(String baseUrl, LocalDate day) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + "/events/dt=" + day + "/data.parquet";
    }

    /** 내보낼 날 = 오늘에서 보관일수만큼 지난 날. TTL 이 지우기 전에 나가야 하므로 TTL 일수보다 작게 준다. */
    public static LocalDate targetDay(LocalDate today, int delayDays) {
        return today.minusDays(delayDays);
    }

    /** 그 날 하루(00:00 이상 다음 날 00:00 미만)를 Parquet 한 파일로 내보낸다. */
    public static String export(String table, String baseUrl, String accessKey, String secretKey, LocalDate day) {
        return "INSERT INTO FUNCTION s3("
                + quote(objectUrl(baseUrl, day)) + ", "
                + quote(accessKey) + ", "
                + quote(secretKey) + ", "
                + "'Parquet')"
                + " SELECT * FROM " + table
                + " WHERE ingested_at >= toDateTime(" + quote(day + " 00:00:00") + ")"
                + " AND ingested_at < toDateTime(" + quote(day.plusDays(1) + " 00:00:00") + ")"
                + " SETTINGS s3_truncate_on_insert = 1";
    }

    // 값에 작은따옴표가 있으면 문자열이 그 자리에서 끊겨 문법 오류가 난다. ClickHouse 는 '' 로 이스케이프한다.
    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
