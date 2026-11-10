package com.edrdog.apiservice.audit.web;

import com.edrdog.apiservice.audit.AuditRecord;

/** GET /api/audit 한 줄. ts 는 epoch millis, actorType 과 result 는 소문자. */
public record AuditResponse(Long id, long ts, Long tenantId, String actorType, Long actorUserId, String actor,
                            String type, String target, String result, String detail, String ip) {

    static AuditResponse from(AuditRecord r) {
        return new AuditResponse(r.getId(), r.getTs().toEpochMilli(), r.getTenantId(),
                r.getActorType().name().toLowerCase(), r.getActorUserId(), r.getActor(), r.getEventType(),
                r.getTarget(), r.getResult().name().toLowerCase(), r.getDetail(), r.getIp());
    }
}
