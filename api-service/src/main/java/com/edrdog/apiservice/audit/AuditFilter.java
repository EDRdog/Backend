package com.edrdog.apiservice.audit;

import java.time.Instant;

/**
 * 조회 조건. tenantId 는 항상 있어야 한다(다른 tenant 기록은 볼 수 없다). 나머지는 null 이면 거르지 않는다.
 * from, to 는 양끝을 포함한다.
 */
public record AuditFilter(Long tenantId, Instant from, Instant to, String type, String actor,
                          AuditResult result, boolean ascending, int limit, int offset, boolean withTotal) {
}
