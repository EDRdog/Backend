package com.edrdog.apiservice.audit;

import java.util.List;

/** 조회 한 페이지. total 은 withTotal 로 요청했을 때만 채우고, 아니면 null. */
public record AuditPage(List<AuditRecord> items, boolean hasMore, Long total) {
}
