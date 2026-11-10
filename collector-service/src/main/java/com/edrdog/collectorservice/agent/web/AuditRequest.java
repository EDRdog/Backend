package com.edrdog.collectorservice.agent.web;

import com.edrdog.collectorservice.agent.AuditEvent;

import java.util.List;

/** 감사기록 전송 요청. 본문에 tenantId 나 host 가 있어도 읽지 않는다. 서버가 node_key 로 푼 값만 쓴다. */
public record AuditRequest(List<AuditEvent> events) {
}
