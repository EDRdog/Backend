package com.edrdog.apiservice.audit;

import com.edrdog.apiservice.auth.Principal;

import java.time.Instant;

/**
 * 남길 감사사건 한 건. {@link AuditService#record(AuditEntry)} 의 입력이다.
 * 비밀값(패스워드, 토큰, 키)은 어느 칸에도 넣지 않는다.
 *
 * @param ts         사건 시각. null 이면 기록하는 시각
 * @param tenantId   사건이 속한 tenant. 모르면(없는 이메일로 로그인 시도 등) null 이고, 그 기록은 조회 API 에 나오지 않는다
 * @param actorUserId USER 일 때 유저 id(모르면 null)
 * @param actor      유저는 이메일, 에이전트는 host
 * @param target     사건 대상(host, 계정 등)
 * @param detail     짧은 설명
 * @param ip         접속 IP
 */
public record AuditEntry(Instant ts, Long tenantId, AuditActor actorType, Long actorUserId, String actor,
                         String type, String target, AuditResult result, String detail, String ip) {

    /** 로그인한 유저가 한 행동. */
    public static AuditEntry user(Principal p, String type, String target, AuditResult result,
                                  String detail, String ip) {
        return new AuditEntry(null, p.tenantId(), AuditActor.USER, p.userId(), p.email(),
                type, target, result, detail, ip);
    }

    /** 서버가 스스로 한 일(자체점검 등). */
    public static AuditEntry system(Long tenantId, String type, String target, AuditResult result, String detail) {
        return new AuditEntry(null, tenantId, AuditActor.SYSTEM, null, "system",
                type, target, result, detail, null);
    }

    /** 에이전트가 보고한 사건. ts 는 에이전트가 사건을 만든 시각. */
    public static AuditEntry agent(Instant ts, Long tenantId, String host, String type,
                                   AuditResult result, String detail) {
        return new AuditEntry(ts, tenantId, AuditActor.AGENT, null, host, type, null, result, detail, null);
    }
}
